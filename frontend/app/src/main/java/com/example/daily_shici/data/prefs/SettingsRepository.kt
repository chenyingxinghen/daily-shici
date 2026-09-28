package com.example.daily_shici.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** DataStore 委托必须是顶层属性，故放在类外。 */
private val Context.shiciSettings: DataStore<Preferences> by preferencesDataStore(name = "shici_settings")

/**
 * 键值设置。只放**不适合进 Room** 的东西：水位、偏移量、开关。
 *
 * 特别是包下载的断点偏移量：它属于「下载过程的瞬时状态」，
 * 放进 Room 会污染诗词库 schema，而它又必须跨进程重启存活（否则续传失效）。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val CATALOG_VERSION = intPreferencesKey("catalog_version")
        val LAST_DAILY_REQUEST_DATE = stringPreferencesKey("last_daily_request_date")
        val NOTIFY_ENABLED = booleanPreferencesKey("notify_enabled")
        val NOTIFY_HOUR = intPreferencesKey("notify_hour")
        val NOTIFY_MINUTE = intPreferencesKey("notify_minute")
        val NICKNAME = stringPreferencesKey("nickname")
        val RECENT_QUERIES = stringPreferencesKey("recent_queries")
    }

    private fun offsetKey(packId: String) = longPreferencesKey("pack_offset_$packId")

    private val prefs: Flow<Preferences> get() = context.shiciSettings.data

    val catalogVersion: Flow<Int> = prefs.map { it[Keys.CATALOG_VERSION] ?: 0 }

    suspend fun setCatalogVersion(version: Int) {
        context.shiciSettings.edit { it[Keys.CATALOG_VERSION] = version }
    }

    val lastDailyRequestDate: Flow<String?> = prefs.map { it[Keys.LAST_DAILY_REQUEST_DATE] }

    suspend fun setLastDailyRequestDate(date: String) {
        context.shiciSettings.edit { it[Keys.LAST_DAILY_REQUEST_DATE] = date }
    }

    /** 断点续传偏移量。0 表示没有未完成的下载。 */
    suspend fun downloadOffset(packId: String): Long =
        prefs.first()[offsetKey(packId)] ?: 0L

    suspend fun setDownloadOffset(packId: String, bytes: Long) {
        context.shiciSettings.edit { it[offsetKey(packId)] = bytes }
    }

    suspend fun clearDownloadOffset(packId: String) {
        context.shiciSettings.edit { it.remove(offsetKey(packId)) }
    }

    val notifyEnabled: Flow<Boolean> = prefs.map { it[Keys.NOTIFY_ENABLED] ?: true }

    suspend fun setNotifyEnabled(enabled: Boolean) {
        context.shiciSettings.edit { it[Keys.NOTIFY_ENABLED] = enabled }
    }

    /** 每日提醒时刻，默认 08:00。 */
    val notifyTime: Flow<Pair<Int, Int>> = prefs.map {
        (it[Keys.NOTIFY_HOUR] ?: 8) to (it[Keys.NOTIFY_MINUTE] ?: 0)
    }

    suspend fun setNotifyTime(hour: Int, minute: Int) {
        context.shiciSettings.edit {
            it[Keys.NOTIFY_HOUR] = hour
            it[Keys.NOTIFY_MINUTE] = minute
        }
    }

    /** 无账号体系，昵称只是本地一个显示名（设计稿里写作「诗友」）。 */
    val nickname: Flow<String> = prefs.map { it[Keys.NICKNAME] ?: "诗友" }

    suspend fun setNickname(name: String) {
        context.shiciSettings.edit { it[Keys.NICKNAME] = name.ifBlank { "诗友" } }
    }

    /**
     * 搜索历史。用「话题」这类纯显示用的小列表，不值得单开一张 Room 表 ——
     * 它没有查询需求，只有「最近 N 条、去重、置顶」。
     *
     * 用 `\u0001` 作分隔符而不是逗号：用户可能搜「李白,杜甫」这种带逗号的词。
     */
    val recentQueries: Flow<List<String>> = prefs.map { prefs ->
        prefs[Keys.RECENT_QUERIES]
            ?.split(RECORD_SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    suspend fun addRecentQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        context.shiciSettings.edit { prefs ->
            val existing = prefs[Keys.RECENT_QUERIES]
                ?.split(RECORD_SEPARATOR)
                ?.filter { it.isNotBlank() }
                .orEmpty()
            val updated = (listOf(trimmed) + existing.filterNot { it == trimmed })
                .take(MAX_RECENT_QUERIES)
            prefs[Keys.RECENT_QUERIES] = updated.joinToString(RECORD_SEPARATOR)
        }
    }

    suspend fun removeRecentQuery(query: String) {
        context.shiciSettings.edit { prefs ->
            val existing = prefs[Keys.RECENT_QUERIES]
                ?.split(RECORD_SEPARATOR)
                ?.filter { it.isNotBlank() }
                .orEmpty()
            prefs[Keys.RECENT_QUERIES] =
                existing.filterNot { it == query }.joinToString(RECORD_SEPARATOR)
        }
    }

    suspend fun clearRecentQueries() {
        context.shiciSettings.edit { it.remove(Keys.RECENT_QUERIES) }
    }

    private companion object {
        const val RECORD_SEPARATOR = "\u0001"
        const val MAX_RECENT_QUERIES = 10
    }
}
