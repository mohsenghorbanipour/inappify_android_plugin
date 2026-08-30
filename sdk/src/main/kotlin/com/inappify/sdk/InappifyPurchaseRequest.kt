package com.inappify.sdk

/**
 * Immutable request for purchasing one Inappify product.
 *
 * Purchase routing is selected per call through [market]:
 * [InappifyMarket.NONE] skips native
 * billing and calls Inappify directly, while [InappifyMarket.BAZAAR] opens the
 * Bazaar flow for non-trial products.
 *
 * [apiKey], [marketKey], [lostPurchaseToken], and [dynamicPriceToken] are
 * sensitive inputs and must never be written to logs, snapshots, errors, or
 * diagnostics. [apiKey] overrides the configured API key only for this
 * purchase request; the private access token still comes from the configured
 * session. [idempotencyKey] is an optional caller-generated correlation value
 * propagated to SDK events. The purchase endpoint does not receive this field
 * and therefore cannot enforce server-side idempotency with it.
 */
public class InappifyPurchaseRequest @JvmOverloads public constructor(
    public val productIdentifier: String,
    public val offeringIdentifier: String,
    public val packageIdentifier: String? = null,
    public val discountCode: String? = null,
    public val country: String? = null,
    public val appVersion: String? = null,
    public val apiKey: String? = null,
    public val discount: Long = 0L,
    public val isCrypto: Boolean = false,
    public val market: InappifyMarket = InappifyMarket.NONE,
    public val marketKey: String? = null,
    public val isLostPurchase: Boolean = false,
    public val lostPurchaseToken: String? = null,
    public val lostPurchaseTime: Long? = null,
    public val idempotencyKey: String? = null,
    public val dynamicPriceToken: String? = null,
) {

    /** Returns a representation that never exposes identifiers or correlation values. */
    public override fun toString(): String =
        "InappifyPurchaseRequest(" +
            "productIdentifier=${productIdentifier.redactedValue()}, " +
            "offeringIdentifier=${offeringIdentifier.redactedValue()}, " +
            "packageIdentifier=${packageIdentifier.redactedValue()}, " +
            "discountCode=${discountCode.redactedValue()}, " +
            "country=$country, " +
            "appVersion=$appVersion, " +
            "apiKey=${apiKey.redactedValue()}, " +
            "discount=$discount, " +
            "isCrypto=$isCrypto, " +
            "market=$market, " +
            "marketKey=${marketKey.redactedValue()}, " +
            "isLostPurchase=$isLostPurchase, " +
            "lostPurchaseToken=${lostPurchaseToken.redactedValue()}, " +
            "lostPurchaseTime=${lostPurchaseTime.redactedValue()}, " +
            "idempotencyKey=${idempotencyKey.redactedValue()}, " +
            "dynamicPriceToken=${dynamicPriceToken.redactedValue()}" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyPurchaseRequest &&
            productIdentifier == other.productIdentifier &&
            offeringIdentifier == other.offeringIdentifier &&
            packageIdentifier == other.packageIdentifier &&
            discountCode == other.discountCode &&
            country == other.country &&
            appVersion == other.appVersion &&
            apiKey == other.apiKey &&
            discount == other.discount &&
            isCrypto == other.isCrypto &&
            market == other.market &&
            marketKey == other.marketKey &&
            isLostPurchase == other.isLostPurchase &&
            lostPurchaseToken == other.lostPurchaseToken &&
            lostPurchaseTime == other.lostPurchaseTime &&
            idempotencyKey == other.idempotencyKey &&
            dynamicPriceToken == other.dynamicPriceToken

    public override fun hashCode(): Int {
        var result = productIdentifier.hashCode()
        result = 31 * result + offeringIdentifier.hashCode()
        result = 31 * result + (packageIdentifier?.hashCode() ?: 0)
        result = 31 * result + (discountCode?.hashCode() ?: 0)
        result = 31 * result + (country?.hashCode() ?: 0)
        result = 31 * result + (appVersion?.hashCode() ?: 0)
        result = 31 * result + (apiKey?.hashCode() ?: 0)
        result = 31 * result + discount.hashCode()
        result = 31 * result + isCrypto.hashCode()
        result = 31 * result + market.hashCode()
        result = 31 * result + (marketKey?.hashCode() ?: 0)
        result = 31 * result + isLostPurchase.hashCode()
        result = 31 * result + (lostPurchaseToken?.hashCode() ?: 0)
        result = 31 * result + (lostPurchaseTime?.hashCode() ?: 0)
        result = 31 * result + (idempotencyKey?.hashCode() ?: 0)
        result = 31 * result + (dynamicPriceToken?.hashCode() ?: 0)
        return result
    }
}
