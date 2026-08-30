package com.hien.rtkmultidevice.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.*
import kotlin.math.roundToInt

/**
 * rememberDeviceHeading — đọc hướng thiết bị từ sensor từ tính/gia tốc.
 *
 * @return Góc hướng của thiết bị (độ, 0=Bắc, 90=Đông, 180=Nam, 270=Tây).
 *         Null nếu thiết bị không có sensor.
 *
 * Dùng ROTATION_VECTOR (ưu tiên) → chính xác hơn và ổn định hơn.
 * Fallback: MAGNETIC_FIELD + ACCELEROMETER.
 */
@Composable
fun rememberDeviceHeading(context: Context): Float? {
    var heading by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(SensorManager::class.java)

        // Ưu tiên dùng ROTATION_VECTOR
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationSensor != null) {
            val listener = object : SensorEventListener {
                private val rotationMatrix  = FloatArray(9)
                private val orientationAngles = FloatArray(3)

                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    // orientationAngles[0] = azimuth (radians), âm = tây, dương = đông
                    val degrees = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                    heading = ((degrees + 360f) % 360f)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            sensorManager.registerListener(
                listener,
                rotationSensor,
                SensorManager.SENSOR_DELAY_UI
            )

            onDispose {
                sensorManager.unregisterListener(listener)
            }
        } else {
            // Fallback: Accelerometer + Magnetic field
            val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val magSensor   = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

            if (accelSensor == null || magSensor == null) {
                heading = null
                onDispose {}
            } else {
                val gravity   = FloatArray(3)
                val geomag    = FloatArray(3)
                val rotation  = FloatArray(9)
                val inclination = FloatArray(9)
                val orientation = FloatArray(3)

                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        when (event.sensor.type) {
                            Sensor.TYPE_ACCELEROMETER  -> event.values.copyInto(gravity)
                            Sensor.TYPE_MAGNETIC_FIELD -> event.values.copyInto(geomag)
                        }
                        if (SensorManager.getRotationMatrix(rotation, inclination, gravity, geomag)) {
                            SensorManager.getOrientation(rotation, orientation)
                            val degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
                            heading = ((degrees + 360f) % 360f)
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }

                sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_UI)
                sensorManager.registerListener(listener, magSensor,   SensorManager.SENSOR_DELAY_UI)

                onDispose {
                    sensorManager.unregisterListener(listener)
                }
            }
        }
    }

    return heading
}
