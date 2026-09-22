# 保留 Room 实体
-keep class com.zhiwo.shiguangjian.data.db.entity.** { *; }
# 保留全部 data 层：Gson 反射读写的持久化 DTO（organize_ops.payloadJson、settings 里的
# 记忆待确认队列/重试队列等）一旦被 R8 改名，运行期会静默解析成半截数据 —— 旧提案直接不可执行
-keep class com.zhiwo.shiguangjian.data.** { *; }
# 保留 Room DAO 接口
-keep class com.zhiwo.shiguangjian.data.db.dao.** { *; }
# 保留 Retrofit 接口
-keep class com.zhiwo.shiguangjian.data.ai.AiApiService { *; }
# 保留 Retrofit 模型
-keepattributes Signature
-keepattributes *Annotation*
# OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
# Kotlin 协程
-keepattributes Exceptions
