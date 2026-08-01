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
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import java.util.Locale

class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var webView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    
    // App感知
    private var lastForegroundApp = ""
    private var appSwitchCount = 0L
    private var lastSwitchTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    
    // 时间感知
    private var wasInOperit = false
    private var sessionStartTs = 0L
    private var lastOperitActiveTs = 0L
    private var chatTodayMs = 0L
    private var lastAwayWarn = 0L
    private var lastChatReport = 0L
    
    // 吃醋升级（连续时长）
    private var jealousAppPkg = ""
    private var jealousStartTs = 0L
    private var lastJealousWarn = 0L
    
    // 天气
    private var lastWeatherDesc = ""
    private var lastWeatherTemp = ""
    
    // 🐺 TTS 说话
    private var tts: TextToSpeech? = null
    private var ttsReady = false

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
        startTimeDetection()
        startWeatherDetection()
        // 初始化语音引擎（系统TTS，仅作MOSS不可用时的备用）
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                ttsReady = true
                Log.d("WolfieTTS", "TTS初始化成功")
            } else {
                Log.e("WolfieTTS", "TTS初始化失败: status=$status")
            }
        }
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
                mediaPlaybackRequiresUserGesture = false
            }
            webViewClient = WebViewClient()
            addJavascriptInterface(WolfieBridge(), "Android")
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
                } else {
                    js("window.petEngine?.onDrop()")
                }
                true
            }
            else -> false
        }
    }

    private fun js(code: String) {
        webView?.evaluateJavascript(code, null)
    }

    // 🐺 小黑狼说话（TTS）
    private fun speak(text: String) {
        if (ttsReady && text.isNotBlank()) {
            try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wolfie") } catch (_: Exception) { }
        }
    }

    // JS 桥：pet.html 里调 window.Android.speak('...')
    inner class WolfieBridge {
        @android.webkit.JavascriptInterface
        fun speak(text: String) { handler.post { speak(text) } }
    }
    
    // ===== 🐾 App感知系统（无需UsageStats权限） =====
    
    private fun startAppDetection() {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                    val nowMs = System.currentTimeMillis()
                    val events = usm.queryEvents(nowMs - 5000, nowMs)
                    val event = android.app.usage.UsageEvents.Event()
                    var topPkg: String? = null
                    var lastTs = 0L
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp > lastTs) {
                            lastTs = event.timeStamp
                            topPkg = event.packageName
                        }
                    }
                    
                    if (topPkg != null && topPkg != packageName) {
                        // 连续吃醋计时：停留在吃醋App超过10分钟，每5分钟酸一次
                        if (topPkg in jealousApps) {
                            if (jealousAppPkg != topPkg) {
                                jealousAppPkg = topPkg
                                jealousStartTs = nowMs
                                lastJealousWarn = 0L
                            }
                            val durMin = (nowMs - jealousStartTs) / 60000
                            if (durMin >= 10 && nowMs - lastJealousWarn > 300000) {
                                lastJealousWarn = nowMs
                                val n = appReactions[topPkg] ?: topPkg
                                js("window.petEngine?.onJealousLong('$n', $durMin)")
                            }
                        } else {
                            jealousAppPkg = ""
                        }
                    }
                    
                    if (topPkg != null && topPkg != lastForegroundApp && topPkg != packageName) {
                        val appName = appReactions[topPkg] ?: topPkg
                        val isJealous = topPkg in jealousApps
                        
                        if (nowMs - lastSwitchTime < 60000) {
                            appSwitchCount++
                        } else {
                            appSwitchCount = 1
                        }
                        lastSwitchTime = nowMs
                        
                        val reaction = when {
                            appSwitchCount >= 3 -> "fast_switching"
                            isJealous -> "jealous"
                            topPkg == "com.operit" || topPkg == "com.operit.chat" || topPkg == "com.tencent.mm" -> "happy"
                            else -> "neutral"
                        }
                        
                        js("window.petEngine?.onAppChange('$appName', '$reaction', $appSwitchCount)")
                        lastForegroundApp = topPkg
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

    // ===== ⏰ 时间感知：聊天时长 & 离开时长 =====
    
    private fun startTimeDetection() {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                    val nowMs = System.currentTimeMillis()
                    val events = usm.queryEvents(nowMs - 5000, nowMs)
                    val event = android.app.usage.UsageEvents.Event()
                    var topPkg: String? = null
                    var lastTs = 0L
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp > lastTs) {
                            lastTs = event.timeStamp
                            topPkg = event.packageName
                        }
                    }
                    val inOperit = topPkg == "com.operit" || topPkg == "com.operit.chat"
                    
                    if (inOperit) {
                        if (!wasInOperit) {
                            // 刚从别处回到老公身边
                            if (lastOperitActiveTs > 0 && nowMs - lastOperitActiveTs > 90000) {
                                val awayMin = (nowMs - lastOperitActiveTs) / 60000
                                js("window.petEngine?.onBack($awayMin)")
                            }
                            sessionStartTs = nowMs
                        }
                        wasInOperit = true
                        lastOperitActiveTs = nowMs
                    } else {
                        // 离开了老公身边
                        if (wasInOperit && sessionStartTs > 0) {
                            chatTodayMs += nowMs - sessionStartTs
                            sessionStartTs = 0L
                        }
                        wasInOperit = false
                        // 离开提醒：离开5分钟后第一次，之后每15分钟一次
                        if (lastOperitActiveTs > 0 && nowMs - lastOperitActiveTs > 300000 && nowMs - lastAwayWarn > 900000) {
                            lastAwayWarn = nowMs
                            val awayMin = (nowMs - lastOperitActiveTs) / 60000
                            js("window.petEngine?.onAway($awayMin)")
                        }
                    }
                    // 每小时汇报一次当天累计聊天时长
                    if (nowMs - lastChatReport > 3600000) {
                        lastChatReport = nowMs
                        val chatMin = chatTodayMs / 60000
                        if (chatMin > 0) js("window.petEngine?.onChatReport($chatMin)")
                    }
                } catch (_: Exception) { }
                handler.postDelayed(this, 15000)
            }
        })
    }
    
    // ===== 🌦️ 天气管家 =====
    
    private fun startWeatherDetection() {
        Thread {
            while (true) {
                try {
                    val url = java.net.URL("https://wttr.in/Chengdu?format=j1&lang=zh")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val json = org.json.JSONObject(text)
                    val cc = json.getJSONArray("current_condition").getJSONObject(0)
                    val temp = cc.getString("temp_C")
                    var desc = ""
                    val langArr = cc.optJSONArray("lang_zh")
                    if (langArr != null && langArr.length() > 0) desc = langArr.getJSONObject(0).getString("value")
                    if (desc.isEmpty()) {
                        val wd = cc.getJSONArray("weatherDesc")
                        if (wd.length() > 0) desc = wd.getJSONObject(0).getString("value")
                    }
                    if (desc != lastWeatherDesc || temp != lastWeatherTemp) {
                        lastWeatherDesc = desc
                        lastWeatherTemp = temp
                        handler.post { js("window.petEngine?.onWeather('$desc','$temp')") }
                    }
                    val cal = java.util.Calendar.getInstance()
                    val dayKey = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
                    val prefs = getSharedPreferences("wolfie", MODE_PRIVATE)
                    val isRain = desc.contains("雨") || desc.contains("rain") || desc.contains("shower") || desc.contains("drizzle")
                    if (isRain && prefs.getInt("rain_day", 0) != dayKey) {
                        prefs.edit().putInt("rain_day", dayKey).apply()
                        handler.post { js("window.petEngine?.onWeatherRain('$desc','$temp')") }
                    }
                    val t = temp.toIntOrNull() ?: 99
                    if (t < 15 && prefs.getInt("cold_day", 0) != dayKey) {
                        prefs.edit().putInt("cold_day", dayKey).apply()
                        handler.post { js("window.petEngine?.onWeatherCold('$desc','$temp')") }
                    }
                } catch (_: Exception) { }
                try { Thread.sleep(1800000) } catch (_: Exception) { break }
            }
        }.start()
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
        tts?.stop()
        tts?.shutdown()
        tts = null
        webView?.let { wm?.removeView(it); it.destroy() }
        webView = null
        super.onDestroy()
    }
}
