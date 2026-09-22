package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.ai.AnalyzeResult
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import com.zhiwo.shiguangjian.data.db.entity.OrganizeOpEntity
import com.zhiwo.shiguangjian.data.memory.ReconcileSuggestion
import com.zhiwo.shiguangjian.data.organizeops.EvolveMemoryPayload
import com.zhiwo.shiguangjian.data.organizeops.MergeMemoryPayload
import com.zhiwo.shiguangjian.data.organizeops.OpPayloads
import com.zhiwo.shiguangjian.data.organizeops.OpStatus
import com.zhiwo.shiguangjian.data.organizeops.OpType
import com.zhiwo.shiguangjian.data.organizeops.SplitMemoryPayload
import com.zhiwo.shiguangjian.data.repository.MemoryRepository
import com.zhiwo.shiguangjian.data.repository.SecureSettingsRepository
import com.zhiwo.shiguangjian.data.repository.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhiwoApplication
    private val memoryRepo = MemoryRepository(app.database.memoryDao())
    private val settingsRepo = SettingsRepository(app.database.settingDao())
    private val secureSettingsRepo = SecureSettingsRepository(app)
    private val opDao = app.database.organizeOpDao()
    private val aiRepo get() = app.aiRepo
    private val reconcileService get() = app.memoryReconciler

    private val _memories = MutableStateFlow<List<MemoryEntity>>(emptyList())
    val memories: StateFlow<List<MemoryEntity>> = _memories

    /** 全部记忆（含 under_review/superseded），供待确认卡片显示旧内容 */
    val allMemories: StateFlow<List<MemoryEntity>> = _memories

    /** 生效中的记忆（主列表展示） */
    val activeMemories: StateFlow<List<MemoryEntity>> = _memories
        .map { list -> list.filter { it.status == "active" } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 已被更正停用的记忆（"已更正"折叠区） */
    val supersededMemories: StateFlow<List<MemoryEntity>> = _memories
        .map { list -> list.filter { it.status == "superseded" } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 待用户确认的更正建议 */
    val pendingCorrections: StateFlow<List<ReconcileSuggestion>> = reconcileService
        .observePending()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _analyzing = MutableStateFlow(false)
    val analyzing: StateFlow<Boolean> = _analyzing

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured

    private val _analysisResult = MutableStateFlow<AnalyzeResult?>(null)
    val analysisResult: StateFlow<AnalyzeResult?> = _analysisResult

    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying

    private val _applyMessage = Channel<String>(Channel.BUFFERED)
    val applyMessage: Flow<String> = _applyMessage.receiveAsFlow()

    init {
        viewModelScope.launch {
            memoryRepo.getAllMemories().collect { _memories.value = it }
        }
        configureAi()
        // 补跑上次失败的对账（AI 已配置时）+ 清理孤儿建议
        viewModelScope.launch {
            try {
                reconcileService.prunePending()
                reconcileService.flushRetries(DateFormats.nowDateTimeIso())
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "补跑对账失败", e)
            }
        }
    }

    private suspend fun refreshAiConfig() {
            aiRepo.configureFromSettings(settingsRepo, secureSettingsRepo)
            _isConfigured.value = aiRepo.isConfigured
    }

    private fun configureAi() {
        viewModelScope.launch { refreshAiConfig() }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            try {
                memoryRepo.deleteMemory(id)
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "删除记忆失败", e)
            }
        }
    }

    // ========== 更正确认（对账） ==========

    /** 应用单条待确认更正 */
    fun applyPendingCorrection(suggestion: ReconcileSuggestion) {
        viewModelScope.launch {
            try {
                val ok = reconcileService.applyPending(suggestion, DateFormats.nowDateTimeIso())
                _applyMessage.send(if (ok) "已更正记忆" else "源记忆已不存在或已处理")
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "应用更正失败", e)
                _applyMessage.send("应用更正失败")
            }
        }
    }

    /** 忽略单条待确认更正（旧记忆恢复 active，且不再重复提示） */
    fun dismissPendingCorrection(suggestion: ReconcileSuggestion) {
        viewModelScope.launch {
            try {
                reconcileService.dismissPendingSuspend(suggestion.memoryId, DateFormats.nowDateTimeIso())
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "忽略更正失败", e)
            }
        }
    }

    /** 一键应用全部待确认更正 */
    fun applyAllPendingCorrections() {
        viewModelScope.launch {
            try {
                _applying.value = true
                val count = reconcileService.applyAllPending(DateFormats.nowDateTimeIso())
                _applyMessage.send("已应用 $count 条更正")
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "一键应用更正失败", e)
                _applyMessage.send("一键应用失败")
            } finally {
                _applying.value = false
            }
        }
    }

    /** 恢复被更正停用的记忆 */
    fun restoreMemory(id: Long) {
        viewModelScope.launch {
            try {
                memoryRepo.restoreMemory(id, DateFormats.nowDateTimeIso())
                _applyMessage.send("已恢复该记忆")
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "恢复记忆失败", e)
                _applyMessage.send("恢复失败")
            }
        }
    }

    // ========== 「已更正的记忆」二级页：彻底删除 / 清空（用户显式动作，唯一的物理删记忆入口） ==========

    /** 彻底删除一条已更正记忆（界面上已二次确认） */
    fun deleteSupersededForever(id: Long) {
        viewModelScope.launch {
            try {
                val memory = memoryRepo.getMemoryById(id)
                if (memory == null || memory.status != "superseded") {
                    _applyMessage.send("该记忆已不存在或仍在生效中")
                    return@launch
                }
                memoryRepo.deleteMemory(id)
                _applyMessage.send("已彻底删除 1 条已更正记忆")
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "彻底删除记忆失败", e)
                _applyMessage.send("删除失败")
            }
        }
    }

    /** 清空全部已更正记忆 */
    fun clearAllSuperseded() {
        viewModelScope.launch {
            try {
                val count = _memories.value.count { it.status == "superseded" }
                if (count == 0) {
                    _applyMessage.send("没有已更正的记忆")
                    return@launch
                }
                memoryRepo.deleteMemories(_memories.value.filter { it.status == "superseded" }.map { it.id })
                _applyMessage.send("已清空 $count 条已更正记忆")
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "清空已更正记忆失败", e)
                _applyMessage.send("清空失败")
            }
        }
    }

    // ========== 历史纠错扫描 ==========

    private val _historyScan = MutableStateFlow<List<com.zhiwo.shiguangjian.data.diary.HistoryCorrectionScanner.Hit>?>(null)
    val historyScan: StateFlow<List<com.zhiwo.shiguangjian.data.diary.HistoryCorrectionScanner.Hit>?> = _historyScan

    private val _scanRunning = MutableStateFlow(false)
    val scanRunning: StateFlow<Boolean> = _scanRunning

    /** 扫描基于已更正记忆的历史日记/评价（本地启发式，不调 AI） */
    fun scanHistoryCorrections() {
        viewModelScope.launch {
            try {
                _scanRunning.value = true
                val diaries = app.database.diaryDao().getAllDiaries().first()
                val reviews = app.database.reviewDao().getAllReviews().first()
                val superseded = _memories.value.filter { it.status == "superseded" }
                _historyScan.value = com.zhiwo.shiguangjian.data.diary.HistoryCorrectionScanner.scan(diaries, reviews, superseded)
                if (_historyScan.value!!.isEmpty()) _applyMessage.send("没有发现使用过时记忆的内容")
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "历史纠错扫描失败", e)
                _applyMessage.send("扫描失败：${e.message}")
            } finally {
                _scanRunning.value = false
            }
        }
    }

    fun clearHistoryScan() { _historyScan.value = null }

    /** 对命中的日记一键重新生成（基于当前 active 记忆）；已编辑的被 Regenerator 拒绝 */
    fun regenerateFromHit(hit: com.zhiwo.shiguangjian.data.diary.HistoryCorrectionScanner.Hit) {
        viewModelScope.launch {
            if (hit.type != "diary") {
                _applyMessage.send("评价内容请到评价页查看，暂不支持一键重生成")
                return@launch
            }
            try {
                _scanRunning.value = true
                val diary = app.database.diaryDao().getDiaryByDate(hit.date) ?: run {
                    _applyMessage.send("原日记已不存在"); return@launch
                }
                when (val outcome = app.diaryRegenerator.regenerate(diary)) {
                    is com.zhiwo.shiguangjian.data.diary.DiaryRegenerator.Outcome.Success -> {
                        _applyMessage.send("已重新生成 ${hit.date} 的日记")
                        // 从扫描结果中移除该命中
                        _historyScan.value = _historyScan.value?.filterNot { it.id == hit.id && it.type == "diary" }
                    }
                    is com.zhiwo.shiguangjian.data.diary.DiaryRegenerator.Outcome.Rejected ->
                        _applyMessage.send(outcome.reason)
                    is com.zhiwo.shiguangjian.data.diary.DiaryRegenerator.Outcome.Failed ->
                        _applyMessage.send("重生成失败：${outcome.reason}")
                }
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "命中重生成失败", e)
                _applyMessage.send("重生成失败")
            } finally {
                _scanRunning.value = false
            }
        }
    }

    // ========== AI 记忆分析 ==========

    fun analyzeMemories() {
        viewModelScope.launch {
            refreshAiConfig()
            if (!aiRepo.isConfigured) return@launch
            // 只分析生效中的记忆
            val memList = _memories.value.filter { it.status == "active" }
            if (memList.size < 2) return@launch
            _analyzing.value = true
            _analysisResult.value = null
            try {
                val result = aiRepo.analyzeMemories(memList)
                _analysisResult.value = result
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "分析记忆失败", e)
                _analysisResult.value = null
            } finally {
                _analyzing.value = false
            }
        }
    }

    fun clearAnalysisResult() { _analysisResult.value = null }

    // ========== 应用分析结果：只生成提案，绝不在记忆页直接改库 ==========

    /** 从内存或 DB 查找记忆，DB 兜底解决 Flow 更新延迟导致的查找失败 */
    private suspend fun findMemory(id: Long): MemoryEntity? {
        return _memories.value.find { it.id == id } ?: memoryRepo.getMemoryById(id)
    }

    /**
     * 合并建议 → MERGE_MEMORY 提案。
     * 旧实现是"插新行 + 硬删旧行 + 无事务"，中途异常只提示"已完成 N 条"，会留下半截合并；
     * 现在与整理页共用同一张 organize_ops 表、同一套审核 UI、同一个执行器（事务 + 幂等 + STALE）。
     */
    fun applyMerge(sourceIds: List<Long>, merged: String) = propose {
        val toMerge = sourceIds.distinct().mapNotNull { findMemory(it) }
        if (toMerge.size < 2) {
            _applyMessage.send("源记忆已不存在，无法生成合并提案")
            return@propose null
        }
        val payload = MergeMemoryPayload(
            memoryIds = toMerge.map { it.id }.sorted(),
            mergedContent = merged.trim(),
            contentHashes = toMerge.associate { it.id to OpPayloads.contentHash(it.content) }
        )
        listOf(newOp(OpType.MERGE_MEMORY, OpPayloads.encode(payload)))
    }

    /** 拆分建议 → SPLIT_MEMORY 提案 */
    fun applySplit(sourceId: Long, splits: List<String>) = propose {
        val memory = findMemory(sourceId)
        if (memory == null) {
            _applyMessage.send("源记忆已不存在，无法生成拆分提案")
            return@propose null
        }
        val parts = splits.map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) {
            _applyMessage.send("拆分内容不足 2 条")
            return@propose null
        }
        val payload = SplitMemoryPayload(
            memoryId = memory.id,
            parts = parts,
            contentHash = OpPayloads.contentHash(memory.content)
        )
        listOf(newOp(OpType.SPLIT_MEMORY, OpPayloads.encode(payload)))
    }

    /** 演化建议 → EVOLVE_MEMORY 提案（执行时旧记忆置 superseded，不物理删） */
    fun applyEvolve(sourceId: Long, newContent: String) = propose {
        val memory = findMemory(sourceId)
        if (memory == null) {
            _applyMessage.send("源记忆已不存在，无法生成演化提案")
            return@propose null
        }
        val payload = EvolveMemoryPayload(
            memoryId = memory.id,
            oldText = memory.content,
            newText = newContent.trim()
        )
        listOf(newOp(OpType.EVOLVE_MEMORY, OpPayloads.encode(payload)))
    }

    /** 一键生成全部分析建议的提案（仍然要用户到整理页确认执行） */
    fun applyAll() = propose {
        val result = _analysisResult.value ?: return@propose null
        val ops = mutableListOf<OrganizeOpEntity>()
        for (merge in result.merge) {
            val toMerge = merge.sourceIds.distinct().mapNotNull { findMemory(it) }
            if (toMerge.size < 2) continue
            ops += newOp(OpType.MERGE_MEMORY, OpPayloads.encode(MergeMemoryPayload(
                memoryIds = toMerge.map { it.id }.sorted(),
                mergedContent = merge.merged.trim(),
                contentHashes = toMerge.associate { it.id to OpPayloads.contentHash(it.content) }
            )))
        }
        for (split in result.split) {
            val memory = findMemory(split.sourceId) ?: continue
            val parts = split.splits.map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            ops += newOp(OpType.SPLIT_MEMORY, OpPayloads.encode(SplitMemoryPayload(
                memoryId = memory.id, parts = parts, contentHash = OpPayloads.contentHash(memory.content)
            )))
        }
        for (evolve in result.evolve) {
            val memory = findMemory(evolve.sourceId) ?: continue
            ops += newOp(OpType.EVOLVE_MEMORY, OpPayloads.encode(EvolveMemoryPayload(
                memoryId = memory.id, oldText = memory.content, newText = evolve.newContent.trim()
            )))
        }
        if (ops.isEmpty()) {
            _applyMessage.send("相关记忆已不存在，未生成提案")
            return@propose null
        }
        ops
    }

    /** 提案生成的统一外壳：单批写库 + 失败只报不猜，绝不做半截硬删 */
    private fun propose(build: suspend () -> List<OrganizeOpEntity>?) {
        if (_applying.value) return
        viewModelScope.launch {
            try {
                _applying.value = true
                val ops = build() ?: return@launch
                opDao.insertOps(ops)
                _analysisResult.value = null
                _applyMessage.send("已生成 ${ops.size} 条整理提案，请到「整理」页审核后执行（本页面不再直接改记忆）")
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "生成提案失败", e)
                _applyMessage.send("生成提案失败：${e.message ?: "未知错误"}（未修改任何数据）")
            } finally {
                _applying.value = false
            }
        }
    }

    private var proposalBatchId: String? = null

    private fun newOp(type: String, payloadJson: String): OrganizeOpEntity {
        val now = System.currentTimeMillis()
        val batch = proposalBatchId ?: UUID.randomUUID().toString().also { proposalBatchId = it }
        return OrganizeOpEntity(
            id = UUID.randomUUID().toString(),
            batchId = batch,
            type = type,
            status = OpStatus.PENDING,
            payloadJson = payloadJson,
            previewJson = payloadJson,
            checked = true,
            createdAt = now,
            updatedAt = now
        )
    }
}
