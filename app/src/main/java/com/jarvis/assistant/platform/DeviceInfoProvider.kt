package com.jarvis.assistant.platform

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.app.ActivityManager

data class DeviceSnapshot(
    val device: String,
    val androidVersion: String,
    val batteryPercent: Int?,
    val availableRamMb: Long?
)

class DeviceInfoProvider(private val context: Context) {
    fun snapshot(): DeviceSnapshot {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        return DeviceSnapshot(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            batteryPercent = battery,
            availableRamMb = memoryInfo.availMem / (1024L * 1024L)
        )
    }
}
