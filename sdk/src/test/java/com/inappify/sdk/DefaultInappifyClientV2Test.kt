package com.inappify.sdk

import android.app.Activity
import com.google.gson.JsonParser
import com.inappify.sdk.internal.DefaultInappifyClient
import com.inappify.sdk.internal.billing.PartialStorePurchaseQueryAdapter
import com.inappify.sdk.internal.billing.StoreBillingAdapter
import com.inappify.sdk.internal.billing.StoreBillingAdapterFactory
import com.inappify.sdk.internal.billing.StoreBillingResult
import com.inappify.sdk.internal.billing.StoreConsumeResult as BillingConsumeResult
import com.inappify.sdk.internal.billing.StoreProductType
import com.inappify.sdk.internal.billing.StorePurchase
import com.inappify.sdk.internal.billing.StorePurchaseQueryMode
import com.inappify.sdk.internal.billing.StorePurchaseQueryResult
import com.inappify.sdk.internal.billing.StorePurchaseRequest
import com.inappify.sdk.internal.billing.StoreUiHost
import com.inappify.sdk.internal.network.BackendPurchase
import com.inappify.sdk.internal.network.BackendResponse
import com.inappify.sdk.internal.network.BackendConsumableDelivery
import com.inappify.sdk.internal.network.ConfigureApiRequest
import com.inappify.sdk.internal.network.ConsumableDeliveriesBackendResponse
import com.inappify.sdk.internal.network.ConsumableDeliveriesServiceResult
import com.inappify.sdk.internal.network.ConsumableDeliverySource
import com.inappify.sdk.internal.network.DirectConsumableDeliveryApiRequest
import com.inappify.sdk.internal.network.InappifyService
import com.inappify.sdk.internal.network.LoginApiRequest
import com.inappify.sdk.internal.network.LogoutApiRequest
import com.inappify.sdk.internal.network.PurchaseApiRequest
import com.inappify.sdk.internal.network.PendingConsumableDeliveriesApiRequest
import com.inappify.sdk.internal.network.RefreshSessionApiRequest
import com.inappify.sdk.internal.network.ResourceApiRequest
import com.inappify.sdk.internal.network.ServiceFailureKind
import com.inappify.sdk.internal.network.ServiceResult
import com.inappify.sdk.internal.network.StoreBackendResponse
import com.inappify.sdk.internal.network.StoreConsumeResultApiRequest
import com.inappify.sdk.internal.network.StoreDeliveryApiRequest
import com.inappify.sdk.internal.network.StorePurchaseApiRequest
import com.inappify.sdk.internal.network.StorePurchaseOperation
import com.inappify.sdk.internal.network.StorePurchaseState
import com.inappify.sdk.internal.network.StorePurchaseStatus
import com.inappify.sdk.internal.network.StoreServiceResult
import com.inappify.sdk.internal.platform.AppMetadata
import com.inappify.sdk.internal.platform.AppMetadataProvider
import com.inappify.sdk.internal.storage.PendingStoreOperation
import com.inappify.sdk.internal.storage.PendingStoreOperationCodec
import com.inappify.sdk.internal.storage.PendingStoreOperationPhase
import com.inappify.sdk.internal.storage.PendingStoreProductType
import com.inappify.sdk.internal.storage.PendingStoreRecoveryState
import com.inappify.sdk.internal.storage.PersistedSession
import com.inappify.sdk.internal.storage.RejectedStoreEvidenceTombstone
import com.inappify.sdk.internal.storage.SessionStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Integration coverage for V1-compatible routing into the V2 store coordinator. */
class DefaultInappifyClientV2Test {

