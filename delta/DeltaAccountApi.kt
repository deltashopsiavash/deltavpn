package com.v2ray.ang.delta

import android.os.SystemClock
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

data class DeltaAccount(val token: String, val username: String, val configs: List<String>, val gb: Int, val expires: Long, val serverNow: Long, val receivedElapsed: Long, val limitBytes: Long, val usedBytes: Long, val remainingBytes: Long, val canConnect: Boolean, val panelStatus: String) {
    val deadlineElapsed: Long get() = receivedElapsed + (expires-serverNow).coerceAtLeast(0)*1000L
    fun remainingSeconds(): Long = ((deadlineElapsed-SystemClock.elapsedRealtime()).coerceAtLeast(0)+999)/1000
}
class DeltaApiException(val status: Int): Exception()

class DeltaAccountApi {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .proxy(Proxy.NO_PROXY).connectTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private fun call(json: JsonObject): JsonObject {
        val endpoint = DeltaEndpoint.URL
        val uri = URI(endpoint)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null)
        val req = Request.Builder().url(endpoint).post(json.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw DeltaApiException(r.code)
            val body = r.body ?: error("empty")
            val source = body.source()
            check(!source.request(4_000_001))
            JsonParser.parseString(source.readUtf8()).asJsonObject
        }
    }
    private fun decode(r: JsonObject): DeltaAccount {
        val a = DeltaAccount(r.get("token").asString, r.get("username").asString,
            r.getAsJsonArray("configs").map { it.asString }, r.get("gb").asInt, r.get("expires").asLong, r.get("server_now").asLong, SystemClock.elapsedRealtime(), r.get("limit_bytes").asLong, r.get("used_bytes").asLong, r.get("remaining_bytes").asLong, r.get("can_connect").asBoolean, r.get("panel_status").asString)
        check(r.get("quota_enforced").asBoolean && a.limitBytes == a.gb.toLong()*1073741824L && a.usedBytes >= 0 && a.remainingBytes == (a.limitBytes-a.usedBytes).coerceAtLeast(0) && (!a.canConnect || (a.remainingBytes>0 && a.panelStatus=="active")) && a.token.matches(Regex("[a-f0-9]{64}")) && a.configs.size <= 1000 && a.gb > 0 &&
            a.expires > a.serverNow && a.expires-a.serverNow <= 3650L*86400L)
        return a
    }
    fun contacts(): Map<String,String> {
        val r=call(JsonObject().apply { addProperty("action","public_config") })
        return listOf("purchase","support").associateWith { k ->
            r.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{4,31}")) } ?: ""
        }
    }
    fun login(name: String, password: String) = decode(call(JsonObject().apply {
        addProperty("action","login"); addProperty("username",name); addProperty("password",password)
    }))
    fun session(token: String) = decode(call(JsonObject().apply {
        addProperty("action","session"); addProperty("token",token)
    }))
    fun status(token: String) = decode(call(JsonObject().apply {
        addProperty("action","status"); addProperty("token",token)
    }))
    fun logout(token: String) { call(JsonObject().apply { addProperty("action","logout"); addProperty("token",token) }) }
}
