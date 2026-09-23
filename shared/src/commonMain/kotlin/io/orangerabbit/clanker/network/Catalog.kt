package io.orangerabbit.clanker.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One OpenRouter model entry, normalized for catalog display. Optional fields
 * are null when absent or unparseable (e.g. non-numeric pricing strings).
 */
data class ModelSummary(
    val id: String,
    val name: String,
    val contextLength: Long?,
    val pricingPrompt: Double?,
    val pricingCompletion: Double?,
)

/** Shared decoder: model entries carry many fields we don't model (architecture, modality, ...). */
private val catalogJson = Json { ignoreUnknownKeys = true }

/**
 * Parses an OpenRouter `GET /api/v1/models` response body into [ModelSummary]s
 * sorted by name. Pricing strings that are absent or non-numeric parse to null
 * rather than throwing, so one malformed model entry cannot break the catalog.
 */
internal fun parseCatalog(body: String): List<ModelSummary> =
    catalogJson.decodeFromString<ModelsResponse>(body).data
        .map { entry ->
            ModelSummary(
                id = entry.id,
                name = entry.name,
                contextLength = entry.contextLength,
                pricingPrompt = entry.pricing?.prompt?.toDoubleOrNull(),
                pricingCompletion = entry.pricing?.completion?.toDoubleOrNull(),
            )
        }
        .sortedBy { it.name }

@Serializable
private data class ModelsResponse(val data: List<ModelEntry>)

@Serializable
private data class ModelEntry(
    val id: String,
    val name: String,
    @SerialName("context_length") val contextLength: Long? = null,
    val pricing: ModelPricing? = null,
)

@Serializable
private data class ModelPricing(
    val prompt: String? = null,
    val completion: String? = null,
)