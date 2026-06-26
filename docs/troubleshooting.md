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

Update the fingerprint in **Settings > Apps** in the WarpLink dashboard.

### App not registered in dashboard

Your Android app must be registered in the WarpLink dashboard with the correct package name and SHA256 fingerprint. WarpLink generates the `assetlinks.json` file automatically.

### Domain mismatch

The SDK only recognizes `aplnk.to` as a WarpLink domain. Custom domain support is planned for a future release.

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

## 3. Play Install Referrer Not Working

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

## 4. SharedPreferences Persistence on Reinstall

**Symptoms:** `checkDeferredDeepLink` returns cached data after reinstalling the app, or reports "not first launch" on what should be a fresh install.

### Cause

Android Auto Backup (enabled by default) preserves SharedPreferences across uninstall/reinstall. The SDK's first-launch flag and cached attribution persist.

### Fix

Option A — Disable backup entirely:

```xml
<application
    android:allowBackup="false"
    ... >
```

Option B — Exclude WarpLink preferences from backup:

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

### Testing tip

For development testing, clear app data before testing deferred deep links:

```bash
adb shell pm clear com.yourcompany.yourapp
```

---

## 5. Deep Link Returns `InvalidUrl`

**Symptoms:** `handleDeepLink` fails with `WarpLinkError.InvalidUrl` for URLs you expect to work.

### URI is not an `aplnk.to` domain

The SDK currently only recognizes `aplnk.to` as a WarpLink domain. URIs with other hosts (including custom domains) will return `InvalidUrl`.

**Workaround:** Check the URI host before calling `handleDeepLink`:

```kotlin
intent?.data?.let { uri ->
    if (uri.host == "aplnk.to") {
        WarpLink.handleDeepLink(uri) { result ->
            // ...
        }
    }
}
```

Custom domain support in the SDK is planned for a future release.

---

## 6. Deferred Deep Link Returns Null

**Symptoms:** `checkDeferredDeepLink` always returns `null` (success with no match).

### Match window expired

If the user installs the app more than 72 hours (default) after clicking the link, the match window has expired. Consider increasing the window:

```kotlin
WarpLink.configure(
    context = this,
    apiKey = "wl_live_...",
    options = WarpLinkOptions(matchWindowHours = 120)
)
```

### Referrer unavailable and fingerprint didn't match

On sideloaded apps or devices without Google Play, the SDK relies on fingerprint matching. If network conditions changed significantly between click and install (different Wi-Fi, VPN, etc.), the fingerprint may not match.

### Not actually first launch

SharedPreferences may have persisted from a previous install (see issue #4 above). Clear app data and try again.

### SDK not configured

If `configure()` hasn't been called, `checkDeferredDeepLink` returns `Result.failure(WarpLinkError.NotConfigured)`, not `null`. Check for errors in the failure callback.

---

## 7. adb Testing Commands

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

## 8. Logcat Filtering

### Enable debug logging

```kotlin
WarpLink.configure(
    context = this,
    apiKey = "wl_live_YOUR_KEY",
    options = WarpLinkOptions(debugLogging = true)
)
```

### Filter by WarpLink tag

```bash
adb logcat -s WarpLink
```

### What to look for

**Configuration:**
- `"Configured with API key: wl_live_****xxxx"` — SDK initialized (key is masked)
- `"API endpoint: https://api.warplink.app/v1"` — endpoint in use
- `"Match window: 72 hours"` — deferred deep link match window
- `"WarpLink SDK configured (v0.1.0)"` — configuration complete

**API key validation:**
- `"API key validated successfully"` — key is valid
- `"API key validation cached, skipping"` — using cached validation (24hr)
- `"API key validation failed: key rejected"` — key is invalid

**Deep links:**
- `"Resolving deep link: abc123@aplnk.to"` — link resolution started
- `"Deep link resolved: <linkId>"` — link resolved successfully
- `"Deep link resolution failed: ..."` — resolution error

**Deferred deep links:**
- `"First launch detected"` — first launch, attribution check starting
- `"Play Install Referrer: ..."` — referrer data read
- `"Deferred deep link matched"` — attribution match found
- `"No deferred deep link match"` — no match
- `"Returning cached attribution"` — returning cached result

---

## 9. ProGuard / R8

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
