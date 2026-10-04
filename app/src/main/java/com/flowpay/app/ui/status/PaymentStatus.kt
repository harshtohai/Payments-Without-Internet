// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.ui.status

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.flowpay.app.R
import com.flowpay.app.data.TransactionStatus
import com.flowpay.app.states.PaymentState
import com.flowpay.app.ui.theme.FlowpayStatusError
import com.flowpay.app.ui.theme.FlowpayStatusNeutral
import com.flowpay.app.ui.theme.FlowpayStatusSuccess
import com.flowpay.app.ui.theme.FlowpayStatusWarning

/**
 * The one mapping from a payment's status word to everything a renderer
 * shows for it: the list label, the shared status color, and the
 * notification title.
 *
 * The words themselves are canonical in [TransactionStatus] (ADR-0001).
 * Screens consume this module; nothing re-derives a status word or invents
 * a synonym. An unrecognized word reads as UNVERIFIED everywhere — an
 * outcome the bank never confirmed is never rendered as success or
 * pending.
 */
object PaymentStatus {

    /** The canonical status word for a raw DB/intent string. */
    fun fromDbString(raw: String): String {
        val word = raw.uppercase()
        return when (word) {
            TransactionStatus.PENDING,
            TransactionStatus.SUCCESS,
            TransactionStatus.FAILED,
            TransactionStatus.CANCELLED,
            TransactionStatus.UNVERIFIED,
            TransactionStatus.NEEDS_REVIEW -> word
            else -> TransactionStatus.UNVERIFIED
        }
    }

    /**
     * The status word for a session state, or null while the payment is
     * still in flight. Timeout is deliberately null too: its row is
     * deleted, so there is no recorded outcome to render.
     */
    fun fromState(state: PaymentState): String? = when (state) {
        is PaymentState.Success -> TransactionStatus.SUCCESS
        is PaymentState.Failed -> TransactionStatus.FAILED
        is PaymentState.Cancelled -> TransactionStatus.CANCELLED
        is PaymentState.NeedsReview -> TransactionStatus.NEEDS_REVIEW
        else -> null
    }

    /** Short list label (history rows, chips). */
    @StringRes
    fun label(status: String): Int = when (status.uppercase()) {
        TransactionStatus.SUCCESS -> R.string.status_label_success
        TransactionStatus.PENDING -> R.string.status_label_pending
        TransactionStatus.CANCELLED -> R.string.status_label_cancelled
        TransactionStatus.FAILED -> R.string.status_label_failed
        TransactionStatus.NEEDS_REVIEW -> R.string.status_label_needs_review
        else -> R.string.status_label_unverified // UNVERIFIED, unknown
    }

    /** The shared status color (history list, detail dialog, result surfaces). */
    fun color(status: String): Color = when (status.uppercase()) {
        TransactionStatus.SUCCESS -> FlowpayStatusSuccess
        TransactionStatus.FAILED -> FlowpayStatusError
        TransactionStatus.PENDING, TransactionStatus.NEEDS_REVIEW -> FlowpayStatusWarning
        else -> FlowpayStatusNeutral // UNVERIFIED, CANCELLED, unknown
    }

    /** Full-sentence title for the payment-result notification. */
    @StringRes
    fun notificationTitle(status: String): Int = when (status.uppercase()) {
        TransactionStatus.SUCCESS -> R.string.payment_status_success
        TransactionStatus.FAILED -> R.string.payment_status_failed
        TransactionStatus.NEEDS_REVIEW -> R.string.payment_status_needs_review
        else -> R.string.payment_status_unverified // UNVERIFIED, PENDING, CANCELLED, unknown
    }
}
