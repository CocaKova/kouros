package com.cocakova.kouros.data

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Server credentials (tokens, passwords, webhook secrets), encrypted with a key that lives in the
 * Android Keystore. The ciphertext sits in a private preferences file that is excluded from
 * backups (see the backup and data-extraction rules in res/xml): a restored phone asks for credentials again rather than
 * carrying them off-device.
 */
class Secrets(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "kouros_keyset", "kouros_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://kouros_master_key")
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) { prefs.edit().remove(name).apply(); return }
        val ct = aead.encrypt(value.toByteArray(), name.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val b64 = prefs.getString(name, null) ?: return null
        return runCatching { String(aead.decrypt(Base64.decode(b64, Base64.NO_WRAP), name.toByteArray())) }.getOrNull()
    }

    fun remove(prefix: String) {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(e::remove)
        e.apply()
    }

    companion object {
        fun serverSecret(serverId: String) = "server:$serverId:secret"
        fun powerSecret(serverId: String) = "server:$serverId:power"
    }
}
