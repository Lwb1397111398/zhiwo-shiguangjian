package com.zhiwo.shiguangjian.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v14 → v15：新增「留念照片」表（任务完成后自愿拍的纪念照，日记/评价按天读取）。
 * 纯建表，不动任何既有数据，所以不需要 13→14 那样的数据指纹对账；
 * IF NOT EXISTS + 逐列不存在才加，半途失败重跑也不会撞 duplicate table。
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `moment_photos` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `date` TEXT NOT NULL, `path` TEXT NOT NULL,
                `label` TEXT NOT NULL DEFAULT '',
                `source` TEXT NOT NULL DEFAULT 'camera',
                `createdAt` TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_photos_date` ON `moment_photos` (`date`)")
        android.util.Log.i("Migration14To15", "v14→v15 完成：moment_photos 建表")
    }
}
