package app.warplink.internal

/** The names [Storage] keeps its device state under. */
internal object StorageKeys {
    const val PREFS_NAME = "warplink_prefs"
    const val API_KEY_VALIDATED_AT = "api_key_validated_at"
    const val DEVICE_SEEN_ATTRIBUTION = "device_seen_attribution"
    const val GATE_FALLBACK_INSTALL_TIME = "deferred_completed_for_install"
    const val CACHED_DOMAINS = "cached_domains"
    const val CACHED_ATTRIBUTION = "cached_attribution"
    const val MARKER_DEFERRED_COMPLETED = "warplink_deferred_completed"
    const val MARKER_DEFERRED_ATTEMPTED = "warplink_deferred_attempted"
}
