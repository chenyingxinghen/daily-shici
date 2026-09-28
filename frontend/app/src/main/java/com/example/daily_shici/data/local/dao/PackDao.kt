package com.example.daily_shici.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.daily_shici.data.local.entity.CollectionEntity
import com.example.daily_shici.data.local.entity.CollectionMemberEntity
import com.example.daily_shici.data.local.entity.InstalledPackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PackDao {

    @Query("SELECT * FROM installed_pack ORDER BY installedAt DESC")
    fun observeInstalled(): Flow<List<InstalledPackEntity>>

    @Query("SELECT * FROM installed_pack")
    suspend fun installed(): List<InstalledPackEntity>

    @Query("SELECT * FROM installed_pack WHERE packId = :packId")
    suspend fun find(packId: String): InstalledPackEntity?

    @Query("SELECT COUNT(*) FROM installed_pack")
    suspend fun installedCount(): Int

    /** 只在**全部成功之后**才写入，UI 据此判断包是否可用（04 文档 §3.3）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInstalled(entity: InstalledPackEntity)

    @Query("DELETE FROM installed_pack WHERE packId = :packId")
    suspend fun deleteInstalled(packId: String)

    @Query("SELECT * FROM collection ORDER BY sortOrder, name")
    fun observeCollections(): Flow<List<CollectionEntity>>

    /** 离线时推断「合集」维度的可选项。与 [observeCollections] 同一排序，避免两处漂移。 */
    @Query("SELECT * FROM collection ORDER BY sortOrder, name")
    suspend fun collections(): List<CollectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollections(rows: List<CollectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(rows: List<CollectionMemberEntity>)

    /** 卸载包后清理指向已删除诗词的合集成员。 */
    @Query("DELETE FROM collection_member WHERE poemId NOT IN (SELECT poemId FROM poem)")
    suspend fun pruneOrphanMembers()

    /** 详情页「收录于《李太白集》」。 */
    @Query(
        "SELECT c.name FROM collection c " +
            "JOIN collection_member m ON m.collectionId = c.slug " +
            "WHERE m.poemId = :poemId ORDER BY c.sortOrder"
    )
    suspend fun collectionsOf(poemId: Long): List<String>
}
