# Install Attribution

WarpLink uses two tiers of attribution matching to connect app installs and opens to the links that drove them. Attribution runs automatically after `configure` (via the first-launch deferred check); this guide explains how the match is made and how to read the result.

## Overview

When a user interacts with a WarpLink URL, the platform captures signals from the click. When the app opens (or is installed and opened for the first time), the SDK sends a small set of device signals to the WarpLink API. The **server** derives the request IP and computes the fingerprint from both sides, then determines the match.

The result is returned as a `WarpLinkDeepLink` with `matchType`, `matchConfidence`, and `matchGuaranteed` properties.

## Match Cascade

On Android, matching is attempted in this order:

1. **Play Install Referrer** (deterministic, confidence 1.0, `matchGuaranteed = true`)
2. **Enriched fingerprint** (probabilistic, confidence 0.20 to 0.85 before multipliers, `matchGuaranteed = false`)

The first successful match wins. Fingerprint is only attempted if the referrer is unavailable or doesn't contain WarpLink data.

## Deterministic Matching (Play Install Referrer)

**Used for:** First-install attribution via the Google Play Store. This is the primary matching method on Android.

| Property | Value |
|----------|-------|
| Signal | Play Install Referrer (`utm_source=warplink&utm_content={link_id}`) |
| Match type | `DETERMINISTIC` |
| Confidence | 1.0 (exact match) |
| Requires Google Play? | Yes |
| Requires user permission? | No |
| Sent alongside | `accept_language`, `timezone`, `timezone_offset`, so the server can fall back to a probabilistic match when the referrer names a link that no longer exists |

When a user clicks a WarpLink URL and is redirected to the Play Store, the referrer URL includes `utm_source=warplink&utm_content={link_id}`. After install and first open, the SDK reads the referrer via `InstallReferrerClient` and extracts the link ID for a direct match. Accuracy is 100% because the link ID is passed through the Play Store itself, with no fingerprinting.

The SDK uses a 2-second timeout for the referrer read. If it expires (e.g., Google Play Services is slow), the SDK falls back to fingerprint matching.

> **Note on device IDs:** iOS augments the deterministic path with the vendor identifier (IDFV). Android has no privacy-safe equivalent, so this SDK never sends a `device_id` and the Play Install Referrer is the deterministic path here. There is no GAID and no Android ID read anywhere in the SDK. The attribution payload does carry a `device_id` field, which the shared API schema defines for iOS; on Android it is always omitted.

## Probabilistic Matching (Enriched Fingerprint)

**Used for:** First-install attribution when the Play Install Referrer is unavailable (sideloaded apps, non-Google-Play devices).

| Property | Value |
|----------|-------|
| Signals | IP address (server-derived) + Accept-Language + timezone |
| Match type | `PROBABILISTIC` |
| Confidence | 0.20 to 0.85 before the multipliers below, never `matchGuaranteed` |
| Requires Google Play? | No |
| Requires user permission? | No |

On first launch, if the referrer is unavailable, the SDK collects its device signals and sends them to the attribution API. The server combines them with the request's IP address to compute the fingerprint and check for a match.

### Signals Collected by the SDK

| Signal | Purpose | Source |
|--------|---------|--------|
| Accept-Language | Fingerprint component (raw preferred language tag; the server normalizes it) | `Locale.getDefault().toLanguageTag()` |
| Timezone | Fingerprint component (the IANA zone name, e.g. `America/Toronto`) | `ZoneId.systemDefault().id`, or empty when the process default zone has no tzdb entry |
| Timezone offset | Fingerprint component, fallback key (minutes, JS sign convention, DST-aware) | `-(TimeZone.getDefault().getOffset(now) / 60000)` |

**On the zone name (new in 1.1.0).** The IANA zone name carries far more entropy than the offset it joins: roughly 340 zones against roughly 38 offsets. It also does not shift at a daylight-saving boundary, which used to break a click and its install apart when the clocks changed between them. The SDK sends both, and the server prefers the zone name (`fingerprint_version: enriched_tz`), falling back to the offset for SDKs that send no zone name. Collection never fails: if the zone cannot be resolved, it is omitted and the request still carries the language and the offset.

