package com.zhiwo.shiguangjian.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * v13→v14 迁移语句的**结构**校验：顺序错一位就会丢数据，而这些顺序用肉眼最容易看漏。
 *
 * 说明白它测不到什么：不真跑 SQL（本工程没有 instrumentation，也没引 sqlite-jdbc），
 * 所以"跑通"仍要靠真机覆盖安装那一次验收。这里只保证不会犯的错一个都别犯。
 */
class Migration13To14SqlTest {

    private val s = Migration13To14Sql.statements()
    private fun indexOf(fragment: String) = s.indexOfFirst { it.contains(fragment, true) }

    @Test fun M01_快照子表必须排在一切破坏性动作之前() {
        val snapshot = indexOf("CREATE TABLE `task_occurrences_bak` AS SELECT")
        val dropTasksOld = indexOf("DROP TABLE `tasks_old`")
        val dropOcc = indexOf("DROP TABLE `task_occurrences`")
        assertTrue("缺快照语句", snapshot >= 0)
        assertTrue("快照必须在 DROP tasks_old 之前，否则 CASCADE 会清空打卡历史", snapshot < dropTasksOld)
        assertTrue("快照必须在 DROP task_occurrences 之前", snapshot < dropOcc)
    }

    @Test fun M02_快照表不能带外键约束() {
        val snapshot = s.first { it.contains("task_occurrences_bak") && it.startsWith("CREATE TABLE") }
        assertTrue("CTAS 快照不该出现 FOREIGN KEY：${snapshot.take(80)}", !snapshot.contains("FOREIGN KEY"))
    }

    @Test fun M03_回填必须显式带id否则自增序号会重排() {
        val insert = s.first { it.startsWith("INSERT INTO `tasks`") }
        assertTrue("INSERT 列表要以 id 开头：${insert.take(90)}", insert.contains("(`id`,"))
        assertTrue("SELECT 也要取旧表 id", insert.contains("SELECT `id`") || insert.contains("SELECT tasks_old.`id`"))
    }

    @Test fun M04_recordId外键改成SET_NULL且不再有CASCADE() {
        assertTrue(Migration13To14Sql.TASKS_DDL.contains("REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL"))
        assertTrue("v14 的 tasks DDL 不该再有 CASCADE", !Migration13To14Sql.TASKS_DDL.contains("CASCADE"))
    }

    @Test fun M05_回填goalId与adhoc期限都在INSERT里做掉() {
        val insert = s.first { it.startsWith("INSERT INTO `tasks`") }
        assertTrue(insert.contains("COALESCE(tasks_old.`goalId`"))
        assertTrue(insert.contains("FROM `goals` g WHERE g.`recordId` = tasks_old.`parentGoalId`"))
        assertTrue(insert.contains("tasks_old.`kind` = 'adhoc'"))
        assertTrue("期限要截掉时间部分", insert.contains("substr(tasks_old.`dueDate`, 1, 10)"))
    }

    @Test fun M06_索引必须在改名之后建且数量与实体一致() {
        val createIndex = s.indexOfFirst { it.startsWith("CREATE INDEX") }
        val dropOld = indexOf("DROP TABLE `tasks_old`")
        assertTrue("索引语句排在 DROP 之前会撞名", createIndex > dropOld)
        assertEquals(4, Migration13To14Sql.TASKS_INDEXES.size)
        assertEquals(2, Migration13To14Sql.OCCURRENCES_INDEXES.size)
        assertTrue(Migration13To14Sql.TASKS_INDEXES.all { it.contains(" ON `tasks` ") })
    }

    @Test fun M07_只用低版本SQLite也有的语法() {
        val banned = listOf("DROP COLUMN", "legacy_alter_table", "writable_schema", "foreign_keys")
        s.forEach { stmt ->
            banned.forEach { b ->
                assertTrue("迁移语句里不许出现 $b：${stmt.take(70)}", !stmt.contains(b, true))
            }
        }
    }

    @Test fun M08_列清单与13json一致() {
        assertEquals(27, Migration13To14Sql.TASK_COLUMNS.size)
        assertEquals(10, Migration13To14Sql.OCCURRENCE_COLUMNS.size)
        // 回填表达式的逗号个数与列数不必相等（函数里也有逗号），这里只钉住"每条语句都有内容"
        assertTrue(Migration13To14Sql.TASKS_INDEXES.all { it.isNotBlank() })
    }

    @Test fun M09_语句清单不许掺空语句() {
        assertTrue(s.isNotEmpty())
        assertTrue(s.none { it.isBlank() })
    }
}
