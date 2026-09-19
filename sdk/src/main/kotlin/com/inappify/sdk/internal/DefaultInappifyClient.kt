package com.inappify.sdk.internal

import android.app.Activity
import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.inappify.sdk.BuildConfig
import com.inappify.sdk.CachedSessionCapableClient
import com.inappify.sdk.ConsumableFulfillmentCapableClient
import com.inappify.sdk.InappifyAttribute
import com.inappify.sdk.InappifyAttributesRequest
import com.inappify.sdk.InappifyClient
import com.inappify.sdk.InappifyConsumableDelivery
import com.inappify.sdk.InappifyConsumableDeliveryHandler
import com.inappify.sdk.InappifyConsumableSyncResult
import com.inappify.sdk.InappifyCustomerInfo
import com.inappify.sdk.InappifyDeleteAttributesRequest
import com.inappify.sdk.InappifyDiscountCodeRequest
import com.inappify.sdk.InappifyDiscountCodeResult
import com.inappify.sdk.InappifyDeliveryResult
import com.inappify.sdk.InappifyDeliverySource
import com.inappify.sdk.InappifyError
import com.inappify.sdk.InappifyErrorCode
import com.inappify.sdk.InappifyEvent
import com.inappify.sdk.InappifyEventListener
import com.inappify.sdk.InappifyEventType
import com.inappify.sdk.HttpDiagnosticsCapableClient
import com.inappify.sdk.InappifyHttpTraceListener
import com.inappify.sdk.InappifyListenerRegistration
import com.inappify.sdk.InappifyLoginRequest
import com.inappify.sdk.InappifyMarket
import com.inappify.sdk.InappifyOptions
import com.inappify.sdk.InappifyOffering
import com.inappify.sdk.InappifyOfferingEvaluationContext
import com.inappify.sdk.InappifyOfferings
import com.inappify.sdk.InappifyPackage
import com.inappify.sdk.InappifyPurchase
import com.inappify.sdk.InappifyPurchaseRequest
import com.inappify.sdk.InappifyPurchaseStatus
import com.inappify.sdk.InappifyProductType
import com.inappify.sdk.InappifyReservedAttribute
import com.inappify.sdk.InappifyReservedAttributeRequest
import com.inappify.sdk.InappifyRestoreResult
import com.inappify.sdk.InappifyResult
import com.inappify.sdk.InappifySnapshot
import com.inappify.sdk.InappifyStorePurchaseStatus
import com.inappify.sdk.StoreV2CapableClient
import com.inappify.sdk.backendKey
import com.inappify.sdk.findActiveEntitlement
import com.inappify.sdk.hasValidValue
import com.inappify.sdk.isValidCustomAttribute
import com.inappify.sdk.removesCustomAttribute
import com.inappify.sdk.internal.billing.AndroidStoreBillingAdapterFactory
import com.inappify.sdk.internal.billing.PartialStorePurchaseQueryAdapter
import com.inappify.sdk.internal.billing.StoreBillingAdapter
import com.inappify.sdk.internal.billing.StoreBillingAdapterFactory
import com.inappify.sdk.internal.billing.StoreBillingError
import com.inappify.sdk.internal.billing.StoreBillingErrorCode
import com.inappify.sdk.internal.billing.StoreBillingResult
import com.inappify.sdk.internal.billing.StoreProductType
import com.inappify.sdk.internal.billing.StorePurchase
import com.inappify.sdk.internal.billing.StorePurchaseQueryMode
import com.inappify.sdk.internal.billing.StorePurchaseQueryResult
import com.inappify.sdk.internal.billing.StorePurchaseRequest
import com.inappify.sdk.internal.billing.StoreUiHost
import com.inappify.sdk.internal.billing.UnsupportedStoreBillingAdapter
import com.inappify.sdk.internal.domain.InappifyDomainJsonCodec
import com.inappify.sdk.internal.network.BackendResponse
import com.inappify.sdk.internal.network.BackendConsumableDelivery
import com.inappify.sdk.internal.network.ConsumableDeliveriesServiceResult
import com.inappify.sdk.internal.network.ConsumableDeliverySource
import com.inappify.sdk.internal.network.ConfigureApiRequest
import com.inappify.sdk.internal.network.DefaultInappifyService
import com.inappify.sdk.internal.network.DirectConsumableDeliveryApiRequest
import com.inappify.sdk.internal.network.InappifyService
import com.inappify.sdk.internal.network.HttpDiagnosticSource
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
import com.inappify.sdk.internal.network.StorePurchaseStatus
import com.inappify.sdk.internal.network.StoreReservedAttributeApiRequest
import com.inappify.sdk.internal.network.SyncAttributesApiRequest
import com.inappify.sdk.internal.network.ValidateDiscountCodeApiRequest
import com.inappify.sdk.internal.platform.AndroidAppMetadataProvider
import com.inappify.sdk.internal.platform.AppMetadata
import com.inappify.sdk.internal.platform.AppMetadataProvider
import com.inappify.sdk.internal.state.InternalSessionState
import com.inappify.sdk.internal.storage.EncryptedSessionStateStore
import com.inappify.sdk.internal.storage.PendingStoreOperation
import com.inappify.sdk.internal.storage.PendingStoreOperationPhase
import com.inappify.sdk.internal.storage.PendingStoreOperationType
import com.inappify.sdk.internal.storage.PendingStoreProductType
import com.inappify.sdk.internal.storage.PendingStorePurchaseEvidence
import com.inappify.sdk.internal.storage.PendingStoreRecoveryState
import com.inappify.sdk.internal.storage.PersistedSession
import com.inappify.sdk.internal.storage.RejectedStoreEvidenceTombstone
import com.inappify.sdk.internal.storage.SessionSaveResult
import com.inappify.sdk.internal.storage.SessionStateStore
import com.inappify.sdk.internal.storage.SessionStorageFailure
import com.inappify.sdk.internal.storage.SessionStorageStage
import com.inappify.sdk.internal.storage.isValidCachedJson
import com.inappify.sdk.internal.storage.isValidCachedExpiration
import com.inappify.sdk.internal.storage.blockCacheRestore
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Default production client. Mutations are serialized per instance and publish
 * only complete state.
 */
