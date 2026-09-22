package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.export.ExportDataBuilder
import com.zhiwo.shiguangjian.data.repository.RecordRepository
import com.zhiwo.shiguangjian.data.repository.ReviewRepository
import com.zhiwo.shiguangjian.data.repository.MemoryRepository
import com.zhiwo.shiguangjian.data.repository.TaskRepository
import com.zhiwo.shiguangjian.data.repository.SecureSettingsRepository
import com.zhiwo.shiguangjian.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*

data class StorageInfo(
    val records: Int,
    val tasks: Int,
    val tags: Int,
    val reviews: Int,
    val memories: Int,
    /** 数据库目录里是否存在迁移失败时的备份文件（.migration_backup_） */
    val hasMigrationBackup: Boolean = false,
    /** 给设置页照抄的一句人话：数据库曾因升级失败被重建过，备份在哪 */
    val migrationNote: String = ""
)

data class CategoryInfo(
    val id: String,
    val name: String,
    val icon: String,
    val color: String
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhiwoApplication
    private val settingsRepo = SettingsRepository(app.database.settingDao())
    private val recordRepo = RecordRepository(
        app.database, app.database.recordDao(), app.database.taskDao(),
        app.database.tagDao(), app.database.keyInfoDao()
    )
    private val taskRepo = TaskRepository(app.database.taskDao())
    private val reviewRepo = ReviewRepository(app.database.reviewDao())
    private val memoryRepo = MemoryRepository(app.database.memoryDao())
    private val aiRepo get() = app.aiRepo
    private val secureSettingsRepo = SecureSettingsRepository(app)
    private val gson = Gson()

    private val _apiBaseUrl = MutableStateFlow("")
    val apiBaseUrl: StateFlow<String> = _apiBaseUrl

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey

    private val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName

    private val _darkModePref = MutableStateFlow("auto")
    val darkModePref: StateFlow<String> = _darkModePref

    private val _autoCalendarSync = MutableStateFlow(true)
    val autoCalendarSync: StateFlow<Boolean> = _autoCalendarSync

    private val _smartReminder = MutableStateFlow(true)
    val smartReminder: StateFlow<Boolean> = _smartReminder

    private val _storageInfo = MutableStateFlow<StorageInfo?>(null)
    val storageInfo: StateFlow<StorageInfo?> = _storageInfo

    private val _categories = MutableStateFlow<List<CategoryInfo>>(emptyList())
    val categories: StateFlow<List<CategoryInfo>> = _categories

    init {
        loadSettings()
        loadStorageInfo()
        loadCategories()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            try {
                _apiBaseUrl.value = settingsRepo.getSetting("apiBaseUrl") ?: ""
                _apiKey.value = secureSettingsRepo.getApiKey()
                _modelName.value = settingsRepo.getSetting("modelName") ?: ""
                val saved = settingsRepo.getSetting("darkMode")
                _darkModePref.value = when (saved) {
                    "true" -> "on"   // 老版本迁移
                    "false" -> "off" // 老版本迁移
                    null -> "auto"
                    else -> saved    // 已是新格式 "auto"/"on"/"off"
                }
                _autoCalendarSync.value = settingsRepo.getSetting("autoCalendarSync") != "false"
                _smartReminder.value = settingsRepo.getSetting("smartReminder") != "false"
                aiRepo.configureFromSettings(settingsRepo, secureSettingsRepo)
                _apiKey.value = secureSettingsRepo.getApiKey()
            } catch (e: Throwable) {
                android.util.Log.e("SettingsVM", "加载设置失败", e)
            }
        }
    }

    fun refreshStorageInfo() { loadStorageInfo() }

    private fun loadStorageInfo() {
        viewModelScope.launch {
            try {
                _storageInfo.value = withContext(Dispatchers.IO) {
                    val dbDir = app.getDatabasePath("zhiwo_shiguangjian").parentFile
                    val hasBackup = dbDir?.listFiles()
                        ?.any { it.name.contains(".migration_backup_") } == true
                    // rebuild() 现在把备份放到外部私有目录（databases/ 里正式版根本取不出来）
                    val rebuildDir = app.getExternalFilesDir("migration_backup")
                    val hasRebuildBackup = rebuildDir?.listFiles()?.isNotEmpty() == true
                    val rebuilt = com.zhiwo.shiguangjian.data.db.DbGate.rebuiltNotice(app)
                    val note = rebuilt?.let { (at, path) ->
                        "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(at))} 数据库升级失败后按你的确认重建过一次，" +
                            "原库备份在：$path"
                    }
                    StorageInfo(
                        records = recordRepo.getRecordCount(),
                        tasks = taskRepo.getTaskCount(),
                        tags = app.database.tagDao().getTagCount(),
                        reviews = reviewRepo.getReviewCount(),
                        memories = memoryRepo.getMemoryCount(),
                        hasMigrationBackup = hasBackup || hasRebuildBackup || note != null,
                        migrationNote = note
                            ?: if (hasBackup || hasRebuildBackup)
                                "检测到历史数据库备份（升级时曾自动恢复），原数据保存在备份文件中" else ""
                    )
                }
            } catch (e: Throwable) {
                android.util.Log.e("SettingsVM", "加载存储信息失败", e)
            }
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            val json = settingsRepo.getSetting("categories")
            if (json != null) {
                try {
                    val type = object : TypeToken<List<CategoryInfo>>() {}.type
                    _categories.value = gson.fromJson(json, type)
                } catch (_: Exception) {
                    _categories.value = getDefaultCategories()
                }
            } else {
                _categories.value = getDefaultCategories()
            }
        }
    }

    private fun getDefaultCategories(): List<CategoryInfo> = listOf(
        CategoryInfo("todo", "待办事项", "📝", "#6B8E9F"),
        CategoryInfo("goal", "目标设定", "🎯", "#F7A8B8"),
        CategoryInfo("idea", "想法灵感", "💡", "#98D8C8"),
        CategoryInfo("emotion", "情绪记录", "💭", "#FFD166"),
        CategoryInfo("question", "问题思考", "❓", "#A78BFA"),
        CategoryInfo("study", "学习笔记", "📚", "#84A59D"),
        CategoryInfo("other", "其他", "📌", "#999999"),
        CategoryInfo("completed", "已完成", "✅", "#98D8C8")
    )

    fun updateApiBaseUrl(value: String) { _apiBaseUrl.value = value }
    fun updateApiKey(value: String) { _apiKey.value = value }
    fun updateModelName(value: String) { _modelName.value = value }

    fun saveApiConfig(onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        // 保存前校验：URL 缺协议前缀时 AiRepository.configure 会静默失败，这里立即报错
        val url = _apiBaseUrl.value.trim()
        if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            onError("API 地址必须以 http:// 或 https:// 开头（如 https://api.deepseek.com/v1）")
            return
        }
        viewModelScope.launch {
            try {
                settingsRepo.setSetting("apiBaseUrl", url)
                secureSettingsRepo.setApiKey(_apiKey.value)
                settingsRepo.deleteSetting("apiKey")
                settingsRepo.setSetting("modelName", _modelName.value)
                aiRepo.configure(url, _apiKey.value, _modelName.value)
                onSuccess()
            } catch (e: Throwable) {
                android.util.Log.e("SettingsVM", "保存配置失败", e)
                onError("保存失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun setDarkModePref(pref: String) {
        val oldValue = _darkModePref.value
        _darkModePref.value = pref
        viewModelScope.launch {
            try {
                settingsRepo.setSetting("darkMode", pref)
            } catch (e: Throwable) {
                android.util.Log.e("SettingsVM", "setDarkModePref failed", e)
                _darkModePref.value = oldValue
            }
        }
    }

    fun toggleAutoCalendarSync(enabled: Boolean) {
        val oldValue = _autoCalendarSync.value
        _autoCalendarSync.value = enabled
        viewModelScope.launch {
            try {
                settingsRepo.setSetting("autoCalendarSync", enabled.toString())
            } catch (e: Throwable) {
                android.util.Log.e("SettingsVM", "toggleAutoCalendarSync failed", e)
                _autoCalendarSync.value = oldValue
            }
        }
    }

    fun toggleSmartReminder(enabled: Boolean) {
        val oldValue = _smartReminder.value
        _smartReminder.value = enabled
        viewModelScope.launch {
            try {
                settingsRepo.setSetting("smartReminder", enabled.toString())
                // 立即生效：开则重设固定闹钟，关则全部取消
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val app = getApplication<Application>()
                    if (enabled) {
                        com.zhiwo.shiguangjian.alarm.AlarmScheduler.syncFixedAlarms(app)
                    } else {
                        com.zhiwo.shiguangjian.alarm.AlarmScheduler.cancelFixedAlarms(app)
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("SettingsVM", "toggleSmartReminder failed", e)
                _smartReminder.value = oldValue
            }
        }
    }

    fun testConnection(callback: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                aiRepo.configure(_apiBaseUrl.value, _apiKey.value, _modelName.value)
                if (!aiRepo.isConfigured) {
                    callback(false, "❌ 请填写完整的 API 配置")
                    return@launch
                }
                val success = aiRepo.testConnection()
                callback(success, if (success) "✅ 连接成功！" else "❌ 连接失败")
            } catch (e: Throwable) {
                android.util.Log.e("SettingsVM", "testConnection 异常", e)
                callback(false, "❌ ${e.message}")
            }
        }
    }

    // ========== 分类管理 ==========

    fun updateCategory(index: Int, cat: CategoryInfo) {
        val list = _categories.value.toMutableList()
        if (index in list.indices) {
            list[index] = cat
            _categories.value = list
        }
    }

    fun addCategory(name: String, icon: String, color: String) {
        val list = _categories.value.toMutableList()
        list.add(CategoryInfo("custom_${System.currentTimeMillis()}", name, icon, color))
        _categories.value = list
    }

    fun removeCategory(index: Int) {
        val list = _categories.value.toMutableList()
        if (index in list.indices) {
            val cat = list[index]
            if (!cat.id.startsWith("custom_")) return // 不能删除系统分类
            list.removeAt(index)
            _categories.value = list
        }
    }

    fun saveCategories() {
        viewModelScope.launch {
            val json = gson.toJson(_categories.value)
            settingsRepo.setSetting("categories", json)
        }
    }

    fun isSystemCategory(id: String): Boolean {
        return listOf("todo", "goal", "idea", "emotion", "question", "study", "other", "completed").contains(id)
    }

    // ========== 数据导出 ==========

    suspend fun buildExportJson(): String = withContext(Dispatchers.IO) {
        val exportData = ExportDataBuilder.build(
            exportedAt = DateFormats.nowDateTimeIso(),
            records = recordRepo.getAllRecords().first(),
            tasks = taskRepo.getAllTasks().first(),
            tags = app.database.tagDao().getAllTags().first(),
            recordTags = app.database.tagDao().getAllRecordTagCrossRefs(),
            keyInfos = app.database.keyInfoDao().getAllKeyInfos(),
            reviews = reviewRepo.getAllReviews().first(),
            memories = memoryRepo.getAllMemories().first(),
            diaries = app.database.diaryDao().getAllDiaries().first(),
            settings = settingsRepo.getAllSettings().first(),
            goals = app.database.goalDao().getAllGoals().first(),
            plans = app.database.planDao().getAllPlans().first(),
            taskOccurrences = app.database.occurrenceDao().observeInRange("0000-01-01", "9999-12-31").first(),
            dayOverrides = app.database.dayOverrideDao().getAll().first()
        )
        gson.toJson(exportData)
    }
}
