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
        → Captures fingerprint signals (IP, language, timezone)
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
    → Automatic handling resolves it (or you call WarpLink.handleDeepLink)
    → SDK validates the host is a WarpLink domain (aplnk.to or a verified custom domain)
    → SDK extracts the slug from the URI path
    → SDK calls GET /links/resolve/{slug}?domain={host}
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
    → Edge captures fingerprint signals (IP, language, timezone)
    → Stores signals as a deferred payload (keyed by fingerprint)
    → Sets Play Install Referrer with WarpLink parameters
    → Redirects user to Play Store

User installs and opens the app
    → SDK detects first launch (no-backup completion marker)
    → SDK reads Play Install Referrer via InstallReferrerClient
    → If referrer contains utm_source=warplink:
        → SDK sends accept_language + timezone offset alongside the referrer to POST /attribution/match
        → Deterministic match (confidence 1.0)
    → If referrer unavailable (sideloaded, no Play Services):
        → SDK sends accept_language + timezone offset to POST /attribution/match
        → Server derives the IP and computes the fingerprint from both sides
        → If fingerprint matches → probabilistic match (confidence 0.20 to 0.85 before multipliers)
    → SDK returns WarpLinkDeepLink with isDeferred = true (dispatched to onLink)
    → SDK records completion and caches the result
    → Your app routes the user to the intended content
```

## SDK Internals

### Initialization

When you call `WarpLink.configure(context, apiKey, options)`:

1. **Format validation**: checks the SDK key matches `wl_live_` or `wl_test_` + 32 alphanumeric characters. On mismatch it does **not** throw: it logs a warning, dispatches `WarpLinkError.InvalidApiKeyFormat` to `onLink`, and leaves the SDK unconfigured.
2. **Component setup** — creates internal API client, storage, fingerprint collector, install referrer reader, and logger. Retains `applicationContext` (not the provided context).
3. **Server validation** — async call to `/sdk/validate` to verify the key is active and to load the org's usable link domains (so custom domains resolve). Successful validation is cached for 24 hours.
4. **Automatic handling** — if enabled, registers `ActivityLifecycleCallbacks` for cold-start deep links and fires the first-launch deferred check, dispatching results to `onLink`.

### First Launch Detection

The SDK tracks first-launch state with two markers stored in the app's no-backup files directory (`noBackupFilesDir`): **attempted** (a check has started) and **completed** (a definitive server response arrived). The deferred request runs until it completes; completion is consumed only on a definitive response, so an offline first launch retries next launch. Because the markers are excluded from Android Auto Backup, a reinstall is treated as a fresh first launch. If that directory cannot be written, the completion marker falls back to `SharedPreferences`, bound to the install's first-install time so a restore cannot carry it onto a reinstall.

### Signal Collection

On every attribution request, the Play Install Referrer path included, the SDK sends three signals:

| Signal | Source |
|--------|--------|
| Accept-Language | `Locale.getDefault().toLanguageTag()` (raw; server normalizes) |
| Timezone offset | `-(TimeZone.getDefault().getOffset(now) / 60000)` (minutes, JS convention, DST-aware) |
| Timezone name | `ZoneId.systemDefault().id` (IANA, e.g. `America/Toronto`; omitted when the zone has no tzdb entry) |

These raw signals are sent to the server, which derives the client IP and computes the fingerprint. The SDK does **not** hash or compute fingerprints locally, and does **not** send User-Agent or screen dimensions (they never matched browser vs. native app).

### Attribution Caching

After a definitive deferred check, the result (match or confirmed no-match) is cached and the completion marker is set. This means:

- The attribution API is called at most once per successful check per install
- Subsequent calls to `checkDeferredDeepLink` return instantly from cache
- A failed (e.g., offline) attempt is not cached, so it retries on the next launch

### Threading

- All network requests execute on a cached thread pool (background threads)
- All callbacks are dispatched to the main thread via `Handler(Looper.getMainLooper())`
- `WarpLink.isConfigured` and `configure()` are thread-safe via `synchronized` blocks

## Related Guides

- [Deferred Deep Links](deferred-deep-links.md) — detailed deferred deep link flow
- [Attribution](attribution.md) — matching tiers and confidence scores
- [API Reference](api-reference.md) — all public types and methods
- [Integration Guide](integration-guide.md) — initial SDK setup
