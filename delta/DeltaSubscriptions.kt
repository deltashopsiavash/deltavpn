package com.v2ray.ang.delta

import android.content.Context
import android.util.Base64
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
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

    fun refresh(value: String): Int {
        val source = value.trim()
        require(SubscriptionPolicy.accepts(source)) { "فقط لینک اشتراک معتبر دلتا پذیرفته می‌شود." }
        val request = Request.Builder().url(source).header("User-Agent", "v2rayNG/2.3.15").get().build()
        val data = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "دریافت اشتراک ناموفق بود (HTTP ${response.code})." }
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
        val clean = data.trim().removePrefix("\uFEFF")
        val schemes = listOf("vless://", "vmess://", "trojan://", "ss://")
        fun supported(text: String) = text.lineSequence().map { it.trim() }
            .filter { line -> schemes.any { line.startsWith(it, true) } && line.length <= 65536 }
            .distinct().take(500).toList()
        val decoded = runCatching { String(Base64.decode(clean, Base64.DEFAULT), Charsets.UTF_8) }
            .recoverCatching { String(Base64.decode(clean, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8) }
            .getOrDefault("")
        val lines = supported(clean).ifEmpty { supported(decoded) }
        check(lines.isNotEmpty()) { "هیچ کانفیگ قابل پشتیبانی در اشتراک پیدا نشد." }
        // Parsing and storage are handled by the pinned upstream engine.
        // Do not pass arbitrary URLs or raw JSON into the generic importer.
        val result = AngConfigManager.importBatchConfig(lines.joinToString("\n"), group, false)
        check(result.first > 0 && servers.isNotEmpty()) { "کانفیگ‌های اشتراک قابل خواندن نیستند." }
        MmkvManager.encodeSubscription(group, SubscriptionItem(remarks = "Delta VPN", url = source))
        check(prefs.edit().putString("subscription_url", source).commit()) { "ذخیره اشتراک ناموفق بود." }
        if (MmkvManager.getSelectServer() !in servers) MmkvManager.setSelectServer(servers.first())
        return result.first
    }

    fun clear() {
        MmkvManager.removeServerViaSubid(group)
        MmkvManager.removeSubscription(group)
        prefs.edit().clear().apply()
    }

    fun canConnect(): Boolean = url?.let(SubscriptionPolicy::accepts) == true &&
        MmkvManager.getSelectServer() in servers
}
