# Deferred Deep Links

Deferred deep links route users to specific content even when they don't have your app installed yet. The user clicks a link, installs your app from the Play Store, and on first launch the SDK matches them back to the original link.

With the opt-out model, this is automatic: `configure` fires the first-launch check for you and dispatches any match to your `onLink` callback. This guide explains the flow, the caching behavior, and the edge cases.

## What Are Deferred Deep Links?

Standard App Links only work when the app is already installed. Deferred deep links solve the "click before install" problem:

1. User clicks a WarpLink URL (e.g., a product share link)
2. App is not installed — user is redirected to the Play Store
3. User installs the app
4. On first launch, the SDK matches the install to the original click
5. Your app routes the user to the intended content

Without deferred deep links, the user would land on your default home screen with no context.

## How It Works

1. **Click.** User taps a WarpLink URL in Chrome or another app
2. **Signal capture.** The edge captures signals (IP, language, timezone) and stores a deferred payload, setting `utm_source=warplink&utm_content={link_id}` as the Play Install Referrer
3. **Store redirect.** User is redirected to the Play Store
4. **Install.** User installs and opens the app
5. **First-launch detection.** The SDK detects the first launch (see Caching below)
6. **Play Install Referrer check.** The SDK reads the referrer via `InstallReferrerClient`; if it contains `utm_source=warplink`, a deterministic match is made (confidence 1.0, `matchGuaranteed = true`). The request also carries the device signals, so a referrer that names a deleted link can still fall back to a probabilistic match. A deferred link is delivered only when the Play install began within the last seven days; an older referrer is still attributed, and nothing is routed.
7. **Fingerprint fallback.** If the referrer is unavailable (sideloaded, no Google Play), the SDK sends `accept_language` + `timezone` + `timezone_offset` to `/attribution/match` and the server computes a probabilistic match
8. **Match result.** A `WarpLinkDeepLink` with `isDeferred = true` is dispatched to your `onLink` callback

## Automatic vs. Manual

By default (`automaticDeferredDeepLinks = true`), the check fires from `configure` and a match is dispatched to `onLink`:

```kotlin
WarpLink.configure(
    context = this,
    apiKey = "wl_live_yoursdkkeyhere000000000000000000",
    options = WarpLinkOptions(
        onLink = { result ->
            result.onSuccess { link ->
                if (link.isDeferred) router.handleDeferred(link)
            }
        }
    )
)
```

A deferred **no-match** is not dispatched (there is no link to route to). To handle "no match" yourself, or to run the check on your own schedule, disable it and call `checkDeferredDeepLink`:

```kotlin
WarpLink.configure(
    context = this,
    apiKey = "wl_live_yoursdkkeyhere000000000000000000",
    options = WarpLinkOptions(automaticDeferredDeepLinks = false)
)

WarpLink.checkDeferredDeepLink { result ->
    result.onSuccess { link ->
        if (link != null) {
            val target = link.deepLinkUrl ?: link.destination
            navigateTo(target)
        } else {
            showOnboarding() // definitive no-match
        }
    }.onFailure { error ->
        Log.e("MyApp", "Deferred error: ${error.message}")
        showOnboarding()
    }
}
```

## Confidence Scores

| Scenario | Confidence | Match Type |
|----------|------------|------------|
| Play Install Referrer | 1.0 | `DETERMINISTIC` |
| Fingerprint, < 1 hour since click | 0.85 | `PROBABILISTIC` |
| Fingerprint, < 3 hours since click | 0.65 | `PROBABILISTIC` |
| Fingerprint, < 6 hours since click | 0.50 | `PROBABILISTIC` |
| Fingerprint, < 24 hours since click | 0.30 | `PROBABILISTIC` |
| 24 hours or more since click | no match | none |

The fingerprint rows are the ceilings for the zone-name variant that an SDK collecting a zone name sends. The offset variant kept for older SDKs scores a band lower (0.80 down to 0.25), and the language-only fallback lower still (0.70 down to 0.20).

Elapsed time is not the only thing that moves a probabilistic score. Two multipliers reduce whichever ceiling applied: 0.6 when more than one distinct link shared the fingerprint, and 0.6 / 0.9 / 1.0 for a carrier-grade-NAT, household-IPv4, or IPv6 click address.

**Recommendation:** Route to specific content when `matchConfidence` is above 0.5; show generic onboarding below 0.5. Gate anything sensitive on `matchGuaranteed` instead, which is `true` only for a deterministic match: a probabilistic match is a best guess from a network-shaped fingerprint and can name the wrong user. See [Attribution](attribution.md) for details.

## Match Window

The match window (how far back the server looks for a matching click) is **server-authoritative**, set per link (`match_window_hours`) in the dashboard. It **defaults to 6 hours and cannot exceed 24**; a link created before that ceiling may still carry a longer value, but the platform clamps it to 24 hours on read. There is no client-side window option.

The window is short on purpose. The fingerprint key is a network, not a device: an IP address, a normalized language, and a timezone. Every phone behind one NAT that shares a language and timezone lands in the same bucket, so each extra hour lets another stranger join it while adding almost no real matches. It governs the probabilistic tier only; the Play Install Referrer path is unaffected.

## Caching Behavior

The deferred check runs exactly once per install and caches its outcome:

- On first launch the SDK performs the attribution request.
- Completion is recorded **only on a definitive server response** (a match or a confirmed no-match).
- If the first attempt fails (for example, the device is offline), completion is **not** recorded, so the check retries on the next launch instead of returning a stale "no match".
- Once complete, subsequent calls return the cached result with no network request.

The completion marker is stored in the app's no-backup files directory, so an Android Auto Backup restore on reinstall does not carry it over — a genuine reinstall is treated as a fresh first launch and re-runs attribution.

## Edge Cases

### Play Install Referrer Unavailability

The referrer is the primary (deterministic) method but is unavailable when:

- The app is sideloaded (installed via `adb install` or a direct APK)
- The device has no Google Play Services (e.g., some Huawei devices)
- The referrer data has expired

In these cases the SDK transparently falls back to fingerprint matching (probabilistic). No code changes are needed.

### Offline First Launch

If there is no connectivity on first launch, the check fails with `WarpLinkError.NetworkError`. Because completion is consumed only on a definitive server response, the check automatically retries on the next launch once connectivity returns — the offline attempt is not lost.

### Reinstall Durability

The first-launch marker is kept out of Android Auto Backup (stored via `noBackupFilesDir`), so it is not restored on reinstall. You do **not** need to set `android:allowBackup="false"` or ship backup rules for WarpLink; a reinstall correctly re-runs the deferred check.

### Multiple Links Clicked Before Install

A fingerprint is a network rather than a device, so several clicks can land under one key: your own user clicking twice, or two strangers behind the same NAT. The platform keeps them as a list, newest first, up to 10 per fingerprint. A second click no longer overwrites the first, so the install that belongs to the earlier click can still find it.

The server matches against the newest entry your app is allowed to claim, and removes only that entry once it becomes an install. When more than one entry was claimable, the match is scored lower to reflect that the answer was picked from a set.

## Related Guides

- [Attribution](attribution.md) — detailed explanation of matching tiers
- [Error Handling](error-handling.md) — handling deferred deep link errors
- [Troubleshooting](troubleshooting.md) — common deferred deep link issues
- [Integration Guide](integration-guide.md) — initial SDK setup
