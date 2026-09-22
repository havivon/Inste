package com.havivon.reelslab.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {

    private val hebrew = Locale("he", "IL")
    private val dateFormat = SimpleDateFormat("d MMM yyyy", hebrew)

    fun count(value: Long): String = when {
        value >= 1_000_000 -> String.format(hebrew, "%.1fM", value / 1_000_000.0)
        value >= 10_000 -> String.format(hebrew, "%.1fK", value / 1_000.0)
        else -> String.format(hebrew, "%,d", value)
    }

    fun duration(seconds: Double): String {
        val total = seconds.toInt()
        return String.format(hebrew, "%d:%02d", total / 60, total % 60)
    }

    /** Instagram sends taken_at in seconds; the local watch clock is millis. */
    fun dateFromSeconds(seconds: Long): String =
        if (seconds <= 0) "" else dateFormat.format(Date(seconds * 1000))

    fun percent(ratio: Double): String = String.format(hebrew, "%.1f%%", ratio * 100)
}
