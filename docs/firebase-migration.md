# Firebase Dynamic Links Migration Guide

Firebase Dynamic Links was deprecated on August 25, 2025. This guide walks you through migrating your Android app to WarpLink.

## Concept Mapping

| Firebase Dynamic Links | WarpLink |
|----------------------|----------|
| Dynamic Links | Links |
| Firebase console | [WarpLink dashboard](https://warplink.app) |
| `firebase-dynamic-links` SDK | `app.warplink:sdk` (single dependency) |
| `yourapp.page.link` domain | `aplnk.to` domain |
| `FirebaseDynamicLinks.getInstance()` | `WarpLink` (singleton object) |
| `getDynamicLink(intent)` | `WarpLink.handleDeepLink(uri, callback)` |
| `PendingDynamicLinkData` | `WarpLinkDeepLink` |
| Link parameters (social metadata, analytics) | Link fields (destination, Android URL, custom params) |

## Step 1: Recreate Your Links

Recreate your Firebase Dynamic Links as WarpLink links via the [dashboard](https://warplink.app) or the [REST API](https://api.warplink.app/v1).

### Parameter Mapping

| Firebase Parameter | WarpLink Field |
|-------------------|----------------|
| `link` (deep link URL) | `destination_url` |
| `apn` (Android package name) | Configured per-app in dashboard |
| `afl` (Android fallback link) | `android_fallback_url` |
| `amv` (minimum app version) | N/A (handle in-app) |
| `efr` (skip preview page) | N/A (WarpLink uses 302 redirects by default) |
| `st` / `sd` / `si` (social metadata) | OG tags on destination page |
| Custom parameters | `custom_params` JSON object |

### Via Dashboard

1. Go to **Links** > **Create Link**
2. Set the destination URL
3. Add Android deep link URL if needed (e.g., `myapp://path`)
4. Add any custom parameters

### Via API

```bash
curl -X POST https://api.warplink.app/v1/links \
  -H "Authorization: Bearer wl_live_your_api_key_here_abcdefgh" \
  -H "Content-Type: application/json" \
  -d '{
    "destination_url": "https://yourapp.com/product/123",
    "android_url": "myapp://product/123",
    "custom_params": { "referrer": "campaign_spring" }
  }'
```

## Step 2: Swap the SDK

### Remove Firebase Dynamic Links

In your app's `build.gradle.kts`:

**Before:**

```kotlin
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.0.0"))
    implementation("com.google.firebase:firebase-dynamic-links")
}
```

**After:** Remove both lines (keep other Firebase dependencies if you use other Firebase services).

Remove Firebase Dynamic Links imports from your source files:

```kotlin
// Remove these imports
import com.google.firebase.dynamiclinks.FirebaseDynamicLinks
import com.google.firebase.dynamiclinks.PendingDynamicLinkData
```

### Add WarpLink

```kotlin
dependencies {
    implementation("app.warplink:sdk:0.1.0")
}
```

## Step 3: Update SDK Initialization

**Firebase:**

```kotlin
// Firebase Dynamic Links doesn't require explicit initialization —
// it initializes automatically via FirebaseApp.
```

**WarpLink:**

```kotlin
import app.warplink.WarpLink

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WarpLink.configure(
            context = this,
            apiKey = "wl_live_your_api_key_here_abcdefgh"
        )
    }
}
```

Make sure your `Application` class is registered in `AndroidManifest.xml`:

```xml
<application
    android:name=".MyApp"
    ... >
```

## Step 4: Update App Links Configuration

In your `AndroidManifest.xml`, update the intent filter:

**Firebase:**

```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data
        android:scheme="https"
        android:host="yourapp.page.link" />
</intent-filter>
```

**WarpLink:**

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

## Step 5: Migrate Deep Link Handling

**Firebase:**

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseDynamicLinks.getInstance()
            .getDynamicLink(intent)
            .addOnSuccessListener { pendingData ->
                val deepLink = pendingData?.link
                if (deepLink != null) {
                    handleDeepLink(deepLink)
                }
            }
            .addOnFailureListener { e ->
                Log.e("MyApp", "getDynamicLink:onFailure", e)
            }
    }
}
```

**WarpLink:**

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            WarpLink.handleDeepLink(uri) { result ->
                result.onSuccess { deepLink ->
                    navigateTo(deepLink.destination)
                }.onFailure { error ->
                    Log.e("MyApp", "Deep link error: ${error.message}")
                }
            }
        }
    }
}
```

