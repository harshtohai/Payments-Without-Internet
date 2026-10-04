// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.helpers

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.flowpay.app.payment.PaymentSessionManager
import com.flowpay.app.payment.sms.SimpleTransaction
import com.flowpay.app.payment.sms.SmsTransactionParser

/**
 * One bank SMS that [TransactionDetector.claim] accepted, bundling what
 * the ingestion pipeline needs: the parsed transaction, plus the session
 * txn id recorded at [TransactionDetector.startOperation]. The id is
 * captured inside [claim] BEFORE a consuming match clears the operation
 * window, so the pipeline can still reattach an orphaned confirmation to
 * its PENDING row even once that window (which also holds the id) is gone.
 */
data class ClaimedSms(
    val transaction: SimpleTransaction,
    /** Session txn id of the payment this window was armed for, or null. */
    val sessionTxnId: String?
)

/**
 * Stateful orchestration around [SmsTransactionParser]: the
 * SharedPreferences-backed operation window (only SMS arriving while a
 * payment is in flight are eligible) and dedup so one SMS is never
 * processed twice.
 *
 * [claim] is the single, atomic entry point of SMS ingestion: the window
 * check, the dedup claim, the parse, and the window consumption all run
 * inside one `@Synchronized` call. Callers can no longer observe — or get
 * wrong — an in-between state: the old protocol spanned callers (check
 * window, claim the body, read the session txn id, then parse and maybe
 * consume) and its ordering constraint was enforced only by KDoc.
 *
 * All actual bank-SMS matching logic lives in [SmsTransactionParser], which
 * is pure and directly unit-testable without a Context.
 */
class TransactionDetector private constructor(context: Context) {

