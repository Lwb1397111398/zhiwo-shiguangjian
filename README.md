# 知我时光笺 · Android

Kotlin + Jetpack Compose 原生客户端。本地 Room 存储，Retrofit 对接 AI API。

## 构建

在仓库根目录 `zhiwo-shiguangjian/` 执行（`gradlew` 与 `settings.gradle.kts` 在此）：

```bash
# 本机务必固定使用项目级 Gradle Home，避免与其它工程共享全局缓存
# 导致 Test Worker classpath 串项目（ClassNotFoundException: GradleWorkerMain）
./gradlew --no-daemon --max-workers=1 -g ".gradle-home" :app:compileDebugKotlin
./gradlew --no-daemon --max-workers=1 -g ".gradle-home" :app:testDebugUnitTest
./gradlew --no-daemon --max-workers=1 -g ".gradle-home" :app:assembleDebug
```

`.gradle-home/`、`build/`、`local.properties` 已在 `.gitignore`，**不要** `git add -A` 后误提交。

## 环境

- `JAVA_HOME` → Android Studio 自带 JBR（或 `gradle.properties` 中的 `org.gradle.java.home`）
- Gradle 8.2 / AGP 8.2.2 / compileSdk 36 / minSdk 26

## 功能说明（画像相关）

- 设置 → 用户画像：查看 / 增删改 / 清空
- 删除的条目进入 blocklist，AI 提取时不会再写回
- 「自动更新画像」开关关闭后只挡写入，评价仍可读现有画像
