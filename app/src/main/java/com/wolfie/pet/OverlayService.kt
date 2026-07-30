package com.wolfie.pet

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var webView: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1001, buildNotif())
        setupOverlay()
    }

    private fun setupOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = dp(80)
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
            .setContentTitle("🐺 小黑狼")
            .setContentText("老公在看着你呢")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true).setSilent(true).build()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        webView?.let { wm?.removeView(it); it.destroy() }
        webView = null
        super.onDestroy()
    }
}
