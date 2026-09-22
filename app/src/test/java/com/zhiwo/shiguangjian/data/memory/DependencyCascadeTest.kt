package com.zhiwo.shiguangjian.data.memory

import com.zhiwo.shiguangjian.data.organizeops.OpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 忽略提案时的依赖级联（MFDC）。
 * 旧实现只把自己置 DISMISSED、不清下游 dependsOn → 同批 DELETE 永久 BLOCKED，谁也没法收尾。
 */
class DependencyCascadeTest {

    private fun op(id: String, vararg deps: String, status: String = OpStatus.PENDING) =
        CascadeOp(id, deps.toList(), status)

    @Test fun MFDC01_单层链_下游被级联取消() {
        val ops = listOf(op("save"), op("del", "save"))
        assertEquals(listOf("del"), DependencyCascade.cascadeCancel("save", ops))
    }

    @Test fun MFDC02_三层链_全部下游被取消() {
        val ops = listOf(op("a"), op("b", "a"), op("c", "b"))
        assertEquals(listOf("b", "c"), DependencyCascade.cascadeCancel("a", ops))
    }

    @Test fun MFDC03_有环也必然收敛不死循环() {
        // B 依赖 A 与 C、C 又依赖 B：互相拉扯的环不能让它们"全都算被取消"，也不能转不停
        val cyclic = listOf(op("a"), op("b", "a", "c"), op("c", "b"))
        assertTrue(DependencyCascade.cascadeCancel("a", cyclic).isEmpty())
        // 根在环里：环上其余成员随根一起取消（单调增长 + 只增不减，一定停止）
        val selfLoop = listOf(op("x", "y"), op("y", "x"))
        assertEquals(listOf("y"), DependencyCascade.cascadeCancel("x", selfLoop).sorted())
        // 自依赖
        assertEquals(emptyList<String>(), DependencyCascade.cascadeCancel("a", listOf(op("a"), op("b", "b"))))
    }

    @Test fun MFDC04_多父依赖必须全部父被取消才级联() {
        val notYet = listOf(op("save1"), op("save2"), op("del", "save1", "save2"))
        // 只忽略 save1：save2 仍会执行，del 的依赖没全断 → 不级联（它只是会被阻塞，由 UI 说明原因）
        assertEquals(emptyList<String>(), DependencyCascade.cascadeCancel("save1", notYet))
        // save2 早已被忽略 → save1 一忽略，del 的全部父都断了 → 级联取消
        val bothGone = listOf(op("save1"), op("save2", status = OpStatus.DISMISSED), op("del", "save1", "save2"))
        assertEquals(listOf("del"), DependencyCascade.cascadeCancel("save1", bothGone))
    }

    @Test fun MFDC05_无依赖的条目只改自己() {
        val ops = listOf(op("save"), op("other"), op("del", "save"))
        assertEquals(listOf("del"), DependencyCascade.cascadeCancel("save", ops))
    }

    @Test fun MFDC06_父不存在的条目保守不级联() {
        // del 还依赖一条已经不在表里的提案：拿不准就留它一命（宁可不删）
        val ops = listOf(op("save"), op("del", "save", "ghost"))
        assertEquals(emptyList<String>(), DependencyCascade.cascadeCancel("save", ops))
    }

    @Test fun MFDC07_撤销忽略恢复被级联取消的下游() {
        val ops = listOf(
            op("save", status = OpStatus.DISMISSED),
            op("del", "save", status = OpStatus.DISMISSED),
            op("del2", "del", status = OpStatus.DISMISSED),
            op("unrelated", status = OpStatus.PENDING),
            op("kept", "save", status = OpStatus.PENDING)     // 没被级联过的不动它
        )
        assertEquals(listOf("del", "del2"), DependencyCascade.restoreSet("save", ops))
    }

    @Test fun MFDC08_直接下游与全部下游分别可查() {
        val ops = listOf(op("a"), op("b", "a"), op("c", "b"), op("d"))
        assertEquals(listOf("b"), DependencyCascade.directDependents("a", ops))
        assertEquals(listOf("b", "c"), DependencyCascade.allDependents("a", ops))
        assertTrue(DependencyCascade.allDependents("d", ops).isEmpty())
    }

    @Test fun MFDC09_未知根返回空不抛异常() {
        val ops = listOf(op("a"), op("b", "a"))
        assertEquals(emptyList<String>(), DependencyCascade.cascadeCancel("nope", ops))
        assertEquals(emptyList<String>(), DependencyCascade.restoreSet("nope", ops))
    }
}
