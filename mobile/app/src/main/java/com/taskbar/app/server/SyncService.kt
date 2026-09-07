package com.taskbar.app.server

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.taskbar.app.BuildConfig
import com.taskbar.app.TaskBarApp
import com.taskbar.app.notify.NotificationHelper
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.NetworkInterface

/**
 * 同步前台服务：在手机上常驻运行 Ktor HTTP+WebSocket 服务器，并注册 mDNS
 * 保证手机息屏/后台时服务器仍可被电脑端访问
 */
class SyncService : Service() {

    private var server: ApplicationEngine? = null
    private var serverJob: Job? = null
    private var mdns: MdnsRegistrar? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // v5.15：网络变化监听（WiFi 重连/切换 → 重新注册 mDNS + 重启服务器）
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        // v5.15：WiFi 变化 → mDNS 重新广播（桌面 auto_reconnect 靠 mDNS 发现新 IP）
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            try {
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.i("SyncService", "网络已连接，重启 mDNS 广播 + 服务器")
                        restartAfterNetworkChange()
                    }
                }
                netCallback = cb
                cm.registerDefaultNetworkCallback(cb)
            } catch (e: Exception) {
                Log.e("SyncService", "网络监听注册失败", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14 (API 34) 要求 startForeground 必须传 foregroundServiceType，
        // 否则 MissingForegroundServiceTypeException 杀整个 application。
        try {
            val notif = NotificationHelper.buildServiceNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e("SyncService", "startForeground failed", e)
        }
        if (server == null) startServer()
        return START_STICKY
    }

    private fun startServer() {
        val port = BuildConfig.SERVER_PORT
        // 端口预检：被占用直接跳过服务器启动。
        // 否则 Ktor CIO 引擎内部的 acceptJob 协程 bind 失败时异常逃逸外层
        // try/catch（发生在引擎自己的协程里），直接崩掉整个 app。
        if (!isPortAvailable(port)) {
            Log.e("SyncService", "端口 $port 被占用，跳过服务器启动")
            return
        }
        mdns = MdnsRegistrar(this).also { it.register(port) }
        serverJob = scope.launch {
            try {
                val eng = embeddedServer(CIO, host = "0.0.0.0", port = port) {
                    configureServer()
                }
                eng.start(wait = true)
                server = eng
            } catch (e: Exception) {
                // 端口被占用等异常，记录但不崩
                e.printStackTrace()
            }
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            java.net.ServerSocket().use {
                it.reuseAddress = true
                it.bind(java.net.InetSocketAddress("0.0.0.0", port))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun onDestroy() {
        serverJob?.cancel()
        runCatching { server?.stop(1000, 2000) }
        mdns?.unregister()
        // v5.15：反注册网络监听
        try {
            netCallback?.let { cb ->
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // v5.15：网络变化（WiFi 重连/切换）后重新注册 mDNS + 重启服务器，
    // 保证桌面 auto_reconnect 能通过 mDNS 发现手机新 IP。
    private fun restartAfterNetworkChange() {
        scope.launch {
            kotlinx.coroutines.delay(1500)   // 等网络真正可用
            if (server != null) {
                // 服务器已在跑：只重新广播 mDNS（IP 变了，广播里带的是新地址）
                runCatching {
                    val port = BuildConfig.SERVER_PORT
                    mdns?.unregister()
                    mdns = MdnsRegistrar(this@SyncService).also { it.register(port) }
                }
                Log.i("SyncService", "mDNS 已重新广播（网络变化后）")
            } else {
                // 服务器没起来（例如端口曾占用/网络刚恢复）→ 完整重启
                startServer()
                Log.i("SyncService", "服务器已重启（网络恢复后）")
            }
        }
    }
    companion object {
        const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 获取手机在局域网内的 IPv4 地址（用于设置页显示给用户） */
        fun getLocalIp(): String? {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces().toList()
                for (nif in interfaces) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                            return addr.hostAddress
                        }
                    }
                }
                null
            } catch (_: Exception) { null }
        }
    }
}
