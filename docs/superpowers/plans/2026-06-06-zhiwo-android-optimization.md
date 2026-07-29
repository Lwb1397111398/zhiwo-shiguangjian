# zhiwo-android Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按审查建议完成 zhiwo-android 的安全、稳定性、权限、导出、日历同步和结构优化。

**Architecture:** 采用分阶段改造：先保证发布安全与数据稳定，再修正系统权限和外部副作用，最后收敛 ViewModel 过大职责。数据库写入保持事务内原子性，日历、闹钟、通知等系统副作用放在事务完成后执行。API Key 从 Room settings 分离到加密存储，Room 继续保存非敏感配置。

**Tech Stack:** Kotlin 1.9.24, Android Gradle Plugin 8.2.2, Jetpack Compose, Room 2.6.1, Retrofit/OkHttp, Coroutines, Android AlarmManager, CalendarContract, Activity Result API.

---

## 约束

- 当前目录 `E:/AI Agent/work area/zhiwo-shiguangjian/zhiwo-android` 不是 git 仓库，因此计划不包含提交步骤。
- 每个阶段完成后运行 `./gradlew :app:compileDebugKotlin`。
- 修改发布构建后运行 `./gradlew :app:assembleRelease`。
- 不做产品功能扩展，只修复审查中确认的问题。

---

## 文件结构

### 需要修改

- `app/build.gradle.kts`：release R8、lint 策略、Jetpack Security 依赖。
- `app/src/main/AndroidManifest.xml`：明文网络配置、权限策略、FileProvider（如采用分享导出）。
- `app/src/main/res/xml/network_security_config.xml`：release 默认不允许全局明文。
- `app/src/main/java/com/zhiwo/shiguangjian/data/db/AppDatabase.kt`：Room migration、正式移除破坏性迁移。
- `app/src/main/java/com/zhiwo/shiguangjian/data/db/entity/TaskEntity.kt`：增加日历事件 ID 字段。
- `app/src/main/java/com/zhiwo/shiguangjian/data/repository/SettingsRepository.kt`：非敏感配置仍走 Room。
- `app/src/main/java/com/zhiwo/shiguangjian/data/repository/SecureSettingsRepository.kt`：新增 API Key 加密存储。
- `app/src/main/java/com/zhiwo/shiguangjian/data/ai/AiRepository.kt`：配置读取拆分、AI 失败不伪装成功。
- `app/src/main/java/com/zhiwo/shiguangjian/ui/viewmodel/InputViewModel.kt`：事务写入、AI 失败状态。
- `app/src/main/java/com/zhiwo/shiguangjian/ui/screens/InputScreen.kt`：展示 AI 失败与重试/保存提示。
- `app/src/main/java/com/zhiwo/shiguangjian/alarm/BootReceiver.kt`：使用 `goAsync()`。
- `app/src/main/java/com/zhiwo/shiguangjian/alarm/SmartScheduleManager.kt`：返回并保存日历事件 ID，取消时删除日历事件。
- `app/src/main/java/com/zhiwo/shiguangjian/alarm/AlarmScheduler.kt`：闹钟取消保持职责清晰。
- `app/src/main/java/com/zhiwo/shiguangjian/notification/NotificationHelper.kt`：发送前检查通知权限。
- `app/src/main/java/com/zhiwo/shiguangjian/calendar/CalendarHelper.kt`：补充删除事件接口。
- `app/src/main/java/com/zhiwo/shiguangjian/MainActivity.kt`：权限按需请求入口。
- `app/src/main/java/com/zhiwo/shiguangjian/ui/viewmodel/SettingsViewModel.kt`：API Key 加密存储、导出逻辑更新。
- `app/src/main/java/com/zhiwo/shiguangjian/ui/screens/SettingsScreen.kt`：导出触发、权限提示、API Key 保存交互保持现有风格。
- `app/src/main/java/com/zhiwo/shiguangjian/ui/viewmodel/RecordListViewModel.kt`：移出整理建议应用逻辑、修正 Flow 缓存。

### 需要新增

- `app/src/main/java/com/zhiwo/shiguangjian/data/repository/SecureSettingsRepository.kt`：API Key 加密存储。
- `app/src/main/java/com/zhiwo/shiguangjian/data/organize/RecordOrganizer.kt`：承接整理建议应用逻辑。
- `app/src/main/res/xml/file_paths.xml`：如导出采用 FileProvider 分享。

---

