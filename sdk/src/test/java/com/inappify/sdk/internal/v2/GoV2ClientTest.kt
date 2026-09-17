package com.inappify.sdk.internal.v2

import com.google.gson.*
import com.inappify.sdk.*
import com.inappify.sdk.internal.DefaultInappifyClient
import com.inappify.sdk.internal.domain.InappifyDomainJsonCodec
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.*
import com.inappify.sdk.internal.storage.PersistedSession
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GoV2ClientTest {
    @Test fun legacyCompanionKeepsPurchaseIdentityAndCannotOverwriteV1Session() = runBlocking {
        val legacyService = LegacyPurchaseFixtureService()
        val legacyStore = MemoryV2Store()
        val legacy = DefaultInappifyClient(legacyService, legacyStore,
            AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) }, "2.0.0")
        legacy.use {
            assertTrue(legacy.configure(InappifyOptions("legacy-key", "customer_demo_123456")) is InappifyResult.Success)
            client().use { sdk ->
                configured(sdk)
                assertTrue(sdk.bindLegacyPurchaseClient(legacy) is InappifyResult.Success)
                assertTrue(legacy.login(InappifyLoginRequest("legacy-key", "customer_other_123456")) is InappifyResult.Success)
                val base = transport.handler
                transport.handler = { req -> if (req.path == "offerings") transport.response(jsonObject(
                    legacyService.offerings).apply { addProperty("status", true); addProperty("forceVersion", 4); addProperty("hasForceUpdate", false) }) else base(req) }
                sdk.refreshOfferings()
                assertTrue(sdk.purchase(InappifyPurchaseRequest("product", "default")) is InappifyResult.Success)
                assertEquals("legacy-token-A", legacyService.lastPurchase?.token)
                assertEquals("legacy-key", legacyService.lastPurchase?.apiKey)
                assertEquals("legacy-token-B", legacyStore.value?.token)
                assertEquals("customer_other_123456", legacy.snapshot.appUserIdentifier)
                assertEquals(signing.subject, sdk.snapshot.appUserIdentifier)
            }
        }
    }
    private val signing = SigningFixture()
    private val store = MemoryV2Store()
    private val transport = V2Transport()
    private var identity = signing.subject
    private fun client(storage: MemoryV2Store = store): GoV2Client = GoV2Client(signing.config,
        GoApi(transport, { signing.now }, {}), storage,
        AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) },
        { signing.now }, Dispatchers.Unconfined, backgroundRecovery = false)
    private fun installHandler() {
        transport.handler = { req ->
            when (req.path) {
                "configure", "login" -> {
                    identity = jsonObject(req.jsonBody).string("appUserIdentifier")
                    transport.response(signing.envelope(identity))
                }
                "logout" -> { identity = anonymousId(); transport.response(signing.envelope(identity)) }
                "customerInfo" -> transport.response(signing.envelope(identity))
                "offerings" -> transport.response(jsonObject("""{"status":true,"forceVersion":4,"hasForceUpdate":false,"offerings":[{"identifier":"default","isDefault":true},{"identifier":"targeted"}],"currentOffering":"targeted","placements":{"onboarding":"default","settings":null}}"""))
                "attributes" -> TransportResult.Response(HttpResponse(204, null, null))
                "validateDiscountCode" -> transport.response(jsonObject("""{"status":true,"data":{"is_valid":false,"error_code":2,"code":"","discount_id":0,"discount_code_id":0,"percent":0,"message":"Invalid","payment_links":[]}}"""))
                else -> error("Unexpected endpoint")
            }
        }
    }
    private fun configured(client: GoV2Client) = runBlocking {
        installHandler()
        val result = client.configure(InappifyOptions("public-key", signing.subject))
        assertTrue(result.toString(), result is InappifyResult.Success)
    }
    @Test fun configureUsesExactV2BodyAndCommitsSignedScope() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val request = transport.requests.single()
            assertEquals(setOf("appUserIdentifier", "identifierValue", "versionName", "versionCode"), jsonObject(request.jsonBody).keySet())
            assertEquals(20400, jsonObject(request.jsonBody).number("versionCode"))
            assertTrue(sdk.isActiveEntitlement("pro")); assertEquals(signing.subject, sdk.snapshot.appUserIdentifier)
            assertFalse(store.value.toString().contains("go-session-token"))
        }
    }
    @Test fun invalidSignatureNeverCommitsTokenOrGrantsAccess() = runBlocking {
        client().use { sdk ->
            transport.handler = { transport.response(signing.envelope().apply { getAsJsonObject("customerInfo").addProperty("hasUsedTrial", true) }) }
            assertTrue(sdk.configure(InappifyOptions("key", signing.subject)) is InappifyResult.Failure)
            assertFalse(sdk.snapshot.isConfigured); assertFalse(sdk.hasEntitlement("pro")); assertNull(store.value)
        }
    }
    @Test fun failedRefreshPreservesVerifiedCache() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val before = sdk.snapshot.customerInfo
            transport.handler = { transport.response(signing.envelope().apply { getAsJsonObject("customerInfo").addProperty("hasUsedTrial", true) }) }
            assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Failure)
            assertEquals(before, sdk.snapshot.customerInfo)
            assertTrue(sdk.customerInfo(InappifyFetchPolicy.CACHE_ONLY) is InappifyResult.Success)
        }
    }
    @Test fun secureWriteFailureCannotPublishNewIdentity() = runBlocking {
        client().use { sdk ->
            configured(sdk); store.failSave = true
            assertTrue(sdk.login(InappifyLoginRequest("public-key", "customer_other_123456")) is InappifyResult.Failure)
            assertEquals(signing.subject, sdk.snapshot.appUserIdentifier)
        }
    }
    @Test fun loginMergeFailureRetainsOldIdentityAndCache() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            transport.handler = { transport.response(jsonObject("""{"status":false,"code":"CUSTOMER_MERGE_FAILED"}"""), 409) }
            assertTrue(sdk.login(InappifyLoginRequest("public-key", "customer_other_123456")) is InappifyResult.Failure)
            assertEquals(signing.subject, sdk.snapshot.appUserIdentifier); assertTrue(sdk.hasEntitlement("pro"))
            assertEquals(2, transport.requests.size)
        }
    }
    @Test fun loginClearsOldOfferingsAndAttributesBeforePublishingNewUser() = runBlocking {
        client().use { sdk ->
            configured(sdk); sdk.refreshOfferings(); sdk.queueAttributes(mapOf("campaign" to "old"))
            assertTrue(sdk.login(InappifyLoginRequest("public-key", "customer_other_123456")) is InappifyResult.Success)
            assertNull(sdk.snapshot.offerings)
            val attributesBefore = transport.requests.count { it.path == "attributes" }
            sdk.flushAttributes()
            assertEquals(attributesBefore, transport.requests.count { it.path == "attributes" })
        }
    }
    @Test fun offlineLogoutHidesIdentityAndPersistsPendingStateAcrossRestart() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
            assertTrue(sdk.logout() is InappifyResult.Failure)
            assertTrue(sdk.isLogoutPending); assertNull(sdk.snapshot.customerInfo); assertNull(sdk.snapshot.appUserIdentifier)
        }
        client().use { restarted ->
            assertTrue(restarted.configure(InappifyOptions("public-key")) is InappifyResult.Failure)
            assertTrue(restarted.isLogoutPending); assertFalse(restarted.hasEntitlement("pro"))
            installHandler(); assertTrue(restarted.recover() is InappifyResult.Success)
            assertFalse(restarted.isLogoutPending); assertTrue(restarted.isCustomerAnonymous(restarted.snapshot.appUserIdentifier))
        }
    }
    @Test fun verifiedCacheLoadsWithoutNetworkAndNeverCrossesApiKey() = runBlocking {
        client().use(::configured)
        transport.requests.clear()
        transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
        client().use { restarted ->
            assertTrue(restarted.configure(InappifyOptions("public-key")) is InappifyResult.Success)
            assertTrue(restarted.hasEntitlement("pro")); assertEquals(0, transport.requests.size)
        }
        client().use { other ->
            assertTrue(other.configure(InappifyOptions("other-key", signing.subject)) is InappifyResult.Failure)
            assertFalse(other.hasEntitlement("pro"))
        }
    }
    @Test fun twentyConcurrentExpiredRequestsUseOneConfigureAndOneReplay() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var customerCalls = 0
            transport.handler = { request ->
                if (request.path == "customerInfo" && ++customerCalls == 1) {
                    entered.complete(Unit); release.await()
                    transport.response(jsonObject("""{"status":false,"code":"SESSION_EXPIRED"}"""), 401)
                } else transport.response(signing.envelope())
            }
            val jobs = (1..20).map { async(start = CoroutineStart.UNDISPATCHED) { sdk.refreshCustomerInfo() } }
            entered.await(); yield(); release.complete(Unit)
            assertTrue(jobs.awaitAll().all { it is InappifyResult.Success })
            assertEquals(2, transport.requests.count { it.path == "configure" })
            assertEquals(2, transport.requests.count { it.path == "customerInfo" })
        }
    }
    @Test fun corruptedCacheCannotGrantAccessAndOnlineConfigureRepairsIt() = runBlocking {
        client().use(::configured)
        val previous = store.value!!
        val corrupt = jsonObject(previous.customerInfoJson!!).apply {
            getAsJsonObject("info").getAsJsonObject("customerInfo").addProperty("hasUsedTrial", true)
        }
        store.value = PersistedSession(null, null, null, 12, null,
            previous.apiKeyFingerprint, corrupt.toString(), null, null)
        transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
        client().use { restarted ->
            assertTrue(restarted.configure(InappifyOptions("public-key", signing.subject)) is InappifyResult.Failure)
            assertNull(restarted.snapshot.customerInfo)
            assertFalse(restarted.hasEntitlement("pro"))
            installHandler()
            assertTrue(restarted.configure(InappifyOptions("public-key", signing.subject)) is InappifyResult.Success)
            assertTrue(restarted.hasEntitlement("pro"))
        }
    }
    @Test fun configureContextChangeInvalidatesOnlyOfferingsCache() = runBlocking {
        client().use { sdk ->
            configured(sdk); sdk.refreshOfferings()
            val customer = sdk.snapshot.customerInfo
            assertNotNull(sdk.snapshot.offerings)
            assertTrue(sdk.configure(InappifyOptions("public-key", signing.subject, country = "TR", appVersion = "3.0.0")) is InappifyResult.Success)
            assertEquals(customer, sdk.snapshot.customerInfo)
            assertNull(sdk.snapshot.offerings)
            sdk.refreshOfferings()
            val body = jsonObject(transport.requests.last().jsonBody)
            assertEquals("TR", body.string("country")); assertEquals("3.0.0", body.string("appVersion"))
        }
    }
    @Test fun cancellingOneCallerDoesNotCancelSharedRefresh() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            transport.handler = { entered.complete(Unit); release.await(); transport.response(signing.envelope()) }
            val first = async { sdk.refreshCustomerInfo() }; entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { sdk.refreshCustomerInfo() }
            first.cancelAndJoin(); release.complete(Unit)
            assertTrue(second.await() is InappifyResult.Success)
            assertEquals(1, transport.requests.count { it.path == "customerInfo" })
        }
    }
    @Test fun repeated401StopsAfterOneReconfiguration() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            transport.handler = { req -> if (req.path == "configure") transport.response(signing.envelope()) else
                transport.response(jsonObject("""{"status":false,"code":"SESSION_INVALID"}"""), 401) }
            assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Failure)
            assertEquals(2, transport.requests.count { it.path == "configure" })
            assertEquals(2, transport.requests.count { it.path == "customerInfo" })
        }
    }
    @Test fun attributesCoalesceDeleteBatchAndNeverReturnServerValues() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            sdk.queueAttributes((1..101).associate { "key$it" to "initial" })
            sdk.queueAttributes(mapOf("key1" to "new", "key2" to null))
            assertTrue(sdk.flushAttributes() is InappifyResult.Success)
            val requests = transport.requests.filter { it.path == "attributes" }.map { jsonObject(it.jsonBody).getAsJsonObject("attributes") }
            assertEquals(listOf(50, 50, 1), requests.map { it.size() })
            assertEquals("new", requests.first().string("key1")); assertTrue(requests.first().get("key2").isJsonNull)
            assertNull(sdk.snapshot.customerInfo?.attributes)
            sdk.flushAttributes(); assertEquals(3, transport.requests.count { it.path == "attributes" })
        }
    }
    @Test fun attributeTimeoutRetainsQueueAnd422QuarantinesRejectedBatch() = runBlocking {
        client().use { sdk ->
            configured(sdk); sdk.queueAttributes(mapOf("campaign" to "summer"))
            transport.handler = { TransportResult.Failure(TransportFailureKind.TIMEOUT) }
            assertTrue(sdk.flushAttributes() is InappifyResult.Failure)
            assertTrue(jsonObject(store.value!!.customerInfoJson!!).getAsJsonObject("attributes").has("campaign"))
            transport.handler = { transport.response(jsonObject("""{"status":false,"code":"ATTRIBUTE_KEY_INVALID"}"""), 422) }
            assertTrue(sdk.flushAttributes() is InappifyResult.Failure)
            assertTrue(jsonObject(store.value!!.customerInfoJson!!).getAsJsonObject("quarantine").has("campaign"))
            assertTrue(sdk.flushAttributes() is InappifyResult.Success)
        }
    }
    @Test fun offeringsUseServerPlacementThenCurrentThenDefault() = runBlocking {
        client().use { sdk ->
            configured(sdk); assertTrue(sdk.refreshOfferings() is InappifyResult.Success)
            fun selected(result: InappifyResult<InappifyOffering?>) = (result as InappifyResult.Success).data?.identifier
            assertEquals("default", selected(sdk.getCurrentOffering("onboarding", false, null)))
            assertEquals("targeted", selected(sdk.getCurrentOffering("settings", false, null)))
            assertEquals("targeted", selected(sdk.getCurrentOffering(null, false, null)))
            val model = sdk.snapshot.offerings!!
            val roundTrip = InappifyDomainJsonCodec.parseOfferings(InappifyDomainJsonCodec.encodeOfferings(model))
            assertEquals(model, roundTrip)
            assertEquals("targeted", roundTrip.currentOfferingIdentifier)
            val context = InappifyOfferingEvaluationContext("IR", "android", "2.4.0")
            assertEquals("default", roundTrip.currentOffering(context, "onboarding")?.identifier)
            assertEquals("targeted", roundTrip.currentOffering(context, "settings")?.identifier)
            assertEquals("targeted", roundTrip.currentOffering(context)?.identifier)
        }
    }
    @Test fun invalidPlacementsCannotReplaceGoodOfferings() = runBlocking {
        client().use { sdk ->
            configured(sdk); sdk.refreshOfferings()
            val before = sdk.snapshot.offerings
            transport.handler = { transport.response(jsonObject("""{"status":true,"forceVersion":5,"hasForceUpdate":false,"offerings":[],"placements":{"onboarding":123}}""")) }
            assertTrue(sdk.refreshOfferings() is InappifyResult.Failure)
            assertEquals(before, sdk.snapshot.offerings)
            assertEquals(4L, sdk.snapshot.forceVersion)
        }
    }
    @Test fun invalidDiscountIsBusinessResultAndNotRetried() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val result = sdk.validateDiscountCode(InappifyDiscountCodeRequest("WRONG")) as InappifyResult.Success
            assertEquals(false, result.data.isValid); assertEquals(2L, result.data.errorCode)
            assertEquals("{\"code\":\"WRONG\"}", transport.requests.last().jsonBody)
        }
    }
    @Test fun missingLaravelCredentialsFailBeforeAnyPurchaseRequest() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val before = transport.requests.size
            val result = sdk.purchase(InappifyPurchaseRequest("coins", "default", productType = InappifyProductType.CONSUMABLE)) as InappifyResult.Failure
            assertEquals("LARAVEL_CREDENTIAL_REQUIRED", result.error.details["serverCode"])
            assertEquals(before, transport.requests.size)
        }
    }
    @Test fun killSwitchKeepsVerifiedCacheAndBlocksNetwork() = runBlocking {
        client().use { sdk ->
            configured(sdk); sdk.setNetworkEnabled(false)
            assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Failure)
            assertTrue(sdk.customerInfo(InappifyFetchPolicy.CACHE_ONLY) is InappifyResult.Success)
            assertEquals(1, transport.requests.size)
        }
    }
}
