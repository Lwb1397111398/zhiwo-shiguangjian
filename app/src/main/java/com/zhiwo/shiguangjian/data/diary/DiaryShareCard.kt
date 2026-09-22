package com.zhiwo.shiguangjian.data.diary

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity
import java.io.File

/**
 * 日记分享图片卡片：用 Canvas 直接绘制（背景 + 情绪色点 + 日期 + 正文 StaticLayout 自动换行），
 * 输出 PNG 到 cacheDir 并通过 FileProvider 分享。
 */
object DiaryShareCard {

    private const val WIDTH = 1080
    private const val PADDING = 72f

    /** 情绪主题色（ARGB），与 DiaryScreen 的 MoodColors 保持一致 */
    private fun moodColorArgb(mood: String): Int = when (mood) {
        "开心" -> 0xFFF2B04D.toInt(); "平淡" -> 0xFF6FB899.toInt(); "低落" -> 0xFF7C93A8.toInt()
        "焦虑" -> 0xFFE27C5B.toInt(); "充实" -> 0xFF5FAF7B.toInt(); "疲惫" -> 0xFF978BB8.toInt()
        "感动" -> 0xFFE296A8.toInt(); "释然" -> 0xFF7BB4D0.toInt(); else -> 0xFFA8B4BE.toInt()
    }

    fun render(context: Context, diary: DiaryEntity): File {
        val content = if (diary.content.length > 420) diary.content.take(420) + "…" else diary.content

        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2E3A44.toInt(); textSize = 40f
        }
        val bodyLayout = StaticLayout.Builder
            .obtain(content, 0, content.length, bodyPaint, (WIDTH - PADDING * 2).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(16f, 1.15f)
            .build()

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6E7B87.toInt(); textSize = 34f
        }
        val moodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = moodColorArgb(diary.mood); textSize = 38f; isFakeBoldText = true
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9AA7B2.toInt(); textSize = 28f
        }

        val height = (PADDING + 34 + 48 + 40 + bodyLayout.height + 90 + PADDING).toInt()
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 背景
        canvas.drawColor(0xFFF7F5F1.toInt())
        // 卡片底
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(
            RectF(PADDING * 0.4f, PADDING * 0.4f, WIDTH - PADDING * 0.4f, height - PADDING * 0.4f),
            40f, 40f, cardPaint
        )
        // 左侧情绪色条
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = moodColorArgb(diary.mood) }
        canvas.drawRoundRect(
            RectF(PADDING * 0.4f, PADDING * 0.4f, PADDING * 0.4f + 16f, height - PADDING * 0.4f),
            8f, 8f, accent
        )
        // 情绪 + 日期头部
        canvas.drawText(diary.mood, PADDING, PADDING + 34, moodPaint)
        canvas.drawText(diary.date, PADDING, PADDING + 34 + 48, headerPaint)
        // 正文
        canvas.save()
        canvas.translate(PADDING, PADDING + 34 + 48 + 40)
        bodyLayout.draw(canvas)
        canvas.restore()
        // 页脚
        canvas.drawText("知我时光笺 · 每日一笺", PADDING, height - PADDING + 8f, footerPaint)

        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(shareDir, "diary_${diary.date}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }
        bitmap.recycle()
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
