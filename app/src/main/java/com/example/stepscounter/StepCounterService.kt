package com.example.stepscounter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

class StepCounterService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private var stepDetector: Sensor? = null
    private var accelerometer: Sensor? = null // SHAKE GUARD
    private var hasStepDetector = false
    private lateinit var sharedPreferences: SharedPreferences
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    // In-memory timestamp for detector validation
    private var lastDetectorTimestamp: Long = 0L
    // In-memory timestamp for SHAKE GUARD
    private var lastShakeTimestamp: Long = 0L

    private val _stepData = MutableStateFlow(StepData(0, 10000))
    val stepData: StateFlow<StepData> = _stepData.asStateFlow()

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): StepCounterService = this@StepCounterService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        
        // Acquire WakeLock
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "StepCounterService::WakeLock")
        wakeLock?.acquire()
        android.util.Log.d("StepCounterService", "WakeLock acquired")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        hasStepDetector = (stepDetector != null)
        
        sharedPreferences = getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
        // Restore detector timestamp just in case
        lastDetectorTimestamp = sharedPreferences.getLong("last_detector_timestamp", 0L)

        createNotificationChannel()
        startForegroundService()

        loadData()
        
        registerSensor()

        // Heartbeat / Watchdog
        val timer = java.util.Timer()
        timer.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                val now = System.currentTimeMillis()
                val lastUpdate = sharedPreferences.getLong("last_event_timestamp", now)
                
                android.util.Log.d("StepCounterService", "Heartbeat. Last event: ${now - lastUpdate}ms ago")
                
                // If no steps for 60 seconds, kick the sensor
                if (now - lastUpdate > 60000) {
                    android.util.Log.d("StepCounterService", "Watchdog: Re-registering sensor...")
                    sensorManager.unregisterListener(this@StepCounterService)
                    registerSensor()
                    // Update timestamp to avoid kicking constantly if user is just sitting
                    sharedPreferences.edit().putLong("last_event_timestamp", now).apply()
                }
            }
        }, 5000, 5000)
    }

    private fun registerSensor() {
        if (stepSensor == null) {
            android.util.Log.e("StepCounterService", "Step Counter Sensor is NULL!")
        } else {
            android.util.Log.d("StepCounterService", "Registering Step Counter...")
            val result = sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_GAME, 0)
            android.util.Log.d("StepCounterService", "Step Counter Register result: $result")
        }
        
        if (stepDetector != null) {
             android.util.Log.d("StepCounterService", "Registering Step Detector...")
             val resultDetector = sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_GAME, 0)
             android.util.Log.d("StepCounterService", "Step Detector Register result: $resultDetector")
        } else {
             android.util.Log.w("StepCounterService", "No Step Detector found! Falling back to Counter only.")
        }
        
        if (accelerometer != null) {
            android.util.Log.d("StepCounterService", "Registering Accelerometer (Shake Guard)...")
            val resultAcc = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI, 0) // UI speed is enough for shake det (60ms)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        // Redeliver intent if killed
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val now = System.currentTimeMillis()
            
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                // SHAKE GUARD LOGIC
                val x = it.values[0]
                val y = it.values[1]
                val z = it.values[2]
                val gForce = Math.sqrt((x * x + y * y + z * z).toDouble())
                
                // 20 m/s^2 is approx 2G. Violent shaking exceeds this. Walking is usually 1.2-1.5G.
                if (gForce > 20.0) {
                     android.util.Log.d("StepCounterService", "SHAKE GUARD: High G-Force Detected! ($gForce). Blocking steps for 2s.")
                     lastShakeTimestamp = now
                }
            } else if (it.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                // IMPACT DETECTED!
                lastDetectorTimestamp = now
            } else if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                // COUNTER UPDATE
                
                // SHAKE GUARD CHECK
                if (now - lastShakeTimestamp < 2000) {
                     android.util.Log.d("StepCounterService", "Anti-Shake: Rejected Counter update. Shake Guard ACTIVE.")
                     return // EXIT
                }
                
                // VALIDATION:
                // Re-enabled strict check for user's specific device (Redmi Note 14).
                // This ensures maximum anti-shake protection for this phone, even if it limits compatibility.
                if (hasStepDetector) {
                    val timeSinceImpact = now - lastDetectorTimestamp
                    // 3000ms window. If > 3s since last impact, ignore this counter update.
                    if (timeSinceImpact > 3000) {
                        android.util.Log.d("StepCounterService", "Anti-Shake: Rejected Counter update. No impact for ${timeSinceImpact}ms")
                        return // EXIT
                    }
                }
                
                val rawSteps = it.values[0].toInt()
                // Update timestamp for watchdog
                sharedPreferences.edit().putLong("last_event_timestamp", now).apply()
                handleStepUpdate(rawSteps)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun handleStepUpdate(currentSensorValue: Int) {
        val currentDate = LocalDate.now().toString()
        val savedDate = sharedPreferences.getString("saved_date", "1970-01-01")!!
        var dailySteps = sharedPreferences.getInt("daily_steps", 0)
        var lastSensorValue = sharedPreferences.getInt("last_sensor_value", -1) 
        var lastEventTime = sharedPreferences.getLong("last_event_timestamp_ms", 0L) // For speed calc
        var lastValidStepTime = sharedPreferences.getLong("last_valid_step_timestamp", 0L) // For debounce reset
        var consecutiveSteps = sharedPreferences.getInt("consecutive_steps", 0) // Buffer

        val now = System.currentTimeMillis()

        // Day change check
        if (currentDate != savedDate) {
             dailySteps = 0
             lastSensorValue = currentSensorValue
             consecutiveSteps = 0
        }

        var diff = 0
        
        if (lastSensorValue == -1) {
            // First run
            diff = 0
            lastSensorValue = currentSensorValue
            lastEventTime = now
            lastValidStepTime = now
        } else {
            diff = currentSensorValue - lastSensorValue
            
            if (diff < 0) {
                // Reboot
                diff = currentSensorValue
            } else if (diff > 0) {
                // 1. SPEED FILTER
                val timeDelta = now - lastEventTime
                var isSpeedOk = true
                
                if (timeDelta > 0) {
                    val speed = (diff.toFloat() / timeDelta.toFloat()) * 1000f
                    // Strict limit: 3.0 steps/sec (180 steps/min is a high cadence run)
                    if (speed > 3.0f) {
                        android.util.Log.d("StepCounterService", "Anti-Shake: Speed too high ($speed). Rejected $diff steps.")
                        isSpeedOk = false
                    }
                }
                
                // CRITICAL: Update lastEventTime regardless of validity to track cadence correctly
                lastEventTime = now

                if (isSpeedOk) {
                    // 2. STEP BUFFERING (Debounce)
                    // If too much time passed since last VALID step, reset buffer (user stopped walking)
                    // 3000ms = 3 seconds gap allowed
                    if (now - lastValidStepTime > 3000) {
                        android.util.Log.d("StepCounterService", "Anti-Shake: Reset buffer (allowance exceeded)")
                        consecutiveSteps = 0
                    }

                    // Accumulate current batch
                    val prevConsecutive = consecutiveSteps
                    consecutiveSteps += diff
                    lastValidStepTime = now

                    // 3. APPLY TO TOTAL
                    // Rule: Only count steps if we have at least 10 consecutive steps
                    if (consecutiveSteps >= 10) {
                        var stepsToAdd = 0
                        
                        if (prevConsecutive < 10) {
                            // TRANSITION: We just crossed the threshold.
                            // Add the entire buffer (the first 9 steps + current batch)
                            stepsToAdd = consecutiveSteps
                            android.util.Log.d("StepCounterService", "Anti-Shake: Threshold reached! Committing buffer: $stepsToAdd")
                        } else {
                            // Already walking, just add the new difference
                            stepsToAdd = diff
                            android.util.Log.d("StepCounterService", "Anti-Shake: Walking... adding $stepsToAdd")
                        }
                        
                        dailySteps += stepsToAdd
                    } else {
                        android.util.Log.d("StepCounterService", "Anti-Shake: Buffering... ($consecutiveSteps/10)")
                    }
                } else {
                    // Speed reject: treat as noise, do not increment buffer
                    diff = 0
                }
            }
        }
        
        // Sanity check
        if (diff > 10000) {
             diff = 0 // Ignore massive spikes
        }

        // Always save state
        sharedPreferences.edit()
             .putInt("last_sensor_value", currentSensorValue)
             .putInt("daily_steps", dailySteps)
             .putString("saved_date", currentDate)
             .putLong("last_event_timestamp_ms", lastEventTime)
             .putLong("last_valid_step_timestamp", lastValidStepTime)
             .putInt("consecutive_steps", consecutiveSteps)
             .apply()

        // Update UI
        val dailyGoal = sharedPreferences.getInt("daily_goal", 10000)
        _stepData.value = StepData(dailySteps, dailyGoal)
        updateNotification()
    }
    
    private fun loadData() {
        val dailySteps = sharedPreferences.getInt("daily_steps", 0)
        val dailyGoal = sharedPreferences.getInt("daily_goal", 10000)
        _stepData.value = StepData(dailySteps, dailyGoal)
    }

    fun setDailyGoal(newGoal: Int) {
        sharedPreferences.edit().putInt("daily_goal", newGoal).apply()
        // Update flow
        val currentSteps = _stepData.value.dailySteps
        _stepData.value = StepData(currentSteps, newGoal)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "step_counter_channel",
                "Step Counter Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, "step_counter_channel")
            .setContentTitle("Steps Counter Active")
            .setContentText("Counting steps: ${_stepData.value.dailySteps}")
            .setSmallIcon(R.mipmap.ic_launcher) // Ensure this icon exists
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.notify(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        wakeLock?.release()
        android.util.Log.d("StepCounterService", "WakeLock released")
    }
}

data class StepData(val dailySteps: Int, val dailyGoal: Int)
