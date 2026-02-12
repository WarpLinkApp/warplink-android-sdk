package app.warplink.internal

internal data class LinkResponse(
    val id: String,
    val slug: String,
    val destinationUrl: String,
    val iosUrl: String?,
    val androidUrl: String?,
    val customParams: Map<String, Any>
)
