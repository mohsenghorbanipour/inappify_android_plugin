package com.inappify.sdk

/** Optional capability that does not add a method to the published V1 interface. */
internal interface CachedSessionCapableClient {
    suspend fun restoreCachedSessionInternal(options: InappifyOptions): InappifyResult<Boolean>
}

/**
 * Publishes an application-private V1 session cache without contacting the server or store.
 *
 * `Success(true)` exposes validated cached CustomerInfo and any valid, configuration-bound
 * Offerings through [InappifyClient.snapshot] and normal SDK events. Cached data is not proof
 * of current server authorization or purchase completion. The five-minute customer getter
 * policy and the network semantics of [InappifyClient.configure] are unchanged.
 *
 * An explicit [InappifyOptions.appUserIdentifier] must match exactly, including an anonymous
 * identifier. Omitting it restores anonymous customers only; a signed-in host must supply its
 * existing identifier. Missing, invalid, unbound, or mismatched cache returns `Success(false)`
 * without changing the current session. Targeting changes discard only cached Offerings.
 *
 * After a successful restore, the host may launch same-identity [InappifyClient.configure]
 * in its background scope and observe events. Network failure does not discard this cache.
 * Hosts must resolve a pending logout before invoking this method and keep their own account
 * generation guard around background work. A cache miss must never be interpreted as login.
 *
 * Go V2 and custom clients without this capability return `UNSUPPORTED_OPERATION`; this
 * method never silently selects another protocol or performs a network fallback.
 */
public suspend fun InappifyClient.restoreCachedSession(
    options: InappifyOptions,
): InappifyResult<Boolean> = if (this is CachedSessionCapableClient) {
    restoreCachedSessionInternal(options)
} else {
    InappifyResult.Failure(
        error = InappifyError(
            code = InappifyErrorCode.UNSUPPORTED_OPERATION,
            message = "Local V1 session restoration is not supported by this client.",
            details = mapOf("operation" to "restoreCachedSession"),
        ),
        snapshot = snapshot,
    )
}
