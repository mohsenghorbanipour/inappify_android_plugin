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
| Targeting | Write-only durable attributes, ordered sync/fetch, server currentOffering/placements retained through Flutter/cache, SDK version exactly 3.1.0 |
| Commerce | Seven V2 POST routes, current Bearer, no legacy credential/context body fields, receipt evidence preserved |
| Renewal | Only SESSION_EXPIRED renews/replays once; ambiguous checkout timeout is not automatically replayed |
| Stores | DirectAndroid, Bazaar, Myket in-app; unsupported/missing routes fail closed |
| Fulfillment | Durable host grant before ACK, selected-store consume/report, bound checkpoint recovery after restart |
| Migration | No V1 HTTP implementation or companion; old paid operations require their original client/ledger, no relabel/deletion |
| Diagnostics | Filtered default traces, Debug raw entry excluded from release |

Version 3.1.0 reconciles the selected native store after Configure, before
Offerings refresh. Pending checkpoints always resume; newly discovered
non-consumable/subscription receipts are skipped only when verified server
entitlements match both the token hash and purchase time. Consumables and
unknown product types still reach server verification. The release passes 403
JVM tests in 31 suites; lint has zero errors and the 15 existing warnings.
A renewal whose store token and purchase time both remain unchanged requires
backend verification and cannot be identified from these fields alone.

Automated evidence covers synthetic HTTP, lifecycle, storage, billing adapter,
market binding, targeting serialization and build integration. Instrumentation
compilation is distinct from execution. Live marketplace/backend acceptance is
not established by a successful build.

Before broad rollout, test registered/signed applications on actual Android/iOS
installations: custom attributes and SDK-version targeting, payment return,
store service/fallback behavior, cancellation, restart, verification expiry,
host durable delivery, failed ACK/consume and account transitions. Never clear
real pending payments for acceptance. Previous releases/tags remain immutable.
