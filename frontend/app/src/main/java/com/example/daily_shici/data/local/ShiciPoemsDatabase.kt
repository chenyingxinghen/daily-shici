package com.example.daily_shici.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.daily_shici.data.local.dao.AnnotationDao
import com.example.daily_shici.data.local.dao.DailyDao
import com.example.daily_shici.data.local.dao.FacetsDao
import com.example.daily_shici.data.local.dao.PackDao
import com.example.daily_shici.data.local.dao.PoemDao
import com.example.daily_shici.data.local.entity.AnnotationEntity
import com.example.daily_shici.data.local.entity.CollectionEntity
import com.example.daily_shici.data.local.entity.CollectionMemberEntity
import com.example.daily_shici.data.local.entity.DailyCacheEntity
import com.example.daily_shici.data.local.entity.FacetsCacheEntity
import com.example.daily_shici.data.local.entity.InstalledPackEntity
import com.example.daily_shici.data.local.entity.PoemEntity

/**
 * **诗词库**（shici_poems.db）—— 可破坏性重建。
 *
 * 里面的一切都能重新下载：schema 变更时清库 + 重导 seed 包（内置，秒级）即可，
 * 因此 v1 不写任何 `Migration`。
 *
 * v2 加了 `poem_annotation`（注疏缓存）；v3 给它补了创作背景 / 文献出处 / 来源许可
 * 四列 —— 需求纠正后注疏改为**从网络导入的真人内容**，字段结构随之变化。
 * 两次都写了**真实迁移**而不是依赖破坏性重建：加表、加列都是最简单的迁移
 * （纯 DDL，无数据变换），而破坏性重建的代价是用户已下载的几万个数据包全部作废 ——
 * 为了省几行代码让用户重下几百 MB，不划算。
 */
@Database(
    entities = [
        PoemEntity::class,
        CollectionEntity::class,
        CollectionMemberEntity::class,
        InstalledPackEntity::class,
        DailyCacheEntity::class,
        FacetsCacheEntity::class,
        AnnotationEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class ShiciPoemsDatabase : RoomDatabase() {
    abstract fun poemDao(): PoemDao
    abstract fun packDao(): PackDao
    abstract fun dailyDao(): DailyDao
    abstract fun facetsDao(): FacetsDao
    abstract fun annotationDao(): AnnotationDao

    companion object {
        const val NAME = "shici_poems.db"
    }
}