internal class DefaultInappifyClient(
    private val service: InappifyService,
    private val sessionStore: SessionStateStore,
    private val metadataProvider: AppMetadataProvider,
    sdkVersion: String,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val purchaseRecoveryIdProvider: () -> String = {
        UUID.randomUUID().toString()
    },
    private val consumableRetrySleep: suspend (Long) -> Unit = { delay(it) },
    private val storeBillingAdapterFactory: StoreBillingAdapterFactory =
        StoreBillingAdapterFactory { _, _ ->
            UnsupportedStoreBillingAdapter(
                StoreBillingError(
                    code = StoreBillingErrorCode.UNSUPPORTED_MARKET,
                    message = "Native store billing is unavailable.",
                ),
            )
        },
    private val legacyPurchaseRouting: Boolean = true,
) : InappifyClient,
    CachedSessionCapableClient,
    StoreV2CapableClient,
    ConsumableFulfillmentCapableClient,
    HttpDiagnosticsCapableClient {

    private val operationMutex = Mutex()
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean(false)
    private val purchaseInProgress = AtomicBoolean(false)
    private val activeStoreAdapter = AtomicReference<StoreBillingAdapter?>(null)
    private val consumableDeliveryHandler =
        AtomicReference<InappifyConsumableDeliveryHandler?>(null)
    private val generation = AtomicLong(0)
    private val listeners = CopyOnWriteArraySet<InappifyEventListener>()
    private val eventExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, EVENT_THREAD_NAME).apply { isDaemon = true }
        }
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = AtomicReference(
        InternalSessionState.initial(sdkVersion),
    )
    private val gson = Gson()
    // Protected by operationMutex. Never retain raw credentials in rejection metadata.
    private val rejectedCachedSessions = HashSet<String>()
    private var offlineRestoredSession: String? = null
    private val storeV2Coordinator = StoreV2Coordinator(
        service = service,
        stateStore = sessionStore,
        billingAdapterFactory = storeBillingAdapterFactory,
        currentTimeMillis = currentTimeMillis,
        registerActiveAdapter = { adapter ->
            activeStoreAdapter.compareAndSet(null, adapter)
        },
        unregisterActiveAdapter = { adapter ->
            activeStoreAdapter.compareAndSet(adapter, null)
            Unit
        },
    )

    override val snapshot: InappifySnapshot
        get() = state.get().toSnapshot()

    /**
     * Captures a purchase-only credential scope for the Go v2 owner. Subsequent login/logout
     * on the host's V1 client cannot change this private companion. Recovery evidence remains
     * durable, while companion session writes cannot overwrite the host's V1 session.
     */
    internal fun createPurchaseCompanion(): DefaultInappifyClient {
        ensureOpen()
        val captured = state.get()
        val purchaseService = object : InappifyService by service {
            override fun close() = Unit
        }
        val purchaseStorage = object : SessionStateStore by sessionStore {
            override suspend fun save(session: PersistedSession): Boolean = true
            override suspend fun saveWithDiagnostics(session: PersistedSession): SessionSaveResult = SessionSaveResult.Success
            override suspend fun clear(): Boolean = true
        }
        return DefaultInappifyClient(purchaseService, purchaseStorage, metadataProvider, captured.sdkVersion,
            currentTimeMillis, purchaseRecoveryIdProvider, consumableRetrySleep, storeBillingAdapterFactory,
            legacyPurchaseRouting = false).also {
            it.state.set(captured)
        }
    }

    override fun addEventListener(
        listener: InappifyEventListener,
    ): InappifyListenerRegistration {
        synchronized(lifecycleLock) {
            ensureOpen()
            listeners.add(listener)
        }
        return InappifyListenerRegistration.create(
            Runnable { listeners.remove(listener) },
        )
    }

    override fun addHttpTraceListenerInternal(
        listener: InappifyHttpTraceListener,
    ): InappifyListenerRegistration = synchronized(lifecycleLock) {
        ensureOpen()
        if (service is HttpDiagnosticSource) {
            service.addHttpTraceListener(listener)
        } else {
            InappifyListenerRegistration.create(Runnable {})
        }
    }

    override fun setConsumableDeliveryHandlerInternal(
        handler: InappifyConsumableDeliveryHandler,
    ): InappifyListenerRegistration = synchronized(lifecycleLock) {
        ensureOpen()
        consumableDeliveryHandler.set(handler)
        InappifyListenerRegistration.create(
            Runnable { consumableDeliveryHandler.compareAndSet(handler, null) },
        )
    }

    override suspend fun syncPendingConsumablesInternal():
        InappifyResult<InappifyConsumableSyncResult> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            ensureOpen()
            syncPendingConsumablesLocked(
                operationGeneration = generation.get(),
                invokeHandler = true,
            ).toPublicResult()
        }
    }

    override suspend fun restoreCachedSessionInternal(
        options: InappifyOptions,
    ): InappifyResult<Boolean> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            ensureOpen()
            val operationGeneration = generation.get()
            val metadata = try {
                metadataProvider.get()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return@withLock cachedSessionFailure(
                    InappifyErrorCode.INVALID_CONFIGURATION,
                    "Host application metadata is unavailable.",
                )
            }
            val normalized = normalize(options, metadata)
                ?: return@withLock cachedSessionFailure(
                    InappifyErrorCode.INVALID_CONFIGURATION,
                    "A non-empty API key and valid marketplace configuration are required.",
                )
            val expectedIdentifier = options.appUserIdentifier.normalized()
            if (options.appUserIdentifier != null &&
                (expectedIdentifier == null || options.appUserIdentifier != expectedIdentifier)
            ) {
                return@withLock cachedSessionFailure(
                    InappifyErrorCode.INVALID_CONFIGURATION,
                    "An explicit cached customer identifier must be non-empty and unmodified.",
                )
            }
            val current = state.get()
            // Never replace a configured account with an older account left on disk.
            val cached = if (current.isConfigured) {
                current.toPersistedSession()
            } else {
                try {
                    sessionStore.loadForCacheRestore()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return@withLock cachedSessionFailure(
                        InappifyErrorCode.STORE_UNAVAILABLE,
                        "The encrypted session cache could not be read.",
                    )
                }
            }
            fun miss(): InappifyResult<Boolean> = InappifyResult.Success(false, snapshot)
            if (cached == null || cached.cacheRestoreBlocked ||
                cached.apiKeyFingerprint != normalized.apiKeyFingerprint ||
                cached.token.isNullOrBlank() || cached.appId == null || cached.appId <= 0 ||
                (cached.forceVersion != null && cached.forceVersion < 1)
            ) return@withLock miss()
            val identifier = cached.appUserIdentifier.normalized()
                ?: return@withLock miss()
            if (identifier != cached.appUserIdentifier ||
                (expectedIdentifier != null && identifier != expectedIdentifier) ||
                (expectedIdentifier == null && !identifier.isAnonymousIdentity()) ||
                cached.offlineSessionFingerprint() in rejectedCachedSessions
            ) return@withLock miss()
            val customerRaw = cached.customerInfoJson ?: return@withLock miss()
            if (!isValidCachedJson(customerRaw)) return@withLock miss()
            val customer = parseCustomerInfo(customerRaw) ?: return@withLock miss()
            if (customer.originalAppUserId != identifier ||
                !isValidCachedExpiration(customer.latestExpirationDate) ||
                customer.entitlements.orEmpty().any { !isValidCachedExpiration(it.expirationDate) }
            ) return@withLock miss()
            var restored = cached.toConfiguredState(
                normalized, metadata, recoverMissingPurchaseBinding = false, validateCachedDocuments = true,
            )
            // An unrelated corrupt offering must not prevent valid customer cache from loading.
            if (restored.offeringsJson?.let(::isValidCachedJson) == false ||
                (restored.offerings?.forceVersion != null &&
                    restored.offerings?.forceVersion != restored.forceVersion)
            ) {
                restored = restored.copy(offerings = null, offeringsJson = null)
            }
            currentCoroutineContext().ensureActive()
            val transition = synchronized(lifecycleLock) {
                if (closed.get() || generation.get() != operationGeneration) null else {
                    val previous = state.get()
                    val next = restored.copy(revision = previous.revision + 1)
                    state.set(next)
                    offlineRestoredSession = cached.offlineSessionFingerprint()
                    previous to next
                }
            } ?: return@withLock cachedSessionFailure(
                InappifyErrorCode.REQUEST_CANCELLED,
                "The SDK operation was cancelled.",
            )
            publishEvents(transition.first, transition.second, requestId = null)
            InappifyResult.Success(true, transition.second.toSnapshot())
        }
    }

    private fun PersistedSession.offlineSessionFingerprint(): String =
        fingerprintComponents(apiKeyFingerprint, token, appUserIdentifier)

    private fun cachedSessionFailure(code: InappifyErrorCode, message: String): InappifyResult<Boolean> =
        resourceFailure(InappifyError(code, message, details = mapOf("operation" to OPERATION_RESTORE_CACHE)))

    /** Only the opt-in offline path adds durable cache rejection to legacy auth behavior. */
    private suspend fun invalidateRestoredSession(
        rejectedState: InternalSessionState,
        error: InappifyError,
        operationGeneration: Long,
    ): InappifyError {
        if (!error.code.requiresNewSession()) return error
        val persisted = rejectedState.toPersistedSession()
        val fingerprint = persisted.offlineSessionFingerprint()
        if (offlineRestoredSession != fingerprint) return error
        rejectedCachedSessions.add(fingerprint)
        val transition = synchronized(lifecycleLock) {
            if (closed.get() || generation.get() != operationGeneration) null else {
                val previous = state.get()
                val next = InternalSessionState.initial(previous.sdkVersion)
                    .copy(revision = previous.revision + 1)
                state.set(next)
                previous to next
            }
        } ?: return error
        publishEvents(transition.first, transition.second, requestId = null)
        val saved = withContext(NonCancellable) {
            try {
                sessionStore.saveWithDiagnostics(persisted.blockCacheRestore())
            } catch (_: Exception) {
                SessionSaveResult.Failure(SessionStorageFailure(SessionStorageStage.UNKNOWN))
            }
        }
        val details = error.details + mapOf(
            "cacheInvalidated" to true,
            "cacheRestoreBlockPersisted" to (saved is SessionSaveResult.Success),
        )
        return if (saved is SessionSaveResult.Success) {
            InappifyError(error.code, error.message, error.isRetryable, details)
        } else {
            InappifyError(
                InappifyErrorCode.STORE_UNAVAILABLE,
                "The rejected session cache could not be blocked securely.",
                details = details + (saved as SessionSaveResult.Failure).diagnostic.toSafeDetails(),
            )
        }
    }

    override suspend fun configure(
        options: InappifyOptions,
    ): InappifyResult<Unit> = operationMutex.withLock {
        ensureOpen()
        val operationGeneration = generation.get()
        val metadata = try {
            metadataProvider.get()
        } catch (_: Exception) {
            return@withLock failure(
                code = InappifyErrorCode.INVALID_CONFIGURATION,
                message = "Host application metadata is unavailable.",
                operation = OPERATION_CONFIGURE,
            )
        }
        val normalized = normalize(options, metadata)
            ?: return@withLock invalidConfiguration(
                "A non-empty API key and valid marketplace configuration are required.",
            )

        val current = state.get()
        val reusable = current.reusableFor(normalized)
            ?.withConfiguration(normalized, metadata)
            ?: loadPersistedSession()
                ?.reusableFor(normalized)
                ?.toConfiguredState(normalized, metadata)

        val configured = completeLifecycleMutation lifecycle@{
            if (
                reusable != null && reusable.storePlatform == null &&
                normalized.market == InappifyMarket.BAZAAR
            ) {
                // Older sessions need route metadata, not a new customer. Keep
                // the same-key identity and recovery binding across this refresh.
                val retainedIdentifier = reusable.appUserIdentifier.normalized()
                    ?: return@lifecycle failure(
                        malformedError(OPERATION_CONFIGURE, null, false),
                    )
                return@lifecycle configureFromNetwork(
                    operationGeneration = operationGeneration,
                    options = normalized,
                    metadata = metadata,
                    previousRecoveryId = reusable.purchaseRecoveryId,
                    previousAppUserIdentifier = retainedIdentifier,
                    requestedAppUserIdentifier = retainedIdentifier,
                )
            }
            if (reusable != null) {
                when (
                    val refreshed = refreshReusableSession(
                        reusable = reusable,
                        options = normalized,
                    )
                ) {
                    is Evaluation.Success -> {
                        val responseIdentifier = refreshed.payload
                            .appUserIdentifier
                            .normalized()
                            ?: return@lifecycle malformedAfterMutation(
                                operation = OPERATION_CONFIGURE,
                                requestId = refreshed.requestId,
                                outcomeMayHaveCommitted = false,
                            )
                        if (
                            normalized.appUserIdentifier != null &&
                            responseIdentifier != normalized.appUserIdentifier
                        ) {
                            return@lifecycle malformedAfterMutation(
                                operation = OPERATION_CONFIGURE,
                                requestId = refreshed.requestId,
                                outcomeMayHaveCommitted = false,
                            )
                        }
                        return@lifecycle hydrateAndCommitLifecycleState(
                            operationGeneration = operationGeneration,
                            operation = OPERATION_CONFIGURE,
                            requestId = refreshed.requestId,
                            candidate = reusable.withRefreshedSession(
                                payload = refreshed.payload,
                                appUserIdentifier = responseIdentifier,
                            ),
                        )
                    }

                    is Evaluation.Failure -> {
                        if (!refreshed.error.code.requiresNewSession()) {
                            return@lifecycle failure(refreshed.error)
                        }
                        val rejected = invalidateRestoredSession(
                            reusable, refreshed.error, operationGeneration,
                        )
                        if (rejected.details["cacheRestoreBlockPersisted"] == false) {
                            return@lifecycle failure(rejected)
                        }
                    }
                }
            }

            configureFromNetwork(
                operationGeneration = operationGeneration,
                options = normalized,
                metadata = metadata,
                previousRecoveryId = reusable?.purchaseRecoveryId,
                previousAppUserIdentifier = reusable?.appUserIdentifier,
            )
        }
        val configuredState = state.get()
        if (configured !is InappifyResult.Success) return@withLock configured
        when (
            val route = configuredState.resolvePurchaseRoute(
                configuredState.market ?: InappifyMarket.NONE,
            )
        ) {
            null -> Unit
            else -> if (route.usesStoreV2 && !configuredState.marketKey.isNullOrBlank()) {
                val reconciliation = syncStoreV2Locked(
                    operationGeneration = operationGeneration,
                    explicitRestore = false,
                    maxPolls = StoreV2Coordinator.MAX_BACKGROUND_POLLS,
                )
                reconcileBazaarDeliveriesLocked(
                    purchases = reconciliation.purchases,
                    operationGeneration = operationGeneration,
                    invokeHandler = true,
                )
            } else if (
                route.market == InappifyMarket.NONE &&
                consumableDeliveryHandler.get() != null
            ) {
                syncDirectConsumablesLocked(
                    operationGeneration = operationGeneration,
                    invokeHandler = true,
                )
            }
        }
        InappifyResult.Success(Unit, state.get().toSnapshot())
    }

    override suspend fun login(
        request: InappifyLoginRequest,
    ): InappifyResult<Unit> = operationMutex.withLock {
        ensureOpen()
        val operationGeneration = generation.get()
        val current = state.get()
        if (!current.isConfigured || current.apiKey.isNullOrBlank()) {
            return@withLock failure(
                code = InappifyErrorCode.NOT_CONFIGURED,
                message = "The client must be configured before login.",
                operation = OPERATION_LOGIN,
            )
        }

        val apiKey = request.apiKey.trim()
        val identifier = request.appUserIdentifier.normalized()
        if (
            apiKey.isEmpty() ||
            apiKey != current.apiKey ||
            identifier == null ||
            identifier.isAnonymousIdentity()
        ) {
            return@withLock invalidConfiguration(
                "Login requires the configured API key and a non-anonymous customer identifier.",
                OPERATION_LOGIN,
            )
        }
        val loggedIn = completeLifecycleMutation lifecycle@{
            val result = callService {
                service.login(
                    LoginApiRequest(
                        apiKey = apiKey,
                        appUserIdentifier = identifier,
                        forceVersion = current.forceVersion,
                        token = current.token,
                    ),
                )
            }
            when (
                val evaluation = evaluate(
                    operation = OPERATION_LOGIN,
                    result = result,
                    mutation = true,
                )
            ) {
                is Evaluation.Failure -> {
                    applyFailedLifecycleForceVersion(
                        operationGeneration = operationGeneration,
                        operation = OPERATION_LOGIN,
                        current = current,
                        result = result,
                    )
                    failure(evaluation.error)
                }
                is Evaluation.Success -> {
                    val token = evaluation.payload.token.normalized()
                        ?: return@lifecycle malformedLifecycleResponse(
                            operationGeneration = operationGeneration,
                            operation = OPERATION_LOGIN,
                            current = current,
                            result = result,
                            requestId = evaluation.requestId,
                        )
                    val responseIdentifier = evaluation.payload
                        .appUserIdentifier
                        .normalized()
                        ?: return@lifecycle malformedLifecycleResponse(
                            operationGeneration = operationGeneration,
                            operation = OPERATION_LOGIN,
                            current = current,
                            result = result,
                            requestId = evaluation.requestId,
                        )
                    if (
                        responseIdentifier != identifier ||
                        responseIdentifier.isAnonymousIdentity()
                    ) {
                        return@lifecycle malformedLifecycleResponse(
                            operationGeneration = operationGeneration,
                            operation = OPERATION_LOGIN,
                            current = current,
                            result = result,
                            requestId = evaluation.requestId,
                        )
                    }
                    hydrateAndCommitLifecycleState(
                        operationGeneration = operationGeneration,
                        operation = OPERATION_LOGIN,
                        requestId = evaluation.requestId,
                        candidate = InternalSessionState(
                            isConfigured = true,
                            isAuthenticated = responseIdentifier
                                .isAuthenticatedIdentity(),
                            apiKey = current.apiKey,
                            apiKeyFingerprint = current.apiKeyFingerprint,
                            cacheContextFingerprint = current.cacheContextFingerprint,
                            token = token,
                            appUserIdentifier = responseIdentifier,
                            market = current.market,
                            marketKey = current.marketKey,
                            country = current.country,
                            appVersion = current.appVersion,
                            sdkVersion = current.sdkVersion,
                            storeInfo = evaluation.payload.storeInfo
                                ?: current.storeInfo,
                            storePlatform = evaluation.payload.storePlatform
                                ?: current.storePlatform,
                            forceVersion = current.monotonicForceVersion(
                                evaluation.payload.forceVersion,
                            ),
                            appId = evaluation.payload.appId ?: current.appId,
                            customerInfoJson = evaluation.payload.customerInfoJson,
                            offeringsJson = null,
                            customerInfoUpdatedAt = null,
                            purchaseRecoveryId = if (
                                responseIdentifier == current.appUserIdentifier
                            ) {
                                current.purchaseRecoveryId?.safePurchaseAttemptId()
                                    ?: newPurchaseRecoveryId()
                            } else {
                                newPurchaseRecoveryId()
                            },
                        ),
                    )
                }
            }
        }
        if (loggedIn !is InappifyResult.Success) return@withLock loggedIn
        val loggedInState = state.get()
        when (
            val route = loggedInState.resolvePurchaseRoute(
                loggedInState.market ?: InappifyMarket.NONE,
            )
        ) {
            null -> Unit
            else -> if (route.usesStoreV2 && !loggedInState.marketKey.isNullOrBlank()) {
                val reconciliation = syncStoreV2Locked(
                    operationGeneration = operationGeneration,
                    explicitRestore = false,
                    maxPolls = StoreV2Coordinator.MAX_BACKGROUND_POLLS,
                )
                reconcileBazaarDeliveriesLocked(
                    purchases = reconciliation.purchases,
                    operationGeneration = operationGeneration,
                    invokeHandler = true,
                )
            } else if (
                route.market == InappifyMarket.NONE &&
                consumableDeliveryHandler.get() != null
            ) {
                syncDirectConsumablesLocked(
                    operationGeneration = operationGeneration,
                    invokeHandler = true,
                )
            }
        }
        InappifyResult.Success(Unit, state.get().toSnapshot())
    }

    override suspend fun logout(): InappifyResult<Unit> =
        operationMutex.withLock {
            ensureOpen()
            val operationGeneration = generation.get()
            val current = state.get()
            val apiKey = current.apiKey
            val token = current.token
            if (!current.isConfigured || apiKey.isNullOrBlank()) {
                return@withLock failure(
                    code = InappifyErrorCode.NOT_CONFIGURED,
                    message = "The client must be configured before logout.",
                    operation = OPERATION_LOGOUT,
                )
            }
            if (token.isNullOrBlank()) {
                return@withLock failure(
                    code = InappifyErrorCode.UNAUTHORIZED,
                    message = "The current session cannot be logged out.",
                    operation = OPERATION_LOGOUT,
                )
            }

            completeLifecycleMutation lifecycle@{
                val result = callService {
                    service.logout(
                        LogoutApiRequest(
                            apiKey = apiKey,
                            token = token,
                            forceVersion = current.forceVersion,
                        ),
                    )
                }
                when (
                    val evaluation = evaluate(
                        operation = OPERATION_LOGOUT,
                        result = result,
                        mutation = true,
                    )
                ) {
                    is Evaluation.Failure -> {
                        applyFailedLifecycleForceVersion(
                            operationGeneration = operationGeneration,
                            operation = OPERATION_LOGOUT,
                            current = current,
                            result = result,
                        )
                        failure(evaluation.error)
                    }
                    is Evaluation.Success -> {
                        val responseIdentifier = evaluation.payload
                            .appUserIdentifier
                            .normalized()
                        if (
                            responseIdentifier != null &&
                            !responseIdentifier.isAnonymousIdentity()
                        ) {
                            return@lifecycle malformedLifecycleResponse(
                                operationGeneration = operationGeneration,
                                operation = OPERATION_LOGOUT,
                                current = current,
                                result = result,
                                requestId = evaluation.requestId,
                            )
                        }
                        hydrateAndCommitLifecycleState(
                            operationGeneration = operationGeneration,
                            operation = OPERATION_LOGOUT,
                            requestId = evaluation.requestId,
                            candidate = InternalSessionState(
                                isConfigured = true,
                                isAuthenticated = false,
                                apiKey = current.apiKey,
                                apiKeyFingerprint = current.apiKeyFingerprint,
                                cacheContextFingerprint = current.cacheContextFingerprint,
                                token = evaluation.payload.token.normalized(),
                                appUserIdentifier = responseIdentifier,
                                market = current.market,
                                marketKey = current.marketKey,
                                country = current.country,
                                appVersion = current.appVersion,
                                sdkVersion = current.sdkVersion,
                                storeInfo = evaluation.payload.storeInfo
                                    ?: current.storeInfo,
                                storePlatform = evaluation.payload.storePlatform
                                    ?: current.storePlatform,
                                forceVersion = current.monotonicForceVersion(
                                    evaluation.payload.forceVersion,
                                ),
                                appId = evaluation.payload.appId ?: current.appId,
                                customerInfoJson = evaluation.payload.customerInfoJson,
                                offeringsJson = null,
                                customerInfoUpdatedAt = null,
                                purchaseRecoveryId = newPurchaseRecoveryId(),
                            ),
                        )
                    }
                }
            }
        }

    override suspend fun getCustomerInfo(
        forceRefresh: Boolean,
    ): InappifyResult<InappifyCustomerInfo> =
        loadCustomerInfo(forceRefresh = forceRefresh)

    override suspend fun refreshCustomerInfo(): InappifyResult<InappifyCustomerInfo> =
        loadCustomerInfo(forceRefresh = true)

    override suspend fun getOfferings(): InappifyResult<InappifyOfferings> =
        loadOfferings(forceRefresh = false)

    override suspend fun refreshOfferings(): InappifyResult<InappifyOfferings> =
        loadOfferings(forceRefresh = true)

    override suspend fun validateDiscountCode(
        request: InappifyDiscountCodeRequest,
    ): InappifyResult<InappifyDiscountCodeResult> = operationMutex.withLock {
        ensureOpen()
        val current = state.get()
        val sessionRequest = current.resourceRequestOrNull()
            ?: return@withLock resourcePreconditionFailure(
                current = current,
                operation = OPERATION_VALIDATE_DISCOUNT_CODE,
            )

        val result = callService {
            service.validateDiscountCode(
                ValidateDiscountCodeApiRequest(
                    apiKey = sessionRequest.apiKey,
                    token = sessionRequest.token,
                    discountCode = request.discountCode,
                ),
            )
        }
        when (
            val evaluation = evaluate(
                operation = OPERATION_VALIDATE_DISCOUNT_CODE,
                result = result,
                mutation = false,
                requireSuccessfulPayloadStatus = false,
            )
        ) {
            is Evaluation.Failure -> resourceFailure(evaluation.error)
            is Evaluation.Success -> {
                val raw = evaluation.payload.discountCodeResultJson
                    ?: return@withLock malformedOperationFailure(
                        operation = OPERATION_VALIDATE_DISCOUNT_CODE,
                        requestId = evaluation.requestId,
                        outcomeMayHaveCommitted = false,
                    )
                val discountCodeResult = try {
                    InappifyDomainJsonCodec.parseDiscountCodeResult(raw)
                } catch (_: IllegalArgumentException) {
                    return@withLock malformedOperationFailure(
                        operation = OPERATION_VALIDATE_DISCOUNT_CODE,
                        requestId = evaluation.requestId,
                        outcomeMayHaveCommitted = false,
                    )
                }
                // Discount validation returns decoded data for any HTTP 200
                // response; this endpoint does not update session forceVersion.
                InappifyResult.Success(discountCodeResult, current.toSnapshot())
            }
        }
    }

    override suspend fun setTargetingContext(
        country: String?,
        appVersion: String?,
    ): InappifyResult<Unit> = operationMutex.withLock {
        ensureOpen()
        val operationGeneration = generation.get()
        val current = state.get()
        if (!current.isConfigured) {
            return@withLock failure(
                code = InappifyErrorCode.NOT_CONFIGURED,
                message = "The client must be configured before updating targeting context.",
                operation = OPERATION_SET_TARGETING_CONTEXT,
            )
        }
        val nextCountry = country?.trim()?.uppercase(Locale.ROOT) ?: current.country
        val nextAppVersion = appVersion?.trim() ?: current.appVersion
        val market = current.market ?: InappifyMarket.NONE
        val resolvedCountry = nextCountry ?: DEFAULT_COUNTRY
        val resolvedAppVersion = nextAppVersion.orEmpty()
        val next = current.copy(
            cacheContextFingerprint = cacheContextFingerprint(
                market = market,
                marketKey = current.marketKey,
                country = resolvedCountry,
                appVersion = resolvedAppVersion,
            ),
            country = resolvedCountry,
            appVersion = resolvedAppVersion,
            offeringsJson = null,
            offerings = null,
            failedToLoadOfferings = false,
        )
        commit(
            operationGeneration = operationGeneration,
            operation = OPERATION_SET_TARGETING_CONTEXT,
            next = next,
        )
    }

    override suspend fun getCurrentOffering(
        placementIdentifier: String?,
        forceRefresh: Boolean,
        context: InappifyOfferingEvaluationContext?,
    ): InappifyResult<InappifyOffering?> {
        val offeringsResult = if (forceRefresh) {
            refreshOfferings()
        } else {
            getOfferings()
        }
        return when (offeringsResult) {
            is InappifyResult.Failure -> offeringsResult
            is InappifyResult.Success -> {
                val evaluationContext = context
                    ?: offeringsResult.snapshot.toOfferingEvaluationContext()
                val offering = try {
                    offeringsResult.data.resolveOffering(
                        context = evaluationContext,
                        placement = placementIdentifier,
                    )
                } catch (_: RuntimeException) {
                    null
                }
                InappifyResult.Success(
                    data = offering,
                    snapshot = offeringsResult.snapshot,
                )
            }
        }
    }

    override suspend fun checkEntitlement(
        identifier: String,
        forceRefresh: Boolean,
    ): InappifyResult<Boolean> {
        val customerResult = if (forceRefresh) {
            refreshCustomerInfo()
        } else {
            getCustomerInfo(forceRefresh = false)
        }
        return when (customerResult) {
            is InappifyResult.Failure -> customerResult
            is InappifyResult.Success -> InappifyResult.Success(
                data = customerResult.data.findActiveEntitlement(identifier) != null,
                snapshot = customerResult.snapshot,
            )
        }
    }

    override suspend fun setAttributes(
        request: InappifyAttributesRequest,
    ): InappifyResult<List<InappifyAttribute>> = operationMutex.withLock {
        ensureOpen()
        val operationGeneration = generation.get()
        val current = state.get()
        val sessionRequest = current.resourceRequestOrNull()
            ?: return@withLock resourcePreconditionFailure(
                current = current,
                operation = OPERATION_SET_ATTRIBUTES,
            )
        val validAttributes = request.attributes.filter(
            InappifyAttribute::isValidCustomAttribute,
        )
        val storeAttributes = validAttributes.filterNot(
            InappifyAttribute::removesCustomAttribute,
        )
        val removeAttributes = validAttributes.filter(
            InappifyAttribute::removesCustomAttribute,
        )
        if (storeAttributes.isEmpty() && removeAttributes.isEmpty()) {
            return@withLock InappifyResult.Success(
                data = current.customerInfo?.attributes.orEmpty(),
                snapshot = current.toSnapshot(),
            )
        }

        // Apply attribute changes optimistically before dispatch. A remote
        // failure does not roll back the local customer projection.
        val optimisticAttributes = current.customerInfo?.attributes
            ?.applyAttributeChanges(storeAttributes, removeAttributes)
        val optimisticCommit = optimisticAttributes?.let { attributes ->
            commit(
                operationGeneration = operationGeneration,
                operation = OPERATION_SET_ATTRIBUTES,
                next = current.withCustomerAttributes(attributes),
            )
        }
        if (
            optimisticCommit is InappifyResult.Failure &&
            optimisticCommit.error.code == InappifyErrorCode.REQUEST_CANCELLED
        ) {
            return@withLock optimisticCommit
        }

        if (storeAttributes.isNotEmpty()) {
            val result = callService {
                service.storeAttributes(
                    StoreAttributesApiRequest(
                        apiKey = sessionRequest.apiKey,
                        token = sessionRequest.token,
                        attributes = storeAttributes,
                        forceVersion = current.forceVersion,
                    ),
                )
            }
            when (
                val evaluation = evaluate(
                    operation = OPERATION_SET_ATTRIBUTES,
                    result = result,
                    mutation = true,
                )
            ) {
                is Evaluation.Failure -> return@withLock resourceFailure(evaluation.error)
                is Evaluation.Success -> Unit
            }
        }
        if (removeAttributes.isNotEmpty()) {
            // Attribute removal is best effort. A failed remove-only request,
            // or a remove failure after a successful store, still resolves
            // successfully.
            callService {
                service.removeAttributes(
                    RemoveAttributesApiRequest(
                        apiKey = sessionRequest.apiKey,
                        token = sessionRequest.token,
                        attributes = removeAttributes,
                        forceVersion = current.forceVersion,
                    ),
                )
            }
        }

        if (optimisticCommit is InappifyResult.Failure) {
            return@withLock optimisticCommit
        }
        val authoritative = state.get()
        InappifyResult.Success(
            data = authoritative.customerInfo?.attributes.orEmpty(),
            snapshot = authoritative.toSnapshot(),
        )
    }

    override suspend fun deleteAttributes(
        request: InappifyDeleteAttributesRequest,
    ): InappifyResult<List<InappifyAttribute>> = setAttributes(
        InappifyAttributesRequest(
            request.keys.map { key -> InappifyAttribute(key = key, value = "") },
        ),
    )

    override suspend fun setReservedAttribute(
        request: InappifyReservedAttributeRequest,
    ): InappifyResult<Unit> = operationMutex.withLock {
        ensureOpen()
        val operationGeneration = generation.get()
        val current = state.get()
        val sessionRequest = current.resourceRequestOrNull()
            ?: return@withLock resourcePreconditionFailure(
                current = current,
                operation = OPERATION_SET_RESERVED_ATTRIBUTE,
            )
        if (!request.hasValidValue()) {
            return@withLock invalidConfiguration(
                message = "The reserved attribute value is invalid.",
                operation = OPERATION_SET_RESERVED_ATTRIBUTE,
            )
        }

        val removesEmail = request.attribute == InappifyReservedAttribute.EMAIL &&
            request.value.isEmpty()
        // Only email updates the cached projection. A non-empty email is
        // appended even when another `$email` exists; clearing email changes
        // only the first match. Other reserved setters leave the cache intact.
        val optimisticEmailAttributes = if (
            request.attribute == InappifyReservedAttribute.EMAIL
        ) {
            current.customerInfo?.attributes?.let { attributes ->
                if (removesEmail) {
                    attributes.clearFirstAttributeValue(request.attribute.backendKey)
                } else {
                    attributes + InappifyAttribute(
                        key = request.attribute.backendKey,
                        value = request.value,
                    )
                }
            }
        } else {
            null
        }
        val optimisticCommit = optimisticEmailAttributes?.let { attributes ->
            commit(
                operationGeneration = operationGeneration,
                operation = OPERATION_SET_RESERVED_ATTRIBUTE,
                next = current.withCustomerAttributes(attributes),
            )
        }
        if (
            optimisticCommit is InappifyResult.Failure &&
            optimisticCommit.error.code == InappifyErrorCode.REQUEST_CANCELLED
        ) {
            return@withLock optimisticCommit
        }
        val result = callService {
            if (removesEmail) {
                service.removeAttributes(
                    RemoveAttributesApiRequest(
                        apiKey = sessionRequest.apiKey,
                        token = sessionRequest.token,
                        attributes = listOf(
                            InappifyAttribute(key = request.attribute.backendKey),
                        ),
                        forceVersion = current.forceVersion,
                    ),
                )
            } else {
                service.storeReservedAttribute(
                    StoreReservedAttributeApiRequest(
                        apiKey = sessionRequest.apiKey,
                        token = sessionRequest.token,
                        key = request.attribute.backendKey,
                        value = request.value,
                        forceVersion = current.forceVersion,
                    ),
                )
            }
        }
        when (
            val evaluation = evaluate(
                operation = OPERATION_SET_RESERVED_ATTRIBUTE,
                result = result,
                mutation = true,
            )
        ) {
            is Evaluation.Failure -> failure(evaluation.error)
            is Evaluation.Success -> {
                optimisticCommit ?: success(state.get())
            }
        }
    }

    override suspend fun syncAttributes(
        request: InappifyAttributesRequest?,
    ): InappifyResult<List<InappifyAttribute>> = operationMutex.withLock {
        ensureOpen()
        val operationGeneration = generation.get()
        val current = state.get()
        val sessionRequest = current.resourceRequestOrNull()
            ?: return@withLock resourcePreconditionFailure(
                current = current,
                operation = OPERATION_SYNC_ATTRIBUTES,
            )
        val requestedAttributes = request?.attributes
            ?: current.customerInfo?.attributes.orEmpty()
        // Full synchronization replaces the local list before I/O. The
        // optimistic projection remains visible if cancellation interrupts
        // the request.
        val optimisticCommit = if (request != null && current.customerInfo != null) {
            commit(
                operationGeneration = operationGeneration,
                operation = OPERATION_SYNC_ATTRIBUTES,
                next = current.withCustomerAttributes(requestedAttributes),
            )
        } else {
            null
        }
        if (
            optimisticCommit is InappifyResult.Failure &&
            optimisticCommit.error.code == InappifyErrorCode.REQUEST_CANCELLED
        ) {
            return@withLock optimisticCommit
        }
        val result = callService {
            service.syncAttributes(
                SyncAttributesApiRequest(
                    apiKey = sessionRequest.apiKey,
                    token = sessionRequest.token,
                    attributes = requestedAttributes,
                    forceVersion = current.forceVersion,
                ),
            )
        }
        if (result is ServiceResult.Failure) {
            val failed = evaluate(
                operation = OPERATION_SYNC_ATTRIBUTES,
                result = result,
                mutation = true,
            ) as Evaluation.Failure
            commit(
                operationGeneration = operationGeneration,
                operation = OPERATION_SYNC_ATTRIBUTES,
                next = state.get().withCustomerAttributes(null),
            )
            return@withLock resourceFailure(failed.error)
        }
        result as ServiceResult.Response
        if (result.statusCode != HTTP_OK) {
            val failed = evaluate(
                operation = OPERATION_SYNC_ATTRIBUTES,
                result = result,
                mutation = true,
            ) as Evaluation.Failure
            commit(
                operationGeneration = operationGeneration,
                operation = OPERATION_SYNC_ATTRIBUTES,
                next = state.get().withCustomerAttributes(null),
                requestId = result.requestId,
            )
            return@withLock resourceFailure(failed.error)
        }

        val payload = result.payload
        val attributes = payload.attributesJson?.let { raw ->
            try {
                InappifyDomainJsonCodec.parseAttributes(raw)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val beforeResponse = state.get()
        val next = beforeResponse
            .withCustomerAttributes(attributes)
            .withReceivedForceVersion(payload.forceVersion)
        val committed = commit(
            operationGeneration = operationGeneration,
            operation = OPERATION_SYNC_ATTRIBUTES,
            next = next,
            requestId = result.requestId,
        )
        scheduleOfferingRefresh(
            previousForceVersion = beforeResponse.forceVersion,
            receivedForceVersion = payload.forceVersion,
        )
        val evaluation = evaluate(
            operation = OPERATION_SYNC_ATTRIBUTES,
            result = result,
            mutation = true,
        )
        if (evaluation is Evaluation.Failure) {
            return@withLock resourceFailure(evaluation.error)
        }
        if (committed is InappifyResult.Failure) {
            return@withLock committed
        }
        val authoritative = state.get()
        InappifyResult.Success(
            data = attributes.orEmpty(),
            snapshot = authoritative.toSnapshot(),
        )
    }

    override suspend fun canSetReservedAttribute(
        key: String,
    ): InappifyResult<Boolean> = operationMutex.withLock {
        ensureOpen()
        val current = state.get()
        InappifyResult.Success(
            data = key.trim().isNotEmpty(),
            snapshot = current.toSnapshot(),
        )
    }

    override suspend fun purchase(
        request: InappifyPurchaseRequest,
    ): InappifyResult<InappifyPurchase> = purchaseWithOptionalActivity(
        activity = null,
        request = request,
    )

    override suspend fun purchase(
        activity: Activity,
        request: InappifyPurchaseRequest,
    ): InappifyResult<InappifyPurchase> = purchaseWithOptionalActivity(
        activity = activity,
        request = request,
    )

    private suspend fun purchaseWithOptionalActivity(
        activity: Activity?,
        request: InappifyPurchaseRequest,
    ): InappifyResult<InappifyPurchase> {
        ensureOpen()
        if (!purchaseInProgress.compareAndSet(false, true)) {
            return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.PURCHASE_IN_PROGRESS,
                    message = "Another Inappify purchase is already in progress.",
                    isRetryable = true,
                    details = mapOf("operation" to OPERATION_PURCHASE),
                ),
            )
        }

        return try {
            operationMutex.withLock {
                ensureOpen()
                val operationGeneration = generation.get()
                val purchased = purchaseLocked(
                    activity = activity,
                    request = request,
                    operationGeneration = operationGeneration,
                )
                if (
                    purchased is InappifyResult.Success &&
                    purchased.data.storePurchaseStatus ==
                    InappifyStorePurchaseStatus.DELIVERY_REQUIRED &&
                    consumableDeliveryHandler.get() != null
                ) {
                    val fulfillment = reconcileBazaarDeliveriesLocked(
                        purchases = listOf(purchased.data),
                        operationGeneration = operationGeneration,
                        invokeHandler = true,
                    )
                    val updated = fulfillment.purchases.firstOrNull {
                        it.attemptId == purchased.data.attemptId
                    } ?: purchased.data
                    InappifyResult.Success(updated, fulfillment.snapshot)
                } else {
                    purchased
                }
            }
        } finally {
            purchaseInProgress.set(false)
        }
    }

    override suspend fun syncPurchases(): InappifyResult<List<InappifyPurchase>> {
        ensureOpen()
        if (!purchaseInProgress.compareAndSet(false, true)) {
            return purchaseSyncFailure(
                InappifyError(
                    code = InappifyErrorCode.PURCHASE_IN_PROGRESS,
                    message = "Another Inappify purchase is already in progress.",
                    isRetryable = true,
                    details = mapOf("operation" to OPERATION_SYNC_PURCHASES),
                ),
            )
        }

        return try {
            operationMutex.withLock {
                ensureOpen()
                val current = state.get()
                when (
                    val route = current.resolvePurchaseRoute(
                        current.market ?: InappifyMarket.NONE,
                    )
                ) {
                    null -> purchaseSyncFailure(
                        unsupportedStoreRouteError(OPERATION_SYNC_PURCHASES),
                    )

                    else -> if (route.usesStoreV2) {
                        syncStoreV2Locked(
                            operationGeneration = generation.get(),
                            explicitRestore = false,
                            maxPolls = StoreV2Coordinator.MAX_BACKGROUND_POLLS,
                        ).toLegacyResult()
                    } else if (route.market == InappifyMarket.NONE) {
                        syncDirectConsumablesLocked(
                            operationGeneration = generation.get(),
                            invokeHandler = true,
                        ).toLegacyResult()
                    } else {
                        syncPurchasesLocked(
                            operationGeneration = generation.get(),
                            market = route.market,
                        )
                    }
                }
            }
        } finally {
            purchaseInProgress.set(false)
        }
    }

    override suspend fun restorePurchasesV2(): InappifyResult<InappifyRestoreResult> {
        ensureOpen()
        if (!purchaseInProgress.compareAndSet(false, true)) {
            return InappifyResult.Failure(
                error = InappifyError(
                    code = InappifyErrorCode.PURCHASE_IN_PROGRESS,
                    message = "Another Inappify purchase operation is already in progress.",
                    isRetryable = true,
                    details = mapOf("operation" to OPERATION_RESTORE_PURCHASES),
                ),
                snapshot = state.get().toSnapshot(),
            )
        }
        return try {
            operationMutex.withLock {
                ensureOpen()
                val current = state.get()
                when (
                    val route = current.resolvePurchaseRoute(
                        current.market ?: InappifyMarket.NONE,
                    )
                ) {
                    null -> InappifyResult.Failure(
                        error = unsupportedStoreRouteError(OPERATION_RESTORE_PURCHASES),
                        snapshot = current.toSnapshot(),
                    )

                    else -> if (route.usesStoreV2) {
                        syncStoreV2Locked(
                            operationGeneration = generation.get(),
                            explicitRestore = true,
                            maxPolls = StoreV2Coordinator.MAX_FOREGROUND_POLLS,
                        ).toRestoreResult()
                    } else {
                        when (
                            val legacy = syncPurchasesLocked(
                                operationGeneration = generation.get(),
                                market = route.market,
                            )
                        ) {
                            is InappifyResult.Success -> InappifyResult.Success(
                                data = InappifyRestoreResult(
                                    restoredCount = legacy.data.size,
                                    alreadyProcessedCount = 0,
                                    failedCount = 0,
                                ),
                                snapshot = legacy.snapshot,
                            )

                            is InappifyResult.Failure -> legacy
                        }
                    }
                }
            }
        } finally {
            purchaseInProgress.set(false)
        }
    }

    override suspend fun confirmDeliveryV2(
        deliveryId: Long,
    ): InappifyResult<InappifyPurchase> {
        ensureOpen()
        if (deliveryId <= 0L) {
            return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.INVALID_CONFIGURATION,
                    message = "A positive delivery identifier is required.",
                    details = mapOf("operation" to OPERATION_CONFIRM_DELIVERY),
                ),
            )
        }
        if (!purchaseInProgress.compareAndSet(false, true)) {
            return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.PURCHASE_IN_PROGRESS,
                    message = "Another Inappify purchase operation is already in progress.",
                    isRetryable = true,
                    details = mapOf("operation" to OPERATION_CONFIRM_DELIVERY),
                ),
            )
        }
        return try {
            operationMutex.withLock {
                ensureOpen()
                confirmDeliveryLocked(deliveryId, generation.get())
            }
        } finally {
            purchaseInProgress.set(false)
        }
    }

    private suspend fun confirmDeliveryLocked(
        deliveryId: Long,
        operationGeneration: Long,
    ): InappifyResult<InappifyPurchase> {
        val current = state.get()
        val sessionRequest = current.resourceRequestOrNull()
            ?: return purchasePreconditionFailure(current)
        val route = current.resolvePurchaseRoute(InappifyMarket.NONE)
        if (route != null && !route.usesStoreV2 && route.market == InappifyMarket.NONE) {
            return confirmDirectConsumableLocked(
                current = current,
                sessionRequest = sessionRequest,
                deliveryId = deliveryId,
                operationGeneration = operationGeneration,
            )
        }
        if (route?.usesStoreV2 != true) {
            return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.UNSUPPORTED_OPERATION,
                    message = "Consumable delivery confirmation is unavailable for this app.",
                    details = mapOf("operation" to OPERATION_CONFIRM_DELIVERY),
                ),
            )
        }
        val metadata = try {
            metadataProvider.get()
        } catch (_: Exception) {
            return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.INVALID_CONFIGURATION,
                    message = "Host application metadata is unavailable.",
                    details = mapOf("operation" to OPERATION_CONFIRM_DELIVERY),
                ),
            )
        }
        val context = try {
            current.storeV2Context(metadata)
        } catch (_: IllegalArgumentException) {
            return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.INVALID_CONFIGURATION,
                    message = "The V2 store configuration is incomplete.",
                    details = mapOf("operation" to OPERATION_CONFIRM_DELIVERY),
                ),
            )
        }
        val recoveryState = sessionStore.loadPendingStoreRecoveryState()
            ?: return purchaseFailure(storeRecoveryUnavailableError(OPERATION_CONFIRM_DELIVERY))
        val pending = recoveryState.operations
            .firstOrNull { operation ->
                operation.deliveryId == deliveryId &&
                    operation.productType == PendingStoreProductType.CONSUMABLE &&
                    operation.matchesStoreV2Binding(current, metadata)
            }
            ?: return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.INVALID_CONFIGURATION,
                    message = "No pending consumable matches this delivery identifier.",
                    details = mapOf("operation" to OPERATION_CONFIRM_DELIVERY),
                ),
            )
        val outcome = storeV2Coordinator.confirmDelivery(
            operation = pending,
            context = context,
        )
        return completeStoreV2PurchaseOutcome(
            current = current,
            outcome = outcome,
            operationGeneration = operationGeneration,
        )
    }

    private suspend fun purchaseLocked(
        activity: Activity?,
        request: InappifyPurchaseRequest,
        operationGeneration: Long,
    ): InappifyResult<InappifyPurchase> {
        val current = state.get()
        val sessionRequest = current.resourceRequestOrNull()
            ?: return purchasePreconditionFailure(current)
        val normalized = normalizePurchaseRequest(request)
            ?: return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.INVALID_CONFIGURATION,
                    message = "The purchase request is invalid.",
                    details = mapOf("operation" to OPERATION_PURCHASE),
                ),
            )
        val metadata = try {
            metadataProvider.get()
        } catch (_: Exception) {
            return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.INVALID_CONFIGURATION,
                    message = "Host application metadata is unavailable.",
                    details = mapOf(
                        "operation" to OPERATION_PURCHASE,
                        "attemptId" to normalized.attemptId,
                    ),
                ),
            )
        }
        val route = current.resolvePurchaseRoute(
            normalized.market,
            preserveRequestedMarket = legacyPurchaseRouting && normalized.productType == null,
        )
            ?: return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.UNSUPPORTED_OPERATION,
                    message = "The configured store platform is not supported by this SDK version.",
                    details = mapOf("operation" to OPERATION_PURCHASE),
                ),
            )
        if (
            normalized.productType == InappifyProductType.CONSUMABLE &&
            !route.usesStoreV2 &&
            route.market != InappifyMarket.NONE
        ) {
            return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.UNSUPPORTED_OPERATION,
                    message = "Consumable marketplace purchases require a V2 store route.",
                    details = mapOf(
                        "operation" to OPERATION_PURCHASE,
                        "attemptId" to normalized.attemptId,
                        "storePlatform" to (current.storePlatform ?: "missing"),
                        "outcomeMayHaveCommitted" to false,
                    ),
                ),
            )
        }
        val effectiveMarket = route.market
        val effectiveMarketKey = when {
            route.usesStoreV2 -> current.marketKey
            route.isServerAuthoritative -> current.marketKey ?: normalized.marketKey
            else -> normalized.marketKey
        }
        // A V1 Direct client may configure without Bazaar credentials. Require
        // the configured RSA key only when it actually enters a Store V2 purchase.
        if (route.usesStoreV2 && effectiveMarketKey.isNullOrBlank()) {
            return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.INVALID_CONFIGURATION,
                    message = "A Cafe Bazaar RSA public key is required for store purchases.",
                    details = mapOf("operation" to OPERATION_PURCHASE, "outcomeMayHaveCommitted" to false),
                ),
            )
        }
        val recoveryState = if (route.usesStoreV2) {
            sessionStore.loadPendingStoreRecoveryState()
                ?: return purchaseFailure(
                    storeRecoveryUnavailableError(
                        operation = OPERATION_PURCHASE,
                        attemptId = normalized.attemptId,
                    ),
                )
        } else {
            null
        }

        if (route.usesStoreV2) {
            val existing = requireNotNull(recoveryState).operations
                .firstOrNull { operation -> operation.id == normalized.attemptId }
            if (existing != null) {
                val sameLogicalPurchase =
                    existing.matchesStoreV2Binding(current, metadata) &&
                        existing.store.equals(STORE_BAZAAR, ignoreCase = true) &&
                        existing.productIdentifier == normalized.productIdentifier &&
                        existing.offeringIdentifier == normalized.offeringIdentifier &&
                        existing.matchesRequestedProductType(normalized.productType)
                if (!sameLogicalPurchase) {
                    return purchaseFailure(
                        InappifyError(
                            code = InappifyErrorCode.INVALID_CONFIGURATION,
                            message = "The purchase idempotency key is already in use.",
                            details = mapOf(
                                "operation" to OPERATION_PURCHASE,
                                "attemptId" to normalized.attemptId,
                            ),
                        ),
                    )
                }
                val context = try {
                    current.storeV2Context(
                        metadata = metadata,
                        marketKey = effectiveMarketKey,
                    )
                } catch (_: IllegalArgumentException) {
                    return purchaseFailure(
                        InappifyError(
                            code = InappifyErrorCode.INVALID_CONFIGURATION,
                            message = "The V2 store configuration is incomplete.",
                            details = mapOf("operation" to OPERATION_PURCHASE),
                        ),
                    )
                }
                return completeStoreV2PurchaseOutcome(
                    current = current,
                    outcome = storeV2Coordinator.resume(
                        operation = existing,
                        context = context,
                        maxPolls = StoreV2Coordinator.MAX_FOREGROUND_POLLS,
                    ),
                    operationGeneration = operationGeneration,
                    packageIdentifier = existing.packageIdentifierFromPayload()
                        ?: normalized.packageIdentifier,
                )
            }
        }

        // A paid checkpoint must resume even if forceVersion invalidated the
        // catalog. Only a new checkout needs the currently offered product.
        // Explicit lost-purchase recovery also bypasses catalog lookup and UI.
        val selectedPackage = if (normalized.isLostPurchase) {
            null
        } else {
            current.findPurchasePackage(normalized)
                ?: return purchaseFailure(
                    InappifyError(
                        code = InappifyErrorCode.INVALID_CONFIGURATION,
                        message = "The requested product is not available in the selected offering.",
                        details = mapOf(
                            "operation" to OPERATION_PURCHASE,
                            "attemptId" to normalized.attemptId,
                        ),
                    ),
                )
        }

        val storePurchase = when {
            normalized.isLostPurchase -> null
            requireNotNull(selectedPackage).isTrial() -> null
            effectiveMarket == InappifyMarket.NONE -> null
            else -> {
                val foregroundActivity = activity
                    ?: return purchaseFailure(
                        StoreBillingError(
                            code = StoreBillingErrorCode.UI_HOST_UNAVAILABLE,
                            message = "A foreground Activity is required for Bazaar billing.",
                        ).toPublicError(normalized.attemptId),
                    )
                when (
                    val storeResult = purchaseFromStore(
                        activity = foregroundActivity,
                        current = current,
                        request = normalized,
                        market = effectiveMarket,
                        marketKey = effectiveMarketKey,
                        selectedPackage = requireNotNull(selectedPackage),
                        expectedPackageName = metadata.packageIdentifier,
                        usesStoreV2 = route.usesStoreV2,
                    )
                ) {
                    is StoreBillingResult.Success -> storeResult.purchase
                    StoreBillingResult.Cancelled -> {
                        return purchaseFailure(
                            InappifyError(
                                code = InappifyErrorCode.PURCHASE_CANCELLED,
                                message = "The marketplace purchase was cancelled.",
                                details = mapOf(
                                    "operation" to OPERATION_PURCHASE,
                                    "attemptId" to normalized.attemptId,
                                    "outcomeMayHaveCommitted" to false,
                                ),
                            ),
                        )
                    }

                    is StoreBillingResult.Failure -> {
                        return purchaseFailure(
                            storeResult.error.toPublicError(normalized.attemptId),
                        )
                    }
                }
            }
        }

        val storeV2Evidence = when {
            !route.usesStoreV2 -> null
            normalized.isLostPurchase -> {
                val purchaseToken = normalized.lostPurchaseToken
                    ?.takeIf(String::isNotBlank)
                    ?: return purchaseFailure(
                        InappifyError(
                            code = InappifyErrorCode.INVALID_CONFIGURATION,
                            message = "A store purchase token is required for V2 recovery.",
                            details = mapOf(
                                "operation" to OPERATION_PURCHASE,
                                "attemptId" to normalized.attemptId,
                            ),
                        ),
                    )
                if (normalized.lostPurchaseTime?.let { it < 0L } == true) {
                    return purchaseFailure(
                        InappifyError(
                            code = InappifyErrorCode.INVALID_CONFIGURATION,
                            message = "The store purchase time must not be negative.",
                            details = mapOf(
                                "operation" to OPERATION_PURCHASE,
                                "attemptId" to normalized.attemptId,
                            ),
                        ),
                    )
                }
                PendingStorePurchaseEvidence(
                    purchaseToken = purchaseToken,
                    packageName = metadata.packageIdentifier,
                    purchaseTimeMillis = normalized.lostPurchaseTime,
                )
            }

            storePurchase != null -> storePurchase.toPendingEvidence()
            else -> null
        }

        if (storeV2Evidence != null) {
            return purchaseStoreV2Locked(
                current = current,
                sessionToken = sessionRequest.token,
                request = normalized,
                selectedPackage = selectedPackage,
                evidence = storeV2Evidence,
                metadata = metadata,
                operationGeneration = operationGeneration,
                marketKey = effectiveMarketKey,
                recoveryState = requireNotNull(recoveryState),
            )
        }

        val purchaseToken = if (normalized.isLostPurchase) {
            normalized.lostPurchaseToken
        } else {
            storePurchase?.purchaseToken
        }
        val purchaseTime = if (normalized.isLostPurchase) {
            normalized.lostPurchaseTime
        } else {
            storePurchase?.purchaseTimeMillis
        }
        val storePurchaseCompleted = storePurchase != null ||
            normalized.isLostPurchase &&
            !normalized.lostPurchaseToken.isNullOrEmpty()
        val backendResult = callService {
            service.purchase(
                PurchaseApiRequest(
                    apiKey = normalized.apiKey ?: sessionRequest.apiKey,
                    token = sessionRequest.token,
                    appIdentifier = metadata.packageIdentifier,
                    country = normalized.country ?: requireNotNull(current.country),
                    productIdentifier = normalized.productIdentifier,
                    offeringIdentifier = normalized.offeringIdentifier,
                    purchaseTokenId = purchaseToken,
                    discount = normalized.discount,
                    isCrypto = normalized.isCrypto,
                    forceVersion = current.forceVersion,
                    appVersion = normalized.appVersion ?: requireNotNull(current.appVersion),
                    purchaseStoreTime = purchaseTime,
                    paywallId = request.paywallId,
                    paywallRevision = request.paywallRevision,
                ),
            )
        }
        val evaluation = evaluate(
            operation = OPERATION_PURCHASE,
            result = backendResult,
            mutation = true,
        )
        var purchase: InappifyPurchase? = null
        var terminalError: InappifyError? = null
        when (evaluation) {
            is Evaluation.Failure -> {
                terminalError = evaluation.error.withPurchaseContext(
                    attemptId = normalized.attemptId,
                    storePurchaseCompleted = storePurchaseCompleted,
                )
            }

            is Evaluation.Success -> {
                val backendPurchase = evaluation.payload.purchase
                if (backendPurchase == null) {
                    terminalError = malformedError(
                        operation = OPERATION_PURCHASE,
                        requestId = evaluation.requestId,
                        outcomeMayHaveCommitted = true,
                    ).withPurchaseContext(
                        attemptId = normalized.attemptId,
                        storePurchaseCompleted = storePurchaseCompleted,
                    )
                } else {
                    try {
                        purchase = InappifyPurchase(
                            attemptId = normalized.attemptId,
                            productIdentifier = normalized.productIdentifier,
                            offeringIdentifier = normalized.offeringIdentifier,
                            packageIdentifier = selectedPackage?.identifier
                                ?: normalized.packageIdentifier,
                            market = effectiveMarket,
                            purchaseStatus = InappifyPurchaseStatus.fromServerValue(
                                backendPurchase.purchaseStatus,
                            ),
                            url = backendPurchase.url,
                            checkoutId = backendPurchase.checkoutId,
                            checkoutStatus = backendPurchase.checkoutStatus,
                            nextActionType = backendPurchase.nextActionType,
                        )
                    } catch (_: IllegalArgumentException) {
                        terminalError = malformedError(
                            operation = OPERATION_PURCHASE,
                            requestId = evaluation.requestId,
                            outcomeMayHaveCommitted = true,
                        ).withPurchaseContext(
                            attemptId = normalized.attemptId,
                            storePurchaseCompleted = storePurchaseCompleted,
                        )
                    }
                }
            }
        }

        val refreshedState = try {
            refreshStateAfterPurchase(
                current = current,
                receivedForceVersion = backendResult.successfulHttpForceVersion(),
            )
        } catch (cancellation: CancellationException) {
            // The backend purchase call already completed. Even if optional
            // reconciliation is cancelled, publish and persist at least any
            // monotonic forceVersion before preserving coroutine cancellation.
            withContext(NonCancellable) {
                commit(
                    operationGeneration = operationGeneration,
                    operation = OPERATION_PURCHASE,
                    next = current.afterPurchaseWithoutRefresh(
                        backendResult.successfulHttpForceVersion(),
                    ),
                    requestId = normalized.attemptId,
                )
            }
            throw cancellation
        }

        val failure = terminalError
        if (failure != null) {
            return commitPurchaseFailure(
                operationGeneration = operationGeneration,
                next = refreshedState,
                error = failure,
                attemptId = normalized.attemptId,
            )
        }
        return commitPurchase(
            operationGeneration = operationGeneration,
            next = refreshedState,
            purchase = requireNotNull(purchase),
            attemptId = normalized.attemptId,
        )
    }

    private suspend fun purchaseStoreV2Locked(
        current: InternalSessionState,
        sessionToken: String,
        request: NormalizedPurchaseRequest,
        selectedPackage: InappifyPackage?,
        evidence: PendingStorePurchaseEvidence,
        metadata: AppMetadata,
        operationGeneration: Long,
        marketKey: String?,
        recoveryState: PendingStoreRecoveryState,
    ): InappifyResult<InappifyPurchase> {
        val apiKey = current.apiKey
            ?: return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.INVALID_CONFIGURATION,
                    message = "The configured API key is unavailable.",
                    details = mapOf("operation" to OPERATION_PURCHASE),
                ),
            )
        val apiKeyFingerprint = current.apiKeyFingerprint
            ?: return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.INVALID_CONFIGURATION,
                    message = "The configured API identity is unavailable.",
                    details = mapOf("operation" to OPERATION_PURCHASE),
                ),
            )
        val customerBinding = current.customerBindingFingerprint()
            ?: return purchaseFailure(
                InappifyError(
                    code = InappifyErrorCode.INVALID_CONFIGURATION,
                    message = "The configured customer identity is unavailable.",
                    details = mapOf("operation" to OPERATION_PURCHASE),
                ),
            )
        val now = currentTimeMillis()
        val pending = PendingStoreOperation(
            id = request.attemptId,
            operation = PendingStoreOperationType.PURCHASE,
            store = STORE_BAZAAR,
            customerToken = sessionToken,
            customerIdentifierFingerprint = customerBinding,
            apiKeyFingerprint = apiKeyFingerprint,
            appIdentifier = metadata.packageIdentifier,
            appId = current.appId,
            productIdentifier = request.productIdentifier,
            offeringIdentifier = request.offeringIdentifier,
            productType = request.productType.toPendingStoreProductType(),
            evidence = evidence,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        if (
            recoveryState.rejectedEvidenceTombstones.contains(
                pending.toRejectedEvidenceTombstone(current),
            )
        ) {
            return purchaseFailure(
                permanentlyRejectedStoreEvidenceError(request.attemptId),
            )
        }
        val outcome = storeV2Coordinator.submit(
            operation = pending,
            context = current.storeV2Context(
                metadata = metadata,
                apiKey = apiKey,
                apiKeyFingerprint = apiKeyFingerprint,
                customerBinding = customerBinding,
                marketKey = marketKey,
            ),
        )
        return completeStoreV2PurchaseOutcome(
            current = current,
            outcome = outcome,
            operationGeneration = operationGeneration,
            packageIdentifier = selectedPackage?.identifier ?: request.packageIdentifier,
        )
    }

    private suspend fun completeStoreV2PurchaseOutcome(
        current: InternalSessionState,
        outcome: StoreV2Outcome,
        operationGeneration: Long,
        packageIdentifier: String? = outcome.operation.packageIdentifierFromPayload(),
    ): InappifyResult<InappifyPurchase> {
        finalizePermanentStoreEvidenceOutcome(outcome, current)
        val next = when (outcome) {
            is StoreV2Outcome.Terminal -> refreshStateAfterPurchase(
                current = current,
                receivedForceVersion = outcome.forceVersion,
            )

            is StoreV2Outcome.PendingDelivery,
            is StoreV2Outcome.Deferred,
            is StoreV2Outcome.Rejected,
            is StoreV2Outcome.Failure,
            -> current.afterPurchaseWithoutRefresh(outcome.forceVersion)
        }
        return when (outcome) {
            is StoreV2Outcome.Terminal -> commitPurchase(
                operationGeneration = operationGeneration,
                next = next,
                purchase = outcome.toPublicPurchase(packageIdentifier),
                attemptId = outcome.operation.id,
            )

            is StoreV2Outcome.PendingDelivery -> commitPurchase(
                operationGeneration = operationGeneration,
                next = next,
                purchase = outcome.toPublicPurchase(packageIdentifier),
                attemptId = outcome.operation.id,
            )

            is StoreV2Outcome.Deferred -> commitPurchaseFailure(
                operationGeneration = operationGeneration,
                next = next,
                error = outcome.error ?: InappifyError(
                    code = InappifyErrorCode.TIMEOUT,
                    message = "The store purchase is still being verified.",
                    isRetryable = true,
                    details = mapOf(
                        "operation" to OPERATION_PURCHASE,
                        "attemptId" to outcome.operation.id,
                        "store" to STORE_BAZAAR,
                        "outcomeMayHaveCommitted" to true,
                    ),
                ),
                attemptId = outcome.operation.id,
            )

            is StoreV2Outcome.Rejected -> commitPurchaseFailure(
                operationGeneration = operationGeneration,
                next = next,
                error = outcome.error,
                attemptId = outcome.operation.id,
            )

            is StoreV2Outcome.Failure -> commitPurchaseFailure(
                operationGeneration = operationGeneration,
                next = next,
                error = outcome.error,
                attemptId = outcome.operation.id,
            )
        }
    }

    private fun StoreV2Outcome.Terminal.toPublicPurchase(
        packageIdentifier: String?,
    ): InappifyPurchase = operation.toPublicPurchase(state, packageIdentifier)

    private fun StoreV2Outcome.PendingDelivery.toPublicPurchase(
        packageIdentifier: String?,
    ): InappifyPurchase = operation.toPublicPurchase(state, packageIdentifier)

    private fun PendingStoreOperation.toPublicPurchase(
        state: com.inappify.sdk.internal.network.StorePurchaseState,
        packageIdentifier: String?,
    ): InappifyPurchase = InappifyPurchase.storeResult(
        attemptId = id,
        productIdentifier = productIdentifier,
        offeringIdentifier = offeringIdentifier.orEmpty(),
        packageIdentifier = packageIdentifier,
        status = InappifyStorePurchaseStatus.fromServerValue(state.status.wireValue),
        deliveryId = state.deliveryId ?: deliveryId,
        verificationRequestId = state.verificationRequestId
            ?: verificationRequestId,
        alreadyProcessed = state.alreadyProcessed == true ||
            state.status == StorePurchaseStatus.ALREADY_PROCESSED,
    )

    private fun StorePurchase.toPendingEvidence(): PendingStorePurchaseEvidence =
        PendingStorePurchaseEvidence(
            purchaseToken = purchaseToken,
            orderId = orderIdentifier.takeIf(String::isNotEmpty),
            packageName = packageName.takeIf(String::isNotEmpty),
            developerPayload = developerPayload.takeIf(String::isNotEmpty),
            originalJson = originalJson.takeIf(String::isNotEmpty),
            signature = signature.takeIf(String::isNotEmpty),
            purchaseTimeMillis = purchaseTimeMillis,
        )

    private fun InappifyProductType?.toPendingStoreProductType(): PendingStoreProductType =
        when (this) {
            InappifyProductType.CONSUMABLE -> PendingStoreProductType.CONSUMABLE
            InappifyProductType.NON_CONSUMABLE -> PendingStoreProductType.NON_CONSUMABLE
            InappifyProductType.SUBSCRIPTION -> PendingStoreProductType.SUBSCRIPTION
            null -> PendingStoreProductType.LEGACY_IN_APP
        }

    private fun PendingStoreOperation.matchesRequestedProductType(
        requestedType: InappifyProductType?,
    ): Boolean = productType == requestedType.toPendingStoreProductType() ||
        // A server delivery response promotes an untyped IN_APP operation.
        // Retrying the original V1 request must resume that same receipt.
        (requestedType == null && productType == PendingStoreProductType.CONSUMABLE)

    private fun PendingStoreProductType.toPublicProductType(): InappifyProductType =
        when (this) {
            PendingStoreProductType.CONSUMABLE -> InappifyProductType.CONSUMABLE
            PendingStoreProductType.NON_CONSUMABLE -> InappifyProductType.NON_CONSUMABLE
            PendingStoreProductType.SUBSCRIPTION -> InappifyProductType.SUBSCRIPTION
            PendingStoreProductType.LEGACY_IN_APP -> InappifyProductType.NON_CONSUMABLE
        }

    private fun InternalSessionState.customerBindingFingerprint(): String? =
        appUserIdentifier
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.fingerprint()

    private fun PendingStoreOperation.matchesStoreV2Binding(
        current: InternalSessionState,
        metadata: AppMetadata,
    ): Boolean =
        apiKeyFingerprint == current.apiKeyFingerprint &&
            customerIdentifierFingerprint == current.customerBindingFingerprint() &&
            appIdentifier == metadata.packageIdentifier &&
            (appId == null || appId == current.appId)

    private fun InternalSessionState.storeV2Context(
        metadata: AppMetadata,
        apiKey: String = requireNotNull(this.apiKey),
        customerToken: String = requireNotNull(this.token),
        apiKeyFingerprint: String = requireNotNull(this.apiKeyFingerprint),
        customerBinding: String = requireNotNull(customerBindingFingerprint()),
        marketKey: String? = this.marketKey,
    ): StoreV2Context = StoreV2Context(
        apiKey = apiKey,
        customerToken = customerToken,
        apiKeyFingerprint = apiKeyFingerprint,
        customerIdentifierFingerprint = customerBinding,
        appIdentifier = metadata.packageIdentifier,
        appId = appId,
        country = requireNotNull(country),
        appVersion = requireNotNull(appVersion),
        forceVersion = forceVersion,
        marketKey = marketKey,
    )

    private fun PendingStoreOperation.packageIdentifierFromPayload(): String? =
        try {
            gson.fromJson(evidence.developerPayload, JsonObject::class.java)
                ?.normalizedString("nativePackageIdentifier")
        } catch (_: Exception) {
            null
        }

    private fun PendingStoreOperation.toRejectedEvidenceTombstone(
        current: InternalSessionState,
        forceVersion: Long? = current.forceVersion,
    ): RejectedStoreEvidenceTombstone = RejectedStoreEvidenceTombstone(
        purchaseTokenFingerprint = evidence.purchaseToken.fingerprint(),
        apiKeyFingerprint = apiKeyFingerprint,
        customerIdentifierFingerprint = customerIdentifierFingerprint,
        appFingerprint = fingerprintComponents(appIdentifier, appId),
        productIdentifierFingerprint = productIdentifier.fingerprint(),
        productTypeFingerprint = productType.name.fingerprint(),
        offeringIdentifierFingerprint = fingerprintComponents(offeringIdentifier),
        appVersionFingerprint = fingerprintComponents(current.appVersion),
        forceVersionFingerprint = fingerprintComponents(forceVersion),
        operationFingerprint = operation.name.fingerprint(),
        countryFingerprint = fingerprintComponents(current.country),
        evidenceFingerprint = fingerprintComponents(
            evidence.purchaseTimeMillis,
            evidence.orderId,
            evidence.packageName,
            evidence.developerPayload,
            evidence.originalJson,
            evidence.signature,
        ),
    )

    private suspend fun finalizePermanentStoreEvidenceOutcome(
        outcome: StoreV2Outcome,
        current: InternalSessionState,
    ) {
        val requiresTombstone = when (outcome) {
            is StoreV2Outcome.Rejected ->
                outcome.state?.status == StorePurchaseStatus.REJECTED ||
                    outcome.requiresRejectionTombstone
            is StoreV2Outcome.Failure -> outcome.requiresRejectionTombstone
            else -> false
        }
        if (requiresTombstone) {
            sessionStore.finalizePendingStoreOperationWithTombstone(
                operationId = outcome.operation.id,
                tombstone = outcome.operation.toRejectedEvidenceTombstone(
                    current = current,
                    forceVersion = maxNullable(
                        current.forceVersion,
                        outcome.forceVersion,
                    ),
                ),
            )
        }
    }

    private fun storeRecoveryUnavailableError(
        operation: String,
        attemptId: String? = null,
    ): InappifyError = InappifyError(
        code = InappifyErrorCode.STORE_UNAVAILABLE,
        message = "Encrypted store purchase recovery is temporarily unavailable.",
        isRetryable = true,
        details = buildMap {
            put("operation", operation)
            attemptId?.let { put("attemptId", it) }
            put("store", STORE_BAZAAR)
            put("outcomeMayHaveCommitted", false)
        },
    )

    private fun permanentlyRejectedStoreEvidenceError(
        attemptId: String,
    ): InappifyError = InappifyError(
        code = InappifyErrorCode.PURCHASE_VERIFICATION_FAILED,
        message = "The Inappify server previously rejected this store purchase.",
        details = mapOf(
            "operation" to OPERATION_PURCHASE,
            "attemptId" to attemptId,
            "store" to STORE_BAZAAR,
            "outcomeMayHaveCommitted" to true,
        ),
    )

    private suspend fun syncPendingConsumablesLocked(
        operationGeneration: Long,
        invokeHandler: Boolean,
    ): ConsumableReconciliation {
        val current = state.get()
        val route = current.resolvePurchaseRoute(current.market ?: InappifyMarket.NONE)
            ?: return ConsumableReconciliation.failure(
                snapshot = current.toSnapshot(),
                error = unsupportedStoreRouteError(OPERATION_SYNC_PENDING_CONSUMABLES),
            )
        return if (route.usesStoreV2) {
            val store = syncStoreV2Locked(
                operationGeneration = operationGeneration,
                explicitRestore = false,
                maxPolls = StoreV2Coordinator.MAX_BACKGROUND_POLLS,
            )
            val deliveries = reconcileBazaarDeliveriesLocked(
                purchases = store.purchases,
                operationGeneration = operationGeneration,
                invokeHandler = invokeHandler,
            )
            deliveries.withError(
                store.fatalError ?: store.partialError ?: deliveries.error,
            )
        } else if (route.market == InappifyMarket.NONE) {
            syncDirectConsumablesLocked(operationGeneration, invokeHandler)
        } else {
            ConsumableReconciliation.failure(
                snapshot = current.toSnapshot(),
                error = InappifyError(
                    code = InappifyErrorCode.UNSUPPORTED_OPERATION,
                    message = "Consumable fulfillment requires Direct Android or Store V2.",
                    details = mapOf("operation" to OPERATION_SYNC_PENDING_CONSUMABLES),
                ),
            )
        }
    }

    private suspend fun syncDirectConsumablesLocked(
        operationGeneration: Long,
        invokeHandler: Boolean,
    ): ConsumableReconciliation {
        val current = state.get()
        val sessionRequest = current.resourceRequestOrNull()
            ?: return ConsumableReconciliation.failure(
                snapshot = current.toSnapshot(),
                error = consumablePreconditionError(current),
            )
        val handler = consumableDeliveryHandler.get().takeIf { invokeHandler }
        val discovered = LinkedHashMap<Long, InappifyConsumableDelivery>()
        val pending = LinkedHashMap<Long, InappifyConsumableDelivery>()
        val purchases = LinkedHashMap<Long, InappifyPurchase>()
        val handled = mutableSetOf<Long>()
        var completedCount = 0
        var firstError: InappifyError? = null
        var batchCount = 0

        while (batchCount < MAX_DIRECT_DELIVERY_BATCHES) {
            batchCount += 1
            val result = callConsumableService {
                service.getPendingConsumableDeliveries(
                    PendingConsumableDeliveriesApiRequest(
                        apiKey = sessionRequest.apiKey,
                        token = sessionRequest.token,
                    ),
                )
            }
            val error = consumableServiceError(
                operation = OPERATION_SYNC_PENDING_CONSUMABLES,
                result = result,
                mutation = false,
            )
            if (error != null) {
                firstError = error
                break
            }
            result as ConsumableDeliveriesServiceResult.Response
            val batch = result.payload.deliveries
            if (batch.any { it.source != ConsumableDeliverySource.DIRECT }) {
                firstError = malformedConsumableError(
                    OPERATION_SYNC_PENDING_CONSUMABLES,
                    result.requestId,
                    false,
                )
                break
            }
            var completedThisBatch = 0
            var discoveredThisBatch = 0
            val batchDeliveryIds = mutableSetOf<Long>()
            for (backendDelivery in batch) {
                if (!batchDeliveryIds.add(backendDelivery.deliveryId)) {
                    firstError = malformedConsumableError(
                        OPERATION_SYNC_PENDING_CONSUMABLES,
                        result.requestId,
                        false,
                    )
                    break
                }
                val delivery = backendDelivery.toPublicDelivery()
                if (!discovered.containsKey(delivery.deliveryId)) {
                    discovered[delivery.deliveryId] = delivery
                    discoveredThisBatch += 1
                }
                when (backendDelivery.status) {
                    StorePurchaseStatus.COMPLETED -> {
                        if (backendDelivery.alreadyDelivered == false) {
                            firstError = malformedConsumableError(
                                OPERATION_SYNC_PENDING_CONSUMABLES,
                                result.requestId,
                                false,
                            )
                            break
                        }
                        purchases[delivery.deliveryId] = backendDelivery.toDirectPurchase()
                    }

                    StorePurchaseStatus.DELIVERY_REQUIRED -> {
                        if (backendDelivery.alreadyDelivered == true) {
                            firstError = malformedConsumableError(
                                OPERATION_SYNC_PENDING_CONSUMABLES,
                                result.requestId,
                                false,
                            )
                            break
                        }
                        purchases[delivery.deliveryId] = backendDelivery.toDirectPurchase()
                        if (handler == null || !handled.add(delivery.deliveryId)) {
                            pending[delivery.deliveryId] = delivery
                            continue
                        }
                        when (invokeDeliveryHandler(handler, delivery)) {
                            InappifyDeliveryResult.RETRY_LATER -> {
                                pending[delivery.deliveryId] = delivery
                            }

                            InappifyDeliveryResult.DELIVERED -> {
                                when (
                                    val confirmation = confirmDirectConsumableLocked(
                                        current = state.get(),
                                        sessionRequest = sessionRequest,
                                        deliveryId = delivery.deliveryId,
                                        operationGeneration = operationGeneration,
                                    )
                                ) {
                                    is InappifyResult.Success -> {
                                        purchases[delivery.deliveryId] = confirmation.data
                                        pending.remove(delivery.deliveryId)
                                        completedCount += 1
                                        completedThisBatch += 1
                                    }

                                    is InappifyResult.Failure -> {
                                        pending[delivery.deliveryId] = delivery
                                        if (firstError == null) firstError = confirmation.error
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        firstError = malformedConsumableError(
                            OPERATION_SYNC_PENDING_CONSUMABLES,
                            result.requestId,
                            false,
                        )
                        break
                    }
                }
            }
            if (firstError != null) break
            if (
                result.payload.deliveries.size < DIRECT_DELIVERY_BATCH_SIZE ||
                handler == null ||
                completedThisBatch == 0 ||
                discoveredThisBatch == 0
            ) {
                break
            }
        }

        val purchaseList = purchases.values.toList()
        val committed = if (purchaseList.isEmpty()) {
            null
        } else {
            commitSynchronizedPurchases(
                operationGeneration = operationGeneration,
                next = state.get(),
                purchases = purchaseList,
            )
        }
        if (committed is InappifyResult.Failure && firstError == null) {
            firstError = committed.error
        }
        return ConsumableReconciliation(
            discovered = discovered.values.toList(),
            completedCount = completedCount,
            pending = pending.values.toList(),
            purchases = purchaseList,
            snapshot = committed?.snapshot ?: state.get().toSnapshot(),
            error = firstError,
        )
    }

    private suspend fun reconcileBazaarDeliveriesLocked(
        purchases: List<InappifyPurchase>,
        operationGeneration: Long,
        invokeHandler: Boolean,
    ): ConsumableReconciliation {
        val handler = consumableDeliveryHandler.get().takeIf { invokeHandler }
        val deliveries = purchases.mapNotNull { purchase ->
            val deliveryId = purchase.deliveryId?.takeIf { it > 0L }
                ?: return@mapNotNull null
            if (purchase.storePurchaseStatus != InappifyStorePurchaseStatus.DELIVERY_REQUIRED) {
                return@mapNotNull null
            }
            InappifyConsumableDelivery(
                deliveryId = deliveryId,
                productIdentifier = purchase.productIdentifier,
                transactionIdentifier = null,
                source = InappifyDeliverySource.BAZAAR,
            ) to purchase
        }
        if (handler == null) {
            val pending = deliveries.map { it.first }
            return ConsumableReconciliation(
                discovered = pending,
                completedCount = 0,
                pending = pending,
                purchases = purchases,
                snapshot = state.get().toSnapshot(),
                error = null,
            )
        }

        val pending = mutableListOf<InappifyConsumableDelivery>()
        val finalPurchases = purchases.associateByTo(
            LinkedHashMap(),
            InappifyPurchase::attemptId,
        )
        var completedCount = 0
        var firstError: InappifyError? = null
        for ((delivery, purchase) in deliveries) {
            when (invokeDeliveryHandler(handler, delivery)) {
                InappifyDeliveryResult.RETRY_LATER -> pending += delivery
                InappifyDeliveryResult.DELIVERED -> {
                    when (
                        val confirmation = confirmDeliveryLocked(
                            deliveryId = delivery.deliveryId,
                            operationGeneration = operationGeneration,
                        )
                    ) {
                        is InappifyResult.Success -> {
                            finalPurchases[purchase.attemptId] = confirmation.data
                            completedCount += 1
                        }

                        is InappifyResult.Failure -> {
                            pending += delivery
                            if (firstError == null) firstError = confirmation.error
                        }
                    }
                }
            }
        }
        return ConsumableReconciliation(
            discovered = deliveries.map { it.first },
            completedCount = completedCount,
            pending = pending,
            purchases = finalPurchases.values.toList(),
            snapshot = state.get().toSnapshot(),
            error = firstError,
        )
    }

    private suspend fun confirmDirectConsumableLocked(
        current: InternalSessionState,
        sessionRequest: ResourceApiRequest,
        deliveryId: Long,
        operationGeneration: Long,
    ): InappifyResult<InappifyPurchase> {
        val result = callConsumableService {
            service.markDirectConsumableDelivered(
                DirectConsumableDeliveryApiRequest(
                    apiKey = sessionRequest.apiKey,
                    token = sessionRequest.token,
                    deliveryId = deliveryId,
                ),
            )
        }
        val error = consumableServiceError(
            operation = OPERATION_CONFIRM_DELIVERY,
            result = result,
            mutation = true,
        )
        if (error != null) return purchaseFailure(error)
        result as ConsumableDeliveriesServiceResult.Response
        val delivery = result.payload.deliveries.singleOrNull()
        if (
            delivery == null ||
            delivery.deliveryId != deliveryId ||
            delivery.source != ConsumableDeliverySource.DIRECT ||
            delivery.status != StorePurchaseStatus.COMPLETED ||
            delivery.alreadyDelivered == false
        ) {
            return purchaseFailure(
                malformedConsumableError(
                    operation = OPERATION_CONFIRM_DELIVERY,
                    requestId = result.requestId,
                    outcomeMayHaveCommitted = true,
                ),
            )
        }
        val purchase = delivery.toDirectPurchase()
        return commitPurchase(
            operationGeneration = operationGeneration,
            next = current,
            purchase = purchase,
            attemptId = purchase.attemptId,
        )
    }

    private suspend fun invokeDeliveryHandler(
        handler: InappifyConsumableDeliveryHandler,
        delivery: InappifyConsumableDelivery,
    ): InappifyDeliveryResult = withContext(Dispatchers.IO) {
        try {
            handler.deliver(delivery)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            InappifyDeliveryResult.RETRY_LATER
        }
    }

    private suspend fun callConsumableService(
        block: suspend () -> ConsumableDeliveriesServiceResult,
    ): ConsumableDeliveriesServiceResult {
        var attempt = 0
        while (true) {
            val result = try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                ConsumableDeliveriesServiceResult.Failure(ServiceFailureKind.UNKNOWN)
            }
            if (attempt >= MAX_CONSUMABLE_HTTP_RETRIES || !result.isTransientFailure()) {
                return result
            }
            val retryAfterMillis = (result as? ConsumableDeliveriesServiceResult.Response)
                ?.retryAfterSeconds
                ?.coerceIn(0L, (Long.MAX_VALUE - CONSUMABLE_RETRY_JITTER_MILLIS) / 1_000L)
                ?.times(1_000L)
            val exponentialMillis = (CONSUMABLE_RETRY_BASE_MILLIS shl attempt)
                .coerceAtMost(CONSUMABLE_RETRY_EXPONENTIAL_CAP_MILLIS)
            val jitterMillis = currentTimeMillis().mod(CONSUMABLE_RETRY_JITTER_MILLIS + 1L)
            consumableRetrySleep(
                maxOf(retryAfterMillis ?: 0L, exponentialMillis).plus(jitterMillis),
            )
            attempt += 1
        }
    }

    private fun ConsumableDeliveriesServiceResult.isTransientFailure(): Boolean = when (this) {
        is ConsumableDeliveriesServiceResult.Failure ->
            kind == ServiceFailureKind.NETWORK || kind == ServiceFailureKind.TIMEOUT

        is ConsumableDeliveriesServiceResult.Response ->
            statusCode == 408 || statusCode == 429 || statusCode == 500 || statusCode == 503
    }

    private fun consumableServiceError(
        operation: String,
        result: ConsumableDeliveriesServiceResult,
        mutation: Boolean,
    ): InappifyError? = when (result) {
        is ConsumableDeliveriesServiceResult.Failure -> {
            val code = when (result.kind) {
                ServiceFailureKind.NETWORK -> InappifyErrorCode.NETWORK
                ServiceFailureKind.TIMEOUT -> InappifyErrorCode.TIMEOUT
                ServiceFailureKind.CANCELLED -> InappifyErrorCode.REQUEST_CANCELLED
                ServiceFailureKind.MALFORMED_RESPONSE -> InappifyErrorCode.MALFORMED_RESPONSE
                ServiceFailureKind.UNKNOWN -> InappifyErrorCode.UNKNOWN
            }
            InappifyError(
                code = code,
                message = defaultMessage(operation, code),
                isRetryable = code.isTransient(),
                details = mapOf(
                    "operation" to operation,
                    "outcomeMayHaveCommitted" to mutation,
                ),
            )
        }

        is ConsumableDeliveriesServiceResult.Response -> when {
            result.statusCode != HTTP_OK -> {
                val code = result.statusCode.toErrorCode()
                val outcomeMayHaveCommitted = mutation &&
                    (result.statusCode == 408 || result.statusCode >= 500)
                InappifyError(
                    code = code,
                    message = defaultMessage(operation, code),
                    isRetryable = code.isTransient(),
                    details = responseDetails(
                        operation = operation,
                        statusCode = result.statusCode,
                        requestId = result.requestId,
                        backendCode = result.payload.errorCode,
                        outcomeMayHaveCommitted = outcomeMayHaveCommitted,
                    ),
                )
            }

            result.payload.status != true -> {
                val code = result.payload.errorCode.toErrorCode()
                InappifyError(
                    code = code,
                    message = defaultMessage(operation, code),
                    isRetryable = code.isTransient(),
                    details = responseDetails(
                        operation = operation,
                        statusCode = result.statusCode,
                        requestId = result.requestId,
                        backendCode = result.payload.errorCode,
                        outcomeMayHaveCommitted = false,
                    ),
                )
            }

            else -> null
        }
    }

    private fun malformedConsumableError(
        operation: String,
        requestId: String?,
        outcomeMayHaveCommitted: Boolean,
    ): InappifyError = InappifyError(
        code = InappifyErrorCode.MALFORMED_RESPONSE,
        message = "The Inappify consumable-delivery response was invalid.",
        details = responseDetails(
            operation = operation,
            statusCode = HTTP_OK,
            requestId = requestId,
            backendCode = null,
            outcomeMayHaveCommitted = outcomeMayHaveCommitted,
        ),
    )

    private fun consumablePreconditionError(
        current: InternalSessionState,
    ): InappifyError = InappifyError(
        code = if (current.isConfigured) {
            InappifyErrorCode.UNAUTHORIZED
        } else {
            InappifyErrorCode.NOT_CONFIGURED
        },
        message = if (current.isConfigured) {
            "The current session is not authorized to sync consumable deliveries."
        } else {
            "The client must be configured before syncing consumable deliveries."
        },
        details = mapOf("operation" to OPERATION_SYNC_PENDING_CONSUMABLES),
    )

    private fun BackendConsumableDelivery.toPublicDelivery():
        InappifyConsumableDelivery = InappifyConsumableDelivery(
        deliveryId = deliveryId,
        productIdentifier = productIdentifier,
        transactionIdentifier = transactionId,
        source = when (source) {
            ConsumableDeliverySource.DIRECT -> InappifyDeliverySource.DIRECT
            ConsumableDeliverySource.BAZAAR -> InappifyDeliverySource.BAZAAR
            ConsumableDeliverySource.MYKET -> InappifyDeliverySource.MYKET
        },
    )

    private fun BackendConsumableDelivery.toDirectPurchase(): InappifyPurchase =
        InappifyPurchase.directDeliveryResult(
            deliveryId = deliveryId,
            productIdentifier = productIdentifier,
            status = when (status) {
                StorePurchaseStatus.COMPLETED -> InappifyStorePurchaseStatus.COMPLETED
                else -> InappifyStorePurchaseStatus.DELIVERY_REQUIRED
            },
        )

    private class ConsumableReconciliation(
        val discovered: List<InappifyConsumableDelivery>,
        val completedCount: Int,
        val pending: List<InappifyConsumableDelivery>,
        val purchases: List<InappifyPurchase>,
        val snapshot: InappifySnapshot,
        val error: InappifyError?,
    ) {
        fun withError(value: InappifyError?): ConsumableReconciliation =
            ConsumableReconciliation(
                discovered = discovered,
                completedCount = completedCount,
                pending = pending,
                purchases = purchases,
                snapshot = snapshot,
                error = value,
            )

        fun toPublicResult(): InappifyResult<InappifyConsumableSyncResult> =
            if (error == null) {
                InappifyResult.Success(
                    data = InappifyConsumableSyncResult(
                        discoveredCount = discovered.size,
                        completedCount = completedCount,
                        pendingDeliveries = pending,
                    ),
                    snapshot = snapshot,
                )
            } else {
                InappifyResult.Failure(requireNotNull(error), snapshot)
            }

        fun toLegacyResult(): InappifyResult<List<InappifyPurchase>> =
            if (error == null) {
                InappifyResult.Success(purchases, snapshot)
            } else {
                InappifyResult.Failure(requireNotNull(error), snapshot)
            }

        companion object {
            fun failure(
                snapshot: InappifySnapshot,
                error: InappifyError,
            ): ConsumableReconciliation = ConsumableReconciliation(
                discovered = emptyList(),
                completedCount = 0,
                pending = emptyList(),
                purchases = emptyList(),
                snapshot = snapshot,
                error = error,
            )
        }
    }

    private suspend fun syncStoreV2Locked(
        operationGeneration: Long,
        explicitRestore: Boolean,
        maxPolls: Int,
    ): StoreV2Reconciliation {
        val current = state.get()
        val sessionRequest = current.resourceRequestOrNull()
            ?: return StoreV2Reconciliation.failure(
                snapshot = current.toSnapshot(),
                error = InappifyError(
                    code = if (current.isConfigured) {
                        InappifyErrorCode.UNAUTHORIZED
                    } else {
                        InappifyErrorCode.NOT_CONFIGURED
                    },
                    message = "The current session cannot restore store purchases.",
                    details = mapOf("operation" to OPERATION_RESTORE_PURCHASES),
                ),
            )
        val route = current.resolvePurchaseRoute(current.market ?: InappifyMarket.NONE)
        if (route?.usesStoreV2 != true) {
            return StoreV2Reconciliation.failure(
                snapshot = current.toSnapshot(),
                error = InappifyError(
                    code = InappifyErrorCode.UNSUPPORTED_OPERATION,
                    message = "Store V2 reconciliation is unavailable for this app.",
                    details = mapOf("operation" to OPERATION_RESTORE_PURCHASES),
                ),
            )
        }
        val metadata = try {
            metadataProvider.get()
        } catch (_: Exception) {
            return StoreV2Reconciliation.failure(
                snapshot = current.toSnapshot(),
                error = InappifyError(
                    code = InappifyErrorCode.INVALID_CONFIGURATION,
                    message = "Host application metadata is unavailable.",
                    details = mapOf("operation" to OPERATION_RESTORE_PURCHASES),
                ),
            )
        }
        val apiKeyFingerprint = current.apiKeyFingerprint
        val customerBinding = current.customerBindingFingerprint()
        if (apiKeyFingerprint == null || customerBinding == null) {
            return StoreV2Reconciliation.failure(
                snapshot = current.toSnapshot(),
                error = InappifyError(
                    code = InappifyErrorCode.INVALID_CONFIGURATION,
                    message = "The store recovery identity is unavailable.",
                    details = mapOf("operation" to OPERATION_RESTORE_PURCHASES),
                ),
            )
        }
        val context = current.storeV2Context(
            metadata = metadata,
            apiKey = sessionRequest.apiKey,
            apiKeyFingerprint = apiKeyFingerprint,
            customerBinding = customerBinding,
        )
        val purchases = mutableListOf<InappifyPurchase>()
        var restoredCount = 0
        var alreadyProcessedCount = 0
        var failedCount = 0
        var receivedForceVersion: Long? = null
        var firstError: InappifyError? = null

        suspend fun record(outcome: StoreV2Outcome) {
            finalizePermanentStoreEvidenceOutcome(outcome, current)
            receivedForceVersion = maxNullable(
                receivedForceVersion,
                outcome.forceVersion,
            )
            val countsTowardsExplicitRestore =
                explicitRestore &&
                    outcome.operation.productType != PendingStoreProductType.CONSUMABLE
            when (outcome) {
                is StoreV2Outcome.Terminal -> {
                    purchases += outcome.toPublicPurchase(
                        outcome.operation.packageIdentifierFromPayload(),
                    )
                    if (countsTowardsExplicitRestore) {
                        if (
                            outcome.state.status == StorePurchaseStatus.ALREADY_PROCESSED ||
                            outcome.state.alreadyProcessed == true
                        ) {
                            alreadyProcessedCount += 1
                        } else {
                            restoredCount += 1
                        }
                    }
                }

                is StoreV2Outcome.PendingDelivery -> {
                    purchases += outcome.toPublicPurchase(
                        outcome.operation.packageIdentifierFromPayload(),
                    )
                    if (countsTowardsExplicitRestore) failedCount += 1
                }

                is StoreV2Outcome.Deferred -> {
                    if (countsTowardsExplicitRestore) failedCount += 1
                    if (firstError == null) firstError = outcome.error
                }

                is StoreV2Outcome.Rejected -> {
                    if (countsTowardsExplicitRestore) failedCount += 1
                    if (firstError == null) firstError = outcome.error
                }

                is StoreV2Outcome.Failure -> {
                    if (countsTowardsExplicitRestore) failedCount += 1
                    if (firstError == null) firstError = outcome.error
                }
            }
        }

        val recoveryState = sessionStore.loadPendingStoreRecoveryState()
            ?: return StoreV2Reconciliation.failure(
                snapshot = current.toSnapshot(),
                error = storeRecoveryUnavailableError(
                    if (explicitRestore) {
                        OPERATION_RESTORE_PURCHASES
                    } else {
                        OPERATION_SYNC_PURCHASES
                    },
                ),
            )
        val matchingRecoveryOperations = recoveryState.operations
            .filter { operation ->
                operation.store.equals(STORE_BAZAAR, ignoreCase = true) &&
                    operation.matchesStoreV2Binding(current, metadata)
            }
        val pending = matchingRecoveryOperations
        val existingRejectedEvidenceTombstones =
            recoveryState.rejectedEvidenceTombstones.toSet()
        for (operation in pending) {
            if (
                operation.toRejectedEvidenceTombstone(current) in
                existingRejectedEvidenceTombstones
            ) {
                sessionStore.removePendingStoreOperation(operation.id)
                if (
                    explicitRestore &&
                    operation.productType != PendingStoreProductType.CONSUMABLE
                ) {
                    failedCount += 1
                }
                continue
            }
            record(
                storeV2Coordinator.resume(
                    operation = operation,
                    context = context,
                    maxPolls = maxPolls,
                ),
            )
        }

        val queuedTokens = matchingRecoveryOperations
            .asSequence()
            .map { operation -> operation.evidence.purchaseToken }
            .toMutableSet()
        val rejectedEvidenceTombstones = existingRejectedEvidenceTombstones
        val owned = mutableListOf<Pair<StorePurchase, InappifyProductType>>()
        var queryFailureCount = 0
        var queryError: InappifyError? = null
        listOf(
            StoreProductType.SUBSCRIPTION to InappifyProductType.SUBSCRIPTION,
            StoreProductType.IN_APP to InappifyProductType.NON_CONSUMABLE,
        ).forEach { (storeType, fallbackType) ->
            when (
                val queried = queryStorePurchases(
                    current = current,
                    productType = storeType,
                    market = InappifyMarket.BAZAAR,
                    queryMode = StorePurchaseQueryMode.PARTIAL,
                )
            ) {
                is StorePurchaseQueryResult.Success -> {
                    if (explicitRestore) {
                        failedCount += queried.invalidPurchaseCount
                    }
                    queried.purchases.forEach {
                        owned += it to fallbackType
                    }
                }

                is StorePurchaseQueryResult.Failure -> {
                    queryFailureCount += 1
                    if (queryError == null) queryError = queried.error.toPublicSyncError()
                }
            }
        }

        for ((storePurchase, fallbackType) in owned) {
            if (
                storePurchase.packageName != metadata.packageIdentifier ||
                !queuedTokens.add(storePurchase.purchaseToken)
            ) {
                continue
            }
            val normalized = storePurchase.toStoreV2RestoreRequest(
                current = current,
                fallbackProductType = fallbackType,
            )
            if (normalized == null) {
                if (explicitRestore) failedCount += 1
                continue
            }
            if (explicitRestore && normalized.productType == InappifyProductType.CONSUMABLE) {
                failedCount += 1
                continue
            }
            val isUntypedLegacyInApp =
                !explicitRestore &&
                    fallbackType == InappifyProductType.NON_CONSUMABLE &&
                    !storePurchase.hasExplicitProductType()
            val operationType = if (
                normalized.productType == InappifyProductType.CONSUMABLE ||
                isUntypedLegacyInApp
            ) {
                PendingStoreOperationType.PURCHASE
            } else {
                PendingStoreOperationType.RESTORE
            }
            val now = currentTimeMillis()
            val operation = PendingStoreOperation(
                id = normalized.attemptId,
                operation = operationType,
                store = STORE_BAZAAR,
                customerToken = sessionRequest.token,
                customerIdentifierFingerprint = customerBinding,
                apiKeyFingerprint = apiKeyFingerprint,
                appIdentifier = metadata.packageIdentifier,
                appId = current.appId,
                productIdentifier = normalized.productIdentifier,
                offeringIdentifier = normalized.offeringIdentifier,
                productType = if (isUntypedLegacyInApp) {
                    PendingStoreProductType.LEGACY_IN_APP
                } else {
                    normalized.productType.toPendingStoreProductType()
                },
                evidence = storePurchase.toPendingEvidence(),
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
            if (
                operation.toRejectedEvidenceTombstone(current) in
                rejectedEvidenceTombstones
            ) {
                if (explicitRestore) failedCount += 1
                continue
            }
            record(
                storeV2Coordinator.submit(
                    operation = operation,
                    context = context,
                    maxPolls = maxPolls,
                ),
            )
        }

        if (queryFailureCount == 2 && owned.isEmpty() && purchases.isEmpty()) {
            return StoreV2Reconciliation.failure(
                snapshot = current.toSnapshot(),
                error = requireNotNull(queryError),
            )
        }
        if (queryFailureCount > 0 && explicitRestore) {
            failedCount += queryFailureCount
        }

        val refreshed = refreshStateAfterPurchase(
            current = current,
            receivedForceVersion = receivedForceVersion,
        )
        val committed = if (purchases.isEmpty()) {
            commit(
                operationGeneration = operationGeneration,
                operation = if (explicitRestore) {
                    OPERATION_RESTORE_PURCHASES
                } else {
                    OPERATION_SYNC_PURCHASES
                },
                next = refreshed,
            )
        } else {
            commitSynchronizedPurchases(
                operationGeneration = operationGeneration,
                next = refreshed,
                purchases = purchases,
            )
        }
        val snapshot = committed.snapshot ?: state.get().toSnapshot()
        val commitError = (committed as? InappifyResult.Failure)?.error
        return StoreV2Reconciliation(
            purchases = purchases.toList(),
            restoredCount = restoredCount,
            alreadyProcessedCount = alreadyProcessedCount,
            failedCount = failedCount,
            snapshot = snapshot,
            fatalError = commitError,
            partialError = firstError ?: queryError,
        )
    }

    private fun maxNullable(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

    private class StoreV2Reconciliation(
        val purchases: List<InappifyPurchase>,
        val restoredCount: Int,
        val alreadyProcessedCount: Int,
        val failedCount: Int,
        val snapshot: InappifySnapshot,
        val fatalError: InappifyError?,
        val partialError: InappifyError?,
    ) {
        fun toLegacyResult(): InappifyResult<List<InappifyPurchase>> {
            val error = fatalError ?: partialError
            return if (error == null) {
                InappifyResult.Success(purchases, snapshot)
            } else {
                InappifyResult.Failure(
                    error = InappifyError(
                        code = error.code,
                        message = error.message,
                        isRetryable = error.isRetryable,
                        details = error.details + mapOf(
                            "recoveredCount" to purchases.size,
                        ),
                    ),
                    snapshot = snapshot,
                )
            }
        }

        fun toRestoreResult(): InappifyResult<InappifyRestoreResult> {
            val error = fatalError
            return if (error == null) {
                InappifyResult.Success(
                    data = InappifyRestoreResult(
                        restoredCount = restoredCount,
                        alreadyProcessedCount = alreadyProcessedCount,
                        failedCount = failedCount,
                    ),
                    snapshot = snapshot,
                )
            } else {
                InappifyResult.Failure(error, snapshot)
            }
        }

        companion object {
            fun failure(
                snapshot: InappifySnapshot,
                error: InappifyError,
            ): StoreV2Reconciliation = StoreV2Reconciliation(
                purchases = emptyList(),
                restoredCount = 0,
                alreadyProcessedCount = 0,
                failedCount = 0,
                snapshot = snapshot,
                fatalError = error,
                partialError = null,
            )
        }
    }

    private suspend fun syncPurchasesLocked(
        operationGeneration: Long,
        market: InappifyMarket,
    ): InappifyResult<List<InappifyPurchase>> {
        val current = state.get()
        val sessionRequest = current.resourceRequestOrNull()
            ?: return purchaseSyncPreconditionFailure(current)

        if (market == InappifyMarket.NONE) {
            return InappifyResult.Success(emptyList(), current.toSnapshot())
        }
        val metadata = try {
            metadataProvider.get()
        } catch (_: Exception) {
            return purchaseSyncFailure(
                InappifyError(
                    code = InappifyErrorCode.INVALID_CONFIGURATION,
                    message = "Host application metadata is unavailable.",
                    details = mapOf("operation" to OPERATION_SYNC_PURCHASES),
                ),
            )
        }
        val expectedRecoveryBinding = current.purchaseRecoveryBinding()
            ?: return purchaseSyncFailure(
                InappifyError(
                    code = InappifyErrorCode.INVALID_CONFIGURATION,
                    message = "The purchase recovery identity is unavailable.",
                    details = mapOf("operation" to OPERATION_SYNC_PURCHASES),
                ),
            )
        val queryResult = queryStorePurchases(current)
        val storePurchases = when (queryResult) {
            is StorePurchaseQueryResult.Success -> queryResult.purchases
            is StorePurchaseQueryResult.Failure -> {
                return purchaseSyncFailure(queryResult.error.toPublicSyncError())
            }
        }

        val recovered = mutableListOf<InappifyPurchase>()
        var receivedForceVersion: Long? = null
        var pendingFailure: InappifyError? = null
        try {
            for (storePurchase in storePurchases) {
                if (
                    storePurchase.packageName != metadata.packageIdentifier ||
                    current.hasVerifiedStorePurchase(storePurchase)
                ) {
                    continue
                }
                val normalized = storePurchase.toRecoveredPurchaseRequest(
                    expectedRecoveryBinding = expectedRecoveryBinding,
                ) ?: continue

                val backendResult = callService {
                    service.purchase(
                        PurchaseApiRequest(
                            apiKey = sessionRequest.apiKey,
                            token = sessionRequest.token,
                            appIdentifier = metadata.packageIdentifier,
                            country = requireNotNull(current.country),
                            productIdentifier = normalized.productIdentifier,
                            offeringIdentifier = normalized.offeringIdentifier,
                            purchaseTokenId = storePurchase.purchaseToken,
                            discount = normalized.discount,
                            isCrypto = normalized.isCrypto,
                            forceVersion = current.forceVersion,
                            appVersion = requireNotNull(current.appVersion),
                            purchaseStoreTime = storePurchase.purchaseTimeMillis,
                        ),
                    )
                }
                val evaluation = evaluate(
                    operation = OPERATION_PURCHASE,
                    result = backendResult,
                    mutation = true,
                )
                if (evaluation is Evaluation.Failure) {
                    if (pendingFailure == null) pendingFailure = evaluation.error
                    if (
                        evaluation.error.code ==
                        InappifyErrorCode.PURCHASE_VERIFICATION_FAILED
                    ) {
                        continue
                    }
                    break
                }
                evaluation as Evaluation.Success
                val backendPurchase = evaluation.payload.purchase
                if (backendPurchase == null) {
                    pendingFailure = malformedError(
                        operation = OPERATION_PURCHASE,
                        requestId = evaluation.requestId,
                        outcomeMayHaveCommitted = true,
                    )
                    break
                }
                val purchaseStatus = try {
                    InappifyPurchaseStatus.fromServerValue(
                        backendPurchase.purchaseStatus,
                    )
                } catch (_: IllegalArgumentException) {
                    pendingFailure = malformedError(
                        operation = OPERATION_PURCHASE,
                        requestId = evaluation.requestId,
                        outcomeMayHaveCommitted = true,
                    )
                    break
                }

                receivedForceVersion = maxOf(
                    receivedForceVersion ?: current.forceVersion ?: 1L,
                    evaluation.payload.forceVersion ?: current.forceVersion ?: 1L,
                )
                recovered += InappifyPurchase(
                    attemptId = normalized.attemptId,
                    productIdentifier = normalized.productIdentifier,
                    offeringIdentifier = normalized.offeringIdentifier,
                    packageIdentifier = normalized.packageIdentifier,
                    market = market,
                    purchaseStatus = purchaseStatus,
                    url = backendPurchase.url,
                    checkoutId = backendPurchase.checkoutId,
                    checkoutStatus = backendPurchase.checkoutStatus,
                    nextActionType = backendPurchase.nextActionType,
                )
            }
        } catch (cancellation: CancellationException) {
            if (recovered.isNotEmpty()) {
                withContext(NonCancellable) {
                    commitSynchronizedPurchases(
                        operationGeneration = operationGeneration,
                        next = current.afterPurchaseWithoutRefresh(receivedForceVersion),
                        purchases = recovered,
                    )
                }
            }
            throw cancellation
        }

        if (recovered.isEmpty()) {
            if (pendingFailure != null) {
                return purchaseSyncFailure(
                    requireNotNull(pendingFailure).withSyncPurchaseContext(0),
                )
            }
            return InappifyResult.Success(emptyList(), current.toSnapshot())
        }

        val refreshedState = try {
            refreshStateAfterPurchase(
                current = current,
                receivedForceVersion = receivedForceVersion,
            )
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                commitSynchronizedPurchases(
                    operationGeneration = operationGeneration,
                    next = current.afterPurchaseWithoutRefresh(receivedForceVersion),
                    purchases = recovered,
                )
            }
            throw cancellation
        }
        val committed = commitSynchronizedPurchases(
            operationGeneration = operationGeneration,
            next = refreshedState,
            purchases = recovered,
        )
        if (pendingFailure != null && committed is InappifyResult.Success) {
            return InappifyResult.Failure(
                error = requireNotNull(pendingFailure).withSyncPurchaseContext(
                    recovered.size,
                ),
                snapshot = committed.snapshot,
            )
        }
        return committed
    }

    private fun normalizePurchaseRequest(
        request: InappifyPurchaseRequest,
    ): NormalizedPurchaseRequest? {
        if (
            request.productIdentifier.isEmpty() ||
            request.offeringIdentifier.isEmpty() ||
            request.apiKey?.isEmpty() == true
        ) {
            return null
        }
        val attemptId = if (request.idempotencyKey == null) {
            "purchase-${UUID.randomUUID()}"
        } else {
            request.idempotencyKey.safePurchaseAttemptId() ?: return null
        }
        return NormalizedPurchaseRequest(
            productIdentifier = request.productIdentifier,
            offeringIdentifier = request.offeringIdentifier,
            packageIdentifier = request.packageIdentifier,
            attemptId = attemptId,
            apiKey = request.apiKey,
            country = request.country,
            appVersion = request.appVersion,
            discount = request.discount,
            isCrypto = request.isCrypto,
            market = request.market,
            marketKey = request.marketKey,
            isLostPurchase = request.isLostPurchase,
            lostPurchaseToken = request.lostPurchaseToken,
            lostPurchaseTime = request.lostPurchaseTime,
            dynamicPriceToken = request.dynamicPriceToken
                ?.takeIf(String::isNotBlank),
            productType = request.productType,
        )
    }

    private fun InternalSessionState.findPurchasePackage(
        request: NormalizedPurchaseRequest,
    ): InappifyPackage? {
        val offering = offerings
            ?.offerings
            ?.firstOrNull { it.identifier == request.offeringIdentifier }
            ?: return null
        // Select the first package containing the requested product.
        // packageIdentifier is transport and result metadata; it does not
        // alter product selection.
        return offering.packages
            ?.firstOrNull { candidate ->
                candidate.product?.identifier == request.productIdentifier
            }
    }

    private fun InappifyPackage.isTrial(): Boolean =
        (product?.trialDays ?: 0L) > 0L

    private suspend fun purchaseFromStore(
        activity: Activity,
        current: InternalSessionState,
        request: NormalizedPurchaseRequest,
        market: InappifyMarket,
        marketKey: String?,
        selectedPackage: InappifyPackage,
        expectedPackageName: String,
        usesStoreV2: Boolean,
    ): StoreBillingResult {
        val recoveryBinding = current.purchaseRecoveryBinding()
            ?: return StoreBillingResult.Failure(
                StoreBillingError(
                    code = StoreBillingErrorCode.INVALID_REQUEST,
                    message = "The purchase recovery identity is unavailable.",
                ),
            )
        val adapter = try {
            storeBillingAdapterFactory.create(
                market = market,
                marketKey = marketKey,
            )
        } catch (_: Exception) {
            return StoreBillingResult.Failure(
                StoreBillingError(
                    code = StoreBillingErrorCode.CONNECTION_FAILED,
                    message = "The native billing adapter is unavailable.",
                    isRetryable = true,
                ),
            )
        }
        if (!activeStoreAdapter.compareAndSet(null, adapter)) {
            try {
                adapter.close()
            } catch (_: Exception) {
                // The unused adapter has no recoverable cleanup operation.
            }
            return StoreBillingResult.Failure(
                StoreBillingError(
                    code = StoreBillingErrorCode.PURCHASE_IN_PROGRESS,
                    message = "Another native purchase is already in progress.",
                    isRetryable = true,
                ),
            )
        }

        return try {
            val result = try {
                withTimeout(STORE_PURCHASE_TIMEOUT_MILLIS) {
                    adapter.purchase(
                        uiHost = StoreUiHost.from(activity),
                        request = StorePurchaseRequest(
                            productIdentifier = request.productIdentifier,
                            productType = when (request.productType) {
                                InappifyProductType.SUBSCRIPTION ->
                                    StoreProductType.SUBSCRIPTION
                                InappifyProductType.CONSUMABLE,
                                InappifyProductType.NON_CONSUMABLE,
                                null,
                                -> StoreProductType.IN_APP
                            },
                            developerPayload = purchaseDeveloperPayload(
                                request = request,
                                selectedPackage = selectedPackage,
                                recoveryBinding = recoveryBinding,
                                usesStoreV2 = usesStoreV2,
                            ),
                            dynamicPriceToken = request.dynamicPriceToken,
                        ),
                    )
                }
            } catch (_: TimeoutCancellationException) {
                StoreBillingResult.Failure(
                    StoreBillingError(
                        code = StoreBillingErrorCode.OPERATION_TIMEOUT,
                        message = "The native marketplace purchase timed out.",
                        isRetryable = false,
                    ),
                )
            }
            if (result is StoreBillingResult.Success) {
                when {
                    result.purchase.productIdentifier != request.productIdentifier ->
                        StoreBillingResult.Failure(
                            StoreBillingError(
                                code = StoreBillingErrorCode.PRODUCT_MISMATCH,
                                message = "The store returned a different product.",
                            ),
                        )

                    result.purchase.packageName != expectedPackageName ->
                        StoreBillingResult.Failure(
                            StoreBillingError(
                                code = StoreBillingErrorCode.PACKAGE_MISMATCH,
                                message = "The store returned a different application package.",
                            ),
                        )

                    else -> result
                }
            } else {
                result
            }
        } finally {
            if (activeStoreAdapter.compareAndSet(adapter, null)) {
                try {
                    adapter.close()
                } catch (_: Exception) {
                    // A completed purchase cannot safely retry adapter cleanup.
                }
            }
        }
    }

    private suspend fun queryStorePurchases(
        current: InternalSessionState,
        productType: StoreProductType = StoreProductType.IN_APP,
        market: InappifyMarket = requireNotNull(current.market),
        queryMode: StorePurchaseQueryMode = StorePurchaseQueryMode.STRICT,
    ): StorePurchaseQueryResult {
        val adapter = try {
            storeBillingAdapterFactory.create(
                market = market,
                marketKey = current.marketKey,
            )
        } catch (_: Exception) {
            return StorePurchaseQueryResult.Failure(
                StoreBillingError(
                    code = StoreBillingErrorCode.CONNECTION_FAILED,
                    message = "The native billing adapter is unavailable.",
                    isRetryable = true,
                ),
            )
        }
        if (!activeStoreAdapter.compareAndSet(null, adapter)) {
            try {
                adapter.close()
            } catch (_: Exception) {
                // The unused adapter has no recoverable cleanup operation.
            }
            return StorePurchaseQueryResult.Failure(
                StoreBillingError(
                    code = StoreBillingErrorCode.PURCHASE_IN_PROGRESS,
                    message = "Another native purchase operation is already in progress.",
                    isRetryable = true,
                ),
            )
        }

        return try {
            try {
                withTimeout(STORE_QUERY_TIMEOUT_MILLIS) {
                    if (
                        queryMode == StorePurchaseQueryMode.PARTIAL &&
                        adapter is PartialStorePurchaseQueryAdapter
                    ) {
                        adapter.queryPurchasesPartially(productType)
                    } else {
                        adapter.queryPurchases(productType)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                StorePurchaseQueryResult.Failure(
                    StoreBillingError(
                        code = StoreBillingErrorCode.OPERATION_TIMEOUT,
                        message = "The marketplace purchase query timed out.",
                        isRetryable = true,
                    ),
                )
            }
        } finally {
            if (activeStoreAdapter.compareAndSet(adapter, null)) {
                try {
                    adapter.close()
                } catch (_: Exception) {
                    // A completed query cannot safely retry adapter cleanup.
                }
            }
        }
    }

    private fun purchaseDeveloperPayload(
        request: NormalizedPurchaseRequest,
        selectedPackage: InappifyPackage,
        recoveryBinding: String,
        usesStoreV2: Boolean,
    ): String = gson.toJson(
        JsonObject().apply {
            addProperty("offeringIdentifier", request.offeringIdentifier)
            addProperty("productIdentifier", request.productIdentifier)
            if (!usesStoreV2) {
                // The legacy Bazaar payload places the product identifier in
                // packageIdentifier. nativePackageIdentifier retains the
                // actual package identifier for recovery.
                addProperty("packageIdentifier", request.productIdentifier)
                addProperty("marketKey", request.marketKey)
            }
            addProperty("nativePackageIdentifier", selectedPackage.identifier)
            addProperty("attemptId", request.attemptId)
            if (usesStoreV2) {
                // Omission preserves the untyped V1 receipt across process death;
                // only a verified server response may classify it as consumable.
                request.productType?.let { addProperty("productType", it.name) }
            } else {
                addProperty("discount", request.discount)
                addProperty("isCrypto", request.isCrypto)
            }
            addProperty("recoveryBinding", recoveryBinding)
        },
    )

    private fun StorePurchase.toRecoveredPurchaseRequest(
        expectedRecoveryBinding: String?,
        fallbackProductType: InappifyProductType = InappifyProductType.NON_CONSUMABLE,
        requireRecoveryBinding: Boolean = true,
    ): NormalizedPurchaseRequest? {
        return try {
            val payload = gson.fromJson(developerPayload, JsonObject::class.java) ?: return null
            if (requireRecoveryBinding) {
                val recoveryBinding = payload.normalizedString("recoveryBinding")
                    ?: return null
                val expected = expectedRecoveryBinding ?: return null
                if (
                    !MessageDigest.isEqual(
                        recoveryBinding.toByteArray(Charsets.UTF_8),
                        expected.toByteArray(Charsets.UTF_8),
                    )
                ) {
                    return null
                }
            }
            val offeringIdentifier = payload.normalizedString("offeringIdentifier") ?: return null
            val explicitProductIdentifier = payload.normalizedString("productIdentifier")
            val payloadPackageIdentifier = payload.normalizedString("packageIdentifier")
            val productIdentifier = explicitProductIdentifier
                ?: payloadPackageIdentifier
                ?: return null
            if (productIdentifier != this.productIdentifier) return null
            val nativePackageIdentifier = payload.normalizedString("nativePackageIdentifier")
                ?: payloadPackageIdentifier?.takeUnless { it == productIdentifier }

            val attemptId = payload
                .normalizedString("attemptId")
                ?.safePurchaseAttemptId()
                ?: "recovery-${UUID.randomUUID()}"
            val discount = payload.nonNegativeLong("discount") ?: 0L
            val isCrypto = payload.boolean("isCrypto") ?: false
            val productType = payload
                .normalizedString("productType")
                ?.let { value ->
                    InappifyProductType.values().firstOrNull { it.name == value }
                }
                ?: fallbackProductType
            NormalizedPurchaseRequest(
                productIdentifier = productIdentifier,
                offeringIdentifier = offeringIdentifier,
                packageIdentifier = nativePackageIdentifier,
                attemptId = attemptId,
                apiKey = null,
                country = null,
                appVersion = null,
                discount = discount,
                isCrypto = isCrypto,
                market = InappifyMarket.BAZAAR,
                marketKey = null,
                isLostPurchase = false,
                lostPurchaseToken = null,
                lostPurchaseTime = null,
                dynamicPriceToken = null,
                productType = productType,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reconstructs a V2 restore from store ownership rather than installation
     * identity. The backend remains authoritative for customer/app/product
     * ownership, which lets restore work after reinstall or device migration.
     */
    private fun StorePurchase.toStoreV2RestoreRequest(
        current: InternalSessionState,
        fallbackProductType: InappifyProductType,
    ): NormalizedPurchaseRequest? {
        toRecoveredPurchaseRequest(
            expectedRecoveryBinding = null,
            fallbackProductType = fallbackProductType,
            requireRecoveryBinding = false,
        )?.let { return it }

        val matchingPackage = current.offerings
            ?.offerings
            .orEmpty()
            .asSequence()
            .mapNotNull { offering ->
                val offeringIdentifier = offering.identifier
                    ?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val storePackage = offering.packages
                    .orEmpty()
                    .firstOrNull { candidate ->
                        candidate.product?.identifier == productIdentifier
                    }
                    ?: return@mapNotNull null
                offeringIdentifier to storePackage
            }
            .firstOrNull()
            ?: return null

        return NormalizedPurchaseRequest(
            productIdentifier = productIdentifier,
            offeringIdentifier = matchingPackage.first,
            packageIdentifier = matchingPackage.second.identifier,
            attemptId = "restore-${purchaseToken.fingerprint()}",
            apiKey = null,
            country = null,
            appVersion = null,
            discount = 0L,
            isCrypto = false,
            market = InappifyMarket.BAZAAR,
            marketKey = null,
            isLostPurchase = false,
            lostPurchaseToken = null,
            lostPurchaseTime = null,
            dynamicPriceToken = null,
            productType = fallbackProductType,
        )
    }

    private fun StorePurchase.hasExplicitProductType(): Boolean = try {
        val payload = gson.fromJson(developerPayload, JsonObject::class.java)
        val value = payload?.normalizedString("productType")
        value != null && InappifyProductType.values().any { it.name == value }
    } catch (_: Exception) {
        false
    }

    private fun JsonObject.normalizedString(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString.normalized()
    }

    private fun JsonObject.nonNegativeLong(name: String): Long? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        return try {
            BigDecimal(element.asString).longValueExact().takeIf { it >= 0L }
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun JsonObject.boolean(name: String): Boolean? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) return null
        return element.asBoolean
    }

    private fun InternalSessionState.hasVerifiedStorePurchase(
        purchase: StorePurchase,
    ): Boolean {
        val purchaseReferenceHash = purchase.purchaseToken.fingerprint()
        return customerInfo
            ?.entitlements
            .orEmpty()
            .any { entitlement ->
                entitlement.purchaseStoreRefHash
                    ?.trim()
                    ?.equals(purchaseReferenceHash, ignoreCase = true) == true
            }
    }

    private fun InternalSessionState.purchaseRecoveryBinding(): String? =
        purchaseRecoveryId?.safePurchaseAttemptId()

    private fun StoreBillingError.toPublicError(attemptId: String): InappifyError {
        val outcomeMayHaveCommitted = code == StoreBillingErrorCode.PURCHASE_FAILED ||
            code == StoreBillingErrorCode.CONNECTION_LOST ||
            code == StoreBillingErrorCode.OPERATION_TIMEOUT ||
            code == StoreBillingErrorCode.UI_HOST_DESTROYED ||
            code == StoreBillingErrorCode.INVALID_PURCHASE_STATE ||
            code == StoreBillingErrorCode.INVALID_PURCHASE_DATA ||
            code == StoreBillingErrorCode.PRODUCT_MISMATCH ||
            code == StoreBillingErrorCode.PACKAGE_MISMATCH
        val publicCode = when (code) {
            StoreBillingErrorCode.PURCHASE_IN_PROGRESS ->
                InappifyErrorCode.PURCHASE_IN_PROGRESS

            StoreBillingErrorCode.UNSUPPORTED_MARKET ->
                InappifyErrorCode.UNSUPPORTED_OPERATION

            StoreBillingErrorCode.ADAPTER_CLOSED ->
                InappifyErrorCode.REQUEST_CANCELLED

            StoreBillingErrorCode.UI_HOST_DESTROYED ->
                InappifyErrorCode.REQUEST_CANCELLED

            StoreBillingErrorCode.OPERATION_TIMEOUT ->
                InappifyErrorCode.TIMEOUT

            StoreBillingErrorCode.INVALID_REQUEST,
            StoreBillingErrorCode.MISSING_MARKET_KEY,
            -> InappifyErrorCode.INVALID_CONFIGURATION

            StoreBillingErrorCode.INVALID_PURCHASE_STATE,
            StoreBillingErrorCode.INVALID_PURCHASE_DATA,
            StoreBillingErrorCode.PRODUCT_MISMATCH,
            StoreBillingErrorCode.PACKAGE_MISMATCH,
            -> InappifyErrorCode.MALFORMED_RESPONSE

            else -> InappifyErrorCode.STORE_UNAVAILABLE
        }
        val publicMessage = when (publicCode) {
            InappifyErrorCode.PURCHASE_IN_PROGRESS ->
                "Another Inappify purchase is already in progress."
            InappifyErrorCode.UNSUPPORTED_OPERATION ->
                "The configured marketplace is not supported."
            InappifyErrorCode.INVALID_CONFIGURATION ->
                "The marketplace purchase configuration is invalid."
            InappifyErrorCode.MALFORMED_RESPONSE ->
                "The marketplace returned invalid purchase data."
            InappifyErrorCode.REQUEST_CANCELLED ->
                "The Inappify purchase was cancelled."
            else -> "The marketplace billing service is unavailable."
        }
        return InappifyError(
            code = publicCode,
            message = publicMessage,
            isRetryable = isRetryable && !outcomeMayHaveCommitted,
            details = mapOf(
                "operation" to OPERATION_PURCHASE,
                "attemptId" to attemptId,
                "store" to "bazar",
                "storeCode" to code.name,
                "outcomeMayHaveCommitted" to outcomeMayHaveCommitted,
            ),
        )
    }

    private fun StoreBillingError.toPublicSyncError(): InappifyError {
        val publicCode = when (code) {
            StoreBillingErrorCode.PURCHASE_IN_PROGRESS ->
                InappifyErrorCode.PURCHASE_IN_PROGRESS
            StoreBillingErrorCode.UNSUPPORTED_MARKET ->
                InappifyErrorCode.UNSUPPORTED_OPERATION
            StoreBillingErrorCode.ADAPTER_CLOSED ->
                InappifyErrorCode.REQUEST_CANCELLED
            StoreBillingErrorCode.OPERATION_TIMEOUT ->
                InappifyErrorCode.TIMEOUT
            StoreBillingErrorCode.MISSING_MARKET_KEY,
            StoreBillingErrorCode.INVALID_REQUEST,
            -> InappifyErrorCode.INVALID_CONFIGURATION
            StoreBillingErrorCode.INVALID_PURCHASE_STATE,
            StoreBillingErrorCode.INVALID_PURCHASE_DATA,
            StoreBillingErrorCode.PRODUCT_MISMATCH,
            StoreBillingErrorCode.PACKAGE_MISMATCH,
            -> InappifyErrorCode.MALFORMED_RESPONSE
            else -> InappifyErrorCode.STORE_UNAVAILABLE
        }
        return InappifyError(
            code = publicCode,
            message = when (publicCode) {
                InappifyErrorCode.PURCHASE_IN_PROGRESS ->
                    "Another Inappify purchase is already in progress."
                InappifyErrorCode.UNSUPPORTED_OPERATION ->
                    "The configured marketplace is not supported."
                InappifyErrorCode.INVALID_CONFIGURATION ->
                    "The marketplace purchase configuration is invalid."
                InappifyErrorCode.MALFORMED_RESPONSE ->
                    "The marketplace returned invalid purchase data."
                InappifyErrorCode.REQUEST_CANCELLED ->
                    "The marketplace purchase sync was cancelled."
                InappifyErrorCode.TIMEOUT ->
                    "The marketplace purchase sync timed out."
                else -> "The marketplace billing service is unavailable."
            },
            isRetryable = isRetryable,
            details = mapOf(
                "operation" to OPERATION_SYNC_PURCHASES,
                "store" to "bazar",
                "storeCode" to code.name,
                "outcomeMayHaveCommitted" to false,
            ),
        )
    }

    private suspend fun refreshStateAfterPurchase(
        current: InternalSessionState,
        receivedForceVersion: Long?,
    ): InternalSessionState {
        val forceVersion = current.monotonicForceVersion(receivedForceVersion)
        val offeringsAreStale = forceVersion != current.forceVersion
        var next = current.copy(
            forceVersion = forceVersion,
            offeringsJson = if (offeringsAreStale) null else current.offeringsJson,
            offerings = if (offeringsAreStale) null else current.offerings,
            failedToLoadOfferings = if (offeringsAreStale) {
                false
            } else {
                current.failedToLoadOfferings
            },
        )

        val customerRequest = next.resourceRequestOrNull()
        if (customerRequest != null) {
            val customerEvaluation = evaluate(
                operation = OPERATION_GET_CUSTOMER_INFO,
                result = callService { service.getCustomerInfo(customerRequest) },
                mutation = false,
            )
            next = if (customerEvaluation is Evaluation.Success) {
                val raw = customerEvaluation.payload.customerInfoJson
                val parsed = raw?.let(::parseCustomerInfo)
                if (
                    raw != null &&
                    parsed?.originalAppUserId.normalized() == next.appUserIdentifier
                ) {
                    val refreshedForceVersion = next.monotonicForceVersion(
                        customerEvaluation.payload.forceVersion,
                    )
                    val invalidateOfferings = refreshedForceVersion != next.forceVersion
                    next.copy(
                        forceVersion = refreshedForceVersion,
                        customerInfoJson = raw,
                        customerInfoUpdatedAt = currentTimeMillis().toString(),
                        customerInfo = parsed,
                        offeringsJson = if (invalidateOfferings) null else next.offeringsJson,
                        offerings = if (invalidateOfferings) null else next.offerings,
                        failedToLoadCustomerInfo = false,
                        failedToLoadOfferings = if (invalidateOfferings) {
                            false
                        } else {
                            next.failedToLoadOfferings
                        },
                    )
                } else {
                    next.copy(failedToLoadCustomerInfo = true)
                }
            } else {
                next.copy(failedToLoadCustomerInfo = true)
            }
        }

        val offeringsRequest = next.resourceRequestOrNull()
        if (offeringsRequest != null) {
            val offeringsEvaluation = evaluate(
                operation = OPERATION_GET_OFFERINGS,
                result = callService { service.getOfferings(offeringsRequest) },
                mutation = false,
            )
            next = if (offeringsEvaluation is Evaluation.Success) {
                val raw = offeringsEvaluation.payload.offeringsJson
                val parsed = raw?.let(::parseOfferings)
                if (raw != null && parsed != null) {
                    next.copy(
                        forceVersion = next.monotonicForceVersion(
                            offeringsEvaluation.payload.forceVersion ?: parsed.forceVersion,
                        ),
                        offeringsJson = raw,
                        offerings = parsed,
                        failedToLoadOfferings = false,
                    )
                } else {
                    next.copy(failedToLoadOfferings = true)
                }
            } else {
                next.copy(failedToLoadOfferings = true)
            }
        }
        return next
    }

    private fun InternalSessionState.afterPurchaseWithoutRefresh(
        receivedForceVersion: Long?,
    ): InternalSessionState {
        val refreshedForceVersion = monotonicForceVersion(receivedForceVersion)
        val offeringsAreStale = refreshedForceVersion != forceVersion
        return copy(
            forceVersion = refreshedForceVersion,
            offeringsJson = if (offeringsAreStale) null else offeringsJson,
            offerings = if (offeringsAreStale) null else offerings,
            failedToLoadOfferings = if (offeringsAreStale) {
                false
            } else {
                failedToLoadOfferings
            },
        )
    }

    private suspend fun commitPurchase(
        operationGeneration: Long,
        next: InternalSessionState,
        purchase: InappifyPurchase,
        attemptId: String,
    ): InappifyResult<InappifyPurchase> = when (
        val committed = commit(
            operationGeneration = operationGeneration,
            operation = OPERATION_PURCHASE,
            next = next,
            requestId = attemptId,
        )
    ) {
        is InappifyResult.Success -> {
            dispatchEvent(
                InappifyEvent.create(
                    type = InappifyEventType.PURCHASE_UPDATED,
                    snapshot = committed.snapshot,
                    requestId = attemptId,
                ),
            )
            InappifyResult.Success(purchase, committed.snapshot)
        }

        is InappifyResult.Failure -> committed
    }

    /** Commits reconciliation state while preserving the original purchase failure. */
    private suspend fun commitPurchaseFailure(
        operationGeneration: Long,
        next: InternalSessionState,
        error: InappifyError,
        attemptId: String,
    ): InappifyResult<InappifyPurchase> {
        val committed = commit(
            operationGeneration = operationGeneration,
            operation = OPERATION_PURCHASE,
            next = next,
            requestId = attemptId,
        )
        return InappifyResult.Failure(
            error = error,
            snapshot = committed.snapshot ?: state.get().toSnapshot(),
        )
    }

    private suspend fun commitSynchronizedPurchases(
        operationGeneration: Long,
        next: InternalSessionState,
        purchases: List<InappifyPurchase>,
    ): InappifyResult<List<InappifyPurchase>> = when (
        val committed = commit(
            operationGeneration = operationGeneration,
            operation = OPERATION_SYNC_PURCHASES,
            next = next,
        )
    ) {
        is InappifyResult.Success -> {
            purchases.forEach { purchase ->
                dispatchEvent(
                    InappifyEvent.create(
                        type = InappifyEventType.PURCHASE_UPDATED,
                        snapshot = committed.snapshot,
                        requestId = purchase.attemptId,
                    ),
                )
            }
            InappifyResult.Success(purchases.toList(), committed.snapshot)
        }

        is InappifyResult.Failure -> committed
    }

    private fun InappifyError.withPurchaseContext(
        attemptId: String,
        storePurchaseCompleted: Boolean,
    ): InappifyError = InappifyError(
        code = code,
        message = message,
        isRetryable = isRetryable,
        details = details + mapOf(
            "attemptId" to attemptId,
            "storePurchaseCompleted" to storePurchaseCompleted,
        ),
    )

    private fun InappifyError.withSyncPurchaseContext(
        recoveredCount: Int,
    ): InappifyError = InappifyError(
        code = code,
        message = message,
        isRetryable = isRetryable,
        details = details + mapOf(
            "operation" to OPERATION_SYNC_PURCHASES,
            "recoveredCount" to recoveredCount,
            "storePurchaseCompleted" to true,
        ),
    )

    private fun purchasePreconditionFailure(
        current: InternalSessionState,
    ): InappifyResult<InappifyPurchase> {
        val code = if (current.isConfigured) {
            InappifyErrorCode.UNAUTHORIZED
        } else {
            InappifyErrorCode.NOT_CONFIGURED
        }
        return purchaseFailure(
            InappifyError(
                code = code,
                message = if (current.isConfigured) {
                    "The current session is not authorized for purchases."
                } else {
                    "The client must be configured before purchasing."
                },
                details = mapOf("operation" to OPERATION_PURCHASE),
            ),
        )
    }

    private fun purchaseFailure(
        error: InappifyError,
    ): InappifyResult<InappifyPurchase> = InappifyResult.Failure(
        error = error,
        snapshot = state.get().toSnapshot(),
    )

    private fun purchaseSyncPreconditionFailure(
        current: InternalSessionState,
    ): InappifyResult<List<InappifyPurchase>> {
        val code = if (current.isConfigured) {
            InappifyErrorCode.UNAUTHORIZED
        } else {
            InappifyErrorCode.NOT_CONFIGURED
        }
        return purchaseSyncFailure(
            InappifyError(
                code = code,
                message = if (current.isConfigured) {
                    "The current session is not authorized to sync purchases."
                } else {
                    "The client must be configured before syncing purchases."
                },
                details = mapOf("operation" to OPERATION_SYNC_PURCHASES),
            ),
        )
    }

    private fun purchaseSyncFailure(
        error: InappifyError,
    ): InappifyResult<List<InappifyPurchase>> = InappifyResult.Failure(
        error = error,
        snapshot = state.get().toSnapshot(),
    )

    private fun unsupportedStoreRouteError(operation: String): InappifyError =
        InappifyError(
            code = InappifyErrorCode.UNSUPPORTED_OPERATION,
            message = "The configured store platform is not supported by this SDK version.",
            details = mapOf("operation" to operation),
        )

    private fun closeActiveStoreAdapter() {
        val adapter = activeStoreAdapter.getAndSet(null) ?: return
        try {
            adapter.close()
        } catch (_: Exception) {
            // Closing the client or finishing an attempt cannot safely retry cleanup.
        }
    }

    private suspend fun loadCustomerInfo(
        forceRefresh: Boolean,
    ): InappifyResult<InappifyCustomerInfo> = operationMutex.withLock {
        ensureOpen()
        val operationGeneration = generation.get()
        val current = state.get()
        val request = current.resourceRequestOrNull()
            ?: return@withLock resourcePreconditionFailure(
                current = current,
                operation = OPERATION_GET_CUSTOMER_INFO,
            )

        val cached = current.customerInfo
        if (
            !forceRefresh &&
            cached != null &&
            current.customerInfoUpdatedAt.isFreshCustomerCache()
        ) {
            return@withLock InappifyResult.Success(cached, current.toSnapshot())
        }

        val result = callService { service.getCustomerInfo(request) }
        when (
            val evaluation = evaluate(
                operation = OPERATION_GET_CUSTOMER_INFO,
                result = result,
                mutation = false,
            )
        ) {
            is Evaluation.Failure -> {
                val rejected = invalidateRestoredSession(current, evaluation.error, operationGeneration)
                if (rejected.details["cacheInvalidated"] == true) {
                    return@withLock resourceFailure(rejected)
                }
                val receivedForceVersion = result.successfulHttpForceVersion()
                markResourceFailure(
                    operationGeneration = operationGeneration,
                    customerInfo = true,
                    receivedForceVersion = receivedForceVersion,
                )
                scheduleOfferingRefresh(
                    previousForceVersion = current.forceVersion,
                    receivedForceVersion = receivedForceVersion,
                )
                resourceFailure(evaluation.error)
            }

            is Evaluation.Success -> {
                val raw = evaluation.payload.customerInfoJson
                    ?: return@withLock malformedResourceFailure(
                        operationGeneration = operationGeneration,
                        operation = OPERATION_GET_CUSTOMER_INFO,
                        requestId = evaluation.requestId,
                        customerInfo = true,
                        receivedForceVersion = evaluation.payload.forceVersion,
                    )
                val customerInfo = parseCustomerInfo(raw)
                    ?: return@withLock malformedResourceFailure(
                        operationGeneration = operationGeneration,
                        operation = OPERATION_GET_CUSTOMER_INFO,
                        requestId = evaluation.requestId,
                        customerInfo = true,
                        // A malformed nested customer model invalidates the
                        // response envelope and cannot advance forceVersion.
                        receivedForceVersion = null,
                    )
                val responseIdentifier = customerInfo.originalAppUserId.normalized()
                if (
                    responseIdentifier == null ||
                    responseIdentifier != current.appUserIdentifier
                ) {
                    return@withLock malformedResourceFailure(
                        operationGeneration = operationGeneration,
                        operation = OPERATION_GET_CUSTOMER_INFO,
                        requestId = evaluation.requestId,
                        customerInfo = true,
                        receivedForceVersion = evaluation.payload.forceVersion,
                    )
                }

                val forceVersion = current.monotonicForceVersion(
                    evaluation.payload.forceVersion,
                )
                val offeringsAreStale = forceVersion != current.forceVersion
                val next = current.copy(
                    forceVersion = forceVersion,
                    customerInfoJson = raw,
                    customerInfoUpdatedAt = currentTimeMillis().toString(),
                    customerInfo = customerInfo,
                    offeringsJson = if (offeringsAreStale) null else current.offeringsJson,
                    offerings = if (offeringsAreStale) null else current.offerings,
                    failedToLoadCustomerInfo = false,
                    failedToLoadOfferings = if (offeringsAreStale) false else
                        current.failedToLoadOfferings,
                )
                val committed = commitResource(
                    operationGeneration = operationGeneration,
                    operation = OPERATION_GET_CUSTOMER_INFO,
                    next = next,
                    data = customerInfo,
                    requestId = evaluation.requestId,
                )
                scheduleOfferingRefresh(
                    previousForceVersion = current.forceVersion,
                    receivedForceVersion = evaluation.payload.forceVersion,
                )
                committed
            }
        }
    }

    private suspend fun loadOfferings(
        forceRefresh: Boolean,
    ): InappifyResult<InappifyOfferings> = operationMutex.withLock {
        ensureOpen()
        val operationGeneration = generation.get()
        val current = state.get()
        val request = current.resourceRequestOrNull()
            ?: return@withLock resourcePreconditionFailure(
                current = current,
                operation = OPERATION_GET_OFFERINGS,
            )

        val cached = current.offerings
        if (!forceRefresh && cached != null) {
            return@withLock InappifyResult.Success(cached, current.toSnapshot())
        }

        val result = callService { service.getOfferings(request) }
        when (
            val evaluation = evaluate(
                operation = OPERATION_GET_OFFERINGS,
                result = result,
                mutation = false,
            )
        ) {
            is Evaluation.Failure -> {
                val rejected = invalidateRestoredSession(current, evaluation.error, operationGeneration)
                if (rejected.details["cacheInvalidated"] == true) {
                    return@withLock resourceFailure(rejected)
                }
                markResourceFailure(
                    operationGeneration = operationGeneration,
                    customerInfo = false,
                )
                resourceFailure(evaluation.error)
            }

            is Evaluation.Success -> {
                val raw = evaluation.payload.offeringsJson
                    ?: return@withLock malformedResourceFailure(
                        operationGeneration = operationGeneration,
                        operation = OPERATION_GET_OFFERINGS,
                        requestId = evaluation.requestId,
                        customerInfo = false,
                        receivedForceVersion = null,
                    )
                val offerings = parseOfferings(raw)
                    ?: return@withLock malformedResourceFailure(
                        operationGeneration = operationGeneration,
                        operation = OPERATION_GET_OFFERINGS,
                        requestId = evaluation.requestId,
                        customerInfo = false,
                        // A malformed rule or list invalidates the response
                        // envelope, including forceVersion.
                        receivedForceVersion = null,
                    )
                val next = current.copy(
                    forceVersion = current.monotonicForceVersion(
                        evaluation.payload.forceVersion ?: offerings.forceVersion,
                    ),
                    offeringsJson = raw,
                    offerings = offerings,
                    failedToLoadOfferings = false,
                )
                commitResource(
                    operationGeneration = operationGeneration,
                    operation = OPERATION_GET_OFFERINGS,
                    next = next,
                    data = offerings,
                    requestId = evaluation.requestId,
                )
            }
        }
    }

    override fun close() {
        val shouldClose = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                false
            } else {
                generation.incrementAndGet()
                true
            }
        }
        if (shouldClose) {
            backgroundScope.cancel()
            consumableDeliveryHandler.set(null)
            listeners.clear()
            eventExecutor.shutdownNow()
            closeActiveStoreAdapter()
            try {
                service.close()
            } catch (_: Exception) {
                // The client is already closed and cannot safely retry cleanup.
            }
        }
    }

    private suspend fun configureFromNetwork(
        operationGeneration: Long,
        options: NormalizedOptions,
        metadata: AppMetadata,
        previousRecoveryId: String?,
        previousAppUserIdentifier: String?,
        requestedAppUserIdentifier: String? = options.appUserIdentifier,
    ): InappifyResult<Unit> {
        val result = callService {
            service.configure(
                ConfigureApiRequest(
                    apiKey = options.apiKey,
                    packageIdentifier = metadata.packageIdentifier,
                    appUserIdentifier = requestedAppUserIdentifier,
                    versionName = options.appVersion,
                    versionCode = metadata.versionCode,
                ),
            )
        }
        return when (
            val evaluation = evaluate(
                operation = OPERATION_CONFIGURE,
                result = result,
                mutation = true,
            )
        ) {
            is Evaluation.Failure -> failure(evaluation.error)
            is Evaluation.Success -> {
                val token = evaluation.payload.token.normalized()
                    ?: return malformedAfterMutation(
                        OPERATION_CONFIGURE,
                        evaluation.requestId,
                    )
                val identifier = evaluation.payload
                    .appUserIdentifier
                    .normalized()
                    ?: return malformedAfterMutation(
                        OPERATION_CONFIGURE,
                        evaluation.requestId,
                    )
                if (
                    requestedAppUserIdentifier != null &&
                    identifier != requestedAppUserIdentifier
                ) {
                    return malformedAfterMutation(
                        OPERATION_CONFIGURE,
                        evaluation.requestId,
                    )
                }
                hydrateAndCommitLifecycleState(
                    operationGeneration = operationGeneration,
                    operation = OPERATION_CONFIGURE,
                    requestId = evaluation.requestId,
                    candidate = InternalSessionState(
                        isConfigured = true,
                        isAuthenticated = identifier.isAuthenticatedIdentity(),
                        apiKey = options.apiKey,
                        apiKeyFingerprint = options.apiKeyFingerprint,
                        cacheContextFingerprint = options.cacheContextFingerprint,
                        token = token,
                        appUserIdentifier = identifier,
                        market = options.market,
                        marketKey = options.marketKey,
                        country = options.country,
                        appVersion = options.appVersion,
                        sdkVersion = state.get().sdkVersion,
                        storeInfo = evaluation.payload.storeInfo,
                        storePlatform = evaluation.payload.storePlatform,
                        forceVersion = evaluation.payload.forceVersion ?: 1L,
                        appId = evaluation.payload.appId,
                        customerInfoJson = evaluation.payload.customerInfoJson,
                        offeringsJson = null,
                        customerInfoUpdatedAt = null,
                        purchaseRecoveryId = if (
                            identifier == previousAppUserIdentifier
                        ) {
                            previousRecoveryId?.safePurchaseAttemptId()
                                ?: newPurchaseRecoveryId()
                        } else {
                            newPurchaseRecoveryId()
                        },
                    ),
                )
            }
        }
    }

    private suspend fun refreshReusableSession(
        reusable: InternalSessionState,
        options: NormalizedOptions,
    ): Evaluation {
        val token = reusable.token
            ?: return Evaluation.Failure(
                InappifyError(
                    code = InappifyErrorCode.UNAUTHORIZED,
                    message = defaultMessage(
                        OPERATION_CONFIGURE,
                        InappifyErrorCode.UNAUTHORIZED,
                    ),
                    details = mapOf("operation" to OPERATION_CONFIGURE),
                ),
            )
        val result = callService {
            service.refreshSession(
                RefreshSessionApiRequest(
                    apiKey = options.apiKey,
                    token = token,
                    forceVersion = reusable.forceVersion,
                ),
            )
        }
        val evaluation = evaluate(
            operation = OPERATION_CONFIGURE,
            result = result,
            mutation = false,
        )
        if (
            evaluation is Evaluation.Failure &&
            result is ServiceResult.Response &&
            result.statusCode == HTTP_OK &&
            result.payload.status == false
        ) {
            return Evaluation.Failure(
                InappifyError(
                    code = InappifyErrorCode.UNAUTHORIZED,
                    message = defaultMessage(
                        OPERATION_CONFIGURE,
                        InappifyErrorCode.UNAUTHORIZED,
                    ),
                    details = responseDetails(
                        operation = OPERATION_CONFIGURE,
                        statusCode = result.statusCode,
                        requestId = result.requestId,
                        backendCode = result.payload.errorCode,
                        outcomeMayHaveCommitted = false,
                    ),
                ),
            )
        }
        return evaluation
    }

    private fun normalize(
        options: InappifyOptions,
        metadata: AppMetadata,
    ): NormalizedOptions? {
        val apiKey = options.apiKey.trim()
        if (apiKey.isEmpty()) return null
        val market = options.market ?: InappifyMarket.NONE
        val marketKey = when (market) {
            // Keep an explicitly supplied key available when the server later
            // selects Bazaar through storePlatform. Direct V1 never
            // reads this value.
            InappifyMarket.NONE -> options.marketKey.normalized()
            InappifyMarket.BAZAAR -> options.marketKey.normalized() ?: return null
        }
        val country = options.country
            .normalized()
            ?.uppercase(Locale.ROOT)
            ?: DEFAULT_COUNTRY
        val appVersion = options.appVersion.normalized()
            ?: metadata.versionName
        return NormalizedOptions(
            apiKey = apiKey,
            apiKeyFingerprint = apiKey.fingerprint(),
            cacheContextFingerprint = cacheContextFingerprint(
                market = market,
                marketKey = marketKey,
                country = country,
                appVersion = appVersion,
            ),
            appUserIdentifier = options.appUserIdentifier
                .normalized()
                ?.takeUnless { it.isAnonymousIdentity() },
            market = market,
            marketKey = marketKey,
            country = country,
            appVersion = appVersion,
        )
    }

    /**
     * Typed V2 requests and background recovery use the server route. Untyped
     * V1 requests retain their explicit Direct/Bazaar selection. A matching
     * Bazaar route can still use V2 verification and its durable delivery flow.
     */
    private fun InternalSessionState.resolvePurchaseRoute(
        requestedMarket: InappifyMarket,
        preserveRequestedMarket: Boolean = false,
    ): PurchaseRoute? {
        val configured = storePlatform
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return PurchaseRoute(
                market = requestedMarket,
                isServerAuthoritative = false,
                usesStoreV2 = false,
            )
        val serverRoute = when (
            configured
                .lowercase(Locale.ROOT)
                .filter(Char::isLetterOrDigit)
        ) {
            "bazar", "bazaar" -> PurchaseRoute(
                market = InappifyMarket.BAZAAR,
                isServerAuthoritative = true,
                usesStoreV2 = true,
            )

            "none", "android", "directandroid" ->
                PurchaseRoute(
                    market = InappifyMarket.NONE,
                    isServerAuthoritative = true,
                    usesStoreV2 = false,
                )

            // MyKet is deliberately deferred to the next implementation phase.
            "myket" -> null
            else -> null
        }
        // Unsupported stores still fail closed; this compatibility rule only
        // selects between the two implemented Android payment markets.
        return if (
            preserveRequestedMarket && serverRoute != null &&
            serverRoute.market != requestedMarket
        ) {
            PurchaseRoute(requestedMarket, isServerAuthoritative = false, usesStoreV2 = false)
        } else {
            serverRoute
        }
    }

    private fun InternalSessionState.reusableFor(
        options: NormalizedOptions,
    ): InternalSessionState? {
        if (!isConfigured || token.isNullOrBlank()) return null
        if (apiKeyFingerprint != options.apiKeyFingerprint) return null
        if (
            options.appUserIdentifier != null &&
            options.appUserIdentifier != appUserIdentifier
        ) {
            return null
        }
        return this
    }

    private fun PersistedSession.reusableFor(
        options: NormalizedOptions,
    ): PersistedSession? {
        if (token.isNullOrBlank()) return null
        if (apiKeyFingerprint != options.apiKeyFingerprint) return null
        if (
            options.appUserIdentifier != null &&
            options.appUserIdentifier != appUserIdentifier
        ) {
            return null
        }
        return this
    }

    private fun InternalSessionState.withConfiguration(
        options: NormalizedOptions,
        metadata: AppMetadata,
    ): InternalSessionState {
        val cacheContextMatches =
            cacheContextFingerprint == options.cacheContextFingerprint
        return InternalSessionState(
            isConfigured = true,
            isAuthenticated = appUserIdentifier.isAuthenticatedIdentity(),
            apiKey = options.apiKey,
            apiKeyFingerprint = options.apiKeyFingerprint,
            cacheContextFingerprint = options.cacheContextFingerprint,
            token = token,
            appUserIdentifier = appUserIdentifier,
            market = options.market,
            marketKey = options.marketKey,
            country = options.country,
            appVersion = options.appVersion.ifBlank { metadata.versionName },
            sdkVersion = sdkVersion,
            storeInfo = storeInfo,
            storePlatform = storePlatform,
            forceVersion = forceVersion ?: 1L,
            appId = appId,
            customerInfoJson = customerInfoJson,
            offeringsJson = if (cacheContextMatches) offeringsJson else null,
            customerInfoUpdatedAt = customerInfoUpdatedAt,
            purchaseRecoveryId = purchaseRecoveryId?.safePurchaseAttemptId()
                ?: newPurchaseRecoveryId(),
            customerInfo = customerInfo,
            offerings = if (cacheContextMatches) offerings else null,
            failedToLoadCustomerInfo = failedToLoadCustomerInfo,
            failedToLoadOfferings = if (cacheContextMatches) {
                failedToLoadOfferings
            } else {
                false
            },
            revision = revision,
        )
    }

    private fun PersistedSession.toConfiguredState(
        options: NormalizedOptions,
        metadata: AppMetadata,
        recoverMissingPurchaseBinding: Boolean = true,
        validateCachedDocuments: Boolean = false,
    ): InternalSessionState {
        val restoredCustomer = customerInfoJson
            ?.let(::parseCustomerInfo)
            ?.takeIf {
                it.originalAppUserId.normalized() == appUserIdentifier
            }
        val cacheContextMatches = cacheContextFingerprint != null &&
            cacheContextFingerprint == options.cacheContextFingerprint
        val restoredOfferings = if (cacheContextMatches) {
            offeringsJson?.takeIf { !validateCachedDocuments || isValidCachedJson(it) }?.let(::parseOfferings)
        } else {
            null
        }
        return InternalSessionState(
            isConfigured = true,
            isAuthenticated = appUserIdentifier.isAuthenticatedIdentity(),
            apiKey = options.apiKey,
            apiKeyFingerprint = options.apiKeyFingerprint,
            cacheContextFingerprint = options.cacheContextFingerprint,
            token = token,
            appUserIdentifier = appUserIdentifier,
            market = options.market,
            marketKey = options.marketKey,
            country = options.country,
            appVersion = options.appVersion.ifBlank { metadata.versionName },
            sdkVersion = state.get().sdkVersion,
            storeInfo = storeInfo,
            storePlatform = storePlatform,
            forceVersion = forceVersion ?: 1L,
            appId = appId,
            customerInfoJson = if (restoredCustomer == null) {
                null
            } else {
                customerInfoJson
            },
            offeringsJson = if (restoredOfferings == null) {
                null
            } else {
                offeringsJson
            },
            customerInfoUpdatedAt = if (restoredCustomer == null) {
                null
            } else {
                customerInfoUpdatedAt
            },
            purchaseRecoveryId = purchaseRecoveryId?.safePurchaseAttemptId()
                ?: if (recoverMissingPurchaseBinding) newPurchaseRecoveryId() else null,
            customerInfo = restoredCustomer,
            offerings = restoredOfferings,
        )
    }

    private fun InternalSessionState.withRefreshedSession(
        payload: BackendResponse,
        appUserIdentifier: String?,
    ): InternalSessionState {
        val refreshedForceVersion = monotonicForceVersion(payload.forceVersion)
        val offeringsAreStale = refreshedForceVersion != forceVersion
        return InternalSessionState(
            isConfigured = true,
            isAuthenticated = appUserIdentifier.isAuthenticatedIdentity(),
            apiKey = apiKey,
            apiKeyFingerprint = apiKeyFingerprint,
            cacheContextFingerprint = cacheContextFingerprint,
            token = token,
            appUserIdentifier = appUserIdentifier,
            market = market,
            marketKey = marketKey,
            country = country,
            appVersion = appVersion,
            sdkVersion = sdkVersion,
            storeInfo = payload.storeInfo ?: storeInfo,
            storePlatform = payload.storePlatform ?: storePlatform,
            forceVersion = refreshedForceVersion,
            appId = payload.appId ?: appId,
            customerInfoJson = payload.customerInfoJson,
            offeringsJson = if (offeringsAreStale) null else offeringsJson,
            customerInfoUpdatedAt = null,
            purchaseRecoveryId = if (
                appUserIdentifier == this.appUserIdentifier
            ) {
                purchaseRecoveryId?.safePurchaseAttemptId()
                    ?: newPurchaseRecoveryId()
            } else {
                newPurchaseRecoveryId()
            },
            offerings = if (offeringsAreStale) null else offerings,
            failedToLoadOfferings = failedToLoadOfferings,
            revision = revision,
        )
    }

    /**
     * Once a lifecycle mutation is dispatched, finish evaluating and storing
     * its result even if the caller is cancelled. This prevents a successful
     * remote identity change from being paired with stale local credentials.
     */
    private suspend fun <T> completeLifecycleMutation(
        block: suspend () -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) { block() }
    }

    /** Applies forceVersion from a failed lifecycle response before returning. */
    private suspend fun applyFailedLifecycleForceVersion(
        operationGeneration: Long,
        operation: String,
        current: InternalSessionState,
        result: ServiceResult,
    ) {
        val receivedForceVersion = result.successfulHttpForceVersion() ?: return
        if (receivedForceVersion <= (current.forceVersion ?: 0L)) return
        val response = result as ServiceResult.Response
        commit(
            operationGeneration = operationGeneration,
            operation = operation,
            next = current.withReceivedForceVersion(receivedForceVersion),
            requestId = response.requestId,
        )
        scheduleOfferingRefresh(
            previousForceVersion = current.forceVersion,
            receivedForceVersion = receivedForceVersion,
        )
    }

    private suspend fun malformedLifecycleResponse(
        operationGeneration: Long,
        operation: String,
        current: InternalSessionState,
        result: ServiceResult,
        requestId: String?,
    ): InappifyResult<Unit> {
        applyFailedLifecycleForceVersion(
            operationGeneration = operationGeneration,
            operation = operation,
            current = current,
            result = result,
        )
        return malformedAfterMutation(operation, requestId)
    }

    /**
     * Decodes lifecycle customer data and refreshes offerings without turning
     * an offerings read failure into a lifecycle mutation failure.
     */
    private suspend fun hydrateAndCommitLifecycleState(
        operationGeneration: Long,
        operation: String,
        requestId: String?,
        candidate: InternalSessionState,
    ): InappifyResult<Unit> = withContext(NonCancellable) {
        val next = hydrateLifecycleState(candidate)
        commit(
            operationGeneration = operationGeneration,
            operation = operation,
            next = next,
            requestId = requestId,
        )
    }

    private suspend fun hydrateLifecycleState(
        candidate: InternalSessionState,
    ): InternalSessionState {
        val customerRaw = candidate.customerInfoJson
        val customerInfo = customerRaw?.let(::parseCustomerInfo)
            ?.takeIf {
                it.originalAppUserId.normalized() == candidate.appUserIdentifier
            }
        val withCustomerInfo = candidate.copy(
            customerInfoJson = if (customerInfo == null) null else customerRaw,
            customerInfoUpdatedAt = if (customerInfo == null) {
                null
            } else {
                currentTimeMillis().toString()
            },
            customerInfo = customerInfo,
            failedToLoadCustomerInfo = customerRaw != null && customerInfo == null,
        )
        val request = withCustomerInfo.resourceRequestOrNull()
            ?: return withCustomerInfo
        val result = callService { service.getOfferings(request) }
        val evaluation = evaluate(
            operation = OPERATION_GET_OFFERINGS,
            result = result,
            mutation = false,
        )
        if (evaluation !is Evaluation.Success) {
            return withCustomerInfo.copy(failedToLoadOfferings = true)
        }
        val raw = evaluation.payload.offeringsJson
        val offerings = raw?.let(::parseOfferings)
            ?: return withCustomerInfo.copy(failedToLoadOfferings = true)
        val refreshedForceVersion = withCustomerInfo.monotonicForceVersion(
            evaluation.payload.forceVersion ?: offerings.forceVersion,
        )
        val offeringsAreStale =
            refreshedForceVersion != withCustomerInfo.forceVersion
        val cacheCandidate = withCustomerInfo.copy(
            forceVersion = refreshedForceVersion,
            offeringsJson = if (offeringsAreStale) {
                null
            } else {
                withCustomerInfo.offeringsJson
            },
            offerings = if (offeringsAreStale) {
                null
            } else {
                withCustomerInfo.offerings
            },
        )
        return cacheCandidate.copy(
            offeringsJson = raw,
            offerings = offerings,
            failedToLoadOfferings = false,
        )
    }

    private fun InternalSessionState.resourceRequestOrNull(): ResourceApiRequest? {
        val currentApiKey = apiKey?.takeIf(String::isNotBlank) ?: return null
        val currentToken = token?.takeIf(String::isNotBlank) ?: return null
        if (!isConfigured) return null
        return ResourceApiRequest(
            apiKey = currentApiKey,
            token = currentToken,
            forceVersion = forceVersion,
        )
    }

    private fun InternalSessionState.monotonicForceVersion(
        received: Long?,
    ): Long = maxOf(forceVersion ?: 1L, received ?: forceVersion ?: 1L)

    private fun String?.isFreshCustomerCache(): Boolean {
        val updatedAt = this?.toLongOrNull() ?: return false
        val age = currentTimeMillis() - updatedAt
        return age >= 0 && age < CUSTOMER_INFO_CACHE_TTL_MILLIS
    }

    private fun parseCustomerInfo(raw: String): InappifyCustomerInfo? = try {
        InappifyDomainJsonCodec.parseCustomerInfo(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseOfferings(raw: String): InappifyOfferings? = try {
        InappifyDomainJsonCodec.parseOfferings(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun InappifySnapshot.toOfferingEvaluationContext():
        InappifyOfferingEvaluationContext {
        val attributes = LinkedHashMap<String, String?>()
        customerInfo?.attributes.orEmpty().forEach { attribute ->
            val key = attribute.key
            if (key != null && !attributes.containsKey(key)) {
                attributes[key] = attribute.value
            }
        }
        return InappifyOfferingEvaluationContext(
            country = country.orEmpty(),
            platform = ANDROID_PLATFORM,
            appVersion = appVersion.orEmpty(),
            sdkVersion = sdkVersion,
            appId = appId,
            customAttributes = attributes,
        )
    }

    private fun List<InappifyAttribute>.applyAttributeChanges(
        stores: List<InappifyAttribute>,
        removals: List<InappifyAttribute>,
    ): List<InappifyAttribute> {
        val updated = toMutableList()
        stores.forEach { attribute ->
            val index = updated.indexOfFirst { current -> current.key == attribute.key }
            if (index >= 0) {
                updated[index] = InappifyAttribute(
                    key = updated[index].key,
                    value = attribute.value,
                )
            } else {
                updated += attribute
            }
        }
        removals.forEach { attribute ->
            val index = updated.indexOfFirst { current -> current.key == attribute.key }
            if (index >= 0) {
                updated[index] = InappifyAttribute(
                    key = updated[index].key,
                    value = "",
                )
            }
        }
        return updated.toList()
    }

    private fun List<InappifyAttribute>.clearFirstAttributeValue(
        key: String,
    ): List<InappifyAttribute> {
        val index = indexOfFirst { attribute -> attribute.key == key }
        if (index < 0) return this
        return toMutableList().also { updated ->
            updated[index] = InappifyAttribute(
                key = updated[index].key,
                value = "",
            )
        }.toList()
    }

    private fun InternalSessionState.withCustomerAttributes(
        attributes: List<InappifyAttribute>?,
    ): InternalSessionState {
        val customer = customerInfo ?: return this
        val updatedCustomer = InappifyCustomerInfo(
            originalAppUserId = customer.originalAppUserId,
            firstSeen = customer.firstSeen,
            requestDate = customer.requestDate,
            latestExpirationDate = customer.latestExpirationDate,
            hasUsedTrial = customer.hasUsedTrial,
            entitlements = customer.entitlements,
            transactions = customer.transactions,
            attributes = attributes,
        )
        return copy(
            customerInfo = updatedCustomer,
            customerInfoJson = InappifyDomainJsonCodec.encodeCustomerInfo(updatedCustomer),
        )
    }

    private fun scheduleOfferingRefresh(
        previousForceVersion: Long?,
        receivedForceVersion: Long?,
    ) {
        val previous = previousForceVersion ?: 0L
        val received = receivedForceVersion ?: return
        if (received <= previous || closed.get()) return
        backgroundScope.launch {
            try {
                refreshOfferings()
            } catch (_: CancellationException) {
                // Closing the client cancels the scheduled offerings refresh.
            } catch (_: Exception) {
                // refreshOfferings already records failures in the snapshot.
            }
        }
    }

    private fun InternalSessionState.withReceivedForceVersion(
        received: Long?,
    ): InternalSessionState {
        val updatedForceVersion = monotonicForceVersion(received)
        if (updatedForceVersion == forceVersion) return this
        return copy(
            forceVersion = updatedForceVersion,
            offeringsJson = null,
            offerings = null,
            failedToLoadOfferings = false,
        )
    }

    /** Only HTTP 200 response bodies may update forceVersion. */
    private fun ServiceResult.successfulHttpForceVersion(): Long? =
        (this as? ServiceResult.Response)
            ?.takeIf { response -> response.statusCode == HTTP_OK }
            ?.payload
            ?.forceVersion

    private suspend fun <T> commitResource(
        operationGeneration: Long,
        operation: String,
        next: InternalSessionState,
        data: T,
        requestId: String?,
    ): InappifyResult<T> = when (
        val committed = commit(
            operationGeneration = operationGeneration,
            operation = operation,
            next = next,
            requestId = requestId,
        )
    ) {
        is InappifyResult.Success -> InappifyResult.Success(
            data = data,
            snapshot = committed.snapshot,
        )

        is InappifyResult.Failure -> committed
    }

    private fun <T> resourcePreconditionFailure(
        current: InternalSessionState,
        operation: String,
    ): InappifyResult<T> {
        val code = if (current.isConfigured) {
            InappifyErrorCode.UNAUTHORIZED
        } else {
            InappifyErrorCode.NOT_CONFIGURED
        }
        val message = if (current.isConfigured) {
            "The current session is not authorized for this operation."
        } else {
            "The client must be configured before this operation."
        }
        return resourceFailure(
            InappifyError(
                code = code,
                message = message,
                details = mapOf("operation" to operation),
            ),
        )
    }

    private fun <T> malformedResourceFailure(
        operationGeneration: Long,
        operation: String,
        requestId: String?,
        customerInfo: Boolean,
        receivedForceVersion: Long?,
    ): InappifyResult<T> {
        val previousForceVersion = state.get().forceVersion
        markResourceFailure(
            operationGeneration = operationGeneration,
            customerInfo = customerInfo,
            receivedForceVersion = receivedForceVersion,
        )
        scheduleOfferingRefresh(
            previousForceVersion = previousForceVersion,
            receivedForceVersion = receivedForceVersion,
        )
        return resourceFailure(
            malformedError(
                operation = operation,
                requestId = requestId,
                outcomeMayHaveCommitted = false,
            ),
        )
    }

    /** Records a read failure while retaining every still-valid cache entry. */
    private fun markResourceFailure(
        operationGeneration: Long,
        customerInfo: Boolean,
        receivedForceVersion: Long? = null,
    ) {
        val (previous, next) = synchronized(lifecycleLock) {
            if (closed.get() || generation.get() != operationGeneration) return
            val current = state.get()
            val forceVersion = current.monotonicForceVersion(
                receivedForceVersion,
            )
            val offeringsAreStale = forceVersion != current.forceVersion
            if (
                (customerInfo && current.failedToLoadCustomerInfo) ||
                (!customerInfo && current.failedToLoadOfferings)
            ) {
                if (!offeringsAreStale) return
            }
            val updated = current.copy(
                forceVersion = forceVersion,
                offeringsJson = if (offeringsAreStale) {
                    null
                } else {
                    current.offeringsJson
                },
                offerings = if (offeringsAreStale) {
                    null
                } else {
                    current.offerings
                },
                failedToLoadCustomerInfo = if (customerInfo) {
                    true
                } else {
                    current.failedToLoadCustomerInfo
                },
                failedToLoadOfferings = if (customerInfo) {
                    if (offeringsAreStale) {
                        false
                    } else {
                        current.failedToLoadOfferings
                    }
                } else {
                    true
                },
                revision = current.revision + 1,
            )
            state.set(updated)
            current to updated
        }
        publishEvents(
            previous = previous,
            next = next,
            requestId = null,
        )
    }

    private fun <T> resourceFailure(
        error: InappifyError,
    ): InappifyResult<T> = InappifyResult.Failure(
        error = error,
        snapshot = state.get().toSnapshot(),
    )

    private fun <T> malformedOperationFailure(
        operation: String,
        requestId: String?,
        outcomeMayHaveCommitted: Boolean,
    ): InappifyResult<T> = resourceFailure(
        malformedError(
            operation = operation,
            requestId = requestId,
            outcomeMayHaveCommitted = outcomeMayHaveCommitted,
        ),
    )

    private suspend fun commit(
        operationGeneration: Long,
        operation: String,
        next: InternalSessionState,
        requestId: String? = null,
    ): InappifyResult<Unit> {
        var previous: InternalSessionState? = null
        var committedState: InternalSessionState? = null
        val committed = synchronized(lifecycleLock) {
            if (
                closed.get() ||
                generation.get() != operationGeneration
            ) {
                false
            } else {
                previous = state.get()
                val candidate = next.copy(
                    revision = previous!!.revision + 1,
                )
                committedState = candidate
                state.set(candidate)
                true
            }
        }
        if (!committed) {
            return failure(
                code = InappifyErrorCode.REQUEST_CANCELLED,
                message = "The SDK operation was cancelled.",
                operation = operation,
            )
        }
        val authoritativeState = requireNotNull(committedState)
        val saveResult = withContext(NonCancellable) {
            try {
                sessionStore.saveWithDiagnostics(
                    authoritativeState.toPersistedSession(),
                )
            } catch (exception: Exception) {
                SessionSaveResult.Failure(
                    SessionStorageFailure(
                        stage = SessionStorageStage.UNKNOWN,
                        causeType = exception.javaClass.name,
                        rootCauseType = exception.rootCauseType(),
                    ),
                )
            }
        }
        publishEvents(
            previous = requireNotNull(previous),
            next = authoritativeState,
            requestId = requestId,
        )
        if (saveResult is SessionSaveResult.Failure) {
            val staleSessionCleared = withContext(NonCancellable) {
                try {
                    sessionStore.clear()
                } catch (_: Exception) {
                    false
                }
            }
            return failure(
                InappifyError(
                    code = InappifyErrorCode.STORE_UNAVAILABLE,
                    message = "The Inappify session could not be saved securely.",
                    details = buildMap {
                        put("operation", operation)
                        put("stateApplied", true)
                        put("staleSessionCleared", staleSessionCleared)
                        put("outcomeMayHaveCommitted", true)
                        putAll(saveResult.diagnostic.toSafeDetails())
                    },
                ),
            )
        }
        if (operation in setOf(OPERATION_CONFIGURE, OPERATION_LOGIN, OPERATION_LOGOUT)) {
            // A committed online lifecycle response re-establishes authority for this token.
            val fingerprint = authoritativeState.toPersistedSession().offlineSessionFingerprint()
            rejectedCachedSessions.remove(fingerprint)
            // Keep the opt-in protection across successful refresh and account/token rotation.
            // Clients that never restored offline retain their original V1 auth semantics.
            if (offlineRestoredSession != null) offlineRestoredSession = fingerprint
        }
        return success(authoritativeState)
    }

    private fun publishEvents(
        previous: InternalSessionState,
        next: InternalSessionState,
        requestId: String?,
    ) {
        val eventTypes = buildList {
            add(InappifyEventType.STATE_CHANGED)
            if (
                previous.isAuthenticated != next.isAuthenticated ||
                previous.appUserIdentifier != next.appUserIdentifier
            ) {
                add(InappifyEventType.AUTHENTICATION_CHANGED)
            }
            if (previous.customerInfoJson != next.customerInfoJson) {
                add(InappifyEventType.CUSTOMER_INFO_CHANGED)
            }
            if (previous.offeringsJson != next.offeringsJson) {
                add(InappifyEventType.OFFERINGS_CHANGED)
            }
        }
        eventTypes.forEach { type ->
            dispatchEvent(
                InappifyEvent.create(
                    type = type,
                    snapshot = next.toSnapshot(),
                    requestId = requestId,
                ),
            )
        }
    }

    private fun dispatchEvent(event: InappifyEvent) {
        if (closed.get() || listeners.isEmpty()) return
        try {
            eventExecutor.execute {
                if (closed.get()) return@execute
                listeners.forEach { listener ->
                    if (closed.get()) return@execute
                    try {
                        listener.onEvent(event)
                    } catch (_: Exception) {
                        // Application listener failures are isolated by design.
                    }
                }
            }
        } catch (_: RuntimeException) {
            // Closing the client may race with event submission.
        }
    }

    private suspend fun loadPersistedSession(): PersistedSession? = try {
        sessionStore.load()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    private suspend fun callService(
        block: suspend () -> ServiceResult,
    ): ServiceResult = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ServiceResult.Failure(ServiceFailureKind.UNKNOWN)
    }

    private fun evaluate(
        operation: String,
        result: ServiceResult,
        mutation: Boolean,
        requireSuccessfulPayloadStatus: Boolean = true,
    ): Evaluation = when (result) {
        is ServiceResult.Failure -> {
            val code = when (result.kind) {
                ServiceFailureKind.NETWORK -> InappifyErrorCode.NETWORK
                ServiceFailureKind.TIMEOUT -> InappifyErrorCode.TIMEOUT
                ServiceFailureKind.CANCELLED ->
                    InappifyErrorCode.REQUEST_CANCELLED
                ServiceFailureKind.MALFORMED_RESPONSE ->
                    InappifyErrorCode.MALFORMED_RESPONSE
                ServiceFailureKind.UNKNOWN -> InappifyErrorCode.UNKNOWN
            }
            val outcomeMayHaveCommitted = mutation
            Evaluation.Failure(
                InappifyError(
                    code = code,
                    message = defaultMessage(operation, code),
                    isRetryable = !outcomeMayHaveCommitted &&
                        code.isTransient(),
                    details = linkedMapOf<String, Any?>(
                        "operation" to operation,
                        "outcomeMayHaveCommitted" to outcomeMayHaveCommitted,
                    ),
                ),
            )
        }

        is ServiceResult.Response -> evaluateResponse(
            operation,
            result,
            mutation,
            requireSuccessfulPayloadStatus,
        )
    }

    private fun evaluateResponse(
        operation: String,
        result: ServiceResult.Response,
        mutation: Boolean,
        requireSuccessfulPayloadStatus: Boolean,
    ): Evaluation {
        if (result.statusCode != HTTP_OK) {
            val code = if (
                operation == OPERATION_PURCHASE &&
                result.statusCode in 400..499 &&
                result.statusCode !in setOf(401, 403, 408, 429)
            ) {
                InappifyErrorCode.PURCHASE_VERIFICATION_FAILED
            } else {
                result.statusCode.toErrorCode()
            }
            val outcomeMayHaveCommitted = mutation &&
                (result.statusCode == 408 || result.statusCode >= 500)
            return Evaluation.Failure(
                InappifyError(
                    code = code,
                    message = defaultMessage(operation, code),
                    isRetryable = !outcomeMayHaveCommitted &&
                        code.isTransient(),
                    details = responseDetails(
                        operation = operation,
                        statusCode = result.statusCode,
                        requestId = result.requestId,
                        backendCode = result.payload.errorCode,
                        outcomeMayHaveCommitted = outcomeMayHaveCommitted,
                    ),
                ),
            )
        }
        val payloadStatus = result.payload.status
        if (requireSuccessfulPayloadStatus && payloadStatus == null) {
            return Evaluation.Failure(
                malformedError(
                    operation = operation,
                    requestId = result.requestId,
                    outcomeMayHaveCommitted = mutation,
                ),
            )
        }
        if (requireSuccessfulPayloadStatus && payloadStatus == false) {
            val mappedCode = result.payload.errorCode.toErrorCode()
            val code = if (
                operation == OPERATION_PURCHASE &&
                mappedCode == InappifyErrorCode.UNKNOWN
            ) {
                InappifyErrorCode.PURCHASE_VERIFICATION_FAILED
            } else {
                mappedCode
            }
            return Evaluation.Failure(
                InappifyError(
                    code = code,
                    message = defaultMessage(operation, code),
                    isRetryable = code.isTransient(),
                    details = responseDetails(
                        operation = operation,
                        statusCode = result.statusCode,
                        requestId = result.requestId,
                        backendCode = result.payload.errorCode,
                        outcomeMayHaveCommitted = false,
                    ),
                ),
            )
        }
        return Evaluation.Success(result.payload, result.requestId)
    }

    private fun malformedAfterMutation(
        operation: String,
        requestId: String?,
        outcomeMayHaveCommitted: Boolean = true,
    ): InappifyResult<Unit> = failure(
        malformedError(operation, requestId, outcomeMayHaveCommitted),
    )

    private fun malformedError(
        operation: String,
        requestId: String?,
        outcomeMayHaveCommitted: Boolean,
    ): InappifyError = InappifyError(
        code = InappifyErrorCode.MALFORMED_RESPONSE,
        message = "The Inappify service returned an incomplete response.",
        details = responseDetails(
            operation = operation,
            statusCode = HTTP_OK,
            requestId = requestId,
            backendCode = null,
            outcomeMayHaveCommitted = outcomeMayHaveCommitted,
        ),
    )

    private fun responseDetails(
        operation: String,
        statusCode: Int,
        requestId: String?,
        backendCode: String?,
        outcomeMayHaveCommitted: Boolean,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "operation" to operation,
        "httpStatus" to statusCode,
        "outcomeMayHaveCommitted" to outcomeMayHaveCommitted,
    ).apply {
        requestId.safeDiagnosticValue()?.let { put("requestId", it) }
        backendCode.safeDiagnosticValue()?.let { put("backendCode", it) }
    }

    private fun success(state: InternalSessionState): InappifyResult<Unit> =
        InappifyResult.Success(Unit, state.toSnapshot())

    private fun failure(error: InappifyError): InappifyResult<Unit> =
        InappifyResult.Failure(error, state.get().toSnapshot())

    private fun failure(
        code: InappifyErrorCode,
        message: String,
        operation: String,
    ): InappifyResult<Unit> = failure(
        InappifyError(
            code = code,
            message = message,
            details = mapOf("operation" to operation),
        ),
    )

    private fun invalidConfiguration(
        message: String,
        operation: String = OPERATION_CONFIGURE,
    ): InappifyResult<Unit> = failure(
        code = InappifyErrorCode.INVALID_CONFIGURATION,
        message = message,
        operation = operation,
    )

    private fun ensureOpen() {
        check(!closed.get()) { "InappifyClient is closed." }
    }

    private fun String?.normalized(): String? =
        this?.trim()?.takeIf(String::isNotEmpty)

    private fun newPurchaseRecoveryId(): String =
        purchaseRecoveryIdProvider()
            .safePurchaseAttemptId()
            ?: UUID.randomUUID().toString()

    private fun String?.isAuthenticatedIdentity(): Boolean =
        !isNullOrBlank() && !isAnonymousIdentity()

    private fun String.isAnonymousIdentity(): Boolean =
        contains(ANONYMOUS_IDENTIFIER_MARKER)

    /**
     * Binds persisted offerings to configuration that can affect targeting.
     * Raw marketplace values never enter persisted diagnostics.
     */
    private fun cacheContextFingerprint(
        market: InappifyMarket,
        marketKey: String?,
        country: String,
        appVersion: String,
    ): String = buildString {
        listOf(market.name, marketKey, country, appVersion).forEach { value ->
            append(value?.length ?: -1)
            append(':')
            append(value.orEmpty())
            append(';')
        }
    }.fingerprint()

    private fun String.fingerprint(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }

    private fun fingerprintComponents(vararg values: Any?): String = buildString {
        values.forEach { raw ->
            val value = raw?.toString()
            append(value?.length ?: -1)
            append(':')
            append(value.orEmpty())
            append(';')
        }
    }.fingerprint()

    private fun String?.safeDiagnosticValue(): String? =
        normalized()
            ?.takeIf { it.length <= MAX_DIAGNOSTIC_VALUE_LENGTH }
            ?.takeIf { value ->
                value.all { character ->
                    character.isLetterOrDigit() ||
                        character == '-' ||
                        character == '_' ||
                        character == '.' ||
                        character == ':'
                }
            }

    private fun String.safePurchaseAttemptId(): String? =
        trim()
            .takeIf(String::isNotEmpty)
            ?.takeIf { it.length <= MAX_PURCHASE_ATTEMPT_ID_LENGTH }
            ?.takeIf { value ->
                value.all { character ->
                    character.isLetterOrDigit() ||
                        character == '-' ||
                        character == '_' ||
                        character == '.' ||
                        character == ':'
                }
            }

    private fun InappifyErrorCode.isTransient(): Boolean =
        this == InappifyErrorCode.NETWORK ||
            this == InappifyErrorCode.TIMEOUT

    private fun InappifyErrorCode.requiresNewSession(): Boolean =
        this == InappifyErrorCode.UNAUTHORIZED ||
            this == InappifyErrorCode.NOT_CONFIGURED

    private fun Int.toErrorCode(): InappifyErrorCode = when (this) {
        400, 404, 409, 422 -> InappifyErrorCode.INVALID_CONFIGURATION
        401, 403 -> InappifyErrorCode.UNAUTHORIZED
        408, 504 -> InappifyErrorCode.TIMEOUT
        429, in 500..599 -> InappifyErrorCode.NETWORK
        else -> InappifyErrorCode.UNKNOWN
    }

    private fun String?.toErrorCode(): InappifyErrorCode =
        when (
            this
                ?.lowercase()
                ?.replace("_", "")
                ?.replace("-", "")
        ) {
            "invalidconfiguration" -> InappifyErrorCode.INVALID_CONFIGURATION
            "notconfigured" -> InappifyErrorCode.NOT_CONFIGURED
            "unauthorized",
            "unauthenticated",
            "sessionexpired",
            "tokenexpired",
            "invalidtoken",
            "authenticationrequired",
            "authrequired" -> InappifyErrorCode.UNAUTHORIZED
            "network" -> InappifyErrorCode.NETWORK
            "requestcancelled", "requestcanceled" ->
                InappifyErrorCode.REQUEST_CANCELLED
            "timeout" -> InappifyErrorCode.TIMEOUT
            "storeunavailable" -> InappifyErrorCode.STORE_UNAVAILABLE
            "purchaseinprogress" -> InappifyErrorCode.PURCHASE_IN_PROGRESS
            "purchasecancelled", "purchasecanceled" ->
                InappifyErrorCode.PURCHASE_CANCELLED
            "purchaseverificationfailed", "invalidpurchase" ->
                InappifyErrorCode.PURCHASE_VERIFICATION_FAILED
            "unsupportedoperation" -> InappifyErrorCode.UNSUPPORTED_OPERATION
            "malformedresponse" -> InappifyErrorCode.MALFORMED_RESPONSE
            else -> InappifyErrorCode.UNKNOWN
        }

    private fun defaultMessage(
        operation: String,
        code: InappifyErrorCode,
    ): String = when (code) {
        InappifyErrorCode.NETWORK -> "The Inappify service is unavailable."
        InappifyErrorCode.TIMEOUT -> "The Inappify request timed out."
        InappifyErrorCode.REQUEST_CANCELLED ->
            "The Inappify request was cancelled."
        InappifyErrorCode.UNAUTHORIZED ->
            "The Inappify session is not authorized."
        InappifyErrorCode.MALFORMED_RESPONSE ->
            "The Inappify service returned an invalid response."
        InappifyErrorCode.PURCHASE_IN_PROGRESS ->
            "Another Inappify purchase is already in progress."
        InappifyErrorCode.PURCHASE_VERIFICATION_FAILED ->
            "The Inappify service rejected the marketplace purchase."
        else -> "Inappify $operation failed."
    }

    private class NormalizedOptions(
        val apiKey: String,
        val apiKeyFingerprint: String,
        val cacheContextFingerprint: String,
        val appUserIdentifier: String?,
        val market: InappifyMarket,
        val marketKey: String?,
        val country: String,
        val appVersion: String,
    )

    private class NormalizedPurchaseRequest(
        val productIdentifier: String,
        val offeringIdentifier: String,
        val packageIdentifier: String?,
        val attemptId: String,
        val apiKey: String?,
        val country: String?,
        val appVersion: String?,
        val discount: Long,
        val isCrypto: Boolean,
        val market: InappifyMarket,
        val marketKey: String?,
        val isLostPurchase: Boolean,
        val lostPurchaseToken: String?,
        val lostPurchaseTime: Long?,
        val dynamicPriceToken: String?,
        val productType: InappifyProductType?,
    )

    private class PurchaseRoute(
        val market: InappifyMarket,
        val isServerAuthoritative: Boolean,
        val usesStoreV2: Boolean,
    )

    private sealed interface Evaluation {
        class Success(
            val payload: BackendResponse,
            val requestId: String?,
        ) : Evaluation

        class Failure(
            val error: InappifyError,
        ) : Evaluation
    }

    internal companion object {
        private const val HTTP_OK = 200
        private const val DEFAULT_COUNTRY = "IR"
        private const val ANDROID_PLATFORM = "android"
        private const val STORE_BAZAAR = "bazar"
        private const val ANONYMOUS_IDENTIFIER_MARKER = "InaAnonymous"
        private const val MAX_DIAGNOSTIC_VALUE_LENGTH = 128
        private const val MAX_PURCHASE_ATTEMPT_ID_LENGTH = 128
        private const val CUSTOMER_INFO_CACHE_TTL_MILLIS = 5 * 60 * 1000L
        private const val STORE_QUERY_TIMEOUT_MILLIS = 30 * 1000L
        private const val STORE_PURCHASE_TIMEOUT_MILLIS = 10 * 60 * 1000L
        private const val OPERATION_CONFIGURE = "configure"
        private const val OPERATION_RESTORE_CACHE = "restoreCachedSession"
        private const val OPERATION_LOGIN = "login"
        private const val OPERATION_LOGOUT = "logout"
        private const val OPERATION_GET_CUSTOMER_INFO = "getCustomerInfo"
        private const val OPERATION_GET_OFFERINGS = "getOfferings"
        private const val OPERATION_VALIDATE_DISCOUNT_CODE = "validateDiscountCode"
        private const val OPERATION_SET_TARGETING_CONTEXT = "setTargetingContext"
        private const val OPERATION_SET_ATTRIBUTES = "setAttributes"
        private const val OPERATION_SET_RESERVED_ATTRIBUTE = "setReservedAttribute"
        private const val OPERATION_SYNC_ATTRIBUTES = "syncAttributes"
        private const val OPERATION_PURCHASE = "purchase"
        private const val OPERATION_SYNC_PURCHASES = "syncPurchases"
        private const val OPERATION_RESTORE_PURCHASES = "restorePurchases"
        private const val OPERATION_CONFIRM_DELIVERY = "confirmDelivery"
        private const val OPERATION_SYNC_PENDING_CONSUMABLES =
            "syncPendingConsumables"
        private const val DIRECT_DELIVERY_BATCH_SIZE = 100
        private const val MAX_DIRECT_DELIVERY_BATCHES = 100
        private const val MAX_CONSUMABLE_HTTP_RETRIES = 5
        private const val CONSUMABLE_RETRY_BASE_MILLIS = 2_000L
        private const val CONSUMABLE_RETRY_JITTER_MILLIS = 250L
        private const val CONSUMABLE_RETRY_EXPONENTIAL_CAP_MILLIS = 30_000L
        private const val CONSUMABLE_RETRY_MAX_MILLIS = 60_250L
        private const val EVENT_THREAD_NAME = "inappify-sdk-events"

        internal fun create(
            context: Context,
            unsafeRawHttpLogging: Boolean = false,
        ): DefaultInappifyClient =
            createProductionClient(
                context = context.applicationContext,
                unsafeRawHttpLogging = unsafeRawHttpLogging,
            )

        private fun createProductionClient(
            context: Context,
            unsafeRawHttpLogging: Boolean,
        ): DefaultInappifyClient {
            val transport = OkHttpTransport.createProduction(unsafeRawHttpLogging)
            return DefaultInappifyClient(
                service = DefaultInappifyService(transport),
                sessionStore = EncryptedSessionStateStore.create(context),
                metadataProvider = AndroidAppMetadataProvider(context),
                sdkVersion = BuildConfig.SDK_VERSION,
                storeBillingAdapterFactory = AndroidStoreBillingAdapterFactory(context),
            )
        }
    }
}

private fun Throwable.rootCauseType(): String {
    var current = this
    repeat(8) {
        val next = current.cause ?: return current.javaClass.name
        if (next === current) return current.javaClass.name
        current = next
    }
    return current.javaClass.name
}
