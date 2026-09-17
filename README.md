# inappify_android_plugin

[![Android CI](https://github.com/mohsenghorbanipour/inappify_android_plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/mohsenghorbanipour/inappify_android_plugin/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/mohsenghorbanipour/inappify_android_plugin)](https://github.com/mohsenghorbanipour/inappify_android_plugin/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Native Android SDK for Inappify purchases, customer information, offerings,
entitlements, discount codes, and customer attributes.

Version 2.0.0 preserves the original client API and adds explicit store product
types, durable verification/recovery, consumable fulfillment, and an opt-in Go
client. Start with the [V1 upgrade checklist](docs/MIGRATION.md) before changing
an existing integration.

The Go SDK v2 protocol is an **explicit opt-in** through
`InappifyV2Client.create(context)`, followed by `configure(InappifyOptions(apiKey))`.
The SDK bundles official service trust and resolves app/project scope from the
verified response. The explicit-configuration overload remains available. It uses signed CustomerInfo
and bearer sessions, independently of the Laravel store v2 described below.
See [the migration guide](V2_MIGRATION.md) and [acceptance matrix](V2_CONTRACT.md).
The original `InappifyClient.create(context)` continues to use V1.
The Go integration is pre-release: the purchase credential bridge,
the full remote Paywall schema and staging/device acceptance
are still required before production rollout.

## Documentation

- [API and behavior guide](docs/API_GUIDE.md): inputs, HTTP/cache behavior,
  identity, attributes, events, error handling, and fulfillment.
- [V1 → V2 migration hints](docs/MIGRATION.md): what to keep, what to change,
  routing examples, retries, and upgrade acceptance.
- [Go V2 integration](V2_MIGRATION.md): signed sessions, fetch policies,
  write-only attributes, and the separate purchase-credential bridge.
- [Release scope and limitations](V2_CONTRACT.md).
- [Changelog](CHANGELOG.md) and [release verification checklist](docs/RELEASING.md).

This public distribution contains the library and synthetic tests, not an
integration application or production credentials.

Version 2 keeps the complete V1 API while adding a server-authoritative Cafe
Bazaar workflow:

- Numeric `storePlatform` values are normalized using the backend
  `AppStorePlatform` enum; missing and unknown numeric values preserve the exact
  V1 route selected by `InappifyPurchaseRequest.market`.
- `DirectAndroid` or `NONE` continues to use `POST /app/v1/purchase`.
- `Bazar` uses Poolakey and `POST /app/v2/store/purchases`, including durable
  verification, restore, subscription renewal, and consumable delivery.
- MyKet is intentionally unsupported in this release.

## Requirements

- Android API 21 or newer
- AndroidX
- Kotlin coroutines
- A registered Inappify Android application
- Cafe Bazaar and its RSA public key for Bazaar purchases

The project itself builds with JDK 17, Gradle 8.7, Android Gradle Plugin 8.5.1,
and Android SDK 34. The published library emits Java 8-compatible bytecode.

## Installation

Releases are published from GitHub tags through JitPack. Add JitPack after
Google and Maven Central. The repository filter includes both this SDK and its
Poolakey runtime dependency:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content {
                includeGroup(
                    "com.github.mohsenghorbanipour",
                )
                includeGroup("com.github.cafebazaar.Poolakey")
            }
        }
    }
}
```

Add the versioned library module:

```kotlin
dependencies {
    implementation(
        "com.github.mohsenghorbanipour:" +
            "inappify_android_plugin:v2.0.0",
    )
}
```

Release tags are immutable version inputs. Avoid branch snapshots in production.

## Client ownership

Every `InappifyClient.create()` call returns an independent client. The SDK
does not keep mutable global session state. Create one client in your
application or dependency-injection scope and reuse it:

```kotlin
class App : Application() {
    val inappify: InappifyClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        InappifyClient.create(this)
    }
}
```

Call `close()` only when the owning scope is permanently released. Closing a
client cancels its work and releases listeners and network resources; it does
not log the customer out.

Client instances persist to the same application-private session file. File
locking prevents concurrent instances or application processes from corrupting
that file, but applications should still keep one authoritative client to avoid
last-writer-wins session changes.

## Configure

SDK operations are suspending and return typed `InappifyResult` values:

```kotlin
val result = client.configure(
    InappifyOptions(
        apiKey = "YOUR_ANDROID_APP_KEY",
        market = InappifyMarket.BAZAAR,
        marketKey = "YOUR_CAFE_BAZAAR_RSA_PUBLIC_KEY",
    ),
)

