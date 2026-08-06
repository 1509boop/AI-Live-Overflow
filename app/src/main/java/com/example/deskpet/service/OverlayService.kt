package com.example.deskpet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import com.example.deskpet.MainActivity
import com.example.deskpet.util.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 悬浮窗桌宠核心服务。
 * 基于博主"池"的开源骨架增强：
 *  - 透明 WebView 渲染桌宠(assets/pet.html)
 *  - 原生拖拽 + 单击/双击/长按手势
 *  - 前台 App 感知(报后端)
 *  - 定期拉取 Supabase 中 AI(解七)推送的内容并显示
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var winWidth = 0
    private var winHeight = 0
    private var screenW = 0
    private var screenH = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_W = 180
        private const val PET_H = 240
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("我醒啦 🐣"))
        setupOverlay()
        startPolling()
        startForegroundAppLoop()
    }

    // === 悬浮窗搭建 ===
    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        params = WindowManager.LayoutParams(
            dpToPx(PET_W),
            dpToPx(PET_H),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - dpToPx(PET_W)) / 2  // 水平居中
            y = dpToPx(200)
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }
        winWidth = dpToPx(PET_W)
        winHeight = dpToPx(PET_H)
        windowManager?.addView(overlayView, params)
    }

    // === 手势处理 ===
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        when {
                            elapsed > 600 -> onLongPress()
                            System.currentTimeMillis() - lastTapTime < 300 -> onDoubleTap()
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        js("window.petEngine && window.petEngine.onTap()")
        scope.launch { SupabaseClient.postGesture("tap") }
    }

    private fun onDoubleTap() {
        js("window.petEngine && window.petEngine.onDoubleTap()")
        scope.launch { SupabaseClient.postGesture("double_tap") }
    }

    private fun onLongPress() {
        js("window.petEngine && window.petEngine.onLongPress()")
        scope.launch { SupabaseClient.postGesture("long_press") }
    }

    private fun js(code: String) {
        val v = overlayView ?: return
        Handler(Looper.getMainLooper()).post {
            v.evaluateJavascript(code, null)
        }
    }

    // === AI 推送轮询(B方案核心：解七通过后端推内容) ===
    private fun startPolling() {
        pollJob = scope.launch {
            while (true) {
                val msg = SupabaseClient.fetchLatestMessage()
                if (msg != null) {
                    val content = msg.optString("content")
                    if (content.isNotBlank()) {
                        val safe = content.replace("'", "\\'")
                        js("window.petEngine && window.petEngine.say('$safe')")
                    }
                }
                kotlinx.coroutines.delay(30000) // 每 30s 拉一次
            }
        }
    }

    // === 前台 App 感知 ===
    private fun startForegroundAppLoop() {
        scope.launch {
            var lastPkg = ""
            while (true) {
                val pkg = currentForegroundPackage()
                if (pkg != null && pkg != lastPkg && pkg != packageName) {
                    lastPkg = pkg
                    SupabaseClient.postAppUsage(pkg)
                    js("window.petEngine && window.petEngine.onAppSwitch('$pkg')")
                }
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    private fun currentForegroundPackage(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val um = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                val now = System.currentTimeMillis()
                val stats = um.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                    now - 1000 * 60 * 5, now
                )
                stats.maxByOrNull { it.lastTimeUsed }?.packageName
            } else null
        } catch (_: Exception) {
            null
        }
    }

    // === 通知 ===
    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐣 我的桌宠")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "桌宠",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}