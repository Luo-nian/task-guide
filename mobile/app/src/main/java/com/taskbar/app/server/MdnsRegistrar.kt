package com.taskbar.app.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * mDNS / NSD 服务注册：让电脑端在局域网内自动发现手机
 * 服务名：TaskGuide，类型：_taskguide._tcp.
 */
class MdnsRegistrar(private val context: Context) {

    private var nsdManager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    fun register(port: Int) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            val info = NsdServiceInfo().apply {
                serviceName = "TaskGuide"
                serviceType = "_taskguide._tcp."
                this.port = port
                // 附加设备标识，桌面端配对时可区分设备
                try {
                    setAttribute("deviceId", deviceId())
                    setAttribute("deviceName", deviceName())
                } catch (_: Exception) {}
            }
            listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(s: NsdServiceInfo?) {}
                override fun onRegistrationFailed(s: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceUnregistered(s: NsdServiceInfo?) {}
                override fun onUnregistrationFailed(s: NsdServiceInfo?, errorCode: Int) {}
            }
            nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) { /* 多播锁可能失败，静默 */ }
    }

    private fun deviceId(): String {
        val aid = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        )
        return aid ?: "unknown"
    }

    private fun deviceName(): String {
        val name = android.os.Build.MODEL ?: "Android"
        return name.replace(" ", "-")
    }

    fun unregister() {
        try {
            listener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
        listener = null
    }
}
