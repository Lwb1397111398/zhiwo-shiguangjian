package com.zhiwo.shiguangjian.data.profile

/**
 * 将 blocklist 渲染为提取 Prompt 可注入的文本。
 *
 * - 空名单 → 空串（模板中整段消失，不留标题/空行）
 * - 非空 → 标题 + 条目列表
 * - 六类合并，每类取尾部（最新），总量最多 30 条
 * - 单条 clamp 100 字符
 * - 只约束 updatedProfile，不影响 newMemories
 */
object ProfileBlocklistPrompt {
    private const val MAX_TOTAL = 30
    private const val MAX_ITEM_CHARS = 100

    fun render(blocklist: ProfileBlocklist): String {
        if (blocklist.isEmpty()) return ""

        // 六类按顺序收集，每类从尾部取（最新优先体现在 takeLast 后整体再截断）
        val buckets = listOf(
            blocklist.stableFacts,
            blocklist.preferences,
            blocklist.supportStyle,
            blocklist.appearanceFacts,
            blocklist.recentStates,
            blocklist.personalityTraits
        )
        // 先把各类尾部拼起来，再全局 takeLast(30) 保留最新
        val allNewestFirst = mutableListOf<String>()
        for (list in buckets) {
            // 列表顺序即插入顺序，尾部最新；先取本类全部（sanitize 已限 50）
            for (item in list) {
                val t = item.trim()
                if (t.isEmpty()) continue
                allNewestFirst += t.take(MAX_ITEM_CHARS)
            }
        }
        if (allNewestFirst.isEmpty()) return ""
        val selected = allNewestFirst.takeLast(MAX_TOTAL)

        val lines = ArrayList<String>(selected.size + 2)
        lines += "【禁止再次学习的内容】"
        lines += "以下条目被用户主动删除，即使今天的记录中再次出现，也不要写入 updatedProfile："
        for (item in selected) {
            lines += "- $item"
        }
        return lines.joinToString("\n")
    }

    /**
     * 将渲染结果注入模板：空串时去掉占位行及多余空行。
     */
    fun injectIntoTemplate(template: String, placeholder: String, rendered: String): String {
        if (rendered.isBlank()) {
            // 删除整行占位（含前后空白行压缩）
            val withoutLine = template
                .lines()
                .filterNot { it.contains(placeholder) }
                .joinToString("\n")
            return withoutLine.replace(Regex("\n{3,}"), "\n\n")
        }
        return template.replace(placeholder, rendered)
    }
}
