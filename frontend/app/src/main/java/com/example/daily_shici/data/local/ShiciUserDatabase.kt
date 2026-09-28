package com.example.daily_shici.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.daily_shici.data.local.dao.UserDao
import com.example.daily_shici.data.local.entity.FavoriteEntity
import com.example.daily_shici.data.local.entity.HistoryEntity

/**
 * **用户库**（shici_user.db）—— 永不清空。
 *
 * 这是整个设计里唯一一处「为未来付费」的结构决策：把收藏与历史从诗词库拆出来，
 * 让它们不受诗词库 schema 变更（`fallbackToDestructiveMigration`）的影响。
 * 代价是多一个 Database 类，收益是用户收藏永远不会因为诗词库升级而丢失。
 *
 * 因此这个库**必须**写真实 `Migration`，不能沿用破坏性重建。
 */
@Database(
    entities = [FavoriteEntity::class, HistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ShiciUserDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao

    companion object {
        const val NAME = "shici_user.db"
    }
}
