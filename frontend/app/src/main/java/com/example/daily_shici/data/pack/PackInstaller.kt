package com.example.daily_shici.data.pack

import android.content.Context
import com.example.daily_shici.data.local.dao.PackDao
import com.example.daily_shici.data.local.dao.PoemDao
import com.example.daily_shici.data.local.entity.CollectionEntity
import com.example.daily_shici.data.local.entity.CollectionMemberEntity
import com.example.daily_shici.data.local.entity.InstalledPackEntity
import com.example.daily_shici.data.local.entity.PackPoemRow
import com.example.daily_shici.data.prefs.SettingsRepository
import com.example.daily_shici.data.remote.dto.PackPoemDto
import com.example.daily_shici.data.repository.deriveCharCount
import com.example.daily_shici.data.repository.deriveExcerpt
import com.example.daily_shici.data.repository.deriveLineCount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 数据包处理阶段。UI 据此显示「下载中 / 校验中 / 入库中」。 */
enum class PackPhase { DOWNLOADING, VERIFYING, IMPORTING, DONE, FAILED }

data class PackProgress(
    val packId: String,
    val phase: PackPhase,
    val bytesRead: Long = 0,
    val totalBytes: Long = 0,
    val poemsImported: Int = 0,
    val expectedPoems: Int = 0,
    val message: String? = null,
) {
    val fraction: Float
        get() = when {
            phase == PackPhase.IMPORTING && expectedPoems > 0 ->
                (poemsImported.toFloat() / expectedPoems).coerceIn(0f, 1f)
            totalBytes > 0 -> (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> 0f
        }
}

/**
 * 数据包下载 · 校验 · 流式入库。内置 seed 包与网络下载包**走同一条代码路径**，
 * 这样 seed 就成了下载链路的日常验证载体，而不是一条只跑一次的旁路。
 */
@Singleton
class PackInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val poemDao: PoemDao,
    private val packDao: PackDao,
    private val settings: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {

    private val _progress = MutableStateFlow<PackProgress?>(null)
    val progress: StateFlow<PackProgress?> = _progress.asStateFlow()

    /**
     * 导入全部内置数据包（`assets/builtin_packs.json` 驱动）。
     *
     * 遍历索引而不是硬编码包列表 —— 新增内置包只需重跑 ETL，客户端一行不用改。
     * **幂等**：已装且版本不低的包直接跳过。
     *
     * 每个包导入完成后才写 `installed_pack`（UI 据此判断包是否可用），
     * 并顺带登记合集归属，这样详情页能显示「收录于《唐诗三百首》」。
     */
    suspend fun importBuiltins(force: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val manifests = readBuiltinIndex()
            if (manifests.isEmpty()) error("内置包索引为空：assets/$BUILTIN_INDEX_ASSET")

            var total = 0
            for (manifest in manifests) {
                val installed = packDao.find(manifest.packId)
                if (!force && installed != null && installed.version >= manifest.version) {
                    total += installed.poemCount
                    continue
                }

                if (force) {
                    poemDao.deleteByPack(manifest.packId)
                    packDao.deleteInstalled(manifest.packId)
                }

                _progress.value = PackProgress(
                    packId = manifest.packId,
                    phase = PackPhase.IMPORTING,
                    expectedPoems = manifest.poemCount,
                )

                // 边解析边算 sha256：既校验了包体完整性，又不必把 150 KB 整个读进内存。
                val digest = MessageDigest.getInstance("SHA-256")
                val inserted = context.assets.open(manifest.assetName).use { raw ->
                    DigestInputStream(raw, digest).use { stream ->
                        importLines(manifest.packId, stream, manifest.poemCount)
                    }
                }

                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (manifest.sha256.isNotBlank() && !actual.equals(manifest.sha256, ignoreCase = true)) {
                    // 包体损坏时不能留下半截数据，否则用户看到的是「缺了几首」而不是报错
                    poemDao.deleteByPack(manifest.packId)
                    error("内置包 ${manifest.packId} 校验失败：期望 ${manifest.sha256.take(16)}…，实际 ${actual.take(16)}…")
                }
                if (manifest.poemCount > 0 && inserted != manifest.poemCount) {
                    error("内置包 ${manifest.packId} 条数不符：manifest 声明 ${manifest.poemCount}，实际入库 $inserted")
                }

                packDao.upsertInstalled(
                    InstalledPackEntity(
                        packId = manifest.packId,
                        version = manifest.version,
                        poemCount = inserted,
                        sha256 = actual,
                        installedAt = System.currentTimeMillis(),
                        bytesGzip = manifest.bytesGzip,
                    )
                )
                registerCollections(manifest)

                _progress.value = PackProgress(
                    packId = manifest.packId,
                    phase = PackPhase.DONE,
                    poemsImported = inserted,
                    expectedPoems = manifest.poemCount,
                )
                total += inserted
            }
            total
        }.onFailure { error ->
            _progress.value = PackProgress(
                packId = BUILTIN_INDEX_ASSET,
                phase = PackPhase.FAILED,
                message = error.message,
            )
        }
    }

    /** 登记该包声明的合集与成员，供详情页展示「收录于《…》」。 */
    private suspend fun registerCollections(manifest: BuiltinPackManifestDto) {
        if (manifest.collections.isEmpty()) return
        packDao.upsertCollections(
            manifest.collections.map { collection ->
                CollectionEntity(
                    slug = collection.slug,
                    name = collection.name.ifBlank { collection.slug },
                    packId = manifest.packId,
                    builtin = true,
                    sortOrder = collection.sortOrder,
                )
            }
        )
        runCatching { packDao.pruneOrphanMembers() }
        val poemIds = poemDao.idsByPack(manifest.packId)
        if (poemIds.isEmpty()) return
        // 分块插入：366/280 条一次插没问题，但全量包会有 25 万条，统一走同一路径
        poemIds.chunked(2_000).forEach { chunk ->
            packDao.upsertMembers(
                manifest.collections.flatMap { collection ->
                    chunk.map { CollectionMemberEntity(collection.slug, it) }
                }
            )
        }
    }

    private fun readBuiltinIndex(): List<BuiltinPackManifestDto> {
        val json = context.assets.open(BUILTIN_INDEX_ASSET).bufferedReader().use { it.readText() }
        return this.json.decodeFromString(BuiltinPackIndexDto.serializer(), json).builtin
    }

    /**
     * 下载并安装一个数据包。
     *
     * 断点续传：包是不可变的，所以用 HTTP `Range` 续传，已下载字节数记在 DataStore。
     * 服务端返回 200（不支持 Range）则从头重下并清除偏移记录。
     * **不存 ETag 做条件请求** —— 包不可变，sha256 已是更强的校验。
     */
    suspend fun install(
        packId: String,
        url: String,
        expectedSha256: String,
        expectedPoems: Int,
        version: Int,
        bytesGzip: Long,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val partFile = File(context.cacheDir, "packs/$packId.part").apply {
                parentFile?.mkdirs()
            }
            val resumeFrom = if (partFile.exists()) settings.downloadOffset(packId) else 0L
            if (resumeFrom == 0L && partFile.exists()) partFile.delete()

            download(url, partFile, resumeFrom, packId, bytesGzip)

            _progress.value = PackProgress(
                packId = packId, phase = PackPhase.VERIFYING,
                bytesRead = partFile.length(), totalBytes = partFile.length(),
                expectedPoems = expectedPoems,
            )
            val actual = sha256Of(partFile)
            if (expectedSha256.isNotBlank() && !actual.equals(expectedSha256, ignoreCase = true)) {
                partFile.delete()
                settings.clearDownloadOffset(packId)
                error("sha256 校验失败：期望 $expectedSha256，实际 $actual")
            }

            // 重试前先按 packId 清掉上次的残留，保证入库幂等。
            poemDao.deleteByPack(packId)

            val inserted = GZIPInputStream(FileInputStream(partFile)).use { stream ->
                importLines(packId, stream, expectedPoems)
            }

            packDao.upsertInstalled(
                InstalledPackEntity(
                    packId = packId,
                    version = version,
                    poemCount = inserted,
                    sha256 = actual,
                    installedAt = System.currentTimeMillis(),
                    bytesGzip = partFile.length(),
                )
            )
            partFile.delete()
            settings.clearDownloadOffset(packId)

            _progress.value = PackProgress(
                packId = packId, phase = PackPhase.DONE,
                poemsImported = inserted, expectedPoems = inserted,
            )
            inserted
        }.onFailure { error ->
            _progress.value = PackProgress(
                packId = packId, phase = PackPhase.FAILED, message = error.message,
            )
        }
    }

    private suspend fun download(
        url: String,
        target: File,
        resumeFrom: Long,
        packId: String,
        declaredBytes: Long,
    ) {
        val request = Request.Builder()
            .url(url)
            .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")

            // 206 = 续传成功；200 说明服务端忽略了 Range，必须从头写而不是追加。
            val appending = response.code == 206 && resumeFrom > 0
            if (!appending && target.exists()) target.delete()

            val total = declaredBytes.takeIf { it > 0 }
                ?: (response.body?.contentLength() ?: 0L) + (if (appending) resumeFrom else 0L)

            var written = if (appending) resumeFrom else 0L
            var lastPersisted = written

            response.body?.byteStream()?.use { input ->
                // 必须用 FileOutputStream(file, append=true) 而不是 file.outputStream()——
                // 后者等价于 append=false，会把已下载的部分**直接截断**，
                // 续传就变成了静默重下（甚至写出带空洞的稀疏文件）。
                FileOutputStream(target, appending).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read

                        // 每 1 MB 落一次偏移量，避免每读一个 buffer 就写一次 DataStore。
                        if (written - lastPersisted >= PROGRESS_PERSIST_INTERVAL) {
                            output.flush()
                            settings.setDownloadOffset(packId, written)
                            lastPersisted = written
                        }
                        _progress.value = PackProgress(
                            packId = packId, phase = PackPhase.DOWNLOADING,
                            bytesRead = written, totalBytes = total,
                        )
                    }
                    output.flush()
                }
            }
            settings.setDownloadOffset(packId, written)
        }
    }

    /**
     * 逐行解析并批量入库。
     *
     * 批大小 2000：Room 的 insertAll 在单事务内执行，2000 条约 700 KB，
     * 是内存与事务开销的平衡点。整包一个事务会耗尽 WAL。
     *
     * 进度按**已解析行数**算，比按字节算更准（gzip 解压速率不均）。
     */
    private suspend fun importLines(packId: String, stream: InputStream, expectedPoems: Int): Int {
        var imported = 0
        val batch = ArrayList<com.example.daily_shici.data.local.entity.PoemEntity>(BATCH_SIZE)

        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val dto = runCatching { json.decodeFromString(PackPoemDto.serializer(), line) }
                    .getOrElse { continue } // 坏行跳过，不让一行毁掉整包

                batch += dto.toRow().toEntity(
                    packId = packId,
                    derivedExcerpt = deriveExcerpt(dto.content),
                    derivedLineCount = deriveLineCount(dto.content),
                    derivedCharCount = deriveCharCount(dto.content),
                )
                if (batch.size >= BATCH_SIZE) {
                    poemDao.insertAll(batch)
                    imported += batch.size
                    batch.clear()
                    _progress.value = PackProgress(
                        packId = packId, phase = PackPhase.IMPORTING,
                        poemsImported = imported, expectedPoems = expectedPoems,
                    )
                }
            }
        }
        if (batch.isNotEmpty()) {
            poemDao.insertAll(batch)
            imported += batch.size
        }
        return imported
    }

    /**
     * 卸载数据包。
     *
     * 顺序不可颠倒：先删诗词行，再清理孤儿合集成员，最后删安装记录。
     * 反过来的话会出现「文件/记录没了但数据还在」的中间态。
     * **收藏与历史不动** —— 它们的诗会变成「未下载」灰态，重装后自动恢复。
     *
     * 内置包（`builtin_packs.json` 里声明过的）**不可卸载**：它们是
     * 「新装用户第一次打开就有东西可读」的保证。
     */
    suspend fun uninstall(packId: String) {
        if (isBuiltin(packId)) return
        withContext(Dispatchers.IO) {
            poemDao.deleteByPack(packId)
            packDao.pruneOrphanMembers()
            packDao.deleteInstalled(packId)
            settings.clearDownloadOffset(packId)
        }
    }

    /** 是否内置包。索引读取有缓存，不会每次 uninstall 都解一遍 JSON。 */
    private fun isBuiltin(packId: String): Boolean =
        runCatching { builtinIdsCache }.getOrNull()?.contains(packId) ?: false

    private val builtinIdsCache: Set<String> by lazy {
        readBuiltinIndex().map { it.packId }.toSet()
    }

    /** 用户取消下载：停任务 → 按 packId 删已入库行 → 删 .part 文件。 */
    suspend fun cancel(packId: String) {
        withContext(Dispatchers.IO) {
            poemDao.deleteByPack(packId)
            packDao.deleteInstalled(packId)
            File(context.cacheDir, "packs/$packId.part").delete()
            settings.clearDownloadOffset(packId)
            _progress.value = null
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 内置包索引。由 `backend/etl/build_builtin_packs.py` 产出，随 APK 打包。 */
        const val BUILTIN_INDEX_ASSET = "builtin_packs.json"

        private const val BATCH_SIZE = 2000

        /** 每下载 1 MB 把偏移量落一次盘。 */
        private const val PROGRESS_PERSIST_INTERVAL = 1L * 1024 * 1024
    }
}

private fun PackPoemDto.toRow() = PackPoemRow(
    poemId = poemId,
    title = title,
    content = content,
    author = author,
    dynasty = dynasty,
    period = period,
    genre = genre,
    form = form,
    cipai = cipai,
    lineCount = lineCount,
    charCount = charCount,
    isPublicDomain = isPublicDomain,
    weight = weight,
)
