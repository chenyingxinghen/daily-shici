package com.example.daily_shici.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.daily_shici.data.local.entity.PoemEntity

/** 搜索三段式排序的中间投影。`tier`：0=标题 1=作者 2=正文。 */
data class SearchTierRow(val poemId: Long, val tier: Int, val weight: Int)

@Dao
interface PoemDao {

    @Query("SELECT * FROM poem WHERE poemId = :poemId")
    suspend fun findById(poemId: Long): PoemEntity?

    @Query("SELECT * FROM poem WHERE poemId IN (:poemIds)")
    suspend fun findByIds(poemIds: List<Long>): List<PoemEntity>

    @Query("SELECT COUNT(*) FROM poem")
    suspend fun countAll(): Int

    /** 「已下载 N 首」——入库过程中实时变化，所以是 Flow 而不是挂起函数。 */
    @Query("SELECT COUNT(*) FROM poem")
    fun observeCount(): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT COUNT(*) FROM poem WHERE packId = :packId")
    suspend fun countByPack(packId: String): Int

    /** 重试下载前先按 packId 清掉残留，保证入库幂等。 */
    @Query("DELETE FROM poem WHERE packId = :packId")
    suspend fun deleteByPack(packId: String)

    /** 某包已入库的全部 poemId。用于写 `collection_member`（合集归属）。 */
    @Query("SELECT poemId FROM poem WHERE packId = :packId")
    suspend fun idsByPack(packId: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PoemEntity>)

    // ---------- 列表（注意：必须显式写 weight DESC, poemId ASC，理由见 PoemEntity 注释） ----------

    @Query("SELECT * FROM poem ORDER BY weight DESC, poemId ASC LIMIT :limit OFFSET :offset")
    suspend fun pageAll(limit: Int, offset: Int): List<PoemEntity>

