package io.orangerabbit.clanker.data

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first

private val Context.secretDataStore by preferencesDataStore(name = "clanker_secrets")

/**
 * Encrypted-at-rest storage for secrets (the API key, for now). Uses a Tink AEAD keyset whose
 * master key lives in the Android Keystore; Tink owns IV/nonce/AAD so no AES-GCM is hand-rolled.
 * Only ciphertext touches DataStore. This is the v1 foundation for the design's secret model
 * (StrongBox preference + biometric gating come later).
 */
@SingleIn(AppScope::class)
@Inject
class SecretStore(private val context: Context) {

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    suspend fun saveApiKey(key: String) {
        val ciphertext = Base64.encodeToString(aead.encrypt(key.encodeToByteArray(), AAD), Base64.NO_WRAP)
        context.secretDataStore.edit { it[API_KEY] = ciphertext }
    }

    suspend fun loadApiKey(): String? {
        val ciphertext = context.secretDataStore.data.first()[API_KEY] ?: return null
        return runCatching {
            aead.decrypt(Base64.decode(ciphertext, Base64.NO_WRAP), AAD).decodeToString()
        }.getOrNull()
    }

    private companion object {
        const val KEYSET_NAME = "clanker_keyset"
        const val KEYSET_PREFS = "clanker_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://clanker_master_key"
        val API_KEY = stringPreferencesKey("openrouter_api_key")
        val AAD = "clanker.apiKey".encodeToByteArray()
    }
}
