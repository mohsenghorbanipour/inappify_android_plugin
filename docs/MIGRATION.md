# Upgrading from library V1 to V2

This guide separates a **library upgrade** from a **Go protocol migration**.
Do not replace the factory merely because the dependency version is 2.x.

## Upgrading from 2.4 to 2.5

- `v2.5.0-rc.1` is a canary for the unified V2 commerce contract. The default
  `InappifyClient.create(context)` factory and V1 wire/storage behavior remain unchanged.
  Library version 2.x alone does not select the Go protocol.
- `InappifyV2Client.create(context)` uses the current Go `sessionToken` as Bearer
  for **all seven** Laravel V2 purchase/fulfillment routes. No legacy `apikey`,
  customer `token`, `appIdentifier`, `country`, `appVersion` or `forceVersion`
  appears in those request bodies. Nested store `purchase.token` remains required.
  The public SDK key is still used only to bootstrap/renew the Go Configure session.
- Remove `bindLegacyPurchaseClient` calls. Its binary signature is retained,
  but it now returns `LEGACY_PURCHASE_BRIDGE_REMOVED` without touching either
  client's state. Recover pre-existing V1 payments with the original V1 client,
  credentials and host ledger. Do not relabel or copy their delivery IDs/receipts
  into the Go journal; no automatic credential or paid-journal migration occurs.
- Direct consumables now use `/consumable-deliveries/pending` and
  `/consumable-deliveries/{id}/delivered` under the commerce V2 base. Register the
  host's durable, idempotent delivery handler or first discover deliveries with
  `syncPendingConsumables`, durably grant them, then call `confirmDelivery(id)`.
  A confirmation requires a delivery observed in this identity/endpoint scope.
  Persisted host-grant checkpoints allow ACK retry after restart without invoking
  the handler again; the host must still deduplicate a crash before SDK checkpointing.
- Only HTTP 401 `SESSION_EXPIRED` triggers one Configure and one replay. The new
  session is verified and saved atomically before retry. Other auth errors return
  to the caller. Store retry checkpoints honor body and HTTP `Retry-After`.
- Go store journal format, recovery namespace and delivery-before-consume ordering
  remain unchanged. Keep the original identity/endpoint for pending operations.
- This release changes the native Android library. Flutter callers explicitly
  selecting `legacyV1` must migrate their integration to Go V2 separately; updating
  the dependency alone will not change their protocol.

## Upgrading from 2.3 to 2.4

- Update the dependency to `v2.4.0`. Existing Bazaar and Direct clients retain
  their factories, request APIs and payment routes.
- To enable Myket, select `InappifyMarket.MYKET`, supply the app's Myket RSA
  public key and configure the backend app/session for `MyKet`/`11`.
- The SDK bundles the isolated Myket integration and fixed manifest settings;
  do not add a separate Myket billing dependency or manifest placeholders.
- Myket supports consumables and non-consumables. Native subscriptions and
  dynamic-price tokens are rejected. Existing fulfillment/recovery APIs apply.
