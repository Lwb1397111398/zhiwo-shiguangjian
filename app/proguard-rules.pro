# 保留 Room 实体
-keep class com.zhiwo.shiguangjian.data.db.entity.** { *; }
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
