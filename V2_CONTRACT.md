# V2 implementation and acceptance

This is a code-derived implementation/acceptance checklist, not a claim that
every device or backend integration has passed. The original design input was
the September 2026 Go SDK V2 contract, version 1.1. The matrix includes the 2.2.0
commerce/session integration, 2.3.0 targeting-sync extension and 2.4.0 Myket support plus 2.5 unified session commerce; evidence for older releases does not certify
the newer changes.

Library 2.x contains two different V2 protocols: Laravel Store V2 payments and
opt-in Go V2 customer/session APIs. `InappifyClient.create(context)` remains the
V1-compatible factory. See the [migration guide](docs/MIGRATION.md).

## Implementation matrix

| Area | Implemented behavior |
| --- | --- |
| V1 upgrade | Retained API entry points and storage; cached Bazaar identity/recovery binding preserved; untyped request market selection retained. |
| 2.1 offline bootstrap | Additive cache-only restoration of a matching encrypted legacy session, existing snapshot/events, host-owned background refresh and identity/logout barriers; durable known-auth rejection for opted-in sessions. |
| Store payments | Direct Android and Bazaar, plus Myket since 2.4.0; explicit types, verification checkpoints, polling and recovery. Unsupported stores fail explicitly. |
| Consumables | Host-owned idempotent inventory transaction; acknowledgement only after durable delivery, then selected-market consume/report. |
| Go trust | Pinned Ed25519, audience/version/scope/subject/time checks. Official factory discovers scope only from a verified response; explicit configuration can constrain it beforehand. |
| Go identity | Stable anonymous UUID; verified session saved before publication; durable pending login/logout attempts; internal signed subject separate from public user identifier. |
| Go resources | Four cache policies, shared refreshes, server-selected offerings/placements and write-only durable attributes. Go does not use V1 forceVersion/hasForceUpdate. |
| 2.3 targeting sync | Additive extension on both clients: attribute upload then fresh offerings under one operation lock; rolling five-call/minute admission and warning/cache-only fallback at the limit. Missing current cache is an explicit retryable failure. Existing protocol-specific identity barriers remain unchanged. |
| Go commerce | All seven Direct/store commerce routes use current-session Bearer and omit legacy credentials/context. Only 401 SESSION_EXPIRED renews and replays once. Both transports use redacted diagnostics. |
| Purchase companion | Deprecated and disabled in 2.5 while retaining the JVM signature. Original V1 clients retain their own recovery; no journal migration occurs. |
| Presentation | Native package fallback, compatibility/size/depth gates, app-owned typography and payment URL allowlists. |
| Diagnostics | Opt-in filtered bounded HTTP exchanges plus storage and signature-stage context; no raw tracing helper in the release variant. |

Go defaults to `https://service.inappify.com/app/v2/`.
Commerce defaults to `https://api.inappify.com/app/v2/`.
V1 and Laravel Store V2 routes are unchanged.

## Not implemented or not guaranteed

- Myket native subscriptions and dynamic-price tokens; Play Store, Apple stores
  and other enum-only stores. Myket in-app support still needs
  live device/backend acceptance; see [Myket integration](docs/MYKET.md).
- Automatic migration of V1 paid operations or host ledgers into the Go identity
  namespace. Recover those operations with the original V1 integration.
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

### 2.5.0-rc.2 unified V2 commerce — October 4, 2026

Canary based on the Android purchase migration contract (document 1.1). All
**621 JVM tests in 34 suites** pass with zero failures/errors/skips. Coverage
includes actual local HTTP for all seven commerce paths and current Bearer/body
contracts, secret redaction, Direct DONE/NEEDTOPAY, bounded expiry renewal,
rejected invalid/revoked sessions, failed signature/storage during renewal,
concurrent renewal, Login/Logout token replacement, Direct process restart and
ACK uncertainty, account/endpoint isolation, store Retry-After after restart,
and verification/consume-report renewal for both Bazaar and Myket. Existing V1
regressions and store-journal tests remain enabled. Superseded legacy bridge
behavior tests now verify explicit rejection without modifying either journal.

The independent public-source build with Gradle 8.7/JDK 17 runs all 95 release
tasks without build-cache reuse: JVM tests, release lint/AAR/sources, Maven publication metadata and
instrumentation compilation. Lint has zero errors, 14 dependency-update suggestions
and the existing Myket exported-receiver warning described below. Instrumentation
and real payments have not run. Native V1 source, existing Go store journal format
and private integration applications are unchanged.

Public/protected JVM declaration/descriptor comparison outside the internal package
with the hash-verified public 2.4.0 AAR examines 89 classes in each artifact and
finds no removed/changed declarations. This includes retaining the deprecated
bridge signature; its explicitly changed behavior requires host migration. The
comparison is not comprehensive Kotlin metadata or runtime/device certification.

The first rc.1 tag CI exposed a diagnostic-test timing race: the fixture closed
the client before its asynchronous trace callbacks finished. rc.2 synchronizes
that assertion without changing production diagnostics or commerce behavior.
The corrected HTTP/diagnostic test also passed 30 consecutive isolated runs.

Use this canary for staged backend/device acceptance. No claim of a successful
real payment, production rollout or automatic Flutter protocol migration is made.

### 2.4.0 Myket integration — October 3, 2026

