package com.example.stepscounter

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Keeps the step counter registered and shows today's total in a notification.
 *
 * Battery: TYPE_STEP_COUNTER is counted by the phone's low-power sensor hub, and it is a non-wake-up
 * sensor, so this service never keeps the CPU awake. While the phone sleeps the hub keeps counting;
 * the next event carries the full total.
 */
class StepCounterService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var receiverRegistered = false
    private var hadReadingSinceStart = false
    private var bootCount = TrackerState.UNKNOWN

    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private lateinit var contentIntent: PendingIntent
    private var shownData: StepData? = null
    private var lastNotifyUptime = 0L
    private val notifyRunnable = Runnable { showLatestNotification() }

    private val systemEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_SCREEN_ON -> StepStore.refreshDay()

                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SHUTDOWN -> StepStore.saveNow()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        StepStore.init(this)
        contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        createNotificationChannel()
        if (!enterForeground()) {
            stopSelf()
            return
        }

        val manager = getSystemService(SensorManager::class.java)
        val counter = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (counter == null) {
            Log.e(TAG, "This device has no step counter sensor")
            stopSelf()
            return
        }
        bootCount = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, TrackerState.UNKNOWN)
        // On-change sensor: the rate is ignored, and no batching keeps updates instant while the
        // screen is on.
        if (!manager.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL)) {
            Log.e(TAG, "Could not register the step counter")
            stopSelf()
            return
        }
        sensorManager = manager

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        ContextCompat.registerReceiver(this, systemEvents, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true

        scope.launch { StepStore.data.collect { scheduleNotificationUpdate() } }
        Log.i(TAG, "Tracking started (boot #$bootCount)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Each startForegroundService() call must be answered with startForeground().
        if (sensorManager == null || !enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val now = System.currentTimeMillis()
        val reading = CounterReading(
            counter = event.values[0].toLong(),
            timeMillis = eventWallClock(event.timestamp, now),
            bootCount = bootCount,
            bootTimeMillis = now - SystemClock.elapsedRealtime(),
            continuous = hadReadingSinceStart,
        )
        hadReadingSinceStart = true
        val result = StepStore.onReading(reading)
        Log.d(TAG, "counter=${reading.counter} ${result.outcome} +${result.credited} today=${result.state.dailySteps}")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        if (receiverRegistered) {
            unregisterReceiver(systemEvents)
            receiverRegistered = false
        }
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        StepStore.saveNow()
        Log.i(TAG, "Tracking stopped")
        super.onDestroy()
    }

    /** Sensor timestamps are nanoseconds since boot; convert to wall-clock time. */
    private fun eventWallClock(timestampNanos: Long, nowMillis: Long): Long {
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - timestampNanos) / 1_000_000L
        return if (ageMillis in 0..MAX_EVENT_AGE_MS) nowMillis - ageMillis else nowMillis
    }

    private fun enterForeground(): Boolean = try {
        val data = StepStore.data.value
        val notification = buildNotification(data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        shownData = data
        lastNotifyUptime = SystemClock.uptimeMillis()
        true
    } catch (e: Exception) {
        // Missing Physical activity permission, or started from the background where not allowed.
        Log.e(TAG, "Could not start in the foreground", e)
        false
    }

    /** At most one notification update every [NOTIFY_INTERVAL_MS]; each one costs System UI work. */
    private fun scheduleNotificationUpdate() {
        handler.removeCallbacks(notifyRunnable)
        val wait = NOTIFY_INTERVAL_MS - (SystemClock.uptimeMillis() - lastNotifyUptime)
        if (wait <= 0) showLatestNotification() else handler.postDelayed(notifyRunnable, wait)
    }

    private fun showLatestNotification() {
        val data = StepStore.data.value
        if (data == shownData) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(data))
        shownData = data
        lastNotifyUptime = SystemClock.uptimeMillis()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(data: StepData): Notification {
        val format = NumberFormat.getIntegerInstance()
        val steps = data.dailySteps
        val goal = data.dailyGoal.coerceAtLeast(1)
        val text = if (steps >= goal) {
            getString(R.string.notification_goal_reached)
        } else {
            getString(R.string.notification_goal_progress, steps * 100 / goal, format.format(goal))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_steps)
            .setColor(ContextCompat.getColor(this, R.color.pixel_amber))
            .setContentTitle(resources.getQuantityString(R.plurals.notification_steps_today, steps, format.format(steps)))
            .setContentText(text)
            .setProgress(goal, steps.coerceAtMost(goal), false)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "StepCounterService"
        private const val CHANNEL_ID = "step_counter_channel"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFY_INTERVAL_MS = 3_000L
        private const val MAX_EVENT_AGE_MS = 60 * 60 * 1000L

        fun hasActivityPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED

        /** Starts tracking if allowed. Safe to call repeatedly. */
        fun start(context: Context): Boolean {
            if (!hasActivityPermission(context)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, StepCounterService::class.java))
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: started from the background.
                Log.w(TAG, "Not allowed to start tracking right now", e)
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "Not allowed to start tracking", e)
                false
            }
        }
    }
}
