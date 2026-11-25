package com.v2ray.ang.delta

import java.net.URI
import java.util.Locale

/** Central allowlist. Add new trusted endpoints here, or replace with a signed policy later. */
object SubscriptionPolicy {
    private val endpoints = setOf("pmainp1.deltamarket2.ir:8000")
    fun accepts(value: String): Boolean = runCatching {
        val u = URI(value.trim())
        val host = u.host?.lowercase(Locale.ROOT) ?: return false
        val port = if (u.port == -1) 443 else u.port
        u.scheme.equals("https", true) &&
            u.rawUserInfo == null && u.rawFragment == null &&
            u.rawQuery == null &&
            "$host:$port" in endpoints &&
            u.rawPath.matches(Regex("/sub/[A-Za-z0-9._~-]+"))
    }.getOrDefault(false)
}
