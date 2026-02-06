package app.warplink

data class WarpLinkOptions(
    val apiEndpoint: String = "https://api.warplink.app/v1",
    val debugLogging: Boolean = false,
    val matchWindowHours: Int = 72
)
