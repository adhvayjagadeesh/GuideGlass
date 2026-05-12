package com.impairedvision.guideglass.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs

class SensorRepository(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)

    private var lastAccelTime: Long = 0
    private val lastAccelValues = FloatArray(3)
    private val MOVEMENT_THRESHOLD = 0.5f

    private val _isMoving = MutableStateFlow(false)
    val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()

    private val _compassBearing = MutableStateFlow(0f)
    val compassBearing: StateFlow<Float> = _compassBearing.asStateFlow()

    fun getSensorDataFlow(): Flow<Unit> = callbackFlow {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)

                        val currentTime = System.currentTimeMillis()
                        if ((currentTime - lastAccelTime) > 200) {
                            val dx = abs(event.values[0] - lastAccelValues[0])
                            val dy = abs(event.values[1] - lastAccelValues[1])
                            val dz = abs(event.values[2] - lastAccelValues[2])
                            val movement = dx + dy + dz
                            _isMoving.value = movement > MOVEMENT_THRESHOLD
                            System.arraycopy(event.values, 0, lastAccelValues, 0, event.values.size)
                            lastAccelTime = currentTime
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
                    }
                }
                updateCompassBearing()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        magnetometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }

    private fun updateCompassBearing() {
        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)

        val success = SensorManager.getRotationMatrix(
            rotationMatrix, null, accelerometerReading, magnetometerReading
        )
        if (success) {
            // Remap coordinate system for a portrait-held phone
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                remappedMatrix
            )
            SensorManager.getOrientation(remappedMatrix, orientationAngles)
            val bearing = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360) % 360).toFloat()
            _compassBearing.value = bearing
        }
    }
}
