package com.zhiwo.shiguangjian.data.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import com.zhiwo.shiguangjian.data.profile.MemoryExtractionParser
import com.zhiwo.shiguangjian.data.profile.MemoryExtractionResult
import com.zhiwo.shiguangjian.data.profile.UserProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.zhiwo.shiguangjian.data.repository.SecureSettingsRepository
import com.zhiwo.shiguangjian.data.repository.SettingsRepository
import java.util.concurrent.TimeUnit

class AiRepository {

    private var baseUrl: String = ""
    private var apiKey: String = ""
    private var modelName: String = ""
    private var retrofit: Retrofit? = null
    private var apiService: AiApiService? = null
    private val rateLimitMutex = Mutex()
    private var lastCallTime = 0L
    private val minCallInterval = 3000L
    private val gson = Gson()

    private fun nowFormatted(): String = DateFormats.nowDateTimeDisplay()

    @Synchronized
    fun configure(baseUrl: String, apiKey: String, modelName: String) {
        // 先重置，防止上次失败的状态残留
        retrofit = null
        apiService = null

        this.baseUrl = baseUrl.trimEnd('/')
        this.apiKey = apiKey
        this.modelName = modelName

        // 如果配置不完整，不初始化
        if (!hasCompleteConfig) return

        // 确保 URL 有 scheme，否则 Retrofit 会崩溃
        val url = this.baseUrl
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            logError("URL 缺少协议前缀: $url")
            return
        }

