# Offline startup and background refresh — 2.1.0

This additive feature is for the existing `InappifyClient` (Laravel V1 session
and Store V2 payments). It does not change the protocol or the meaning of the
existing `configure` method. Go retains its separate signed-cache/fetch policies.

## Local bootstrap

```kotlin
import com.inappify.sdk.restoreCachedSession

// Use the same key, market settings and exact public customer identifier.
val cached = client.restoreCachedSession(options)
if (cached is InappifyResult.Success && cached.data) {
    // Local disk/memory work has completed; no HTTP request was needed.
    renderCachedState(cached.snapshot)
}
```

`Success(true)` means a validated matching session was published to `snapshot`
and normal state events. `Success(false)` is a cache miss, not an authentication
success. Failure describes invalid configuration or an unavailable operation.
The extension does not introduce a new abstract method on the original public
interface; third-party clients that do not implement restoration return an
unsupported result rather than receiving an unsafe generic fallback.

Restoration performs no HTTP, billing connection, purchase, delivery, consume,
attribute write or purchase-journal mutation. It reads the encrypted native
session without importing unbound legacy Flutter preferences. An installation
that only has those older preferences needs a successful normal online
bootstrap before this offline path can use its bound encrypted session.

## Identity and validity

- Use the same API key and exact requested public customer identifier. Numeric
  V1 identifiers are not converted into Go identifiers.
- A null requested identifier accepts only an anonymous cached customer; it
  does not authorize restoring a previous signed-in customer's data.
- Validate the host's pending-logout barrier before attempting restoration.
  V1 does not provide Go's pending-logout lifecycle automatically.
- Malformed, unreadable or mismatched cached CustomerInfo is never published as
  access for the requested customer. Invalid offerings are not displayed.
- Offline JSON documents are bounded to 1 MiB, 32 levels and 50,000 values;
  duplicate keys and invalid calendar/time components are rejected. These
  offline acceptance checks do not change the old online parsing APIs.
- Offerings depend on their saved targeting context. A changed market, key,
  country or app version can invalidate the catalog even if CustomerInfo is
  reusable; fetch a fresh catalog when connectivity returns.
- A definitive authentication rejection after opting into restored state
  blocks future cache restoration durably when that storage write succeeds.
  Purchase recovery credentials and journals are not erased. A successful
  normal online lifecycle commit can replace that blocked cache.
  If secure storage refuses the block write, the operation reports
  `cacheRestoreBlockPersisted=false` and keeps this client blocked; no SDK can
  guarantee a durable marker after an unsuccessful disk write.

Cache freshness is not proof of entitlement freshness. Check local entitlement
expiration before granting offline subscription access; a server's old
`isActive=true` does not extend an expired subscription. Offline access cannot
detect a new server-side revocation, and device-clock checks are not a trusted
server clock. New purchases and consumable fulfillment still require their
normal authoritative verification and delivery flows.

## Refresh without blocking cached display

After publishing/reading the local result, start `configure(options)` in a
lifecycle-owned background coroutine and observe `STATE_CHANGED`,
`CUSTOMER_INFO_CHANGED` and `OFFERINGS_CHANGED`. Cache restoration does not start
that request itself. Coalesce refreshes per identity and guard their UI effects
with a host identity generation; logout or account switching must hide the old
account immediately and take precedence over queued callbacks.

When no valid local cache exists, normal Configure is necessary before the
session is usable. A transient network refresh failure is not a logout and
does not discard previously restored valid data. Inspect the Result separately
from the retained snapshot; do not convert every failure into purchase success.

Do not enqueue the network request first and expect a later asynchronous cache
getter to bypass it: client operations are serialized. Read the restored
snapshot first. Existing `getCustomerInfo(false)` still uses its five-minute
freshness rule; `getOfferings()` still uses already-loaded cache and does not
itself schedule a background refresh. Their defaults and V1 ABI are unchanged.

## Upgrade checks

Test process recreation without clearing data; cold startup offline with an old
but unexpired entitlement; reconnect and event-driven updates; wrong key/user;
pending logout; malformed cache; expired subscriptions; targeting changes; and
account switching during a delayed refresh. Unit/build checks do not substitute
for a signed, same-package device upgrade or a live payment acceptance test.
