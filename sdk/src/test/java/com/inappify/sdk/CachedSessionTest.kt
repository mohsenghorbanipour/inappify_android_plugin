package com.inappify.sdk

import com.google.gson.JsonParser
import com.inappify.sdk.internal.DefaultInappifyClient
import com.inappify.sdk.internal.billing.StoreBillingAdapterFactory
import com.inappify.sdk.internal.network.BackendResponse
import com.inappify.sdk.internal.network.ConfigureApiRequest
import com.inappify.sdk.internal.network.InappifyService
import com.inappify.sdk.internal.network.LoginApiRequest
import com.inappify.sdk.internal.network.LogoutApiRequest
import com.inappify.sdk.internal.network.RefreshSessionApiRequest
import com.inappify.sdk.internal.network.ResourceApiRequest
import com.inappify.sdk.internal.network.ServiceFailureKind
import com.inappify.sdk.internal.network.ServiceResult
import com.inappify.sdk.internal.platform.AppMetadata
import com.inappify.sdk.internal.platform.AppMetadataProvider
import com.inappify.sdk.internal.storage.PendingStoreRecoveryState
import com.inappify.sdk.internal.storage.PersistedSession
import com.inappify.sdk.internal.storage.SessionStateStore
import com.inappify.sdk.internal.storage.blockCacheRestore
import com.inappify.sdk.internal.storage.isValidCachedJson
import com.inappify.sdk.internal.storage.readCacheRestoreBlocked
import com.inappify.sdk.internal.storage.isValidCachedExpiration
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CachedSessionTest {
    @Test
    fun coldRestorePublishesOldCacheWithoutHttpBillingWritesOrJournalAccess() = runBlocking {
        val service = Service()
        val store = Store(cached())
        client(service, store).use { sdk ->
            val changed = CountDownLatch(3)
            sdk.addEventListener { event ->
                if (event.type in setOf(InappifyEventType.STATE_CHANGED,
                        InappifyEventType.CUSTOMER_INFO_CHANGED, InappifyEventType.OFFERINGS_CHANGED)) {
                    changed.countDown()
                }
            }
            val result = sdk.restoreCachedSession(options()) as InappifyResult.Success
            assertTrue(result.data)
            assertTrue(result.snapshot.isConfigured)
            assertTrue(result.snapshot.isAuthenticated)
            assertEquals(CUSTOMER, result.snapshot.customerInfo?.originalAppUserId)
            assertEquals("cached", result.snapshot.offerings?.offerings?.single()?.identifier)
            assertTrue(changed.await(2, TimeUnit.SECONDS))
            assertEquals(0, service.calls)
            assertEquals(1, store.cacheReads)
            assertEquals(0, store.normalReads)
            assertEquals(0, store.writes)
            assertEquals(0, store.clears)
            assertEquals(0, store.journalReads)
            assertFalse(result.toString().contains(TOKEN))
            assertFalse(result.toString().contains(KEY))
        }
    }

    @Test
    fun networkConfigureStillFailsOfflineWithoutImplicitlyPublishingDiskCache() = runBlocking {
        val service = Service()
        client(service).use { sdk ->
            assertTrue(sdk.configure(options()) is InappifyResult.Failure)
            assertEquals(1, service.calls)
            assertFalse(sdk.snapshot.isConfigured)
            assertNull(sdk.snapshot.customerInfo)
        }
    }

    @Test
    fun failedBackgroundConfigurePreservesRestoredCache() = runBlocking {
        val service = Service()
        client(service).use { sdk ->
            sdk.restoreCachedSession(options())
            val before = sdk.snapshot
            val result = sdk.configure(options()) as InappifyResult.Failure
            assertEquals(InappifyErrorCode.NETWORK, result.error.code)
            assertEquals(before.customerInfo, sdk.snapshot.customerInfo)
            assertEquals(before.offerings, sdk.snapshot.offerings)
            assertEquals(before.revision, sdk.snapshot.revision)
        }
    }

    @Test
    fun backgroundRefreshPublishesFreshResourcesOnlyAfterItsResponse() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = Service().apply {
            refresh = {
                started.complete(Unit)
                release.await()
                response(customer = "{\"originalAppUserId\":\"$CUSTOMER\",\"hasUsedTrial\":true}")
            }
            offerings = response(offerings = "{\"offerings\":[{\"identifier\":\"fresh\"}]}")
        }
        val store = Store(cached())
        client(service, store).use { sdk ->
            sdk.restoreCachedSession(options())
            val revision = sdk.snapshot.revision
            val refresh = async { sdk.configure(options()) }
            started.await()
            assertEquals("cached", sdk.snapshot.offerings?.offerings?.single()?.identifier)
            assertEquals(0, store.writes)
            release.complete(Unit)
            assertTrue(refresh.await() is InappifyResult.Success)
            assertEquals("fresh", sdk.snapshot.offerings?.offerings?.single()?.identifier)
            assertTrue(sdk.snapshot.customerInfo?.hasUsedTrial == true)
            assertTrue(sdk.snapshot.revision > revision)
            assertEquals(1, store.writes)
        }
    }

    @Test
    fun explicitWrongKeyOrCustomerNeverAdoptsCache() = runBlocking {
        for (options in listOf(options(key = "different-app-key"), options(identifier = "other-customer"))) {
            client().use { sdk ->
                assertFalse((sdk.restoreCachedSession(options) as InappifyResult.Success).data)
                assertNull(sdk.snapshot.customerInfo)
                assertFalse(sdk.snapshot.isConfigured)
            }
        }
    }

    @Test
    fun absentIdentifierDoesNotResurrectSignedInCustomer() = runBlocking {
        client().use { sdk ->
            assertFalse((sdk.restoreCachedSession(options(identifier = null)) as InappifyResult.Success).data)
            assertNull(sdk.snapshot.appUserIdentifier)
        }
    }

    @Test
    fun explicitAnonymousIdentityMustMatchExactlyAndOmittedIdentityCanRestoreAnonymous() = runBlocking {
        val id = "InaAnonymousId-test-one"
        for (requested in listOf<String?>(null, id)) {
            client(store = Store(cached(identifier = id))).use { sdk ->
                assertTrue((sdk.restoreCachedSession(options(identifier = requested)) as InappifyResult.Success).data)
                assertEquals(id, sdk.snapshot.appUserIdentifier)
                assertFalse(sdk.snapshot.isAuthenticated)
            }
        }
        client(store = Store(cached(identifier = id))).use { sdk ->
            assertFalse((sdk.restoreCachedSession(options(identifier = "InaAnonymousId-test-two")) as InappifyResult.Success).data)
        }
    }

    @Test
    fun malformedMismatchedOrMissingCustomerCacheIsNotPublished() = runBlocking {
        for (raw in listOf(null, "{}", "[]", "not json", "{\"originalAppUserId\":\"other\"}",
            "{\"originalAppUserId\":\"$CUSTOMER\",\"entitlements\":42}",
            "{\"originalAppUserId\":\"$CUSTOMER\",\"entitlements\":[{\"is_active\":\"true\"}]}",
            "{\"originalAppUserId\":\"$CUSTOMER\",\"originalAppUserId\":\"$CUSTOMER\"}",
            "{\"originalAppUserId\":\"$CUSTOMER\"} {}",
        )) {
            client(store = Store(cached(customer = raw))).use { sdk ->
                assertFalse((sdk.restoreCachedSession(options()) as InappifyResult.Success).data)
                assertNull(sdk.snapshot.customerInfo)
            }
        }
    }

    @Test
    fun malformedExpiryCannotBecomeOfflineEntitlementAuthority() = runBlocking {
        for (extra in listOf(
            "\"latestExpirationDate\":\"broken\"",
            "\"entitlements\":[{\"identifier\":\"pro\",\"is_active\":true,\"expiration_date\":\"broken\"}]",
            "\"entitlements\":[{\"identifier\":\"pro\",\"is_active\":true,\"expiration_date\":123}]",
        )) {
            client(store = Store(cached(customer = "{\"originalAppUserId\":\"$CUSTOMER\",$extra}"))).use { sdk ->
                assertFalse((sdk.restoreCachedSession(options()) as InappifyResult.Success).data)
            }
        }
    }

    @Test
    fun impossibleCachedCalendarOrOffsetIsRejectedWithoutChangingLegacyDateHelper() = runBlocking {
        for (expiry in listOf("2026-13-01T00:00:00Z", "2026-02-30T00:00:00Z", "2025-02-29",
            "2026-01-01T25:00:00Z", "2026-01-01T00:60:00Z", "2026-01-01T00:00:99Z",
            "2026-01-01T00:00:00+99:00", "2026-01-01T00:00:00+03:99")) {
            assertFalse(isValidCachedExpiration(expiry))
            val customer = """{"originalAppUserId":"$CUSTOMER","entitlements":[
                {"identifier":"pro","is_active":true,"expiration_date":"$expiry"}]}"""
            client(store = Store(cached(customer = customer))).use { sdk ->
                assertFalse((sdk.restoreCachedSession(options()) as InappifyResult.Success).data)
                assertNull(sdk.snapshot.customerInfo)
            }
        }
    }

    @Test
    fun strictOfflineDateValidationAcceptsRealDatesAndLegacyFormatting() {
        for (expiry in listOf(null, "", "2024-02-29", "2026-01-01T23:59:59Z",
            "2026-01-01T23:59:59.123456+03:30", "2026-01-01 12:30:45", "20260101T123045Z")) {
            assertTrue("Must accept supported expiration format", isValidCachedExpiration(expiry))
        }
    }

    @Test
    fun expiredEntitlementsStayExpiredWhileLifetimeAndOtherDataRemainReadable() = runBlocking {
        val customer = """{"originalAppUserId":"$CUSTOMER","entitlements":[
            {"identifier":"expired","is_active":true,"expiration_date":"2000-01-01T00:00:00Z"},
            {"identifier":"lifetime","is_active":true,"expiration_date":""}]}"""
        client(store = Store(cached(customer = customer))).use { sdk ->
            assertTrue((sdk.restoreCachedSession(options()) as InappifyResult.Success).data)
            assertFalse(sdk.isActiveEntitlement("expired"))
            assertTrue(sdk.isActiveEntitlement("lifetime"))
        }
    }

    @Test
    fun invalidOfferingCacheDoesNotPreventValidCustomerFromRestoring() = runBlocking {
        for (raw in listOf("broken", "{\"offerings\":42}", "{\"offerings\":[],\"offerings\":[]}",
            "{\"offerings\":[],\"forceVersion\":99}")) {
            client(store = Store(cached(offerings = raw))).use { sdk ->
                assertTrue((sdk.restoreCachedSession(options()) as InappifyResult.Success).data)
                assertNotNull(sdk.snapshot.customerInfo)
                assertNull(sdk.snapshot.offerings)
            }
        }
    }

    @Test
    fun targetingChangesInvalidateOfferingsButKeepCustomer() = runBlocking {
        for (option in listOf(options(country = "US"), options(version = "9.0"),
            options(market = InappifyMarket.BAZAAR, marketKey = "test-rsa"))) {
            val store = Store(cached())
            client(store = store).use { sdk ->
                assertTrue((sdk.restoreCachedSession(option) as InappifyResult.Success).data)
                assertNotNull(sdk.snapshot.customerInfo)
                assertNull(sdk.snapshot.offerings)
                assertEquals(option.country, sdk.snapshot.country)
                assertEquals(0, store.writes)
            }
        }
    }

    @Test
    fun oldMissingTargetingFingerprintRestoresCustomerOnly() = runBlocking {
        client(store = Store(cached(contextFingerprint = null))).use { sdk ->
            assertTrue((sdk.restoreCachedSession(options()) as InappifyResult.Success).data)
            assertNotNull(sdk.snapshot.customerInfo)
            assertNull(sdk.snapshot.offerings)
        }
    }

    @Test
    fun missingBindingOrInvalidEnvelopeIsCacheMiss() = runBlocking {
        for (entry in listOf(cached(keyFingerprint = null), cached(token = null), cached(token = " "),
            cached(appId = null), cached(appId = -1), cached(forceVersion = -1), cached(identifier = " "))) {
            client(store = Store(entry)).use { sdk ->
                assertFalse((sdk.restoreCachedSession(options()) as InappifyResult.Success).data)
            }
        }
    }

    @Test
    fun blankKeyIdentityOrMissingBazaarKeyFailsBeforeStorageRead() = runBlocking {
        for (option in listOf(options(key = " "), options(identifier = " "), options(identifier = " $CUSTOMER "),
            options(market = InappifyMarket.BAZAAR))) {
            val store = Store(cached())
            client(store = store).use { sdk ->
                val result = sdk.restoreCachedSession(option) as InappifyResult.Failure
                assertEquals(InappifyErrorCode.INVALID_CONFIGURATION, result.error.code)
                assertEquals(0, store.cacheReads)
            }
        }
    }

    @Test
    fun configuredMemoryWinsOverOlderDiskAndDoesNotReadIt() = runBlocking {
        val store = Store(cached())
        client(store = store).use { sdk ->
            sdk.restoreCachedSession(options())
            store.value = cached(identifier = "other-customer")
            assertTrue((sdk.restoreCachedSession(options()) as InappifyResult.Success).data)
            assertEquals(CUSTOMER, sdk.snapshot.appUserIdentifier)
            assertEquals(1, store.cacheReads)
            assertFalse((sdk.restoreCachedSession(options(identifier = "other-customer")) as InappifyResult.Success).data)
            assertEquals(CUSTOMER, sdk.snapshot.appUserIdentifier)
            assertEquals(1, store.cacheReads)
        }
    }

    @Test
    fun existingCustomerTtlAndOfferingGetterSemanticsAreUnchanged() = runBlocking {
        val service = Service()
        client(service).use { sdk ->
            sdk.restoreCachedSession(options())
            assertTrue(sdk.getOfferings() is InappifyResult.Success)
            assertEquals(0, service.calls)
            assertTrue(sdk.getCustomerInfo(forceRefresh = false) is InappifyResult.Failure)
            assertEquals(1, service.calls)
            assertNotNull(sdk.snapshot.customerInfo)
        }
    }

    @Test
    fun definitivelyRejectedRestoredTokenIsBlockedAcrossRestartWithoutLosingRecovery() = runBlocking {
        val service = Service().apply { refresh = { unauthorized() } }
        val store = Store(cached())
        client(service, store).use { sdk ->
            sdk.restoreCachedSession(options())
            assertTrue(sdk.configure(options()) is InappifyResult.Failure)
            assertFalse(sdk.snapshot.isConfigured)
            assertNull(sdk.snapshot.customerInfo)
            assertTrue(store.value!!.cacheRestoreBlocked)
            assertEquals(TOKEN, store.value?.token)
            assertEquals(RECOVERY, store.value?.purchaseRecoveryId)
            assertEquals(CUSTOMER, store.value?.appUserIdentifier)
            assertEquals(0, store.clears)
            assertEquals(0, store.journalReads)
            assertFalse((sdk.restoreCachedSession(options()) as InappifyResult.Success).data)
        }
        client(store = store).use { rebooted ->
            assertFalse((rebooted.restoreCachedSession(options()) as InappifyResult.Success).data)
            assertFalse(rebooted.snapshot.isConfigured)
        }
    }

    @Test
    fun onlineReauthenticationClearsDurableBlockAndKeepsRecoveryBinding() = runBlocking {
        val store = Store(cached().blockCacheRestore())
        val service = Service().apply { refresh = { response() }; offerings = response(offerings = OFFERINGS) }
        client(service, store).use { sdk ->
            assertFalse((sdk.restoreCachedSession(options()) as InappifyResult.Success).data)
            assertTrue(sdk.configure(options()) is InappifyResult.Success)
            assertFalse(store.value!!.cacheRestoreBlocked)
            assertEquals(RECOVERY, store.value?.purchaseRecoveryId)
        }
        client(store = store).use { rebooted ->
            assertTrue((rebooted.restoreCachedSession(options()) as InappifyResult.Success).data)
        }
    }

    @Test
    fun successfulBackgroundConfigureDoesNotDisableFutureAuthRejectionProtection() = runBlocking {
        val service = Service().apply { refresh = { response() }; offerings = response(offerings = OFFERINGS) }
        val store = Store(cached())
        client(service, store).use { sdk ->
            sdk.restoreCachedSession(options())
            assertTrue(sdk.configure(options()) is InappifyResult.Success)
            service.customer = unauthorized()
            assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Failure)
            assertTrue(store.value!!.cacheRestoreBlocked)
            assertNull(sdk.snapshot.customerInfo)
        }
        client(store = store).use { rebooted ->
            assertFalse((rebooted.restoreCachedSession(options()) as InappifyResult.Success).data)
        }
    }

    @Test
    fun authRejectionPersistenceFailureIsReportedAndDoesNotKeepVisibleCache() = runBlocking {
        val service = Service().apply { refresh = { unauthorized() } }
        val store = Store(cached()).apply { saveSucceeds = false }
        client(service, store).use { sdk ->
            sdk.restoreCachedSession(options())
            val result = sdk.configure(options()) as InappifyResult.Failure
            assertEquals(InappifyErrorCode.STORE_UNAVAILABLE, result.error.code)
            assertEquals(false, result.error.details["cacheRestoreBlockPersisted"])
            assertFalse(sdk.snapshot.isConfigured)
            assertFalse((sdk.restoreCachedSession(options()) as InappifyResult.Success).data)
            assertEquals(0, store.clears)
        }
    }

    @Test
    fun resourceAuthRejectionAlsoBlocksRestoredCache() = runBlocking {
        for (offerings in listOf(false, true)) {
            val service = Service().apply { customer = unauthorized(); this.offerings = unauthorized() }
            val store = Store(cached())
            client(service, store).use { sdk ->
                sdk.restoreCachedSession(options())
                val result = if (offerings) sdk.refreshOfferings() else sdk.refreshCustomerInfo()
                assertTrue(result is InappifyResult.Failure)
                assertTrue(store.value!!.cacheRestoreBlocked)
                assertNull(sdk.snapshot.customerInfo)
            }
        }
    }

    @Test
    fun storageExceptionIsSafeAndCancellationPropagates() = runBlocking {
        val store = Store(cached()).apply { loadError = IllegalStateException("$KEY $TOKEN") }
        client(store = store).use { sdk ->
            val result = sdk.restoreCachedSession(options()) as InappifyResult.Failure
            assertEquals(InappifyErrorCode.STORE_UNAVAILABLE, result.error.code)
            assertFalse(result.toString().contains(KEY))
            assertFalse(result.toString().contains(TOKEN))
        }
        store.loadError = CancellationException("cancelled")
        client(store = store).use { sdk ->
            try {
                sdk.restoreCachedSession(options())
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { }
            assertFalse(sdk.snapshot.isConfigured)
        }
    }

    @Test
    fun closeDuringDiskReadPreventsLateCachePublication() = runBlocking {
        val readStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = Store(cached()).apply { beforeRead = { readStarted.complete(Unit); release.await() } }
        val sdk = client(store = store)
        val restoration = async { sdk.restoreCachedSession(options()) }
        readStarted.await()
        sdk.close()
        release.complete(Unit)
        assertEquals(InappifyErrorCode.REQUEST_CANCELLED, (restoration.await() as InappifyResult.Failure).error.code)
        assertNull(sdk.snapshot.customerInfo)
    }

    @Test
    fun legacyMissingRecoveryBindingIsNotGeneratedOrPersistedDuringRestore() = runBlocking {
        val store = Store(cached(recoveryBinding = null))
        client(store = store).use { sdk ->
            assertTrue((sdk.restoreCachedSession(options()) as InappifyResult.Success).data)
            assertNull(store.value?.purchaseRecoveryId)
            assertEquals(0, store.writes)
            assertEquals(0, store.journalReads)
        }
    }

    @Test
    fun persistedOfflineBlockDecoderDefaultsLegacyAndFailsClosedOnMalformedMarker() {
        fun blocked(raw: String) = JsonParser.parseString(raw).asJsonObject.readCacheRestoreBlocked()
        assertFalse(blocked("{}"))
        assertFalse(blocked("{\"cacheRestoreBlocked\":false}"))
        assertTrue(blocked("{\"cacheRestoreBlocked\":true}"))
        for (raw in listOf("null", "\"false\"", "0", "[]", "{}")) {
            assertTrue(blocked("{\"cacheRestoreBlocked\":$raw}"))
        }
    }

    @Test
    fun lateAuthRejectionAfterCloseCannotOverwriteThePersistedSession() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = Service().apply { refresh = { started.complete(Unit); release.await(); unauthorized() } }
        val store = Store(cached())
        val sdk = client(service, store)
        sdk.restoreCachedSession(options())
        val request = async { sdk.configure(options()) }
        started.await()
        sdk.close()
        store.value = cached(identifier = "new-customer-after-close")
        release.complete(Unit)
        request.await()
        assertEquals(0, store.writes)
        assertEquals("new-customer-after-close", store.value?.appUserIdentifier)
    }

    @Test
    fun strictCacheBoundaryRejectsDuplicatesDepthAndOversizeButAcceptsLegacyArrays() {
        assertTrue(isValidCachedJson("[]"))
        assertTrue(isValidCachedJson("{\"offerings\":[],\"metadata\":null}"))
        assertFalse(isValidCachedJson("{\"a\":1,\"a\":2}"))
        assertFalse(isValidCachedJson("[1,]"))
        assertFalse(isValidCachedJson("[".repeat(34) + "0" + "]".repeat(34)))
        assertFalse(isValidCachedJson("\"" + "x".repeat(1024 * 1024) + "\""))
    }

    private fun client(service: Service = Service(), store: Store = Store(cached())) = DefaultInappifyClient(
        service = service,
        sessionStore = store,
        metadataProvider = AppMetadataProvider { AppMetadata("com.example.cachetest", VERSION, 1) },
        sdkVersion = "test",
        currentTimeMillis = { 1_000_000 },
        purchaseRecoveryIdProvider = { error("Offline restore must not create a recovery binding") },
        storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ -> error("Offline restore must not bind Billing") },
    )

    private fun options(key: String = KEY, identifier: String? = CUSTOMER, country: String = "IR",
        version: String = VERSION, market: InappifyMarket = InappifyMarket.NONE, marketKey: String? = null) =
        InappifyOptions(key, identifier, market, marketKey, country, version)

    private fun cached(identifier: String = CUSTOMER, token: String? = TOKEN,
        keyFingerprint: String? = hash(KEY), contextFingerprint: String? = hash("4:NONE;-1:;2:IR;3:1.0;"),
        customer: String? = "{\"originalAppUserId\":\"$identifier\"}", offerings: String? = OFFERINGS,
        appId: Long? = 12, forceVersion: Long? = 1, recoveryBinding: String? = RECOVERY) = PersistedSession(
        token, identifier, forceVersion, appId, null, keyFingerprint, customer, offerings, "1",
        cacheContextFingerprint = contextFingerprint, purchaseRecoveryId = recoveryBinding, storePlatform = "directandroid",
    )

    private class Store(var value: PersistedSession?) : SessionStateStore {
        var normalReads = 0
        var cacheReads = 0
        var writes = 0
        var clears = 0
        var journalReads = 0
        var saveSucceeds = true
        var loadError: Exception? = null
        var beforeRead: suspend () -> Unit = {}
        override suspend fun load(): PersistedSession? { normalReads++; return value }
        override suspend fun loadForCacheRestore(): PersistedSession? {
            cacheReads++
            beforeRead()
            loadError?.let { throw it }
            return value
        }
        override suspend fun save(session: PersistedSession): Boolean {
            writes++
            if (saveSucceeds) value = session
            return saveSucceeds
        }
        override suspend fun clear(): Boolean { clears++; value = null; return true }
        override suspend fun loadPendingStoreRecoveryState(): PendingStoreRecoveryState? {
            journalReads++
            error("Cache restore must not inspect purchase journal")
        }
    }

    private class Service : InappifyService {
        var calls = 0
        var refresh: suspend () -> ServiceResult = { offline() }
        var configure: ServiceResult = offline()
        var customer: ServiceResult = offline()
        var offerings: ServiceResult = offline()
        override suspend fun configure(request: ConfigureApiRequest): ServiceResult { calls++; return configure }
        override suspend fun login(request: LoginApiRequest): ServiceResult = error("Login not expected")
        override suspend fun logout(request: LogoutApiRequest): ServiceResult = error("Logout not expected")
        override suspend fun refreshSession(request: RefreshSessionApiRequest): ServiceResult { calls++; return refresh() }
        override suspend fun getCustomerInfo(request: ResourceApiRequest): ServiceResult { calls++; return customer }
        override suspend fun getOfferings(request: ResourceApiRequest): ServiceResult { calls++; return offerings }
        override fun close() = Unit
    }

    companion object {
        private const val KEY = "test-public-cache-key"
        private const val TOKEN = "test-cached-session-token"
        private const val CUSTOMER = "test-customer-1234"
        private const val RECOVERY = "test-recovery-binding"
        private const val VERSION = "1.0"
        private const val OFFERINGS = "{\"offerings\":[{\"identifier\":\"cached\"}]}"
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun offline(): ServiceResult = ServiceResult.Failure(ServiceFailureKind.NETWORK)
        private fun unauthorized() = response(status = false, httpStatus = 401)
        private fun response(customer: String = "{\"originalAppUserId\":\"$CUSTOMER\"}",
            offerings: String? = null, status: Boolean = true, httpStatus: Int = 200): ServiceResult =
            ServiceResult.Response(httpStatus, BackendResponse(status, null, null, null, CUSTOMER,
                customer, null, 12, 1, offeringsJson = offerings), requestId = null)
    }
}
