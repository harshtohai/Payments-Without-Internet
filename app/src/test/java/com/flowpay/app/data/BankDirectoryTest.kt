// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.data

import com.flowpay.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bank directory is the one display vocabulary for banks (ADR-0002):
 * ids are unique, every id resolves to a distinct name resource, and an
 * unknown id can never render a blank.
 */
class BankDirectoryTest {

    @Test
    fun `bank ids are unique and in setup order`() {
        assertEquals(BankDirectory.ids.size, BankDirectory.ids.toSet().size)
        assertEquals("sbi", BankDirectory.ids.first())
        assertEquals("canara", BankDirectory.ids.last())
    }

    @Test
    fun `every bank id resolves to a distinct name resource`() {
        val names = BankDirectory.ids.map { BankDirectory.name(it) }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `an unknown bank id falls back to the default bank's name`() {
        assertEquals(R.string.bank_name_hdfc, BankDirectory.name("unknown"))
        assertEquals(R.string.bank_name_hdfc, BankDirectory.name(""))
    }

    @Test
    fun `no known bank id resolves to another bank's name`() {
        BankDirectory.ids.forEach { id ->
            assertTrue(
                "id $id fell through to the fallback",
                id == "hdfc" || BankDirectory.name(id) != R.string.bank_name_hdfc
            )
        }
    }
}
