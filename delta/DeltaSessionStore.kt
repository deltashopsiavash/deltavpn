package com.v2ray.ang.delta

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only a bearer token, encrypted by a non-exportable Android Keystore key. */
class DeltaSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("delta_session_v1", Context.MODE_PRIVATE)
    private val alias = "delta_session_aes_v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun read(): String? {
        val saved = prefs.getString("token", null) ?: return null
        return runCatching {
            val parts = saved.split(":")
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)
                .also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        }.getOrElse { clear(); null }
    }
    fun save(token: String) {
        require(token.matches(Regex("[a-f0-9]{64}")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val value = Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(token.toByteArray()),Base64.NO_WRAP)
        check(prefs.edit().putString("token",value).commit())
    }
    fun clear() { prefs.edit().clear().commit() }
}