**Not collected.** User-Agent and screen dimensions were removed from the fingerprint. They never matched between the browser (at click time) and the native app (at install time) — browser vs. native UA strings differ, and CSS pixels vs. physical pixels differ — so they only added noise. The IP address is derived server-side and is never sent by the SDK.

### Confidence by Time Window

The elapsed time and the fingerprint variant set the ceiling. `enriched_tz` is the zone-name variant that an SDK collecting a zone name sends; `enriched` is the offset variant kept for older SDKs; `basic` is the language-only key the server tries last.

| Time Since Click | `enriched_tz` | `enriched` | `basic` |
|------------------|---------------|------------|---------|
| < 1 hour | 0.85 | 0.80 | 0.70 |
| < 3 hours | 0.65 | 0.60 | 0.50 |
| < 6 hours | 0.50 | 0.45 | 0.35 |
| < 24 hours | 0.30 | 0.25 | 0.20 |
| 24 hours or more | no match | no match | no match |

Confidence decreases over time because IP addresses and network conditions change. Those values are ceilings, and two multipliers pull the score down when the answer was less certain than the clock alone suggests:

| Condition | Multiplier |
|-----------|------------|
| More than one claimable click sat in the fingerprint's bucket | 0.6 |
| The click's IP was carrier-grade NAT or private | 0.6 |
| The click's IP was a household IPv4 address | 0.9 |
| The click's IP was IPv6 | 1.0 |

### The match window

The match window is server-authoritative, set per link in the dashboard. It **defaults to 6 hours and cannot exceed 24**; a link created before that ceiling may still carry a longer value, but the platform clamps it to 24 hours on read. There is no client-side window to tune.

The window is short on purpose. The fingerprint key is a network, not a device: an IP address, a normalized language, and a timezone. Every phone behind one NAT that shares a language and timezone lands in the same bucket, so each extra hour lets another stranger join it while adding almost no real matches. This governs the probabilistic tier only. The Play Install Referrer path reads stored click data instead of the fingerprint bucket, so the window does not apply to it.

## App Identity

Every attribution request also carries `app_package_name`, your app's application ID, on both the referrer path and the fingerprint path. It is not a fingerprint component and does not affect confidence.

An API key is scoped to your organization, not to a single app. If your organization ships more than one app, the package name is what lets the server attribute an install to the app it actually happened in. The SDK reads it from the context you pass to `configure`, so there is nothing to set up.

| Signal | Field | Source |
|--------|-------|--------|
| Application ID | `app_package_name` | `Context.getPackageName()` |

## Reinstalls

A reinstall counts as an install. Deleting the app removes the completion
marker, which lives in `noBackupFilesDir` and is never restored, so the next
install runs the deferred check again and can be attributed again.

Each request carries one boolean, `is_reinstall`, so the two can be told apart
afterwards. It is `true` when this device completed WarpLink attribution under
an earlier install of your app, and `false` on a genuine first install. It
gates nothing: it does not suppress the check, change the match cascade, or
affect confidence, and it is not a fingerprint component.

| Signal | Field | Source |
|--------|-------|--------|
| Reinstall tag | `is_reinstall` | A flag in `SharedPreferences`, which Auto Backup restores |

The flag has to survive an uninstall to mean anything, which is why it lives in
the one store Android Auto Backup restores. One consequence is worth knowing: a
phone set up from another phone's backup arrives carrying the flag, so its first
install of your app reports itself as a reinstall. That affects the tag only,
never whether the install is attributed.

## Interpreting Match Results

The `WarpLinkDeepLink` returned via `onLink` (or `handleDeepLink` / `checkDeferredDeepLink`) includes:

- `matchType: MatchType?` — `DETERMINISTIC` or `PROBABILISTIC`
- `matchConfidence: Double?` — 0.0 to 1.0
- `matchGuaranteed: Boolean`, `true` only for a deterministic match
- `isDeferred: Boolean` — whether the link came from a deferred install match

### Gate sensitive work on `matchGuaranteed`

`matchGuaranteed` is `true` only when the match came from a deterministic signal (the Play Install Referrer). Gate anything sensitive on it (auto sign-in, restoring an account, showing personal data) rather than on a confidence threshold. A probabilistic match is a best guess drawn from a network-shaped fingerprint, so even a high score can name the wrong user.

```kotlin
if (link.matchGuaranteed) {
    restoreAccount(link)      // this is the person we think it is
} else {
    showContent(link)         // route content, but never identity
}
```

