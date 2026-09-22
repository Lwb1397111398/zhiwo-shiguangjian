package com.zhiwo.shiguangjian.data.db

/**
 * v13 -> v14 的 SQL。由 tools/gen_migration_13_14.py 从 app/schemas/…/13.json 生成，**不要手改**：
 * 实体或列一变，重跑生成脚本，否则迁移与实体会对不上。
 *
 * 这一版**不删老列**（全库还有读者），只做两件与数据有关的事：
 * 1. tasks.recordId 外键由 CASCADE 改成 SET NULL：删掉一条记录不该把它派生的任务一起带走。
 * 2. 回填 goalId 与 adhoc 期限：AI 生成的任务只写了老的 parentGoalId、goalId 一直是空的
 *    （InputViewModel 传 goalIdOf = { null }），按「parentGoalId 指向的记录本身就是某条 goals 的
 *    recordId」认回去；认不上的保留原样，不强猜。adhoc 的期限从老 dueDate 搬进 endDate。
 *
 * 顺序照基准文档 §四 的实测结论（本机 SQLite 复现过）：DROP 父表会级联清空 task_occurrences，
 * RENAME 会改写子表外键目标，INSERT 不带 id 会让自增序号从 1 重排、回填的 taskId 全体错位。
 * 所以：快照子表 -> 建新表（显式带 id）-> 删旧表 -> 重建子表并回填 -> 指纹断言，不符就抛异常整体回滚。
 * 语句只用 CREATE TABLE / AS SELECT / INSERT…SELECT / DROP TABLE / ALTER TABLE RENAME / CREATE INDEX：
 * minSdk 26~28 的设备是 SQLite 3.19~3.24，ALTER TABLE DROP COLUMN 在那儿根本不存在。
 */
object Migration13To14Sql {

