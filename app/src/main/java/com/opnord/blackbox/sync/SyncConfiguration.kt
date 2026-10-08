package com.opnord.blackbox.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

data class SyncConfiguration(val baseUrl: String, val deviceId: String, val token: String)

object SyncConfigurationStore {
    private const val PREFS = "sync_configuration"
    private const val TOKEN = "encrypted_token"
    private const val KEY_ALIAS = "opnord.device-token"

    fun saveObdPin(context: Context, address: String, pin: String) {
        require(pin.length in 1..16 && pin.all { it in '0'..'9' })
        context.getSharedPreferences("obd_pin", Context.MODE_PRIVATE).edit()
            .putString("address", address.uppercase()).putString("encrypted_pin", encrypt(pin)).apply()
    }

    fun readObdPin(context: Context, address: String): String? {
        val prefs = context.getSharedPreferences("obd_pin", Context.MODE_PRIVATE)
        if (!address.equals(prefs.getString("address", null), ignoreCase = true)) return null
        return prefs.getString("encrypted_pin", null)?.let { runCatching { decrypt(it) }.getOrNull() }
    }

    fun read(context: Context): SyncConfiguration? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("base_url", null)?.trim()?.trimEnd('/') ?: return null
        val deviceId = prefs.getString("device_id", null) ?: return null
        val encrypted = prefs.getString(TOKEN, null) ?: return null
        return runCatching { SyncConfiguration(baseUrl, deviceId, decrypt(encrypted)) }.getOrNull()
    }

    fun save(context: Context, baseUrl: String, deviceId: String, token: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encryptedToken = if (token.isBlank()) prefs.getString(TOKEN, null) ?: encrypt("") else encrypt(token.trim())
        prefs.edit().putString("base_url", baseUrl.trim().trimEnd('/'))
            .putString("device_id", deviceId.trim()).putString(TOKEN, encryptedToken).apply()
    }

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return cipher.doFinal(packed.copyOfRange(12, packed.size)).toString(Charsets.UTF_8)
    }

    private fun key(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? java.security.Key)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
}
