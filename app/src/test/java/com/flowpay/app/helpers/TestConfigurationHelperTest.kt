// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.helpers

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Looper
import com.flowpay.app.R
import com.flowpay.app.constants.AppConstants
import com.flowpay.app.managers.CallType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The test-flow state machine in [TestConfigurationHelper] — its first tests
 * ever. Every transition writes its facts once into the single
 * [TestConfigurationHelper.state] flow; these tests drive the transitions the
 * activity triggers (dial paths, the 25-second timeout, confirmations,
 * dismissals, skip/continue) and assert the resulting TestState. All four
 * constructor seams are recording lambdas, so no Android UI or storage is
 * involved: the completion flag lands in [completionWrites], toasts in
 * [toasts], navigation and permission requests in counters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestConfigurationHelperTest {

    /** initialize() needs an Activity (PermissionManager requires one). */
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private val toasts = mutableListOf<String>()
    private val completionWrites = mutableListOf<Boolean>()
    private var navigationCount = 0
    private var permissionRequestCount = 0

    private lateinit var helper: TestConfigurationHelper

    private val state get() = helper.state.value

    @Before
    fun setUp() {
        // Phone permissions pre-granted so the dial paths reach the state
        // machine; the denied path denies them again in its own test.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        helper = TestConfigurationHelper(
            context = activity,
            showToast = { toasts += it },
            navigateToMain = { navigationCount++ },
            requestPhonePermissions = { permissionRequestCount++ },
            completionStore = { completed -> completionWrites += completed }
        )
        helper.initialize()
    }

    @After
    fun tearDown() {
        // Mirrors the activity's onDestroy contract: cancels any pending
        // timeout runnable (Robolectric's main looper never fires it).
        helper.cleanup()
    }

    @Test
    fun `initial state is idle with the initializing progress message`() {
        assertEquals(TestConfigurationHelper.TestState(), state)
        assertFalse(helper.canContinue())
        assertFalse(helper.allTestsCompleted())
    }

    @Test
    fun `ussd dial without phone permissions requests permissions and changes no state`() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )

        helper.initiateCall(CallType.USSD)

        assertEquals(1, permissionRequestCount)
        assertEquals(listOf(activity.getString(R.string.error_call_permission_required_ussd)), toasts)
        assertEquals(TestConfigurationHelper.TestState(), state)
    }

    @Test
    fun `starting the ussd test marks testing, shows the progress dialog and updates the message`() {
        helper.initiateCall(CallType.USSD)

        assertTrue(state.ussdTesting)
        assertTrue(state.showUssdDialog)
        assertEquals("Dialing *99#...", state.ussdProgressMessage)
        assertFalse(state.showUssdConfigurationOptions)
        assertFalse(state.ussdTestCompleted)
    }

    @Test
    fun `ussd timeout swaps the progress dialog for the configuration options`() {
        helper.initiateCall(CallType.USSD)

        shadowOf(Looper.getMainLooper()).runToEndOfTasks()

        assertFalse(state.showUssdDialog)
        assertTrue(state.showUssdConfigurationOptions)
        // Still in a test: only the confirmation dialog may end it.
        assertTrue(state.ussdTesting)
        assertFalse(state.ussdTestCompleted)
    }

    @Test
    fun `dismissing the ussd configuration options behaves like not-yet`() {
        helper.initiateCall(CallType.USSD)
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()

        helper.dismissUssdDialog()

        assertFalse(state.showUssdDialog)
        assertFalse(state.showUssdConfigurationOptions)
        assertFalse(state.ussdTesting)
        assertFalse(state.ussdTestCompleted)
        assertEquals(listOf(activity.getString(R.string.status_ussd_setup_incomplete)), toasts)
    }

    @Test
    fun `does-not-work dismissal records the ussd opt-out and waives continuation`() {
        helper.initiateCall(CallType.USSD)

        helper.dismissUssdDialog(fromDoesNotWork = true)

        assertFalse(state.ussdTesting)
        assertFalse(state.showUssdDialog)
        assertTrue(SetupHelper.hasUserReportedUssdNotWorking(activity))
        assertTrue(helper.canContinue())
        assertEquals(listOf(activity.getString(R.string.status_scan_to_pay_disabled)), toasts)
    }

    @Test
    fun `confirming ussd setup completes it, persists results and enables continue`() {
        helper.initiateCall(CallType.USSD)

        helper.handleUssdConfigurationConfirmation(true)

        assertTrue(state.ussdTestCompleted)
        assertFalse(state.ussdTesting)
        assertFalse(state.showUssdDialog)
        assertFalse(state.showUssdConfigurationOptions)
        assertTrue(state.showCallCompleteButton)
        assertTrue(helper.canContinue())
        // UPI123 is still missing, so "all tests passed" stays out of reach.
        assertFalse(helper.allTestsCompleted())
        assertEquals(listOf(activity.getString(R.string.status_ussd_setup_complete)), toasts)
        val persisted = helper.getTestResults()
        assertNotNull(persisted)
        assertTrue(persisted!!.ussdEnabled)
    }

    @Test
    fun `a second ussd dial after completion is ignored`() {
        helper.initiateCall(CallType.USSD)
        helper.handleUssdConfigurationConfirmation(true)

        helper.initiateCall(CallType.USSD)

        // The duplicate-dial guard kept the dialog down and testing off.
        assertFalse(state.showUssdDialog)
        assertFalse(state.ussdTesting)
        assertTrue(state.ussdTestCompleted)
        assertEquals(0, permissionRequestCount)
    }

    @Test
    fun `declining ussd setup leaves it incomplete`() {
        helper.initiateCall(CallType.USSD)

        helper.handleUssdConfigurationConfirmation(false)

        assertFalse(state.ussdTestCompleted)
        assertFalse(state.ussdTesting)
        assertFalse(state.showUssdDialog)
        assertFalse(state.showUssdConfigurationOptions)
        assertFalse(helper.canContinue())
        assertEquals(listOf(activity.getString(R.string.status_ussd_setup_incomplete)), toasts)
    }

    @Test
    fun `starting the upi123 test marks testing and shows its dialog`() {
        helper.initiateUpi123Test()

        assertTrue(state.upi123Testing)
        assertTrue(state.showUpi123Dialog)
        assertFalse(state.showUpi123ConfigurationOptions)
        assertFalse(state.upi123TestCompleted)
    }

    @Test
    fun `confirming upi123 setup completes it and persists results`() {
        helper.initiateUpi123Test()

        helper.handleUpi123ConfigurationConfirmation(true)

        assertTrue(state.upi123TestCompleted)
        assertFalse(state.upi123Testing)
        assertFalse(state.showUpi123Dialog)
        assertFalse(state.showUpi123ConfigurationOptions)
        assertTrue(state.showCallCompleteButton)
        // A completed UPI123 test alone is enough to continue...
        assertTrue(helper.canContinue())
        // ...but not enough for "all tests passed" (USSD still missing).
        assertFalse(helper.allTestsCompleted())
        assertEquals(listOf(activity.getString(R.string.status_upi123_setup_complete)), toasts)
        val persisted = helper.getTestResults()
        assertNotNull(persisted)
        assertTrue(persisted!!.upi123Enabled)
    }

    @Test
    fun `allTestsCompleted only once both rails are confirmed`() {
        helper.initiateCall(CallType.USSD)
        helper.handleUssdConfigurationConfirmation(true)
        helper.initiateUpi123Test()
        helper.handleUpi123ConfigurationConfirmation(true)

        assertTrue(helper.allTestsCompleted())
    }

    @Test
    fun `skipTests persists the completion flag, results and navigates`() {
        helper.skipTests()

        assertEquals(listOf(true), completionWrites)
        assertEquals(1, navigationCount)
        // Results are still persisted on skip — completion flag apart, the
        // user's partial progress survives the skip.
        val persisted = helper.getTestResults()
        assertNotNull(persisted)
        assertFalse(persisted!!.ussdEnabled)
        assertFalse(persisted.upi123Enabled)
    }

    @Test
    fun `continueToMain persists the completion flag and navigates too`() {
        helper.continueToMain()

        assertEquals(listOf(true), completionWrites)
        assertEquals(1, navigationCount)
    }

    @Test
    fun `jio primary sim waives the ussd requirement for continuation`() {
        // SetupHelper treats "selected_primary_sim == jio" as "this SIM has
        // no *99# USSD", which waives the USSD half of the gate.
        activity.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("selected_primary_sim", "jio")
            .commit()

        assertTrue(helper.canContinue())
        assertFalse(helper.allTestsCompleted())
    }

    @Test
    fun `phone permission results only toast`() {
        helper.onPhonePermissionsResult(true)
        helper.onPhonePermissionsResult(false)

        assertEquals(
            listOf(
                activity.getString(R.string.status_permissions_granted_ussd),
                activity.getString(R.string.error_permissions_denied)
            ),
            toasts
        )
    }
}