    const val TASKS_DDL = "CREATE TABLE IF NOT EXISTS `tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `recordId` INTEGER, `parentGoalId` INTEGER, `content` TEXT NOT NULL, `dueDate` TEXT NOT NULL, `taskType` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, `completedAt` TEXT, `dailyCompletionDate` TEXT, `isPermanentlyCompleted` INTEGER NOT NULL, `calendarEventId` INTEGER, `createdAt` TEXT NOT NULL, `kind` TEXT NOT NULL, `repeatRule` TEXT NOT NULL, `weekdaysCsv` TEXT NOT NULL, `intervalDays` INTEGER NOT NULL, `dayPolicy` TEXT NOT NULL, `startDate` TEXT NOT NULL, `endDate` TEXT NOT NULL, `scheduledDate` TEXT NOT NULL, `remindTime` TEXT NOT NULL, `durationMinutes` INTEGER NOT NULL, `goalId` INTEGER, `planId` INTEGER, `status` TEXT NOT NULL, `colorIndex` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, FOREIGN KEY(`recordId`) REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`goalId`) REFERENCES `goals`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`planId`) REFERENCES `plans`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
    const val OCCURRENCES_DDL = "CREATE TABLE IF NOT EXISTS `task_occurrences` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `taskId` INTEGER NOT NULL, `date` TEXT NOT NULL, `status` TEXT NOT NULL, `reasonCode` TEXT NOT NULL, `reasonNote` TEXT NOT NULL, `actualMinutes` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    val TASKS_INDEXES = listOf(
        "CREATE INDEX IF NOT EXISTS `index_tasks_recordId` ON `tasks` (`recordId`)",
        "CREATE INDEX IF NOT EXISTS `index_tasks_parentGoalId` ON `tasks` (`parentGoalId`)",
        "CREATE INDEX IF NOT EXISTS `index_tasks_goalId` ON `tasks` (`goalId`)",
        "CREATE INDEX IF NOT EXISTS `index_tasks_planId` ON `tasks` (`planId`)",
    )

    val OCCURRENCES_INDEXES = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_task_occurrences_taskId_date` ON `task_occurrences` (`taskId`, `date`)",
        "CREATE INDEX IF NOT EXISTS `index_task_occurrences_taskId` ON `task_occurrences` (`taskId`)",
    )

    val TASK_COLUMNS = listOf(
        "id",
        "recordId",
        "parentGoalId",
        "content",
        "dueDate",
        "taskType",
        "isCompleted",
        "completedAt",
        "dailyCompletionDate",
        "isPermanentlyCompleted",
        "calendarEventId",
        "createdAt",
        "kind",
        "repeatRule",
        "weekdaysCsv",
        "intervalDays",
        "dayPolicy",
        "startDate",
        "endDate",
        "scheduledDate",
        "remindTime",
        "durationMinutes",
        "goalId",
        "planId",
        "status",
        "colorIndex",
        "sortOrder",
    )

    val OCCURRENCE_COLUMNS = listOf(
        "id",
        "taskId",
        "date",
        "status",
        "reasonCode",
        "reasonNote",
        "actualMinutes",
        "note",
        "createdAt",
        "updatedAt",
    )

    /** 按顺序执行的建表/搬数据语句；断言在 Migration13To14 里（要读库返回值，不在这份清单里） */
    fun statements(): List<String> = listOf(
        "CREATE TABLE `task_occurrences_bak` AS SELECT * FROM `task_occurrences`",
        "ALTER TABLE `tasks` RENAME TO `tasks_old`",
        TASKS_DDL,
        "INSERT INTO `tasks` (`id`, `recordId`, `parentGoalId`, `content`, `dueDate`, `taskType`, `isCompleted`, `completedAt`, `dailyCompletionDate`, `isPermanentlyCompleted`, `calendarEventId`, `createdAt`, `kind`, `repeatRule`, `weekdaysCsv`, `intervalDays`, `dayPolicy`, `startDate`, `endDate`, `scheduledDate`, `remindTime`, `durationMinutes`, `goalId`, `planId`, `status`, `colorIndex`, `sortOrder`) SELECT tasks_old.`id`, (SELECT rr.`id` FROM `records` rr WHERE rr.`id` = tasks_old.`recordId`), tasks_old.`parentGoalId`, tasks_old.`content`, tasks_old.`dueDate`, tasks_old.`taskType`, tasks_old.`isCompleted`, tasks_old.`completedAt`, tasks_old.`dailyCompletionDate`, tasks_old.`isPermanentlyCompleted`, tasks_old.`calendarEventId`, tasks_old.`createdAt`, tasks_old.`kind`, tasks_old.`repeatRule`, tasks_old.`weekdaysCsv`, tasks_old.`intervalDays`, tasks_old.`dayPolicy`, tasks_old.`startDate`, CASE WHEN tasks_old.`kind` = 'adhoc' AND tasks_old.`endDate` = '' THEN substr(tasks_old.`dueDate`, 1, 10) ELSE tasks_old.`endDate` END, tasks_old.`scheduledDate`, tasks_old.`remindTime`, tasks_old.`durationMinutes`, (SELECT gg.`id` FROM `goals` gg WHERE gg.`id` = COALESCE(tasks_old.`goalId`, (SELECT g.`id` FROM `goals` g WHERE g.`recordId` = tasks_old.`parentGoalId`))), (SELECT pp.`id` FROM `plans` pp WHERE pp.`id` = tasks_old.`planId`), tasks_old.`status`, tasks_old.`colorIndex`, tasks_old.`sortOrder` FROM `tasks_old`",
        "DROP TABLE `tasks_old`",
        *TASKS_INDEXES.toTypedArray(),
        "DROP TABLE `task_occurrences`",
        OCCURRENCES_DDL,
        "INSERT INTO `task_occurrences` (`id`, `taskId`, `date`, `status`, `reasonCode`, `reasonNote`, `actualMinutes`, `note`, `createdAt`, `updatedAt`) SELECT task_occurrences_bak.`id`, task_occurrences_bak.`taskId`, task_occurrences_bak.`date`, task_occurrences_bak.`status`, task_occurrences_bak.`reasonCode`, task_occurrences_bak.`reasonNote`, task_occurrences_bak.`actualMinutes`, task_occurrences_bak.`note`, task_occurrences_bak.`createdAt`, task_occurrences_bak.`updatedAt` FROM `task_occurrences_bak` WHERE `taskId` IN (SELECT `id` FROM `tasks`)",
        "DROP TABLE `task_occurrences_bak`",
        *OCCURRENCES_INDEXES.toTypedArray()
    )
}
