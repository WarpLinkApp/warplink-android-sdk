# WarpLink Android SDK

[![CI](https://github.com/WarpLinkApp/warplink-android-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/WarpLinkApp/warplink-android-sdk/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Deep linking SDK for Android. Handle App Links, deferred deep links, and install attribution with zero third-party dependencies.

## Requirements

- Android API 26+ (Android 8.0+)
- Kotlin 1.8+

## Installation

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("app.warplink:sdk:0.1.0")
}
```

## Quick Start

### 1. Configure the SDK

Initialize WarpLink in your `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WarpLink.configure(
            context = this,
            apiKey = "wl_live_your_api_key_here"
        )
    }
}
```

### 2. Add App Links Intent Filter

Add the following intent filter to your main Activity in `AndroidManifest.xml` so App Links open directly in your app:

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

### 3. Handle Deep Links

In your Activity, handle incoming deep links:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    intent?.data?.let { uri ->
        WarpLink.handleDeepLink(uri) { result ->
            result.onSuccess { deepLink ->
                // Navigate to deepLink.destination
            }.onFailure { error ->
                // Handle error
            }
        }
    }
}
```

### 4. Check for Deferred Deep Links

On first launch, check if the user arrived via a deferred deep link:

```kotlin
WarpLink.checkDeferredDeepLink { result ->
    result.onSuccess { deepLink ->
        deepLink?.let {
            // Navigate to deferred deep link destination
        }
    }
}
```

## Configuration Options

```kotlin
WarpLink.configure(
    context = this,
    apiKey = "wl_live_your_api_key_here",
    options = WarpLinkOptions(
        debugLogging = true,       // Enable debug logging (default: false)
        matchWindowHours = 48      // Attribution match window (default: 72)
    )
)
```

## Documentation

- [Integration Guide](docs/integration-guide.md) — step-by-step setup from account creation to testing
- [API Reference](docs/api-reference.md) — all public types and methods
- [Deferred Deep Links](docs/deferred-deep-links.md) — deferred attribution flow and edge cases
- [Attribution](docs/attribution.md) — Play Install Referrer and fingerprint matching
- [Error Handling](docs/error-handling.md) — every error case with recovery actions
- [Architecture](docs/architecture.md) — how the SDK communicates with the platform
- [Troubleshooting](docs/troubleshooting.md) — common issues and solutions
- [Firebase Migration](docs/firebase-migration.md) — migrating from Firebase Dynamic Links

For hosted documentation, visit [warplink.app/docs](https://warplink.app/docs).

## License

MIT - See [LICENSE](LICENSE) for details.