## Task 1: 发布安全与构建基线

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/xml/network_security_config.xml`

- [ ] **Step 1: 修改 release 配置**

在 `app/build.gradle.kts` 中把 release 改为开启 R8：

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

- [ ] **Step 2: 修改 lint 策略**

把 lint 改为错误阻断：

```kotlin
lint {
    abortOnError = true
}
```

- [ ] **Step 3: 增加 Jetpack Security 依赖**

在 dependencies 增加：

```kotlin
implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

- [ ] **Step 4: 收紧明文网络**

`network_security_config.xml` 改为只允许本地开发地址明文：

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>
        <domain includeSubdomains="true">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

- [ ] **Step 5: Manifest 移除全局明文开关**

删除 `android:usesCleartextTraffic="true"`，保留 `android:networkSecurityConfig="@xml/network_security_config"`。

- [ ] **Step 6: 验证**

Run:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleRelease
```

Expected: 两条命令均成功。

---

## Task 2: API Key 加密存储

**Files:**
- Create: `app/src/main/java/com/zhiwo/shiguangjian/data/repository/SecureSettingsRepository.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/data/ai/AiRepository.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/ui/viewmodel/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/ui/viewmodel/InputViewModel.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/ui/viewmodel/ReviewViewModel.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/ui/viewmodel/RecordListViewModel.kt`

- [ ] **Step 1: 新增加密仓库**

新增 `SecureSettingsRepository.kt`：

```kotlin
package com.zhiwo.shiguangjian.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureSettingsRepository(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val preferences = EncryptedSharedPreferences.create(
        context,
        "secure_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getApiKey(): String = preferences.getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(value: String) {
        preferences.edit().putString(KEY_API_KEY, value).apply()
    }

    fun clearApiKey() {
        preferences.edit().remove(KEY_API_KEY).apply()
    }

    private companion object {
        const val KEY_API_KEY = "apiKey"
    }
}
```

- [ ] **Step 2: 修改 AI 配置读取接口**

`AiRepository.configureFromSettings()` 改为接收 `SecureSettingsRepository`：

```kotlin
suspend fun configureFromSettings(
    settingsRepo: SettingsRepository,
    secureSettingsRepo: SecureSettingsRepository
) {
    try {
        val baseUrl = settingsRepo.getSetting("apiBaseUrl") ?: ""
        val apiKey = secureSettingsRepo.getApiKey()
        val modelName = settingsRepo.getSetting("modelName") ?: ""
        configure(baseUrl, apiKey, modelName)
    } catch (e: Throwable) {
        android.util.Log.e("AiRepository", "从设置加载 AI 配置失败", e)
    }
}
```

- [ ] **Step 3: 修改 SettingsViewModel 保存逻辑**

`SettingsViewModel` 中新增：

```kotlin
private val secureSettingsRepo = SecureSettingsRepository(app)
```

`loadSettings()` 中：

```kotlin
_apiKey.value = secureSettingsRepo.getApiKey()
aiRepo.configureFromSettings(settingsRepo, secureSettingsRepo)
```

`saveApiConfig()` 中：

```kotlin
settingsRepo.setSetting("apiBaseUrl", _apiBaseUrl.value)
secureSettingsRepo.setApiKey(_apiKey.value)
settingsRepo.setSetting("modelName", _modelName.value)
aiRepo.configure(_apiBaseUrl.value, _apiKey.value, _modelName.value)
```

- [ ] **Step 4: 修改其他 ViewModel 的 configureFromSettings 调用**

所有调用 `aiRepo.configureFromSettings(settingsRepo)` 的地方改为：

```kotlin
val secureSettingsRepo = SecureSettingsRepository(app)
aiRepo.configureFromSettings(settingsRepo, secureSettingsRepo)
```

- [ ] **Step 5: 验证**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: 编译成功。

---

## Task 3: Room migration 与任务日历事件 ID

**Files:**
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/data/db/entity/TaskEntity.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/data/db/AppDatabase.kt`

- [ ] **Step 1: 给 TaskEntity 增加 calendarEventId**

在 `TaskEntity` 增加字段：

```kotlin
val calendarEventId: Long? = null
```

- [ ] **Step 2: 数据库版本升到 4 并增加 migration**

`AppDatabase.kt` 添加 import：

```kotlin
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
```

`@Database(version = 4)`。

`companion object` 中新增：

```kotlin
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN calendarEventId INTEGER")
    }
}
```

