package io.legado.app.thirdhub

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.service.WebService

/**
 * 引擎设置页(极简): 账号登录 + 运行状态 + 书源导入指引
 * 开箱即用: 登录一次账号, 导入书源, 之后引擎全自动运行
 */
class EngineSetupActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "阅读引擎"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        tvStatus = TextView(this).apply { textSize = 15f }
        root.addView(tvStatus)

        val hintTv = TextView(this).apply {
            text = "\n使用步骤:\n1. 登录 ThirdHub 账号(与后端 App 同一账号)\n2. 返回首页 → 我的 → 书源管理 → 导入书源\n3. 完成。前端搜索小说/漫画时自动经过后端调用本引擎"
            textSize = 13f; setTextColor(Color.GRAY)
        }
        root.addView(hintTv)

        if (EngineBridge.token.isEmpty()) {
            val mail = EditText(this).apply { hint = "邮箱"; inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
            val pass = EditText(this).apply { hint = "密码(至少6位)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            val btnLogin = Button(this).apply { text = "登录" }
            val btnReg = Button(this).apply { text = "注册" }
            btnLogin.setOnClickListener {
                toast("登录中…")
                EngineBridge.signIn(mail.text.toString().trim(), pass.text.toString()) { err ->
                    runOnUiThread { if (err == null) { toast("已登录"); recreate() } else toast(err) }
                }
            }
            btnReg.setOnClickListener {
                toast("注册中…")
                EngineBridge.signUp(mail.text.toString().trim(), pass.text.toString()) { err ->
                    runOnUiThread { if (err == null) { toast("已注册并登录"); recreate() } else toast(err) }
                }
            }
            root.addView(mail); root.addView(pass); root.addView(btnLogin); root.addView(btnReg)
        } else {
            val btnOut = Button(this).apply { text = "退出账号 (${EngineBridge.email})" }
            btnOut.setOnClickListener { EngineBridge.signOut(); recreate() }
            root.addView(btnOut)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    private fun refreshStatus() {
        tvStatus.text = buildString {
            appendLine("引擎服务: ${if (WebService.isRun) "运行中 ✓" else "启动中…"}")
            appendLine("引擎地址: ${WebService.hostAddress.ifEmpty { "获取中…" }}")
            appendLine("后端连接: ${EngineBridge.pairStatus}")
            if (EngineBridge.backendUrl.isNotEmpty()) appendLine("后端地址: ${EngineBridge.backendUrl}")
        }
        tvStatus.postDelayed({ if (!isDestroyed) refreshStatus() }, 3000)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
