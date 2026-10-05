# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with the **zhiwo-android** project.

---

## 项目概览

"知我时光笺" Android 应用 — Kotlin + Jetpack Compose 原生应用，使用 Room + Retrofit 实现本地存储和 AI 分析功能。与根目录的 Vue 3 前端共享产品定位，但完全独立实现。

---

## 构建与命令

**强烈建议本机固定使用项目级 Gradle Home**，避免与其他工程共享 `%USERPROFILE%\.gradle` 导致 Test Worker classpath 串项目（`ClassNotFoundException: GradleWorkerMain`）：

```bash
# 唯一可用入口（源码镜像到纯 ASCII 目录 + ASCII Gradle Home，见下方"中文路径"一节）
bash tools/verify.sh compileDebugKotlin
bash tools/verify.sh testDebugUnitTest
bash tools/verify.sh assembleDebug
```

**中文路径会让构建假装成功**：① AGP 直接拒建（已加 `android.overridePathCheck=true` 跳过检查）；② Kotlin 守护进程与 Gradle test worker 把中文路径转义成 `u77E5...` —— 编译期报 "plugin classpath entry points to a non-existent location"，测试期所有测试类 `ClassNotFoundException`。实测**无效**的两条路：ASCII junction 指向项目目录（Gradle 会规范化回真实路径）、把 `-Djava.io.tmpdir` 指到 ASCII。唯一解法就是 `tools/verify.sh`：源码 robocopy 到 `E:\AI Agent\zhiwo-build` 构建，Gradle Home 用 `E:\AI Agent\zhiwo-gradle-home`（1.2G 依赖缓存副本，离线复制，不要联网重下）。**只在真目录改代码**，镜像是临时工作区。

**读测试结果必须验新鲜度**：`app/build/test-results/*.xml` 会留在原地（本项目曾把 2026-08-23 的旧 XML 当成"122 个测试通过"的证据）。`verify.sh` 跑之前清空镜像结果、用 `.run-start` 时间戳断言"非本次产生的 XML == 0"，并把汇总写进 `docs/quality/GATE-*.txt`（进版本库）。注意 XML 里的 `timestamp` 是 **UTC**，别拿本地日期比。

`.gradle-home/` 已在 `.gitignore` 中，勿提交。

**Git 与推送的事实**：git 仓库根就是 `zhiwo-android/`（上层 `work area/` 不是仓库），分支 `master`。**2026-10-05 起仓库已迁到老板大号的公开仓库 `https://github.com/Lwb1397111398/zhiwo-shiguangjian.git`**（原小号私有仓库 `lwb13738132243-xiaohao/zhiwo-shiguangjian` 已弃用，大号对它无权限；老板批准迁移是因为要上应用自更新，公开仓库匿名即可查 Release）。推送直接 `git push origin master`——Windows 凭据管理器里的大号凭据（gh CLI 已登录 `Lwb1397111398`，`~\.local\bin\gh.exe`）就能推，不需要 token。`*.apk` 已被忽略，给用户拿包走 GitHub Release（CI 自动发 latest）；`docs/…/证据/**/binary/` 也加了忽略（Gradle 的 `.bin/.idx` 是垃圾，只留可读的 XML/TXT）。

**应用自更新**：推 master 即自动构建发版（`.github/workflows/build-release.yml`），App 内检查/下载/安装，全链路见 `docs/模块总览/应用自更新模块总览.md`。版本号 = git 提交数（`app/build.gradle.kts` 的 `providers.exec`），debug/release 统一签名 `android/keystore/app.keystore`（PKCS12 / android / androiddebugkey，随公开仓库公开，个人项目风险可控）。

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

### 迁移链约定（重要决定，勿删）

正式迁移链从 **v3 起完整**（v3→v13）。**v1、v2 的 schema 信息在代码库中不存在，无法补齐迁移**——本项目实际不存在 v1/v2 存量用户（主要用户从 v9+ 开始使用），因此不做 v1/v2 兼容。防线：Release 构建无 `fallbackToDestructiveMigration`，迁移失败时 `AppDatabase.rebuild()` 会把主库 + `-wal` + `-shm` 三件套改名备份为 `.migration_backup_<时间戳>` 后重建，原数据可人工抢救。设置页存储区会显示备份文件存在提示。

**v13 起 debug 与 release 的迁移行为一致**：原先 debug 独有的 `fallbackToDestructiveMigration()` 已删除（它会让缺迁移时静默清库、`rebuild()` 与备份逻辑都不执行）。真机迁移验收**用 debug 包**：release 没配 `signingConfig`，产物是 `app-release-unsigned.apk` 装不上，且与 debug 签名不同源，覆盖安装要先卸载 = 手机上唯一一份数据清零。

### 当前验证状态（2026-08 第三批结束时）

