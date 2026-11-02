package app.warplink.internal

/**
 * The environment an API key belongs to, which decides the device-state
 * namespace the SDK reads and writes.
 *
 * A developer can run a `wl_test_` build and a `wl_live_` build on one device.
 * Test state lives under its own names, so Test never reads, writes or clears
 * what the live build stored. Production keeps the names every earlier release
 * used, so an upgrade resets nothing.
 */
internal enum class KeyEnvironment {
    PRODUCTION,
    TEST;

    /**
     * [name] in this environment's namespace, with [extension] after the
     * suffix. Production returns the name every earlier release used.
     */
    fun scoped(name: String, extension: String = ""): String = when (this) {
        PRODUCTION -> name + extension
        TEST -> "${name}_test$extension"
    }

    companion object {
        private const val TEST_PREFIX = "wl_test_"

        /** Any key that does not start with `wl_test_` is Production, as before. */
        fun of(apiKey: String): KeyEnvironment =
            if (apiKey.startsWith(TEST_PREFIX)) TEST else PRODUCTION
    }
}
