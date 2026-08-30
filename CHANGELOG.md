# Changelog

All notable changes to this project are documented in this file. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-08-30

### Added

- Native configure, login, logout, customer-information, and offering APIs.
- Ordered offering targeting and entitlement helpers.
- Direct purchases through `InappifyMarket.NONE`.
- Cafe Bazaar purchases and interrupted-purchase recovery through Poolakey.
- Discount-code and custom/reserved attribute operations.
- Immutable state snapshots and ordered client event listeners.
- AES-GCM session persistence backed by Android Keystore.
- Safe migration of Inappify-owned values from previous mobile installations.
- Sample Android application and public Maven publication metadata.

### Security

- Sensitive credentials, identities, checkout data, and purchase evidence are
  redacted from public snapshots and diagnostic representations.
- Mutable client and session state is instance-owned; persistence uses a file
  lock rather than mutable global synchronization.

[1.0.0]: https://github.com/mohsenghorbanipour/inappify_android_plugin/releases/tag/v1.0.0
