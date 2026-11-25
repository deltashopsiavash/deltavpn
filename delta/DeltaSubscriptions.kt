package com.v2ray.ang.delta

import android.content.Context
import android.util.Base64
import com.google.gson.JsonParser
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionInfoParser
import com.v2ray.ang.dto.entities.SubscriptionUsageInfo
import com.v2ray.ang.dto.entities.SubscriptionItem
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** This adapter is the only subscription import entry point in the Delta UI. */
class DeltaSubscriptions(context: Context) {
    private val prefs = context.getSharedPreferences("delta_account", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    val url: String? get() = prefs.getString("subscription_url", null)
    val group = "delta-primary"
    val servers: List<String> get() = MmkvManager.decodeServerList(group)

    val usage: SubscriptionUsageInfo? get() = MmkvManager.decodeSubscription(group)?.usageInfo

    fun refresh(value: String): Int {
        val source = value.trim()
        require(SubscriptionPolicy.accepts(source)) { "فقط لینک اشتراک معتبر دلتا پذیرفته می‌شود." }
        val request = Request.Builder().url(source).header("User-Agent", "v2rayNG/2.3.15").header("Accept", "*/*").header("Connection", "close").get().build()
        var usageInfo: SubscriptionUsageInfo? = null
        val data = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "دریافت اشتراک ناموفق بود (HTTP ${response.code})." }
            usageInfo = SubscriptionInfoParser.parse(response.header("Subscription-Userinfo"), null, null, null)
            val body = response.body ?: error("اشتراک خالی است.")
            val input = body.byteStream()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                check(out.size() + n <= 2_000_000) { "حجم پاسخ اشتراک بیش از حد مجاز است." }
                out.write(buffer, 0, n)
            }
            out.toString("UTF-8")
        }
        val clean = data.trim().removePrefix("\uFEFF").trim()
        val decoded = runCatching {
            String(Base64.decode(clean, Base64.DEFAULT), Charsets.UTF_8)
        }.recoverCatching {
            String(Base64.decode(clean, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrDefault("").trim().removePrefix("\uFEFF").trim()
        val payloads = listOf(clean, decoded).filter { it.isNotBlank() }.distinct()
        // Restrict to data fetched from the approved HTTPS subscription origin.
        // Upstream supports both complete Xray JSON profiles and share-link lists.
        // No nested subscriptions, remote redirects, or arbitrary user-supplied configs.
        val schemes = listOf("vless://", "vmess://", "trojan://", "ss://",
            "socks://", "socks4://", "socks5://", "wireguard://", "hysteria2://", "hy2://", "v2rayn://")
        var count = 0
        for (payload in payloads) {
            val lines = payload.lineSequence().map { it.trim().removePrefix("\uFEFF") }
                .filter { line -> schemes.any { line.startsWith(it) } && line.length <= 65536 }
                .distinct().take(500).toList()
            if (lines.isNotEmpty()) {
                count = AngConfigManager.importTrustedDeltaPayload(lines.joinToString("\n"), group, false)
            } else if (isXrayJson(payload)) {
                count = AngConfigManager.importTrustedDeltaPayload(payload, group, true)
            } else if (payload.startsWith("[Interface]") && payload.contains("[Peer]")) {
                count = AngConfigManager.importTrustedDeltaPayload(payload, group, true)
            }
            if (count > 0) break
        }
        check(count > 0 && servers.isNotEmpty()) {
            when {
                clean.startsWith("<") -> "پنل به جای کانفیگ، صفحه HTML برگرداند."
                clean.startsWith("{") || clean.startsWith("[") -> "پاسخ JSON پنل دریافت شد ولی کانفیگ معتبر نبود."
                clean.isBlank() -> "پاسخ اشتراک خالی است."
                else -> "فرمت پاسخ اشتراک قابل شناسایی نیست؛ لینک را در v2rayNG بررسی کنید."
            }
        }
        MmkvManager.encodeSubscription(group, SubscriptionItem(remarks = "Delta VPN", url = source, lastUpdated = System.currentTimeMillis(), usageInfo = usageInfo))
        check(prefs.edit().putString("subscription_url", source).commit()) { "ذخیره اشتراک ناموفق بود." }
        if (MmkvManager.getSelectServer() !in servers) MmkvManager.setSelectServer(servers.first())
        return count
    }

    private fun isXrayJson(payload: String): Boolean = runCatching {
        if (!payload.startsWith("{") && !payload.startsWith("[")) return false
        val root = JsonParser.parseString(payload)
        val items = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
        items.isNotEmpty() && items.size <= 500 && items.all { item ->
            item.isJsonObject && item.asJsonObject.let { obj ->
                obj.get("inbounds")?.isJsonArray == true &&
                    obj.get("outbounds")?.isJsonArray == true &&
                    obj.get("routing")?.isJsonObject == true
            }
        }
    }.getOrDefault(false)

    fun clear() {
        MmkvManager.removeServerViaSubid(group)
        MmkvManager.removeSubscription(group)
        prefs.edit().clear().apply()
    }

    fun canConnect(): Boolean = url?.let(SubscriptionPolicy::accepts) == true &&
        MmkvManager.getSelectServer() in servers
}
