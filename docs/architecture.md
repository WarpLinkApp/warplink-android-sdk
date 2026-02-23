# Architecture Overview

How the WarpLink Android SDK communicates with the WarpLink platform. This overview is aimed at SDK consumers — it explains the flows you need to understand, not internal infrastructure details.

## Link Creation

Developers create links via the [WarpLink dashboard](https://warplink.app) or the [REST API](https://api.warplink.app/v1). Each link has:

- A **short URL** (e.g., `https://aplnk.to/abc123`)
- A **destination URL** — where the user should end up
- An optional **Android deep link URL** (e.g., `myapp://product/123`)
- Optional **custom parameters** — arbitrary key-value data attached to the link

When a link is created, it's stored in the database and cached at the edge for sub-10ms resolution globally. The `assetlinks.json` file is generated automatically for App Links verification.

## Click Flow

When a user clicks a WarpLink URL:

```
User taps link
    → Edge server resolves the link (sub-10ms)
    → Parses User-Agent
    → Bot? → Returns HTML with OG/Twitter Card tags (social previews)
    → Real user on Android with app installed?
        → 302 redirect → Android App Links opens your app
    → Real user on Android without app?
        → Captures browser signals (IP, UA, language, screen, timezone)
        → Sets Play Install Referrer (utm_source=warplink&utm_content={link_id})
        → Redirects to Play Store (or fallback URL)
    → Other platform?
        → 302 redirect to destination URL
```

The key insight: the edge server decides where to send the user based on their device, whether the app is installed, and the link's configuration.

## App Link Resolution

When Android opens your app via an App Link:

```
Android opens your Activity with the URI as intent data
    → Your Activity reads intent?.data in onCreate() or onNewIntent()
    → Your app calls WarpLink.handleDeepLink(uri, callback)
    → SDK validates the URI is a WarpLink domain (aplnk.to)
    → SDK extracts the slug from the URI path
    → SDK calls GET /links/resolve/{slug}?domain=aplnk.to
    → API returns link data (destination, Android URL, custom params)
    → SDK returns WarpLinkDeepLink to your callback
    → Your app routes the user to the intended content
```

The API call resolves the short link slug to its full link data, including the destination URL, any Android-specific deep link URL, and custom parameters.

### How App Links Verification Works

When the user installs your app, Android checks the `android:autoVerify="true"` attribute on your intent filter. It then fetches `https://aplnk.to/.well-known/assetlinks.json` and verifies that your app's package name and SHA256 signing certificate are listed. Once verified, links to `aplnk.to` open directly in your app without a disambiguation dialog.

## Deferred Deep Link Flow

When a user clicks a link before the app is installed:

```
User taps link (app not installed)
    → Edge captures browser signals (IP, UA, language, screen, timezone)
    → Stores signals as a deferred payload (keyed by fingerprint)
    → Sets Play Install Referrer with WarpLink parameters
    → Redirects user to Play Store

User installs and opens the app
    → SDK detects first launch (SharedPreferences)
    → SDK reads Play Install Referrer via InstallReferrerClient
    → If referrer contains utm_source=warplink:
        → Deterministic match (confidence 1.0)
    → If referrer unavailable (sideloaded, no Play Services):
        → SDK collects device signals (screen, timezone, language, UA)
        → SDK calls POST /attribution/match with device signals
        → Server compares signals against stored click signals
        → If fingerprint matches → probabilistic match (confidence 0.4–0.85)
    → SDK returns WarpLinkDeepLink with isDeferred = true
    → SDK caches the result in SharedPreferences
    → Your app routes the user to the intended content
```

## SDK Internals

### Initialization

When you call `WarpLink.configure(context, apiKey, options)`:

1. **Format validation** — checks the API key matches `wl_live_` or `wl_test_` + 32 alphanumeric characters. Throws `WarpLinkError.InvalidApiKeyFormat` on mismatch.
2. **Component setup** — creates internal API client, storage, fingerprint collector, install referrer reader, and logger. Retains `applicationContext` (not the provided context).
3. **Server validation** — async call to `/sdk/validate` to verify the key is active.
4. **Validation caching** — successful validation is cached for 24 hours (stored in SharedPreferences) to avoid redundant network calls.

### First Launch Detection

The SDK uses SharedPreferences to track whether the app has been launched before. On the very first launch, `checkDeferredDeepLink` performs the attribution request. On all subsequent launches, it returns the cached result.

**Note:** SharedPreferences may persist across app reinstalls if Android Auto Backup is enabled. See [Deferred Deep Links](deferred-deep-links.md) for edge cases and backup exclusion configuration.

### Signal Collection

On first launch, the SDK collects:

| Signal | Source |
|--------|--------|
| Accept-Language | Device locale configuration |
| Screen dimensions | `WindowManager.defaultDisplay` (width x height in pixels) |
| Timezone offset | `TimeZone.getDefault().rawOffset` (minutes from UTC) |
| User-Agent | Custom WarpLink UA string with SDK version |

These raw signals are sent to the server, which computes the fingerprint using the client IP + these signals. The SDK does **not** hash or compute fingerprints locally.

### Attribution Caching

After the first deferred deep link check, the result (whether a match was found or not) is cached in SharedPreferences. This means:

- The attribution API is called at most once per app install
- Subsequent calls to `checkDeferredDeepLink` return instantly from cache
- No unnecessary network requests on app launches after the first

### Threading

- All network requests execute on a cached thread pool (background threads)
- All callbacks are dispatched to the main thread via `Handler(Looper.getMainLooper())`
- `WarpLink.isConfigured` and `configure()` are thread-safe via `synchronized` blocks

## Related Guides

- [Deferred Deep Links](deferred-deep-links.md) — detailed deferred deep link flow
- [Attribution](attribution.md) — matching tiers and confidence scores
- [API Reference](api-reference.md) — all public types and methods
- [Integration Guide](integration-guide.md) — initial SDK setup
