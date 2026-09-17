# Opt-in Go SDK v2

This implementation follows `InAppify-SDK-V2.pdf` document 1.1. Go v2 session
APIs and Laravel store v2 are different protocols. Existing V1 applications
continue to use `InappifyClient.create(context)` without changes.

## V1 library-upgrade compatibility (2026-09-16)

- An existing same-key Bazaar session lacking store routing is reconfigured with
  its saved public customer ID (including anonymous IDs) and retains its purchase
  recovery binding. A changed response identity is rejected. Explicit customer
  changes or API-key changes never inherit another customer's binding.
- Untyped V1 requests on the default client keep their requested Direct/Bazaar
  market; typed V2 requests and Go companions retain server-authoritative routing.
  Unsupported stores remain unsupported. No new host flag is required.
  A V1 `NONE` client can configure without Bazaar credentials; the RSA key is
  checked before a Store V2 purchase, and automatic Bazaar recovery is skipped
  while it is missing. Explicit `BAZAAR` configuration still requires that key.
- An untyped Bazaar V2 receipt is journaled as `LEGACY_IN_APP`, not an explicitly
  non-consumable product. A verified delivery response can classify it as
  consumable. The same attempt resumes its receipt after that classification,
  process recreation, or catalog invalidation; it does not open checkout again.
- Delivery still requires a durable, idempotent host grant and `confirmDelivery`
  or a registered delivery handler. `DELIVERY_REQUIRED` is not `DONE`. Upgrading
  the library cannot supply an application's inventory logic. Explicit
  non-consumables and subscriptions never enter the consumable delivery flow.

Go protocol adoption is a separate, explicit migration: its identity, attribute,
trust and credential-bridge requirements below are not V1 drop-in semantics.

## Configuration

The official service root is `https://service.inappify.com/app`; Go endpoints use
its `/v2/` prefix. `InappifyV2Configuration.DEFAULT_API_BASE_URL` therefore defaults
to `https://service.inappify.com/app/v2/`. V1 remains on `/app/v1/`, and Laravel
store-v2 routing is unchanged. For the official service, the host supplies only
its public SDK API key:

```kotlin
val sdk = InappifyV2Client.create(applicationContext)
val result = sdk.configure(InappifyOptions(apiKey = publicSdkApiKey))
```

The SDK generates and persists a UUID-v4 anonymous identity before the first
request, reads Android package/version metadata, and authenticates the response
with its bundled service signing key. It learns `issuer`, `appId` and `projectId`
only from verified JWS claims, checks the response's `appId` against those claims,
and binds subsequent responses to that same scope. None are Configure body fields.
`sdk.verifiedSessionScope` exposes the accepted scope read-only after persistence;
it is null before Configure and during pending logout.

The existing overload below remains supported for controlled custom environments
or applications requiring a predeclared issuer/app/project scope:

```kotlin
val sdk = InappifyV2Client.create(
    applicationContext,
    InappifyV2Configuration(
        // apiBaseUrl is optional; override only for an explicit HTTPS /app/v2/ test environment.
        issuer = backendConfiguration.issuer,
        appId = backendConfiguration.appId,
        projectId = backendConfiguration.projectId,
        pinnedSigningKeys = backendConfiguration.signingKeys, // kid -> raw Ed25519 base64url
        paymentHosts = backendConfiguration.paymentHosts, // exact lowercase hosts
        assetHosts = backendConfiguration.assetHosts,
    ),
)
val result = sdk.configure(InappifyOptions(apiKey = publicSdkApiKey))
```

Retain one client for the application scope. Version name/code and package ID
come from Android metadata. `InappifyOptions.appVersion` overrides version name.
All identity/configuration responses are verified before persistence or publication.
Only the signed `customer_info` is accepted; a mismatching unsigned twin rejects the response.

Custom user IDs must follow the new server contract (16-100 characters, permitted
ASCII characters, non-numeric). Existing V1 identity rules remain unchanged.
Do not silently transform an existing customer ID; arrange aliases/migration with
the backend if it does not meet V2 validation. Anonymous UUID-v4 IDs are generated
and stored before the first configure call, so a timeout retains the same identity.

### Public identity versus internal subject

