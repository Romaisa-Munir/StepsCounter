package com.example.stepscounter

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Step accounting, kept free of Android APIs so it can be unit tested.
 *
 * TYPE_STEP_COUNTER reports the total number of steps since the phone booted, and the phone's
 * sensor hub keeps counting while our process is dead or the CPU is asleep. So instead of
 * filtering individual events, we take the difference between two readings of that running total
 * and decide which day it belongs to. A missed or late event never loses steps: the next reading
 * still carries the full total.
 */
data class TrackerState(
    /** Day that [dailySteps] belongs to, or null before anything was recorded. */
    val day: LocalDate? = null,
    val dailySteps: Int = 0,
    /** Last raw counter value seen, or [NO_BASELINE]. */
    val lastCounter: Long = NO_BASELINE,
    /** Wall-clock time (ms) at which the counter had [lastCounter]. */
    val lastReadingMillis: Long = 0L,
    /** Settings.Global.BOOT_COUNT when [lastCounter] was read, or [UNKNOWN]. */
    val bootCount: Int = UNKNOWN,
) {
    companion object {
        const val NO_BASELINE = -1L
        const val UNKNOWN = -1
    }
}

/** One value from the hardware step counter. */
data class CounterReading(
    val counter: Long,
    /** Wall-clock time (ms) at which the counter had this value. */
    val timeMillis: Long,
    /** Settings.Global.BOOT_COUNT now, or [TrackerState.UNKNOWN]. */
    val bootCount: Int,
    /** Wall-clock time (ms) at which the phone last booted. */
    val bootTimeMillis: Long,
    /**
     * True when this process was already listening at the previous reading, i.e. the app was not
     * dead in between, so the steps in this reading were taken just before [timeMillis].
     */
    val continuous: Boolean,
)

enum class Outcome {
    /** First reading ever: only remembered as the starting point. */
    BASELINE,
    COUNTED,
    NO_CHANGE,
    /** The app was not running across midnight, so we can't tell which day the steps belong to. */
    UNATTRIBUTABLE,
    /** More steps than a person could take in the elapsed time: a sensor glitch. */
    IMPLAUSIBLE,
}

data class TrackerResult(val state: TrackerState, val credited: Int, val outcome: Outcome)

class StepTracker(private val zone: () -> ZoneId = ZoneId::systemDefault) {

    fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate()

    /** Starts a new day at zero once [nowMillis] is past the day of the stored total. */
    fun rollover(state: TrackerState, nowMillis: Long): TrackerState {
        val today = dayOf(nowMillis)
        val day = state.day
        return if (day == null || today.isAfter(day)) state.copy(day = today, dailySteps = 0) else state
    }

    fun onReading(state: TrackerState, reading: CounterReading): TrackerResult {
        // A late event stamped before the stored day is credited to the stored day.
        val rolled = rollover(state, reading.timeMillis)
        val today = rolled.day!!
        val next = rolled.copy(
            lastCounter = reading.counter,
            lastReadingMillis = reading.timeMillis,
            bootCount = reading.bootCount,
        )
        if (state.lastCounter == TrackerState.NO_BASELINE) {
            return TrackerResult(next, 0, Outcome.BASELINE)
        }

        val rebooted =
            (reading.bootCount != TrackerState.UNKNOWN &&
                state.bootCount != TrackerState.UNKNOWN &&
                reading.bootCount != state.bootCount) ||
                state.lastReadingMillis < reading.bootTimeMillis - BOOT_TIME_SLACK_MS
        // The counter starts again from zero after a reboot (or a sensor hub reset).
        val restarted = rebooted || reading.counter < state.lastCounter
        val delta = if (restarted) reading.counter else reading.counter - state.lastCounter
        if (delta <= 0L) return TrackerResult(next, 0, Outcome.NO_CHANGE)

        // The steps in [delta] were taken somewhere between [since] and the reading.
        val since =
            if (rebooted) maxOf(reading.bootTimeMillis, state.lastReadingMillis) else state.lastReadingMillis
        if (dayOf(since).isBefore(today) && !reading.continuous) {
            return TrackerResult(next, 0, Outcome.UNATTRIBUTABLE)
        }

        val elapsedSeconds = (reading.timeMillis - since).coerceAtLeast(0L) / 1000L
        if (delta > MAX_BURST_STEPS + elapsedSeconds * MAX_STEPS_PER_SECOND) {
            return TrackerResult(next, 0, Outcome.IMPLAUSIBLE)
        }

        val credited = delta.toInt()
        return TrackerResult(next.copy(dailySteps = next.dailySteps + credited), credited, Outcome.COUNTED)
    }

    companion object {
        /** Faster than any sprinter's cadence. */
        const val MAX_STEPS_PER_SECOND = 5L

        /** Step chips hold back the first steps of a walk and then report them all at once. */
        const val MAX_BURST_STEPS = 200L

        /** Boot time is derived from two clocks, so allow for some jitter. */
        const val BOOT_TIME_SLACK_MS = 60_000L
    }
}
