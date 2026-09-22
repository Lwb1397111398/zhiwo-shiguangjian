import json, io, os

ROOT = r"E:/AI Agent/work area/知我时光笺/zhiwo-android"
schema = json.load(io.open(os.path.join(ROOT, "app/schemas/com.zhiwo.shiguangjian.data.db.AppDatabase/13.json"), encoding="utf-8"))
ent = {e["tableName"]: e for e in schema["database"]["entities"]}
tasks, occ = ent["tasks"], ent["task_occurrences"]
tasks_cols = [f["columnName"] for f in tasks["fields"]]
occ_cols = [f["columnName"] for f in occ["fields"]]

tasks_ddl = tasks["createSql"].replace("${TABLE_NAME}", "tasks").replace(
    "FOREIGN KEY(`recordId`) REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE",
    "FOREIGN KEY(`recordId`) REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL")
occ_ddl = occ["createSql"].replace("${TABLE_NAME}", "task_occurrences")
tasks_idx = [i["createSql"].replace("${TABLE_NAME}", "tasks") for i in tasks["indices"]]
occ_idx = [i["createSql"].replace("${TABLE_NAME}", "task_occurrences") for i in occ["indices"]]

sel, ins = [], []
for c in tasks_cols:
    ins.append("`%s`" % c)
    if c == "goalId":
        # 相关子查询包一层：算出来的目标行不存在时返回 NULL，而不是把外键撞断
        sel.append("(SELECT gg.`id` FROM `goals` gg WHERE gg.`id` = COALESCE(tasks_old.`goalId`, "
                   "(SELECT g.`id` FROM `goals` g WHERE g.`recordId` = tasks_old.`parentGoalId`)))")
    elif c == "recordId":
        sel.append("(SELECT rr.`id` FROM `records` rr WHERE rr.`id` = tasks_old.`recordId`)")
    elif c == "planId":
        sel.append("(SELECT pp.`id` FROM `plans` pp WHERE pp.`id` = tasks_old.`planId`)")
    elif c == "endDate":
        sel.append("CASE WHEN tasks_old.`kind` = 'adhoc' AND tasks_old.`endDate` = '' "
                   "THEN substr(tasks_old.`dueDate`, 1, 10) ELSE tasks_old.`endDate` END")
    else:
        sel.append("tasks_old.`%s`" % c)

insert_tasks = "INSERT INTO `tasks` (%s) SELECT %s FROM `tasks_old`" % (", ".join(ins), ", ".join(sel))
insert_occ = ("INSERT INTO `task_occurrences` (%s) SELECT %s FROM `task_occurrences_bak`"
              % (", ".join("`%s`" % c for c in occ_cols), ", ".join("task_occurrences_bak.`%s`" % c for c in occ_cols)))

def ks(s):
    return json.dumps(s, ensure_ascii=False)

L = []
A = L.append
A("package com.zhiwo.shiguangjian.data.db")
A("")
A("/**")
A(" * v13 -> v14 的 SQL。由 tools/gen_migration_13_14.py 从 app/schemas/…/13.json 生成，**不要手改**：")
A(" * 实体或列一变，重跑生成脚本，否则迁移与实体会对不上。")
A(" *")
A(" * 这一版**不删老列**（全库还有读者），只做两件与数据有关的事：")
A(" * 1. tasks.recordId 外键由 CASCADE 改成 SET NULL：删掉一条记录不该把它派生的任务一起带走。")
A(" * 2. 回填 goalId 与 adhoc 期限：AI 生成的任务只写了老的 parentGoalId、goalId 一直是空的")
A(" *    （InputViewModel 传 goalIdOf = { null }），按「parentGoalId 指向的记录本身就是某条 goals 的")
A(" *    recordId」认回去；认不上的保留原样，不强猜。adhoc 的期限从老 dueDate 搬进 endDate。")
A(" *")
A(" * 顺序照基准文档 §四 的实测结论（本机 SQLite 复现过）：DROP 父表会级联清空 task_occurrences，")
A(" * RENAME 会改写子表外键目标，INSERT 不带 id 会让自增序号从 1 重排、回填的 taskId 全体错位。")
A(" * 所以：快照子表 -> 建新表（显式带 id）-> 删旧表 -> 重建子表并回填 -> 指纹断言，不符就抛异常整体回滚。")
A(" * 语句只用 CREATE TABLE / AS SELECT / INSERT…SELECT / DROP TABLE / ALTER TABLE RENAME / CREATE INDEX：")
A(" * minSdk 26~28 的设备是 SQLite 3.19~3.24，ALTER TABLE DROP COLUMN 在那儿根本不存在。")
A(" */")
A("object Migration13To14Sql {")
A("")
A("    const val TASKS_DDL = %s" % ks(tasks_ddl))
A("    const val OCCURRENCES_DDL = %s" % ks(occ_ddl))
A("")
A("    val TASKS_INDEXES = listOf(")
for s in tasks_idx: A("        %s," % ks(s))
A("    )")
A("")
A("    val OCCURRENCES_INDEXES = listOf(")
for s in occ_idx: A("        %s," % ks(s))
A("    )")
A("")
A("    val TASK_COLUMNS = listOf(")
for c in tasks_cols: A("        \"%s\"," % c)
A("    )")
A("")
A("    val OCCURRENCE_COLUMNS = listOf(")
for c in occ_cols: A("        \"%s\"," % c)
A("    )")
A("")
A("    /** 按顺序执行的建表/搬数据语句；断言在 Migration13To14 里（要读库返回值，不在这份清单里） */")
A("    fun statements(): List<String> = listOf(")
A("        %s," % ks("CREATE TABLE `task_occurrences_bak` AS SELECT * FROM `task_occurrences`"))
A("        %s," % ks("ALTER TABLE `tasks` RENAME TO `tasks_old`"))
A("        TASKS_DDL,")
A("        %s," % ks(insert_tasks))
A("        %s," % ks("DROP TABLE `tasks_old`"))
A("        *TASKS_INDEXES.toTypedArray(),")
A("        %s," % ks("DROP TABLE `task_occurrences`"))
A("        OCCURRENCES_DDL,")
A("        %s," % ks(insert_occ + " WHERE `taskId` IN (SELECT `id` FROM `tasks`)"))
A("        %s," % ks("DROP TABLE `task_occurrences_bak`"))
A("        *OCCURRENCES_INDEXES.toTypedArray()")
A("    )")
A("}")
A("")

out = os.path.join(ROOT, "app/src/main/java/com/zhiwo/shiguangjian/data/db/Migration13To14Sql.kt")
io.open(out, "w", encoding="utf-8", newline="\n").write("\n".join(L))
print("生成", out, len(L), "行")
print("tasks 列数", len(tasks_cols), "occ 列数", len(occ_cols))
