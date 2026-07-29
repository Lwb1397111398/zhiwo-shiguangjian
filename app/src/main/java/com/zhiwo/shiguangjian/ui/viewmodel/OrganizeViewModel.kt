package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.ai.ConsolidateItem
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.ai.MemoryEvolveItem
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import com.zhiwo.shiguangjian.data.db.entity.ReviewEntity
import com.zhiwo.shiguangjian.data.repository.MemoryRepository
import com.zhiwo.shiguangjian.data.repository.RecordRepository
import com.zhiwo.shiguangjian.data.repository.ReviewRepository
import com.zhiwo.shiguangjian.data.repository.SecureSettingsRepository
import com.zhiwo.shiguangjian.data.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private data class OrganizeSourceIds(
    val recordIds: Set<Long> = emptySet(),
    val reviewIds: Set<Long> = emptySet()
)

class OrganizeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhiwoApplication
    private val recordRepo = RecordRepository(
        app.database, app.database.recordDao(), app.database.taskDao(),
        app.database.tagDao(), app.database.keyInfoDao()
    )
    private val reviewRepo = ReviewRepository(app.database.reviewDao())
    private val memoryRepo = MemoryRepository(app.database.memoryDao())
    private val settingsRepo = SettingsRepository(app.database.settingDao())
    private val secureSettingsRepo = SecureSettingsRepository(app)
    private val aiRepo get() = app.aiRepo

    private val _completedRecords = MutableStateFlow<List<RecordEntity>>(emptyList())
    val completedRecords: StateFlow<List<RecordEntity>> = _completedRecords

    private val _oldRecords = MutableStateFlow<List<RecordEntity>>(emptyList())
    val oldRecords: StateFlow<List<RecordEntity>> = _oldRecords

    private val _reviews = MutableStateFlow<List<ReviewEntity>>(emptyList())
    val reviews: StateFlow<List<ReviewEntity>> = _reviews

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured

    private val _aiLoading = MutableStateFlow(false)
    val aiLoading: StateFlow<Boolean> = _aiLoading

    private val _aiStatus = MutableStateFlow("")
    val aiStatus: StateFlow<String> = _aiStatus

    // 选择状态
    private val _sourceItemIds = MutableStateFlow<Set<Long>>(emptySet())
    val sourceItemIds: StateFlow<Set<Long>> = _sourceItemIds

    private val _sourceReviewIds = MutableStateFlow<Set<Long>>(emptySet())
    val sourceReviewIds: StateFlow<Set<Long>> = _sourceReviewIds

    private val _cleanableItems = MutableStateFlow<List<String>>(emptyList())
    val cleanableItems: StateFlow<List<String>> = _cleanableItems

    private val _memoryItems = MutableStateFlow<List<String>>(emptyList())
    val memoryItems: StateFlow<List<String>> = _memoryItems

    private val _evolveResults = MutableStateFlow<List<MemoryEvolveItem>>(emptyList())
    val evolveResults: StateFlow<List<MemoryEvolveItem>> = _evolveResults

    // 用于 confirmClean 时按清理项精确删除来源，避免移动/移除单项后仍整批删除。
    private val cleanableSourceMap = mutableMapOf<String, OrganizeSourceIds>()

    // 保存记忆内容对应的原始日期（用于 confirmSaveMemories）
    private val memoryDateMap = mutableMapOf<String, String>()

    init {
        loadData()
        configureAi()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                recordRepo.getRecordsByCategory("completed").collect { records ->
                    _completedRecords.value = records
                    selectAllIfEmpty()
                }
            } catch (e: Throwable) {
                android.util.Log.e("OrganizeVM", "loadData completed failed", e)
            }
        }
        viewModelScope.launch {
            try {
                recordRepo.getAllRecords().collect { allRecords ->
                    val thirtyDaysAgo = java.time.LocalDateTime.now().minusDays(30).format(DateFormats.DATE_TIME_ISO)
                    _oldRecords.value = allRecords.filter {
                        it.createdAt < thirtyDaysAgo && it.category != "todo" && it.category != "goal"
                    }
                    selectAllIfEmpty()
                }
            } catch (e: Throwable) {
                android.util.Log.e("OrganizeVM", "loadData allRecords failed", e)
            }
        }
        viewModelScope.launch {
            try {
                reviewRepo.getAllReviews().collect { reviews ->
                    _reviews.value = reviews
                    selectAllIfEmpty()
                }
            } catch (e: Throwable) {
                android.util.Log.e("OrganizeVM", "loadData reviews failed", e)
            }
        }
    }

    // 首次加载时默认全选
    private var hasAutoSelected = false
    private fun selectAllIfEmpty() {
        if (hasAutoSelected) return
        if (_completedRecords.value.isEmpty() && _oldRecords.value.isEmpty() && _reviews.value.isEmpty()) return
        hasAutoSelected = true
        _sourceItemIds.value = (_completedRecords.value.map { it.id } + _oldRecords.value.map { it.id }).toSet()
        _sourceReviewIds.value = _reviews.value.map { it.id }.toSet()
    }

    private fun configureAi() {
        viewModelScope.launch {
            refreshAiConfig()
        }
    }

    private suspend fun refreshAiConfig() {
            aiRepo.configureFromSettings(settingsRepo, secureSettingsRepo)
            _isConfigured.value = aiRepo.isConfigured
    }

    // ========== 选择管理 ==========

    fun toggleSourceItem(id: Long) {
        val current = _sourceItemIds.value
        _sourceItemIds.value = if (id in current) current - id else current + id
    }

    fun toggleSourceReview(id: Long) {
        val current = _sourceReviewIds.value
        _sourceReviewIds.value = if (id in current) current - id else current + id
    }

    private fun getSelectedItems(
        recordIds: Set<Long> = _sourceItemIds.value,
        reviewIds: Set<Long> = _sourceReviewIds.value
    ): List<ConsolidateItem> {
        val items = mutableListOf<ConsolidateItem>()
        for (id in recordIds) {
            val r = _completedRecords.value.find { it.id == id }
                ?: _oldRecords.value.find { it.id == id }
                ?: continue
            items.add(ConsolidateItem(
                title = r.title,
                date = r.createdAt.take(10),
                summary = r.summary.ifBlank { r.content.take(80) }
            ))
        }
        for (id in reviewIds) {
            val rv = _reviews.value.find { it.id == id } ?: continue
            items.add(ConsolidateItem(
                title = "${if (rv.type == "daily") "每日" else "每周"}评价·${rv.date}",
                date = rv.date,
                summary = rv.content.take(60)
            ))
        }
        return items
    }

    // ========== AI 操作 ==========

    fun smartConsolidate() {
        viewModelScope.launch {
            refreshAiConfig()
            if (!aiRepo.isConfigured) return@launch
            val items = getSelectedItems()
            if (items.isEmpty()) return@launch
            _aiLoading.value = true; _aiStatus.value = "正在智能归并..."
            try {
                // 获取选中记录的最早日期
                val earliestDate = items.mapNotNull { it.date.takeIf { d -> d.isNotBlank() } }.minOrNull() ?: DateFormats.nowDate()
                val results = aiRepo.consolidate(items)
                // 保存日期映射
                for (content in results) {
                    if (content !in memoryDateMap) {
                        memoryDateMap[content] = earliestDate
                    }
                }
                _memoryItems.value = (_memoryItems.value + results).distinct()
                _sourceItemIds.value = emptySet(); _sourceReviewIds.value = emptySet()
            } catch (e: Throwable) { _aiStatus.value = "归并失败：${e.message}" }
            finally { _aiLoading.value = false }
        }
    }

    fun smartClassify() {
        viewModelScope.launch {
            refreshAiConfig()
            if (!aiRepo.isConfigured) return@launch
            val items = getSelectedItems()
            if (items.isEmpty()) return@launch
            _aiLoading.value = true; _aiStatus.value = "正在智能分类..."
            try {
                val existingMemories = memoryRepo.getAllMemories().first()
                // 获取选中记录的最早日期
                val earliestDate = items.mapNotNull { it.date.takeIf { d -> d.isNotBlank() } }.minOrNull() ?: DateFormats.nowDate()
                val result = aiRepo.classify(items, existingMemories)
                if (result.memories.isNotEmpty()) {
                    // 保存日期映射
                    for (content in result.memories) {
                        if (content !in memoryDateMap) {
                            memoryDateMap[content] = earliestDate
                        }
                    }
                    _memoryItems.value = (_memoryItems.value + result.memories).distinct()
                }
                if (result.cleanable.isNotEmpty()) {
                    // 保存 cleanableItems 的日期映射
                    for (content in result.cleanable) {
                        if (content !in memoryDateMap) {
                            memoryDateMap[content] = earliestDate
                        }
                        cleanableSourceMap[content] = OrganizeSourceIds(
                            recordIds = _sourceItemIds.value,
                            reviewIds = _sourceReviewIds.value
                        )
                    }
                    _cleanableItems.value = (_cleanableItems.value + result.cleanable).distinct()
                }
                _sourceItemIds.value = emptySet(); _sourceReviewIds.value = emptySet()
            } catch (e: Throwable) { _aiStatus.value = "分类失败：${e.message}" }
            finally { _aiLoading.value = false }
        }
    }

    fun smartClean() {
        viewModelScope.launch {
            refreshAiConfig()
            if (!aiRepo.isConfigured) return@launch
            if (_cleanableItems.value.isEmpty()) return@launch
            _aiLoading.value = true; _aiStatus.value = "正在智慧清扫..."
            try {
                val existingMemories = memoryRepo.getAllMemories().first()
                val items = _cleanableItems.value.map { text ->
                    ConsolidateItem(title = text, date = memoryDateMap[text] ?: DateFormats.nowDate(), text = text)
                }
                val result = aiRepo.smartClean(items, existingMemories)
                val previousSources = cleanableSourceMap.toMap()
                _cleanableItems.value = result.shouldClean
                cleanableSourceMap.clear()
                result.shouldClean.forEach { item ->
                    previousSources[item]?.let { cleanableSourceMap[item] = it }
                }
                if (result.shouldKeep.isNotEmpty()) {
                    // 保存 shouldKeep 的日期映射（使用 cleanableItems 的日期）
                    for (content in result.shouldKeep) {
                        if (content !in memoryDateMap) {
                            // 尝试从 cleanableItems 中找到对应的日期
                            val sourceDate = result.shouldClean.firstOrNull()?.let { memoryDateMap[it] }
                                ?: memoryDateMap.values.firstOrNull()
                                ?: DateFormats.nowDate()
                            memoryDateMap[content] = sourceDate
                        }
                    }
                    _memoryItems.value = (_memoryItems.value + result.shouldKeep).distinct()
                }
            } catch (e: Throwable) { _aiStatus.value = "清扫失败：${e.message}" }
            finally { _aiLoading.value = false }
        }
    }

    fun evolveMemories() {
        viewModelScope.launch {
            refreshAiConfig()
            if (!aiRepo.isConfigured) return@launch
            val memories = memoryRepo.getAllMemories().first()
            if (memories.isEmpty()) return@launch
            _aiLoading.value = true; _aiStatus.value = "正在演化记忆..."
            try {
                _evolveResults.value = aiRepo.evolveMemories(memories)
            } catch (e: Throwable) { _aiStatus.value = "演化失败：${e.message}" }
            finally { _aiLoading.value = false }
        }
    }

    // ========== 面板操作 ==========

    fun moveToCleanable(item: String) {
        _memoryItems.value = _memoryItems.value - item
        _cleanableItems.value = (_cleanableItems.value + item).distinct()
        cleanableSourceMap.putIfAbsent(item, OrganizeSourceIds())
    }

    fun moveToMemory(item: String) {
        _cleanableItems.value = _cleanableItems.value - item
        _memoryItems.value = (_memoryItems.value + item).distinct()
        cleanableSourceMap.remove(item)
    }

    fun removeCleanable(index: Int) {
        val list = _cleanableItems.value.toMutableList()
        if (index in list.indices) {
            cleanableSourceMap.remove(list[index])
            list.removeAt(index)
        }
        _cleanableItems.value = list
    }

    fun removeMemory(index: Int) {
        val list = _memoryItems.value.toMutableList()
        if (index in list.indices) list.removeAt(index)
        _memoryItems.value = list
    }

    fun removeEvolve(index: Int) {
        val list = _evolveResults.value.toMutableList()
        if (index in list.indices) list.removeAt(index)
        _evolveResults.value = list
    }

    fun confirmSaveMemories() {
        viewModelScope.launch {
            try {
                val now = DateFormats.nowDateTimeIso()
                for (content in _memoryItems.value) {
                    // 使用原始日期，如果没有则使用当前日期
                    val originalDate = memoryDateMap[content]
                    val createdAt = if (originalDate != null) {
                        // 将日期转换为 ISO 格式
                        try {
                            if (originalDate.length == 10) {
                                // 只有日期，补充时间
                                "${originalDate}T00:00:00.000Z"
                            } else {
                                originalDate
                            }
                        } catch (e: Throwable) {
                            now
                        }
                    } else {
                        now
                    }
                    memoryRepo.insertMemory(
                        MemoryEntity(content = content, source = "organize", createdAt = createdAt, updatedAt = now)
                    )
                }
                memoryRepo.enforceMemoryLimit(100)
                _memoryItems.value = emptyList()
                memoryDateMap.clear()
                _aiStatus.value = "已存入记忆！"
            } catch (e: Throwable) {
                android.util.Log.e("OrganizeVM", "confirmSaveMemories failed", e)
                _aiStatus.value = "保存失败：${e.message}"
            }
        }
    }

    fun confirmClean() {
        viewModelScope.launch {
            try {
                val sources = _cleanableItems.value.mapNotNull { cleanableSourceMap[it] }
                val recordIds = sources.flatMap { it.recordIds }.toSet()
                val reviewIds = sources.flatMap { it.reviewIds }.toSet()
                for (id in recordIds) {
                    recordRepo.deleteRecord(id)
                }
                for (id in reviewIds) {
                    reviewRepo.deleteReview(id)
                }
                _cleanableItems.value = emptyList()
                cleanableSourceMap.clear()
                _aiStatus.value = "已清理完成！"
            } catch (e: Throwable) {
                android.util.Log.e("OrganizeVM", "confirmClean failed", e)
                _aiStatus.value = "清理失败：${e.message}"
            }
        }
    }

    fun confirmEvolve() {
        viewModelScope.launch {
            try {
                val allMemories = memoryRepo.getAllMemories().first()
                for (item in _evolveResults.value) {
                    val memory = allMemories.find { it.content == item.old }
                    if (memory != null) {
                        memoryRepo.updateMemory(memory.copy(content = item.new))
                    }
                }
                _evolveResults.value = emptyList()
                _aiStatus.value = "已应用演化！"
            } catch (e: Throwable) {
                android.util.Log.e("OrganizeVM", "confirmEvolve failed", e)
                _aiStatus.value = "演化失败：${e.message}"
            }
        }
    }

    // ========== 一键整理 ==========

    fun autoOrganize() {
        viewModelScope.launch {
            refreshAiConfig()
            if (!aiRepo.isConfigured) return@launch
            _aiLoading.value = true
            try {
                // 步骤 1: 获取当前选中项
                _aiStatus.value = "正在准备整理..."
                val allRecordIds = _sourceItemIds.value.ifEmpty {
                    (_completedRecords.value.map { it.id } + _oldRecords.value.map { it.id }).toSet()
                }
                val allReviewIds = _sourceReviewIds.value.ifEmpty {
                    _reviews.value.map { it.id }.toSet()
                }

                if (allRecordIds.isEmpty() && allReviewIds.isEmpty()) {
                    _aiStatus.value = "暂无待整理项目"
                    _aiLoading.value = false
                    return@launch
                }

                // 步骤 2: 智能分类
                _aiStatus.value = "正在智能分类..."
                val items = getSelectedItems(allRecordIds, allReviewIds)
                if (items.isNotEmpty()) {
                    val existingMemories = memoryRepo.getAllMemories().first()
                    val earliestDate = items.mapNotNull { it.date.takeIf { d -> d.isNotBlank() } }.minOrNull() ?: DateFormats.nowDate()
                    val classifyResult = aiRepo.classify(items, existingMemories)

                    if (classifyResult.memories.isNotEmpty()) {
                        for (content in classifyResult.memories) {
                            if (content !in memoryDateMap) {
                                memoryDateMap[content] = earliestDate
                            }
                        }
                        _memoryItems.value = (_memoryItems.value + classifyResult.memories).distinct()
                    }
                    if (classifyResult.cleanable.isNotEmpty()) {
                        for (content in classifyResult.cleanable) {
                            if (content !in memoryDateMap) {
                                memoryDateMap[content] = earliestDate
                            }
                            cleanableSourceMap[content] = OrganizeSourceIds(
                                recordIds = allRecordIds,
                                reviewIds = allReviewIds
                            )
                        }
                        _cleanableItems.value = (_cleanableItems.value + classifyResult.cleanable).distinct()
                    }
                }
                _sourceItemIds.value = emptySet()
                _sourceReviewIds.value = emptySet()

                // 步骤 3: 智慧清扫（如果有待清理项）
                if (_cleanableItems.value.isNotEmpty()) {
                    _aiStatus.value = "正在智慧清扫..."
                    val existingMemories = memoryRepo.getAllMemories().first()
                    val cleanItems = _cleanableItems.value.map { text ->
                        ConsolidateItem(title = text, date = memoryDateMap[text] ?: DateFormats.nowDate(), text = text)
                    }
                    val cleanResult = aiRepo.smartClean(cleanItems, existingMemories)
                    val previousSources = cleanableSourceMap.toMap()
                    _cleanableItems.value = cleanResult.shouldClean
                    cleanableSourceMap.clear()
                    cleanResult.shouldClean.forEach { item ->
                        previousSources[item]?.let { cleanableSourceMap[item] = it }
                    }
                    if (cleanResult.shouldKeep.isNotEmpty()) {
                        for (content in cleanResult.shouldKeep) {
                            if (content !in memoryDateMap) {
                                memoryDateMap[content] = memoryDateMap.values.firstOrNull() ?: DateFormats.nowDate()
                            }
                        }
                        _memoryItems.value = (_memoryItems.value + cleanResult.shouldKeep).distinct()
                    }
                }

                // 步骤 4: 记忆演化
                _aiStatus.value = "正在演化记忆..."
                val allMemories = memoryRepo.getAllMemories().first()
                if (allMemories.isNotEmpty()) {
                    _evolveResults.value = aiRepo.evolveMemories(allMemories)
                    // 自动应用演化
                    if (_evolveResults.value.isNotEmpty()) {
                        for (item in _evolveResults.value) {
                            val memory = allMemories.find { it.content == item.old }
                            if (memory != null) {
                                memoryRepo.updateMemory(memory.copy(content = item.new))
                            }
                        }
                        _evolveResults.value = emptyList()
                    }
                }

                // 步骤 5: 自动确认保存记忆
                _aiStatus.value = "正在保存记忆..."
                val now = DateFormats.nowDateTimeIso()
                for (content in _memoryItems.value) {
                    val originalDate = memoryDateMap[content]
                    val createdAt = if (originalDate != null) {
                        try {
                            if (originalDate.length == 10) {
                                "${originalDate}T00:00:00.000Z"
                            } else {
                                originalDate
                            }
                        } catch (e: Throwable) {
                            now
                        }
                    } else {
                        now
                    }
                    memoryRepo.insertMemory(
                        MemoryEntity(content = content, source = "organize", createdAt = createdAt, updatedAt = now)
                    )
                }
                memoryRepo.enforceMemoryLimit(100)

                // 步骤 6: 清理已归类的记录和评价
                val sources = _cleanableItems.value.mapNotNull { cleanableSourceMap[it] }
                val cleanRecordIds = sources.flatMap { it.recordIds }.toSet()
                val cleanReviewIds = sources.flatMap { it.reviewIds }.toSet()
                if (cleanRecordIds.isNotEmpty()) {
                    for (id in cleanRecordIds) { recordRepo.deleteRecord(id) }
                }
                if (cleanReviewIds.isNotEmpty()) {
                    for (id in cleanReviewIds) { reviewRepo.deleteReview(id) }
                }

                // 清理状态
                _memoryItems.value = emptyList()
                _cleanableItems.value = emptyList()
                cleanableSourceMap.clear()
                memoryDateMap.clear()
                _aiStatus.value = "整理完成！"

            } catch (e: Throwable) {
                android.util.Log.e("OrganizeVM", "autoOrganize failed", e)
                _aiStatus.value = "整理失败：${e.message}"
            } finally {
                _aiLoading.value = false
            }
        }
    }
}