`verification.jws`'s `sub` identifies the backend customer and need not equal the
public `appUserId`/`appUserIdentifier`. The signed `customer_info.originalAppUserId`
must still match the public identity requested by the host. A valid first session
binds its signed internal subject in encrypted storage; CustomerInfo refresh and
same-identity Configure/renewal must preserve it. Never replace the public UUID
with an internal numeric customer ID or remove customer-scope checks entirely.

Verified Login/Logout or explicit Configure to another public identity can bind a
new internal customer. Login merge may also change the internal subject for the
same public alias: old private cache/attribute queues are then cleared and the
legacy companion is unbound. Failed verification or save does not publish the new
binding. Old Go caches derive a missing binding from the verified cached session,
and cached CustomerInfo must agree with that subject. A failed old Configure's
saved anonymous UUID is reused; no app-data reset is required for this fix.

Signature mismatch diagnostics now include `mismatchField`, `validationRule`,
`expectedSource`, types/lengths and `signatureVerified`. App/project/version numeric
comparisons may show values; raw identities, tokens and arbitrary signed strings
are never included. Rejected successful HTTP responses include operation/status
context. Lifecycle `outcomeMayHaveCommitted=true` is not a local rollback guarantee.

## Key trust and cache

The official factory bundles key `sdk-v2-2026-09-09`, obtained over verified HTTPS
from the operator-confirmed `https://service.inappify.com/app/v2/public-keys` on
2026-09-12. No customer credential was sent. This is a public Ed25519 key, not an
API key or private signing key. Publisher review of this trust anchor is still a
release responsibility; runtime network responses do not establish new trust.
The explicit factory uses the supplied pins and expected scope as before.
Unknown `kid` triggers one public-keys refresh per verification;
only key material matching a configured pin may acquire a new alias. For rotation
to genuinely new key material, distribute overlapping old/new pins through trusted
application configuration. A network key list cannot remove a pinned key.

First-session issuer discovery relies on the bundled key being dedicated to the
official service. The SDK validates a nonempty issuer and positive app/project
claims after signature verification, then enforces exact scope equality for
refresh, renewal, login and cached state. An application that needs an independently
predeclared issuer must use the explicit configuration overload.

The official payment allowlist contains only `pay.inappify.com`; no wildcard is
accepted. `sdk.isPaymentUrlAllowed(url)` lets hosts reuse this policy. Other payment
hosts require an explicit configuration. Asset rendering policy is unchanged.

Go state uses its own Android Keystore aliases and encrypted no-backup file.
It never migrates, deletes or overwrites the V1 session. Verified cached customer
information is reverified on load and can be used offline after the short-lived
JWS envelope expires; entitlement expiration still applies. Use client entitlement
helpers, which also reject malformed non-empty expiration dates.

`customerInfo(policy)` and `offerings(policy)` support CACHE_ONLY, CACHE_FIRST,
NETWORK_FIRST and NETWORK_ONLY. CACHE_FIRST publishes cache and schedules refresh.
The client coalesces concurrent refreshes, and one caller cancelling does not
cancel another caller's shared request. `stateUpdates()` emits token-free snapshots.
Event listeners run on the Android main dispatcher. HTTP trace callbacks run in
the diagnostic executor and always redact Go bearer/session/JWS values.

## Identity, attributes and lifecycle

Login uses the Go session bearer, not the API key carried by the legacy request
DTO. The API-key field in `InappifyLoginRequest` remains for source compatibility.
A failed merge/verification/storage write keeps the previous identity.
An offline logout enters `isLogoutPending`, hides the old customer's data and
disables account operations until the server logout succeeds.

```kotlin
sdk.queueAttributes(mapOf("campaign_source" to "summer", "old_key" to null))
sdk.flushAttributes()
val offering = sdk.getCurrentOffering(placementIdentifier = "onboarding")
```

The queue coalesces by key, writes at most 50 items per request and accepts 500
Unicode code points per value. Null/empty values delete. `$ip` is not supported
by Go v2. Attributes are write-only: legacy list-returning methods return an
empty list after success, and `syncAttributes()` flushes pending values instead
of reading customer attributes. Rejected 422 batches are quarantined intact;
network failures retain the queue. Queue contents never move to a new identity.

Call `sdk.recover()` from a lifecycle-aware coroutine on startup/resume,
connectivity restoration and return from a payment URL. Configure/login schedule
recovery automatically. Recovery also completes a pending logout. Register a
delivery handler before binding purchases. Host fulfillment must atomically
record App/Customer/deliveryId and grant inventory exactly once; callbacks must
not re-enter SDK mutations while the fulfillment operation owns the state lock.

