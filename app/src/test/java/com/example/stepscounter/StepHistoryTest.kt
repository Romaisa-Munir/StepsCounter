package com.example.stepscounter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StepHistoryTest {

    private val today = LocalDate.of(2026, 11, 23)

    @Test
    fun lastDaysFillsGapsWithZeroAndUsesLiveTodayCount() {
        val history = mapOf(today.minusDays(1) to 6100, today.minusDays(3) to 4200)
        val week = StepHistory.lastDays(history, today, todaySteps = 812)
        assertEquals(7, week.size)
        assertEquals(today.minusDays(6), week.first().day)
        assertEquals(listOf(0, 0, 0, 4200, 0, 6100, 812), week.map { it.steps })
    }

    @Test
    fun recordDropsDaysOlderThanTheKeepWindow() {
        val old = today.minusDays(StepHistory.KEEP_DAYS.toLong())
        val kept = today.minusDays(StepHistory.KEEP_DAYS - 1L)
        val history = StepHistory.record(mapOf(old to 10, kept to 20), today.minusDays(1), 30, today)
        assertEquals(mapOf(kept to 20, today.minusDays(1) to 30), history)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val history = mapOf(today.minusDays(2) to 4200, today.minusDays(1) to 0)
        assertEquals(history, StepHistory.decode(StepHistory.encode(history)))
    }

    @Test
    fun decodeSkipsDamagedEntries() {
        val decoded = StepHistory.decode("2026-11-20=500,garbage,2026-13-40=1,2026-11-21=-3,2026-11-22=7")
        assertEquals(mapOf(LocalDate.of(2026, 11, 20) to 500, LocalDate.of(2026, 11, 22) to 7), decoded)
        assertEquals(emptyMap<LocalDate, Int>(), StepHistory.decode(null))
    }
}
