package com.zhiwo.shiguangjian.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 留念照片的文件侧：统一存在应用私有目录 filesDir/photos/（卸载即清、不占公共相册、无需存储权限）。
 * 系统相机通过 FileProvider 直接往这里写（见 file_paths.xml 的 moment_photos）；
 * 相册选来的图复制进来并压到长边 ≤1600，避免原图几十兆把目录撑爆。
 */
object MomentPhotoStore {

    const val DIR = "photos"

    fun photosDir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** 给系统相机用的目标空文件（TakePicture 要求调用方先建好文件再给 URI） */
    fun newCameraTarget(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(photosDir(context), "IMG_${stamp}_${(1000..9999).random()}.jpg")
    }

    /**
     * 把相册 URI 的图复制进 photos 目录：解码降采样到长边 ≤1600、JPEG 85 保存。
     * 解码/读取失败返回 null，调用方决定怎么提示，绝不写半成品文件进库。
     */
    fun importFromUri(context: Context, uri: Uri): File? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 1600)
            }
            val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            val scaled = scaleToMax(src, 1600)
            val out = newCameraTarget(context)
            out.outputStream().use { os ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, os)
            }
            if (scaled !== src) scaled.recycle()
            src.recycle()
            out
        } catch (e: Throwable) {
            null
        }
    }

    /** 相机写回的原文件也统一压一遍（相机原图常常 4000px+/8MB） */
    fun compressInPlace(file: File, maxSide: Int = 1600, quality: Int = 85): File? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            }
            val src = BitmapFactory.decodeFile(file.path, opts) ?: return null
            val scaled = scaleToMax(src, maxSide)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { os -> scaled.compress(Bitmap.CompressFormat.JPEG, quality, os) }
            if (scaled !== src) scaled.recycle()
            src.recycle()
            if (tmp.renameTo(file)) file else { tmp.delete(); file }
        } catch (e: Throwable) {
            null
        }
    }

    /** 给视觉 AI 用：降到长边 ≤1024、JPEG 78，转成 OpenAI 识图格式的 data URL */
    fun toDataUrl(file: File, maxSide: Int = 1024, quality: Int = 78): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            }
            val src = BitmapFactory.decodeFile(file.path, opts) ?: return null
            val scaled = scaleToMax(src, maxSide)
            val baos = ByteArrayOutputStream()
            val bmp = if (scaled === src) src else scaled
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            if (scaled !== src) { scaled.recycle() } else { src.recycle() }
            "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Throwable) {
            null
        }
    }

    fun deleteQuietly(file: File) {
        try { if (file.exists()) file.delete() } catch (_: Throwable) {}
    }

    /** 算 inSampleSize：2 的幂，降采样后长边仍 ≥maxSide 的一半即可，省内存 */
    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /** inSampleSize 只按 2 的幂粗降，精确缩到 maxSide 再补一次矩阵缩放 */
    private fun scaleToMax(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createBitmap(
            src, 0, 0, src.width, src.height,
            Matrix().apply { setScale(ratio, ratio) }, true
        )
    }
}
