package dev.pipilot.app.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import dev.pipilot.app.log.AppLog
import java.io.ByteArrayOutputStream

/** MIME plus base64, with a small thumbnail for the composer preview. */
data class PreparedImage(
    val base64: String,
    val mimeType: String,
    val thumbnail: android.graphics.Bitmap,
    val sizeKb: Int,
)

private const val TAG = "ImagePrep"

/**
 * Compress a user-picked image to a size remote models accept: always JPEG
 * (raw PNG screenshots become 4–8MB of base64 and providers reject them),
 * longest side 1568 (vision-model recommended cap), quality 82.
 * Bytes are read into memory once then decoded, because some gallery URIs
 * cannot be opened a second time. Every failure path is logged so "read failed"
 * can be diagnosed on a real device.
 */
fun prepareImageForUpload(context: Context, uri: Uri, maxSide: Int = 1568): PreparedImage? {
    val resolver = context.contentResolver
    val bytes: ByteArray = try {
        resolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        AppLog.e(TAG, "open stream failed: ${e.javaClass.simpleName}: ${e.message} uri=$uri")
        null
    } ?: run {
        AppLog.e(TAG, "open stream returned null uri=$uri")
        return null
    }
    AppLog.i(TAG, "picked ${bytes.size / 1024}KB from $uri")

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        // Decoder does not recognize this format (e.g. HEIC on older OS): mime is null, raw size does not help
        AppLog.e(TAG, "decode bounds failed: mime=${bounds.outMimeType} bytes=${bytes.size} — format unsupported by local decoder?")
        return null
    }
    val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    var sample = 1
    while (longest / sample > maxSide) sample *= 2

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    if (bitmap == null) {
        AppLog.e(TAG, "decode failed at full pass: ${bounds.outWidth}x${bounds.outHeight} mime=${bounds.outMimeType} sample=$sample")
        return null
    }
    return try {
        val ok = ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        }
        if (ok.isEmpty()) {
            AppLog.e(TAG, "jpeg compress produced 0 bytes")
            return null
        }
        // Small thumbnail for the composer (~128px)
        val thumbScale = maxOf(1, maxOf(bitmap.width, bitmap.height) / 128)
        val scaled = android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width / thumbScale).coerceAtLeast(1),
            (bitmap.height / thumbScale).coerceAtLeast(1),
            true,
        )
        // createScaledBitmap may return the original when already small; we recycle the original, so copy for the thumbnail
        val thumbnail = if (scaled === bitmap) {
            bitmap.copy(bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false) ?: scaled
        } else scaled
        AppLog.i(TAG, "prepared ${bounds.outWidth}x${bounds.outHeight} ${bounds.outMimeType} -> ${ok.size / 1024}KB jpeg")
        PreparedImage(
            base64 = Base64.encodeToString(ok, Base64.NO_WRAP),
            mimeType = "image/jpeg",
            thumbnail = thumbnail,
            sizeKb = ok.size / 1024,
        )
    } finally {
        bitmap.recycle()
    }
}
