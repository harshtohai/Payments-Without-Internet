// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.ui.status

import com.flowpay.app.R
import com.flowpay.app.data.Transaction
import com.flowpay.app.data.TransactionStatus
import com.flowpay.app.states.PaymentState
import com.flowpay.app.states.TimeoutType
import com.flowpay.app.ui.theme.FlowpayStatusError
import com.flowpay.app.ui.theme.FlowpayStatusNeutral
import com.flowpay.app.ui.theme.FlowpayStatusSuccess
import com.flowpay.app.ui.theme.FlowpayStatusWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The status-words module is the single test surface for every renderer's
 * word choices (ADR-0001): canonical words map to themselves, junk and
 * phantom synonyms read as UNVERIFIED — never as success — and every
 * presentation fact (label, color, notification title) is decided here
 * once.
 */
class PaymentStatusTest {

    @Test
    fun `each canonical status word maps to itself`() {
        listOf(
            TransactionStatus.PENDING,
            TransactionStatus.SUCCESS,
            TransactionStatus.FAILED,
            TransactionStatus.CANCELLED,
            TransactionStatus.UNVERIFIED,
            TransactionStatus.NEEDS_REVIEW
        ).forEach { word ->
            assertEquals(word, PaymentStatus.fromDbString(word))
        }
    }

    @Test
    fun `phantom synonyms and junk read as UNVERIFIED, never as success`() {
        listOf("SUCCESSFUL", "COMPLETED", "DECLINED", "WHATEVER", "").forEach { raw ->
            assertEquals(TransactionStatus.UNVERIFIED, PaymentStatus.fromDbString(raw))
        }
    }

    @Test
    fun `terminal session states map to status words`() {
        assertEquals(
            TransactionStatus.SUCCESS,
            PaymentStatus.fromState(
                PaymentState.Success(transactionId = "t1", phoneNumber = "98", amount = "500")
            )
        )
        assertEquals(
            TransactionStatus.FAILED,
            PaymentStatus.fromState(
                PaymentState.Failed(error = "declined", phoneNumber = "98", amount = "500", transactionId = "t1")
            )
        )
        assertEquals(
            TransactionStatus.CANCELLED,
            PaymentStatus.fromState(
                PaymentState.Cancelled(phoneNumber = "98", amount = "500", transactionId = "t1")
            )
        )
        assertEquals(
            TransactionStatus.NEEDS_REVIEW,
            PaymentStatus.fromState(
                PaymentState.NeedsReview(transactionId = "t1", phoneNumber = "98", amount = "500")
            )
        )
    }

    @Test
    fun `in-flight session states map to no status word`() {
        assertNull(PaymentStatus.fromState(PaymentState.Idle))
        assertNull(PaymentStatus.fromState(PaymentState.Initiating(phoneNumber = "98", amount = "500")))
        assertNull(
            PaymentStatus.fromState(
                PaymentState.InProgress(step = "pay", progress = 0.5f, phoneNumber = "98", amount = "500", transactionId = "t1")
            )
        )
        assertNull(
            PaymentStatus.fromState(
                PaymentState.WaitingForVerification(timeout = 60_000L, phoneNumber = "98", amount = "500", transactionId = "t1")
            )
        )
        assertNull(
            PaymentStatus.fromState(
                PaymentState.Timeout(timeoutType = TimeoutType.BANK_VERIFICATION, phoneNumber = "98", amount = "500", transactionId = "t1")
            )
        )
    }

    @Test
    fun `label picks a distinct resource per canonical word`() {
        assertEquals(R.string.status_label_pending, PaymentStatus.label(TransactionStatus.PENDING))
        assertEquals(R.string.status_label_success, PaymentStatus.label(TransactionStatus.SUCCESS))
        assertEquals(R.string.status_label_failed, PaymentStatus.label(TransactionStatus.FAILED))
        assertEquals(R.string.status_label_cancelled, PaymentStatus.label(TransactionStatus.CANCELLED))
        assertEquals(R.string.status_label_unverified, PaymentStatus.label(TransactionStatus.UNVERIFIED))
        assertEquals(R.string.status_label_needs_review, PaymentStatus.label(TransactionStatus.NEEDS_REVIEW))
        // Junk reads as UNVERIFIED here too — no renderer invents a word.
        assertEquals(R.string.status_label_unverified, PaymentStatus.label("COMPLETED"))
    }

    @Test
    fun `color matches the palette, one token per outcome group`() {
        assertEquals(FlowpayStatusSuccess, PaymentStatus.color(TransactionStatus.SUCCESS))
        assertEquals(FlowpayStatusError, PaymentStatus.color(TransactionStatus.FAILED))
        assertEquals(FlowpayStatusWarning, PaymentStatus.color(TransactionStatus.PENDING))
        assertEquals(FlowpayStatusWarning, PaymentStatus.color(TransactionStatus.NEEDS_REVIEW))
        assertEquals(FlowpayStatusNeutral, PaymentStatus.color(TransactionStatus.UNVERIFIED))
        assertEquals(FlowpayStatusNeutral, PaymentStatus.color(TransactionStatus.CANCELLED))
        assertEquals(FlowpayStatusNeutral, PaymentStatus.color("COMPLETED"))
    }

    @Test
    fun `notification title never claims success for an unconfirmed outcome`() {
        assertEquals(R.string.payment_status_success, PaymentStatus.notificationTitle(TransactionStatus.SUCCESS))
        assertEquals(R.string.payment_status_failed, PaymentStatus.notificationTitle(TransactionStatus.FAILED))
        assertEquals(R.string.payment_status_needs_review, PaymentStatus.notificationTitle(TransactionStatus.NEEDS_REVIEW))
        assertEquals(R.string.payment_status_unverified, PaymentStatus.notificationTitle(TransactionStatus.UNVERIFIED))
        // PENDING / CANCELLED / junk: the honest title, never "Payment Successful".
        assertEquals(R.string.payment_status_unverified, PaymentStatus.notificationTitle("WHATEVER"))
    }

    @Test
    fun `toPaymentDetails passes the row's canonical word through`() {
        fun row(status: String) = Transaction(
            transactionId = "t1",
            amount = "500",
            status = status,
            bankName = "HDFC Bank",
            smsExcerpt = "₹500 debited — HDFC Bank"
        )
        assertEquals(TransactionStatus.SUCCESS, row(TransactionStatus.SUCCESS).toPaymentDetails().status)
        assertEquals(TransactionStatus.FAILED, row("failed").toPaymentDetails().status)
    }
}
