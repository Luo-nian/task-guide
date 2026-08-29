package com.taskguide.app.server

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

    fun unregister() {
        try {
            listener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
        listener = null
    }
}
