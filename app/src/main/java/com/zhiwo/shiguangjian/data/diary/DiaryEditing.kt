package com.zhiwo.shiguangjian.data.diary

import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity

fun prepareEditedDiary(diary: DiaryEntity, newContent: String): DiaryEntity {
    return diary.copy(content = newContent, exported = false)
}
