package eu.pretix.pretixscan.utils

import java.math.BigDecimal

fun format(value: BigDecimal, currency: String): String {
    try {
        val curr = java.util.Currency.getInstance(currency)
        val format = java.text.NumberFormat.getCurrencyInstance()
        format.currency = curr
        return format.format(value)
    } catch (e: IllegalArgumentException) {
        return String.format("%s %.2f", currency, value)
    }

}