### Key Differences

| | Firebase | WarpLink |
|-|---------|----------|
| Entry point | `getDynamicLink(intent)` | `handleDeepLink(uri, callback)` |
| Input | `Intent` | `Uri` (from `intent?.data`) |
| Return type | `PendingDynamicLinkData?` | `Result<WarpLinkDeepLink>` |
| Deep link URL | `pendingData.link` (Uri) | `deepLink.destination` (String) or `deepLink.deepLinkUrl` |
| Error handling | `addOnFailureListener` | `Result.onFailure` with typed `WarpLinkError` |
| Custom parameters | Embedded in the URL query params | `deepLink.customParams` map |
| `onNewIntent` | Handled automatically | Must call `handleDeepLink` explicitly |

## Step 6: Migrate Deferred Deep Links

**Firebase:**

```kotlin
// Firebase handled deferred deep links automatically via getDynamicLink(intent)
// on first launch. No separate API call needed.
FirebaseDynamicLinks.getInstance()
    .getDynamicLink(intent)
    .addOnSuccessListener { pendingData ->
        val deepLink = pendingData?.link
        // This returned the deferred deep link on first launch
    }
```

**WarpLink:**

```kotlin
// WarpLink uses an explicit call for deferred deep links
WarpLink.checkDeferredDeepLink { result ->
    result.onSuccess { deepLink ->
        if (deepLink != null) {
            navigateTo(deepLink.destination)
        } else {
            // No deferred deep link — show onboarding
        }
    }.onFailure { error ->
        Log.e("MyApp", "Deferred error: ${error.message}")
    }
}
```

### Key Differences

| | Firebase | WarpLink |
|-|---------|----------|
| When to call | Automatic via `getDynamicLink` | Explicit `checkDeferredDeepLink` call |
| First launch detection | Built-in | SDK detects via SharedPreferences |
| Caching | Automatic | Automatic (cached after first check) |
| Attribution data | None | `matchType`, `matchConfidence` |
| Matching method | Play Install Referrer | Play Install Referrer + fingerprint fallback |
| Custom params | Via URL query params | `deepLink.customParams` map |

## Step 7: Testing Checklist

After migration, verify each flow works:

- [ ] **SDK initializes** — enable `debugLogging` and check for `WarpLink SDK configured` in Logcat
- [ ] **API key validates** — check for `API key validated successfully` in Logcat
- [ ] **App Links open the app** — tap a WarpLink URL on a physical device
- [ ] **App Links verified** — `adb shell pm get-app-links com.yourpackage` shows `verified`
- [ ] **Deep link resolves** — `handleDeepLink` returns a `WarpLinkDeepLink` with the correct destination
- [ ] **Custom parameters preserved** — check `deepLink.customParams` matches what you configured
- [ ] **Deferred deep links work** — uninstall app, click link, reinstall, launch, verify `checkDeferredDeepLink` returns match
- [ ] **assetlinks.json is correct** — `curl https://aplnk.to/.well-known/assetlinks.json` shows your package name and SHA256
- [ ] **Error handling works** — test with an invalid URL, expired link, and no connectivity
- [ ] **Old Firebase code fully removed** — no remaining Firebase Dynamic Links imports or dependencies
- [ ] **Build succeeds** — clean build without Firebase Dynamic Links dependencies

## Related Guides

- [Integration Guide](integration-guide.md) — full setup walkthrough
- [API Reference](api-reference.md) — all public types and methods
- [Troubleshooting](troubleshooting.md) — common issues after migration
- [Deferred Deep Links](deferred-deep-links.md) — understanding deferred attribution
