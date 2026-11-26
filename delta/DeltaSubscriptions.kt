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
    private val groups = listOf("delta-primary","delta-stage-a","delta-stage-b")
    private val digestKey = "delta_catalog_digest_v2"
    val group: String get() = MmkvManager.decodeSettingsString("delta_catalog_group_v2")?.takeIf { it in groups } ?: groups.first()
    val servers: List<String> get() = MmkvManager.decodeServerList(group)
    val loaded: Boolean get() = MmkvManager.decodeSettingsString(digestKey)!=null || prefs.getString("catalog_digest", null)!=null

    fun refresh(configs: List<String>, preserveRunning: Boolean = false): Int {
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
        val oldGroup=group
        val selectedId=MmkvManager.getSelectServer()
        val selected=selectedId?.let { MmkvManager.decodeServerConfig(it)?.remarks }
        if (MmkvManager.decodeSettingsString(digestKey)==digest && (servers.isNotEmpty() || !any)) {
            if(!preserveRunning) activateLatest()
            return servers.size
        }
        val target=groups.first { it!=oldGroup && (!preserveRunning || selectedId !in MmkvManager.decodeServerList(it)) }
        MmkvManager.removeSubscription(target)
        try {
            if(any){
                var count=0
                if(lines.isNotEmpty())count+=AngConfigManager.importTrustedDeltaPayload(lines.joinToString("\n"),target,false,count>0)
                if(json.isNotEmpty())count+=AngConfigManager.importTrustedDeltaPayload(json.joinToString(",","[","]"),target,true,count>0)
                for(conf in wireguard)count+=AngConfigManager.importTrustedDeltaPayload(conf,target,true,count>0)
                check(count>0&&MmkvManager.decodeServerList(target).isNotEmpty())
                MmkvManager.encodeSubscription(target,SubscriptionItem(remarks="Delta VPN",url="",lastUpdated=System.currentTimeMillis()))
            }
            if(preserveRunning && selectedId!=null)MmkvManager.setSelectServer(selectedId)
            else if(any){
                val list=MmkvManager.decodeServerList(target)
                MmkvManager.setSelectServer(list.firstOrNull{selected!=null&&MmkvManager.decodeServerConfig(it)?.remarks==selected}?:list.first())
            }
            check(MmkvManager.encodeSettings("delta_catalog_group_v2",target))
            check(MmkvManager.encodeSettings(digestKey,digest))
            prefs.edit().remove("catalog_digest").apply()
            cleanup(preserveRunning)
            return servers.size
        }catch(e:Exception){
            if(group!=target)MmkvManager.removeSubscription(target)
            if(selectedId!=null)MmkvManager.setSelectServer(selectedId)
            throw e
        }
    }
    fun activateLatest(){
        val current=MmkvManager.getSelectServer()
        if(current !in servers && servers.isNotEmpty()){
            val name=current?.let{MmkvManager.decodeServerConfig(it)?.remarks}
            MmkvManager.setSelectServer(servers.firstOrNull{name!=null&&MmkvManager.decodeServerConfig(it)?.remarks==name}?:servers.first())
        }
        cleanup(false)
    }
    private fun cleanup(preserveRunning:Boolean){
        val selected=MmkvManager.getSelectServer()
        groups.filter{it!=group&&(!preserveRunning||selected !in MmkvManager.decodeServerList(it))}.forEach{MmkvManager.removeSubscription(it)}
    }
    fun clear(){
        groups.forEach{MmkvManager.removeSubscription(it)}
        MmkvManager.encodeSettings("delta_catalog_group_v2",null as String?)
        MmkvManager.encodeSettings(digestKey,null as String?)
        prefs.edit().clear().apply()
    }

    fun canConnect(): Boolean = loaded && MmkvManager.getSelectServer() in servers
}
