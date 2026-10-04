// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.helpers

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.flowpay.app.R
import com.flowpay.app.constants.AppConstants
import com.flowpay.app.data.TestResults
import com.flowpay.app.data.TestResultsManager
import com.flowpay.app.managers.CallManager
import com.flowpay.app.managers.CallType
import com.flowpay.app.managers.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Helper class containing all business logic for TestConfigurationActivity.
 * Owns the whole test-flow state as a single [state] StateFlow: every
 * transition writes its facts there exactly once and the activity renders
 * by collecting it. What the helper cannot own itself — showing toasts,
 * navigating, launching the permission request, and persisting the
 * completion flag — is injected as constructor seams.
 */
class TestConfigurationHelper(
    private val context: Context,
    private val showToast: (String) -> Unit,
    private val navigateToMain: () -> Unit,
    private val requestPhonePermissions: () -> Unit,
    private val completionStore: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "TestConfigurationHelper"

        /** The window a *99# session gets before the confirm dialog appears. */
        private const val USSD_CONFIGURATION_TIMEOUT_MS = 25000L

        /** Grace period after the UPI 123 IVR call ends before confirming. */
        private const val UPI123_CONFIGURATION_DELAY_MS = 2000L

        /**
         * Production completion store: persists the test-configuration-
         * completed flag in the shared FlowpayPrefs. Tests inject a plain
         * lambda instead so the flag is assertable without Android storage.
         */
        fun persistentCompletionStore(context: Context): (Boolean) -> Unit =
            { completed ->
                context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(AppConstants.KEY_TEST_COMPLETED, completed)
                    .apply()
            }
    }

    /**
     * Immutable snapshot of every test-flow fact the activity renders.
     * Field names match the former per-fact callback updates.
     */
    data class TestState(
        val ussdTesting: Boolean = false,
        val showUssdDialog: Boolean = false,
        val ussdTestCompleted: Boolean = false,
        val upi123Testing: Boolean = false,
        val upi123TestCompleted: Boolean = false,
        val voiceTesting: Boolean = false,
        val showVoiceDialog: Boolean = false,
        val voiceTestCompleted: Boolean = false,
        val showCallCompleteButton: Boolean = false,
        val ussdProgressMessage: String = "Initializing...",
        val showUssdConfigurationOptions: Boolean = false,
        val showUpi123Dialog: Boolean = false,
        val showUpi123ConfigurationOptions: Boolean = false
    )

    // Managers
    private lateinit var callManager: CallManager
    private lateinit var testResultsManager: TestResultsManager
    private lateinit var permissionManager: PermissionManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ussdTimeoutRunnable: Runnable? = null
    private var upi123ConfigDelayRunnable: Runnable? = null

    private val _state = MutableStateFlow(TestState())

    /** The single source of truth for this screen; collect it, never poll. */
    val state: StateFlow<TestState> = _state

    /**
     * Initialize all managers
     */
    fun initialize() {
        callManager = CallManager(context)
        testResultsManager = TestResultsManager(context)
        permissionManager = PermissionManager(context as Activity)
    }

    /**
     * Releases everything with a lifecycle: pending Handler runnables and
     * the CallManager's PhoneStateListener. Must be called from the host
     * activity's onDestroy(), otherwise a listener registered for an
     * in-flight test call outlives the screen.
     */
    fun cleanup() {
        cancelUssdTimeout()
        cancelUpi123ConfigDelay()
        if (::callManager.isInitialized) {
            callManager.cleanup()
        }
    }

    /**
     * Handle the phone-permission launcher result. There is no auto-retry:
     * the user re-taps the test action once permissions are granted.
     */
    fun onPhonePermissionsResult(granted: Boolean) {
        if (granted) {
            Log.d(TAG, "All permissions granted")
            showToast(context.getString(R.string.status_permissions_granted_ussd))
        } else {
            Log.w(TAG, "Some permissions denied")
            showToast(context.getString(R.string.error_permissions_denied))
        }
    }

    /**
     * Initiate a test call
     */
    fun initiateCall(callType: CallType) {
        // Guard: prevent duplicate dials
        if (callType == CallType.USSD &&
            (_state.value.ussdTesting || _state.value.ussdTestCompleted)
        ) {
            Log.d(TAG, "USSD already in progress or completed, ignoring duplicate dial")
            return
        }

        // Check phone permissions before initiating call - request if missing
        if (!permissionManager.hasPhonePermissions()) {
            Log.e(TAG, "Phone permissions not granted")
            showToast(context.getString(R.string.error_call_permission_required_ussd))
            requestPhonePermissions()
            return
        }

        val number = when (callType) {
            CallType.USSD -> "*99#"
            CallType.VOICE -> "1234567890"
            CallType.UPI123 -> "1234567890" // This should not be used anymore
            CallType.MANUAL_TRANSFER -> "1234567890"
        }

        // Update UI state
        when (callType) {
            CallType.USSD -> {
                Log.d(TAG, "Initiating USSD setup for *99# - will show dialog after 25 seconds")
                SetupHelper.setUserReportedUssdNotWorking(context, false)
                _state.update {
                    it.copy(
                        ussdTesting = true,
                        showUssdDialog = true,
                        ussdProgressMessage = "Dialing *99#..."
                    )
                }
                Log.d(
                    TAG,
                    "USSD state set - ussdTesting: ${_state.value.ussdTesting}, " +
                        "showUssdDialog: ${_state.value.showUssdDialog}"
                )
            }
            CallType.VOICE, CallType.MANUAL_TRANSFER -> {
                _state.update { it.copy(voiceTesting = true, showVoiceDialog = true) }
            }
            CallType.UPI123 -> {
                // UPI123 should use initiateUpi123Test() method instead
                Log.w(TAG, "UPI123 should use initiateUpi123Test() method instead of initiateCall()")
                return
            }
        }

        // For USSD, start the 25-second timeout
        if (callType == CallType.USSD) {
            startUssdTimeout() // Start 25-second timeout
        }

        callManager.initiateCall(
            context = context,
            phoneNumber = number,
            callType = callType,
            onCallEnded = { type ->
                when (type) {
                    CallType.VOICE, CallType.MANUAL_TRANSFER -> {
                        _state.update {
                            it.copy(
                                voiceTesting = false,
                                showVoiceDialog = false,
                                voiceTestCompleted = true,
                                showCallCompleteButton = true
                            )
                        }
                    }
                    CallType.UPI123 -> {
                        // UPI123 is handled by initiateUpi123Test() method
                        Log.w(TAG, "UPI123 call ended in main initiateCall - this should not happen")
                    }
                    CallType.USSD -> {
                        // USSD call ended - just update button state
                        _state.update { it.copy(showCallCompleteButton = true) }
                    }
                }
            }
        )
    }

    // Simplified dialog logic - using simple 25-second timeout for USSD and immediate dialog for UPI123

    private fun cancelUssdTimeout() {
        ussdTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        ussdTimeoutRunnable = null
    }

    private fun cancelUpi123ConfigDelay() {
        upi123ConfigDelayRunnable?.let { mainHandler.removeCallbacks(it) }
        upi123ConfigDelayRunnable = null
    }

    /**
     * Start 25-second timeout for USSD setup
     */
    private fun startUssdTimeout() {
        Log.d(TAG, "Starting 25-second USSD timeout timer")
        cancelUssdTimeout()
        ussdTimeoutRunnable = Runnable {
            ussdTimeoutRunnable = null
            Log.d(TAG, "USSD 25-second timeout reached - showing configuration dialog")
            val snapshot = _state.value
            if (snapshot.ussdTesting && !snapshot.ussdTestCompleted) {
                _state.update {
                    it.copy(showUssdDialog = false, showUssdConfigurationOptions = true)
                }
                Log.d(
                    TAG,
                    "Configuration dialog shown after 25 seconds - " +
                        "showUssdDialog: ${_state.value.showUssdDialog}, " +
                        "showUssdConfigurationOptions: ${_state.value.showUssdConfigurationOptions}"
                )
            } else {
                Log.d(
                    TAG,
                    "USSD timeout reached but conditions not met - " +
                        "ussdTesting: ${snapshot.ussdTesting}, " +
                        "ussdTestCompleted: ${snapshot.ussdTestCompleted}"
                )
            }
        }
        mainHandler.postDelayed(ussdTimeoutRunnable!!, USSD_CONFIGURATION_TIMEOUT_MS)
    }

    /**
     * Close USSD dialog from the X button, or dismiss progress early ("does not work" uses [fromDoesNotWork] toast).
     * If the confirmation step is showing, behaves like "Not Yet".
     */
    fun dismissUssdDialog(fromDoesNotWork: Boolean = false) {
        if (_state.value.showUssdConfigurationOptions) {
            handleUssdConfigurationConfirmation(false)
            return
        }
        cancelUssdTimeout()
        _state.update {
            it.copy(
                ussdTesting = false,
                showUssdDialog = false,
                showUssdConfigurationOptions = false
            )
        }
        if (fromDoesNotWork) {
            SetupHelper.setUserReportedUssdNotWorking(context, true)
            showToast(context.getString(R.string.status_scan_to_pay_disabled))
        }
    }

    /**
     * Close UPI123 dialog from the X button. If confirming setup, behaves like "Not Yet".
     */
    fun dismissUpi123Dialog() {
        if (_state.value.showUpi123ConfigurationOptions) {
            handleUpi123ConfigurationConfirmation(false)
            return
        }
        cancelUpi123ConfigDelay()
        _state.update {
            it.copy(
                upi123Testing = false,
                showUpi123Dialog = false,
                showUpi123ConfigurationOptions = false
            )
        }
    }

    /**
     * Handle USSD configuration confirmation
     */
    fun handleUssdConfigurationConfirmation(configured: Boolean) {
        Log.d(TAG, "USSD configuration confirmation: $configured")
        cancelUssdTimeout()

        if (configured) {
            SetupHelper.setUserReportedUssdNotWorking(context, false)
            _state.update {
                it.copy(
                    showUssdConfigurationOptions = false,
                    showUssdDialog = false,
                    ussdTesting = false,
                    ussdTestCompleted = true,
                    showCallCompleteButton = true
                )
            }
            saveTestResults()
            showToast(context.getString(R.string.status_ussd_setup_complete))
        } else {
            _state.update {
                it.copy(
                    showUssdConfigurationOptions = false,
                    showUssdDialog = false,
                    ussdTesting = false
                )
            }
            showToast(context.getString(R.string.status_ussd_setup_incomplete))
        }
    }

    /**
     * Handle UPI123 configuration confirmation
     */
    fun handleUpi123ConfigurationConfirmation(configured: Boolean) {
        Log.d(TAG, "UPI123 configuration confirmation: $configured")
        cancelUpi123ConfigDelay()

        if (configured) {
            _state.update {
                it.copy(
                    showUpi123ConfigurationOptions = false,
                    showUpi123Dialog = false,
                    upi123Testing = false,
                    upi123TestCompleted = true,
                    showCallCompleteButton = true
                )
            }
            saveTestResults()
            showToast(context.getString(R.string.status_upi123_setup_complete))
        } else {
            _state.update {
                it.copy(
                    showUpi123ConfigurationOptions = false,
                    showUpi123Dialog = false,
                    upi123Testing = false
                )
            }
            showToast(context.getString(R.string.status_upi123_setup_incomplete))
        }
    }

    /**
     * Initiate UPI123 test - simplified flow - show dialog when call ends
     */
    fun initiateUpi123Test() {
        val snapshot = _state.value
        Log.d(
            TAG,
            "initiateUpi123Test called - upi123TestCompleted: ${snapshot.upi123TestCompleted}, " +
                "upi123Testing: ${snapshot.upi123Testing}"
        )
        if (snapshot.upi123TestCompleted || snapshot.upi123Testing) {
            Log.d(TAG, "UPI123 test not started - already completed or testing")
            return
        }
        Log.d(TAG, "Starting UPI123 test")
        _state.update { it.copy(upi123Testing = true, showUpi123Dialog = true) }

        // Initiate UPI123 call - simple flow
        callManager.initiateCall(
            context = context,
            phoneNumber = AppConstants.DEFAULT_UPI_SERVICE_NUMBER,
            callType = CallType.UPI123,
            onCallEnded = { type ->
                if (type == CallType.UPI123 && _state.value.upi123Testing) {
                    Log.d(TAG, "UPI123 call ended - showing configuration dialog after 2 second delay")

                    // Stop testing state
                    _state.update { it.copy(upi123Testing = false) }

                    cancelUpi123ConfigDelay()
                    upi123ConfigDelayRunnable = Runnable {
                        upi123ConfigDelayRunnable = null
                        if (!_state.value.upi123TestCompleted) {
                            _state.update {
                                it.copy(
                                    showUpi123Dialog = false,
                                    showUpi123ConfigurationOptions = true
                                )
                            }
                            Log.d(TAG, "UPI123 configuration dialog shown after 2 second delay")
                        }
                    }
                    mainHandler.postDelayed(upi123ConfigDelayRunnable!!, UPI123_CONFIGURATION_DELAY_MS)
                }
            }
        )
    }

    /**
     * Save the current test results snapshot
     */
    private fun saveTestResults() {
        val snapshot = _state.value
        val results = TestResults(
            ussdEnabled = snapshot.ussdTestCompleted,
            upi123Enabled = snapshot.upi123TestCompleted
        )
        testResultsManager.saveTestResults(results)
    }

    /**
     * Get existing test results
     */
    fun getTestResults(): TestResults? {
        return testResultsManager.getTestResults()
    }

    /**
     * Skip tests and continue to main
     */
    fun skipTests() {
        finishTestConfiguration()
    }

    /**
     * Continue to main after completing tests
     */
    fun continueToMain() {
        finishTestConfiguration()
    }

    /**
     * Persist the current results, record the completion flag and hand off to
     * the host's navigation. [skipTests] and [continueToMain] were
     * byte-identical twins, so both delegate here.
     */
    private fun finishTestConfiguration() {
        // Save current test results before continuing
        saveTestResults()

        // Mark test configuration as completed even when skipped
        completionStore(true)

        navigateToMain()
    }

    /**
     * Check if tests can continue
     */
    fun canContinue(): Boolean {
        val ussdWaived = !SetupHelper.isPrimarySimUssdCapable(context) ||
            SetupHelper.hasUserReportedUssdNotWorking(context)
        return (_state.value.ussdTestCompleted || ussdWaived) || _state.value.upi123TestCompleted
    }

    /**
     * Check if all tests are completed
     */
    fun allTestsCompleted(): Boolean {
        val ussdWaived = !SetupHelper.isPrimarySimUssdCapable(context) ||
            SetupHelper.hasUserReportedUssdNotWorking(context)
        return (_state.value.ussdTestCompleted || ussdWaived) && _state.value.upi123TestCompleted
    }
}
