# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with the **zhiwo-android** project.

---

## 项目概览

"知我时光笺" Android 应用 — Kotlin + Jetpack Compose 原生应用，使用 Room + Retrofit 实现本地存储和 AI 分析功能。与根目录的 Vue 3 前端共享产品定位，但完全独立实现。

---

## 构建与命令

```bash
# 编译检查（命令行）
./gradlew :app:compileDebugKotlin

# 构建 debug APK
./gradlew :app:assembleDebug

# 构建 release APK
./gradlew :app:assembleRelease

# 清理
./gradlew clean
```

**注意**：Gradle 构建必须在项目根目录（`zhiwo-shiguangjian/`）执行，因为 `gradlew` 和 `settings.gradle.kts` 在那里。若从 `zhiwo-android/` 子目录执行，需要先 `cd` 到父目录。

### 环境配置

确保 **JAVA_HOME** 指向 Android Studio 自带的 JDK：
```
JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
```
`gradle.properties` 中已通过 `org.gradle.java.home` 备选指定。

### Gradle/SDK 版本约束

| 项 | 值 | 说明 |
|---|---|---|
| AGP | 8.2.2 | 与 Gradle 8.2 兼容 |
| Gradle | 8.2 | 本地已缓存，华为云镜像 |
| compileSdk | 36 | android-36 的 android.jar 在本地 SDK 中完整 |
| buildTools | 36.1.0 | 本地已安装 |
| targetSdk / minSdk | 34 / 26 | — |
| Kotlin | 1.9.24 | 对应 Compose Compiler 1.5.14 |

**AGP 8.2.2 与 compileSdk 36 不是官方兼容组合**。`gradle.properties` 中通过 `android.suppressUnsupportedCompileSdk=36` 抑制警告。android-34 平台缺少 `android.jar`，因此必须使用 compileSdk 36。**升级 AGP 或 Gradle 前请注意**：外网下载在此环境下不可靠，需谨慎操作。

---

## 技术栈

Kotlin 1.9.24 · Jetpack Compose (BOM 2024.02.00) · Room 2.6.1 (KSP) · Retrofit 2.9.0 · OkHttp 4.12.0 · Navigation Compose 2.7.6 · DataStore 1.0.0 · Coroutines 1.7.3

---

## 架构：MVVM + Repository，单 Activity，Compose UI

```
app/src/main/java/com/zhiwo/shiguangjian/
  MainActivity.kt              # 单 Activity，权限请求，闹钟调度，NavHost
  ZhiwoApplication.kt          # Application：Room 数据库单例，通知渠道初始化，全局异常捕获
  data/
    db/
      AppDatabase.kt           # Room 数据库（单例，破坏性迁移，version=2）
      entity/                  # 8 个实体（Record, Task, Tag, RecordTagCrossRef, KeyInfo, Review, Memory, Setting）
      dao/                     # 7 个 DAO（全部返回 Flow，响应式读取）
    repository/                # 5 个仓库（Record, Task, Review, Memory, Settings）
    ai/
      AiApiService.kt          # Retrofit 接口（POST chat/completions，JsonObject 入参）
      AiRepository.kt          # 核心 AI 逻辑：分析内容、生成日/周评价、提取记忆、整理分类等
  ui/
    theme/                     # Color.kt, Theme.kt (亮色/暗色), Type.kt
    screens/                   # 8 个页面 Composable
    viewmodel/                 # 7 个 ViewModel（全部继承 AndroidViewModel，手动构造仓库，无 DI 框架）
    components/                # BottomNavBar, CalendarGrid, RecordCard, TaskItem
  alarm/                       # AlarmScheduler（4 个重复闹钟）, AlarmReceiver, BootReceiver, SmartScheduleManager
  notification/                # NotificationHelper（3 个通知渠道）
  calendar/                    # CalendarHelper（系统日历 CRUD，尚未被 UI 调用）
```

### 路由 (NavHost, 8 条)

`input`(起始页) → `records` → `record_detail/{id}` → `calendar` → `review` → `memories` → `organize` → `settings`

底部导航栏显示 5 个 Tab：记录(列表)、日历、记录(输入/+)、评价、设置。`record_detail` 页面隐藏底部导航栏。

### 数据流

```
用户输入 → InputViewModel.saveAndAnalyze()
  → AiRepository.analyzeContent()          [Retrofit → 远端 AI API]
  → RecordRepository.insertRecord()        [Room 写入，Dispatchers.IO]
  → KeyInfoDao + TagDao + TaskDao          [批量写入关联数据]
  → 跳转到 RecordDetailScreen

列表页面 → ViewModel 收集 Flow → Room DAO → UI 自动更新

评价生成 → ReviewViewModel.generateDailyReview()
  → AI 生成评价 → 写入 reviews 表
  → AI 提取记忆 → 写入 memories 表（上限 100 条）
```

### 关键设计模式

- **无 DI 框架**：所有 ViewModel 继承 `AndroidViewModel`，在构造函数中通过 `ZhiwoApplication.instance.database` 手动获取 DAO 并构造 Repository
- **响应式读取**：所有 DAO 查询返回 `Flow`，ViewModel 中通过 `collectAsState` 绑定到 Compose UI
- **写操作切线程**：数据库写入使用 `withContext(Dispatchers.IO)` 切换到 IO 线程
- **Room 破坏性迁移**：`fallbackToDestructiveMigration()`，schema 变更会清空数据
- **AI 限速**：最小调用间隔 3 秒，90 秒超时
- **全局异常捕获**：`ZhiwoApplication` 中设置了 `UncaughtExceptionHandler`，崩溃日志输出到 Logcat

### AI 数据流

`AiRepository` 不通过构造函数注入，而是由 ViewModel 在 `init` 块中从 `SettingsRepository` 读取 `apiBaseUrl`、`apiKey`、`modelName` 后调用 `configure()` 初始化。Retrofit 实例在 `configure()` 中懒创建。

---

## 已实现但需谨慎维护的功能

- `CalendarHelper` — 系统日历 CRUD 已接入任务调度；日历权限只应在用户开启自动同步时请求。
- `OrganizeScreen` — 已接入 AI 分类、归并、清理和记忆演化；清理源数据时必须保持建议项与来源记录/评价的精确绑定。
- `SettingsViewModel.buildExportJson()` — 已导出记录、任务、标签、评价、记忆、日记和非敏感设置；不要导出 API Key 等敏感配置。

---

## 权限

网络、精确闹钟、通知、日历读写、开机自启动。
`network_security_config.xml` 允许明文 HTTP（用于连接本地或非 HTTPS 的 AI API）。

---

## 依赖仓库配置

Maven 仓库使用了美团内网镜像（`depot.sankuai.com`、`pixel.sankuai.com`）以及 Google 和 Maven Central。`settings.gradle.kts` 的 `pluginManagement` 和 `dependencyResolutionManagement` 中均有配置。
