package com.inappify.sdk.internal

import android.app.Activity
import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.inappify.sdk.BuildConfig
import com.inappify.sdk.InappifyAttribute
import com.inappify.sdk.InappifyAttributesRequest
import com.inappify.sdk.InappifyClient
import com.inappify.sdk.InappifyCustomerInfo
import com.inappify.sdk.InappifyDeleteAttributesRequest
import com.inappify.sdk.InappifyDiscountCodeRequest
import com.inappify.sdk.InappifyDiscountCodeResult
import com.inappify.sdk.InappifyError
import com.inappify.sdk.InappifyErrorCode
import com.inappify.sdk.InappifyEvent
import com.inappify.sdk.InappifyEventListener
import com.inappify.sdk.InappifyEventType
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
import com.inappify.sdk.InappifyReservedAttribute
import com.inappify.sdk.InappifyReservedAttributeRequest
import com.inappify.sdk.InappifyResult
import com.inappify.sdk.InappifySnapshot
import com.inappify.sdk.backendKey
import com.inappify.sdk.findActiveEntitlement
import com.inappify.sdk.hasValidValue
import com.inappify.sdk.isValidCustomAttribute
import com.inappify.sdk.removesCustomAttribute
import com.inappify.sdk.internal.billing.AndroidStoreBillingAdapterFactory
import com.inappify.sdk.internal.billing.StoreBillingAdapter
import com.inappify.sdk.internal.billing.StoreBillingAdapterFactory
import com.inappify.sdk.internal.billing.StoreBillingError
import com.inappify.sdk.internal.billing.StoreBillingErrorCode
import com.inappify.sdk.internal.billing.StoreBillingResult
import com.inappify.sdk.internal.billing.StoreProductType
import com.inappify.sdk.internal.billing.StorePurchase
import com.inappify.sdk.internal.billing.StorePurchaseQueryResult
import com.inappify.sdk.internal.billing.StorePurchaseRequest
import com.inappify.sdk.internal.billing.StoreUiHost
import com.inappify.sdk.internal.billing.UnsupportedStoreBillingAdapter
import com.inappify.sdk.internal.domain.InappifyDomainJsonCodec
import com.inappify.sdk.internal.network.BackendResponse
import com.inappify.sdk.internal.network.ConfigureApiRequest
import com.inappify.sdk.internal.network.DefaultInappifyService
import com.inappify.sdk.internal.network.InappifyService
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
import com.inappify.sdk.internal.platform.AndroidAppMetadataProvider
import com.inappify.sdk.internal.platform.AppMetadata
import com.inappify.sdk.internal.platform.AppMetadataProvider
import com.inappify.sdk.internal.state.InternalSessionState
import com.inappify.sdk.internal.storage.EncryptedSessionStateStore
import com.inappify.sdk.internal.storage.PersistedSession
import com.inappify.sdk.internal.storage.SessionStateStore
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
    private val storeBillingAdapterFactory: StoreBillingAdapterFactory =
        StoreBillingAdapterFactory { _, _ ->
            UnsupportedStoreBillingAdapter(
                StoreBillingError(
                    code = StoreBillingErrorCode.UNSUPPORTED_MARKET,
                    message = "Native store billing is unavailable.",
                ),
            )
        },
) : InappifyClient {

    private val operationMutex = Mutex()
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean(false)
    private val purchaseInProgress = AtomicBoolean(false)
    private val activeStoreAdapter = AtomicReference<StoreBillingAdapter?>(null)
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

    override val snapshot: InappifySnapshot
        get() = state.get().toSnapshot()

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

        completeLifecycleMutation lifecycle@{
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
        completeLifecycleMutation lifecycle@{
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
                purchaseLocked(
                    activity = activity,
                    request = request,
                    operationGeneration = generation.get(),
                )
            }
        } finally {
            closeActiveStoreAdapter()
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
                syncPurchasesLocked(generation.get())
            }
        } finally {
            closeActiveStoreAdapter()
            purchaseInProgress.set(false)
        }
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

        // Lost-purchase recovery bypasses cached-offering lookup and
        // marketplace UI. The supplied store evidence is submitted directly.
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
            normalized.market == InappifyMarket.NONE -> null
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
                        selectedPackage = requireNotNull(selectedPackage),
                        expectedPackageName = metadata.packageIdentifier,
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
                            market = normalized.market,
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

    private suspend fun syncPurchasesLocked(
        operationGeneration: Long,
    ): InappifyResult<List<InappifyPurchase>> {
        val current = state.get()
        val sessionRequest = current.resourceRequestOrNull()
            ?: return purchaseSyncPreconditionFailure(current)
        val market = current.market
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
        selectedPackage: InappifyPackage,
        expectedPackageName: String,
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
                market = request.market,
                marketKey = request.marketKey,
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
                            productType = StoreProductType.IN_APP,
                            developerPayload = purchaseDeveloperPayload(
                                request = request,
                                selectedPackage = selectedPackage,
                                recoveryBinding = recoveryBinding,
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
    ): StorePurchaseQueryResult {
        val adapter = try {
            storeBillingAdapterFactory.create(
                market = requireNotNull(current.market),
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
                    adapter.queryPurchases(StoreProductType.IN_APP)
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
    ): String = gson.toJson(
        JsonObject().apply {
            addProperty("offeringIdentifier", request.offeringIdentifier)
            addProperty("productIdentifier", request.productIdentifier)
            // The Bazaar wire contract places the product identifier in
            // packageIdentifier. nativePackageIdentifier retains the actual
            // package identifier for recovery.
            addProperty("packageIdentifier", request.productIdentifier)
            addProperty("marketKey", request.marketKey)
            addProperty("nativePackageIdentifier", selectedPackage.identifier)
            addProperty("attemptId", request.attemptId)
            addProperty("discount", request.discount)
            addProperty("isCrypto", request.isCrypto)
            addProperty("recoveryBinding", recoveryBinding)
        },
    )

    private fun StorePurchase.toRecoveredPurchaseRequest(
        expectedRecoveryBinding: String,
    ): NormalizedPurchaseRequest? {
        return try {
            val payload = gson.fromJson(developerPayload, JsonObject::class.java) ?: return null
            val recoveryBinding = payload.normalizedString("recoveryBinding") ?: return null
            if (
                !MessageDigest.isEqual(
                    recoveryBinding.toByteArray(Charsets.UTF_8),
                    expectedRecoveryBinding.toByteArray(Charsets.UTF_8),
                )
            ) {
                return null
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
            )
        } catch (_: Exception) {
            null
        }
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
    ): InappifyResult<Unit> {
        val result = callService {
            service.configure(
                ConfigureApiRequest(
                    apiKey = options.apiKey,
                    packageIdentifier = metadata.packageIdentifier,
                    appUserIdentifier = options.appUserIdentifier,
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
                    options.appUserIdentifier != null &&
                    identifier != options.appUserIdentifier
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
            InappifyMarket.NONE -> null
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
    ): InternalSessionState {
        val restoredCustomer = customerInfoJson
            ?.let(::parseCustomerInfo)
            ?.takeIf {
                it.originalAppUserId.normalized() == appUserIdentifier
            }
        val cacheContextMatches = cacheContextFingerprint != null &&
            cacheContextFingerprint == options.cacheContextFingerprint
        val restoredOfferings = if (cacheContextMatches) {
            offeringsJson?.let(::parseOfferings)
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
                ?: newPurchaseRecoveryId(),
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
        val persisted = withContext(NonCancellable) {
            try {
                sessionStore.save(authoritativeState.toPersistedSession())
            } catch (_: Exception) {
                false
            }
        }
        publishEvents(
            previous = requireNotNull(previous),
            next = authoritativeState,
            requestId = requestId,
        )
        if (!persisted) {
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
                    details = mapOf(
                        "operation" to operation,
                        "stateApplied" to true,
                        "staleSessionCleared" to staleSessionCleared,
                        "outcomeMayHaveCommitted" to true,
                    ),
                ),
            )
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
        private const val ANONYMOUS_IDENTIFIER_MARKER = "InaAnonymous"
        private const val MAX_DIAGNOSTIC_VALUE_LENGTH = 128
        private const val MAX_PURCHASE_ATTEMPT_ID_LENGTH = 128
        private const val CUSTOMER_INFO_CACHE_TTL_MILLIS = 5 * 60 * 1000L
        private const val STORE_QUERY_TIMEOUT_MILLIS = 30 * 1000L
        private const val STORE_PURCHASE_TIMEOUT_MILLIS = 10 * 60 * 1000L
        private const val OPERATION_CONFIGURE = "configure"
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
        private const val EVENT_THREAD_NAME = "inappify-sdk-events"

        internal fun create(context: Context): DefaultInappifyClient =
            createProductionClient(context.applicationContext)

        private fun createProductionClient(
            context: Context,
        ): DefaultInappifyClient {
            val transport = OkHttpTransport.createProduction()
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
