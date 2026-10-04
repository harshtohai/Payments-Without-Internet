// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.services

import androidx.annotation.StringRes
import com.flowpay.app.R
import com.flowpay.app.managers.TerminalDialog
import com.flowpay.app.states.PaymentState

/**
 * Immutable description of everything the call overlay should do for one
 * [PaymentState]: the progress content to display, the result dialog to
 * show on wind-down, and how to wind down. Pure data — [CallOverlayService]
 * owns every effect (window management, pacing animators, timing, audio,
 * haptics, the actual view/dialog calls) and renders whatever this
 * describes. Defaults mean "show nothing, change nothing".
 */
data class OverlayUi(
    /** Progress heading/step/bar update to apply, or null to leave the overlay as-is. */
    val progress: ProgressUpdate? = null,
    /** Result dialog to show when the overlay winds down, if any. */
    val dialog: TerminalDialog? = null,
    /** Whether — and how — the service should wind the overlay down. */
    val finish: Finish = Finish.None,
    /** Hang up the bank call once this outcome has settled (best-effort). */
    val endCall: Boolean = false,
    /** Fire the success haptic. */
    val vibrate: Boolean = false,
    /** Start the in-call progress creep toward its ceiling (see CallOverlayService). */
    val startInCallPacing: Boolean = false
) {
    /** One overlay content update: the heading, the line under the bar, the bar fill. */
    data class ProgressUpdate(
        /** The overlay heading, as a string resource (compiler-checked copy). */
        @StringRes val statusTextRes: Int,
        /** The step line under the progress bar. */
        @StringRes val stepTextRes: Int,
        /** Bar fill as a 0..1 fraction. */
        val fraction: Float
    )

    /** How the service should wind the overlay down for a settled outcome. */
    enum class Finish {
        /** Not settled (or nothing to show) — the overlay stays up. */
        None,
        /** Wind down immediately via the service's finishWithResult. */
        Now,
        /**
         * Let the filled bar be seen first, then wind down. The hold
         * duration is the service's PROGRESS_COMPLETE_HOLD_MS and the
         * delay is posted on the service's pacing handler, so a newer
         * state change still cancels it.
         */
        AfterBarHold
    }
}

/**
 * The overlay presenter — the pure mapping from payment-session state to
 * what the call overlay should show (CONTEXT.md, "Overlay presenter"):
 * which dialog, which copy, what progress. It decides; [CallOverlayService]
 * renders. Everything with an Android dependency stays in the service —
 * window management, pacing animators, the timing choreography, audio,
 * haptics, and the actual view/dialog calls — which is what keeps this
 * object plain JVM and unit-testable without a device
 * ([OverlayPresenterTest]), the same pattern as the PaymentStatus mapping
 * in ui/status.
 *
 * Deliberate behaviors this mapping locks in (each was a comment in the
 * service's old state handler):
 *  - Success and NeedsReview show NO dialog: PaymentResultActivity —
 *    launched by the SMS pipeline, with the NEEDS_REVIEW status extra for
 *    the latter — is the outcome surface; a second dialog from the service
 *    would duplicate it or race it.
 *  - Timeout shows nothing at all: no bank SMS by the deadline means there
 *    is no confirmed payment to report — the row is discarded — so the
 *    overlay just winds down.
 *  - WaitingForVerification is the ONLY state that fills the bar: the
 *    request is genuinely through. In-call pacing creeps toward a ceiling
 *    it can never pass on a timer, so the overlay can sit short of full but
 *    can never claim the payment is through when it isn't.
 *  - InProgress ignores the session's own step/progress fields: the
 *    in-call choreography is fixed (answered-call start fraction below,
 *    then the service's creep), not a mirror of the session's last
 *    reported value.
 *  - Cancelled: "…by user…" in the reason marks the user (the overlay's
 *    terminate button → onUserCancelled) as the cancelling party; every
 *    other reason — the never-started watchdog, a failed dial, a
 *    too-short call — reads as a generic cancel.
 */
object OverlayPresenter {

    /**
     * Bar fill the moment the bank's call is answered. The IVR emits no
     * progress events, so from here the service's pacing creeps the bar
     * toward a ceiling it can never pass on a timer — the fills this
     * mapper chooses are the only "real" ones.
     */
    private const val IN_CALL_START_FRACTION = 0.20f

    /**
     * The only fill that can ever complete the bar: a genuinely sent
     * request (WaitingForVerification). No timer can reach it.
     */
    private const val FILLED_FRACTION = 1f

    /** The overlay content and wind-down plan for one payment-session state. */
    fun present(state: PaymentState): OverlayUi = when (state) {
        PaymentState.Idle,
        is PaymentState.Initiating -> OverlayUi()

        is PaymentState.InProgress -> OverlayUi(
            progress = OverlayUi.ProgressUpdate(
                statusTextRes = R.string.overlay_processing_payment,
                stepTextRes = R.string.overlay_sending_request,
                fraction = IN_CALL_START_FRACTION
            ),
            startInCallPacing = true
        )

        is PaymentState.WaitingForVerification -> OverlayUi(
            progress = OverlayUi.ProgressUpdate(
                statusTextRes = R.string.overlay_heading_request_sent,
                stepTextRes = R.string.overlay_request_sent,
                fraction = FILLED_FRACTION
            ),
            dialog = TerminalDialog.AwaitingConfirmation,
            finish = OverlayUi.Finish.AfterBarHold
        )

        is PaymentState.Success -> OverlayUi(
            finish = OverlayUi.Finish.Now,
            endCall = true,
            vibrate = true
        )

        is PaymentState.Failed -> OverlayUi(
            dialog = TerminalDialog.Failed,
            finish = OverlayUi.Finish.Now,
            endCall = true
        )

        is PaymentState.NeedsReview -> OverlayUi(
            finish = OverlayUi.Finish.Now,
            endCall = true
        )

        is PaymentState.Cancelled -> OverlayUi(
            dialog = if (state.reason.contains("by user", ignoreCase = true)) {
                TerminalDialog.CancelledByUser
            } else {
                TerminalDialog.Cancelled
            },
            finish = OverlayUi.Finish.Now
        )

        is PaymentState.Timeout -> OverlayUi(
            finish = OverlayUi.Finish.Now
        )
    }
}