All **607 JVM tests in 33 suites** passed with zero failures, errors or skips
(83 additional test executions versus 2.3.0, including parameterized Bazaar/Myket
coordinator, commerce and lifecycle cases). Coverage includes fresh purchase,
delivery-before-consume, signed receipt scope, invalid signatures, cross-store
replay rejection, unsupported subscriptions and cancellation. These JVM tests
use synthetic billing/server fixtures; they do not run the Myket application.

The SDK AAR, publication sources, Maven-local metadata/publication and
instrumentation APK compiled with Gradle 8.7/AGP 8.5.1. Release lint has zero
errors, 14 dependency-update suggestions and one `ExportedReceiver` warning: the official Myket broadcast fallback
requires a cross-app receiver without a documented sender permission. Replies
are gated by an unpredictable per-helper nonce; receipts additionally require
RSA verification and existing backend verification. The receiver is not an
independent payment authority. No global lint suppression was added.

A separate temporary consumer resolved the locally published Maven artifact
and its transitive dependencies, without project substitution, Myket dependency,
manifest placeholders or host manifest overrides. Both Debug (D8 duplicate-class
check) and minified Release (R8) built successfully with minSdk 21/compileSdk 34.
Both store adapters and Binder namespaces occur in the R8 mapping; the merged
manifest includes both permissions and no unresolved placeholders. Neither APK
was installed or executed.

Public/protected JVM descriptor comparison with the downloaded `v2.3.0` AAR
(**88 classes / 692 members**) found no removed descriptors or incompatible
access/supertype/static/final/abstract changes. The candidate has **88 / 694**;
this is bounded binary evidence, not complete Kotlin metadata or device
certification. The SDK contains no original `com.android.vending.billing`
classes; those remain owned by Poolakey. The Myket Binder namespace is isolated.
Publication sources have no duplicate entries, and archives exclude the private
sample, local settings, engineering notes and unsafe debug tracing helpers.

The release version is 2.4.0. No device install, payment or backend
configuration was performed as part of local verification. V1/Go backend acceptance, Myket service/broadcast
fallback and actual purchase/recovery/consumption on a registered app remain
rollout gates. See [Myket setup](docs/MYKET.md).

### 2.3.0 targeting sync — October 1, 2026

All **524 SDK JVM tests in 32 suites** passed with zero failures, errors or skips,
including 39 additional tests. The new coverage includes V1/Go request ordering,
fresh fetch despite cache, cache-only throttling, exact/rolling clock boundaries,
concurrent bursts, attribute/offerings failures, cancelled operations, storage
failures, serialized Login, account/context cache invalidation, protocol-specific
logout barriers, events/persistence, Go multi-batch/deletion/quarantine behavior
and old custom-client compatibility. V1 pending-logout guards remain host-owned;
the extension does not silently change the legacy lifecycle contract.

All **95 release verification tasks** executed with the build cache disabled:
SDK-only unit tests, release lint (zero issues), release AAR/publication sources,
Maven-local metadata/publication and instrumentation compilation. Dependencies
were resolved offline; a task-local mirror definition reused the existing cache,
without changing public repositories. The freshly downloaded Gradle 8.7 archive
matched its official SHA-256. Instrumentation was not executed on a device.

The release AAR embeds SDK version 2.3.0. Its checked public/protected JVM surface
has **88 classes / 692 members**; comparison with published V1 (**50 / 475**),
V2.1 (**86 / 681**) and V2.2 (**86 / 690**) found no removed descriptors/supertypes,
narrowed access or incompatible static/final/abstract restrictions. Existing
public interface source files were not changed. This is bounded classfile
evidence, not certification of every Kotlin metadata, reflection or runtime use.

Archive inspection found no private application, engineering notes or debug-only
raw HTTP logger. The publication sources contain no duplicate entries. A bounded
comparison against four private local configuration values found no matches in
public sources or artifacts; it is not a comprehensive secret scan. No sample,
Flutter/iOS/Web change, real-device install, live targeting call, purchase or data
reset was performed for this release. Backend/device acceptance remains required.

### 2.2.0 integration — September 30, 2026

All **485 SDK JVM tests in 29 suites** passed with zero failures, errors or skips.
Release lint reported zero issues. The versioned 2.2.0 release AAR, sources JAR,
Maven-local publication/metadata and instrumentation APK were rebuilt with all
95 tasks executed and the build cache disabled. Instrumentation was compiled,
not executed. Dependency resolution used cached dependencies/offline mode.
The AAR embeds SDK version 2.2.0; the actual publication sources archive has no
duplicate entries, and neither release artifact contains private application or
debug-only unsafe tracing code. Artifact checks are not live payment acceptance.

The fresh candidate release AAR retains the checked public/protected JVM surface
of V1 (**50 classes / 475 members**) and V2.1 (**86 classes / 681 members**).
The candidate has **86 classes / 690 members**. The comparison found no removed
descriptors, narrowed visibility, incompatible static/final/abstract changes or
removed supertypes. In particular, the original configuration constructor,
Kotlin default-argument constructor and `getApiBaseUrl()` remain present.
This does not certify Kotlin metadata, generic signatures, reflection or runtime
behavior. Device upgrade, live backend and payment acceptance remain separate.

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
