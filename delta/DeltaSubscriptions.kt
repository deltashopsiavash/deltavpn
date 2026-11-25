package com.v2ray.ang.delta

import android.content.Context
import com.google.gson.JsonParser
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
        val json = configs.filter { raw ->
            raw.length <= 262144 && raw.startsWith("{") && runCatching {
                val o = JsonParser.parseString(raw).asJsonObject
                o.get("inbounds")?.isJsonArray == true && o.get("outbounds")?.isJsonArray == true &&
                    o.get("routing")?.isJsonObject == true
            }.getOrDefault(false)
        }.distinct()
        val wireguard = configs.filter { it.length <= 262144 && it.startsWith("[Interface]") && it.contains("[Peer]") }.distinct()
        val payload = (lines + json + wireguard).joinToString("\u0000")
        val any = lines.isNotEmpty() || json.isNotEmpty() || wireguard.isNotEmpty()
        check(configs.isEmpty() || any) { "فرمت کانفیگ قابل استفاده نیست." }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        if (prefs.getString("catalog_digest", null) == digest && (servers.isNotEmpty() || !any)) return servers.size
        val selected = MmkvManager.getSelectServer()?.let { MmkvManager.decodeServerConfig(it)?.remarks }
        clear()
        if (any) {
            var count = 0
            if (lines.isNotEmpty()) count += AngConfigManager.importTrustedDeltaPayload(lines.joinToString("\n"), group, false, count > 0)
            if (json.isNotEmpty()) count += AngConfigManager.importTrustedDeltaPayload(json.joinToString(",", "[", "]"), group, true, count > 0)
            for (conf in wireguard) count += AngConfigManager.importTrustedDeltaPayload(conf, group, true, count > 0)
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
