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

data class DeltaAccount(val token: String, val username: String, val configs: List<String>, val gb: Double, val expires: Long, val serverNow: Long, val receivedElapsed: Long, val limitBytes: Long, val usedBytes: Long, val remainingBytes: Long, val canConnect: Boolean, val panelStatus: String, val refreshMinutes: Int = 5, val refreshRevision: Long = 0, val reconnectRevision: Long = 0) {
    val deadlineElapsed: Long get() = receivedElapsed + (expires-serverNow).coerceAtLeast(0)*1000L
    fun remainingSeconds(): Long = ((deadlineElapsed-SystemClock.elapsedRealtime()).coerceAtLeast(0)+999)/1000
}
data class DeltaNewsItem(val id: Long, val body: String, val url: String, val buttonLabel: String, val created: Long, val read: Boolean)
data class DeltaNewsFeed(val unread: Int, val news: List<DeltaNewsItem>, val nextBefore: Long, val floating: DeltaNewsItem?)
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
            r.getAsJsonArray("configs").map { it.asString }, r.get("gb").asDouble, r.get("expires").asLong, r.get("server_now").asLong, SystemClock.elapsedRealtime(), r.get("limit_bytes").asLong, r.get("used_bytes").asLong, r.get("remaining_bytes").asLong, r.get("can_connect").asBoolean, r.get("panel_status").asString, r.get("refresh_minutes")?.asInt ?: 5, r.get("refresh_revision")?.asLong ?: 0, r.get("reconnect_revision")?.asLong ?: 0)
        check(a.refreshMinutes in 1..1440 && a.refreshRevision>=0 && a.reconnectRevision in 0..a.refreshRevision && r.get("quota_enforced").asBoolean && kotlin.math.abs(a.limitBytes.toDouble()-a.gb*1073741824.0)<1.0 && a.usedBytes >= 0 && a.remainingBytes == (a.limitBytes-a.usedBytes).coerceAtLeast(0) && (!a.canConnect || (a.remainingBytes>0 && a.panelStatus=="active")) && a.token.matches(Regex("[a-f0-9]{64}")) && a.configs.size <= 1000 && a.gb > 0 &&
            a.expires-a.serverNow <= 3650L*86400L)
        return a
    }
    fun contacts(): Map<String,String> {
        val r=call(JsonObject().apply { addProperty("action","public_config") })
        return listOf("purchase","support").associateWith { k ->
            r.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{4,31}")) } ?: ""
        }
    }
    fun login(name: String, password: String, device: String, model: String) = decode(call(JsonObject().apply {
        addProperty("action","login"); addProperty("username",name); addProperty("password",password);addProperty("device_id",device);addProperty("device_model",model)
    }))
    fun session(token: String) = decode(call(JsonObject().apply {
        addProperty("action","session"); addProperty("token",token)
    }))
    fun status(token: String) = decode(call(JsonObject().apply {
        addProperty("action","status"); addProperty("token",token)
    }))
    fun devices(token:String, revoke:Long?=null):JsonObject = call(JsonObject().apply {
        addProperty("action",if(revoke==null)"devices" else "revoke_device");addProperty("token",token)
        if(revoke!=null)addProperty("device",revoke)
    })
    private fun decodeNews(r: JsonObject): DeltaNewsFeed {
        fun item(j: JsonObject): DeltaNewsItem {
            val id=j.get("id").asLong;val body=j.get("body").asString
            val rawUrl=j.get("url").asString
            val url=if(rawUrl.isBlank()) "" else runCatching {
                val u=URI(rawUrl)
                require(u.scheme.equals("https",true)&&u.host!=null&&u.rawUserInfo==null&&rawUrl.length<=2048&&rawUrl.none { it.isWhitespace() || it=='\\' })
                rawUrl
            }.getOrDefault("")
            val label=j.get("button_label").asString.take(60)
            require(id>0&&body.isNotBlank()&&body.length<=10000)
            return DeltaNewsItem(id,body,url,label,j.get("created").asLong,j.get("read").asBoolean)
        }
        val list=r.getAsJsonArray("news").map { item(it.asJsonObject) }
        val floating=r.get("floating")?.takeUnless { it.isJsonNull }?.let { item(it.asJsonObject) }
        val n=r.get("unread").asInt;val next=r.get("next_before").asLong
        require(n>=0&&list.size<=30&&next>=0)
        return DeltaNewsFeed(n,list,next,floating)
    }
    fun news(token:String,before:Long=0):DeltaNewsFeed = decodeNews(call(JsonObject().apply{
        addProperty("action","news");addProperty("token",token);addProperty("before",before)
    }))
    fun acknowledgeNews(token:String,id:Long,floating:Boolean):DeltaNewsFeed = decodeNews(call(JsonObject().apply{
        addProperty("action",if(floating)"news_dismiss" else "news_read");addProperty("token",token);addProperty("id",id)
    }))
    fun logout(token: String) { call(JsonObject().apply { addProperty("action","logout"); addProperty("token",token) }) }
}
