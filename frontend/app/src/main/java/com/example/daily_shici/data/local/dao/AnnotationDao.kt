package com.example.daily_shici.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.daily_shici.data.local.entity.AnnotationEntity

/**
 * 注释缓存（docs/06）。
 *
 * 只有一个写入口 [upsert]，而且是**整行覆盖**：服务端重生成会整体换掉三个字段，
 * 逐字段合并既没意义（它们同时产出）也容易搞出「新译文配旧赏析」这种拼盘。
 */
@Dao
interface AnnotationDao {

    @Query("SELECT * FROM poem_annotation WHERE poemId = :poemId")
    suspend fun find(poemId: Long): AnnotationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AnnotationEntity)

    @Query("SELECT COUNT(*) FROM poem_annotation")
    suspend fun count(): Int
}
