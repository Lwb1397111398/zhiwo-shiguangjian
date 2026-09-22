package com.zhiwo.shiguangjian.data.memory

/**
 * 记忆文本相似度：整理/对账判重的唯一口径（纯 Kotlin，零 Android 依赖，可在 JVM 直接跑）。
 *
 * 为什么需要它：旧判重是「整句 trim 后完全相等」，换个说法的旧记忆永远不会被取代，
 * 于是"一键整理之后老有残存的记忆"——新旧两条同时 active。
 *
 * 约定：只有 [SimilarityLevel.EXACT] 与 [SimilarityLevel.CONTAINS] 允许自动合并；
 * [SimilarityLevel.SIMILAR] 只做"疑似重复"提示，绝不自动合（防止"我在准备法考"被并进"我通过法考"）。
 */
enum class SimilarityLevel { EXACT, CONTAINS, SIMILAR, DIFFERENT }

object MemorySimilarity {

    /** 判定为 SIMILAR 的最低相似度 */
    const val SIMILAR_THRESHOLD = 0.70

    /** CONTAINS 允许的长短差（字符数），超过则认为说的是两件事 */
    const val CONTAINS_LENGTH_DIFF = 8

    /** 被包含方至少这么长才算 CONTAINS，否则"我"这种单字能包含进任意句子里 */
    const val CONTAINS_MIN_LENGTH = 3

    /**
     * 归一化：全角转半角 → 只保留字母/数字/CJK → 空白折叠为单个空格并去首尾 → 小写。
     *
     * 标点（中英文）直接丢弃，因此「我喜欢跑步。」与「我喜欢跑步」判为 EXACT；
     * 引号、换行、全角空格同样不参与比较。幂等：normalize(normalize(x)) == normalize(x)。
     */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val ch = when {
                c == '　' -> ' '                                   // 全角空格
                c in '！'..'～' -> (c - 0xFEE0)             // 全角标点/字母/数字 → 半角
                c.isWhitespace() -> ' '
                else -> c
            }
            if (ch == ' ') {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            } else if (Character.isLetterOrDigit(ch)) {
                sb.append(ch.lowercaseChar())
            }
        }
        var end = sb.length
        while (end > 0 && sb[end - 1] == ' ') end--
        return sb.substring(0, end)
    }

    /** 四级判定：归一化后全等 / 完整包含（长度差有限） / Dice 相似 / 不同 */
    fun level(a: String, b: String): SimilarityLevel {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return if (x == y) SimilarityLevel.EXACT else SimilarityLevel.DIFFERENT
        // 中文里空格只是断句噪声（换行、引号、两个空格），比较时一律去掉
        val cx = x.filterNot { it == ' ' }
        val cy = y.filterNot { it == ' ' }
        if (cx == cy) return SimilarityLevel.EXACT
        val shorter = if (cx.length <= cy.length) cx else cy
        val longer = if (cx.length <= cy.length) cy else cx
        if (shorter.length >= CONTAINS_MIN_LENGTH &&
            longer.length - shorter.length <= CONTAINS_LENGTH_DIFF &&
            longer.contains(shorter)
        ) return SimilarityLevel.CONTAINS
        // 数字是事实（年龄/年份/次数），数字不一致就绝不判相似——
        // 「我今年25岁」与「我今年26岁」字形 Dice 高达 0.83，却是两条互相矛盾的记忆
        if (digitsOf(cx) != digitsOf(cy)) return SimilarityLevel.DIFFERENT
        return if (score(cx, cy, normalized = true) >= SIMILAR_THRESHOLD) SimilarityLevel.SIMILAR else SimilarityLevel.DIFFERENT
    }

    /** 自动合并是否放行：仅 EXACT / CONTAINS */
    fun autoMergeable(a: String, b: String): Boolean {
        return when (level(a, b)) {
            SimilarityLevel.EXACT, SimilarityLevel.CONTAINS -> true
            else -> false
        }
    }

    /** 是否"疑似重复但需要人看"：SIMILAR */
    fun suspiciousDuplicate(a: String, b: String): Boolean = level(a, b) == SimilarityLevel.SIMILAR

    /**
     * 综合得分：字符 1-gram 与 2-gram（bigram）的 Dice 系数取较大值。
     *
     * 不用 Jaccard 是因为中文短句下它系统性偏低：「我喜欢跑步」vs「我喜爱跑步」
     * 字符 Jaccard 只有 0.67（4/6），会被误判成"两条不同的记忆"（正是"整理后还剩同义记忆"的成因）；
     * 字符 Dice 为 0.80，配上 0.70 阈值才判得出来。而「我今年25岁」vs「我今年26岁」Dice 虽然也有 0.83，
     * 但数字不一致会被 [level] 的"数字即事实"规则直接判 DIFFERENT（见 MFS15/MFS07c）。
     * 仍然正确地不判相似——不会把两件相反的事合成一条。
     * [normalized] = true 表示入参已经 normalize + 去空格过（[level] 内部走这条路径）。
     */
    fun score(a: String, b: String, normalized: Boolean = false): Double {
        val x = if (normalized) a else compact(a)
        val y = if (normalized) b else compact(b)
        return maxOf(dice(grams(x, 1), grams(y, 1)), dice(grams(x, 2), grams(y, 2)))
    }

    /** 归一化后的 bigram Dice */
    fun bigramDice(a: String, b: String): Double = dice(grams(compact(a), 2), grams(compact(b), 2))

    /** 归一化后的单字符集合 Dice */
    fun charDice(a: String, b: String): Double = dice(grams(compact(a), 1), grams(compact(b), 1))

    /** 归一化 + 去掉所有空白：比较用的紧凑形态 */
    private fun compact(s: String): String = normalize(s).filterNot { it == ' ' }

    /** 按出现顺序取出的数字串列表，用于"数字即事实"的防误合判定 */
    private fun digitsOf(s: String): List<String> = Regex("[0-9]+").findAll(s).map { it.value }.toList()

    /** 数字是否一致（供上层做提示时复用同一判定） */
    fun digitsConflict(a: String, b: String): Boolean = digitsOf(compact(a)) != digitsOf(compact(b))

    private fun grams(s: String, n: Int): Set<String> {
        if (s.isEmpty()) return emptySet()
        if (s.length < n) return setOf(s)
        val out = HashSet<String>(s.length)
        for (i in 0..s.length - n) out += s.substring(i, i + n)
        return out
    }

    private fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return 2.0 * inter / (a.size + b.size)
    }
}