    @Test
    fun missingStorePlatform_commitsDirectConfigurationAndAuthentication() = runBlocking {
        val harness = Harness(storePlatform = null)
        try {
            val result = harness.client.configure(
                InappifyOptions(
                    apiKey = "api-key",
                    appUserIdentifier = "customer-1",
                    market = InappifyMarket.NONE,
                    marketKey = "configured-market-key",
                ),
            ) as InappifyResult.Success

            assertTrue(result.snapshot.isConfigured)
            assertTrue(result.snapshot.isAuthenticated)
            assertEquals("customer-1", result.snapshot.appUserIdentifier)
            assertEquals(InappifyMarket.NONE, result.snapshot.market)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun missingStorePlatform_keepsBazaarRequestOnLegacyPurchaseContract() = runBlocking {
        val harness = configuredHarness(storePlatform = null)
        try {
            val result = harness.client.purchase(
                Activity(),
                purchaseRequest(market = InappifyMarket.BAZAAR),
            ) as InappifyResult.Success<InappifyPurchase>

            assertEquals(InappifyPurchaseStatus.DONE, result.data.purchaseStatus)
            assertEquals(InappifyMarket.BAZAAR, result.data.market)
            assertEquals(1, harness.billing.purchaseCalls)
            assertEquals(1, harness.service.legacyPurchaseCalls)
            assertEquals(0, harness.service.storePurchaseCalls)
            assertNotNull(harness.service.lastLegacyPurchase?.purchaseTokenId)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun missingStorePlatform_rejectsConsumableBeforeOpeningLegacyCheckout() = runBlocking {
        val harness = configuredHarness(storePlatform = null)
        try {
            val result = harness.client.purchase(
                Activity(),
                purchaseRequest(
                    market = InappifyMarket.BAZAAR,
                    productType = InappifyProductType.CONSUMABLE,
                ),
            ) as InappifyResult.Failure

            assertEquals(InappifyErrorCode.UNSUPPORTED_OPERATION, result.error.code)
            assertEquals(false, result.error.details["outcomeMayHaveCommitted"])
            assertEquals("missing", result.error.details["storePlatform"])
            assertEquals(0, harness.billing.purchaseCalls)
            assertEquals(0, harness.service.legacyPurchaseCalls)
            assertEquals(0, harness.service.storePurchaseCalls)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun directAndroid_isAuthoritativeAndNeverOpensBazaarBilling() = runBlocking {
        val harness = configuredHarness(storePlatform = "DirectAndroid")
        try {
            val result = harness.client.purchase(
                purchaseRequest(market = InappifyMarket.BAZAAR, productType = InappifyProductType.NON_CONSUMABLE),
            ) as InappifyResult.Success<InappifyPurchase>

            assertEquals(InappifyPurchaseStatus.DONE, result.data.purchaseStatus)
            assertEquals(InappifyMarket.NONE, result.data.market)
            assertEquals(0, harness.factory.createCalls)
            assertEquals(0, harness.billing.purchaseCalls)
            assertEquals(1, harness.service.legacyPurchaseCalls)
            assertEquals(0, harness.service.storePurchaseCalls)
            assertEquals(null, harness.service.lastLegacyPurchase?.purchaseTokenId)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun directAndroid_consumableUsesV1CheckoutWithoutStoreConsumption() = runBlocking {
        val harness = configuredHarness(storePlatform = "DirectAndroid")
        try {
            val result = harness.client.purchase(
                purchaseRequest(
                    market = InappifyMarket.BAZAAR,
                    productType = InappifyProductType.CONSUMABLE,
                ),
            ) as InappifyResult.Success<InappifyPurchase>

            assertEquals(InappifyPurchaseStatus.DONE, result.data.purchaseStatus)
            assertEquals(InappifyMarket.NONE, result.data.market)
            assertEquals(1, harness.service.legacyPurchaseCalls)
            assertEquals(0, harness.service.storePurchaseCalls)
            assertEquals(0, harness.billing.purchaseCalls)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun directAndroidSync_ignoresLegacyBazaarFallbackAndReturnsEmpty() = runBlocking {
        val harness = Harness(storePlatform = "DirectAndroid")
        try {
            val configured = harness.client.configure(
                InappifyOptions(
                    apiKey = "api-key",
                    appUserIdentifier = "customer-1",
                    market = InappifyMarket.BAZAAR,
                    marketKey = "configured-market-key",
                ),
            )
            assertTrue(configured is InappifyResult.Success)
            harness.resetObservations()

            val synced = harness.client.syncPurchases()
                as InappifyResult.Success<List<InappifyPurchase>>
            val restored = harness.client.restorePurchases()
                as InappifyResult.Success<InappifyRestoreResult>

            assertTrue(synced.data.isEmpty())
            assertEquals(0, restored.data.restoredCount)
            assertEquals(0, restored.data.alreadyProcessedCount)
            assertEquals(0, restored.data.failedCount)
            assertEquals(0, harness.factory.createCalls)
            assertEquals(0, harness.billing.queryCalls)
            assertEquals(0, harness.service.legacyPurchaseCalls)
            assertEquals(0, harness.service.storePurchaseCalls)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun directConsumableSync_deliversAndAcknowledgesExactlyOnce() = runBlocking {
        val harness = configuredHarness(storePlatform = "DirectAndroid")
        harness.service.pendingDirectDeliveries += directDelivery(701L)
        var grants = 0
        val registration = harness.client.setConsumableDeliveryHandler { delivery ->
            assertEquals(701L, delivery.deliveryId)
            assertEquals("premium-product", delivery.productIdentifier)
            assertEquals(InappifyDeliverySource.DIRECT, delivery.source)
            grants += 1
            InappifyDeliveryResult.DELIVERED
        }
        try {
            val first = harness.client.syncPendingConsumables()
                as InappifyResult.Success<InappifyConsumableSyncResult>
            val second = harness.client.syncPendingConsumables()
                as InappifyResult.Success<InappifyConsumableSyncResult>

            assertEquals(1, first.data.discoveredCount)
            assertEquals(1, first.data.completedCount)
            assertTrue(first.data.pendingDeliveries.isEmpty())
            assertEquals(0, second.data.discoveredCount)
            assertEquals(1, grants)
            assertEquals(listOf(701L), harness.service.directDeliveryConfirmations)
            assertEquals(0, harness.billing.purchaseCalls)
            assertEquals(0, harness.service.storePurchaseCalls)
        } finally {
            registration.close()
            harness.client.close()
        }
    }

    @Test
    fun handlerRegisteredBeforeConfigure_reconcilesDirectCustomerQueue() = runBlocking {
        val harness = Harness(storePlatform = "DirectAndroid")
        harness.service.pendingDirectDeliveries += directDelivery(706L)
        var grants = 0
        val registration = harness.client.setConsumableDeliveryHandler {
            grants += 1
            InappifyDeliveryResult.DELIVERED
        }
        try {
            val configured = harness.client.configure(
                InappifyOptions(
                    apiKey = "api-key",
                    appUserIdentifier = "customer-1",
                    market = InappifyMarket.NONE,
                ),
            )

            assertTrue(configured is InappifyResult.Success)
            assertEquals(1, grants)
            assertEquals(listOf(706L), harness.service.directDeliveryConfirmations)
            assertTrue(harness.service.pendingDirectDeliveries.isEmpty())
        } finally {
            registration.close()
            harness.client.close()
        }
    }

    @Test
    fun concurrentDirectSyncs_doNotInvokeDeliveryHandlerTwice() = runBlocking {
        val harness = configuredHarness(storePlatform = "DirectAndroid")
        harness.service.pendingDirectDeliveries += directDelivery(702L)
        var grants = 0
        val registration = harness.client.setConsumableDeliveryHandler {
            grants += 1
            InappifyDeliveryResult.DELIVERED
        }
        try {
            val first = async { harness.client.syncPendingConsumables() }
            val second = async { harness.client.syncPendingConsumables() }

            assertTrue(first.await() is InappifyResult.Success)
            assertTrue(second.await() is InappifyResult.Success)
            assertEquals(1, grants)
            assertEquals(listOf(702L), harness.service.directDeliveryConfirmations)
        } finally {
            registration.close()
            harness.client.close()
        }
    }

    @Test
    fun directConsumableSync_retryLaterDoesNotAcknowledge() = runBlocking {
        val harness = configuredHarness(storePlatform = "DirectAndroid")
        harness.service.pendingDirectDeliveries += directDelivery(703L)
        val registration = harness.client.setConsumableDeliveryHandler {
            InappifyDeliveryResult.RETRY_LATER
        }
        try {
            val result = harness.client.syncPendingConsumables()
                as InappifyResult.Success<InappifyConsumableSyncResult>

            assertEquals(1, result.data.discoveredCount)
            assertEquals(0, result.data.completedCount)
            assertEquals(703L, result.data.pendingDeliveries.single().deliveryId)
            assertTrue(harness.service.directDeliveryConfirmations.isEmpty())
        } finally {
            registration.close()
            harness.client.close()
        }
    }

    @Test
    fun directConsumableSync_drainsMoreThanOneHundredDeliveries() = runBlocking {
        val harness = configuredHarness(storePlatform = "DirectAndroid")
        harness.service.pendingDirectDeliveries += (1L..101L).map(::directDelivery)
        val registration = harness.client.setConsumableDeliveryHandler {
            InappifyDeliveryResult.DELIVERED
        }
        try {
            val result = harness.client.syncPendingConsumables()
                as InappifyResult.Success<InappifyConsumableSyncResult>

            assertEquals(101, result.data.discoveredCount)
            assertEquals(101, result.data.completedCount)
            assertTrue(result.data.pendingDeliveries.isEmpty())
            assertEquals(101, harness.service.directDeliveryConfirmations.size)
            assertTrue(harness.service.pendingDirectDeliveries.isEmpty())
        } finally {
            registration.close()
            harness.client.close()
        }
    }

    @Test
    fun directConsumableSync_rejectsIncompatibleSourceOrStatus() = runBlocking {
        listOf(
            directDelivery(704L, source = ConsumableDeliverySource.BAZAAR),
            directDelivery(705L, status = StorePurchaseStatus.CONSUME_REQUIRED),
        ).forEach { incompatible ->
            val harness = configuredHarness(storePlatform = "DirectAndroid")
            harness.service.pendingDirectDeliveries += incompatible
            var grants = 0
            val registration = harness.client.setConsumableDeliveryHandler {
                grants += 1
                InappifyDeliveryResult.DELIVERED
            }
            try {
                val result = harness.client.syncPendingConsumables()
                    as InappifyResult.Failure

                assertEquals(InappifyErrorCode.MALFORMED_RESPONSE, result.error.code)
                assertEquals(0, grants)
                assertTrue(harness.service.directDeliveryConfirmations.isEmpty())
            } finally {
                registration.close()
                harness.client.close()
            }
        }
    }

    @Test
    fun directConsumableSync_retriesTransientFailureButNotValidationError() = runBlocking {
        val transientHarness = configuredHarness(storePlatform = "DirectAndroid")
        transientHarness.service.pendingDirectTransientFailures = 2
        try {
            val recovered = transientHarness.client.syncPendingConsumables()

            assertTrue(recovered is InappifyResult.Success)
            assertEquals(3, transientHarness.service.pendingDirectCalls)
        } finally {
            transientHarness.client.close()
        }

        val validationHarness = configuredHarness(storePlatform = "DirectAndroid")
        validationHarness.service.pendingDirectHttpError = 422
        try {
            val rejected = validationHarness.client.syncPendingConsumables()
                as InappifyResult.Failure

            assertEquals(InappifyErrorCode.INVALID_CONFIGURATION, rejected.error.code)
            assertEquals(1, validationHarness.service.pendingDirectCalls)
        } finally {
            validationHarness.client.close()
        }
    }

    @Test
    fun bazaarPlatform_usesV2AndNeverCallsLegacyPurchase() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        try {
            val result = harness.client.purchase(
                Activity(),
                purchaseRequest(market = InappifyMarket.NONE, productType = InappifyProductType.NON_CONSUMABLE),
            ) as InappifyResult.Success<InappifyPurchase>

            assertEquals(InappifyPurchaseStatus.DONE, result.data.purchaseStatus)
            assertEquals(
                InappifyStorePurchaseStatus.COMPLETED,
                result.data.storePurchaseStatus,
            )
            assertEquals(InappifyMarket.BAZAAR, result.data.market)
            assertEquals(1, harness.billing.purchaseCalls)
            assertEquals(1, harness.service.storePurchaseCalls)
            assertEquals(0, harness.service.legacyPurchaseCalls)
            assertEquals(
                "store-token",
                harness.service.lastStorePurchase?.purchase?.token,
            )
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun bazaarLostPurchaseRecovery_usesV2WithoutOpeningBillingUi() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        try {
            val result = harness.client.purchase(
                InappifyPurchaseRequest(
                    productIdentifier = "premium-product",
                    offeringIdentifier = "main",
                    packageIdentifier = "monthly",
                    market = InappifyMarket.NONE,
                    isLostPurchase = true,
                    lostPurchaseToken = "lost-store-token",
                    lostPurchaseTime = 1_725_000_000_001L,
                    idempotencyKey = "lost-integration-attempt",
                    productType = InappifyProductType.NON_CONSUMABLE,
                ),
            ) as InappifyResult.Success<InappifyPurchase>

            assertEquals(InappifyPurchaseStatus.DONE, result.data.purchaseStatus)
            assertEquals(
                InappifyStorePurchaseStatus.COMPLETED,
                result.data.storePurchaseStatus,
            )
            assertEquals(0, harness.factory.createCalls)
            assertEquals(0, harness.billing.purchaseCalls)
            assertEquals(0, harness.service.legacyPurchaseCalls)
            assertEquals(1, harness.service.storePurchaseCalls)
            assertEquals(
                StorePurchaseOperation.PURCHASE,
                harness.service.lastStorePurchase?.operation,
            )
            assertEquals(
                "lost-store-token",
                harness.service.lastStorePurchase?.purchase?.token,
            )
            assertEquals(
                1_725_000_000_001L,
                harness.service.lastStorePurchase?.purchase?.purchaseTime,
            )
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun paidStoreCallback_followedByNetworkFailureRemainsDurable() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        harness.service.storePurchaseResult = StoreServiceResult.Failure(
            ServiceFailureKind.NETWORK,
        )
        try {
            val result = harness.client.purchase(
                Activity(),
                purchaseRequest(market = InappifyMarket.BAZAAR),
            ) as InappifyResult.Failure

            assertEquals(InappifyErrorCode.NETWORK, result.error.code)
            assertEquals(1, harness.billing.purchaseCalls)
            assertEquals(1, harness.service.storePurchaseCalls)
            assertEquals(0, harness.service.legacyPurchaseCalls)
            assertEquals(1, harness.store.pending.size)
            assertEquals(
                PendingStoreOperationPhase.REGISTERING,
                harness.store.pending.single().phase,
            )
            assertEquals("store-token", harness.store.pending.single().evidence.purchaseToken)
            assertTrue(result.error.isRetryable)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun v1DirectSelection_doesNotOpenBazaarEvenWhenServerPlatformIsBazaar() = runBlocking {
        listOf(false, true).forEach { withActivity ->
            val harness = configuredHarness("Bazar")
            try {
                val request = purchaseRequest(InappifyMarket.NONE).withPaywallAttribution(12, 34)
                assertEquals(null, request.productType)
                val result = (if (withActivity) harness.client.purchase(Activity(), request)
                    else harness.client.purchase(request)) as InappifyResult.Success<InappifyPurchase>
                assertEquals(InappifyMarket.NONE, result.data.market)
                assertEquals(InappifyPurchaseStatus.DONE, result.data.purchaseStatus)
                assertEquals(null, result.data.storePurchaseStatus)
                assertEquals(0, harness.billing.purchaseCalls)
                assertEquals(1, harness.service.legacyPurchaseCalls)
                assertEquals(0, harness.service.storePurchaseCalls)
            } finally { harness.client.close() }
        }
    }

    @Test
    fun v1BazaarSelection_isNotSilentlyChangedToDirect() = runBlocking {
        val harness = configuredHarness("DirectAndroid")
        try {
            val result = harness.client.purchase(Activity(), purchaseRequest(InappifyMarket.BAZAAR))
                as InappifyResult.Success<InappifyPurchase>
            assertEquals(InappifyMarket.BAZAAR, result.data.market)
            assertEquals(InappifyPurchaseStatus.DONE, result.data.purchaseStatus)
            assertEquals(1, harness.billing.purchaseCalls)
            assertEquals(1, harness.service.legacyPurchaseCalls)
            assertEquals(0, harness.service.storePurchaseCalls)
            assertEquals("store-token", harness.service.lastLegacyPurchase?.purchaseTokenId)
        } finally { harness.client.close() }
    }

    @Test
    fun goPurchaseCompanion_keepsServerRoutingForUntypedRequest() = runBlocking {
        val harness = configuredHarness("Bazar")
        val companion = harness.client.createPurchaseCompanion()
        try {
            val request = purchaseRequest(InappifyMarket.NONE)
            val result = companion.purchase(Activity(), request) as InappifyResult.Success<InappifyPurchase>
            assertEquals(InappifyMarket.BAZAAR, result.data.market)
            assertEquals(1, harness.billing.purchaseCalls)
            assertEquals(1, harness.service.storePurchaseCalls)
            assertEquals(0, harness.service.legacyPurchaseCalls)
            // Creating the companion must not change the original V1 client.
            val legacy = harness.client.purchase(request) as InappifyResult.Success<InappifyPurchase>
            assertEquals(InappifyMarket.NONE, legacy.data.market)
            assertEquals(1, harness.billing.purchaseCalls)
            assertEquals(1, harness.service.legacyPurchaseCalls)
        } finally { companion.close(); harness.client.close() }
    }

    @Test
    fun v1PendingRetry_rejectsDifferentProductTypeEvenWithInvalidatedCatalog() = runBlocking {
        val harness = configuredHarness("Bazar")
        harness.service.storePurchaseResult = storeResponse(StorePurchaseStatus.DELIVERY_REQUIRED, deliveryId = 905L)
        try {
            assertTrue(harness.client.purchase(Activity(), purchaseRequest(InappifyMarket.BAZAAR)) is InappifyResult.Success)
            assertEquals(null, harness.client.snapshot.offerings)
            val mismatched = harness.client.purchase(Activity(), purchaseRequest(InappifyMarket.BAZAAR,
                InappifyProductType.SUBSCRIPTION)) as InappifyResult.Failure
            assertEquals(InappifyErrorCode.INVALID_CONFIGURATION, mismatched.error.code)
            assertEquals(1, harness.billing.purchaseCalls)
            assertEquals(1, harness.service.storePurchaseCalls)
            assertEquals(905L, harness.store.pending.single().deliveryId)
        } finally { harness.client.close() }
    }

    @Test
    fun v1UntypedConsumable_acceptsDeliveryAndRetriesWithoutRepurchasing() = runBlocking {
        val harness = configuredHarness("Bazar")
        harness.service.storePurchaseResult = storeResponse(StorePurchaseStatus.DELIVERY_REQUIRED, deliveryId = 901L)
        val request = purchaseRequest(InappifyMarket.BAZAAR)
        try {
            repeat(2) {
                val outcome = harness.client.purchase(Activity(), request)
                assertTrue("retry=$it result=$outcome", outcome is InappifyResult.Success)
                val result = outcome as InappifyResult.Success<InappifyPurchase>
                assertEquals(InappifyStorePurchaseStatus.DELIVERY_REQUIRED, result.data.storePurchaseStatus)
                assertEquals(null, result.data.purchaseStatus)
                assertEquals(901L, result.data.deliveryId)
            }
            assertEquals(1, harness.billing.purchaseCalls)
            assertEquals(1, harness.service.storePurchaseCalls)
            assertEquals(0, harness.billing.consumeCalls)
            assertTrue(harness.service.storeDeliveryConfirmations.isEmpty())
            assertEquals(PendingStoreProductType.CONSUMABLE, harness.store.pending.single().productType)
            val payload = JsonParser.parseString(harness.billing.lastRequest?.developerPayload).asJsonObject
            assertFalse(payload.has("productType"))
            assertEquals(StoreProductType.IN_APP, harness.billing.lastRequest?.productType)

            // Simulate a host that durably granted the content before ACK.
            val confirmed = harness.client.confirmDelivery(901L) as InappifyResult.Success<InappifyPurchase>
            assertEquals(InappifyPurchaseStatus.DONE, confirmed.data.purchaseStatus)
            assertEquals(listOf(901L), harness.service.storeDeliveryConfirmations)
            assertEquals(listOf(901L), harness.service.storeConsumeReports)
            assertEquals(1, harness.billing.consumeCalls)
            assertTrue(harness.store.pending.isEmpty())
        } finally { harness.client.close() }
    }

    @Test
    fun v1UntypedConsumable_registeredHandlerRunsOnlyAfterVerifiedDelivery() = runBlocking {
        val harness = configuredHarness("Bazar")
        harness.service.storePurchaseResult = storeResponse(StorePurchaseStatus.DELIVERY_REQUIRED, deliveryId = 902L)
        var grants = 0
        val registration = harness.client.setConsumableDeliveryHandler { delivery ->
            assertEquals(902L, delivery.deliveryId)
            assertTrue(harness.service.storeDeliveryConfirmations.isEmpty())
            assertEquals(0, harness.billing.consumeCalls)
            grants += 1
            InappifyDeliveryResult.DELIVERED
        }
        try {
            val result = harness.client.purchase(Activity(), purchaseRequest(InappifyMarket.BAZAAR))
                as InappifyResult.Success<InappifyPurchase>
            assertEquals(InappifyStorePurchaseStatus.COMPLETED, result.data.storePurchaseStatus)
            assertEquals(1, grants)
            assertEquals(1, harness.billing.consumeCalls)
            assertEquals(listOf(902L), harness.service.storeDeliveryConfirmations)
            assertEquals(listOf(902L), harness.service.storeConsumeReports)
            harness.client.syncPendingConsumables()
            assertEquals(1, grants)
            assertEquals(1, harness.billing.consumeCalls)
        } finally { registration.close(); harness.client.close() }
    }

    @Test
    fun v1UntypedNonConsumable_stillReturnsDoneWithoutConsumption() = runBlocking {
        val harness = configuredHarness("Bazar")
        try {
            val result = harness.client.purchase(Activity(), purchaseRequest(InappifyMarket.BAZAAR))
                as InappifyResult.Success<InappifyPurchase>
            assertEquals(InappifyPurchaseStatus.DONE, result.data.purchaseStatus)
            assertEquals(InappifyStorePurchaseStatus.COMPLETED, result.data.storePurchaseStatus)
            assertEquals(0, harness.billing.consumeCalls)
            assertTrue(harness.service.storeDeliveryConfirmations.isEmpty())
            assertTrue(harness.store.pending.isEmpty())
        } finally { harness.client.close() }
    }

    @Test
    fun explicitNonConsumableAndSubscription_stillRejectConsumableDelivery() = runBlocking {
        listOf(InappifyProductType.NON_CONSUMABLE, InappifyProductType.SUBSCRIPTION).forEach { type ->
            val harness = configuredHarness("Bazar")
            harness.service.storePurchaseResult = storeResponse(StorePurchaseStatus.DELIVERY_REQUIRED, deliveryId = 903L)
            var grants = 0
            val registration = harness.client.setConsumableDeliveryHandler {
                grants += 1
                InappifyDeliveryResult.DELIVERED
            }
            try {
                val result = harness.client.purchase(Activity(), purchaseRequest(InappifyMarket.BAZAAR, type))
                    as InappifyResult.Failure
                assertEquals(InappifyErrorCode.MALFORMED_RESPONSE, result.error.code)
                assertEquals(0, grants)
                assertEquals(0, harness.billing.consumeCalls)
                assertTrue(harness.service.storeDeliveryConfirmations.isEmpty())
                assertEquals(1, harness.store.pending.size)
            } finally { registration.close(); harness.client.close() }
        }
    }

    @Test
    fun v1UntypedPurchase_survivesClientRecreationAfterPaidNetworkFailure() = runBlocking {
        val first = configuredHarness("Bazar")
        first.service.storePurchaseResult = StoreServiceResult.Failure(ServiceFailureKind.NETWORK)
        val request = purchaseRequest(InappifyMarket.BAZAAR)
        try {
            assertTrue(first.client.purchase(Activity(), request) is InappifyResult.Failure)
            assertEquals(PendingStoreProductType.LEGACY_IN_APP, first.store.pending.single().productType)
            assertEquals(1, first.billing.purchaseCalls)
        } finally { first.client.close() }

        val recoveredStore = FakeSessionStore().apply {
            save(requireNotNull(first.store.load()))
            pending += PendingStoreOperationCodec.decode(PendingStoreOperationCodec.encode(first.store.pending))
        }
        val restarted = Harness("Bazar", recoveredStore)
        restarted.service.storePurchaseResult = storeResponse(StorePurchaseStatus.DELIVERY_REQUIRED, deliveryId = 904L)
        try {
            assertTrue(restarted.client.configure(InappifyOptions(apiKey = "api-key",
                appUserIdentifier = "customer-1", marketKey = "configured-market-key")) is InappifyResult.Success)
            val result = restarted.client.purchase(Activity(), request) as InappifyResult.Success<InappifyPurchase>
            assertEquals(904L, result.data.deliveryId)
            assertEquals(InappifyStorePurchaseStatus.DELIVERY_REQUIRED, result.data.storePurchaseStatus)
            assertEquals(0, restarted.billing.purchaseCalls)
            assertEquals(0, restarted.billing.consumeCalls)
            assertEquals(1, restarted.service.storePurchaseCalls)
            assertEquals("store-token", restarted.service.lastStorePurchase?.purchase?.token)
        } finally { restarted.client.close() }
    }

    @Test
    fun repeatedV2IdempotencyKey_resumesCheckpointWithoutOpeningBillingAgain() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        harness.service.storePurchaseResult = StoreServiceResult.Failure(
            ServiceFailureKind.NETWORK,
        )
        try {
            val first = harness.client.purchase(
                Activity(),
                purchaseRequest(market = InappifyMarket.BAZAAR),
            ) as InappifyResult.Failure
            assertEquals(InappifyErrorCode.NETWORK, first.error.code)
            assertEquals(1, harness.billing.purchaseCalls)
            assertEquals(1, harness.store.pending.size)

            harness.service.storePurchaseResult = storeResponse(
                StorePurchaseStatus.COMPLETED,
            )
            val resumed = harness.client.purchase(
                Activity(),
                purchaseRequest(market = InappifyMarket.BAZAAR),
            ) as InappifyResult.Success<InappifyPurchase>

            assertEquals(InappifyStorePurchaseStatus.COMPLETED, resumed.data.storePurchaseStatus)
            assertEquals(1, harness.billing.purchaseCalls)
            assertEquals(2, harness.service.storePurchaseCalls)
            assertTrue(harness.store.pending.isEmpty())
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun completedV2Purchase_refreshesCustomerAndClearsPendingRecord() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        try {
            val result = harness.client.purchase(
                Activity(),
                purchaseRequest(market = InappifyMarket.BAZAAR),
            ) as InappifyResult.Success<InappifyPurchase>

            assertEquals(InappifyPurchaseStatus.DONE, result.data.purchaseStatus)
            assertEquals(
                InappifyStorePurchaseStatus.COMPLETED,
                result.data.storePurchaseStatus,
            )
            assertEquals(1, harness.service.customerInfoCalls)
            assertEquals(1, harness.service.offeringsCalls)
            assertTrue(harness.store.pending.isEmpty())
            assertEquals("customer-1", result.snapshot.appUserIdentifier)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun deliveryRequired_returnsPublicDeliveryIdAndRetainsCheckpoint() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        harness.service.storePurchaseResult = storeResponse(
            status = StorePurchaseStatus.DELIVERY_REQUIRED,
            deliveryId = 901L,
        )
        try {
            val result = harness.client.purchase(
                Activity(),
                purchaseRequest(
                    market = InappifyMarket.BAZAAR,
                    productType = InappifyProductType.CONSUMABLE,
                ),
            ) as InappifyResult.Success<InappifyPurchase>

            assertEquals(
                InappifyStorePurchaseStatus.DELIVERY_REQUIRED,
                result.data.storePurchaseStatus,
            )
            assertEquals(901L, result.data.deliveryId)
            assertEquals(1, harness.store.pending.size)
            assertEquals(901L, harness.store.pending.single().deliveryId)
            assertEquals(
                PendingStoreOperationPhase.DELIVERY_REQUIRED,
                harness.store.pending.single().phase,
            )
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun registeredHandler_completesBazaarDeliveryThenConsumesReceipt() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        harness.service.storePurchaseResult = storeResponse(
            status = StorePurchaseStatus.DELIVERY_REQUIRED,
            deliveryId = 902L,
        )
        var grants = 0
        val registration = harness.client.setConsumableDeliveryHandler { delivery ->
            assertEquals(902L, delivery.deliveryId)
            assertEquals(InappifyDeliverySource.BAZAAR, delivery.source)
            grants += 1
            InappifyDeliveryResult.DELIVERED
        }
        try {
            val result = harness.client.purchase(
                Activity(),
                purchaseRequest(
                    market = InappifyMarket.BAZAAR,
                    productType = InappifyProductType.CONSUMABLE,
                ),
            ) as InappifyResult.Success<InappifyPurchase>

            assertEquals(InappifyStorePurchaseStatus.COMPLETED, result.data.storePurchaseStatus)
            assertEquals(1, grants)
            assertEquals(listOf(902L), harness.service.storeDeliveryConfirmations)
            assertEquals(listOf(902L), harness.service.storeConsumeReports)
            assertEquals(1, harness.billing.consumeCalls)
            assertTrue(harness.store.pending.isEmpty())
        } finally {
            registration.close()
            harness.client.close()
        }
    }

    @Test
    fun explicitSubscription_usesSubscriptionBillingProductType() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        try {
            val result = harness.client.purchase(
                Activity(),
                purchaseRequest(
                    market = InappifyMarket.BAZAAR,
                    productType = InappifyProductType.SUBSCRIPTION,
                ),
            ) as InappifyResult.Success<InappifyPurchase>

            assertEquals(InappifyPurchaseStatus.DONE, result.data.purchaseStatus)
            assertEquals(
                InappifyStorePurchaseStatus.COMPLETED,
                result.data.storePurchaseStatus,
            )
            assertEquals(StoreProductType.SUBSCRIPTION, harness.billing.lastRequest?.productType)
            assertEquals(1, harness.service.storePurchaseCalls)
            assertEquals(0, harness.service.legacyPurchaseCalls)

            val developerPayload = JsonParser.parseString(
                harness.billing.lastRequest?.developerPayload,
            ).asJsonObject
            assertEquals(
                setOf(
                    "offeringIdentifier",
                    "productIdentifier",
                    "nativePackageIdentifier",
                    "attemptId",
                    "productType",
                    "recoveryBinding",
                ),
                developerPayload.keySet(),
            )
            assertEquals("SUBSCRIPTION", developerPayload["productType"].asString)
            assertFalse(developerPayload.has("discount"))
            assertFalse(developerPayload.has("isCrypto"))
            assertFalse(developerPayload.has("marketKey"))
            assertFalse(developerPayload.toString().contains("request-market-key"))
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun configureReconciliation_resubmitsOwnedBazaarSubscriptionForServerRenewalDecision() =
        runBlocking {
            val harness = Harness(storePlatform = "Bazar")
            harness.billing.queryResults[StoreProductType.SUBSCRIPTION] = listOf(
                StorePurchase(
                    orderIdentifier = "renewal-order",
                    purchaseToken = "stable-subscription-token",
                    developerPayload =
                        """{"offeringIdentifier":"main","productIdentifier":"premium-product","nativePackageIdentifier":"monthly","attemptId":"subscription-attempt","productType":"SUBSCRIPTION"}""",
                    packageName = "com.example.host",
                    productIdentifier = "premium-product",
                    purchaseTimeMillis = 1_725_000_000_000L,
                    originalJson = "renewal-json",
                    signature = "renewal-signature",
                ),
            )
            val options = InappifyOptions(
                apiKey = "api-key",
                appUserIdentifier = "customer-1",
                market = InappifyMarket.NONE,
                marketKey = "configured-market-key",
            )
            try {
                val first = harness.client.configure(options)
                assertTrue(first is InappifyResult.Success)
                assertEquals(1, harness.service.storePurchaseCalls)
                assertEquals(
                    "stable-subscription-token",
                    harness.service.lastStorePurchase?.purchase?.token,
                )

                harness.service.storePurchaseResult = storeResponse(
                    StorePurchaseStatus.ALREADY_PROCESSED,
                )
                val second = harness.client.configure(options)

                assertTrue(second is InappifyResult.Success)
                assertEquals(2, harness.service.storePurchaseCalls)
                assertEquals(4, harness.billing.queryCalls)
                assertEquals(
                    "stable-subscription-token",
                    harness.service.lastStorePurchase?.purchase?.token,
                )
            } finally {
                harness.client.close()
            }
        }

    @Test
    fun configureReconciliation_recoversUntypedLegacyConsumableWithoutConsumingItEarly() =
        runBlocking {
            val harness = Harness(storePlatform = "Bazar")
            harness.billing.queryResults[StoreProductType.IN_APP] = listOf(
                StorePurchase(
                    orderIdentifier = "legacy-consumable-order",
                    purchaseToken = "legacy-consumable-token",
                    developerPayload =
                        """{"offeringIdentifier":"main","productIdentifier":"premium-product","nativePackageIdentifier":"monthly","attemptId":"legacy-consumable-attempt"}""",
                    packageName = "com.example.host",
                    productIdentifier = "premium-product",
                    purchaseTimeMillis = 1_725_000_000_000L,
                    originalJson = "legacy-consumable-json",
                    signature = "legacy-consumable-signature",
                ),
            )
            harness.service.storePurchaseResult = storeResponse(
                status = StorePurchaseStatus.DELIVERY_REQUIRED,
                deliveryId = 901L,
            )
            try {
                val configured = harness.client.configure(
                    InappifyOptions(
                        apiKey = "api-key",
                        appUserIdentifier = "customer-1",
                        market = InappifyMarket.BAZAAR,
                        marketKey = "configured-market-key",
                    ),
                )

                assertTrue(configured is InappifyResult.Success)
                assertEquals(1, harness.service.storePurchaseCalls)
                assertEquals(
                    StorePurchaseOperation.PURCHASE,
                    harness.service.lastStorePurchase?.operation,
                )
                assertEquals(1, harness.store.pending.size)
                assertEquals(
                    PendingStoreProductType.CONSUMABLE,
                    harness.store.pending.single().productType,
                )
                assertEquals(
                    PendingStoreOperationPhase.DELIVERY_REQUIRED,
                    harness.store.pending.single().phase,
                )
                assertEquals(901L, harness.store.pending.single().deliveryId)
                assertEquals(0, harness.billing.purchaseCalls)
            } finally {
                harness.client.close()
            }
        }

    @Test
    fun restoreAfterReinstall_doesNotRequireThePreviousLocalRecoveryBinding() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        harness.billing.queryResults[StoreProductType.SUBSCRIPTION] = listOf(
            StorePurchase(
                orderIdentifier = "owned-order",
                purchaseToken = "owned-subscription-token",
                developerPayload =
                    """{"offeringIdentifier":"main","productIdentifier":"premium-product","nativePackageIdentifier":"monthly","attemptId":"old-install-attempt","productType":"SUBSCRIPTION","recoveryBinding":"old-install-binding"}""",
                packageName = "com.example.host",
                productIdentifier = "premium-product",
                purchaseTimeMillis = 1_725_000_000_000L,
                originalJson = "owned-json",
                signature = "owned-signature",
            ),
        )
        try {
            val result = harness.client.restorePurchases()
                as InappifyResult.Success<InappifyRestoreResult>

            assertEquals(1, result.data.restoredCount)
            assertEquals(0, result.data.alreadyProcessedCount)
            assertEquals(0, result.data.failedCount)
            assertEquals(2, harness.billing.queryCalls)
            assertEquals(StorePurchaseOperation.RESTORE, harness.service.lastStorePurchase?.operation)
            assertEquals(
                "owned-subscription-token",
                harness.service.lastStorePurchase?.purchase?.token,
            )
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun restoreProcessesValidBazaarReceiptsAndCountsInvalidReceiptsIndependently() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        harness.billing.queryResults[StoreProductType.SUBSCRIPTION] = listOf(
            StorePurchase(
                orderIdentifier = "valid-owned-order",
                purchaseToken = "valid-owned-token",
                developerPayload =
                    """{"offeringIdentifier":"main","productIdentifier":"premium-product","nativePackageIdentifier":"monthly","attemptId":"valid-owned-attempt","productType":"SUBSCRIPTION"}""",
                packageName = "com.example.host",
                productIdentifier = "premium-product",
                purchaseTimeMillis = 1_725_000_000_000L,
                originalJson = "valid-owned-json",
                signature = "valid-owned-signature",
            ),
        )
        harness.billing.invalidPurchaseCounts[StoreProductType.SUBSCRIPTION] = 1
        harness.billing.invalidPurchaseCounts[StoreProductType.IN_APP] = 2
        try {
            val result = harness.client.restorePurchases()
                as InappifyResult.Success<InappifyRestoreResult>

            assertEquals(1, result.data.restoredCount)
            assertEquals(0, result.data.alreadyProcessedCount)
            assertEquals(3, result.data.failedCount)
            assertEquals(1, harness.service.storePurchaseCalls)
            assertEquals(
                listOf(
                    StorePurchaseQueryMode.PARTIAL,
                    StorePurchaseQueryMode.PARTIAL,
                ),
                harness.billing.queryModes,
            )
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun permanentRejectionTombstone_blocksUnchangedDirectRecoveryUntilAppVersionChanges() =
        runBlocking {
            val harness = configuredHarness(storePlatform = "Bazar")
            val request = InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                packageIdentifier = "monthly",
                market = InappifyMarket.NONE,
                isLostPurchase = true,
                lostPurchaseToken = "permanently-rejected-token",
                lostPurchaseTime = 1_725_000_000_001L,
                idempotencyKey = "permanently-rejected-attempt",
                productType = InappifyProductType.NON_CONSUMABLE,
            )
            harness.service.storePurchaseResult = storeResponse(
                status = StorePurchaseStatus.REJECTED,
                forceVersion = 2,
            )
            try {
                val rejected = harness.client.purchase(request) as InappifyResult.Failure

                assertEquals(
                    InappifyErrorCode.PURCHASE_VERIFICATION_FAILED,
                    rejected.error.code,
                )
                assertEquals(1, harness.service.storePurchaseCalls)
                assertEquals(1, harness.store.tombstones.size)
                assertTrue(harness.store.pending.isEmpty())
                assertFalse(
                    harness.store.tombstones.single().purchaseTokenFingerprint ==
                        "permanently-rejected-token",
                )
                assertFalse(
                    harness.store.tombstones.single().toString()
                        .contains("permanently-rejected-token"),
                )

                val suppressed = harness.client.purchase(request) as InappifyResult.Failure

                assertEquals(
                    InappifyErrorCode.PURCHASE_VERIFICATION_FAILED,
                    suppressed.error.code,
                )
                assertEquals(1, harness.service.storePurchaseCalls)
                assertEquals(0, harness.factory.createCalls)

                val configured = harness.client.configure(
                    InappifyOptions(
                        apiKey = "api-key",
                        appUserIdentifier = "customer-1",
                        market = InappifyMarket.NONE,
                        marketKey = "configured-market-key",
                        appVersion = "2.0.0",
                    ),
                )
                assertTrue(configured is InappifyResult.Success)
                harness.service.storePurchaseResult = storeResponse(
                    StorePurchaseStatus.COMPLETED,
                )

                val retried = harness.client.purchase(request)

                assertTrue(retried is InappifyResult.Success)
                assertEquals(2, harness.service.storePurchaseCalls)
            } finally {
                harness.client.close()
            }
        }

    @Test
    fun correctedEvidence_canRetryAfterAuthoritativeRejection() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        harness.service.storePurchaseResult = storeResponse(StorePurchaseStatus.REJECTED)
        try {
            val rejected = harness.client.purchase(
                InappifyPurchaseRequest(
                    productIdentifier = "premium-product",
                    offeringIdentifier = "main",
                    packageIdentifier = "monthly",
                    market = InappifyMarket.NONE,
                    isLostPurchase = true,
                    lostPurchaseToken = "correctable-token",
                    lostPurchaseTime = 100L,
                    idempotencyKey = "correctable-first",
                    productType = InappifyProductType.NON_CONSUMABLE,
                ),
            )
            assertTrue(rejected is InappifyResult.Failure)
            assertEquals(1, harness.store.tombstones.size)

            harness.service.storePurchaseResult = storeResponse(StorePurchaseStatus.COMPLETED)
            val retried = harness.client.purchase(
                InappifyPurchaseRequest(
                    productIdentifier = "premium-product",
                    offeringIdentifier = "main",
                    packageIdentifier = "monthly",
                    market = InappifyMarket.NONE,
                    isLostPurchase = true,
                    lostPurchaseToken = "correctable-token",
                    lostPurchaseTime = 101L,
                    idempotencyKey = "correctable-second",
                    productType = InappifyProductType.NON_CONSUMABLE,
                ),
            )

            assertTrue(retried is InappifyResult.Success)
            assertEquals(2, harness.service.storePurchaseCalls)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun restoreOperation_canRetryEvidenceRejectedForPurchaseOperation() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        harness.service.storePurchaseResult = storeResponse(StorePurchaseStatus.REJECTED)
        try {
            val rejected = harness.client.purchase(
                Activity(),
                purchaseRequest(market = InappifyMarket.BAZAAR),
            )
            assertTrue(rejected is InappifyResult.Failure)
            val exactDeveloperPayload = requireNotNull(
                harness.billing.lastRequest?.developerPayload,
            )
            assertEquals(1, harness.store.tombstones.size)

            harness.billing.queryResults[StoreProductType.IN_APP] = listOf(
                StorePurchase(
                    orderIdentifier = "store-order",
                    purchaseToken = "store-token",
                    developerPayload = exactDeveloperPayload,
                    packageName = "com.example.host",
                    productIdentifier = "premium-product",
                    purchaseTimeMillis = 1_725_000_000_000L,
                    originalJson = "store-json",
                    signature = "store-signature",
                ),
            )
            harness.service.storePurchaseResult = storeResponse(StorePurchaseStatus.COMPLETED)

            val restored = harness.client.restorePurchases()
                as InappifyResult.Success<InappifyRestoreResult>

            assertEquals(1, restored.data.restoredCount)
            assertEquals(2, harness.service.storePurchaseCalls)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun unauthorizedEnvelope_retainsEvidenceWithoutPermanentTombstone() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        val request = InappifyPurchaseRequest(
            productIdentifier = "premium-product",
            offeringIdentifier = "main",
            packageIdentifier = "monthly",
            market = InappifyMarket.NONE,
            isLostPurchase = true,
            lostPurchaseToken = "envelope-rejection-token",
            lostPurchaseTime = 100L,
            idempotencyKey = "envelope-rejection-attempt",
            productType = InappifyProductType.NON_CONSUMABLE,
        )
        harness.service.storePurchaseResult = StoreServiceResult.Response(
            statusCode = 401,
            payload = StoreBackendResponse(
                status = false,
                state = null,
                hasForceUpdate = false,
                forceVersion = null,
                errorCode = "UNAUTHORIZED",
                message = "The request authorization is invalid.",
            ),
            requestId = null,
        )
        try {
            val rejected = harness.client.purchase(request)

            assertTrue(rejected is InappifyResult.Failure)
            rejected as InappifyResult.Failure
            assertEquals(InappifyErrorCode.UNAUTHORIZED, rejected.error.code)
            assertTrue(rejected.error.isRetryable)
            assertEquals(1, harness.store.pending.size)
            assertTrue(harness.store.tombstones.isEmpty())

            harness.service.storePurchaseResult = storeResponse(StorePurchaseStatus.COMPLETED)
            val retried = harness.client.purchase(request)

            assertTrue(retried is InappifyResult.Success)
            assertEquals(2, harness.service.storePurchaseCalls)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun validationEnvelope_suppressesOnlyUnchangedEvidence() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        val rejectedRequest = InappifyPurchaseRequest(
            productIdentifier = "premium-product",
            offeringIdentifier = "main",
            packageIdentifier = "monthly",
            market = InappifyMarket.NONE,
            isLostPurchase = true,
            lostPurchaseToken = "validation-rejection-token",
            lostPurchaseTime = 100L,
            idempotencyKey = "validation-rejection-attempt",
            productType = InappifyProductType.NON_CONSUMABLE,
        )
        harness.service.storePurchaseResult = StoreServiceResult.Response(
            statusCode = 422,
            payload = StoreBackendResponse(
                status = false,
                state = null,
                hasForceUpdate = false,
                forceVersion = null,
                errorCode = "PRODUCT_MISMATCH",
                message = "The product does not match the receipt.",
            ),
            requestId = null,
        )
        try {
            val rejected = harness.client.purchase(rejectedRequest)

            assertTrue(rejected is InappifyResult.Failure)
            assertTrue(harness.store.pending.isEmpty())
            assertEquals(1, harness.store.tombstones.size)
            assertEquals(1, harness.service.storePurchaseCalls)

            val unchanged = harness.client.purchase(rejectedRequest)

            assertTrue(unchanged is InappifyResult.Failure)
            assertEquals(1, harness.service.storePurchaseCalls)

            harness.service.storePurchaseResult = storeResponse(StorePurchaseStatus.COMPLETED)
            val corrected = harness.client.purchase(
                InappifyPurchaseRequest(
                    productIdentifier = "premium-product",
                    offeringIdentifier = "main",
                    packageIdentifier = "monthly",
                    market = InappifyMarket.NONE,
                    isLostPurchase = true,
                    lostPurchaseToken = "validation-rejection-token",
                    lostPurchaseTime = 101L,
                    idempotencyKey = "validation-corrected-attempt",
                    productType = InappifyProductType.NON_CONSUMABLE,
                ),
            )

            assertTrue(corrected is InappifyResult.Success)
            assertEquals(2, harness.service.storePurchaseCalls)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun failedAtomicTombstoneTransition_retainsPendingEvidence() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        harness.store.failAtomicFinalization = true
        harness.service.storePurchaseResult = storeResponse(StorePurchaseStatus.REJECTED)
        try {
            val result = harness.client.purchase(
                InappifyPurchaseRequest(
                    productIdentifier = "premium-product",
                    offeringIdentifier = "main",
                    packageIdentifier = "monthly",
                    market = InappifyMarket.NONE,
                    isLostPurchase = true,
                    lostPurchaseToken = "retained-rejection-token",
                    lostPurchaseTime = 100L,
                    idempotencyKey = "retained-rejection-attempt",
                    productType = InappifyProductType.NON_CONSUMABLE,
                ),
            )

            assertTrue(result is InappifyResult.Failure)
            assertEquals(1, harness.store.pending.size)
            assertTrue(harness.store.tombstones.isEmpty())
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun unavailableRecoveryState_blocksBazaarUiAndReconciliation() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        harness.store.recoveryStateAvailable = false
        try {
            val purchase = harness.client.purchase(
                Activity(),
                purchaseRequest(market = InappifyMarket.BAZAAR),
            ) as InappifyResult.Failure

            assertEquals(InappifyErrorCode.STORE_UNAVAILABLE, purchase.error.code)
            assertTrue(purchase.error.isRetryable)
            assertEquals(0, harness.factory.createCalls)
            assertEquals(0, harness.billing.purchaseCalls)

            val restore = harness.client.restorePurchases() as InappifyResult.Failure

            assertEquals(InappifyErrorCode.STORE_UNAVAILABLE, restore.error.code)
            assertEquals(0, harness.billing.queryCalls)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun queuedTokenFromDifferentAppId_doesNotSuppressCurrentAppRestore() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        harness.service.storePurchaseResult = StoreServiceResult.Failure(
            ServiceFailureKind.NETWORK,
        )
        try {
            harness.client.purchase(
                Activity(),
                purchaseRequest(market = InappifyMarket.BAZAAR),
            )
            val foreign = harness.store.pending.single()
            harness.store.pending[0] = foreign.copy(
                appId = 999L,
                evidence = foreign.evidence.copy(
                    purchaseToken = "current-app-owned-token",
                ),
            )
            harness.resetObservations()
            harness.billing.queryResults[StoreProductType.SUBSCRIPTION] = listOf(
                ownedSubscription("current-app-owned-token"),
            )
            harness.service.storePurchaseResult = storeResponse(StorePurchaseStatus.COMPLETED)

            val restored = harness.client.restorePurchases()
                as InappifyResult.Success<InappifyRestoreResult>

            assertEquals(1, restored.data.restoredCount)
            assertEquals(1, harness.service.storePurchaseCalls)
            assertEquals(
                "current-app-owned-token",
                harness.service.lastStorePurchase?.purchase?.token,
            )
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun explicitRestore_skipsMatchingPermanentRejectionAndCountsItAsFailed() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        harness.billing.queryResults[StoreProductType.SUBSCRIPTION] = listOf(
            ownedSubscription("permanently-rejected-owned-token"),
        )
        harness.service.storePurchaseResult = storeResponse(
            status = StorePurchaseStatus.REJECTED,
            forceVersion = 2,
        )
        try {
            val first = harness.client.restorePurchases()
                as InappifyResult.Success<InappifyRestoreResult>
            assertEquals(1, first.data.failedCount)
            assertEquals(1, harness.service.storePurchaseCalls)
            assertEquals(1, harness.store.tombstones.size)

            harness.service.resetObservations()
            val second = harness.client.restorePurchases()
                as InappifyResult.Success<InappifyRestoreResult>

            assertEquals(0, second.data.restoredCount)
            assertEquals(0, second.data.alreadyProcessedCount)
            assertEquals(1, second.data.failedCount)
            assertEquals(0, harness.service.storePurchaseCalls)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun explicitRestore_rejectsNewlyQueriedConsumableWithoutServerSubmission() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        harness.billing.queryResults[StoreProductType.IN_APP] = listOf(
            StorePurchase(
                orderIdentifier = "owned-consumable-order",
                purchaseToken = "owned-consumable-token",
                developerPayload =
                    """{"offeringIdentifier":"main","productIdentifier":"premium-product","nativePackageIdentifier":"monthly","attemptId":"owned-consumable-attempt","productType":"CONSUMABLE"}""",
                packageName = "com.example.host",
                productIdentifier = "premium-product",
                purchaseTimeMillis = 1_725_000_000_000L,
                originalJson = "owned-consumable-json",
                signature = "owned-consumable-signature",
            ),
        )
        try {
            val restored = harness.client.restorePurchases()
                as InappifyResult.Success<InappifyRestoreResult>

            assertEquals(0, restored.data.restoredCount)
            assertEquals(0, restored.data.alreadyProcessedCount)
            assertEquals(1, restored.data.failedCount)
            assertEquals(0, harness.service.storePurchaseCalls)
            assertTrue(harness.store.pending.isEmpty())
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun explicitRestore_countsResumedNonConsumablePurchaseCheckpoints() = runBlocking {
        val cases = listOf(
            Triple(StorePurchaseStatus.COMPLETED, 1 to 0, 0),
            Triple(StorePurchaseStatus.ALREADY_PROCESSED, 0 to 1, 0),
            Triple(StorePurchaseStatus.REJECTED, 0 to 0, 1),
        )
        for ((status, successCounts, expectedFailures) in cases) {
            val harness = configuredHarness(storePlatform = "Bazar")
            try {
                harness.service.storePurchaseResult = StoreServiceResult.Failure(
                    ServiceFailureKind.NETWORK,
                )
                val initial = harness.client.purchase(
                    Activity(),
                    purchaseRequest(market = InappifyMarket.BAZAAR),
                )
                assertTrue(initial is InappifyResult.Failure)
                assertEquals(1, harness.store.pending.size)
                harness.store.pending[0] = harness.store.pending.single().copy(
                    nextRetryAtEpochMillis = null,
                )

                harness.resetObservations()
                harness.service.storePurchaseResult = storeResponse(
                    status = status,
                    forceVersion = 1,
                )
                val restored = harness.client.restorePurchases()
                    as InappifyResult.Success<InappifyRestoreResult>

                assertEquals(successCounts.first, restored.data.restoredCount)
                assertEquals(
                    successCounts.second,
                    restored.data.alreadyProcessedCount,
                )
                assertEquals(expectedFailures, restored.data.failedCount)
                assertEquals(1, harness.service.storePurchaseCalls)
            } finally {
                harness.client.close()
            }
        }
    }

    @Test
    fun explicitRestore_countsPendingPurchaseAndNewRestoreExactlyOnceEach() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        try {
            harness.service.storePurchaseResult = StoreServiceResult.Failure(
                ServiceFailureKind.NETWORK,
            )
            val initial = harness.client.purchase(
                Activity(),
                purchaseRequest(market = InappifyMarket.BAZAAR),
            )
            assertTrue(initial is InappifyResult.Failure)
            harness.store.pending[0] = harness.store.pending.single().copy(
                nextRetryAtEpochMillis = null,
            )

            harness.resetObservations()
            harness.billing.queryResults[StoreProductType.SUBSCRIPTION] = listOf(
                ownedSubscription("second-owned-token"),
            )
            harness.service.storePurchaseResult = storeResponse(
                status = StorePurchaseStatus.COMPLETED,
                forceVersion = 1,
            )

            val restored = harness.client.restorePurchases()
                as InappifyResult.Success<InappifyRestoreResult>

            assertEquals(2, restored.data.restoredCount)
            assertEquals(0, restored.data.alreadyProcessedCount)
            assertEquals(0, restored.data.failedCount)
            assertEquals(2, harness.service.storePurchaseCalls)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun explicitRestore_doesNotCountResumedConsumablePurchaseCheckpoint() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        try {
            harness.service.storePurchaseResult = StoreServiceResult.Failure(
                ServiceFailureKind.NETWORK,
            )
            val initial = harness.client.purchase(
                Activity(),
                purchaseRequest(
                    market = InappifyMarket.BAZAAR,
                    productType = InappifyProductType.CONSUMABLE,
                ),
            )
            assertTrue(initial is InappifyResult.Failure)
            harness.store.pending[0] = harness.store.pending.single().copy(
                nextRetryAtEpochMillis = null,
            )

            harness.resetObservations()
            harness.service.storePurchaseResult = storeResponse(
                status = StorePurchaseStatus.COMPLETED,
                forceVersion = 1,
            )
            val restored = harness.client.restorePurchases()
                as InappifyResult.Success<InappifyRestoreResult>

            assertEquals(0, restored.data.restoredCount)
            assertEquals(0, restored.data.alreadyProcessedCount)
            assertEquals(0, restored.data.failedCount)
            assertEquals(1, harness.service.storePurchaseCalls)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun explicitRestore_countsUnexpectedNonConsumablePendingDeliveryAsFailed() = runBlocking {
        val harness = configuredHarness(storePlatform = "Bazar")
        try {
            harness.service.storePurchaseResult = StoreServiceResult.Failure(
                ServiceFailureKind.NETWORK,
            )
            val initial = harness.client.purchase(
                Activity(),
                purchaseRequest(market = InappifyMarket.BAZAAR, productType = InappifyProductType.NON_CONSUMABLE),
            )
            assertTrue(initial is InappifyResult.Failure)
            harness.store.pending[0] = harness.store.pending.single().copy(
                nextRetryAtEpochMillis = null,
            )

            harness.resetObservations()
            harness.service.storePurchaseResult = storeResponse(
                status = StorePurchaseStatus.DELIVERY_REQUIRED,
                deliveryId = 404L,
                forceVersion = 1,
            )
            val restored = harness.client.restorePurchases()
                as InappifyResult.Success<InappifyRestoreResult>

            assertEquals(0, restored.data.restoredCount)
            assertEquals(0, restored.data.alreadyProcessedCount)
            assertEquals(1, restored.data.failedCount)
            assertEquals(1, harness.service.storePurchaseCalls)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun v1DirectClient_canConfigureWithoutBazaarKeyButStorePurchaseStillRequiresIt() = runBlocking {
        val harness = Harness(storePlatform = "Bazar")
        try {
            val result = harness.client.configure(
                InappifyOptions(
                    apiKey = "api-key",
                    appUserIdentifier = "customer-1",
                    market = InappifyMarket.NONE,
                    marketKey = null,
                ),
            )

            assertTrue(result is InappifyResult.Success)
            assertEquals(0, harness.factory.createCalls)
            assertEquals(0, harness.billing.queryCalls)
            assertEquals(0, harness.service.storePurchaseCalls)
            val direct = harness.client.purchase(purchaseRequest(InappifyMarket.NONE))
                as InappifyResult.Success<InappifyPurchase>
            assertEquals(InappifyMarket.NONE, direct.data.market)
            assertEquals(1, harness.service.legacyPurchaseCalls)
            val store = harness.client.purchase(Activity(), purchaseRequest(InappifyMarket.NONE,
                InappifyProductType.NON_CONSUMABLE)) as InappifyResult.Failure
            assertEquals(InappifyErrorCode.INVALID_CONFIGURATION, store.error.code)
            assertEquals(false, store.error.details["outcomeMayHaveCommitted"])
            assertEquals(0, harness.factory.createCalls)
            assertEquals(0, harness.billing.purchaseCalls)
            assertEquals(0, harness.service.storePurchaseCalls)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun knownNonAndroidPlatforms_areRejectedWithoutUsingFallback() = runBlocking {
        listOf("DirectIos", "DirectWeb", "PlayStore", "AppStore", "SibApp")
            .forEach { storePlatform ->
                val harness = configuredHarness(storePlatform = storePlatform)
                try {
                    val result = harness.client.purchase(
                        purchaseRequest(market = InappifyMarket.BAZAAR),
                    ) as InappifyResult.Failure

                    assertEquals(InappifyErrorCode.UNSUPPORTED_OPERATION, result.error.code)
                    assertEquals(0, harness.factory.createCalls)
                    assertEquals(0, harness.service.legacyPurchaseCalls)
                    assertEquals(0, harness.service.storePurchaseCalls)
                } finally {
                    harness.client.close()
                }
            }
    }

    @Test
    fun myKetPlatform_isRejectedBeforeBillingOrNetworkPurchase() = runBlocking {
        val harness = configuredHarness(storePlatform = "MyKet")
        try {
            val result = harness.client.purchase(
                purchaseRequest(market = InappifyMarket.BAZAAR),
            ) as InappifyResult.Failure

            assertEquals(InappifyErrorCode.UNSUPPORTED_OPERATION, result.error.code)
            assertEquals(0, harness.factory.createCalls)
            assertEquals(0, harness.billing.purchaseCalls)
            assertEquals(0, harness.service.storePurchaseCalls)
            assertEquals(0, harness.service.legacyPurchaseCalls)
            assertFalse(result.error.isRetryable)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun unsupportedServerStore_isRejectedBeforeSyncOrRestoreFallback() = runBlocking {
        val harness = Harness(storePlatform = "MyKet")
        try {
            val configured = harness.client.configure(
                InappifyOptions(
                    apiKey = "api-key",
                    appUserIdentifier = "customer-1",
                    market = InappifyMarket.BAZAAR,
                    marketKey = "configured-market-key",
                ),
            )
            assertTrue(configured is InappifyResult.Success)
            harness.resetObservations()

            val synced = harness.client.syncPurchases() as InappifyResult.Failure
            val restored = harness.client.restorePurchases() as InappifyResult.Failure

            assertEquals(InappifyErrorCode.UNSUPPORTED_OPERATION, synced.error.code)
            assertEquals(InappifyErrorCode.UNSUPPORTED_OPERATION, restored.error.code)
            assertEquals(0, harness.factory.createCalls)
            assertEquals(0, harness.billing.queryCalls)
            assertEquals(0, harness.service.legacyPurchaseCalls)
            assertEquals(0, harness.service.storePurchaseCalls)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun cancellingSyncWaitingForMutex_doesNotCloseConfigureReconciliationAdapter() = runBlocking {
        val harness = Harness(storePlatform = "Bazar")
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        harness.billing.queryStarted = queryStarted
        harness.billing.releaseQuery = releaseQuery
        val configure = async {
            harness.client.configure(
                InappifyOptions(
                    apiKey = "api-key",
                    appUserIdentifier = "customer-1",
                    market = InappifyMarket.NONE,
                    marketKey = "configured-market-key",
                ),
            )
        }
        try {
            queryStarted.await()

            val waitingSync = async(start = CoroutineStart.UNDISPATCHED) {
                harness.client.syncPurchases()
            }
            assertTrue(waitingSync.isActive)
            waitingSync.cancelAndJoin()

            assertEquals(0, harness.billing.closeCalls)
            releaseQuery.complete(Unit)
            assertTrue(configure.await() is InappifyResult.Success)
            assertEquals(2, harness.billing.closeCalls)
        } finally {
            releaseQuery.complete(Unit)
            configure.cancelAndJoin()
            harness.client.close()
        }
    }

    private suspend fun configuredHarness(storePlatform: String?): Harness {
        val harness = Harness(storePlatform)
        val configured = harness.client.configure(
            InappifyOptions(
                apiKey = "api-key",
                appUserIdentifier = "customer-1",
                market = InappifyMarket.NONE,
                marketKey = "configured-market-key",
            ),
        )
        check(configured is InappifyResult.Success) {
            "The integration harness must configure successfully."
        }
        harness.resetObservations()
        return harness
    }

    private fun purchaseRequest(
        market: InappifyMarket,
        productType: InappifyProductType? = null,
    ): InappifyPurchaseRequest = if (productType == null) {
        InappifyPurchaseRequest(
            productIdentifier = "premium-product",
            offeringIdentifier = "main",
            packageIdentifier = "monthly",
            market = market,
            marketKey = "request-market-key",
            idempotencyKey = "integration-attempt",
        )
    } else {
        InappifyPurchaseRequest(
            productIdentifier = "premium-product",
            offeringIdentifier = "main",
            productType = productType,
            packageIdentifier = "monthly",
            market = market,
            marketKey = "request-market-key",
            idempotencyKey = "integration-attempt",
        )
    }

    private class Harness(storePlatform: String?, val store: FakeSessionStore = FakeSessionStore()) {
        val service = FakeService(storePlatform)
        val billing = FakeBillingAdapter()
        val factory = RecordingBillingFactory(billing)
        val client = DefaultInappifyClient(
            service = service,
            sessionStore = store,
            metadataProvider = AppMetadataProvider {
                AppMetadata(
                    packageIdentifier = "com.example.host",
                    versionName = "1.2.3",
                    versionCode = 42,
                )
            },
            sdkVersion = "test-sdk",
            currentTimeMillis = { 1_725_000_000_000L },
            purchaseRecoveryIdProvider = { TEST_RECOVERY_BINDING },
            consumableRetrySleep = {},
            storeBillingAdapterFactory = factory,
        )

        fun resetObservations() {
            service.resetObservations()
            billing.resetObservations()
            factory.resetObservations()
        }
    }

    private class FakeService(
        private val configuredStorePlatform: String?,
    ) : InappifyService {
        var storePurchaseResult: StoreServiceResult = storeResponse(
            StorePurchaseStatus.COMPLETED,
        )
        var legacyPurchaseCalls: Int = 0
        var storePurchaseCalls: Int = 0
        var customerInfoCalls: Int = 0
        var offeringsCalls: Int = 0
        var lastLegacyPurchase: PurchaseApiRequest? = null
        var lastStorePurchase: StorePurchaseApiRequest? = null
        val pendingDirectDeliveries = mutableListOf<BackendConsumableDelivery>()
        val directDeliveryConfirmations = mutableListOf<Long>()
        val storeDeliveryConfirmations = mutableListOf<Long>()
        val storeConsumeReports = mutableListOf<Long>()
        var pendingDirectCalls: Int = 0
        var pendingDirectTransientFailures: Int = 0
        var pendingDirectHttpError: Int? = null

        override suspend fun configure(request: ConfigureApiRequest): ServiceResult =
            lifecycleResponse(
                token = "customer-token",
                identifier = "customer-1",
                storePlatform = configuredStorePlatform,
            )

        override suspend fun login(request: LoginApiRequest): ServiceResult =
            lifecycleResponse(
                token = "customer-token",
                identifier = request.appUserIdentifier,
                storePlatform = configuredStorePlatform,
            )

        override suspend fun logout(request: LogoutApiRequest): ServiceResult =
            lifecycleResponse(
                token = "anonymous-token",
                identifier = "InaAnonymousId-1",
                storePlatform = configuredStorePlatform,
            )

        override suspend fun refreshSession(
            request: RefreshSessionApiRequest,
        ): ServiceResult = lifecycleResponse(
            token = null,
            identifier = "customer-1",
            storePlatform = configuredStorePlatform,
        )

        override suspend fun getCustomerInfo(
            request: ResourceApiRequest,
        ): ServiceResult {
            customerInfoCalls += 1
            return lifecycleResponse(
                token = null,
                identifier = "customer-1",
                storePlatform = null,
            )
        }

        override suspend fun getOfferings(
            request: ResourceApiRequest,
        ): ServiceResult {
            offeringsCalls += 1
            return ServiceResult.Response(
                statusCode = 200,
                payload = BackendResponse(
                    status = true,
                    message = null,
                    errorCode = null,
                    token = null,
                    appUserIdentifier = null,
                    customerInfoJson = null,
                    storeInfo = null,
                    appId = null,
                    forceVersion = 1,
                    offeringsJson = OFFERINGS_JSON,
                ),
                requestId = "offerings-request",
            )
        }

        override suspend fun purchase(request: PurchaseApiRequest): ServiceResult {
            legacyPurchaseCalls += 1
            lastLegacyPurchase = request
            return ServiceResult.Response(
                statusCode = 200,
                payload = BackendResponse(
                    status = true,
                    message = null,
                    errorCode = null,
                    token = null,
                    appUserIdentifier = null,
                    customerInfoJson = null,
                    storeInfo = null,
                    appId = null,
                    forceVersion = 1,
                    purchase = BackendPurchase(
                        url = null,
                        purchaseStatus = "DONE",
                        checkoutId = null,
                        checkoutStatus = null,
                        nextActionType = null,
                    ),
                ),
                requestId = "legacy-purchase-request",
            )
        }

        override suspend fun submitStorePurchase(
            request: StorePurchaseApiRequest,
        ): StoreServiceResult {
            storePurchaseCalls += 1
            lastStorePurchase = request
            return storePurchaseResult
        }

        override suspend fun markStoreDeliveryDelivered(
            request: StoreDeliveryApiRequest,
        ): StoreServiceResult {
            storeDeliveryConfirmations += request.deliveryId
            return storeResponse(
                status = StorePurchaseStatus.CONSUME_REQUIRED,
                deliveryId = request.deliveryId,
            )
        }

        override suspend fun reportStoreConsumeResult(
            request: StoreConsumeResultApiRequest,
        ): StoreServiceResult {
            storeConsumeReports += request.deliveryId
            return storeResponse(
                status = StorePurchaseStatus.COMPLETED,
                deliveryId = request.deliveryId,
            )
        }

        override suspend fun getPendingConsumableDeliveries(
            request: PendingConsumableDeliveriesApiRequest,
        ): ConsumableDeliveriesServiceResult {
            pendingDirectCalls += 1
            if (pendingDirectTransientFailures > 0) {
                pendingDirectTransientFailures -= 1
                return ConsumableDeliveriesServiceResult.Failure(ServiceFailureKind.NETWORK)
            }
            val httpError = pendingDirectHttpError
            return ConsumableDeliveriesServiceResult.Response(
                statusCode = httpError ?: 200,
                payload = ConsumableDeliveriesBackendResponse(
                    status = httpError == null,
                    deliveries = if (httpError == null) {
                        pendingDirectDeliveries.take(100)
                    } else {
                        emptyList()
                    },
                    errorCode = if (httpError == null) null else "VALIDATION_ERROR",
                    message = null,
                ),
                requestId = "pending-consumables-request",
            )
        }

        override suspend fun markDirectConsumableDelivered(
            request: DirectConsumableDeliveryApiRequest,
        ): ConsumableDeliveriesServiceResult {
            val delivery = pendingDirectDeliveries
                .firstOrNull { it.deliveryId == request.deliveryId }
                ?: return ConsumableDeliveriesServiceResult.Response(
                    statusCode = 422,
                    payload = ConsumableDeliveriesBackendResponse(
                        status = false,
                        deliveries = emptyList(),
                        errorCode = "DELIVERY_NOT_FOUND",
                        message = "Delivery not found.",
                    ),
                    requestId = "direct-delivery-request",
                )
            directDeliveryConfirmations += request.deliveryId
            pendingDirectDeliveries.removeAll { it.deliveryId == request.deliveryId }
            return ConsumableDeliveriesServiceResult.Response(
                statusCode = 200,
                payload = ConsumableDeliveriesBackendResponse(
                    status = true,
                    deliveries = listOf(
                        BackendConsumableDelivery(
                            deliveryId = delivery.deliveryId,
                            status = StorePurchaseStatus.COMPLETED,
                            source = delivery.source,
                            paymentId = delivery.paymentId,
                            transactionId = delivery.transactionId,
                            productIdentifier = delivery.productIdentifier,
                            alreadyDelivered = true,
                            consumeAttempts = delivery.consumeAttempts,
                        ),
                    ),
                    errorCode = null,
                    message = null,
                ),
                requestId = "direct-delivery-request",
            )
        }

        override fun close() = Unit

        fun resetObservations() {
            legacyPurchaseCalls = 0
            storePurchaseCalls = 0
            customerInfoCalls = 0
            offeringsCalls = 0
            lastLegacyPurchase = null
            lastStorePurchase = null
            directDeliveryConfirmations.clear()
            storeDeliveryConfirmations.clear()
            storeConsumeReports.clear()
            pendingDirectCalls = 0
            pendingDirectTransientFailures = 0
            pendingDirectHttpError = null
        }
    }

    private class FakeSessionStore : SessionStateStore {
        val pending = mutableListOf<PendingStoreOperation>()
        val tombstones = mutableListOf<RejectedStoreEvidenceTombstone>()
        var recoveryStateAvailable: Boolean = true
        var failAtomicFinalization: Boolean = false
        private var persisted: PersistedSession? = null

        override suspend fun load(): PersistedSession? = persisted

        override suspend fun save(session: PersistedSession): Boolean {
            persisted = session
            return true
        }

        override suspend fun clear(): Boolean {
            persisted = null
            return true
        }

        override suspend fun loadPendingStoreOperations(): List<PendingStoreOperation> =
            pending.toList()

        override suspend fun loadPendingStoreRecoveryState(): PendingStoreRecoveryState? =
            if (recoveryStateAvailable) {
                PendingStoreRecoveryState(
                    operations = pending.toList(),
                    rejectedEvidenceTombstones = tombstones.toList(),
                )
            } else {
                null
            }

        override suspend fun upsertPendingStoreOperation(
            operation: PendingStoreOperation,
        ): Boolean {
            pending.removeAll { it.id == operation.id }
            pending += operation
            return true
        }

        override suspend fun removePendingStoreOperation(operationId: String): Boolean {
            pending.removeAll { it.id == operationId }
            return true
        }

        override suspend fun mutatePendingStoreOperations(
            mutation: (List<PendingStoreOperation>) -> List<PendingStoreOperation>,
        ): Boolean {
            val updated = mutation(pending.toList())
            pending.clear()
            pending += updated
            return true
        }

        override suspend fun clearPendingStoreOperations(): Boolean {
            pending.clear()
            return true
        }

        override suspend fun loadRejectedStoreEvidenceTombstones():
            List<RejectedStoreEvidenceTombstone> = tombstones.toList()

        override suspend fun upsertRejectedStoreEvidenceTombstone(
            tombstone: RejectedStoreEvidenceTombstone,
        ): Boolean {
            tombstones.remove(tombstone)
            tombstones += tombstone
            while (tombstones.size > 256) tombstones.removeAt(0)
            return true
        }

        override suspend fun finalizePendingStoreOperationWithTombstone(
            operationId: String,
            tombstone: RejectedStoreEvidenceTombstone,
        ): Boolean {
            if (failAtomicFinalization) return false
            if (pending.none { operation -> operation.id == operationId }) return false
            val nextTombstones = tombstones
                .filterNot { existing -> existing == tombstone }
                .plus(tombstone)
                .takeLast(256)
            pending.removeAll { operation -> operation.id == operationId }
            tombstones.clear()
            tombstones += nextTombstones
            return true
        }

        override suspend fun clearRejectedStoreEvidenceTombstones(): Boolean {
            tombstones.clear()
            return true
        }
    }

    private class RecordingBillingFactory(
        private val adapter: StoreBillingAdapter,
    ) : StoreBillingAdapterFactory {
        var createCalls: Int = 0

        override fun create(
            market: InappifyMarket,
            marketKey: String?,
        ): StoreBillingAdapter {
            createCalls += 1
            return adapter
        }

        fun resetObservations() {
            createCalls = 0
        }
    }

    private class FakeBillingAdapter :
        StoreBillingAdapter,
        PartialStorePurchaseQueryAdapter {
        var purchaseCalls: Int = 0
        var queryCalls: Int = 0
        var closeCalls: Int = 0
        var consumeCalls: Int = 0
        var lastRequest: StorePurchaseRequest? = null
        var queryStarted: CompletableDeferred<Unit>? = null
        var releaseQuery: CompletableDeferred<Unit>? = null
        val queryResults = mutableMapOf<StoreProductType, List<StorePurchase>>()
        val invalidPurchaseCounts = mutableMapOf<StoreProductType, Int>()
        val queryModes = mutableListOf<StorePurchaseQueryMode>()

        override suspend fun purchase(
            uiHost: StoreUiHost,
            request: StorePurchaseRequest,
        ): StoreBillingResult {
            purchaseCalls += 1
            lastRequest = request
            return StoreBillingResult.Success(
                StorePurchase(
                    orderIdentifier = "store-order",
                    purchaseToken = "store-token",
                    developerPayload = request.developerPayload.orEmpty(),
                    packageName = "com.example.host",
                    productIdentifier = request.productIdentifier,
                    purchaseTimeMillis = 1_725_000_000_000L,
                    originalJson = "store-json",
                    signature = "store-signature",
                ),
            )
        }

        override suspend fun queryPurchases(
            productType: StoreProductType,
        ): StorePurchaseQueryResult {
            queryCalls += 1
            queryModes += StorePurchaseQueryMode.STRICT
            awaitQueryRelease()
            return StorePurchaseQueryResult.Success(
                purchases = queryResults[productType].orEmpty(),
            )
        }

        override suspend fun queryPurchasesPartially(
            productType: StoreProductType,
        ): StorePurchaseQueryResult {
            queryCalls += 1
            queryModes += StorePurchaseQueryMode.PARTIAL
            awaitQueryRelease()
            return StorePurchaseQueryResult.Success(
                purchases = queryResults[productType].orEmpty(),
                invalidPurchaseCount = invalidPurchaseCounts[productType] ?: 0,
            )
        }

        override suspend fun consume(purchase: StorePurchase): BillingConsumeResult {
            consumeCalls += 1
            return BillingConsumeResult.Success
        }

        override fun close() {
            closeCalls += 1
        }

        private suspend fun awaitQueryRelease() {
            queryStarted?.complete(Unit)
            releaseQuery?.await()
        }

        fun resetObservations() {
            purchaseCalls = 0
            queryCalls = 0
            closeCalls = 0
            consumeCalls = 0
            lastRequest = null
            queryResults.clear()
            invalidPurchaseCounts.clear()
            queryModes.clear()
        }
    }

    private companion object {
        private const val TEST_RECOVERY_BINDING =
            "7df28083c2ecb4db1d628fa7a8a6d539b142591af143207ee4f700ab573d0ad7"
        private val OFFERINGS_JSON =
            """
            {
              "offerings": [{
                "identifier": "main",
                "packages": [{
                  "identifier": "monthly",
                  "product": {
                    "identifier": "premium-product",
                    "trialDays": 0
                  }
                }]
              }],
              "rules": []
            }
            """.trimIndent()

        private fun lifecycleResponse(
            token: String?,
            identifier: String,
            storePlatform: String?,
        ): ServiceResult.Response = ServiceResult.Response(
            statusCode = 200,
            payload = BackendResponse(
                status = true,
                message = null,
                errorCode = null,
                token = token,
                appUserIdentifier = identifier,
                customerInfoJson = """{"originalAppUserId":"$identifier"}""",
                storeInfo = if (storePlatform == "Bazar") "bazar" else null,
                appId = 12,
                forceVersion = 1,
                storePlatform = storePlatform,
            ),
            requestId = "lifecycle-request",
        )

        private fun storeResponse(
            status: StorePurchaseStatus,
            deliveryId: Long? = null,
            forceVersion: Long? = 2,
        ): StoreServiceResult.Response = StoreServiceResult.Response(
            statusCode = 200,
            payload = StoreBackendResponse(
                status = true,
                state = StorePurchaseState(
                    status = status,
                    paymentId = if (status == StorePurchaseStatus.COMPLETED) 71L else null,
                    eventId = if (status == StorePurchaseStatus.COMPLETED) 72L else null,
                    deliveryId = deliveryId,
                    verificationRequestId = null,
                    retryAfter = null,
                    errorCode = null,
                    message = null,
                    alreadyProcessed = false,
                ),
                hasForceUpdate = false,
                forceVersion = forceVersion,
                errorCode = null,
                message = null,
            ),
            requestId = "store-purchase-request",
        )

        private fun directDelivery(
            deliveryId: Long,
            source: ConsumableDeliverySource = ConsumableDeliverySource.DIRECT,
            status: StorePurchaseStatus = StorePurchaseStatus.DELIVERY_REQUIRED,
        ): BackendConsumableDelivery =
            BackendConsumableDelivery(
                deliveryId = deliveryId,
                status = status,
                source = source,
                paymentId = deliveryId + 1,
                transactionId = "transaction-$deliveryId",
                productIdentifier = "premium-product",
                alreadyDelivered = false,
                consumeAttempts = null,
            )

        private fun ownedSubscription(token: String): StorePurchase = StorePurchase(
            orderIdentifier = "owned-order-$token",
            purchaseToken = token,
            developerPayload =
                """{"offeringIdentifier":"main","productIdentifier":"premium-product","nativePackageIdentifier":"monthly","attemptId":"attempt-$token","productType":"SUBSCRIPTION"}""",
            packageName = "com.example.host",
            productIdentifier = "premium-product",
            purchaseTimeMillis = 1_725_000_000_000L,
            originalJson = "owned-json-$token",
            signature = "owned-signature-$token",
        )
    }
}