## Purchase compatibility until the official bridge exists

Pages 29 and 50 explicitly state that Go SESSION_TOKEN is not Laravel CUSTOMER_TOKEN.
No bridge route is defined in the supplied contract. A fresh Go session therefore
cannot automatically purchase. Missing credentials fail with category CONFIGURATION
and serverCode LARAVEL_CREDENTIAL_REQUIRED before opening Billing UI.

An application that already has a valid, securely held V1 customer token can retain
its original native client and explicitly bind it:

```kotlin
// Both clients must already represent exactly the same app/customer/store.
sdk.bindLegacyPurchaseClient(existingV1Client)
val purchase = sdk.purchase(activity, purchaseRequest)
```

Binding captures an independent purchase companion from the native V1 client.
Subsequent V1 login/logout cannot change its customer scope; its session writes
cannot overwrite V1 state. Keep the original client's transport alive while bound.
Go login/logout unbinds it; bind again only after both identities match. No credentials
appear in public snapshots. Do not obtain a legacy token through an invented/private
endpoint. New installations require the official backend bridge before enabling purchases.

The companion reuses the existing native Bazaar PROCESSING/poll/delivery/consume
queue and Direct pending-delivery recovery. Only signed Go refresh can update the
Go client's entitlements afterward. A payment URL or purchase callback cannot grant
Go access. Failed signed refresh leaves the last verified customer state; recover
on lifecycle/connectivity to refresh again. Myket remains outside this phase.

Direct purchase attribution is additive:

```kotlin
val attributed = purchaseRequest.withPaywallAttribution(paywallId, paywallRevision)
```

The two fields are sent only to Laravel V1 purchase; old constructors omit them.
On revision error 110, refresh offerings and require a fresh user selection.

## Paywall fallback and schema boundary

The guide specifies the envelope and asset rules but does not define the complete
element/action/localization grammar. This SDK advertises schema/renderer 1 and
provides an explicit native package fallback. It does **not** claim full schema-2
remote rendering. Obtain the authoritative JSON schema and representative documents
before enabling that renderer or advertising version 2.

```kotlin
val view = InappifyPaywall.createPackageView(
    context = activity,
    offering = offering,
    typography = InappifyPaywallTypography(
        regular = R.font.app_regular,
        medium = R.font.app_medium,
        bold = R.font.app_bold,
    ),
    locale = Locale("fa"),
    darkMode = true,
    onPurchase = { packageFromThisOffering -> /* start existing purchase workflow */ },
)
```

All text uses bundled fonts or system fallback; regular font loading is checked.
Package actions resolve against the supplied Offering. The standalone compatibility
policy bounds version/depth/count/text and sanitizes font assets, exact HTTPS asset
hosts and semantic icons (`ListCheck.svg` becomes `listcheck`, raw SVG/data URLs are
rejected). It does not download untrusted document assets or execute arbitrary actions.

## Validation and release gates

Automated suites cover JWS tampering/scope/expiry/key rotation, transport error
envelopes and limits, session recovery/concurrency/cancellation, secure-commit
failures, identity/cache isolation, offline pending logout, attribute batches,
targeting/discounts, purchase credential preconditions, and V1 regressions.
Instrumentation covers real Keystore file isolation and native package-view layout;
building the tests is not equivalent to running them on a device.

Full staging/device verification, publisher trust-anchor review, credential bridge,
remote Paywall schema/rendering, and operator canary/rollback acceptance remain
release gates. `setNetworkEnabled(false)` is an explicit host-controlled kill switch;
it keeps verified cache and prevents subsequent V2 requests. There is no automatic
V2-to-V1 authentication downgrade.

Use the [API behavior guide](docs/API_GUIDE.md) to build a host integration and
the [upgrade checklist](docs/MIGRATION.md) to test existing customers. Keep test
credentials and integration applications outside the public repository. A
successful key-only Go session does not invent the missing credential bridge;
neither a build nor a successful legacy Configure proves Go-contract acceptance.

For manual AAR integration, include the published transitive dependencies, including
`org.bouncycastle:bcprov-jdk15to18:1.85.2`. Maven/JitPack consumers receive them through
the generated POM. The verifier uses the lightweight API without changing Android's
global crypto providers.
