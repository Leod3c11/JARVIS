package com.jarvis.assistant.platform

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

data class DeviceSnapshot(
    val device: String,
    val soc: String,
    val androidVersion: String,
    val batteryPercent: Int?,
    val availableRamMb: Long?,
    val totalRamMb: Long,
    val cpuCores: Int,
    val abis: String,
    val openGles: String,
    val vulkan: String,
    val sensorCount: Int,
    val thermal: String
)

class DeviceInfoProvider(private val context: Context) {
    fun snapshot(): DeviceSnapshot {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val glesVersion = activityManager.deviceConfigurationInfo?.glEsVersion ?: "indisponível"
        val features = context.packageManager.systemAvailableFeatures
        val vulkanLevel = features.firstOrNull {
            it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL
        }?.version
        val vulkanVersion = features.firstOrNull {
            it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
        }?.version
        val vulkan = when {
            vulkanVersion != null -> "Disponível (nível $vulkanLevel, versão 0x${vulkanVersion.toString(16)})"
            vulkanLevel != null -> "Disponível (nível $vulkanLevel)"
            else -> "Não anunciado pelo Android"
        }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Leve"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderado"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severo"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Crítico"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergência"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Desligamento iminente"
                else -> "Desconhecido"
            }
        } else "API indisponível"

        return DeviceSnapshot(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL
                .takeIf { it.isNotBlank() } ?: Build.HARDWARE else Build.HARDWARE,
            androidVersion = Build.VERSION.RELEASE,
            batteryPercent = battery,
            availableRamMb = memoryInfo.availMem / (1024L * 1024L),
            totalRamMb = memoryInfo.totalMem / (1024L * 1024L),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            abis = Build.SUPPORTED_ABIS.joinToString(", "),
            openGles = glesVersion,
            vulkan = vulkan,
            sensorCount = sensorManager.getSensorList(android.hardware.Sensor.TYPE_ALL).size,
            thermal = thermal
        )
    }
}
