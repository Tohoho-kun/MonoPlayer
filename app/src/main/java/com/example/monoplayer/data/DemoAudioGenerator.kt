package com.example.monoplayer.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Generates high-resolution stereo test tracks on device (inspired by dsp.html's Wide High-Frequency Shimmer Synth
 * plus additional tracks across different genres/artists with intentional artist name variations to showcase
 * Artist Name Normalization, Album grouping, and Stereo-to-Mono DSP differences).
 */
object DemoAudioGenerator {

    data class BuiltinSpec(
        val id: String,
        val fileName: String,
        val title: String,
        val rawArtist: String,
        val album: String,
        val trackNumber: Int,
        val genre: String,
        val mode: Int
    )

    val BUILTIN_SPECS = listOf(
        BuiltinSpec(
            id = "demo_shimmer_synth",
            fileName = "01_wide_shimmer_synth.wav",
            title = "Wide High-Freq Shimmer Synth (DSP検証音源)",
            rawArtist = "The Mono Lab",
            album = "Stereo-to-Mono DSP Reference",
            trackNumber = 1,
            genre = "Synthwave / Test",
            mode = 0
        ),
        BuiltinSpec(
            id = "demo_haas_arpeggio",
            fileName = "02_haas_crystal_arpeggio.wav",
            title = "Haas Delay Crystal Arpeggio (0.45ms位相差)",
            rawArtist = "Mono Lab, The", // Intentional variation of "The Mono Lab" to demonstrate normalization!
            album = "Stereo-to-Mono DSP Reference",
            trackNumber = 2,
            genre = "Electronic",
            mode = 1
        ),
        BuiltinSpec(
            id = "demo_antiphase_cymbal",
            fileName = "03_cyber_highway_drive.wav",
            title = "Midnight Wangan VFD Groove",
            rawArtist = "DJ WINAMP 1999 feat. K.Asai",
            album = "90s Car Stereo Classics",
            trackNumber = 1,
            genre = "Eurobeat / Synth",
            mode = 2
        ),
        BuiltinSpec(
            id = "demo_night_cruiser",
            fileName = "04_neon_spectrum_cruise.wav",
            title = "Neon Spectrum Analyzer Cruise",
            rawArtist = "ｄｊ ｗｉｎａｍｐ １９９９", // Full-width + lowercase variation of "DJ WINAMP 1999"
            album = "90s Car Stereo Classics",
            trackNumber = 2,
            genre = "City Pop / Chill",
            mode = 3
        )
    )

    fun ensureDemoFilesGenerated(context: Context): File {
        val dir = File(context.filesDir, "demo_library")
        if (!dir.exists()) dir.mkdirs()

        for (spec in BUILTIN_SPECS) {
            val outFile = File(dir, spec.fileName)
            if (!outFile.exists() || outFile.length() < 1000L) {
                writeStereoWavFile(outFile, spec.mode)
            }
        }
        return dir
    }

