# inappify_android_plugin

[![Android CI](https://github.com/mohsenghorbanipour/inappify_android_plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/mohsenghorbanipour/inappify_android_plugin/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/mohsenghorbanipour/inappify_android_plugin)](https://github.com/mohsenghorbanipour/inappify_android_plugin/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Native Android SDK for Inappify purchases, customer information, offerings,
entitlements, discount codes, and customer attributes.

Version 1 supports two purchase routes:

- `InappifyMarket.NONE`: direct Inappify purchase without marketplace UI.
- `InappifyMarket.BAZAAR`: Cafe Bazaar billing through Poolakey.

## Requirements

- Android API 21 or newer
- AndroidX
- Kotlin coroutines
- A registered Inappify Android application
- Cafe Bazaar and its RSA public key for Bazaar purchases

The project itself builds with JDK 17, Gradle 8.7, Android Gradle Plugin 8.5.1,
and Android SDK 34. The published library emits Java 8-compatible bytecode.

## Installation

The release is published from GitHub tags through JitPack. Add JitPack after
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
                    "com.github.mohsenghorbanipour.inappify_android_plugin",
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
        "com.github.mohsenghorbanipour.inappify_android_plugin:" +
            "inappify_android_plugin:v1.0.0",
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

Use `InappifyMarket.NONE` and omit `marketKey` when the application does not
use native marketplace billing.

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
        packageIdentifier = "monthly",
        idempotencyKey = "purchase-attempt-42",
        market = InappifyMarket.BAZAAR,
        marketKey = bazaarPublicKey,
    ),
)
```

The SDK verifies the requested cached product, launches Poolakey, validates the
returned product and application package, submits private purchase evidence to
Inappify, then refreshes customer information and offerings. It never exposes
purchase tokens, signatures, or raw receipts through the public API.

Only one marketplace purchase can run per client. User cancellation returns
`PURCHASE_CANCELLED`; timeouts and interrupted outcomes are typed failures.

## Purchase recovery

Reconcile an owned Bazaar purchase when marketplace payment completed but
server verification was interrupted:

```kotlin
when (val recovery = client.syncPurchases()) {
    is InappifyResult.Success -> recovery.data.forEach(::render)
    is InappifyResult.Failure -> handle(recovery.error)
}
```

Recovery accepts locally verified purchases bound to the active encrypted
session and does not reopen marketplace UI.

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

## Persistence and security

Session state is encrypted with AES-GCM. Android 23 and newer use an AES key
stored in Android Keystore. Android 21 and 22 use a random AES key wrapped by an
RSA key stored in Android Keystore. Encrypted data lives in the application's
no-backup directory.

Public snapshots and diagnostic strings redact API keys, access tokens,
customer identifiers, market keys, checkout URLs, identity data, and purchase
evidence. The SDK does not expose a configurable production base URL, reducing
the risk of credentials being redirected to an untrusted server.

### Migration from the Inappify Flutter SDK

On the first restore, the SDK can import Inappify-owned lifecycle values from
`FlutterSharedPreferences`. It removes only the seven Inappify keys after the
encrypted write succeeds and leaves unrelated preferences untouched. A
migrated token without an API-key binding is never sent to the backend;
configuration safely creates a fresh authenticated session.

## Sample application

The `app` module demonstrates configuration, customer and offering refresh,
Bazaar purchase, event handling, and purchase recovery. Local credentials and
signing configuration are documented in [SAMPLE_APP.md](SAMPLE_APP.md) and are
excluded from source control.

## Build and verify

```shell
./gradlew \
  :sdk:testDebugUnitTest \
  :sdk:lintRelease \
  :sdk:assembleRelease \
  :sdk:publishReleasePublicationToMavenLocal \
  :app:assembleDebug
```

Compile the Android Keystore instrumentation suite with:

```shell
./gradlew :sdk:assembleDebugAndroidTest
```

Run `:sdk:connectedDebugAndroidTest` on API 21 or 22, API 23, and a current
Android API before changes to encryption or persistence.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security reports must follow
[SECURITY.md](SECURITY.md).

## License

`inappify_android_plugin` is available under the
[Apache License 2.0](LICENSE).