    companion object {
        private const val TAG = "TransactionDetector"
        private const val PREF_NAME = "payment_operation"
        private const val KEY_ACTIVE = "is_active"
        private const val KEY_START_TIME = "start_time"
        private const val KEY_OPERATION_TYPE = "operation_type"
        private const val KEY_EXPECTED_AMOUNT = "expected_amount"
        private const val KEY_PHONE_NUMBER = "phone_number"
        private const val KEY_SESSION_TXN_ID = "session_txn_id"

        /**
         * How long after a payment starts an incoming SMS is still eligible.
         * Derived from the session verification deadline (plus a grace margin
         * for a confirmation racing the deadline) — it must never be shorter,
         * or a slow-but-genuine bank SMS would be dropped here and a payment
         * that did go through would never be recorded.
         */
        internal const val OPERATION_WINDOW_MILLIS =
            PaymentSessionManager.DEFAULT_VERIFICATION_DEADLINE_MS + 30_000L

        private const val SMS_CLAIM_WINDOW_MILLIS = 60 * 1000L // dedupe window across pipelines

        @Volatile
        private var instance: TransactionDetector? = null

        fun getInstance(context: Context): TransactionDetector {
            return instance ?: synchronized(this) {
                instance ?: TransactionDetector(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // Recently claimed SMS fingerprints (body hash -> claim time). claim()
    // records a body here before parsing it, so a message is parsed and
    // persisted exactly once even if the platform delivers the same
    // SMS_RECEIVED broadcast more than once.
    private val recentSmsClaims = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 64
    }

    /**
     * The one way an incoming SMS can influence a payment outcome: decides,
     * atomically under one lock, whether [body] from [sender] is the
     * confirmation of the in-flight payment. In order:
     *
     * 1. the operation window must be open — a payment in flight, inside
     *    [OPERATION_WINDOW_MILLIS], with an operation type — else null;
     * 2. this body must not already have been claimed within the 60-second
     *    dedup window — else null;
     * 3. the message must parse as a bank transaction
     *    ([SmsTransactionParser.parse]) — else null;
     * 4. the session txn id is read from the window BEFORE step 5 can
     *    clear it, and is returned alongside the parsed transaction so the
     *    pipeline can reattach an orphaned confirmation;
     * 5. a consuming match closes the operation window — EXCEPT an
     *    incoming CREDIT: a stray credit must not eat the window the real
     *    outgoing-debit confirmation may still need.
     *
     * `@Synchronized` so that when two ingestion entry points race, only
     * the first one inside consumes the active operation — the second
     * finds the window closed (or the body already claimed) and gets
     * null.
     */
    @Synchronized
    fun claim(sender: String, body: String): ClaimedSms? {
        Log.d(TAG, "Claiming SMS")

        if (!shouldProcessSMS() || !tryClaimSms(body)) {
            // Whichever guard rejected, and why, is logged inside it.
            return null
        }

        // Captured BEFORE the stopOperation() below can clear the window:
        // this is what lets a confirmation whose session died mid-payment
        // still be reattached to its PENDING row instead of inserting a
        // duplicate.
        val sessionTxnId = prefs.getString(KEY_SESSION_TXN_ID, null)
        val expectedAmount = prefs.getString(KEY_EXPECTED_AMOUNT, null)
        val transaction = SmsTransactionParser.parse(sender, body, expectedAmount)

        if (transaction == null) {
            Log.d(TAG, "SMS did not match a bank transaction")
        } else {
            Log.d(TAG, "Matched ${transaction.bankName} transaction: ${transaction.status}")
            // Mark the operation complete — unless this was an incoming
            // CREDIT. A stray credit must not consume the operation window
            // the real outgoing-debit confirmation may still need.
            if (transaction.transactionType != "CREDIT") {
                stopOperation()
            }
        }

        return transaction?.let { ClaimedSms(it, sessionTxnId) }
    }

    /**
     * Start monitoring for a payment operation. [sessionTxnId] is the
     * PaymentSessionManager transaction id (the PENDING row's key), persisted
     * here so a confirming SMS that arrives AFTER a process death — when the
     * in-memory session is gone — can still be matched back to its row
     * instead of inserting a duplicate.
     */
    fun startOperation(
        operationType: String,
        expectedAmount: String? = null,
        phoneNumber: String? = null,
        sessionTxnId: String? = null
    ) {
        Log.d(TAG, "Starting operation: $operationType")

        prefs.edit().apply {
            putBoolean(KEY_ACTIVE, true)
            putLong(KEY_START_TIME, System.currentTimeMillis())
            putString(KEY_OPERATION_TYPE, operationType)
            expectedAmount?.let { putString(KEY_EXPECTED_AMOUNT, it) }
            phoneNumber?.let { putString(KEY_PHONE_NUMBER, it) }
            sessionTxnId?.let { putString(KEY_SESSION_TXN_ID, it) }
            apply()
        }
    }

    // Stop monitoring
    fun stopOperation() {
        Log.d(TAG, "Stopping operation")

        prefs.edit().apply {
            clear()
            apply()
        }
    }

    fun getOperationType(): String? = prefs.getString(KEY_OPERATION_TYPE, null)
    fun getPhoneNumber(): String? = prefs.getString(KEY_PHONE_NUMBER, null)

    /**
     * True when the operation window still authorizes claiming SMS: a
     * payment is in flight, the window has not expired, and the window
     * carries an operation type. A window found expired or typeless is
     * stale and gets cleared here rather than left half-open.
     */
    private fun shouldProcessSMS(): Boolean {
        val isActive = prefs.getBoolean(KEY_ACTIVE, false)
        val elapsed = System.currentTimeMillis() - prefs.getLong(KEY_START_TIME, 0)
        val expired = elapsed > OPERATION_WINDOW_MILLIS
        val hasOperationType = !prefs.getString(KEY_OPERATION_TYPE, null).isNullOrEmpty()

        // An inactive window is already closed — nothing to clean up. An
        // active one that expired (or lost its operation type) is stale
        // and must not half-openly linger.
        if (isActive && (expired || !hasOperationType)) {
            if (expired) {
                Log.d(TAG, "Operation timed out after ${elapsed / 1000} seconds")
            }
            stopOperation()
            return false
        }

        if (!isActive) {
            Log.d(TAG, "No active payment operation")
        }
        return isActive && hasOperationType
    }

    /**
     * Records [body] as claimed for the dedup window; false when the same
     * normalized body was claimed recently. Key is body-only: the same SMS
     * can reach claim() under different sender strings (DLT header vs
     * messaging-app display name — or a hand-typed one in a debug
     * injection), so a sender-qualified key would never dedupe across
     * them.
     */
    @Synchronized
    private fun tryClaimSms(body: String): Boolean {
        val key = SmsTransactionParser.claimKey(body).hashCode().toString()
        val now = System.currentTimeMillis()
        val lastClaim = recentSmsClaims[key]
        if (lastClaim != null && now - lastClaim < SMS_CLAIM_WINDOW_MILLIS) {
            Log.d(TAG, "SMS already claimed")
            return false
        }
        recentSmsClaims[key] = now
        return true
    }
}
