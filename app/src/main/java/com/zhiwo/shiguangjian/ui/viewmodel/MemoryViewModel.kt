package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.ai.AnalyzeResult
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import com.zhiwo.shiguangjian.data.repository.MemoryRepository
import com.zhiwo.shiguangjian.data.repository.SecureSettingsRepository
import com.zhiwo.shiguangjian.data.repository.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhiwoApplication
    private val memoryRepo = MemoryRepository(app.database.memoryDao())
    private val settingsRepo = SettingsRepository(app.database.settingDao())
    private val secureSettingsRepo = SecureSettingsRepository(app)
    private val aiRepo get() = app.aiRepo

    private val _memories = MutableStateFlow<List<MemoryEntity>>(emptyList())
    val memories: StateFlow<List<MemoryEntity>> = _memories

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

    // ========== AI 记忆分析 ==========

    fun analyzeMemories() {
        viewModelScope.launch {
            refreshAiConfig()
            if (!aiRepo.isConfigured) return@launch
            val memList = _memories.value
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

    // ========== 应用分析结果（逐条应用，保留其余建议） ==========

    /** 从内存或 DB 查找记忆，DB 兜底解决 Flow 更新延迟导致的查找失败 */
    private suspend fun findMemory(id: Long): MemoryEntity? {
        return _memories.value.find { it.id == id } ?: memoryRepo.getMemoryById(id)
    }

    fun applyMerge(sourceIds: List<Long>, merged: String) {
        if (_applying.value) return
        viewModelScope.launch {
            try {
                _applying.value = true
                val toMerge = sourceIds.mapNotNull { findMemory(it) }
                if (toMerge.size < 2) {
                    _applyMessage.send("源记忆已不存在，无法合并")
                    return@launch
                }
                val earliest = toMerge.minOf { it.createdAt }
                val now = DateFormats.nowDateTimeIso()
                memoryRepo.insertMemory(
                    MemoryEntity(content = merged, source = "organize", createdAt = earliest, updatedAt = now)
                )
                for (m in toMerge) { memoryRepo.deleteMemory(m.id) }
                memoryRepo.enforceMemoryLimit(100)
                val current = _analysisResult.value
                _analysisResult.value = current?.copy(
                    merge = current.merge.filter { it.sourceIds != sourceIds }
                )
                _applyMessage.send("已合并 ${toMerge.size} 条记忆")
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "合并记忆失败", e)
                _applyMessage.send("合并失败")
            } finally {
                _applying.value = false
            }
        }
    }

    fun applySplit(sourceId: Long, splits: List<String>) {
        if (_applying.value) return
        viewModelScope.launch {
            try {
                _applying.value = true
                val memory = findMemory(sourceId)
                if (memory == null) {
                    _applyMessage.send("源记忆已不存在，无法拆分")
                    return@launch
                }
                val now = DateFormats.nowDateTimeIso()
                for (content in splits) {
                    memoryRepo.insertMemory(
                        MemoryEntity(content = content, source = "organize", createdAt = memory.createdAt, updatedAt = now)
                    )
                }
                memoryRepo.deleteMemory(memory.id)
                memoryRepo.enforceMemoryLimit(100)
                val current = _analysisResult.value
                _analysisResult.value = current?.copy(
                    split = current.split.filter { it.sourceId != sourceId }
                )
                _applyMessage.send("已拆分为 ${splits.size} 条记忆")
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "拆分记忆失败", e)
                _applyMessage.send("拆分失败")
            } finally {
                _applying.value = false
            }
        }
    }

    fun applyEvolve(sourceId: Long, newContent: String) {
        if (_applying.value) return
        viewModelScope.launch {
            try {
                _applying.value = true
                val memory = findMemory(sourceId)
                if (memory == null) {
                    _applyMessage.send("源记忆已不存在，无法演化")
                    return@launch
                }
                val now = DateFormats.nowDateTimeIso()
                memoryRepo.insertMemory(
                    MemoryEntity(content = newContent, source = "organize", createdAt = memory.createdAt, updatedAt = now)
                )
                memoryRepo.deleteMemory(memory.id)
                memoryRepo.enforceMemoryLimit(100)
                val current = _analysisResult.value
                _analysisResult.value = current?.copy(
                    evolve = current.evolve.filter { it.sourceId != sourceId }
                )
                _applyMessage.send("已演化记忆")
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "演化记忆失败", e)
                _applyMessage.send("演化失败")
            } finally {
                _applying.value = false
            }
        }
    }

    /** 一键全部应用：按 merge → split → evolve 顺序逐条执行 */
    fun applyAll() {
        if (_applying.value) return
        viewModelScope.launch {
            _applying.value = true
            var count = 0
            try {
                val result = _analysisResult.value ?: return@launch

                for (merge in result.merge) {
                    val toMerge = merge.sourceIds.mapNotNull { findMemory(it) }
                    if (toMerge.size < 2) continue
                    val earliest = toMerge.minOf { it.createdAt }
                    val now = DateFormats.nowDateTimeIso()
                    memoryRepo.insertMemory(
                        MemoryEntity(content = merge.merged, source = "organize", createdAt = earliest, updatedAt = now)
                    )
                    for (m in toMerge) { memoryRepo.deleteMemory(m.id) }
                    count++
                }

                for (split in result.split) {
                    val memory = findMemory(split.sourceId) ?: continue
                    val now = DateFormats.nowDateTimeIso()
                    for (content in split.splits) {
                        memoryRepo.insertMemory(
                            MemoryEntity(content = content, source = "organize", createdAt = memory.createdAt, updatedAt = now)
                        )
                    }
                    memoryRepo.deleteMemory(memory.id)
                    count++
                }

                for (evolve in result.evolve) {
                    val memory = findMemory(evolve.sourceId) ?: continue
                    val now = DateFormats.nowDateTimeIso()
                    memoryRepo.insertMemory(
                        MemoryEntity(content = evolve.newContent, source = "organize", createdAt = memory.createdAt, updatedAt = now)
                    )
                    memoryRepo.deleteMemory(memory.id)
                    count++
                }

                memoryRepo.enforceMemoryLimit(100)
                _analysisResult.value = null
                _applyMessage.send("已全部应用 $count 条建议")
            } catch (e: Throwable) {
                android.util.Log.e("MemoryVM", "一键应用失败", e)
                _applyMessage.send("操作中断，已完成 $count 条")
            } finally {
                _applying.value = false
            }
        }
    }
}
