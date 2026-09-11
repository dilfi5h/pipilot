package dev.pipilot.app.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** MIME 与 base64,附带小缩略图供输入框预览。 */
data class PreparedImage(
    val base64: String,
    val mimeType: String,
    val thumbnail: android.graphics.Bitmap,
    val sizeKb: Int,
)

/**
 * 将用户选取的图片压成远端模型可接受的大小:统一转 JPEG(截图的 PNG 原样传会到
 * 4-8MB base64,provider 必挂),最长边 1568(视觉模型推荐上限),质量 82。
 */
fun prepareImageForUpload(context: Context, uri: Uri, maxSide: Int = 1568): PreparedImage? {
    val resolver = context.contentResolver
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
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        }
        // 输入框预览用的小缩略图(约 128px)
        val thumbScale = maxOf(1, maxOf(bitmap.width, bitmap.height) / 128)
        val thumbnail = android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width / thumbScale).coerceAtLeast(1),
            (bitmap.height / thumbScale).coerceAtLeast(1),
            true,
        )
        PreparedImage(
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            mimeType = "image/jpeg",
            thumbnail = thumbnail,
            sizeKb = bytes.size / 1024,
        )
    } finally {
        bitmap.recycle()
    }
}
