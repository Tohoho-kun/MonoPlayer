package com.example.monoplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleSpeedTrackerTest {

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
}
