package com.example.deskpet.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Supabase 通信工具。
 * 功能：
 *  1. 监听 pet_state 表——AI（解七）通过后端写入待显示内容，桌宠拉取后播放。
 *  2. 上报 gesture_log / app_usage——桌宠感知到的交互与前台 App 回传后端。
 *
 * 注意：URL 与 anon key 在正式版应放入 BuildConfig / 本地隐藏配置，
 * 这里作为可运行示例使用占位常量。
 */
object SupabaseClient {

    // TODO: 填入你的 Supabase 项目地址与 anon key（下一步配置）
    const val SUPABASE_URL: String = "https://YOUR_PROJECT.supabase.co"
    const val SUPABASE_KEY: String = "YOUR_ANON_KEY"

    private const val TIMEOUT = 8000

    /**
     * 拉取最新一条 AI 推送(pet_msg)。
     * 返回的内容结构由后端约定：{ id, content, mood, created_at }
     */
    suspend fun fetchLatestMessage(): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$SUPABASE_URL/rest/v1/pet_msg?order=created_at.desc&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", SUPABASE_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val arr = JSONArray(body)
                return@withContext if (arr.length() > 0) arr.getJSONObject(0) else null
            }
            conn.disconnect()
            null
        } catch (_: Exception) {
            null
        }
    }

    /** 上报一条手势/交互事件到 gesture_log */
    suspend fun postGesture(gesture: String) {
        postRow("gesture_log", JSONObject().put("gesture_type", gesture))
    }

    /** 上报当前前台 App 到 app_usage */
    suspend fun postAppUsage(packageName: String) {
        postRow("app_usage", JSONObject().put("package_name", packageName))
    }

    private suspend fun postRow(table: String, body: JSONObject) = withContext(Dispatchers.IO) {
        try {
            val url = URL("$SUPABASE_URL/rest/v1/$table")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", SUPABASE_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            conn.disconnect()
        } catch (_: Exception) {
        }
    }

    // 简易 JSONArray，避免额外依赖
    private class JSONArray(private val json: String) {
        private val trimmed = json.trim().let {
            if (it.startsWith("[")) it.substring(1, it.length - 1) else it
        }
        val length: Int get() = if (trimmed.isBlank()) 0 else trimmed.split("},{").size

        fun getJSONObject(index: Int): JSONObject {
            val parts = trimmed.split("},{")
            val raw = if (index > 0) "{" + parts[index] + "}" else parts[index]
            return JSONObject(raw)
        }
    }
}