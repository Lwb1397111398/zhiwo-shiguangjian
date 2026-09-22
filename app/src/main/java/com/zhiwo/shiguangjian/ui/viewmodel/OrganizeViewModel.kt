package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.ai.CleanableItem
import com.zhiwo.shiguangjian.data.ai.ConsolidateItem
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.OrganizeOpEntity
import com.zhiwo.shiguangjian.data.memory.DependencyCascade
import com.zhiwo.shiguangjian.data.memory.CascadeOp
import com.zhiwo.shiguangjian.data.memory.MemorySimilarity
import com.zhiwo.shiguangjian.data.organizeops.CleanCandidate
import com.zhiwo.shiguangjian.data.organizeops.DeleteSourcePlanner
import com.zhiwo.shiguangjian.data.organizeops.EvolveMemoryPayload
import com.zhiwo.shiguangjian.data.organizeops.MemoryRef
import com.zhiwo.shiguangjian.data.organizeops.OpPayloads
import com.zhiwo.shiguangjian.data.organizeops.OpStatus
import com.zhiwo.shiguangjian.data.organizeops.OpType
import com.zhiwo.shiguangjian.data.organizeops.OpValidator
import com.zhiwo.shiguangjian.data.organizeops.OrganizeOpExecutor
import com.zhiwo.shiguangjian.data.organizeops.SaveMemoryPayload
import com.zhiwo.shiguangjian.data.repository.MemoryRepository
import com.zhiwo.shiguangjian.data.repository.RecordRepository
import com.zhiwo.shiguangjian.data.repository.ReviewRepository
import com.zhiwo.shiguangjian.data.repository.SecureSettingsRepository
import com.zhiwo.shiguangjian.data.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/** 一次"忽略"的完整现场，用于撤销 */
private data class DismissRecord(val opId: String, val cascadeIds: List<String>)

