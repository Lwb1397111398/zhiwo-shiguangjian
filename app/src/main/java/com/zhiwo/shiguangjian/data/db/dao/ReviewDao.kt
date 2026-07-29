package com.zhiwo.shiguangjian.data.db.dao

import androidx.room.*
import com.zhiwo.shiguangjian.data.db.entity.ReviewEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewDao {
    @Query("SELECT * FROM reviews ORDER BY createdAt DESC")
    fun getAllReviews(): Flow<List<ReviewEntity>>

    @Query("SELECT * FROM reviews WHERE type = :type ORDER BY date DESC")
    fun getReviewsByType(type: String): Flow<List<ReviewEntity>>

    @Query("SELECT * FROM reviews WHERE date = :date AND type = :type LIMIT 1")
    suspend fun getReviewByDate(date: String, type: String): ReviewEntity?

    @Query("SELECT * FROM reviews WHERE date BETWEEN :startDate AND :endDate ORDER BY date DESC")
    suspend fun getReviewsByDateRange(startDate: String, endDate: String): List<ReviewEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReview(review: ReviewEntity): Long

    @Update
    suspend fun updateReview(review: ReviewEntity)

    @Delete
    suspend fun deleteReview(review: ReviewEntity)

    @Query("DELETE FROM reviews WHERE id = :id")
    suspend fun deleteReviewById(id: Long)

    @Query("SELECT COUNT(*) FROM reviews")
    suspend fun getReviewCount(): Int
}
