# Offline cache in 3.3.0

V2 persists verified sessions/resources in an encrypted document. Configure
validates matching app, endpoint, identity, signed scope and expiry before reusing
that state. It is not a V1 cache migration. Old preferences/files are not deleted.

Use `customerInfo` and `offerings` with `CACHE_ONLY`, `CACHE_FIRST`,
`NETWORK_FIRST` or `NETWORK_ONLY` according to the screen's needs. A cache miss
or identity/logout barrier is a typed failure, not fabricated customer state.
V2 `forceVersion` is null and `hasForceUpdate` is false; they do not identify SDK
version or authorize entitlement grants.

`restoreCachedSession` is a compatibility capability for custom clients. The
production V2 client does not implement the old cache-only startup API. Start
with V2 Configure and fetch policies; Flutter must handle unsupported explicit
restore without falling back to a legacy HTTP client.

Durable login/logout attempts and delivery checkpoints remain in the existing
V2 document. Invoke `recover()` when the app returns to the foreground, network
access returns or a payment page returns. Pending logout blocks old-user state.
The host owns its inventory ledger, authenticated-account generation and UI
lifecycle. Do not clear pending operations to make offline startup succeed.
