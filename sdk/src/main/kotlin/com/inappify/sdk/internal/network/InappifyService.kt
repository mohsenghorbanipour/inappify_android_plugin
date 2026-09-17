package com.inappify.sdk.internal.network

import com.inappify.sdk.InappifyAttribute

internal class ConfigureApiRequest(
    internal val apiKey: String,
    internal val packageIdentifier: String,
    internal val appUserIdentifier: String?,
    internal val versionName: String,
    internal val versionCode: Long,
)

internal class LoginApiRequest(
    internal val apiKey: String,
    internal val appUserIdentifier: String,
    internal val forceVersion: Long?,
    internal val token: String?,
)

internal class LogoutApiRequest(
    internal val apiKey: String,
    internal val token: String,
    internal val forceVersion: Long?,
)

/** Shared wire request for session-bound customer and offering resources. */
internal class ResourceApiRequest(
    internal val apiKey: String,
    internal val token: String,
    internal val forceVersion: Long?,
)

/** Wire request used to verify and register one store purchase with Inappify. */
internal class PurchaseApiRequest(
    internal val apiKey: String,
    internal val token: String,
    internal val appIdentifier: String,
    internal val country: String,
    internal val productIdentifier: String,
    internal val offeringIdentifier: String,
    internal val purchaseTokenId: String?,
    internal val discount: Long,
    internal val isCrypto: Boolean,
    internal val forceVersion: Long?,
    internal val appVersion: String,
    internal val purchaseStoreTime: Long?,
    internal val paywallId: Long? = null,
    internal val paywallRevision: Long? = null,
)

/** The server operation represented by one V2 store-purchase submission. */
internal enum class StorePurchaseOperation(internal val wireValue: String) {
    PURCHASE("purchase"),
    RESTORE("restore"),
}

/**
 * Store evidence forwarded to Inappify for server-to-server verification.
 *
 * None of these values may be treated as entitlement authority on-device.
 */
internal class StorePurchaseEvidence(
    internal val token: String,
    internal val purchaseTime: Long?,
    internal val orderId: String?,
    internal val packageName: String?,
    internal val developerPayload: String?,
    internal val originalJson: String?,
    internal val signature: String?,
) {
    override fun toString(): String =
        "StorePurchaseEvidence(" +
            "token=<redacted>, " +
            "purchaseTime=<redacted>, " +
            "orderId=<redacted>, " +
            "packageName=<redacted>, " +
            "developerPayload=<redacted>, " +
            "originalJson=<redacted>, " +
            "signature=<redacted>" +
            ")"
}

/** Wire request for registering or restoring one store purchase through V2. */
internal class StorePurchaseApiRequest(
    internal val apiKey: String,
    internal val token: String,
    internal val appIdentifier: String,
    internal val productIdentifier: String,
    internal val offeringIdentifier: String,
    internal val country: String,
    internal val appVersion: String,
    internal val forceVersion: Long?,
    internal val operation: StorePurchaseOperation,
    internal val purchase: StorePurchaseEvidence,
) {
    override fun toString(): String =
        "StorePurchaseApiRequest(" +
            "apiKey=<redacted>, " +
            "token=<redacted>, " +
            "appIdentifier=<redacted>, " +
            "productIdentifier=<redacted>, " +
            "offeringIdentifier=<redacted>, " +
            "country=<redacted>, " +
            "appVersion=<redacted>, " +
            "forceVersion=$forceVersion, " +
            "operation=$operation, " +
            "purchase=$purchase" +
            ")"
}

/** Wire request for polling one durable store-verification operation. */
internal class StoreVerificationStatusApiRequest(
    internal val apiKey: String,
    internal val token: String,
    internal val verificationRequestId: Long,
) {
    override fun toString(): String =
        "StoreVerificationStatusApiRequest(" +
            "apiKey=<redacted>, token=<redacted>, verificationRequestId=<redacted>)"
}

