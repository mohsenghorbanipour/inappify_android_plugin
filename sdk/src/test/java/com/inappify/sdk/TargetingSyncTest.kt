package com.inappify.sdk

import com.inappify.sdk.internal.DefaultInappifyClient
import com.inappify.sdk.internal.TargetingSyncRateLimiter
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.AppMetadata
import com.inappify.sdk.internal.platform.AppMetadataProvider
import com.inappify.sdk.internal.v2.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The same admission, ordering and identity contract applies to both production clients. */
@RunWith(Parameterized::class)
class TargetingSyncTest(private val go: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "Go={0}")
        fun protocols(): List<Array<Boolean>> = listOf(arrayOf(false), arrayOf(true))
    }

    @Test fun uploadsAttributesBeforeFreshOfferingsAndPersistsAndPublishesTheResult() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.client.refreshOfferings().success()
            val old = f.client.snapshot.offerings
            f.queue()
            f.calls.clear()
            f.catalog = "targeted"
            val event = CompletableDeferred<InappifyEvent>()
            f.client.addEventListener {
                if (it.type == InappifyEventType.OFFERINGS_CHANGED &&
                    it.snapshot.offerings?.offerings?.firstOrNull()?.identifier == "targeted") event.complete(it)
            }.use {
                val result = f.client.syncAttributesAndOfferingsIfNeeded().success()
                assertEquals(listOf("attributes", "offerings"), f.calls)
                assertNotSame(old, result.data)
                assertEquals("targeted", result.data.offerings!!.single().identifier)
                assertEquals("targeted", result.snapshot.offerings!!.offerings!!.single().identifier)
                assertEquals(result.snapshot.revision, withTimeout(2_000) { event.await() }.snapshot.revision)
                val persisted = if (go) f.store.value!!.customerInfoJson else f.store.value!!.offeringsJson
                assertTrue(persisted!!.contains("targeted"))
                if (go) {
                    val body = jsonObject(f.transport.requests.last { it.path == "attributes" }.jsonBody)
                    assertEquals("gold", body.getAsJsonObject("attributes").get("tier").asString)
                    assertFalse(jsonObject(f.transport.requests.last().jsonBody).has("forceVersion"))
                    assertTrue(f.transport.requests.last().headers["Authorization"]!!.startsWith("Bearer "))
                } else {
                    assertEquals("gold", f.lastAttributes!!.attributes.single().value)
                    assertEquals("tier", f.lastAttributes!!.attributes.single().key)
                    assertEquals(f.lastAttributes!!.token, f.lastOfferings!!.token)
                }
            }
        }
    }

    @Test fun sixthCallReturnsOnlyCacheAndExactWindowBoundaryAdmitsAgain() = runBlocking {
        Fixture().use { f ->
            f.configure()
            repeat(5) { f.client.syncAttributesAndOfferingsIfNeeded().success() }
            val snapshot = f.client.snapshot
            f.calls.clear()
            f.offline = true
            val cached = f.client.syncAttributesAndOfferingsIfNeeded().success()
            assertSame(snapshot.offerings, cached.data)
            assertEquals(snapshot.revision, cached.snapshot.revision)
            assertTrue(f.calls.isEmpty())
            assertEquals(1, f.warnings.size)
            assertFalse(f.warnings.single().contains("fixture-key"))
            f.elapsed = 59_999
            f.client.syncAttributesAndOfferingsIfNeeded().success()
            assertTrue(f.calls.isEmpty())
            f.elapsed = 60_000
            f.offline = false
            f.client.syncAttributesAndOfferingsIfNeeded().success()
            assertEquals(1, f.calls.count { it == "offerings" })
        }
    }

    @Test fun concurrentBurstAdmitsExactlyFiveNetworkOperations() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.calls.clear()
            (1..12).map { async { f.client.syncAttributesAndOfferingsIfNeeded().success() } }.awaitAll()
            assertEquals(5, f.calls.count { it == "offerings" })
            assertEquals(7, f.warnings.size)
        }
    }

    @Test fun unconfiguredCallsDoNotSpendQuota() = runBlocking {
        Fixture().use { f ->
            repeat(7) { assertTrue(f.client.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Failure) }
            assertTrue(f.calls.isEmpty())
            f.configure()
            f.calls.clear()
            repeat(5) { f.client.syncAttributesAndOfferingsIfNeeded().success() }
            assertEquals(5, f.calls.count { it == "offerings" })
            assertTrue(f.warnings.isEmpty())
        }
    }

    @Test fun attributeNetworkFailureNeverFetchesOfferingsAndRetryKeepsValues() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.queue()
            f.calls.clear()
            f.offline = true
            assertTrue(f.client.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Failure)
            assertFalse(f.calls.contains("offerings"))
            f.offline = false
            f.calls.clear()
            f.client.syncAttributesAndOfferingsIfNeeded().success()
            assertEquals(listOf("attributes", "offerings"), f.calls)
            if (!go) assertEquals("gold", f.lastAttributes!!.attributes.single().value)
            else assertEquals("gold", jsonObject(f.transport.requests.last { it.path == "attributes" }.jsonBody)
                .getAsJsonObject("attributes").get("tier").asString)
        }
    }

    @Test fun serverRejectedAttributesStopBeforeOfferings() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.queue()
            f.calls.clear()
            f.rejectAttributes = true
            assertTrue(f.client.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Failure)
            assertEquals(listOf("attributes"), f.calls)
        }
    }

    @Test fun offeringsFailureReturnsFailureEvenWithCacheAndDoesNotEraseIt() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.client.refreshOfferings().success()
            val cached = f.client.snapshot.offerings
            f.failOfferings = true
            assertTrue(f.client.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Failure)
            assertEquals(cached!!.offerings!!.single().identifier,
                f.client.snapshot.offerings!!.offerings!!.single().identifier)
        }
    }

    @Test fun failedCallsCountAndMissingCacheIsAnExplicitRetryableFailure() = runBlocking {
        Fixture().use { f ->
            f.failOfferings = true
            f.configure()
            f.queue()
            f.offline = true
            repeat(5) { assertTrue(f.client.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Failure) }
            f.calls.clear()
            val result = f.client.syncAttributesAndOfferingsIfNeeded() as InappifyResult.Failure
            assertEquals(InappifyErrorCode.UNKNOWN, result.error.code)
            assertTrue(result.error.isRetryable)
            assertEquals("RATE_LIMITED_NO_CACHE", result.error.details["reason"])
            assertEquals(60_000L, result.error.details["retryAfterMillis"])
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun cancellingAttributeSyncStopsFetchReleasesLockAndCountsAdmission() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.queue()
            f.calls.clear()
            val entered = CompletableDeferred<Unit>()
            f.attributeGate = { entered.complete(Unit); awaitCancellation() }
            val task = async { f.client.syncAttributesAndOfferingsIfNeeded() }
            withTimeout(2_000) { entered.await() }
            task.cancelAndJoin()
            assertEquals(listOf("attributes"), f.calls)
            f.attributeGate = {}
            f.calls.clear()
            repeat(5) { f.client.syncAttributesAndOfferingsIfNeeded().success() }
            assertEquals(4, f.calls.count { it == "offerings" })
            assertEquals(1, f.warnings.size)
        }
    }

    @Test fun storageFailureAfterUploadStopsBeforeFetchingOfferings() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.queue()
            f.calls.clear()
            f.store.failSave = true
            assertTrue(f.client.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Failure)
            assertEquals(listOf("attributes"), f.calls)
        }
    }

    @Test fun loginCannotInterleaveBetweenSyncAndOfferings() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.queue()
            f.calls.clear()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.attributeGate = { entered.complete(Unit); release.await() }
            val syncing = async(start = CoroutineStart.UNDISPATCHED) { f.client.syncAttributesAndOfferingsIfNeeded() }
            withTimeout(2_000) { entered.await() }
            val login = async(start = CoroutineStart.UNDISPATCHED) {
                f.client.login(InappifyLoginRequest("fixture-key", "customer_other_123456"))
            }
            assertFalse(f.calls.contains("login"))
            release.complete(Unit)
            val synced = withTimeout(2_000) { syncing.await() }.success()
            withTimeout(2_000) { login.await() }.success()
            assertEquals(listOf("attributes", "offerings", "login"), f.calls.take(3))
            assertEquals(f.signing.subject, synced.snapshot.appUserIdentifier)
            assertEquals("customer_other_123456", f.client.snapshot.appUserIdentifier)
        }
    }

    @Test fun identitySwitchNeverReturnsPriorAccountCacheOrResetsTheAllowance() = runBlocking {
        Fixture().use { f ->
            f.configure()
            repeat(5) { f.client.syncAttributesAndOfferingsIfNeeded().success() }
            f.catalog = "other-account"
            f.client.login(InappifyLoginRequest("fixture-key", "customer_other_123456")).success()
            f.client.refreshOfferings().success()
            f.calls.clear()
            val result = f.client.syncAttributesAndOfferingsIfNeeded().success()
            assertEquals("other-account", result.data.offerings!!.single().identifier)
            assertEquals("customer_other_123456", result.snapshot.appUserIdentifier)
            assertTrue(f.calls.isEmpty())
            assertEquals(1, f.warnings.size)
        }
    }

    @Test fun contextInvalidationDoesNotReviveOldCacheAtTheLimit() = runBlocking {
        Fixture().use { f ->
            f.configure()
            repeat(5) { f.client.syncAttributesAndOfferingsIfNeeded().success() }
            f.client.setTargetingContext(country = "DE").success()
            f.calls.clear()
            val failure = f.client.syncAttributesAndOfferingsIfNeeded() as InappifyResult.Failure
            assertEquals("RATE_LIMITED_NO_CACHE", failure.error.details["reason"])
            assertNull(requireNotNull(failure.snapshot).offerings)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun legacyForceVersionAdvanceUsesOneAwaitedFetchAndGoKeepsForceFieldsAbsent() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.queue()
            f.calls.clear()
            f.bumpForceOnSync = true
            f.client.syncAttributesAndOfferingsIfNeeded().success()
            // Acquiring the client mutex again drains any incorrectly scheduled extra refresh.
            f.client.setTargetingContext(country = "DE").success()
            assertEquals(1, f.calls.count { it == "offerings" })
            if (go) assertNull(f.client.snapshot.forceVersion)
            else {
                assertEquals(2L, f.client.snapshot.forceVersion)
                assertEquals(2L, f.lastOfferings!!.forceVersion)
            }
        }
    }

    @Test fun retainsExistingProtocolSpecificLogoutBarriers() = runBlocking {
        Fixture().use { f ->
            f.configure()
            repeat(5) { f.client.syncAttributesAndOfferingsIfNeeded().success() }
            f.offline = true
            assertTrue(f.client.logout() is InappifyResult.Failure)
            f.calls.clear()
            val result = f.client.syncAttributesAndOfferingsIfNeeded()
            if (go) {
                assertTrue(result is InappifyResult.Failure)
                assertTrue(f.warnings.isEmpty())
            } else {
                // V1 intentionally retains its predecessor on failed logout. Its host
                // owns the pending-logout barrier, just as for existing cache getters.
                assertEquals(f.signing.subject, result.success().snapshot.appUserIdentifier)
                assertEquals(1, f.warnings.size)
            }
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun closedClientDoesNotMakeRequestsOrReturnCachedSuccess() = runBlocking {
        Fixture().use { f ->
            f.configure()
            repeat(5) { f.client.syncAttributesAndOfferingsIfNeeded().success() }
            f.client.close()
            f.calls.clear()
            try {
                f.client.syncAttributesAndOfferingsIfNeeded()
                fail("Closed clients must reject operations")
            } catch (_: IllegalStateException) { }
            assertTrue(f.calls.isEmpty())
        }
    }

    private fun <T> InappifyResult<T>.success(): InappifyResult.Success<T> {
        assertTrue("Expected success, got $this", this is InappifyResult.Success)
        return this as InappifyResult.Success<T>
    }

    private inner class Fixture : AutoCloseable {
        val signing = SigningFixture()
        val store = MemoryV2Store()
        val transport = V2Transport()
        val calls = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var elapsed = 0L
        var catalog = "default"
        var offline = false
        var failOfferings = false
        var rejectAttributes = false
        var bumpForceOnSync = false
        private var forceVersion = 1L
        var attributeGate: suspend () -> Unit = {}
        var lastAttributes: SyncAttributesApiRequest? = null
        var lastOfferings: ResourceApiRequest? = null
        private val limiter = TargetingSyncRateLimiter({ elapsed }, { warnings += it })
        private val metadata = AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) }
        private fun offerings() = """{"status":true,"offerings":[{"identifier":"$catalog","isDefault":true}],"currentOffering":"$catalog"}"""
        private val service = object : InappifyService {
            private fun response(identity: String = signing.subject, withSession: Boolean = false,
                attributes: Boolean = false, status: Boolean = true, offers: Boolean = false): ServiceResult =
                ServiceResult.Response(200, BackendResponse(status, null, null,
                    if (withSession) "token-$identity" else null, identity,
                    """{"originalAppUserId":"$identity","attributes":[{"key":"tier","value":"gold"}]}""",
                    null, 12, forceVersion, offeringsJson = if (offers) offerings() else null,
                    attributesJson = if (attributes) """[{"key":"tier","value":"gold"}]""" else null), null)
            override suspend fun configure(request: ConfigureApiRequest): ServiceResult {
                calls += "configure"
                return response(request.appUserIdentifier ?: signing.subject, withSession = true)
            }
            override suspend fun login(request: LoginApiRequest): ServiceResult {
                calls += "login"
                return response(request.appUserIdentifier, withSession = true)
            }
            override suspend fun logout(request: LogoutApiRequest): ServiceResult {
                calls += "logout"
                return if (offline) ServiceResult.Failure(ServiceFailureKind.NETWORK)
                else response("InaAnonymousId-1", withSession = true)
            }
            override suspend fun refreshSession(request: RefreshSessionApiRequest) = response()
            override suspend fun getCustomerInfo(request: ResourceApiRequest) = response()
            override suspend fun getOfferings(request: ResourceApiRequest): ServiceResult {
                calls += "offerings"
                lastOfferings = request
                return if (offline || failOfferings) ServiceResult.Failure(ServiceFailureKind.NETWORK)
                else response(offers = true)
            }
            override suspend fun syncAttributes(request: SyncAttributesApiRequest): ServiceResult {
                calls += "attributes"
                lastAttributes = request
                attributeGate()
                if (bumpForceOnSync) forceVersion = 2
                return if (offline) ServiceResult.Failure(ServiceFailureKind.NETWORK)
                else response(attributes = true, status = !rejectAttributes)
            }
            override fun close() = Unit
        }
        init {
            transport.handler = { request ->
                calls += request.path
                if (request.path == "attributes") attributeGate()
                when {
                    offline || (request.path == "offerings" && failOfferings) ->
                        TransportResult.Failure(TransportFailureKind.NETWORK)
                    request.path == "configure" || request.path == "login" ->
                        transport.response(signing.envelope(jsonObject(request.jsonBody).string("appUserIdentifier")))
                    request.path == "offerings" -> transport.response(jsonObject(offerings()))
                    request.path == "attributes" && rejectAttributes ->
                        transport.response(jsonObject("""{"status":false,"code":"ATTRIBUTE_INVALID"}"""), 422)
                    request.path == "attributes" -> TransportResult.Response(HttpResponse(204, null, null))
                    else -> error("Unexpected path ${request.path}")
                }
            }
        }
        val client: InappifyClient = if (go) GoV2Client(signing.config, GoApi(transport, { signing.now }, {}),
            store, metadata, { signing.now }, Dispatchers.Unconfined, backgroundRecovery = false,
            targetingSyncLimiter = limiter)
        else DefaultInappifyClient(service, store, metadata, "test", targetingSyncLimiter = limiter)
        suspend fun configure() {
            client.configure(InappifyOptions("fixture-key", appUserIdentifier = signing.subject)).success()
        }
        suspend fun queue() {
            // V1 already holds the complete current attributes from configure; Go is write-only.
            if (go) (client as InappifyV2Client).queueAttributes(mapOf("tier" to "gold")).success()
        }
        override fun close() = client.close()
    }
}
