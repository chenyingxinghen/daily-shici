package com.example.daily_shici.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.daily_shici.data.pack.PackPhase
import com.example.daily_shici.data.repository.PackRepository
import com.example.daily_shici.domain.model.ChineseDate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 数据包下载 + 入库。
 *
 * 走前台服务：29 MB 的包在后台静默下载会让用户以为 App 卡死，
 * 必须给可见进度（`setForeground`）。
 *
 * 并发策略 `KEEP` + 按 packId 唯一命名 → 同一包只会有一个任务在跑，
 * 重复点「下载」不会叠加。
 */
@HiltWorker
class PackDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val packRepository: PackRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val packId = inputData.getString(KEY_PACK_ID) ?: return Result.failure()
        val packName = inputData.getString(KEY_PACK_NAME).orEmpty().ifBlank { packId }
        val url = inputData.getString(KEY_URL).orEmpty()
        val sha256 = inputData.getString(KEY_SHA256).orEmpty()
        val poemCount = inputData.getInt(KEY_POEM_COUNT, 0)

        setForeground(foreground(packName, text = "准备中", percent = 0, indeterminate = true))

        // CoroutineWorker 的 `coroutineContext` 已废弃；显式建一个受控作用域，
        // 任务结束时一并取消，避免观察协程泄漏。
        val observerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val watcher = observerScope.launch {
            packRepository.progress.collect { progress ->
                if (progress == null || progress.packId != packId) return@collect
                val (text, indeterminate) = when (progress.phase) {
                    PackPhase.DOWNLOADING -> {
                        val remain = progress.totalBytes - progress.bytesRead
                        val speedHint = if (progress.totalBytes > 0) {
                            "${ChineseDate.humanBytes(progress.bytesRead)} / ${ChineseDate.humanBytes(progress.totalBytes)}"
                        } else {
                            ChineseDate.humanBytes(progress.bytesRead)
                        }
                        val eta = if (progress.fraction > 0.01f && remain > 0) {
                            " · 剩 ${ChineseDate.humanBytes(remain)}"
                        } else {
                            ""
                        }
                        (speedHint + eta) to false
                    }
                    PackPhase.VERIFYING -> "校验中…" to true
                    PackPhase.IMPORTING ->
                        "入库中 ${progress.poemsImported}/${progress.expectedPoems}" to false
                    PackPhase.DONE -> "完成" to false
                    PackPhase.FAILED -> (progress.message ?: "失败") to true
                }
                runCatching {
                    setForeground(
                        foreground(
                            packName,
                            text,
                            percent = (progress.fraction * 100).toInt(),
                            indeterminate = indeterminate,
                        )
                    )
                }
                setProgress(workDataOf(KEY_PROGRESS to progress.fraction, KEY_PHASE to progress.phase.name))
            }
        }

        val pack = com.example.daily_shici.domain.model.PackInfo(
            packId = packId,
            name = packName,
            description = "",
            poemCount = poemCount,
            bytesGzip = inputData.getLong(KEY_BYTES, 0),
            builtin = false,
        )
        val result = packRepository.install(pack, url, sha256)
        watcher.cancel()
        observerScope.cancel()

        return if (result.isSuccess) {
            Result.success(workDataOf(KEY_IMPORTED to result.getOrDefault(0)))
        } else {
            // 失败交给 WorkManager 的指数退避；包是不可变的，续传靠 Range 而不是重来。
            Result.retry()
        }
    }

    private fun foreground(
        packName: String,
        text: String,
        percent: Int,
        indeterminate: Boolean,
    ): ForegroundInfo {
        ShiciNotifications.ensureChannels(applicationContext)
        val notification = ShiciNotifications.packProgressNotification(
            context = applicationContext,
            packName = packName,
            progress = percent,
            indeterminate = indeterminate,
            text = text,
        )
        return ForegroundInfo(
            ShiciNotifications.NOTIFICATION_PACK_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_PACK_ID = "pack_id"
        const val KEY_PACK_NAME = "pack_name"
        const val KEY_URL = "url"
        const val KEY_SHA256 = "sha256"
        const val KEY_POEM_COUNT = "poem_count"
        const val KEY_BYTES = "bytes_gzip"
        const val KEY_PROGRESS = "progress"
        const val KEY_PHASE = "phase"
        const val KEY_IMPORTED = "imported"

        /**
         * 入队下载。同一 packId 用 `KEEP` 保证只跑一个 ——
         * 用户连点「下载」不会变成并发写同一个 poem 表。
         */
        fun enqueue(
            context: Context,
            packId: String,
            packName: String,
            url: String,
            sha256: String,
            poemCount: Int,
            bytesGzip: Long,
        ) {
            val request = OneTimeWorkRequestBuilder<PackDownloadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_PACK_ID, packId)
                        .putString(KEY_PACK_NAME, packName)
                        .putString(KEY_URL, url)
                        .putString(KEY_SHA256, sha256)
                        .putInt(KEY_POEM_COUNT, poemCount)
                        .putLong(KEY_BYTES, bytesGzip)
                        .build()
                )
                .addTag(TAG_PACK)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${TAG_PACK}-$packId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context, packId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("$TAG_PACK-$packId")
        }

        private const val TAG_PACK = "pack_download"
    }
}