- Run the [Myket device/backend rollout checks](MYKET.md#validation-and-rollout)
  before enabling it for production customers. Local tests do not certify an
  actual payment or backend integration.

## Upgrading from 2.2 to 2.3

- Update the Android dependency to `v2.3.0`. Existing integrations need no factory,
  credentials, identity, storage or payment-flow changes. Do not clear app data.
- The new `syncAttributesAndOfferingsIfNeeded()` API is an opt-in Kotlin extension;
  import `com.inappify.sdk.syncAttributesAndOfferingsIfNeeded`. It is available on
  both built-in clients without adding an abstract method to the old interface.
  Custom `InappifyClient` implementations remain valid and return an explicit
  unsupported result unless they are a built-in implementation.
- Await attribute setters/Go queue writes before invoking it. Treat admitted-call
  failures as failures, not fresh targeting data. After five calls per rolling
  minute, cached success is possible; missing cache gives `RATE_LIMITED_NO_CACHE`
  in error details. See the [complete contract](API_GUIDE.md#sync-attributes-then-fetch-targeted-offerings).
- Existing `getOfferings`, `refreshOfferings`, standalone `syncAttributes`, cache
  policies, Configure and purchase/recovery routes keep their previous semantics.
  No new HTTP endpoint or protocol migration is required.
- This release changes only the Android library. It does not automatically add a
  Flutter MethodChannel or Dart/iOS API; those adapters require a separate update.

## Upgrading from 2.1 to 2.2

Historical behavior below applies to 2.2–2.4. The 2.5 guidance above replaces its
legacy companion and Direct-fulfillment requirements.

- Keep the existing V1 factory, app/customer identity, credentials, cache and
  inventory ledger. The dependency upgrade does not opt a V1 client into Go.
- Existing `InappifyV2Configuration(apiBaseUrl = ...)` constructors/getters and
  Kotlin default arguments remain available. The separate SDK/commerce endpoint
  overload is additive; see [Go configuration](../V2_MIGRATION.md#configuration).
- An unbound Go client can now start Direct/Bazaar purchases using its Go session
  Bearer. This requires the backend's commerce contract; do not assume a legacy
  app API key is a Go key. Direct consumable fulfillment still needs separately
  configured, scope-matched legacy credentials.
- An explicitly bound legacy companion keeps its existing purchase/recovery
  route. Finish pending purchases before changing routes. Rebind after a customer
  binding change; do not erase SDK storage or the host's inventory ledger to
  bypass a recovery/rebinding error.
- Native Go receipt journals bind to their commerce endpoint. Retaining session
  cache after an endpoint change does not authorize replaying old paid receipts
  against that new endpoint. Recover using the original endpoint and identity.
- Go no longer uses V1 `forceVersion`/`hasForceUpdate`; use verified resource
  results, cache policies and events. Default-client behavior is unchanged.
- Uncertain Go login/logout retains a durable attempt and hides customer access
  until the attempt is recovered. Call `recover()` after connectivity returns;
  do not start a different identity operation to bypass this barrier.
- Go custom identifiers now accept 1–100 Unicode code points subject to control,
  edge-whitespace and reserved-prefix restrictions. Keep existing valid IDs;
  the library upgrade is not a reason to generate new customer identities.

Review [Go commerce limitations](../V2_MIGRATION.md#go-commerce-and-legacy-recovery)
and run the [upgrade acceptance checklist](#7-upgrade-acceptance-checklist),
especially the host-owned exactly-once grant and live backend/device scenarios,
before broad production rollout.

## Opt-in offline startup in 2.1.0

Existing `configure` and resource method behavior is unchanged. Applications
that need immediate cached display can call the additive
`restoreCachedSession(options)` extension before starting their normal
Configure/refresh in the background. A cache miss is not a configured session.
See [the offline cache guide](OFFLINE_CACHE.md) for identity checks, logout
barriers, targeting invalidation and stale-entitlement limits. No switch to Go,
new API key, data reset, or purchase-journal migration is required.

## 1. Keep the existing V1 integration

### Repository history cleanup

With the repository owner's approval, the V1 source history/tag was rewritten
to remove only the integration application and its dedicated guide/configuration
example. No remaining V1 source or build file was modified. Existing V1 release
AAR and sources-JAR assets were not replaced. This source-history cleanup is
separate from the V2 SDK changes below.

The published V1 tag now identifies cleaned source history. Existing clones and
commit-pinned source integrations need explicit reconciliation; do not merge an
old branch into the cleaned history or bulk-push old refs. Downloaded/cached
artifacts are not retroactively erased by a Git rewrite.

Under the sample-only restriction, V1 retains its original references to the
removed application in settings, CI and documentation. Application-specific
build commands are therefore no longer usable from that cleaned V1 checkout.
V2's public build and CI target only the SDK.

### Application upgrade

Keep `InappifyClient.create(applicationContext)`, the same app API key,
application ID/package, app signing identity, customer identifiers and
application inventory ledger. Update the dependency once the V2 release is
available; do not uninstall the app, clear SDK storage or generate a new user ID.

The original public API signatures, constructor overloads, Kotlin default-mask
entry points and V1 `DONE`/`NEEDTOPAY` enum remain available. V1 session storage
names and migration paths are retained. Go state uses separate storage.

For an existing Bazaar session missing new route metadata, Configure obtains
fresh metadata using the same cached customer and recovery binding. A response
that unexpectedly changes that identity is rejected. A different API key or
explicit customer is not allowed to inherit another customer's pending work.

A V1 persistence failure may have already applied memory state:
inspect `stateApplied`, `storageStage` and `outcomeMayHaveCommitted`.
Do not erase the user's data or start a replacement purchase as a workaround.

## 2. Understand routing before adding product types

| Request/client | Route |
| --- | --- |
| Default V1 client, old constructor without productType, market=NONE | Keeps Direct selection; does not unexpectedly open Bazaar. |
| Default V1 client, old constructor, market=BAZAAR | Keeps Bazaar selection; uses Store V2 when the server identifies Bazaar. |
| Explicit productType constructor | Uses the supported server route; request market is fallback when route metadata is absent. |
| Go V2 client | Uses the verified session route; no legacy purchase companion in 2.5. |
| Recognized unsupported server store | Fails explicitly; never silently switches store. |

Unknown numeric platform values follow the legacy fallback; enum recognition
does not imply store implementation. The published 2.3.0 release supports only Direct Android and Bazaar payments.

An unchanged Direct request remains valid:

```kotlin
val result = client.purchase(
    InappifyPurchaseRequest(
        productIdentifier = selectedProductId,
        offeringIdentifier = selectedOfferingId,
        market = InappifyMarket.NONE,
    ),
)
```

Configure with `market=NONE` does not require a Bazaar RSA key. Without that
key, automatic Bazaar reconciliation is skipped. Configuring explicitly for
Bazaar still requires the RSA key, and actual Store V2 purchases fail before
billing UI if the configured key is missing.

Request-level API key, country, app version and market-key overrides apply only
to legacy V1 routes. Store V2 uses the configured session so a durable operation
can resume with the same app/customer binding.

## 3. Use explicit types for new store integrations

Match the actual store catalog: `SUBSCRIPTION`, `NON_CONSUMABLE` or
`CONSUMABLE`. Never infer type or quantity from a SKU name.

```kotlin
val result = client.purchase(
    activity,
    InappifyPurchaseRequest(
        productIdentifier = selectedProductId,
        offeringIdentifier = selectedOfferingId,
        packageIdentifier = selectedPackageId,
        market = InappifyMarket.BAZAAR,
        productType = InappifyProductType.CONSUMABLE,
        idempotencyKey = savedAttemptId,
    ),
)
```

`savedAttemptId` is a host-created unique ID for one logical purchase, persisted
before dispatch. Reuse it for that attempt's retry, not for a different purchase.

The old constructor has no product type and uses legacy IN_APP billing. It is
not silently converted into a non-consumable. On Store V2, authoritative
`DELIVERY_REQUIRED` with a delivery identifier classifies it as consumable.
Subscriptions should always use the explicit type so subscription billing is used.

## 4. Do not grant inventory for every Success

A successful SDK operation is not synonymous with completed fulfillment:

| Result | Host action |
| --- | --- |
| V1 NEEDTOPAY | Open only an allowed HTTPS checkout URL; refresh/recover after return. |
| Store PROCESSING / deferred operation | Let SDK verification/recovery continue; do not start another charge. |
| DELIVERY_REQUIRED | Grant durably and idempotently, then acknowledge through the handler or manual confirmation. |
| CONSUME_REQUIRED | SDK resumes store consume/report; do not grant inventory again. |
| COMPLETED / RESTORED / ALREADY_PROCESSED | Use authoritative customer/entitlement state; no additional consumable grant. |
| Failure with uncertain outcome | Keep the attempt; recover before deciding whether a new purchase is appropriate. |

If old host code awards coins on every `Success`, update that code to the
delivery contract. Binary compatibility cannot make an application inventory
transaction exactly-once by itself.

Preferred flow: register `setConsumableDeliveryHandler` before Configure.
In a host-owned database transaction, use an app/customer/delivery unique key,
record the delivery and apply the mapped inventory change atomically. Return
`DELIVERED` only after commit. If the receipt already exists, return
`DELIVERED` without adding inventory again; otherwise return `RETRY_LATER`
when a durable grant cannot be completed.

For manual fulfillment, perform that same transaction before
`confirmDelivery(deliveryId)`. Retrying an acknowledgement is not permission
to repeat the inventory grant. Do not call mutating SDK operations from inside
the delivery callback.

## 5. Recover across errors, restart and identity changes

- Keep pending attempts after timeouts, lost callbacks or uncertain outcomes.
  Use `syncPurchases()` and `syncPendingConsumables()` as appropriate.
- Call pending-consumable sync after Configure, on foreground and after
  connectivity returns. Startup reconciliation is not a permanent background job.
- A paid checkpoint can resume even if cached offerings were invalidated.
  A new purchase still needs a valid selected product/offering.
- `restorePurchases()` reports restored/already-processed/failed counts;
  aggregate Success can include individual failures.
- Do not restore a consumable as an unconditional new inventory grant.
- Logout retains recoverable receipt evidence, but a different app/customer
  cannot replay it. Scope the host inventory ledger to the same identity.
- `isRetryable` describes SDK error classification, not permission for a new
  purchase attempt. Keep both semantic error details and the returned snapshot.

## 6. Go V2 is a separate opt-in

Only move to `InappifyV2Client.create(context)` when your backend integration is
ready. Official setup requires the Go public SDK key, not issuer/app/project
form inputs. Android metadata and a durable anonymous UUID are SDK-managed;
scope is accepted only from verified responses.

Go validates custom identifiers (1–100 Unicode code points, no controls or edge
whitespace, no reserved anonymous prefix; numeric IDs are accepted). Use your backend's stable account ID, for example
`account_user_123456`, consistently on all devices. Do not use an email,
session token, random ID on every login, or silently hash an existing V1 ID
into a new customer. Legacy invalid identifiers need an explicit migration plan.

Go CustomerInfo is signed, attributes are write-only, fetch policies are
explicit and logout may remain pending offline. A Go Bearer session is not a
Laravel JSON purchase token. Commerce V2 uses the Go Bearer directly for Direct
and native stores, including fulfillment. The old bridge is disabled in 2.5.
Existing V1 operations must be completed with their original V1 client; no paid
journal is automatically moved into another credential scope.

See [Go integration](../V2_MIGRATION.md) for complete setup and limitations.

## 7. Upgrade acceptance checklist

Before broad rollout, test an actual V1-installed app without clearing data:

- Anonymous and logged-in customer remain the same after upgrade/restart.
- Old Direct and Bazaar request code selects the same intended store.
- Explicit types match subscription, non-consumable and consumable catalogs.
- Retry after receipt, verification, inventory commit, acknowledgement and
  consume never produces a second charge or grant.
- Login/logout does not expose another customer's cache or pending inventory.
- Missing RSA key, unavailable Bazaar, offline service and expired Go session
  produce actionable failures without deleting recoverable state.
- Checkout return refreshes authoritative state instead of trusting a deep link.
- Release dependency resolution, R8 host build and planned rollback are tested.

Local ABI/unit evidence and pending device gates are recorded in
[the acceptance matrix](../V2_CONTRACT.md). No migration guide can substitute
for testing the host application's own inventory and identity integration.

Version 2.4.0 adds [Myket in-app integration](MYKET.md) with an explicit
server route and matching RSA key; it does not change the upgrade instructions
for already published versions.
