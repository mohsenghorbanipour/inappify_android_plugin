# Android API and behavior guide (3.3.0)

Both client factories return V2. Keep one authoritative application-scoped
client, handle `InappifyResult.Success`/`Failure`, and render only its current
snapshot. `close()` cancels work and listeners without logging out.

## Authentication and endpoints

Configure uses a public app key and app metadata. A successful session must pass
Ed25519, audience/version, scope, subject and time verification and persist
atomically before publication. Other SDK requests carry the opaque current
session Bearer. Resource APIs use `https://service.inappify.com/app/v2/`.
Commerce uses `https://api.inappify.com/app/v2/`.

| Commerce POST path, relative to the V2 base | Body |
| --- | --- |
| `purchase` | productIdentifier, offeringIdentifier, optional paywallId/paywallRevision, isCrypto |
| `consumable-deliveries/pending` | empty object |
| `consumable-deliveries/{id}/delivered` | empty object |
| `store/purchases` | productIdentifier, offeringIdentifier, operation, purchase evidence |
| `store/verifications/{id}/status` | empty object |
| `store/deliveries/{id}/delivered` | empty object |
| `store/deliveries/{id}/consume-result` | result and optional errorCode |

Every commerce request uses session Bearer and omits legacy credentials and app
context. Nested `purchase.token` is receipt evidence, not a session credential.
Only `401 / SESSION_EXPIRED` admits one Configure under the operation mutex and
one replay after verified persistence. REQUIRED/INVALID/REVOKED do not create an
automatic renewal loop. Ambiguous Direct checkout timeouts are not replayed.

## Identity and resources

The client keeps a stable anonymous identifier. Login/logout attempts are
persisted before transitions; uncertain attempts block publication of stale
account state. Call `recover` on foreground, connectivity restoration and
payment return. Custom identifiers accept 1–100 Unicode code points, without
controls, edge whitespace or reserved anonymous prefixes. Never turn a session
token into a user ID.

`customerInfo` and `offerings` support `CACHE_ONLY`, `CACHE_FIRST`,
`NETWORK_FIRST`, `NETWORK_ONLY`; failures do not manufacture verified state.
Relevant targeting/identity changes invalidate Offerings. Current offering and
placement selection come from the server. Version 3 sends SDK version `3.3.0`,
independently of host app version. Old force-version state is not used by V2.

## Attributes and targeting sync

Use `queueAttributes` for durable write-only values, then `flushAttributes` or
`syncAttributesAndOfferingsIfNeeded`. Null/empty deletes a key. Batches contain
at most 50 values. A failed sync prevents the combined operation's fresh
Offerings fetch. Reserved keys use the supported V2 key set.

The combined sync/fetch admits five calls per rolling 60 seconds per client using
a monotonic clock. A failed or canceled admitted operation counts. Login/logout
does not reset the rate budget. At the limit the operation performs no HTTP,
returns usable current cache, or gives a retryable RATE_LIMIT failure with
retryAfterMillis when cache is absent. This limit does not count all SDK traffic.

## Purchases and fulfillment

The verified session's storePlatform selects DirectAndroid (2), Bazar (10) or
MyKet (11). Unsupported/missing/conflicting routes fail. Bazaar uses Poolakey;
Myket uses its isolated included adapter and application-specific RSA public
key. Myket supports only consumable/non-consumable in-app products.

Use an explicit product type and stable attempt identifier. Store UI needs the
current foreground AndroidX lifecycle/activity-result owner. Validate Direct
payment URLs with the SDK allowlist. A return URL or receipt is not entitlement
authority. PROCESSING/DELIVERY_REQUIRED/CONSUME_REQUIRED remain pending states.

For consumables, commit the host inventory grant durably and idempotently before
`confirmDelivery` or returning successfully from a registered delivery handler.
The SDK ACKs only after that checkpoint, then consumes and reports through the
same marketplace. Recovery preserves app, identity, endpoint and market binding.
An ACK continues after restart even if the delivery disappears from pending.
Do not clear journals or rewrite old delivery identifiers. `syncPurchases` and
`restorePurchases` query the selected store; Direct pending deliveries have their
own V2 recovery path.

Successful Configure schedules native store recovery in the background, including
when reusing a valid cached session. Bazaar queries both owned in-app purchases
and subscriptions; Myket queries in-app purchases only. Recovery attempts store
reconciliation before Offerings, even if CustomerInfo refresh failed. A resource
failure is still reported by `recover`, but does not block the store attempt.

For newly discovered non-consumables/subscriptions, automatic sync skips a receipt
only when a verified current-customer entitlement matches both
`purchaseStoreRefHash = SHA256(purchaseToken UTF-8)` and the exact
`purchaseStoreTime` in epoch milliseconds. Entitlement activity is irrelevant to
this comparison. A changed subscription purchase time is a new occurrence even
if its token is unchanged. Failed CustomerInfo refresh disables this optimization
for that automatic sync. Missing fields require server verification, not guesses.

An older bound receipt may omit `productType` or leave it empty. A receipt from
the marketplace subscription query is then treated as a subscription for
reconciliation, hash/time comparison and durable renewal checkpoints. Its
original payload/signature are forwarded unchanged. An in-app query cannot
distinguish consumables from non-consumables, so an omitted type retains the
existing unknown in-app server workflow. An explicitly conflicting type remains
invalid. Other payload/app/account guards still apply; this does not import V1
receipts that lack the existing V2 recovery binding.

New Bazaar/Myket purchases include `purchaseBinding` in their developer payload.
It binds the verified app ID, Android package name, SHA-256 of the public
app-user identifier, product identifier, offering identifier and selected native
package identifier. Values use UTF-8 byte-length prefixes before SHA-256, with a
versioned `v1:` result. This is a binding format version, not a V1 API route.
Recovery validates this field when present and forwards the original evidence
unchanged. Receipts without it retain all existing recovery guards. No new
public method or Flutter channel argument is required.

Pending journal operations always continue regardless of that comparison.
Consumable/unknown in-app receipts also continue through the server workflow;
entitlement metadata alone does not prove delivery or consumption. Account,
app, marketplace and endpoint bindings remain required. Renewal decisions and
grants are authoritative server results; the SDK does not invent receipt changes.
Call `recover` on subsequent foreground/connectivity events to retry interrupted
work; Configure success does not mean background store recovery has completed.

## Diagnostics and events

Snapshots/events do not expose the V2 session token. Standard HTTP diagnostics
redact credentials, receipt/signature data, customer identifiers, delivery IDs
and payment URLs. Listener removal and client close are explicit lifecycle
boundaries. Marshal UI work to the app's UI dispatcher. The Debug-only raw trace
entry point is excluded from release AARs.

Storage, signing or malformed-response failures require inspecting the typed
failure category/stage, not assuming the market caused the failure. Builds and
synthetic tests cannot confirm live marketplace or backend acceptance.

See [migration](MIGRATION.md), [offline cache](OFFLINE_CACHE.md),
[Myket](MYKET.md) and [contract](../V2_CONTRACT.md).
