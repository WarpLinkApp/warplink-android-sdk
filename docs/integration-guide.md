# Integration Guide

Step-by-step guide to integrate the WarpLink Android SDK. The SDK is opt-out: after `configure`, cold-start deep links, deferred deep links, and attribution all work automatically. You'll go from zero to working links in under 30 minutes.

## Prerequisites

- Android API 26+ (Android 8.0+), Kotlin 1.8+
- A physical Android device recommended (App Links verification works best on real devices)

## Step 1: Create a WarpLink Account

Sign up at [warplink.app](https://warplink.app). The free tier includes 10,000 clicks per month.

## Step 2: Register Your Android App

1. In the WarpLink dashboard, go to **Apps**
2. Click **Register App** and select **Android**
3. Fill in your app details:
   - **Package Name** (e.g., `com.yourcompany.yourapp`)
   - **SHA256 Fingerprints** (from your signing key — see below)
   - **Play Store URL** (or leave blank during development)
4. Save the app. WarpLink generates the `assetlinks.json` file automatically for App Links verification.

### Getting Your SHA256 Fingerprint

Debug signing key:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
```

Release signing key:

```bash
keytool -list -v -keystore your-release-key.keystore -alias your-alias
```

Copy the `SHA256` value and paste it into the dashboard.

## Step 3: Create an SDK Key

WarpLink issues two kinds of credentials. Mobile apps need an **SDK key**, which is pre-scoped for link resolution and install attribution. API keys are for backend scripts, CI, and AI agents, and they cannot record installs.

1. Go to **API Keys** in the dashboard
2. Click **SDK key**
3. Name it (for example, `Android production`) and click **Create SDK Key**
4. Copy the key. It is shown once, so store it securely

> **Using an API key here is the most common setup mistake.** Deep links still resolve, so the integration looks healthy, but every attribution call is rejected and no installs appear in your dashboard.

## Step 4: Install the SDK

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("app.warplink:sdk:1.1.0")
}
```

Sync your project with Gradle files. The SDK declares the `INTERNET` permission itself, so you do not need to add it.

## Step 5: Configure the SDK

Initialize WarpLink in your `Application.onCreate()`. Pass one `onLink` callback: it receives every resolved link, whether from a cold start, a warm start, or a deferred install match.

```kotlin
import android.app.Application
import app.warplink.WarpLink
import app.warplink.WarpLinkOptions

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WarpLink.configure(
            context = this,
            apiKey = "wl_live_yoursdkkeyhere000000000000000000",
            options = WarpLinkOptions(
                onLink = { result ->
                    result.onSuccess { link -> router.handle(link) }
                        .onFailure { error ->
                            Log.w("MyApp", "WarpLink: ${error.message}")
                        }
                }
            )
        )
    }
}
```

Register your `Application` class in `AndroidManifest.xml`:

```xml
<application
    android:name=".MyApp"
    ... >
```

> **Note:** A malformed SDK key does **not** throw. `configure()` logs a warning, dispatches `WarpLinkError.InvalidApiKeyFormat` to `onLink`, and leaves the SDK unconfigured. It is safe to call from `Application.onCreate()` without a try-catch.

### What `configure` does automatically

- **Cold start:** registers `ActivityLifecycleCallbacks` and resolves the launching activity's `intent.data`, dispatching to `onLink`.
- **Deferred check:** fires once on first launch and dispatches any match to `onLink`.
- **Attribution:** collected server-side from the request; the SDK sends only a raw language tag, the IANA timezone name, and a DST-aware timezone offset (see [Attribution](attribution.md)).

Foreign URIs (intents that aren't WarpLink links) are ignored, so `onLink` only fires for real links.

## Step 6: Add the App Links Intent Filter

Add the intent filter to your entry Activity, and set `launchMode` so warm-start intents reach the same task:

```xml
<activity
    android:name=".MainActivity"
    android:launchMode="singleTask"
    android:exported="true">

    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>

    <!-- WarpLink App Links intent filter -->
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" android:host="aplnk.to" />
    </intent-filter>
</activity>
```

`android:autoVerify="true"` tells Android to verify `assetlinks.json` at install time, so links open directly in your app without a disambiguation dialog. If you use a verified custom domain, add a second `<data>` host for it.

### Custom domains: declare them so the first launch works

The SDK loads your organization's verified domains from the server and caches them, so a custom domain works from the second launch onward on its own. The first launch is the gap: the intent arrives before any server response exists, and the SDK has to decide right then whether the link is its own.

Declare your domains so that decision can be made immediately. Either in code:

```kotlin
options = WarpLinkOptions(
    linkDomains = listOf("links.yourapp.com"),
    onLink = { result -> router.handle(result) }
)
```

Or in the manifest, inside `<application>`, as a comma separated list:

```xml
<meta-data
    android:name="app.warplink.DOMAINS"
    android:value="links.yourapp.com,go.yourapp.com" />
```

Both are optional, both are read at `configure()`, and the two lists are unioned with the server's. Whitespace and letter case do not matter, and a full URL is reduced to its host. If you only use `aplnk.to`, there is nothing to do here.

## Step 7: Forward warm-start intents (one line)

Cold start is automatic. Warm start (a link arriving while your task is already running) is the one thing Android's lifecycle callbacks cannot deliver, so forward it from `onNewIntent`:

```kotlin
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import app.warplink.WarpLink

class MainActivity : AppCompatActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        WarpLink.onNewIntent(intent) // dispatches to your onLink callback
    }
}
```

That's the entire integration. `onLink` now receives cold-start, warm-start, and deferred links.

This line is gated by `automaticDeepLinks`. Leave that option at its default and the forward works; set it to `false` and this becomes a no-op, because you have taken over routing.

> **Tip (Jetpack Compose):** With a single-Activity Compose app the same `onNewIntent` line applies. Route from your `onLink` callback into a shared `ViewModel` or navigation state holder.

## Handling links manually (optional)

If you prefer to drive a piece yourself, disable it and call the SDK directly.

```kotlin
WarpLink.configure(
    context = this,
    apiKey = "wl_live_yoursdkkeyhere000000000000000000",
    options = WarpLinkOptions(
        automaticDeepLinks = false,        // you will call handleDeepLink
                                           // (this also disables the Step 7 forward)
        automaticDeferredDeepLinks = false // you will call checkDeferredDeepLink
    )
)

// Cold/warm start, handled by you:
intent?.data?.let { uri ->
    WarpLink.handleDeepLink(uri) { result ->
        result.onSuccess { link -> router.handle(link) }
    }
}

// Deferred check, handled by you:
WarpLink.checkDeferredDeepLink { result ->
    result.onSuccess { link -> link?.let { router.handle(it) } }
}
```

The automatic and manual paths are deduped, so enabling auto and also calling `handleDeepLink` for the same URI will not double-dispatch. All callbacks are invoked on the main thread.

## Step 8: Test on a Physical Device

### Testing App Links

1. Build and install your app on a physical device
2. Verify App Links are set up correctly:

```bash
adb shell pm get-app-links com.yourcompany.yourapp
```

Look for `aplnk.to` with status `verified`.

3. Open a test link directly:

```bash
adb shell am start -a android.intent.action.VIEW \
    -d "https://aplnk.to/abc123" \
    com.yourcompany.yourapp
```

4. Check Logcat for `WarpLink` messages if you enabled `debugLogging`:

```bash
adb logcat -s WarpLink
```

### Creating a Test Link

**Via Dashboard:**

1. Go to **Links** in the WarpLink dashboard
2. Click **Create Link**
3. Set the destination URL (e.g., `https://yourapp.com/product/123`)
4. Optionally set an Android deep link URL (e.g., `myapp://product/123`)
5. Copy the generated short link (e.g., `https://aplnk.to/abc123`)

**Via API:**

```bash
curl -X POST https://api.warplink.app/v1/links \
  -H "Authorization: Bearer wl_live_YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "destination_url": "https://yourapp.com/product/123",
    "android_url": "myapp://product/123"
  }'
```

### Testing Deferred Deep Links

1. Uninstall your app from the test device
2. Open the test link in Chrome — you'll be redirected to the Play Store (or a fallback URL during development)
3. Install the app via Android Studio (or `adb install`)
4. Launch the app — the automatic deferred check dispatches the matched link to `onLink`

### Debugging Tips

- Enable debug logging: `WarpLinkOptions(debugLogging = true)`
- Filter Logcat: `adb logcat -s WarpLink`
- Verify assetlinks.json: `curl https://aplnk.to/.well-known/assetlinks.json`
- Check App Links status: `adb shell pm get-app-links com.yourcompany.yourapp`
- See [Troubleshooting](troubleshooting.md) for common issues

## Next Steps

- [API Reference](api-reference.md) — full documentation of all public types and methods
- [Deferred Deep Links](deferred-deep-links.md) — how deferred attribution works on Android
- [Attribution](attribution.md) — understanding confidence scores and match types
- [Error Handling](error-handling.md) — how to handle every error case
- [Architecture](architecture.md) — how the system works end-to-end
- [Troubleshooting](troubleshooting.md) — solutions for common issues
- [Firebase Migration](firebase-migration.md) — migrating from Firebase Dynamic Links
