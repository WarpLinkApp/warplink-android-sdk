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
val SDK_VERSION: String // "1.1.0"
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

Configure the SDK with your SDK key. Call once, typically in `Application.onCreate()`.

The `apiKey` parameter takes an **SDK key**, created in the dashboard under **API Keys** > **SDK key**. An API key has the same shape but cannot record install attribution.

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `context` | `Context` | Android context (typically `Application`). The SDK retains `applicationContext` internally. An `Application` context is required for automatic cold-start handling. |
| `apiKey` | `String` | Your WarpLink SDK key. Must match the format `wl_live_` or `wl_test_` followed by 32 alphanumeric characters. |
| `options` | `WarpLinkOptions` | Configuration and the `onLink` callback. Defaults to `WarpLinkOptions()`. |

**Does not throw.**
- A malformed SDK key does **not** throw. `configure()` logs a warning, dispatches `WarpLinkError.InvalidApiKeyFormat` to `options.onLink` (if provided), and leaves the SDK unconfigured (`isConfigured == false`). This makes it safe to call from `Application.onCreate()`.

**Behavior:**
- On a valid key, initializes internal components and performs async server-side validation via `/sdk/validate` (result cached 24 hours). The validation response also supplies verified custom link domains, which the SDK then resolves.
- If `options.automaticDeepLinks` is `true` (default) and the context is an `Application`, registers `ActivityLifecycleCallbacks` to resolve cold-start deep links and dispatch them to `onLink`. The same flag gates the warm-start path, so `onNewIntent` is a no-op when it is `false`.
- If `options.automaticDeferredDeepLinks` is `true` (default), fires the first-launch deferred check and dispatches any match to `onLink`.

**Example:**

```kotlin
// Opt-out model: one callback receives cold-start, warm-start, and deferred links
WarpLink.configure(
    context = this,
    apiKey = "wl_live_yoursdkkeyhere000000000000000000",
    options = WarpLinkOptions(
        onLink = { result ->
            result.onSuccess { link -> router.handle(link) }
        }
    )
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

Resolve an App Link URI to a deep link. Optional in the opt-out model (cold start is automatic via `configure`); use it for manual integrations or when `automaticDeepLinks = false`. Deduped against automatic dispatch of the same URI.

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `uri` | `Uri` | The App Link URI received by the Activity (from `intent?.data`). |
| `callback` | `(Result<WarpLinkDeepLink>) -> Unit` | Called with the resolved deep link or an error. **Always called on the main thread.** |

**Errors (returned via `Result.failure`):**
- `WarpLinkError.NotConfigured` — SDK not configured yet
- `WarpLinkError.InvalidUrl` — URI is not a recognized WarpLink domain (`aplnk.to`)
- `WarpLinkError.LinkNotFound` — Link does not exist or is inactive
- `WarpLinkError.PasswordRequired`: Link is password protected, so it resolves to nothing
- `WarpLinkError.NetworkError(cause)` — Network request failed
- `WarpLinkError.ServerError(statusCode, message)` — API returned an error
- `WarpLinkError.InvalidApiKey`: SDK key rejected by server
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

#### `onNewIntent(intent)`

```kotlin
fun onNewIntent(intent: Intent)
```

Warm-start bridge for the automatic model. Call from your Activity's `onNewIntent` so links that arrive while your task is already running reach `onLink`. This one line is unavoidable: `ActivityLifecycleCallbacks` has no new-intent hook.

**Requirements:**
- An `onLink` callback must be configured (otherwise this is a no-op).
- `options.automaticDeepLinks` must be `true`, which is the default. With it set to `false` this is a no-op and you route warm start yourself with `handleDeepLink`, the same way you route cold start.
- The Activity should use `android:launchMode="singleTask"` (or `singleTop`) so warm-start intents are delivered to the existing task.

**Example:**

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    WarpLink.onNewIntent(intent)
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
- Fires automatically from `configure()` when `automaticDeferredDeepLinks = true` (the default); call it directly only when you have disabled that flag.
- On first launch: reads Play Install Referrer (deterministic), then falls back to fingerprint matching (probabilistic). Sends device signals to the attribution API and returns the match result.
- Completion is recorded only on a definitive server response, so an offline first launch retries next launch. Once complete, subsequent launches return the cached result with no network call. The completion marker is stored in `noBackupFilesDir`, so a reinstall re-runs the check.
- The matched deep link has `isDeferred = true` and includes `matchType`, `matchConfidence`, and `matchGuaranteed`.

**Errors (returned via `Result.failure`):**
- `WarpLinkError.NotConfigured` — SDK not configured yet
- `WarpLinkError.NetworkError(cause)` — Network request failed
- `WarpLinkError.ServerError(statusCode, message)` — API returned an error
- `WarpLinkError.InvalidApiKey`: SDK key rejected by server
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

Configuration options and the single link callback. The SDK is opt-out: the automatic flags default to `true`.

```kotlin
data class WarpLinkOptions(
    val apiEndpoint: String = "https://api.warplink.app/v1",
    val debugLogging: Boolean = false,
    val automaticDeepLinks: Boolean = true,
    val automaticDeferredDeepLinks: Boolean = true,
    val linkDomains: List<String> = emptyList(),
    val onLink: ((Result<WarpLinkDeepLink>) -> Unit)? = null
)
```

### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `apiEndpoint` | `String` | `"https://api.warplink.app/v1"` | The API endpoint URL. Override for testing or custom deployments. |
| `debugLogging` | `Boolean` | `false` | Enable debug logging with the `WarpLink` tag in Logcat. |
| `automaticDeepLinks` | `Boolean` | `true` | Resolve incoming deep links and dispatch them to `onLink`: cold start via `ActivityLifecycleCallbacks`, warm start via `onNewIntent`. Set `false` to handle both yourself with `handleDeepLink`. |
| `automaticDeferredDeepLinks` | `Boolean` | `true` | Auto-fire the first-launch deferred check and dispatch a match to `onLink`. Set `false` to call `checkDeferredDeepLink` yourself. |
| `linkDomains` | `List<String>` | `emptyList()` | Your verified custom link domains, for example `listOf("links.yourapp.com")`. Optional. Declaring them makes them recognized from the first line of `configure()`, before `/sdk/validate` answers, which is what a link opened on a first launch needs. Full URLs are accepted and reduced to their host. See [Custom link domains](#custom-link-domains). |
| `onLink` | `((Result<WarpLinkDeepLink>) -> Unit)?` | `null` | Single sink for cold-start, warm-start, and deferred matches (disambiguate deferred via `WarpLinkDeepLink.isDeferred`). A deferred no-match is not dispatched. Failures are delivered as `Result.failure`. Invoked on the main thread. |

**Example:**

```kotlin
// Opt-out model
val options = WarpLinkOptions(
    onLink = { result -> result.onSuccess { link -> router.handle(link) } }
)

