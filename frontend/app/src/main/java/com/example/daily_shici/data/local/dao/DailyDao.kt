package com.example.daily_shici.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.daily_shici.data.local.entity.DailyCacheEntity

@Dao
interface DailyDao {

    @Query("SELECT * FROM daily_cache WHERE date = :date")
    suspend fun find(date: String): DailyCacheEntity?

    /** 预取水位：只补缺口，避免每次重复拉 30 天。返回 null 表示缓存为空。 */
    @Query("SELECT MAX(date) FROM daily_cache")
    suspend fun maxDate(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DailyCacheEntity>)

    /** 缓存上限 365 天，超出删最旧，防止无限增长。 */
    @Query("DELETE FROM daily_cache WHERE date < :beforeDate")
    suspend fun pruneBefore(beforeDate: String)

    @Query("SELECT COUNT(*) FROM daily_cache")
    suspend fun count(): Int
}
