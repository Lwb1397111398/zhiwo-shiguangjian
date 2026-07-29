package com.zhiwo.shiguangjian.data.repository

import com.zhiwo.shiguangjian.data.db.dao.DiaryDao
import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity
import kotlinx.coroutines.flow.Flow

class DiaryRepository(private val diaryDao: DiaryDao) {

    fun getAllDiaries(): Flow<List<DiaryEntity>> = diaryDao.getAllDiaries()

    suspend fun getDiaryByDate(date: String): DiaryEntity? = diaryDao.getDiaryByDate(date)

    fun searchDiaries(query: String): Flow<List<DiaryEntity>> = diaryDao.searchDiaries(query)

    suspend fun insertDiary(diary: DiaryEntity): Long = diaryDao.insertDiary(diary)

    suspend fun updateDiary(diary: DiaryEntity) = diaryDao.updateDiary(diary)

    suspend fun deleteDiaryById(id: Long) = diaryDao.deleteDiaryById(id)

    suspend fun getDiaryCount(): Int = diaryDao.getDiaryCount()

    suspend fun markAsExported(id: Long) = diaryDao.markAsExported(id)

    suspend fun markAllAsExported(ids: List<Long>) = diaryDao.markAllAsExported(ids)

    suspend fun deleteExportedDiaries() = diaryDao.deleteExportedDiaries()

    suspend fun getExportedCount(): Int = diaryDao.getExportedCount()
}
