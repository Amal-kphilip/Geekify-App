package com.geekify.android.player.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.BitmapLoader
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import com.geekify.android.R
import com.geekify.android.ui.theme.InkBackground
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Media3 [BitmapLoader] backed by the app's existing Coil image loader.
 *
 * Why: Media3's default loader uses its own plain HTTP stack with no disk cache, no downsampling and no
 * fallback, so a slow, redirected or offline cover meant "no artwork" in the notification and on the lock screen.
 * This loader reuses Coil's memory + disk cache (offline covers, no repeat downloads), decodes at a small
 * notification size, crops to a square, and ALWAYS completes with a bitmap (Geekify's default art on failure).
 *
 * Every request is per-URI and returns its own future, so Media3 pairs a result with the media item that asked
 * for it; a slow cover for song A can never be delivered as song B's artwork.
 * All work runs off the main thread on [scope]; call [close] when the service is destroyed.
 */
@UnstableApi
class CoilBitmapLoader(
    private val context: Context,
    private val targetSizePx: Int = ArtworkUrls.NOTIFICATION_SIZE_PX,
    private val imageLoader: () -> ImageLoader = { SingletonImageLoader.get(context) }
) : BitmapLoader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val fallbackBitmap: Bitmap by lazy { buildFallback() }

    override fun supportsMimeType(mimeType: String): Boolean = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = launchFuture {
        val decoded = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
        decoded?.toSquare(targetSizePx) ?: fallbackBitmap
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = launchFuture {
        load(uri.toString())
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        metadata.artworkData?.let { return decodeBitmap(it) }
        val uri = metadata.artworkUri
            ?: return launchFuture { fallbackBitmap } // no artwork known for this song: Geekify art, never the previous song's
        return loadBitmap(uri)
    }

    fun close() = scope.cancel()

    private suspend fun load(url: String): Bitmap {
        val normalized = ArtworkUrls.normalize(url) ?: return fallbackBitmap
        // First try normally (fresh or valid cache), then cache-only so previously seen covers work offline.
        return fetch(normalized, networkAllowed = true)
            ?: fetch(normalized, networkAllowed = false)
            ?: fallbackBitmap
    }

    private suspend fun fetch(url: String, networkAllowed: Boolean): Bitmap? = try {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(targetSizePx)
            .precision(Precision.INEXACT)
            .allowHardware(false) // hardware bitmaps cannot be parceled into MediaSession / notifications
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(if (networkAllowed) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()
        val result = imageLoader().execute(request)
        (result as? SuccessResult)?.image?.toBitmap()?.toSquare(targetSizePx)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private fun launchFuture(block: suspend () -> Bitmap): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        val job = scope.launch {
            try {
                future.set(block())
            } catch (e: CancellationException) {
                future.cancel(false)
                throw e
            } catch (t: Throwable) {
                // Never fail the future: a failed future means a notification without any artwork.
                future.set(fallbackBitmap)
            }
        }
        future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return future
    }

    /** Software ARGB_8888, centre-cropped to a square and never larger than [max]. The source is never mutated. */
    private fun Bitmap.toSquare(max: Int): Bitmap {
        var src = this
        if (src.config == Bitmap.Config.HARDWARE) {
            src = src.copy(Bitmap.Config.ARGB_8888, false) ?: return fallbackBitmap
        }
        val side = minOf(src.width, src.height)
        if (side <= 0) return fallbackBitmap
        var out = if (src.width == src.height) src
        else Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        if (out.width > max) out = Bitmap.createScaledBitmap(out, max, max, true)
        return out
    }

    /** Geekify's launcher art on the app's dark background. Built once, only when first needed. */
    private fun buildFallback(): Bitmap {
        val bmp = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(InkBackground.toArgb())
        ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)?.let { d ->
            d.setBounds(0, 0, targetSizePx, targetSizePx)
            d.draw(canvas)
        }
        return bmp
    }
}