/** Wire request acknowledging that the host delivered one consumable item. */
internal class StoreDeliveryApiRequest(
    internal val apiKey: String,
    internal val token: String,
    internal val deliveryId: Long,
) {
    override fun toString(): String =
        "StoreDeliveryApiRequest(" +
            "apiKey=<redacted>, token=<redacted>, deliveryId=<redacted>)"
}

/** Result of consuming a delivered purchase in the official store SDK. */
internal enum class StoreConsumeResult(internal val wireValue: String) {
    SUCCEEDED("succeeded"),
    FAILED("failed"),
}

/** Wire request reporting the store-consumption result for one delivery. */
internal class StoreConsumeResultApiRequest(
    internal val apiKey: String,
    internal val token: String,
    internal val deliveryId: Long,
    internal val result: StoreConsumeResult,
    internal val errorCode: String?,
) {
    override fun toString(): String =
        "StoreConsumeResultApiRequest(" +
            "apiKey=<redacted>, " +
            "token=<redacted>, " +
            "deliveryId=<redacted>, " +
            "result=$result, " +
            "errorCode=<redacted>" +
            ")"
}

/** Session-bound request for the shared unfinished-consumable queue. */
internal class PendingConsumableDeliveriesApiRequest(
    internal val apiKey: String,
    internal val token: String,
)

/** Session-bound acknowledgement for a Direct consumable delivery. */
internal class DirectConsumableDeliveryApiRequest(
    internal val apiKey: String,
    internal val token: String,
    internal val deliveryId: Long,
)

internal enum class ConsumableDeliverySource(internal val wireValue: String) {
    DIRECT("direct"),
    BAZAAR("bazar"),
    MYKET("myket"),
}

/** Backend projection returned by the shared consumable-delivery endpoints. */
internal class BackendConsumableDelivery(
    internal val deliveryId: Long,
    internal val status: StorePurchaseStatus,
    internal val source: ConsumableDeliverySource,
    internal val paymentId: Long?,
    internal val transactionId: String?,
    internal val productIdentifier: String,
    internal val alreadyDelivered: Boolean?,
    internal val consumeAttempts: Long?,
)

internal class ConsumableDeliveriesBackendResponse(
    internal val status: Boolean?,
    internal val deliveries: List<BackendConsumableDelivery>,
    internal val errorCode: String?,
    internal val message: String?,
)

internal sealed interface ConsumableDeliveriesServiceResult {
    class Response(
        internal val statusCode: Int,
        internal val payload: ConsumableDeliveriesBackendResponse,
        internal val requestId: String?,
        internal val retryAfterSeconds: Long? = null,
    ) : ConsumableDeliveriesServiceResult

    class Failure(
        internal val kind: ServiceFailureKind,
    ) : ConsumableDeliveriesServiceResult
}

/** Wire request for validating one discount code against the active session. */
internal class ValidateDiscountCodeApiRequest(
    internal val apiKey: String,
    internal val token: String,
    internal val discountCode: String,
)

/** Wire request for storing custom attributes with non-blank values. */
internal class StoreAttributesApiRequest(
    internal val apiKey: String,
    internal val token: String,
    attributes: List<InappifyAttribute>,
    internal val forceVersion: Long?,
) {
    internal val attributes: List<InappifyAttribute> = attributes.toList()
}

/** Wire request for removing custom or reserved attributes by key. */
internal class RemoveAttributesApiRequest(
    internal val apiKey: String,
    internal val token: String,
    attributes: List<InappifyAttribute>,
    internal val forceVersion: Long?,
) {
    internal val attributes: List<InappifyAttribute> = attributes.toList()
}

/** Wire request for storing one reserved customer attribute. */
internal class StoreReservedAttributeApiRequest(
    internal val apiKey: String,
    internal val token: String,
    internal val key: String,
    internal val value: String,
    internal val forceVersion: Long?,
)

/** Wire request for synchronizing the complete local attribute collection. */
internal class SyncAttributesApiRequest(
    internal val apiKey: String,
    internal val token: String,
    attributes: List<InappifyAttribute>,
    internal val forceVersion: Long?,
) {
    internal val attributes: List<InappifyAttribute> = attributes.toList()
}

