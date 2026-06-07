# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
