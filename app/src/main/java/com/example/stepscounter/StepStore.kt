package com.example.stepscounter

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

data class StepData(
    val dailySteps: Int,
    val dailyGoal: Int,
    /** The last 7 days, oldest first, ending with today. */
    val week: List<DayTotal> = emptyList(),
)

/**
 * Process-wide owner of today's step total. The service feeds it sensor readings and the UI reads
 * [data]. Main thread only.
 */
@MainThread
object StepStore {
    const val DEFAULT_GOAL = 10_000

    /**
     * Disk writes are batched. Losing unsaved progress to a kill is harmless: the next reading
     * recovers the difference from the hardware counter.
     */
    private const val SAVE_DELAY_MS = 30_000L

    private const val PREFS_NAME = "step_prefs"
    private const val KEY_DAY = "saved_date"
    private const val KEY_DAILY_STEPS = "daily_steps"
    private const val KEY_GOAL = "daily_goal"
    private const val KEY_LAST_COUNTER = "last_counter"
    private const val KEY_LAST_READING = "last_reading_ms"
    private const val KEY_BOOT_COUNT = "boot_count"
    private const val KEY_HISTORY = "history"

    // Written by the previous version of the app.
    private const val LEGACY_LAST_SENSOR_VALUE = "last_sensor_value"
    private const val LEGACY_LAST_EVENT_MS = "last_event_timestamp_ms"
    private val LEGACY_KEYS = listOf(
        LEGACY_LAST_SENSOR_VALUE,
        LEGACY_LAST_EVENT_MS,
        "last_event_timestamp",
        "last_valid_step_timestamp",
        "consecutive_steps",
        "last_detector_timestamp",
    )

    private lateinit var prefs: SharedPreferences
    private val tracker = StepTracker()
    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveNow() }
    private var state = TrackerState()
    private var history: Map<LocalDate, Int> = emptyMap()
    private var dirty = false
    private var saveScheduled = false

    private val _data = MutableStateFlow(StepData(0, DEFAULT_GOAL))
    val data: StateFlow<StepData> = _data.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        state = load()
        history = StepHistory.decode(prefs.getString(KEY_HISTORY, null))
        publish()
        refreshDay()
    }

    fun onReading(reading: CounterReading): TrackerResult {
        val dayBefore = state.day
        val result = tracker.onReading(state, reading)
        moveTo(result.state)
        markDirty(saveImmediately = state.day != dayBefore)
        publish()
        return result
    }

    /** Shows a fresh day at zero after midnight, even before the first step of the day. */
    fun refreshDay(nowMillis: Long = System.currentTimeMillis()) {
        val rolled = tracker.rollover(state, nowMillis)
        if (rolled == state) return
        moveTo(rolled)
        markDirty(saveImmediately = true)
        publish()
    }

    fun setDailyGoal(goal: Int) {
        prefs.edit { putInt(KEY_GOAL, goal) }
        publish()
    }

    fun saveNow() {
        handler.removeCallbacks(saveRunnable)
        saveScheduled = false
        if (!dirty) return
        dirty = false
        prefs.edit {
            putString(KEY_DAY, state.day?.toString())
            putInt(KEY_DAILY_STEPS, state.dailySteps)
            putLong(KEY_LAST_COUNTER, state.lastCounter)
            putLong(KEY_LAST_READING, state.lastReadingMillis)
            putInt(KEY_BOOT_COUNT, state.bootCount)
            putString(KEY_HISTORY, StepHistory.encode(history))
            LEGACY_KEYS.forEach { remove(it) }
        }
    }

    /** Applies a new state, archiving the finished day's total when the day rolls over. */
    private fun moveTo(next: TrackerState) {
        val finished = state.day
        val today = next.day
        if (finished != null && today != null && today.isAfter(finished)) {
            history = StepHistory.record(history, finished, state.dailySteps, today)
        }
        state = next
    }

    private fun markDirty(saveImmediately: Boolean) {
        dirty = true
        if (saveImmediately) {
            saveNow()
        } else if (!saveScheduled) {
            saveScheduled = true
            handler.postDelayed(saveRunnable, SAVE_DELAY_MS)
        }
    }

    private fun publish() {
        val today = state.day ?: LocalDate.now()
        _data.value = StepData(
            dailySteps = state.dailySteps,
            dailyGoal = prefs.getInt(KEY_GOAL, DEFAULT_GOAL),
            week = StepHistory.lastDays(history, today, state.dailySteps),
        )
    }

    private fun load(): TrackerState {
        val day = prefs.getString(KEY_DAY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val lastReading = prefs.getLong(KEY_LAST_READING, prefs.getLong(LEGACY_LAST_EVENT_MS, 0L))
        val lastCounter = when {
            // Without a time we can't tell which boot or day the old value came from.
            lastReading <= 0L -> TrackerState.NO_BASELINE
            prefs.contains(KEY_LAST_COUNTER) -> prefs.getLong(KEY_LAST_COUNTER, TrackerState.NO_BASELINE)
            else -> prefs.getInt(LEGACY_LAST_SENSOR_VALUE, -1).toLong()
        }
        return TrackerState(
            day = day,
            dailySteps = prefs.getInt(KEY_DAILY_STEPS, 0),
            lastCounter = lastCounter,
            lastReadingMillis = lastReading,
            bootCount = prefs.getInt(KEY_BOOT_COUNT, TrackerState.UNKNOWN),
        )
    }
}
