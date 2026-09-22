package com.zhiwo.shiguangjian.data.repository

import com.zhiwo.shiguangjian.data.db.dao.ReviewDao
import com.zhiwo.shiguangjian.data.db.entity.ReviewEntity
import kotlinx.coroutines.flow.Flow

class ReviewRepository(private val reviewDao: ReviewDao) {

    fun getAllReviews(): Flow<List<ReviewEntity>> = reviewDao.getAllReviews()

    fun getReviewsByType(type: String): Flow<List<ReviewEntity>> =
        reviewDao.getReviewsByType(type)

    suspend fun getReviewByDate(date: String, type: String): ReviewEntity? =
        reviewDao.getReviewByDate(date, type)

    suspend fun getReviewsByDateRange(startDate: String, endDate: String): List<ReviewEntity> =
        reviewDao.getReviewsByDateRange(startDate, endDate)

    suspend fun getReviewById(id: Long): ReviewEntity? = reviewDao.getReviewById(id)

    suspend fun insertReview(review: ReviewEntity): Long = reviewDao.insertReview(review)

    suspend fun updateReview(review: ReviewEntity) = reviewDao.updateReview(review)

    suspend fun deleteReview(id: Long) = reviewDao.deleteReviewById(id)

    suspend fun getReviewCount(): Int = reviewDao.getReviewCount()
}
