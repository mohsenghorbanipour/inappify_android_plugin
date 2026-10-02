# Changelog

Library versions are independent of the V1, Laravel Store V2 and Go V2 HTTP
protocols. See the [upgrade guide](docs/MIGRATION.md) before changing factories.

## [2.4.0] - 2026-10-03

- Add `InappifyMarket.MYKET` for consumable and non-consumable purchases on the
  default V1 client and opt-in Go commerce client, including signed receipt
  validation, durable recovery, delivery-before-consume and store-bound retries.
- Bundle an isolated adaptation of Myket Billing Client 1.6. Its Binder Java
  package and UI resources avoid collisions with the existing Poolakey dependency;
  fixed manifest settings require no host placeholders or separate Myket artifact.
- Preserve existing factories, Direct/Bazaar routes and API descriptors. Myket
  requires the backend `MyKet`/`11` route and the matching RSA public key. Reject
  native subscriptions, dynamic-price tokens and conflicting store bindings.
- See [setup and rollout checks](docs/MYKET.md) and
  [third-party source provenance](THIRD_PARTY_NOTICES.md). Device/backend
  acceptance remains pending.
- Verification: 607 JVM tests passed; SDK release lint has zero errors and one
  documented exported-receiver warning (plus 14 dependency-update suggestions
  in the clean release build). Independent consumer Debug and R8
  Release builds succeeded. Bounded JVM comparison with 2.3.0 found no removed
  public/protected descriptors or incompatible access/supertype restrictions.

## [2.3.0] - 2026-10-01

- Add the Kotlin extension `InappifyClient.syncAttributesAndOfferingsIfNeeded()`
  for both the default V1 client and the opt-in Go V2 client. Await setters first;
  the operation uploads attributes before requesting fresh offerings under the
  same identity lock. It does not introduce a GET-attributes endpoint.
- Limit this operation to five admitted calls per rolling 60 seconds per client.
  Excess calls log a non-sensitive warning and return current-session offerings
  cache without HTTP. Missing/invalidated cache returns a retryable failure with
  `reason=RATE_LIMITED_NO_CACHE` and `retryAfterMillis`.
- Admitted calls return network/attribute failures, not cached success. V1 sync
  retains unsent local values on transient failure; Go uses the existing durable
  write-only queue, batching and rejected-batch quarantine. Successful fetches
  update the existing cache, snapshots and events.
- Keep published V1/V2 interface members, factories, getter defaults, standalone
  attribute methods, storage and purchase behavior unchanged. Custom client
  implementations without the optional capability return `UNSUPPORTED_OPERATION`.
  No Flutter/iOS/Web changes are included in this Android release.
- Verification: 524 JVM tests passed (39 new), release lint has zero issues,
  AAR/publication sources and instrumentation compile successfully. Bounded
  public JVM comparisons with published V1, 2.1 and 2.2 found no incompatibilities.

