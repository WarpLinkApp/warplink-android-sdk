# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.1] - 2026-10-10

### Changed

- **Rate limits are retried once.** When the link resolve or the attribution
  match answers `429` with a `Retry-After` of 5 seconds or less, the SDK waits
  that long and sends the request one more time. With no readable `Retry-After`
  it waits 1 second. A longer `Retry-After` is not retried. If the retry is also
  refused, or the `Retry-After` is longer than 5 seconds, the call fails with
  `WarpLinkError.ServerError` and `statusCode == 429`. The new
  `retryAfterSeconds` property carries the server's `Retry-After` when it sent a
  readable one. No new error class is added, so existing `when` expressions keep
  compiling.

### Match guarantee

`matchGuaranteed` is not a credential. Use it, like a confidence threshold, to
pick the destination the attribution result routes to. It is `true` for
deterministic matches, so route sign-in and personal data on a separate
authentication check.

A deterministic match comes from the Play Install Referrer. A device ID lookup
returns the install the device already has, with that install's own match type
and confidence.

## [1.1.0] - 2026-09-15

Opt-out release: a bare `configure()` now makes deep linking, deferred deep
linking, and attribution work automatically. Every piece stays disable-able and
all existing manual methods are preserved (additive, backward compatible).

### Added

- `WarpLinkOptions.automaticDeepLinks` (default `true`) resolves incoming deep
  links for you and dispatches them to `onLink`: cold start through
  `ActivityLifecycleCallbacks`, warm start through `WarpLink.onNewIntent`. Set
  it to `false` and both paths become yours to route with `handleDeepLink`.
- `WarpLinkOptions.automaticDeferredDeepLinks` (default `true`) auto-fires the
  first-launch deferred check.
- `WarpLinkOptions.onLink`: a single sink that receives cold-start, warm-start,
  and deferred matches (disambiguate deferred via `WarpLinkDeepLink.isDeferred`).
