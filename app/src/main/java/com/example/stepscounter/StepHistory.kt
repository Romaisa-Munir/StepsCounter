package com.example.stepscounter

import java.time.LocalDate

data class DayTotal(val day: LocalDate, val steps: Int)

/** Finished days' totals, kept for the weekly chart. Pure functions so they can be unit tested. */
object StepHistory {
    const val KEEP_DAYS = 31

    /** Stores [steps] for [day] and drops days older than [KEEP_DAYS] before [today]. */
    fun record(history: Map<LocalDate, Int>, day: LocalDate, steps: Int, today: LocalDate): Map<LocalDate, Int> {
        val oldest = today.minusDays(KEEP_DAYS - 1L)
        return (history + (day to steps)).filterKeys { !it.isBefore(oldest) }
    }

    /** The [count] days ending today, oldest first; days without data are 0. */
    fun lastDays(history: Map<LocalDate, Int>, today: LocalDate, todaySteps: Int, count: Int = 7): List<DayTotal> =
        (count - 1 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            DayTotal(day, if (back == 0) todaySteps else history[day] ?: 0)
        }

    fun encode(history: Map<LocalDate, Int>): String =
        history.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    fun decode(text: String?): Map<LocalDate, Int> =
        text.orEmpty().split(',').mapNotNull { entry ->
            val parts = entry.split('=')
            if (parts.size != 2) return@mapNotNull null
            val day = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val steps = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            day to steps
        }.toMap()
}
