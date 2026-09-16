package com.example.xraylocal

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var html by remember { mutableStateOf("") }
            var status by remember { mutableStateOf("点击按钮后建立无端口 TUN 隧道") }
            var loading by remember { mutableStateOf(false) }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !loading,
                            onClick = {
                                loading = true
                                html = ""
                                status = "正在建立 Xray TUN 隧道…"
                                requestVpnPermissionAndRun { result, message ->
                                    runOnUiThread {
                                        html = result
                                        status = message
                                        loading = false
                                    }
                                }
                            }
                        ) {
                            Text("访问 google")
                        }

                        Text(status, style = MaterialTheme.typography.bodyMedium)

                        Text(
                            text = html,
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    private fun requestVpnPermissionAndRun(onComplete: (String, String) -> Unit) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingCallback = onComplete
            startActivityForResult(prepareIntent, REQUEST_VPN)
        } else {
            startVpnAndFetch(onComplete)
        }
    }

    @Deprecated("Use Activity Result APIs in new code")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return

        val callback = pendingCallback
        pendingCallback = null
        if (resultCode == Activity.RESULT_OK && callback != null) {
            startVpnAndFetch(callback)
        } else {
            callback?.invoke("", "未授予 VPN 权限")
        }
    }

    private fun startVpnAndFetch(onComplete: (String, String) -> Unit) {
        XrayVpnService.start(this)

        Thread {
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (!XrayVpnService.isRunning && System.nanoTime() < deadline) {
                    Thread.sleep(100)
                }
                if (!XrayVpnService.isRunning) {
                    error("Xray VPN service did not start")
                }

                val body = fetchGoogleDirect()
                onComplete(body, "访问完成，返回 ${body.length} 个字符；请求未经过任何本地 SOCKS/HTTP 端口")
            } catch (t: Throwable) {
                onComplete(
                    "${t::class.java.simpleName}: ${t.message ?: "unknown error"}",
                    "访问失败"
                )
            } finally {
                XrayVpnService.stop(this)
            }
        }.start()
    }

    private fun fetchGoogleDirect(): String {
        val connection = URL("https://www.google.com/").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; XrayLocal)")
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val REQUEST_VPN = 1001
        private var pendingCallback: ((String, String) -> Unit)? = null
    }
}
