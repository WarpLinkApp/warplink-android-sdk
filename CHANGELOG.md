# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

### Added

- First stable release.

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
