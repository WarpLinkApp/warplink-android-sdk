# Install Attribution

WarpLink uses two tiers of attribution matching to connect app installs and opens to the links that drove them. The SDK collects device signals and sends them to the WarpLink API, which determines the match.

## Overview

When a user interacts with a WarpLink URL, the platform captures signals from the click. When the app opens (or is installed and opened for the first time), the SDK collects device-side signals. The server compares both sets of signals to determine if there's a match.

The result is returned as a `WarpLinkDeepLink` with `matchType` and `matchConfidence` properties.

## Match Cascade

On Android, the SDK tries matching methods in this order:

1. **Play Install Referrer** (deterministic, confidence 1.0)
2. **Enriched fingerprint** (probabilistic, confidence 0.40–0.85)

The first successful match wins. Fingerprint is only attempted if the referrer is unavailable or doesn't contain WarpLink data.

## Deterministic Matching (Play Install Referrer)

**Used for:** First-install attribution via the Google Play Store.

| Property | Value |
|----------|-------|
| Signal | Play Install Referrer (`utm_source=warplink&utm_content={link_id}`) |
| Match type | `DETERMINISTIC` |
| Confidence | 1.0 (exact match) |
| Requires Google Play? | Yes |
| Requires user permission? | No |

When a user clicks a WarpLink URL and is redirected to the Play Store, the referrer URL includes `utm_source=warplink&utm_content={link_id}`. After the user installs and opens the app, the SDK reads the referrer data via `InstallReferrerClient` and extracts the link ID for a direct match.

This is the primary matching method on Android. It provides 100% accuracy because the link ID is passed through the Play Store itself — no fingerprinting needed.

The SDK uses a 2-second timeout for the referrer read. If the timeout expires (e.g., Google Play Services is slow), the SDK falls back to fingerprint matching.

## Probabilistic Matching (Enriched Fingerprint)

**Used for:** First-install attribution when the Play Install Referrer is unavailable.

| Property | Value |
|----------|-------|
| Signals | IP address + User-Agent + Accept-Language + screen resolution + timezone |
| Match type | `PROBABILISTIC` |
| Confidence | 0.40 to 0.85 (varies by time window) |
| Requires Google Play? | No |
| Requires user permission? | No |

When the Play Install Referrer is unavailable (sideloaded apps, non-Google-Play devices), the SDK falls back to fingerprint matching. On first app launch, the SDK collects device signals via `FingerprintCollector` and sends them to the attribution API. The server computes a fingerprint from both the click-time and install-time signals, including the request's IP address, and checks for a match.

### Signals Collected by the SDK

| Signal | Purpose | Source |
|--------|---------|--------|
| Accept-Language | Fingerprint component | Device locale configuration |
| Screen dimensions | Fingerprint component | `WindowManager.defaultDisplay` |
| Timezone offset | Fingerprint component | `TimeZone.getDefault().rawOffset` |
| User-Agent | Fingerprint component | Custom WarpLink UA string with SDK version |

### Confidence by Time Window

| Time Since Click | Confidence |
|------------------|------------|
| < 1 hour | 0.85 |
| < 24 hours | 0.65 |
| < 72 hours | 0.40 |
| Multiple candidates | -0.15 per additional match |

Confidence decreases over time because IP addresses and network conditions change. The multiple-candidate penalty applies when more than one stored click matches the fingerprint.

## Interpreting Match Results

The `WarpLinkDeepLink` returned by `handleDeepLink` and `checkDeferredDeepLink` includes:

- `matchType: MatchType?` — `DETERMINISTIC` or `PROBABILISTIC`
- `matchConfidence: Double?` — 0.0 to 1.0

### Recommended Thresholds

| Confidence | Recommended Action |
|------------|-------------------|
| 1.0 (deterministic) | Route directly to content |
| > 0.5 (probabilistic) | Route to content — high confidence |
| 0.3 to 0.5 | Show content with a confirmation (e.g., "Were you looking for...?") |
| < 0.3 | Show generic onboarding — too uncertain |

```kotlin
WarpLink.checkDeferredDeepLink { result ->
    result.onSuccess { deepLink ->
        if (deepLink != null) {
            val confidence = deepLink.matchConfidence ?: 0.0

            when {
                confidence >= 0.5 -> navigateTo(deepLink.destination)
                confidence >= 0.3 -> showSuggestion(deepLink.destination)
                else -> showOnboarding()
            }
        }
    }
}
```

## Privacy Considerations

### What the SDK Does NOT Collect

- **No GAID** (Google Advertising ID) — WarpLink does not use or request the advertising identifier
- **No Android ID** — the device's persistent identifier is not collected
- **No location data** — GPS/network location is never accessed
- **No contacts or personal data**
- **No cross-app identifiers**

### What the SDK Does Collect

- **Play Install Referrer** — first-party Google API data, passed through the Play Store with user consent (the user chose to install the app). No additional permission needed.
- **Fingerprint signals** — non-PII device characteristics (screen size, timezone, language, user agent). These are the same signals any web server receives in HTTP headers.

### Data Handling

- Device signals are sent to the WarpLink API over HTTPS
- Fingerprint data is used solely for attribution matching
- No cross-app tracking is performed
- Play Install Referrer data is read once at first launch and not stored beyond the attribution result

> **Note:** WarpLink currently supports the `aplnk.to` domain only. Custom domain support is planned.

## Related Guides

- [Deferred Deep Links](deferred-deep-links.md) — how deferred deep linking uses attribution
- [API Reference](api-reference.md) — `MatchType` and `WarpLinkDeepLink` documentation
- [Integration Guide](integration-guide.md) — initial SDK setup
