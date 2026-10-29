package org.evsyukov.hermes.ui.common

/** A token count the way the usage screens print it: 950, 12.4 K, 1.3 M. */
fun tokenCount(n: Long?): String {
    val v = n ?: 0
    return when {
        v >= 1_000_000 -> "%.1f M".format(v / 1_000_000.0)
        v >= 1_000 -> "%.1f K".format(v / 1_000.0)
        else -> v.toString()
    }
}

/** Dollars to the cent; a chat that spent less than a cent says so instead of showing $0.00. */
fun dollars(amount: Double): String = if (amount < 0.01) "<$0.01" else "$${"%.2f".format(amount)}"
