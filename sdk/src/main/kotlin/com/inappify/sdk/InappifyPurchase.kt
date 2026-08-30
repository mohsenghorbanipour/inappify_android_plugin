package com.inappify.sdk

/** Purchase state returned after Inappify server processing. */
public enum class InappifyPurchaseStatus {
    /** The checkout completed and the server accepted the purchase. */
    DONE,

    /** Additional payment action is required before the checkout can complete. */
    NEEDTOPAY,
    ;

    internal companion object {
        /**
         * Maps server status values. A missing status maps to null; unknown
         * values are rejected as malformed.
         */
        internal fun fromServerValue(value: String?): InappifyPurchaseStatus? =
            when (value) {
                null -> null
                "DONE" -> DONE
                "NEEDTOPAY" -> NEEDTOPAY
                else -> throw IllegalArgumentException("Unknown purchase status.")
            }
    }
}

/**
 * Immutable, token-free result of a purchase attempt.
 *
 * [attemptId] correlates SDK events for one logical attempt. [url] may contain
 * payment-provider state and therefore must not be logged. Store receipts and
 * purchase tokens are deliberately retained only inside the SDK.
 */
public class InappifyPurchase @JvmOverloads public constructor(
    public val attemptId: String,
    public val productIdentifier: String,
    public val offeringIdentifier: String,
    public val market: InappifyMarket,
    public val purchaseStatus: InappifyPurchaseStatus? = null,
    public val packageIdentifier: String? = null,
    public val url: String? = null,
    public val checkoutId: String? = null,
    public val checkoutStatus: String? = null,
    public val nextActionType: String? = null,
) {

    /** Returns useful state while redacting correlation, product, checkout, and URL values. */
    public override fun toString(): String =
        "InappifyPurchase(" +
            "attemptId=${attemptId.redactedValue()}, " +
            "productIdentifier=${productIdentifier.redactedValue()}, " +
            "offeringIdentifier=${offeringIdentifier.redactedValue()}, " +
            "packageIdentifier=${packageIdentifier.redactedValue()}, " +
            "market=$market, " +
            "purchaseStatus=$purchaseStatus, " +
            "url=${url.redactedValue()}, " +
            "checkoutId=${checkoutId.redactedValue()}, " +
            "checkoutStatus=$checkoutStatus, " +
            "nextActionType=$nextActionType" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyPurchase &&
            attemptId == other.attemptId &&
            productIdentifier == other.productIdentifier &&
            offeringIdentifier == other.offeringIdentifier &&
            packageIdentifier == other.packageIdentifier &&
            market == other.market &&
            purchaseStatus == other.purchaseStatus &&
            url == other.url &&
            checkoutId == other.checkoutId &&
            checkoutStatus == other.checkoutStatus &&
            nextActionType == other.nextActionType

    public override fun hashCode(): Int {
        var result = attemptId.hashCode()
        result = 31 * result + productIdentifier.hashCode()
        result = 31 * result + offeringIdentifier.hashCode()
        result = 31 * result + (packageIdentifier?.hashCode() ?: 0)
        result = 31 * result + market.hashCode()
        result = 31 * result + (purchaseStatus?.hashCode() ?: 0)
        result = 31 * result + (url?.hashCode() ?: 0)
        result = 31 * result + (checkoutId?.hashCode() ?: 0)
        result = 31 * result + (checkoutStatus?.hashCode() ?: 0)
        result = 31 * result + (nextActionType?.hashCode() ?: 0)
        return result
    }
}
