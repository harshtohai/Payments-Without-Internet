// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.data

import androidx.annotation.StringRes
import com.flowpay.app.R

/**
 * The one display vocabulary for banks: bank id → user-facing name, plus
 * the ordered list the setup screen shows. No screen hardcodes a bank
 * name (ADR-0002): the SMS-matching keywords in SmsTransactionParser are
 * deliberately separate — they answer "did this SMS come from this
 * bank", not "what is this bank called".
 */
object BankDirectory {

    /** Bank ids in setup order. The id is what gets persisted. */
    val ids: List<String> = listOf(
        "sbi", "hdfc", "icici", "axis", "kotak",
        "pnb", "bob", "yes", "idbi", "canara"
    )

    /**
     * The display name for a bank id. Unknown ids fall back to HDFC —
     * the same default the main screen has always shown — so a stray
     * pref can never render a blank.
     */
    @StringRes
    fun name(id: String): Int = when (id) {
        "sbi" -> R.string.bank_name_sbi
        "hdfc" -> R.string.bank_name_hdfc
        "icici" -> R.string.bank_name_icici
        "axis" -> R.string.bank_name_axis
        "kotak" -> R.string.bank_name_kotak
        "pnb" -> R.string.bank_name_pnb
        "bob" -> R.string.bank_name_bob
        "yes" -> R.string.bank_name_yes
        "idbi" -> R.string.bank_name_idbi
        "canara" -> R.string.bank_name_canara
        else -> R.string.bank_name_hdfc
    }
}
