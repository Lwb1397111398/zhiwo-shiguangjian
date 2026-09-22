package com.zhiwo.shiguangjian.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆判重口径（MFS）。
 * 旧实现是"整句 trim 后完全相等"，换个说法的旧记忆永远不被取代 —— 用户看到的"一键整理后还有残存记忆"。
 */
class MemorySimilarityTest {

    // ========== normalize ==========

    @Test fun MFS01_空串与全空格归一为空() {
        assertEquals("", MemorySimilarity.normalize(""))
        assertEquals("", MemorySimilarity.normalize("   "))
        assertEquals("", MemorySimilarity.normalize(" \t\n  "))
        assertEquals("", MemorySimilarity.normalize("　　"))   // 全角空格
    }

    @Test fun MFS02_全角标点转半角或直接丢弃() {
        assertEquals("我喜欢跑步", MemorySimilarity.normalize("我喜欢跑步。"))
        assertEquals("我喜欢跑步天天都跑", MemorySimilarity.normalize("「我喜欢跑步，天天都跑」"))
        // 全角标点与半角标点在归一化后不存在差异
        assertEquals(
            MemorySimilarity.normalize("我会开车,2024年拿证"),
            MemorySimilarity.normalize("我会开车，2024年拿证")
        )
    }

    @Test fun MFS03_大小写与全角字母数字归一() {
        assertEquals("abc123", MemorySimilarity.normalize("ＡＢＣ１２３"))
        assertEquals("i love running", MemorySimilarity.normalize("I Love Running"))
        assertEquals(SimilarityLevel.EXACT, MemorySimilarity.level("ABC", "abc"))
    }

    @Test fun MFS11_normalize_幂等() {
        listOf("  我喜欢跑步。 ", "ＡＢＣ１２３\n\"引号\"", "", "   ", "我在准备法考！！").forEach { raw ->
            val once = MemorySimilarity.normalize(raw)
            assertEquals("幂等被破坏: [$raw]", once, MemorySimilarity.normalize(once))
        }
    }

    @Test fun MFS12_换行与引号不算差异() {
        assertEquals(
            SimilarityLevel.EXACT,
            MemorySimilarity.level("\"我\n喜欢   跑步\"", "我喜欢跑步")
        )
    }

    // ========== level：CONTAINS ==========

    @Test fun MFS04_CONTAINS_长度差边界_8_含_9_不含() {
        val short = "我喜欢跑步"                                     // 5 字
        val diff8 = "我喜欢跑步abcdefgh"                        // 13 字，差 8
        val diff9 = "我喜欢跑步abcdefghi"                       // 14 字，差 9
        assertEquals(SimilarityLevel.CONTAINS, MemorySimilarity.level(short, diff8))
        assertEquals(SimilarityLevel.CONTAINS, MemorySimilarity.level(diff8, short))   // 参数顺序无关
        assertEquals(SimilarityLevel.DIFFERENT, MemorySimilarity.level(short, diff9))
    }

    @Test fun MFS05_被包含方太短不算_CONTAINS() {
        // 单字"我"能出现在任何句子里，判 CONTAINS 会自动合出一堆垃圾
        assertEquals(SimilarityLevel.DIFFERENT, MemorySimilarity.level("我", "我会开车了"))
        assertEquals(SimilarityLevel.CONTAINS, MemorySimilarity.level("会开车", "我会开车了"))
    }

    // ========== level：SIMILAR 阈值 ==========

    @Test fun MFS06_得分低于阈值判_DIFFERENT() {
        val a = "abcdefgh"
        val b = "abxyzwdeg"
        assertEquals(0.588, MemorySimilarity.score(a, b), 0.001)
        assertEquals(SimilarityLevel.DIFFERENT, MemorySimilarity.level(a, b))
    }

    @Test fun MFS07_得分达到阈值判_SIMILAR() {
        val a = "我喜欢跑步"
        val b = "我喜爱跑步"
        assertEquals(0.8, MemorySimilarity.score(a, b), 0.001)
        assertEquals(SimilarityLevel.SIMILAR, MemorySimilarity.level(a, b))
    }

