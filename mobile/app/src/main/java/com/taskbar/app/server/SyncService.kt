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

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
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

    override fun onDestroy() {
        serverJob?.cancel()
        runCatching { server?.stop(1000, 2000) }
        mdns?.unregister()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
