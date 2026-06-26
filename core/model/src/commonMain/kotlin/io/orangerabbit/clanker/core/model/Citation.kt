package io.orangerabbit.clanker.core.model

/**
 * A web source cited by a server-executed web tool (OpenRouter `url_citation` annotation). Display
 * metadata only; the model has already incorporated the content into its answer.
 */
data class Citation(
    val url: String,
    val title: String? = null,
    val content: String? = null,
    val startIndex: Int? = null,
    val endIndex: Int? = null,
)
