package com.example.daily_shici.data.repository

import android.content.Context
import com.example.daily_shici.data.local.dao.PackDao
import com.example.daily_shici.data.local.dao.PoemDao
import com.example.daily_shici.data.local.entity.InstalledPackEntity
import com.example.daily_shici.data.pack.PackInstaller
import com.example.daily_shici.data.pack.BuiltinPackIndexDto
import com.example.daily_shici.data.pack.BuiltinPackManifestDto
import com.example.daily_shici.data.pack.PackProgress
import com.example.daily_shici.data.prefs.SettingsRepository
import com.example.daily_shici.data.remote.ShiciApi
import com.example.daily_shici.domain.model.PackInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** 包清单 + 是否来自网络。 [offline] 为真时 UI 要说明「仅显示已装」。 */
data class PackCatalog(val packs: List<PackInfo>, val offline: Boolean)

@Singleton
class PackRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packDao: PackDao,
    private val poemDao: PoemDao,
    private val api: ShiciApi,
    private val installer: PackInstaller,
    private val settings: SettingsRepository,
    private val jsonCodec: Json,
) {

    val installed: Flow<List<InstalledPackEntity>> = packDao.observeInstalled()
    val progress: Flow<PackProgress?> = installer.progress

    /** 首次启动把内置数据包灌进本地库。幂等，可在每次冷启动调用。 */
    suspend fun ensureBuiltinsLoaded() {
        installer.importBuiltins()
    }

    /**
     * 包清单。**必须网络** —— 失败时退化为用 `installed_pack` 渲染「已装」状态，
     * 而不是抛错让页面空白。这样断网时用户仍然知道自己装了什么、能卸载什么。
     */
    suspend fun catalog(): PackCatalog {
        val installedMap = packDao.installed().associateBy { it.packId }

        // 契约里 /packs 无查询参数：客户端存下 catalog_version 自行比对
        // （docs/02 §3.9「客户端存下它，下次请求带 If-None-Match 或直接比对」）。
        val remote = runCatching { api.packs() }.getOrNull()
        if (remote == null) {
            return PackCatalog(packs = withBuiltin(emptyList(), installedMap), offline = true)
        }
        settings.setCatalogVersion(remote.catalogVersion)
        val mapped = remote.packs.map { it.toDomain(installedMap[it.packId]?.version) }
        return PackCatalog(packs = withBuiltin(mapped, installedMap), offline = false)
    }

    /**
     * 内置包**永远出现在清单里**（且不可卸载），即使服务端清单没返回它们。
     * 它们的存在由 APK 内的 `builtin_packs.json` 决定，而非服务端 —— 断网时也必须可见。
     */
    private fun withBuiltin(remote: List<PackInfo>, installed: Map<String, InstalledPackEntity>): List<PackInfo> {
        val declared = builtinManifests()
        if (declared.isEmpty()) return remote
        val present = remote.map { it.packId }.toSet()
        val builtinEntries = declared
            .filterNot { it.packId in present }
            .map { manifest ->
                val record = installed[manifest.packId]
                PackInfo(
                    packId = manifest.packId,
                    name = manifest.name.ifBlank { manifest.packId },
                    description = "随应用内置，无需下载",
                    poemCount = record?.poemCount ?: manifest.poemCount,
                    bytesGzip = manifest.bytesGzip,
                    builtin = true,
                    installedVersion = record?.version,
                    latestVersion = manifest.version,
                )
            }
        return builtinEntries + remote
    }

    /** 读取 APK 内的内置包清单。读不到就返回空 —— 内置包读取失败不应让包清单页整体挂掉。 */
    private fun builtinManifests(): List<BuiltinPackManifestDto> {
        val raw = runCatching {
            context.assets.open(PackInstaller.BUILTIN_INDEX_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()

        return runCatching<List<BuiltinPackManifestDto>> {
            jsonCodec.decodeFromString(BuiltinPackIndexDto.serializer(), raw).builtin
        }.getOrElse { emptyList() }
    }

    suspend fun install(pack: PackInfo, url: String, sha256: String): Result<Int> =
        installer.install(
            packId = pack.packId,
            url = url,
            expectedSha256 = sha256,
            expectedPoems = pack.poemCount,
            version = pack.latestVersion,
            bytesGzip = pack.bytesGzip,
        )

    suspend fun uninstall(packId: String) = installer.uninstall(packId)

    suspend fun cancel(packId: String) = installer.cancel(packId)

    suspend fun downloadedCountOf(packId: String): Int = poemDao.countByPack(packId)

    /**
     * 命中存在但本地为 0 时，指出「装哪个包可能有帮助」。
     *
     * 这里只做粗粒度推断：本地一首都没有 → 用户大概还没装任何非内置包，
     * 于是把清单里所有未安装的包列出来。够用且不会误导（不猜具体是哪个包）。
     */
    suspend fun suggestedPacksForMissingContent(): List<String> =
        runCatching { catalog() }
            .getOrNull()
            ?.packs
            ?.filter { !it.isInstalled }
            ?.map { it.packId }
            .orEmpty()

    suspend fun installedPoemTotal(): Int = poemDao.countAll()
}
