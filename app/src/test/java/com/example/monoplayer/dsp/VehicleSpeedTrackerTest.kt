package com.example.monoplayer.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleSpeedTrackerTest {

    private data class Vec3(val x: Float, val y: Float, val z: Float) {
        operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
        fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
        fun cross(o: Vec3) = Vec3(
            y * o.z - z * o.y,
            z * o.x - x * o.z,
            x * o.y - y * o.x
        )
        fun norm() = sqrt(x * x + y * y + z * z)
        fun normalized(): Vec3 {
            val n = norm()
            return if (n > 1e-6f) Vec3(x / n, y / n, z / n) else Vec3(0f, 0f, 1f)
        }
    }

    private fun buildRotatedMountBasis(): Triple<Vec3, Vec3, Vec3> {
        val gHat = Vec3(0.25f, 0.45f, 0.857f).normalized()
        val ref = Vec3(1f, 0f, 0f)
        val baseFwd = (ref - gHat * ref.dot(gHat)).normalized()
        val baseLat = gHat.cross(baseFwd).normalized()
        val yawRad = 0.65f // ~37 deg rotated holder
        val fwd = (baseFwd * cos(yawRad) + baseLat * sin(yawRad)).normalized()
        val lat = gHat.cross(fwd).normalized()
        return Triple(fwd, lat, gHat)
    }

    @Test
    fun intensityCalculationMatchesCruisingSpeedAndFactor() {
        // Standard factor (1.0x) at 80 km/h reference
        val refKmh = 80f
        val factor = 1.0f

        // Stationary (0 km/h) -> intensity 0%
        val i0 = VehicleSpeedTracker.calculateIntensity(0f, refKmh, factor)
        assertEquals(0.0f, i0, 1e-4f)

        // Half speed (40 km/h) -> intensity 50%
        val i40 = VehicleSpeedTracker.calculateIntensity(40f, refKmh, factor)
        assertEquals(0.50f, i40, 1e-4f)

        // Full cruising (80 km/h) -> intensity 100%
        val i80 = VehicleSpeedTracker.calculateIntensity(80f, refKmh, factor)
        assertEquals(1.0f, i80, 1e-4f)

        // Over-speed (120 km/h) -> clamps to 100% at standard 1.0x
        val i120 = VehicleSpeedTracker.calculateIntensity(120f, refKmh, factor)
        assertEquals(1.0f, i120, 1e-4f)

        // Max compensation factor (2.0x) at reference cruising speed -> reaches 200% (2.0f)
        val iMaxAtRef = VehicleSpeedTracker.calculateIntensity(80f, refKmh, 2.0f)
        assertEquals(2.0f, iMaxAtRef, 1e-4f)

        // Half speed at max factor (2.0x) -> 100% (1.0f)
        val iHalfAtMax = VehicleSpeedTracker.calculateIntensity(40f, refKmh, 2.0f)
        assertEquals(1.0f, iHalfAtMax, 1e-4f)
    }

    @Test
    fun compensationFactorScalesSensitivityDynamically() {
        val refKmh = 100f

        // Mild factor (0.50x) -> at 50 km/h, intensity should be 25% instead of 50%
        val mild = VehicleSpeedTracker.calculateIntensity(50f, refKmh, 0.50f)
        assertEquals(0.25f, mild, 1e-4f)

        // Aggressive factor (1.50x) -> at 50 km/h, intensity should be 75%
        val aggressive = VehicleSpeedTracker.calculateIntensity(50f, refKmh, 1.50f)
        assertEquals(0.75f, aggressive, 1e-4f)
    }

    @Test
    fun negativeSpeedAndZeroReferenceAreSafelyGuarded() {
        val safe1 = VehicleSpeedTracker.calculateIntensity(-30f, 80f, 1.0f)
        assertEquals(0f, safe1, 1e-4f)

        val safe2 = VehicleSpeedTracker.calculateIntensity(50f, 0f, 1.0f)
        assertTrue(safe2.isFinite() && safe2 in 0f..1f)
    }

    @Test
    fun rotatedMountCalibratesForwardAxisAndTracksBraking() {
        assertTrue(RustNativeBridge.isAvailable)
        val handle = RustNativeBridge.nativeVehicleTrackerCreate()
        assertTrue(handle != 0L)
        try {
            val (fwd, _, gHat) = buildRotatedMountBasis()
            val g = gHat * 9.81f
            val out2 = FloatArray(2)
            val out5 = FloatArray(5)

            // Seed initial gravity anchor & start GPS window at 20 km/h
            RustNativeBridge.nativeVehicleTrackerOnImuSample(
                handle, 0f, 0f, 0f, g.x, g.y, g.z, 0f, 0f, 0f, true, 0.05f, false, out5
            )
            RustNativeBridge.nativeVehicleTrackerOnGps(handle, 20f, out2)

            // Straight-line acceleration along rotated forward axis with vertical road bounce
            for (i in 0 until 30) {
                val bump = 1.6f * sin(i * 0.4f)
                val a = fwd * 2.0f + gHat * bump
                RustNativeBridge.nativeVehicleTrackerOnImuSample(
                    handle, a.x, a.y, a.z, g.x, g.y, g.z, 0f, 0f, 0f, true, 0.05f, false, out5
                )
            }

            // Finish calibration window with higher GPS speed (20 -> 32 km/h)
            RustNativeBridge.nativeVehicleTrackerOnGps(handle, 32f, out2)

            // Probe horizontal forward/lateral decomposition
            val probe = fwd * 2.0f + gHat * 1.2f
            RustNativeBridge.nativeVehicleTrackerOnImuSample(
                handle, probe.x, probe.y, probe.z, g.x, g.y, g.z, 0f, 0f, 0f, true, 0.05f, false, out5
            )
            assertEquals("Forward axis should be calibrated", 1f, out5[2], 1e-4f)
            assertEquals("Vertical bump should not pollute a_fwd", 2.0f, out5[3], 0.15f)
            assertEquals("Straight drive should have ~0 lateral accel", 0.0f, out5[4], 0.15f)

            // Ramp GPS up to ~60 km/h then enter tunnel and brake
            repeat(20) {
                RustNativeBridge.nativeVehicleTrackerOnGps(handle, 60f, out2)
            }
            val preBrakeSpeed = out2[0]
            assertTrue("Expected smoothed speed near 60 km/h, got $preBrakeSpeed", preBrakeSpeed > 55f)

            val brake = fwd * (-3.0f)
            for (i in 0 until 40) {
                RustNativeBridge.nativeVehicleTrackerOnImuSample(
                    handle, brake.x, brake.y, brake.z, g.x, g.y, g.z, 0f, 0f, 0f, true, 0.05f, true, out5
                )
            }
            assertTrue(
                "Braking in tunnel should reduce speed (pre=$preBrakeSpeed, post=${out5[0]})",
                out5[0] < preBrakeSpeed - 10f
            )
            assertTrue("a_fwd should be negative during braking", out5[3] < -2.0f)
        } finally {
            RustNativeBridge.nativeVehicleTrackerDestroy(handle)
        }
    }

    @Test
    fun corneringCurveDoesNotCorruptForwardSpeedOrFalseStop() {
        assertTrue(RustNativeBridge.isAvailable)
        val handle = RustNativeBridge.nativeVehicleTrackerCreate()
        assertTrue(handle != 0L)
        try {
            val (fwd, lat, gHat) = buildRotatedMountBasis()
            val g = gHat * 9.81f
            val out2 = FloatArray(2)
            val out5 = FloatArray(5)

            // Calibrate forward axis on straight acceleration (15 -> 30 km/h)
            RustNativeBridge.nativeVehicleTrackerOnImuSample(
                handle, 0f, 0f, 0f, g.x, g.y, g.z, 0f, 0f, 0f, true, 0.05f, false, out5
            )
            RustNativeBridge.nativeVehicleTrackerOnGps(handle, 15f, out2)
            for (i in 0 until 30) {
                val a = fwd * 2.2f
                RustNativeBridge.nativeVehicleTrackerOnImuSample(
                    handle, a.x, a.y, a.z, g.x, g.y, g.z, 0f, 0f, 0f, true, 0.05f, false, out5
                )
            }
            RustNativeBridge.nativeVehicleTrackerOnGps(handle, 30f, out2)
            repeat(25) {
                RustNativeBridge.nativeVehicleTrackerOnGps(handle, 60f, out2)
            }
            val cruisingBeforeCurve = out2[0]

            // Lose GPS in curved ramp: strong lateral centripetal accel + yaw rate, zero forward accel
            val curveAccel = lat * 3.5f
            val curveGyro = gHat * 0.35f
            for (i in 0 until 40) {
                RustNativeBridge.nativeVehicleTrackerOnImuSample(
                    handle,
                    curveAccel.x, curveAccel.y, curveAccel.z,
                    g.x, g.y, g.z,
                    curveGyro.x, curveGyro.y, curveGyro.z,
                    true, 0.05f, true, out5
                )
            }

            assertEquals("Calibrated state must remain active", 1f, out5[2], 1e-4f)
            assertTrue("Expected lateral accel ~3.5 m/s^2, got ${out5[4]}", abs(out5[4]) > 2.5f)
            assertEquals("Source during curve should remain TUNNEL_IMU (2)", 2, out5[1].toInt())
            assertTrue(
                "Cornering centripetal force must not inflate/drop cruising speed (before=$cruisingBeforeCurve, after=${out5[0]})",
                abs(out5[0] - cruisingBeforeCurve) < 8f
            )
        } finally {
            RustNativeBridge.nativeVehicleTrackerDestroy(handle)
        }
    }

    @Test
    fun handheldPickupStopsTrackingAndRecoversWhenSteadyInHolder() {
        assertTrue(RustNativeBridge.isAvailable)
        val handle = RustNativeBridge.nativeVehicleTrackerCreate()
        assertTrue(handle != 0L)
        try {
            val (fwd, _, gHat) = buildRotatedMountBasis()
            val g = gHat * 9.81f
            val out2 = FloatArray(2)
            val out5 = FloatArray(5)

            // Calibrate & establish cruising speed ~50 km/h
            RustNativeBridge.nativeVehicleTrackerOnImuSample(
                handle, 0f, 0f, 0f, g.x, g.y, g.z, 0f, 0f, 0f, true, 0.05f, false, out5
            )
            RustNativeBridge.nativeVehicleTrackerOnGps(handle, 15f, out2)
            for (i in 0 until 25) {
                val a = fwd * 2.0f
                RustNativeBridge.nativeVehicleTrackerOnImuSample(
                    handle, a.x, a.y, a.z, g.x, g.y, g.z, 0f, 0f, 0f, true, 0.05f, false, out5
                )
            }
            RustNativeBridge.nativeVehicleTrackerOnGps(handle, 50f, out2)
            RustNativeBridge.nativeVehicleTrackerOnImuSample(
                handle, 0f, 0f, 0f, g.x, g.y, g.z, 0f, 0f, 0f, true, 0.05f, false, out5
            )
            assertEquals(1f, out5[2], 1e-4f)

            // Pick up phone: tilt gravity > 0.5 rad for 1.0s
            val tiltedG = (gHat * 0.70f + fwd * 0.714f).normalized() * 9.81f
            for (i in 0 until 20) {
                RustNativeBridge.nativeVehicleTrackerOnImuSample(
                    handle, 0.2f, 0.1f, 0f,
                    tiltedG.x, tiltedG.y, tiltedG.z,
                    0.3f, 0.2f, 0f,
                    true, 0.05f, false, out5
                )
            }
            assertEquals("Expected HANDHELD source code 4", 4, out5[1].toInt())
            assertEquals("Expected speed reset to 0 in HANDHELD", 0f, out5[0], 1e-4f)

            // GPS callbacks during handheld must be ignored
            val gpsDuringHandheld = RustNativeBridge.nativeVehicleTrackerOnGps(handle, 80f, out2)
            assertEquals(0f, gpsDuringHandheld, 1e-4f)
            assertEquals(4, out2[1].toInt())

            // Hold steady in new mount angle for 3.0s (> 2.5s recovery window)
            for (i in 0 until 60) {
                RustNativeBridge.nativeVehicleTrackerOnImuSample(
                    handle, 0f, 0f, 0f,
                    tiltedG.x, tiltedG.y, tiltedG.z,
                    0f, 0f, 0f,
                    true, 0.05f, true, out5
                )
            }
            assertTrue("Should exit HANDHELD after steady recovery (got ${out5[1]})", out5[1].toInt() != 4)
            assertEquals(
                "Forward calibration should reset when recovered mount angle shifted > 0.25 rad",
                0f,
                out5[2],
                1e-4f
            )
        } finally {
            RustNativeBridge.nativeVehicleTrackerDestroy(handle)
        }
    }

    @Test
    fun dspStateHolderTracksHandheldAndForwardCalibration() {
        DspStateHolder.updateVehicleSpeed(62.5f, SpeedTrackingSource.GPS, forwardCalibrated = true)
        assertEquals(62.5f, DspStateHolder.currentDetectedSpeedKmh, 1e-4f)
        assertEquals(SpeedTrackingSource.GPS, DspStateHolder.currentSpeedSource)
        assertTrue(DspStateHolder.currentCarForwardCalibrated)
        assertTrue(DspStateHolder.telemetry.value.carForwardCalibrated)

        DspStateHolder.updateVehicleSpeed(0f, SpeedTrackingSource.HANDHELD, forwardCalibrated = false)
        assertEquals(0f, DspStateHolder.currentDetectedSpeedKmh, 1e-4f)
        assertEquals(SpeedTrackingSource.HANDHELD, DspStateHolder.currentSpeedSource)
        assertFalse(DspStateHolder.currentCarForwardCalibrated)
        assertEquals(SpeedTrackingSource.HANDHELD, DspStateHolder.speedTrackingFlow.value.second)
        assertFalse(DspStateHolder.telemetry.value.carForwardCalibrated)

        DspStateHolder.updateVehicleSpeed(0f, SpeedTrackingSource.OFF, forwardCalibrated = false)
    }
}
