package com.example.daily_shici.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.daily_shici.data.local.entity.FacetsCacheEntity

/**
 * 筛选器元数据缓存（单行表）。它存在的唯一理由是：
 * 本地查询命中 0 条时，需要知道「服务端统计下这里本该有多少条」，
 * 才能区分「真的没有」与「该内容未下载」。
 */
@Dao
interface FacetsDao {

    @Query("SELECT * FROM facets_cache WHERE id = 1")
    suspend fun get(): FacetsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FacetsCacheEntity)
}
