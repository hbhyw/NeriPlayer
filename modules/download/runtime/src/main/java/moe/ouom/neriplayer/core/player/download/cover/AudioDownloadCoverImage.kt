package moe.ouom.neriplayer.core.player.download.cover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Locale
import moe.ouom.neriplayer.core.download.storage.metadata.isCoverPixelBudgetWithin

internal class AudioDownloadCoverImage(val bytes: ByteArray, val mimeType: String)

/**
 * 校验并（必要时）缩小下载到的封面。
 *
 * `ImageDecoder` 是 API 28 才有的能力，手表端最低支持 Android 8.1（API 27），
 * 因此 27 上退回 `BitmapFactory`：先用 inJustDecodeBounds 读尺寸，再按预算采样解码。
 */
internal fun prepareDownloadedCoverImage(bytes: ByteArray, maxBytes: Long): AudioDownloadCoverImage {
    if (bytes.isEmpty() || bytes.size.toLong() > maxBytes) {
        throw IOException("封面文件大小校验失败")
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        prepareWithImageDecoder(bytes, maxBytes)
    } else {
        prepareWithBitmapFactory(bytes, maxBytes)
    }
}

private fun prepareWithImageDecoder(bytes: ByteArray, maxBytes: Long): AudioDownloadCoverImage {
    var sourceFitsBudget = false
    var sourceMimeType = ""
    // 解码器在分配像素前采样，截断或损坏的图片仍由默认的严格解码检查拒绝
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        if (width <= 0 || height <= 0) throw IOException("封面图片尺寸无效")
        sourceFitsBudget = isCoverPixelBudgetWithin(width, height)
        sourceMimeType = info.mimeType
        var sampleSize = 1
        while (
            ((width.toLong() + sampleSize - 1) / sampleSize) *
                ((height.toLong() + sampleSize - 1) / sampleSize) > MAX_COVER_DECODE_PIXELS
        ) {
            sampleSize *= 2
        }
        decoder.setTargetSampleSize(sampleSize)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }
    return finalizeCoverImage(
        sourceBytes = bytes,
        bitmap = bitmap,
        maxBytes = maxBytes,
        sourceFitsBudget = sourceFitsBudget,
        sourceMimeType = sourceMimeType.ifBlank { sniffCoverMimeType(bytes) }
    )
}

private fun prepareWithBitmapFactory(bytes: ByteArray, maxBytes: Long): AudioDownloadCoverImage {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) throw IOException("封面图片尺寸无效")
    val sourceFitsBudget = isCoverPixelBudgetWithin(width, height)
    var sampleSize = 1
    while (
        ((width.toLong() + sampleSize - 1) / sampleSize) *
            ((height.toLong() + sampleSize - 1) / sampleSize) > MAX_COVER_DECODE_PIXELS
    ) {
        sampleSize *= 2
    }
    val bitmap = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    ) ?: throw IOException("封面图片解码失败")
    return finalizeCoverImage(
        sourceBytes = bytes,
        bitmap = bitmap,
        maxBytes = maxBytes,
        sourceFitsBudget = sourceFitsBudget,
        sourceMimeType = sniffCoverMimeType(bytes)
    )
}

private fun finalizeCoverImage(
    sourceBytes: ByteArray,
    bitmap: Bitmap,
    maxBytes: Long,
    sourceFitsBudget: Boolean,
    sourceMimeType: String
): AudioDownloadCoverImage {
    return try {
        if (!isCoverPixelBudgetWithin(bitmap.width, bitmap.height, MAX_COVER_DECODE_PIXELS)) {
            throw IOException("封面采样后尺寸仍超过限制")
        }
        if (sourceFitsBudget) {
            AudioDownloadCoverImage(sourceBytes, sourceMimeType)
        } else {
            val hasAlpha = bitmap.hasAlpha()
            val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val output = BoundedCoverOutputStream(maxBytes)
            if (!bitmap.compress(format, COVER_JPEG_QUALITY, output) || output.limitExceeded) {
                throw IOException("封面采样编码失败或超过大小限制")
            }
            val encoded = output.toByteArray()
            if (encoded.isEmpty()) throw IOException("封面采样编码为空")
            AudioDownloadCoverImage(encoded, if (hasAlpha) "image/png" else "image/jpeg")
        }
    } finally {
        bitmap.recycle()
    }
}

/** API 27 上拿不到 ImageDecoder 的 mimeType，用文件头嗅探顶替 */
private fun sniffCoverMimeType(bytes: ByteArray): String {
    if (bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    ) {
        return "image/png"
    }
    if (bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    ) {
        return "image/jpeg"
    }
    if (bytes.size >= 12 &&
        String(bytes, 0, 4, Charsets.US_ASCII).equals("RIFF", ignoreCase = true) &&
        String(bytes, 8, 4, Charsets.US_ASCII).lowercase(Locale.ROOT) == "webp"
    ) {
        return "image/webp"
    }
    return ""
}

private const val COVER_JPEG_QUALITY = 95
// 为每路封面解码限制像素数量，避免并发增强分配完整大图
internal const val MAX_COVER_DECODE_PIXELS = 4_000_000L

private class BoundedCoverOutputStream(private val maxBytes: Long) : OutputStream() {
    private val output = ByteArrayOutputStream()
    var limitExceeded = false
        private set

    override fun write(value: Int) {
        checkCapacity(1)
        output.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkCapacity(length)
        output.write(bytes, offset, length)
    }

    private fun checkCapacity(length: Int) {
        if (output.size().toLong() + length > maxBytes) {
            limitExceeded = true
            throw IOException("封面采样编码超过大小限制: $maxBytes")
        }
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}
