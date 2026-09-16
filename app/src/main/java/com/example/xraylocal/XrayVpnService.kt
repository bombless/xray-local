package com.example.xraylocal

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Runs Xray directly against the TUN file descriptor supplied by Android's VpnService.
 *
 * There is deliberately no SOCKS/HTTP inbound and therefore no listening TCP/UDP port.
 * Only the currently resolved IPv4 addresses of www.google.com are routed into the TUN;
 * Xray's own connection to the upstream proxy remains outside the VPN route table.
 */
class XrayVpnService : VpnService() {
    private var vpnInterface: android.os.ParcelFileDescriptor? = null
    private var controller: CoreController? = null

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long {
            isRunning = true
            return 0L
        }

        override fun shutdown(): Long {
            isRunning = false
            return 0L
        }

        override fun onEmitStatus(code: Long, message: String): Long {
            android.util.Log.d(TAG, "[$code] $message")
            return 0L
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startXrayVpn()
            ACTION_STOP -> {
                stopXrayVpn()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startXrayVpn() {
        if (isRunning) return

        try {
            // Resolve before establishing the VPN, so the DNS query itself cannot enter the TUN.
            val googleAddresses = InetAddress.getAllByName(GOOGLE_HOST)
                .filterIsInstance<Inet4Address>()
                .distinctBy { it.hostAddress }
                .ifEmpty { error("No IPv4 address found for $GOOGLE_HOST") }

            val builder = Builder()
                .setSession("Xray Local - Google TUN")
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS, TUN_PREFIX)

            // Route only Google destinations into Xray. This keeps Xray's own upstream
            // connection off the VPN path and avoids a self-loop without a socket-protect hook.
            googleAddresses.forEach { address ->
                builder.addRoute(address.hostAddress, 32)
            }

            vpnInterface?.close()
            vpnInterface = builder.establish() ?: error("VpnService.establish() returned null")

            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            controller = Libv2ray.newCoreController(callback)

            val root = JSONObject(
                assets.open("config.json").bufferedReader().use { it.readText() }
            )

            // Replace every proxy inbound with Xray's native TUN inbound. Xray's TUN
            // implementation consumes the Android-provided FD and does not open a port.
            root.put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("tag", TUN_TAG)
                        .put("port", 0)
                        .put("protocol", "tun")
                        .put(
                            "settings",
                            JSONObject()
                                .put("name", "xray-local-tun")
                                .put("mtu", TUN_MTU)
                                .put("gateway", JSONArray().put(TUN_GATEWAY))
                        )
                )
            )

            val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
            log.put("loglevel", log.optString("loglevel", "warning"))
            log.put("access", java.io.File(filesDir, "access.log").absolutePath)
            log.put("error", java.io.File(filesDir, "error.log").absolutePath)

            controller!!.startLoop(root.toString(), vpnInterface!!.fd)
            android.util.Log.i(
                TAG,
                "Xray native TUN started; no local proxy port; routed Google IPs=" +
                    googleAddresses.joinToString(",") { it.hostAddress }
            )
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed to start Xray TUN", t)
            stopXrayVpn()
            stopSelf()
        }
    }

    private fun stopXrayVpn() {
        try {
            controller?.stopLoop()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Failed to stop Xray core cleanly", t)
        }
        controller = null
        vpnInterface?.close()
        vpnInterface = null
        isRunning = false
    }

    override fun onDestroy() {
        stopXrayVpn()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    companion object {
        private const val TAG = "XrayLocalVpn"
        private const val ACTION_START = "com.example.xraylocal.action.START"
        private const val ACTION_STOP = "com.example.xraylocal.action.STOP"
        private const val GOOGLE_HOST = "www.google.com"
        private const val TUN_TAG = "xray-local-tun"
        private const val TUN_MTU = 1500
        private const val TUN_ADDRESS = "10.88.0.2"
        private const val TUN_GATEWAY = "10.88.0.1/30"
        private const val TUN_PREFIX = 30

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            context.startService(
                Intent(context, XrayVpnService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, XrayVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
