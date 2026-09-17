package com.inappify.sdk

import android.content.Context
import com.inappify.sdk.internal.v2.GoV2Client
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Collections
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Explicit, fail-closed opt-in to the Go v2 service. V1 creation is unchanged. */
public class InappifyV2Configuration public constructor(
    public val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    public val issuer: String,
    public val appId: Long,
    public val projectId: Long,
    pinnedSigningKeys: Map<String, String>,
    paymentHosts: Set<String>,
    assetHosts: Set<String> = emptySet(),
) {
    /** kid to unpadded base64url encoded, raw 32-byte Ed25519 public key. */
    public val pinnedSigningKeys: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(pinnedSigningKeys))
    public val paymentHosts: Set<String> = Collections.unmodifiableSet(LinkedHashSet(paymentHosts))
    public val assetHosts: Set<String> = Collections.unmodifiableSet(LinkedHashSet(assetHosts))
    init {
        val url = apiBaseUrl.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null && url.encodedPath == "/app/v2/") {
            "Go base URL must be an HTTPS origin followed by /app/v2/."
        }
        require(issuer.isNotBlank() && appId > 0 && projectId > 0)
        require(pinnedSigningKeys.isNotEmpty() && pinnedSigningKeys.keys.all { it.isNotBlank() })
        require((paymentHosts + assetHosts).all { host ->
            host.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*")) && host.contains('.')
        }) { "URL allowlists require exact lowercase host names, without wildcards." }
    }
    public override fun toString(): String = "InappifyV2Configuration(appId=$appId, projectId=$projectId)"

    public companion object {
        /** Official /app service root plus the Go v2 route prefix; V1 routing is independent. */
        public const val DEFAULT_API_BASE_URL: String = "https://service.inappify.com/app/v2/"
    }
}

public enum class InappifyFetchPolicy { CACHE_ONLY, CACHE_FIRST, NETWORK_FIRST, NETWORK_ONLY }

/** Additional Go v2 APIs; the inherited V1-facing method signatures are retained. */
public interface InappifyV2Client : InappifyClient {
    public val hasForceUpdate: Boolean
    public val isLogoutPending: Boolean
    public suspend fun customerInfo(policy: InappifyFetchPolicy): InappifyResult<InappifyCustomerInfo>
    public suspend fun offerings(policy: InappifyFetchPolicy): InappifyResult<InappifyOfferings>
    /** Queues write-only values durably; null or empty string deletes a value. */
    public suspend fun queueAttributes(values: Map<String, String?>): InappifyResult<Unit>
    public suspend fun flushAttributes(): InappifyResult<Unit>
    /** Invoke on foreground, connectivity restoration, and return from a payment URL. */
    public suspend fun recover(): InappifyResult<Unit>
    /** Stops v2 network use without discarding verified cache; host owns rollout flags. */
    public fun setNetworkEnabled(enabled: Boolean)
    /**
     * Retains an already configured legacy purchase client until the official credential bridge
     * is available. The client must have the same app and customer, and remain owned by the host.
     * Rebind after login/logout; this method never obtains or exchanges a token.
     */
    public suspend fun bindLegacyPurchaseClient(client: InappifyClient): InappifyResult<Unit>

    public companion object {
        /** Uses the official Go service and bundled signing trust; configure only needs an API key. */
        @JvmStatic
        public fun create(context: Context): InappifyV2Client =
            GoV2Client.create(context.applicationContext)

        @JvmStatic
        public fun create(context: Context, configuration: InappifyV2Configuration): InappifyV2Client =
            GoV2Client.create(context.applicationContext, configuration)
    }
}

/** Server scope authenticated by the bundled/configured signing key, never a Configure input. */
public data class InappifyV2SessionScope(
    public val issuer: String,
    public val appId: Long,
    public val projectId: Long,
)

/** Null until a session is verified, and while logout is pending. Contains no credentials. */
public val InappifyV2Client.verifiedSessionScope: InappifyV2SessionScope?
    get() = (this as? InappifyV2RuntimeCapabilities)?.verifiedScope

/** Checks the SDK's configured payment allowlist without requiring the host to duplicate it. */
public fun InappifyV2Client.isPaymentUrlAllowed(url: String): Boolean =
    (this as? InappifyV2RuntimeCapabilities)?.allowsPaymentUrl(url) == true

internal interface InappifyV2RuntimeCapabilities {
    val verifiedScope: InappifyV2SessionScope?
    fun allowsPaymentUrl(url: String): Boolean
}

/**
 * Observes token-free authoritative snapshots, including null customer state during pending logout.
 * Collectors share the client's refresh and never cancel one another's HTTP request.
 */
public fun InappifyV2Client.stateUpdates(): Flow<InappifySnapshot> = callbackFlow {
    val registration = addEventListener { event ->
        if (event.type == InappifyEventType.STATE_CHANGED) trySend(event.snapshot)
    }
    trySend(snapshot)
    awaitClose { registration.close() }
}.distinctUntilChanged { old, new -> old.revision == new.revision }
