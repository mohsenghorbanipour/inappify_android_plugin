package com.inappify.sdk

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicApiTest {

    @Test
    fun optionsToString_redactsSensitiveValues() {
        val rendered = InappifyOptions(
            apiKey = "public-api-key",
            appUserIdentifier = "09120000000",
            market = InappifyMarket.BAZAAR,
            marketKey = "market-public-key",
            country = "IR",
            appVersion = "1.0.0",
        ).toString()

        assertFalse(rendered.contains("public-api-key"))
        assertFalse(rendered.contains("09120000000"))
        assertFalse(rendered.contains("market-public-key"))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test
    fun loginRequestToString_redactsSensitiveValues() {
        val rendered = InappifyLoginRequest(
            apiKey = "public-api-key",
            appUserIdentifier = "09120000000",
        ).toString()

        assertFalse(rendered.contains("public-api-key"))
        assertFalse(rendered.contains("09120000000"))
    }

    @Test
    fun initialSnapshot_isUnconfiguredAndTokenFree() {
        val snapshot = InappifySnapshot.initial(sdkVersion = "test")

        assertFalse(snapshot.isConfigured)
        assertFalse(snapshot.isAuthenticated)
        assertEquals(0L, snapshot.revision)
        assertNull(snapshot.appUserIdentifier)
        assertNull(snapshot.market)
        assertNull(snapshot.customerInfo)
        assertNull(snapshot.offerings)
        assertFalse(snapshot.failedToLoadCustomerInfo)
        assertFalse(snapshot.failedToLoadOfferings)
        assertFalse(snapshot.toString().contains("token", ignoreCase = true))
    }

    @Test
    fun result_preservesAuthoritativeSnapshot() {
        val snapshot = InappifySnapshot.initial(sdkVersion = "test")
        val success = InappifyResult.Success(Unit, snapshot)
        val failure = InappifyResult.Failure(
            error = InappifyError(
                code = InappifyErrorCode.NETWORK,
                message = "Network request failed.",
                isRetryable = true,
            ),
        )

        assertSame(snapshot, success.snapshot)
        assertEquals(Unit, success.data)
        assertNull(failure.snapshot)
        assertEquals(InappifyErrorCode.NETWORK, failure.error.code)
    }

    @Test
    fun error_copiesDiagnosticDetails() {
        val source = mutableMapOf<String, Any?>("status" to 503)
        val error = InappifyError(
            code = InappifyErrorCode.NETWORK,
            message = "Network request failed.",
            details = source,
        )

        source["status"] = 200

        assertEquals(503, error.details["status"])
    }

    @Test
    fun purchaseRequest_isImmutableByValueAndRedactsIdentifiers() {
        val first = InappifyPurchaseRequest(
            productIdentifier = "annual-product",
            offeringIdentifier = "premium-offering",
            packageIdentifier = "annual-package",
            discountCode = "WELCOME15",
            country = "IR",
            appVersion = "4.5.6",
            apiKey = "purchase-api-key",
            discount = 15,
            isCrypto = false,
            market = InappifyMarket.BAZAAR,
            marketKey = "bazaar-public-key",
            isLostPurchase = true,
            lostPurchaseToken = "lost-purchase-token",
            lostPurchaseTime = 1_725_000_000_000L,
            idempotencyKey = "retry-correlation-id",
            dynamicPriceToken = "dynamic-price-token",
        )
        val second = InappifyPurchaseRequest(
            productIdentifier = "annual-product",
            offeringIdentifier = "premium-offering",
            packageIdentifier = "annual-package",
            discountCode = "WELCOME15",
            country = "IR",
            appVersion = "4.5.6",
            apiKey = "purchase-api-key",
            discount = 15,
            isCrypto = false,
            market = InappifyMarket.BAZAAR,
            marketKey = "bazaar-public-key",
            isLostPurchase = true,
            lostPurchaseToken = "lost-purchase-token",
            lostPurchaseTime = 1_725_000_000_000L,
            idempotencyKey = "retry-correlation-id",
            dynamicPriceToken = "dynamic-price-token",
        )
        val rendered = first.toString()

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertFalse(rendered.contains("annual-product"))
        assertFalse(rendered.contains("premium-offering"))
        assertFalse(rendered.contains("annual-package"))
        assertFalse(rendered.contains("WELCOME15"))
        assertFalse(rendered.contains("purchase-api-key"))
        assertFalse(rendered.contains("bazaar-public-key"))
        assertFalse(rendered.contains("lost-purchase-token"))
        assertFalse(rendered.contains("1725000000000"))
        assertFalse(rendered.contains("retry-correlation-id"))
        assertFalse(rendered.contains("dynamic-price-token"))
        assertTrue(rendered.contains("discount=15"))
        assertTrue(rendered.contains("market=BAZAAR"))
        assertTrue(rendered.contains("isLostPurchase=true"))
    }

    @Test
    fun purchase_isTokenFreeByContractAndRedactsCorrelationValues() {
        val first = InappifyPurchase(
            attemptId = "attempt-id",
            productIdentifier = "annual-product",
            offeringIdentifier = "premium-offering",
            market = InappifyMarket.BAZAAR,
            purchaseStatus = InappifyPurchaseStatus.NEEDTOPAY,
            packageIdentifier = "annual-package",
            url = "https://checkout.example/private",
            checkoutId = "checkout-id",
            checkoutStatus = "PENDING",
            nextActionType = "REDIRECT",
        )
        val second = InappifyPurchase(
            attemptId = "attempt-id",
            productIdentifier = "annual-product",
            offeringIdentifier = "premium-offering",
            market = InappifyMarket.BAZAAR,
            purchaseStatus = InappifyPurchaseStatus.NEEDTOPAY,
            packageIdentifier = "annual-package",
            url = "https://checkout.example/private",
            checkoutId = "checkout-id",
            checkoutStatus = "PENDING",
            nextActionType = "REDIRECT",
        )
        val rendered = first.toString()

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertFalse(rendered.contains("attempt-id"))
        assertFalse(rendered.contains("annual-product"))
        assertFalse(rendered.contains("premium-offering"))
        assertFalse(rendered.contains("annual-package"))
        assertFalse(rendered.contains("https://checkout.example/private"))
        assertFalse(rendered.contains("checkout-id"))
        assertTrue(rendered.contains("purchaseStatus=NEEDTOPAY"))
    }

    @Test
    fun purchaseStatus_matchesTheNullableBackendContract() {
        assertEquals(
            InappifyPurchaseStatus.DONE,
            InappifyPurchaseStatus.fromServerValue("DONE"),
        )
        assertEquals(
            InappifyPurchaseStatus.NEEDTOPAY,
            InappifyPurchaseStatus.fromServerValue("NEEDTOPAY"),
        )
        assertNull(InappifyPurchaseStatus.fromServerValue(null))
        listOf("future-state", "NEEDS_PAYMENT", "NEED_TO_PAY", " DONE ", "done")
            .forEach { malformed ->
                assertThrows(IllegalArgumentException::class.java) {
                    InappifyPurchaseStatus.fromServerValue(malformed)
                }
            }
    }

    @Suppress("unused")
    private class FakeClient : InappifyClient {
        override var snapshot: InappifySnapshot =
            InappifySnapshot.initial(sdkVersion = "test")

        override suspend fun configure(
            options: InappifyOptions,
        ): InappifyResult<Unit> = InappifyResult.Success(Unit, snapshot)

        override suspend fun login(
            request: InappifyLoginRequest,
        ): InappifyResult<Unit> = InappifyResult.Success(Unit, snapshot)

        override suspend fun logout(): InappifyResult<Unit> =
            InappifyResult.Success(Unit, snapshot)

        override suspend fun getCustomerInfo(
            forceRefresh: Boolean,
        ): InappifyResult<InappifyCustomerInfo> =
            InappifyResult.Success(InappifyCustomerInfo(), snapshot)

        override suspend fun refreshCustomerInfo(): InappifyResult<InappifyCustomerInfo> =
            getCustomerInfo()

        override suspend fun getOfferings(): InappifyResult<InappifyOfferings> =
            InappifyResult.Success(InappifyOfferings(), snapshot)

        override suspend fun refreshOfferings(): InappifyResult<InappifyOfferings> =
            getOfferings()

        override suspend fun validateDiscountCode(
            request: InappifyDiscountCodeRequest,
        ): InappifyResult<InappifyDiscountCodeResult> = InappifyResult.Success(
            InappifyDiscountCodeResult(),
            snapshot,
        )

        override suspend fun setTargetingContext(
            country: String?,
            appVersion: String?,
        ): InappifyResult<Unit> = InappifyResult.Success(Unit, snapshot)

        override suspend fun getCurrentOffering(
            placementIdentifier: String?,
            forceRefresh: Boolean,
            context: InappifyOfferingEvaluationContext?,
        ): InappifyResult<InappifyOffering?> = InappifyResult.Success(null, snapshot)

        override suspend fun checkEntitlement(
            identifier: String,
            forceRefresh: Boolean,
        ): InappifyResult<Boolean> = InappifyResult.Success(false, snapshot)

        override suspend fun setAttributes(
            request: InappifyAttributesRequest,
        ): InappifyResult<List<InappifyAttribute>> =
            InappifyResult.Success(request.attributes, snapshot)

        override suspend fun deleteAttributes(
            request: InappifyDeleteAttributesRequest,
        ): InappifyResult<List<InappifyAttribute>> =
            InappifyResult.Success(emptyList(), snapshot)

        override suspend fun setReservedAttribute(
            request: InappifyReservedAttributeRequest,
        ): InappifyResult<Unit> = InappifyResult.Success(Unit, snapshot)

        override suspend fun syncAttributes(
            request: InappifyAttributesRequest?,
        ): InappifyResult<List<InappifyAttribute>> = InappifyResult.Success(
            request?.attributes.orEmpty(),
            snapshot,
        )

        override suspend fun canSetReservedAttribute(
            key: String,
        ): InappifyResult<Boolean> = InappifyResult.Success(
            key.isNotBlank(),
            snapshot,
        )

        override suspend fun purchase(
            request: InappifyPurchaseRequest,
        ): InappifyResult<InappifyPurchase> = unsupportedPurchase()

        override suspend fun purchase(
            activity: Activity,
            request: InappifyPurchaseRequest,
        ): InappifyResult<InappifyPurchase> = unsupportedPurchase()

        private fun unsupportedPurchase(): InappifyResult<InappifyPurchase> =
            InappifyResult.Failure(
                InappifyError(
                    code = InappifyErrorCode.UNSUPPORTED_OPERATION,
                    message = "Purchases are not supported by this fake.",
                ),
                snapshot,
            )

        override suspend fun syncPurchases(): InappifyResult<List<InappifyPurchase>> =
            InappifyResult.Success(emptyList(), snapshot)

        override fun addEventListener(
            listener: InappifyEventListener,
        ): InappifyListenerRegistration =
            InappifyListenerRegistration.create(Runnable {})

        override fun close() = Unit
    }
}
