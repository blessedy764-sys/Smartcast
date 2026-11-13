package com.smartcast.live.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Host side: announces the session on the local network so [SmartCastDiscovery] can find it. */
class SmartCastAdvertiser(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start(port: Int, name: String, pinProtected: Boolean) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_smartcast._udp."
            setPort(port)
            setAttribute("pin", if (pinProtected) "1" else "0") // the PIN itself is never advertised
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("NSD", "Register failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        listener = registration
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        } catch (e: Exception) {
            Log.e("NSD", "Register error", e)
            listener = null
        }
    }

    fun stop() {
        listener?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
        }
        listener = null
    }
}