/** Token-free purchase projection returned by the Inappify backend. */
internal class BackendPurchase(
    internal val url: String?,
    internal val purchaseStatus: String?,
    internal val checkoutId: String?,
    internal val checkoutStatus: String?,
    internal val nextActionType: String?,
)

/** Resource request used while configuration validates a restored session. */
internal typealias RefreshSessionApiRequest = ResourceApiRequest

internal class BackendResponse(
    internal val status: Boolean?,
    internal val message: String?,
    internal val errorCode: String?,
    internal val token: String?,
    internal val appUserIdentifier: String?,
    internal val customerInfoJson: String?,
    internal val storeInfo: String?,
    internal val appId: Long?,
    internal val forceVersion: Long?,
    internal val offeringsJson: String? = null,
    internal val purchase: BackendPurchase? = null,
    internal val discountCodeResultJson: String? = null,
    internal val attributesJson: String? = null,
    /** V2 route selected by app configuration; distinct from legacy storeInfo. */
    internal val storePlatform: String? = null,
)

/** Server-authoritative state of one V2 store operation. */
internal enum class StorePurchaseStatus(internal val wireValue: String) {
    PROCESSING("PROCESSING"),
    COMPLETED("COMPLETED"),
    RESTORED("RESTORED"),
    ALREADY_PROCESSED("ALREADY_PROCESSED"),
    DELIVERY_REQUIRED("DELIVERY_REQUIRED"),
    CONSUME_REQUIRED("CONSUME_REQUIRED"),
    REJECTED("REJECTED"),
}

/** Typed projection shared by initial, polling, and delivery V2 responses. */
internal class StorePurchaseState(
    internal val status: StorePurchaseStatus,
    internal val paymentId: Long?,
    internal val eventId: Long?,
    internal val deliveryId: Long?,
    internal val verificationRequestId: Long?,
    internal val retryAfter: Long?,
    internal val errorCode: String?,
    internal val message: String?,
    internal val alreadyProcessed: Boolean?,
    internal val source: String? = null,
    internal val productIdentifier: String? = null,
    internal val alreadyDelivered: Boolean? = null,
) {
    override fun toString(): String =
        "StorePurchaseState(" +
            "status=$status, " +
            "paymentId=<redacted>, " +
            "eventId=<redacted>, " +
            "deliveryId=<redacted>, " +
            "verificationRequestId=<redacted>, " +
            "retryAfter=$retryAfter, " +
            "errorCode=<redacted>, " +
            "message=<redacted>, " +
            "alreadyProcessed=$alreadyProcessed" +
            ")"
}

/** V2 envelope kept separate from the legacy purchase response contract. */
internal class StoreBackendResponse(
    internal val status: Boolean?,
    internal val state: StorePurchaseState?,
    internal val hasForceUpdate: Boolean?,
    internal val forceVersion: Long?,
    internal val errorCode: String?,
    internal val message: String?,
) {
    override fun toString(): String =
        "StoreBackendResponse(" +
            "status=$status, " +
            "state=$state, " +
            "hasForceUpdate=$hasForceUpdate, " +
            "forceVersion=$forceVersion, " +
            "errorCode=<redacted>, " +
            "message=<redacted>" +
            ")"
}

internal enum class ServiceFailureKind {
    NETWORK,
    TIMEOUT,
    CANCELLED,
    MALFORMED_RESPONSE,
    UNKNOWN,
}

internal sealed interface ServiceResult {
    class Response(
        internal val statusCode: Int,
        internal val payload: BackendResponse,
        internal val requestId: String?,
    ) : ServiceResult

    class Failure(
        internal val kind: ServiceFailureKind,
    ) : ServiceResult
}

/** Result boundary for the V2 store API; legacy response decoding is untouched. */
internal sealed interface StoreServiceResult {
    class Response(
        internal val statusCode: Int,
        internal val payload: StoreBackendResponse,
        internal val requestId: String?,
        internal val retryAfterSeconds: Long? = null,
    ) : StoreServiceResult

