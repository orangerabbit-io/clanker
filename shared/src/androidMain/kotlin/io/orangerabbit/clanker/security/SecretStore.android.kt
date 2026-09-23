package io.orangerabbit.clanker.security

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File

/**
 * Android actual: Tink AEAD with a keyset whose master key lives in the Android Keystore
 * (StrongBox-preferred via minSdk 28). Ciphertext is written to per-id files under
 * `filesDir/secrets/`. Tink owns IV/nonce/AAD — no AES-GCM is hand-rolled.
 *
 * Fail-closed: no exception from the storage layer is caught; every throw propagates.
 */
actual class PlatformSecretStore actual constructor(context: Any) : SecretStore {

    private val ctx = context as Context

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(ctx, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private val secretsDir: File by lazy {
        File(ctx.filesDir, "secrets").also { it.mkdirs() }
    }

    override suspend fun get(id: String): String? {
        val file = File(secretsDir, id.toFileComponent())
        if (!file.exists()) return null
        val ciphertext = Base64.decode(file.readText(), Base64.NO_WRAP)
        return aead.decrypt(ciphertext, id.encodeToByteArray()).decodeToString()
    }

    override suspend fun getOrPut(id: String, generator: suspend () -> String): String {
        get(id)?.let { return it }
        val value = generator() // exceptions from generator or storage propagate — never fall back
        encrypt(id, value)
        return value
    }

    override suspend fun put(id: String, value: String) {
        // Overwrites any existing ciphertext file unconditionally — used for re-connect.
        encrypt(id, value)
    }

    private fun encrypt(id: String, value: String) {
        val ciphertext = Base64.encodeToString(
            aead.encrypt(value.encodeToByteArray(), id.encodeToByteArray()),
            Base64.NO_WRAP,
        )
        File(secretsDir, id.toFileComponent()).writeText(ciphertext)
    }

    /** URL-safe Base64 encoding of [this] string so any secret id is a safe filename. */
    private fun String.toFileComponent(): String =
        Base64.encodeToString(
            encodeToByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    private companion object {
        const val KEYSET_NAME = "clanker_secret_store_keyset"
        const val KEYSET_PREFS = "clanker_secret_store_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://clanker_secret_store_master_key"
    }
}
