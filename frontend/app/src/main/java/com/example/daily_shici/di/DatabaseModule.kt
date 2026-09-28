package com.example.daily_shici.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.daily_shici.data.local.ShiciPoemsDatabase
import com.example.daily_shici.data.local.ShiciUserDatabase
import com.example.daily_shici.data.local.dao.AnnotationDao
import com.example.daily_shici.data.local.dao.DailyDao
import com.example.daily_shici.data.local.dao.FacetsDao
import com.example.daily_shici.data.local.dao.PackDao
import com.example.daily_shici.data.local.dao.PoemDao
import com.example.daily_shici.data.local.dao.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * v1 → v2：新增 `poem_annotation`（注释缓存，docs/06）。
 *
 * **必须与 `AnnotationEntity` 的字段逐字对齐** —— Room 的 schema 校验在迁移后会
 * 比对实际表结构与预期，对不上会在首次打开时崩掉（而且信息很含糊）。
 * 默认值也要写全：老版本升级上来的库是空表，但结构必须一致。
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `poem_annotation` (
                `poemId` INTEGER NOT NULL,
                `annotation` TEXT,
                `translation` TEXT,
                `appreciation` TEXT,
                `sourcesJson` TEXT,
                `model` TEXT,
                `generatedAt` TEXT,
                `cachedAt` INTEGER NOT NULL,
                PRIMARY KEY(`poemId`)
            )
            """.trimIndent()
        )
    }
}

/**
 * v2 → v3：`poem_annotation` 换结构（创作背景 / 文献出处 / 来源 / 许可进来，
 * `model` / `generatedAt` 出去）。
 *
 * 需求纠正后注疏从「LLM 生成」改为「从网络导入的真人内容」（`docs/06 §1`），
 * 字段结构随之变化：`model` / `generatedAt` 是那条被否决的生成路径留下的，没有意义。
 *
 * ⚠️ **为什么是整表重建而不是 `ALTER TABLE ADD COLUMN`。**
 * Room 在迁移后会把**实际表结构**与预期 schema 严格比对（`TableInfo.equals`），
 * **多一列少一列都会崩在首次打开**，且报错信息很含糊
 * （「Migration didn't properly handle …」）。
 * 只加列的话，v2 遗留的 `model` / `generatedAt` 还在表里 → 校验失败。
 * 而 SQLite 没有 `DROP COLUMN`（3.35 之前），所以只能走「建新表 → 拷数据 → 删旧表 → 改名」。
 *
 * 老缓存行里的注疏内容本身已经作废（是模型生成的，与新的真人注疏不是一回事），
 * 但**表要保住并尽量搬运**：几列能复用的文本搬过来，用户在离线状态下读过的注疏
 * 不该因为我们换数据源就凭空消失。
 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `poem_annotation_new` (
                `poemId` INTEGER NOT NULL,
                `annotation` TEXT,
                `translation` TEXT,
                `appreciation` TEXT,
                `background` TEXT,
                `citation` TEXT,
                `sourcesJson` TEXT,
                `source` TEXT,
                `license` TEXT,
                `cachedAt` INTEGER NOT NULL,
                PRIMARY KEY(`poemId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO `poem_annotation_new`
                (`poemId`, `annotation`, `translation`, `appreciation`, `sourcesJson`, `cachedAt`)
            SELECT `poemId`, `annotation`, `translation`, `appreciation`, `sourcesJson`, `cachedAt`
            FROM `poem_annotation`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `poem_annotation`")
        db.execSQL("ALTER TABLE `poem_annotation_new` RENAME TO `poem_annotation`")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * 诗词库：**允许破坏性重建**。里面的东西全部可以重新下载
     * （seed 内置秒级重导，其余按包重下）。
     *
     * 但「允许」不等于「优先」：能写迁移就写迁移，见上面两个 `MIGRATION_*`。
     * `fallbackToDestructiveMigration` 现在是最后一道保险，不再承担常规升级。
     */
    @Provides
    @Singleton
    fun providePoemsDatabase(@ApplicationContext context: Context): ShiciPoemsDatabase =
        Room.databaseBuilder(context, ShiciPoemsDatabase::class.java, ShiciPoemsDatabase.NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    /**
     * 用户库：**绝不破坏性重建**。收藏与历史不可重建，
     * schema 变更时必须写真实 Migration（当前 version = 1，尚无迁移）。
     */
    @Provides
    @Singleton
    fun provideUserDatabase(@ApplicationContext context: Context): ShiciUserDatabase =
        Room.databaseBuilder(context, ShiciUserDatabase::class.java, ShiciUserDatabase.NAME)
            .build()

    @Provides
    fun providePoemDao(database: ShiciPoemsDatabase): PoemDao = database.poemDao()

    @Provides
    fun providePackDao(database: ShiciPoemsDatabase): PackDao = database.packDao()

    @Provides
    fun provideDailyDao(database: ShiciPoemsDatabase): DailyDao = database.dailyDao()

    @Provides
    fun provideFacetsDao(database: ShiciPoemsDatabase): FacetsDao = database.facetsDao()

    @Provides
    fun provideAnnotationDao(database: ShiciPoemsDatabase): AnnotationDao = database.annotationDao()

    @Provides
    fun provideUserDao(database: ShiciUserDatabase): UserDao = database.userDao()
}
