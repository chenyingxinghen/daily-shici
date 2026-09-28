package com.example.daily_shici.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.daily_shici.MainActivity
import com.example.daily_shici.R

/** 通知渠道与构造。渠道只在 O+ 需要，minSdk 24 因此必须运行时判断。 */
object ShiciNotifications {

    const val CHANNEL_DAILY = "daily_poem"
    const val CHANNEL_PACKS = "pack_download"

    const val NOTIFICATION_DAILY_ID = 1001
    const val NOTIFICATION_PACK_ID = 1002

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DAILY,
                "每日一诗",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "每天一条诗词提醒" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PACKS,
                "数据包下载",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "数据包下载进度" }
        )
    }

    /** 每日提醒。点击打开详情页，poemId 从 daily_cache 取。 */
    fun dailyNotification(context: Context, title: String, excerpt: String, poemId: Long) =
        NotificationCompat.Builder(context, CHANNEL_DAILY)
            .setSmallIcon(R.drawable.ic_notification_poem)
            .setContentTitle(title)
            .setContentText(excerpt)
            .setStyle(NotificationCompat.BigTextStyle().bigText(excerpt))
            .setContentIntent(openPoemIntent(context, poemId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

    /** 数据包下载的前台进度通知。29 MB 的包必须给用户可见进度。 */
    fun packProgressNotification(
        context: Context,
        packName: String,
        progress: Int,
        indeterminate: Boolean,
        text: String,
    ) = NotificationCompat.Builder(context, CHANNEL_PACKS)
        .setSmallIcon(R.drawable.ic_notification_poem)
        .setContentTitle("正在下载 $packName")
        .setContentText(text)
        .setProgress(100, progress, indeterminate)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun openPoemIntent(context: Context, poemId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_POEM_ID, poemId)
        }
        return PendingIntent.getActivity(
            context,
            poemId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 统一出口。Android 13+ 未授予 POST_NOTIFICATIONS 时 notify 会被静默丢弃，
     * 这里显式吞掉异常 —— 通知失败不该影响后台任务本身。
     */
    fun post(context: Context, id: Int, notification: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
