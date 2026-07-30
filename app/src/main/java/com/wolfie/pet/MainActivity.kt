package com.wolfie.pet

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

    private fun checkPermission() {
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, OverlayService::class.java))
            finish()
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
