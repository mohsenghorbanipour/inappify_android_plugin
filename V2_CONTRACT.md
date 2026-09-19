# V2 implementation and acceptance

This is a code-derived implementation/acceptance checklist, not a claim that
every device or backend integration has passed. The original design input was
the September 2026 Go SDK V2 contract, version 1.1.

Library 2.x contains two different V2 protocols: Laravel Store V2 payments and
opt-in Go V2 customer/session APIs. `InappifyClient.create(context)` remains the
V1-compatible factory. See the [migration guide](docs/MIGRATION.md).

## Implementation matrix

| Area | Implemented behavior |
| --- | --- |
| V1 upgrade | Retained API entry points and storage; cached Bazaar identity/recovery binding preserved; untyped request market selection retained. |
| 2.1 offline bootstrap | Additive cache-only restoration of a matching encrypted legacy session, existing snapshot/events, host-owned background refresh and identity/logout barriers; durable known-auth rejection for opted-in sessions. |
| Store payments | Direct Android and Bazaar; explicit types, verification checkpoints, polling and recovery. Unsupported stores fail explicitly. |
| Consumables | Host-owned idempotent inventory transaction; acknowledgement only after durable delivery, then Bazaar consume/report. |
| Go trust | Pinned Ed25519, audience/version/scope/subject/time checks. Official factory discovers scope only from a verified response; explicit configuration can constrain it beforehand. |
| Go identity | Stable anonymous UUID; verified session saved before publication; offline pending logout; internal signed subject separate from public user identifier. |
| Go resources | Four cache policies, shared refreshes, server-selected offerings/placements, forceVersion/hasForceUpdate and write-only durable attributes. |
| Purchase companion | Explicit, scope-matched legacy credentials in an isolated companion; Go Bearer tokens are never used as Laravel customer tokens. |
| Presentation | Native package fallback, compatibility/size/depth gates, app-owned typography and payment URL allowlists. |
| Diagnostics | Opt-in filtered bounded HTTP exchanges plus storage and signature-stage context; no raw tracing helper in the release variant. |

Go defaults to `https://service.inappify.com/app/v2/`.
V1 and Laravel Store V2 routes are unchanged.

## Not implemented or not guaranteed

- Myket, Play Store, Apple stores and other enum-only stores.
- An automatic Go-to-Laravel credential exchange: no official bridge endpoint is
  implemented. Hosts need supported, separately configured purchase credentials.
- Complete rendering of arbitrary remote Paywall element/action documents.
  Unsupported documents must use the native package fallback.
- A host inventory database or globally exactly-once fulfillment without a
  durable, scoped idempotency ledger supplied by the host.
- Independent persisted profiles merely by constructing multiple clients.
- Elimination of all external outages, device Keystore failures or backend
  contract errors.

The publisher must review the bundled official signing pin and its rotation
plan. Runtime key discovery cannot establish trust in unrelated key material.

## Local evidence

### 2.1.0 — September 19, 2026

All **419 SDK JVM tests in 23 suites** passed with zero failures, errors or skips.
The SDK-only release lint, release AAR/sources, Maven-local publication and
instrumentation compilation also passed. The local offline lint report contains
zero issues; this does not establish that every dependency is the newest version.
Two additional encrypted-cache instrumentation cases were compiled, not executed.

A classfile comparison of matching published release artifacts checked **475
public/protected JVM members across 50 V1 classes** and **679 members across 84
V2 classes**, with no removed descriptors, changed static/visibility contracts,
new final restrictions or removed original supertypes. This is bounded binary
surface evidence, not full Kotlin metadata or behavioral certification. Existing
API tests and legacy behavior regressions passed separately in the JVM suite.

The new cache tests cover no-network restoration, exact key/customer matching,
malformed and oversized data, expiry syntax, catalog targeting, no legacy
preference import, auth rejection across restart, storage failure and lifecycle
cancellation. See [offline integration and limits](docs/OFFLINE_CACHE.md).
No real-device installation, payment or upgrade was performed for this release.

### Previous 2.0.0 evidence

On September 17, a fresh copy containing only public candidate sources (no
integration app or local configuration) passed all **388 SDK tests in 22 suites**,
with zero failures, errors or skips. Release lint reported zero errors and
14 dependency-version warnings; AAR,
release sources JAR and instrumentation APK compilation succeeded. All 92 tasks
executed with the build cache disabled. No device tests were executed.

The normal checkout also passed SDK-only build and Maven-local publication.
The release AAR/sources contained no private application or debug-only unsafe
tracing helper. Public file links and index-boundary checks passed; the boundary
check was also tested to reject tracked private application/local-note fixtures.

The September 16 compatibility check passed **388 SDK unit tests**, with no
failures/errors/skips, release SDK lint with zero issues, and the release AAR
build. Sixteen added regressions cover retained identity/recovery binding,
app/customer isolation, untyped receipt delivery after journal recreation,
retry with invalidated offerings, V1 market selection and Go companion routing.

A local comparison with `v1.0.0` found no removed/changed descriptors among
470 checked JVM API members across 49 classes. Eight tests compiled against V1
ran against the matching V2 variant without recompilation. This is bounded ABI
evidence, not a complete Kotlin metadata or third-party integration guarantee.

SDK instrumentation sources compile, but compilation is **not device execution**.
No real-device upgrade or live backend purchase was performed by these checks.

Reproduce the public SDK checks with JDK 17 and Android SDK 34:

```sh
bash scripts/check-publication.sh
./gradlew :sdk:testDebugUnitTest :sdk:lintRelease :sdk:assembleRelease :sdk:assembleDebugAndroidTest
```

## Required device and backend acceptance

| Scenario | Required evidence | Status |
| --- | --- | --- |
| Existing V1 app upgrade | Update without uninstall/clear; preserve customer, pending receipts and inventory ledger. | Pending device acceptance |
| Trust/auth | Real signed lifecycle responses, expiry, invalid signatures, wrong scope/subject and planned signing-key rotation. | Unit-covered; live acceptance pending |
| Offline identity | Restart during pending logout; no prior-user data or attribute replay to the next user. | Unit-covered; device acceptance pending |
| Consumable | Restart at verification, grant, acknowledge, consume and report checkpoints; inventory granted once per scope/delivery. | Unit-covered; device/store acceptance pending |
| Subscription/non-consumable | Restore and renewal update authoritative entitlements; no consumable grant/consume path. | Unit-covered; device/store acceptance pending |
| Direct checkout | Safe URL handling, authoritative refresh on return and pending consumable recovery. | Live checkout acceptance pending |
| Android storage | Keystore/restart/upgrade on supported API levels, including legacy compatibility path. | Instrumentation compiled; device execution pending |
| Remote Paywall | Complete published schema, accessibility, RTL/LTR and bounded safe assets. | Full schema rendering deferred; native fallback available |
| Rollout/rollback | Backend readiness, metrics, host kill switch and explicit return to retained V1 client. | Operator acceptance pending |

See [API behavior](docs/API_GUIDE.md), [Go integration](V2_MIGRATION.md) and the
[release checklist](docs/RELEASING.md). No row marked pending should be represented
as a passed automated test or a production certification.
