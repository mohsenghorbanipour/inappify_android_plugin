package com.inappify.sdk

import com.google.gson.JsonParser
import com.inappify.sdk.internal.network.ConfigureApiRequest
import com.inappify.sdk.internal.network.DefaultInappifyService
import com.inappify.sdk.internal.network.LoginApiRequest
import com.inappify.sdk.internal.network.LogoutApiRequest
import com.inappify.sdk.internal.network.OkHttpTransport
import com.inappify.sdk.internal.network.PurchaseApiRequest
import com.inappify.sdk.internal.network.RemoveAttributesApiRequest
import com.inappify.sdk.internal.network.RefreshSessionApiRequest
import com.inappify.sdk.internal.network.ResourceApiRequest
import com.inappify.sdk.internal.network.ServiceFailureKind
import com.inappify.sdk.internal.network.ServiceResult
import com.inappify.sdk.internal.network.StoreAttributesApiRequest
import com.inappify.sdk.internal.network.StoreReservedAttributeApiRequest
import com.inappify.sdk.internal.network.SyncAttributesApiRequest
import com.inappify.sdk.internal.network.ValidateDiscountCodeApiRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DefaultInappifyServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: DefaultInappifyService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = DefaultInappifyService(
            OkHttpTransport.create(
                baseUrl = server.url("/app/v1/"),
                client = OkHttpClient(),
            ),
            purchasePath = "purchase",
        )
    }

    @After
    fun tearDown() {
        service.close()
        server.shutdown()
    }

    @Test
    fun configure_usesMobileContractAndDecodesSession() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Request-ID", "configure-request")
                .setBody(
                    """
                    {
                      "status": true,
                      "token": "anonymous-token",
                      "customerInfo": {
                        "originalAppUserId": "InaAnonymousId-1"
                      },
                      "storeInfo": "bazar",
                      "appId": 19,
                      "forceVersion": 5
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.configure(
            ConfigureApiRequest(
                apiKey = "mobile-api-key",
                packageIdentifier = "com.example.host",
                appUserIdentifier = "09120000000",
                versionName = "3.4.6",
                versionCode = 2046,
            ),
        ) as ServiceResult.Response

        val request = server.takeRequest()
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("POST", request.method)
        assertEquals("/app/v1/configure", request.path)
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals(
            setOf(
                "apikey",
                "identifierValue",
                "appUserIdentifier",
                "vName",
                "vCode",
            ),
            json.keySet(),
        )
        assertEquals("mobile-api-key", json["apikey"].asString)
        assertEquals("com.example.host", json["identifierValue"].asString)
        assertEquals("09120000000", json["appUserIdentifier"].asString)
        assertEquals("3.4.6", json["vName"].asString)
        assertEquals(2046L, json["vCode"].asLong)
        assertFalse(json.has("country"))
        assertEquals("configure-request", result.requestId)
        assertEquals("anonymous-token", result.payload.token)
        assertEquals("InaAnonymousId-1", result.payload.appUserIdentifier)
        assertEquals("bazar", result.payload.storeInfo)
        assertEquals(19L, result.payload.appId)
        assertEquals(5L, result.payload.forceVersion)
    }

    @Test
    fun responseIntegerFields_truncateAcrossMobileContracts() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": true,
                  "token": "anonymous-token",
                  "customerInfo": {"originalAppUserId": "InaAnonymousId-1"},
                  "appId": 19.9,
                  "forceVersion": 5.9
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": true,
                  "data": {"originalAppUserId": "customer"},
                  "forceVersion": 8.9
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"status": true, "forceVersion": 9, "offerings": [], "rules": []}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": true,
                  "forceVersion": 10.9,
                  "data": {"purchaseStatus": "DONE"}
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":true,"forceVersion":-8.9,"data":[]}""",
            ),
        )

        val lifecycle = service.configure(
            ConfigureApiRequest(
                apiKey = "key",
                packageIdentifier = "com.example.host",
                appUserIdentifier = null,
                versionName = "1.0.0",
                versionCode = 1,
            ),
        ) as ServiceResult.Response
        val customer = service.getCustomerInfo(
            ResourceApiRequest(apiKey = "key", token = "token", forceVersion = null),
        ) as ServiceResult.Response
        val offerings = service.getOfferings(
            ResourceApiRequest(apiKey = "key", token = "token", forceVersion = null),
        ) as ServiceResult.Response
        val purchase = service.purchase(
            PurchaseApiRequest(
                apiKey = "key",
                token = "token",
                appIdentifier = "com.example.host",
                country = "IR",
                productIdentifier = "product",
                offeringIdentifier = "main",
                purchaseTokenId = "store-token",
                discount = 0,
                isCrypto = false,
                forceVersion = null,
                appVersion = "1.0.0",
                purchaseStoreTime = null,
            ),
        ) as ServiceResult.Response
        val sync = service.syncAttributes(
            SyncAttributesApiRequest(
                apiKey = "key",
                token = "token",
                attributes = emptyList(),
                forceVersion = null,
            ),
        ) as ServiceResult.Response

        assertEquals(19L, lifecycle.payload.appId)
        assertEquals(5L, lifecycle.payload.forceVersion)
        assertEquals(8L, customer.payload.forceVersion)
        assertEquals(9L, offerings.payload.forceVersion)
        assertEquals(10L, purchase.payload.forceVersion)
        assertEquals(-8L, sync.payload.forceVersion)
    }

    @Test
    fun generatedApiResponse_preservesEnvelopeIntegerOutsideIntRangeAfterTruncation() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "status": true,
                      "appId": 2147483648.9,
                      "forceVersion": 5
                    }
                    """.trimIndent(),
                ),
            )

            val result = service.configure(
                ConfigureApiRequest(
                    apiKey = "key",
                    packageIdentifier = "com.example.host",
                    appUserIdentifier = null,
                    versionName = "1.0.0",
                    versionCode = 1,
                ),
            ) as ServiceResult.Response

            assertEquals(2_147_483_648L, result.payload.appId)
            assertEquals(5L, result.payload.forceVersion)
        }

    @Test
    fun generatedApiResponse_rejectsEnvelopeIntegerOutsideLongRangeAfterTruncation() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"status":true,"appId":9223372036854775808.9}""",
                ),
            )

            val result = service.configure(
                ConfigureApiRequest(
                    apiKey = "key",
                    packageIdentifier = "com.example.host",
                    appUserIdentifier = null,
                    versionName = "1.0.0",
                    versionCode = 1L,
                ),
            ) as ServiceResult.Failure

            assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, result.kind)
        }

    @Test
    fun loginAndLogout_sendExactLifecycleFields() = runBlocking {
        server.enqueue(successResponse("customer-token", "09120000000"))
        server.enqueue(successResponse("anonymous-token", "InaAnonymousId-2"))

        val login = service.login(
            LoginApiRequest(
                apiKey = "mobile-api-key",
                appUserIdentifier = "09120000000",
                forceVersion = 6,
                token = "anonymous-token",
            ),
        ) as ServiceResult.Response
        val loginRequest = server.takeRequest()
        val loginJson = JsonParser.parseString(
            loginRequest.body.readUtf8(),
        ).asJsonObject

        assertEquals("/app/v1/login", loginRequest.path)
        assertEquals(
            setOf("apikey", "appUserIdentifier", "forceVersion", "token"),
            loginJson.keySet(),
        )
        assertEquals("anonymous-token", loginJson["token"].asString)
        assertEquals("customer-token", login.payload.token)

        val logout = service.logout(
            LogoutApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                forceVersion = 6,
            ),
        ) as ServiceResult.Response
        val logoutRequest = server.takeRequest()
        val logoutJson = JsonParser.parseString(
            logoutRequest.body.readUtf8(),
        ).asJsonObject

        assertEquals("/app/v1/logout", logoutRequest.path)
        assertEquals(
            setOf("apikey", "token", "forceVersion"),
            logoutJson.keySet(),
        )
        assertEquals("customer-token", logoutJson["token"].asString)
        assertEquals("anonymous-token", logout.payload.token)
    }

    @Test
    fun refreshSession_usesCustomerInfoContract() = runBlocking {
        server.enqueue(successResponse("unused-token", "09120000000"))

        val result = service.refreshSession(
            RefreshSessionApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                forceVersion = 6,
            ),
        ) as ServiceResult.Response
        val request = server.takeRequest()
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject

        assertEquals("/app/v1/customerInfo", request.path)
        assertEquals(
            setOf("apikey", "token", "forceVersion"),
            json.keySet(),
        )
        assertEquals("mobile-api-key", json["apikey"].asString)
        assertEquals("customer-token", json["token"].asString)
        assertEquals(6L, json["forceVersion"].asLong)
        assertEquals("09120000000", result.payload.appUserIdentifier)
    }

    @Test
    fun getCustomerInfo_decodesGenericDataButConsumesOnlyRootCustomerInfo() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "status": true,
                      "data": {
                        "originalAppUserId": "customer-from-data",
                        "hasUsedTrial": false
                      },
                      "forceVersion": 8
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.getCustomerInfo(
            ResourceApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                forceVersion = 7,
            ),
        ) as ServiceResult.Response
        val request = server.takeRequest()
        val requestJson = JsonParser.parseString(
            request.body.readUtf8(),
        ).asJsonObject
        assertEquals("/app/v1/customerInfo", request.path)
        assertEquals(
            setOf("apikey", "token", "forceVersion"),
            requestJson.keySet(),
        )
        assertEquals("mobile-api-key", requestJson["apikey"].asString)
        assertEquals("customer-token", requestJson["token"].asString)
        assertEquals(7L, requestJson["forceVersion"].asLong)
        assertNull(result.payload.appUserIdentifier)
        assertNull(result.payload.customerInfoJson)
        assertEquals(8L, result.payload.forceVersion)
    }

    @Test
    fun getCustomerInfo_preservesMissingStatusForceAfterValidGenericDecode() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "customerInfo": {"originalAppUserId": "customer"},
                      "forceVersion": 9
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.getCustomerInfo(
            ResourceApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                forceVersion = 8L,
            ),
        ) as ServiceResult.Response

        assertNull(result.payload.status)
        assertEquals(9L, result.payload.forceVersion)
        assertEquals("customer", result.payload.appUserIdentifier)
    }

    @Test
    fun generatedEnvelopeWrongStringType_discardsForceWithMalformedFailure() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "status": false,
                      "token": 42,
                      "forceVersion": 10
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.login(
            LoginApiRequest(
                apiKey = "mobile-api-key",
                appUserIdentifier = "customer",
                forceVersion = 8L,
                token = "anonymous-token",
            ),
        ) as ServiceResult.Failure

        assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, result.kind)
    }

    @Test
    fun getOfferings_acceptsMissingStatusAndDecodesCanonicalPayload() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "forceVersion": 9,
                      "offerings": [
                        {"identifier": "main", "isDefault": true}
                      ],
                      "rules": [
                        {"default_offering": "main", "sort": 1}
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.getOfferings(
            ResourceApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                forceVersion = 8,
            ),
        ) as ServiceResult.Response
        val request = server.takeRequest()
        val requestJson = JsonParser.parseString(
            request.body.readUtf8(),
        ).asJsonObject
        val offeringsJson = JsonParser.parseString(
            requireNotNull(result.payload.offeringsJson),
        ).asJsonObject

        assertEquals("/app/v1/offerings", request.path)
        assertEquals(
            setOf("apikey", "token", "forceVersion"),
            requestJson.keySet(),
        )
        assertEquals("mobile-api-key", requestJson["apikey"].asString)
        assertEquals("customer-token", requestJson["token"].asString)
        assertEquals(8L, requestJson["forceVersion"].asLong)
        assertTrue(result.payload.status == true)
        assertEquals(9L, result.payload.forceVersion)
        assertEquals(
            "main",
            offeringsJson["offerings"].asJsonArray[0]
                .asJsonObject["identifier"].asString,
        )
        assertEquals(
            "main",
            offeringsJson["rules"].asJsonArray[0]
                .asJsonObject["default_offering"].asString,
        )
        assertFalse(offeringsJson.has("forceVersion"))
    }

    @Test
    fun getOfferings_ignoresExplicitFailureStatusOnHttp200() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "status": false,
                      "errorCode": "session_expired",
                      "message": "Session expired."
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.getOfferings(
            ResourceApiRequest(
                apiKey = "mobile-api-key",
                token = "expired-token",
                forceVersion = 8,
            ),
        ) as ServiceResult.Response
        val offeringsJson = JsonParser.parseString(
            requireNotNull(result.payload.offeringsJson),
        ).asJsonObject

        assertTrue(result.payload.status == true)
        assertEquals("session_expired", result.payload.errorCode)
        assertTrue(offeringsJson["offerings"].asJsonArray.isEmpty)
        assertTrue(offeringsJson["rules"].asJsonArray.isEmpty)
    }

    @Test
    fun getOfferings_rejectsMalformedSuccessfulCollections() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "status": true,
                      "offerings": "not-an-array",
                      "rules": []
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.getOfferings(
            ResourceApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                forceVersion = 8,
            ),
        ) as ServiceResult.Failure

        assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, result.kind)
    }

    @Test
    fun getOfferings_ignoresWrongTypeStatusOnHttp200() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "status": "true",
                      "offerings": [],
                      "rules": []
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.getOfferings(
            ResourceApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                forceVersion = 8,
            ),
        ) as ServiceResult.Response

        assertTrue(result.payload.status == true)
        assertEquals(
            "{\"offerings\":[],\"rules\":[]}",
            result.payload.offeringsJson,
        )
    }

    @Test
    fun getOfferings_rejectsNonIntegerRootForceVersion() = runBlocking {
        listOf("9.0", "9.9", "9e0").forEach { forceVersion ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "status": true,
                          "forceVersion": $forceVersion,
                          "offerings": [],
                          "rules": []
                        }
                        """.trimIndent(),
                    ),
            )

            val result = service.getOfferings(
                ResourceApiRequest(
                    apiKey = "mobile-api-key",
                    token = "customer-token",
                    forceVersion = 8,
                ),
            ) as ServiceResult.Failure

            assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, result.kind)
        }
    }

    @Test
    fun getOfferings_acceptsStrictIntegerForceVersionOutsideIntRange() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": true,
                  "forceVersion": 2147483648,
                  "offerings": [],
                  "rules": []
                }
                """.trimIndent(),
            ),
        )

        val result = service.getOfferings(
            ResourceApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                forceVersion = 8L,
            ),
        ) as ServiceResult.Response

        assertEquals(2_147_483_648L, result.payload.forceVersion)
    }

    @Test
    fun purchase_usesLegacyBackendContractAndDecodesTokenFreeResult() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Request-ID", "purchase-request")
                .setBody(
                    """
                    {
                      "status": true,
                      "forceVersion": 10,
                      "data": {
                        "url": null,
                        "purchaseStatus": "DONE",
                        "checkoutId": null,
                        "checkoutStatus": null,
                        "nextActionType": null
                      }
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.purchase(
            PurchaseApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                appIdentifier = "com.example.host",
                country = "IR",
                productIdentifier = "premium-monthly",
                offeringIdentifier = "main",
                purchaseTokenId = "store-token",
                discount = 15,
                isCrypto = false,
                forceVersion = 9,
                appVersion = "3.4.6",
                purchaseStoreTime = 1_725_000_000_000L,
            ),
        ) as ServiceResult.Response

        val request = server.takeRequest()
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("/app/v1/purchase", request.path)
        assertEquals(
            setOf(
                "apikey",
                "token",
                "appIdentifier",
                "country",
                "productIdentifier",
                "purchaseTokenId",
                "discount",
                "isCrypto",
                "forceVersion",
                "offeringIdentifier",
                "appVersion",
                "purchaseStoreTime",
            ),
            json.keySet(),
        )
        assertEquals("mobile-api-key", json["apikey"].asString)
        assertEquals("customer-token", json["token"].asString)
        assertEquals("com.example.host", json["appIdentifier"].asString)
        assertEquals("premium-monthly", json["productIdentifier"].asString)
        assertEquals("store-token", json["purchaseTokenId"].asString)
        assertEquals(15L, json["discount"].asLong)
        assertEquals(0, json["isCrypto"].asInt)
        assertEquals(1_725_000_000_000L, json["purchaseStoreTime"].asLong)
        assertEquals("purchase-request", result.requestId)
        assertEquals(10L, result.payload.forceVersion)
        assertEquals("DONE", result.payload.purchase?.purchaseStatus)
        assertFalse(result.payload.toString().contains("store-token"))
    }

    @Test
    fun purchase_rejectsMalformedSuccessfulData() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "status": true,
                      "data": {"purchaseStatus": 1}
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.purchase(
            PurchaseApiRequest(
                apiKey = "key",
                token = "token",
                appIdentifier = "com.example.host",
                country = "IR",
                productIdentifier = "product",
                offeringIdentifier = "main",
                purchaseTokenId = null,
                discount = 0,
                isCrypto = false,
                forceVersion = 1,
                appVersion = "1.0.0",
                purchaseStoreTime = null,
            ),
        ) as ServiceResult.Failure

        assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, result.kind)
    }

    @Test
    fun purchase_rejectsUnknownStatusBeforeExposingEnvelopeForceVersion() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"status":true,"forceVersion":19,"data":{"purchaseStatus":"UNKNOWN"}}""",
                ),
        )

        val result = service.purchase(purchaseRequest()) as ServiceResult.Failure

        assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, result.kind)
    }

    @Test
    fun purchase_ignoresUnsupportedCheckoutSnakeCaseField() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"status":true,"forceVersion":18,"data":{"checkout_id":17}}""",
                ),
        )

        val result = service.purchase(purchaseRequest()) as ServiceResult.Response

        assertEquals(18L, result.payload.forceVersion)
        assertNull(result.payload.purchase?.checkoutId)
    }

    @Test
    fun purchase_httpOkBackendFailureRejectsMalformedNonNullData() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "status": false,
                      "forceVersion": 12,
                      "data": []
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.purchase(purchaseRequest()) as ServiceResult.Failure

        assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, result.kind)
    }

    @Test
    fun purchase_httpOkNullableStatusAndDataPreserveParsedForceVersion() = runBlocking {
        val cases = listOf(
            Triple(
                """{"status":false,"forceVersion":13,"data":null}""",
                13L,
                false,
            ),
            Triple(
                """{"status":false,"forceVersion":14,"data":{"purchaseStatus":"DONE"}}""",
                14L,
                false,
            ),
            Triple(
                """{"forceVersion":15,"data":{"purchaseStatus":"DONE"}}""",
                15L,
                null,
            ),
            Triple(
                """{"status":null,"forceVersion":16,"data":{"purchaseStatus":"DONE"}}""",
                16L,
                null,
            ),
            Triple(
                """{"status":true,"forceVersion":17,"data":null}""",
                17L,
                true,
            ),
        )

        cases.forEach { (body, forceVersion, status) ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(body))

            val result = service.purchase(purchaseRequest()) as ServiceResult.Response

            assertEquals(forceVersion, result.payload.forceVersion)
            assertEquals(status, result.payload.status)
            assertEquals(forceVersion !in setOf(13L, 17L), result.payload.purchase != null)
        }
    }

    @Test
    fun purchase_preservesBackendFailureWithoutParsingSuccessData() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setBody(
                    """
                    {
                      "status": false,
                      "errorCode": "invalid_purchase",
                      "message": "Purchase rejected.",
                      "data": []
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.purchase(
            PurchaseApiRequest(
                apiKey = "key",
                token = "token",
                appIdentifier = "com.example.host",
                country = "IR",
                productIdentifier = "product",
                offeringIdentifier = "main",
                purchaseTokenId = "store-token",
                discount = 0,
                isCrypto = false,
                forceVersion = 1,
                appVersion = "1.0.0",
                purchaseStoreTime = 1L,
            ),
        ) as ServiceResult.Response

        assertEquals(422, result.statusCode)
        assertEquals(false, result.payload.status)
        assertEquals("invalid_purchase", result.payload.errorCode)
        assertEquals("Purchase rejected.", result.payload.message)
    }

    private fun purchaseRequest(): PurchaseApiRequest = PurchaseApiRequest(
        apiKey = "key",
        token = "token",
        appIdentifier = "com.example.host",
        country = "IR",
        productIdentifier = "product",
        offeringIdentifier = "main",
        purchaseTokenId = null,
        discount = 0,
        isCrypto = false,
        forceVersion = 1,
        appVersion = "1.0.0",
        purchaseStoreTime = null,
    )

    @Test
    fun validateDiscountCode_usesBackendContractAndRetainsTypedData() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Request-ID", "discount-request")
                .setBody(
                    """
                    {
                      "status": true,
                      "forceVersion": 12,
                      "data": {
                        "is_valid": true,
                        "error_code": null,
                        "code": "WELCOME20",
                        "discount_id": 31,
                        "discount_code_id": 32,
                        "percent": 20,
                        "message": "Accepted",
                        "payment_links": [
                          {"offering": "main", "url": "https://example.invalid/pay"}
                        ],
                        "offering": {"identifier": "main", "isDefault": true}
                      }
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.validateDiscountCode(
            ValidateDiscountCodeApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                discountCode = "WELCOME20",
            ),
        ) as ServiceResult.Response
        val request = server.takeRequest()
        val requestJson = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val data = JsonParser.parseString(
            requireNotNull(result.payload.discountCodeResultJson),
        ).asJsonObject

        assertEquals("/app/v1/validateDiscountCode", request.path)
        assertEquals(setOf("apikey", "token", "code"), requestJson.keySet())
        assertEquals("mobile-api-key", requestJson["apikey"].asString)
        assertEquals("customer-token", requestJson["token"].asString)
        assertEquals("WELCOME20", requestJson["code"].asString)
        assertEquals("discount-request", result.requestId)
        assertEquals(12L, result.payload.forceVersion)
        assertTrue(data["is_valid"].asBoolean)
        assertEquals(20, data["percent"].asInt)
        assertEquals("main", data["offering"].asJsonObject["identifier"].asString)
    }

    @Test
    fun validateDiscountCode_acceptsValidDataIndependentOfStatusAndTruncatesForceVersion() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "status": false,
                          "forceVersion": 12.9,
                          "data": {"is_valid": true, "code": "FALSE-STATUS"}
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "forceVersion": -8.9,
                          "data": {"is_valid": true, "code": "MISSING-STATUS"}
                        }
                        """.trimIndent(),
                    ),
            )

            val falseStatus = service.validateDiscountCode(
                ValidateDiscountCodeApiRequest(
                    apiKey = "key",
                    token = "token",
                    discountCode = "FALSE-STATUS",
                ),
            ) as ServiceResult.Response
            val missingStatus = service.validateDiscountCode(
                ValidateDiscountCodeApiRequest(
                    apiKey = "key",
                    token = "token",
                    discountCode = "MISSING-STATUS",
                ),
            ) as ServiceResult.Response

            assertFalse(falseStatus.payload.status ?: true)
            assertEquals(12L, falseStatus.payload.forceVersion)
            assertTrue(falseStatus.payload.discountCodeResultJson?.contains("FALSE-STATUS") == true)
            assertEquals(null, missingStatus.payload.status)
            assertEquals(-8L, missingStatus.payload.forceVersion)
            assertTrue(
                missingStatus.payload.discountCodeResultJson?.contains("MISSING-STATUS") == true,
            )
        }

    @Test
    fun validateDiscountCode_rejectsNonBooleanStatusEvenWhenDataIsPresent() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "status": "false",
                      "data": {"is_valid": true, "code": "WRONG-TYPE"}
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.validateDiscountCode(
            ValidateDiscountCodeApiRequest(
                apiKey = "key",
                token = "token",
                discountCode = "WRONG-TYPE",
            ),
        ) as ServiceResult.Failure

        assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, result.kind)
    }

    @Test
    fun validateDiscountCode_rejectsMissingSuccessfulData() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":true,"data":null}"""),
        )

        val result = service.validateDiscountCode(
            ValidateDiscountCodeApiRequest(
                apiKey = "key",
                token = "token",
                discountCode = "WELCOME20",
            ),
        ) as ServiceResult.Failure

        assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, result.kind)
    }

    @Test
    fun attributeMutations_useExactBodiesAndAcceptAnyHttp200Body() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val stored = service.storeAttributes(
            StoreAttributesApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                attributes = listOf(
                    InappifyAttribute(key = "language", value = "fa"),
                    InappifyAttribute(key = "theme", value = "dark"),
                ),
                forceVersion = 4,
            ),
        ) as ServiceResult.Response
        val storeRequest = server.takeRequest()
        val storeJson = JsonParser.parseString(storeRequest.body.readUtf8()).asJsonObject

        assertEquals("/app/v1/storeAttributes", storeRequest.path)
        assertEquals(
            setOf("apikey", "token", "attributes", "forceVersion"),
            storeJson.keySet(),
        )
        assertEquals("language", storeJson["attributes"].asJsonArray[0]
            .asJsonObject["key"].asString)
        assertEquals("fa", storeJson["attributes"].asJsonArray[0]
            .asJsonObject["value"].asString)
        assertEquals(4L, storeJson["forceVersion"].asLong)
        assertTrue(stored.payload.status == true)
        assertEquals(null, stored.payload.forceVersion)

        val removed = service.removeAttributes(
            RemoveAttributesApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                attributes = listOf(InappifyAttribute(key = "theme", value = "ignored")),
                forceVersion = null,
            ),
        ) as ServiceResult.Response
        val removeRequest = server.takeRequest()
        val removeJson = JsonParser.parseString(removeRequest.body.readUtf8()).asJsonObject

        assertEquals("/app/v1/removeAttributes", removeRequest.path)
        assertEquals(
            setOf("apikey", "token", "attributes", "forceVersion"),
            removeJson.keySet(),
        )
        assertEquals(
            setOf("key"),
            removeJson["attributes"].asJsonArray[0].asJsonObject.keySet(),
        )
        assertTrue(removeJson["forceVersion"].isJsonNull)
        assertTrue(removed.payload.status == true)

        val reserved = service.storeReservedAttribute(
            StoreReservedAttributeApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                key = "\$displayName",
                value = "Example User",
                forceVersion = 4,
            ),
        ) as ServiceResult.Response
        val reservedRequest = server.takeRequest()
        val reservedJson = JsonParser.parseString(
            reservedRequest.body.readUtf8(),
        ).asJsonObject

        assertEquals("/app/v1/storeReservedAttribute", reservedRequest.path)
        assertEquals(
            setOf("apikey", "token", "key", "value", "forceVersion"),
            reservedJson.keySet(),
        )
        assertEquals("\$displayName", reservedJson["key"].asString)
        assertEquals("Example User", reservedJson["value"].asString)
        assertTrue(reserved.payload.status == true)
    }

    @Test
    fun deleteAttributes_routesToRemoveEndpoint() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = service.deleteAttributes(
            RemoveAttributesApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                attributes = listOf(InappifyAttribute(key = "language")),
                forceVersion = 7,
            ),
        ) as ServiceResult.Response
        val request = server.takeRequest()

        assertEquals("/app/v1/removeAttributes", request.path)
        assertTrue(result.payload.status == true)
    }

    @Test
    fun syncAttributes_usesBackendContractAndDecodesReturnedCollection() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "status": true,
                      "forceVersion": 8,
                      "data": [
                        {"key": "language", "value": "fa"},
                        {"key": "theme", "value": "dark"}
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.syncAttributes(
            SyncAttributesApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                attributes = listOf(InappifyAttribute(key = "language", value = "fa")),
                forceVersion = 7,
            ),
        ) as ServiceResult.Response
        val request = server.takeRequest()
        val requestJson = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val attributes = JsonParser.parseString(
            requireNotNull(result.payload.attributesJson),
        ).asJsonArray

        assertEquals("/app/v1/syncAttributes", request.path)
        assertEquals(
            setOf("apikey", "token", "attributes", "forceVersion"),
            requestJson.keySet(),
        )
        assertEquals("language", requestJson["attributes"].asJsonArray[0]
            .asJsonObject["key"].asString)
        assertEquals(2, attributes.size())
        assertEquals("dark", attributes[1].asJsonObject["value"].asString)
        assertEquals(8L, result.payload.forceVersion)
    }

    @Test
    fun syncAttributes_decodesHttp200DataIndependentlyOfNullableStatus() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"status":false,"forceVersion":5,"data":[{"key":"server","value":"one"}]}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"forceVersion":6,"data":[{"key":"server","value":"two"}]}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":true,"forceVersion":7,"data":null}"""),
        )

        suspend fun sync(): ServiceResult.Response = service.syncAttributes(
            SyncAttributesApiRequest(
                apiKey = "key",
                token = "token",
                attributes = emptyList(),
                forceVersion = 4,
            ),
        ) as ServiceResult.Response

        val falseStatus = sync()
        server.takeRequest()
        val nullStatus = sync()
        server.takeRequest()
        val nullData = sync()
        server.takeRequest()

        assertFalse(falseStatus.payload.status == true)
        assertEquals("server", JsonParser.parseString(
            requireNotNull(falseStatus.payload.attributesJson),
        ).asJsonArray[0].asJsonObject["key"].asString)
        assertEquals(null, nullStatus.payload.status)
        assertEquals(6L, nullStatus.payload.forceVersion)
        assertEquals(null, nullData.payload.attributesJson)
        assertTrue(nullData.payload.status == true)
    }

    @Test
    fun syncAttributes_rejectsMalformedSuccessfulCollection() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":true,"data":{"theme":"dark"}}"""),
        )

        val result = service.syncAttributes(
            SyncAttributesApiRequest(
                apiKey = "key",
                token = "token",
                attributes = emptyList(),
                forceVersion = null,
            ),
        ) as ServiceResult.Failure

        assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, result.kind)
    }

    @Test
    fun successfulHttpResponseWithInvalidJson_isMalformed() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("not-json"),
        )

        val result = service.configure(
            ConfigureApiRequest(
                apiKey = "mobile-api-key",
                packageIdentifier = "com.example.host",
                appUserIdentifier = null,
                versionName = "1.0.0",
                versionCode = 1,
            ),
        ) as ServiceResult.Failure

        assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, result.kind)
    }

    @Test
    fun nonSuccessHttpResponsePreservesSafeStatusMetadata() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("X-Request-ID", "unauthorized-request")
                .setBody(
                    """
                    {
                      "status": false,
                      "errorCode": "unauthorized",
                      "message": "Unauthorized request."
                    }
                    """.trimIndent(),
                ),
        )

        val result = service.login(
            LoginApiRequest(
                apiKey = "mobile-api-key",
                appUserIdentifier = "09120000000",
                forceVersion = 1,
                token = "anonymous-token",
            ),
        ) as ServiceResult.Response

        assertEquals(401, result.statusCode)
        assertEquals("unauthorized", result.payload.errorCode)
        assertEquals("unauthorized-request", result.requestId)
        assertTrue(result.payload.status == false)
    }

    private fun successResponse(
        token: String,
        identifier: String,
    ): MockResponse = MockResponse()
        .setResponseCode(200)
        .setBody(
            """
            {
              "status": true,
              "token": "$token",
              "customerInfo": {
                "originalAppUserId": "$identifier"
              },
              "forceVersion": 6
            }
            """.trimIndent(),
        )
}
