# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
- `WarpLinkOptions.onLink` — a single sink that receives cold-start, warm-start,
  and deferred matches (disambiguate deferred via `WarpLinkDeepLink.isDeferred`).
- `WarpLink.onNewIntent(intent)` — one-liner warm-start bridge (call it from your
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
  context you pass to `configure`. An API key is scoped to an organization
  rather than to one app, so an organization shipping more than one app could
  have an install attributed to a sibling app. The package name tells the server
  which app the install actually happened in. It is sent on both the Play
  Install Referrer path and the fingerprint path, and is not a fingerprint
  component.
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
  unconfigured — a bad key can no longer crash `Application.onCreate()`.
- Attribution requests stop sending `user_agent` and screen dimensions. They
  never matched between the browser (click) and the native app (install), so the
  server dropped them from the fingerprint. The enriched body now carries only
  `accept_language` + `timezone_offset`.
- The timezone offset is now DST-aware (`getOffset(now)` instead of `rawOffset`).
- **Attribution signals:** the body now also carries `timezone`, the IANA zone
  name (`ZoneId.systemDefault().id`), alongside `timezone_offset`. The zone name carries more entropy
  than a raw offset and does not shift at a daylight-saving boundary. The offset
  is still sent, and still used, when the server has no zone name for the click.
- **Match guarantee:** a resolved link now exposes `matchGuaranteed`. It is
  `true` only for a deterministic match (a real universal/app link, the install
  referrer, or a device-id match) and `false` for a probabilistic one. Gate
  auto-login, personal data, and anything else you cannot safely show the wrong
  person on this flag, not on the match alone.
- **Match window:** the deferred payload's default lifetime drops from 72 hours
  to 6, with a 24-hour ceiling. The probabilistic tier only. Deterministic
  matches are unaffected. A deferred link that is claimed more than 24 hours
  after the click will no longer match.
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
- **In-app opens are now recorded as clicks.** An app that opens a link directly
  through verified App Links previously produced no click. Those opens now appear
  in analytics, alongside the redirects that reach the link in a browser, and they
  count toward your plan's click allowance. This is a server-side change, so it
  applies to 1.0.x hosts too, and there is nothing to configure.

### Removed

- `WarpLinkOptions.matchWindowHours`. The match window is server-authoritative
  (`link.match_window_hours`); the client option was inert.

### Fixed

- An install is no longer stranded by an attribution response it cannot use. The
  deferred check is allowed to run once per install, and any 200 used to spend
  it, including a `matched` response that named no link and carried no
  destination. The SDK was right to discard such a response, but it then never
  asked again, so the install stayed unattributed for good. Such a response is
  now treated as unfinished business and retried on the next launch. A confirmed
  no-match is still definitive and still completes the check, exactly as before.
- Deep link URLs are no longer delivered as the literal string `"null"`. Android's
  `org.json` returns `"null"` for an explicit JSON null rather than an empty
  string, so the guard on it never fired and `WarpLinkDeepLink.deepLinkUrl` held
  four characters of text instead of a Kotlin null. The documented
  `link.deepLinkUrl ?: link.destination` routing then navigated to `"null"`.
  Affected 1.0.0 through 1.0.2 on cold start, warm start, `handleDeepLink`, and
  the first launch after a matched install, for any link with no Android deep
  link set, which is the default shape of a new link. Upgrade if you route on
  `deepLinkUrl`.
- Play Install Referrer failures now say why. Every failure previously returned
  the same `null` an organic install returns, with nothing logged, so a broken
  referrer integration and a genuinely organic install were indistinguishable.
  Enable `debugLogging` to see the cause. Affected 1.0.0 through 1.0.2.
- An install upgrading from 1.0.x is attributed instead of being locked out.
  1.0.x wrote its `is_first_launch` flag at the start of the deferred check,
  before any request was sent, so the flag only ever proved that a check began.
  Reading it as proof of a completed attribution meant an upgrading install
  never ran the check again, in a marker no later release could clear. The flag
  is now ignored. An upgrading install runs the check once, as a first install.
  If that install originally came from a WarpLink link, its Play referrer is
  still readable, so the install is recorded. A deferred link is delivered only
  when the Play install began within the last seven days; an older referrer is
  attributed and nothing is routed, so a long-time user is not dropped into old
  content and `attributionResult` stays null.
- The completion marker now holds even when the app's no-backup directory cannot
  be written. Before, a failed write re-ran the check on every launch, re-sent
  the request and handed the deferred link to `onLink` again. The fallback lives
  in `SharedPreferences` and is bound to the install, so a backup restore cannot
  carry it onto a reinstall.
- A cached match that Auto Backup restored without its completion marker is
  cleared, so `attributionResult` no longer returns the previous install's match
  while the new check is in flight.
- An abandoned deferred check (a reconfigure while one was in flight) now
  answers `Result.success(null)`, matching iOS, instead of a `NotConfigured`
  failure on an SDK that is configured.
- Declared link domains are trimmed from one explicit set of invisible
  characters shared with the iOS SDK. A zero-width space pasted at the end of a
  domain used to make it never match on Android only.
- Attribution requests sent on the Play Install Referrer path now carry the
  device signals too, so a referrer naming a deleted or foreign link can still
  match on the signals. `fingerprint_version` now describes what was sent.
- With Auto Backup disabled, `configure()` logs (debug logging on) that a
  reinstall on this device will be reported as a first install.
- An App Link whose path is not exactly one segment is no longer treated as a
  link. `https://<your-domain>/blog/hello` used to resolve as slug `blog`, which
  could open a completely unrelated destination, since a slug can never contain
  `/` anywhere else in the system. It now yields no slug, `isWarpLinkUri` is
  false for it, and automatic handling skips it so the URI reaches your own
  routing. This matters because App Links are verified for the whole host, so
  the OS hands the app URLs the SDK was never able to resolve. A percent-encoded
  `%2F` inside a single segment is rejected for the same reason.
- A tapped link that fails on a weak connection is retried. A resolve or a
  deferred check that fails with a dead or flaky connection, or with a 5xx, is
  now attempted up to three times, each attempt with its own time limit and the
  whole sequence limited to about twelve seconds. A weak connection usually
  delivers on the second attempt, in about eight. Every attempt of one tap
  carries the same identifier, so one tap still records one click. Refusals such
  as a missing link, a password protected link or an expired link are never
  retried.

## [1.0.2] - 2026-06-26

### Fixed

- The SDK now actually reports its real version in the `WarpLink-Android/<version>`
  User-Agent and in the `sdk_version` field of attribution requests. Every prior
  release (including 1.0.1) reported `0.1.1` here, because the runtime version is
  read from the `WarpLink.SDK_VERSION` constant, which had never been bumped. The
  1.0.1 change only updated `VERSION_NAME` (the Maven coordinate), which does not
  feed the User-Agent, so the wire version was unchanged. This release bumps the
  constant itself, so `WarpLink.SDK_VERSION`, the User-Agent, the fingerprint
  collector, and attribution `sdk_version` all report `1.0.2`.

## [1.0.1] - 2026-06-26

### Fixed

- The SDK now reports its correct version (`1.0.1`) in the `WarpLink-Android/<version>` User-Agent. Earlier builds carried a stale `VERSION_NAME`.

### Changed

- Releases now create the GitHub Release automatically on tag push, alongside the existing Maven Central publish.

## [1.0.0] - 2026-06-17

First stable release. The public API is now covered by semantic versioning
guarantees. Upgrade by bumping the dependency to `app.warplink:sdk:1.0.0`.

## [0.1.1] - 2026-06-07

### Fixed

- Referrer-based (Play Install Referrer) install attribution now sends the
  required `fingerprint_version` field. The deterministic referrer path
  previously omitted it, so the attribution API rejected the request with a
  400 validation error and re-engagement matches never landed.

## [0.1.0] - 2026-05-16

### Added

- Initial SDK scaffolding with public API surface
- Gradle build configuration with Maven Central publishing
- CI/CD workflows for build, test, and release
