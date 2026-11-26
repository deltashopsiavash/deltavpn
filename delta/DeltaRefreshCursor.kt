package com.v2ray.ang.delta
import android.os.SystemClock
import com.v2ray.ang.handler.MmkvManager
import java.security.MessageDigest
/** Shared cursor: daemon writes while connected; UI writes while disconnected. */
class DeltaRefreshCursor(token:String){
    private val key="delta_refresh_"+MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun read()=MmkvManager.decodeSettingsString(key)?.split(':')?.mapNotNull{it.toLongOrNull()}?.takeIf{it.size==3}?:listOf(0L,0L,0L)
    fun due(a:DeltaAccount):Boolean{
        val c=read();val now=SystemClock.elapsedRealtime()
        return a.refreshRevision>c[0]||a.reconnectRevision>c[1]||now<c[2]||now-c[2]>=a.refreshMinutes*60000L||c[2]==0L
    }
    fun reconnect(a:DeltaAccount)=a.reconnectRevision>read()[1]
    fun applied(a:DeltaAccount,reconnected:Boolean){
        val c=read()
        check(MmkvManager.encodeSettings(key,"${maxOf(c[0],a.refreshRevision)}:${if(reconnected)maxOf(c[1],a.reconnectRevision) else c[1]}:${SystemClock.elapsedRealtime()}"))
    }
    fun clear(){MmkvManager.encodeSettings(key,null as String?)}
}
