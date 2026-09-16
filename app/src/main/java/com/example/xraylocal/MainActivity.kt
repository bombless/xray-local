package com.example.xraylocal

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
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private var controller: CoreController? = null
    @Volatile private var coreStarted = false

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long {
            coreStarted = true
            return 0L
        }

        override fun shutdown(): Long {
            coreStarted = false
            return 0L
        }

        override fun onEmitStatus(code: Long, message: String): Long {
            android.util.Log.d("XrayLocal", "[$code] $message")
            return 0L
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startXray()

        setContent {
            var html by remember { mutableStateOf("") }
            var status by remember { mutableStateOf("Xray 正在启动…") }
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
                                status = "正在通过 Xray 隧道访问 Google…"
                                Thread {
                                    try {
                                        val body = fetchGoogle()
                                        runOnUiThread {
                                            html = body
                                            status = "访问完成，返回 ${body.length} 个字符"
                                            loading = false
                                        }
                                    } catch (t: Throwable) {
                                        runOnUiThread {
                                            html = "${t::class.java.simpleName}: ${t.message ?: "unknown error"}"
                                            status = "访问失败"
                                            loading = false
                                        }
                                    }
                                }.start()
                            }
                        ) {
                            Text("访问google")
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

    private fun startXray() {
        try {
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            controller = Libv2ray.newCoreController(callback)

            val root = JSONObject(assets.open("config.json").bufferedReader().use { it.readText() })
            val inbounds = root.optJSONArray("inbounds") ?: JSONArray().also { root.put("inbounds", it) }
            inbounds.put(
                JSONObject()
                    .put("listen", "127.0.0.1")
                    .put("port", SOCKS_PORT)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", false))
            )

            // Keep the supplied outbound/routing configuration intact, but make log paths
            // writable inside the app sandbox.
            val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
            log.put("loglevel", log.optString("loglevel", "warning"))
            log.put("access", java.io.File(filesDir, "access.log").absolutePath)
            log.put("error", java.io.File(filesDir, "error.log").absolutePath)

            controller!!.startLoop(root.toString(), 0)
            statusLog("Xray started without TUN; app-local SOCKS is 127.0.0.1:$SOCKS_PORT")
        } catch (t: Throwable) {
            android.util.Log.e("XrayLocal", "Failed to start Xray", t)
        }
    }

    private fun fetchGoogle(): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!coreStarted && System.nanoTime() < deadline) {
            Thread.sleep(100)
        }
        if (!coreStarted) error("Xray core did not start")

        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SOCKS_PORT))
        val connection = (URL("https://www.google.com/").openConnection(proxy) as HttpURLConnection)
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

    private fun statusLog(message: String) {
        android.util.Log.i("XrayLocal", message)
    }

    override fun onDestroy() {
        controller?.stopLoop()
        controller = null
        coreStarted = false
        super.onDestroy()
    }

    companion object {
        private const val SOCKS_PORT = 10808
    }
}
