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
)

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
)

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
