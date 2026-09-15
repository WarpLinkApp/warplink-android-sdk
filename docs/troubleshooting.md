# Troubleshooting

Common issues and solutions when integrating the WarpLink Android SDK.

## 1. App Links Don't Open My App

**Symptoms:** Tapping a WarpLink URL opens the browser or shows a disambiguation dialog instead of your app.

**Possible Causes and Solutions:**

### App Links not verified

Check App Links verification status:

```bash
adb shell pm get-app-links com.yourcompany.yourapp
```

Look for `aplnk.to` with status `verified`. If the status is `undefined` or `ask`, verification failed.

### `android:autoVerify="true"` missing

Ensure your intent filter in `AndroidManifest.xml` includes `android:autoVerify="true"`:

```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data
        android:scheme="https"
        android:host="aplnk.to" />
</intent-filter>
```

### SHA256 fingerprint mismatch

The SHA256 fingerprint in the WarpLink dashboard must match your app's signing key. Get your fingerprint:

```bash
# Debug key
keytool -list -v -keystore ~/.android/debug.keystore \
    -alias androiddebugkey -storepass android

# Release key
keytool -list -v -keystore your-release-key.keystore -alias your-alias
```

Update the fingerprint under **Apps** in the WarpLink dashboard.

### App not registered in dashboard

Your Android app must be registered in the WarpLink dashboard with the correct package name and SHA256 fingerprint. WarpLink generates the `assetlinks.json` file automatically.

### Domain mismatch

The SDK recognizes `aplnk.to`, any domain you declared locally, and any verified custom domain your org owns. Server-side domains are loaded automatically from `/sdk/validate` when your SDK key validates and are cached for offline launches. If a custom-domain link returns `InvalidUrl`, confirm the domain is verified and live in the dashboard, and declare it locally with `WarpLinkOptions.linkDomains` or the `app.warplink.DOMAINS` manifest entry so it is recognized on a first launch too.

---

## 2. assetlinks.json Verification Fails

**Symptoms:** `adb shell pm get-app-links` shows status other than `verified`.

### Check assetlinks.json content

```bash
curl -s https://aplnk.to/.well-known/assetlinks.json | python3 -m json.tool
```

### Expected structure

```json
[{
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
        "namespace": "android_app",
        "package_name": "com.yourcompany.yourapp",
        "sha256_cert_fingerprints": [
            "AB:CD:EF:..."
        ]
    }
}]
```

### What to check

1. Your package name appears in the `target.package_name` field
2. Your SHA256 fingerprint appears in `sha256_cert_fingerprints`
3. The response is served over HTTPS with `Content-Type: application/json`

### Force re-verification

After updating the dashboard, you can force Android to re-verify:

```bash
adb shell pm verify-app-links --re-verify com.yourcompany.yourapp
```

---

## 3. Deep Links Work but No Installs Appear

**Symptoms:** Links open your app correctly, Logcat shows `API key validated successfully`, but the dashboard records no installs. The deferred check fails with `WarpLinkError.InvalidApiKey`.

### An API key was passed to `configure()`

This is the most common setup mistake. WarpLink issues two credentials that look identical (`wl_live_` plus 32 alphanumeric characters), and only an **SDK key** carries the `attribution:write` scope. An API key that holds `links:read` still passes `/sdk/validate` and still resolves deep links, so the integration looks healthy while every attribution call is rejected.

Fix it in the dashboard:

1. Go to **API Keys**
2. Click **SDK key**, name it, and click **Create SDK Key**
3. Replace the key in your `configure()` call

There is no way to add attribution access to an existing API key. Create an SDK key instead.

---

## 4. Play Install Referrer Not Working

**Symptoms:** Deferred deep links fall back to fingerprint matching instead of deterministic (Play Install Referrer) matching.

### Device needs Google Play Services

The Play Install Referrer API requires Google Play Services. It's unavailable on:
- Devices without Google Play (e.g., Huawei with HMS)
- Emulators without Google Play
- Sideloaded apps (installed via `adb install` or direct APK)

The SDK automatically falls back to fingerprint matching in these cases.

### Check dependency

