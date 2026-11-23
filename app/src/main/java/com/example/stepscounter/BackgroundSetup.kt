package com.example.stepscounter

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * Settings the user has to change so the phone's battery saver doesn't stop tracking.
 * Xiaomi (MIUI / HyperOS) also blocks restarts after a kill or reboot unless Autostart is on.
 */
object BackgroundSetup {
    private const val PREFS_NAME = "setup_prefs"
    private const val KEY_AUTOSTART_CONFIRMED = "autostart_confirmed"

    /** MIUI's private app-op for Autostart. */
    private const val MIUI_OP_AUTO_START = 10008

    val isXiaomi: Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    fun isBatteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /** True/false on Xiaomi when it can be read, null when unknown; always true elsewhere. */
    @SuppressLint("DiscouragedPrivateApi")
    fun isAutostartAllowed(context: Context): Boolean? {
        if (!isXiaomi) return true
        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val checkOp = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            checkOp.invoke(appOps, MIUI_OP_AUTO_START, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            null
        }
    }

    fun isAutostartConfirmed(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_AUTOSTART_CONFIRMED, false)

    fun confirmAutostart(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_AUTOSTART_CONFIRMED, true) }
    }

    /** Asks the system to exempt the app from battery optimisation (one-tap dialog). */
    @SuppressLint("BatteryLife")
    fun requestUnrestrictedBattery(context: Context) {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        if (!tryStart(context, request) && !tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            openAppDetails(context)
        }
    }

    fun openAutostartSettings(context: Context) {
        val autostart = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        if (!tryStart(context, autostart)) openAppDetails(context)
    }

    fun openAppDetails(context: Context): Boolean =
        tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