    class Failure(
        internal val kind: ServiceFailureKind,
    ) : StoreServiceResult
}

internal interface InappifyService : AutoCloseable {
    suspend fun configure(request: ConfigureApiRequest): ServiceResult

    suspend fun login(request: LoginApiRequest): ServiceResult

    suspend fun logout(request: LogoutApiRequest): ServiceResult

    suspend fun refreshSession(request: RefreshSessionApiRequest): ServiceResult

    /** Reads the customer resource for the active token. */
    suspend fun getCustomerInfo(request: ResourceApiRequest): ServiceResult

    /** Reads offering definitions and targeting rules for the active token. */
    suspend fun getOfferings(request: ResourceApiRequest): ServiceResult

    /** Verifies a store result and registers the purchase with Inappify. */
    suspend fun purchase(request: PurchaseApiRequest): ServiceResult =
        ServiceResult.Failure(ServiceFailureKind.UNKNOWN)

    /** Submits one store purchase or restore operation for V2 verification. */
    suspend fun submitStorePurchase(
        request: StorePurchaseApiRequest,
    ): StoreServiceResult = StoreServiceResult.Failure(ServiceFailureKind.UNKNOWN)

    /** Polls the server-authoritative state of an accepted store operation. */
    suspend fun getStoreVerificationStatus(
        request: StoreVerificationStatusApiRequest,
    ): StoreServiceResult = StoreServiceResult.Failure(ServiceFailureKind.UNKNOWN)

    /** Acknowledges successful host delivery before a consumable is consumed. */
    suspend fun markStoreDeliveryDelivered(
        request: StoreDeliveryApiRequest,
    ): StoreServiceResult = StoreServiceResult.Failure(ServiceFailureKind.UNKNOWN)

    /** Reports whether the official store SDK consumed the delivered item. */
    suspend fun reportStoreConsumeResult(
        request: StoreConsumeResultApiRequest,
    ): StoreServiceResult = StoreServiceResult.Failure(ServiceFailureKind.UNKNOWN)

    /** Returns up to one backend batch of unfinished consumable deliveries. */
    suspend fun getPendingConsumableDeliveries(
        request: PendingConsumableDeliveriesApiRequest,
    ): ConsumableDeliveriesServiceResult =
        ConsumableDeliveriesServiceResult.Failure(ServiceFailureKind.UNKNOWN)

    /** Acknowledges a durably granted Direct consumable. */
    suspend fun markDirectConsumableDelivered(
        request: DirectConsumableDeliveryApiRequest,
    ): ConsumableDeliveriesServiceResult =
        ConsumableDeliveriesServiceResult.Failure(ServiceFailureKind.UNKNOWN)

    /** Validates one discount code for the active session. */
    suspend fun validateDiscountCode(request: ValidateDiscountCodeApiRequest): ServiceResult =
        ServiceResult.Failure(ServiceFailureKind.UNKNOWN)

    /** Stores custom attributes whose values are not blank. */
    suspend fun storeAttributes(request: StoreAttributesApiRequest): ServiceResult =
        ServiceResult.Failure(ServiceFailureKind.UNKNOWN)

    /** Removes custom or reserved attribute keys. */
    suspend fun removeAttributes(request: RemoveAttributesApiRequest): ServiceResult =
        ServiceResult.Failure(ServiceFailureKind.UNKNOWN)

    /** Deletes custom or reserved attribute keys. */
    suspend fun deleteAttributes(request: RemoveAttributesApiRequest): ServiceResult =
        removeAttributes(request)

    /** Stores one reserved customer attribute. */
    suspend fun storeReservedAttribute(
        request: StoreReservedAttributeApiRequest,
    ): ServiceResult = ServiceResult.Failure(ServiceFailureKind.UNKNOWN)

    /** Synchronizes the complete local attribute collection. */
    suspend fun syncAttributes(request: SyncAttributesApiRequest): ServiceResult =
        ServiceResult.Failure(ServiceFailureKind.UNKNOWN)

    override fun close()
}