    private fun writeStereoWavFile(file: File, mode: Int) {
        val sr = 44100
        val durationSec = 8
        val numSamples = sr * durationSec
        val numChannels = 2
        val dataBytes = numSamples * numChannels * 2
        val totalBytes = 44 + dataBytes

        val chords = arrayOf(
            doubleArrayOf(174.61, 220.00, 261.63, 329.63, 392.00), // Fmaj9
            doubleArrayOf(196.00, 246.94, 293.66, 329.63, 392.00), // G6
            doubleArrayOf(164.81, 196.00, 246.94, 293.66, 392.00), // Em7
            doubleArrayOf(220.00, 261.63, 329.63, 392.00, 493.88)  // Am9
        )

        val arpNotes = when (mode) {
            1 -> doubleArrayOf(1318.51, 1567.98, 2093.00, 2637.02, 3135.96, 3951.07, 3135.96, 2093.00)
            2 -> doubleArrayOf(880.0, 1174.66, 1318.51, 1760.0, 2349.32, 2637.02, 3520.0, 2637.02)
            3 -> doubleArrayOf(987.77, 1174.66, 1479.98, 1975.53, 2349.32, 2959.96, 2349.32, 1479.98)
            else -> doubleArrayOf(1046.50, 1318.51, 1567.98, 1975.53, 2093.00, 2637.02, 3135.96, 2637.02)
        }

        var seed = 1234567L + mode * 9999L
        fun rand(): Double {
            seed = (seed * 16807L) % 2147483647L
            return (seed.toDouble() / 2147483647.0) * 2.0 - 1.0
        }

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalBytes - 8)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(numChannels.toShort())
        header.putInt(sr)
        header.putInt(sr * numChannels * 2)
        header.putShort((numChannels * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)

        FileOutputStream(file).use { fos ->
            fos.write(header.array())
            val chunkFrames = 2048
            val chunkBuf = ByteBuffer.allocate(chunkFrames * 4).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until numSamples) {
                val t = i.toDouble() / sr
                val chordIdx = min(3, (t / 2.0).toInt())
                val chord = chords[(chordIdx + mode) % 4]

                // 1. Center Bass & Warm Pad (In-phase)
                val bassFreq = chord[0] * 0.5
                var midPad = 0.22 * sin(2.0 * Math.PI * bassFreq * t) +
                        0.08 * sin(4.0 * Math.PI * bassFreq * t)
                for (f in chord) {
                    midPad += 0.06 * sin(2.0 * Math.PI * f * t)
                }

                // 2. Wide Stereo SuperSaw / Shimmer Harmonics (Haas delay & anti-phase high harmonics)
                var sparkleL = 0.0
                var sparkleR = 0.0
                for (n in 1 until chord.size) {
                    val fHigh = chord[n] * 4.0
                    val haasOffset = 0.00045
                    sparkleL += 0.045 * sin(2.0 * Math.PI * fHigh * t)
                    sparkleR += 0.045 * sin(2.0 * Math.PI * fHigh * (t - haasOffset) + Math.PI * 0.85)

                    val fUltra = chord[n] * 8.0
                    sparkleL += 0.03 * sin(2.0 * Math.PI * fUltra * t + 0.3)
                    sparkleR += -0.03 * sin(2.0 * Math.PI * fUltra * t + 0.3)
                }

                // 3. Stereo Bell Arpeggio
                val tempoMul = if (mode == 2) 10.0 else 8.0
                val step = floor(t * tempoMul).toInt()
                val stepTime = (t * tempoMul) - step
                val env = exp(-stepTime * 5.5)
                val arpFreq = arpNotes[step % arpNotes.size]

                val arpL = env * (
                        0.09 * sin(2.0 * Math.PI * arpFreq * t) +
                                0.07 * sin(2.0 * Math.PI * arpFreq * 2.0 * t) +
                                0.05 * sin(2.0 * Math.PI * arpFreq * 3.0 * t)
                        )
                val arpR = env * (
                        0.09 * sin(2.0 * Math.PI * arpFreq * t + Math.PI * 0.75) +
                                0.07 * sin(2.0 * Math.PI * arpFreq * 2.0 * t + Math.PI * 0.92) -
                                0.05 * sin(2.0 * Math.PI * arpFreq * 3.0 * t)
                        )

                // 4. Stereo Hi-Hat / Air Shimmer
                val hatStep = (t * 4.0) - floor(t * 4.0)
                val hatEnv = exp(-hatStep * 18.0)
                val metal = rand() * sin(2.0 * Math.PI * 6500.0 * t) * hatEnv * 0.12

                val outL = tanh(midPad + sparkleL + arpL + metal)
                val outR = tanh(midPad + sparkleR + arpR - metal * 0.9)

                val pcmL = (outL * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                val pcmR = (outR * 32767.0).toInt().coerceIn(-32768, 32767).toShort()

                chunkBuf.putShort(pcmL)
                chunkBuf.putShort(pcmR)

                if (!chunkBuf.hasRemaining()) {
                    fos.write(chunkBuf.array())
                    chunkBuf.clear()
                }
            }
            if (chunkBuf.position() > 0) {
                fos.write(chunkBuf.array(), 0, chunkBuf.position())
            }
        }
    }
}
