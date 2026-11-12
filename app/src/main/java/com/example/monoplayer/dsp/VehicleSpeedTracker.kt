package com.example.monoplayer.dsp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Intelligent Vehicle Speed & Tunnel Dead-Reckoning Tracker for MonoPlayer.
 *
 * 1. Normal driving: Queries low-power GPS updates (2.5s interval) to measure true vehicle speed.
 * 2. Tunnels / Underground: Automatically switches to accelerometer Dead-Reckoning
 *    (gravity-removed linear acceleration leaky integration + road vibration energy sensing + ZUPT stop detection)
 *    to maintain smooth noise compensation without runaway drift.
 * 3. Maps speed to [LoudnessConfig.carCabinIntensity] scaled by [LoudnessConfig.speedCompensationFactor].
 */
class VehicleSpeedTracker(private val context: Context) : LocationListener, SensorEventListener {

    companion object {
        private const val GPS_MIN_TIME_MS = 2500L
        private const val GPS_MIN_DISTANCE_M = 4.0f
        private const val GPS_TIMEOUT_MS = 4500L
        private const val VIBRATION_STOP_THRESHOLD = 0.08f // Below this -> engine idle / stationary vehicle

        /**
         * Calculates auto cabin intensity (0.0 .. 2.0) given current speed, reference cruising speed,
         * and the user-selected compensation factor.
         * Standard factor (1.0) reaches 1.0 at reference speed; Max factor (2.0) reaches 2.0.
         */
        fun calculateIntensity(
            speedKmh: Float,
            speedRefKmh: Float,
            compensationFactor: Float
        ): Float {
            if (RustNativeBridge.isAvailable) {
                return RustNativeBridge.nativeVehicleTrackerCalcIntensity(
                    speedKmh,
                    speedRefKmh,
                    compensationFactor
                )
            }
            val validRef = speedRefKmh.coerceIn(40f, 150f)
            val normalizedRatio = (speedKmh.coerceAtLeast(0f) / validRef).coerceIn(0f, 1f)
            val scaled = normalizedRatio * compensationFactor.coerceIn(0.2f, 2.0f)
            return scaled.coerceIn(0f, 2f)
        }

        /**
         * Checks whether either FINE or COARSE location permission is currently granted.
         */
        fun hasLocationPermission(context: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            return fine || coarse
        }
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val linearAccelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile var isTracking = false
        private set

    // Speed states
    private var nativeHandle: Long = 0L
    private val nativeOut2 = FloatArray(2)

    private var lastGpsTimestampMs: Long = 0L
    private var lastGpsSpeedKmh: Float = 0f
    private var estimatedSpeedKmh: Float = 0f
    private var smoothedSpeedKmh: Float = 0f

    // IMU dead-reckoning state
    private var lastSensorTimestampNs: Long = 0L
    private var vibrationHighPass = 0f
    private var vibrationEnergyRms = 0f
    private var forwardAccelLp = 0f

    init {
        if (RustNativeBridge.isAvailable) {
            nativeHandle = RustNativeBridge.nativeVehicleTrackerCreate()
        }
    }

    protected fun finalize() {
        if (nativeHandle != 0L) {
            RustNativeBridge.nativeVehicleTrackerDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    @Synchronized
    fun startTracking() {
        if (isTracking) return
        if (!hasLocationPermission(context)) {
            DspStateHolder.updateVehicleSpeed(0f, SpeedTrackingSource.OFF)
            return
        }

        isTracking = true
        lastGpsTimestampMs = 0L
        lastGpsSpeedKmh = 0f
        estimatedSpeedKmh = 0f
        smoothedSpeedKmh = 0f
        lastSensorTimestampNs = 0L
        vibrationHighPass = 0f
        vibrationEnergyRms = 0f
        forwardAccelLp = 0f

        // Register GPS updates
        try {
            if (locationManager != null) {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        GPS_MIN_TIME_MS,
                        GPS_MIN_DISTANCE_M,
                        this
                    )
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        GPS_MIN_TIME_MS * 2,
                        GPS_MIN_DISTANCE_M * 2,
                        this
                    )
                }
            }
        } catch (_: SecurityException) {
        }

        // Register Accelerometer for tunnel compensation
        linearAccelSensor?.let { sensor ->
            sensorManager?.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL // ~20-50 Hz, minimal battery consumption
            )
        }

