package dev.pipilot.app.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import dev.pipilot.app.log.AppLog
import java.io.ByteArrayOutputStream

/** MIME 与 base64,附带小缩略图供输入框预览。 */
data class PreparedImage(
    val base64: String,
    val mimeType: String,
    val thumbnail: android.graphics.Bitmap,
    val sizeKb: Int,
)

private const val TAG = "ImagePrep"

/**
 * 将用户选取的图片压成远端模型可接受的大小:统一转 JPEG(截图的 PNG 原样传会到
 * 4-8MB base64,provider 必挂),最长边 1568(视觉模型推荐上限),质量 82。
 * 字节一次性读入内存再解码,避免部分相册 URI 不允许二次打开流。
 * 所有失败路径都打日志:没有日志就没法在实机上定位"读取失败"。
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
        // 解码器不认这个格式(如老系统上的 HEIC):mime 为 null,原始字节再大也没用
        AppLog.e(TAG, "decode bounds failed: mime=${bounds.outMimeType} bytes=${bytes.size} — 格式本机解码器不支持?")
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
        // 输入框预览用的小缩略图(约 128px)
        val thumbScale = maxOf(1, maxOf(bitmap.width, bitmap.height) / 128)
        val thumbnail = android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width / thumbScale).coerceAtLeast(1),
            (bitmap.height / thumbScale).coerceAtLeast(1),
            true,
        )
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
