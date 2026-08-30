package com.inappify.sdk

/**
 * Complete public state produced by an [InappifyClient] operation.
 *
 * This model is intentionally token-free. API keys, access tokens, identity
 * tickets, and purchase tokens remain private to the implementation.
 */
public class InappifySnapshot internal constructor(
    /** Client-local, monotonically increasing state revision. */
    public val revision: Long,
    public val isConfigured: Boolean,
    public val isAuthenticated: Boolean,
    public val appUserIdentifier: String?,
    public val market: InappifyMarket?,
    public val country: String?,
    public val appVersion: String?,
    public val sdkVersion: String,
    public val storeInfo: String?,
    public val forceVersion: Long?,
    public val appId: Long?,
    /** Last valid customer information for the active session, if available. */
    public val customerInfo: InappifyCustomerInfo?,
    /** Last valid offerings for the active session, if available. */
    public val offerings: InappifyOfferings?,
    /** Whether the most recent customer-information load failed. */
    public val failedToLoadCustomerInfo: Boolean,
    /** Whether the most recent offerings load failed. */
    public val failedToLoadOfferings: Boolean,
) {

    /** Returns token-free state while redacting the customer identifier. */
    public override fun toString(): String =
        "InappifySnapshot(" +
            "revision=$revision, " +
            "isConfigured=$isConfigured, " +
            "isAuthenticated=$isAuthenticated, " +
            "appUserIdentifier=${if (appUserIdentifier == null) "null" else "<redacted>"}, " +
            "market=$market, " +
            "country=$country, " +
            "appVersion=$appVersion, " +
            "sdkVersion=$sdkVersion, " +
            "storeInfo=$storeInfo, " +
            "forceVersion=$forceVersion, " +
            "appId=$appId, " +
            "hasCustomerInfo=${customerInfo != null}, " +
            "offeringsCount=${offerings?.offerings?.size ?: 0}, " +
            "failedToLoadCustomerInfo=$failedToLoadCustomerInfo, " +
            "failedToLoadOfferings=$failedToLoadOfferings" +
            ")"

    internal companion object {

        /** Creates the state held before the first successful configuration. */
        internal fun initial(sdkVersion: String): InappifySnapshot =
            InappifySnapshot(
                revision = 0,
                isConfigured = false,
                isAuthenticated = false,
                appUserIdentifier = null,
                market = null,
                country = null,
                appVersion = null,
                sdkVersion = sdkVersion,
                storeInfo = null,
                forceVersion = 1L,
                appId = null,
                customerInfo = null,
                offerings = null,
                failedToLoadCustomerInfo = false,
                failedToLoadOfferings = false,
            )
    }
}
