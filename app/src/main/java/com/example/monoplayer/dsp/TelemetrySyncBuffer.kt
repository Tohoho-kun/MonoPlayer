package com.example.monoplayer.dsp

import kotlin.math.abs

/**
 * Timestamped ring buffer that delays visualizer telemetry to match actual speaker / Bluetooth output,
 * compensating for ExoPlayer's AudioSink / AudioTrack pre-buffering (~150ms..800ms).
 */
class TelemetrySyncBuffer(private val capacity: Int = 128) {

    private data class StampedEntry(
        var produceRealtimeMs: Long = 0L,
        var queuedStreamMs: Long = 0L,
        var flushGen: Int = 0,
        var telemetry: VisualizerTelemetry = VisualizerTelemetry()
    )

    private val ring = Array(capacity) { StampedEntry() }
    private var writeIdx = 0
    private var count = 0

    @Volatile var flushGeneration: Int = 0
        private set

    @Volatile var latestQueuedStreamMs: Long = 0L
        private set

    @Volatile var latestProduceRealtimeMs: Long = 0L
        private set

    // Auto-estimated AudioSink + HAL + Bluetooth buffer lead time in ms
    @Volatile var autoBufferLeadMs: Long = 220L
        private set

    @Volatile private var lastClockUpdateMs: Long = -10_000L

    // Optional manual user trim (-200 .. +600 ms) for external car DSPs / AUX adapters
    @Volatile var manualSyncOffsetMs: Int = 0

    @Synchronized
    fun onFlush(): Int {
        flushGeneration++
        count = 0
        writeIdx = 0
        latestQueuedStreamMs = 0L
        lastClockUpdateMs = -10_000L
        return flushGeneration
    }

    @Synchronized
    fun push(
        telemetry: VisualizerTelemetry,
        produceRealtimeMs: Long,
        queuedStreamMs: Long,
        gen: Int = flushGeneration
    ) {
        if (gen != flushGeneration) return
        val slot = ring[writeIdx]
        slot.produceRealtimeMs = produceRealtimeMs
        slot.queuedStreamMs = queuedStreamMs
        slot.flushGen = gen
        slot.telemetry = telemetry

        writeIdx = (writeIdx + 1) % capacity
        if (count < capacity) count++

        latestProduceRealtimeMs = produceRealtimeMs
        latestQueuedStreamMs = queuedStreamMs
    }

    /**
     * Called from [MonoPlaybackService] with the continuous stream duration actually played out of
     * AudioTrack (compensated by Android's AudioTimestamp / latency tracker) since the last flush.
     */
    @Synchronized
    fun updatePlayedStreamClock(playedStreamMs: Long, nowRealtimeMs: Long, gen: Int) {
        if (gen != flushGeneration || count == 0 || playedStreamMs <= 0L) return
        lastClockUpdateMs = nowRealtimeMs
        // Extrapolate how much audio has been queued as of nowRealtimeMs
        val elapsedSincePush = (nowRealtimeMs - latestProduceRealtimeMs).coerceIn(0L, 250L)
        val estQueuedNowMs = latestQueuedStreamMs - elapsedSincePush.coerceAtMost(0L)
        val rawLeadMs = (estQueuedNowMs - playedStreamMs).coerceIn(20L, 1400L)
        // Smooth estimate to avoid jitter while locking quickly on seek/start
        autoBufferLeadMs = if (abs(rawLeadMs - autoBufferLeadMs) > 180L) {
            (autoBufferLeadMs * 2L + rawLeadMs * 3L) / 5L
        } else {
            (autoBufferLeadMs * 7L + rawLeadMs) / 8L
        }
    }

    fun getTotalDelayMs(nowRealtimeMs: Long = System.nanoTime() / 1_000_000L): Long {
        val clockActive = (nowRealtimeMs - lastClockUpdateMs) in 0L..1500L
        val baseLead = if (clockActive) autoBufferLeadMs else 0L
        return (baseLead + manualSyncOffsetMs.toLong()).coerceIn(0L, 1800L)
    }

    /**
     * Retrieves the telemetry snapshot whose acoustic samples are emerging from the speaker at [nowRealtimeMs].
     */
    @Synchronized
    fun sampleAt(nowRealtimeMs: Long): VisualizerTelemetry? {
        if (count == 0) return null
        val targetProduceMs = nowRealtimeMs - getTotalDelayMs(nowRealtimeMs)

        var bestEntry: StampedEntry? = null
        var bestDiff = Long.MAX_VALUE

        val startIdx = (writeIdx - count + capacity) % capacity
        for (i in 0 until count) {
            val entry = ring[(startIdx + i) % capacity]
            if (entry.flushGen != flushGeneration) continue
            val diff = abs(entry.produceRealtimeMs - targetProduceMs)
            if (diff <= bestDiff) {
                bestDiff = diff
                bestEntry = entry
            }
        }
        return bestEntry?.telemetry
    }
}
