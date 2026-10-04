// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.services

import com.flowpay.app.R
import com.flowpay.app.managers.TerminalDialog
import com.flowpay.app.states.PaymentState
import com.flowpay.app.states.TimeoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the overlay presenter — the money-path mapping from payment-session
 * state to what the overlay supervising a live payment shows. The mapping
 * lived untested inside CallOverlayService until it was extracted; these
 * are its first tests.
 *
 * The deliberate silences matter most: Success and NeedsReview finish with
 * no dialog (PaymentResultActivity owns those surfaces), Timeout shows
 * nothing at all (the row is discarded), WaitingForVerification is the
 * only state that fills the bar, and the watchdog's never-started
 * cancellation must read as a generic cancel — not as the user's own
 * terminate-button press.
 */
class OverlayPresenterTest {

    private fun cancelled(reason: String) = PaymentState.Cancelled(
        phoneNumber = "9123456780",
        amount = "500",
        transactionId = "t1",
        reason = reason
    )

    @Test
    fun `idle and initiating sessions change nothing`() {
        assertEquals(OverlayUi(), OverlayPresenter.present(PaymentState.Idle))
        assertEquals(
            OverlayUi(),
            OverlayPresenter.present(
                PaymentState.Initiating(phoneNumber = "9123456780", amount = "500")
            )
        )
    }

    @Test
    fun `in progress shows the fixed choreography not the session's own step`() {
        val ui = OverlayPresenter.present(
            PaymentState.InProgress(
                step = "session-step-that-must-be-ignored",
                progress = 0.99f,
                phoneNumber = "9123456780",
                amount = "500",
                transactionId = "t1"
            )
        )
        val progress = ui.progress
        assertNotNull(progress)
        assertEquals(R.string.overlay_processing_payment, progress!!.statusTextRes)
        assertEquals(R.string.overlay_sending_request, progress.stepTextRes)
        assertEquals(0.20f, progress.fraction)
        assertTrue(ui.startInCallPacing)
        assertEquals(OverlayUi.Finish.None, ui.finish)
        assertNull(ui.dialog)
        assertFalse(ui.endCall)
        assertFalse(ui.vibrate)
    }

    @Test
    fun `waiting for verification fills the bar and defers the awaiting dialog`() {
        val ui = OverlayPresenter.present(
            PaymentState.WaitingForVerification(
                timeout = 60_000L,
                phoneNumber = "9123456780",
                amount = "500",
                transactionId = "t1"
            )
        )
        val progress = ui.progress
        assertNotNull(progress)
        assertEquals(R.string.overlay_heading_request_sent, progress!!.statusTextRes)
        assertEquals(R.string.overlay_request_sent, progress.stepTextRes)
        // The only fill the mapping ever chooses — proof the request is through.
        assertEquals(1f, progress.fraction)
        assertEquals(TerminalDialog.AwaitingConfirmation, ui.dialog)
        // The filled bar is held so it can be seen before the wind-down.
        assertEquals(OverlayUi.Finish.AfterBarHold, ui.finish)
        // Deliberate: this state does NOT hang up the call.
        assertFalse(ui.endCall)
        assertFalse(ui.vibrate)
        assertFalse(ui.startInCallPacing)
    }

    @Test
    fun `success finishes with no dialog - PaymentResultActivity owns the success surface`() {
        val ui = OverlayPresenter.present(
            PaymentState.Success(transactionId = "t1", phoneNumber = "9123456780", amount = "500")
        )
        // The deliberate no-success-dialog rule.
        assertNull(ui.dialog)
        assertEquals(OverlayUi.Finish.Now, ui.finish)
        assertTrue(ui.endCall)
        assertTrue(ui.vibrate)
        assertNull(ui.progress)
    }

    @Test
    fun `failure shows the failure dialog and hangs up the call`() {
        val ui = OverlayPresenter.present(
            PaymentState.Failed(
                error = "declined",
                phoneNumber = "9123456780",
                amount = "500",
                transactionId = "t1"
            )
        )
        assertEquals(TerminalDialog.Failed, ui.dialog)
        assertEquals(OverlayUi.Finish.Now, ui.finish)
        assertTrue(ui.endCall)
        assertFalse(ui.vibrate)
        assertNull(ui.progress)
    }

    @Test
    fun `needs review finishes with no dialog - PaymentResultActivity owns the review surface`() {
        val ui = OverlayPresenter.present(
            PaymentState.NeedsReview(transactionId = "t1", phoneNumber = "9123456780", amount = "500")
        )
        // NeedsReview deliberately routes to PaymentResultActivity, not to a dialog.
        assertNull(ui.dialog)
        assertEquals(OverlayUi.Finish.Now, ui.finish)
        assertTrue(ui.endCall)
        assertFalse(ui.vibrate)
    }

    @Test
    fun `a user cancellation routes to the by-user dialog`() {
        // PaymentSessionManager.onUserCancelled() emits exactly this reason.
        assertEquals(
            TerminalDialog.CancelledByUser,
            OverlayPresenter.present(cancelled("Cancelled by user")).dialog
        )
        // The match is a case-insensitive substring of the reason string.
        assertEquals(
            TerminalDialog.CancelledByUser,
            OverlayPresenter.present(cancelled("cancelled BY USER")).dialog
        )
        // The terminate path already ended the call; the mapping never hangs up for a cancel.
        val ui = OverlayPresenter.present(cancelled("Cancelled by user"))
        assertEquals(OverlayUi.Finish.Now, ui.finish)
        assertFalse(ui.endCall)
        assertFalse(ui.vibrate)
    }

    @Test
    fun `the watchdog never-started cancellation reads as a generic cancel`() {
        // The overlay watchdog fires PaymentSessionManager.onCallNeverStarted(),
        // whose reason must not be mistaken for the user's terminate press.
        // This route had zero coverage before the presenter existed.
        val ui = OverlayPresenter.present(cancelled("The payment call was never connected"))
        assertEquals(TerminalDialog.Cancelled, ui.dialog)
        assertEquals(OverlayUi.Finish.Now, ui.finish)
        assertNull(ui.progress)
    }

    @Test
    fun `every other non-user reason also reads as a generic cancel`() {
        listOf(
            "Could not start the payment call", // onDialFailed(), from MainActivityHelper
            "Call ended before the payment flow could complete", // short-call heuristic
            "User cancelled" // the data class default: no "by user" substring
        ).forEach { reason ->
            assertEquals(TerminalDialog.Cancelled, OverlayPresenter.present(cancelled(reason)).dialog)
        }
    }

    @Test
    fun `timeout winds the overlay down and shows nothing for every timeout type`() {
        // No confirming bank SMS by the deadline: the row is discarded and
        // nothing is shown, regardless of which deadline expired.
        TimeoutType.entries.forEach { type ->
            val ui = OverlayPresenter.present(
                PaymentState.Timeout(type, phoneNumber = "9123456780", amount = "500", transactionId = "t1")
            )
            assertNull(ui.dialog)
            assertNull(ui.progress)
            assertEquals(OverlayUi.Finish.Now, ui.finish)
            assertFalse(ui.endCall)
            assertFalse(ui.vibrate)
        }
    }
}
