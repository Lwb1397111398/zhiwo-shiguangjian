package com.zhiwo.shiguangjian.data.organizeops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpValidatorTest {

    private fun snapshot(
        memories: List<MemoryRef> = emptyList(),
        records: Map<Long, SourceState> = emptyMap(),
        reviews: Map<Long, SourceState> = emptyMap()
    ) = OpSnapshot(memories, records, reviews)

    private fun src(content: String, updatedAt: String) = SourceState(OpPayloads.contentHash(content), updatedAt)

    // ========== SAVE_MEMORY ==========

    @Test
    fun `save memory executes when no duplicate active memory`() {
        val payload = OpPayloads.encode(SaveMemoryPayload(content = "会开车", sourceDate = "2026-01-01"))
        val decision = OpValidator.decide(OpType.SAVE_MEMORY, payload, snapshot())
        assertTrue(decision is Decision.Execute)
    }

    @Test
    fun `save memory is idempotent when same active text exists`() {
        val payload = OpPayloads.encode(SaveMemoryPayload(content = "会开车"))
        val snap = snapshot(memories = listOf(MemoryRef(1, "会开车", "active")))
        val decision = OpValidator.decide(OpType.SAVE_MEMORY, payload, snap)
        assertTrue(decision is Decision.AlreadyApplied)
    }

    @Test
    fun `save memory executes when same text only exists as superseded`() {
        val payload = OpPayloads.encode(SaveMemoryPayload(content = "会开车"))
        val snap = snapshot(memories = listOf(MemoryRef(1, "会开车", "superseded")))
        assertTrue(OpValidator.decide(OpType.SAVE_MEMORY, payload, snap) is Decision.Execute)
    }

    @Test
    fun `save memory with blank content is invalid`() {
        val payload = OpPayloads.encode(SaveMemoryPayload(content = "  "))
        assertTrue(OpValidator.decide(OpType.SAVE_MEMORY, payload, snapshot()) is Decision.Invalid)
    }

    // ========== DELETE_SOURCE ==========

    @Test
    fun `delete source executes when sources unchanged`() {
        val payload = OpPayloads.encode(DeleteSourcePayload(
            recordIds = listOf(1, 2),
            recordVersions = mapOf(1L to "v1", 2L to "v2"),
            recordHashes = mapOf(1L to OpPayloads.contentHash("标题A", "内容A"), 2L to OpPayloads.contentHash("标题B", "内容B"))
        ))
        val snap = snapshot(
            records = mapOf(
                1L to src("标题A|内容A", "v1"),  // contentHash(标题A, 内容A) 即 md5("标题A|内容A")
                2L to src("标题B|内容B", "v2")
            )
        )
        assertTrue(OpValidator.decide(OpType.DELETE_SOURCE, payload, snap) is Decision.Execute)
    }

    @Test
    fun `delete source already applied when all sources gone`() {
        val payload = OpPayloads.encode(DeleteSourcePayload(
            recordIds = listOf(1, 2), recordVersions = mapOf(1L to "v1", 2L to "v2")
        ))
        val snap = snapshot(records = emptyMap())
        assertTrue(OpValidator.decide(OpType.DELETE_SOURCE, payload, snap) is Decision.AlreadyApplied)
    }

    @Test
    fun `delete source is stale when record content changed`() {
        val originalHash = OpPayloads.contentHash("标题A", "内容A")
        val payload = OpPayloads.encode(DeleteSourcePayload(
            recordIds = listOf(1), recordHashes = mapOf(1L to originalHash)
        ))
        val snap = snapshot(records = mapOf(1L to src("标题A|内容被改过", "v1")))
        assertTrue(OpValidator.decide(OpType.DELETE_SOURCE, payload, snap) is Decision.Stale)
    }

    @Test
    fun `delete source not stale when only updatedAt changed but hash matches`() {
        // 用户加标签等操作会刷新 updatedAt，但内容未变 → 不应误报 STALE（哈希优先的意义）
        val hash = OpPayloads.contentHash("标题A", "内容A")
        val payload = OpPayloads.encode(DeleteSourcePayload(
            recordIds = listOf(1),
            recordVersions = mapOf(1L to "old-version"),
            recordHashes = mapOf(1L to hash)
        ))
        val snap = snapshot(records = mapOf(1L to SourceState(hash, "new-version-after-tag-added")))
        assertTrue(OpValidator.decide(OpType.DELETE_SOURCE, payload, snap) is Decision.Execute)
    }

    @Test
    fun `delete source falls back to version check when hash missing`() {
        val payload = OpPayloads.encode(DeleteSourcePayload(
            recordIds = listOf(1), recordVersions = mapOf(1L to "v1")  // 无哈希（旧数据）
        ))
        val snap = snapshot(records = mapOf(1L to src("任何内容", "v2")))
        assertTrue(OpValidator.decide(OpType.DELETE_SOURCE, payload, snap) is Decision.Stale)
    }

    @Test
    fun `delete source executes when only some sources remain and unchanged`() {
        val payload = OpPayloads.encode(DeleteSourcePayload(
            recordIds = listOf(1, 2), recordVersions = mapOf(1L to "v1", 2L to "v2")
        ))
        val snap = snapshot(records = mapOf(2L to src("内容B", "v2")))  // 1 已被别处删除
        assertTrue(OpValidator.decide(OpType.DELETE_SOURCE, payload, snap) is Decision.Execute)
    }

    // ========== EVOLVE_MEMORY ==========

    @Test
    fun `evolve executes when memory active and text matches`() {
        val payload = OpPayloads.encode(EvolveMemoryPayload(memoryId = 5, oldText = "2024拿到驾照", newText = "会开车"))
        val snap = snapshot(memories = listOf(MemoryRef(5, "2024拿到驾照", "active")))
        assertTrue(OpValidator.decide(OpType.EVOLVE_MEMORY, payload, snap) is Decision.Execute)
    }

    @Test
    fun `evolve already applied when new text exists and old superseded`() {
        val payload = OpPayloads.encode(EvolveMemoryPayload(memoryId = 5, oldText = "2024拿到驾照", newText = "会开车"))
        val snap = snapshot(memories = listOf(
            MemoryRef(6, "会开车", "active"),
            MemoryRef(5, "2024拿到驾照", "superseded")
        ))
        assertTrue(OpValidator.decide(OpType.EVOLVE_MEMORY, payload, snap) is Decision.AlreadyApplied)
    }

    @Test
    fun `evolve stale when memory content changed since proposal`() {
        val payload = OpPayloads.encode(EvolveMemoryPayload(memoryId = 5, oldText = "旧描述", newText = "新描述"))
        val snap = snapshot(memories = listOf(MemoryRef(5, "被用户改过的描述", "active")))
        assertTrue(OpValidator.decide(OpType.EVOLVE_MEMORY, payload, snap) is Decision.Stale)
    }

    @Test
    fun `evolve stale when memory deleted and new text absent`() {
        val payload = OpPayloads.encode(EvolveMemoryPayload(memoryId = 5, oldText = "旧描述", newText = "新描述"))
        assertTrue(OpValidator.decide(OpType.EVOLVE_MEMORY, payload, snapshot()) is Decision.Stale)
    }

    @Test
    fun `unknown type is invalid`() {
        assertTrue(OpValidator.decide("WHAT_EVER", "{}", snapshot()) is Decision.Invalid)
    }

    // ========== 批次编排 ==========

    @Test
    fun `plan batch keeps only checked pending and sorts by type priority`() {
        val ops = listOf(
            BatchOp("a", OpType.DELETE_SOURCE, OpStatus.PENDING, true, "{}"),
            BatchOp("b", OpType.SAVE_MEMORY, OpStatus.PENDING, true, "{}"),
            BatchOp("c", OpType.EVOLVE_MEMORY, OpStatus.PENDING, true, "{}"),
            BatchOp("d", OpType.SAVE_MEMORY, OpStatus.PENDING, false, "{}"),   // 未勾选
            BatchOp("e", OpType.SAVE_MEMORY, OpStatus.APPLIED, true, "{}"),    // 已执行
            BatchOp("f", OpType.DELETE_SOURCE, OpStatus.DISMISSED, true, "{}") // 已忽略
        )
        val plan = OpValidator.planBatch(ops)
        // 只剩 a/b/c，顺序：EVOLVE(c) → SAVE(b) → DELETE(a)
        assertEquals(listOf("c", "b", "a"), plan.map { it.id })
    }

    @Test
    fun `plan batch includes blocked ops for dependency re-evaluation`() {
        // BLOCKED 的 op 依赖可能已满足（如前置重试成功），执行编排时需重新评估
        val ops = listOf(
            BatchOp("a", OpType.SAVE_MEMORY, OpStatus.PENDING, true, "{}"),
            BatchOp("b", OpType.DELETE_SOURCE, OpStatus.BLOCKED, true, "{}", dependsOn = listOf("a"))
        )
        val plan = OpValidator.planBatch(ops)
        assertEquals(listOf("a", "b"), plan.map { it.id })
    }

    @Test
    fun `plan batch with all applied returns empty`() {
        val ops = listOf(BatchOp("a", OpType.SAVE_MEMORY, OpStatus.APPLIED, true, "{}"))
        assertTrue(OpValidator.planBatch(ops).isEmpty())
    }

    // ========== 依赖检查（防"源删了记忆没存"） ==========

    @Test
    fun `dependency satisfied only when all deps applied`() {
        assertNull(DependencyChecker.evaluate(listOf("x", "y"), mapOf("x" to OpStatus.APPLIED, "y" to OpStatus.APPLIED)))
    }

    @Test
    fun `dependency blocked when a dep failed`() {
        // 场景还原：SAVE_MEMORY 失败 → 依赖它的 DELETE_SOURCE 必须被阻止，否则数据丢失
        val reason = DependencyChecker.evaluate(
            listOf("save-1"),
            mapOf("save-1" to OpStatus.FAILED)
        )
        assertTrue(reason != null)
        assertTrue(reason!!.contains("FAILED"))
    }

    @Test
    fun `dependency blocked when a dep is missing or pending`() {
        assertTrue(DependencyChecker.evaluate(listOf("gone"), emptyMap()) != null)
        assertTrue(DependencyChecker.evaluate(
            listOf("save-1"), mapOf("save-1" to OpStatus.PENDING)
        ) != null)
    }

    // ========== Payload 编解码 roundtrip ==========

    @Test
    fun `payloads roundtrip`() {
        val save = SaveMemoryPayload(content = "会开车", sourceDate = "2026-01-01")
        assertEquals(save, OpPayloads.decodeSaveMemory(OpPayloads.encode(save)))

        val del = DeleteSourcePayload(
            label = "旧记录", recordIds = listOf(1, 2), reviewIds = listOf(3),
            recordVersions = mapOf(1L to "a", 2L to "b"), reviewVersions = mapOf(3L to "c"),
            recordHashes = mapOf(1L to "h1"), reviewHashes = mapOf(3L to "h3")
        )
        assertEquals(del, OpPayloads.decodeDeleteSource(OpPayloads.encode(del)))

        val evo = EvolveMemoryPayload(memoryId = 9, oldText = "旧", newText = "新")
        assertEquals(evo, OpPayloads.decodeEvolveMemory(OpPayloads.encode(evo)))

        assertEquals(listOf("op-1", "op-2"), OpPayloads.decodeDependsOn(OpPayloads.encodeDependsOn(listOf("op-1", "op-2"))))
        assertTrue(OpPayloads.decodeDependsOn(null).isEmpty())
        assertTrue(OpPayloads.decodeDependsOn("broken").isEmpty())
    }

    @Test
    fun `garbage payload decodes to null without throwing`() {
        assertEquals(null, OpPayloads.decodeSaveMemory("not json"))
        assertEquals(null, OpPayloads.decodeDeleteSource("{broken"))
        assertEquals(null, OpPayloads.decodeEvolveMemory(""))
    }

    @Test
    fun `content hash is stable and collision-tested for edit detection`() {
        val h1 = OpPayloads.contentHash("标题", "内容")
        assertEquals(h1, OpPayloads.contentHash("标题", "内容"))
        assertTrue(h1 != OpPayloads.contentHash("标题", "内容2"))
    }

    // ========== MFV：判重口径换成 MemorySimilarity（R3） ==========

    @Test fun MFV01_save_memory_CONTAINS_判重不放行() {
        val payload = OpPayloads.encode(SaveMemoryPayload(content = "我会开车了"))
        val snap = snapshot(memories = listOf(MemoryRef(1, "会开车", "active")))
        assertTrue(OpValidator.decide(OpType.SAVE_MEMORY, payload, snap) is Decision.AlreadyApplied)
    }

    @Test fun MFV02_save_memory_只有标点空格差异也是重复() {
        val payload = OpPayloads.encode(SaveMemoryPayload(content = "我喜欢跑步。"))
        val snap = snapshot(memories = listOf(MemoryRef(1, "我 喜欢 跑步", "active")))
        assertTrue(OpValidator.decide(OpType.SAVE_MEMORY, payload, snap) is Decision.AlreadyApplied)
    }

    @Test fun MFV03_save_memory_SIMILAR_只提示不自动拦() {
        // 语义相近但可能说的是两件事（准备法考 / 通过法考），必须放行给人看
        val payload = OpPayloads.encode(SaveMemoryPayload(content = "我喜欢跑步"))
        val snap = snapshot(memories = listOf(MemoryRef(1, "我喜爱跑步", "active")))
        val decision = OpValidator.decide(OpType.SAVE_MEMORY, payload, snap)
        assertTrue(decision is Decision.Execute)
        assertTrue((decision as Decision.Execute).hint.isNotBlank())
    }

    // ========== MFV：MERGE_MEMORY ==========

    private fun mergeJson(ids: List<Long>, merged: String, hashes: Map<Long, String> = emptyMap()) =
        OpPayloads.encode(MergeMemoryPayload(memoryIds = ids, mergedContent = merged, contentHashes = hashes))

    private fun hashesOf(vararg refs: MemoryRef) = refs.associate { it.id to OpPayloads.contentHash(it.content) }

    @Test fun MFV04_merge_只传一个_id_非法() {
        val a = MemoryRef(1, "会开车", "active")
        val snap = snapshot(memories = listOf(a))
        val decision = OpValidator.decide(OpType.MERGE_MEMORY, mergeJson(listOf(1L), "会开车"), snap)
        assertTrue(decision is Decision.Invalid)
        assertTrue(OpValidator.decide(OpType.MERGE_MEMORY, mergeJson(emptyList(), "会开车"), snap) is Decision.Invalid)
        assertTrue(OpValidator.decide(OpType.MERGE_MEMORY, mergeJson(listOf(1L, 1L), "会开车"), snap) is Decision.Invalid)
        assertTrue(OpValidator.decide(OpType.MERGE_MEMORY, mergeJson(listOf(1L, 2L), "  "), snap) is Decision.Invalid)
    }

    @Test fun MFV05_merge_目标全部生效中且哈希一致才执行() {
        val a = MemoryRef(1, "会开车", "active")
        val b = MemoryRef(2, "2024拿到驾照", "active")
        val snap = snapshot(memories = listOf(a, b))
        val payload = mergeJson(listOf(1L, 2L), "有驾照会开车", hashesOf(a, b))
        assertTrue(OpValidator.decide(OpType.MERGE_MEMORY, payload, snap) is Decision.Execute)
    }

    @Test fun MFV06_merge_目标非_active_判_STALE() {
        val a = MemoryRef(1, "会开车", "active")
        val gone = MemoryRef(2, "2024拿到驾照", "superseded")
        val snap = snapshot(memories = listOf(a, gone))
        val payload = mergeJson(listOf(1L, 2L), "有驾照会开车", hashesOf(a, gone))
        val decision = OpValidator.decide(OpType.MERGE_MEMORY, payload, snap)
        assertTrue("$decision", decision is Decision.Stale)
        // under_review 也不能被整理顺手改掉：用户对它另有决定
        val reviewing = MemoryRef(2, "2024拿到驾照", "under_review")
        val reviewingSnap = snapshot(memories = listOf(a, reviewing))
        assertTrue(
            OpValidator.decide(OpType.MERGE_MEMORY, mergeJson(listOf(1L, 2L), "有驾照会开车", hashesOf(a, reviewing)), reviewingSnap)
                is Decision.Stale
        )
    }

    @Test fun MFV07_merge_内容哈希变了判_STALE() {
        val a = MemoryRef(1, "会开车", "active")
        val b = MemoryRef(2, "2024拿到驾照", "active")
        val snap = snapshot(memories = listOf(a, b))
        // 提案生成后用户把 b 改成了别的话
        val payload = mergeJson(listOf(1L, 2L), "有驾照会开车", mapOf(1L to a.hash, 2L to "deadbeefdeadbeef"))
        assertTrue(OpValidator.decide(OpType.MERGE_MEMORY, payload, snap) is Decision.Stale)
    }

    @Test fun MFV08_merge_已达成目标态判幂等() {
        val merged = MemoryRef(3, "有驾照会开车", "active")
        val old1 = MemoryRef(1, "会开车", "superseded")
        val old2 = MemoryRef(2, "2024拿到驾照", "superseded")
        val snap = snapshot(memories = listOf(merged, old1, old2))
        val payload = mergeJson(listOf(1L, 2L), "有驾照会开车", hashesOf(old1, old2))
        assertTrue(OpValidator.decide(OpType.MERGE_MEMORY, payload, snap) is Decision.AlreadyApplied)
        // 源记忆被删光且结果没入库 → 提案过期
        assertTrue(OpValidator.decide(OpType.MERGE_MEMORY, mergeJson(listOf(9L, 8L), "有驾照会开车"), snap) is Decision.Stale)
    }

    // ========== MFV：SPLIT_MEMORY / DELETE_MEMORY ==========

    @Test fun MFV09_split_少于两条非法_哈希变了_STALE() {
        val a = MemoryRef(1, "准备法考并且买了网课", "active")
        val snap = snapshot(memories = listOf(a))
        val hash = OpPayloads.contentHash(a.content)
        assertTrue(
            OpValidator.decide(OpType.SPLIT_MEMORY, OpPayloads.encode(SplitMemoryPayload(1, listOf("只有一条"), hash)), snap)
                is Decision.Invalid
        )
        assertTrue(
            OpValidator.decide(OpType.SPLIT_MEMORY, OpPayloads.encode(SplitMemoryPayload(1, listOf(" ", " "), hash)), snap)
                is Decision.Invalid
        )
        assertTrue(
            OpValidator.decide(OpType.SPLIT_MEMORY, OpPayloads.encode(SplitMemoryPayload(1, listOf("备考法考", "买了网课"), hash)), snap)
                is Decision.Execute
        )
        assertTrue(
            OpValidator.decide(OpType.SPLIT_MEMORY, OpPayloads.encode(SplitMemoryPayload(1, listOf("备考法考", "买了网课"), "bad")), snap)
                is Decision.Stale
        )
        assertTrue(
            OpValidator.decide(OpType.SPLIT_MEMORY, OpPayloads.encode(SplitMemoryPayload(99, listOf("备考法考", "买了网课"), hash)), snap)
                is Decision.Stale
        )
    }

    @Test fun MFV10_delete_memory_哈希不一致拒绝物理删_不存在算已达成() {
        val a = MemoryRef(1, "过时记忆", "active")
        val snap = snapshot(memories = listOf(a))
        assertTrue(
            OpValidator.decide(OpType.DELETE_MEMORY, OpPayloads.encode(DeleteMemoryPayload(1, a.hash)), snap) is Decision.Execute
        )
        assertTrue(
            OpValidator.decide(OpType.DELETE_MEMORY, OpPayloads.encode(DeleteMemoryPayload(1, "changed")), snap) is Decision.Stale
        )
        assertTrue(
            OpValidator.decide(OpType.DELETE_MEMORY, OpPayloads.encode(DeleteMemoryPayload(777, "")), snapshot())
                is Decision.AlreadyApplied
        )
    }

    // ========== MFV：新 op 必须"两处都登记" ==========

    @Test fun MFV11_未知类型与漏登记类型都能被识别() {
        assertTrue(OpValidator.decide("WHAT_EVER", "{}", snapshot()) is Decision.Invalid)
        // 拼错的新类型名不能被当成"未知"以外的任何东西
        val decision = OpValidator.decide("MERGE_MEMRY", "{}", snapshot()) as Decision.Invalid
        assertTrue(decision.reason.contains("未知操作类型"))
    }

    @Test fun MFV12_执行顺序表与_decide_分支一一对应() {
        val order = OpType.executionOrder()
        assertEquals(
            listOf(
                OpType.EVOLVE_MEMORY, OpType.MERGE_MEMORY, OpType.SPLIT_MEMORY,
                OpType.DELETE_MEMORY, OpType.SAVE_MEMORY, OpType.DELETE_SOURCE
            ),
            order
        )
        // 每个登记在执行顺序表里的类型，decide 都必须认得（不得回"未知操作类型"）
        val wellFormed = mapOf(
            OpType.SAVE_MEMORY to """{"content":"会开车","sourceDate":""}""",
            OpType.DELETE_SOURCE to """{"label":"x","recordIds":[1],"reviewIds":[],"recordVersions":{},"reviewVersions":{},"recordHashes":{},"reviewHashes":{},"forceDelete":false}""",
            OpType.EVOLVE_MEMORY to """{"memoryId":1,"oldText":"旧","newText":"新"}""",
            OpType.MERGE_MEMORY to """{"memoryIds":[1,2],"mergedContent":"新","contentHashes":{}}""",
            OpType.SPLIT_MEMORY to """{"memoryId":1,"parts":["一","二"],"contentHash":""}""",
            OpType.DELETE_MEMORY to """{"memoryId":1,"contentHash":""}"""
        )
        order.forEach { type ->
            val decision = OpValidator.decide(type, wellFormed.getValue(type), snapshot())
            val reason = (decision as? Decision.Invalid)?.reason.orEmpty()
            assertTrue("$type 漏登记：$reason", !reason.contains("未知操作类型"))
            assertTrue("$type 载荷解析失败（@SerializedName 或字段名漂移）", !reason.contains("载荷解析失败"))
        }
    }

    @Test fun MFV13_载荷损坏时_decide_不抛异常() {
        // Gson 对缺失字段会注入 null，纯校验函数必须兜住，不能让执行器崩在解析上
        listOf(OpType.MERGE_MEMORY, OpType.SPLIT_MEMORY, OpType.DELETE_MEMORY, OpType.SAVE_MEMORY).forEach {
            assertTrue("$it", OpValidator.decide(it, "{}", snapshot()) is Decision.Invalid)
        }
    }

    // ========== MFV：清理提案不得空绑（R6 的最后一道） ==========

    @Test fun MFV14_delete_source_没绑定任何源判非法() {
        val payload = OpPayloads.encode(DeleteSourcePayload(label = "只有文字没有 id"))
        val decision = OpValidator.decide(OpType.DELETE_SOURCE, payload, snapshot(records = mapOf(1L to src("a", "v"))))
        assertTrue("$decision", decision is Decision.Invalid)
    }

    // ========== MFV：判重合并规划 & 提案预筛 ==========

    @Test fun MFV15_planDedupeMerges_只合_EXACT_与_CONTAINS() {
        val refs = listOf(
            MemoryRef(1, "会开车", "active"),
            MemoryRef(2, "我会开车了", "active"),
            MemoryRef(3, "我喜爱跑步", "active"),
            MemoryRef(4, "我在准备法考", "active"),
            MemoryRef(5, "我今年通过法考", "superseded")
        )
        val plans = OpValidator.planDedupeMerges(refs)
        assertEquals(1, plans.size)
        assertEquals(listOf(1L, 2L), plans[0].memoryIds)
        assertEquals("我会开车了", plans[0].mergedContent)          // 取信息量最大的那条
        assertEquals(mapOf(1L to OpPayloads.contentHash("会开车"), 2L to OpPayloads.contentHash("我会开车了")), plans[0].contentHashes)
        // SIMILAR（跑步）与 DIFFERENT（法考）都不得自动合并；superseded 不参与
        assertTrue(plans.none { it.memoryIds.contains(3L) || it.memoryIds.contains(4L) || it.memoryIds.contains(5L) })
    }

    @Test fun MFV16_filterSaveProposals_跳过重复但保留疑似重复() {
        val filter = OpValidator.filterSaveProposals(
            contents = listOf("会开车", "我喜欢跑步", "我会开车了", "我喜欢跑步", "新的爱好是烘焙"),
            existingActiveContents = listOf("我会开车了")
        )
        assertEquals(listOf("我喜欢跑步", "新的爱好是烘焙"), filter.keep)
        // 与现有记忆重复的、以及本批内部自己重复的，都不再入第二条
        assertEquals(listOf("会开车", "我会开车了", "我喜欢跑步"), filter.skipped)
        assertTrue(filter.suspicious.isEmpty())

        // 表述相近（SIMILAR）的保留但要点名，让用户自己决定合不合
        val near = OpValidator.filterSaveProposals(listOf("我喜爱跑步"), listOf("我喜欢跑步"))
        assertEquals(listOf("我喜爱跑步"), near.keep)
        assertEquals(listOf("我喜爱跑步"), near.suspicious)
    }

    @Test fun MFV17_planBatch_新类型顺序与_onlyChecked_开关() {
        val ops = listOf(
            BatchOp("d1", OpType.DELETE_SOURCE, OpStatus.PENDING, false, "{}"),
            BatchOp("s1", OpType.SAVE_MEMORY, OpStatus.PENDING, false, "{}"),
            BatchOp("dm", OpType.DELETE_MEMORY, OpStatus.PENDING, false, "{}"),
            BatchOp("sp", OpType.SPLIT_MEMORY, OpStatus.PENDING, false, "{}"),
            BatchOp("mg", OpType.MERGE_MEMORY, OpStatus.PENDING, false, "{}"),
            BatchOp("ev", OpType.EVOLVE_MEMORY, OpStatus.PENDING, false, "{}")
        )
        assertTrue(OpValidator.planBatch(ops).isEmpty())
        assertEquals(
            listOf("ev", "mg", "sp", "dm", "s1", "d1"),
            OpValidator.planBatch(ops, onlyChecked = false).map { it.id }
        )
    }
}
