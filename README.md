# inappify_android_plugin

Native Android SDK **3.0.0** for Inappify sessions, purchases, offerings,
entitlements, attributes and consumable fulfillment. All production routes use
`/app/v2/`. Both `InappifyClient.create(context)` and
`InappifyV2Client.create(context)` create the same V2 client.

This is a major migration: the V1 client, transport, endpoints and purchase
companion API have been removed. Existing V1 payment operations must finish in
an installation using their original client and ledger before migration; version
3 never relabels receipts or deletes old state. Read [migration](docs/MIGRATION.md).

## Installation

Use Google, Maven Central and JitPack. Include both SDK and Poolakey groups in
any JitPack repository filter:

```kotlin
maven("https://jitpack.io") {
    content {
        includeGroup("com.github.mohsenghorbanipour")
        includeGroup("com.github.cafebazaar.Poolakey")
    }
}
```

```kotlin
implementation("com.github.mohsenghorbanipour:inappify_android_plugin:3.0.0")
```

Versions and new tags are exactly `major.minor.patch`, without prefixes or
suffixes. Android API 21+, AndroidX and JDK 17 for building this repository are
required. The library emits Java 8 bytecode and bundles the isolated Myket
adapter alongside Poolakey; the app adds no Myket dependency or placeholder.

## Configure and ownership

Create one authoritative application-scoped client. `close()` releases resources
without logging out. Session persistence is encrypted and app-private.

```kotlin
val client = InappifyClient.create(applicationContext)
val configured = client.configure(InappifyOptions(
    apiKey = "YOUR_PUBLIC_INAPPIFY_APP_KEY",
    market = InappifyMarket.BAZAAR,
    marketKey = "YOUR_BAZAAR_RSA_PUBLIC_KEY",
))
```

The public app key is only used to bootstrap or renew Configure. Subsequent
resource and commerce requests authenticate with the current opaque session
Bearer. API keys, customer tokens and app context are not sent in commerce JSON.
`purchase.token` is a store receipt token and remains part of purchase evidence.
Signing trust, scope and customer identity are verified before session publication.

## Routes and supported stores

SDK sessions/resources use `https://service.inappify.com/app/v2/`.
Commerce uses `https://api.inappify.com/app/v2/`. Custom origins must have the same
V2 path. The transport rejects requests outside that path or to another origin.

| Server storePlatform | Supported Android route |
| --- | --- |
| 2 / DirectAndroid | V2 checkout and Direct deliveries |
| 10 / Bazar | Poolakey, V2 verification and fulfillment |
| 11 / MyKet | Myket in-app consumable/non-consumable, V2 verification and fulfillment |

The verified server session selects the route. Missing, unsupported or
conflicting store selection fails explicitly. Myket subscriptions and dynamic
price tokens are unsupported. See [Myket setup](docs/MYKET.md).

## Targeting and attributes

V2 attributes are durable write-only updates. Queue attributes, then synchronize
before resolving a paywall:

```kotlin
client.queueAttributes(mapOf("family" to "gold"))
val result = client.syncAttributesAndOfferingsIfNeeded()
```

The operation flushes attributes before fetching Offerings under the same lock.
It admits five calls per rolling minute; excess calls return current valid cache
or a retryable rate-limit failure if no usable cache exists. SDK version sent for
targeting is exactly `3.0.0`. `currentOffering` and `placements` are server
selections; V2 does not evaluate legacy rules locally. Flutter preserves these
fields in MethodChannel and Dart cache serialization.

## Purchase and fulfillment

```kotlin
val purchase = client.purchase(activity, InappifyPurchaseRequest(
    productIdentifier = "coins_100",
    offeringIdentifier = "main",
    productType = InappifyProductType.CONSUMABLE,
    idempotencyKey = savedAttemptId,
))
```

Use the current foreground AndroidX lifecycle/activity-result owner for store
UI. Direct checkout URLs must pass the SDK payment allowlist. Checkout return
alone does not prove payment; refresh/recover server state.

For consumables, `DELIVERY_REQUIRED` is pending host delivery. Commit an
idempotent inventory grant in a durable host ledger, then acknowledge delivery.
The SDK subsequently consumes through the selected store and reports its result.
Use `syncPendingConsumables`, `confirmDelivery`, `restorePurchases` and `recover`
on startup, foreground and connectivity restoration. Preserve journal and
identity bindings across process recreation. Never grant from an unverified
receipt or a UI callback alone.

Only `401 / SESSION_EXPIRED` triggers verified Configure and one replay. Other
session failures do not automatically Configure. Ambiguous checkout timeouts are
not replayed automatically. Diagnostics are filtered by default; release assets
exclude the raw Debug entry point.

## Documentation and verification

- [API guide](docs/API_GUIDE.md)
- [3.0 migration](docs/MIGRATION.md)
- [V2 trust and sessions](V2_MIGRATION.md)
- [Contract and acceptance](V2_CONTRACT.md)
- [Offline cache](docs/OFFLINE_CACHE.md)
- [Release checks](docs/RELEASING.md), [changelog](CHANGELOG.md)

The public distribution contains SDK source and synthetic tests. It excludes the
private integration app and credentials. Automated builds do not certify a live
marketplace/backend payment; registered, signed device acceptance remains needed.