        try {
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $apiKey")
                        .build()
                    chain.proceed(request)
                }
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(url + "/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            apiService = retrofit?.create(AiApiService::class.java)
        } catch (e: Throwable) {
            logError("初始化 Retrofit 失败: ${e.message}", e)
            retrofit = null
            apiService = null
        }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        try {
            if (throwable == null) {
                Log.e("AiRepository", message)
            } else {
                Log.e("AiRepository", message, throwable)
            }
        } catch (_: RuntimeException) {
            // JVM 单元测试没有 Android Log 实现，忽略日志不影响业务状态。
        }
    }

    suspend fun configureFromSettings(
        settingsRepo: SettingsRepository,
        secureSettingsRepo: SecureSettingsRepository
    ) {
        try {
            val baseUrl = settingsRepo.getSetting("apiBaseUrl") ?: ""
            val storedApiKey = secureSettingsRepo.getApiKey()
            val legacyApiKey = settingsRepo.getSetting("apiKey") ?: ""
            val apiKey = storedApiKey.ifBlank { legacyApiKey }
            if (storedApiKey.isBlank() && legacyApiKey.isNotBlank()) {
                settingsRepo.deleteSetting("apiKey")
                secureSettingsRepo.setApiKey(legacyApiKey)
            }
            val modelName = settingsRepo.getSetting("modelName") ?: ""
            configure(baseUrl, apiKey, modelName)
        } catch (e: Throwable) {
            android.util.Log.e("AiRepository", "从设置加载 AI 配置失败", e)
        }
    }

    private val hasCompleteConfig: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && modelName.isNotBlank()

    val isConfigured: Boolean
        get() = apiService != null

    private suspend fun rateLimitedCall() {
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastCallTime
            if (elapsed < minCallInterval) {
                delay(minCallInterval - elapsed)
            }
            lastCallTime = System.currentTimeMillis()
        }
    }

    private suspend fun chat(messages: List<Pair<String, String>>, maxTokens: Int = 2000): String {
        rateLimitedCall()
        val api = apiService
            ?: throw IllegalStateException("AI 服务未配置，请检查 API 地址格式是否正确（需包含 http:// 或 https://）")

        val messagesArray = JsonArray()
        messages.forEach { (role, content) ->
            val msgObj = JsonObject()
            msgObj.addProperty("role", role)
            msgObj.addProperty("content", content)
            messagesArray.add(msgObj)
        }

        val body = JsonObject()
        body.addProperty("model", modelName)
        body.add("messages", messagesArray)
        body.addProperty("temperature", 0.7)
        body.addProperty("max_tokens", maxTokens)

        return try {
            val response = api.chat(body)
            if (!response.isSuccessful) {
                val errorCode = response.code()
                val errorBody = try { response.errorBody()?.string() ?: "" } catch (e: Throwable) { "" }
                val errorMsg = if (errorBody.isNotBlank()) {
                    try {
                        JsonParser.parseString(errorBody)?.asJsonObject
                            ?.getAsJsonObject("error")
                            ?.get("message")?.asString ?: "请求失败($errorCode)"
                    } catch (e: Throwable) {
                        "请求失败($errorCode)"
                    }
                } else {
                    "请求失败($errorCode)"
                }
                throw Exception(errorMsg)
            }

            val responseBody = response.body()
                ?: throw Exception("空响应")

            // 安全解析响应，防止 JSON 结构不符导致崩溃
            val choices = try {
                responseBody.asJsonObject.getAsJsonArray("choices")
            } catch (e: Throwable) {
                throw Exception("API 响应格式异常：缺少 choices 字段")
            }
            if (choices == null || choices.size() == 0) {
                throw Exception("API 返回的 choices 为空")
            }
            val message = try {
                choices.get(0).asJsonObject.getAsJsonObject("message")
            } catch (e: Throwable) {
                throw Exception("API 响应格式异常：缺少 message 字段")
            }
            val content = try {
                message.get("content")?.asString
            } catch (e: Throwable) {
                throw Exception("API 响应格式异常：缺少 content 字段")
            }
            content ?: throw Exception("API 返回的 content 为空")
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Throwable) {
            // 捕获所有异常（包括 Error 类型），防止闪退
            Log.e("AiRepository", "chat 调用失败: ${e.message}", e)
            throw Exception(e.message ?: "网络请求失败")
        }
    }

    private fun parseJsonResponse(raw: String): JsonObject = AiJsonParser.parseObject(raw)

    private fun parseJsonArray(raw: String): JsonArray = AiJsonParser.parseArray(raw)

    suspend fun testConnection(): Boolean {
        if (!isConfigured) throw Exception("请先配置 AI 接口（Base URL / API Key / Model 不能为空）")
        rateLimitedCall()
        val api = apiService
            ?: throw Exception("AI 服务初始化失败，请检查 API 地址格式。\n提示：需以 http:// 或 https:// 开头，如 https://api.deepseek.com/v1")
        return try {
            val body = JsonObject()
            body.addProperty("model", modelName)
            val messages = JsonArray()
            val msg = JsonObject()
            msg.addProperty("role", "user")
            msg.addProperty("content", "hi")
            messages.add(msg)
            body.add("messages", messages)
            body.addProperty("max_tokens", 10)
            val response = api.chat(body)
            if (!response.isSuccessful) {
                val errorCode = response.code()
                val errorBody = try { response.errorBody()?.string() ?: "" } catch (e: Throwable) { "" }
                val detail = if (errorBody.isNotBlank()) {
                    try {
                        "：${JsonParser.parseString(errorBody)?.asJsonObject?.getAsJsonObject("error")?.get("message")?.asString ?: ""}"
                    } catch (_: Throwable) { "" }
                } else ""
                throw Exception("请求失败($errorCode)$detail")
            }
            val responseBody = response.body()
            if (responseBody == null) {
                throw Exception("空响应")
            }
            // 验证响应结构是否有效
            val choices = responseBody.asJsonObject.getAsJsonArray("choices")
            choices != null && choices.size() > 0
        } catch (e: Throwable) {
            val friendlyMsg = getErrorMessage(e)
            throw Exception(friendlyMsg)
        }
    }

    private fun getErrorMessage(e: Throwable): String {
        val msg = e.message ?: ""
        return when {
            msg.contains("network", true) || msg.contains("connection", true) -> "网络连接失败，请检查API配置和网络"
            msg.contains("timeout", true) -> "请求超时，请检查网络连接"
            msg.contains("401") -> "API Key无效或已过期"
            msg.contains("403") -> "没有权限访问此API"
            msg.contains("404") -> "API地址不正确"
            msg.contains("429") -> "请求过于频繁，请稍后重试"
            msg.contains("500") || msg.contains("502") || msg.contains("503") -> "API服务器暂时不可用"
            else -> msg.ifBlank { "未知错误" }
        }
    }

    // ========== 内容分析 ==========
    suspend fun analyzeContent(userInput: String): ContentAnalysis {
        val currentTime = nowFormatted()
        val prompt = ANALYSIS_PROMPT
            .replace("{{user_input}}", userInput)
            .replace("{{current_time}}", currentTime)
        return try {
            val result = chat(listOf("user" to prompt))
            val parsed = parseJsonResponse(result)
            ContentAnalysis(
                title = parsed.get("title")?.asString?.take(15) ?: userInput.take(15),
                category = resolveCategory(parsed.get("category")?.asString),
                keyInfo = parsed.getAsJsonArray("key_info")?.map { it.asString } ?: emptyList(),
                tags = parsed.getAsJsonArray("tags")?.map { it.asString } ?: emptyList(),
                tasks = parsed.getAsJsonArray("tasks")?.map { taskObj ->
                    val obj = taskObj.asJsonObject
                    TaskInfo(
                        content = obj.get("content")?.asString ?: "",
                        dueDate = obj.get("due_date")?.asString ?: "",
                        taskType = obj.get("task_type")?.asString ?: "once"
                    )
                } ?: emptyList(),
                summary = parsed.get("summary")?.asString ?: ""
            )
        } catch (e: Throwable) {
            Log.e("AiRepository", "analyzeContent error", e)
            throw Exception(getErrorMessage(e))
        }
    }

    // ========== 每日评价 ==========
    suspend fun generateDailyReview(
        taskSummary: String,
        todayRecords: String,
        userInput: String,
        relatedMemories: String,
        userProfileSummary: String = "暂无可靠画像"
    ): String {
        val prompt = DAILY_REVIEW_PROMPT
            .replace("{{task_summary}}", taskSummary)
            .replace("{{today_records}}", todayRecords)
            .replace("{{user_input}}", userInput)
            .replace("{{related_memories}}", relatedMemories)
            .replace("{{user_profile}}", userProfileSummary)
        return enhanceDailyReviewText(chat(listOf("user" to prompt)))
    }

    // ========== 每周报告 ==========
    suspend fun generateWeeklyReview(
        completionRate: String,
        categoryStats: String,
        highlights: String,
        relatedMemories: String
    ): String {
        val prompt = WEEKLY_REVIEW_PROMPT
            .replace("{{completion_rate}}", completionRate)
            .replace("{{category_stats}}", categoryStats)
            .replace("{{highlights}}", highlights)
            .replace("{{related_memories}}", relatedMemories)
        return chat(listOf("user" to prompt))
    }

    // ========== 记忆提取 ==========
    /**
     * @param blockedItemsText 由 [com.zhiwo.shiguangjian.data.profile.ProfileBlocklistPrompt]
     * 渲染；空串时模板去掉占位行。仅约束 updatedProfile，不影响 newMemories。
     */
    suspend fun extractMemoriesAndProfile(
        userContent: String,
        currentProfile: UserProfile,
        blockedItemsText: String = ""
    ): MemoryExtractionResult {
        val withBlocked = com.zhiwo.shiguangjian.data.profile.ProfileBlocklistPrompt.injectIntoTemplate(
            template = MEMORY_EXTRACTION_PROMPT,
            placeholder = "{{blocked_items}}",
            rendered = blockedItemsText
        )
        val prompt = withBlocked
            .replace("{{current_time}}", nowFormatted())
            .replace("{{current_profile}}", gson.toJson(currentProfile))
            .replace("{{user_content}}", userContent)

        return try {
            val result = chat(listOf("user" to prompt))
            MemoryExtractionParser.parse(result)
        } catch (e: Throwable) {
            Log.e("AiRepository", "extractMemoriesAndProfile 失败，回退空结果", e)
            MemoryExtractionResult()
        }
    }

    // ========== 智能归并 ==========
    suspend fun consolidate(items: List<ConsolidateItem>): List<String> {
        val currentTime = nowFormatted()
        val itemsText = items.mapIndexed { i, item ->
            "${i + 1}. ${item.title}（${item.date}）${item.summary}"
        }.joinToString("\n")
        val prompt = CONSOLIDATE_PROMPT
            .replace("{{current_time}}", currentTime)
            .replace("{{items}}", itemsText)
        return try {
            val result = chat(listOf("user" to prompt))
            parseJsonArray(result).map { it.asString }
        } catch (e: Throwable) {
            Log.e("AiRepository", "consolidate error", e)
            items.map { it.title }
        }
    }

    // ========== 智能分类 ==========
    suspend fun classify(
        items: List<ConsolidateItem>,
        existingMemories: List<MemoryEntity>
    ): ClassifyResult {
        val currentTime = nowFormatted()
        val itemsText = items.mapIndexed { i, item ->
            "${i + 1}. ${item.title}（${item.date}）${item.summary}"
        }.joinToString("\n")
        val memoriesText = existingMemories.joinToString("\n") { "· ${it.content}" }
        val prompt = CLASSIFY_PROMPT
            .replace("{{current_time}}", currentTime)
            .replace("{{items}}", itemsText)
            .replace("{{existing_memories}}", memoriesText.ifBlank { "暂无记忆" })
        return try {
            val result = chat(listOf("user" to prompt))
            val parsed = parseJsonResponse(result)
            ClassifyResult(
                memories = parsed.getAsJsonArray("memories")?.map { it.asString } ?: emptyList(),
                cleanable = parsed.getAsJsonArray("cleanable")?.map { it.asString } ?: emptyList()
            )
        } catch (e: Throwable) {
            // 取消异常必须继续传播，否则会破坏协程取消
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("AiRepository", "classify error", e)
            // 失败时绝不能把条目默认标为可清理（会导致误删），直接报错让界面提示
            throw Exception("AI 分类失败，未对任何数据做更改（${e.message ?: "返回格式异常"}）")
        }
    }

    // ========== 智慧清扫 ==========
    suspend fun smartClean(
        items: List<ConsolidateItem>,
        existingMemories: List<MemoryEntity>
    ): SmartCleanResult {
        val currentTime = nowFormatted()
        val itemsText = items.mapIndexed { i, item ->
            "${i + 1}. ${item.text}（${item.date}）"
        }.joinToString("\n")
        val memoriesText = existingMemories.joinToString("\n") { "· ${it.content}" }
        val prompt = SMART_CLEAN_PROMPT
            .replace("{{current_time}}", currentTime)
            .replace("{{items}}", itemsText)
            .replace("{{existing_memories}}", memoriesText.ifBlank { "暂无记忆" })
        return try {
            val result = chat(listOf("user" to prompt))
            val parsed = parseJsonResponse(result)
            SmartCleanResult(
                shouldClean = parsed.getAsJsonArray("should_clean")?.map { it.asString } ?: emptyList(),
                shouldKeep = parsed.getAsJsonArray("should_keep")?.map { it.asString } ?: emptyList()
            )
        } catch (e: Throwable) {
            Log.e("AiRepository", "smartClean error", e)
            SmartCleanResult(emptyList(), items.map { it.text })
        }
    }

    // ========== 记忆演化 ==========
    suspend fun evolveMemories(memories: List<MemoryEntity>): List<MemoryEvolveItem> {
        val currentTime = nowFormatted()
        val memoriesText = memories.mapIndexed { i, m ->
            "${i + 1}. ${m.content}（创建于${m.createdAt}）"
        }.joinToString("\n")
        val prompt = EVOLVE_MEMORIES_PROMPT
            .replace("{{current_time}}", currentTime)
            .replace("{{memories}}", memoriesText)
        return try {
            val result = chat(listOf("user" to prompt))
            parseJsonArray(result).map { obj ->
                val jsonObj = obj.asJsonObject
                MemoryEvolveItem(
                    old = jsonObj.get("old")?.asString ?: "",
                    new = jsonObj.get("new")?.asString ?: ""
                )
            }
        } catch (e: Throwable) {
            Log.e("AiRepository", "evolveMemories error", e)
            emptyList()
        }
    }

    // ========== 记忆分析 ==========
    suspend fun analyzeMemories(memories: List<MemoryEntity>): AnalyzeResult {
        val currentTime = nowFormatted()
        val memoriesText = memories.joinToString("\n") { m ->
            "${m.id}. ${m.content}（创建于${m.createdAt}）"
        }
        val prompt = ANALYZE_MEMORIES_PROMPT
            .replace("{{current_time}}", currentTime)
            .replace("{{memories}}", memoriesText.ifBlank { "暂无记忆" })
        return try {
            val result = chat(listOf("user" to prompt), maxTokens = 4000)
            val parsed = parseJsonResponse(result)
            val merge = parsed.getAsJsonArray("merge")?.map { element ->
                val obj = element.asJsonObject
                MergeItem(
                    sourceIds = obj.getAsJsonArray("source_ids")?.map { it.asLong } ?: emptyList(),
                    merged = obj.get("merged")?.asString ?: "",
                    reason = obj.get("reason")?.asString ?: ""
                )
            } ?: emptyList()
            val split = parsed.getAsJsonArray("split")?.map { element ->
                val obj = element.asJsonObject
                SplitItem(
                    sourceId = obj.get("source_id")?.asLong ?: 0L,
                    splits = obj.getAsJsonArray("splits")?.map { it.asString } ?: emptyList(),
                    reason = obj.get("reason")?.asString ?: ""
                )
            } ?: emptyList()
            val evolve = parsed.getAsJsonArray("evolve")?.map { element ->
                val obj = element.asJsonObject
                EvolveItem(
                    sourceId = obj.get("source_id")?.asLong ?: 0L,
                    newContent = obj.get("new_content")?.asString ?: "",
                    reason = obj.get("reason")?.asString ?: ""
                )
            } ?: emptyList()
            AnalyzeResult(merge, split, evolve)
        } catch (e: Throwable) {
            Log.e("AiRepository", "analyzeMemories error", e)
            AnalyzeResult(emptyList(), emptyList(), emptyList())
        }
    }

    // ========== 日记生成 ==========
    suspend fun generateDiary(
        todayRecords: String,
        taskSummary: String,
        userInput: String,
        relatedMemories: String
    ): DiaryResult {
        val prompt = DIARY_PROMPT
            .replace("{{today_records}}", todayRecords)
            .replace("{{task_summary}}", taskSummary)
            .replace("{{user_input}}", userInput)
            .replace("{{related_memories}}", relatedMemories)
        val result = chat(listOf("user" to prompt), maxTokens = 4000)
        val parsed = parseJsonResponse(result)
        return DiaryResult(
            content = parsed.get("content")?.asString ?: "",
            mood = parsed.get("mood")?.asString ?: "平淡"
        )
    }

    // ========== 记录分析 ==========
    suspend fun analyzeRecords(records: List<RecordEntity>): RecordAnalysisResult {
        val currentTime = nowFormatted()
        val recordsText = records.map { r ->
            "${r.id}. [${r.category}] ${r.title}（${r.summary.ifBlank { r.content.take(50) }}）"
        }.joinToString("\n")
        val prompt = ANALYZE_RECORDS_PROMPT
            .replace("{{current_time}}", currentTime)
            .replace("{{records}}", recordsText.ifBlank { "暂无记录" })
        return try {
            val result = chat(listOf("user" to prompt), maxTokens = 4000)
            val parsed = parseJsonResponse(result)
            val merge = parsed.getAsJsonArray("merge")?.map { element ->
                val obj = element.asJsonObject
                RecordMergeItem(
                    sourceIds = obj.getAsJsonArray("source_ids")?.map { it.asLong } ?: emptyList(),
                    mergedTitle = obj.get("merged_title")?.asString ?: "",
                    mergedCategory = obj.get("merged_category")?.asString ?: "",
                    reason = obj.get("reason")?.asString ?: ""
                )
            } ?: emptyList()
            val split = parsed.getAsJsonArray("split")?.map { element ->
                val obj = element.asJsonObject
                RecordSplitItem(
                    sourceId = obj.get("source_id")?.asLong ?: 0L,
                    splits = obj.getAsJsonArray("splits")?.map { splitElement ->
                        val splitObj = splitElement.asJsonObject
                        SplitEntry(
                            title = splitObj.get("title")?.asString ?: "",
                            category = splitObj.get("category")?.asString ?: ""
                        )
                    } ?: emptyList(),
                    reason = obj.get("reason")?.asString ?: ""
                )
            } ?: emptyList()
            val goalToTodos = parsed.getAsJsonArray("goal_to_todos")?.map { element ->
                val obj = element.asJsonObject
                RecordGoalToTodos(
                    sourceId = obj.get("source_id")?.asLong ?: 0L,
                    todos = obj.getAsJsonArray("todos")?.map { it.asString } ?: emptyList(),
                    reason = obj.get("reason")?.asString ?: ""
                )
            } ?: emptyList()
            val todosToGoal = parsed.getAsJsonArray("todos_to_goal")?.map { element ->
                val obj = element.asJsonObject
                RecordTodosToGoal(
                    sourceIds = obj.getAsJsonArray("source_ids")?.map { it.asLong } ?: emptyList(),
                    goalTitle = obj.get("goal_title")?.asString ?: "",
                    reason = obj.get("reason")?.asString ?: ""
                )
            } ?: emptyList()
            val toMemory = parsed.getAsJsonArray("to_memory")?.map { element ->
                val obj = element.asJsonObject
                RecordToMemory(
                    sourceId = obj.get("source_id")?.asLong ?: 0L,
                    memoryContent = obj.get("memory_content")?.asString ?: "",
                    reason = obj.get("reason")?.asString ?: ""
                )
            } ?: emptyList()
            RecordAnalysisResult(merge, split, goalToTodos, todosToGoal, toMemory)
        } catch (e: Throwable) {
            Log.e("AiRepository", "analyzeRecords error", e)
            RecordAnalysisResult()
        }
    }

}
