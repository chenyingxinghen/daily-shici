package com.example.daily_shici.data.repository

import com.example.daily_shici.data.local.dao.PoemDao
import com.example.daily_shici.data.local.dao.UserDao
import com.example.daily_shici.data.local.entity.PoemEntity
import com.example.daily_shici.domain.model.LibraryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 收藏与历史。**纯本地**，收藏/取消收藏只写 favorite 表，不调任何接口。
 *
 * 跨库合并在这里做：favorite 在 shici_user.db，poem 在 shici_poems.db，
 * Room 无法跨库 JOIN，所以先取 id 再回查诗词表 —— 在 Kotlin 侧完成 LEFT JOIN 语义。
 * 查不到就是「未下载」，显示灰态，**不删除收藏记录**。
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val userDao: UserDao,
    private val poemDao: PoemDao,
) {

    val favorites: Flow<List<LibraryEntry>> = userDao.observeFavorites().map { favorites ->
        val ids = favorites.map { it.poemId }
        val poems = if (ids.isEmpty()) emptyMap() else poemDao.findByIds(ids).associateBy { it.poemId }
        favorites.map { favorite ->
            poems[favorite.poemId].toLibraryEntry(
                poemId = favorite.poemId,
                timestamp = favorite.createdAt,
            )
        }
    }

    val history: Flow<List<LibraryEntry>> = userDao.observeHistory(HISTORY_LIMIT).map { records ->
        val ids = records.map { it.poemId }
        val poems = if (ids.isEmpty()) emptyMap() else poemDao.findByIds(ids).associateBy { it.poemId }
        records.map { record ->
            poems[record.poemId].toLibraryEntry(
                poemId = record.poemId,
                timestamp = record.lastReadAt,
                readCount = record.readCount,
            )
        }
    }

    val favoriteCount: Flow<Int> = userDao.observeFavoriteCount()

    val historyCount: Flow<Int> = userDao.observeHistoryCount()

    fun observeIsFavorite(poemId: Long): Flow<Boolean> = userDao.observeIsFavorite(poemId)

    /** 详情页收藏按钮：纯本地写入，无网络往返，因此不需要 loading 态。 */
    suspend fun setFavorite(poemId: Long, favorite: Boolean) {
        if (favorite) {
            userDao.upsertFavorite(favoriteOf(poemId, System.currentTimeMillis()))
        } else {
            userDao.deleteFavorite(poemId)
        }
    }

    suspend fun toggleFavorite(poemId: Long): Boolean {
        val nowFavorite = !userDao.observeIsFavorite(poemId).first()
        setFavorite(poemId, nowFavorite)
        return nowFavorite
    }

    /** 打开详情页时记一次阅读。累加 readCount，保留首次之外的次数。 */
    suspend fun markRead(poemId: Long) {
        val existing = userDao.findHistory(poemId)
        userDao.upsertHistory(bumpedHistory(existing, poemId, System.currentTimeMillis()))
    }

    suspend fun removeHistory(poemId: Long) = userDao.deleteHistory(poemId)

    suspend fun clearHistory() = userDao.clearHistory()

    /** 今天是否已读过 —— 每日提醒发送前的检查，读过就不打扰。 */
    suspend fun readToday(nowMillis: Long): Boolean = userDao.countReadSince(nowMillis) > 0

    companion object {
        private const val HISTORY_LIMIT = 100
    }
}

/**
 * 诗词行 → 列表项。 [PoemEntity] 为 null 表示本地没有这首（包被卸载或来自在线搜索）。
 */
private fun PoemEntity?.toLibraryEntry(
    poemId: Long,
    timestamp: Long,
    readCount: Int = 0,
) = LibraryEntry(
    poemId = poemId,
    title = this?.title.orEmpty(),
    excerpt = this?.excerpt.orEmpty(),
    author = this?.author.orEmpty(),
    dynasty = this?.dynasty.orEmpty(),
    isDownloaded = this != null,
    timestamp = timestamp,
    readCount = readCount,
)
