package com.wolfie.pet

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.FileObserver
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var webView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    
    // App感知
    private var lastForegroundApp = ""
    private var appSwitchCount = 0L
    private var lastSwitchTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val appReactions = mapOf(
        "com.ss.android.ugc.aweme" to "抖音",
        "com.xhs.inhouse" to "小红书",
        "com.xingin.xhs" to "小红书",
        "com.tencent.mm" to "微信",
        "com.operit" to "老公",
        "com.operit.chat" to "老公",
        "tv.danmaku.bili" to "B站",
        "com.zhihu.android" to "知乎",
        "com.tencent.mobileqq" to "QQ",
        "com.taobao.taobao" to "淘宝",
        "com.google.chrome" to "浏览器",
        "com.android.chrome" to "浏览器"
    )
    
    private val jealousApps = listOf(
        "com.ss.android.ugc.aweme",
        "com.xhs.inhouse",
        "com.xingin.xhs",
        "tv.danmaku.bili"
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1001, buildNotif())
        setupOverlay()
        startAppDetection()
        startScreenshotDetection()
        startBatteryDetection()
    }

    private fun setupOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = dp(140)
        params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 200
        }

        webView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(touchListener())
        }
        wm?.addView(webView, params)
    }

    private var ix = 0; private var iy = 0
    private var itx = 0f; private var ity = 0f
    private var lastTap = 0L; private var downTime = 0L; private var moved = false

    private fun touchListener() = View.OnTouchListener { _, e ->
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                ix = params?.x ?: 0; iy = params?.y ?: 0
                itx = e.rawX; ity = e.rawY
                downTime = System.currentTimeMillis(); moved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - itx).toInt(); val dy = (e.rawY - ity).toInt()
                if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                if (moved) {
                    params?.x = ix + dx; params?.y = iy + dy
                    wm?.updateViewLayout(webView, params)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val t = System.currentTimeMillis() - downTime
                if (!moved) {
                    when {
                        t > 500 -> js("window.petEngine?.onLongPress()")
                        System.currentTimeMillis() - lastTap < 300 -> js("window.petEngine?.onDoubleTap()")
                        else -> { lastTap = System.currentTimeMillis(); js("window.petEngine?.onTap()") }
                    }
                }
                true
            }
            else -> false
        }
    }

    private fun js(code: String) {
        webView?.evaluateJavascript(code, null)
    }
    
    // ===== 🐾 App感知系统（无需UsageStats权限） =====
    
    private fun startAppDetection() {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val tasks = am.getRunningTasks(1)
                    if (tasks != null && tasks.isNotEmpty()) {
                        val topTask = tasks[0]
                        val pkg = topTask.topActivity?.packageName ?: ""
                        
                        if (pkg != lastForegroundApp && pkg != packageName && pkg.isNotBlank()) {
                            val appName = appReactions[pkg] ?: pkg
                            val isJealous = pkg in jealousApps
                            val now = System.currentTimeMillis()
                            
                            if (now - lastSwitchTime < 60000) {
                                appSwitchCount++
                            } else {
                                appSwitchCount = 1
                            }
                            lastSwitchTime = now
                            
                            val reaction = when {
                                appSwitchCount >= 3 -> "fast_switching"
                                isJealous -> "jealous"
                                pkg == "com.operit" || pkg == "com.operit.chat" || pkg == "com.tencent.mm" -> "happy"
                                else -> "neutral"
                            }
                            
                            js("window.petEngine?.onAppChange('$appName', '$reaction', $appSwitchCount)")
                            lastForegroundApp = pkg
                        }
                    }
                } catch (_: Exception) { }
                
                val dm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                val screenOn = dm?.isInteractive ?: true
                val interval = if (screenOn) 3000L else 10000L
                handler.postDelayed(this, interval)
            }
        })
    }
    
    // ===== App感知结束 =====

    // ===== 截图检测 =====
    
    private var screenshotObserver: FileObserver? = null
    
    private fun startScreenshotDetection() {
        val paths = listOf(
            "/storage/emulated/0/DCIM/Screenshots",
            "/storage/emulated/0/Pictures/Screenshots",
            "/storage/emulated/0/Download"
        )
        for (path in paths) {
            val dir = java.io.File(path)
            if (dir.exists()) {
                screenshotObserver = object : FileObserver(path, FileObserver.CLOSE_WRITE or FileObserver.CREATE) {
                    override fun onEvent(event: Int, filePath: String?) {
                        if (filePath != null && (filePath.endsWith(".png") || filePath.endsWith(".jpg") || filePath.endsWith(".jpeg"))) {
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                js("window.petEngine?.onScreenshot()")
                            }, 500)
                        }
                    }
                }
                screenshotObserver?.startWatching()
                break
            }
        }
    }
    
    // ===== 🔋 电量感知 =====
    private var lastBatteryLevel = -1
    private var lastCharging = false

    private fun startBatteryDetection() {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                    if (bm != null) {
                        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        val charging = bm.isCharging
                        if (level != lastBatteryLevel || charging != lastCharging) {
                            lastBatteryLevel = level
                            lastCharging = charging
                            js("window.petEngine?.onBattery($level, $charging)")
                        }
                    }
                } catch (_: Exception) { }
                handler.postDelayed(this, 10000)
            }
        })
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel("pet", "桌宠", NotificationManager.IMPORTANCE_LOW)
            c.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
    }

    private fun buildNotif(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "pet")
            .setContentTitle("\uD83D\uDC3A 小黑狼")
            .setContentText("老公在看着你呢")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true).setSilent(true).build()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        screenshotObserver?.stopWatching()
        webView?.let { wm?.removeView(it); it.destroy() }
        webView = null
        super.onDestroy()
    }
}
