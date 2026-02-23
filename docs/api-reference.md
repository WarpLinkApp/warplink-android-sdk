# API Reference

Complete reference for all public types and methods in the WarpLink Android SDK.

## WarpLink

The main entry point for the SDK. All methods are accessed via the singleton object.

```kotlin
object WarpLink
```

### Properties

#### `SDK_VERSION`

```kotlin
const val SDK_VERSION: String // "0.1.0"
```

The current SDK version string.

#### `isConfigured`

```kotlin
val isConfigured: Boolean
```

Whether the SDK has been configured via `configure()`. Thread-safe.

### Methods

#### `configure(context, apiKey, options)`

```kotlin
fun configure(
    context: Context,
    apiKey: String,
    options: WarpLinkOptions = WarpLinkOptions()
)
```

Configure the SDK with your API key. Must be called before any other SDK methods.

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `context` | `Context` | Android context (typically `Application`). The SDK retains `applicationContext` internally. |
| `apiKey` | `String` | Your WarpLink API key (e.g., `wl_live_xxx...`). Must match the format `wl_live_` or `wl_test_` followed by 32 alphanumeric characters. |
| `options` | `WarpLinkOptions` | Configuration overrides. Defaults to `WarpLinkOptions()`. |

**Throws:**

- `WarpLinkError.InvalidApiKeyFormat` if the API key doesn't match the expected format.

**Behavior:**
- Validates API key format locally. Throws on invalid format (unlike iOS, which silently returns).
- On valid format, initializes internal components and performs async server-side API key validation via `/sdk/validate`.
- Server validation result is cached for 24 hours to avoid repeated network calls.

**Example:**

```kotlin
// Basic configuration
WarpLink.configure(
    context = this,
    apiKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
)

// With options
WarpLink.configure(
    context = this,
    apiKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345",
    options = WarpLinkOptions(debugLogging = true)
)
```

---

#### `handleDeepLink(uri, callback)`

```kotlin
fun handleDeepLink(
    uri: Uri,
    callback: (Result<WarpLinkDeepLink>) -> Unit
)
```

Handle an incoming App Link URI and resolve it to a deep link.

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `uri` | `Uri` | The App Link URI received by the Activity (from `intent?.data`). |
| `callback` | `(Result<WarpLinkDeepLink>) -> Unit` | Called with the resolved deep link or an error. **Always called on the main thread.** |

**Errors (returned via `Result.failure`):**
- `WarpLinkError.NotConfigured` — SDK not configured yet
- `WarpLinkError.InvalidUrl` — URI is not a recognized WarpLink domain (`aplnk.to`)
- `WarpLinkError.LinkNotFound` — Link does not exist or is inactive
- `WarpLinkError.NetworkError(cause)` — Network request failed
- `WarpLinkError.ServerError(statusCode, message)` — API returned an error
- `WarpLinkError.InvalidApiKey` — API key rejected by server
- `WarpLinkError.DecodingError(cause)` — Response parsing failed

**Example:**

```kotlin
// In Activity
intent?.data?.let { uri ->
    WarpLink.handleDeepLink(uri) { result ->
        result.onSuccess { deepLink ->
            Log.d("MyApp", "Link ID: ${deepLink.linkId}")
            Log.d("MyApp", "Destination: ${deepLink.destination}")
            deepLink.deepLinkUrl?.let { url ->
                Log.d("MyApp", "Deep link URL: $url")
            }
        }.onFailure { error ->
            Log.e("MyApp", "Error: ${error.message}")
        }
    }
}
```

---

#### `checkDeferredDeepLink(callback)`

```kotlin
fun checkDeferredDeepLink(
    callback: (Result<WarpLinkDeepLink?>) -> Unit
)
```

Check for a deferred deep link on first launch. Returns `null` in the success case if no match was found.

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `callback` | `(Result<WarpLinkDeepLink?>) -> Unit` | Called with the matched deep link (or `null` if no match), or an error. **Always called on the main thread.** |

**Behavior:**
- On first launch: reads Play Install Referrer (deterministic), then falls back to fingerprint matching (probabilistic). Sends device signals to the attribution API and returns the match result.
- On subsequent launches: returns the cached result from SharedPreferences without a network call.
- The matched deep link has `isDeferred = true` and includes `matchType` and `matchConfidence`.

**Errors (returned via `Result.failure`):**
- `WarpLinkError.NotConfigured` — SDK not configured yet
- `WarpLinkError.NetworkError(cause)` — Network request failed
- `WarpLinkError.ServerError(statusCode, message)` — API returned an error
- `WarpLinkError.InvalidApiKey` — API key rejected by server
- `WarpLinkError.DecodingError(cause)` — Response parsing failed

**Example:**

```kotlin
WarpLink.checkDeferredDeepLink { result ->
    result.onSuccess { deepLink ->
        if (deepLink != null) {
            Log.d("MyApp", "Deferred match: ${deepLink.destination}")
            Log.d("MyApp", "Confidence: ${deepLink.matchConfidence}")
        } else {
            Log.d("MyApp", "No deferred deep link")
        }
    }.onFailure { error ->
        Log.e("MyApp", "Error: ${error.message}")
    }
}
```

