// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.managers

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.StringRes
import com.flowpay.app.R

/**
 * One terminal payment outcome, expressed as the result dialog that reports
 * it. The named instances in the companion replace the four near-identical
 * show*() methods this manager used to carry; each carries exactly the
 * resource ids and behaviour its old method had.
 *
 * [ackOnDismiss] is false only for [AwaitingConfirmation], which deliberately
 * does NOT acknowledge the session on dismiss: the session is still waiting
 * for the bank's confirmation SMS, and acknowledging it would suppress the
 * result surface that SMS is meant to trigger. Every other outcome
 * acknowledges on dismiss so the result is never re-shown.
 */
data class TerminalDialog(
    @StringRes val title: Int,
    @StringRes val message: Int,
    @StringRes val confirmLabel: Int,
    /** Toast this outcome degrades to when its dialog cannot be shown. */
    @StringRes val fallbackToast: Int,
    /** Whether dismissing this outcome's dialog acknowledges the session. */
    val ackOnDismiss: Boolean,
    /** Label for the dismissal / show-failure log lines. */
    val dismissLogLabel: String
) {
    companion object {
        /** The call ended before the payment flow could complete. */
        val Cancelled = TerminalDialog(
            title = R.string.dialog_cancelled_title,
            message = R.string.dialog_cancelled_message,
            confirmLabel = R.string.action_ok,
            fallbackToast = R.string.toast_payment_cancelled,
            ackOnDismiss = true,
            dismissLogLabel = "Transaction cancelled"
        )

        /** The user terminated the transaction via the terminate button. */
        val CancelledByUser = TerminalDialog(
            title = R.string.dialog_cancelled_title,
            message = R.string.dialog_cancelled_by_user_message,
            confirmLabel = R.string.action_ok,
            fallbackToast = R.string.toast_payment_cancelled,
            ackOnDismiss = true,
            dismissLogLabel = "Transaction cancelled by user"
        )

        /** The bank's SMS reported the transaction as failed. */
        val Failed = TerminalDialog(
            title = R.string.dialog_failed_title,
            message = R.string.dialog_failed_message,
            confirmLabel = R.string.action_ok,
            fallbackToast = R.string.toast_payment_failed,
            ackOnDismiss = true,
            dismissLogLabel = "Transaction failed"
        )

        /**
         * The call finished normally; the request was sent and the bank's
         * confirmation (callback call + SMS) is still on its way.
         */
        val AwaitingConfirmation = TerminalDialog(
            title = R.string.dialog_awaiting_title,
            message = R.string.dialog_awaiting_message,
            confirmLabel = R.string.action_got_it,
            fallbackToast = R.string.toast_awaiting_confirmation,
            ackOnDismiss = false,
            dismissLogLabel = "Awaiting confirmation"
        )
    }
}

/**
 * Manages transaction-related dialogs based on payment session outcomes.
 *
 * When constructed with a Service context the dialog window type is set to
 * TYPE_APPLICATION_OVERLAY so the dialog can actually display over the
 * dialer (a plain AlertDialog from a Service throws BadTokenException —
 * previously these silently degraded to toasts).
 *
 * [onAnyDismiss] fires whenever the user dismisses a result dialog, letting
 * the caller acknowledge the payment session so the result isn't re-shown.
 *
 * There is deliberately no success dialog here. PaymentResultActivity —
 * launched by the SMS ingestion pipeline, and backed by a notification
 * when the direct launch is blocked — is the only success surface, so a
 * second one from this service would either duplicate it or race it.
 */
class TransactionDialogManager(
    private val context: Context,
    private val onAnyDismiss: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "TransactionDialogManager"
    }

    private fun AlertDialog.prepareForOverlayDisplay(): AlertDialog {
        if (context !is Activity) {
            window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
        return this
    }

    private fun dismissed(outcome: TerminalDialog) {
        Log.d(TAG, "${outcome.dismissLogLabel} dialog dismissed")
        if (outcome.ackOnDismiss) {
            onAnyDismiss?.invoke()
        }
    }

    /**
     * Shows the dialog for a terminal payment outcome.
     *
     * On dismiss, ack-firing outcomes acknowledge the session via
     * [onAnyDismiss] (see [TerminalDialog.ackOnDismiss]). If showing the
     * dialog throws, every outcome degrades to its
     * [TerminalDialog.fallbackToast], and ack-firing outcomes still
     * acknowledge so the result is never re-shown elsewhere.
     */
    fun show(outcome: TerminalDialog) {
        try {
            AlertDialog.Builder(context)
                .setTitle(context.getString(outcome.title))
                .setMessage(context.getString(outcome.message))
                .setPositiveButton(context.getString(outcome.confirmLabel)) { dialog, _ ->
                    dialog.dismiss()
                    dismissed(outcome)
                }
                .setCancelable(false)
                .create()
                .prepareForOverlayDisplay()
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show ${outcome.dismissLogLabel.lowercase()} dialog", e)
            Toast.makeText(context, outcome.fallbackToast, Toast.LENGTH_LONG).show()
            if (outcome.ackOnDismiss) {
                onAnyDismiss?.invoke()
            }
        }
    }
}
