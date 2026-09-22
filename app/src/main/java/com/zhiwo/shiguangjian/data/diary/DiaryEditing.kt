package com.zhiwo.shiguangjian.data.diary

import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity

fun prepareEditedDiary(diary: DiaryEntity, newContent: String): DiaryEntity {
    // 手动编辑过的日记视为用户内容，后续不允许被自动重新生成覆盖
    return diary.copy(content = newContent, exported = false, isUserEdited = true)
}
