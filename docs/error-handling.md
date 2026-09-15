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
            apiKey = "wl_live_yoursdkkeyhere000000000000000000"
        )
    }
}
```

---

### `InvalidApiKeyFormat`

**When:** The SDK key passed to `configure()` does not match the expected format: `wl_live_` or `wl_test_` followed by exactly 32 alphanumeric characters.

**Fix:** Verify your SDK key in the [WarpLink dashboard](https://warplink.app) under **API Keys**. Ensure you're copying the full key.

**Note:** `configure()` does **not** throw on a malformed key. It logs a warning, dispatches `WarpLinkError.InvalidApiKeyFormat` to `options.onLink` (if provided), and leaves the SDK unconfigured (`isConfigured == false`). This keeps a bad key from crashing `Application.onCreate()`. To surface it during development, read it from `onLink`:

```kotlin
WarpLink.configure(
    context = this,
    apiKey = apiKey,
    options = WarpLinkOptions(
        onLink = { result ->
            result.onFailure { error ->
                if (error is WarpLinkError.InvalidApiKeyFormat) {
                    Log.e("MyApp", "Invalid API key format: ${error.message}")
                }
            }
        }
    )
)
```

---

### `InvalidApiKey`

**When:** The server rejects the key (HTTP 401 or 403). The key may be an API key rather than an SDK key, or it may be revoked, expired, or incorrect.

**Fix:**
1. Confirm you are using an **SDK key**. Install attribution requires one, and an API key cannot record installs whatever scopes it holds. Create one at **API Keys** > **SDK key** and pass it to `WarpLink.configure()`
2. Confirm the key string matches the dashboard value exactly. A key that is still well formed but altered by a typo or a stale build config passes the local format check and is rejected by the server
3. Verify the key is still active in the dashboard
4. Generate a new key if the current one was revoked

**Telltale symptom:** deep links resolve normally and Logcat shows `API key validated successfully`, but the deferred check fails with `InvalidApiKey` and no installs appear in your dashboard. That is an API key in an SDK slot.

---

### `NetworkError(cause: Throwable)`

**When:** A network request fails — no internet connectivity, DNS resolution failure, or request timeout.

**Fix:** Check device connectivity and show an offline state. A retry loop of your own is not the first thing to reach for here, because the SDK has already run one.

Before you see this, the SDK has already tried again. A resolve or a deferred check that fails on a dead or flaky connection, or with a 5xx, is attempted up to three times, with short waits between attempts. Every attempt has its own time limit, so one that hangs rather than fails cannot swallow the others, and the whole sequence is limited to about twelve seconds. The limit is enforced as total elapsed time as well as per attempt, so a server that answers very slowly, a byte at a time, cannot stretch a tap's resolution past it. A weak connection usually delivers on the second attempt, in about eight. By the time `NetworkError` reaches you, all of those attempts have failed. Refusals are never retried: a 404, a 403 and an expired link are answered on the first attempt, because the server will give the same answer to the second.

A failed resolve also releases the SDK's duplicate-tap guard, so a user who taps the same link again straight away really re-resolves it rather than being handed the failure back.

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
| 401 | Unauthorized | Check the key is active and correct |
| 403 | Forbidden | A password protected link returns `PasswordRequired`, otherwise confirm it is an SDK key, not an API key |
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

**When:** A URI is not a recognized WarpLink App Link. Recognized hosts are `aplnk.to` plus any verified custom domains returned by `/sdk/validate`. Foreign URIs fail fast locally with `InvalidUrl` and never hit the network.

**Fix:** In the opt-out model you rarely see this: automatic handling ignores foreign URIs, so `onLink` only fires for real WarpLink links. If you call `handleDeepLink` manually, it is safe to pass any `intent.data`; foreign hosts simply return `InvalidUrl`. Custom domains resolve automatically once your SDK key validates (no manual allowlist needed).

---

### `LinkNotFound`

**When:** The link slug does not exist, or the link has been deactivated or expired (HTTP 404).

**Fix:**
1. Verify the link exists in the [WarpLink dashboard](https://warplink.app)
2. Check that the link is active (not expired or disabled)
3. Ensure the slug in the URL matches

---

### `PasswordRequired`

**When:** The link is password protected (HTTP 403). Resolving it returns no destination and no platform URLs, because the password is checked in the browser and the app never sees it.

**Fix:** Open the short URL itself in a browser. The password form lives there, and a correct password redirects on to the destination.

```kotlin
is WarpLinkError.PasswordRequired -> {
    startActivity(Intent(Intent.ACTION_VIEW, tappedUri))
}
```

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
                // Rate limited. Back off.
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
        is WarpLinkError.PasswordRequired -> {
            // Password checked in the browser, never in the app
            startActivity(Intent(Intent.ACTION_VIEW, tappedUri))
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
    apiKey = "wl_live_yoursdkkeyhere000000000000000000",
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