    @Test fun MFS07c_数字不一致时字形再像也判_DIFFERENT() {
        assertEquals(0.833, MemorySimilarity.score("我今年25岁", "我今年26岁"), 0.001)
        assertEquals(SimilarityLevel.DIFFERENT, MemorySimilarity.level("我今年25岁", "我今年26岁"))
        assertEquals(SimilarityLevel.DIFFERENT, MemorySimilarity.level("我2024年拿证", "我2025年拿证"))
        assertTrue(MemorySimilarity.digitsConflict("跑5公里", "跑6公里"))
        assertFalse(MemorySimilarity.digitsConflict("我今年25岁", "25岁的我"))
    }

    @Test fun MFS07b_dice_系数的字符集与bigram两路分别可查() {
        // 两句各 5 字、只差 1 字：字符集 ∩=4 → Dice 0.8；bigram ∩=2 → Dice 0.5
        assertEquals(0.5, MemorySimilarity.bigramDice("我喜欢跑步", "我喜爱跑步"), 0.001)
        assertEquals(0.8, MemorySimilarity.charDice("我喜欢跑步", "我喜爱跑步"), 0.001)
        assertEquals(0.8, MemorySimilarity.score("我喜欢跑步", "我喜爱跑步"), 0.001)
    }

    @Test fun MFS08_换个说法的同一条记忆判_SIMILAR() {
        assertEquals(SimilarityLevel.SIMILAR, MemorySimilarity.level("我喜欢跑步", "我喜爱跑步"))
        // SIMILAR 只提示，不自动合并
        assertFalse(MemorySimilarity.autoMergeable("我喜欢跑步", "我喜爱跑步"))
        assertTrue(MemorySimilarity.suspiciousDuplicate("我喜欢跑步", "我喜爱跑步"))
    }

    @Test fun MFS09_两件不同的事必须判_DIFFERENT() {
        // 过度合并会把"在准备法考"吃掉，用户就再也不知道自己备考过
        assertEquals(SimilarityLevel.DIFFERENT, MemorySimilarity.level("我在准备法考", "我今年通过法考"))
        assertEquals(SimilarityLevel.DIFFERENT, MemorySimilarity.level("我喜欢跑步", "我讨厌跑步"))
    }

    @Test fun MFS15_只差一个数字不得判重() {
        assertEquals(SimilarityLevel.DIFFERENT, MemorySimilarity.level("我今年25岁", "我今年26岁"))
    }

    // ========== level：EXACT / 自反 ==========

    @Test fun MFS10_自反性必为_EXACT() {
        listOf(
            "我喜欢跑步", "", "   ", "我在准备法考", "I Love Running", "abcde",
            "我会开车（2024年拿证）"
        ).forEach { s ->
            assertEquals("自反性失败: [$s]", SimilarityLevel.EXACT, MemorySimilarity.level(s, s))
        }
    }

    @Test fun MFS13_只有标点与空格差异判_EXACT() {
        assertEquals(SimilarityLevel.EXACT, MemorySimilarity.level("我会开车（2024年）", "我会开车 2024 年"))
        assertEquals(SimilarityLevel.EXACT, MemorySimilarity.level("DeepSeek 用得好", "deepseek用得好"))
        assertEquals(SimilarityLevel.EXACT, MemorySimilarity.level("拿到驾照、会开车", "拿到驾照会开车"))
    }

    @Test fun MFS14_autoMergeable_只放行_EXACT_与_CONTAINS() {
        assertTrue(MemorySimilarity.autoMergeable("会开车", "会开车"))                       // EXACT
        assertTrue(MemorySimilarity.autoMergeable("会开车", "我会开车了"))                  // CONTAINS
        assertFalse(MemorySimilarity.autoMergeable("我喜欢跑步", "我喜爱跑步"))             // SIMILAR
        assertFalse(MemorySimilarity.autoMergeable("我在准备法考", "我今年通过法考"))        // DIFFERENT
        assertFalse(MemorySimilarity.autoMergeable("会开车", ""))                            // 空串不得吞掉一切
    }
}
