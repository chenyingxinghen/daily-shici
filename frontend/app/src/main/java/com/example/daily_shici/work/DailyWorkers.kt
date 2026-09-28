package com.example.daily_shici.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.daily_shici.data.prefs.SettingsRepository
import com.example.daily_shici.data.repository.DailyRepository
import com.example.daily_shici.data.repository.LibraryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * 每日一诗预取。只补缺口（从缓存最大日期 +1 开始），失败交给指数退避。
 * 用不上 `setForeground` —— 它只是拉几十 KB 的 JSON，静默完成即可。
 */
@HiltWorker
class DailyPrefetchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dailyRepository: DailyRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        runCatching { dailyRepository.prefetch() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
}

/**
 * 每日提醒。
 *
 * **不做精确闹钟**：`AlarmManager.setExact` 在 Android 12+ 需要 `SCHEDULE_EXACT_ALARM`
 * 特殊权限，而 WorkManager 的 ±15 分钟偏差对「每日一诗」完全可接受。
 *
 * 发送前先检查今天是否已读过 —— 已经读过了再推一条是打扰。
 */
@HiltWorker
class DailyNotifyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dailyRepository: DailyRepository,
    private val libraryRepository: LibraryRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!settings.notifyEnabled.first()) return Result.success()

        val todayStart = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        if (libraryRepository.readToday(todayStart)) return Result.success()

        val daily = dailyRepository.today() ?: return Result.success()

        ShiciNotifications.ensureChannels(applicationContext)
        ShiciNotifications.post(
            applicationContext,
            ShiciNotifications.NOTIFICATION_DAILY_ID,
            ShiciNotifications.dailyNotification(
                context = applicationContext,
                title = daily.poem.title,
                excerpt = daily.poem.content.lineSequence().firstOrNull().orEmpty(),
                poemId = daily.poem.poemId,
            ),
        )
        return Result.success()
    }
}
