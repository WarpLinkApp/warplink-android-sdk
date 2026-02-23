# Error Handling

The WarpLink SDK uses the `WarpLinkError` sealed class for all error cases. Every error extends `Exception` and provides a human-readable `message`.

## Error Cases

### `NotConfigured`

**When:** Any SDK method is called before `WarpLink.configure()`.

**Fix:** Call `configure()` during app initialization — in your `Application.onCreate()`.

```kotlin
// Ensure this runs before any handleDeepLink or checkDeferredDeepLink calls
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WarpLink.configure(
            context = this,
            apiKey = "wl_live_your_api_key_here_abcdefgh"
        )
    }
}
```

---

### `InvalidApiKeyFormat`

**When:** The API key passed to `configure()` does not match the expected format: `wl_live_` or `wl_test_` followed by exactly 32 alphanumeric characters.

**Fix:** Verify your API key in the [WarpLink dashboard](https://warplink.app) under **Settings > API Keys**. Ensure you're copying the full key.

**Note:** Unlike the iOS SDK (which silently returns), the Android SDK **throws** `WarpLinkError.InvalidApiKeyFormat` from `configure()`. Wrap in a try-catch during development if needed:

```kotlin
try {
    WarpLink.configure(context = this, apiKey = apiKey)
} catch (e: WarpLinkError.InvalidApiKeyFormat) {
    Log.e("MyApp", "Invalid API key format: ${e.message}")
}
```

---

### `InvalidApiKey`

**When:** The server rejects the API key (HTTP 401 or 403). The key may be revoked, expired, or incorrect.

**Fix:**
1. Check that you're using the correct key (live vs. test)
2. Verify the key is still active in the dashboard
3. Generate a new key if the current one was revoked

---

### `NetworkError(cause: Throwable)`

**When:** A network request fails — no internet connectivity, DNS resolution failure, or request timeout.

**Fix:** Retry with exponential backoff. Check device connectivity before retrying.

```kotlin
result.onFailure { error ->
    if (error is WarpLinkError.NetworkError) {
        val cause = error.cause
        Log.e("MyApp", "Network error: ${cause?.message}")
        // Retry with backoff or show offline message
    }
}
```

---

### `ServerError(statusCode: Int, message: String)`

**When:** The WarpLink API returns a non-2xx HTTP status code.

**Fix:** Check the `statusCode` to determine the appropriate response:

| Status Code | Meaning | Action |
|-------------|---------|--------|
| 401 | Unauthorized | Check API key |
| 403 | Forbidden | Check API key permissions |
| 429 | Rate limited | Retry after delay |
| 500 | Server error | Retry later, report if persistent |
| 503 | Service unavailable | Retry later |

```kotlin
result.onFailure { error ->
    if (error is WarpLinkError.ServerError) {
        when (error.statusCode) {
            429 -> retryAfterDelay()
            in 500..599 -> retryLater()
            else -> Log.e("MyApp", "Server error: ${error.message}")
        }
    }
}
```

---

### `InvalidUrl`

**When:** A URI passed to `handleDeepLink()` is not a recognized WarpLink App Link. Currently, only the `aplnk.to` domain is recognized.

**Fix:** Verify the URI host is `aplnk.to`. If you're using a custom domain, note that custom domain support in the SDK requires a future update.

```kotlin
// Only pass WarpLink URIs to handleDeepLink
intent?.data?.let { uri ->
    if (uri.host == "aplnk.to") {
        WarpLink.handleDeepLink(uri) { result ->
            // ...
        }
    }
}
```

---

### `LinkNotFound`

**When:** The link slug does not exist, or the link has been deactivated or expired (HTTP 404).

**Fix:**
1. Verify the link exists in the [WarpLink dashboard](https://warplink.app)
2. Check that the link is active (not expired or disabled)
3. Ensure the slug in the URL matches

---

### `DecodingError(cause: Throwable)`

**When:** The API response could not be parsed. This may indicate an SDK version mismatch with the API.

**Fix:** Update the SDK to the latest version. If the issue persists, enable `debugLogging` and report the error.

---

## Complete Error Handling Example

```kotlin
fun handleWarpLinkError(error: Throwable) {
    when (error) {
        is WarpLinkError.NotConfigured -> {
            // Programming error — configure SDK earlier in app lifecycle
            throw IllegalStateException("WarpLink SDK not configured")
        }
        is WarpLinkError.InvalidApiKeyFormat -> {
            // Programming error — check API key format
            throw IllegalStateException("Invalid WarpLink API key format")
        }
        is WarpLinkError.InvalidApiKey -> {
            // API key revoked or incorrect
            showAlert("Authentication error. Please update the app.")
        }
        is WarpLinkError.NetworkError -> {
            // No connectivity or timeout
            showAlert("Network error. Please try again.")
        }
        is WarpLinkError.ServerError -> {
            if (error.statusCode == 429) {
                // Rate limited — back off
                retryAfterDelay()
            } else {
                showAlert("Server error. Please try again later.")
            }
        }
        is WarpLinkError.InvalidUrl -> {
            // URL is not a WarpLink URL — ignore or log
            Log.w("MyApp", "Not a WarpLink URL")
        }
        is WarpLinkError.LinkNotFound -> {
            // Link deleted or expired
            showAlert("This link is no longer available.")
        }
        is WarpLinkError.DecodingError -> {
            // SDK may be outdated
            showAlert("Please update the app to the latest version.")
        }
    }
}
```

Usage:

```kotlin
WarpLink.handleDeepLink(uri) { result ->
    result.onSuccess { deepLink ->
        navigateTo(deepLink.destination)
    }.onFailure { error ->
        handleWarpLinkError(error)
    }
}
```

## Logcat Debugging

Enable debug logging to see all SDK activity in Logcat:

```kotlin
WarpLink.configure(
    context = this,
    apiKey = "wl_live_your_api_key_here_abcdefgh",
    options = WarpLinkOptions(debugLogging = true)
)
```

Filter Logcat by the `WarpLink` tag:

```bash
adb logcat -s WarpLink
```

## Related Guides

- [API Reference](api-reference.md) — `WarpLinkError` sealed class documentation
- [Troubleshooting](troubleshooting.md) — common issues and solutions
- [Integration Guide](integration-guide.md) — initial SDK setup
