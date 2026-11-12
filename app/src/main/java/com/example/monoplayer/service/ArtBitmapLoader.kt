package com.example.monoplayer.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.example.monoplayer.data.ArtworkLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Resolves `monoplayer-art://` artwork URIs on a background thread, only for the track that the OS
 * (notification / lock screen / Bluetooth) is actually showing.
 */
@UnstableApi
class ArtBitmapLoader(private val context: Context) : BitmapLoader {

    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        executor.submit(Callable {
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw IllegalStateException("Could not decode artwork")
        })

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        executor.submit(Callable {
            val track = ArtworkLoader.trackFromArtworkUri(uri)
                ?: throw IllegalArgumentException("Unsupported artwork uri: $uri")
            ArtworkLoader.loadArtworkBlocking(context, track)
        })
}
