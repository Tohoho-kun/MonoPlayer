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
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Intelligent Vehicle Speed, 3D Holder IMU Dead-Reckoning, and Handheld Detector for MonoPlayer.
 *
 * 1. Normal driving: Queries low-power GPS updates (2.5s interval) to measure true vehicle speed.
 * 2. Tunnels / Underground: Automatically switches to 3D holder-mounted IMU dead-reckoning
 *    (gravity-projected horizontal acceleration + straight-line forward calibration + curve immunity + ZUPT).
 * 3. Handheld pickup detection: Dynamically disables GPS updates when the device is picked up
 *    from the car holder, resetting compensation cleanly and resuming GPS once stabilized back in holder.
 * 4. Maps speed to [LoudnessConfig.carCabinIntensity] scaled by [LoudnessConfig.speedCompensationFactor].
 */
class VehicleSpeedTracker(private val context: Context) : LocationListener, SensorEventListener {

    companion object {
        private const val GPS_MIN_TIME_MS = 1000L
        private const val GPS_MIN_DISTANCE_M = 0.0f
        private const val GPS_TIMEOUT_MS = 12000L
        private const val VIBRATION_STOP_THRESHOLD = 0.08f // Below this -> engine idle / stationary vehicle
        private const val GRAVITY_LPF_TAU_SEC = 0.8f
        private const val GYRO_STALE_NS = 500_000_000L
        private const val HANDHELD_TILT_THRESHOLD_RAD = 0.30f
        private const val HANDHELD_RECOVERY_TILT_RAD = 0.20f
        private const val HANDHELD_ROT_RATE_THRESHOLD = 0.85f
        private const val HANDHELD_RECOVERY_SEC = 2.5f

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
            val safeSpeed = if (speedKmh.isFinite()) speedKmh.coerceAtLeast(0f) else 0f
            val safeRef = if (speedRefKmh.isFinite()) speedRefKmh.coerceIn(40f, 150f) else 80f
            val safeFactor = if (compensationFactor.isFinite()) compensationFactor.coerceIn(0.2f, 2.0f) else 1.0f
            val normalizedRatio = (safeSpeed / safeRef).coerceIn(0f, 1f)
            val scaled = normalizedRatio * safeFactor
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

        private fun mapSourceCode(srcCode: Int): SpeedTrackingSource = when (srcCode) {
            1 -> SpeedTrackingSource.GPS
            2 -> SpeedTrackingSource.TUNNEL_IMU
            3 -> SpeedTrackingSource.STOPPED
            4 -> SpeedTrackingSource.HANDHELD
            else -> SpeedTrackingSource.OFF
        }
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val linearAccelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val rawAccelSensor = if (linearAccelSensor == null || gravitySensor == null) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    } else {
        null
    }
    private val gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile var isTracking = false
        private set

    // Speed & native states
    private var nativeHandle: Long = 0L
    private val nativeOut2 = FloatArray(2)
    private val nativeOut5 = FloatArray(5)

    private var lastGpsTimestampMs: Long = 0L
    private var lastGpsSpeedKmh: Float = 0f
    private var estimatedSpeedKmh: Float = 0f
    private var smoothedSpeedKmh: Float = 0f
    private var lastForwardCalibrated: Boolean = false
    @Volatile private var isGpsPausedForHandheld: Boolean = false

    // Multi-sensor fusion buffers
    private val latestGravity = FloatArray(3)
    private var hasGravity = false
    private val lpfGravity = FloatArray(3)
    private var lastRawAccelTimestampNs: Long = 0L

    private val latestGyro = FloatArray(3)
    private var hasGyroSample = false
    private var lastGyroTimestampNs: Long = 0L

    // IMU dead-reckoning & Kotlin fallback state
    private var lastSensorTimestampNs: Long = 0L
    private var vibrationHighPass = 0f
    private var vibrationEnergyRms = 0f
    private var forwardAccelLp = 0f

