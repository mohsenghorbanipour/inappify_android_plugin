package com.inappify.sdk.internal.v2

import com.google.gson.JsonObject
import com.inappify.sdk.internal.network.*
import kotlinx.coroutines.CancellationException

/** Adapts the existing durable Bazaar coordinator to the session-scoped Laravel V2 API. */
internal class GoV2StoreService(
    private val api: GoApi,
    private val sessionToken: suspend () -> String,
) : InappifyService {
    override suspend fun configure(request: ConfigureApiRequest): ServiceResult = unsupported()
    override suspend fun login(request: LoginApiRequest): ServiceResult = unsupported()
    override suspend fun logout(request: LogoutApiRequest): ServiceResult = unsupported()
    override suspend fun refreshSession(request: RefreshSessionApiRequest): ServiceResult = unsupported()
    override suspend fun getCustomerInfo(request: ResourceApiRequest): ServiceResult = unsupported()
    override suspend fun getOfferings(request: ResourceApiRequest): ServiceResult = unsupported()
    override fun close() = Unit // GoV2Client owns the transport.

    private fun unsupported(): ServiceResult = ServiceResult.Failure(ServiceFailureKind.UNKNOWN)

    override suspend fun submitStorePurchase(request: StorePurchaseApiRequest): StoreServiceResult =
        call("store/purchases", JsonObject().apply {
            addProperty("productIdentifier", request.productIdentifier)
            addProperty("offeringIdentifier", request.offeringIdentifier)
            addProperty("operation", request.operation.wireValue)
            add("purchase", JsonObject().apply {
                addProperty("token", request.purchase.token)
                request.purchase.purchaseTime?.let { addProperty("purchaseTime", it) }
                request.purchase.orderId?.let { addProperty("orderId", it) }
                request.purchase.packageName?.let { addProperty("packageName", it) }
                request.purchase.developerPayload?.let { addProperty("developerPayload", it) }
                request.purchase.originalJson?.let { addProperty("originalJson", it) }
                request.purchase.signature?.let { addProperty("signature", it) }
            })
        }, nestedPurchase = true)

    override suspend fun getStoreVerificationStatus(request: StoreVerificationStatusApiRequest): StoreServiceResult =
        call("store/verifications/${request.verificationRequestId}/status", JsonObject())

    override suspend fun markStoreDeliveryDelivered(request: StoreDeliveryApiRequest): StoreServiceResult =
        call("store/deliveries/${request.deliveryId}/delivered", JsonObject())

    override suspend fun reportStoreConsumeResult(request: StoreConsumeResultApiRequest): StoreServiceResult =
        call("store/deliveries/${request.deliveryId}/consume-result", JsonObject().apply {
            addProperty("result", request.result.wireValue)
            request.errorCode?.let { addProperty("errorCode", it) }
        })

    private suspend fun call(endpoint: String, body: JsonObject, nestedPurchase: Boolean = false): StoreServiceResult {
        return try {
            // Submission is keyed by store receipt on the server. The coordinator owns replay/backoff.
            val response = api.request(endpoint, sessionToken(), body, retry = false)
            val data = response.getAsJsonObject("data") ?: return malformed()
            val state = (if (nestedPurchase) data.getAsJsonObject("purchase") else data) ?: return malformed()
            val status = state.text("status")?.let { value ->
                StorePurchaseStatus.entries.firstOrNull { it.wireValue == value }
            } ?: return malformed()
            StoreServiceResult.Response(200, StoreBackendResponse(true, StorePurchaseState(
                status = status,
                paymentId = state.long("paymentId"),
                eventId = state.long("eventId"),
                deliveryId = state.long("deliveryId"),
                verificationRequestId = state.long("verificationRequestId"),
                retryAfter = state.long("retryAfter"),
                errorCode = state.text("errorCode"),
                message = state.text("message"),
                alreadyProcessed = state.bool("alreadyProcessed"),
                source = state.text("source"),
                productIdentifier = state.text("productIdentifier"),
                alreadyDelivered = state.bool("alreadyDelivered"),
            ), false, null, null, null), null)
        } catch (failure: V2Failure) {
            val details = failure.sdkError.details
            val status = details["httpStatus"] as? Int
            if (status == null) StoreServiceResult.Failure(when (details["category"]) {
                "TIMEOUT" -> ServiceFailureKind.TIMEOUT
                "NETWORK" -> ServiceFailureKind.NETWORK
                "DECODING" -> ServiceFailureKind.MALFORMED_RESPONSE
                else -> ServiceFailureKind.UNKNOWN
            }) else StoreServiceResult.Response(status, StoreBackendResponse(false, null, false, null,
                details["serverCode"] as? String, null), null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            malformed()
        }
    }

    private fun malformed(): StoreServiceResult = StoreServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
    private fun JsonObject.text(name: String): String? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asString
    }.getOrNull()
    private fun JsonObject.long(name: String): Long? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asLong
    }.getOrNull()
    private fun JsonObject.bool(name: String): Boolean? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean
    }.getOrNull()
}
