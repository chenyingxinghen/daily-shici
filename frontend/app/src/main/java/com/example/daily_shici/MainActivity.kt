package com.example.daily_shici

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.daily_shici.data.repository.PackRepository
import com.example.daily_shici.ui.nav.ShiciNavHost
import com.example.daily_shici.ui.theme.DailyshiciTheme
import com.example.daily_shici.work.ShiciNotifications
import com.example.daily_shici.work.ShiciWorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 单 Activity + Compose 架构。整个 App 只有这一个 Activity，
 * 页面切换全部由 `ui/nav` 的导航图承担。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var packRepository: PackRepository

    @Inject
    lateinit var workScheduler: ShiciWorkScheduler

    private var pendingPoemId: Long = -1L

    /**
     * Android 13+ 的通知权限。被拒后**不再询问** ——
     * 反复弹窗要权限是最让人反感的行为之一，改为在设置页留一个入口。
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 授权与否都不阻塞 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ⚠️ 必须显式用 SystemBarStyle.light，不能用默认的 auto。
        //
        // 默认的 auto 会**跟随系统深色模式**决定图标明暗：用户开了深色模式时，
        // 系统给浅色（白色）图标，而本 App 无论系统主题都是浅色纸底 #FAF5EB，
        // 于是白字压在浅底上几乎看不见 —— 这就是「状态栏与 App 背景对比度极低」的成因。
        //
        // App 只有浅色一套方案，故两个系统栏恒为「浅底 + 深色图标」。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        pendingPoemId = readPoemId(intent)
        ShiciNotifications.ensureChannels(this)
        requestNotificationPermissionIfNeeded()

        setContent {
            DailyshiciTheme {
                ShiciNavHost(startPoemId = pendingPoemId.takeIf { it > 0L })
            }
        }

        lifecycleScope.launch {
            // 内置 seed 包：让新装用户第一次打开就有内容可读。导入是幂等的。
            runCatching { packRepository.ensureBuiltinsLoaded() }
            runCatching { workScheduler.scheduleAll() }
        }
    }

    /** 通知点击且 Activity 已存在时不会走 onCreate，这里补一次。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val poemId = readPoemId(intent)
        if (poemId > 0L) {
            pendingPoemId = poemId
            recreate()
        }
    }

    private fun readPoemId(intent: Intent?): Long =
        intent?.getLongExtra(EXTRA_OPEN_POEM_ID, -1L) ?: -1L

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_OPEN_POEM_ID = "extra_open_poem_id"
    }
}