    private val fallbackGravityAnchor = FloatArray(3)
    private var hasFallbackAnchor = false
    private val prevGravityHat = FloatArray(3)
    private var hasPrevGravityHat = false
    private var fallbackHandheld = false
    private var fallbackTiltAccumSec = 0f
    private var fallbackSteadyAccumSec = 0f

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
            DspStateHolder.updateVehicleSpeed(0f, SpeedTrackingSource.OFF, forwardCalibrated = false)
            return
        }

        isTracking = true
        lastGpsTimestampMs = 0L
        lastGpsSpeedKmh = 0f
        estimatedSpeedKmh = 0f
        smoothedSpeedKmh = 0f
        lastForwardCalibrated = false
        isGpsPausedForHandheld = false

        hasGravity = false
        latestGravity.fill(0f)
        lpfGravity.fill(0f)
        lastRawAccelTimestampNs = 0L
        hasGyroSample = false
        latestGyro.fill(0f)
        lastGyroTimestampNs = 0L

        lastSensorTimestampNs = 0L
        vibrationHighPass = 0f
        vibrationEnergyRms = 0f
        forwardAccelLp = 0f
        fallbackGravityAnchor.fill(0f)
        hasFallbackAnchor = false
        prevGravityHat.fill(0f)
        hasPrevGravityHat = false
        fallbackHandheld = false
        fallbackTiltAccumSec = 0f
        fallbackSteadyAccumSec = 0f

        if (nativeHandle != 0L) {
            RustNativeBridge.nativeVehicleTrackerReset(nativeHandle)
        }

        registerGpsUpdates()

        // Register IMU sensors for 3D holder tracking & handheld detection
        linearAccelSensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gravitySensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        rawAccelSensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroSensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        DspStateHolder.updateVehicleSpeed(0f, SpeedTrackingSource.GPS, forwardCalibrated = false)
    }

    @Synchronized
    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        isGpsPausedForHandheld = false

        unregisterGpsUpdates()
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {
        }

        if (nativeHandle != 0L) {
            RustNativeBridge.nativeVehicleTrackerReset(nativeHandle)
        }

        DspStateHolder.updateVehicleSpeed(0f, SpeedTrackingSource.OFF, forwardCalibrated = false)
    }

    private fun registerGpsUpdates() {
        try {
            if (locationManager != null && hasLocationPermission(context)) {
                val looper = Looper.getMainLooper()
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        GPS_MIN_TIME_MS,
                        GPS_MIN_DISTANCE_M,
                        this,
                        looper
                    )
                    val lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    if (lastLoc != null && lastLoc.hasSpeed()) {
                        val ageMs = SystemClock.elapsedRealtime() - (lastLoc.elapsedRealtimeNanos / 1_000_000L)
                        if (ageMs in 0L..14_999L) {
                            onLocationChanged(lastLoc)
                        }
                    }
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        GPS_MIN_TIME_MS * 2,
                        GPS_MIN_DISTANCE_M,
                        this,
                        looper
                    )
                }
            }
        } catch (_: SecurityException) {
        }
    }

    private fun unregisterGpsUpdates() {
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    private fun handleHandheldTransition(source: SpeedTrackingSource) {
        if (source == SpeedTrackingSource.HANDHELD) {
            if (!isGpsPausedForHandheld) {
                isGpsPausedForHandheld = true
                unregisterGpsUpdates()
            }
        } else if (isGpsPausedForHandheld) {
            isGpsPausedForHandheld = false
            registerGpsUpdates()
        }
    }

    // --- LocationListener Callbacks ---

    @Synchronized
    override fun onLocationChanged(location: Location) {
        if (!isTracking || isGpsPausedForHandheld) return
        val nowMs = SystemClock.elapsedRealtime()

        val speedMs = if (location.hasSpeed()) location.speed else 0f
        val speedKmh = if (speedMs.isFinite()) (speedMs * 3.6f).coerceAtLeast(0f) else 0f

        if (nativeHandle != 0L) {
            val smoothed = RustNativeBridge.nativeVehicleTrackerOnGps(nativeHandle, speedKmh, nativeOut2)
            val source = mapSourceCode(nativeOut2[1].toInt())
            if (source == SpeedTrackingSource.HANDHELD) {
                handleHandheldTransition(SpeedTrackingSource.HANDHELD)
                applyAutoLoudness(0f, SpeedTrackingSource.HANDHELD, lastForwardCalibrated)
                return
            }
            lastGpsTimestampMs = nowMs
            lastGpsSpeedKmh = speedKmh
            estimatedSpeedKmh = speedKmh
            smoothedSpeedKmh = smoothed
            applyAutoLoudness(smoothed, source, lastForwardCalibrated)
        } else {
            lastGpsTimestampMs = nowMs
            lastGpsSpeedKmh = speedKmh
            estimatedSpeedKmh = speedKmh
            updateAutoLoudness(speedKmh, SpeedTrackingSource.GPS, lastForwardCalibrated)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // --- SensorEventListener Callbacks (9-Axis IMU Dead-Reckoning & Handheld Detection) ---

    @Synchronized
    override fun onSensorChanged(event: SensorEvent?) {
        if (!isTracking || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                latestGravity[0] = event.values.getOrNull(0) ?: 0f
                latestGravity[1] = event.values.getOrNull(1) ?: 0f
                latestGravity[2] = event.values.getOrNull(2) ?: 0f
                hasGravity = true
            }

            Sensor.TYPE_GYROSCOPE -> {
                latestGyro[0] = event.values.getOrNull(0) ?: 0f
                latestGyro[1] = event.values.getOrNull(1) ?: 0f
                latestGyro[2] = event.values.getOrNull(2) ?: 0f
                hasGyroSample = true
                lastGyroTimestampNs = event.timestamp
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val rawX = event.values.getOrNull(0) ?: 0f
                val rawY = event.values.getOrNull(1) ?: 0f
                val rawZ = event.values.getOrNull(2) ?: 0f

                if (lastRawAccelTimestampNs == 0L) {
                    lastRawAccelTimestampNs = event.timestamp
                    lpfGravity[0] = rawX
                    lpfGravity[1] = rawY
                    lpfGravity[2] = rawZ
                    if (gravitySensor == null) {
                        latestGravity[0] = rawX
                        latestGravity[1] = rawY
                        latestGravity[2] = rawZ
                        hasGravity = true
                    }
                    return
                }

                val rawDt = ((event.timestamp - lastRawAccelTimestampNs) / 1_000_000_000f)
                    .coerceIn(0.005f, 0.20f)
                lastRawAccelTimestampNs = event.timestamp

                val alpha = GRAVITY_LPF_TAU_SEC / (GRAVITY_LPF_TAU_SEC + rawDt)
                lpfGravity[0] = alpha * lpfGravity[0] + (1f - alpha) * rawX
                lpfGravity[1] = alpha * lpfGravity[1] + (1f - alpha) * rawY
                lpfGravity[2] = alpha * lpfGravity[2] + (1f - alpha) * rawZ

                if (gravitySensor == null) {
                    latestGravity[0] = lpfGravity[0]
                    latestGravity[1] = lpfGravity[1]
                    latestGravity[2] = lpfGravity[2]
                    hasGravity = true
                }

                // If TYPE_LINEAR_ACCELERATION is available, it drives processImuSample directly
                if (linearAccelSensor != null) return

                val gx = if (gravitySensor != null && hasGravity) latestGravity[0] else lpfGravity[0]
                val gy = if (gravitySensor != null && hasGravity) latestGravity[1] else lpfGravity[1]
                val gz = if (gravitySensor != null && hasGravity) latestGravity[2] else lpfGravity[2]
                processImuSample(
                    nowNs = event.timestamp,
                    ax = rawX - gx,
                    ay = rawY - gy,
                    az = rawZ - gz,
                    gx = gx,
                    gy = gy,
                    gz = gz
                )
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Wait briefly for initial gravity vector if a gravity/raw-accel sensor exists
                if (!hasGravity && (gravitySensor != null || rawAccelSensor != null)) return
                val ax = event.values.getOrNull(0) ?: 0f
                val ay = event.values.getOrNull(1) ?: 0f
                val az = event.values.getOrNull(2) ?: 0f
                processImuSample(
                    nowNs = event.timestamp,
                    ax = ax,
                    ay = ay,
                    az = az,
                    gx = latestGravity[0],
                    gy = latestGravity[1],
                    gz = latestGravity[2]
                )
            }
        }
    }

    private fun processImuSample(
        nowNs: Long,
        ax: Float,
        ay: Float,
        az: Float,
        gx: Float,
        gy: Float,
        gz: Float
    ) {
        if (lastSensorTimestampNs == 0L) {
            lastSensorTimestampNs = nowNs
            return
        }
        val dtSec = ((nowNs - lastSensorTimestampNs) / 1_000_000_000f).coerceIn(0.005f, 0.20f)
        lastSensorTimestampNs = nowNs

        val nowMs = SystemClock.elapsedRealtime()
        val gpsAgeMs = if (lastGpsTimestampMs > 0L) nowMs - lastGpsTimestampMs else Long.MAX_VALUE
        val isGpsLost = gpsAgeMs > GPS_TIMEOUT_MS

        val hasGyro = hasGyroSample && (nowNs - lastGyroTimestampNs) <= GYRO_STALE_NS
        val wx = if (hasGyro) latestGyro[0] else 0f
        val wy = if (hasGyro) latestGyro[1] else 0f
        val wz = if (hasGyro) latestGyro[2] else 0f

        if (nativeHandle != 0L) {
            val smoothed = RustNativeBridge.nativeVehicleTrackerOnImuSample(
                nativeHandle,
                ax, ay, az,
                gx, gy, gz,
                wx, wy, wz,
                hasGyro,
                dtSec,
                isGpsLost,
                nativeOut5
            )
            smoothedSpeedKmh = smoothed
            val source = mapSourceCode(nativeOut5[1].toInt())
            val forwardCalibrated = nativeOut5[2] >= 0.5f
            lastForwardCalibrated = forwardCalibrated

            handleHandheldTransition(source)

            if (isGpsLost ||
                source == SpeedTrackingSource.HANDHELD ||
                source != DspStateHolder.currentSpeedSource ||
                forwardCalibrated != DspStateHolder.currentCarForwardCalibrated ||
                abs(smoothed - DspStateHolder.currentDetectedSpeedKmh) >= 0.5f
            ) {
                applyAutoLoudness(smoothed, source, forwardCalibrated)
            }
            return
        }

        // --- Kotlin Fallback (when native library is unavailable) ---
        processKotlinFallbackImu(ax, ay, az, gx, gy, gz, wx, wy, wz, hasGyro, dtSec, isGpsLost)
    }

    private fun processKotlinFallbackImu(
        ax: Float,
        ay: Float,
        az: Float,
        gx: Float,
        gy: Float,
        gz: Float,
        wx: Float,
        wy: Float,
        wz: Float,
        hasGyro: Boolean,
        dtSec: Float,
        isGpsLost: Boolean
    ) {
        val gNorm = sqrt(gx * gx + gy * gy + gz * gz)
        var horizNorm = sqrt(ax * ax + ay * ay + az * az)

        if (gNorm > 1.0f) {
            val gHatX = gx / gNorm
            val gHatY = gy / gNorm
            val gHatZ = gz / gNorm

            // Project linear acceleration onto horizontal plane (removes vertical suspension bumps)
            val aVert = ax * gHatX + ay * gHatY + az * gHatZ
            val ahX = ax - aVert * gHatX
            val ahY = ay - aVert * gHatY
            val ahZ = az - aVert * gHatZ
            horizNorm = sqrt(ahX * ahX + ahY * ahY + ahZ * ahZ)

            if (!hasFallbackAnchor) {
                fallbackGravityAnchor[0] = gHatX
                fallbackGravityAnchor[1] = gHatY
                fallbackGravityAnchor[2] = gHatZ
                hasFallbackAnchor = true
            }

            val dotAnchor = (gHatX * fallbackGravityAnchor[0] +
                gHatY * fallbackGravityAnchor[1] +
                gHatZ * fallbackGravityAnchor[2]).coerceIn(-1f, 1f)
            val tiltAngleRad = acos(dotAnchor)

            var dgDt = 0f
            if (hasPrevGravityHat) {
                val dx = gHatX - prevGravityHat[0]
                val dy = gHatY - prevGravityHat[1]
                val dz = gHatZ - prevGravityHat[2]
                dgDt = sqrt(dx * dx + dy * dy + dz * dz) / dtSec
            }
            prevGravityHat[0] = gHatX
            prevGravityHat[1] = gHatY
            prevGravityHat[2] = gHatZ
            hasPrevGravityHat = true

            val gyroNorm = if (hasGyro) sqrt(wx * wx + wy * wy + wz * wz) else 0f
            val rotRate = max(dgDt, gyroNorm)

            if (!fallbackHandheld) {
                if (tiltAngleRad > HANDHELD_TILT_THRESHOLD_RAD || rotRate > HANDHELD_ROT_RATE_THRESHOLD) {
                    fallbackTiltAccumSec += dtSec
                    if (fallbackTiltAccumSec >= 0.35f || rotRate > 1.5f) {
                        fallbackHandheld = true
                        fallbackSteadyAccumSec = 0f
                    }
                } else {
                    fallbackTiltAccumSec = (fallbackTiltAccumSec - dtSec * 0.5f).coerceAtLeast(0f)
                }
            } else {
                if (tiltAngleRad < HANDHELD_RECOVERY_TILT_RAD && rotRate < 0.25f) {
                    fallbackSteadyAccumSec += dtSec
                    if (fallbackSteadyAccumSec >= HANDHELD_RECOVERY_SEC) {
                        fallbackHandheld = false
                        fallbackTiltAccumSec = 0f
                        fallbackGravityAnchor[0] = gHatX
                        fallbackGravityAnchor[1] = gHatY
                        fallbackGravityAnchor[2] = gHatZ
                    }
                } else {
                    // Re-seed comparison anchor slowly to check if posture stabilizes in a new mount angle
                    fallbackSteadyAccumSec = 0f
                    if (rotRate < 0.20f) {
                        fallbackGravityAnchor[0] = gHatX
                        fallbackGravityAnchor[1] = gHatY
                        fallbackGravityAnchor[2] = gHatZ
                    }
                }
            }
        }

        if (fallbackHandheld) {
            handleHandheldTransition(SpeedTrackingSource.HANDHELD)
            estimatedSpeedKmh = 0f
            smoothedSpeedKmh = 0f
            applyAutoLoudness(0f, SpeedTrackingSource.HANDHELD, forwardCalibrated = false)
            return
        }

        // 10-40 Hz Road Vibration Energy (HPF on horizontal acceleration)
        val alphaHp = 0.85f
        vibrationHighPass = alphaHp * (vibrationHighPass + horizNorm - forwardAccelLp)
        forwardAccelLp += 0.15f * (horizNorm - forwardAccelLp)
        val sampleVibEnergy = abs(vibrationHighPass)
        vibrationEnergyRms = vibrationEnergyRms * 0.96f + sampleVibEnergy * 0.04f

        if (!isGpsLost) {
            handleHandheldTransition(SpeedTrackingSource.GPS)
            return
        }

        val source: SpeedTrackingSource
        if (vibrationEnergyRms < VIBRATION_STOP_THRESHOLD) {
            estimatedSpeedKmh = 0f
            source = SpeedTrackingSource.STOPPED
        } else {
            val leakFactor = exp(-dtSec / 20.0f)
            val targetAnchor = max(20f, lastGpsSpeedKmh)
            estimatedSpeedKmh = targetAnchor * (1f - leakFactor) + estimatedSpeedKmh * leakFactor
            source = SpeedTrackingSource.TUNNEL_IMU
        }

        handleHandheldTransition(source)
        updateAutoLoudness(estimatedSpeedKmh, source, forwardCalibrated = false)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun applyAutoLoudness(
        smoothedSpeed: Float,
        source: SpeedTrackingSource,
        forwardCalibrated: Boolean
    ) {
        if (source == SpeedTrackingSource.HANDHELD) {
            DspStateHolder.updateVehicleSpeed(0f, SpeedTrackingSource.HANDHELD, forwardCalibrated)
            return
        }
        DspStateHolder.updateVehicleSpeed(smoothedSpeed, source, forwardCalibrated)

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

    private fun updateAutoLoudness(
        rawSpeedKmh: Float,
        source: SpeedTrackingSource,
        forwardCalibrated: Boolean
    ) {
        // Smooth speed with ~2.0s time constant to prevent zipper / sudden jumps
        val alphaSpeed = 0.18f
        smoothedSpeedKmh += alphaSpeed * (rawSpeedKmh - smoothedSpeedKmh)
        smoothedSpeedKmh = smoothedSpeedKmh.coerceAtLeast(0f)

        applyAutoLoudness(smoothedSpeedKmh, source, forwardCalibrated)
    }
}
