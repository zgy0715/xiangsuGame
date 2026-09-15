package com.example.xiangsugame.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 摇一摇检测器 —— 基于加速度传感器(TYPE_ACCELEROMETER)。
 *
 * 监听设备加速度变化,当合加速度超过阈值且持续时间足够时触发 [onShake] 回调。
 * 带 1.5 秒防抖,避免一次摇晃触发多次。
 *
 * 用法:
 * ```
 * val shake = ShakeDetector(context) { /* 重置棋盘 */ }
 * shake.start()
 * // ... onDestroy: shake.stop()
 * ```
 */
class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastShakeTime = 0L

    /** 是否可用(设备有加速度传感器)。 */
    val isAvailable: Boolean get() = accelerometer != null

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val now = System.currentTimeMillis()
        if (now - lastTime < SAMPLE_INTERVAL_MS) return

        val dt = now - lastTime
        if (dt == 0L) {
            lastTime = now
            lastX = event.values[0]
            lastY = event.values[1]
            lastZ = event.values[2]
            return
        }

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // 合加速度变化量(减去重力影响的近似)
        val delta = kotlin.math.abs(x + y + z - lastX - lastY - lastZ) / dt * 10000

        if (delta > SHAKE_THRESHOLD && now - lastShakeTime > SHAKE_COOLDOWN_MS) {
            lastShakeTime = now
            onShake()
        }

        lastTime = now
        lastX = x
        lastY = y
        lastZ = z
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val SAMPLE_INTERVAL_MS = 100L
        private const val SHAKE_THRESHOLD = 800f
        private const val SHAKE_COOLDOWN_MS = 1500L
    }
}
