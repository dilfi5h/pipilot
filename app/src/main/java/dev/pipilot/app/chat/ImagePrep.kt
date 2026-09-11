package dev.pipilot.app.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream

/** MIME 与 base64,附带小缩略图供输入框预览。 */
data class PreparedImage(
    val base64: String,
    val mimeType: String,
    val thumbnail: android.graphics.Bitmap,
)

/**
 * 将用户选取的图片压缩到远端模型可接受的范围。
 * JPEG/PNG/WebP 直接下采样到边长 2000 以内再压缩；HEIC/HEIF 等本机
 * 解码/编码链不支持的格式会解码后转码为 JPEG，保证任何扩展名都可发送。
 */
fun prepareImageForUpload(context: Context, uri: Uri, maxSide: Int = 2000): PreparedImage? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri)?.lowercase()
        ?: MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.lowercase()?.let {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
        }?.lowercase()
        ?: "image/jpeg"

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: return null
    val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    var sample = 1
    while (longest / sample > maxSide) sample *= 2

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: return null
    return try {
        val outMime = if (mime == "image/png" || mime == "image/webp") mime else "image/jpeg"
        val format = when (outMime) {
            "image/png" -> android.graphics.Bitmap.CompressFormat.PNG
            "image/webp" -> android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
            else -> android.graphics.Bitmap.CompressFormat.JPEG
        }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(format, 85, out)
            out.toByteArray()
        }
        // 输入框预览用的小缩略图(约 128px)
        val thumbSide = 128
        val thumbScale = maxOf(1, maxOf(bitmap.width, bitmap.height) / thumbSide)
        val thumbnail = android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width / thumbScale).coerceAtLeast(1),
            (bitmap.height / thumbScale).coerceAtLeast(1),
            true,
        )
        PreparedImage(Base64.encodeToString(bytes, Base64.NO_WRAP), outMime, thumbnail)
    } finally {
        bitmap.recycle()
    }
}