// Manual: disable automatic handling
val manual = WarpLinkOptions(
    debugLogging = true,
    automaticDeepLinks = false,
    automaticDeferredDeepLinks = false
)

WarpLink.configure(
    context = this,
    apiKey = "wl_live_yoursdkkeyhere000000000000000000",
    options = options
)
```

> **Removed in 1.1.0:** `matchWindowHours`. The match window is server-authoritative (set per link); the client option was inert.

### Custom link domains

The SDK always recognizes `aplnk.to`. It also loads the domains your organization has verified from `/sdk/validate` and caches them, so a custom domain works from the second launch onward with no extra setup.

Declaring your domains locally covers the first launch as well. `handleDeepLink` and the automatic handling both have to answer "is this link mine" the moment the intent arrives, and on a genuinely first launch (or any launch with no network) no server answer exists yet. Without a local declaration, a custom-domain link on that launch is handed back to your app unresolved.

Declare them either way, whichever suits your project. Both are read at `configure()` and the results are unioned, so you can use both.

In code:

```kotlin
WarpLink.configure(
    context = this,
    apiKey = "wl_live_yoursdkkeyhere000000000000000000",
    options = WarpLinkOptions(
        linkDomains = listOf("links.yourapp.com", "go.yourapp.com"),
        onLink = { result -> /* ... */ }
    )
)
```

Or in `AndroidManifest.xml`, inside `<application>`, as a comma separated list:

```xml
<meta-data
    android:name="app.warplink.DOMAINS"
    android:value="links.yourapp.com,go.yourapp.com" />
```

Values are normalized for you: whitespace and letter case do not matter, and a full URL is reduced to its host, so `https://links.yourapp.com/` and `links.yourapp.com` are the same declaration. `www.` is never stripped, since it is a different host.

Declaring a domain does not replace verifying it. It tells the SDK which links are its own; the domain still has to be verified and live in your dashboard, and still needs its own `<data>` host in your App Links intent filter.

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
    val matchConfidence: Double? = null,
    val matchGuaranteed: Boolean = false
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
| `matchGuaranteed` | `Boolean` | `true` only when the match was deterministic. Gate anything sensitive (auto sign-in, showing personal data) on this rather than on a confidence threshold: a probabilistic match is a best guess from a network-shaped fingerprint and can name the wrong user. |

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
| `DETERMINISTIC` | Matched via Play Install Referrer. Confidence is always 1.0 and `matchGuaranteed` is `true`. |
| `PROBABILISTIC` | Matched via fingerprint. Confidence varies by time window (0.20 to 0.85), reduced further when the fingerprint bucket was ambiguous or the click IP was widely shared. `matchGuaranteed` is always `false`. |

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
| `InvalidApiKeyFormat` | SDK key format is invalid (must be `wl_live_` or `wl_test_` + 32 alphanumeric characters). Reported to `onLink` and logged; `configure()` does **not** throw. |
| `InvalidApiKey` | SDK key was rejected by the server. It was revoked, mistyped, or is an API key. |
| `NetworkError(cause: Throwable)` | Network request failed with an underlying cause. |
| `ServerError(statusCode: Int, message: String)` | API returned an error response. |
| `InvalidUrl` | The URI is not a recognized WarpLink App Link (host is not `aplnk.to` or a verified custom domain). |
| `LinkNotFound` | The link was not found (404) or is no longer active. |
| `PasswordRequired` | The link is password protected, so it resolves to no destination and no platform URLs. |
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
        is WarpLinkError.PasswordRequired -> { /* Open the short URL in a browser */ }
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
