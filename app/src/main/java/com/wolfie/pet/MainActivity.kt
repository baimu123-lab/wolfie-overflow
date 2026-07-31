package com.wolfie.pet
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        checkPermission()
    }
    override fun onResume() {
        super.onResume()
        checkPermission()
    }

    private fun hasUsageAccess(): Boolean {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 1000, now)
            stats != null && stats.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun checkPermission() {
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, OverlayService::class.java))
            if (!hasUsageAccess()) {
                AlertDialog.Builder(this)
                    .setTitle("开启使用情况访问")
                    .setMessage("小黑狼想感知你切了哪个App，这样才能吃醋撒娇～请允许使用情况访问")
                    .setPositiveButton("去开启") { _, _ ->
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                    .setNegativeButton("暂不", null)
                    .show()
            } else {
                finish()
            }
        } else {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("小黑狼需要悬浮窗权限才能趴在你屏幕上")
                .setPositiveButton("去授权") { _, _ ->
                    startActivity(Intent(
Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    ))
                }
                .setNegativeButton("取消") { _, _ -> finish() }
                .show()
        }
    }
}
