package com.familyledger.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test
    fun parsesPieceRateWithoutFloatingPointLoss() {
        assertEquals(700L, parseYuanToMicros("0.07"))
        assertEquals(12_000L, parseYuanToMicros("1.2"))
        assertEquals(1_234L, parseYuanToMicros("0.1234"))
    }

    @Test
    fun rejectsMoreThanFourDecimalPlaces() {
        assertNull(parseYuanToMicros("0.12345"))
        assertNull(parseYuanToMicros("-1"))
    }

    @Test
    fun calculatesAndFormatsWorkAmountExactly() {
        val amount = calculateWorkAmount(quantity = 120, unitPriceMicros = parseYuanToMicros("1.2")!!)
        assertEquals(1_440_000L, amount)
        assertEquals("¥144.00", formatYuan(amount!!))
    }

    @Test
    fun keepsSubCentPrecisionWhenNeeded() {
        assertEquals("¥0.1234", formatYuan(1_234L))
    }

    @Test
    fun formatsLongMinValueWithoutOverflow() {
        assertEquals("¥922337203685477.5808", formatYuan(Long.MIN_VALUE))
    }

    @Test
    fun rejectsOverflowingWorkAmount() {
        assertNull(calculateWorkAmount(Long.MAX_VALUE, 2))
    }
}
