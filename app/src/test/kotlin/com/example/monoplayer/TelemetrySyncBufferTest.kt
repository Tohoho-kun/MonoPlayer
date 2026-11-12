package com.example.monoplayer

import com.example.monoplayer.dsp.TelemetrySyncBuffer
import com.example.monoplayer.dsp.VisualizerTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetrySyncBufferTest {

    @Test
    fun syncBuffer_compensatesAudioSinkBufferLeadAccurately() {
        val buf = TelemetrySyncBuffer(capacity = 64)
        val gen = buf.flushGeneration

        // Simulate 30 frames pushed every 20ms (t = 1000ms .. 1580ms), where AudioSink leads speaker by ~300ms
        for (i in 0 until 30) {
            val tMs = 1000L + i * 20L
            val queuedMs = (i + 1) * 20L + 300L
            buf.push(
                telemetry = VisualizerTelemetry(vuLeftDb = -40f + i.toFloat()),
                produceRealtimeMs = tMs,
                queuedStreamMs = queuedMs,
                gen = gen
            )
            // Speaker has played (i + 1) * 20ms -> lead is 300ms
            buf.updatePlayedStreamClock(
                playedStreamMs = (i + 1) * 20L,
                nowRealtimeMs = tMs,
                gen = gen
            )
        }

        assertTrue("Auto buffer lead should lock near 300ms, got ${buf.autoBufferLeadMs}", buf.autoBufferLeadMs in 260L..330L)

        // Sampling at t = 1580ms must return the frame produced ~300ms earlier (around t = 1280ms -> i = 14, vuLeftDb = -26)
        // rather than the latest premature frame (i = 29, vuLeftDb = -11)!
        val synced = buf.sampleAt(1580L)!!
        assertEquals(-26f, synced.vuLeftDb, 2.0f)
    }

    @Test
    fun syncBuffer_flushDiscardsStaleFramesImmediately() {
        val buf = TelemetrySyncBuffer(capacity = 32)
        buf.push(
            telemetry = VisualizerTelemetry(vuLeftDb = -5f),
            produceRealtimeMs = 2000L,
            queuedStreamMs = 400L
        )
        buf.onFlush()
        assertNull("Flush must clear stale pre-seek frames", buf.sampleAt(2050L))
    }
}
