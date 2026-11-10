package si.abdullahf.fjair

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.security.SecureRandom

/** Registers the _raop._tcp and _airplay._tcp Bonjour services (TXT values mirror UxPlay). */
class Advertiser(ctx: Context, private val name: String) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("fjair").apply { setReferenceCounted(false) }
    private val prefs = ctx.getSharedPreferences("id", Context.MODE_PRIVATE)
    private val listeners = ArrayList<NsdManager.RegistrationListener>()

    // Stable per-install identity.
    private fun stored(k: String, gen: () -> String) =
        prefs.getString(k, null) ?: gen().also { prefs.edit().putString(k, it).apply() }
    private fun rnd(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    val deviceId = stored("mac") {
        rnd(6).also { it[0] = ((it[0].toInt() and 0xFE) or 0x02).toByte() }
            .joinToString(":") { "%02X".format(it) }
    }
    /** Announces the TXT records exactly as the native server builds them, so /info and mDNS never disagree. */
    fun start(port: Int, onResult: (String) -> Unit) {
        stop()
        lock.acquire()
        reg("_raop._tcp", deviceId.replace(":", "") + "@" + name, port, Native.txtMap(0), onResult)
        reg("_airplay._tcp", name, port, Native.txtMap(1), onResult)
    }

    private fun reg(type: String, n: String, port: Int, txt: Map<String, String>, cb: (String) -> Unit) {
        val info = NsdServiceInfo().apply {
            serviceName = n; serviceType = type; this.port = port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = cb("registered ${i.serviceType} as ${i.serviceName}")
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) = cb("FAILED ${i.serviceType} err=$e")
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        listeners += l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listeners.forEach { try { nsd.unregisterService(it) } catch (_: IllegalArgumentException) {} }
        listeners.clear()
        if (lock.isHeld) lock.release()
    }
}
