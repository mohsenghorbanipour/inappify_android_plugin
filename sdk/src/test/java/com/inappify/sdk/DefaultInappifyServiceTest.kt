package com.inappify.sdk

import com.google.gson.JsonParser
import com.inappify.sdk.internal.network.ConfigureApiRequest
import com.inappify.sdk.internal.network.ConsumableDeliveriesServiceResult
import com.inappify.sdk.internal.network.ConsumableDeliverySource
import com.inappify.sdk.internal.network.DefaultInappifyService
import com.inappify.sdk.internal.network.DirectConsumableDeliveryApiRequest
import com.inappify.sdk.internal.network.LoginApiRequest
import com.inappify.sdk.internal.network.LogoutApiRequest
import com.inappify.sdk.internal.network.OkHttpTransport
import com.inappify.sdk.internal.network.PendingConsumableDeliveriesApiRequest
import com.inappify.sdk.internal.network.PurchaseApiRequest
import com.inappify.sdk.internal.network.RemoveAttributesApiRequest
import com.inappify.sdk.internal.network.RefreshSessionApiRequest
import com.inappify.sdk.internal.network.ResourceApiRequest
import com.inappify.sdk.internal.network.ServiceFailureKind
import com.inappify.sdk.internal.network.ServiceResult
import com.inappify.sdk.internal.network.StoreAttributesApiRequest
import com.inappify.sdk.internal.network.StoreConsumeResult
import com.inappify.sdk.internal.network.StoreConsumeResultApiRequest
import com.inappify.sdk.internal.network.StoreDeliveryApiRequest
import com.inappify.sdk.internal.network.StorePurchaseApiRequest
import com.inappify.sdk.internal.network.StorePurchaseEvidence
import com.inappify.sdk.internal.network.StorePurchaseOperation
import com.inappify.sdk.internal.network.StorePurchaseStatus
import com.inappify.sdk.internal.network.StoreServiceResult
import com.inappify.sdk.internal.network.StoreVerificationStatusApiRequest
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

    @Test
    fun storeBackpressureDecodesHeaderEvenWithoutJson() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "120"))
        val result = service.getStoreVerificationStatus(StoreVerificationStatusApiRequest("key", "customer-token", 77)) as StoreServiceResult.Response
        assertEquals(503, result.statusCode)
        assertEquals(120L, result.retryAfterSeconds)
    }

    @Test
    fun directPurchaseIncludesOnlyExplicitPaywallAttribution() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":true,"data":{"purchaseStatus":"DONE"}}"""))
        service.purchase(PurchaseApiRequest("key", "token", "com.example", "IR", "product", "default",
            null, 0, false, 4, "2.0.0", null, paywallId = 15, paywallRevision = 3))
        val json = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals(15, json.get("paywallId").asInt)
        assertEquals(3, json.get("paywallRevision").asInt)
    }

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
            storeApiBaseUrl = server.url("/app/v2/store/").toString(),
            directApiBaseUrl = server.url("/app/v1/").toString(),
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
                      "storePlatform": "Bazar",
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
        assertEquals("Bazar", result.payload.storePlatform)
        assertEquals(19L, result.payload.appId)
        assertEquals(5L, result.payload.forceVersion)
    }

    @Test
    fun configure_normalizesBackendNumericStorePlatforms() = runBlocking {
        listOf(
            1L to "DirectIos",
            2L to "DirectAndroid",
            3L to "DirectWeb",
            5L to "PlayStore",
            6L to "AppStore",
            10L to "Bazar",
            11L to "MyKet",
            12L to "SibApp",
        ).forEach { (wireValue, expectedRoute) ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "status": true,
                      "token": "customer-token",
                      "customerInfo": {
                        "originalAppUserId": "customer-1"
                      },
                      "storePlatform": $wireValue,
                      "appId": 3,
                      "forceVersion": 1
                    }
                    """.trimIndent(),
                ),
            )

            val result = service.configure(
                ConfigureApiRequest(
                    apiKey = "mobile-api-key",
                    packageIdentifier = "com.example.host",
                    appUserIdentifier = "customer-1",
                    versionName = "3.4.7",
                    versionCode = 4034,
                ),
            ) as ServiceResult.Response

            assertEquals("customer-token", result.payload.token)
            assertEquals("customer-1", result.payload.appUserIdentifier)
            assertEquals(expectedRoute, result.payload.storePlatform)
            assertEquals(3L, result.payload.appId)
            assertEquals(1L, result.payload.forceVersion)
        }
    }

    @Test
    fun configure_acceptsUnknownNumericStorePlatformAsMissing() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":true,"storePlatform":99}""",
            ),
        )

        val result = service.configure(
            ConfigureApiRequest(
                apiKey = "mobile-api-key",
                packageIdentifier = "com.example.host",
                appUserIdentifier = null,
                versionName = "3.4.7",
                versionCode = 4034,
            ),
        ) as ServiceResult.Response

        assertNull(result.payload.storePlatform)
    }

    @Test
    fun configure_rejectsMalformedLegacyStorePlatformValues() = runBlocking {
        val malformedValues = listOf(
            "true",
            "{}",
            "[]",
            "2.5",
            "-1",
            "9223372036854775808",
        )

        malformedValues.forEach { malformedValue ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"status":true,"storePlatform":$malformedValue}""",
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
            ) as ServiceResult.Failure

            assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, result.kind)
        }
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
    fun pendingConsumables_usesV1ContractAndToleratesNullableFutureFields() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": true,
                  "data": {
                    "deliveries": [{
                      "deliveryId": 81,
                      "status": "DELIVERY_REQUIRED",
                      "source": "direct",
                      "paymentId": null,
                      "transactionId": null,
                      "productIdentifier": "coins-100",
                      "alreadyDelivered": false,
                      "consumeAttempts": null,
                      "futureField": {"ignored": true}
                    }],
                    "nextCursor": "ignored"
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = service.getPendingConsumableDeliveries(
            PendingConsumableDeliveriesApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
            ),
        ) as ConsumableDeliveriesServiceResult.Response
        val request = server.takeRequest()
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject

        assertEquals("POST", request.method)
        assertEquals("/app/v1/consumable-deliveries/pending", request.path)
        assertEquals(setOf("apikey", "token"), json.keySet())
        assertEquals("mobile-api-key", json["apikey"].asString)
        assertEquals("customer-token", json["token"].asString)
        val delivery = result.payload.deliveries.single()
        assertEquals(81L, delivery.deliveryId)
        assertEquals(StorePurchaseStatus.DELIVERY_REQUIRED, delivery.status)
        assertEquals(ConsumableDeliverySource.DIRECT, delivery.source)
        assertEquals("coins-100", delivery.productIdentifier)
        assertNull(delivery.paymentId)
        assertNull(delivery.transactionId)
        assertNull(delivery.consumeAttempts)
        assertFalse(delivery.alreadyDelivered ?: true)
    }

    @Test
    fun directDelivered_usesSameDeliveryIdAndDecodesCompletedState() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": true,
                  "data": {
                    "deliveryId": 82,
                    "status": "COMPLETED",
                    "source": "direct",
                    "paymentId": 900,
                    "transactionId": "transaction-82",
                    "productIdentifier": "coins-500",
                    "alreadyDelivered": true,
                    "consumeAttempts": 0
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = service.markDirectConsumableDelivered(
            DirectConsumableDeliveryApiRequest(
                apiKey = "mobile-api-key",
                token = "customer-token",
                deliveryId = 82L,
            ),
        ) as ConsumableDeliveriesServiceResult.Response
        val request = server.takeRequest()
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject

        assertEquals("/app/v1/consumable-deliveries/82/delivered", request.path)
        assertEquals(setOf("apikey", "token"), json.keySet())
        val delivery = result.payload.deliveries.single()
        assertEquals(82L, delivery.deliveryId)
        assertEquals(StorePurchaseStatus.COMPLETED, delivery.status)
        assertTrue(delivery.alreadyDelivered == true)
    }

    @Test
    fun pendingConsumables_rejectsIncompatibleDeliveryContract() = runBlocking {
        listOf(
            """{"status":true,"data":{"deliveries":[{"deliveryId":1,"status":"CONSUME_REQUIRED","source":"direct","productIdentifier":"coins"}]}}""",
            """{"status":true,"data":{"deliveries":[{"deliveryId":1,"status":"DELIVERY_REQUIRED","source":"unknown","productIdentifier":"coins"}]}}""",
        ).forEach { body ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(body))

            val decoded = service.getPendingConsumableDeliveries(
                PendingConsumableDeliveriesApiRequest("key", "token"),
            )
            server.takeRequest()

            if (body.contains("unknown")) {
                assertTrue(decoded is ConsumableDeliveriesServiceResult.Failure)
            } else {
                val response = decoded as ConsumableDeliveriesServiceResult.Response
                assertEquals(StorePurchaseStatus.CONSUME_REQUIRED, response.payload.deliveries.single().status)
            }
        }
    }

    @Test
    fun pendingConsumables_preservesHttpErrorAndBoundedRetryAfter() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "7")
                .setBody(
                    """{"status":false,"errorCode":"RATE_LIMITED","message":"Retry later."}""",
                ),
        )

        val result = service.getPendingConsumableDeliveries(
            PendingConsumableDeliveriesApiRequest("key", "token"),
        ) as ConsumableDeliveriesServiceResult.Response

        assertEquals(429, result.statusCode)
        assertEquals(7L, result.retryAfterSeconds)
        assertEquals("RATE_LIMITED", result.payload.errorCode)
        assertTrue(result.payload.deliveries.isEmpty())
    }

    @Test
    fun submitStorePurchase_usesV2ContractAndDecodesNestedProcessingState() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Request-ID", "store-purchase-request")
                .setBody(
                    """
                    {
                      "status": true,
                      "data": {
                        "purchase": {
                          "status": "PROCESSING",
                          "paymentId": null,
                          "eventId": null,
                          "alreadyProcessed": false,
                          "deliveryId": null,
                          "verificationRequestId": 731,
                          "retryAfter": 2,
                          "errorCode": null,
                          "message": null
                        },
                        "hasForceUpdate": false,
                        "forceVersion": 2
                      }
                    }
                    """.trimIndent(),
                ),
        )

        val apiRequest = fullStorePurchaseRequest()
        val result = service.submitStorePurchase(apiRequest) as StoreServiceResult.Response
        val request = server.takeRequest()
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val purchase = json["purchase"].asJsonObject

        assertEquals("POST", request.method)
        assertEquals("/app/v2/store/purchases", request.path)
        assertEquals(
            setOf(
                "apikey",
                "token",
                "appIdentifier",
                "productIdentifier",
                "offeringIdentifier",
                "country",
                "appVersion",
                "forceVersion",
                "operation",
                "purchase",
            ),
            json.keySet(),
        )
        assertEquals("mobile-api-key", json["apikey"].asString)
        assertEquals("customer-token", json["token"].asString)
        assertEquals("com.example.host", json["appIdentifier"].asString)
        assertEquals("premium-monthly", json["productIdentifier"].asString)
        assertEquals("main", json["offeringIdentifier"].asString)
        assertEquals("IR", json["country"].asString)
        assertEquals("3.4.6", json["appVersion"].asString)
        assertEquals(9L, json["forceVersion"].asLong)
        assertEquals("purchase", json["operation"].asString)
        assertEquals(
            setOf(
                "token",
                "purchaseTime",
                "orderId",
                "packageName",
                "developerPayload",
                "originalJson",
                "signature",
            ),
            purchase.keySet(),
        )
        assertEquals("store-token-secret", purchase["token"].asString)
        assertEquals(1_725_000_000_000L, purchase["purchaseTime"].asLong)
        assertEquals("order-secret", purchase["orderId"].asString)
        assertEquals("com.example.host", purchase["packageName"].asString)
        assertEquals("payload-secret", purchase["developerPayload"].asString)
        assertEquals("raw-json-secret", purchase["originalJson"].asString)
        assertEquals("signature-secret", purchase["signature"].asString)

        assertEquals("store-purchase-request", result.requestId)
        assertTrue(result.payload.status == true)
        assertFalse(result.payload.hasForceUpdate ?: true)
        assertEquals(2L, result.payload.forceVersion)
        assertEquals(StorePurchaseStatus.PROCESSING, result.payload.state?.status)
        assertEquals(731L, result.payload.state?.verificationRequestId)
        assertEquals(2L, result.payload.state?.retryAfter)
        assertFalse(result.payload.state?.alreadyProcessed ?: true)
        assertNull(result.payload.state?.paymentId)
        assertNull(result.payload.state?.deliveryId)

        val printable = apiRequest.toString() + result.payload.toString()
        listOf(
            "mobile-api-key",
            "customer-token",
            "store-token-secret",
            "payload-secret",
            "raw-json-secret",
            "signature-secret",
        ).forEach { secret -> assertFalse(printable.contains(secret)) }
    }

    @Test
    fun submitStoreRestore_omitsAbsentOptionalFields() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": true,
                  "data": {"purchase": {"status": "RESTORED"}},
                  "hasForceUpdate": false
                }
                """.trimIndent(),
            ),
        )

        val result = service.submitStorePurchase(
            StorePurchaseApiRequest(
                apiKey = "key",
                token = "customer",
                appIdentifier = "com.example.host",
                productIdentifier = "lifetime",
                offeringIdentifier = "main",
                country = "IR",
                appVersion = "1.0.0",
                forceVersion = null,
                operation = StorePurchaseOperation.RESTORE,
                purchase = StorePurchaseEvidence(
                    token = "owned-token",
                    purchaseTime = null,
                    orderId = null,
                    packageName = null,
                    developerPayload = null,
                    originalJson = null,
                    signature = null,
                ),
            ),
        ) as StoreServiceResult.Response
        val json = JsonParser.parseString(
            server.takeRequest().body.readUtf8(),
        ).asJsonObject

        assertFalse(json.has("forceVersion"))
        assertEquals("restore", json["operation"].asString)
        assertEquals(setOf("token"), json["purchase"].asJsonObject.keySet())
        assertEquals(StorePurchaseStatus.RESTORED, result.payload.state?.status)
    }

    @Test
    fun getStoreVerificationStatus_decodesEveryV2StatusFromFlatData() = runBlocking {
        StorePurchaseStatus.values().forEachIndexed { index, expectedStatus ->
            val errorFields = if (expectedStatus == StorePurchaseStatus.REJECTED) {
                """, "errorCode":"INVALID_PURCHASE", "message":"Rejected""""
            } else {
                """, "errorCode":null, "message":null"""
            }
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "status": true,
                      "data": {
                        "status": "${expectedStatus.wireValue}",
                        "paymentId": 401,
                        "eventId": 402,
                        "deliveryId": 403,
                        "verificationRequestId": 404,
                        "retryAfter": 0,
                        "alreadyProcessed": ${expectedStatus == StorePurchaseStatus.ALREADY_PROCESSED}
                        $errorFields
                      }
                    }
                    """.trimIndent(),
                ),
            )

            val result = service.getStoreVerificationStatus(
                StoreVerificationStatusApiRequest(
                    apiKey = "key",
                    token = "customer",
                    verificationRequestId = 700L + index,
                ),
            ) as StoreServiceResult.Response
            val request = server.takeRequest()
            val requestJson = JsonParser.parseString(
                request.body.readUtf8(),
            ).asJsonObject

            assertEquals(
                "/app/v2/store/verifications/${700L + index}/status",
                request.path,
            )
            assertEquals(setOf("apikey", "token"), requestJson.keySet())
            assertEquals(expectedStatus, result.payload.state?.status)
            assertEquals(401L, result.payload.state?.paymentId)
            assertEquals(402L, result.payload.state?.eventId)
            assertEquals(403L, result.payload.state?.deliveryId)
            assertEquals(404L, result.payload.state?.verificationRequestId)
            assertEquals(0L, result.payload.state?.retryAfter)
            if (expectedStatus == StorePurchaseStatus.REJECTED) {
                assertEquals("INVALID_PURCHASE", result.payload.state?.errorCode)
                assertEquals("Rejected", result.payload.state?.message)
            }
        }
    }

    @Test
    fun storeApi_rejectsMalformedSuccessfulContracts() = runBlocking {
        val malformedPollingBodies = listOf(
            """{"data":{"status":"PROCESSING"}}""",
            """{"status":"true","data":{"status":"PROCESSING"}}""",
            """{"status":true,"data":null}""",
            """{"status":true,"data":[]}""",
            """{"status":true,"data":{"status":"UNKNOWN"}}""",
            """{"status":true,"data":{"status":1}}""",
            """{"status":true,"data":{"status":"PROCESSING","retryAfter":2.5}}""",
            """{"status":true,"data":{"status":"PROCESSING","retryAfter":-1}}""",
            """{"status":true,"data":{"status":"COMPLETED","paymentId":"41"}}""",
            """{"status":true,"data":{"status":"COMPLETED","paymentId":0}}""",
            """{"status":true,"data":{"status":"COMPLETED","eventId":-1}}""",
            """{"status":true,"data":{"status":"DELIVERY_REQUIRED","deliveryId":0}}""",
            """{"status":true,"data":{"status":"PROCESSING","verificationRequestId":-1}}""",
            """{"status":true,"data":{"status":"COMPLETED","alreadyProcessed":"false"}}""",
            """{"status":true,"hasForceUpdate":"false","data":{"status":"COMPLETED"}}""",
            """{"status":true,"forceVersion":2.0,"data":{"status":"COMPLETED"}}""",
            """{"status":true,"data":{"status":"COMPLETED","hasForceUpdate":"false"}}""",
            """{"status":true,"data":{"status":"COMPLETED","forceVersion":2.0}}""",
        )

        malformedPollingBodies.forEach { body ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(body))
            val result = service.getStoreVerificationStatus(
                StoreVerificationStatusApiRequest(
                    apiKey = "key",
                    token = "customer",
                    verificationRequestId = 91,
                ),
            ) as StoreServiceResult.Failure
            server.takeRequest()

            assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, result.kind)
        }

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":true,"data":{"status":"PROCESSING"}}""",
            ),
        )
        val wrongInitialShape = service.submitStorePurchase(
            fullStorePurchaseRequest(),
        ) as StoreServiceResult.Failure

        assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, wrongInitialShape.kind)
    }

    @Test
    fun deliveryAndConsume_useExactBodiesAndAcceptNestedOrFlatState() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": true,
                  "data": {
                    "purchase": {
                      "status": "CONSUME_REQUIRED",
                      "deliveryId": 51
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": true,
                  "data": {
                    "status": "COMPLETED",
                    "paymentId": 61,
                    "eventId": 62,
                    "deliveryId": 51
                  }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": true,
                  "data": {
                    "purchase": {
                      "status": "CONSUME_REQUIRED",
                      "deliveryId": 52,
                      "retryAfter": 4
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val delivered = service.markStoreDeliveryDelivered(
            StoreDeliveryApiRequest(
                apiKey = "key",
                token = "customer",
                deliveryId = 51,
            ),
        ) as StoreServiceResult.Response
        val deliveredRequest = server.takeRequest()
        val deliveredJson = JsonParser.parseString(
            deliveredRequest.body.readUtf8(),
        ).asJsonObject

        assertEquals("/app/v2/store/deliveries/51/delivered", deliveredRequest.path)
        assertEquals(setOf("apikey", "token"), deliveredJson.keySet())
        assertEquals(StorePurchaseStatus.CONSUME_REQUIRED, delivered.payload.state?.status)

        val consumed = service.reportStoreConsumeResult(
            StoreConsumeResultApiRequest(
                apiKey = "key",
                token = "customer",
                deliveryId = 51,
                result = StoreConsumeResult.SUCCEEDED,
                errorCode = null,
            ),
        ) as StoreServiceResult.Response
        val consumedRequest = server.takeRequest()
        val consumedJson = JsonParser.parseString(
            consumedRequest.body.readUtf8(),
        ).asJsonObject

        assertEquals(
            "/app/v2/store/deliveries/51/consume-result",
            consumedRequest.path,
        )
        assertEquals(setOf("apikey", "token", "result"), consumedJson.keySet())
        assertEquals("succeeded", consumedJson["result"].asString)
        assertEquals(StorePurchaseStatus.COMPLETED, consumed.payload.state?.status)
        assertEquals(61L, consumed.payload.state?.paymentId)

        val consumeFailed = service.reportStoreConsumeResult(
            StoreConsumeResultApiRequest(
                apiKey = "key",
                token = "customer",
                deliveryId = 52,
                result = StoreConsumeResult.FAILED,
                errorCode = "STORE_TEMPORARILY_UNAVAILABLE",
            ),
        ) as StoreServiceResult.Response
        val failedRequest = server.takeRequest()
        val failedJson = JsonParser.parseString(
            failedRequest.body.readUtf8(),
        ).asJsonObject

        assertEquals(
            setOf("apikey", "token", "result", "errorCode"),
            failedJson.keySet(),
        )
        assertEquals("failed", failedJson["result"].asString)
        assertEquals(
            "STORE_TEMPORARILY_UNAVAILABLE",
            failedJson["errorCode"].asString,
        )
        assertEquals(StorePurchaseStatus.CONSUME_REQUIRED, consumeFailed.payload.state?.status)
        assertEquals(4L, consumeFailed.payload.state?.retryAfter)
    }

    @Test
    fun storeApi_preservesBackendErrorsWithoutInventingSuccessState() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """
                {
                  "status": false,
                  "errorCode": "APP_MISMATCH",
                  "message": "The purchase belongs to another app.",
                  "requestId": "store-error-request",
                  "data": []
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": false,
                  "errorCode": "INVALID_PURCHASE",
                  "message": "Rejected",
                  "data": null
                }
                """.trimIndent(),
            ),
        )

        val httpError = service.submitStorePurchase(
            fullStorePurchaseRequest(),
        ) as StoreServiceResult.Response
        server.takeRequest()
        val envelopeError = service.getStoreVerificationStatus(
            StoreVerificationStatusApiRequest(
                apiKey = "key",
                token = "customer",
                verificationRequestId = 44,
            ),
        ) as StoreServiceResult.Response

        assertEquals(422, httpError.statusCode)
        assertFalse(httpError.payload.status ?: true)
        assertNull(httpError.payload.state)
        assertEquals("APP_MISMATCH", httpError.payload.errorCode)
        assertEquals("store-error-request", httpError.requestId)
        assertEquals(200, envelopeError.statusCode)
        assertFalse(envelopeError.payload.status ?: true)
        assertNull(envelopeError.payload.state)
        assertEquals("INVALID_PURCHASE", envelopeError.payload.errorCode)
    }

    private fun fullStorePurchaseRequest(): StorePurchaseApiRequest =
        StorePurchaseApiRequest(
            apiKey = "mobile-api-key",
            token = "customer-token",
            appIdentifier = "com.example.host",
            productIdentifier = "premium-monthly",
            offeringIdentifier = "main",
            country = "IR",
            appVersion = "3.4.6",
            forceVersion = 9,
            operation = StorePurchaseOperation.PURCHASE,
            purchase = StorePurchaseEvidence(
                token = "store-token-secret",
                purchaseTime = 1_725_000_000_000L,
                orderId = "order-secret",
                packageName = "com.example.host",
                developerPayload = "payload-secret",
                originalJson = "raw-json-secret",
                signature = "signature-secret",
            ),
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