builder 改为：

```kotlin
.addMigrations(MIGRATION_3_4)
.build()
```

正式移除 `.fallbackToDestructiveMigration()`。

- [ ] **Step 3: 验证**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: Room KSP 通过，`TaskEntity` 与 schema 匹配。

---

## Task 4: BootReceiver 可靠恢复闹钟

**Files:**
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/alarm/BootReceiver.kt`

- [ ] **Step 1: 使用 goAsync() 包裹异步恢复**

`onReceive()` 中：

```kotlin
val pendingResult = goAsync()
restoreTaskAlarms(context.applicationContext, pendingResult)
```

`restoreTaskAlarms` 签名改为：

```kotlin
private fun restoreTaskAlarms(context: Context, pendingResult: PendingResult)
```

协程 finally 中调用：

```kotlin
finally {
    pendingResult.finish()
}
```

- [ ] **Step 2: 验证**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: 编译成功。

---

## Task 5: 输入保存事务与 AI 失败提示

**Files:**
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/data/ai/AiRepository.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/ui/viewmodel/InputViewModel.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/ui/screens/InputScreen.kt`

- [ ] **Step 1: AI 分析失败不返回默认成功结果**

`AiRepository.analyzeContent()` 删除 catch 中默认 `ContentAnalysis`，改为抛出友好异常：

```kotlin
} catch (e: Throwable) {
    Log.e("AiRepository", "analyzeContent error", e)
    throw Exception(getErrorMessage(e))
}
```

- [ ] **Step 2: InputViewModel 使用数据库事务**

在 `InputViewModel.saveAndAnalyze()` 中数据库写入改为：

```kotlin
val scheduledTasks = mutableListOf<ScheduledTaskRequest>()
val recordId = withContext(Dispatchers.IO) {
    app.database.withTransaction {
        val id = recordRepo.insertRecord(...)
        ...
        scheduledTasks.add(ScheduledTaskRequest(...))
        id
    }
}
scheduledTasks.forEach { request ->
    SmartScheduleManager.scheduleTask(...)
}
```

新增局部或文件级 data class：

```kotlin
private data class ScheduledTaskRequest(
    val taskId: Long,
    val taskContent: String,
    val taskType: String,
    val dueDate: String,
    val recordTitle: String
)
```

- [ ] **Step 3: UI 显示 AI 失败**

`InputScreen` 保持现有输入流程，`onError` 展示：“AI 分析失败：xxx。请检查配置或稍后重试。”

- [ ] **Step 4: 验证**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: 编译成功；AI 异常不再静默保存默认分类。

---

## Task 6: 日历事件持久化与删除

**Files:**
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/calendar/CalendarHelper.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/alarm/SmartScheduleManager.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/ui/viewmodel/InputViewModel.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/ui/viewmodel/RecordListViewModel.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/ui/viewmodel/CalendarViewModel.kt`

- [ ] **Step 1: CalendarHelper 增加删除事件**

新增：

```kotlin
fun deleteEvent(context: Context, eventId: Long): Boolean {
    return try {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        context.contentResolver.delete(uri, null, null) > 0
    } catch (e: Exception) {
        Log.e(TAG, "删除日历事件失败", e)
        false
    }
}
```

- [ ] **Step 2: 保存 scheduleTask 返回的 eventId**

`InputViewModel` 在事务后执行 `scheduleTask`，如果返回 `eventId != null`，更新对应 `TaskEntity.calendarEventId`。

- [ ] **Step 3: 取消任务时删除日历事件**

在 `completeTask`、`deleteRecord`、`completeGoal` 等取消闹钟的位置，读取 `task.calendarEventId`，非空则调用 `CalendarHelper.deleteEvent(app, eventId)`。

- [ ] **Step 4: 验证**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: 编译成功。

---

## Task 7: 通知与权限按需处理

**Files:**
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/MainActivity.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/notification/NotificationHelper.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/ui/screens/SettingsScreen.kt`

- [ ] **Step 1: NotificationHelper 检查通知权限**