Ensure the Install Referrer library is included (it's a transitive dependency of the WarpLink SDK, but verify if you have dependency exclusions):

```kotlin
implementation("com.android.installreferrer:installreferrer:2.2")
```

### Timeout

The SDK uses a 2-second timeout for the referrer read. On slow devices or when Play Services is initializing, this timeout may expire. The SDK falls back to fingerprint matching.

---

## 5. Reinstall Re-runs (or Skips) the Deferred Check

**Symptoms:** After reinstalling, the deferred check behaves unexpectedly — either re-running or returning stale data.

### How it works now

The SDK stores its first-launch completion marker in the app's no-backup files directory (`noBackupFilesDir`). Android Auto Backup does not restore that directory, so a genuine reinstall is treated as a fresh first launch and re-runs attribution. **You do not need `android:allowBackup="false"` or custom backup rules for WarpLink.**

Cached attribution and the API-key validation timestamp remain in SharedPreferences; if a backup restores them, they are refreshed by the re-run check. The completion marker is what gates the network call, and it is never restored.

### Testing tip

For development testing, clear app data before testing deferred deep links:

```bash
adb shell pm clear com.yourcompany.yourapp
```

---

## 6. Deep Link Returns `InvalidUrl`

**Symptoms:** `handleDeepLink` fails with `WarpLinkError.InvalidUrl` for URLs you expect to work.

### URI host is not recognized

The SDK recognizes `aplnk.to` plus any verified custom domains. A URI with an unrecognized host returns `InvalidUrl`. You do not need to pre-filter — foreign URIs are ignored by automatic handling and fail fast (no network) when passed to `handleDeepLink`.

If your custom-domain link returns `InvalidUrl`, the domain list may not have loaded yet:

- Confirm the domain is verified and live in the dashboard.
- Declare the domain locally so it does not depend on a server response at all. Pass `WarpLinkOptions(linkDomains = listOf("links.yourapp.com"))`, or add `<meta-data android:name="app.warplink.DOMAINS" android:value="links.yourapp.com" />` inside `<application>`. This is the fix for a link that fails on a first launch and works on every launch after it, and for any launch with no network. Enable `debugLogging` and look for a `Link domains declared locally:` log line.
- Ensure the SDK key has validated at least once (the server side of the list comes from `/sdk/validate` and is cached afterward). Enable `debugLogging` and look for a `Link domains loaded:` log line.

---

## 7. Deferred Deep Link Returns Null

**Symptoms:** `checkDeferredDeepLink` always returns `null` (success with no match).

### Match window expired

If the user installs the app after the link's match window, the click is no longer eligible. The window is server-authoritative (set per link in the dashboard, `match_window_hours`); there is no client-side option to tune it. Increase the window on the link if legitimate installs arrive later.

### Referrer unavailable and fingerprint didn't match

On sideloaded apps or devices without Google Play, the SDK relies on fingerprint matching. If network conditions changed significantly between click and install (different Wi-Fi, VPN, etc.), the fingerprint may not match.

### Check already completed

Once a definitive server response is recorded, the SDK returns the cached result without re-checking. For development, clear app data (`adb shell pm clear ...`) to force a fresh first launch.

### SDK not configured

If `configure()` hasn't been called, `checkDeferredDeepLink` returns `Result.failure(WarpLinkError.NotConfigured)`, not `null`. Check for errors in the failure callback.

---

## 8. adb Testing Commands

### Open a link directly

```bash
adb shell am start -a android.intent.action.VIEW \
    -d "https://aplnk.to/abc123" \
    com.yourcompany.yourapp
```

### Check App Links verification

```bash
adb shell pm get-app-links com.yourcompany.yourapp
```

### Force re-verify App Links

```bash
adb shell pm verify-app-links --re-verify com.yourcompany.yourapp
```

### Clear app data (reset first-launch state)

```bash
adb shell pm clear com.yourcompany.yourapp
```

### Check installed packages

```bash
adb shell pm list packages | grep yourcompany
```

---

## 9. Logcat Filtering

### Enable debug logging

```kotlin
WarpLink.configure(
    context = this,
    apiKey = "wl_live_yoursdkkeyhere000000000000000000",
    options = WarpLinkOptions(debugLogging = true)
)
```

### Filter by WarpLink tag

```bash
adb logcat -s WarpLink
```

### What to look for

**Configuration:**
- `"Configured with API key: "` followed by the masked key: SDK initialized
- `"API endpoint: "` followed by the endpoint in use
- `"WarpLink SDK configured (v"` followed by the version: configuration complete

**API key validation:**
- `"API key validated successfully"`: key is valid
- `"API key validation cached, skipping"`: using the cached validation (24 hours)
- `"WarpLink API key was rejected by the server"`: key is invalid or revoked (always logged, even with debug logging off)

**Deep links:**
- `"Resolving deep link: "` followed by slug@domain: resolution started
- `"Deep link resolved: "` followed by the link id: resolved
- `"Deep link resolution failed: "` followed by the reason

**Deferred deep links:**
- `"Retrying deferred check after a previous failed attempt"`: an earlier launch ran the check offline or got no definitive answer
- `"First launch: trying Play Install Referrer"`: the deferred check is starting on the referrer path
- `"First launch: collecting device signals"`: the deferred check is starting on the fingerprint path (no referrer reader)
- `"Referrer found: "` followed by the link id: the Play Install Referrer named a WarpLink link
- `"No WarpLink referrer, falling back to fingerprint"`: organic install, or the referrer was unavailable
- `"Deferred deep link matched: "` followed by the link id: match found
- `"No deferred deep link match"`: the server looked and found nothing
- `"Deferred check already completed, returning cached attribution"`: returning the cached result

---

## 10. ProGuard / R8

The WarpLink SDK does not use reflection, so no ProGuard or R8 rules are needed. If you encounter issues with minification enabled, add:

```proguard
-keep class app.warplink.** { *; }
```

However, this should not be necessary under normal circumstances.

## Related Guides

- [Integration Guide](integration-guide.md) — step-by-step setup
- [Error Handling](error-handling.md) — handling SDK errors programmatically
- [Deferred Deep Links](deferred-deep-links.md) — understanding deferred attribution
- [API Reference](api-reference.md) — all public types and methods
