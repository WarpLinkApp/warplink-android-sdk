# WarpLink Android SDK

[![CI](https://github.com/WarpLinkApp/warplink-android-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/WarpLinkApp/warplink-android-sdk/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Deep linking, deferred deep linking, and install attribution for Android, with zero third-party dependencies.

The SDK is **opt-out**: a single `configure(...)` call wires up cold-start deep links, the first-launch deferred check, and attribution automatically. Provide one `onLink` callback and you are done. Every piece can be disabled and driven manually if you prefer.

## Requirements

- Android API 26+ (Android 8.0+)
- Kotlin 1.8+

## Installation

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("app.warplink:sdk:1.1.0")
}
```

## Quick Start

### 1. Configure once, receive links in one place

In your `Application.onCreate()`, call `configure` with an `onLink` callback. That callback receives cold-start, warm-start, and deferred matches. Nothing else is required. A **background deferred check that fails**, for example on a launch with no network, also reaches `onLink` as a failure. The gate stays open, so the next launch retries by itself, and the error is a report rather than something for the host to act on.

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
                    result.onSuccess { link ->
                        // Route the user. link.isDeferred tells you whether this
                        // came from a deferred install match.
                        navigateTo(link.deepLinkUrl ?: link.destination)
                    }.onFailure { error ->
                        Log.w("MyApp", "WarpLink: ${error.message}")
                    }
                }
            )
        )
    }
}
```

Pass an **SDK key**, not an API key. SDK keys are created under **API Keys** > **SDK key** in the dashboard and are the only credential that can record install attribution. An API key resolves deep links but silently fails attribution.

A malformed SDK key does **not** throw. It is reported to `onLink` (and logged), and the SDK stays unconfigured, so a bad key can never crash app startup.

### 2. Add the App Links intent filter

Add this to your entry Activity in `AndroidManifest.xml` so links open your app directly:

```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="aplnk.to" />
</intent-filter>
```

### 3. Add the one warm-start line

Cold start is automatic. Warm start (a link arriving while your task is already running) is the one thing Android cannot deliver automatically, so forward it in a single line. Give the Activity `android:launchMode="singleTask"` (or `singleTop`) and:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    WarpLink.onNewIntent(intent) // dispatches to your onLink callback
}
```

This forward is gated by `automaticDeepLinks`. Leave that option at its
default and the forward works. Set it to `false` and this line becomes a
no-op, because you have taken over routing with `handleDeepLink`.

That's the whole integration. Deep links, deferred deep links, and attribution now work.

## Disabling any piece

Each capability is a flag on `WarpLinkOptions`. Turn one off to handle it yourself with `WarpLink.handleDeepLink(uri) { ... }` or `WarpLink.checkDeferredDeepLink { ... }`.

```kotlin
WarpLink.configure(
    context = this,
    apiKey = "wl_live_yoursdkkeyhere000000000000000000",
    options = WarpLinkOptions(
        debugLogging = true,               // WarpLink-tagged Logcat output
        automaticDeepLinks = false,        // opt out of cold and warm start auto-handling
        automaticDeferredDeepLinks = false // opt out of the auto deferred check
    )
)
```

### Checking a URI before you route it

If your app has its own router and forwards every incoming URI, ask whether a URI is a WarpLink link before you hand it anywhere:

```kotlin
if (WarpLink.isWarpLinkUri(uri)) {
    WarpLink.handleDeepLink(uri) { result -> /* ... */ }
} else {
    myRouter.handle(uri)
}
```

`isWarpLinkUri(uri)` is the same check the automatic path makes: a host in the known link-domain set, and a path that carries a slug. It resolves nothing and touches no network, so it is safe to call on every URI. Before `configure()` the known set is `aplnk.to` alone, so a link on your own domain reads as `false` until you have declared it (see below) or the server has returned it.

## Custom link domains

`aplnk.to` works out of the box. If your links live on your own domain, declare it so it is recognized on the very first launch, before the SDK has talked to the server:

```kotlin
options = WarpLinkOptions(
    linkDomains = listOf("links.yourapp.com"),
    onLink = { result -> /* ... */ }
)
```

Or declare it in `AndroidManifest.xml`, inside `<application>`, with no code change:

```xml
<meta-data
    android:name="app.warplink.DOMAINS"
    android:value="links.yourapp.com,go.yourapp.com" />
```

Both are optional and both are additive: your organization's verified domains still load from the server as before. See [Custom link domains](docs/api-reference.md#custom-link-domains).

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
