package com.inappify.sdk.internal.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSyntaxException
import com.inappify.sdk.InappifyAttribute
import com.inappify.sdk.InappifyHttpTraceListener
import com.inappify.sdk.InappifyListenerRegistration
import com.inappify.sdk.internal.toLongExact
import java.math.BigDecimal

/** Maps typed SDK requests to the Inappify HTTP contract. */
internal class DefaultInappifyService(
    private val transport: HttpTransport,
    private val gson: Gson = GsonBuilder().serializeNulls().create(),
    private val purchasePath: String = PRODUCTION_PURCHASE_URL,
    private val storeApiBaseUrl: String = PRODUCTION_STORE_API_BASE_URL,
    private val directApiBaseUrl: String = PRODUCTION_DIRECT_API_BASE_URL,
) : InappifyService, HttpDiagnosticSource {

    override fun addHttpTraceListener(
        listener: InappifyHttpTraceListener,
    ): InappifyListenerRegistration =
        if (transport is HttpDiagnosticSource) {
            transport.addHttpTraceListener(listener)
        } else {
            InappifyListenerRegistration.create(Runnable {})
        }

    override suspend fun configure(request: ConfigureApiRequest): ServiceResult {
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("identifierValue", request.packageIdentifier)
            request.appUserIdentifier?.let {
                addProperty("appUserIdentifier", it)
            }
            addProperty("vName", request.versionName)
            addProperty("vCode", request.versionCode)
        }
        return execute(CONFIGURE_PATH, body)
    }

    override suspend fun login(request: LoginApiRequest): ServiceResult {
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("appUserIdentifier", request.appUserIdentifier)
            addNullableNumber("forceVersion", request.forceVersion)
            addNullableString("token", request.token)
        }
        return execute(LOGIN_PATH, body)
    }

    override suspend fun logout(request: LogoutApiRequest): ServiceResult {
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("token", request.token)
            addNullableNumber("forceVersion", request.forceVersion)
        }
        return execute(LOGOUT_PATH, body)
    }

    override suspend fun refreshSession(
        request: RefreshSessionApiRequest,
    ): ServiceResult = getCustomerInfo(request)

    override suspend fun getCustomerInfo(
        request: ResourceApiRequest,
    ): ServiceResult {
        return execute(
            path = CUSTOMER_INFO_PATH,
            body = request.toJson(),
            contract = ResponseContract.CUSTOMER_INFO,
        )
    }

    override suspend fun getOfferings(
        request: ResourceApiRequest,
    ): ServiceResult {
        return execute(
            path = OFFERINGS_PATH,
            body = request.toJson(),
            contract = ResponseContract.OFFERINGS,
        )
    }

    override suspend fun purchase(request: PurchaseApiRequest): ServiceResult {
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("token", request.token)
            addProperty("appIdentifier", request.appIdentifier)
            addProperty("country", request.country)
            addProperty("productIdentifier", request.productIdentifier)
            addNullableString("purchaseTokenId", request.purchaseTokenId)
            addProperty("discount", request.discount)
            addProperty("isCrypto", if (request.isCrypto) 1 else 0)
            addNullableNumber("forceVersion", request.forceVersion)
            addProperty("offeringIdentifier", request.offeringIdentifier)
            addProperty("appVersion", request.appVersion)
            addNullableNumber("purchaseStoreTime", request.purchaseStoreTime)
            request.paywallId?.let { addProperty("paywallId", it) }
            request.paywallRevision?.let { addProperty("paywallRevision", it) }
        }
        return execute(
            path = purchasePath,
            body = body,
            contract = ResponseContract.PURCHASE,
        )
    }

    override suspend fun submitStorePurchase(
        request: StorePurchaseApiRequest,
    ): StoreServiceResult {
        val purchase = JsonObject().apply {
            addProperty("token", request.purchase.token)
            request.purchase.purchaseTime?.let { addProperty("purchaseTime", it) }
            request.purchase.orderId?.let { addProperty("orderId", it) }
            request.purchase.packageName?.let { addProperty("packageName", it) }
            request.purchase.developerPayload?.let {
                addProperty("developerPayload", it)
            }
            request.purchase.originalJson?.let { addProperty("originalJson", it) }
            request.purchase.signature?.let { addProperty("signature", it) }
        }
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("token", request.token)
            addProperty("appIdentifier", request.appIdentifier)
            addProperty("productIdentifier", request.productIdentifier)
            addProperty("offeringIdentifier", request.offeringIdentifier)
            addProperty("country", request.country)
            addProperty("appVersion", request.appVersion)
            request.forceVersion?.let { addProperty("forceVersion", it) }
            addProperty("operation", request.operation.wireValue)
            add("purchase", purchase)
        }
        return executeStore(
            path = storePath(STORE_PURCHASES_PATH),
            body = body,
            contract = StoreResponseContract.INITIAL_PURCHASE,
        )
    }

    override suspend fun getStoreVerificationStatus(
        request: StoreVerificationStatusApiRequest,
    ): StoreServiceResult {
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("token", request.token)
        }
        return executeStore(
            path = storePath(
                "verifications/${request.verificationRequestId}/status",
            ),
            body = body,
            contract = StoreResponseContract.FLAT_STATE,
        )
    }

    override suspend fun markStoreDeliveryDelivered(
        request: StoreDeliveryApiRequest,
    ): StoreServiceResult {
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("token", request.token)
        }
        return executeStore(
            path = storePath("deliveries/${request.deliveryId}/delivered"),
            body = body,
            contract = StoreResponseContract.TRANSITION_STATE,
        )
    }

    override suspend fun reportStoreConsumeResult(
        request: StoreConsumeResultApiRequest,
    ): StoreServiceResult {
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("token", request.token)
            addProperty("result", request.result.wireValue)
            request.errorCode?.let { addProperty("errorCode", it) }
        }
        return executeStore(
            path = storePath("deliveries/${request.deliveryId}/consume-result"),
            body = body,
            contract = StoreResponseContract.TRANSITION_STATE,
        )
    }

    override suspend fun getPendingConsumableDeliveries(
        request: PendingConsumableDeliveriesApiRequest,
    ): ConsumableDeliveriesServiceResult {
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("token", request.token)
        }
        return executeConsumableDeliveries(
            path = directPath("consumable-deliveries/pending"),
            body = body,
            contract = ConsumableDeliveryResponseContract.PENDING,
        )
    }

    override suspend fun markDirectConsumableDelivered(
        request: DirectConsumableDeliveryApiRequest,
    ): ConsumableDeliveriesServiceResult {
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("token", request.token)
        }
        return executeConsumableDeliveries(
            path = directPath(
                "consumable-deliveries/${request.deliveryId}/delivered",
            ),
            body = body,
            contract = ConsumableDeliveryResponseContract.DELIVERED,
        )
    }

    override suspend fun validateDiscountCode(
        request: ValidateDiscountCodeApiRequest,
    ): ServiceResult {
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("token", request.token)
            addProperty("code", request.discountCode)
        }
        return execute(
            path = VALIDATE_DISCOUNT_CODE_PATH,
            body = body,
            contract = ResponseContract.DISCOUNT_CODE,
        )
    }

    override suspend fun storeAttributes(
        request: StoreAttributesApiRequest,
    ): ServiceResult {
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("token", request.token)
            add("attributes", request.attributes.toValueArray())
            addNullableNumber("forceVersion", request.forceVersion)
        }
        return execute(
            path = STORE_ATTRIBUTES_PATH,
            body = body,
            contract = ResponseContract.STATUS_CODE_MUTATION,
        )
    }

    override suspend fun removeAttributes(
        request: RemoveAttributesApiRequest,
    ): ServiceResult {
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("token", request.token)
            add("attributes", request.attributes.toKeyArray())
            addNullableNumber("forceVersion", request.forceVersion)
        }
        return execute(
            path = REMOVE_ATTRIBUTES_PATH,
            body = body,
            contract = ResponseContract.STATUS_CODE_MUTATION,
        )
    }

    override suspend fun storeReservedAttribute(
        request: StoreReservedAttributeApiRequest,
    ): ServiceResult {
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("token", request.token)
            addProperty("key", request.key)
            addProperty("value", request.value)
            addNullableNumber("forceVersion", request.forceVersion)
        }
        return execute(
            path = STORE_RESERVED_ATTRIBUTE_PATH,
            body = body,
            contract = ResponseContract.STATUS_CODE_MUTATION,
        )
    }

    override suspend fun syncAttributes(
        request: SyncAttributesApiRequest,
    ): ServiceResult {
        val body = JsonObject().apply {
            addProperty("apikey", request.apiKey)
            addProperty("token", request.token)
            add("attributes", request.attributes.toValueArray())
            addNullableNumber("forceVersion", request.forceVersion)
        }
        return execute(
            path = SYNC_ATTRIBUTES_PATH,
            body = body,
            contract = ResponseContract.ATTRIBUTE_SYNC,
        )
    }

    private fun ResourceApiRequest.toJson(): JsonObject {
        return JsonObject().apply {
            addProperty("apikey", apiKey)
            addProperty("token", token)
            addNullableNumber("forceVersion", forceVersion)
        }
    }

    private suspend fun execute(
        path: String,
        body: JsonObject,
        contract: ResponseContract = ResponseContract.LIFECYCLE,
    ): ServiceResult {
        return when (
            val result = transport.execute(
                HttpRequest(
                    path = path,
                    jsonBody = gson.toJson(body),
                ),
            )
        ) {
            is TransportResult.Failure -> ServiceResult.Failure(
                kind = when (result.kind) {
                    TransportFailureKind.NETWORK -> ServiceFailureKind.NETWORK
                    TransportFailureKind.TIMEOUT -> ServiceFailureKind.TIMEOUT
                    TransportFailureKind.CANCELLED -> ServiceFailureKind.CANCELLED
                    TransportFailureKind.MALFORMED_RESPONSE ->
                        ServiceFailureKind.MALFORMED_RESPONSE
                },
            )

            is TransportResult.Response -> {
                if (contract == ResponseContract.STATUS_CODE_MUTATION) {
                    decodeStatusCodeMutation(result.response)
                } else {
                    decode(result.response, contract)
                }
            }
        }
    }

    private suspend fun executeStore(
        path: String,
        body: JsonObject,
        contract: StoreResponseContract,
    ): StoreServiceResult {
        return when (
            val result = transport.execute(
                HttpRequest(
                    path = path,
                    jsonBody = gson.toJson(body),
                ),
            )
        ) {
            is TransportResult.Failure -> StoreServiceResult.Failure(
                kind = when (result.kind) {
                    TransportFailureKind.NETWORK -> ServiceFailureKind.NETWORK
                    TransportFailureKind.TIMEOUT -> ServiceFailureKind.TIMEOUT
                    TransportFailureKind.CANCELLED -> ServiceFailureKind.CANCELLED
                    TransportFailureKind.MALFORMED_RESPONSE ->
                        ServiceFailureKind.MALFORMED_RESPONSE
                },
            )

            is TransportResult.Response -> decodeStore(result.response, contract)
        }
    }

    private suspend fun executeConsumableDeliveries(
        path: String,
        body: JsonObject,
        contract: ConsumableDeliveryResponseContract,
    ): ConsumableDeliveriesServiceResult {
        return when (
            val result = transport.execute(
                HttpRequest(path = path, jsonBody = gson.toJson(body)),
            )
        ) {
            is TransportResult.Failure -> ConsumableDeliveriesServiceResult.Failure(
                kind = result.kind.toServiceFailureKind(),
            )

            is TransportResult.Response -> decodeConsumableDeliveries(
                response = result.response,
                contract = contract,
            )
        }
    }

    override fun close() {
        transport.close()
    }

    private fun decode(
        response: HttpResponse,
        contract: ResponseContract,
    ): ServiceResult {
        val body = response.body?.takeIf(String::isNotBlank)
        if (body == null) {
            return if (response.statusCode == HTTP_OK) {
                ServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
            } else {
                emptyResponse(response)
            }
        }

        val root = try {
            JsonParser.parseString(body)
        } catch (_: JsonSyntaxException) {
            return if (response.statusCode == HTTP_OK) {
                ServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
            } else {
                emptyResponse(response)
            }
        }
        if (!root.isJsonObject) {
            return if (response.statusCode == HTTP_OK) {
                ServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
            } else {
                emptyResponse(response)
            }
        }

        val json = root.asJsonObject
        // Most endpoints truncate fractional numeric envelope fields toward
        // zero within the signed 64-bit range. The offerings endpoint requires
        // an integral forceVersion and intentionally ignores appId.
        if (
            response.statusCode == HTTP_OK &&
            contract != ResponseContract.OFFERINGS &&
            (!json.hasValidOptionalBoolean("status") ||
                !json.hasValidOptionalString("message") ||
                !json.hasValidOptionalString("token") ||
                !json.hasValidOptionalString("storeInfo") ||
                !json.hasValidOptionalStorePlatform("storePlatform") ||
                !json.hasValidOptionalStorePlatform("store_platform") ||
                !json.hasValidOptionalObject("customerInfo") ||
                json.optionalObjectArray("offerings") == null ||
                json.optionalObjectArray("rules") == null ||
                !json.hasValidOptionalLong("appId") ||
                !json.hasValidOptionalLong("forceVersion"))
        ) {
            return ServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
        }
        val endpointFields = when (contract) {
            ResponseContract.LIFECYCLE -> EndpointFields(
                status = json.booleanValue("status"),
                customerInfo = json.objectValue("customerInfo"),
                offeringsJson = null,
                forceVersion = json.longValue("forceVersion"),
            )

            ResponseContract.CUSTOMER_INFO -> decodeCustomerInfoFields(
                json = json,
                successfulHttpResponse = response.statusCode == HTTP_OK,
            ) ?: return malformedOrEmpty(response)

            ResponseContract.OFFERINGS -> decodeOfferingsFields(
                json = json,
                successfulHttpResponse = response.statusCode == HTTP_OK,
            ) ?: return malformedOrEmpty(response)

            ResponseContract.PURCHASE -> decodePurchaseFields(
                json = json,
                successfulHttpResponse = response.statusCode == HTTP_OK,
            ) ?: return malformedOrEmpty(response)

            ResponseContract.DISCOUNT_CODE -> decodeDiscountCodeFields(
                json = json,
                successfulHttpResponse = response.statusCode == HTTP_OK,
            ) ?: return malformedOrEmpty(response)

            ResponseContract.ATTRIBUTE_SYNC -> decodeAttributeSyncFields(
                json = json,
                successfulHttpResponse = response.statusCode == HTTP_OK,
            ) ?: return malformedOrEmpty(response)

            ResponseContract.STATUS_CODE_MUTATION -> error(
                "Status-code mutation responses must bypass JSON decoding.",
            )
        }
        val payload = BackendResponse(
            status = endpointFields.status,
            message = json.stringValue("message")
                ?: json.stringValue("errorMessage"),
            errorCode = json.stringValue("errorCode")
                ?: json.stringValue("code"),
            token = json.stringValue("token"),
            appUserIdentifier = endpointFields.customerInfo
                ?.stringValue("originalAppUserId")
                ?: json.stringValue("appUserIdentifier"),
            customerInfoJson = endpointFields.customerInfo?.let(gson::toJson),
            storeInfo = json.stringValue("storeInfo"),
            appId = json.longValue("appId"),
            forceVersion = endpointFields.forceVersion,
            offeringsJson = endpointFields.offeringsJson,
            purchase = endpointFields.purchase,
            discountCodeResultJson = endpointFields.discountCodeResultJson,
            attributesJson = endpointFields.attributesJson,
            storePlatform = json.storePlatformValue("storePlatform")
                ?: json.storePlatformValue("store_platform"),
        )
        return ServiceResult.Response(
            statusCode = response.statusCode,
            payload = payload,
            requestId = response.requestId ?: json.stringValue("requestId"),
        )
    }

    private fun decodeStore(
        response: HttpResponse,
        contract: StoreResponseContract,
    ): StoreServiceResult {
        val body = response.body?.takeIf(String::isNotBlank)
            ?: return if (response.statusCode == HTTP_OK) {
                StoreServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
            } else {
                emptyStoreResponse(response)
            }
        val root = try {
            JsonParser.parseString(body)
        } catch (_: JsonSyntaxException) {
            return if (response.statusCode == HTTP_OK) {
                StoreServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
            } else {
                emptyStoreResponse(response)
            }
        }
        if (!root.isJsonObject) {
            return if (response.statusCode == HTTP_OK) {
                StoreServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
            } else {
                emptyStoreResponse(response)
            }
        }

        val json = root.asJsonObject
        if (response.statusCode != HTTP_OK) {
            return StoreServiceResult.Response(
                statusCode = response.statusCode,
                payload = StoreBackendResponse(
                    status = json.booleanValue("status"),
                    state = null,
                    hasForceUpdate = json.booleanValue("hasForceUpdate"),
                    forceVersion = json.strictLongValue("forceVersion"),
                    errorCode = json.stringValue("errorCode")
                        ?: json.stringValue("code"),
                    message = json.stringValue("message")
                        ?: json.stringValue("errorMessage"),
                ),
                requestId = response.requestId ?: json.stringValue("requestId"),
                retryAfterSeconds = maxOf(response.retryAfterSeconds() ?: 0L,
                    json.strictLongValue("retryAfter")?.coerceAtLeast(0) ?: 0L),
            )
        }

        if (
            !json.hasNonNullValue("status") ||
            !json.hasValidOptionalBoolean("status") ||
            !json.hasValidOptionalBoolean("hasForceUpdate") ||
            !json.hasValidOptionalStrictLong("forceVersion") ||
            STORE_ENVELOPE_STRING_FIELDS.any { !json.hasValidOptionalString(it) }
        ) {
            return StoreServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
        }

        val envelopeStatus = json.booleanValue("status")
            ?: return StoreServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
        val dataElement = json.nonNullValue("data")
        val data = dataElement
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
        if (dataElement != null && data == null) {
            return StoreServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
        }
        if (
            data != null &&
            (!data.hasValidOptionalBoolean("hasForceUpdate") ||
                !data.hasValidOptionalStrictLong("forceVersion"))
        ) {
            return StoreServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
        }

        val state = if (envelopeStatus) {
            val stateJson = data?.storeStateObject(contract)
                ?: return StoreServiceResult.Failure(
                    ServiceFailureKind.MALFORMED_RESPONSE,
                )
            decodeStorePurchaseState(stateJson)
                ?: return StoreServiceResult.Failure(
                    ServiceFailureKind.MALFORMED_RESPONSE,
                )
        } else {
            null
        }

        return StoreServiceResult.Response(
            statusCode = HTTP_OK,
            payload = StoreBackendResponse(
                status = envelopeStatus,
                state = state,
                hasForceUpdate = data?.booleanValue("hasForceUpdate")
                    ?: json.booleanValue("hasForceUpdate"),
                forceVersion = data?.strictLongValue("forceVersion")
                    ?: json.strictLongValue("forceVersion"),
                errorCode = json.stringValue("errorCode")
                    ?: json.stringValue("code"),
                message = json.stringValue("message")
                    ?: json.stringValue("errorMessage"),
            ),
            requestId = response.requestId ?: json.stringValue("requestId"),
        )
    }

    private fun decodeConsumableDeliveries(
        response: HttpResponse,
        contract: ConsumableDeliveryResponseContract,
    ): ConsumableDeliveriesServiceResult {
        val body = response.body?.takeIf(String::isNotBlank)
            ?: return emptyConsumableResponse(response)
        val root = try {
            JsonParser.parseString(body)
        } catch (_: JsonSyntaxException) {
            return emptyConsumableResponse(response)
        }
        if (!root.isJsonObject) {
            return emptyConsumableResponse(response)
        }
        val json = root.asJsonObject
        if (response.statusCode != HTTP_OK) {
            return ConsumableDeliveriesServiceResult.Response(
                statusCode = response.statusCode,
                payload = ConsumableDeliveriesBackendResponse(
                    status = json.booleanValue("status"),
                    deliveries = emptyList(),
                    errorCode = json.stringValue("errorCode")
                        ?: json.stringValue("code"),
                    message = json.stringValue("message")
                        ?: json.stringValue("errorMessage"),
                ),
                requestId = response.requestId ?: json.stringValue("requestId"),
                retryAfterSeconds = response.retryAfterSeconds(),
            )
        }
        if (
            !json.hasValidOptionalBoolean("status") ||
            !json.hasValidOptionalString("errorCode") ||
            !json.hasValidOptionalString("code") ||
            !json.hasValidOptionalString("message") ||
            !json.hasValidOptionalString("errorMessage")
        ) {
            return ConsumableDeliveriesServiceResult.Failure(
                ServiceFailureKind.MALFORMED_RESPONSE,
            )
        }
        val envelopeStatus = json.booleanValue("status")
        if (response.statusCode == HTTP_OK && envelopeStatus == null) {
            return ConsumableDeliveriesServiceResult.Failure(
                ServiceFailureKind.MALFORMED_RESPONSE,
            )
        }
        val deliveries = if (response.statusCode == HTTP_OK && envelopeStatus == true) {
            val data = json.nonNullValue("data")
                ?.takeIf(JsonElement::isJsonObject)
                ?.asJsonObject
                ?: return ConsumableDeliveriesServiceResult.Failure(
                    ServiceFailureKind.MALFORMED_RESPONSE,
                )
            val elements = when (contract) {
                ConsumableDeliveryResponseContract.PENDING -> {
                    val array = data.nonNullValue("deliveries")
                        ?.takeIf(JsonElement::isJsonArray)
                        ?.asJsonArray
                        ?: return ConsumableDeliveriesServiceResult.Failure(
                            ServiceFailureKind.MALFORMED_RESPONSE,
                        )
                    array.toList()
                }

                ConsumableDeliveryResponseContract.DELIVERED -> listOf(data)
            }
            elements.map { element ->
                val deliveryJson = element
                    .takeIf(JsonElement::isJsonObject)
                    ?.asJsonObject
                    ?: return ConsumableDeliveriesServiceResult.Failure(
                        ServiceFailureKind.MALFORMED_RESPONSE,
                    )
                decodeConsumableDelivery(deliveryJson)
                    ?: return ConsumableDeliveriesServiceResult.Failure(
                        ServiceFailureKind.MALFORMED_RESPONSE,
                    )
            }
        } else {
            emptyList()
        }
        return ConsumableDeliveriesServiceResult.Response(
            statusCode = response.statusCode,
            payload = ConsumableDeliveriesBackendResponse(
                status = envelopeStatus,
                deliveries = deliveries,
                errorCode = json.stringValue("errorCode") ?: json.stringValue("code"),
                message = json.stringValue("message") ?: json.stringValue("errorMessage"),
            ),
            requestId = response.requestId ?: json.stringValue("requestId"),
            retryAfterSeconds = response.retryAfterSeconds(),
        )
    }

    private fun emptyConsumableResponse(
        response: HttpResponse,
    ): ConsumableDeliveriesServiceResult = if (response.statusCode == HTTP_OK) {
        ConsumableDeliveriesServiceResult.Failure(
            ServiceFailureKind.MALFORMED_RESPONSE,
        )
    } else {
        ConsumableDeliveriesServiceResult.Response(
            statusCode = response.statusCode,
            payload = ConsumableDeliveriesBackendResponse(
                status = null,
                deliveries = emptyList(),
                errorCode = null,
                message = null,
            ),
            requestId = response.requestId,
            retryAfterSeconds = response.retryAfterSeconds(),
        )
    }

    private fun HttpResponse.retryAfterSeconds(): Long? {
        val value = headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.trim() ?: return null
        value.toLongOrNull()?.takeIf { it >= 0 }?.let { return it }
        return runCatching {
            val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(value) ?: return null
            ((date.time - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1000
        }.getOrNull()
    }

    private fun decodeConsumableDelivery(
        json: JsonObject,
    ): BackendConsumableDelivery? {
        val deliveryId = json.strictLongValue("deliveryId")
            ?.takeIf { it > 0L }
            ?: return null
        val status = json.stringValue("status")?.let { wireValue ->
            StorePurchaseStatus.values().firstOrNull { it.wireValue == wireValue }
        } ?: return null
        val source = json.stringValue("source")?.let { wireValue ->
            ConsumableDeliverySource.values().firstOrNull { it.wireValue == wireValue }
        } ?: return null
        val productIdentifier = json.stringValue("productIdentifier")
            ?.takeIf(String::isNotBlank)
            ?: return null
        if (
            !json.hasValidOptionalStrictLong("paymentId") ||
            !json.hasValidOptionalString("transactionId") ||
            !json.hasValidOptionalBoolean("alreadyDelivered") ||
            !json.hasValidOptionalStrictLong("consumeAttempts")
        ) {
            return null
        }
        val paymentId = json.strictLongValue("paymentId")
        val consumeAttempts = json.strictLongValue("consumeAttempts")
        if (paymentId?.let { it <= 0L } == true || consumeAttempts?.let { it < 0L } == true) {
            return null
        }
        return BackendConsumableDelivery(
            deliveryId = deliveryId,
            status = status,
            source = source,
            paymentId = paymentId,
            transactionId = json.stringValue("transactionId"),
            productIdentifier = productIdentifier,
            alreadyDelivered = json.booleanValue("alreadyDelivered"),
            consumeAttempts = consumeAttempts,
        )
    }

    private fun JsonObject.storeStateObject(
        contract: StoreResponseContract,
    ): JsonObject? {
        return when (contract) {
            StoreResponseContract.INITIAL_PURCHASE -> {
                val purchase = nonNullValue("purchase") ?: return null
                purchase.takeIf(JsonElement::isJsonObject)?.asJsonObject
            }

            StoreResponseContract.FLAT_STATE -> this

            StoreResponseContract.TRANSITION_STATE -> {
                val purchase = nonNullValue("purchase")
                when {
                    purchase == null -> this
                    purchase.isJsonObject -> purchase.asJsonObject
                    else -> null
                }
            }
        }
    }

    private fun decodeStorePurchaseState(json: JsonObject): StorePurchaseState? {
        if (
            !json.hasNonNullValue("status") ||
            !json.hasValidOptionalString("status") ||
            STORE_STATE_ID_FIELDS.any { !json.hasValidOptionalStrictLong(it) } ||
            !json.hasValidOptionalStrictLong("retryAfter") ||
            !json.hasValidOptionalBoolean("alreadyProcessed") ||
            !json.hasValidOptionalBoolean("alreadyDelivered") ||
            !json.hasValidOptionalString("source") ||
            !json.hasValidOptionalString("productIdentifier") ||
            STORE_STATE_STRING_FIELDS.any { !json.hasValidOptionalString(it) }
        ) {
            return null
        }
        val statusValue = json.stringValue("status") ?: return null
        val status = StorePurchaseStatus.values().firstOrNull {
            it.wireValue == statusValue
        } ?: return null
        val retryAfter = json.strictLongValue("retryAfter")
        if (retryAfter != null && retryAfter < 0L) return null
        val identifiers = STORE_STATE_ID_FIELDS.associateWith { field ->
            json.strictLongValue(field)
        }
        if (identifiers.values.any { identifier -> identifier != null && identifier <= 0L }) {
            return null
        }

        return StorePurchaseState(
            status = status,
            paymentId = identifiers["paymentId"],
            eventId = identifiers["eventId"],
            deliveryId = identifiers["deliveryId"],
            verificationRequestId = identifiers["verificationRequestId"],
            retryAfter = retryAfter,
            errorCode = json.stringValue("errorCode"),
            message = json.stringValue("message"),
            alreadyProcessed = json.booleanValue("alreadyProcessed"),
            source = json.stringValue("source"),
            productIdentifier = json.stringValue("productIdentifier"),
            alreadyDelivered = json.booleanValue("alreadyDelivered"),
        )
    }

    private fun decodeCustomerInfoFields(
        json: JsonObject,
        successfulHttpResponse: Boolean,
    ): EndpointFields? {
        if (successfulHttpResponse && !json.hasValidOptionalBoolean("status")) {
            return null
        }
        val status = json.booleanValue("status")
        val customerElement = json.nonNullValue("customerInfo")
        val dataElement = json.nonNullValue("data")
        val customerInfo = customerElement
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject

        if (successfulHttpResponse) {
            if (customerElement != null && customerInfo == null) return null
            // The envelope validates generic data even though this endpoint
            // consumes customerInfo from the response root.
            if (dataElement != null && !dataElement.isJsonObject) return null
            if (!json.hasValidOptionalLong("forceVersion")) return null
        }

        return EndpointFields(
            status = status,
            customerInfo = customerInfo,
            offeringsJson = null,
            forceVersion = json.longValue("forceVersion"),
        )
    }

    private fun decodeOfferingsFields(
        json: JsonObject,
        successfulHttpResponse: Boolean,
    ): EndpointFields? {
        val explicitStatus = json.booleanValue("status")
        if (!successfulHttpResponse) {
            return EndpointFields(
                status = explicitStatus,
                customerInfo = null,
                offeringsJson = null,
                forceVersion = json.strictLongValue("forceVersion"),
            )
        }

        // On HTTP 200, this endpoint ignores the JSON status field, treats the
        // response as successful, and requires an integral forceVersion.
        if (!json.hasValidOptionalStrictLong("forceVersion")) return null
        val offerings = json.optionalObjectArray("offerings") ?: return null
        val rules = json.optionalObjectArray("rules") ?: return null
        val canonical = JsonObject().apply {
            add("offerings", offerings)
            add("rules", rules)
        }
        return EndpointFields(
            status = true,
            customerInfo = null,
            offeringsJson = gson.toJson(canonical),
            forceVersion = json.strictLongValue("forceVersion"),
        )
    }

    private fun decodePurchaseFields(
        json: JsonObject,
        successfulHttpResponse: Boolean,
    ): EndpointFields? {
        if (successfulHttpResponse && !json.hasValidOptionalBoolean("status")) {
            return null
        }
        if (successfulHttpResponse && !json.hasValidOptionalLong("forceVersion")) {
            return null
        }
        val status = json.booleanValue("status")
        if (!successfulHttpResponse) {
            return EndpointFields(
                status = status,
                customerInfo = null,
                offeringsJson = null,
                forceVersion = json.longValue("forceVersion"),
            )
        }

        val dataElement = json.nonNullValue("data")
        val data = dataElement
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
        if (dataElement != null && data == null) return null
        if (
            data != null &&
            PURCHASE_STRING_FIELDS.any { !data.hasValidOptionalString(it) }
        ) {
            return null
        }
        val purchaseStatus = data?.stringValue("purchaseStatus")
        if (purchaseStatus != null && purchaseStatus !in PURCHASE_STATUS_VALUES) {
            return null
        }

        val purchase = data?.let {
            BackendPurchase(
                url = it.stringValue("url"),
                purchaseStatus = purchaseStatus,
                checkoutId = it.stringValue("checkoutId"),
                checkoutStatus = it.stringValue("checkoutStatus"),
                nextActionType = it.stringValue("nextActionType"),
            )
        }

        return EndpointFields(
            status = status,
            customerInfo = null,
            offeringsJson = null,
            forceVersion = json.longValue("forceVersion"),
            purchase = purchase,
        )
    }

    private fun decodeDiscountCodeFields(
        json: JsonObject,
        successfulHttpResponse: Boolean,
    ): EndpointFields? {
        val status = json.booleanValue("status")
        if (!successfulHttpResponse) {
            return EndpointFields(
                status = status,
                customerInfo = null,
                offeringsJson = null,
                forceVersion = json.longValue("forceVersion"),
            )
        }

        if (!json.hasValidOptionalBoolean("status")) {
            return null
        }
        if (!json.hasValidOptionalLong("forceVersion")) {
            return null
        }
        val dataElement = json.nonNullValue("data")
        if (dataElement != null && !dataElement.isJsonObject) return null
        if (status == true && dataElement == null) return null
        if (dataElement == null) {
            return EndpointFields(
                status = status,
                customerInfo = null,
                offeringsJson = null,
                forceVersion = json.longValue("forceVersion"),
            )
        }

        return EndpointFields(
            status = status,
            customerInfo = null,
            offeringsJson = null,
            forceVersion = json.longValue("forceVersion"),
            discountCodeResultJson = gson.toJson(dataElement.asJsonObject),
        )
    }

    private fun decodeAttributeSyncFields(
        json: JsonObject,
        successfulHttpResponse: Boolean,
    ): EndpointFields? {
        if (successfulHttpResponse && !json.hasValidOptionalBoolean("status")) {
            return null
        }
        if (successfulHttpResponse && !json.hasValidOptionalLong("forceVersion")) {
            return null
        }
        val status = json.booleanValue("status")
        if (!successfulHttpResponse) {
            return EndpointFields(
                status = status,
                customerInfo = null,
                offeringsJson = null,
                forceVersion = json.longValue("forceVersion"),
            )
        }

        // On HTTP 200, data is decoded independently of the nullable or false
        // status flag and becomes the synchronized local projection.
        val attributes = json.nonNullValue("data")
            ?: return EndpointFields(
                status = status,
                customerInfo = null,
                offeringsJson = null,
                forceVersion = json.longValue("forceVersion"),
                attributesJson = null,
            )
        if (!attributes.isJsonArray) return null
        if (attributes.asJsonArray.any { attribute ->
                !attribute.isJsonObject ||
                    !attribute.asJsonObject.hasValidOptionalString("key") ||
                    !attribute.asJsonObject.hasValidOptionalString("value")
            }
        ) {
            return null
        }
        return EndpointFields(
            status = status,
            customerInfo = null,
            offeringsJson = null,
            forceVersion = json.longValue("forceVersion"),
            attributesJson = gson.toJson(attributes.asJsonArray),
        )
    }

    /**
     * Attribute mutation endpoints treat every HTTP 200 as success without
     * decoding the response body or force-version metadata.
     */
    private fun decodeStatusCodeMutation(response: HttpResponse): ServiceResult {
        if (response.statusCode != HTTP_OK) {
            return decode(response, ResponseContract.LIFECYCLE)
        }
        return ServiceResult.Response(
            statusCode = HTTP_OK,
            payload = BackendResponse(
                status = true,
                message = null,
                errorCode = null,
                token = null,
                appUserIdentifier = null,
                customerInfoJson = null,
                storeInfo = null,
                appId = null,
                forceVersion = null,
            ),
            requestId = response.requestId,
        )
    }

    private fun malformedOrEmpty(response: HttpResponse): ServiceResult {
        return if (response.statusCode == HTTP_OK) {
            ServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
        } else {
            emptyResponse(response)
        }
    }

    private fun emptyResponse(response: HttpResponse): ServiceResult.Response =
        ServiceResult.Response(
            statusCode = response.statusCode,
            payload = BackendResponse(
                status = null,
                message = null,
                errorCode = null,
                token = null,
                appUserIdentifier = null,
                customerInfoJson = null,
                storeInfo = null,
                appId = null,
                forceVersion = null,
                offeringsJson = null,
            ),
            requestId = response.requestId,
        )

    private fun emptyStoreResponse(
        response: HttpResponse,
    ): StoreServiceResult.Response = StoreServiceResult.Response(
        statusCode = response.statusCode,
        payload = StoreBackendResponse(
            status = null,
            state = null,
            hasForceUpdate = null,
            forceVersion = null,
            errorCode = null,
            message = null,
        ),
        requestId = response.requestId,
        retryAfterSeconds = response.retryAfterSeconds(),
    )

    private fun storePath(relativePath: String): String =
        "${storeApiBaseUrl.trimEnd('/')}/$relativePath"

    private fun directPath(relativePath: String): String =
        "${directApiBaseUrl.trimEnd('/')}/$relativePath"

    private fun TransportFailureKind.toServiceFailureKind(): ServiceFailureKind = when (this) {
        TransportFailureKind.NETWORK -> ServiceFailureKind.NETWORK
        TransportFailureKind.TIMEOUT -> ServiceFailureKind.TIMEOUT
        TransportFailureKind.CANCELLED -> ServiceFailureKind.CANCELLED
        TransportFailureKind.MALFORMED_RESPONSE -> ServiceFailureKind.MALFORMED_RESPONSE
    }

    private fun JsonObject.addNullableString(name: String, value: String?) {
        add(name, value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    }

    private fun JsonObject.addNullableNumber(name: String, value: Number?) {
        add(name, value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    }

    private fun List<InappifyAttribute>.toValueArray(): JsonArray =
        JsonArray().also { target ->
            forEach { attribute ->
                target.add(
                    JsonObject().apply {
                        addNullableString("key", attribute.key)
                        addNullableString("value", attribute.value)
                    },
                )
            }
        }

    private fun List<InappifyAttribute>.toKeyArray(): JsonArray =
        JsonArray().also { target ->
            forEach { attribute ->
                target.add(
                    JsonObject().apply {
                        addNullableString("key", attribute.key)
                    },
                )
            }
        }

    private fun JsonObject.stringValue(name: String): String? =
        primitiveValue(name)
            ?.takeIf { it.isString }
            ?.asString

    private fun JsonObject.booleanValue(name: String): Boolean? =
        primitiveValue(name)
            ?.takeIf { it.isBoolean }
            ?.asBoolean

    private fun JsonObject.hasValidOptionalBoolean(name: String): Boolean {
        val element = nonNullValue(name) ?: return true
        return element.isJsonPrimitive && element.asJsonPrimitive.isBoolean
    }

    private fun JsonObject.hasValidOptionalString(name: String): Boolean {
        val element = nonNullValue(name) ?: return true
        return element.isJsonPrimitive && element.asJsonPrimitive.isString
    }

    /** Accepts named routes and non-negative backend enum values. */
    private fun JsonObject.hasValidOptionalStorePlatform(name: String): Boolean {
        val element = nonNullValue(name) ?: return true
        if (!element.isJsonPrimitive) return false
        val primitive = element.asJsonPrimitive
        return primitive.isString ||
            (primitive.isNumber && strictLongValue(name)?.let { it >= 0L } == true)
    }

    /**
     * Normalizes the backend AppStorePlatform enum emitted by the production
     * mobile endpoint. Unknown numeric values remain absent so older apps keep
     * their request-selected V1 route instead of guessing a marketplace.
     */
    private fun JsonObject.storePlatformValue(name: String): String? {
        val primitive = primitiveValue(name) ?: return null
        if (primitive.isString) return primitive.asString
        if (!primitive.isNumber) return null
        val wireValue = strictLongValue(name) ?: return null
        return AppStorePlatform.fromWireValue(wireValue)?.canonicalName
    }

    private fun JsonObject.hasValidOptionalObject(name: String): Boolean {
        val element = nonNullValue(name) ?: return true
        return element.isJsonObject
    }

    /** Truncates numeric values toward zero within the signed 64-bit bound. */
    private fun JsonObject.longValue(name: String): Long? =
        primitiveValue(name)
            ?.takeIf { it.isNumber }
            ?.let {
                try {
                    BigDecimal(it.asString).toBigInteger().toLongExact()
                } catch (_: ArithmeticException) {
                    null
                } catch (_: NumberFormatException) {
                    null
                }
            }

    private fun JsonObject.hasValidOptionalLong(name: String): Boolean {
        if (!hasNonNullValue(name)) return true
        return longValue(name) != null
    }

    private fun JsonObject.strictLongValue(name: String): Long? =
        primitiveValue(name)
            ?.takeIf { it.isNumber }
            ?.let {
                val raw = it.asString
                if (!JSON_INTEGER_PATTERN.matches(raw)) return@let null
                try {
                    BigDecimal(raw).longValueExact()
                } catch (_: ArithmeticException) {
                    null
                } catch (_: NumberFormatException) {
                    null
                }
            }

    private fun JsonObject.hasValidOptionalStrictLong(name: String): Boolean {
        if (!hasNonNullValue(name)) return true
        return strictLongValue(name) != null
    }

    private fun JsonObject.optionalObjectArray(name: String): JsonArray? {
        val element = nonNullValue(name) ?: return JsonArray()
        if (!element.isJsonArray) return null
        val array = element.asJsonArray
        if (array.any { !it.isJsonObject }) return null
        return array.deepCopy()
    }

    private fun JsonObject.nonNullValue(name: String): JsonElement? =
        get(name)?.takeUnless(JsonElement::isJsonNull)

    private fun JsonObject.hasNonNullValue(name: String): Boolean =
        nonNullValue(name) != null

    private fun JsonObject.objectValue(name: String): JsonObject? =
        get(name)
            ?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject

    private fun JsonObject.primitiveValue(name: String): JsonPrimitive? =
        get(name)
            ?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive

    private companion object {
        private const val HTTP_OK = 200
        private const val CONFIGURE_PATH = "configure"
        private const val LOGIN_PATH = "login"
        private const val LOGOUT_PATH = "logout"
        private const val CUSTOMER_INFO_PATH = "customerInfo"
        private const val OFFERINGS_PATH = "offerings"
        private const val VALIDATE_DISCOUNT_CODE_PATH = "validateDiscountCode"
        private const val STORE_ATTRIBUTES_PATH = "storeAttributes"
        private const val REMOVE_ATTRIBUTES_PATH = "removeAttributes"
        private const val STORE_RESERVED_ATTRIBUTE_PATH = "storeReservedAttribute"
        private const val SYNC_ATTRIBUTES_PATH = "syncAttributes"
        private const val PRODUCTION_PURCHASE_URL =
            "https://api.inappify.com/app/v1/purchase"
        private const val PRODUCTION_STORE_API_BASE_URL =
            "https://api.inappify.com/app/v2/store/"
        private const val PRODUCTION_DIRECT_API_BASE_URL =
            "https://api.inappify.com/app/v1/"
        private const val STORE_PURCHASES_PATH = "purchases"
        private const val MAX_RETRY_AFTER_SECONDS = 60L
        private val JSON_INTEGER_PATTERN = Regex("-?(?:0|[1-9][0-9]*)")
        private val PURCHASE_STRING_FIELDS = listOf(
            "url",
            "purchaseStatus",
            "checkoutId",
            "checkoutStatus",
            "nextActionType",
        )
        private val PURCHASE_STATUS_VALUES = setOf("DONE", "NEEDTOPAY")
        private val STORE_ENVELOPE_STRING_FIELDS = listOf(
            "message",
            "errorMessage",
            "errorCode",
            "code",
            "requestId",
        )
        private val STORE_STATE_ID_FIELDS = listOf(
            "paymentId",
            "eventId",
            "deliveryId",
            "verificationRequestId",
        )
        private val STORE_STATE_STRING_FIELDS = listOf(
            "errorCode",
            "message",
        )
    }

    /** Canonical backend enum mapping; only Android-supported routes are executed. */
    private enum class AppStorePlatform(
        val wireValue: Long,
        val canonicalName: String,
    ) {
        DIRECT_IOS(1L, "DirectIos"),
        DIRECT_ANDROID(2L, "DirectAndroid"),
        DIRECT_WEB(3L, "DirectWeb"),
        PLAY_STORE(5L, "PlayStore"),
        APP_STORE(6L, "AppStore"),
        BAZAR(10L, "Bazar"),
        MY_KET(11L, "MyKet"),
        SIB_APP(12L, "SibApp");

        companion object {
            private val byWireValue = values().associateBy(AppStorePlatform::wireValue)

            fun fromWireValue(value: Long): AppStorePlatform? = byWireValue[value]
        }
    }

    private enum class ResponseContract {
        LIFECYCLE,
        CUSTOMER_INFO,
        OFFERINGS,
        PURCHASE,
        DISCOUNT_CODE,
        STATUS_CODE_MUTATION,
        ATTRIBUTE_SYNC,
    }

    private enum class StoreResponseContract {
        INITIAL_PURCHASE,
        FLAT_STATE,
        TRANSITION_STATE,
    }

    private enum class ConsumableDeliveryResponseContract {
        PENDING,
        DELIVERED,
    }

    private class EndpointFields(
        val status: Boolean?,
        val customerInfo: JsonObject?,
        val offeringsJson: String?,
        val forceVersion: Long?,
        val purchase: BackendPurchase? = null,
        val discountCodeResultJson: String? = null,
        val attributesJson: String? = null,
    )
}
