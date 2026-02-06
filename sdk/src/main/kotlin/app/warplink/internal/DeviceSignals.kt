package app.warplink.internal

internal data class DeviceSignals(
    val acceptLanguage: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val timezoneOffset: Int,
    val userAgent: String
)
