package com.example.daily_shici

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 自定义 WorkManager 配置的持有者。
 *
 * WorkManager 默认的初始化器在 manifest 里被移除（见 AndroidManifest），
 * 改由这里提供 [HiltWorkerFactory]，这样 Worker 才能拿到注入的仓库。
 */
@HiltAndroidApp
class ShiciApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