在 Android 13+ 且未授权时直接返回：

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
) {
    Log.w("NotificationHelper", "通知权限未授权，跳过发送通知")
    return
}
```

- [ ] **Step 2: MainActivity 启动时不请求日历权限**

`requestPermissions()` 只保留通知权限请求。日历权限在开启自动同步或执行日历写入前请求。

- [ ] **Step 3: SettingsScreen 开启自动日历同步时提示授权**

在用户打开 `autoCalendarSync` 时，如果没有日历读写权限，提示用户授权或说明同步不可用。

- [ ] **Step 4: 验证**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: 编译成功；启动不再主动请求日历权限。

---

## Task 8: 导出体验优化

**Files:**
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/ui/viewmodel/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/ui/screens/SettingsScreen.kt`

- [ ] **Step 1: 导出接口返回 JSON 字符串而不是直接写私有目录**

`SettingsViewModel` 新增：

```kotlin
suspend fun buildExportJson(): String = withContext(Dispatchers.IO) {
    val records = recordRepo.getAllRecords().first()
    val tasks = taskRepo.getAllTasks().first()
    val reviews = reviewRepo.getAllReviews().first()
    val memories = memoryRepo.getAllMemories().first()
    gson.toJson(mapOf(
        "version" to 1,
        "exportDate" to Date().toString(),
        "records" to records,
        "tasks" to tasks,
        "reviews" to reviews,
        "memories" to memories
    ))
}
```

- [ ] **Step 2: SettingsScreen 使用 CreateDocument 写入用户选择的位置**

使用 `ActivityResultContracts.CreateDocument("application/json")` 创建文件，拿到 Uri 后写入 `buildExportJson()` 结果。

- [ ] **Step 3: 验证**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: 编译成功；导出由系统文件选择器选择保存位置。

---

## Task 9: RecordListViewModel 结构拆分

**Files:**
- Create: `app/src/main/java/com/zhiwo/shiguangjian/data/organize/RecordOrganizer.kt`
- Modify: `app/src/main/java/com/zhiwo/shiguangjian/ui/viewmodel/RecordListViewModel.kt`

- [ ] **Step 1: 新建 RecordOrganizer**

把 `doApplyRecordMerge`、`doApplyRecordSplit`、`doApplyToMemory`、`doApplyGoalToTodos`、`doApplyTodosToGoal` 的数据库与闹钟逻辑迁移到 `RecordOrganizer`。

构造函数：

```kotlin
class RecordOrganizer(
    private val app: ZhiwoApplication,
    private val recordRepo: RecordRepository,
    private val taskRepo: TaskRepository,
    private val memoryRepo: MemoryRepository
)
```

公开方法：

```kotlin
suspend fun applyRecordMerge(sourceIds: List<Long>, mergedTitle: String, mergedCategory: String)
suspend fun applyRecordSplit(sourceId: Long, splits: List<Pair<String, String>>)
suspend fun applyToMemory(sourceId: Long, memoryContent: String)
suspend fun applyGoalToTodos(sourceId: Long, todos: List<String>)
suspend fun applyTodosToGoal(sourceIds: List<Long>, goalTitle: String)
```

- [ ] **Step 2: RecordListViewModel 只负责状态更新**

公共 `applyXxx` 方法调用 `recordOrganizer.applyXxx()`，成功后调用 `removeAppliedSuggestion()`。

- [ ] **Step 3: 详情 Flow 缓存改为按需 stateIn**

删除无限增长的 `mutableMapOf<Long, StateFlow<...>>()` 缓存，改为每次返回 DAO Flow 的 `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)`。

- [ ] **Step 4: 验证**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: 编译成功；`RecordListViewModel` 职责变轻。

---

## Task 10: 最终验证

**Files:**
- Verify only.

- [ ] **Step 1: 编译 debug**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 2: 构建 release**

Run:

```bash
./gradlew :app:assembleRelease
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 手动检查路径**

检查项：

1. 启动应用，不主动请求日历权限。
2. 设置页保存 API Key 后重新打开仍能测试连接。
3. 输入记录时 AI 失败会明确提示，不静默保存默认分析。
4. 有截止时间任务能创建提醒和日历事件。
5. 完成/删除任务后闹钟取消，日历事件删除。
6. 导出时弹出系统文件保存器。

---

## Self Review

- Spec coverage: 覆盖安全/发布、Room 迁移、BootReceiver、事务、AI 失败提示、权限、通知、导出、日历事件持久化、RecordListViewModel 拆分。
- Placeholder scan: 无 TBD/TODO/implement later。
- Type consistency: 新增 `SecureSettingsRepository`、`RecordOrganizer`、`calendarEventId` 在后续任务中名称一致。
- Scope check: 全部建议较大，但已拆成 10 个可独立验证阶段。