### Recommended Thresholds

| Confidence | Recommended Action |
|------------|-------------------|
| 1.0 (deterministic) | Route directly to content |
| > 0.5 (probabilistic) | Route to content — high confidence |
| 0.3 to 0.5 | Show content with a confirmation (e.g., "Were you looking for...?") |
| < 0.3 | Show generic onboarding — too uncertain |

```kotlin
WarpLink.configure(
    context = this,
    apiKey = "wl_live_yoursdkkeyhere000000000000000000",
    options = WarpLinkOptions(
        onLink = { result ->
            result.onSuccess { link ->
                val confidence = link.matchConfidence ?: 1.0
                when {
                    confidence >= 0.5 -> navigateTo(link.destination)
                    confidence >= 0.3 -> showSuggestion(link.destination)
                    else -> showOnboarding()
                }
            }
        }
    )
)
```

## Privacy Considerations

### What the SDK Does NOT Collect

- **No GAID** (Google Advertising ID)
- **No Android ID** — the device's persistent identifier is not collected or sent
- **No location data**
- **No contacts or personal data**
- **No cross-app identifiers**
- **No User-Agent or screen dimensions** (removed from the fingerprint)

### What the SDK Does Collect

- **Play Install Referrer**: first-party Google API data, passed through the Play Store when the user installs the app. No additional permission needed.
- **Fingerprint signals**: non-PII characteristics (preferred language, timezone name, timezone offset). These are the same signals any web server receives in HTTP headers.
- **Reinstall flag**: one boolean, `is_reinstall`, saying whether this device has run the app before. It is sent on every attribution request, on both the deterministic and the probabilistic path. It exists so a returning user is routed back to the content they tapped, and so reinstalls can be told apart from first installs afterwards.
- **Backup dependency**: the device-seen marker behind `is_reinstall` lives in `SharedPreferences`, which only outlives an uninstall when Android Auto Backup is enabled (`android:allowBackup`, the default). With backup off, a reinstall is reported as a first install. The SDK logs this at `configure()` when debug logging is on.

### Data Handling

- Device signals are sent to the WarpLink API over HTTPS
- Fingerprint data is used solely for attribution matching
- No cross-app tracking is performed
- Play Install Referrer data is read once at first launch and not stored beyond the attribution result
- The reinstall flag is retained on the install record, so it outlives the request that carried it

### Play Data safety mapping

Google Play has no equivalent of the iOS privacy manifest. Nothing this SDK
ships reaches Google on its own, so the Data safety form in your Play Console
listing is yours to fill in. This table is the mapping for everything the SDK
sends, so you can answer it accurately.

| What the SDK sends | Play data type | Purpose | Notes |
|---|---|---|---|
| `referrer` (Play Install Referrer) | App activity → Other actions | App functionality, Analytics | Read once at first launch. Deterministic attribution path. The device signals below travel in the same request. |
| `is_reinstall` | App activity → Other actions | App functionality, Analytics | One boolean. Retained on the install record. |
| `accept_language`, `timezone`, `timezone_offset` | Device or other IDs | App functionality, Analytics | Sent as raw values. The server hashes them with the request IP into a probabilistic match key, so declare them as an identifier rather than as device settings. |
| Request IP address | Device or other IDs | App functionality | Not sent by the SDK. The server reads it from the connection and it forms part of the same match key. |
| `platform`, `sdk_version`, `app_package_name` | Not user data | n/a | Describes the caller, not the device or the person. |

Three answers depend on your integration rather than on the SDK, so decide them
yourself:

- **Encrypted in transit.** Yes. Every request is HTTPS.
- **Required or optional.** The SDK is opt-out per feature, so you can disable
  attribution at `configure`. If you leave it on with no consent gate in front
  of it, declare the collection as required. If you gate it, declare it optional.
- **Can users request deletion.** Answer for your own deletion path, not for the
  SDK. The SDK provides no end-user deletion call.

Not legal advice. Your Data safety declaration covers your whole app, and every
other SDK in it, not this one alone.

## Related Guides

- [Deferred Deep Links](deferred-deep-links.md) — how deferred deep linking uses attribution
- [API Reference](api-reference.md) — `MatchType` and `WarpLinkDeepLink` documentation
- [Integration Guide](integration-guide.md) — initial SDK setup
