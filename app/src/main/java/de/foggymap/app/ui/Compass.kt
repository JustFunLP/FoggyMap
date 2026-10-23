package de.foggymap.app.ui

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Viewing direction from the rotation-vector sensor (true north, smoothed). Phone held flat: the
 * direction the top edge points to. Phone held upright (looking through it, car holder): the
 * direction the back camera faces. Runs only while the map is visible.
 */
class Compass(context: Context, private val onChange: (Double) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)
    private val remapped = FloatArray(9)
    private val o = FloatArray(3)
    private var sx = 0.0; private var sy = 0.0
    private var init = false
    private var upright = false
    private var declination = 0.0
    private var running = false

    val available get() = sensor != null

    fun setLocation(lat: Double, lon: Double, alt: Double) {
        declination = runCatching { GeomagneticField(lat.toFloat(), lon.toFloat(), alt.toFloat(), System.currentTimeMillis()).declination.toDouble() }.getOrDefault(0.0)
    }

    fun start() {
        if (running || sensor == null) return
        running = true
        init = false
        sm?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!running) return
        running = false
        sm?.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // r[8]: how much the screen faces up. With hysteresis so the mode does not flicker at ~50°.
        val up = abs(r[8])
        if (upright && up > 0.72f) upright = false else if (!upright && up < 0.55f) upright = true
        val m = if (upright) { SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped); remapped } else r
        SensorManager.getOrientation(m, o)
        val az = Math.toRadians(Math.toDegrees(o[0].toDouble()) + declination)
        if (!init) { sx = cos(az); sy = sin(az); init = true }
        else { sx += 0.15 * (cos(az) - sx); sy += 0.15 * (sin(az) - sy) }
        onChange((Math.toDegrees(atan2(sy, sx)) + 360.0) % 360.0)
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
}
