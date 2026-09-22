package com.zhiwo.shiguangjian.data.memory

import com.zhiwo.shiguangjian.data.organizeops.OpStatus

/**
 * 整理提案的依赖级联（纯函数，零 Android 依赖）。
 *
 * 背景：忽略一条「存入记忆/演化」提案时，只改自己状态、不清下游「清理源数据」提案的 dependsOn，
 * 于是下游永远 BLOCKED，被整理过的东西就一直残留在待办里。这里给出两套闭包：
 * - [cascadeCancel]：忽略某条时，哪些下游要一起被取消（依赖的全部父都被取消才级联，防误删）
 * - [restoreSet]：撤销忽略时，哪些被级联取消的下游要恢复 PENDING
 */
data class CascadeOp(
    val id: String,
    val dependsOn: List<String> = emptyList(),
    val status: String = OpStatus.PENDING
)

object DependencyCascade {

    /** 视为"已取消"的状态：这些父不会再 APPLIED，下游因此永远阻塞 */
    val CANCELLED_STATUSES = setOf(OpStatus.DISMISSED)

    /**
     * 返回因 [rootId] 被取消而应一起取消的下游 op id（不含 root 自身）。
     *
     * 规则：一个 op 有 ≥1 个已知父依赖，且**全部**父都处于取消集合内 → 它也被取消；迭代到不动点。
     * 集合只增不减，因此依赖成环（A→B→A）也必然收敛，不会死循环。
     * 引用了图中不存在的父 id 时保守处理：视为未取消，不级联。
     */
    fun cascadeCancel(rootId: String, ops: List<CascadeOp>, cancelled: Set<String> = CANCELLED_STATUSES): List<String> {
        val byId = ops.associateBy { it.id }
        if (rootId !in byId) return emptyList()
        val isCancelled = HashMap<String, Boolean>()
        ops.forEach { isCancelled[it.id] = it.status in cancelled }
        isCancelled[rootId] = true
        var changed = true
        while (changed) {
            changed = false
            for (op in ops) {
                if (isCancelled[op.id] == true) continue
                val parents = op.dependsOn.filter { it in byId }
                if (parents.isEmpty()) continue                     // 无依赖 → 永不级联
                if (op.dependsOn.size != parents.size) continue      // 父不存在，保守不级联
                if (parents.all { isCancelled[it] == true }) {
                    isCancelled[op.id] = true
                    changed = true
                }
            }
        }
        // 只返回"因本次操作而新被取消"的条目；本来就是 DISMISSED 的不算（否则调用方会把它当新取消的
        // 去恢复/计数，用户撤销忽略时会把无关条目一起放回来）
        return ops.filter { it.id != rootId && isCancelled[it.id] == true && it.status !in cancelled }.map { it.id }
    }

    /**
     * 撤销忽略：返回 root 的传递下游中当前仍是"已取消"态的 op id（即当初被级联取消、现在该恢复的那批）。
     * 沿 children 边走，带 visited 保护，成环不死循环。
     */
    fun restoreSet(rootId: String, ops: List<CascadeOp>, cancelled: Set<String> = CANCELLED_STATUSES): List<String> {
        val childrenOf = HashMap<String, MutableList<String>>()
        ops.forEach { op ->
            op.dependsOn.forEach { parent -> childrenOf.getOrPut(parent) { mutableListOf() }.add(op.id) }
        }
        val out = mutableListOf<String>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<String>()
        childrenOf[rootId]?.let { queue.addAll(it) }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!visited.add(id)) continue
            val op = ops.find { it.id == id }
            if (op != null && op.status in cancelled) out += id
            childrenOf[id]?.let { queue.addAll(it) }
        }
        return out
    }

    /** root 的直接下游（含未取消的），供 UI 提示「同时取消 N 条依赖它的清理」 */
    fun directDependents(rootId: String, ops: List<CascadeOp>): List<String> =
        ops.filter { it.id != rootId && it.dependsOn.contains(rootId) }.map { it.id }

    /** root 的全部传递下游 id（不管状态），用于把受影响的清理项标出阻塞原因 */
    fun allDependents(rootId: String, ops: List<CascadeOp>): List<String> {
        val childrenOf = HashMap<String, MutableList<String>>()
        ops.forEach { op ->
            op.dependsOn.forEach { parent -> childrenOf.getOrPut(parent) { mutableListOf() }.add(op.id) }
        }
        val out = mutableListOf<String>()
        val visited = hashSetOf(rootId)
        val queue = ArrayDeque<String>()
        childrenOf[rootId]?.let { queue.addAll(it) }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!visited.add(id)) continue
            out += id
            childrenOf[id]?.let { queue.addAll(it) }
        }
        return out
    }
}
