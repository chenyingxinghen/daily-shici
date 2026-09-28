package com.example.daily_shici.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.daily_shici.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台任务编排。全部是 PeriodicWork —— 没有一次性任务需要在这里排。
 */
@Singleton
class ShiciWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {

    /** 应用启动时调用一次。挂着 suspend 是因为提醒时刻存在 DataStore 里。 */
    suspend fun scheduleAll() {
        scheduleDailyPrefetch()
        scheduleDailyNotify()
    }

    /**
     * 预取：12 小时一次（一天两次，足够补上当天缺口），必须联网。
     *
     * 用 `UPDATE` 而不是 `KEEP`：用户改了设置（如缓存水位）后要能生效，
     * 而 `KEEP` 会让老任务一直占位。
     */
    fun scheduleDailyPrefetch() {
        val request = PeriodicWorkRequestBuilder<DailyPrefetchWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(TAG_DAILY)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_DAILY_PREFETCH,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * 提醒：每天一次，首次延迟到设置里的时刻（默认 08:00）。
     *
     * 用 `setInitialDelay` 对齐时刻而不是 `setExact`：PeriodicWork 的首次延迟
     * 之后按 24h 周期漂移，偏差在 ±15 分钟内，对本功能无损。
     */
    suspend fun scheduleDailyNotify() {
        val (hour, minute) = settings.notifyTime.first()
        val request = PeriodicWorkRequestBuilder<DailyNotifyWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntil(hour, minute).toMinutes(), TimeUnit.MINUTES)
            .addTag(TAG_DAILY)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_DAILY_NOTIFY,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelDailyNotify() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_DAILY_NOTIFY)
    }

    /** 到下一个 hh:mm 的时长；已过则算明天。 */
    private fun delayUntil(hour: Int, minute: Int): Duration {
        val now = LocalDateTime.now()
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target)
    }

    /** 供设置页展示「下次提醒时间」。 */
    fun nextNotifyTime(hour: Int, minute: Int): LocalTime = LocalTime.of(hour, minute)

    companion object {
        private const val TAG_DAILY = "shici_daily"
        const val WORK_DAILY_PREFETCH = "shici_daily_prefetch"
        const val WORK_DAILY_NOTIFY = "shici_daily_notify"
    }
}
