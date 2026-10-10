# Myket integration (3.4.0)

Version 3.4.0 supports Myket alongside Direct/Bazaar through the V2-only client.
Use the `3.4.0` dependency in the
[installation guide](../README.md#installation).

## Application setup

The SDK includes the Myket billing client, `ir.mservices.market.BILLING`
permission, package visibility, receiver and private proxy Activity. Do not add
`com.github.myketstore:myket-billing-client` or Myket manifest placeholders for
this integration. No `onActivityResult` forwarding is needed.

Use the RSA **public key for this application in Myket**, not the Bazaar key or
server verification credentials:

```kotlin
val client = InappifyClient.create(applicationContext)
// InappifyV2Client.create(applicationContext) creates the same V2 client.
val configured = client.configure(
    InappifyOptions(
        apiKey = "YOUR_INAPPIFY_APP_KEY",
        market = InappifyMarket.MYKET,
        marketKey = "YOUR_MYKET_RSA_PUBLIC_KEY",
    ),
)
```

The Inappify backend must return `storePlatform=11` (`MyKet`) for this app/session
and support Myket verification, delivery and consume reporting on its existing
Store V2 endpoints. The SDK does not configure backend credentials. Commerce and
fulfillment use the current Go session Bearer. No Myket-specific wire field has been invented: the
backend selects the store from the authenticated app/session.

Explicitly selecting Bazaar with a Myket route, or Myket with a Bazaar route,
is rejected. A missing server route cannot fall back to legacy Myket billing.
Go can also use the public RSA key returned in its verified session `storeInfo`
when `marketKey` is omitted; supplying the application key explicitly is the
recommended integration. Use one authoritative client per application scope.

## Purchase and delivery

The host Activity must implement `ActivityResultRegistryOwner` and
`LifecycleOwner` (for example `ComponentActivity` or `FragmentActivity`), matching
the existing native purchase API. Configure successfully and load the selected
offering before purchase:

```kotlin
val result = client.purchase(
    activity = this@MainActivity,
    request = InappifyPurchaseRequest(
        productIdentifier = "coins_100",
        offeringIdentifier = "main",
        productType = InappifyProductType.CONSUMABLE,
        idempotencyKey = savedAttemptId,
        market = InappifyMarket.MYKET,
    ),
)
```

Use `CONSUMABLE` or `NON_CONSUMABLE`. Native Myket `SUBSCRIPTION` and Bazaar's
`dynamicPriceToken` are rejected before opening Billing. An untyped existing
purchase uses `IN_APP` with the legacy delivery semantics; explicit product
types are recommended for new integrations.

The existing fulfillment API and durable host ledger remain required for
consumables. Report delivery only after granting the content durably and
idempotently; then the coordinator acknowledges delivery, consumes in Myket,
and reports the consume result. `InappifyPurchase.market` is `MYKET`, and
consumable deliveries report `InappifyDeliverySource.MYKET`. See the
[fulfillment contract](API_GUIDE.md#purchase-routes-results-and-fulfillment).

`syncPurchases()`, `restorePurchases()` and Go `recover()` use the selected
store's adapter. Myket queries only owned `IN_APP` products. Signed receipts
must match the app, product and purchase payload. Invalid records are rejected;
partial reconciliation can still process valid records. Stored operations are
bound to the store in addition to the existing account/app/endpoint checks.
Switching stores cannot replay or consume the other store's operation.

Timeout, Activity destruction or cancellation can occur after payment. Preserve
the logical attempt and use reconciliation; do not grant content from the
market callback alone or automatically create a new purchase after an uncertain
result. The existing recovery APIs and error details remain applicable.

## Coexistence with Bazaar

Poolakey remains the Bazaar implementation. Myket Billing Client 1.6 is adapted
inside `com.inappify.sdk.internal.billing.myket`; its Binder Java classes have a
separate package while preserving the marketplace's wire descriptor and
transaction numbers. This avoids the duplicate `com.android.vending.billing`
classes present when the original two artifacts are combined directly.

Both marketplace manifest declarations are merged into the host. The adapter
for the selected session performs the operation; the SDK does not try both
stores. Myket helpers are serialized per process because the fallback broadcast
protocol uses application-wide callbacks. Broadcast replies use a fresh helper
nonce; local receipt signature checks and backend verification remain required.
Internal Myket logging is disabled to avoid exposing purchase evidence.
The required exported receiver produces one documented lint warning; it has no
documented sender permission, so callback nonce and receipt checks remain essential.

## Validation and rollout

607 local JVM tests passed, including coverage for routing, signed receipt validation, store-bound recovery,
fulfillment, replay rejection and cancellation. SDK and consumer build evidence
is recorded in the [acceptance matrix](../V2_CONTRACT.md).

Real Myket purchases have not been executed. Before rollout, use a registered,
correctly signed application with the matching Myket and Inappify configuration
to exercise purchase success/cancel, missing store/login, both installed stores,
rotation/background/process death, restore, delivery retries and consumption.
Verify both the service and broadcast fallback paths with supported Myket
versions, including the echoed broadcast nonce. Run the same backend acceptance
for V2 commerce; compilation does not prove server support.

Source provenance and local adaptations are in
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). Official references:
[Myket Java integration](https://myket.ir/kb/pages/java/) and
[Billing Client 1.6 source](https://github.com/myketstore/myket-billing-client/tree/1.6).