when (result) {
    is InappifyResult.Success -> render(result.snapshot)
    is InappifyResult.Failure -> handle(result.error)
}
```

The server-provided `storePlatform` is authoritative for explicitly typed V2
requests, Go purchase companions, and recovery. Named values are accepted
directly and numeric values use the following backend enum mapping:

| Value | Canonical name | Android SDK V2 status |
| ---: | --- | --- |
| 1 | `DirectIos` | Unsupported |
| 2 | `DirectAndroid` | Supported through Direct V1 |
| 3 | `DirectWeb` | Unsupported |
| 5 | `PlayStore` | Unsupported |
| 6 | `AppStore` | Unsupported |
| 10 | `Bazar` | Supported through Bazaar V2 |
| 11 | `MyKet` | Deferred to the next implementation phase |
| 12 | `SibApp` | Unsupported |

On the default client, an unchanged V1 request (without `productType`) retains
its Direct/Bazaar `market` selection. Selecting Bazaar with a server Bazaar
route uses V2 verification; choosing `NONE` does not unexpectedly open Bazaar.
Typed V2 requests use `market` only when server routing is absent. Unsupported
server stores still fail closed. A V1 Direct client can configure with `NONE`
without a Bazaar RSA key; automatic Bazaar reconciliation is skipped until that
key is configured. An actual Bazaar V2 purchase requires the configured RSA key
and fails before checkout if it is missing. Configuring `market=BAZAAR` still
requires the key. Explicit consumables are supported by
`DirectAndroid` and Bazaar V2. A consumable is rejected before checkout only
when it would otherwise enter the legacy Bazaar V1 route, where fulfillment
cannot be completed safely.

## Customer information and offerings

```kotlin
val customer = client.getCustomerInfo()
val cachedCustomer = client.getCustomerInfo(forceRefresh = false)

val offerings = client.getOfferings()
val refreshedOfferings = client.refreshOfferings()
val currentOffering = client.getCurrentOffering(
    placementIdentifier = "main_paywall",
)
```

Customer information is cached for five minutes when
`forceRefresh = false`. Offerings stay session-bound until identity,
targeting context, or server `forceVersion` invalidates them.

Entitlement helpers are available on the client, snapshot, and customer model:

```kotlin
if (client.hasEntitlement("premium")) {
    showPremiumContent()
}
```

## Direct purchase

`NONE` submits the purchase to Inappify without opening marketplace UI:

```kotlin
val result = client.purchase(
    InappifyPurchaseRequest(
        productIdentifier = "premium_monthly",
        offeringIdentifier = "main",
        market = InappifyMarket.NONE,
    ),
)
```

The product must exist in the selected cached offering unless the request is a
lost-purchase recovery. Trial products also bypass marketplace UI.

Direct purchases keep the V1 request body, response parsing, redirect flow,
and public result behavior unchanged. Direct consumables additionally use the
V1 pending/delivered fulfillment endpoints after checkout. Request-level API
key, country, app version, and market-key overrides apply only to this legacy
path. A host that opens `purchase.url` must require HTTPS and an explicit
Inappify payment-host allowlist; a deep-link/browser return is only a sync
trigger and is never proof of payment or delivery.

## Cafe Bazaar purchase

The Activity overload is required for a non-trial Bazaar purchase. The Activity
must implement `ActivityResultRegistryOwner` and `LifecycleOwner`, as
`ComponentActivity`, `FragmentActivity`, and `AppCompatActivity` do:

```kotlin
val result = client.purchase(
    activity = this@MainActivity,
    request = InappifyPurchaseRequest(
        productIdentifier = "premium_monthly",
        offeringIdentifier = "main",
        productType = InappifyProductType.SUBSCRIPTION,
        packageIdentifier = "monthly",
        idempotencyKey = savedAttemptId, // Persist a unique ID per logical purchase.
        market = InappifyMarket.BAZAAR,
    ),
)
```

Use `SUBSCRIPTION`, `NON_CONSUMABLE`, or `CONSUMABLE` to match the Bazaar
catalog. The unchanged V1 constructor uses untyped IN_APP billing. On a Bazaar
V2 route, only a verified `DELIVERY_REQUIRED` response classifies it as consumable;
the host must then grant it idempotently and confirm delivery, or register a
delivery handler. No product type is inferred from a SKU name. New integrations
should always pass the type explicitly, particularly for subscriptions.

The SDK verifies the requested cached product, launches Poolakey, validates the
returned product and application package, stores the evidence in an encrypted
recovery queue, and submits it to Inappify for server-to-server verification.
A successful store callback is not treated as an entitlement. Customer
information and offerings are refreshed only after a terminal server result.
Purchase tokens, signatures, developer payloads, and raw receipts are never
returned through the public API.

V1 status remains available in `purchase.purchaseStatus`. The additive
`purchase.storePurchaseStatus` reports the V2 state without changing the old
`DONE`/`NEEDTOPAY` enum. The SDK owns polling and persists
`verificationRequestId`; applications must not poll it themselves.

Only one marketplace purchase can run per client. User cancellation returns
`PURCHASE_CANCELLED`; timeouts and interrupted outcomes are typed failures.

## Consumable delivery

For every consumable payment, Inappify creates an independent `deliveryId`.
Never use an entitlement to infer consumable quantity and never parse quantity
from the product name. Map `productIdentifier` to a host-owned inventory effect
and commit both the grant and the unique `deliveryId` receipt in one atomic
transaction.

Register the handler before `configure`. Returning `DELIVERED` is safe only
after that durable transaction has committed; returning `RETRY_LATER` leaves
the delivery pending:

```kotlin
val deliveryRegistration = client.setConsumableDeliveryHandler { delivery ->
    val committed = inventoryDatabase.inTransaction {
        // This host-owned key must include both app and customer identity.
        if (insertDeliveryReceiptIfAbsent(currentAppCustomerKey, delivery.deliveryId)) {
            grantMappedProduct(delivery.productIdentifier)
        }
        true
    }

    if (committed) {
        InappifyDeliveryResult.DELIVERED
    } else {
        InappifyDeliveryResult.RETRY_LATER
    }
}