See [2.2 → 2.3 migration](docs/MIGRATION.md#upgrading-from-22-to-23) and the
[method contract](docs/API_GUIDE.md#sync-attributes-then-fetch-targeted-offerings).
Live backend targeting and real-device acceptance remain separate rollout gates.

## [2.2.0] - 2026-09-30

- Separate Go SDK and commerce endpoints. Unbound Go Direct checkout and Bazaar
  purchase/verification/delivery use the Go session Bearer at commerce V2;
  explicitly bound legacy clients retain their purchase/recovery route.
- Retain the original `InappifyV2Configuration` constructor, Kotlin default-argument
  entry point, `apiBaseUrl` getter and `DEFAULT_API_BASE_URL` constant. The named
  SDK endpoint alias and separate commerce constructor are additive.
- Persist Login/Logout attempt headers for uncertain lifecycle recovery; migrate
  old pending-logout caches without exposing their previous customer. Go custom
  IDs accept 1–100 Unicode code points without controls/edge whitespace/reserved
  anonymous prefixes. Existing valid anonymous UUID casing remains accepted.
- Configure sends SDK version and normalized country. Go no longer uses V1
  `forceVersion`; legacy behavior is unchanged. Default construction performs no
  third-party IP-geolocation request.
- Harden Go store receipt recovery, result parsing, delivery cancellation and
  disabled-network checks; retain Direct legacy delivery confirmation and forward
  redacted traces from both Go transports. See [Go migration](V2_MIGRATION.md).
- Scope native Go receipt journals to their commerce endpoint independently of
  session cache; block ambiguous route changes while purchases require recovery
  or a previously bound legacy companion needs explicit rebinding.
- Keep the Gradle 8.7 / AGP 8.5.1 / Kotlin 1.9 / Android API 21-compatible dependency
  set. Uncoordinated main-branch dependency bumps require a separate toolchain
  migration; this integration does not raise the library's minimum Android API.

This is a new minor library version; published 2.1.0 artifacts and tags are not
replaced. Live Go commerce/backend and real-device acceptance have not been
performed by this release automation and remain required before production rollout.
See [2.1 → 2.2 migration notes](docs/MIGRATION.md#upgrading-from-21-to-22).

## [2.1.0] - 2026-09-19

### Added

- Opt-in `InappifyClient.restoreCachedSession(options)` extension for local-only
  legacy session bootstrap. Valid matching CustomerInfo and configuration-bound
  Offerings are published through the existing snapshot/events before the host
  starts a background Configure. See [offline integration](docs/OFFLINE_CACHE.md).
- Strict offline cache acceptance: API-key and exact customer binding,
  anonymous-only implicit identity, bounded JSON, duplicate-key rejection and
  calendar/time validation. Invalid catalogs do not hide valid CustomerInfo.
- Durable rejection markers for known authentication failures after opting into
  restored state, including after a successful background refresh. Recovery
  credentials and pending purchase journals remain intact.

### Compatibility and safety

- Existing Configure network behavior, getter defaults, factories, V1 storage
  migration and public interface methods are unchanged. No automatic Go switch.
- Restoration performs no HTTP, billing, delivery, consume or attribute writes;
  it does not import unbound old Flutter preferences. Hosts retain responsibility
  for pending logout, account-generation guards and local entitlement expiration.
- Transient refresh failures retain valid restored state. A successful local
  cache load is not evidence of current server authorization or payment success.
- Added 30 cache regressions, an API compatibility test and two compile-verified
  instrumentation cases. All 419 JVM tests pass; matching release artifacts have
  no removed/changed checked V1/V2 public JVM descriptors. Device upgrade and
  live store acceptance remain separate checks.

## [2.0.0] - 2026-09-17

### Added

- Bazaar Store V2 receipt submission, bounded verification polling, encrypted
  recovery checkpoints and independent subscription/non-consumable restore results.
- Explicit product types and additive store status, verification and delivery
  metadata on purchase results.
- Consumable delivery handlers, manual acknowledgement and
  `syncPendingConsumables()`. Host delivery precedes Bazaar consumption;
  Direct fulfillment uses the existing pending/delivered endpoints.
- Serialized fulfillment, bounded transient retries and multi-batch pending
  delivery draining.
- Opt-in `InappifyV2Client`: Go Bearer sessions, signed CustomerInfo, server
  offerings/placements, explicit cache policies and state Flow.
- API-key-only official Go setup with Android metadata, durable anonymous UUID,
  bundled Ed25519 trust and scope bound from verified responses.
- Durable write-only attribute coalescing, 50-item batches, rejected-batch
  quarantine, pending offline logout and host-controlled Go network access.
- Explicit scope-checked legacy purchase companion; no automatic credential
  exchange between Go and Laravel.
- Optional Direct paywall attribution and a safe native package fallback.
- Opt-in bounded HTTP tracing and detailed storage/signature diagnostics.

### Fixed

- Retain the cached V1 Bazaar customer and recovery binding when refreshing
  missing route metadata; reject unexpected replacement identities.
- Preserve untyped V1 request market selection. Typed V2 requests and Go purchase
  companions retain server-authoritative routing.
- Allow unchanged Direct/NONE Configure without a Bazaar RSA key. An actual
  Bazaar V2 purchase still fails before billing UI if its key is missing.
- Preserve legacy untyped IN_APP purchases through verification and retry;
  classify them as consumable only from authoritative delivery evidence.
- Resume already-paid checkpoints before looking up an invalidated catalog.
- Let Android Keystore generate the AES-GCM encryption IV, avoiding
  `Caller-provided IV not permitted`.
- Accept documented optional/nullable customer fields while rejecting malformed
  envelopes and invalid identities.
- Keep signed Go internal customer subject separate from the public anonymous
  UUID across Configure, refresh, renewal, cache recovery and login/logout.
- Honor store recovery retry timing and validate returned delivery scope.

### Compatibility and behavior notes

- Keep the original V1 factory, public method signatures, constructors,
  default-argument entry points and legacy purchase-status enum.
- Preserve V1 storage names and migration paths; Go storage is separate.
- Recognize backend platform values 1, 2, 3, 5, 6, 10, 11 and 12. Payment
  implementation remains limited to Direct Android and Cafe Bazaar.
- A successful purchase result can still require delivery or checkout.
  Hosts must grant inventory durably and idempotently; the SDK cannot infer
  product quantities or provide an application inventory database.
- Go is a separate, explicit protocol migration with different credentials,
  identity validation and attribute semantics.
- Public source builds contain the SDK and synthetic tests, not the private
  integration application, APKs, local configuration or engineering notes.

### Security and limitations

- Scope-bound encrypted purchase journals retain resumable evidence.
  Permanent rejections use bounded one-way fingerprints.
- Normal diagnostics are filtered. The explicitly unsafe raw tracing helper is
  debug-only and is excluded from the release AAR.
- Downloaded Go keys cannot introduce new trust: new key material requires
  securely distributing a new pin.
- Myket, automatic Go-to-Laravel credential exchange and complete remote Paywall
  element rendering are not implemented.
- Device upgrades, live payment/recovery acceptance and rollout validation remain
  separate release gates; local unit tests are not substitutes for them.

### Verification

- 388 SDK unit tests pass, including 16 added V1 upgrade regressions.
- Local V1 comparison: 49 classes / 470 checked JVM members, with no removed or
  changed checked descriptors; eight precompiled V1 API tests pass against V2.
  This is bounded compatibility evidence, not a guarantee for every integration.
- Release lint and AAR build pass. See [acceptance evidence](V2_CONTRACT.md).

## [1.0.0] - 2026-08-30

### Added

- Native Configure, Login, Logout, CustomerInfo and Offerings.
- Ordered offering targeting and entitlement helpers.
- Direct checkout and Cafe Bazaar billing/recovery through Poolakey.
- Discount-code and custom/reserved attribute operations.
- Immutable snapshots and ordered event listeners.
- Keystore-backed AES-GCM session persistence and migration of SDK-owned data.
- Maven publication metadata.

### Security

- Redacted diagnostic representations; object properties remain available to
  the host and must not be treated as anonymized data.
- Instance-owned mutable client state and file-locked persistence.

[2.0.0]: https://github.com/mohsenghorbanipour/inappify_android_plugin/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/mohsenghorbanipour/inappify_android_plugin/releases/tag/v1.0.0
