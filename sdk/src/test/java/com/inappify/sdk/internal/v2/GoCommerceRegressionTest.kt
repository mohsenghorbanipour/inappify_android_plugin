package com.inappify.sdk.internal.v2

import com.google.gson.JsonObject
import com.inappify.sdk.*
import com.inappify.sdk.internal.DefaultInappifyClient
import com.inappify.sdk.internal.billing.*
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.*
import com.inappify.sdk.internal.storage.*
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GoCommerceRegressionTest {
    private val signing = SigningFixture()
    private val storage = CommerceStore()
    private val sdkTransport = DiagnosticTransport()
    private val commerceTransport = DiagnosticTransport()
    private val owned = mutableMapOf<StoreProductType, List<StorePurchase>>()
    private var billingCalls = 0
    private var billingKey: String? = null
    private var platform = 10
    private val metadata = AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) }
    private fun client(configuration: InappifyV2Configuration = signing.config): GoV2Client {
        sdkTransport.handler = { request -> when (request.path) {
            "configure", "customerInfo" -> sdkTransport.response(signing.envelope().apply {
                addProperty("storePlatform", platform)
                addProperty("storeInfo", "fixture-public-rsa")
            })
            "offerings" -> sdkTransport.response(jsonObject("""{"status":true,"offerings":[
                {"identifier":"first","packages":[{"identifier":"first-package","product":{"identifier":"product"}}]},
                {"identifier":"original","packages":[{"identifier":"package","product":{"identifier":"product"}}]}]}"""))
            else -> error("Unexpected SDK request ${request.path}")
        } }
        commerceTransport.handler = { request -> commerceTransport.response(jsonObject(
            if (request.path == "store/purchases") """{"status":true,"data":{"purchase":{"status":"COMPLETED"}}}"""
            else """{"status":true,"data":{"status":"COMPLETED"}}""")) }
        return GoV2Client(configuration, GoApi(sdkTransport, { signing.now }, {}), storage, metadata,
            { signing.now }, Dispatchers.Unconfined, backgroundRecovery = false,
            commerceApi = GoApi(commerceTransport, { signing.now }, {}),
            billingFactory = StoreBillingAdapterFactory { _, key ->
                billingCalls++
                billingKey = key
                object : StoreBillingAdapter {
                    override suspend fun purchase(uiHost: StoreUiHost, request: StorePurchaseRequest): StoreBillingResult =
                        error("A recovery test must never start Billing UI")
                    override suspend fun queryPurchases(productType: StoreProductType): StorePurchaseQueryResult =
                        StorePurchaseQueryResult.Success(owned[productType].orEmpty())
                    override fun close() = Unit
                }
            })
    }
    private suspend fun configured(sdk: GoV2Client) {
        assertTrue(sdk.configure(InappifyOptions("public-fixture-key", signing.subject)) is InappifyResult.Success)
        assertTrue(sdk.refreshOfferings() is InappifyResult.Success)
    }
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun pending(type: PendingStoreProductType = PendingStoreProductType.CONSUMABLE,
        phase: PendingStoreOperationPhase = PendingStoreOperationPhase.DELIVERY_REQUIRED): PendingStoreOperation =
        PendingStoreOperation("attempt-1", PendingStoreOperationType.PURCHASE, "bazar", "old-session-token",
            digest(signing.subject), storeFingerprint(), "com.example.mobile", 12,
            "product", "original", type, PendingStorePurchaseEvidence("fixture-store-token"),
            phase, deliveryId = if (phase == PendingStoreOperationPhase.DELIVERY_REQUIRED) 7 else null,
            createdAtEpochMillis = signing.now)
    private fun receipt(type: String = "NON_CONSUMABLE", offering: String = "original",
        product: String = "product", attempt: String = "attempt-1"): StorePurchase {
        val binding = digest("${storeFingerprint()}:${digest(signing.subject)}")
        val payload = JsonObject().apply {
            addProperty("productIdentifier", product); addProperty("offeringIdentifier", offering)
            addProperty("nativePackageIdentifier", "package"); addProperty("productType", type)
            addProperty("recoveryBinding", binding); addProperty("attemptId", attempt)
        }
        return StorePurchase("fixture-order", "fixture-store-token", payload.toString(), "com.example.mobile",
            "product", signing.now, "fixture-original", "fixture-signature")
    }
    private fun storeFingerprint() = goStoreFingerprint(storage.value!!.apiKeyFingerprint!!, signing.config.commerceApiBaseUrl)
    @Test fun changingCommerceEndpointKeepsSessionCacheButCannotReplayPaidEvidence() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending())
            owned[StoreProductType.IN_APP] = listOf(receipt(type = "CONSUMABLE"))
        }
        val originalCacheFingerprint = storage.value!!.apiKeyFingerprint
        val originalOperation = storage.operations.single()
        val otherCommerce = InappifyV2Configuration(sdkApiBaseUrl = signing.config.sdkApiBaseUrl,
            issuer = signing.config.issuer, appId = signing.config.appId, projectId = signing.config.projectId,
            pinnedSigningKeys = signing.config.pinnedSigningKeys, paymentHosts = signing.config.paymentHosts,
            commerceApiBaseUrl = "https://other-commerce.example/app/v2/")
        client(otherCommerce).use { sdk ->
            configured(sdk)
            val retry = sdk.purchase(InappifyPurchaseRequest("product", "original",
                idempotencyKey = "attempt-1", productType = InappifyProductType.CONSUMABLE)) as InappifyResult.Failure
            assertEquals("PURCHASE_ATTEMPT_CONFLICT", retry.error.details["serverCode"])
            val confirm = sdk.confirmDelivery(7) as InappifyResult.Failure
            assertEquals("DELIVERY_NOT_FOUND", confirm.error.details["serverCode"])
            val sync = sdk.syncPurchases() as InappifyResult.Success
            assertTrue(sync.data.isEmpty())
            assertTrue(commerceTransport.requests.isEmpty())
            assertEquals(originalOperation, storage.operations.single())
            assertEquals(originalCacheFingerprint, storage.value!!.apiKeyFingerprint)
        }
    }
    @Test fun existingBazaarRequestAcceptsExplicitMarketAndServerRsa() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending())
            val result = sdk.purchase(InappifyPurchaseRequest("product", "original",
                productType = InappifyProductType.CONSUMABLE, market = InappifyMarket.BAZAAR,
                idempotencyKey = "attempt-1"))
            assertTrue(result is InappifyResult.Success)
            assertEquals(InappifyStorePurchaseStatus.DELIVERY_REQUIRED,
                (result as InappifyResult.Success).data.storePurchaseStatus)
            assertEquals(0, billingCalls)
            assertTrue(commerceTransport.requests.isEmpty())
        }
    }
    @Test fun retryCannotChangeProductTypeOfAnExistingAttempt() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending())
            val result = sdk.purchase(InappifyPurchaseRequest("product", "original",
                productType = InappifyProductType.NON_CONSUMABLE, idempotencyKey = "attempt-1"))
            assertEquals("PURCHASE_ATTEMPT_CONFLICT", (result as InappifyResult.Failure).error.details["serverCode"])
            assertEquals(0, billingCalls)
            assertEquals(PendingStoreProductType.CONSUMABLE, storage.operations.single().productType)
        }
    }
    @Test fun paidAttemptResumesAfterConfigureInvalidatesCatalogAndLegacyTypeWasClassified() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending())
            assertTrue(sdk.configure(InappifyOptions("public-fixture-key", signing.subject, country = "US"))
                is InappifyResult.Success)
            assertNull(sdk.snapshot.offerings)
            val result = sdk.purchase(InappifyPurchaseRequest("product", "original", idempotencyKey = "attempt-1"))
            assertTrue(result is InappifyResult.Success)
            assertEquals(InappifyStorePurchaseStatus.DELIVERY_REQUIRED,
                (result as InappifyResult.Success).data.storePurchaseStatus)
            assertEquals(0, billingCalls)
        }
    }
    @Test fun unsupportedDirectDiscountCannotSilentlyCreateAFullPriceCheckout() = runBlocking {
        platform = 2
        client().use { sdk ->
            configured(sdk)
            assertTrue(sdk.purchase(InappifyPurchaseRequest("product", "original", discount = 20)) is InappifyResult.Failure)
            assertTrue(sdk.purchase(InappifyPurchaseRequest("product", "original", discountCode = "fixture-code")) is InappifyResult.Failure)
            assertTrue(commerceTransport.requests.isEmpty())
        }
    }
    @Test fun disabledNetworkDoesNotQueryStoreOrAcknowledgeDelivery() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending())
            sdk.setNetworkEnabled(false)
            assertTrue(sdk.syncPurchases() is InappifyResult.Failure)
            assertTrue(sdk.confirmDelivery(7) is InappifyResult.Failure)
            assertEquals(0, billingCalls)
            assertTrue(commerceTransport.requests.isEmpty())
            assertFalse(storage.operations.single().deliveryAcknowledged)
        }
    }
    @Test fun transportFailureRetainsAttemptAndCannotBeReportedAsPurchaseSuccess() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending(phase = PendingStoreOperationPhase.REGISTERING))
            commerceTransport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
            val result = sdk.purchase(InappifyPurchaseRequest("product", "original",
                productType = InappifyProductType.CONSUMABLE, idempotencyKey = "attempt-1"))
            assertTrue(result is InappifyResult.Failure)
            assertTrue((result as InappifyResult.Failure).error.isRetryable)
            assertEquals(true, result.error.details["outcomeMayHaveCommitted"])
            assertEquals("attempt-1", storage.operations.single().id)
            assertEquals(1, commerceTransport.requests.size)
            assertEquals("Bearer go-session-token", commerceTransport.requests.single().headers["Authorization"])
        }
    }
    @Test fun ownedRecoveryUsesOriginalOfferingAndCurrentTokenWithoutLegacyCredential() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            owned[StoreProductType.IN_APP] = listOf(receipt())
            val result = sdk.syncPurchases()
            assertTrue(result is InappifyResult.Success)
            val purchase = (result as InappifyResult.Success).data.single()
            assertEquals("original", purchase.offeringIdentifier)
            assertEquals("attempt-1", purchase.attemptId)
            val request = commerceTransport.requests.single()
            assertEquals("Bearer go-session-token", request.headers["Authorization"])
            assertEquals("original", jsonObject(request.jsonBody).string("offeringIdentifier"))
            assertFalse(jsonObject(request.jsonBody).has("apikey"))
            assertEquals("fixture-public-rsa", billingKey)
            assertTrue(storage.operations.isEmpty())
        }
    }
    @Test fun ownedRecoveryCannotInventAnotherOfferingOrChangeReceiptProduct() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            for (invalid in listOf(receipt(offering = ""), receipt(product = "other"))) {
                owned[StoreProductType.IN_APP] = listOf(invalid)
                assertTrue(sdk.syncPurchases() is InappifyResult.Success)
            }
            assertTrue(commerceTransport.requests.isEmpty())
            assertTrue(storage.operations.isEmpty())
        }
    }
    @Test fun validatedOwnedReceiptCanRecoverAfterCatalogWasInvalidated() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            owned[StoreProductType.IN_APP] = listOf(receipt(offering = "previous-offering"))
            assertTrue(sdk.configure(InappifyOptions("public-fixture-key", signing.subject, country = "US"))
                is InappifyResult.Success)
            assertNull(sdk.snapshot.offerings)
            assertTrue(sdk.syncPurchases() is InappifyResult.Success)
            assertEquals("previous-offering", jsonObject(commerceTransport.requests.single().jsonBody)
                .string("offeringIdentifier"))
        }
    }
    @Test fun subscriptionInventoryCannotBeReclassifiedAsConsumable() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            owned[StoreProductType.SUBSCRIPTION] = listOf(receipt(type = "CONSUMABLE"))
            assertTrue(sdk.syncPurchases() is InappifyResult.Success)
            assertTrue(commerceTransport.requests.isEmpty())
        }
    }
    @Test fun ownedReceiptsSharingAttemptCannotOverwriteOneAnothersPendingDelivery() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val first = receipt(type = "CONSUMABLE")
            val other = StorePurchase("other-order", "other-token", first.developerPayload,
                first.packageName, first.productIdentifier, first.purchaseTimeMillis, "other-original", "other-signature")
            owned[StoreProductType.IN_APP] = listOf(first, other)
            commerceTransport.handler = { commerceTransport.response(jsonObject(
                """{"status":true,"data":{"purchase":{"status":"DELIVERY_REQUIRED","deliveryId":7}}}""")) }
            assertTrue(sdk.syncPurchases() is InappifyResult.Success)
            assertEquals(1, commerceTransport.requests.size)
            assertEquals("fixture-store-token", storage.operations.single().evidence.purchaseToken)
        }
    }
    @Test fun explicitRestoreSkipsOwnedConsumables() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            owned[StoreProductType.IN_APP] = listOf(receipt(type = "CONSUMABLE"))
            assertTrue(sdk.restorePurchases() is InappifyResult.Success)
            assertTrue(commerceTransport.requests.isEmpty())
        }
    }
    @Test fun deliveryHandlerCancellationNeverAcknowledgesOrConsumes() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending())
            sdk.setConsumableDeliveryHandler { throw CancellationException("fixture cancellation") }
            try { sdk.syncPendingConsumables(); fail("Cancellation must propagate") }
            catch (_: CancellationException) { }
            assertTrue(commerceTransport.requests.isEmpty())
            assertFalse(storage.operations.single().deliveryAcknowledged)
        }
    }
    @Test fun failedAcknowledgementAfterHostDeliveryCannotBecomeSyncSuccess() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending())
            sdk.setConsumableDeliveryHandler { InappifyDeliveryResult.DELIVERED }
            commerceTransport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
            val result = sdk.syncPendingConsumables()
            assertTrue(result is InappifyResult.Failure)
            assertEquals(true, (result as InappifyResult.Failure).error.details["outcomeMayHaveCommitted"])
            assertTrue(storage.operations.single().deliveryAcknowledged)
            assertEquals(PendingStoreOperationPhase.DELIVERY_CONFIRMING, storage.operations.single().phase)
        }
    }
    @Test fun deferredVerificationCannotStarveAnotherPaidDelivery() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending(phase = PendingStoreOperationPhase.REGISTERING)
                .copy(id = "deferred-attempt", nextRetryAtEpochMillis = Long.MAX_VALUE))
            storage.upsertPendingStoreOperation(pending())
            var offeredDelivery: Long? = null
            sdk.setConsumableDeliveryHandler { delivery ->
                offeredDelivery = delivery.deliveryId
                InappifyDeliveryResult.RETRY_LATER
            }
            assertTrue(sdk.syncPendingConsumables() is InappifyResult.Failure)
            assertEquals(7L, offeredDelivery)
            assertEquals(2, storage.operations.size)
            assertTrue(commerceTransport.requests.isEmpty())
        }
    }
    @Test fun completedReconciliationsAreCountedInConsumableSync() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending().copy(
                phase = PendingStoreOperationPhase.CONSUME_RESULT_REPORTING,
                deliveryAcknowledged = true, consumeResult = PendingStoreConsumeResult.SUCCEEDED))
            val result = sdk.syncPendingConsumables() as InappifyResult.Success
            assertEquals(1, result.data.discoveredCount)
            assertEquals(1, result.data.completedCount)
        }
    }
    @Test fun httpListenerRegistersAndUnregistersBothTransports() {
        client().use { sdk ->
            val registration = sdk.addHttpTraceListener { }
            assertEquals(1, sdkTransport.listeners.size)
            assertEquals(1, commerceTransport.listeners.size)
            registration.close()
            assertTrue(sdkTransport.listeners.isEmpty())
            assertTrue(commerceTransport.listeners.isEmpty())
        }
    }
    @Test fun boundDirectDeliveryStillUsesItsMatchingLegacyCompanion() = runBlocking {
        platform = 2
        var confirmationToken: String? = null
        val legacyBase = LegacyPurchaseFixtureService()
        val service = object : InappifyService by legacyBase {
            override suspend fun markDirectConsumableDelivered(request: DirectConsumableDeliveryApiRequest): ConsumableDeliveriesServiceResult {
                confirmationToken = request.token
                return ConsumableDeliveriesServiceResult.Response(200, ConsumableDeliveriesBackendResponse(true,
                    listOf(BackendConsumableDelivery(request.deliveryId, StorePurchaseStatus.COMPLETED,
                        ConsumableDeliverySource.DIRECT, null, null, "product", true, null)), null, null), null)
            }
        }
        DefaultInappifyClient(service, MemoryV2Store(), metadata, "2.1.0").use { legacy ->
            assertTrue(legacy.configure(InappifyOptions("legacy-fixture-key", signing.subject)) is InappifyResult.Success)
            client().use { sdk ->
                configured(sdk)
                assertTrue(sdk.bindLegacyPurchaseClient(legacy) is InappifyResult.Success)
                assertTrue(sdk.purchase(InappifyPurchaseRequest("product", "default")) is InappifyResult.Success)
                assertEquals("legacy-token-A", legacyBase.lastPurchase!!.token)
                val result = sdk.confirmDelivery(7)
                assertTrue(result is InappifyResult.Success)
                assertEquals("legacy-token-A", confirmationToken)
                assertTrue(commerceTransport.requests.isEmpty())
            }
        }
    }
    @Test fun explicitlyBoundBazaarPreservesLegacyPaidAttemptAndDeliveryRoute() = runBlocking {
        var confirmedWithToken: String? = null
        val legacyStorage = CommerceStore()
        val base = LegacyPurchaseFixtureService()
        fun response() = ServiceResult.Response(200, BackendResponse(true, null, null, "legacy-token-A",
            signing.subject, """{"originalAppUserId":"${signing.subject}"}""", "fixture-public-rsa", 12, 4,
            offeringsJson = base.offerings, storePlatform = "Bazar"), null)
        val service = object : InappifyService by base {
            override suspend fun configure(request: ConfigureApiRequest): ServiceResult = response()
            override suspend fun getCustomerInfo(request: ResourceApiRequest): ServiceResult = response()
            override suspend fun getOfferings(request: ResourceApiRequest): ServiceResult = response()
            override suspend fun markStoreDeliveryDelivered(request: StoreDeliveryApiRequest): StoreServiceResult {
                confirmedWithToken = request.token
                return StoreServiceResult.Failure(ServiceFailureKind.NETWORK)
            }
        }
        DefaultInappifyClient(service, legacyStorage, metadata, "2.1.0").use { legacy ->
            assertTrue(legacy.configure(InappifyOptions("legacy-fixture-key", signing.subject,
                market = InappifyMarket.BAZAAR, marketKey = "fixture-public-rsa")) is InappifyResult.Success)
            client().use { sdk ->
                configured(sdk)
                legacyStorage.upsertPendingStoreOperation(pending().copy(
                    apiKeyFingerprint = legacyStorage.value!!.apiKeyFingerprint!!,
                    offeringIdentifier = "default"))
                assertTrue(sdk.bindLegacyPurchaseClient(legacy) is InappifyResult.Success)
                assertTrue(sdk.purchase(InappifyPurchaseRequest("product", "default",
                    productType = InappifyProductType.CONSUMABLE, idempotencyKey = "attempt-1")) is InappifyResult.Success)
                assertTrue(sdk.confirmDelivery(7) is InappifyResult.Failure)
                assertEquals("legacy-token-A", confirmedWithToken)
                assertTrue(commerceTransport.requests.isEmpty())
                assertTrue(storage.operations.isEmpty())
            }
        }
    }
    @Test fun cannotSwitchToLegacyRouteWhileGoPurchaseIsPending() = runBlocking {
        platform = 2
        DefaultInappifyClient(LegacyPurchaseFixtureService(), MemoryV2Store(), metadata, "2.1.0").use { legacy ->
            assertTrue(legacy.configure(InappifyOptions("legacy-fixture-key", signing.subject)) is InappifyResult.Success)
            client().use { sdk ->
                configured(sdk)
                storage.upsertPendingStoreOperation(pending())
                val result = sdk.bindLegacyPurchaseClient(legacy) as InappifyResult.Failure
                assertEquals("GO_PURCHASE_RECOVERY_REQUIRED", result.error.details["serverCode"])
                assertEquals(1, storage.operations.size)
            }
        }
    }
    @Test fun identityChangeCannotSilentlyMoveBoundLegacyPaymentsToGoCommerce() = runBlocking {
        platform = 2
        DefaultInappifyClient(LegacyPurchaseFixtureService(), MemoryV2Store(), metadata, "2.1.0").use { legacy ->
            assertTrue(legacy.configure(InappifyOptions("legacy-fixture-key", signing.subject)) is InappifyResult.Success)
            client().use { sdk ->
                configured(sdk)
                assertTrue(sdk.bindLegacyPurchaseClient(legacy) is InappifyResult.Success)
                val oldHandler = sdkTransport.handler
                sdkTransport.handler = { request -> if (request.path == "login")
                    sdkTransport.response(signing.envelope("customer_other_123456")) else oldHandler(request) }
                assertTrue(sdk.login(InappifyLoginRequest("public-fixture-key", "customer_other_123456")) is InappifyResult.Success)
                val results = listOf(sdk.purchase(InappifyPurchaseRequest("product", "original")),
                    sdk.confirmDelivery(7), sdk.syncPurchases(), sdk.restorePurchases(), sdk.syncPendingConsumables())
                results.forEach { result ->
                    assertEquals("LEGACY_PURCHASE_REBIND_REQUIRED", (result as InappifyResult.Failure).error.details["serverCode"])
                }
                assertTrue(commerceTransport.requests.isEmpty())
            }
        }
    }

    private class CommerceStore : SessionStateStore {
        var value: PersistedSession? = null
        val operations = mutableListOf<PendingStoreOperation>()
        override suspend fun load(): PersistedSession? = value
        override suspend fun save(session: PersistedSession): Boolean { value = session; return true }
        override suspend fun clear(): Boolean { value = null; return true }
        override suspend fun loadPendingStoreOperations() = operations.toList()
        override suspend fun upsertPendingStoreOperation(operation: PendingStoreOperation): Boolean {
            operations.removeAll { it.id == operation.id }; operations += operation; return true
        }
        override suspend fun removePendingStoreOperation(operationId: String): Boolean =
            true.also { operations.removeAll { it.id == operationId } }
    }
    private class DiagnosticTransport : HttpTransport, HttpDiagnosticSource {
        val requests = mutableListOf<HttpRequest>()
        val listeners = mutableSetOf<InappifyHttpTraceListener>()
        lateinit var handler: suspend (HttpRequest) -> TransportResult
        override suspend fun execute(request: HttpRequest): TransportResult { requests += request; return handler(request) }
        override fun close() = Unit
        override fun addHttpTraceListener(listener: InappifyHttpTraceListener): InappifyListenerRegistration {
            listeners += listener
            return InappifyListenerRegistration.create(Runnable { listeners -= listener })
        }
        fun response(body: JsonObject): TransportResult = TransportResult.Response(HttpResponse(200, body.toString(), null))
    }
}
