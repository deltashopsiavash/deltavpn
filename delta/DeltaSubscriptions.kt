package com.v2ray.ang.delta

import android.content.Context
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.dto.entities.SubscriptionItem
import java.security.MessageDigest

/** The authenticated API supplies the shared list; the app never fetches source URLs. */
class DeltaSubscriptions(context: Context) {
    private val prefs = context.getSharedPreferences("delta_account", Context.MODE_PRIVATE)
    val group = "delta-primary"
    val servers: List<String> get() = MmkvManager.decodeServerList(group)
    val loaded: Boolean get() = prefs.getString("catalog_digest", null) != null

    fun refresh(configs: List<String>): Int {
        require(configs.size <= 1000)
        val schemes = setOf("vless","vmess","trojan","ss","socks","socks4","socks5","wireguard","hysteria2","hy2","v2rayn")
        val lines = configs.filter { it.length <= 16384 && it.substringBefore("://") in schemes &&
            it.contains("://") && it.none { c -> c.isWhitespace() || c == '<' || c == '>' } }.distinct()
        val payload = lines.joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        if (prefs.getString("catalog_digest", null) == digest && (servers.isNotEmpty() || lines.isEmpty())) return servers.size
        val selected = MmkvManager.getSelectServer()?.let { MmkvManager.decodeServerConfig(it)?.remarks }
        clear()
        if (lines.isNotEmpty()) {
            val count = AngConfigManager.importTrustedDeltaPayload(payload, group, false)
            check(count > 0 && servers.isNotEmpty()) { "کانفیگ قابل استفاده دریافت نشد." }
            MmkvManager.encodeSubscription(group, SubscriptionItem(remarks = "Delta VPN", url = "", lastUpdated = System.currentTimeMillis()))
            val match = servers.firstOrNull { selected != null && MmkvManager.decodeServerConfig(it)?.remarks == selected }
            MmkvManager.setSelectServer(match ?: servers.first())
        }
        check(prefs.edit().putString("catalog_digest", digest).commit())
        return servers.size
    }

    fun clear() {
        MmkvManager.removeServerViaSubid(group)
        MmkvManager.removeSubscription(group)
        prefs.edit().clear().apply()
    }

    fun canConnect(): Boolean = loaded && MmkvManager.getSelectServer() in servers
}
