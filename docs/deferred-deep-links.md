# Deferred Deep Links

Deferred deep links let you route users to specific content even when they don't have your app installed yet. The user clicks a link, installs your app from the Play Store, and on first launch the SDK matches them back to the original link.

## What Are Deferred Deep Links?

Standard App Links only work when the app is already installed. Deferred deep links solve the "click before install" problem:

1. User clicks a WarpLink URL (e.g., a product share link)
2. App is not installed — user is redirected to the Play Store
3. User installs the app
4. On first launch, the SDK matches the install to the original click
5. Your app routes the user to the intended content (e.g., the shared product)

Without deferred deep links, the user would land on your default home screen with no context about what brought them there.

## How It Works

The deferred deep link flow involves 8 steps:

1. **Click** — User taps a WarpLink URL in Chrome or another app
2. **Signal capture** — WarpLink's edge server captures browser signals (IP, User-Agent, Accept-Language, screen size, timezone) and stores a deferred payload in KV with `utm_source=warplink&utm_content={link_id}` as the Play Install Referrer parameter
3. **Store redirect** — User is redirected to the Play Store
4. **Install** — User installs and opens the app
5. **First launch detection** — The SDK detects this is the first launch (tracked via SharedPreferences)
6. **Play Install Referrer check** — The SDK reads the Play Install Referrer via `InstallReferrerClient`. If the referrer contains `utm_source=warplink`, a deterministic match is made (confidence 1.0)
7. **Fingerprint fallback** — If the referrer is unavailable (sideloaded app, no Google Play Services), the SDK collects device signals (screen size, timezone, preferred languages, user agent) and sends them to `/attribution/match`
8. **Match result** — The server returns a `WarpLinkDeepLink` with `isDeferred = true`

## Confidence Scores

The match confidence depends on the matching method and time elapsed since the click:

| Scenario | Confidence | Match Type |
|----------|------------|------------|
| Play Install Referrer (installed from Play Store via WarpLink) | 1.0 | `DETERMINISTIC` |
| Enriched fingerprint, < 1 hour since click | 0.85 | `PROBABILISTIC` |
| Enriched fingerprint, < 24 hours since click | 0.65 | `PROBABILISTIC` |
| Enriched fingerprint, < 72 hours since click | 0.40 | `PROBABILISTIC` |
| Multiple candidates matched | -0.15 per additional candidate | `PROBABILISTIC` |

**Recommendation:** Route to specific content when `matchConfidence` is above 0.5. Show generic onboarding when below 0.5.

## Match Window Configuration

The match window controls how far back the server looks for matching clicks. Default is 72 hours.

```kotlin
// Reduce match window to 48 hours for higher accuracy
WarpLink.configure(
    context = this,
    apiKey = "wl_live_your_api_key_here_abcdefgh",
    options = WarpLinkOptions(matchWindowHours = 48)
)
```

A shorter window reduces false positives but may miss users who take longer to install.

## Caching Behavior

- The SDK checks for a deferred deep link only on the first launch.
- The result (match or no match) is cached in SharedPreferences.
- Subsequent calls to `checkDeferredDeepLink` return the cached result without a network request.
- This means the attribution check happens exactly once per app install.

## Code Example

```kotlin
WarpLink.checkDeferredDeepLink { result ->
    result.onSuccess { deepLink ->
        if (deepLink == null) {
            // No deferred deep link — show default onboarding
            showOnboarding()
            return@onSuccess
        }

        // Route based on confidence
        val confidence = deepLink.matchConfidence ?: 0.0

        if (confidence > 0.5) {
            // High confidence — route to specific content
            val target = deepLink.deepLinkUrl ?: deepLink.destination
            navigateTo(target)
        } else {
            // Low confidence — show generic welcome with a hint
            showWelcome(suggestedContent = deepLink.destination)
        }
    }.onFailure { error ->
        // Network error on first launch — show default experience
        Log.e("MyApp", "Deferred deep link error: ${error.message}")
        showOnboarding()
    }
}
```

## Edge Cases

### Play Install Referrer Unavailability

The Play Install Referrer is the primary (deterministic) matching method on Android. However, it's unavailable when:

- The app is sideloaded (installed via `adb install` or direct APK)
- The device doesn't have Google Play Services (e.g., Huawei devices with HMS)
- The referrer data has expired (Play Store may not retain it indefinitely)

In these cases, the SDK automatically falls back to fingerprint matching (probabilistic). No code changes are needed — the fallback is transparent.

### Offline First Launch

If the device has no network connectivity on first launch, `checkDeferredDeepLink` will fail with `WarpLinkError.NetworkError`. The first-launch flag will have been consumed, so retrying after connectivity is restored will return the cached `null` result.

**Recommendation:** If network connectivity is critical for your first-launch experience, check for connectivity before calling `checkDeferredDeepLink`, or implement a retry mechanism that listens for network changes.

### SharedPreferences Persistence on Reinstall

Android may preserve SharedPreferences across app uninstall/reinstall if Auto Backup is enabled (the default). If SharedPreferences persists, the SDK will consider it a subsequent launch and return the previously cached result instead of performing a new attribution check.

**Recommendation:** Either set `android:allowBackup="false"` in your `AndroidManifest.xml`, or exclude WarpLink preferences from backup using [backup rules](https://developer.android.com/guide/topics/data/autobackup#IncludingFiles):

```xml
<!-- AndroidManifest.xml -->
<application
    android:fullBackupContent="@xml/backup_rules"
    ... >
```

```xml
<!-- res/xml/backup_rules.xml -->
<full-backup-content>
    <exclude domain="sharedpref"
        path="warplink_prefs.xml" />
</full-backup-content>
```

### Multiple Links Clicked Before Install

If a user clicks multiple WarpLink URLs before installing, only the **most recent** click is stored for matching. The server matches against the latest deferred payload for the fingerprint.

## Related Guides

- [Attribution](attribution.md) — detailed explanation of matching tiers
- [Error Handling](error-handling.md) — handling deferred deep link errors
- [Troubleshooting](troubleshooting.md) — common deferred deep link issues
- [Integration Guide](integration-guide.md) — initial SDK setup
