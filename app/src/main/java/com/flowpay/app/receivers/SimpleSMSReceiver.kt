// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.flowpay.app.helpers.TransactionDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SimpleSMSReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SimpleSMSReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Framework-provided PDU parsing (handles format + multipart correctly)
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages[0]?.originatingAddress ?: return
        val body = messages.filterNotNull().joinToString("") { it.messageBody ?: "" }
        if (body.isBlank()) return

        Log.d(TAG, "SMS received")

        // Keep the process alive until claiming + persistence complete —
        // a fire-and-forget coroutine from onReceive() can be killed
        // mid-write once onReceive() returns.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // goAsync() grants ~10s before the system finishes the
                // receiver anyway; bound our own work below that so
                // pendingResult.finish() always runs on our terms.
                val completed = withTimeoutOrNull(8_000) {
                    // Window check, dedup claim, and parse in one atomic
                    // step: null means this SMS decides nothing about any
                    // payment.
                    val claimed = TransactionDetector.getInstance(context).claim(sender, body)
                    if (claimed == null) {
                        Log.d(TAG, "SMS not claimed - ignoring")
                    } else {
                        SmsIngestionPipeline.ingest(context, claimed)
                    }
                }
                if (completed == null) {
                    Log.w(TAG, "SMS processing timed out before completion")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
