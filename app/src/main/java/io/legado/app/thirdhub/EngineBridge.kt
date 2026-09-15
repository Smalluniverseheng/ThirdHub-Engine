package io.legado.app.thirdhub

import android.content.Context
import io.legado.app.service.WebService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * ThirdHub 引擎桥: 阅读引擎 = 后端的"外部数据库"
 * 1) 登录 ThirdHub 账号(Supabase) → 2) 从配对表读后端地址+密钥 → 3) 周期心跳自注册到后端 /v1/pair
 * 用户唯一要做的: 导入书源。其余全自动。
 */
object EngineBridge {
    private const val BASE = "https://mxvxlgjzeboktufumxbp.supabase.co"
    private const val ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im14dnhsZ2p6ZWJva3R1ZnVteGJwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQzODM5OTcsImV4cCI6MjA5OTk1OTk5N30.QjSLfYAFhwX72YSeAcbTN5O2_PDLaNcv76HhdGJsqpo"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    var token = ""; var uid = ""; var email = ""
    var pairStatus = "未登录"
    var backendUrl = ""

    private val prefs get() = appCtx.getSharedPreferences("th_engine", Context.MODE_PRIVATE)

    fun restore() {
        token = prefs.getString("token", "") ?: ""
        uid = prefs.getString("uid", "") ?: ""
        email = prefs.getString("email", "") ?: ""
    }

    private fun save() {
        prefs.edit().putString("token", token).putString("uid", uid).putString("email", email).apply()
    }

    fun signOut() { token = ""; uid = ""; email = ""; pairStatus = "未登录"; save() }

    private fun http(method: String, url: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000; c.readTimeout = 8000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; OutputStreamWriter(c.outputStream).use { it.write(body) } }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        return code to txt
    }

    fun signIn(mail: String, pass: String, cb: (String?) -> Unit) {
        scope.launch {
            val err = runCatching {
                val (code, txt) = http("POST", "$BASE/auth/v1/token?grant_type=password",
                    JSONObject().put("email", mail).put("password", pass).toString(),
                    mapOf("apikey" to ANON, "Content-Type" to "application/json"))
                if (code != 200) return@runCatching JSONObject(txt).optString("error_description", "登录失败($code)")
                val j = JSONObject(txt)
                token = j.optString("access_token"); uid = j.optJSONObject("user")?.optString("id") ?: ""
                email = mail; save()
                null
            }.getOrElse { it.message ?: "网络错误" }
            cb(err)
        }
    }

    fun signUp(mail: String, pass: String, cb: (String?) -> Unit) {
        scope.launch {
            val (code, txt) = http("POST", "$BASE/auth/v1/signup",
                JSONObject().put("email", mail).put("password", pass).toString(),
                mapOf("apikey" to ANON, "Content-Type" to "application/json"))
            if (code == 200) signIn(mail, pass, cb) else cb(runCatching { JSONObject(txt).optString("error_description", "注册失败($code)") }.getOrDefault("注册失败($code)"))
        }
    }

    /** 从配对表找后端设备 */
    private fun fetchBackend(): Pair<String, String>? {
        if (token.isEmpty()) return null
        val (code, txt) = http("GET", "$BASE/rest/v1/th_devices?user_id=eq.$uid&device_type=eq.backend&select=lan_url,secret", null,
            mapOf("apikey" to ANON, "Authorization" to "Bearer $token"))
        if (code != 200) return null
        val arr = JSONArray(txt)
        if (arr.length() == 0) return null
        val d = arr.getJSONObject(0)
        val lan = d.optString("lan_url"); val sec = d.optString("secret")
        return if (lan.isNotEmpty() && sec.isNotEmpty()) lan to sec else null
    }

    /** 心跳: 每60秒把本引擎注册到后端(后端据此把搜索/正文请求转发过来) */
    fun start() {
        if (started) return
        started = true
        restore()
        scope.launch {
            while (true) {
                try {
                    if (token.isNotEmpty() && WebService.isRun && WebService.hostAddress.isNotEmpty()) {
                        val backend = fetchBackend()
                        if (backend == null) {
                            pairStatus = "未找到后端(先在第三方后端App登录同账号)"
                        } else {
                            backendUrl = backend.first
                            val body = JSONObject()
                                .put("device_url", WebService.hostAddress)
                                .put("device_type", "legado")
                                .put("caps", JSONArray().put("novel").put("comic").put("audio"))
                                .toString()
                            val (code, _) = http("POST", "${backend.first}/v1/pair", body,
                                mapOf("Content-Type" to "application/json", "X-TH-Token" to backend.second))
                            pairStatus = if (code in 200..299) "已连接后端 ✓" else "配对失败($code)"
                        }
                    } else if (token.isEmpty()) {
                        pairStatus = "未登录"
                    } else {
                        pairStatus = "Web服务启动中…"
                    }
                } catch (e: Exception) {
                    pairStatus = "异常: ${e.message}"
                }
                delay(60_000)
            }
        }
    }
}
