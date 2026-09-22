package com.zhiwo.shiguangjian.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryReconciliationTest {

    // ========== parseSuggestions ==========

    @Test
    fun `parse plain json array`() {
        val raw = """[{"memoryId": 1, "relation": "conflict", "action": "supersede", "newText": "法考未报名已放弃", "reason": "用户明确放弃", "confidence": "high"}]"""
        val result = MemoryReconciliation.parseSuggestions(raw)
        assertEquals(1, result.size)
        assertEquals(1L, result[0].memoryId)
        assertEquals("supersede", result[0].action)
        assertEquals("法考未报名已放弃", result[0].newText)
        assertEquals("high", result[0].confidence)
    }

    @Test
    fun `parse markdown fenced json`() {
        val raw = """
            ```json
            [{"memoryId": 5, "relation": "update", "action": "revise", "newText": "会开车", "confidence": "medium"}]
            ```
        """.trimIndent()
        val result = MemoryReconciliation.parseSuggestions(raw)
        assertEquals(1, result.size)
        assertEquals("revise", result[0].action)
        assertEquals("medium", result[0].confidence)
    }

    @Test
    fun `parse skips broken entries and continues`() {
        val raw = """[
            {"memoryId": "not-a-number", "action": "supersede"},
            {"memoryId": 7, "action": "supersede", "confidence": "高"},
            {"relation": "conflict"}
        ]"""
        val result = MemoryReconciliation.parseSuggestions(raw)
        assertEquals(1, result.size)
        assertEquals(7L, result[0].memoryId)
        // 中文置信度归一化
        assertEquals("high", result[0].confidence)
    }

    @Test
    fun `parse empty array and garbage return empty`() {
        assertTrue(MemoryReconciliation.parseSuggestions("[]").isEmpty())
        assertTrue(MemoryReconciliation.parseSuggestions("not json at all").isEmpty())
    }

    @Test
    fun `parse accepts memory_id alias`() {
        val raw = """[{"memory_id": 9, "action": "revise", "newText": "x"}]"""
        val result = MemoryReconciliation.parseSuggestions(raw)
        assertEquals(1, result.size)
        assertEquals(9L, result[0].memoryId)
        // 未给 confidence 默认 low
        assertEquals("low", result[0].confidence)
    }

    // ========== buildPlan ==========

    @Test
    fun `law_exam scenario - high confidence supersede auto applies`() {
        val suggestions = listOf(
            ReconcileSuggestion(
                memoryId = 12,
                relation = "conflict",
                action = "supersede",
                newText = "法考未报名已放弃",
                reason = "用户忘记报名，放弃今年法考",
                confidence = "high"
            )
        )
        val plan = MemoryReconciliation.buildPlan(suggestions, validMemoryIds = setOf(12L))
        assertEquals(1, plan.autoApplies.size)
        assertEquals(0, plan.pending.size)
        assertEquals("法考未报名已放弃", plan.autoApplies[0].newText)
    }

    @Test
    fun `medium confidence goes to pending`() {
        val suggestions = listOf(
            ReconcileSuggestion(memoryId = 3, relation = "update", action = "revise", newText = "新表述", confidence = "medium")
        )
        val plan = MemoryReconciliation.buildPlan(suggestions, setOf(3L))
        assertEquals(0, plan.autoApplies.size)
        assertEquals(1, plan.pending.size)
    }

    @Test
    fun `invalid memory id and keep action are dropped`() {
        val suggestions = listOf(
            ReconcileSuggestion(memoryId = 99, action = "supersede", confidence = "high"),   // id 不存在
            ReconcileSuggestion(memoryId = 1, action = "keep", confidence = "high"),          // keep
            ReconcileSuggestion(memoryId = 2, relation = "support", action = "none", confidence = "high")
        )
        val plan = MemoryReconciliation.buildPlan(suggestions, setOf(1L, 2L))
        assertTrue(plan.autoApplies.isEmpty())
        assertTrue(plan.pending.isEmpty())
    }

    @Test
    fun `revise without newText degrades to supersede`() {
        val suggestions = listOf(
            ReconcileSuggestion(memoryId = 4, action = "revise", newText = "  ", confidence = "high")
        )
        val plan = MemoryReconciliation.buildPlan(suggestions, setOf(4L))
        assertEquals(1, plan.autoApplies.size)
        assertEquals("supersede", plan.autoApplies[0].action)
    }

    @Test
    fun `duplicate suggestions for same memory keep first`() {
        val suggestions = listOf(
            ReconcileSuggestion(memoryId = 6, action = "supersede", newText = "第一版", confidence = "high"),
            ReconcileSuggestion(memoryId = 6, action = "revise", newText = "第二版", confidence = "high")
        )
        val plan = MemoryReconciliation.buildPlan(suggestions, setOf(6L))
        assertEquals(1, plan.autoApplies.size)
        assertEquals("第一版", plan.autoApplies[0].newText)
    }

    // ========== mergePending ==========

    @Test
    fun `mergePending replaces same memory and keeps order`() {
        val existing = listOf(
            ReconcileSuggestion(memoryId = 1, action = "supersede", newText = "旧建议1"),
            ReconcileSuggestion(memoryId = 2, action = "revise", newText = "旧建议2")
        )
        val incoming = listOf(
            ReconcileSuggestion(memoryId = 2, action = "supersede", newText = "新建议2"),
            ReconcileSuggestion(memoryId = 3, action = "revise", newText = "新建议3")
        )
        val merged = MemoryReconciliation.mergePending(existing, incoming)
        assertEquals(3, merged.size)
        assertEquals("旧建议1", merged[0].newText)
        assertEquals("新建议2", merged[1].newText)
        assertEquals("新建议3", merged[2].newText)
    }

    @Test
    fun `mergePending drops oldest beyond limit`() {
        val existing = (1..20).map {
            ReconcileSuggestion(memoryId = it.toLong(), action = "supersede", newText = "s$it")
        }
        val incoming = listOf(ReconcileSuggestion(memoryId = 100, action = "supersede", newText = "new"))
        val merged = MemoryReconciliation.mergePending(existing, incoming)
        assertEquals(MemoryReconciliation.MAX_PENDING, merged.size)
        // 最旧的 1 被挤出
        assertTrue(merged.none { it.memoryId == 1L })
        assertTrue(merged.any { it.memoryId == 100L })
    }

    // ========== MFR：被挤掉/被清理的建议必须能被追溯（否则记忆永久卡 in under_review） ==========

    @Test
    fun `MFR01 mergePendingWithDropped reports exactly the overflowed suggestions`() {
        val existing = (1..MemoryReconciliation.MAX_PENDING).map {
            ReconcileSuggestion(memoryId = it.toLong(), action = "supersede", newText = "s$it")
        }
        val incoming = listOf(
            ReconcileSuggestion(memoryId = 101, action = "supersede", newText = "a"),
            ReconcileSuggestion(memoryId = 102, action = "revise", newText = "b")
        )
        val result = MemoryReconciliation.mergePendingWithDropped(existing, incoming)
        assertEquals(MemoryReconciliation.MAX_PENDING, result.merged.size)
        // 挤出去的就是最旧的两条，且顺序保持先进先出
        assertEquals(listOf(1L, 2L), result.dropped.map { it.memoryId })
        assertTrue(result.merged.none { it.memoryId in listOf(1L, 2L) })
        assertTrue(result.merged.any { it.memoryId == 102L })
    }

    @Test
    fun `MFR02 mergePendingWithDropped without overflow drops nothing`() {
        val result = MemoryReconciliation.mergePendingWithDropped(
            listOf(ReconcileSuggestion(memoryId = 1, action = "supersede")),
            listOf(ReconcileSuggestion(memoryId = 2, action = "revise", newText = "x"))
        )
        assertEquals(listOf(1L, 2L), result.merged.map { it.memoryId })
        assertTrue(result.dropped.isEmpty())
    }

    @Test
    fun `MFR03 orphanUnderReviewIds 找出队列里已无对应建议的记忆`() {
        val pending = listOf(
            ReconcileSuggestion(memoryId = 7, action = "supersede"),
            ReconcileSuggestion(memoryId = 8, action = "revise", newText = "y")
        )
        val orphans = MemoryReconciliation.orphanUnderReviewIds(listOf(7L, 8L, 9L, 10L), pending)
        assertEquals(listOf(9L, 10L), orphans)
        assertTrue(MemoryReconciliation.orphanUnderReviewIds(emptyList(), pending).isEmpty())
        assertTrue(MemoryReconciliation.orphanUnderReviewIds(listOf(7L, 8L), pending).isEmpty())
    }
}
