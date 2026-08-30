package com.inappify.sdk

import android.app.Activity
import com.google.gson.JsonParser
import com.inappify.sdk.internal.DefaultInappifyClient
import com.inappify.sdk.internal.billing.StoreBillingAdapter
import com.inappify.sdk.internal.billing.StoreBillingAdapterFactory
import com.inappify.sdk.internal.billing.StoreBillingResult
import com.inappify.sdk.internal.billing.StoreProductType
import com.inappify.sdk.internal.billing.StorePurchase
import com.inappify.sdk.internal.billing.StorePurchaseQueryResult
import com.inappify.sdk.internal.billing.StorePurchaseRequest
import com.inappify.sdk.internal.billing.StoreUiHost
import com.inappify.sdk.internal.network.BackendPurchase
import com.inappify.sdk.internal.network.BackendResponse
import com.inappify.sdk.internal.network.ConfigureApiRequest
import com.inappify.sdk.internal.network.InappifyService
import com.inappify.sdk.internal.network.LoginApiRequest
import com.inappify.sdk.internal.network.LogoutApiRequest
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
import com.inappify.sdk.internal.platform.AppMetadata
import com.inappify.sdk.internal.platform.AppMetadataProvider
import com.inappify.sdk.internal.storage.PersistedSession
import com.inappify.sdk.internal.storage.SessionStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DefaultInappifyClientTest {

    @Test
    fun configure_validatesBeforeCallingService() = runBlocking {
        val service = FakeService()
        val client = createClient(service = service)

        val result = client.configure(
            InappifyOptions(
                apiKey = " ",
                market = InappifyMarket.BAZAAR,
            ),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.INVALID_CONFIGURATION, result.error.code)
        assertEquals(0, service.configureCalls)
        assertFalse(client.snapshot.isConfigured)
    }

    @Test
    fun configure_noneIgnoresUnusedMarketKey() = runBlocking {
        val service = FakeService()
        val client = createClient(service = service)

        client.configure(
            InappifyOptions(
                apiKey = "mobile-api-key",
                market = InappifyMarket.NONE,
                marketKey = "ignored-first-key",
            ),
        )
        service.refreshResult = successfulResponseWithoutToken(
            identifier = "InaAnonymousId-1",
        )
        client.configure(
            InappifyOptions(
                apiKey = "mobile-api-key",
                market = InappifyMarket.NONE,
                marketKey = "ignored-second-key",
            ),
        )

        assertEquals(1, service.configureCalls)
        assertEquals(1, service.refreshCalls)
        client.close()
    }

    @Test
    fun configure_commitsCompleteAnonymousSession() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponse(
                token = "anonymous-token",
                identifier = "InaAnonymousId-1",
                storeInfo = "bazar",
                forceVersion = 4,
                appId = 12,
            )
        }
        val store = FakeSessionStore()
        val client = createClient(service = service, store = store)

        val result = client.configure(
            InappifyOptions(
                apiKey = "mobile-api-key",
                market = InappifyMarket.BAZAAR,
                marketKey = "market-key",
                country = "ir",
            ),
        ) as InappifyResult.Success<*>

        assertTrue(result.snapshot.isConfigured)
        assertFalse(result.snapshot.isAuthenticated)
        assertEquals("InaAnonymousId-1", result.snapshot.appUserIdentifier)
        assertEquals(InappifyMarket.BAZAAR, result.snapshot.market)
        assertEquals("IR", result.snapshot.country)
        assertEquals("1.2.3", result.snapshot.appVersion)
        assertEquals(4L, result.snapshot.forceVersion)
        assertEquals(12L, result.snapshot.appId)
        assertEquals("com.example.host", service.lastConfigure?.packageIdentifier)
        assertEquals(42L, service.lastConfigure?.versionCode)
        assertEquals("anonymous-token", store.lastSaved?.token)
        assertEquals(TEST_RECOVERY_BINDING, store.lastSaved?.purchaseRecoveryId)
        assertFalse(result.snapshot.toString().contains("anonymous-token"))
        assertFalse(result.toString().contains("mobile-api-key"))
    }

    @Test
    fun configure_doesNotSendUnboundLegacyTokenToBackend() = runBlocking {
        val service = FakeService()
        val store = FakeSessionStore(
            loaded = persistedSession(
                token = "cached-token",
                identifier = "09120000000",
                apiKeyFingerprint = null,
            ),
        )
        val client = createClient(service = service, store = store)

        val result = client.configure(
            InappifyOptions(apiKey = "mobile-api-key"),
        ) as InappifyResult.Success<*>

        assertEquals(1, service.configureCalls)
        assertEquals(0, service.refreshCalls)
        assertFalse(result.snapshot.isAuthenticated)
        assertEquals("InaAnonymousId-1", result.snapshot.appUserIdentifier)
        assertNotNull(store.lastSaved?.apiKeyFingerprint)
        assertFalse(store.lastSaved.toString().contains("anonymous-token"))
        assertFalse(store.lastSaved.toString().contains("09120000000"))
    }

    @Test
    fun configure_validatesBoundPersistedSessionBeforeAdoptingIt() = runBlocking {
        val firstService = FakeService().apply {
            configureResult = successfulResponse(
                token = "cached-token",
                identifier = "09120000000",
            )
        }
        val firstStore = FakeSessionStore()
        createClient(service = firstService, store = firstStore).configure(
            InappifyOptions(
                apiKey = "mobile-api-key",
                appUserIdentifier = "09120000000",
            ),
        )
        val service = FakeService().apply {
            refreshResult = successfulResponseWithoutToken("09120000000")
        }
        val restoredStore = FakeSessionStore(loaded = firstStore.lastSaved)

        val result = createClient(service, restoredStore).configure(
            InappifyOptions(apiKey = "mobile-api-key"),
        ) as InappifyResult.Success<*>

        assertEquals(0, service.configureCalls)
        assertEquals(1, service.refreshCalls)
        assertEquals("cached-token", service.lastRefresh?.token)
        assertTrue(result.snapshot.isAuthenticated)
        assertEquals("09120000000", result.snapshot.appUserIdentifier)
    }

    @Test
    fun configure_replacesExpiredBoundSessionThroughConfigureEndpoint() =
        runBlocking {
            val firstStore = FakeSessionStore()
            createClient(store = firstStore).configure(
                InappifyOptions(apiKey = "mobile-api-key"),
            )
            val service = FakeService().apply {
                refreshResult = ServiceResult.Response(
                    statusCode = 200,
                    payload = BackendResponse(
                        status = false,
                        message = "Session expired.",
                        errorCode = "session_expired",
                        token = null,
                        appUserIdentifier = null,
                        customerInfoJson = null,
                        storeInfo = null,
                        appId = null,
                        forceVersion = null,
                    ),
                    requestId = "expired-session",
                )
                configureResult = successfulResponse(
                    token = "replacement-token",
                    identifier = "InaAnonymousId-2",
                )
            }

            val result = createClient(
                service = service,
                store = FakeSessionStore(loaded = firstStore.lastSaved),
            ).configure(InappifyOptions(apiKey = "mobile-api-key"))

            assertTrue(result is InappifyResult.Success<*>)
            assertEquals(1, service.refreshCalls)
            assertEquals(1, service.configureCalls)
        }

    @Test
    fun configure_validatesCurrentSessionInsteadOfTrustingMemoryCache() =
        runBlocking {
            val service = FakeService()
            val client = createClient(service)
            client.configure(InappifyOptions(apiKey = "mobile-api-key"))
            service.refreshResult = successfulResponseWithoutToken(
                identifier = "InaAnonymousId-1",
            )

            val result = client.configure(
                InappifyOptions(apiKey = "mobile-api-key"),
            )

            assertTrue(result is InappifyResult.Success<*>)
            assertEquals(1, service.configureCalls)
            assertEquals(1, service.refreshCalls)
        }

    @Test
    fun reconfigure_preservesValidOfferingsWhenBestEffortRefreshFails() =
        runBlocking {
            val service = FakeService().apply {
                offeringsResult = successfulOfferingsResponse("starter")
            }
            val client = createClient(service = service)
            client.configure(InappifyOptions(apiKey = "mobile-api-key"))
            assertEquals(
                "starter",
                client.snapshot.offerings?.offerings?.single()?.identifier,
            )
            service.refreshResult = successfulResponseWithoutToken(
                identifier = "InaAnonymousId-1",
            )
            service.offeringsResult = ServiceResult.Failure(
                ServiceFailureKind.NETWORK,
            )

            val result = client.configure(
                InappifyOptions(apiKey = "mobile-api-key"),
            ) as InappifyResult.Success<*>

            assertEquals(
                "starter",
                result.snapshot.offerings?.offerings?.single()?.identifier,
            )
            assertTrue(result.snapshot.failedToLoadOfferings)
        }

    @Test
    fun persistedOfferings_areRestoredOnlyForMatchingCacheContext() =
        runBlocking {
            val firstStore = FakeSessionStore()
            val firstService = FakeService().apply {
                offeringsResult = successfulOfferingsResponse("starter")
            }
            createClient(firstService, firstStore).configure(
                InappifyOptions(
                    apiKey = "mobile-api-key",
                    country = "IR",
                ),
            )
            val persisted = requireNotNull(firstStore.lastSaved)

            val matchingService = FakeService().apply {
                refreshResult = successfulResponseWithoutToken(
                    identifier = "InaAnonymousId-1",
                )
                offeringsResult = ServiceResult.Failure(
                    ServiceFailureKind.NETWORK,
                )
            }
            val matching = createClient(
                service = matchingService,
                store = FakeSessionStore(loaded = persisted),
            ).configure(
                InappifyOptions(
                    apiKey = "mobile-api-key",
                    country = "IR",
                ),
            ) as InappifyResult.Success<*>

            assertEquals(
                "starter",
                matching.snapshot.offerings?.offerings?.single()?.identifier,
            )

            val changedContext = createClient(
                service = matchingService,
                store = FakeSessionStore(loaded = persisted),
            ).configure(
                InappifyOptions(
                    apiKey = "mobile-api-key",
                    country = "US",
                ),
            ) as InappifyResult.Success<*>

            assertNull(changedContext.snapshot.offerings)
        }

    @Test
    fun configure_rejectsRefreshWithoutAuthoritativeIdentity() = runBlocking {
        val service = FakeService()
        val client = createClient(service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        service.refreshResult = successfulResponseWithoutToken(identifier = null)

        val result = client.configure(
            InappifyOptions(apiKey = "mobile-api-key"),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.MALFORMED_RESPONSE, result.error.code)
        assertEquals(1, service.configureCalls)
        assertEquals(1, service.refreshCalls)
        assertEquals("InaAnonymousId-1", client.snapshot.appUserIdentifier)
    }

    @Test
    fun configure_replacesRejectedSessionEvenWithoutBackendErrorCode() =
        runBlocking {
            val service = FakeService()
            val client = createClient(service)
            client.configure(InappifyOptions(apiKey = "mobile-api-key"))
            service.refreshResult = ServiceResult.Response(
                statusCode = 200,
                payload = backendResponse(status = false),
                requestId = "rejected-session",
            )
            service.configureResult = successfulResponse(
                token = "replacement-token",
                identifier = "InaAnonymousId-2",
            )

            val result = client.configure(
                InappifyOptions(apiKey = "mobile-api-key"),
            )

            assertTrue(result is InappifyResult.Success<*>)
            assertEquals(2, service.configureCalls)
            assertEquals(1, service.refreshCalls)
            assertEquals("InaAnonymousId-2", client.snapshot.appUserIdentifier)
        }

    @Test
    fun failedReconfigure_preservesEntirePreviousSession() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponse(
                token = "customer-token",
                identifier = "09120000000",
            )
        }
        val store = FakeSessionStore()
        val client = createClient(service = service, store = store)
        client.configure(
            InappifyOptions(
                apiKey = "first-api-key",
                appUserIdentifier = "09120000000",
            ),
        )
        val writesBeforeReconfigure = store.saveCalls
        service.configureResult = ServiceResult.Failure(
            ServiceFailureKind.TIMEOUT,
        )

        val result = client.configure(
            InappifyOptions(apiKey = "second-api-key"),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.TIMEOUT, result.error.code)
        assertTrue(client.snapshot.isConfigured)
        assertTrue(client.snapshot.isAuthenticated)
        assertEquals("09120000000", client.snapshot.appUserIdentifier)
        assertEquals(writesBeforeReconfigure, store.saveCalls)
    }

    @Test
    fun configure_commitsSuccessfulSessionWhenCallerCancelsDuringOfferingsHydration() =
        runBlocking {
            val service = FakeService().apply {
                blockOfferings = true
            }
            val store = FakeSessionStore()
            val client = createClient(service = service, store = store)

            val configure = async {
                client.configure(InappifyOptions(apiKey = "mobile-api-key"))
            }
            service.offeringsStarted.await()
            configure.cancel()
            service.releaseOfferings.complete(Unit)
            configure.join()

            assertTrue(configure.isCancelled)
            assertTrue(client.snapshot.isConfigured)
            assertEquals("InaAnonymousId-1", client.snapshot.appUserIdentifier)
            assertEquals("anonymous-token", store.lastSaved?.token)
            client.close()
        }

    @Test
    fun configure_finishesRemoteResponseAndCommitAfterDispatchCancellation() = runBlocking {
        val service = BlockingService()
        val store = FakeSessionStore()
        val client = createClient(service = service, store = store)

        val configure = async {
            client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        }
        service.configureStarted.await()
        configure.cancel()
        assertFalse(client.snapshot.isConfigured)
        service.releaseConfigure.complete(Unit)
        configure.join()

        assertTrue(configure.isCancelled)
        assertTrue(client.snapshot.isConfigured)
        assertEquals("InaAnonymousId-1", client.snapshot.appUserIdentifier)
        assertEquals("anonymous-token", store.lastSaved?.token)
        client.close()
    }

    @Test
    fun login_requiresConfiguredClient() = runBlocking {
        val client = createClient()

        val result = client.login(
            InappifyLoginRequest(
                apiKey = "mobile-api-key",
                appUserIdentifier = "09120000000",
            ),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.NOT_CONFIGURED, result.error.code)
        assertFalse(result.snapshot?.isConfigured ?: true)
    }

    @Test
    fun login_replacesIdentityAndInvalidatesIdentityCaches() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponse(
                token = "anonymous-token",
                identifier = "InaAnonymousId-1",
            )
            loginResult = successfulResponse(
                token = "customer-token",
                identifier = "09120000000",
                forceVersion = 7,
            )
            offeringsResult = successfulOfferingsResponse("anonymous-offer")
        }
        val store = FakeSessionStore()
        val client = createClient(service = service, store = store)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        assertNotNull(client.snapshot.offerings)
        service.offeringsResult = ServiceResult.Failure(
            ServiceFailureKind.NETWORK,
        )

        val result = client.login(
            InappifyLoginRequest(
                apiKey = "mobile-api-key",
                appUserIdentifier = "09120000000",
            ),
        ) as InappifyResult.Success<*>

        assertTrue(result.snapshot.isAuthenticated)
        assertEquals("09120000000", result.snapshot.appUserIdentifier)
        assertEquals(7L, result.snapshot.forceVersion)
        assertEquals("anonymous-token", service.lastLogin?.token)
        assertEquals("customer-token", store.lastSaved?.token)
        assertEquals(
            "09120000000",
            result.snapshot.customerInfo?.originalAppUserId,
        )
        assertNull(result.snapshot.offerings)
        assertNull(store.lastSaved?.offeringsJson)
    }

    @Test
    fun loginForCurrentIdentity_stillRefreshesServerSession() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponse(
                token = "customer-token-1",
                identifier = "09120000000",
            )
            loginResult = successfulResponse(
                token = "customer-token-2",
                identifier = "09120000000",
            )
        }
        val client = createClient(service = service)
        client.configure(
            InappifyOptions(
                apiKey = "mobile-api-key",
                appUserIdentifier = "09120000000",
            ),
        )

        val result = client.login(
            InappifyLoginRequest(
                apiKey = "mobile-api-key",
                appUserIdentifier = "09120000000",
            ),
        )

        assertTrue(result is InappifyResult.Success<*>)
        assertEquals(1, service.loginCalls)
        assertEquals("customer-token-1", service.lastLogin?.token)
    }

    @Test
    fun backendUnauthorizedError_isMappedAndSensitiveMessageIsRedacted() =
        runBlocking {
            val service = FakeService().apply {
                configureResult = successfulResponse(
                    token = "anonymous-token",
                    identifier = "InaAnonymousId-1",
                )
            }
            val client = createClient(service = service)
            client.configure(InappifyOptions(apiKey = "mobile-api-key"))
            service.loginResult = ServiceResult.Response(
                statusCode = 401,
                payload = BackendResponse(
                    status = false,
                    message = "Invalid mobile-api-key for 09120000000.",
                    errorCode = "unauthorized",
                    token = null,
                    appUserIdentifier = null,
                    customerInfoJson = null,
                    storeInfo = null,
                    appId = null,
                    forceVersion = null,
                ),
                requestId = "unauthorized-request",
            )

            val result = client.login(
                InappifyLoginRequest(
                    apiKey = "mobile-api-key",
                    appUserIdentifier = "09120000000",
                ),
            ) as InappifyResult.Failure

            assertEquals(InappifyErrorCode.UNAUTHORIZED, result.error.code)
            assertFalse(result.error.message.contains("mobile-api-key"))
            assertFalse(result.error.message.contains("09120000000"))
            assertEquals("unauthorized-request", result.error.details["requestId"])
        }

    @Test
    fun login_rejectsBackendIdentityMismatchWithoutCommittingIt() = runBlocking {
        val service = FakeService().apply {
            loginResult = successfulResponse(
                token = "unexpected-token",
                identifier = "different-customer",
            )
        }
        val store = FakeSessionStore()
        val client = createClient(service, store)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        val writesBeforeLogin = store.saveCalls

        val result = client.login(
            InappifyLoginRequest(
                apiKey = "mobile-api-key",
                appUserIdentifier = "09120000000",
            ),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.MALFORMED_RESPONSE, result.error.code)
        assertFalse(client.snapshot.isAuthenticated)
        assertEquals(writesBeforeLogin, store.saveCalls)
    }

    @Test
    fun loginFailure_preservesPreviousSnapshotAndReportsCommitUncertainty() =
        runBlocking {
            val service = FakeService().apply {
                configureResult = successfulResponse(
                    token = "anonymous-token",
                    identifier = "InaAnonymousId-1",
                )
            }
            val store = FakeSessionStore()
            val client = createClient(service = service, store = store)
            client.configure(InappifyOptions(apiKey = "mobile-api-key"))
            val writesBeforeLogin = store.saveCalls
            service.loginResult = ServiceResult.Failure(
                ServiceFailureKind.NETWORK,
            )

            val result = client.login(
                InappifyLoginRequest(
                    apiKey = "mobile-api-key",
                    appUserIdentifier = "09120000000",
                ),
            ) as InappifyResult.Failure

            assertEquals(InappifyErrorCode.NETWORK, result.error.code)
            assertEquals(true, result.error.details["outcomeMayHaveCommitted"])
            assertEquals("InaAnonymousId-1", client.snapshot.appUserIdentifier)
            assertFalse(client.snapshot.isAuthenticated)
            assertEquals(writesBeforeLogin, store.saveCalls)
        }

    @Test
    fun loginFalseStatus_appliesForceAndRefreshesOfferingsBeforeReturningFailure() =
        runBlocking {
            val service = FakeService().apply {
                offeringsResult = successfulOfferingsResponse("initial", forceVersion = 1L)
            }
            val client = createClient(service = service)
            client.configure(InappifyOptions(apiKey = "mobile-api-key"))
            service.offeringsResult = successfulOfferingsResponse("refreshed", forceVersion = 4L)
            service.loginResult = ServiceResult.Response(
                statusCode = 200,
                payload = backendResponse(
                    status = false,
                    identifier = "09120000000",
                    forceVersion = 4L,
                ),
                requestId = "login-status-false",
            )

            val result = client.login(
                InappifyLoginRequest(
                    apiKey = "mobile-api-key",
                    appUserIdentifier = "09120000000",
                ),
            ) as InappifyResult.Failure

            assertEquals(4L, result.snapshot?.forceVersion)
            assertFalse(result.snapshot?.isAuthenticated == true)
            var attempts = 0
            while (
                client.snapshot.offerings?.offerings?.singleOrNull()?.identifier != "refreshed" &&
                attempts < 100
            ) {
                delay(10)
                attempts += 1
            }
            assertEquals("refreshed", client.snapshot.offerings?.offerings?.single()?.identifier)
            client.close()
        }

    @Test
    fun logoutFailureIsAtomic_thenSuccessCommitsAnonymousSession() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponse(
                token = "customer-token",
                identifier = "09120000000",
            )
        }
        val store = FakeSessionStore()
        val client = createClient(service = service, store = store)
        client.configure(
            InappifyOptions(
                apiKey = "mobile-api-key",
                appUserIdentifier = "09120000000",
            ),
        )
        service.logoutResult = ServiceResult.Response(
            statusCode = 503,
            payload = backendResponse(status = null),
            requestId = "logout-503",
        )

        val failed = client.logout() as InappifyResult.Failure

        assertEquals(InappifyErrorCode.NETWORK, failed.error.code)
        assertTrue(client.snapshot.isAuthenticated)
        assertEquals("09120000000", client.snapshot.appUserIdentifier)

        service.logoutResult = successfulResponse(
            token = "new-anonymous-token",
            identifier = "InaAnonymousId-2",
        )
        val succeeded = client.logout() as InappifyResult.Success<*>

        assertFalse(succeeded.snapshot.isAuthenticated)
        assertEquals("InaAnonymousId-2", succeeded.snapshot.appUserIdentifier)
        assertEquals("new-anonymous-token", store.lastSaved?.token)
    }

    @Test
    fun logoutMissingStatus_appliesForceWithoutChangingIdentity() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponse(
                token = "customer-token",
                identifier = "09120000000",
            )
            offeringsResult = successfulOfferingsResponse("initial", forceVersion = 1L)
        }
        val client = createClient(service = service)
        client.configure(
            InappifyOptions(
                apiKey = "mobile-api-key",
                appUserIdentifier = "09120000000",
            ),
        )
        service.offeringsResult = successfulOfferingsResponse("refreshed", forceVersion = 5L)
        service.logoutResult = ServiceResult.Response(
            statusCode = 200,
            payload = backendResponse(
                status = null,
                identifier = null,
                forceVersion = 5L,
            ),
            requestId = "logout-missing-status",
        )

        val result = client.logout() as InappifyResult.Failure

        assertEquals(InappifyErrorCode.MALFORMED_RESPONSE, result.error.code)
        assertEquals(5L, result.snapshot?.forceVersion)
        assertTrue(result.snapshot?.isAuthenticated == true)
        assertEquals("09120000000", result.snapshot?.appUserIdentifier)
        var attempts = 0
        while (
            client.snapshot.offerings?.offerings?.singleOrNull()?.identifier != "refreshed" &&
            attempts < 100
        ) {
            delay(10)
            attempts += 1
        }
        assertEquals("refreshed", client.snapshot.offerings?.offerings?.single()?.identifier)
        client.close()
    }

    @Test
    fun logout_acceptsSuccessfulResponseWithoutReplacementSession() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponse(
                token = "customer-token",
                identifier = "09120000000",
            )
            logoutResult = successfulResponseWithoutToken(identifier = null)
        }
        val store = FakeSessionStore()
        val client = createClient(service, store)
        client.configure(
            InappifyOptions(
                apiKey = "mobile-api-key",
                appUserIdentifier = "09120000000",
            ),
        )

        val result = client.logout() as InappifyResult.Success<*>

        assertFalse(result.snapshot.isAuthenticated)
        assertNull(result.snapshot.appUserIdentifier)
        assertNull(store.lastSaved?.token)
        assertNull(store.lastSaved?.appUserIdentifier)
    }

    @Test
    fun logout_rejectsNonAnonymousBackendIdentity() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponse(
                token = "customer-token",
                identifier = "09120000000",
            )
            logoutResult = successfulResponse(
                token = "unexpected-token",
                identifier = "another-customer",
            )
        }
        val store = FakeSessionStore()
        val client = createClient(service, store)
        client.configure(
            InappifyOptions(
                apiKey = "mobile-api-key",
                appUserIdentifier = "09120000000",
            ),
        )
        val writesBeforeLogout = store.saveCalls

        val result = client.logout() as InappifyResult.Failure

        assertEquals(InappifyErrorCode.MALFORMED_RESPONSE, result.error.code)
        assertTrue(client.snapshot.isAuthenticated)
        assertEquals(writesBeforeLogout, store.saveCalls)
    }

    @Test
    fun successfulMutationWithStoreFailure_appliesStateAndReportsFailure() =
        runBlocking {
            val service = FakeService()
            val store = FakeSessionStore(saveSucceeds = false)
            val client = createClient(service, store)

            val result = client.configure(
                InappifyOptions(apiKey = "mobile-api-key"),
            ) as InappifyResult.Failure

            assertEquals(InappifyErrorCode.STORE_UNAVAILABLE, result.error.code)
            assertTrue(client.snapshot.isConfigured)
            assertEquals(true, result.error.details["stateApplied"])
            assertEquals(true, result.error.details["staleSessionCleared"])
            assertEquals(1, store.clearCalls)
        }

    @Test
    fun operationsAreSerialized() = runBlocking {
        val service = BlockingService()
        val client = createClient(service = service)

        val configure = async {
            client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        }
        service.configureStarted.await()
        val login = async {
            client.login(
                InappifyLoginRequest(
                    apiKey = "mobile-api-key",
                    appUserIdentifier = "09120000000",
                ),
            )
        }
        delay(25)

        assertEquals(0, service.loginCalls)

        service.releaseConfigure.complete(Unit)
        assertTrue(configure.await() is InappifyResult.Success<*>)
        assertTrue(login.await() is InappifyResult.Success<*>)
        assertEquals(1, service.loginCalls)
    }

    @Test
    fun closeIsIdempotentAndRejectsNewOperations() = runBlocking {
        val service = FakeService()
        val client = createClient(service = service)

        client.close()
        client.close()

        assertEquals(1, service.closeCalls)
        try {
            client.configure(InappifyOptions(apiKey = "mobile-api-key"))
            fail("A closed client must reject new operations.")
        } catch (_: IllegalStateException) {
            // Expected lifecycle failure.
        }
    }

    @Test
    fun customerInfo_defaultsToRefreshAndCanUseFiveMinuteCache() =
        runBlocking {
            var now = 1_000_000L
            val service = FakeService()
            val client = createClient(
                service = service,
                currentTimeMillis = { now },
            )
            client.configure(InappifyOptions(apiKey = "mobile-api-key"))

            val cached = client.getCustomerInfo(forceRefresh = false)
            assertTrue(cached is InappifyResult.Success)
            assertEquals(0, service.customerInfoCalls)

            val refreshed = client.getCustomerInfo()
            assertTrue(refreshed is InappifyResult.Success)
            assertEquals(1, service.customerInfoCalls)

            now += 5 * 60 * 1000L
            val stale = client.getCustomerInfo(forceRefresh = false)
            assertTrue(stale is InappifyResult.Success)
            assertEquals(2, service.customerInfoCalls)
        }

    @Test
    fun customerInfo_refreshAcceptsMillisecondStorePurchaseTime() = runBlocking {
        val service = FakeService()
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        service.customerInfoResult = ServiceResult.Response(
            statusCode = 200,
            payload = BackendResponse(
                status = true,
                message = null,
                errorCode = null,
                token = null,
                appUserIdentifier = "InaAnonymousId-1",
                customerInfoJson =
                    """
                    {
                      "originalAppUserId": "InaAnonymousId-1",
                      "entitlements": [{
                        "identifier": "premium",
                        "purchase_store_time": 1725000000000
                      }]
                    }
                    """.trimIndent(),
                storeInfo = null,
                appId = null,
                forceVersion = 1,
            ),
            requestId = "customer-info-millisecond-time",
        )

        val result = client.refreshCustomerInfo()
            as InappifyResult.Success<InappifyCustomerInfo>

        assertEquals(
            1_725_000_000_000L,
            result.data.entitlements?.single()?.purchaseStoreTime,
        )
        assertTrue(result.snapshot.failedToLoadCustomerInfo.not())
    }

    @Test
    fun offerings_cacheIsSessionBoundAndRefreshAlwaysUsesNetwork() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = successfulOfferingsResponse("starter")
        }
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))

        val automaticallyLoaded = client.getOfferings()
            as InappifyResult.Success<InappifyOfferings>
        assertEquals(
            "starter",
            automaticallyLoaded.data.offerings?.single()?.identifier,
        )
        assertNull(automaticallyLoaded.data.forceVersion)
        assertEquals(1L, automaticallyLoaded.snapshot.forceVersion)
        assertNull(automaticallyLoaded.snapshot.offerings?.forceVersion)
        assertEquals(1, service.offeringsCalls)

        service.offeringsResult = successfulOfferingsResponse("refreshed")
        val refreshed = client.refreshOfferings()
            as InappifyResult.Success<InappifyOfferings>
        assertEquals("refreshed", refreshed.data.offerings?.single()?.identifier)
        assertEquals(2, service.offeringsCalls)
    }

    @Test
    fun failedCustomerRefresh_preservesCacheAndMarksSnapshot() = runBlocking {
        val service = FakeService()
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        val cached = client.snapshot.customerInfo
        assertNotNull(cached)
        service.customerInfoResult = ServiceResult.Failure(
            ServiceFailureKind.NETWORK,
        )

        val result = client.refreshCustomerInfo() as InappifyResult.Failure

        assertEquals(InappifyErrorCode.NETWORK, result.error.code)
        assertEquals(cached, result.snapshot?.customerInfo)
        assertTrue(result.snapshot?.failedToLoadCustomerInfo == true)
    }

    @Test
    fun customerStatusFailure_appliesForceAndStartsOfferingRefresh() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = successfulOfferingsResponse("initial", forceVersion = 1L)
        }
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        service.offeringsResult = successfulOfferingsResponse("refreshed", forceVersion = 6L)
        service.customerInfoResult = ServiceResult.Response(
            statusCode = 200,
            payload = backendResponse(
                status = false,
                identifier = "InaAnonymousId-1",
                forceVersion = 6L,
            ),
            requestId = "customer-status-false",
        )

        val result = client.refreshCustomerInfo() as InappifyResult.Failure

        assertEquals(6L, result.snapshot?.forceVersion)
        assertTrue(result.snapshot?.failedToLoadCustomerInfo == true)
        var attempts = 0
        while (
            client.snapshot.offerings?.offerings?.singleOrNull()?.identifier != "refreshed" &&
            attempts < 100
        ) {
            delay(10)
            attempts += 1
        }
        assertEquals("refreshed", client.snapshot.offerings?.offerings?.single()?.identifier)
        client.close()
    }

    @Test
    fun successfulCustomerForceChange_startsOfferingRefresh() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = successfulOfferingsResponse("initial", forceVersion = 1L)
        }
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        service.offeringsResult = successfulOfferingsResponse("refreshed", forceVersion = 7L)
        service.customerInfoResult = ServiceResult.Response(
            statusCode = 200,
            payload = backendResponse(
                status = true,
                identifier = "InaAnonymousId-1",
                forceVersion = 7L,
            ),
            requestId = "customer-force-success",
        )

        val result = client.refreshCustomerInfo()
            as InappifyResult.Success<InappifyCustomerInfo>

        assertEquals(7L, result.snapshot.forceVersion)
        var attempts = 0
        while (
            client.snapshot.offerings?.offerings?.singleOrNull()?.identifier != "refreshed" &&
            attempts < 100
        ) {
            delay(10)
            attempts += 1
        }
        assertEquals("refreshed", client.snapshot.offerings?.offerings?.single()?.identifier)
        client.close()
    }

    @Test
    fun failedOfferingsRefresh_preservesCacheAndMarksSnapshot() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = successfulOfferingsResponse("starter")
        }
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        val cached = client.snapshot.offerings
        assertNotNull(cached)
        service.offeringsResult = ServiceResult.Failure(
            ServiceFailureKind.TIMEOUT,
        )

        val result = client.refreshOfferings() as InappifyResult.Failure

        assertEquals(InappifyErrorCode.TIMEOUT, result.error.code)
        assertEquals(cached, result.snapshot?.offerings)
        assertTrue(result.snapshot?.failedToLoadOfferings == true)
    }

    @Test
    fun newerCustomerForceVersion_invalidatesCachedOfferings() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = successfulOfferingsResponse("starter")
        }
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        assertNotNull(client.snapshot.offerings)
        service.customerInfoResult = ServiceResult.Response(
            statusCode = 200,
            payload = backendResponse(
                status = true,
                identifier = "InaAnonymousId-1",
                forceVersion = 4,
            ),
            requestId = "customer-force-version",
        )

        val result = client.refreshCustomerInfo()
            as InappifyResult.Success<InappifyCustomerInfo>

        assertEquals(4L, result.snapshot.forceVersion)
        assertNull(result.snapshot.offerings)
    }

    @Test
    fun malformedOfferingFields_followEmptyOfferingFallback() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = successfulOfferingsResponse("starter")
        }
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        service.offeringsResult = ServiceResult.Response(
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
                forceVersion = 9,
                offeringsJson =
                    """{"offerings":[{"identifier":7}],"rules":[],"forceVersion":9}""",
            ),
            requestId = "malformed-newer-offerings",
        )

        val result = client.refreshOfferings() as InappifyResult.Success<InappifyOfferings>

        assertEquals(9L, result.snapshot.forceVersion)
        assertEquals(1, result.data.offerings?.size)
        assertNull(result.data.offerings?.single()?.identifier)
        assertFalse(result.snapshot.failedToLoadOfferings)
    }

    @Test
    fun purchase_verifiesBazaarEvidenceThenRefreshesAuthoritativeResources() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
            purchaseResult = successfulPurchaseResponse()
        }
        val adapter = FakeStoreBillingAdapter(
            StoreBillingResult.Success(storePurchase()),
        )
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ -> adapter },
        )
        val purchaseEvent = CountDownLatch(1)
        var eventRequestId: String? = null
        client.addEventListener { event ->
            if (event.type == InappifyEventType.PURCHASE_UPDATED) {
                eventRequestId = event.requestId
                purchaseEvent.countDown()
            }
        }
        configureBazaar(client)

        val result = client.purchase(
            activity = Activity(),
            request = InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                packageIdentifier = "monthly",
                market = InappifyMarket.BAZAAR,
                marketKey = "market-key",
                idempotencyKey = "purchase-attempt-1",
                discount = 10,
            ),
        ) as InappifyResult.Success

        assertEquals(InappifyPurchaseStatus.DONE, result.data.purchaseStatus)
        assertEquals("purchase-attempt-1", result.data.attemptId)
        assertEquals(1, adapter.purchaseCalls)
        assertEquals(1, adapter.closeCalls)
        assertEquals("premium-product", adapter.lastRequest?.productIdentifier)
        val developerPayload = JsonParser.parseString(
            adapter.lastRequest?.developerPayload,
        ).asJsonObject
        assertEquals("main", developerPayload["offeringIdentifier"].asString)
        assertEquals("premium-product", developerPayload["packageIdentifier"].asString)
        assertEquals("market-key", developerPayload["marketKey"].asString)
        assertEquals("monthly", developerPayload["nativePackageIdentifier"].asString)
        assertFalse(developerPayload.toString().contains("mobile-api-key"))
        assertFalse(developerPayload.toString().contains("InaAnonymousId-1"))
        assertEquals(1, service.purchaseCalls)
        assertEquals("store-purchase-token", service.lastPurchase?.purchaseTokenId)
        assertEquals(1_725_000_000_000L, service.lastPurchase?.purchaseStoreTime)
        assertEquals("com.example.host", service.lastPurchase?.appIdentifier)
        assertEquals("IR", service.lastPurchase?.country)
        assertEquals("1.2.3", service.lastPurchase?.appVersion)
        assertEquals(1, service.customerInfoCalls)
        assertEquals(2, service.offeringsCalls)
        assertTrue(purchaseEvent.await(2, TimeUnit.SECONDS))
        assertEquals("purchase-attempt-1", eventRequestId)
        assertFalse(result.data.toString().contains("store-purchase-token"))
        client.close()
    }

    @Test
    fun purchase_cancelledByBazaarNeverCallsBackend() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
        }
        val adapter = FakeStoreBillingAdapter(StoreBillingResult.Cancelled)
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ -> adapter },
        )
        configureBazaar(client)

        val result = client.purchase(
            Activity(),
            InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                market = InappifyMarket.BAZAAR,
                marketKey = "market-key",
                idempotencyKey = "purchase-cancelled-1",
            ),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.PURCHASE_CANCELLED, result.error.code)
        assertEquals(false, result.error.details["outcomeMayHaveCommitted"])
        assertEquals(0, service.purchaseCalls)
        assertEquals(1, adapter.closeCalls)
        client.close()
    }

    @Test
    fun purchase_backendRejectionIgnoresNon200ForceAndNeverLeaksStoreEvidence() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
            purchaseResult = ServiceResult.Response(
                statusCode = 422,
                payload = BackendResponse(
                    status = false,
                    message = "Purchase rejected.",
                    errorCode = "invalid_purchase",
                    token = null,
                    appUserIdentifier = null,
                    customerInfoJson = null,
                    storeInfo = null,
                    appId = null,
                    forceVersion = 9,
                ),
                requestId = "purchase-rejected",
            )
        }
        val adapter = FakeStoreBillingAdapter(
            StoreBillingResult.Success(storePurchase()),
        )
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ -> adapter },
        )
        configureBazaar(client)

        val result = client.purchase(
            Activity(),
            InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                market = InappifyMarket.BAZAAR,
                marketKey = "market-key",
                idempotencyKey = "purchase-rejected-1",
            ),
        ) as InappifyResult.Failure

        assertEquals(
            InappifyErrorCode.PURCHASE_VERIFICATION_FAILED,
            result.error.code,
        )
        assertEquals(true, result.error.details["storePurchaseCompleted"])
        assertFalse(result.error.toString().contains("store-purchase-token"))
        assertEquals(1, service.customerInfoCalls)
        assertEquals(2, service.offeringsCalls)
        assertEquals(1L, result.snapshot?.forceVersion)
        client.close()
    }

    @Test
    fun purchase_nullableBackendStatusRefreshesForceVersionBeforeFailure() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
            purchaseResult = successfulPurchaseResponse(
                forceVersion = 10,
                responseStatus = null,
            )
        }
        val client = createClient(service = service)
        configureBazaar(client)

        val result = client.purchase(
            InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                market = InappifyMarket.NONE,
                idempotencyKey = "nullable-backend-status-1",
            ),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.MALFORMED_RESPONSE, result.error.code)
        assertEquals(1, service.customerInfoCalls)
        assertEquals(2, service.offeringsCalls)
        assertEquals(10L, result.snapshot?.forceVersion)
        client.close()
    }

    @Test
    fun purchase_networkFailureRefreshesAndPreservesOriginalFailure() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
            purchaseResult = ServiceResult.Failure(ServiceFailureKind.NETWORK)
        }
        val client = createClient(service = service)
        configureBazaar(client)

        val result = client.purchase(
            InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                market = InappifyMarket.NONE,
                idempotencyKey = "transport-failure-1",
            ),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.NETWORK, result.error.code)
        assertEquals(1, service.customerInfoCalls)
        assertEquals(2, service.offeringsCalls)
        client.close()
    }

    @Test
    fun purchase_cancelledServiceResultRefreshesAndPreservesOriginalFailure() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
            purchaseResult = ServiceResult.Failure(ServiceFailureKind.CANCELLED)
        }
        val client = createClient(service = service)
        configureBazaar(client)

        val result = client.purchase(
            InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                market = InappifyMarket.NONE,
                idempotencyKey = "cancelled-service-result-1",
            ),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.REQUEST_CANCELLED, result.error.code)
        assertEquals(1, service.customerInfoCalls)
        assertEquals(2, service.offeringsCalls)
        client.close()
    }

    @Test
    fun purchase_malformedResponseStillRefreshesAndPreservesMalformedFailure() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
            purchaseResult = ServiceResult.Response(
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
                    forceVersion = 8,
                    purchase = null,
                ),
                requestId = "malformed-purchase-response",
            )
        }
        val client = createClient(service = service)
        configureBazaar(client)

        val result = client.purchase(
            InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                market = InappifyMarket.NONE,
                idempotencyKey = "malformed-purchase-1",
            ),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.MALFORMED_RESPONSE, result.error.code)
        assertEquals(1, service.customerInfoCalls)
        assertEquals(2, service.offeringsCalls)
        assertEquals(8L, result.snapshot?.forceVersion)
        client.close()
    }

    @Test
    fun purchase_decoderFailureDropsForceButNullStatusIsAccepted() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
            purchaseResult = ServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
        }
        val client = createClient(service = service)
        configureBazaar(client)

        val malformed = client.purchase(
            InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                market = InappifyMarket.NONE,
                idempotencyKey = "unknown-status-1",
            ),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.MALFORMED_RESPONSE, malformed.error.code)
        assertEquals(1L, malformed.snapshot?.forceVersion)
        assertEquals(1, service.customerInfoCalls)
        assertEquals(2, service.offeringsCalls)

        service.purchaseResult = successfulPurchaseResponse(
            purchaseStatus = null,
            forceVersion = 7,
        )
        val nullable = client.purchase(
            InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                market = InappifyMarket.NONE,
                idempotencyKey = "null-status-1",
            ),
        ) as InappifyResult.Success

        assertNull(nullable.data.purchaseStatus)
        client.close()
    }

    @Test
    fun purchase_cancellationAfterResponseCommitsMinimalForceVersion() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
            purchaseResult = successfulPurchaseResponse(forceVersion = 11)
        }
        val store = FakeSessionStore()
        val client = createClient(service = service, store = store)
        configureBazaar(client)
        service.blockOfferings = true

        val purchase = async {
            client.purchase(
                InappifyPurchaseRequest(
                    productIdentifier = "premium-product",
                    offeringIdentifier = "main",
                    market = InappifyMarket.NONE,
                    idempotencyKey = "cancelled-reconciliation-1",
                ),
            )
        }
        service.offeringsStarted.await()
        purchase.cancel()
        service.releaseOfferings.complete(Unit)
        purchase.join()

        assertTrue(purchase.isCancelled)
        assertEquals(11L, client.snapshot.forceVersion)
        assertNull(client.snapshot.offerings)
        assertEquals(11L, store.lastSaved?.forceVersion)
        client.close()
    }

    @Test
    fun trialPurchase_bypassesBazaarAndUsesTheInappifyBackend() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 7)
            purchaseResult = successfulPurchaseResponse()
        }
        var adapterCreations = 0
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ ->
                adapterCreations += 1
                FakeStoreBillingAdapter(StoreBillingResult.Cancelled)
            },
        )
        configureBazaar(client)

        val result = client.purchase(
            InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                market = InappifyMarket.BAZAAR,
                marketKey = "market-key",
                idempotencyKey = "trial-attempt-1",
            ),
        ) as InappifyResult.Success

        assertEquals(InappifyPurchaseStatus.DONE, result.data.purchaseStatus)
        assertEquals(0, adapterCreations)
        assertEquals(1, service.purchaseCalls)
        assertNull(service.lastPurchase?.purchaseTokenId)
        assertNull(service.lastPurchase?.purchaseStoreTime)
        client.close()
    }

    @Test
    fun bazaarNonTrial_withoutActivityFailsBeforeOpeningStoreOrBackend() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
            purchaseResult = successfulPurchaseResponse()
        }
        var adapterCreations = 0
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ ->
                adapterCreations += 1
                FakeStoreBillingAdapter(StoreBillingResult.Cancelled)
            },
        )
        configureBazaar(client)

        val result = client.purchase(
            InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                market = InappifyMarket.BAZAAR,
                marketKey = "market-key",
                idempotencyKey = "missing-activity-1",
            ),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.STORE_UNAVAILABLE, result.error.code)
        assertEquals(false, result.error.details["outcomeMayHaveCommitted"])
        assertEquals(0, adapterCreations)
        assertEquals(0, service.purchaseCalls)
        client.close()
    }

    @Test
    fun purchase_noneSkipsStoreAndUsesPerPurchaseOverrides() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
            purchaseResult = successfulPurchaseResponse()
        }
        var adapterCreations = 0
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ ->
                adapterCreations += 1
                FakeStoreBillingAdapter(StoreBillingResult.Cancelled)
            },
        )
        configureBazaar(client)

        val result = client.purchase(
            InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                packageIdentifier = "monthly",
                discountCode = "WELCOME20",
                country = " us ",
                appVersion = " 9.8.7 ",
                apiKey = " direct-api-key ",
                market = InappifyMarket.NONE,
                marketKey = "unused-direct-market-key",
                idempotencyKey = "direct-attempt-1",
            ),
        ) as InappifyResult.Success

        assertEquals(InappifyMarket.NONE, result.data.market)
        assertEquals(0, adapterCreations)
        assertEquals(" direct-api-key ", service.lastPurchase?.apiKey)
        assertEquals(" us ", service.lastPurchase?.country)
        assertEquals(" 9.8.7 ", service.lastPurchase?.appVersion)
        assertNull(service.lastPurchase?.purchaseTokenId)
        assertNull(service.lastPurchase?.purchaseStoreTime)
        assertEquals(InappifyMarket.BAZAAR, result.snapshot.market)
        client.close()
    }

    @Test
    fun purchase_preservesRawIdentifiersAndDoesNotTrimForLookup() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
            purchaseResult = successfulPurchaseResponse()
        }
        val client = createClient(service = service)
        configureBazaar(client)

        val whitespaceProduct = client.purchase(
            InappifyPurchaseRequest(
                productIdentifier = " premium-product ",
                offeringIdentifier = "main",
                market = InappifyMarket.NONE,
                idempotencyKey = "raw-lookup-1",
            ),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.INVALID_CONFIGURATION, whitespaceProduct.error.code)
        assertEquals(0, service.purchaseCalls)

        val emptyApiKey = client.purchase(
            InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                apiKey = "",
                market = InappifyMarket.NONE,
                idempotencyKey = "raw-empty-api-key-1",
            ),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.INVALID_CONFIGURATION, emptyApiKey.error.code)
        assertEquals(0, service.purchaseCalls)

        val raw = client.purchase(
            InappifyPurchaseRequest(
                productIdentifier = " lost-product ",
                offeringIdentifier = " lost-offering ",
                packageIdentifier = " lost-package ",
                country = " ir ",
                appVersion = " 2.0.0 ",
                apiKey = " raw-api-key ",
                market = InappifyMarket.NONE,
                isLostPurchase = true,
                lostPurchaseToken = "raw-lost-token",
                lostPurchaseTime = 1_725_111_222_334L,
                idempotencyKey = "raw-lost-purchase-1",
            ),
        ) as InappifyResult.Success

        assertEquals(" lost-product ", service.lastPurchase?.productIdentifier)
        assertEquals(" lost-offering ", service.lastPurchase?.offeringIdentifier)
        assertEquals(" ir ", service.lastPurchase?.country)
        assertEquals(" 2.0.0 ", service.lastPurchase?.appVersion)
        assertEquals(" raw-api-key ", service.lastPurchase?.apiKey)
        assertEquals(" lost-package ", raw.data.packageIdentifier)
        client.close()
    }

    @Test
    fun purchase_bazaarUsesRequestMarketAndKeyInsteadOfConfiguredMarket() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
            purchaseResult = successfulPurchaseResponse()
        }
        val adapter = FakeStoreBillingAdapter(
            StoreBillingResult.Success(storePurchase()),
        )
        var requestedMarket: InappifyMarket? = null
        var requestedMarketKey: String? = null
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { market, marketKey ->
                requestedMarket = market
                requestedMarketKey = marketKey
                adapter
            },
        )
        val configured = client.configure(
            InappifyOptions(
                apiKey = "mobile-api-key",
                market = InappifyMarket.NONE,
            ),
        )
        assertTrue(configured is InappifyResult.Success)

        val result = client.purchase(
            Activity(),
            InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                market = InappifyMarket.BAZAAR,
                marketKey = " per-purchase-market-key ",
                idempotencyKey = "bazaar-override-1",
            ),
        ) as InappifyResult.Success

        assertEquals(InappifyMarket.BAZAAR, requestedMarket)
        assertEquals(" per-purchase-market-key ", requestedMarketKey)
        assertEquals(1, adapter.purchaseCalls)
        assertEquals(InappifyMarket.BAZAAR, result.data.market)
        assertEquals("store-purchase-token", service.lastPurchase?.purchaseTokenId)
        assertEquals(InappifyMarket.NONE, result.snapshot.market)
        client.close()
    }

    @Test
    fun lostPurchase_bypassesOfferingsAndStoreAndSubmitsSuppliedEvidence() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = ServiceResult.Failure(ServiceFailureKind.NETWORK)
            purchaseResult = successfulPurchaseResponse()
        }
        var adapterCreations = 0
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ ->
                adapterCreations += 1
                FakeStoreBillingAdapter(StoreBillingResult.Cancelled)
            },
        )
        configureBazaar(client)

        val result = client.purchase(
            InappifyPurchaseRequest(
                productIdentifier = "lost-product",
                offeringIdentifier = "lost-offering",
                packageIdentifier = "lost-package",
                apiKey = "recovery-api-key",
                market = InappifyMarket.BAZAAR,
                isLostPurchase = true,
                lostPurchaseToken = "lost-store-token",
                lostPurchaseTime = 1_725_111_222_333L,
                idempotencyKey = "lost-attempt-1",
            ),
        ) as InappifyResult.Success

        assertEquals(0, adapterCreations)
        assertEquals("recovery-api-key", service.lastPurchase?.apiKey)
        assertEquals("lost-store-token", service.lastPurchase?.purchaseTokenId)
        assertEquals(1_725_111_222_333L, service.lastPurchase?.purchaseStoreTime)
        assertEquals("lost-package", result.data.packageIdentifier)
        assertEquals(InappifyMarket.BAZAAR, result.data.market)
        assertFalse(result.data.toString().contains("lost-store-token"))
        client.close()
    }

    @Test
    fun concurrentPurchase_isRejectedBeforeOpeningASecondStoreFlow() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
            purchaseResult = successfulPurchaseResponse()
        }
        val adapter = BlockingStoreBillingAdapter()
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ -> adapter },
        )
        configureBazaar(client)
        val first = async {
            client.purchase(
                Activity(),
                InappifyPurchaseRequest(
                    productIdentifier = "premium-product",
                    offeringIdentifier = "main",
                    market = InappifyMarket.BAZAAR,
                    marketKey = "market-key",
                    idempotencyKey = "purchase-first",
                ),
            )
        }
        adapter.started.await()

        val second = client.purchase(
            Activity(),
            InappifyPurchaseRequest(
                productIdentifier = "premium-product",
                offeringIdentifier = "main",
                market = InappifyMarket.BAZAAR,
                marketKey = "market-key",
                idempotencyKey = "purchase-second",
            ),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.PURCHASE_IN_PROGRESS, second.error.code)
        assertEquals(1, adapter.purchaseCalls)
        adapter.release.complete(Unit)
        assertTrue(first.await() is InappifyResult.Success)
        client.close()
    }

    @Test
    fun syncPurchases_recoversCompatibleBazaarReceiptWithoutOpeningUi() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
            purchaseResult = successfulPurchaseResponse()
        }
        val payload =
            """{"offeringIdentifier":"main","productIdentifier":"premium-product","packageIdentifier":"monthly","attemptId":"recovery-attempt","discount":7,"isCrypto":false,"recoveryBinding":"$TEST_RECOVERY_BINDING"}"""
        val adapter = FakeStoreBillingAdapter(
            result = StoreBillingResult.Cancelled,
            queryResult = StorePurchaseQueryResult.Success(
                listOf(storePurchase(developerPayload = payload)),
            ),
        )
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ -> adapter },
        )
        configureBazaar(client)

        val result = client.syncPurchases()
            as InappifyResult.Success<List<InappifyPurchase>>

        assertEquals(1, result.data.size)
        assertEquals("recovery-attempt", result.data.single().attemptId)
        assertEquals(InappifyPurchaseStatus.DONE, result.data.single().purchaseStatus)
        assertEquals(0, adapter.purchaseCalls)
        assertEquals(1, adapter.queryCalls)
        assertEquals(1, adapter.closeCalls)
        assertEquals(1, service.purchaseCalls)
        assertEquals("store-purchase-token", service.lastPurchase?.purchaseTokenId)
        assertEquals(7L, service.lastPurchase?.discount)
        assertFalse(result.toString().contains("store-purchase-token"))
        client.close()
    }

    @Test
    fun syncPurchases_ignoresReceiptWithoutCompatibleInappifyPayload() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
        }
        val adapter = FakeStoreBillingAdapter(
            result = StoreBillingResult.Cancelled,
            queryResult = StorePurchaseQueryResult.Success(
                listOf(storePurchase(developerPayload = "unrelated-payload")),
            ),
        )
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ -> adapter },
        )
        configureBazaar(client)

        val result = client.syncPurchases()
            as InappifyResult.Success<List<InappifyPurchase>>

        assertTrue(result.data.isEmpty())
        assertEquals(1, adapter.queryCalls)
        assertEquals(0, service.purchaseCalls)
        client.close()
    }

    @Test
    fun syncPurchases_skipsReceiptAlreadyRepresentedByEntitlementHash() = runBlocking {
        val service = FakeService().apply {
            configureResult = ServiceResult.Response(
                statusCode = 200,
                payload = BackendResponse(
                    status = true,
                    message = null,
                    errorCode = null,
                    token = "anonymous-token",
                    appUserIdentifier = "InaAnonymousId-1",
                    customerInfoJson =
                        """{"originalAppUserId":"InaAnonymousId-1","entitlements":[{"purchaseStoreRefHash":"f6ef7439f17f083c88b810a4f0e8b6ddcd4dd3f815baf8dbcb1c3eea12130a09"}]}""",
                    storeInfo = "bazar",
                    appId = 1,
                    forceVersion = 1,
                ),
                requestId = "configure-with-entitlement",
            )
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
        }
        val payload =
            """{"offeringIdentifier":"main","productIdentifier":"premium-product","packageIdentifier":"monthly","recoveryBinding":"$TEST_RECOVERY_BINDING"}"""
        val adapter = FakeStoreBillingAdapter(
            result = StoreBillingResult.Cancelled,
            queryResult = StorePurchaseQueryResult.Success(
                listOf(storePurchase(developerPayload = payload)),
            ),
        )
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ -> adapter },
        )
        configureBazaar(client)

        val result = client.syncPurchases()
            as InappifyResult.Success<List<InappifyPurchase>>

        assertTrue(result.data.isEmpty())
        assertEquals(0, service.purchaseCalls)
        client.close()
    }

    @Test
    fun syncPurchases_rejectsReceiptCreatedForPreviousInappifyIdentity() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = purchaseOfferingsResponse(trialDays = 0)
        }
        val payload =
            """{"offeringIdentifier":"main","productIdentifier":"premium-product","packageIdentifier":"monthly","recoveryBinding":"$TEST_RECOVERY_BINDING"}"""
        val adapter = FakeStoreBillingAdapter(
            result = StoreBillingResult.Cancelled,
            queryResult = StorePurchaseQueryResult.Success(
                listOf(storePurchase(developerPayload = payload)),
            ),
        )
        val recoveryIds = mutableListOf(
            TEST_RECOVERY_BINDING,
            "different-identity-recovery-id",
        )
        val client = createClient(
            service = service,
            purchaseRecoveryIdProvider = { recoveryIds.removeAt(0) },
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ -> adapter },
        )
        configureBazaar(client)
        val login = client.login(
            InappifyLoginRequest(
                apiKey = "mobile-api-key",
                appUserIdentifier = "09120000000",
            ),
        )
        assertTrue(login is InappifyResult.Success)

        val result = client.syncPurchases()
            as InappifyResult.Success<List<InappifyPurchase>>

        assertTrue(result.data.isEmpty())
        assertEquals(0, service.purchaseCalls)
        client.close()
    }

    @Test
    fun syncPurchases_preservesRecoveryIdentityWhenSameCustomerLogsInAgain() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = successfulOfferingsResponse("replacement-offering")
            purchaseResult = successfulPurchaseResponse()
        }
        val userRecoveryId = "same-user-recovery-id"
        val payload =
            """{"offeringIdentifier":"main","productIdentifier":"premium-product","recoveryBinding":"$userRecoveryId"}"""
        val adapter = FakeStoreBillingAdapter(
            result = StoreBillingResult.Cancelled,
            queryResult = StorePurchaseQueryResult.Success(
                listOf(storePurchase(developerPayload = payload)),
            ),
        )
        val recoveryIds = mutableListOf(TEST_RECOVERY_BINDING, userRecoveryId)
        val client = createClient(
            service = service,
            purchaseRecoveryIdProvider = { recoveryIds.removeAt(0) },
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ -> adapter },
        )
        configureBazaar(client)
        repeat(2) {
            val login = client.login(
                InappifyLoginRequest(
                    apiKey = "mobile-api-key",
                    appUserIdentifier = "09120000000",
                ),
            )
            assertTrue(login is InappifyResult.Success)
        }

        val result = client.syncPurchases()
            as InappifyResult.Success<List<InappifyPurchase>>

        assertEquals(1, result.data.size)
        assertEquals(1, service.purchaseCalls)
        client.close()
    }

    @Test
    fun syncPurchases_recoversReceiptAfterOfferingWasRemoved() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = successfulOfferingsResponse("replacement-offering")
            purchaseResult = successfulPurchaseResponse()
        }
        val payload =
            """{"offeringIdentifier":"removed-offering","productIdentifier":"premium-product","packageIdentifier":"monthly","recoveryBinding":"$TEST_RECOVERY_BINDING"}"""
        val adapter = FakeStoreBillingAdapter(
            result = StoreBillingResult.Cancelled,
            queryResult = StorePurchaseQueryResult.Success(
                listOf(storePurchase(developerPayload = payload)),
            ),
        )
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ -> adapter },
        )
        configureBazaar(client)

        val result = client.syncPurchases()
            as InappifyResult.Success<List<InappifyPurchase>>

        assertEquals("removed-offering", result.data.single().offeringIdentifier)
        assertEquals(1, service.purchaseCalls)
        client.close()
    }

    @Test
    fun syncPurchases_commitsSuccessfulSubsetBeforeReturningLaterFailure() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = successfulOfferingsResponse("replacement-offering")
            queuedPurchaseResults += successfulPurchaseResponse()
            queuedPurchaseResults += ServiceResult.Failure(ServiceFailureKind.NETWORK)
        }
        val firstPayload =
            """{"offeringIdentifier":"first","productIdentifier":"premium-product","attemptId":"first-recovery","recoveryBinding":"$TEST_RECOVERY_BINDING"}"""
        val secondPayload =
            """{"offeringIdentifier":"second","productIdentifier":"premium-product","attemptId":"second-recovery","recoveryBinding":"$TEST_RECOVERY_BINDING"}"""
        val adapter = FakeStoreBillingAdapter(
            result = StoreBillingResult.Cancelled,
            queryResult = StorePurchaseQueryResult.Success(
                listOf(
                    storePurchase(
                        developerPayload = firstPayload,
                        purchaseToken = "first-token",
                    ),
                    storePurchase(
                        developerPayload = secondPayload,
                        purchaseToken = "second-token",
                    ),
                ),
            ),
        )
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ -> adapter },
        )
        configureBazaar(client)
        val revisionBeforeSync = client.snapshot.revision

        val result = client.syncPurchases() as InappifyResult.Failure

        assertEquals(InappifyErrorCode.NETWORK, result.error.code)
        assertEquals(1, result.error.details["recoveredCount"])
        assertEquals(2, service.purchaseCalls)
        assertTrue(requireNotNull(result.snapshot).revision > revisionBeforeSync)
        client.close()
    }

    @Test
    fun syncPurchases_permanentReceiptRejectionDoesNotBlockLaterReceipt() = runBlocking {
        val service = FakeService().apply {
            offeringsResult = successfulOfferingsResponse("replacement-offering")
            queuedPurchaseResults += ServiceResult.Response(
                statusCode = 422,
                payload = BackendResponse(
                    status = false,
                    message = "Purchase rejected.",
                    errorCode = "invalid_purchase",
                    token = null,
                    appUserIdentifier = null,
                    customerInfoJson = null,
                    storeInfo = null,
                    appId = null,
                    forceVersion = 1,
                ),
                requestId = "rejected-recovery",
            )
            queuedPurchaseResults += successfulPurchaseResponse()
        }
        val rejectedPayload =
            """{"offeringIdentifier":"rejected","productIdentifier":"premium-product","recoveryBinding":"$TEST_RECOVERY_BINDING"}"""
        val validPayload =
            """{"offeringIdentifier":"valid","productIdentifier":"premium-product","recoveryBinding":"$TEST_RECOVERY_BINDING"}"""
        val adapter = FakeStoreBillingAdapter(
            result = StoreBillingResult.Cancelled,
            queryResult = StorePurchaseQueryResult.Success(
                listOf(
                    storePurchase(
                        developerPayload = rejectedPayload,
                        purchaseToken = "rejected-token",
                    ),
                    storePurchase(
                        developerPayload = validPayload,
                        purchaseToken = "valid-token",
                    ),
                ),
            ),
        )
        val client = createClient(
            service = service,
            storeBillingAdapterFactory = StoreBillingAdapterFactory { _, _ -> adapter },
        )
        configureBazaar(client)

        val result = client.syncPurchases() as InappifyResult.Failure

        assertEquals(InappifyErrorCode.PURCHASE_VERIFICATION_FAILED, result.error.code)
        assertEquals(1, result.error.details["recoveredCount"])
        assertEquals(2, service.purchaseCalls)
        client.close()
    }

    @Test
    fun discountAndAttributesUseMobileContract() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponseWithCustomer(
                customerJson =
                    """{"originalAppUserId":"InaAnonymousId-1","attributes":[{"key":"language","value":"en"},{"key":"theme","value":"old"}]}""",
            )
            validateDiscountCodeResult = successfulMobileOperationResponse(
                discountCodeResultJson =
                    """{"is_valid":true,"code":"WELCOME20","percent":20}""",
            )
            storeAttributesResult = successfulMobileOperationResponse()
            removeAttributesResult = successfulMobileOperationResponse()
        }
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))

        val discount = client.validateDiscountCode(
            InappifyDiscountCodeRequest(
                discountCode = "WELCOME20",
                offeringIdentifier = "not-sent-by-mobile",
            ),
        ) as InappifyResult.Success<InappifyDiscountCodeResult>
        val attributes = client.setAttributes(
            InappifyAttributesRequest(
                listOf(
                    InappifyAttribute(key = "language", value = "fa"),
                    InappifyAttribute(key = "theme", value = "   "),
                    InappifyAttribute(key = "\$reserved", value = "ignored"),
                ),
            ),
        ) as InappifyResult.Success<List<InappifyAttribute>>

        assertTrue(discount.data.isValid == true)
        assertEquals(20L, discount.data.percent)
        assertEquals("WELCOME20", service.lastValidateDiscountCode?.discountCode)
        assertEquals(1, service.storeAttributesCalls)
        assertEquals(1, service.removeAttributesCalls)
        assertEquals("language", service.lastStoreAttributes?.attributes?.single()?.key)
        assertEquals("theme", service.lastRemoveAttributes?.attributes?.single()?.key)
        assertEquals("fa", attributes.data.first { it.key == "language" }.value)
        assertEquals("", attributes.data.first { it.key == "theme" }.value)
        assertEquals(attributes.data, attributes.snapshot.customerInfo?.attributes)
        client.close()
    }

    @Test
    fun mobileParity_discountAcceptsFalseOrMissingStatusWithoutApplyingForceVersion() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponseWithCustomer(
                customerJson = """{"originalAppUserId":"InaAnonymousId-1"}""",
            )
            validateDiscountCodeResult = successfulMobileOperationResponse(
                status = false,
                forceVersion = 99,
                discountCodeResultJson =
                    """{"is_valid":true,"code":"FALSE-STATUS","percent":10}""",
            )
        }
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        val beforeDiscount = client.snapshot

        val falseStatus = client.validateDiscountCode(
            InappifyDiscountCodeRequest("FALSE-STATUS"),
        ) as InappifyResult.Success<InappifyDiscountCodeResult>

        assertEquals("FALSE-STATUS", falseStatus.data.code)
        assertEquals(beforeDiscount.forceVersion, falseStatus.snapshot.forceVersion)
        assertEquals(beforeDiscount.revision, falseStatus.snapshot.revision)
        assertEquals(beforeDiscount.forceVersion, client.snapshot.forceVersion)

        service.validateDiscountCodeResult = successfulMobileOperationResponse(
            status = null,
            forceVersion = 100,
            discountCodeResultJson =
                """{"is_valid":true,"code":"MISSING-STATUS","percent":15}""",
        )
        val missingStatus = client.validateDiscountCode(
            InappifyDiscountCodeRequest("MISSING-STATUS"),
        ) as InappifyResult.Success<InappifyDiscountCodeResult>

        assertEquals("MISSING-STATUS", missingStatus.data.code)
        assertEquals(beforeDiscount.forceVersion, missingStatus.snapshot.forceVersion)
        assertEquals(beforeDiscount.revision, missingStatus.snapshot.revision)
        assertEquals(beforeDiscount.forceVersion, client.snapshot.forceVersion)
        client.close()
    }

    @Test
    fun mobileParity_reservedAndFullAttributeSyncUpdateAuthoritativeSnapshot() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponseWithCustomer(
                customerJson = """{"originalAppUserId":"InaAnonymousId-1","attributes":[]}""",
            )
            storeReservedAttributeResult = successfulMobileOperationResponse()
            removeAttributesResult = successfulMobileOperationResponse()
            syncAttributesResult = successfulMobileOperationResponse(
                attributesJson = """[{"key":"server","value":"authoritative"}]""",
            )
        }
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))

        val invalidEmail = client.setEmail("not-an-email") as InappifyResult.Failure
        assertEquals(InappifyErrorCode.INVALID_CONFIGURATION, invalidEmail.error.code)
        assertEquals(0, service.storeReservedAttributeCalls)

        assertTrue(client.setEmail("user@example.com") is InappifyResult.Success)
        assertEquals("\$email", service.lastStoreReservedAttribute?.key)
        assertTrue(client.setEmail("") is InappifyResult.Success)
        assertEquals("\$email", service.lastRemoveAttributes?.attributes?.single()?.key)

        val synced = client.syncAttributes(
            InappifyAttributesRequest(
                listOf(InappifyAttribute(key = "local", value = "candidate")),
                idempotencyKey = "sync-1",
            ),
        ) as InappifyResult.Success<List<InappifyAttribute>>

        assertEquals("local", service.lastSyncAttributes?.attributes?.single()?.key)
        assertEquals("server", synced.data.single().key)
        assertEquals(synced.data, synced.snapshot.customerInfo?.attributes)
        assertTrue(client.canSetReservedAttribute("email") is InappifyResult.Success)
        val blankCapability = client.canSetReservedAttribute("   ")
            as InappifyResult.Success<Boolean>
        assertFalse(blankCapability.data)
        client.close()
    }

    @Test
    fun setAttributes_keepsOptimisticSnapshotAndIgnoresRemoveFailure() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponseWithCustomer(
                customerJson =
                    """{"originalAppUserId":"InaAnonymousId-1","attributes":[{"key":"language","value":"en"},{"key":"theme","value":"dark"}]}""",
            )
            storeAttributesResult = ServiceResult.Failure(ServiceFailureKind.NETWORK)
            removeAttributesResult = ServiceResult.Failure(ServiceFailureKind.TIMEOUT)
        }
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))

        val rejectedStore = client.setAttributes(
            InappifyAttributesRequest(
                listOf(
                    InappifyAttribute(key = "language", value = "fa"),
                    InappifyAttribute(key = "theme", value = ""),
                ),
            ),
        ) as InappifyResult.Failure

        assertEquals(InappifyErrorCode.NETWORK, rejectedStore.error.code)
        assertEquals(
            "fa",
            rejectedStore.snapshot?.customerInfo?.attributes
                ?.first { it.key == "language" }
                ?.value,
        )
        assertEquals(
            "",
            rejectedStore.snapshot?.customerInfo?.attributes
                ?.first { it.key == "theme" }
                ?.value,
        )
        assertEquals(0, service.removeAttributesCalls)

        val removeOnly = client.deleteAttributes(
            InappifyDeleteAttributesRequest(listOf("language")),
        ) as InappifyResult.Success<List<InappifyAttribute>>

        assertEquals("", removeOnly.data.first { it.key == "language" }.value)
        assertEquals(1, service.removeAttributesCalls)
        client.close()
    }

    @Test
    fun reservedAttributes_updateOnlyEmailInSnapshot() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponseWithCustomer(
                customerJson =
                    """{"originalAppUserId":"InaAnonymousId-1","attributes":[{"key":"${'$'}email","value":"old@example.com"}]}""",
            )
            storeReservedAttributeResult = ServiceResult.Failure(
                ServiceFailureKind.NETWORK,
            )
            removeAttributesResult = successfulMobileOperationResponse()
        }
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))

        val failedEmail = client.setEmail("new@example.com") as InappifyResult.Failure
        val afterFailedEmail = requireNotNull(
            failedEmail.snapshot?.customerInfo?.attributes,
        )
        assertEquals(2, afterFailedEmail.count { it.key == "\$email" })
        assertEquals("new@example.com", afterFailedEmail.last().value)

        service.storeReservedAttributeResult = successfulMobileOperationResponse()
        assertTrue(client.setDisplayName("Example User") is InappifyResult.Success)
        assertFalse(
            client.snapshot.customerInfo?.attributes.orEmpty()
                .any { it.key == "\$displayName" },
        )

        assertTrue(client.setEmail("") is InappifyResult.Success)
        val emails = client.snapshot.customerInfo?.attributes.orEmpty()
            .filter { it.key == "\$email" }
        assertEquals("", emails.first().value)
        assertEquals("new@example.com", emails.last().value)
        client.close()
    }

    @Test
    fun syncAttributes_appliesDataBeforeStatusAndRefreshesOfferingsOnForceChange() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponseWithCustomer(
                customerJson =
                    """{"originalAppUserId":"InaAnonymousId-1","attributes":[{"key":"local","value":"old"}]}""",
            )
            offeringsResult = successfulOfferingsResponse("initial", forceVersion = 1)
        }
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        service.offeringsResult = successfulOfferingsResponse("refreshed", forceVersion = 4)
        service.syncAttributesResult = ServiceResult.Response(
            statusCode = 200,
            payload = BackendResponse(
                status = false,
                message = "Sync rejected.",
                errorCode = null,
                token = null,
                appUserIdentifier = null,
                customerInfoJson = null,
                storeInfo = null,
                appId = null,
                forceVersion = 4,
                attributesJson = """[{"key":"server","value":"authoritative"}]""",
            ),
            requestId = "sync-status-false",
        )

        val result = client.syncAttributes(
            InappifyAttributesRequest(
                listOf(InappifyAttribute(key = "local", value = "candidate")),
            ),
        ) as InappifyResult.Failure

        assertEquals(
            "server",
            result.snapshot?.customerInfo?.attributes?.single()?.key,
        )
        repeat(100) {
            if (service.offeringsCalls >= 2) return@repeat
            delay(10)
        }
        assertTrue(service.offeringsCalls >= 2)
        assertEquals("refreshed", client.snapshot.offerings?.offerings?.single()?.identifier)
        assertEquals(4L, client.snapshot.forceVersion)
        client.close()
    }

    @Test
    fun syncAttributes_failureAndSuccessfulNullDataClearSnapshot() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponseWithCustomer(
                customerJson =
                    """{"originalAppUserId":"InaAnonymousId-1","attributes":[{"key":"theme","value":"dark"}]}""",
            )
            syncAttributesResult = ServiceResult.Failure(ServiceFailureKind.NETWORK)
        }
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))

        assertTrue(client.syncAttributes() is InappifyResult.Failure)
        assertNull(client.snapshot.customerInfo?.attributes)

        service.syncAttributesResult = ServiceResult.Response(
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
                attributesJson = null,
            ),
            requestId = "sync-null-data",
        )
        val success = client.syncAttributes() as InappifyResult.Success<*>

        assertEquals(emptyList<InappifyAttribute>(), success.data)
        assertNull(success.snapshot.customerInfo?.attributes)
        client.close()
    }

    @Test
    fun currentOfferingTargetingAndEntitlementUseMobileContract() = runBlocking {
        val service = FakeService().apply {
            configureResult = successfulResponseWithCustomer(
                customerJson =
                    """{"originalAppUserId":"InaAnonymousId-1","entitlements":[{"identifier":"premium","is_active":true,"expiration_date":"invalid-but-compatible"}]}""",
            )
            offeringsResult = ServiceResult.Response(
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
                    offeringsJson =
                        """
                        {
                          "offerings": [
                            {"identifier":"default","isDefault":true},
                            {"identifier":"vip"}
                          ],
                          "rules": [{
                            "default_offering":"vip",
                            "sort":1,
                            "conditions":[{
                              "field":"custom_attribute",
                              "operator":"=",
                              "context":"segment",
                              "value":"vip"
                            }]
                          }]
                        }
                        """.trimIndent(),
                ),
                requestId = "offerings-request",
            )
        }
        val client = createClient(service = service)
        client.configure(InappifyOptions(apiKey = "mobile-api-key"))

        val currentOffering = client.getCurrentOffering(
            context = InappifyOfferingEvaluationContext(
                country = "IR",
                platform = "android",
                appVersion = "1.2.3",
                customAttributes = mapOf("segment" to "vip"),
            ),
        ) as InappifyResult.Success<InappifyOffering?>

        assertEquals("vip", currentOffering.data?.identifier)
        assertTrue(client.isActiveEntitlement("premium"))
        assertTrue(client.hasEntitlement("premium"))
        assertNotNull(client.getEntitlement("premium"))
        val checked = client.checkEntitlement("premium", forceRefresh = false)
            as InappifyResult.Success<Boolean>
        assertTrue(checked.data)

        val targeted = client.setTargetingContext(country = "de", appVersion = "2.0.0")
            as InappifyResult.Success<Unit>
        assertEquals("DE", targeted.snapshot.country)
        assertEquals("2.0.0", targeted.snapshot.appVersion)
        assertNull(targeted.snapshot.offerings)
        client.close()
    }

    @Test
    fun listenerFailuresAreIsolatedAndEventsUseCommittedRevision() = runBlocking {
        val service = FakeService()
        val client = createClient(service = service)
        val delivered = CountDownLatch(1)
        var deliveredRevision = -1L
        client.addEventListener { throw IllegalStateException("host failure") }
        val registration = client.addEventListener { event ->
            if (event.type == InappifyEventType.STATE_CHANGED) {
                deliveredRevision = event.snapshot.revision
                delivered.countDown()
            }
        }

        val result = client.configure(
            InappifyOptions(apiKey = "mobile-api-key"),
        ) as InappifyResult.Success<Unit>

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(result.snapshot.revision, deliveredRevision)
        registration.close()
        client.close()
    }

    @Test
    fun lateResponseAfterCloseCannotCommitState() = runBlocking {
        val service = LateResponseService()
        val client = createClient(service = service)
        val operation = async {
            client.configure(InappifyOptions(apiKey = "mobile-api-key"))
        }
        service.started.await()

        client.close()
        service.release.complete(Unit)

        val result = operation.await() as InappifyResult.Failure
        assertEquals(InappifyErrorCode.REQUEST_CANCELLED, result.error.code)
        assertFalse(client.snapshot.isConfigured)
    }

    private fun createClient(
        service: InappifyService = FakeService(),
        store: FakeSessionStore = FakeSessionStore(),
        currentTimeMillis: () -> Long = System::currentTimeMillis,
        purchaseRecoveryIdProvider: () -> String = { TEST_RECOVERY_BINDING },
        storeBillingAdapterFactory: StoreBillingAdapterFactory =
            StoreBillingAdapterFactory { _, _ ->
                FakeStoreBillingAdapter(StoreBillingResult.Cancelled)
            },
    ): DefaultInappifyClient = DefaultInappifyClient(
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
        currentTimeMillis = currentTimeMillis,
        purchaseRecoveryIdProvider = purchaseRecoveryIdProvider,
        storeBillingAdapterFactory = storeBillingAdapterFactory,
    )

    private suspend fun configureBazaar(client: DefaultInappifyClient) {
        val result = client.configure(
            InappifyOptions(
                apiKey = "mobile-api-key",
                market = InappifyMarket.BAZAAR,
                marketKey = "market-key",
            ),
        )
        assertTrue(result is InappifyResult.Success)
    }

    private fun purchaseOfferingsResponse(
        trialDays: Long,
    ): ServiceResult.Response = ServiceResult.Response(
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
            offeringsJson =
                """
                {
                  "offerings": [{
                    "identifier": "main",
                    "packages": [{
                      "identifier": "monthly",
                      "product": {
                        "identifier": "premium-product",
                        "trialDays": $trialDays
                      }
                    }]
                  }],
                  "rules": [],
                  "forceVersion": 1
                }
                """.trimIndent(),
        ),
        requestId = "offerings-request",
    )

    private fun successfulPurchaseResponse(
        purchaseStatus: String? = "DONE",
        forceVersion: Long = 1L,
        responseStatus: Boolean? = true,
    ): ServiceResult.Response =
        ServiceResult.Response(
            statusCode = 200,
            payload = BackendResponse(
                status = responseStatus,
                message = null,
                errorCode = null,
                token = null,
                appUserIdentifier = null,
                customerInfoJson = null,
                storeInfo = null,
                appId = null,
                forceVersion = forceVersion,
                purchase = BackendPurchase(
                    url = null,
                    purchaseStatus = purchaseStatus,
                    checkoutId = null,
                    checkoutStatus = null,
                    nextActionType = null,
                ),
            ),
            requestId = "purchase-request",
        )

    private fun successfulResponseWithCustomer(
        customerJson: String,
    ): ServiceResult.Response = ServiceResult.Response(
        statusCode = 200,
        payload = BackendResponse(
            status = true,
            message = null,
            errorCode = null,
            token = "anonymous-token",
            appUserIdentifier = "InaAnonymousId-1",
            customerInfoJson = customerJson,
            storeInfo = null,
            appId = 12,
            forceVersion = 1,
        ),
        requestId = "configure-request",
    )

    private fun successfulMobileOperationResponse(
        status: Boolean? = true,
        forceVersion: Long? = 1L,
        discountCodeResultJson: String? = null,
        attributesJson: String? = null,
    ): ServiceResult.Response = ServiceResult.Response(
        statusCode = 200,
        payload = BackendResponse(
            status = status,
            message = null,
            errorCode = null,
            token = null,
            appUserIdentifier = null,
            customerInfoJson = null,
            storeInfo = null,
            appId = null,
            forceVersion = forceVersion,
            discountCodeResultJson = discountCodeResultJson,
            attributesJson = attributesJson,
        ),
        requestId = "mobile-operation-request",
    )

    private fun storePurchase(
        developerPayload: String = "payload",
        purchaseToken: String = "store-purchase-token",
    ): StorePurchase = StorePurchase(
        orderIdentifier = "store-order",
        purchaseToken = purchaseToken,
        developerPayload = developerPayload,
        packageName = "com.example.host",
        productIdentifier = "premium-product",
        purchaseTimeMillis = 1_725_000_000_000L,
        originalJson = "sensitive-json",
        signature = "sensitive-signature",
    )

    private companion object {
        private const val TEST_RECOVERY_BINDING =
            "7df28083c2ecb4db1d628fa7a8a6d539b142591af143207ee4f700ab573d0ad7"
    }

    private fun successfulResponse(
        token: String,
        identifier: String?,
        storeInfo: String? = null,
        forceVersion: Long? = 1L,
        appId: Long? = null,
    ): ServiceResult.Response = ServiceResult.Response(
        statusCode = 200,
        payload = backendResponse(
            status = true,
            token = token,
            identifier = identifier,
            storeInfo = storeInfo,
            forceVersion = forceVersion,
            appId = appId,
        ),
        requestId = "request-1",
    )

    private fun successfulResponseWithoutToken(
        identifier: String?,
    ): ServiceResult.Response = ServiceResult.Response(
        statusCode = 200,
        payload = backendResponse(
            status = true,
            identifier = identifier,
        ),
        requestId = "refresh-request",
    )

    private fun successfulOfferingsResponse(
        identifier: String,
        forceVersion: Long = 1L,
    ): ServiceResult.Response = ServiceResult.Response(
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
            forceVersion = forceVersion,
            offeringsJson =
                """{"offerings":[{"identifier":"$identifier"}],"rules":[]}""",
        ),
        requestId = "offerings-request",
    )

    private fun backendResponse(
        status: Boolean?,
        token: String? = null,
        identifier: String? = null,
        storeInfo: String? = null,
        forceVersion: Long? = null,
        appId: Long? = null,
    ): BackendResponse = BackendResponse(
        status = status,
        message = null,
        errorCode = null,
        token = token,
        appUserIdentifier = identifier,
        customerInfoJson = identifier?.let {
            "{\"originalAppUserId\":\"$it\"}"
        },
        storeInfo = storeInfo,
        appId = appId,
        forceVersion = forceVersion,
    )

    private fun persistedSession(
        token: String,
        identifier: String,
        apiKeyFingerprint: String?,
    ): PersistedSession = PersistedSession(
        token = token,
        appUserIdentifier = identifier,
        forceVersion = 3,
        appId = 8,
        storeInfo = "bazar",
        apiKeyFingerprint = apiKeyFingerprint,
        customerInfoJson = "{}",
        offeringsJson = "{}",
        customerInfoUpdatedAt = null,
    )

    private inner class FakeService : InappifyService {
        var configureResult: ServiceResult = successfulResponse(
            token = "anonymous-token",
            identifier = "InaAnonymousId-1",
        )
        var loginResult: ServiceResult = successfulResponse(
            token = "customer-token",
            identifier = "09120000000",
        )
        var logoutResult: ServiceResult = successfulResponse(
            token = "anonymous-token-2",
            identifier = "InaAnonymousId-2",
        )
        var refreshResult: ServiceResult = successfulResponseWithoutToken(
            identifier = "InaAnonymousId-1",
        )
        var customerInfoResult: ServiceResult = successfulResponseWithoutToken(
            identifier = "InaAnonymousId-1",
        )
        var offeringsResult: ServiceResult = ServiceResult.Failure(
            ServiceFailureKind.NETWORK,
        )
        var purchaseResult: ServiceResult = ServiceResult.Failure(
            ServiceFailureKind.NETWORK,
        )
        var validateDiscountCodeResult: ServiceResult = ServiceResult.Failure(
            ServiceFailureKind.NETWORK,
        )
        var storeAttributesResult: ServiceResult = ServiceResult.Failure(
            ServiceFailureKind.NETWORK,
        )
        var removeAttributesResult: ServiceResult = ServiceResult.Failure(
            ServiceFailureKind.NETWORK,
        )
        var storeReservedAttributeResult: ServiceResult = ServiceResult.Failure(
            ServiceFailureKind.NETWORK,
        )
        var syncAttributesResult: ServiceResult = ServiceResult.Failure(
            ServiceFailureKind.NETWORK,
        )
        var blockOfferings = false
        val offeringsStarted = CompletableDeferred<Unit>()
        val releaseOfferings = CompletableDeferred<Unit>()
        val queuedPurchaseResults = mutableListOf<ServiceResult>()
        var configureCalls = 0
        var loginCalls = 0
        var refreshCalls = 0
        var customerInfoCalls = 0
        var offeringsCalls = 0
        var purchaseCalls = 0
        var storeAttributesCalls = 0
        var removeAttributesCalls = 0
        var storeReservedAttributeCalls = 0
        var closeCalls = 0
        var lastConfigure: ConfigureApiRequest? = null
        var lastLogin: LoginApiRequest? = null
        var lastRefresh: RefreshSessionApiRequest? = null
        var lastPurchase: PurchaseApiRequest? = null
        var lastValidateDiscountCode: ValidateDiscountCodeApiRequest? = null
        var lastStoreAttributes: StoreAttributesApiRequest? = null
        var lastRemoveAttributes: RemoveAttributesApiRequest? = null
        var lastStoreReservedAttribute: StoreReservedAttributeApiRequest? = null
        var lastSyncAttributes: SyncAttributesApiRequest? = null

        override suspend fun configure(request: ConfigureApiRequest): ServiceResult {
            configureCalls += 1
            lastConfigure = request
            return configureResult
        }

        override suspend fun login(request: LoginApiRequest): ServiceResult {
            loginCalls += 1
            lastLogin = request
            return loginResult
        }

        override suspend fun logout(request: LogoutApiRequest): ServiceResult =
            logoutResult

        override suspend fun refreshSession(
            request: RefreshSessionApiRequest,
        ): ServiceResult {
            refreshCalls += 1
            lastRefresh = request
            return refreshResult
        }

        override suspend fun getCustomerInfo(
            request: ResourceApiRequest,
        ): ServiceResult {
            customerInfoCalls += 1
            return customerInfoResult
        }

        override suspend fun getOfferings(
            request: ResourceApiRequest,
        ): ServiceResult {
            offeringsCalls += 1
            if (blockOfferings) {
                offeringsStarted.complete(Unit)
                releaseOfferings.await()
            }
            return offeringsResult
        }

        override suspend fun purchase(request: PurchaseApiRequest): ServiceResult {
            purchaseCalls += 1
            lastPurchase = request
            return if (queuedPurchaseResults.isEmpty()) {
                purchaseResult
            } else {
                queuedPurchaseResults.removeAt(0)
            }
        }

        override suspend fun validateDiscountCode(
            request: ValidateDiscountCodeApiRequest,
        ): ServiceResult {
            lastValidateDiscountCode = request
            return validateDiscountCodeResult
        }

        override suspend fun storeAttributes(
            request: StoreAttributesApiRequest,
        ): ServiceResult {
            storeAttributesCalls += 1
            lastStoreAttributes = request
            return storeAttributesResult
        }

        override suspend fun removeAttributes(
            request: RemoveAttributesApiRequest,
        ): ServiceResult {
            removeAttributesCalls += 1
            lastRemoveAttributes = request
            return removeAttributesResult
        }

        override suspend fun storeReservedAttribute(
            request: StoreReservedAttributeApiRequest,
        ): ServiceResult {
            storeReservedAttributeCalls += 1
            lastStoreReservedAttribute = request
            return storeReservedAttributeResult
        }

        override suspend fun syncAttributes(
            request: SyncAttributesApiRequest,
        ): ServiceResult {
            lastSyncAttributes = request
            return syncAttributesResult
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private class FakeStoreBillingAdapter(
        private val result: StoreBillingResult,
        private val queryResult: StorePurchaseQueryResult =
            StorePurchaseQueryResult.Success(emptyList()),
    ) : StoreBillingAdapter {
        var purchaseCalls = 0
        var queryCalls = 0
        var closeCalls = 0
        var lastRequest: StorePurchaseRequest? = null

        override suspend fun purchase(
            uiHost: StoreUiHost,
            request: StorePurchaseRequest,
        ): StoreBillingResult {
            purchaseCalls += 1
            lastRequest = request
            return result
        }

        override suspend fun queryPurchases(
            productType: StoreProductType,
        ): StorePurchaseQueryResult {
            queryCalls += 1
            return queryResult
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private inner class BlockingStoreBillingAdapter : StoreBillingAdapter {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var purchaseCalls = 0
        var closeCalls = 0

        override suspend fun purchase(
            uiHost: StoreUiHost,
            request: StorePurchaseRequest,
        ): StoreBillingResult {
            purchaseCalls += 1
            started.complete(Unit)
            release.await()
            return StoreBillingResult.Success(storePurchase())
        }

        override suspend fun queryPurchases(
            productType: StoreProductType,
        ): StorePurchaseQueryResult = StorePurchaseQueryResult.Success(emptyList())

        override fun close() {
            closeCalls += 1
        }
    }

    private inner class BlockingService : InappifyService {
        val configureStarted = CompletableDeferred<Unit>()
        val releaseConfigure = CompletableDeferred<Unit>()
        var loginCalls = 0

        override suspend fun configure(request: ConfigureApiRequest): ServiceResult {
            configureStarted.complete(Unit)
            releaseConfigure.await()
            return successfulResponse(
                token = "anonymous-token",
                identifier = "InaAnonymousId-1",
            )
        }

        override suspend fun login(request: LoginApiRequest): ServiceResult {
            loginCalls += 1
            return successfulResponse(
                token = "customer-token",
                identifier = request.appUserIdentifier,
            )
        }

        override suspend fun logout(request: LogoutApiRequest): ServiceResult =
            successfulResponse(
                token = "anonymous-token-2",
                identifier = "InaAnonymousId-2",
            )

        override suspend fun refreshSession(
            request: RefreshSessionApiRequest,
        ): ServiceResult = successfulResponseWithoutToken(
            identifier = "InaAnonymousId-1",
        )

        override suspend fun getCustomerInfo(
            request: ResourceApiRequest,
        ): ServiceResult = successfulResponseWithoutToken(
            identifier = "InaAnonymousId-1",
        )

        override suspend fun getOfferings(
            request: ResourceApiRequest,
        ): ServiceResult = ServiceResult.Failure(ServiceFailureKind.NETWORK)

        override fun close() = Unit
    }

    private inner class LateResponseService : InappifyService {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun configure(request: ConfigureApiRequest): ServiceResult {
            started.complete(Unit)
            release.await()
            return successfulResponse(
                token = "late-token",
                identifier = "InaAnonymousId-late",
            )
        }

        override suspend fun login(request: LoginApiRequest): ServiceResult =
            error("Not used")

        override suspend fun logout(request: LogoutApiRequest): ServiceResult =
            error("Not used")

        override suspend fun refreshSession(
            request: RefreshSessionApiRequest,
        ): ServiceResult = error("Not used")

        override suspend fun getCustomerInfo(
            request: ResourceApiRequest,
        ): ServiceResult = error("Not used")

        override suspend fun getOfferings(
            request: ResourceApiRequest,
        ): ServiceResult = error("Not used")

        override fun close() = Unit
    }

    private inner class FakeSessionStore(
        private val loaded: PersistedSession? = null,
        private val saveSucceeds: Boolean = true,
    ) : SessionStateStore {
        var lastSaved: PersistedSession? = null
        var saveCalls = 0
        var clearCalls = 0

        override suspend fun load(): PersistedSession? = loaded

        override suspend fun save(session: PersistedSession): Boolean {
            lastSaved = session
            saveCalls += 1
            return saveSucceeds
        }

        override suspend fun clear(): Boolean {
            lastSaved = null
            clearCalls += 1
            return true
        }
    }
}
