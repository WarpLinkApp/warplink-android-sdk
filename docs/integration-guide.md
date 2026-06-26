# Integration Guide

Step-by-step guide to integrate the WarpLink Android SDK into your app. You'll go from zero to working deep links in under 30 minutes.

## Prerequisites

- Android API 26+ (Android 8.0+), Kotlin 1.8+
- A physical Android device recommended (App Links verification works best on real devices)

## Step 1: Create a WarpLink Account

Sign up at [warplink.app](https://warplink.app). The free tier includes 10,000 clicks per month.

## Step 2: Register Your Android App

1. In the WarpLink dashboard, go to **Settings > Apps**
2. Click **Add App** and select **Android**
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

## Step 3: Create an API Key

1. Go to **Settings > API Keys** in the dashboard
2. Click **Create API Key**
3. Copy your key (format: `wl_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxx`)
4. Store it securely — you'll use this to configure the SDK

## Step 4: Install the SDK

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("app.warplink:sdk:1.0.2")
}
```

Sync your project with Gradle files.

## Step 5: Configure the SDK

Initialize WarpLink in your `Application.onCreate()`:

```kotlin
import android.app.Application
import app.warplink.WarpLink
import app.warplink.WarpLinkOptions

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WarpLink.configure(
            context = this,
            apiKey = "wl_live_YOUR_KEY"
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

You can also pass options for debug logging or a custom match window:

```kotlin
WarpLink.configure(
    context = this,
    apiKey = "wl_live_YOUR_KEY",
    options = WarpLinkOptions(
        debugLogging = true,
        matchWindowHours = 48
    )
)
```

> **Note:** `configure()` throws `WarpLinkError.InvalidApiKeyFormat` if the API key doesn't match the expected format (`wl_(live|test)_<32 alphanumeric chars>`). Wrap in a try-catch during development if needed.

## Step 6: Add App Links Intent Filter

Add the following intent filter to your main Activity in `AndroidManifest.xml`:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true">

    <!-- Existing launcher intent filter -->
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>

    <!-- WarpLink App Links intent filter -->
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data
            android:scheme="https"
            android:host="aplnk.to" />
    </intent-filter>
</activity>
```

The `android:autoVerify="true"` attribute tells Android to verify the domain's `assetlinks.json` at install time, so App Links open directly in your app without a disambiguation dialog.

> **Note:** WarpLink currently supports the `aplnk.to` domain. Custom domains will be supported in a future SDK update.

## Step 7: Handle Deep Links (Activity-Based)

When a user taps a WarpLink URL and your app is installed, Android opens your Activity with the URL as the intent data. Handle it in both `onCreate()` and `onNewIntent()`:

```kotlin
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.warplink.WarpLink

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
                    // Route based on destination or deep link URL
                    navigateTo(deepLink.destination)
                }.onFailure { error ->
                    // Handle error — see Error Handling guide
                    Log.e("MyApp", "Deep link error: ${error.message}")
                }
            }
        }
    }
}
```

The callback is always invoked on the main thread, so you can safely update UI from it.

## Step 8: Handle Deep Links (Jetpack Compose)

If you're using Jetpack Compose with a single-Activity architecture, handle the intent in your Activity and pass the deep link data to your composables:

```kotlin
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.warplink.WarpLink
import app.warplink.WarpLinkDeepLink

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyApp(
                onDeepLink = { deepLink ->
                    // Navigate using your Compose navigation
                }
            )
        }
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
                    // Update navigation state or shared ViewModel
                }.onFailure { error ->
                    Log.e("MyApp", "Deep link error: ${error.message}")
                }
            }
        }
    }
}
```

> **Tip:** Use a shared `ViewModel` or state holder to pass deep link data from the Activity to your Compose navigation graph.

## Step 9: Check for Deferred Deep Links

Deferred deep links work when a user clicks a WarpLink URL, installs your app from the Play Store, and opens it for the first time. The SDK matches the install back to the original click using the Play Install Referrer (deterministic) or fingerprint matching (probabilistic fallback).

Call `checkDeferredDeepLink` once, early in your app's first-launch flow:

```kotlin
WarpLink.checkDeferredDeepLink { result ->
    result.onSuccess { deepLink ->
        if (deepLink != null) {
            // User arrived via a WarpLink — route to intended content
            navigateTo(deepLink.destination)
        } else {
            // No deferred deep link — show default onboarding
        }
    }.onFailure { error ->
        Log.e("MyApp", "Deferred deep link error: ${error.message}")
    }
}
```

The SDK automatically detects first launch and caches the result in SharedPreferences. Subsequent calls return the cached result without a network request.

See [Deferred Deep Links](deferred-deep-links.md) for details on confidence scores and edge cases.

## Step 10: Test on a Physical Device

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
  -H "Authorization: Bearer wl_live_YOUR_KEY" \
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
4. Launch the app — `checkDeferredDeepLink` should return the matched deep link

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