client.configure(options)

// Also call at process start/resume and after connectivity returns.
val sync = client.syncPendingConsumables()
```

The SDK serializes overlapping syncs and drains pending responses in batches of
up to 100. For Direct, it acknowledges through the V1 `delivered` endpoint and
never calls a marketplace consume endpoint. For Bazaar, it acknowledges the
host grant, consumes the exact receipt only after Inappify returns
`CONSUME_REQUIRED`, and reports the consume result. `confirmDelivery` remains
available for hosts that intentionally implement the same sequence manually.

Transient timeouts and 429/500/503 responses keep the same delivery pending and
use bounded retries; 422 contract errors are not retried automatically. A
failed acknowledgement must never cause the host grant to run twice—the host's
unique `deliveryId` receipt is the authority. Close `deliveryRegistration` when
its owning application component is permanently disposed.

Keep the host ledger scoped to the same App/Customer identity as the configured
SDK session. Configure/login/logout and fulfillment are serialized, so an
identity cannot change during a callback; after login, sync the new customer
instead of replaying the previous customer's local queue.

Subscriptions and non-consumables never enter this handler. Their state remains
entitlement/ownership based: subscriptions are time-bounded, while a
non-consumable entitlement has no expiry.

## Restore and startup reconciliation

Restore owned Bazaar subscriptions and non-consumables without reopening the
marketplace UI:

```kotlin
when (val restore = client.restorePurchases()) {
    is InappifyResult.Success -> {
        render(restore.snapshot)
        log("restored=${restore.data.restoredCount}")
        log("alreadyProcessed=${restore.data.alreadyProcessedCount}")
        log("failed=${restore.data.failedCount}")
    }
    is InappifyResult.Failure -> handle(restore.error)
}
```

Restore reconciles subscriptions and non-consumables independently, so a
successful result can still report individual failures through `failedCount`.
Consumables are not restored as new inventory grants. Recovery can resume a
durable checkpoint or discover an older untyped owned IN_APP receipt and ask the
server whether delivery is required. `syncPurchases()` retains its API signature;
its behavior follows the configured protocol and can advance pending operations.

After a successful `configure` or `login`, the SDK automatically resumes
matching consumable operations when a handler is registered. Bazaar V2 also
reconciles currently owned subscriptions and non-consumables. Applications
must call `syncPendingConsumables()` on process start/resume and after
connectivity returns; concurrent calls are serialized. Active Bazaar
subscription tokens may be submitted again on later startups; Inappify remains
authoritative for renewal and `validUntil`.

## Attributes and discount codes

The SDK supports custom attributes, reserved attributes, full synchronization,
and discount-code validation:

```kotlin
client.updateAttributes(
    mapOf(
        "plan_source" to "campaign",
        "obsolete_key" to null,
    ),
)
client.setEmail("customer@example.com")

