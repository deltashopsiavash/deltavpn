package com.v2ray.ang.delta

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

data class DeltaAccount(val token: String, val username: String, val configs: List<String>, val gb: Int, val expires: Long)

class DeltaAccountApi {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .proxy(Proxy.NO_PROXY).connectTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private fun call(json: JsonObject): JsonObject {
        val endpoint = DeltaEndpoint.URL
        val uri = URI(endpoint)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null)
        val req = Request.Builder().url(endpoint).post(json.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(req).execute().use { r ->
            check(r.isSuccessful)
            val body = r.body ?: error("empty")
            val source = body.source()
            check(!source.request(4_000_001))
            JsonParser.parseString(source.readUtf8()).asJsonObject
        }
    }
    private fun decode(r: JsonObject): DeltaAccount {
        val a = DeltaAccount(r.get("token").asString, r.get("username").asString,
            r.getAsJsonArray("configs").map { it.asString }, r.get("gb").asInt, r.get("expires").asLong)
        check(a.token.matches(Regex("[a-f0-9]{64}")) && a.configs.size <= 1000 && a.gb > 0 &&
            a.expires > System.currentTimeMillis()/1000)
        return a
    }
    fun login(name: String, password: String) = decode(call(JsonObject().apply {
        addProperty("action","login"); addProperty("username",name); addProperty("password",password)
    }))
    fun session(token: String) = decode(call(JsonObject().apply {
        addProperty("action","session"); addProperty("token",token)
    }))
    fun logout(token: String) { call(JsonObject().apply { addProperty("action","logout"); addProperty("token",token) }) }
}
