package com.familyledger.app.domain

import java.math.BigDecimal
import java.math.RoundingMode

const val MONEY_SCALE = 10_000L

fun parseYuanToMicros(input: String): Long? {
    return try {
        val decimal = input.trim().replace(",", "").let(::BigDecimal)
        if (decimal.signum() < 0) return null
        decimal
            .setScale(4, RoundingMode.UNNECESSARY)
            .movePointRight(4)
            .longValueExact()
    } catch (_: Exception) {
        null
    }
}

fun formatYuan(micros: Long): String {
    val decimal = BigDecimal.valueOf(micros, 4).abs()
    val strippedScale = decimal.stripTrailingZeros().scale().coerceAtLeast(0)
    val displayScale = if (strippedScale > 2) strippedScale.coerceAtMost(4) else 2
    return "¥${decimal.setScale(displayScale, RoundingMode.UNNECESSARY).toPlainString()}"
}

fun formatSignedYuan(micros: Long): String = when {
    micros > 0 -> "+${formatYuan(micros)}"
    micros < 0 -> "−${formatYuan(micros)}"
    else -> formatYuan(0)
}

fun calculateWorkAmount(quantity: Long, unitPriceMicros: Long): Long? = try {
    if (quantity <= 0 || unitPriceMicros <= 0) null else Math.multiplyExact(quantity, unitPriceMicros)
} catch (_: ArithmeticException) {
    null
}