- `WarpLink.onNewIntent(intent)`: one-liner warm-start bridge (call it from your
  Activity's `onNewIntent`; the lifecycle callbacks have no new-intent hook).
- The SDK now consumes the `domains` list from `/sdk/validate`, so verified
  custom domains resolve without a hardcoded `aplnk.to` allowlist.
- `WarpLinkOptions.linkDomains` (default `emptyList()`), and the equivalent
  no-code manifest entry `<meta-data android:name="app.warplink.DOMAINS"
  android:value="links.a.com,links.b.com" />`, let you declare your custom link
  domains locally. Both are optional, both are read synchronously inside
  `configure()`, and both are additive: the server list above still loads and
  the effective set is the union of `aplnk.to`, what you declared, the cached
  list, and the live `/sdk/validate` response. Nothing changes for an
  integration that declares neither.
  This closes the case where a custom-domain link opened on a genuinely first
  launch was handed back to the host app unresolved. `handleDeepLink` and the
  automatic handling must answer "is this link mine" the moment the intent
  arrives, and on that launch no server response exists yet. Values are
  normalized, so a pasted `https://links.a.com/` and `links.a.com` are the same
  declaration; `www.` is never stripped, since it is a different host.
- `<uses-permission android:name="android.permission.INTERNET"/>` is now declared
  by the SDK manifest.
- Attribution requests now carry `app_package_name`, read from the application
  context you pass to `configure`, so the server knows which app the install
  happened in. It is sent on both the Play Install Referrer path and the
  fingerprint path, and is not a fingerprint component.
- Attribution requests now carry `is_reinstall`, on both the Play Install
  Referrer path and the fingerprint path. A reinstall still counts as an install
  and is still attributed, exactly as before. The flag only lets the two be told
  apart afterwards. It is `true` when this device completed WarpLink attribution
  under an earlier install of the same app, `false` on a genuine first install,
  and it is not a fingerprint component. It gates nothing: the once-per-install
  gate is still the marker in `noBackupFilesDir`. The new device-seen flag sits
  in `SharedPreferences` instead, which Android Auto Backup does restore, since
  it has to outlive an uninstall to mean anything. Two markers with two
  lifetimes, because one marker doing both jobs is what makes a reinstall either
  skip attribution or report itself as a first install.
- `WarpLinkError.PasswordRequired`, a named error for a password protected link.
  Resolving one returns no destination and no platform URLs, because the password
  is checked in the browser and the app never sees it. Earlier versions reported
  that refusal as an invalid API key. Handle it by opening the short URL itself:
  the password form lives there, and a correct password redirects on to the
  destination. A link on a suspended custom domain reuses the existing
  `WarpLinkError.LinkNotFound`, so no second error was added.
- **`WarpLink.isWarpLinkUri(uri)`** answers whether a URI is a WarpLink link the
  SDK would claim: a host in the known link-domain set, and a path that carries
  a slug. It is the check the automatic path already makes before it touches its
  dedupe window, exposed for a host that forwards every incoming URI and needs
  to make the same decision at the same point. It resolves nothing, claims
  nothing and touches no network. Before `configure()` the known set is
  `aplnk.to` alone, so a custom-domain link reads as `false` until it has been
  declared or the server has returned it.

### Changed

- `configure()` no longer throws on a malformed API key. It logs a warning,
  dispatches `WarpLinkError.InvalidApiKeyFormat` to `onLink`, and leaves the SDK
  unconfigured. A bad key can no longer crash `Application.onCreate()`.
- Attribution requests stop sending `user_agent` and screen dimensions. The
  enriched body carries `accept_language` and `timezone_offset`.
- The timezone offset is now DST-aware (`getOffset(now)` instead of `rawOffset`).
- **Attribution signals:** the body now also carries `timezone`, the IANA zone
  name (`ZoneId.systemDefault().id`), alongside `timezone_offset`. The zone name carries more entropy
  than a raw offset and does not shift at a daylight-saving boundary. The offset
  is still sent, and still used, when the server has no zone name for the click.
- **Match guarantee:** a resolved link now exposes `matchGuaranteed`. It is
  `true` only for a deterministic match (a real universal/app link or the install
  referrer) and `false` for a probabilistic one.
- **Match window:** the deferred payload's default lifetime drops from 72 hours
  to 6, with a 24-hour ceiling. The probabilistic tier only. Deterministic
  matches are unaffected.
- First-launch tracking is split into "attempted" vs "completed" and the marker
  lives in `noBackupFilesDir`, so an offline first launch retries next launch and
  an Android Auto Backup reinstall correctly re-runs attribution.
- **The tapped URL's parameters now reach the resolved link.** The SDK forwards
  the tapped URL's query string when it resolves a link, so `WarpLinkDeepLink`'s
  `destination` and `deepLinkUrl` carry the link's UTM and custom parameters
  exactly as a browser redirect would, and `customParams` is the full parameter
  set on the resolved destination rather than an empty map.
- **Resolved URLs are normalized like redirected ones.** `WarpLinkDeepLink`'s
  `destination` and the platform URLs come back shaped exactly the way the
  redirect path shapes them, so a bare origin arrives carrying its trailing
  slash, and the `customParams` map now includes the query parameters already
  present on the stored destination. Both were always true of a browser redirect,
  and this is a server-side change with nothing to configure.
- **In-app opens are now recorded as clicks.** Links an app opens directly
  through verified App Links appear in analytics, alongside the redirects that
  reach the link in a browser, and they count toward your plan's click
  allowance. This is a server-side change, so it applies to 1.0.x hosts too, and
  there is nothing to configure.

### Removed

- `WarpLinkOptions.matchWindowHours`. The match window is server-authoritative
  (`link.match_window_hours`); the client option was inert.

### Fixed

- Installs retry attribution on the next launch when a response names no link. A
  confirmed no-match is still definitive and still completes the check, exactly
  as before.
- `WarpLinkDeepLink.deepLinkUrl` is a Kotlin null when a link has no Android
  deep link set.
- Play Install Referrer failures now say why: enable `debugLogging` to see the
  cause.
- An install upgrading from 1.0.x runs the deferred check once, as a first
  install.
- The completion marker now holds even when the app's no-backup directory cannot
  be written. The fallback lives in `SharedPreferences` and is bound to the
  install, so a backup restore cannot carry it onto a reinstall.
- A cached match restored without its completion marker is cleared.
- An abandoned deferred check (a reconfigure while one was in flight) now
  answers `Result.success(null)`, matching iOS.
- Declared link domains are trimmed using the same invisible-character set as
  the iOS SDK.
- Attribution requests sent on the Play Install Referrer path now carry the
  device signals too, so a referrer naming a deleted or foreign link can still
  match on the signals. `fingerprint_version` now describes what was sent.
- With Auto Backup disabled, `configure()` logs (debug logging on) that a
  reinstall on this device will be reported as a first install.
- An App Link whose path is not exactly one segment, such as
  `https://<your-domain>/blog/hello`, is no longer treated as a link. It yields
  no slug, `isWarpLinkUri` is false for it, and automatic handling skips it so
  the URI reaches your own routing. This matters because App Links are verified
  for the whole host, so the OS hands the app URLs the SDK was never able to
  resolve. A percent-encoded `%2F` inside a single segment is rejected for the
  same reason.
- A resolve or deferred check that fails on a poor connection or with a 5xx
  retries up to three times. Every attempt of one tap carries the same
  identifier, so one tap still records one click. Refusals such as a missing
  link, a password protected link or an expired link are never retried.

## [1.0.2] - 2026-06-26

### Fixed

- The SDK reports `1.0.2` through `WarpLink.SDK_VERSION`, the
  `WarpLink-Android/<version>` User-Agent, the fingerprint collector, and the
  `sdk_version` field of attribution requests.

## [1.0.1] - 2026-06-26

### Fixed

- The SDK reports `1.0.1` in the `WarpLink-Android/<version>` User-Agent.

### Changed

- Releases now create the GitHub Release automatically on tag push, alongside the existing Maven Central publish.

## [1.0.0] - 2026-06-17

First stable release. The public API is now covered by semantic versioning
guarantees. Upgrade by bumping the dependency to `app.warplink:sdk:1.0.0`.

## [0.1.1] - 2026-06-07

### Fixed

- Referrer-based (Play Install Referrer) install attribution sends the required
  `fingerprint_version` field.

## [0.1.0] - 2026-05-16

### Added

- Initial SDK scaffolding with public API surface
- Gradle build configuration with Maven Central publishing
- CI/CD workflows for build, test, and release
