package com.example.stepscounter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class StepTrackerTest {

    private val zone = ZoneOffset.UTC
    private val tracker = StepTracker { zone }
    private val day1 = LocalDate.of(2026, 11, 22)
    private val day2 = day1.plusDays(1)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0, second: Int = 0): Long =
        day.atTime(hour, minute, second).toInstant(zone).toEpochMilli()

    /** The phone booted at 06:00 on day 1 (boot #5) unless a test says otherwise. */
    private fun reading(
        counter: Long,
        time: Long,
        continuous: Boolean = true,
        bootCount: Int = 5,
        bootTime: Long = at(day1, 6),
    ) = CounterReading(counter, time, bootCount, bootTime, continuous)

    private fun feed(state: TrackerState, vararg readings: CounterReading): TrackerState =
        readings.fold(state) { s, r -> tracker.onReading(s, r).state }

    /** Tracking that started at 09:00 on day 1 with the counter at 1000. */
    private fun started(): TrackerState = feed(TrackerState(), reading(1000, at(day1, 9), continuous = false))

    @Test
    fun firstReadingOnlySetsBaseline() {
        val r = tracker.onReading(TrackerState(), reading(1000, at(day1, 9), continuous = false))
        assertEquals(Outcome.BASELINE, r.outcome)
        assertEquals(0, r.state.dailySteps)
        assertEquals(1000L, r.state.lastCounter)
        assertEquals(day1, r.state.day)
    }

    @Test
    fun shortWalksAndBurstsAreAllCounted() {
        // The old filters dropped walks of under 10 steps and bursts released by the step chip.
        val s = feed(
            started(),
            reading(1003, at(day1, 9, 0, 2)), // 3 steps
            reading(1007, at(day1, 9, 1)), // 4 steps after a pause
            reading(1017, at(day1, 9, 1) + 5), // 10 held-back steps released 5 ms later
            reading(1018, at(day1, 9, 1, 1)),
        )
        assertEquals(18, s.dailySteps)
    }

    @Test
    fun sameCounterValueChangesNothing() {
        val r = tracker.onReading(started(), reading(1000, at(day1, 9, 5)))
        assertEquals(Outcome.NO_CHANGE, r.outcome)
        assertEquals(0, r.state.dailySteps)
    }

    @Test
    fun stepsTakenWhileAppWasDeadAreRecoveredOnSameDay() {
        val s = feed(started(), reading(1100, at(day1, 9, 5)))
        // Killed at 09:05, restarted at 17:00 in a new process.
        val r = tracker.onReading(s, reading(4100, at(day1, 17), continuous = false))
        assertEquals(Outcome.COUNTED, r.outcome)
        assertEquals(3100, r.state.dailySteps)
    }

    @Test
    fun newDayStartsAtZeroWithoutWaitingForASteps() {
        val s = feed(started(), reading(1500, at(day1, 20)))
        val rolled = tracker.rollover(s, at(day2, 0, 0, 5))
        assertEquals(day2, rolled.day)
        assertEquals(0, rolled.dailySteps)
        assertEquals(1500L, rolled.lastCounter)
        assertEquals(rolled, tracker.rollover(rolled, at(day2, 8)))
    }

    @Test
    fun liveTrackingAcrossMidnightCreditsTheNewDay() {
        val s = feed(started(), reading(1500, at(day1, 23, 50)))
        val r = tracker.onReading(s, reading(1530, at(day2, 0, 10)))
        assertEquals(day2, r.state.day)
        assertEquals(30, r.state.dailySteps)
    }

    @Test
    fun gapAcrossMidnightWhileAppWasDeadIsNotGuessed() {
        val s = feed(started(), reading(1500, at(day1, 15)))
        val r = tracker.onReading(s, reading(6500, at(day2, 9), continuous = false))
        assertEquals(Outcome.UNATTRIBUTABLE, r.outcome)
        assertEquals(day2, r.state.day)
        assertEquals(0, r.state.dailySteps)
        // Counting carries on normally from the new baseline.
        assertEquals(20, tracker.onReading(r.state, reading(6520, at(day2, 9, 1))).state.dailySteps)
    }

    @Test
    fun rebootDetectedByBootCountCountsStepsSinceBoot() {
        val s = feed(started(), reading(5000, at(day1, 12)))
        // Rebooted at 13:00; the new counter has already passed the old value.
        val r = tracker.onReading(
            s,
            reading(6000, at(day1, 18), continuous = false, bootCount = 6, bootTime = at(day1, 13)),
        )
        assertEquals(4000 + 6000, r.state.dailySteps)
    }

    @Test
    fun rebootDetectedFromBootTimeWhenBootCountIsUnknown() {
        // Data saved by the previous app version has no boot count.
        val s = TrackerState(
            day = day1,
            dailySteps = 300,
            lastCounter = 18675,
            lastReadingMillis = at(day1, 10),
        )
        val r = tracker.onReading(s, reading(250, at(day1, 15), continuous = false, bootTime = at(day1, 14)))
        assertEquals(550, r.state.dailySteps)
    }

    @Test
    fun counterGoingBackwardsIsTreatedAsARestart() {
        val s = feed(started(), reading(1200, at(day1, 10)))
        val r = tracker.onReading(s, reading(15, at(day1, 10, 5)))
        assertEquals(215, r.state.dailySteps)
    }

    @Test
    fun rebootOnPreviousDayIsNotCreditedToToday() {
        val s = feed(started(), reading(1200, at(day1, 10)))
        val r = tracker.onReading(
            s,
            reading(3000, at(day2, 10), continuous = false, bootCount = 6, bootTime = at(day1, 22)),
        )
        assertEquals(Outcome.UNATTRIBUTABLE, r.outcome)
        assertEquals(0, r.state.dailySteps)
    }

    @Test
    fun rebootEarlierTodayCreditsStepsSinceBoot() {
        val s = feed(started(), reading(1200, at(day1, 10)))
        val r = tracker.onReading(
            s,
            reading(800, at(day2, 10), continuous = false, bootCount = 6, bootTime = at(day2, 7)),
        )
        assertEquals(day2, r.state.day)
        assertEquals(800, r.state.dailySteps)
    }

    @Test
    fun impossibleJumpIsIgnoredAndBecomesNewBaseline() {
        val r = tracker.onReading(started(), reading(1_000_000, at(day1, 9, 0, 10)))
        assertEquals(Outcome.IMPLAUSIBLE, r.outcome)
        assertEquals(0, r.state.dailySteps)
        assertEquals(1_000_000L, r.state.lastCounter)
    }

    @Test
    fun lateEventStampedBeforeMidnightGoesToCurrentDay() {
        val s = tracker.rollover(feed(started(), reading(1100, at(day1, 23, 50))), at(day2, 0, 1))
        val r = tracker.onReading(s, reading(1110, at(day1, 23, 59)))
        assertEquals(day2, r.state.day)
        assertEquals(10, r.state.dailySteps)
    }
}
