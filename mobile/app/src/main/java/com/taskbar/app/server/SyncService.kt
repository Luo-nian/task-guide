package com.taskbar.app.server

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.taskbar.app.BuildConfig
import com.taskbar.app.TaskBarApp
import com.taskbar.app.data.model.TrackingInfo
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

    // v5.18.0：追踪通知相关的状态
    //   mainHandler —— 追踪状态在任意线程变化，通知必须在主线程更新
    //   foregrounded —— 当前是否处于"前台服务 + 通知"状态
    private val mainHandler = Handler(Looper.getMainLooper())
    private var foregrounded = false

    override fun onCreate() {
        super.onCreate()
        instance = this
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
        if (intent?.hasExtra(EXTRA_COUNT) == true) {
            // 调用方（MainActivity / 追踪观察器）已经查过库 → 一步到位
            applyTracking(
                intent.getStringExtra(EXTRA_TITLE) ?: "",
                intent.getStringExtra(EXTRA_STEP) ?: "",
                intent.getIntExtra(EXTRA_COUNT, 0),
                mustForeground = true,   // 本路径由 startForegroundService() 启动 → 必须过桥
            )
        } else {
            // 系统重启服务（intent 为 null）拿不到追踪状态 → 先用上一次已知状态，
            // 再异步查库补正。注意 Context.startForegroundService() 启的服务必须在
            // 5 秒内调用一次 startForeground()，否则抛 RemoteServiceException 直接崩进程，
            // 所以即使"确实没有追踪任务"，也必须先用一条极简通知过桥再撤掉。
            val cached = lastInfo
            applyTracking(cached.title, cached.step, cached.count, mustForeground = true)
            scope.launch { refreshFromDb() }
        }
        // v5.31.0：按角色启动 —— 服务器起 Ktor 等被连；客户端跑同步循环去连服务器。
        //   一个同步组里只能有一个服务器，客户端模式下本机不再监听端口。
        scope.launch {
            val role = ClientSync.role()
            // v5.31.0 诊断：vivo 上拿不到 App 日志 → 把关键判断落库，用 run-as 就能读
            runCatching {
                TaskBarApp.instance.repo.setSetting(
                    "svc_diag",
                    "role=$role serverNull=${server == null} listen=${server != null} t=${System.currentTimeMillis()}"
                )
            }
            if (role == ClientSync.ROLE_CLIENT) {
                // 从服务器切到客户端：先把本机的 Ktor/mDNS 停掉（一个同步组只能有一个服务器）
                if (server != null) {
                    runCatching { server?.stop(500, 1000) }
                    server = null
                    runCatching { mdns?.unregister() }
                    mdns = null
                    Log.i("SyncService", "已切换为客户端：本机服务器已停止")
                }
                ClientSync.startLoop(scope)
                // 立刻先跑一次，把失败原因落库（不然要等 8 秒且日志看不到）
                runCatching { ClientSync.syncOnce() }
                    .onFailure {
                        runCatching {
                            TaskBarApp.instance.repo.setSetting("client_last_error", "first: ${it.message}")
                        }
                    }
                Log.i("SyncService", "客户端模式：已启动与服务器的同步循环")
            } else if (server == null) {
                startServer()
            }
        }
        return START_STICKY
    }

    // ==================== 追踪通知（v5.18.0）====================

    /**
     * 按当前追踪状态设置 / 撤销前台通知。
     *
     * - **有追踪** → 前台服务 + 通知（任务名 + 当前步骤）← boss R30
     * - **无追踪** → `stopForeground(REMOVE)`，通知从通知栏消失；
     *   服务本身继续作为**普通后台服务**提供同步（前台态一撤，同步仍可用，只是没了通知）
     *
     * 技术前提（Android 的硬规定）：**前台服务必须有可见通知**，
     * 所以"既要后台常驻、又不要通知"只能取其一 —— 这里按 boss 的要求选"不要通知"。
     */
    private fun applyTracking(title: String, step: String, count: Int, mustForeground: Boolean) {
        lastInfo = if (count > 0) TrackingInfo(title, step, count) else TrackingInfo.NONE
        try {
            if (count > 0) {
                promote(NotificationHelper.buildTrackingNotification(this, title, step, count))
                foregrounded = true
                return
            }
            // ── 没有追踪任务：要把前台态撤掉 ──
            var bridged = false
            if (mustForeground) {
                // ⚠️ 实测踩过的崩（v5.18.0 真机第一次装就复现）：
                //   只要是 Context.startForegroundService() 启动的，系统就要求
                //   5 秒内必须调用过一次 startForeground()，否则抛
                //   ForegroundServiceDidNotStartInTimeException 直接把进程干掉。
                //   所以"确实没有追踪任务"时也得先用一条极简通知过桥，再立刻撤掉。
                promote(NotificationHelper.buildTrackingNotification(this, "任务栏", "", 0))
                bridged = true
            }
            if (foregrounded || bridged) stopForeground(STOP_FOREGROUND_REMOVE)
            foregrounded = false
        } catch (e: Exception) {
            // Android 12+ 禁止后台启动前台服务 → 这里是可预期异常，记日志即可，绝不崩进程
            Log.e("SyncService", "更新追踪通知失败", e)
        }
    }

    private fun promote(notif: android.app.Notification) {
        // Android 14 (API 34) 要求 startForeground 必须传 foregroundServiceType，
        // 否则 MissingForegroundServiceTypeException 杀整个 application。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /** 冷启动补正：查一次库，按真实追踪状态更新通知 */
    private suspend fun refreshFromDb() {
        val info = runCatching { TaskBarApp.instance.repo.trackingSnapshot() }.getOrNull() ?: return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            applyTracking(info.title, info.step, info.count, mustForeground = false)
        }
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
        ClientSync.stopLoop()
        serverJob?.cancel()
        runCatching { server?.stop(1000, 2000) }
        mdns?.unregister()
        // v5.15：反注册网络监听
        try {
            netCallback?.let { cb ->
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
            }
        } catch (_: Exception) {}
        instance = null
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

        private const val EXTRA_TITLE = "tb_track_title"
        private const val EXTRA_STEP = "tb_track_step"
        private const val EXTRA_COUNT = "tb_track_count"

        /** 运行中的服务实例。同进程内直接调用，免得每次刷新都 startService（后台会被限制） */
        @Volatile
        private var instance: SyncService? = null

        /** 最近一次已知的追踪状态：服务被系统重启时先按它显示，避免通知来回跳 */
        @Volatile
        private var lastInfo: TrackingInfo = TrackingInfo.NONE

        /**
         * 启动同步服务。
         * @param info 调用方查库得到的追踪状态；传 null 时服务会自己异步补正
         */
        fun start(context: Context, info: TrackingInfo? = null) {
            val intent = Intent(context, SyncService::class.java)
            if (info != null) {
                intent.putExtra(EXTRA_TITLE, info.title)
                intent.putExtra(EXTRA_STEP, info.step)
                intent.putExtra(EXTRA_COUNT, info.count)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 追踪状态变化 → 刷新通知栏。
         * 没有追踪任务时 info.count == 0 → 通知被撤销（这正是 boss 要的效果）。
         * 服务没在跑就什么都不做：下次 start() 会带上最新状态。
         */
        fun refreshTracking(info: TrackingInfo) {
            val inst = instance ?: return
            inst.mainHandler.post {
                // 同进程直调（不是 startForegroundService 启动的）→ 不需要过桥
                inst.applyTracking(info.title, info.step, info.count, mustForeground = false)
            }
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
