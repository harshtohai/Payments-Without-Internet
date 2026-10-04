// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.states

import java.util.UUID

/**
 * Sealed class hierarchy for payment states providing type-safe state management
 * and clear state transitions for the UPI123 payment system
 */
sealed class PaymentState {

    /**
     * The facts every non-idle payment carries, on every state: which
     * payment (its transaction id), to whom (phone number), and for how
     * much (amount). [Idle] alone implements nothing — before a payment
     * starts there are no facts to carry.
     */
    interface SessionData {
        val transactionId: String
        val phoneNumber: String
        val amount: String
    }

    /**
     * Initial state when no payment is in progress
     */
    object Idle : PaymentState()

    /**
     * State when payment is being initiated
     * @param phoneNumber The recipient's phone number
     * @param amount The payment amount
     * @param transactionId Unique transaction identifier
     */
    data class Initiating(
        override val phoneNumber: String,
        override val amount: String,
        override val transactionId: String = UUID.randomUUID().toString()
    ) : PaymentState(), SessionData

    /**
     * State when UPI123 call is in progress
     * @param step Current step in the call process
     * @param progress Progress percentage (0.0 to 1.0)
     * @param phoneNumber The recipient's phone number
     * @param amount The payment amount
     * @param transactionId Unique transaction identifier
     */
    data class InProgress(
        val step: String,
        val progress: Float,
        override val phoneNumber: String,
        override val amount: String,
        override val transactionId: String
    ) : PaymentState(), SessionData

    /**
     * State when waiting for bank verification call
     * @param timeout Timeout in milliseconds
     * @param phoneNumber The recipient's phone number
     * @param amount The payment amount
     * @param transactionId Unique transaction identifier
     */
    data class WaitingForVerification(
        val timeout: Long,
        override val phoneNumber: String,
        override val amount: String,
        override val transactionId: String
    ) : PaymentState(), SessionData

    /**
     * State when payment is successfully completed
     * @param transactionId Unique transaction identifier
     * @param phoneNumber The recipient's phone number
     * @param amount The payment amount
     * @param bankReference Bank's reference number
     * @param timestamp Completion timestamp
     */
    data class Success(
        override val transactionId: String,
        override val phoneNumber: String,
        override val amount: String,
        val bankReference: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) : PaymentState(), SessionData

    /**
     * State when payment fails
     * @param error Error message describing the failure
     * @param errorCode Specific error code if available
     * @param phoneNumber The recipient's phone number
     * @param amount The payment amount
     * @param transactionId Unique transaction identifier
     * @param canRetry Whether the payment can be retried
     */
    data class Failed(
        val error: String,
        val errorCode: String? = null,
        override val phoneNumber: String,
        override val amount: String,
        override val transactionId: String,
        val canRetry: Boolean = true
    ) : PaymentState(), SessionData

    /**
     * Terminal state when a bank SMS arrived during the payment but did not
     * match what was sent (e.g. a different amount). The outcome is recorded
     * but must be reviewed by the user against their bank statement.
     * @param transactionId Unique transaction identifier
     * @param phoneNumber The recipient's phone number
     * @param amount The payment amount that was requested
     */
    data class NeedsReview(
        override val transactionId: String,
        override val phoneNumber: String,
        override val amount: String
    ) : PaymentState(), SessionData

    /**
     * State when payment is cancelled by user
     * @param phoneNumber The recipient's phone number
     * @param amount The payment amount
     * @param transactionId Unique transaction identifier
     * @param reason Reason for cancellation
     */
    data class Cancelled(
        override val phoneNumber: String,
        override val amount: String,
        override val transactionId: String,
        val reason: String = "User cancelled"
    ) : PaymentState(), SessionData

    /**
     * State when payment is in a timeout scenario
     * @param timeoutType Type of timeout (call, verification, etc.)
     * @param phoneNumber The recipient's phone number
     * @param amount The payment amount
     * @param transactionId Unique transaction identifier
     */
    data class Timeout(
        val timeoutType: TimeoutType,
        override val phoneNumber: String,
        override val amount: String,
        override val transactionId: String
    ) : PaymentState(), SessionData

    /**
     * Checks if the payment is in a terminal state (Success, Failed, Cancelled)
     */
    fun isTerminal(): Boolean = this is Success || this is Failed || this is Cancelled || this is NeedsReview

    /**
     * Checks if the payment is in progress
     */
    fun isInProgress(): Boolean = this is Initiating || this is InProgress || this is WaitingForVerification

    /**
     * Checks if the payment can be retried
     */
    fun canRetry(): Boolean = when (this) {
        is Failed -> canRetry
        is Timeout -> true
        else -> false
    }

    /**
     * Gets the transaction ID if available
     */
    fun getTransactionIdValue(): String? = (this as? SessionData)?.transactionId

    /**
     * Gets the phone number if available
     */
    fun getPhoneNumberValue(): String? = (this as? SessionData)?.phoneNumber

    /**
     * Gets the amount if available
     */
    fun getAmountValue(): String? = (this as? SessionData)?.amount
}

/**
 * Enum for different types of timeouts
 */
enum class TimeoutType {
    CALL_INITIATION,
    BANK_VERIFICATION,
    USER_RESPONSE,
    NETWORK_CONNECTION
}
