package com.example.deskpet

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.deskpet.service.OverlayService

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 极简布局：标题 + 说明 + 启动按钮
        val title = TextView(this).apply {
            text = "🐣 我的桌宠"
            textSize = 24f
            setTextColor(0xFF3A3A3A.toInt())
        }
        val desc = TextView(this).apply {
            text = "在屏幕上方悬浮的 AI 桌宠。\n点击「启动」即可让宠物开始陪伴你。"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
        }
        val btn = Button(this).apply {
            text = "启动桌宠"
            textSize = 18f
            setOnClickListener { requestOverlayAndStart() }
        }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(title)
        root.addView(desc, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        (root.getChildAt(1) as TextView).setPadding(0, 24, 0, 24)
        root.addView(btn)
        setContentView(root)

        // 若已有悬浮窗权限，直接启动
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    private fun requestOverlayAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            // 引导去授权悬浮窗权限
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        } else {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从权限页返回后自动启动
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }
}