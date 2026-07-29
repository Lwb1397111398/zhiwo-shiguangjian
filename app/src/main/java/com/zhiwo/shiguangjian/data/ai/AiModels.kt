package com.zhiwo.shiguangjian.data.ai

// ========== 数据模型 ==========

data class ContentAnalysis(
    val title: String,
    val category: String,
    val keyInfo: List<String>,
    val tags: List<String>,
    val tasks: List<TaskInfo>,
    val summary: String
)

data class TaskInfo(
    val content: String,
    val dueDate: String,
    val taskType: String
)

data class ConsolidateItem(
    val title: String,
    val date: String,
    val summary: String = "",
    val text: String = ""
)

data class ClassifyResult(
    val memories: List<String>,
    val cleanable: List<String>
)

data class SmartCleanResult(
    val shouldClean: List<String>,
    val shouldKeep: List<String>
)

data class MemoryEvolveItem(
    val old: String,
    val new: String
)

data class MergeItem(
    val sourceIds: List<Long>,
    val merged: String,
    val reason: String
)

data class SplitItem(
    val sourceId: Long,
    val splits: List<String>,
    val reason: String
)

data class EvolveItem(
    val sourceId: Long,
    val newContent: String,
    val reason: String
)

data class AnalyzeResult(
    val merge: List<MergeItem>,
    val split: List<SplitItem>,
    val evolve: List<EvolveItem>
)

data class RecordAnalysisResult(
    val merge: List<RecordMergeItem> = emptyList(),
    val split: List<RecordSplitItem> = emptyList(),
    val goalToTodos: List<RecordGoalToTodos> = emptyList(),
    val todosToGoal: List<RecordTodosToGoal> = emptyList(),
    val toMemory: List<RecordToMemory> = emptyList()
)

data class RecordMergeItem(
    val sourceIds: List<Long>,
    val mergedTitle: String,
    val mergedCategory: String,
    val reason: String
)

data class RecordSplitItem(
    val sourceId: Long,
    val splits: List<SplitEntry>,
    val reason: String
)

data class SplitEntry(
    val title: String,
    val category: String
)

data class RecordGoalToTodos(
    val sourceId: Long,
    val todos: List<String>,
    val reason: String
)

data class RecordTodosToGoal(
    val sourceIds: List<Long>,
    val goalTitle: String,
    val reason: String
)

data class RecordToMemory(
    val sourceId: Long,
    val memoryContent: String,
    val reason: String
)

data class DiaryResult(
    val content: String,
    val mood: String
)

// ========== 分类映射 ==========

val CATEGORY_MAP = mapOf(
    "待办事项" to "todo",
    "目标设定" to "goal",
    "想法灵感" to "idea",
    "情绪记录" to "emotion",
    "问题思考" to "question",
    "学习笔记" to "study",
    "其他" to "other",
    "已完成" to "completed"
)

private val CATEGORY_FUZZY_MAP = mapOf(
    // todo 别名（排除已在 CATEGORY_MAP 中的"待办事项"）
    "待办" to "todo",
    "任务" to "todo",
    "todo" to "todo",
    "task" to "todo",
    "待办任务" to "todo",
    "日常任务" to "todo",
    "每日任务" to "todo",
    // goal 别名（排除"目标设定"）
    "目标" to "goal",
    "goal" to "goal",
    "长期目标" to "goal",
    // idea 别名（排除"想法灵感"）
    "想法" to "idea",
    "灵感" to "idea",
    "idea" to "idea",
    // emotion 别名（排除"情绪记录"）
    "情绪" to "emotion",
    "emotion" to "emotion",
    "心情" to "emotion",
    // question 别名（排除"问题思考"）
    "问题" to "question",
    "思考" to "question",
    "question" to "question",
    // study 别名（排除"学习笔记"）
    "学习" to "study",
    "笔记" to "study",
    "study" to "study",
    // other 别名（排除"其他"）
    "other" to "other",
    "未分类" to "other",
    // completed 别名（排除"已完成"）
    "完成" to "completed",
    "completed" to "completed"
)

/**
 * 模糊匹配分类：先精确匹配 CATEGORY_MAP，再模糊匹配 CATEGORY_FUZZY_MAP，最后 fallback 到 "other"
 */
fun resolveCategory(raw: String?): String {
    if (raw.isNullOrBlank()) return "other"
    val trimmed = raw.trim()
    val normalized = trimmed.lowercase()
    CATEGORY_MAP[trimmed]?.let { return it }
    CATEGORY_FUZZY_MAP[normalized]?.let { return it }
    return when {
        normalized.contains("待办") || normalized.contains("任务") || normalized.contains("todo") -> "todo"
        normalized.contains("目标") || normalized.contains("goal") -> "goal"
        normalized.contains("想法") || normalized.contains("灵感") || normalized.contains("idea") -> "idea"
        normalized.contains("情绪") || normalized.contains("心情") || normalized.contains("emotion") -> "emotion"
        normalized.contains("问题") || normalized.contains("思考") || normalized.contains("question") -> "question"
        normalized.contains("学习") || normalized.contains("笔记") || normalized.contains("study") -> "study"
        else -> "other"
    }
}
