package com.example.monoplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Loads embedded ID3/MP4 album art from audio files, or generates a stylish 90s/00s retro vinyl/CD jacket art
 * if the file has no embedded artwork.
 */
object ArtworkLoader {
    private val cache = object : LruCache<String, Bitmap>(32) {}

    private const val SCHEME = "monoplayer-art"

    /**
     * Lightweight artwork reference stored in MediaItem metadata. The playback service resolves it lazily
     * (see ArtBitmapLoader), so building a queue of thousands of tracks does not decode any artwork.
     */
    fun artworkUriFor(track: TrackItem): Uri =
        Uri.Builder()
            .scheme(SCHEME)
            .authority("art")
            .appendQueryParameter("id", track.id)
            .appendQueryParameter("src", track.uriString)
            .appendQueryParameter("album", track.album)
            .appendQueryParameter("artist", track.canonicalArtistName)
            .appendQueryParameter("demo", if (track.isDemoTrack) "1" else "0")
            .build()

    fun trackFromArtworkUri(uri: Uri): TrackItem? {
        if (uri.scheme != SCHEME) return null
        return TrackItem(
            id = uri.getQueryParameter("id") ?: return null,
            uriString = uri.getQueryParameter("src") ?: "",
            title = "",
            rawArtist = uri.getQueryParameter("artist") ?: "",
            normalizedArtistKey = "",
            canonicalArtistName = uri.getQueryParameter("artist") ?: "",
            album = uri.getQueryParameter("album") ?: "",
            durationMs = 0L,
            isDemoTrack = uri.getQueryParameter("demo") == "1"
        )
    }

    suspend fun loadArtwork(context: Context, track: TrackItem): Bitmap = withContext(Dispatchers.IO) {
        loadArtworkBlocking(context, track)
    }

    /** Blocking variant for background threads (e.g. the playback service's bitmap loader). */
    fun loadArtworkBlocking(context: Context, track: TrackItem): Bitmap {
        cache.get(track.id)?.let { return it }

        var bitmap: Bitmap? = null
        if (!track.isDemoTrack && track.uriString.isNotEmpty()) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, Uri.parse(track.uriString))
                val bytes = mmr.embeddedPicture
                if (bytes != null && bytes.isNotEmpty()) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 800 && bounds.outHeight / (sample * 2) >= 800) sample *= 2
                    bitmap = BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample }
                    )
                }
            } catch (_: Exception) {
            } finally {
                try {
                    mmr.release()
                } catch (_: Exception) {
                }
            }
        }

        val finalBitmap = bitmap ?: generateRetroSynthJacket(track)
        cache.put(track.id, finalBitmap)
        return finalBitmap
    }


    private fun generateRetroSynthJacket(track: TrackItem): Bitmap {
        val size = 360
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val hash = abs(track.album.hashCode() + track.canonicalArtistName.hashCode())
        val palettes = arrayOf(
            intArrayOf(0xFF0F172A.toInt(), 0xFF1E1B4B.toInt(), 0xFF0284C7.toInt(), 0xFF38BDF8.toInt()),
            intArrayOf(0xFF18181B.toInt(), 0xFF3B0764.toInt(), 0xFF9333EA.toInt(), 0xFFF472B6.toInt()),
            intArrayOf(0xFF022C22.toInt(), 0xFF064E3B.toInt(), 0xFF059669.toInt(), 0xFF34D399.toInt()),
            intArrayOf(0xFF1C1917.toInt(), 0xFF451A03.toInt(), 0xFFD97706.toInt(), 0xFFFBBF24.toInt())
        )
        val pal = palettes[hash % palettes.size]

        // Background gradient
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                intArrayOf(pal[0], pal[1], pal[2]),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        // Retro 90s perspective grid lines
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal[3]
            alpha = 55
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        for (y in 180..size step 24) {
            canvas.drawLine(0f, y.toFloat(), size.toFloat(), y.toFloat(), gridPaint)
        }
        for (x in -120..size + 120 step 45) {
            canvas.drawLine(size / 2f, 160f, x.toFloat(), size.toFloat(), gridPaint)
        }

        // Vinyl / MiniDisc concentric rings
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal[3]
            alpha = 180
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(size / 2f, size / 2f - 16f, 88f, ringPaint)
        ringPaint.strokeWidth = 1.5f
        ringPaint.alpha = 100
        canvas.drawCircle(size / 2f, size / 2f - 16f, 68f, ringPaint)
        canvas.drawCircle(size / 2f, size / 2f - 16f, 48f, ringPaint)

        // Center label dot
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal[3]
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f - 16f, 14f, dotPaint)

        // Album & Artist typography stamp
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 22f
            isFakeBoldText = true
        }
        val shortAlbum = track.album.take(18)
        canvas.drawText(shortAlbum, 24f, size - 52f, textPaint)

        textPaint.textSize = 16f
        textPaint.color = pal[3]
        textPaint.isFakeBoldText = false
        canvas.drawText(track.canonicalArtistName.take(24), 24f, size - 26f, textPaint)

        // MONO HI-FI corner badge
        textPaint.textSize = 13f
        textPaint.color = 0xCCFFFFFF.toInt()
        canvas.drawText("MONO MASTER DSP", 24f, 34f, textPaint)

        return bmp
    }
}
