# Android SDK API and behavior guide

This guide describes library 2.0.0 as implemented, including failure and cache
semantics. Start with [installation](../README.md#installation), then read
[migration hints](MIGRATION.md) for an existing app.

## Protocols and ownership

| Client/path | Authentication | Purpose |
| --- | --- | --- |
| `InappifyClient.create(context)` / V1 | API key and customer token in JSON | Existing lifecycle, resources and Direct checkout. |
| Bazaar Store V2 in the default client | Configured Laravel credentials in JSON | Store receipts, verification, delivery and consume reporting. |
| `InappifyV2Client.create(context)` / Go | Public SDK Bearer key for Configure; Go session Bearer afterwards | Signed customer/session APIs, offerings and attributes. |

The library version, HTTP route version, Paywall schema version and server
`forceVersion` are different values. A successful Go Configure does not supply
Laravel purchase credentials.

Keep one authoritative client in an Application/DI scope. Instances own their
memory/listeners/network resources, but production instances of the same
protocol share an installation's persisted file names; they are not independent
disk profiles. V1 and Go session files are separate. `close()` releases work and
listeners, not the customer account or pending receipt journal.

## Results, snapshots and error handling

Most operations suspend and return `InappifyResult.Success(data, snapshot)` or
`Failure(error, snapshot)`. Inspect the result's data/status, not only Success.

- `isConfigured` is usable client session state, not continuous network health.
- `isAuthenticated` means non-anonymous identity, not Bazaar account login or
  an active entitlement.
- `snapshot` reads memory without HTTP. Its revision counts state changes in
  this instance; it is not a payment or persistent delivery identifier.
- Snapshot properties include customer IDs and business data even though tokens
  are not exposed. Redacted `toString()` is not anonymization of properties.
- A Failure can accompany changed state or an operation already committed
  remotely. Cancellation/close does not guarantee a charge was cancelled.
- Coroutine cancellation can be thrown. Do not swallow it as a definitive
  failed purchase or immediately start a new purchase.

| Diagnostic | Meaning |
| --- | --- |
| `operation` | Failing stage; often more useful than the top-level code. |
| `outcomeMayHaveCommitted` | Remote mutation/payment may have happened; recover first. |
| `stateApplied` | V1 memory state was applied despite a later save failure. |
| `storageStage`, exception type fields | Local encrypted persistence failure, not necessarily a marketplace outage. |
| `category`, `serverCode` | Go protocol category/code; signature codes can be SDK-generated. |
| `mismatchField`, `validationRule`, `signatureVerified` | Which identity/scope check failed and whether cryptographic verification preceded it. |
| `httpStatus=200` with signature failure | Transport succeeded; SDK rejected the response contents. |
| `isRetryable` | Error classification, never an instruction to create a second charge. |

V1 `STORE_UNAVAILABLE` can also describe secure session persistence errors.
Use the diagnostic stage before blaming Bazaar. V1 resource failure flags are
available in snapshots; Go currently does not use those flags to represent
resource errors, so inspect Results and traces.

## Configure and app metadata

### Existing V1-compatible client

```kotlin
val client = InappifyClient.create(applicationContext)
val configured = client.configure(
    InappifyOptions(
        apiKey = appApiKey,
        market = InappifyMarket.BAZAAR,
        marketKey = bazaarRsaPublicKey,
    ),
)
```

The factory itself does not call Configure. Options also accept an optional
`appUserIdentifier`, `country` and targeting `appVersion`. Android package,
version name and version code come from the host application metadata.
V1 normalizes country by trimming/uppercasing; IR is the default.

Configure is serialized. It searches memory/encrypted storage for a same-key,
matching-identity session. A reusable V1 session is still refreshed over the
network through CustomerInfo. Unauthorized/unconfigured sessions can lead to
fresh Configure; an ordinary network failure does not blindly create a new user.

A pre-V2 Bazaar cache lacking route metadata triggers fresh Configure with the
retained cached identity/recovery binding. An unexpected replacement identity is
rejected. A new session calls Configure, validates the response/customer and
requests Offerings. An Offerings failure alone need not fail Configure;
`failedToLoadOfferings` records it.

V1 applies memory state before saving. On save failure it reports diagnostics
and attempts to clear a stale session file; it does not erase pending purchases
as a substitute for recovery. Events may already describe the new memory state.

After lifecycle success the SDK can reconcile matching Bazaar operations and
subscriptions, and Direct deliveries when a handler exists. This is why one
Configure button can produce more than one HTTP exchange.

### Opt-in official Go client

```kotlin
val go = InappifyV2Client.create(applicationContext)
val configured = go.configure(InappifyOptions(apiKey = goPublicSdkKey))
```

The official API base is `https://service.inappify.com/app/v2/`.
Package/version metadata and a stable `$INAAnonymousID:<UUID-v4>` are managed by
the SDK. A newly generated UUID is persisted before the first Configure HTTP
request; retry/restart must not generate a different customer.

The host does not enter issuer, appId, projectId or signing keys for the official
factory. A bundled public signing pin establishes trust; verified response
claims establish scope. Subsequent responses must match it.
`verifiedSessionScope` is read-only and null before verification/pending logout.

The explicit configuration overload remains available for an explicitly trusted
environment. It requires a validated HTTPS base with path `/app/v2/`, expected
scope, signing pins and exact allowed hosts; never populate it with guessed
values or an untrusted key downloaded alongside the response.

A valid same-app Go cache can satisfy Configure immediately; background recovery
then refreshes resources. Go verifies candidate data and saves it before publishing
the new identity. Invalid cache does not grant access. Changing API key on the
same Go instance requires a new client.

See [Go integration](../V2_MIGRATION.md) for complete configuration and cache rules.

## Login, logout and identity

`login(InappifyLoginRequest(...))` uses a stable host account identifier.
It is not a username/password login to Bazaar and is not an SDK API key or
session token. Use the same backend account ID on every device.

V1 retains its established login/logout contract. Go custom IDs are more
restrictive: 16–100 characters, start with ASCII letter/digit, remaining characters
from letters/digits and `._:-`, not entirely numeric or a forbidden placeholder.
Do not silently transform an old identifier into a new account.

Go's signed `sub` is an internal customer subject, not the public UUID.
The signed customer's `originalAppUserId` must match the public requested
identity. Refresh/renew/cache preserve the internal subject; verified lifecycle
transitions can establish a new binding.

Identity transitions serialize with mutations/fulfillment. Go offline logout
persists a pending intent and hides the prior user; recovery must complete that
logout before treating a session as active. Cache/queued attributes are
identity-scoped. Rebind the legacy purchase companion after identity changes.

Logout does not destroy recoverable marketplace evidence. A different identity
cannot replay that evidence. The host must independently scope its inventory
ledger and UI to the configured app/customer.

## CustomerInfo, Offerings and cache

| API | V1 default client | Go client |
| --- | --- | --- |
| `getCustomerInfo()` / `refreshCustomerInfo()` | Network refresh. | NETWORK_ONLY. |
| `getCustomerInfo(false)` | Reuse cache younger than five minutes; otherwise network. | CACHE_FIRST. |
| `getOfferings()` | Session-bound cached offerings when present. | CACHE_FIRST. |
| `refreshOfferings()` | Network refresh. | NETWORK_ONLY. |
| `snapshot` and local entitlement helpers | Memory only. | Memory only, with Go entitlement expiry checks. |

Offerings are invalidated by relevant identity, targeting or force-version
changes. A stale selected product must not be used to initiate a new purchase.
`forceVersion` is server synchronization state, not the SDK version.

Go's explicit `customerInfo(policy)` and `offerings(policy)` provide:

| Policy | Behavior |
| --- | --- |
| CACHE_ONLY | Return available accepted cache; fail on cache miss, no resource fetch. |
| CACHE_FIRST | Return cache and schedule background refresh when available; otherwise fetch. |
| NETWORK_FIRST | Attempt network; on failure return remaining accepted cache if present. |
| NETWORK_ONLY | Require the network operation to succeed; do not return cache as fallback. |

NETWORK_FIRST fallback can mask a refresh failure behind a successful cached
result. Use NETWORK_ONLY when your workflow must observe the online result.
A valid cached signature does not prove the server has not since revoked access.

Go Offerings refresh flushes queued attributes before requesting the catalog.
A failed attribute flush can therefore prevent the Offerings fetch.
Equivalent concurrent refreshes share work; cancellation by one observer does
not automatically cancel everyone else's request.

### Selecting offerings and checking entitlements

`getCurrentOffering(placementIdentifier, forceRefresh, context)` selects an
offering. V1 uses its existing ordered local targeting resolver and context;
Go uses the server's current offering/placement mapping, not V1 local rules.
`setTargetingContext(country, appVersion)` updates context and invalidates
affected cached offerings.

`getEntitlement`, `hasEntitlement`, `isActiveEntitlement` and
`isCustomerAnonymous` are local helpers. `checkEntitlement(identifier,
forceRefresh=true)` refreshes CustomerInfo by default before checking.
An entitlement is not a consumable quantity or a substitute for a delivery.

## Attributes and discounts

V1 exposes `setAttributes`, `updateAttributes`, `deleteAttributes`,
`syncAttributes`, `setReservedAttribute` and `canSetReservedAttribute`.
Convenience setters include email, display name (both existing spellings),
phone, IP, FCM/APNs token, IDFA/IDFV, campaign and keyword.

V1 preserves its existing semantics:

- Invalid custom entries are ignored; null/blank custom values remove entries.
- Local customer attributes are optimistically updated before HTTP.
- Store failures do not imply local rollback; removal in mixed/set operations
  is best effort.
- Full `syncAttributes` is different from a merge and can replace the local list;
  a transport/non-200 sync failure clears the local attribute projection.
- Reserved keys follow their dedicated validation/availability rules.

Go attributes are write-only:

- `queueAttributes(map)` validates and durably coalesces changes.
- `flushAttributes()` sends batches of at most 50; values are bounded to
  500 Unicode code points. Null/empty values delete.
- `setAttributes` queues then flushes; inherited mutation APIs return an empty
  attribute list on success, not a server read-back.
- `syncAttributes()` without input flushes pending writes, not V1 full read-back.
- Retryable failures keep queued values. HTTP 422 quarantines the rejected batch
  instead of endlessly retrying it; a corrected value must be queued explicitly.
- Login/logout must not replay one customer's attributes into another.

`validateDiscountCode(InappifyDiscountCodeRequest(...))` performs validation.
Inspect the returned business result; a completed HTTP request does not make a
discount valid. Do not reuse dynamic price tokens across unrelated purchases.

## Purchase routes, results and fulfillment

See [routing/migration](MIGRATION.md#2-understand-routing-before-adding-product-types)
and the [purchase examples](../README.md#cafe-bazaar-purchase).
A new purchase validates the selected offering/product and request scope.
A resumable paid attempt is checked before catalog validation.

Bazaar UI requires an Activity implementing `ActivityResultRegistryOwner` and
`LifecycleOwner`, such as ComponentActivity/AppCompatActivity. Trials and
Direct requests do not open store UI. Match explicit product type to the store
catalog. The old untyped constructor remains available for existing IN_APP code.

Store callbacks are evidence, not fulfillment. The SDK validates receipt
product/package, persists scoped evidence and submits it to the server.
Polling belongs to the SDK; the host should not poll verification IDs separately.

| State | Meaning / next action |
| --- | --- |
| PROCESSING | Server verification pending; preserve attempt and recover. |
| COMPLETED | Authoritative non-consumable/subscription completion. |
| RESTORED / ALREADY_PROCESSED | Existing event recognized; do not duplicate a grant. |
| DELIVERY_REQUIRED | Verified consumable needs host-owned durable inventory delivery. |
| CONSUME_REQUIRED | Acknowledged delivery; SDK must consume/report the matching receipt. |
| REJECTED | Permanent verification rejection; not an entitlement. |

Bounded polling can return a retryable/deferred failure while work remains
pending. It is not permission to charge again. `purchaseStatus` retains the
V1 enum; `storePurchaseStatus` adds V2 state.

For consumables:

1. Register `setConsumableDeliveryHandler` before Configure, or choose the
   manual flow intentionally.
2. In a host database transaction, record a unique app/customer/delivery key and
   grant the mapped product together. The SDK cannot infer quantity from the SKU.
3. Return `DELIVERED` only after commit (including a previously committed receipt);
   otherwise `RETRY_LATER`.
4. The SDK acknowledges delivery. Bazaar is consumed only after the server
   permits consumption, then the result is reported. Direct acknowledges through
   its pending/delivered contract without marketplace consumption.

For manual flow, commit the same inventory transaction before
`confirmDelivery(deliveryId)`. If acknowledgement fails, retry it without
granting again. Never call a mutating SDK method from inside the handler;
fulfillment is already serialized and re-entry can block.

Subscriptions/non-consumables use authoritative entitlements/ownership, not
this inventory handler. Recovery of an untyped historical receipt may discover
a pending consumable, but never grants unconditionally.

## Direct checkout and recovery

A Direct `NEEDTOPAY` result is not payment confirmation. The host opens a URL
only after HTTPS/exact-host validation. Go exposes `isPaymentUrlAllowed(url)`.
The default Go payment host is `pay.inappify.com`; explicit environments can
configure their own trusted exact hosts.

A browser/deep-link return is a trigger to refresh/recover, never proof of a
successful payment. For Go, call `recover()` and inspect authoritative state;
for the default client refresh customer data and sync pending consumables.

`syncPurchases()` can advance pending work, not merely list purchases.
`restorePurchases()` reports independent restored/already-processed/failed
counts. `syncPendingConsumables()` drains applicable pending deliveries and
returns unresolved items. Aggregate Success can contain individual failures or
pending deliveries.

Invoke recovery on startup/foreground and connectivity restoration. Background
reconciliation/polling is bounded, not an Android job guaranteed to run while
the process is dead. Preserve attempts across interruption. The host-controlled
Go `setNetworkEnabled(false)` is a Go network switch, not a cancellation of an
already committed store charge or a global firewall for other clients.

## Events, threading and lifetime

`addEventListener` returns a registration; close it with the observing component.
Events carry immutable state snapshots and include state, authentication,
customer, offerings and purchase updates. Multiple events can share a revision.

V1 events run on an ordered SDK executor, not necessarily the main thread.
The production Go dispatcher is Main.immediate. Always respect your UI
framework's thread/lifecycle requirements. Delivery handlers run away from the
Android main thread and must not re-enter SDK mutations.

Go `stateUpdates()` offers a Flow of state revisions and unregisters on
collector cancellation. Close the client only when its owner is permanently
released; do not close an application-scoped client when one screen disappears.

## Storage, signatures and diagnostics

V1 session, Go session and pending receipt journal use separate encrypted
app-private persistence. Writes are atomic/file-locked, but app inventory
transactions are not part of that same transaction. Use a durable idempotency
ledger to cross that boundary safely.

Android 23+ uses Keystore AES-GCM; Android 21/22 uses a random AES key wrapped
by Keystore RSA. Encryption lets the provider generate the IV. Data is stored
under no-backup storage. Keep the same package/signing identity for real upgrades;
do not clear data to hide migration errors.

Go uses strict JSON and canonical Ed25519 JWS validation, with audience/version,
scope, identity and expiry checks. Public customer identity and internal subject
are separately bound. Cached signatures are rechecked; expired cache signatures
do not by themselves grant an expired entitlement. Unknown key IDs can discover
aliases of already pinned key material, never arbitrary new trusted keys.
New signing material requires secure pin distribution.

`addHttpTraceListener` is opt-in and bounded. Normal traces filter credentials,
customer IDs, attributes, checkout data and receipts before callbacks.
Method, endpoint, status, duration, request ID and failure category help correlate
requests. Remove listeners after troubleshooting; never upload raw secrets or
real receipt fixtures to public issue trackers.

Debug-only unsafe trace factories are excluded from release artifacts.
A release consumer should not depend on those factories.

## Limits and release evidence

Only Direct Android and Cafe Bazaar payments are implemented. Go purchase
bridging needs separately configured matching legacy credentials; full remote
Paywall element rendering and automatic credential exchange are not implemented.
The native package fallback is not a complete remote schema renderer.

See [acceptance evidence](../V2_CONTRACT.md) for verified local checks and pending
device/backend gates. See [release checklist](RELEASING.md) for artifact/privacy
validation.

## Source map

- [Public V1 API](../sdk/src/main/kotlin/com/inappify/sdk/InappifyClient.kt)
- [Go public APIs](../sdk/src/main/kotlin/com/inappify/sdk/InappifyV2.kt)
- [V1 lifecycle/resources](../sdk/src/main/kotlin/com/inappify/sdk/internal/DefaultInappifyClient.kt)
- [Go lifecycle/resources](../sdk/src/main/kotlin/com/inappify/sdk/internal/v2/GoV2Client.kt)
- [Go protocol verification](../sdk/src/main/kotlin/com/inappify/sdk/internal/v2/V2Protocol.kt)
- [Store coordinator](../sdk/src/main/kotlin/com/inappify/sdk/internal/StoreV2Coordinator.kt)
- [Fulfillment contract](../sdk/src/main/kotlin/com/inappify/sdk/InappifyConsumableFulfillment.kt)
- [Encrypted storage](../sdk/src/main/kotlin/com/inappify/sdk/internal/storage/EncryptedSessionStateStore.kt)