        DspStateHolder.updateVehicleSpeed(0f, SpeedTrackingSource.GPS)
    }

    @Synchronized
    fun stopTracking() {
        if (!isTracking) return
        isTracking = false

        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {
        }

        DspStateHolder.updateVehicleSpeed(0f, SpeedTrackingSource.OFF)
    }

    // --- LocationListener Callbacks ---

    override fun onLocationChanged(location: Location) {
        if (!isTracking) return
        val nowMs = SystemClock.elapsedRealtime()
        lastGpsTimestampMs = nowMs

        val speedMs = if (location.hasSpeed()) location.speed else 0f
        val speedKmh = (speedMs * 3.6f).coerceAtLeast(0f)
        lastGpsSpeedKmh = speedKmh
        estimatedSpeedKmh = speedKmh

        if (nativeHandle != 0L) {
            val smoothed = RustNativeBridge.nativeVehicleTrackerOnGps(nativeHandle, speedKmh, nativeOut2)
            smoothedSpeedKmh = smoothed
            applyAutoLoudness(smoothed, SpeedTrackingSource.GPS)
        } else {
            updateAutoLoudness(speedKmh, SpeedTrackingSource.GPS)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // --- SensorEventListener Callbacks (Tunnel Dead-Reckoning) ---

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isTracking || event == null) return
        val nowNs = event.timestamp
        if (lastSensorTimestampNs == 0L) {
            lastSensorTimestampNs = nowNs
            return
        }
        val dtSec = ((nowNs - lastSensorTimestampNs) / 1_000_000_000f).coerceIn(0.005f, 0.20f)
        lastSensorTimestampNs = nowNs

        val nowMs = SystemClock.elapsedRealtime()
        val gpsAgeMs = if (lastGpsTimestampMs > 0L) nowMs - lastGpsTimestampMs else Long.MAX_VALUE
        val isGpsLost = gpsAgeMs > GPS_TIMEOUT_MS

        val ax = event.values.getOrNull(0) ?: 0f
        val ay = event.values.getOrNull(1) ?: 0f
        val az = event.values.getOrNull(2) ?: 0f

        if (nativeHandle != 0L) {
            val smoothed = RustNativeBridge.nativeVehicleTrackerOnSensor(
                nativeHandle,
                ax,
                ay,
                az,
                dtSec,
                isGpsLost,
                nativeOut2
            )
            smoothedSpeedKmh = smoothed
            val srcCode = nativeOut2[1].toInt()
            val source = when (srcCode) {
                1 -> SpeedTrackingSource.GPS
                2 -> SpeedTrackingSource.TUNNEL_IMU
                3 -> SpeedTrackingSource.STOPPED
                else -> SpeedTrackingSource.OFF
            }
            if (isGpsLost) {
                applyAutoLoudness(smoothed, source)
            }
            return
        }

        val totalNorm = sqrt(ax * ax + ay * ay + az * az)

        // 10-40 Hz Road Vibration Energy (HPF)
        val alphaHp = 0.85f
        vibrationHighPass = alphaHp * (vibrationHighPass + totalNorm - forwardAccelLp)
        forwardAccelLp += 0.15f * (totalNorm - forwardAccelLp)
        val sampleVibEnergy = abs(vibrationHighPass)
        vibrationEnergyRms = vibrationEnergyRms * 0.96f + sampleVibEnergy * 0.04f

        if (!isGpsLost) {
            // While GPS is active, periodically align the baseline
            return
        }

        // --- Tunnel Dead-Reckoning Mode ---
        val source: SpeedTrackingSource
        if (vibrationEnergyRms < VIBRATION_STOP_THRESHOLD) {
            // Stationary stop inside tunnel (ZUPT: Zero Velocity Update)
            estimatedSpeedKmh = 0f
            source = SpeedTrackingSource.STOPPED
        } else {
            // In motion: Leaky integration toward anchor speed
            val leakFactor = exp(-dtSec / 20.0f) // 20s time constant to resist slope drift
            val targetAnchor = max(20f, lastGpsSpeedKmh)
            estimatedSpeedKmh = targetAnchor * (1f - leakFactor) + estimatedSpeedKmh * leakFactor
            source = SpeedTrackingSource.TUNNEL_IMU
        }

        updateAutoLoudness(estimatedSpeedKmh, source)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun applyAutoLoudness(smoothedSpeed: Float, source: SpeedTrackingSource) {
        DspStateHolder.updateVehicleSpeed(smoothedSpeed, source)

        val cfg = DspStateHolder.loudnessConfig
        if (cfg.mode == LoudnessMode.NOISY_CAR && cfg.autoSpeedEnabled) {
            val autoIntensity = calculateIntensity(
                speedKmh = smoothedSpeed,
                speedRefKmh = cfg.speedReferenceKmh,
                compensationFactor = cfg.speedCompensationFactor
            )
            if (abs(cfg.carCabinIntensity - autoIntensity) > 0.01f) {
                DspStateHolder.updateLoudnessConfig(cfg.copy(carCabinIntensity = autoIntensity))
            }
        }
    }

    private fun updateAutoLoudness(rawSpeedKmh: Float, source: SpeedTrackingSource) {
        // Smooth speed with ~2.0s time constant to prevent zipper / sudden jumps
        val alphaSpeed = 0.18f
        smoothedSpeedKmh += alphaSpeed * (rawSpeedKmh - smoothedSpeedKmh)
        smoothedSpeedKmh = smoothedSpeedKmh.coerceAtLeast(0f)

        applyAutoLoudness(smoothedSpeedKmh, source)
    }
}