val discount = client.validateDiscountCode(
    InappifyDiscountCodeRequest(discountCode = "SUMMER"),
)
```

Invalid custom attributes are ignored. Null or blank custom values remove their
key. Reserved attributes use their documented validation rules.

## State events

```kotlin
val registration = client.addEventListener { event ->
    when (event.type) {
        InappifyEventType.CUSTOMER_INFO_CHANGED -> render(event.snapshot)
        InappifyEventType.OFFERINGS_CHANGED -> updatePaywall(event.snapshot)
        else -> Unit
    }
}

// Release the observer with its owning component.
registration.close()
```

Events contain immutable, token-free snapshots and are delivered in order for
each client.

## HTTP troubleshooting

Temporary HTTP tracing is opt-in and disabled until a listener is registered:

```kotlin
val traceRegistration = client.addHttpTraceListener { trace ->
    log("${trace.method} ${trace.endpoint}")
    log("request=${trace.requestBody}")
    log("status=${trace.statusCode} duration=${trace.durationMillis}ms")
    log("response=${trace.responseBody}")
}

traceRegistration.close()
```

Traces include the method, endpoint, safe content headers, response status,
request ID, duration, failure category, and bounded request/response JSON. The
SDK structurally redacts credentials, identities, checkout URLs, dynamic store
IDs, attributes, receipts, signatures, and purchase evidence before invoking
application code. Non-JSON bodies are omitted. Do not forward even redacted
traces to third-party analytics, and remove troubleshooting listeners after
the test session.

## Persistence and security

Session state is encrypted with AES-GCM. Android 23 and newer use an AES key
stored in Android Keystore. Android 21 and 22 use a random AES key wrapped by an
RSA key stored in Android Keystore. Encrypted data lives in the application's
no-backup directory.

Pending store operations use a separate encrypted, atomic queue in the same
no-backup area. Every record is bound to the API key, customer, Android package,
and Inappify app ID that created it. Logout does not destroy recoverable
evidence, but a different identity cannot replay it. Terminal and permanent
rejection states remove their receipt checkpoints. Permanent rejections retain
only bounded, one-way fingerprints so unchanged evidence is not submitted again
until the relevant app version or server configuration changes.

Public snapshots contain no session token but do expose customer IDs and business
data through their properties. Their diagnostic string representations redact
sensitive values; this does not anonymize direct property access. Purchase URL
properties can contain checkout state and must not be logged. Server diagnostics
and standard HTTP traces are filtered before reaching application code. The V1
factory uses fixed service endpoints; the explicit Go configuration overload
accepts a validated HTTPS origin and explicit signing/host trust policies.

### Migration from the Inappify Flutter SDK

On the first restore, the SDK can import Inappify-owned lifecycle values from
`FlutterSharedPreferences`. It removes only the seven Inappify keys after the
encrypted write succeeds and leaves unrelated preferences untouched. A
migrated token without an API-key binding is never sent to the backend;
configuration safely creates a fresh authenticated session.

## Build and verify

```shell
bash scripts/check-publication.sh
./gradlew \
  :sdk:testDebugUnitTest \
  :sdk:lintRelease \
  :sdk:assembleRelease \
  :sdk:publishReleasePublicationToMavenLocal
```

Compile the Android Keystore instrumentation suite with:

```shell
./gradlew :sdk:assembleDebugAndroidTest
```

Run `:sdk:connectedDebugAndroidTest` only on an isolated test installation on
API 21 or 22, API 23, and a current Android API. Do not run it against an app
with real pending receipts. Building instrumentation does not execute it.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security reports must follow
[SECURITY.md](SECURITY.md).

## License

`inappify_android_plugin` is available under the
[Apache License 2.0](LICENSE).
