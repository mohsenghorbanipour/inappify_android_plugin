# Version 3 implementation and acceptance

Version 3.0.0 removes all production V1 routes and mobile implementations.
Both Android factories create V2; Flutter defaults to goV2, Android uses the
native bridge and iOS uses Dart V2 with Keychain. The public app key remains only
for Configure bootstrap/renewal. All other requests use the verified current
session Bearer, including every Direct/store commerce operation.

| Area | Contract |
| --- | --- |
| Authentication | Pinned Ed25519, signed scope/subject/time, opaque encrypted session, atomic persistence before publication |
| Identity | Stable anonymous ID, durable login/logout attempts, pending-transition barrier |
| Resources | Four fetch policies, identity/context invalidation, no Go forceVersion |
| Targeting | Write-only durable attributes, ordered sync/fetch, server currentOffering/placements retained through Flutter/cache, SDK version exactly 3.4.0 |
| Commerce | Eight V2 POST routes, current Bearer, no legacy credential/context body fields, receipt evidence preserved |
| Renewal | Only SESSION_EXPIRED renews/replays once; ambiguous checkout timeout is not automatically replayed |
| Stores | DirectAndroid, Bazaar, Myket in-app; unsupported/missing routes fail closed |
| Fulfillment | Durable host grant before ACK, selected-store consume/report, bound checkpoint recovery after restart |
| Migration | No V1 HTTP implementation or companion; old paid operations require their original client/ledger, no relabel/deletion |
| Diagnostics | Filtered default traces, Debug raw entry excluded from release |

Version 3.1.0 reconciles the selected native store after Configure, before
Offerings refresh. Pending checkpoints always resume; newly discovered
non-consumable/subscription receipts are skipped only when verified server
entitlements match both the token hash and purchase time. Consumables and
unknown product types still reach server verification.
A renewal whose store token and purchase time both remain unchanged requires
backend verification and cannot be identified from these fields alone.

Version 3.2.0 resolves missing/null/blank payload product types from the native
subscription query before reconciliation. Explicit conflicting/malformed types
and account/app bindings remain checked; unknown in-app receipts keep their
server delivery/consume workflow. No receipt evidence or V2 wire field is changed.

Version 3.3.0 adds a versioned `purchaseBinding` inside the developer payload of
new Bazaar/Myket purchases. It binds the verified app and public customer identity
to the product, offering and selected package. Recovery validates it only when
present; earlier receipts retain the existing guards. Public APIs and V2 journal
formats remain unchanged. The release passes 416 JVM tests in 32 suites and
all 95 build/publication tasks; lint has zero errors and one existing receiver
warning. Instrumentation was compiled, not executed.

Version 3.4.0 adds payloadless Bazaar subscription recovery, enabled by default through
`InappifyOptions.enableSubscriptionRecoveryWithoutPayload` (default true).
It requires the separate commerce route and ownership contract documented in
[SUBSCRIPTION_RECOVERY.md](docs/SUBSCRIPTION_RECOVERY.md). Deploy the Laravel
counterpart before releasing this SDK; applications can explicitly opt out with
`false`. Ordinary binding checks are not relaxed. All 444 JVM tests in 34 suites
and 95 build/publication tasks pass; lint has zero errors and one existing
receiver warning. Instrumentation is compiled, not executed.

Automated evidence covers synthetic HTTP, lifecycle, storage, billing adapter,
market binding, targeting serialization and build integration. Instrumentation
compilation is distinct from execution. Live marketplace/backend acceptance is
not established by a successful build.

Before broad rollout, test registered/signed applications on actual Android/iOS
installations: custom attributes and SDK-version targeting, payment return,
store service/fallback behavior, cancellation, restart, verification expiry,
host durable delivery, failed ACK/consume and account transitions. Never clear
real pending payments for acceptance. Previous releases/tags remain immutable.