- 代码实现完成：记忆对账/生命周期、整理两阶段（依赖保护/forceDelete）、日记改版+重生成+历史纠错扫描、reviews/diaries 契约（v12）
- JVM 单元测试 278 个（基线 125 + 本轮新增 153）与 Debug APK 构建通过，证据：`docs/quality/GATE-testDebugUnitTest-*.txt`
- **尚未验证（不标记最终验收通过）**：真机升级迁移（v12→v13）、Release WAL 备份恢复、核心 UI 交互
- 本地无可用模拟器（系统镜像目录为空壳，外网下载不可靠），不为此引入 sqlite-jdbc——Room 迁移最终需真实 Android SQLite 环境，用真机一次验证更有价值

### 真机最终验收顺序（全部功能完成后执行）

1. 安装旧版 v11，创建记录、记忆、日记、整理提案
2. 覆盖安装正式 v12 包，确认数据和旧提案可读（**关键：正常迁移完整性**）
3. 验证日记筛选、统计、阅读、导出和重新生成保护（isUserEdited 拒绝覆盖）
4. 验证法考历史纠错扫描及未编辑日记重生成
5. 验证整理页"只删不存"的二次确认和依赖保护
6. Release 包执行缺失迁移测试，确认 `.migration_backup_` 主库 + `-wal` + `-shm` 三件套均生成（**关键：异常迁移数据保护**）
7. 关闭智能提醒确认固定提醒取消；重新打开确认恢复
8. 输入非法 API 地址，确认保存被拒绝并显示错误

其中第 2、6 步最重要。这轮真机验证完成前，第三批保持"代码完成、待设备验证"状态。

---

## 技术栈

Kotlin 1.9.24 · Jetpack Compose (BOM 2024.02.00) · Room 2.6.1 (KSP) · Retrofit 2.9.0 · OkHttp 4.12.0 · Navigation Compose 2.7.6 · DataStore 1.0.0 · Coroutines 1.7.3

---

## 架构：MVVM + Repository，单 Activity，Compose UI

```
app/src/main/java/com/zhiwo/shiguangjian/
  MainActivity.kt              # 单 Activity，权限请求，闹钟调度，NavHost
  ZhiwoApplication.kt          # Application：Room 单例，通知渠道，全局异常捕获，appScope，数据库预热兜底
  data/
    db/
      AppDatabase.kt           # Room 数据库（单例，version=13；v13 新增 goals/plans/task_occurrences/day_overrides 四表 + tasks 加 15 列 + memories.occurredAt）
      entity/                  # 15 个实体（Record, Task, Tag, CrossRef, KeyInfo, Review, Memory, Setting, Diary, SpecialDate, OrganizeOp）
      dao/                     # 9 个 DAO（全部返回 Flow，响应式读取）
    repository/                # 7 个仓库（Record, Task, Review, Memory, Diary, Settings, SecureSettings）
    ai/
      AiApiService.kt          # Retrofit 接口（POST chat/completions，JsonObject 入参）
      AiPrompts.kt             # 全部 Prompt 模板（评价/日记/周报含"事实底线"接地约束）
      AiRepository.kt          # 核心 AI 逻辑：分析、评价、记忆提取、对账、整理提案等
    memory/                    # 记忆对账：MemoryReconciliation（纯逻辑）+ MemoryReconcileService（执行器）
    organizeops/               # 整理两阶段：OpPayloads/OpValidator（纯逻辑）+ OrganizeOpExecutor（Apply 执行器）
    profile/                   # 用户画像 + blocklist
    organize/                  # RecordOrganizer（记录合并/拆分/转记忆事务操作）
  ui/
    screens/                   # 11 个页面 Composable
    viewmodel/                 # 10 个 ViewModel（全部 AndroidViewModel，手动构造仓库，无 DI）
  alarm/                       # AlarmScheduler, AlarmReceiver, BootReceiver, SmartScheduleManager（闹钟ID统一 taskIdToAlarmId）
```

### 记忆状态机（v10+）

memories.status: `active`（生效，注入 prompt）→ `superseded`（被更正停用，supersededBy 指向新记忆）；
`under_review`（存在待确认冲突，不注入 prompt，用户应用/忽略后转移）。
对账触发点：保存记录后（appScope 异步）、每日评价时、目标完成/放弃时。高置信自动应用，中低置信进记忆页待确认队列（settings 表持久化）。

### 整理页两阶段（v11+）

Plan（generateXxx）只调 AI 并写 organize_ops 表（PENDING 提案，payload 自包含源ID+版本快照），不碰业务表；
审核 UI 支持逐条勾选/编辑文本/忽略/恢复原建议；Apply（applyBatch）由 OrganizeOpExecutor 按
EVOLVE → SAVE_MEMORY → DELETE_SOURCE 顺序逐条独立事务执行，幂等 + STALE（源数据变化）校验。

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
- **Room 迁移**：无破坏性兜底，加表/加列必须写 `Migration`（`ADD COLUMN` 前先 `PRAGMA table_info` 探测以支持失败重跑），迁移末尾做行数自检
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