/**
 * 整理页两阶段 ViewModel：
 * Plan 阶段（generateXxx）只调 AI 并把建议写成 organize_ops 的 PENDING 提案，不碰任何业务表；
 * Apply 阶段按**勾选的 op id 集合**执行（可跨批次），由 [OrganizeOpExecutor] 逐条独立事务完成。
 * 上一批没跑完的提案常驻顶部「未完成的整理」，不再因为"界面只认最新批次"而被埋掉。
 */
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
    private val opDao = app.database.organizeOpDao()
    private val executor = OrganizeOpExecutor(app.database, memoryRepo, recordRepo, reviewRepo)
    private val aiRepo get() = app.aiRepo

    // ===== 源数据 =====
    private val _completedRecords = MutableStateFlow<List<com.zhiwo.shiguangjian.data.db.entity.RecordEntity>>(emptyList())
    val completedRecords: StateFlow<List<com.zhiwo.shiguangjian.data.db.entity.RecordEntity>> = _completedRecords

    private val _oldRecords = MutableStateFlow<List<com.zhiwo.shiguangjian.data.db.entity.RecordEntity>>(emptyList())
    val oldRecords: StateFlow<List<com.zhiwo.shiguangjian.data.db.entity.RecordEntity>> = _oldRecords

    private val _reviews = MutableStateFlow<List<com.zhiwo.shiguangjian.data.db.entity.ReviewEntity>>(emptyList())
    val reviews: StateFlow<List<com.zhiwo.shiguangjian.data.db.entity.ReviewEntity>> = _reviews

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured

    private val _aiLoading = MutableStateFlow(false)
    val aiLoading: StateFlow<Boolean> = _aiLoading

    private val _aiStatus = MutableStateFlow("")
    val aiStatus: StateFlow<String> = _aiStatus

    private val _sourceItemIds = MutableStateFlow<Set<Long>>(emptySet())
    val sourceItemIds: StateFlow<Set<Long>> = _sourceItemIds

    private val _sourceReviewIds = MutableStateFlow<Set<Long>>(emptySet())
    val sourceReviewIds: StateFlow<Set<Long>> = _sourceReviewIds

    // ===== 提案批次 =====
    private val _currentBatchId = MutableStateFlow<String?>(null)
    val currentBatchId: StateFlow<String?> = _currentBatchId

    val ops: StateFlow<List<OrganizeOpEntity>> = _currentBatchId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else opDao.getOpsByBatch(id)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 跨批次的非终态提案（PENDING/FAILED/STALE/BLOCKED）：顶部常驻「未完成的整理」 */
    val unfinishedOps: StateFlow<List<OrganizeOpEntity>> = opDao.getUnfinishedOps()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _batchApplying = MutableStateFlow(false)
    val batchApplying: StateFlow<Boolean> = _batchApplying

    /** 最近一次忽略的级联现场（供"撤销忽略"） */
    private var lastDismiss: DismissRecord? = null
    private val _canUndoDismiss = MutableStateFlow(false)
    val canUndoDismiss: StateFlow<Boolean> = _canUndoDismiss

    init {
        loadData()
        configureAi()
        loadLatestBatch()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                recordRepo.getRecordsByCategory("completed").collect { records ->
                    _completedRecords.value = records
                    selectAllIfEmpty()
                }
            } catch (e: Throwable) {
                Log.e("OrganizeVM", "loadData completed failed", e)
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
                Log.e("OrganizeVM", "loadData allRecords failed", e)
            }
        }
        viewModelScope.launch {
            try {
                reviewRepo.getAllReviews().collect { reviews ->
                    _reviews.value = reviews
                    selectAllIfEmpty()
                }
            } catch (e: Throwable) {
                Log.e("OrganizeVM", "loadData reviews failed", e)
            }
        }
    }

    private var hasAutoSelected = false
    private fun selectAllIfEmpty() {
        if (hasAutoSelected) return
        if (_completedRecords.value.isEmpty() && _oldRecords.value.isEmpty() && _reviews.value.isEmpty()) return
        hasAutoSelected = true
        _sourceItemIds.value = (_completedRecords.value.map { it.id } + _oldRecords.value.map { it.id }).toSet()
        _sourceReviewIds.value = _reviews.value.map { it.id }.toSet()
    }

    private fun configureAi() {
        viewModelScope.launch { refreshAiConfig() }
    }

    private suspend fun refreshAiConfig() {
        aiRepo.configureFromSettings(settingsRepo, secureSettingsRepo)
        _isConfigured.value = aiRepo.isConfigured
    }

    private fun loadLatestBatch() {
        viewModelScope.launch {
            try {
                val latest = opDao.getLatestOp()
                _currentBatchId.value = latest?.batchId
            } catch (e: Throwable) {
                Log.e("OrganizeVM", "loadLatestBatch failed", e)
            }
        }
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

    /** 所选源数据：items 给 AI 看（序号即下标+1），refs 是同号的落库回指（提案靠它绑定"只删自己那一组"） */
    private class Selection(val items: List<ConsolidateItem>, val refs: List<CleanCandidate>)

    private fun getSelected(
        recordIds: Set<Long> = _sourceItemIds.value,
        reviewIds: Set<Long> = _sourceReviewIds.value
    ): Selection {
        val items = mutableListOf<ConsolidateItem>()
        val refs = mutableListOf<CleanCandidate>()
        for (id in recordIds) {
            val r = _completedRecords.value.find { it.id == id }
                ?: _oldRecords.value.find { it.id == id }
                ?: continue
            items.add(
                ConsolidateItem(
                    title = r.title,
                    date = r.createdAt.take(10),
                    summary = r.summary.ifBlank { r.content.take(80) }
                )
            )
            refs.add(
                CleanCandidate(
                    id = r.id,
                    isReview = false,
                    label = r.title,
                    version = r.updatedAt,
                    contentHash = OpPayloads.contentHash(r.title, r.content)
                )
            )
        }
        for (id in reviewIds) {
            val rv = _reviews.value.find { it.id == id } ?: continue
            val label = "${if (rv.type == "daily") "每日" else "每周"}评价·${rv.date}"
            items.add(
                ConsolidateItem(
                    title = label,
                    date = rv.date,
                    summary = rv.content.take(60)
                )
            )
            refs.add(
                CleanCandidate(
                    id = rv.id,
                    isReview = true,
                    label = label,
                    version = rv.createdAt,
                    contentHash = OpPayloads.contentHash(rv.content)
                )
            )
        }
        return Selection(items, refs)
    }

    // ========== Plan 阶段：只生成提案，不写业务表 ==========

    /** 一键整理 = 判重合并 + 分类 + 清扫复审 + 演化，全部落为提案 */
    fun generateProposals() {
        viewModelScope.launch {
            refreshAiConfig()
            if (!aiRepo.isConfigured) {
                _aiStatus.value = "请先配置 AI 接口"
                return@launch
            }
            val recordIds = _sourceItemIds.value.ifEmpty {
                (_completedRecords.value.map { it.id } + _oldRecords.value.map { it.id }).toSet()
            }
            val reviewIds = _sourceReviewIds.value.ifEmpty { _reviews.value.map { it.id }.toSet() }
            if (recordIds.isEmpty() && reviewIds.isEmpty()) {
                _aiStatus.value = "暂无待整理项目"
                return@launch
            }

            _aiLoading.value = true
            try {
                val selection = getSelected(recordIds, reviewIds)
                if (selection.items.isEmpty()) {
                    _aiStatus.value = "所选项目已不可用，请刷新"
                    return@launch
                }
                val earliestDate = selection.items.mapNotNull { it.date.takeIf { d -> d.isNotBlank() } }.minOrNull()
                    ?: DateFormats.nowDate()

                // 1. 智能分类（失败直接抛，不产生半截批次）
                _aiStatus.value = "正在智能分类..."
                val existingMemories = memoryRepo.getAllMemories().first().filter { it.status == "active" }
                val classifyResult = aiRepo.classify(selection.items, existingMemories)

                // 2. 清扫复审（序号要翻译回原始条目序号，见 translate）
                var finalCleanable = classifyResult.cleanable
                var keptFromClean = emptyList<CleanableItem>()
                if (classifyResult.cleanable.isNotEmpty()) {
                    _aiStatus.value = "正在智慧清扫..."
                    val stage = classifyResult.cleanable
                    val cleanItems = stage.map { text ->
                        ConsolidateItem(title = text.text, date = earliestDate, text = text.text)
                    }
                    val cleanResult = aiRepo.smartClean(cleanItems, existingMemories)
                    finalCleanable = cleanResult.shouldClean.map { translate(it, stage) }
                    keptFromClean = cleanResult.shouldKeep.map { translate(it, stage) }
                }

                // 3. 记忆演化 + 现存记忆的判重合并（"整理后残存"的另一半：旧条目从没被合并过）
                _aiStatus.value = "正在演化记忆..."
                val batchId = UUID.randomUUID().toString()
                val evolveOps = buildEvolveOps(batchId)
                // 同一条记忆不能既演化又合并：演化后它就 superseded 了，合并提案必然 STALE，白折腾
                val touchedByEvolve = evolveOps.mapNotNull { op ->
                    OpPayloads.decodeEvolveMemory(op.payloadJson)?.memoryId
                }.toSet()
                val mergeOps = buildDedupeMergeOps(batchId, touchedByEvolve)

                // 4. 汇总生成提案批次
                val now = System.currentTimeMillis()
                val opsToInsert = mutableListOf<OrganizeOpEntity>()

                val saveCandidates = (classifyResult.memories + keptFromClean.map { it.text })
                val filter = OpValidator.filterSaveProposals(
                    saveCandidates,
                    existingMemories.map { it.content } + (mergeOps.mapNotNull {
                        OpPayloads.decodeMergeMemory(it.payloadJson)?.mergedContent
                    })
                )
                filter.keep.forEach { content ->
                    opsToInsert += newOp(batchId, OpType.SAVE_MEMORY,
                        OpPayloads.encode(SaveMemoryPayload(content = content, sourceDate = earliestDate)), now)
                }

                val dependsOn = OpPayloads.encodeDependsOn(
                    (opsToInsert.map { it.id } + evolveOps.map { it.id } + mergeOps.map { it.id }).distinct()
                )
                // 每条清理提案只携带它自己那一组源；一条都绑定不上就不生成（防"勾 1 条删全部"）
                var skippedDeletes = 0
                finalCleanable.forEach { item ->
                    val payload = DeleteSourcePlanner.plan(item.text, item.itemIndexes, selection.refs)
                    if (payload == null) skippedDeletes++
                    else opsToInsert += newOp(batchId, OpType.DELETE_SOURCE, OpPayloads.encode(payload), now, dependsOn)
                }
                opsToInsert += evolveOps
                opsToInsert += mergeOps

                if (opsToInsert.isEmpty()) {
                    _aiStatus.value = buildString {
                        append(if (filter.skipped.isNotEmpty()) "没有新记忆：${filter.skipped.size} 条与现有记忆重复，已跳过" else "AI 认为当前没有需要整理的内容")
                        if (skippedDeletes > 0) append("；$skippedDeletes 条清理项没绑定到源数据，已丢弃")
                    }
                    return@launch
                }
                opDao.insertOps(opsToInsert)
                _sourceItemIds.value = emptySet()
                _sourceReviewIds.value = emptySet()
                _currentBatchId.value = batchId
                _aiStatus.value = buildString {
                    append("已生成 ${opsToInsert.size} 条提案，请逐条审核后执行")
                    if (filter.skipped.isNotEmpty()) append("（${filter.skipped.size} 条与现有记忆重复，未重复入库）")
                    if (filter.suspicious.isNotEmpty()) append("；${filter.suspicious.size} 条与现有记忆只是表述相近，已留给你确认")
                    if (skippedDeletes > 0) append("；$skippedDeletes 条清理项未匹配到源数据，已丢弃")
                }
            } catch (e: Throwable) {
                Log.e("OrganizeVM", "生成提案失败", e)
                _aiStatus.value = "AI 调用失败，未生成建议：${e.message ?: "未知错误"}（未修改任何数据）"
            } finally {
                _aiLoading.value = false
            }
        }
    }

    /** 单步：智能归并 → 提案（存记忆） */
    fun consolidateProposals() {
        viewModelScope.launch {
            refreshAiConfig()
            if (!aiRepo.isConfigured) return@launch
            val selection = getSelected()
            if (selection.items.isEmpty()) return@launch
            _aiLoading.value = true; _aiStatus.value = "正在智能归并..."
            try {
                val earliestDate = selection.items.mapNotNull { it.date.takeIf { d -> d.isNotBlank() } }.minOrNull()
                    ?: DateFormats.nowDate()
                val results = aiRepo.consolidate(selection.items)
                if (results.isEmpty()) {
                    _aiStatus.value = "AI 未给出归并建议"
                    return@launch
                }
                val existing = memoryRepo.getAllMemories().first().filter { it.status == "active" }.map { it.content }
                val filter = OpValidator.filterSaveProposals(results, existing)
                if (filter.keep.isEmpty()) {
                    _aiStatus.value = "归并结果与现有记忆重复，未生成提案"
                    return@launch
                }
                val batchId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                opDao.insertOps(filter.keep.map { content ->
                    newOp(batchId, OpType.SAVE_MEMORY,
                        OpPayloads.encode(SaveMemoryPayload(content = content, sourceDate = earliestDate)), now)
                })
                _currentBatchId.value = batchId
                _aiStatus.value = "已生成 ${filter.keep.size} 条存记忆提案，请审核" +
                    if (filter.skipped.isNotEmpty()) "（${filter.skipped.size} 条重复已跳过）" else ""
            } catch (e: Throwable) {
                Log.e("OrganizeVM", "consolidateProposals failed", e)
                _aiStatus.value = "AI 调用失败，未生成建议：${e.message ?: "未知错误"}（未修改任何数据）"
            } finally { _aiLoading.value = false }
        }
    }

    /** 单步：智能分类 → 提案（存记忆 + 清理源） */
    fun classifyProposals() {
        viewModelScope.launch {
            refreshAiConfig()
            if (!aiRepo.isConfigured) return@launch
            val recordIds = _sourceItemIds.value
            val reviewIds = _sourceReviewIds.value
            val selection = getSelected(recordIds, reviewIds)
            if (selection.items.isEmpty()) return@launch
            _aiLoading.value = true; _aiStatus.value = "正在智能分类..."
            try {
                val earliestDate = selection.items.mapNotNull { it.date.takeIf { d -> d.isNotBlank() } }.minOrNull()
                    ?: DateFormats.nowDate()
                val existingMemories = memoryRepo.getAllMemories().first().filter { it.status == "active" }
                val result = aiRepo.classify(selection.items, existingMemories)
                if (result.memories.isEmpty() && result.cleanable.isEmpty()) {
                    _aiStatus.value = "AI 未给出分类建议"
                    return@launch
                }
                val batchId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val opsToInsert = mutableListOf<OrganizeOpEntity>()
                val filter = OpValidator.filterSaveProposals(result.memories, existingMemories.map { it.content })
                filter.keep.forEach { content ->
                    opsToInsert += newOp(batchId, OpType.SAVE_MEMORY,
                        OpPayloads.encode(SaveMemoryPayload(content = content, sourceDate = earliestDate)), now)
                }
                // DELETE 依赖同批全部 SAVE，前置失败时禁止删源
                val dependsOn = OpPayloads.encodeDependsOn(opsToInsert.map { it.id })
                var skippedDeletes = 0
                result.cleanable.forEach { item ->
                    val payload = DeleteSourcePlanner.plan(item.text, item.itemIndexes, selection.refs)
                    if (payload == null) skippedDeletes++
                    else opsToInsert += newOp(batchId, OpType.DELETE_SOURCE, OpPayloads.encode(payload), now, dependsOn)
                }
                if (opsToInsert.isEmpty()) {
                    _aiStatus.value = "分类结果与现有记忆重复，未生成提案" +
                        if (skippedDeletes > 0) "；$skippedDeletes 条清理项未匹配到源数据，已丢弃" else ""
                    return@launch
                }
                opDao.insertOps(opsToInsert)
                _sourceItemIds.value = emptySet()
                _sourceReviewIds.value = emptySet()
                _currentBatchId.value = batchId
                _aiStatus.value = "已生成 ${opsToInsert.size} 条提案，请审核" +
                    if (skippedDeletes > 0) "；$skippedDeletes 条清理项未匹配到源数据，已丢弃" else ""
            } catch (e: Throwable) {
                Log.e("OrganizeVM", "classifyProposals failed", e)
                _aiStatus.value = "AI 调用失败，未生成建议：${e.message ?: "未知错误"}（未修改任何数据）"
            } finally { _aiLoading.value = false }
        }
    }

    /** 单步：记忆演化 → 提案 */
    fun evolveProposals() {
        viewModelScope.launch {
            refreshAiConfig()
            if (!aiRepo.isConfigured) return@launch
            _aiLoading.value = true; _aiStatus.value = "正在演化记忆..."
            try {
                val batchId = UUID.randomUUID().toString()
                val evolveOps = buildEvolveOps(batchId)
                if (evolveOps.isEmpty()) {
                    _aiStatus.value = "AI 未给出演化建议"
                    return@launch
                }
                opDao.insertOps(evolveOps)
                _currentBatchId.value = batchId
                _aiStatus.value = "已生成 ${evolveOps.size} 条演化提案，请审核"
            } catch (e: Throwable) {
                Log.e("OrganizeVM", "evolveProposals failed", e)
                _aiStatus.value = "AI 调用失败，未生成建议：${e.message ?: "未知错误"}"
            } finally { _aiLoading.value = false }
        }
    }

    /** 单步：智慧清扫 → 对当前批次 PENDING 的清理提案复审（保留的转为存记忆提案） */
    fun smartCleanProposals() {
        viewModelScope.launch {
            refreshAiConfig()
            if (!aiRepo.isConfigured) return@launch
            val batchId = _currentBatchId.value ?: run {
                _aiStatus.value = "当前没有待清扫的提案"; return@launch
            }
            val pendingDeletes = opDao.getOpsByBatchOnce(batchId)
                .filter { it.type == OpType.DELETE_SOURCE && it.status == OpStatus.PENDING }
            if (pendingDeletes.isEmpty()) {
                _aiStatus.value = "当前批次没有待清理提案"; return@launch
            }
            _aiLoading.value = true; _aiStatus.value = "正在智慧清扫..."
            var skippedReReviews = 0
            try {
                val existingMemories = memoryRepo.getAllMemories().first().filter { it.status == "active" }
                // stage 的第 i 项自带序号 i+1（= 它自己就是第几条待清理提案），复审结果靠这个序号回指提案
                val stage = pendingDeletes.mapIndexed { i, op ->
                    val label = OpPayloads.decodeDeleteSource(op.payloadJson)?.label ?: return@mapIndexed null
                    CleanableItem(label, listOf(i + 1))
                }.filterNotNull()
                val items = stage.map { ConsolidateItem(title = it.text, date = "", text = it.text) }
                val result = aiRepo.smartClean(items, existingMemories)
                val now = System.currentTimeMillis()
                // 保留项 → 转 SAVE_MEMORY 提案；清理项维持 PENDING；被 AI 判为保留的原 DELETE 置 DISMISSED
                // 定位靠序号（translate），不再用 label 文字全等反查
                result.shouldKeep.forEach { kept ->
                    val target = translate(kept, stage).itemIndexes
                        .mapNotNull { i -> pendingDeletes.getOrNull(i - 1) }
                        .firstOrNull()
                    if (target == null) {
                        skippedReReviews++
                        return@forEach
                    }
                    opDao.updateOp(target.copy(status = OpStatus.DISMISSED, updatedAt = now))
                    opDao.insertOps(listOf(newOp(batchId, OpType.SAVE_MEMORY,
                        OpPayloads.encode(SaveMemoryPayload(content = kept.text, sourceDate = DateFormats.nowDate())), now)))
                }
                _aiStatus.value = "复审完成：${result.shouldKeep.size - skippedReReviews} 条转为存记忆，" +
                    "${result.shouldClean.size} 条维持清理提案" +
                    if (skippedReReviews > 0) "；$skippedReReviews 条没匹配到对应提案，已保持原样" else ""
            } catch (e: Throwable) {
                Log.e("OrganizeVM", "smartCleanProposals failed", e)
                _aiStatus.value = "AI 调用失败，未生成建议：${e.message ?: "未知错误"}（未修改任何数据）"
            } finally { _aiLoading.value = false }
        }
    }

    // ========== 审核操作 ==========

    fun toggleChecked(opId: String) {
        viewModelScope.launch {
            val op = opDao.getOpById(opId) ?: return@launch
            opDao.updateOp(op.copy(checked = !op.checked, updatedAt = System.currentTimeMillis()))
        }
    }

    /** 编辑提案文本（存记忆/演化/合并结果/拆分条目），编辑后即为唯一执行来源 */
    fun editOpText(opId: String, newText: String) {
        viewModelScope.launch {
            val op = opDao.getOpById(opId) ?: return@launch
            val trimmed = newText.trim()
            if (trimmed.isEmpty()) return@launch
            val newPayload = when (op.type) {
                OpType.SAVE_MEMORY -> OpPayloads.decodeSaveMemory(op.payloadJson)
                    ?.copy(content = trimmed)?.let(OpPayloads::encode)
                OpType.EVOLVE_MEMORY -> OpPayloads.decodeEvolveMemory(op.payloadJson)
                    ?.copy(newText = trimmed)?.let(OpPayloads::encode)
                OpType.MERGE_MEMORY -> OpPayloads.decodeMergeMemory(op.payloadJson)
                    ?.copy(mergedContent = trimmed)?.let(OpPayloads::encode)
                OpType.SPLIT_MEMORY -> OpPayloads.decodeSplitMemory(op.payloadJson)
                    ?.copy(parts = op.payloadJson.splitPartsWith(trimmed))?.let(OpPayloads::encode)
                else -> null
            } ?: return@launch
            opDao.updateOp(op.copy(payloadJson = newPayload, updatedAt = System.currentTimeMillis()))
        }
    }

    /** 拆分提案的"编辑"只改第一条，其余保持（多行编辑不在私人场景做复杂） */
    private fun String.splitPartsWith(first: String): List<String> {
        val parts = OpPayloads.decodeSplitMemory(this)?.parts ?: return listOf(first)
        return listOf(first) + parts.drop(1)
    }

    /** 恢复 AI 原始建议 */
    fun restorePreview(opId: String) {
        viewModelScope.launch {
            val op = opDao.getOpById(opId) ?: return@launch
            val preview = op.previewJson ?: return@launch
            opDao.updateOp(op.copy(payloadJson = preview, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * 忽略一条提案：级联取消"全部父依赖都被取消"的下游清理提案（否则下游永远 BLOCKED 在暗处）。
     * 结果写进 [lastDismiss]，可 [undoDismiss] 还原。
     */
    fun dismissOp(opId: String) {
        viewModelScope.launch {
            val op = opDao.getOpById(opId) ?: return@launch
            val now = System.currentTimeMillis()
            val universe = cascadeUniverse(opId)
            val cascade = DependencyCascade.cascadeCancel(opId, universe)
            opDao.updateOp(op.copy(status = OpStatus.DISMISSED, updatedAt = now))
            cascade.forEach { id ->
                opDao.getOpById(id)?.let { dep ->
                    if (dep.status != OpStatus.DISMISSED) {
                        opDao.updateOp(dep.copy(status = OpStatus.DISMISSED, error = "上游提案已被忽略", updatedAt = now))
                    }
                }
            }
            // 没被级联取消、但依赖链经过本条的下游：它们会被永久阻塞，必须写明原因而不是静默卡住
            val stranded = DependencyCascade.allDependents(opId, universe).filterNot { it in cascade }
            stranded.forEach { id ->
                opDao.getOpById(id)?.let { dep ->
                    if (dep.status == OpStatus.PENDING || dep.status == OpStatus.BLOCKED) {
                        opDao.updateOp(
                            dep.copy(
                                status = OpStatus.BLOCKED,
                                error = "它依赖的提案已被忽略，永远不会全部成功；要么勾选后用「只删不存」强制执行，要么丢弃这条",
                                updatedAt = now
                            )
                        )
                    }
                }
            }
            lastDismiss = DismissRecord(opId, cascade)
            _canUndoDismiss.value = true
            _aiStatus.value = buildString {
                append("已忽略 1 条提案")
                if (cascade.isNotEmpty()) append("，同时取消 ${cascade.size} 条依赖它的清理")
                if (stranded.isNotEmpty()) append("；另有 ${stranded.size} 条清理被阻塞，见「未完成的整理」")
            }
        }
    }

    /** 撤销忽略：把刚被级联取消的下游恢复为 PENDING */
    fun undoDismiss() {
        viewModelScope.launch {
            val record = lastDismiss ?: return@launch
            val now = System.currentTimeMillis()
            val universe = cascadeUniverse(record.opId)
            val ids = (listOf(record.opId) + DependencyCascade.restoreSet(record.opId, universe) + record.cascadeIds).distinct()
            var restored = 0
            ids.forEach { id ->
                opDao.getOpById(id)?.let { op ->
                    if (op.status == OpStatus.DISMISSED) {
                        opDao.updateOp(op.copy(status = OpStatus.PENDING, error = null, checked = true, updatedAt = now))
                        restored++
                    }
                }
            }
            lastDismiss = null
            _canUndoDismiss.value = false
            _aiStatus.value = "已撤销忽略，恢复 $restored 条提案为待审核"
        }
    }

    /** 级联计算的输入：当前批次 + 全部未完成提案（跨批次依赖也在里面） */
    private suspend fun cascadeUniverse(rootId: String): List<CascadeOp> {
        val byId = LinkedHashMap<String, OrganizeOpEntity>()
        (opDao.getUnfinishedOpsOnce() + opDao.getOpsByIds(listOf(rootId))).forEach { byId[it.id] = it }
        _currentBatchId.value?.let { batch ->
            opDao.getOpsByBatchOnce(batch).forEach { byId[it.id] = it }
        }
        return byId.values.map {
            CascadeOp(it.id, OpPayloads.decodeDependsOn(it.dependsOn), it.status)
        }
    }

    /** 失败/过期/被阻塞项重试：先重置为 PENDING 再立即执行该条 */
    fun retryOp(opId: String) {
        viewModelScope.launch {
            val op = opDao.getOpById(opId) ?: return@launch
            if (op.status == OpStatus.FAILED || op.status == OpStatus.STALE || op.status == OpStatus.BLOCKED) {
                opDao.updateOp(op.copy(status = OpStatus.PENDING, error = null, updatedAt = System.currentTimeMillis()))
            }
            runOps(listOf(opId), "重试", onlyChecked = false)
        }
    }

    /** 「继续执行」：单条立即执行（忽略勾选框，用户点这条就是要执行它） */
    fun runSingleOp(opId: String) {
        viewModelScope.launch { runOps(listOf(opId), "执行", onlyChecked = false) }
    }

    /** Apply 阶段：执行传入的 op id 集合（可跨批次） */
    fun applyOps(opIds: Collection<String>) {
        viewModelScope.launch { runOps(opIds.toList(), "执行") }
    }

    /** 「全部同意」：当前批次 + 未完成区里所有勾选且非终态的提案，按 id 集合执行 */
    fun applyAllChecked() {
        viewModelScope.launch {
            val ids = LinkedHashSet<String>()
            val batch = _currentBatchId.value
            if (batch != null) {
                opDao.getOpsByBatchOnce(batch).forEach { if (it.checked && it.status in OpStatus.UNFINISHED) ids += it.id }
            }
            opDao.getUnfinishedOpsOnce().forEach { if (it.checked && it.status in OpStatus.UNFINISHED) ids += it.id }
            if (ids.isEmpty()) {
                _aiStatus.value = "没有勾选且待执行的提案"
                return@launch
            }
            runOps(ids.toList(), "执行")
        }
    }

    /** 统一执行入口：跑完后把汇总写进状态栏 */
    private suspend fun runOps(opIds: List<String>, label: String, onlyChecked: Boolean = true) {
        if (opIds.isEmpty()) return
        _batchApplying.value = true
        try {
            val summary = executor.applyOps(opIds, onlyChecked)
            _aiStatus.value = buildString {
                append("$label 完成：成功 ${summary.executed} 条")
                if (summary.blocked > 0) append("，${summary.blocked} 条因前置未成功被阻止（源数据未动）")
                if (summary.stale > 0) append("，${summary.stale} 条因源数据变化需重新生成")
                if (summary.failed > 0) append("，${summary.failed} 条失败可重试")
            }
            if (summary.total == 0) _aiStatus.value = "$label：没有需要执行的提案（可能已全部处理过）"
        } catch (e: Throwable) {
            Log.e("OrganizeVM", "applyOps failed", e)
            _aiStatus.value = "执行中断：${e.message ?: "未知错误"}，已完成项不受影响"
        } finally {
            _batchApplying.value = false
        }
    }

    /** 丢弃一条（与忽略同义，供「未完成的整理」区使用） */
    fun discardOp(opId: String) = dismissOp(opId)

    /** 依赖当前 op 的下游删除提案（供 UI 做级联提示） */
    fun dependentsOf(opId: String): List<OrganizeOpEntity> =
        ops.value.filter { op -> OpPayloads.decodeDependsOn(op.dependsOn).contains(opId) }

    /** 取消勾选 op；级联取消其下游删除提案（数据安全默认） */
    fun uncheckWithCascade(opId: String) {
        viewModelScope.launch {
            val targets = mutableListOf(opId)
            targets += dependentsOf(opId).map { it.id }
            val now = System.currentTimeMillis()
            targets.forEach { id ->
                opDao.getOpById(id)?.let { op ->
                    if (op.checked) opDao.updateOp(op.copy(checked = false, updatedAt = now))
                }
            }
        }
    }

    /**
     * 用户明示"只删不存"（双重确认后）：取消勾选本条存记忆提案，
     * 下游删除提案清除依赖并打 forceDelete 标记（执行时跳过依赖检查，其余校验照常）。
     */
    fun forceUncheckSave(opId: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            opDao.getOpById(opId)?.let { op ->
                if (op.checked) opDao.updateOp(op.copy(checked = false, updatedAt = now))
            }
            dependentsOf(opId).forEach { dep ->
                val payload = OpPayloads.decodeDeleteSource(dep.payloadJson) ?: return@forEach
                val forced = payload.copy(forceDelete = true)
                opDao.updateOp(
                    dep.copy(
                        dependsOn = null,
                        payloadJson = OpPayloads.encode(forced),
                        status = if (dep.status == OpStatus.BLOCKED) OpStatus.PENDING else dep.status,
                        error = null,
                        updatedAt = now
                    )
                )
            }
        }
    }

    /** 清理已完结（APPLIED/DISMISSED）提案：跨批次一次清干净，历史包袱不再留在表里 */
    fun clearFinishedOps() {
        viewModelScope.launch {
            try {
                opDao.clearAllFinishedOps()
                _aiStatus.value = "已清理全部已完结提案"
            } catch (e: Throwable) {
                Log.e("OrganizeVM", "clearFinishedOps failed", e)
            }
        }
    }

    // ========== 内部工具 ==========

    /** AI 给的序号是相对 [stage] 的；翻译回原始条目序号，序号缺失时退回文本匹配 */
    private fun translate(item: CleanableItem, stage: List<CleanableItem>): CleanableItem {
        val mapped = item.itemIndexes
            .mapNotNull { i -> stage.getOrNull(i - 1) }
            .flatMap { it.itemIndexes }
            .distinct()
        if (mapped.isNotEmpty()) return item.copy(itemIndexes = mapped)
        val parent = stage.firstOrNull { MemorySimilarity.autoMergeable(item.text, it.text) }
        return item.copy(itemIndexes = parent?.itemIndexes ?: emptyList())
    }

    private suspend fun buildEvolveOps(batchId: String): List<OrganizeOpEntity> {
        val activeMemories = memoryRepo.getAllMemories().first().filter { it.status == "active" }
        if (activeMemories.isEmpty()) return emptyList()
        val results = aiRepo.evolveMemories(activeMemories)
        if (results.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return results.mapNotNull { item ->
            // AI 按文本匹配，这里解析成稳定的 memoryId（匹配不到的丢弃，避免误改）
            val memory = activeMemories.find { it.content.trim() == item.old.trim() }
                ?: return@mapNotNull null
            newOp(batchId, OpType.EVOLVE_MEMORY,
                OpPayloads.encode(EvolveMemoryPayload(
                    memoryId = memory.id, oldText = memory.content, newText = item.new
                )), now)
        }
    }

    /**
     * 现存 active 记忆里"换个说法的同一条"批量生成 MERGE_MEMORY 提案。
     * 判重口径见 MemorySimilarity：只有归一化全等 / 完整包含才自动出合并提案，SIMILAR 不出。
     */
    private suspend fun buildDedupeMergeOps(batchId: String, excludeMemoryIds: Set<Long> = emptySet()): List<OrganizeOpEntity> {
        val active = memoryRepo.getAllMemories().first().filter { it.status == "active" && it.id !in excludeMemoryIds }
        if (active.size < 2) return emptyList()
        val refs = active.map { MemoryRef(it.id, it.content, it.status) }
        val now = System.currentTimeMillis()
        return OpValidator.planDedupeMerges(refs).map { payload ->
            newOp(batchId, OpType.MERGE_MEMORY, OpPayloads.encode(payload), now)
        }
    }

    private fun newOp(
        batchId: String,
        type: String,
        payloadJson: String,
        now: Long,
        dependsOn: String? = null
    ): OrganizeOpEntity =
        OrganizeOpEntity(
            id = UUID.randomUUID().toString(),
            batchId = batchId,
            type = type,
            status = OpStatus.PENDING,
            payloadJson = payloadJson,
            previewJson = payloadJson,  // 初始一致，编辑后 payloadJson 偏离即为"已修改"
            checked = true,
            dependsOn = dependsOn,
            createdAt = now,
            updatedAt = now
        )
}
