package com.zhiwo.shiguangjian.data.db

import android.content.ContentValues
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zhiwo.shiguangjian.data.db.dao.*
import com.zhiwo.shiguangjian.data.db.entity.*

@Database(
    entities = [
        RecordEntity::class,
        TaskEntity::class,
        TagEntity::class,
        RecordTagCrossRef::class,
        KeyInfoEntity::class,
        ReviewEntity::class,
        MemoryEntity::class,
        SettingEntity::class,
        DiaryEntity::class,
        SpecialDateEntity::class,
        OrganizeOpEntity::class,
        GoalEntity::class,
        PlanEntity::class,
        TaskOccurrenceEntity::class,
        DayOverrideEntity::class
    ],
    version = AppDatabase.VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordDao(): RecordDao
    abstract fun taskDao(): TaskDao
    abstract fun tagDao(): TagDao
    abstract fun keyInfoDao(): KeyInfoDao
    abstract fun reviewDao(): ReviewDao
    abstract fun memoryDao(): MemoryDao
    abstract fun settingDao(): SettingDao
    abstract fun diaryDao(): DiaryDao
    abstract fun specialDateDao(): SpecialDateDao
    abstract fun organizeOpDao(): OrganizeOpDao
    abstract fun goalDao(): GoalDao
    abstract fun planDao(): PlanDao
    abstract fun occurrenceDao(): TaskOccurrenceDao
    abstract fun dayOverrideDao(): DayOverrideDao

    companion object {
        /** 版本号只留这一处：失败页要说"从哪升到哪"，注解与运行时得是同一个数 */
        const val VERSION = 14

        private const val DATABASE_NAME = "zhiwo_shiguangjian"

        /** ADD COLUMN 没有 IF NOT EXISTS 形式；迁移被中断后重跑会撞 duplicate column，所以逐条探测 */
        private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
            query("PRAGMA table_info(`$table`)").use { c ->
                val i = c.getColumnIndexOrThrow("name")
                generateSequence { if (c.moveToNext()) c.getString(i) else null }
                    .any { it.equals(column, ignoreCase = true) }
            }

        private fun SupportSQLiteDatabase.addColumnIfMissing(table: String, ddl: String) {
            val column = ddl.substringAfter("ADD COLUMN `").substringBefore("`")
            if (!hasColumn(table, column)) execSQL("ALTER TABLE `$table` $ddl")
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `goals` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL, `description` TEXT NOT NULL DEFAULT '',
                        `startDate` TEXT NOT NULL DEFAULT '', `targetDate` TEXT NOT NULL DEFAULT '',
                        `status` TEXT NOT NULL DEFAULT 'active', `recordId` INTEGER,
                        `sortOrder` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL DEFAULT '')"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `plans` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `goalId` INTEGER,
                        `title` TEXT NOT NULL, `description` TEXT NOT NULL DEFAULT '',
                        `startDate` TEXT NOT NULL DEFAULT '', `endDate` TEXT NOT NULL DEFAULT '',
                        `status` TEXT NOT NULL DEFAULT 'active', `recordId` INTEGER,
                        `sortOrder` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL DEFAULT '',
                        FOREIGN KEY(`goalId`) REFERENCES `goals`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL)"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plans_goalId` ON `plans` (`goalId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `task_occurrences` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `taskId` INTEGER NOT NULL,
                        `date` TEXT NOT NULL, `status` TEXT NOT NULL DEFAULT 'pending',
                        `reasonCode` TEXT NOT NULL DEFAULT '', `reasonNote` TEXT NOT NULL DEFAULT '',
                        `actualMinutes` INTEGER NOT NULL DEFAULT 0, `note` TEXT NOT NULL DEFAULT '',
                        `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL DEFAULT '',
                        FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE)"""
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_task_occurrences_taskId_date` ON `task_occurrences` (`taskId`, `date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_occurrences_taskId` ON `task_occurrences` (`taskId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `day_overrides` (
                        `date` TEXT NOT NULL, `type` TEXT NOT NULL, `createdAt` TEXT NOT NULL,
                        PRIMARY KEY(`date`))"""
                )
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `occurredAt` TEXT NOT NULL DEFAULT ''")

                db.addColumnIfMissing("tasks", "ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'adhoc'")
                db.addColumnIfMissing("tasks", "ADD COLUMN `repeatRule` TEXT NOT NULL DEFAULT 'everyday'")
                db.addColumnIfMissing("tasks", "ADD COLUMN `weekdaysCsv` TEXT NOT NULL DEFAULT ''")
                db.addColumnIfMissing("tasks", "ADD COLUMN `intervalDays` INTEGER NOT NULL DEFAULT 1")
                db.addColumnIfMissing("tasks", "ADD COLUMN `dayPolicy` TEXT NOT NULL DEFAULT 'all'")
                db.addColumnIfMissing("tasks", "ADD COLUMN `startDate` TEXT NOT NULL DEFAULT ''")
                db.addColumnIfMissing("tasks", "ADD COLUMN `endDate` TEXT NOT NULL DEFAULT ''")
                db.addColumnIfMissing("tasks", "ADD COLUMN `scheduledDate` TEXT NOT NULL DEFAULT ''")
                db.addColumnIfMissing("tasks", "ADD COLUMN `remindTime` TEXT NOT NULL DEFAULT ''")
                db.addColumnIfMissing("tasks", "ADD COLUMN `durationMinutes` INTEGER NOT NULL DEFAULT 0")
                db.addColumnIfMissing("tasks", "ADD COLUMN `goalId` INTEGER REFERENCES `goals`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL")
                db.addColumnIfMissing("tasks", "ADD COLUMN `planId` INTEGER REFERENCES `plans`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL")
                db.addColumnIfMissing("tasks", "ADD COLUMN `status` TEXT NOT NULL DEFAULT 'active'")
                db.addColumnIfMissing("tasks", "ADD COLUMN `colorIndex` INTEGER NOT NULL DEFAULT 0")
                db.addColumnIfMissing("tasks", "ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_goalId` ON `tasks` (`goalId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_planId` ON `tasks` (`planId`)")

                backfill(db)
            }

            /**
             * 存量搬运。谓词三路并集：完成/放弃过的目标记录 category 已被改成 completed，
             * 只捞 category='goal' 会让用户过去完成的目标整层消失。
             */
            private fun backfill(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis().toString()
                val goalMap = HashMap<Long, Long>()
                db.query(
                    """SELECT r.id, r.title, r.content, r.summary, r.category, r.createdAt FROM records r
                       WHERE r.category = 'goal'
                          OR r.id IN (SELECT parentGoalId FROM tasks WHERE parentGoalId IS NOT NULL)
                          OR r.id IN (SELECT recordId FROM tasks WHERE taskType = 'goal')
                       ORDER BY r.id ASC"""
                ).use { c ->
                    val idCol = c.getColumnIndexOrThrow("id")
                    val titleCol = c.getColumnIndexOrThrow("title")
                    val contentCol = c.getColumnIndexOrThrow("content")
                    val summaryCol = c.getColumnIndexOrThrow("summary")
                    val catCol = c.getColumnIndexOrThrow("category")
                    val createdCol = c.getColumnIndexOrThrow("createdAt")
                    while (c.moveToNext()) {
                        val recordId = c.getLong(idCol)
                        val title = c.getString(titleCol).ifBlank {
                            c.getString(contentCol).trim().take(40).ifBlank { "未命名目标" }
                        }
                        val values = ContentValues().apply {
                            put("title", title)
                            put("description", c.getString(summaryCol) ?: "")
                            put("startDate", "")
                            put("targetDate", "")
                            put("status", mapLegacyGoalStatus(c.getString(catCol)))
                            put("recordId", recordId)
                            put("sortOrder", 0)
                            put("createdAt", c.getString(createdCol) ?: now)
                            put("updatedAt", "")
                        }
                        goalMap[recordId] = db.insert("goals", android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values)
                    }
                }

                val rows = mutableListOf<LegacyTaskRow>()
                db.query("SELECT * FROM tasks").use { c ->
                    val id = c.getColumnIndexOrThrow("id")
                    val content = c.getColumnIndexOrThrow("content")
                    val recordId = c.getColumnIndexOrThrow("recordId")
                    val parentGoalId = c.getColumnIndexOrThrow("parentGoalId")
                    val dueDate = c.getColumnIndexOrThrow("dueDate")
                    val taskType = c.getColumnIndexOrThrow("taskType")
                    val isCompleted = c.getColumnIndexOrThrow("isCompleted")
                    val completedAt = c.getColumnIndexOrThrow("completedAt")
                    val dailyCompletionDate = c.getColumnIndexOrThrow("dailyCompletionDate")
                    val isPerm = c.getColumnIndexOrThrow("isPermanentlyCompleted")
                    val createdAt = c.getColumnIndexOrThrow("createdAt")
                    while (c.moveToNext()) {
                        if (c.isNull(id)) continue
                        rows += LegacyTaskRow(
                            id = c.getLong(id),
                            content = c.getString(content) ?: "",
                            recordId = if (c.isNull(recordId)) null else c.getLong(recordId),
                            parentGoalId = if (c.isNull(parentGoalId)) null else c.getLong(parentGoalId),
                            dueDate = c.getString(dueDate) ?: "",
                            taskType = c.getString(taskType) ?: "",
                            isCompleted = c.getInt(isCompleted) != 0,
                            completedAt = if (c.isNull(completedAt)) null else c.getString(completedAt),
                            dailyCompletionDate = if (c.isNull(dailyCompletionDate)) null else c.getString(dailyCompletionDate),
                            isPermanentlyCompleted = c.getInt(isPerm) != 0,
                            calendarEventId = null,
                            createdAt = c.getString(createdAt) ?: ""
                        )
                    }
                }

                val today = com.zhiwo.shiguangjian.data.ai.DateFormats.nowDate()
                var occurrenceCount = 0
                rows.forEach { row ->
                    val (mapped, _) = mapLegacyTask(row, { goalMap[it] }, today)
                    db.execSQL(
                        """UPDATE `tasks` SET `kind`=?, `repeatRule`=?, `weekdaysCsv`=?, `intervalDays`=?,
                            `dayPolicy`=?, `startDate`=?, `endDate`=?, `scheduledDate`=?, `remindTime`=?,
                            `durationMinutes`=?, `goalId`=?, `planId`=?, `status`=?, `colorIndex`=?, `sortOrder`=?
                            WHERE `id`=?""".trimIndent(),
                        arrayOf(
                            mapped.kind, mapped.repeatRule, mapped.weekdaysCsv, mapped.intervalDays,
                            mapped.dayPolicy, mapped.startDate, mapped.endDate, mapped.scheduledDate,
                            mapped.remindTime, mapped.durationMinutes, mapped.goalId, null, mapped.status,
                            0, 0, mapped.id
                        )
                    )
                    mapped.occurrences.forEach { occ ->
                        val inserted = db.insert(
                            "task_occurrences",
                            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
                            ContentValues().apply {
                                put("taskId", occ.taskId); put("date", occ.date); put("status", occ.status)
                                put("reasonCode", ""); put("reasonNote", ""); put("actualMinutes", 0)
                                put("note", ""); put("createdAt", now); put("updatedAt", "")
                            }
                        )
                        if (inserted > 0) occurrenceCount++
                    }
                }

                // 自检：宁可大声失败，也不静默少搬一条
                val taskCount = db.query("SELECT COUNT(*) FROM tasks").use { c -> c.moveToFirst(); c.getInt(0) }
                if (taskCount != rows.size) {
                    throw IllegalStateException("v13 迁移自检失败：tasks 行数 $taskCount != 映射行数 ${rows.size}")
                }
                if (goalMap.size != db.query("SELECT COUNT(*) FROM goals").use { c -> c.moveToFirst(); c.getInt(0) }) {
                    throw IllegalStateException("v13 迁移自检失败：goals 行数与来源记录数不一致")
                }
                android.util.Log.i(
                    "AppDatabase",
                    "v13 迁移完成：任务 ${rows.size} 条、目标 ${goalMap.size} 个、历史打卡 $occurrenceCount 条"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 迁移失败时的抢救：把主库 + -wal + -shm 三件套改名备份后重建。
         * 备份落在应用外部文件目录（正式版从 databases/ 目录里取不出来，等于没有备份）。
         */
        fun rebuild(context: Context): AppDatabase {
            synchronized(this) {
                val dbFile = context.applicationContext.getDatabasePath(DATABASE_NAME)
                if (dbFile.exists()) {
                    try {
                        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(
                            dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                        )
                        raw.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                        raw.close()
                    } catch (_: Throwable) {
                    }
                    val stamp = System.currentTimeMillis()
                    val backupDir = context.getExternalFilesDir("migration_backup")
                        ?: java.io.File(context.filesDir, "migration_backup")
                    if (!backupDir.exists()) backupDir.mkdirs()
                    val destDir = java.io.File(backupDir, "pre_rebuild_$stamp")
                    destDir.mkdirs()
                    dbFile.copyTo(java.io.File(destDir, DATABASE_NAME), overwrite = true)
                    listOf("-wal", "-shm").forEach { suffix ->
                        java.io.File(dbFile.parentFile, "$DATABASE_NAME$suffix")
                            .takeIf { it.exists() }?.copyTo(java.io.File(destDir, "$DATABASE_NAME$suffix"), overwrite = true)
                    }
                    dbFile.delete()
                    java.io.File(dbFile.parentFile, "$DATABASE_NAME-wal").delete()
                    java.io.File(dbFile.parentFile, "$DATABASE_NAME-shm").delete()
                    DbGate.markRebuilt(context.applicationContext, destDir.absolutePath)
                } else {
                    DbGate.markRebuilt(context.applicationContext, "（原来就没有数据库文件）")
                }
                INSTANCE = null
                return getInstance(context)
            }
        }

        fun getInstance(context: Context): AppDatabase {
            // 冷启动只有一条路会清库：用户在失败页上打字确认后留下的标记（见 DbGate）。
            // 迁移异常本身绝不再触发清库 —— 以前是 catch 完直接 rebuild，用户只会看见"记录全空了"。
            if (DbGate.rebuildRequested(context.applicationContext)) {
                DbGate.takeRebuildRequest(context.applicationContext)
                return rebuild(context)
            }
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                    // 正式版与开发版都不允许静默清库：缺迁移必须抛出来，由 rebuild 的备份路径兜底
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN calendarEventId INTEGER")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS diaries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    date TEXT NOT NULL,
                    content TEXT NOT NULL,
                    mood TEXT NOT NULL DEFAULT '平淡',
                    createdAt TEXT NOT NULL
                )""")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diaries ADD COLUMN exported INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_diaries_date ON diaries (date)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS special_dates (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    month INTEGER NOT NULL,
                    day INTEGER NOT NULL,
                    isLunar INTEGER NOT NULL DEFAULT 0,
                    type TEXT NOT NULL DEFAULT 'birthday',
                    note TEXT,
                    createdAt TEXT NOT NULL
                )""")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE special_dates ADD COLUMN year INTEGER")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN status TEXT NOT NULL DEFAULT 'active'")
                db.execSQL("ALTER TABLE memories ADD COLUMN supersededBy INTEGER")
                db.execSQL("ALTER TABLE memories ADD COLUMN note TEXT")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS organize_ops (
                    id TEXT NOT NULL PRIMARY KEY,
                    batchId TEXT NOT NULL,
                    type TEXT NOT NULL,
                    status TEXT NOT NULL,
                    payloadJson TEXT NOT NULL,
                    previewJson TEXT,
                    sourceVersion TEXT,
                    checked INTEGER NOT NULL DEFAULT 1,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    appliedAt INTEGER,
                    error TEXT
                )""")
                db.execSQL("ALTER TABLE memories ADD COLUMN sourceRecordId INTEGER")
                db.execSQL("ALTER TABLE diaries ADD COLUMN sourceRecordIds TEXT")
                db.execSQL("ALTER TABLE diaries ADD COLUMN generationVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE diaries ADD COLUMN isUserEdited INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE organize_ops ADD COLUMN dependsOn TEXT")
                db.execSQL("ALTER TABLE reviews ADD COLUMN sourceRecordIds TEXT")
                db.execSQL("ALTER TABLE reviews ADD COLUMN generationVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE reviews ADD COLUMN isUserEdited INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
