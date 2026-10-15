package com.zhiwo.shiguangjian.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 留念照片（v15 起）：任务完成后用户自愿拍的纪念照，也供日记/每日评价读取。
 * 照片文件存在应用私有目录 filesDir/photos/，本表只存路径与归属日期；
 * 删除日记不会删照片（照片是独立纪念），删除照片会同时删文件（见 MomentPhotoRepository）。
 */
@Entity(tableName = "moment_photos", indices = [Index("date")])
data class MomentPhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: String,            // "yyyy-MM-dd"，按天聚合进日记/评价
    val path: String,            // 本地文件绝对路径（filesDir/photos/ 下）
    val label: String = "",      // 关联任务名或"手动补拍"，仅展示与提示 AI 用
    val source: String = "camera", // camera=拍照 / gallery=相册选图
    val createdAt: String        // ISO 时间戳
)
