package com.lc.shoppingrecord.model

import java.time.DateTimeException
import java.time.LocalDate

object DateOnly {
    private val DATE_PATTERN = Regex(
        "(?<!\\d)(19\\d{2}|20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})(?:日)?"
    )

    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val match = DATE_PATTERN.find(value) ?: return null
        return try {
            LocalDate.of(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            ).toString()
        } catch (_: DateTimeException) {
            null
        }
    }

    fun shortLabel(raw: String?): String = normalize(raw)?.substring(5) ?: "--"
}
