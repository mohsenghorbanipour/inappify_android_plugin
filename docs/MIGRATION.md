# Upgrading from library V1 to V2

This guide separates a **library upgrade** from a **Go protocol migration**.
Do not replace the factory merely because the dependency version is 2.x.

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
| Go purchase companion | Uses the bound server route, not legacy request-level route selection. |
| Recognized unsupported server store | Fails explicitly; never silently switches store. |

Unknown numeric platform values follow the legacy fallback; enum recognition
does not imply store implementation. Only Direct Android and Bazaar payments
are implemented.

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

Go validates custom identifiers (16–100 characters, restricted ASCII syntax,
not entirely numeric). Use your backend's stable account ID, for example
`account_user_123456`, consistently on all devices. Do not use an email,
session token, random ID on every login, or silently hash an existing V1 ID
into a new customer. Legacy invalid identifiers need an explicit migration plan.

Go CustomerInfo is signed, attributes are write-only, fetch policies are
explicit and logout may remain pending offline. A Go Bearer session is not a
Laravel purchase token. The purchase companion requires separately configured,
matching credentials and rebinding after identity changes. The SDK does not
invent a credential-exchange endpoint.

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
