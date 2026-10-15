package com.zhiwo.shiguangjian.data.photo

import android.content.Context
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.dao.MomentPhotoDao
import com.zhiwo.shiguangjian.data.db.entity.MomentPhotoEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * 留念照片的统一入口：DB 行与磁盘文件必须同进退（删记录必删文件，写记录失败要回收文件）。
 */
class MomentPhotoRepository(private val context: Context, private val dao: MomentPhotoDao) {

    fun observeByDate(date: String): Flow<List<MomentPhotoEntity>> = dao.observeByDate(date)

    suspend fun photosOn(date: String): List<MomentPhotoEntity> = dao.getByDate(date)

    /** 相机直写成功的文件入库（入库前先压一遍，原图太大既占空间也拖慢识图请求） */
    suspend fun adoptCameraFile(file: File, date: String, label: String): MomentPhotoEntity? {
        val saved = MomentPhotoStore.compressInPlace(file) ?: file
        return insert(saved, date, label, "camera")
    }

    /** 相册 URI 复制进私有目录入库 */
    suspend fun importFromUri(uri: android.net.Uri, date: String, label: String): MomentPhotoEntity? {
        val file = MomentPhotoStore.importFromUri(context, uri) ?: return null
        return insert(file, date, label, "gallery")
    }

    private suspend fun insert(file: File, date: String, label: String, source: String): MomentPhotoEntity? = try {
        val entity = MomentPhotoEntity(
            date = date,
            path = file.absolutePath,
            label = label,
            source = source,
            createdAt = DateFormats.nowDateTimeIso()
        )
        val id = dao.insert(entity)
        entity.copy(id = id)
    } catch (e: Throwable) {
        // 写库失败就别把文件孤儿在磁盘上
        MomentPhotoStore.deleteQuietly(file)
        null
    }

    suspend fun delete(photo: MomentPhotoEntity) {
        dao.deleteById(photo.id)
        MomentPhotoStore.deleteQuietly(File(photo.path))
    }

    /**
     * 给视觉 AI 的图：当天照片按时间取最多 [MAX_AI_PHOTOS] 张转 data URL。
     * 张数上限是请求体大小的硬闸——一次 5 张 1024px 图就能到几 MB，
     * 日记/评价本来就要传全文本，不能在这挤爆请求。
     */
    suspend fun aiPhotoUrls(date: String): List<String> =
        dao.getByDate(date)
            .filter { File(it.path).exists() }
            .take(MAX_AI_PHOTOS)
            .mapNotNull { MomentPhotoStore.toDataUrl(File(it.path)) }

    /** prompt 里照片的说明文字（与 aiPhotoUrls 同步取图，保证数量口径一致） */
    suspend fun aiPhotosNote(date: String): String {
        val photos = dao.getByDate(date).filter { File(it.path).exists() }.take(MAX_AI_PHOTOS)
        if (photos.isEmpty()) return ""
        val labels = photos.joinToString("、") { "「${it.label.ifBlank { "留念照" }}」" }
        return "今天拍了 ${photos.size} 张留念照片（$labels），图像已附在消息里。"
    }

    companion object {
        const val MAX_AI_PHOTOS = 4
    }
}
