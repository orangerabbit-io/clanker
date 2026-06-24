package io.orangerabbit.clanker.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "clanker_settings")

/** Non-secret app settings (model defaults, FX intensity, per-character model overrides). */
data class Settings(
    val defaultChatModel: String = DEFAULT_CHAT_MODEL,
    val defaultImageModel: String = DEFAULT_IMAGE_MODEL,
    /** Master cyberpunk-FX intensity, 0f (flat) .. 1f (full glow/glitch/CRT). */
    val fxIntensity: Float = 1f,
    /** Cumulative spend across all sessions (USD), from OpenRouter's reported per-request cost. */
    val lifetimeCostUsd: Double = 0.0,
) {
    companion object {
        const val DEFAULT_CHAT_MODEL = "openai/gpt-4o-mini"
        const val DEFAULT_IMAGE_MODEL = "google/gemini-2.5-flash-image"
    }
}

/** Per-character model override. Null fields fall back to the global defaults. */
data class CharacterModels(val chatModel: String? = null, val imageModel: String? = null)

/**
 * Plaintext-safe settings (NOT secrets — secrets live in [SecretStore]). Backed by a separate
 * Preferences DataStore. Per-character overrides are keyed by character name so a re-imported card
 * keeps its assigned models even before full character persistence (Room) lands.
 */
@SingleIn(AppScope::class)
@Inject
class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.settingsDataStore.data.map { it.toSettings() }

    suspend fun setDefaultChatModel(model: String) =
        context.settingsDataStore.edit { it[DEFAULT_CHAT] = model }.let { }

    suspend fun setDefaultImageModel(model: String) =
        context.settingsDataStore.edit { it[DEFAULT_IMAGE] = model }.let { }

    suspend fun setFxIntensity(value: Float) =
        context.settingsDataStore.edit { it[FX_INTENSITY] = value }.let { }

    /** Adds a turn's cost to the persisted lifetime total (read-modify-write under DataStore's lock). */
    suspend fun addLifetimeCost(delta: Double) =
        context.settingsDataStore.edit { it[LIFETIME_COST] = (it[LIFETIME_COST] ?: 0.0) + delta }.let { }

    suspend fun resetLifetimeCost() =
        context.settingsDataStore.edit { it[LIFETIME_COST] = 0.0 }.let { }

    /** Persists a per-character override (empty string clears that field back to the default). */
    suspend fun setCharacterModels(name: String, models: CharacterModels) {
        context.settingsDataStore.edit { prefs ->
            prefs[chatKey(name)] = models.chatModel.orEmpty()
            prefs[imageKey(name)] = models.imageModel.orEmpty()
        }
    }

    /** Reads any stored override for [name]; missing/blank fields come back null. */
    suspend fun characterModels(name: String): CharacterModels {
        val prefs = context.settingsDataStore.data.first()
        return CharacterModels(
            chatModel = prefs[chatKey(name)]?.ifBlank { null },
            imageModel = prefs[imageKey(name)]?.ifBlank { null },
        )
    }

    private fun Preferences.toSettings() = Settings(
        defaultChatModel = this[DEFAULT_CHAT] ?: Settings.DEFAULT_CHAT_MODEL,
        defaultImageModel = this[DEFAULT_IMAGE] ?: Settings.DEFAULT_IMAGE_MODEL,
        fxIntensity = this[FX_INTENSITY] ?: 1f,
        lifetimeCostUsd = this[LIFETIME_COST] ?: 0.0,
    )

    private companion object {
        val DEFAULT_CHAT = stringPreferencesKey("default_chat_model")
        val DEFAULT_IMAGE = stringPreferencesKey("default_image_model")
        val FX_INTENSITY = floatPreferencesKey("fx_intensity")
        val LIFETIME_COST = doublePreferencesKey("lifetime_cost_usd")
        fun chatKey(name: String) = stringPreferencesKey("cm_chat::$name")
        fun imageKey(name: String) = stringPreferencesKey("cm_image::$name")
    }
}
