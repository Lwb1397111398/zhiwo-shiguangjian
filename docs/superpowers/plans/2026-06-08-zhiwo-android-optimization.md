# zhiwo-android 优化交付计划

**目标：** 先处理低风险、高确定性的基础质量问题，再逐步推进架构与功能层优化。

## 已交付优化

1. **分类解析稳定性**
   - 文件：`app/src/main/java/com/zhiwo/shiguangjian/data/ai/AiModels.kt`
   - 问题：`resolveCategory()` 对英文分类的包含匹配每次都单独执行忽略大小写判断，逻辑重复；前后空格与大小写组合缺少回归测试。
   - 方案：统一生成 `normalized` 后复用，减少重复判断，保持原有分类接口不变。
   - 验证：新增 `AiModelsTest` 覆盖英文空格、英文大小写、中文空格、空值和未知分类。

2. **单元测试基础设施**
   - 文件：`app/build.gradle.kts`
   - 问题：项目没有 JVM 单元测试依赖，纯 Kotlin 工具逻辑缺少低成本回归测试入口。
   - 方案：加入 `junit:junit:4.13.2`，不引入额外测试框架。

## 后续优化路线

1. **AI 响应解析模块化**
   - 将 fenced JSON 清洗、对象/数组解析从 `AiRepository` 拆出为纯 Kotlin 工具。
   - 为 Markdown JSON、纯 JSON、异常 JSON 增加单元测试。
   - 成功标准：AI 解析逻辑无需 Retrofit/Android 环境即可测试。

2. **ViewModel 配置状态去重**
   - 多个 ViewModel 都手动调用 `configureFromSettings()` 并维护 `_isConfigured`。
   - 目标是形成单一 AI 配置刷新入口，避免配置状态不一致。
   - 成功标准：设置页保存后，输入/评价/记忆页状态更新路径清晰。

3. **整理功能事务边界增强**
   - `RecordOrganizer` 当前跨记录、任务、记忆执行多步写入。
   - 后续应按操作补 Room 事务边界，避免中途失败留下半完成状态。
   - 成功标准：合并、拆分、转记忆、目标/待办互转均具备原子性验证。

4. **导出功能补齐**
   - `SettingsViewModel.exportData()` 当前仍是骨架输出。
   - 后续应补真实记录、任务、标签、评价、记忆导出。
   - 成功标准：导出的 JSON 可覆盖主要用户数据并可被人工校验。

5. **UI 警告清理**
   - 当前编译存在 Compose 图标弃用和未使用参数警告。
   - 后续单独处理，不与行为改动混杂。
   - 成功标准：`compileDebugKotlin` 输出仅保留外部 SDK 警告或无项目级警告。

## 本轮自审

- 改动范围只涉及分类解析、测试依赖和测试文件，没有改动 UI、数据库 schema 或 API 行为。
- `resolveCategory()` 的公开签名保持不变，调用方不需要同步修改。
- 新增测试只验证纯 Kotlin 行为，不依赖 Android runtime、网络或 Room。
- 单元测试执行阶段存在本机 Gradle worker 启动异常；主代码和测试代码编译已通过。
