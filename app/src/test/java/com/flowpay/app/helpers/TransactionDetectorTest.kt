// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.helpers

import android.content.Context
import com.flowpay.app.data.TransactionStatus
import com.flowpay.app.payment.PaymentSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The stateful half of SMS confirmation: the SharedPreferences-backed
 * operation window and the cross-pipeline dedup, exercised end-to-end
 * through the one atomic [TransactionDetector.claim] entry point every
 * ingestion pipeline calls. Every case here guards a money outcome — a
 * window that closes too early drops a genuine bank confirmation (false
 * UNVERIFIED), a dedup that never collides records the same payment
 * twice, and a stray credit that consumes the window leaves the real
 * debit confirmation unmatchable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransactionDetectorTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var detector: TransactionDetector

    // Same file TransactionDetector writes, so tests can tamper with the
    // persisted window (e.g. back-date start_time) like a reboot/GC would.
    private val prefs
        get() = context.getSharedPreferences("payment_operation", Context.MODE_PRIVATE)

    // Confirmation bodies for the Rs.500 payment every test arms. Distinct
    // ref numbers across the fixtures keep one claim from tripping the
    // dedup of a neighbouring assertion in the same test.
    private val debitBody = "Rs 500 sent to JOHN via UPI Ref 123456789012 -HDFC Bank"
    private val otherDebitBody = "Rs 500 sent to JOHN via UPI Ref 998877665544 -HDFC Bank"
    private val creditBody = "Rs 500 credited from JOHN via UPI ref 123456789012 -HDFC Bank"
    private val otherCreditBody = "Rs 500 credited from JOHN via UPI ref 998877665544 -HDFC Bank"

    @Before
    fun freshDetector() {
        // The @Volatile singleton outlives a single test: statics survive in
        // Robolectric's shared classloader while each test method gets a
        // fresh Application (and prefs directory). Left alone, a detector
        // cached by an earlier test would keep using the PREVIOUS test's
        // prefs file and leak in-memory SMS claims into this one — so rebind
        // the singleton to THIS test's application before every test.
        TransactionDetector::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        detector = TransactionDetector.getInstance(context)
        detector.stopOperation()
    }

    @Test
    fun `claim rejects a bank SMS when no payment operation is active`() {
        // Outside a payment, bank SMS must be ignored entirely: processing
        // an unrelated debit here would fabricate a Flowpay transaction.
        // The body is one that parses when a window IS armed (see the
        // consuming-match tests below), so this null is the window's.
        assertNull(detector.claim("VK-HDFCBK", debitBody))
    }

    @Test
    fun `startOperation opens the window and stopOperation closes it`() {
        detector.startOperation("SEND_MONEY", expectedAmount = "500")
        assertEquals("SEND_MONEY", detector.getOperationType())
        assertNotNull(
            "a window armed by startOperation must accept its confirmation",
            detector.claim("VK-HDFCBK", debitBody)
        )

        // The debit claim above consumed the window (that is its job), so
        // re-arm to show stopOperation closing a live one.
        detector.startOperation("SEND_MONEY", expectedAmount = "500")
        detector.stopOperation()
        assertNull("closed window must reject SMS", detector.claim("VK-HDFCBK", otherDebitBody))
        assertNull(detector.getOperationType())
    }

    @Test
    fun `expired window rejects the SMS and stops the operation`() {
        detector.startOperation("SEND_MONEY", expectedAmount = "500")

        // Back-date the persisted start beyond the window, as if the SMS
        // arrived long after the operation began.
        prefs.edit()
            .putLong("start_time", System.currentTimeMillis() - TransactionDetector.OPERATION_WINDOW_MILLIS - 1)
            .commit()

        assertNull("SMS after the window must be rejected", detector.claim("VK-HDFCBK", debitBody))
        // The expired operation must also be cleaned up, not left half-open.
        assertFalse("expiry must clear the persisted operation", prefs.getBoolean("is_active", false))
        assertNull(detector.getOperationType())
    }

    @Test
    fun `operation window covers the full session verification deadline`() {
        // Regression guard: the window was once 5 minutes while the session
        // waited 10 — a slow-but-genuine bank SMS was dropped HERE while the
        // session still expected it, producing a false UNVERIFIED for money
        // that actually moved. The window must never undercut the deadline.
        assertTrue(
            "operation window must not be shorter than the verification deadline",
            TransactionDetector.OPERATION_WINDOW_MILLIS >= PaymentSessionManager.DEFAULT_VERIFICATION_DEADLINE_MS
        )
    }

    @Test
    fun `bank SMS arriving six minutes after start is still eligible`() {
        // Real banks routinely confirm minutes late; six minutes is inside
        // the 10.5-minute window (and would have been dropped by the old
        // 5-minute one — this is the concrete slow-SMS regression case).
        detector.startOperation("SEND_MONEY", expectedAmount = "500")
        prefs.edit()
            .putLong("start_time", System.currentTimeMillis() - 6 * 60 * 1000L)
            .commit()

        val claimed = detector.claim("VK-HDFCBK", debitBody)
        assertNotNull("a slow-but-genuine confirmation must still land", claimed)
    }

    @Test
    fun `window missing an operation type is rejected and cleaned up`() {
        // A half-written window (process death mid-startOperation, or
        // tampered prefs) must not let SMS through on is_active alone.
        detector.startOperation("SEND_MONEY", expectedAmount = "500")
        prefs.edit()
            .remove("operation_type")
            .commit()

        assertNull(detector.claim("VK-HDFCBK", debitBody))
        assertFalse("a typeless window must be cleared", prefs.getBoolean("is_active", false))
    }

    @Test
    fun `claim returns the session txn id recorded at startOperation`() {
        // The persisted session id is what lets a confirmation that arrives
        // AFTER process death reattach to its PENDING row instead of
        // inserting a duplicate transaction. It must ride along with the
        // claim even though the same call clears the window.
        detector.startOperation("SEND_MONEY", expectedAmount = "500", sessionTxnId = "abc")

        val claimed = detector.claim("VK-HDFCBK", debitBody)
        assertNotNull(claimed)
        assertEquals("abc", claimed!!.sessionTxnId)

        // The consumed window must not leak the old id into the next
        // operation armed without one.
        detector.startOperation("SEND_MONEY", expectedAmount = "500")
        val fresh = detector.claim("VK-HDFCBK", otherDebitBody)
        assertNotNull(fresh)
        assertNull("cleared window must not leak the old session id", fresh!!.sessionTxnId)
    }

    @Test
    fun `same SMS body is claimed exactly once`() {
        // The claim key is body-only, not sender-qualified, so a redelivered
        // SMS_RECEIVED broadcast (or a repeat debug injection) for the exact
        // same message can never be claimed twice. A sender-qualified key
        // would let cosmetic sender differences slip past the dedup and
        // silently double-record a payment — this is the regression test.
        //
        // Credits are the probe: a credit claims successfully but leaves
        // the window open, so a second null can only come from the dedup —
        // not from a consumed window.
        detector.startOperation("SEND_MONEY", expectedAmount = "500")

        assertNotNull("first claim on this body succeeds", detector.claim("VM-HDFCBK", creditBody))
        assertNull("a repeat claim on the same body must lose", detector.claim("VM-HDFCBK", creditBody))
        assertNull(
            "whitespace differences in a redelivery must still dedupe",
            detector.claim("VM-HDFCBK", "  " + creditBody.replace(" ", "  "))
        )
        assertNotNull(
            "a different SMS must not be blocked by earlier claims",
            detector.claim("VM-HDFCBK", otherCreditBody)
        )
    }

    @Test
    fun `debit confirmation consumes the operation window`() {
        detector.startOperation("SEND_MONEY", expectedAmount = "500")

        val claimed = detector.claim("VK-HDFCBK", debitBody)

        assertNotNull("matching debit confirmation must claim", claimed)
        assertEquals("DEBIT", claimed!!.transaction.transactionType)
        assertEquals(TransactionStatus.SUCCESS, claimed.transaction.status)

        // The window is one-shot for debits: a second bank SMS must not be
        // able to confirm the same payment twice.
        assertNull("consumed window must not claim again", detector.claim("VK-HDFCBK", otherDebitBody))
        assertNull(detector.getOperationType())
    }

    @Test
    fun `credit SMS does not consume the window awaited by a debit`() {
        // Money-safety: while we wait for OUR outgoing debit to confirm, an
        // unrelated incoming credit (someone paying the user) must not eat
        // the one-shot window — otherwise the real confirmation arriving a
        // moment later is dropped and the payment is left unverified.
        detector.startOperation("SEND_MONEY", expectedAmount = "500")

        val credit = detector.claim("VM-HDFCBK", creditBody)
        assertNotNull(credit)
        assertEquals("CREDIT", credit!!.transaction.transactionType)

        val debit = detector.claim("VK-HDFCBK", otherDebitBody)
        assertNotNull("the awaited debit must still be claimable", debit)
        assertEquals("DEBIT", debit!!.transaction.transactionType)

        assertNull("the debit, not the credit, consumes the window", detector.getOperationType())
    }

    @Test
    fun `an unrelated debit alert leaves the window open for the real confirmation`() {
        // The interference scenario end-to-end: while a Rs.500 payment is
        // pending, an unrelated Rs.200 card decline from the same bank arrives.
        // It is NOT this payment's confirmation, so it must be dropped without
        // consuming the one-shot window — otherwise the genuine Rs.500
        // confirmation arriving moments later is lost and the payment is
        // mis-recorded (as FAILED-with-retry, the money-loss bug).
        detector.startOperation("SEND_MONEY", expectedAmount = "500")

        val interfering = detector.claim(
            "VK-HDFCBK",
            "Your HDFC Bank card txn of Rs.200.00 at AMAZON was declined due to insufficient balance. Ref 700000000001"
        )
        assertNull("a mismatched-amount alert is not our confirmation", interfering)

        val real = detector.claim("VK-HDFCBK", debitBody)
        assertNotNull("the genuine confirmation must still land", real)
        assertEquals(TransactionStatus.SUCCESS, real!!.transaction.status)

        assertNull("the real confirmation consumes the window", detector.getOperationType())
    }

    @Test
    fun `claim returns null for a body the parser does not match`() {
        // The window is armed; only the parser can reject this message (a
        // bank sender, but no transaction indicators and no amount).
        detector.startOperation("SEND_MONEY", expectedAmount = "500")

        assertNull(
            "a non-transaction bank message must never be claimed",
            detector.claim("VK-HDFCBK", "Dear customer, your KYC update is due")
        )

        // A parse failure must not consume the window: the real
        // confirmation arriving next still claims.
        assertNotNull(
            "the window must survive a non-matching message",
            detector.claim("VK-HDFCBK", debitBody)
        )
    }
}
