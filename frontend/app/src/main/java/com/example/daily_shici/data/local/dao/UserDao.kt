package com.example.daily_shici.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.daily_shici.data.local.entity.FavoriteEntity
import com.example.daily_shici.data.local.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 收藏与历史。走**用户库**（shici_user.db），永不清空（04 文档 §2.4）。
 * 收藏/取消收藏只写本地，不调任何接口。
 */
@Dao
interface UserDao {

    @Query("SELECT * FROM favorite ORDER BY createdAt DESC")
    fun observeFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT poemId FROM favorite")
    fun observeFavoriteIds(): Flow<List<Long>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE poemId = :poemId)")
    fun observeIsFavorite(poemId: Long): Flow<Boolean>

    @Query("SELECT COUNT(*) FROM favorite")
    fun observeFavoriteCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(entity: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE poemId = :poemId")
    suspend fun deleteFavorite(poemId: Long)

    @Query("SELECT * FROM reading_history ORDER BY lastReadAt DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM reading_history WHERE poemId = :poemId")
    suspend fun findHistory(poemId: Long): HistoryEntity?

    @Query("SELECT COUNT(*) FROM reading_history")
    fun observeHistoryCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(entity: HistoryEntity)

    @Query("DELETE FROM reading_history WHERE poemId = :poemId")
    suspend fun deleteHistory(poemId: Long)

    @Query("DELETE FROM reading_history")
    suspend fun clearHistory()

    /** 通知发送前的检查：今天是否已经读过。 */
    @Query("SELECT COUNT(*) FROM reading_history WHERE lastReadAt >= :sinceMillis")
    suspend fun countReadSince(sinceMillis: Long): Int
}
