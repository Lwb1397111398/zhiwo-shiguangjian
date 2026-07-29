package com.zhiwo.shiguangjian.data.db

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
        SpecialDateEntity::class
    ],
    version = 9,
    exportSchema = false
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

    companion object {
        private const val DATABASE_NAME = "zhiwo_shiguangjian"

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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
