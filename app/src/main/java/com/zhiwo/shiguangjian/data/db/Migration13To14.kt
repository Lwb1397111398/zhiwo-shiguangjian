package com.zhiwo.shiguangjian.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v13 → v14。SQL 清单来自 [Migration13To14Sql]（由 13.json 生成），这里只负责"搬家前后各按一次指印"。
 *
 * 指纹故意**不含** goalId / endDate —— 这两列本来就是要改值的；其余任何一项对不上都说明搬错了，
 * 直接抛异常让 Room 的整体事务回滚（库停在 v13），而不是留下半迁移状态。
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val before = Fingerprint.capture(db)
        val fkBefore = fkViolations(db)

        Migration13To14Sql.statements().forEach { db.execSQL(it) }

        val after = Fingerprint.capture(db)
        if (before != after) {
            throw IllegalStateException("v13→v14 数据指纹不一致，已回滚：升级前 $before，升级后 $after")
        }
        val taskIndexes = count(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name LIKE 'index_tasks_%'")
        val occIndexes = count(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name LIKE 'index_task_occurrences_%'")
        if (taskIndexes != Migration13To14Sql.TASKS_INDEXES.size.toLong() ||
            occIndexes != Migration13To14Sql.OCCURRENCES_INDEXES.size.toLong()
        ) {
            throw IllegalStateException(
                "v13→v14 索引数量不对（tasks=$taskIndexes 期望=${Migration13To14Sql.TASKS_INDEXES.size}，" +
                    "occurrences=$occIndexes 期望=${Migration13To14Sql.OCCURRENCES_INDEXES.size}），已回滚"
            )
        }
        val fkAfter = fkViolations(db)
        if (fkAfter > fkBefore) {
            throw IllegalStateException("v13→v14 之后外键违规变多（$fkBefore → $fkAfter），已回滚")
        }
        android.util.Log.i(
            "Migration13To14",
            "v13→v14 完成：$before；外键违规 $fkBefore→$fkAfter（历史遗留，不清也不挡升级）"
        )
    }
}

private data class Fingerprint(
    val tasks: Long,
    val taskIds: Long,
    val taskContentLen: Long,
    val occurrences: Long,
    val occurrenceIds: Long,
    val done: Long,
    val notDone: Long,
    val pending: Long,
    val overrides: Long
) {
    companion object {
        fun capture(db: SupportSQLiteDatabase) = Fingerprint(
            tasks = count(db, "SELECT COUNT(*) FROM `tasks`"),
            taskIds = count(db, "SELECT COALESCE(SUM(`id`), 0) FROM `tasks`"),
            taskContentLen = count(db, "SELECT COALESCE(SUM(LENGTH(`content`)), 0) FROM `tasks`"),
            occurrences = count(db, "SELECT COUNT(*) FROM `task_occurrences`"),
            occurrenceIds = count(db, "SELECT COALESCE(SUM(`id`), 0) FROM `task_occurrences`"),
            done = count(db, "SELECT COUNT(*) FROM `task_occurrences` WHERE `status` = 'done'"),
            notDone = count(db, "SELECT COUNT(*) FROM `task_occurrences` WHERE `status` = 'not_done'"),
            pending = count(db, "SELECT COUNT(*) FROM `task_occurrences` WHERE `status` = 'pending'"),
            overrides = count(db, "SELECT COUNT(*) FROM `day_overrides`")
        )
    }
}

private fun count(db: SupportSQLiteDatabase, sql: String): Long =
    db.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

/** 只看有没有违规行，不比 0：历史脏数据不该把用户 100% 关在失败页外 */
private fun fkViolations(db: SupportSQLiteDatabase): Long = try {
    db.query("PRAGMA foreign_key_check").use { it.count.toLong() }
} catch (e: Exception) {
    android.util.Log.w("Migration13To14", "foreign_key_check 读失败，按 0 处理: ${e.message}")
    0L
}
