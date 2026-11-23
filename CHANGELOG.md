# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.4.0] - 2026-11-22

### Added

- **`WarpLink.readTether()`.** The same call the iOS, React Native and Flutter
  SDKs offer, so one code path serves every platform. Android never reads the
  clipboard: the call succeeds with `TetherReadResult.NoClipboard` once
  `configure` has run, and fails with `WarpLinkError.NotConfigured` before.
- **Tether test codes.** A `wl1t_` test code in the Play install referrer is
  sent to the server on its own. The server records nothing for it, and the SDK
  keeps the receipt in memory in `WarpLink.lastTetherTestReceipt`. The receipt
  clears with `clearAttribution()` and on restart. A test code never holds up
  the install referrer link id or the device signals behind it.
- **`WarpLink.deferredCheckStatus`.** A read-only status that tells a check the
  SDK skipped from a check that found no match. It is one of `NotChecked`,
  `Checking`, `Matched`, `NoMatch`, `Waiting(retryAtMillis)`,
  `SkippedOldInstall` or `Stopped`. It sends no request, and it comes from
  state stored on the device, so a relaunch keeps it. A `wl_test_` key never
  reports `Waiting`, `SkippedOldInstall` or `Stopped`. From Java, call
  `WarpLink.INSTANCE.getDeferredCheckStatus()`.

### Changed

- **Far fewer repeat attribution requests.** The first-launch check is still
  automatic and still sends one request when it succeeds. When it does not
  succeed, it now asks less often.
  - An install older than 30 days sends no request and answers no match. The
    SDK keeps this separate from the completed flag, so a later reinstall is
    still detected.
  - An install older than 48 hours sends no raw device signals. A check that
    has a link id or a recovery token still sends it. A check with nothing
    else to send sends nothing and answers no match.
  - After a check fails, the next one waits 15 minutes, then 1 hour, 6 hours
    and 1 day. The SDK stops for good 3 days after the first failure. A call
    made during a wait answers `success(null)` and sends nothing.
  - The SDK saves the result as soon as the server answers, before it hands the
    result to the main thread, so an app that is killed at that moment does not
    ask again.
  - `configure` with the same key and the same options does nothing, and the
    check that is running keeps running. The `onLink` function is not part of
    that comparison: a new one replaces the old one and receives the results.
    A different key or different options still rebuilds the SDK.
  - The check, automatic or manual, starts only while one of your activities is
    on screen. A call made earlier waits and then runs. It never fails because
    of the wait.
  - The server can change the waits and the age limits. The SDK keeps its own
    values as defaults and limits every value the server sends. An older server
    changes nothing.
  - A `wl_test_` key skips the age limits and the waits, so you can test a first
    launch as often as you like.
- With `debugLogging` on, each deferred check logs its attempt number and
  whether it carries raw device signals once, says what a failed request ran
  into (for example `timed out after 3 s (try 1 of 3)`), and logs its outcome
  once as `Deferred check: <state>`.
- An activity that was on screen before `configure` stays counted as on screen
  while a dialog activity opens over it and closes again, so a waiting check
  runs when it should.
- Every attribution request now carries `installation_id`, a random id made
  once per install and removed with the app. The check also sends
  `attempt_number`, which counts the launches that sent a request.

## [1.3.0] - 2026-11-02

### Added

- **`WarpLink.clearAttribution()`.** With a `wl_test_` key it clears the
  attribution stored on the device (the cached match, the attempt and
  completion markers, and any held referral invitation) and runs a new
  attribution check when `automaticDeferredDeepLinks` is on, so a developer can
  test a first install again without deleting the app. A check already on the
  wire when you call it is discarded and cannot refill what was cleared. Only
  the device is cleared: server records are not deleted. It returns a failure
  with `WarpLinkError.DecodingError` (cause message "Could not clear stored
  attribution data.") when a stored item could not be deleted and some state
  may remain. With a `wl_live_` key it does
  nothing and returns success. Before `configure` it fails with
  `WarpLinkError.NotConfigured`.

### Changed

- **Test and live state are kept apart on the device.** The SDK picks a
  storage namespace from the key prefix before it reads any state. A
  `wl_test_` key uses its own preferences file, install markers and referral
  files, so a Test build never reads or clears what a live build on the same
  device stored. Live keys, and any other key prefix, keep the names earlier
  releases used, so state written by 1.2.0 carries over. Switching keys at
  runtime replaces the cached link domains and retires referral redeems that
  the earlier key started.

## [1.2.0]

### Added

- **Referral handoff.** The Play Install Referrer can carry a one-time
  recovery token (`wl_rt`) next to the link id. The SDK redeems it first, on its
  own, for an exact match, and keeps every other referrer parameter your app
  already uses. A token the server refuses is dropped and the first-launch check
  goes on with the link id and the device signals. A token the server cannot
  confirm yet (`503`) ends the check without completing it, so the next launch
  tries the same referrer again.
- **`WarpLink.redeemInvitation(text, callback)`.** Redeems an invitation the
  person pasted. Text that is not an invitation sends nothing. An invitation
  past its expiry is refused on the device. A refused invitation fails with
  `WarpLinkError.ServerError` (status `400`) and a message that says how to open
  the invitation link again. An invitation the server cannot confirm yet is kept
  on the device and finished on a later launch. The SDK never reads the
  clipboard.
- **A retry stays the original.** The SDK keeps one id with each recovery
  token and sends it on every redeem of that token. After a lost response, the
  next launch reads `replayState = ORIGINAL` for the person's own install.
- **`WarpLink.claimReferral(installerId, callback)`.** Credits the referral
  behind the install to the person who signed up. Idempotent. The status
  `NONE` is an ordinary answer, not an error.
- **Evidence on `WarpLinkDeepLink`.** New fields with safe defaults:
  `attributionId`, `matchMethod`, `evidenceClass`, `replayState`,
  `capturedParams` and `tappedAt`. A match made from a link id alone reports
  `evidenceClass = LEGACY_LINK` and `matchMethod = LINK_OPEN`.

### Compatibility

- Source compatible. No existing signature changed and no `WarpLinkError` subclass
  was added, so existing `when` expressions keep compiling.
- **Upgrade note.** `WarpLinkDeepLink` has six new constructor parameters
  (`attributionId`, `matchMethod`, `evidenceClass`, `replayState`,
  `capturedParams`, `tappedAt`), all with defaults. The constructor and `copy()`
  signatures change. Code that calls them with named or default arguments
  compiles as before. Code that destructures the class gets six more
  `componentN()` functions. A library or module compiled against 1.1.x that
  builds or copies a `WarpLinkDeepLink`, such as a test fake, must be recompiled
  against 1.2.0.
- A link match cached by an earlier version still reads as a match, with the new
  fields at their defaults.
- The pending invitation and the data a claim needs live in `noBackupFilesDir`,
  so a restore or a reinstall never replays an old invitation.

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