---

## WarpLinkOptions

Configuration options for the SDK.

```kotlin
data class WarpLinkOptions(
    val apiEndpoint: String = "https://api.warplink.app/v1",
    val debugLogging: Boolean = false,
    val matchWindowHours: Int = 72
)
```

### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `apiEndpoint` | `String` | `"https://api.warplink.app/v1"` | The API endpoint URL. Override for testing or custom deployments. |
| `debugLogging` | `Boolean` | `false` | Enable debug logging with `WarpLink` tag in Logcat. |
| `matchWindowHours` | `Int` | `72` | The match window in hours for deferred deep link attribution. |

**Example:**

```kotlin
// Default options
val options = WarpLinkOptions()

// Custom options
val options = WarpLinkOptions(
    debugLogging = true,
    matchWindowHours = 48
)

WarpLink.configure(context = this, apiKey = "wl_live_...", options = options)
```

---

## WarpLinkDeepLink

Resolved deep link data returned by the SDK.

```kotlin
data class WarpLinkDeepLink(
    val linkId: String,
    val destination: String,
    val deepLinkUrl: String? = null,
    val customParams: Map<String, Any> = emptyMap(),
    val isDeferred: Boolean = false,
    val matchType: MatchType? = null,
    val matchConfidence: Double? = null
)
```

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `linkId` | `String` | The unique identifier of the link. |
| `destination` | `String` | The resolved destination URL. |
| `deepLinkUrl` | `String?` | The Android-specific deep link URL (e.g., `myapp://path`), if configured on the link. |
| `customParams` | `Map<String, Any>` | Custom parameters attached to the link. See note below. |
| `isDeferred` | `Boolean` | Whether this deep link was resolved via deferred attribution. |
| `matchType` | `MatchType?` | The type of attribution match (`DETERMINISTIC` or `PROBABILISTIC`). |
| `matchConfidence` | `Double?` | The confidence score of the attribution match (0.0 to 1.0). |

### Working with `customParams`

`customParams` is typed as `Map<String, Any>` because link parameters can contain mixed types (strings, numbers, booleans, nested objects).

Use safe casting when accessing values:

```kotlin
WarpLink.handleDeepLink(uri) { result ->
    result.onSuccess { deepLink ->
        // Safe casting for custom parameters
        val productId = deepLink.customParams["product_id"] as? String
        productId?.let { showProduct(it) }

        val discount = deepLink.customParams["discount"] as? Double
        discount?.let { applyDiscount(it) }
    }
}
```

---

## MatchType

The type of attribution match used to resolve a deferred deep link.

```kotlin
enum class MatchType {
    DETERMINISTIC,
    PROBABILISTIC
}
```

### Values

| Value | Description |
|-------|-------------|
| `DETERMINISTIC` | Matched via Play Install Referrer. Confidence is always 1.0. |
| `PROBABILISTIC` | Matched via enriched fingerprint. Confidence varies by time window (0.40–0.85). |

See [Attribution](attribution.md) for details on confidence scores.

---

## WarpLinkError

Typed errors for all SDK operations. Extends `Exception`.

```kotlin
sealed class WarpLinkError(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
```

### Subclasses

| Subclass | Description |
|----------|-------------|
| `NotConfigured` | SDK used before `configure()` was called. |
| `InvalidApiKeyFormat` | API key format is invalid (must be `wl_live_` or `wl_test_` + 32 alphanumeric characters). **Thrown by `configure()`.** |
| `InvalidApiKey` | API key was rejected by the server (revoked or incorrect). |
| `NetworkError(cause: Throwable)` | Network request failed with an underlying cause. |
| `ServerError(statusCode: Int, message: String)` | API returned an error response. |
| `InvalidUrl` | The URI is not a valid WarpLink App Link (not an `aplnk.to` domain). |
| `LinkNotFound` | The link was not found (404) or is no longer active. |
| `DecodingError(cause: Throwable)` | Response parsing failed. |

Use exhaustive `when` for handling:

```kotlin
result.onFailure { error ->
    when (error) {
        is WarpLinkError.NotConfigured -> { /* Call configure() first */ }
        is WarpLinkError.InvalidApiKeyFormat -> { /* Check key format */ }
        is WarpLinkError.InvalidApiKey -> { /* Regenerate key */ }
        is WarpLinkError.NetworkError -> { /* Retry with backoff */ }
        is WarpLinkError.ServerError -> { /* Log statusCode and message */ }
        is WarpLinkError.InvalidUrl -> { /* Not a WarpLink URL */ }
        is WarpLinkError.LinkNotFound -> { /* Check link in dashboard */ }
        is WarpLinkError.DecodingError -> { /* Update SDK */ }
        else -> { /* Unknown error */ }
    }
}
```

See [Error Handling](error-handling.md) for recommended recovery actions for each case.

## Thread Safety

- `WarpLink.isConfigured` is thread-safe (protected by `synchronized` lock).
- All callbacks (`handleDeepLink`, `checkDeferredDeepLink`) are dispatched to the **main thread**.
- `configure()` can be called from any thread, but should be called once during app initialization.