    @Query(
        "SELECT * FROM poem WHERE period = :period " +
            "ORDER BY weight DESC, poemId ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun pageByPeriod(period: String, limit: Int, offset: Int): List<PoemEntity>

    @Query(
        "SELECT * FROM poem WHERE dynasty = :dynasty " +
            "ORDER BY weight DESC, poemId ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun pageByDynasty(dynasty: String, limit: Int, offset: Int): List<PoemEntity>

    @Query(
        "SELECT * FROM poem WHERE genre = :genre " +
            "ORDER BY weight DESC, poemId ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun pageByGenre(genre: String, limit: Int, offset: Int): List<PoemEntity>

    @Query(
        "SELECT * FROM poem WHERE cipai = :cipai " +
            "ORDER BY weight DESC, poemId ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun pageByCipai(cipai: String, limit: Int, offset: Int): List<PoemEntity>

    /**
     * 合集筛选。**本地按 slug 而不是中文名**：`collection_member.collectionId`
     * 存的是 slug（与服务端 `collection=tangshi-sanbai` 同一个键），
     * 本地中文名只在 `collection.name` 里。这与朝代/体裁相反 —— 那两列在
     * poem 表里直接存中文名，没有 slug。
     */
    @Query(
        "SELECT p.* FROM poem p " +
            "INNER JOIN collection_member m ON m.poemId = p.poemId " +
            "WHERE m.collectionId = :collectionId " +
            "ORDER BY p.weight DESC, p.poemId ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun pageByCollection(collectionId: String, limit: Int, offset: Int): List<PoemEntity>

    @Query(
        "SELECT * FROM poem WHERE author = :author " +
            "ORDER BY weight DESC, poemId ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun pageByAuthor(author: String, limit: Int, offset: Int): List<PoemEntity>

    /** 观测用的全量流：入库过程中 poem 表逐步可查，UI 能立刻看到已导入的部分。 */
    @Query("SELECT * FROM poem ORDER BY weight DESC, poemId ASC LIMIT :limit")
    fun observeTop(limit: Int): kotlinx.coroutines.flow.Flow<List<PoemEntity>>

    // ---------- 离线兜底选诗 ----------

    /**
     * 只取一行，故允许 OFFSET（与随机漫游同一理由：offset 由日期取模得到，
     * 不是单调递增，每天会落到不同位置）。
     */
    @Query("SELECT * FROM poem ORDER BY poemId LIMIT 1 OFFSET :offset")
    suspend fun pickAtOffset(offset: Int): PoemEntity?

    // ---------- 随机漫游（离线口径） ----------

    /**
     * 漫游要在**某个筛选范围内**随机取一首，所以必须有各口径的条数 ——
     * 否则 offset 只能按全表随机，选了「词牌=浣溪沙」却可能给出一首诗。
     *
     * 这些 count 与上面的 `pageBy*` 一一对应（同样的 WHERE），
     * 漫游取法是 `count → 随机 offset → pageBy*(limit=1, offset)`。
     */
    @Query("SELECT COUNT(*) FROM poem WHERE dynasty = :dynasty")
    suspend fun countByDynasty(dynasty: String): Int

    @Query("SELECT COUNT(*) FROM poem WHERE genre = :genre")
    suspend fun countByGenre(genre: String): Int

    @Query("SELECT COUNT(*) FROM poem WHERE cipai = :cipai")
    suspend fun countByCipai(cipai: String): Int

    @Query(
        "SELECT COUNT(*) FROM poem p " +
            "INNER JOIN collection_member m ON m.poemId = p.poemId " +
            "WHERE m.collectionId = :collectionId"
    )
    suspend fun countByCollection(collectionId: String): Int

    // ---------- 搜索 ----------

    /**
     * 三段式实现「标题 > 作者 > 正文」粗排序。
     *
     * **必须写 `MIN(tier)`，不能只写裸列 `tier`。** SQLite 允许 GROUP BY 时选裸列，
     * 但取的是任意一行的值而非最小值 —— 一首诗标题和正文都含「月」时，
     * 若不取 MIN，它可能被归入 tier=2（正文档），「标题优先」就失效了。
     *
     * `weight` 用裸列是安全的：它函数依赖于 poemId（同一首诗只有一个权重）。
     */
    @Query(
        """
        SELECT poemId, MIN(tier) AS tier, weight FROM (
            SELECT poemId, 0 AS tier, weight FROM poem WHERE title   LIKE '%' || :q || '%' ESCAPE '\'
            UNION ALL
            SELECT poemId, 1 AS tier, weight FROM poem WHERE author  LIKE '%' || :q || '%' ESCAPE '\'
            UNION ALL
            SELECT poemId, 2 AS tier, weight FROM poem WHERE content LIKE '%' || :q || '%' ESCAPE '\'
        )
        GROUP BY poemId
        ORDER BY tier ASC, weight DESC, poemId ASC
        LIMIT :limit
        """
    )
    suspend fun searchTiers(q: String, limit: Int): List<SearchTierRow>

    // ---------- 筛选器（离线时由本地推断可用选项） ----------

    @Query("SELECT DISTINCT period FROM poem WHERE period <> '' ORDER BY period")
    suspend fun distinctPeriods(): List<String>

    @Query("SELECT DISTINCT dynasty FROM poem WHERE dynasty <> '' ORDER BY dynasty")
    suspend fun distinctDynasties(): List<String>

    @Query("SELECT DISTINCT genre FROM poem WHERE genre <> '' ORDER BY genre")
    suspend fun distinctGenres(): List<String>

    /** 词牌。离线时用它推断「词牌」维度有哪些取值可选（在线由 `/facets` 给全量）。 */
    @Query("SELECT DISTINCT cipai FROM poem WHERE cipai IS NOT NULL AND cipai <> '' ORDER BY cipai")
    suspend fun distinctCipais(): List<String>

    @Query("SELECT DISTINCT author FROM poem WHERE author <> '' ORDER BY author LIMIT :limit")
    suspend fun distinctAuthors(limit: Int): List<String>
}
