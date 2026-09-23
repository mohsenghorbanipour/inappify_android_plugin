package com.inappify.sdk.internal.v2

import android.app.Activity
import android.content.Context
import com.google.gson.*
import com.inappify.sdk.*
import com.inappify.sdk.internal.domain.InappifyDomainJsonCodec
import com.inappify.sdk.internal.DefaultInappifyClient
import com.inappify.sdk.internal.StoreV2Context
import com.inappify.sdk.internal.StoreV2Coordinator
import com.inappify.sdk.internal.StoreV2Outcome
import com.inappify.sdk.internal.billing.*
import com.inappify.sdk.internal.network.StorePurchaseStatus
import com.inappify.sdk.internal.platform.*
import com.inappify.sdk.internal.network.OkHttpTransport
import com.inappify.sdk.internal.storage.*
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Serial state owner. Shared requests belong to the client, never to one caller's Job. */
internal class GoV2Client(
    private val config: GoV2Environment,
    private val sdkApi: GoApi,
    private val storage: SessionStateStore,
    private val metadata: AppMetadataProvider,
    private val now: () -> Long = System::currentTimeMillis,
    private val eventDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val backgroundRecovery: Boolean = true,
    private val countryResolver: suspend () -> String? = { null },
    private val commerceApi: GoApi? = null,
    private val billingFactory: StoreBillingAdapterFactory = StoreBillingAdapterFactory { _, _ ->
        UnsupportedStoreBillingAdapter(StoreBillingError(StoreBillingErrorCode.UNSUPPORTED_MARKET,
            "Native store billing is unavailable."))
    },
) : InappifyV2Client, StoreV2CapableClient, ConsumableFulfillmentCapableClient, HttpDiagnosticsCapableClient,
    InappifyV2RuntimeCapabilities {
    constructor(config: InappifyV2Configuration, sdkApi: GoApi, storage: SessionStateStore,
        metadata: AppMetadataProvider, now: () -> Long = System::currentTimeMillis,
        eventDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate, backgroundRecovery: Boolean = true,
        countryResolver: suspend () -> String? = { null }, commerceApi: GoApi? = null,
        billingFactory: StoreBillingAdapterFactory = StoreBillingAdapterFactory { _, _ ->
            UnsupportedStoreBillingAdapter(StoreBillingError(StoreBillingErrorCode.UNSUPPORTED_MARKET,
                "Native store billing is unavailable."))
        }) :
        this(GoV2Environment.explicit(config), sdkApi, storage, metadata, now, eventDispatcher, backgroundRecovery,
            countryResolver, commerceApi, billingFactory)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val flights = mutableMapOf<String, Deferred<*>>()
    private val listeners = CopyOnWriteArraySet<InappifyEventListener>()
    private var trustedTimeFloor = 0L
    private var timeAnchorNanos = System.nanoTime()
    private val verifier = CustomerInfoVerifier(config, ::trustedNow)
    private var options: InappifyOptions? = null
    private var document = JsonObject()
    private var purchaseClient: InappifyClient? = null
    private var deliveryHandler: InappifyConsumableDeliveryHandler? = null
    private var handlerRegistration: InappifyListenerRegistration? = null
    private var renewalFailure: V2Failure? = null
    private var nextRenewalAt = 0L
    private var detectedCountry: String? = null
    private val storeCoordinator = commerceApi?.let { endpoint ->
        StoreV2Coordinator(GoV2StoreService(endpoint, ::storeSessionToken), storage, billingFactory, now)
    }
    @Volatile private var closed = false
    @Volatile private var currentSnapshot = InappifySnapshot.initial(BuildConfig.SDK_VERSION, forceVersion = null)
    @Volatile private var pendingLogout = false
    @Volatile private var pendingLogin = false
    @Volatile private var authenticatedScope: InappifyV2SessionScope? = null
    override val snapshot: InappifySnapshot get() = currentSnapshot
    override val hasForceUpdate: Boolean get() = false
    override val isLogoutPending: Boolean get() = pendingLogout
    override val verifiedScope: InappifyV2SessionScope?
        get() = authenticatedScope.takeIf { snapshot.isConfigured && !pendingLogout && !pendingLogin }
    override fun allowsPaymentUrl(url: String): Boolean = allowedUrl(url, config.paymentHosts)

    private suspend fun <T> run(action: suspend () -> T): InappifyResult<T> = mutex.withLock {
        check(!closed) { "The Inappify client is closed." }
        try { InappifyResult.Success(action(), snapshot) }
        catch (e: CancellationException) { throw e }
        catch (e: V2Failure) { InappifyResult.Failure(e.sdkError, snapshot) }
        catch (_: Exception) { InappifyResult.Failure(InappifyError(
            InappifyErrorCode.MALFORMED_RESPONSE, "Inappify v2 returned an invalid response."), snapshot) }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> shared(key: String, action: suspend () -> InappifyResult<T>): InappifyResult<T> {
        check(!closed)
        val job = synchronized(flights) {
            val existing = flights[key]
            if (existing != null && !existing.isCompleted) existing as Deferred<InappifyResult<T>>
            else scope.async(start = CoroutineStart.LAZY) { action() }.also { fresh ->
                flights[key] = fresh
                fresh.invokeOnCompletion { synchronized(flights) { if (flights[key] === fresh) flights.remove(key) } }
                fresh.start()
            }
        }
        return job.await()
    }

    override suspend fun configure(options: InappifyOptions): InappifyResult<Unit> =
        shared("configure:${fingerprint(options)}:${options.appUserIdentifier}:${options.country}:${options.appVersion}") { run {
            validateOptions(options)
            if (this.options != null && fingerprint(this.options!!) != fingerprint(options))
                fail("CONFIGURATION", "CREATE_NEW_CLIENT_FOR_APP_CHANGE")
            this.options = options
            if (document.size() == 0) {
                val stored = storage.load()
                if (stored != null && stored.customerInfoJson != null && cacheMatches(stored, options)) {
                    val cacheEnvelope = runCatching { jsonObject(stored.customerInfoJson) }.getOrNull()
                    val cachedKid = runCatching {
                        cacheEnvelope?.getAsJsonObject("session")?.getAsJsonObject("verification")?.string("keyId")
                    }.getOrNull()
                    if (cachedKid != null && !verifier.known(cachedKid)) {
                        pendingLogout = cacheEnvelope?.get("pendingLogout")?.asBoolean == true
                        verifier.refresh(sdkApi.request("public-keys", null))
                    }
                    val saved = verifiedCache(stored.customerInfoJson, null)
                    if (saved != null) {
                        document = saved
                        // Keep verified app scope even when Configure requests another customer,
                        // without first publishing that customer's predecessor into the UI.
                        val cachedIdentity = saved.getAsJsonObject("session")?.string("appUserId")
                        if (saved.has("pendingLogin") || options.appUserIdentifier == null ||
                            cachedIdentity == options.appUserIdentifier) publish()
                        else pendingLogout = saved.get("pendingLogout")?.asBoolean == true
                    } else if (stored.apiKeyFingerprint == fingerprint(options)) {
                        // A stale/unknown signing kid must not rotate the locally persisted UUID.
                        // This does not accept a session, token, tenant scope or entitlements.
                        val cached = runCatching { jsonObject(stored.customerInfoJson) }.getOrNull()
                        if (cached?.get("pendingLogout")?.asBoolean == true) {
                            pendingLogout = true
                            fail("SIGNATURE", "INVALID_PENDING_LOGOUT_CACHE")
                        }
                        if (cached?.has("pendingLogin") == true)
                            fail("SIGNATURE", "INVALID_PENDING_LOGIN_CACHE")
                        runCatching { cached?.get("anonymousId")?.asString }.getOrNull()
                            ?.takeIf(::isAnonymous)?.let { id ->
                                document = JsonObject().apply {
                                    addProperty("anonymousId", id)
                                    // A local saved scope may restrict recovery, but can never grant
                                    // trust: the network reply still needs a valid pinned signature.
                                    cached?.getAsJsonObject("scope")?.let { add("scope", it.deepCopy()) }
                                }
                            }
                    }
                }
            }
            if (pendingLogout) { logoutLocked(); return@run }
            if (pendingLogin) {
                val target = document.getAsJsonObject("pendingLogin").string("appUserId")
                if (options.appUserIdentifier != null && options.appUserIdentifier != target)
                    fail("AUTH", "LOGIN_PENDING")
                resumeLogin()
                scheduleRecovery()
                return@run
            }
            val session = document.getAsJsonObject("session")
            val country = normalizeCountry(options.country ?: document.get("country")?.asString ?: "IR")
            val appVersion = options.appVersion ?: metadata.get().versionName
            val contextChanged = session != null &&
                (document.get("country")?.asString != country || document.get("appVersion")?.asString != appVersion)
            if (session != null && (options.appUserIdentifier == null || options.appUserIdentifier == session.string("appUserId")) &&
                !contextChanged && document.get("sessionInvalid")?.asBoolean != true &&
                isoMillis(session.string("sessionExpiresAt")) > now()) {
                if (!snapshot.isConfigured || snapshot.appUserIdentifier != session.string("appUserId")) publish()
                scheduleRecovery(); return@run
            }
            val identity = options.appUserIdentifier ?: session?.string("appUserId")
                ?: document.get("anonymousId")?.asString ?: anonymousId().also { id ->
                    commit(document.deepCopy().apply { addProperty("anonymousId", id) })
                }
            configureLocked(identity)
            scheduleRecovery()
        } }

    /** Invalid cache is never published, but must not permanently prevent online recovery. */
    private fun verifiedCache(raw: String, requestedIdentity: String?): JsonObject? = try {
        val saved = jsonObject(raw)
        val session = saved.getAsJsonObject("session")
        if (session == null) {
            if (saved.get("anonymousId")?.asString?.let(::isAnonymous) != true) null else saved
        } else if (requestedIdentity != null && requestedIdentity != session.string("appUserId")) null
        else {
            if (session.string("sessionType") != "public")
                fail("SIGNATURE", "SCOPE_MISMATCH")
            session.number("storePlatform"); isoMillis(session.string("sessionExpiresAt"))
            if (saved.get("sessionInvalid")?.asBoolean != true && session.string("sessionToken").isBlank())
                fail("SIGNATURE", "INVALID_SESSION")
            val verified = verifier.verifyWithScope(session, session.string("appUserId"), allowExpiredCache = true)
            if (session.number("appId") != verified.scope.appId ||
                (saved.has("scope") && saved.getAsJsonObject("scope").sessionScope() != verified.scope))
                fail("SIGNATURE", "SCOPE_MISMATCH")
            if (saved.has("customerSubject") && saved.string("customerSubject") != verified.subject)
                verificationMismatch("sub", "stored binding must match signed session", saved.get("customerSubject"),
                    JsonPrimitive(verified.subject), "signedSession")
            val info = verifier.verifyWithScope(saved.getAsJsonObject("info") ?: session,
                session.string("appUserId"), verified.scope, allowExpiredCache = true, expectedSubject = verified.subject).info
            InappifyDomainJsonCodec.parseCustomerInfo(info.toString())
            saved.getAsJsonObject("offerings")?.let { InappifyDomainJsonCodec.parseOfferings(it.toString()) }
            saved.add("scope", verified.scope.toJson())
            // Older Go caches have no separate binding; derive it only from verified signed data.
            saved.addProperty("customerSubject", verified.subject)
            saved.getAsJsonObject("session")?.remove("forceVersion")
            saved.getAsJsonObject("info")?.remove("forceVersion")
            saved.getAsJsonObject("offerings")?.apply {
                remove("forceVersion"); remove("hasForceUpdate")
            }
            saved
        }
    } catch (_: Exception) { null }

    /** The explicit Go configuration's older cache is reusable only after authenticating its scope. */
    private fun cacheMatches(stored: PersistedSession, options: InappifyOptions): Boolean {
        if (stored.apiKeyFingerprint == fingerprint(options)) return true
        if (config.expectedScope != null) return false
        return runCatching {
            val session = jsonObject(stored.customerInfoJson!!).getAsJsonObject("session") ?: return false
            val verified = verifier.verifyWithScope(session, session.string("appUserId"), allowExpiredCache = true)
            stored.apiKeyFingerprint == explicitFingerprint(options, verified.scope)
        }.getOrDefault(false)
    }

    private fun boundScope(state: JsonObject = document): InappifyV2SessionScope? =
        state.getAsJsonObject("scope")?.sessionScope() ?: config.expectedScope

    private fun boundSubject(): String? = document.get("customerSubject")?.asString

    private suspend fun configureLocked(identity: String, preservePendingLogin: Boolean = false,
        preservePendingLogout: Boolean = pendingLogout) {
        val opt = options ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        val app = metadata.get()
        val country = opt.country ?: document.get("country")?.asString ?: resolveCountry()
        val response = sdkApi.request("configure", opt.apiKey, JsonObject().apply {
            addProperty("appUserIdentifier", identity)
            addProperty("identifierValue", app.packageIdentifier)
            addProperty("versionName", opt.appVersion ?: app.versionName)
            addProperty("versionCode", app.versionCode)
            addProperty("sdkVersion", BuildConfig.SDK_VERSION)
            addProperty("country", normalizeCountry(country))
        })
        verifiedResponse("configure", mayHaveCommitted = true) {
            acceptSession(response, identity, keepLogoutPending = preservePendingLogout,
                keepLoginPending = preservePendingLogin, configuredCountry = country)
        }
    }

    private suspend fun resolveCountry(): String {
        detectedCountry?.let { return it }
        val country = try { countryResolver() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        return (country?.takeIf(::validCountry)?.let(::normalizeCountry) ?: "IR")
            .also { detectedCountry = it }
    }

    private suspend fun verifyResponse(response: JsonObject, identity: String,
        expectedSubject: String? = boundSubject()): VerifiedCustomerInfo {
        val verification = response.getAsJsonObject("verification") ?: fail("SIGNATURE", "MISSING_VERIFICATION")
        val kid = verification.string("keyId")
        if (!verifier.known(kid)) verifier.refresh(sdkApi.request("public-keys", null))
        return verifier.verifyWithScope(response, identity, boundScope(), expectedSubject = expectedSubject)
    }

    private suspend fun verify(response: JsonObject, identity: String): JsonObject = verifyResponse(response, identity).info

    private suspend fun acceptSession(response: JsonObject, expectedIdentity: String?, keepLogoutPending: Boolean = false,
        identityTransition: Boolean = false, configuredCountry: String? = null,
        keepLoginPending: Boolean = false) {
        val identity = response.string("appUserId")
        if (expectedIdentity != null && identity != expectedIdentity)
            verificationMismatch("appUserId", "exact requested app-user match", response.get("appUserId"),
                JsonPrimitive(expectedIdentity), "request", "IDENTITY_MISMATCH", signatureVerified = false)
        if (expectedIdentity == null && !isAnonymous(identity)) fail("SIGNATURE", "EXPECTED_ANONYMOUS")
        if (response.number("appId") <= 0 || response.string("sessionType") != "public" ||
            response.string("sessionToken").isBlank() || isoMillis(response.string("sessionExpiresAt")) <= now())
            fail("SIGNATURE", "INVALID_SESSION")
        response.number("storePlatform")
        response.get("storeInfo")?.takeUnless { it.isJsonNull }?.let { response.string("storeInfo") }
        val identityChanged = document.getAsJsonObject("session")?.string("appUserId") != identity
        // Only a verified lifecycle identity transition may replace the internal customer binding.
        // Ordinary Configure reuse/renewal and resource refresh must retain the current subject.
        val verified = verifyResponse(response, identity,
            expectedSubject = if (identityChanged || identityTransition) null else boundSubject())
        if (response.number("appId") != verified.scope.appId)
            verificationMismatch("appId", "must equal signed app_id", response.get("appId"),
                JsonPrimitive(verified.scope.appId), "signedCustomerInfo")
        InappifyDomainJsonCodec.parseCustomerInfo(verified.info.toString())
        val bindingChanged = identityChanged || boundSubject() != verified.subject
        val next = if (bindingChanged) JsonObject() else document.deepCopy()
        next.add("scope", verified.scope.toJson())
        next.addProperty("customerSubject", verified.subject)
        val v2Response = response.deepCopy().apply { remove("forceVersion"); remove("hasForceUpdate") }
        next.add("session", v2Response.deepCopy())
        next.add("info", v2Response)
        if (keepLoginPending) document.getAsJsonObject("pendingLogin")?.let { next.add("pendingLogin", it.deepCopy()) }
        else next.remove("pendingLogin")
        next.addProperty("infoFetchedAt", now())
        next.addProperty("country", normalizeCountry(configuredCountry ?: options?.country ?: document.get("country")?.asString ?: "IR"))
        next.addProperty("appVersion", options?.appVersion ?: metadata.get().versionName)
        if (!keepLogoutPending) next.remove("pendingLogout")
        next.remove("sessionInvalid")
        // V2 has no forceVersion. A new session may reflect changed offerings.
        next.remove("offerings")
        if (isAnonymous(identity)) next.addProperty("anonymousId", identity)
        commit(next)
        if (bindingChanged) unbindPurchaseClient()
    }

    private suspend fun sessionRequest(endpoint: String, body: JsonObject = JsonObject(),
        retry: Boolean = true, allowLogout: Boolean = false): JsonObject {
        if (pendingLogout && !allowLogout) fail("AUTH", "LOGOUT_PENDING")
        if (pendingLogin) fail("AUTH", "LOGIN_PENDING")
        var session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        if (document.get("sessionInvalid")?.asBoolean == true || isoMillis(session.string("sessionExpiresAt")) <= now()) {
            renewSession(session.string("appUserId"))
            session = document.getAsJsonObject("session")
        }
        return try { sdkApi.request(endpoint, session.string("sessionToken"), body, retry) }
        catch (e: V2Failure) {
            if (e.sdkError.details["serverCode"] !in setOf("SESSION_REQUIRED", "SESSION_INVALID", "SESSION_EXPIRED")) throw e
            // The state mutex makes reconfiguration single-flight for all resource operations.
            if (e.sdkError.details["serverCode"] == "SESSION_INVALID") {
                commit(document.deepCopy().apply {
                    addProperty("sessionInvalid", true)
                    getAsJsonObject("session").remove("sessionToken")
                })
            }
            renewSession(session.string("appUserId"))
            sdkApi.request(endpoint, document.getAsJsonObject("session").string("sessionToken"), body, retry)
        }
    }

    private suspend fun renewSession(identity: String) {
        if (now() < nextRenewalAt) renewalFailure?.let { throw it }
        try {
            configureLocked(identity)
            renewalFailure = null; nextRenewalAt = 0
        } catch (e: V2Failure) {
            renewalFailure = e; nextRenewalAt = now() + 15000
            throw e
        }
    }

    override suspend fun login(request: InappifyLoginRequest): InappifyResult<Unit> = run {
        if (!validIdentity(request.appUserIdentifier) || isAnonymous(request.appUserIdentifier))
            fail("VALIDATION", "APP_USER_ID_INVALID")
        if (pendingLogout) fail("AUTH", "LOGOUT_PENDING")
        val pending = document.getAsJsonObject("pendingLogin")
        if (pending != null) {
            if (pending.string("appUserId") != request.appUserIdentifier) fail("AUTH", "LOGIN_PENDING")
        } else {
            var session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
            if (document.get("sessionInvalid")?.asBoolean == true ||
                isoMillis(session.string("sessionExpiresAt")) <= now()) {
                renewSession(session.string("appUserId"))
                session = document.getAsJsonObject("session")
            }
            commit(document.deepCopy().apply {
                add("pendingLogin", JsonObject().apply {
                    addProperty("appUserId", request.appUserIdentifier)
                    addProperty("attemptId", UUID.randomUUID().toString())
                })
            })
        }
        resumeLogin()
        scheduleRecovery()
    }
    private suspend fun resumeLogin(allowFreshPredecessor: Boolean = true) {
        val pending = document.getAsJsonObject("pendingLogin") ?: fail("CONFIGURATION", "LOGIN_NOT_PENDING")
        val session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        val target = pending.string("appUserId")
        val attemptId = pending.string("attemptId")
        val response = try {
            sdkApi.request("login", session.string("sessionToken"), JsonObject().apply {
                addProperty("appUserIdentifier", target)
            }, headers = mapOf("X-Inappify-Login-Attempt" to attemptId))
        } catch (e: V2Failure) {
            if (allowFreshPredecessor && e.sdkError.details["serverCode"] == "LOGIN_ATTEMPT_NOT_FOUND") {
                // Go proved no transition committed for this signed predecessor.
                // Configure the same old identity, preserving the attempt atomically,
                // then submit it from the newly issued predecessor session.
                configureLocked(session.string("appUserId"), preservePendingLogin = true)
                return resumeLogin(allowFreshPredecessor = false)
            }
            // These responses are defined before commit, so the predecessor remains usable.
            if (e.sdkError.details["serverCode"] in setOf("APP_USER_ID_INVALID", "CUSTOMER_MERGE_FAILED"))
                commit(document.deepCopy().apply { remove("pendingLogin") })
            throw e
        }
        verifiedResponse("login", mayHaveCommitted = true) {
            acceptSession(response, target, identityTransition = true)
        }
    }
    override suspend fun logout(): InappifyResult<Unit> = run { logoutLocked() }
    private suspend fun logoutLocked(allowFreshPredecessor: Boolean = true) {
        if (pendingLogin) fail("AUTH", "LOGIN_PENDING")
        if (!pendingLogout) {
            var session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
            if (document.get("sessionInvalid")?.asBoolean == true ||
                isoMillis(session.string("sessionExpiresAt")) <= now()) {
                renewSession(session.string("appUserId"))
                session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
            }
            commit(document.deepCopy().apply {
                addProperty("pendingLogout", true)
                add("pendingLogoutAttempt", JsonObject().apply {
                    addProperty("attemptId", UUID.randomUUID().toString() + UUID.randomUUID().toString())
                })
            })
            unbindPurchaseClient()
        }
        val attempt = document.getAsJsonObject("pendingLogoutAttempt")
            ?: fail("AUTH", "LOGOUT_RECOVERY_UNAVAILABLE")
        val session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        val response = try {
            sdkApi.request("logout", session.string("sessionToken"), retry = false,
                headers = mapOf("X-Inappify-Logout-Attempt" to attempt.string("attemptId")))
        } catch (e: V2Failure) {
            if (allowFreshPredecessor && e.sdkError.details["serverCode"] == "LOGOUT_ATTEMPT_NOT_FOUND") {
                configureLocked(session.string("appUserId"))
                try {
                    return logoutLocked(allowFreshPredecessor = false)
                } catch (replayFailure: V2Failure) {
                    if (replayFailure.sdkError.details["serverCode"] !in setOf("SESSION_REVOKED", "SESSION_EXPIRED"))
                        throw replayFailure
                    configureLocked(anonymousId(), preservePendingLogout = false)
                    scheduleRecovery()
                    return
                }
            }
            if (e.sdkError.details["serverCode"] in setOf("SESSION_REVOKED", "SESSION_EXPIRED")) {
                // The predecessor cannot grant access. A signed fresh anonymous
                // Configure completes local logout if replay has become impossible.
                configureLocked(anonymousId(), preservePendingLogout = false)
                scheduleRecovery()
                return
            }
            throw e
        }
        verifiedResponse("logout", mayHaveCommitted = true) {
            acceptSession(response, null, identityTransition = true)
        }
        scheduleRecovery()
    }

    override suspend fun getCustomerInfo(forceRefresh: Boolean): InappifyResult<InappifyCustomerInfo> =
        customerInfo(if (forceRefresh) InappifyFetchPolicy.NETWORK_ONLY else InappifyFetchPolicy.CACHE_FIRST)
    override suspend fun refreshCustomerInfo(): InappifyResult<InappifyCustomerInfo> =
        customerInfo(InappifyFetchPolicy.NETWORK_ONLY)
    override suspend fun customerInfo(policy: InappifyFetchPolicy): InappifyResult<InappifyCustomerInfo> {
        val cached = snapshot.customerInfo
        if (cached != null && policy in setOf(InappifyFetchPolicy.CACHE_ONLY, InappifyFetchPolicy.CACHE_FIRST)) {
            if (policy == InappifyFetchPolicy.CACHE_FIRST && backgroundRecovery) scope.launch { refreshCustomerInfo() }
            return InappifyResult.Success(cached, snapshot)
        }
        if (policy == InappifyFetchPolicy.CACHE_ONLY) return run { fail("CONFIGURATION", "CACHE_MISS") }
        val result = shared("customer:${snapshot.appUserIdentifier}") { run {
            val response = sessionRequest("customerInfo")
            val info = verifiedResponse("customerInfo") {
                verify(response, document.getAsJsonObject("session").string("appUserId"))
            }
            val parsed = InappifyDomainJsonCodec.parseCustomerInfo(info.toString())
            commit(document.deepCopy().apply { add("info", response); addProperty("infoFetchedAt", now()) })
            parsed
        } }
        return if (policy == InappifyFetchPolicy.NETWORK_FIRST && result is InappifyResult.Failure &&
            snapshot.customerInfo != null) InappifyResult.Success(snapshot.customerInfo!!, snapshot) else result
    }

    override suspend fun getOfferings(): InappifyResult<InappifyOfferings> = offerings(InappifyFetchPolicy.CACHE_FIRST)
    override suspend fun refreshOfferings(): InappifyResult<InappifyOfferings> = offerings(InappifyFetchPolicy.NETWORK_ONLY)
    override suspend fun offerings(policy: InappifyFetchPolicy): InappifyResult<InappifyOfferings> {
        val cached = snapshot.offerings
        if (cached != null && policy in setOf(InappifyFetchPolicy.CACHE_ONLY, InappifyFetchPolicy.CACHE_FIRST)) {
            if (policy == InappifyFetchPolicy.CACHE_FIRST && backgroundRecovery) scope.launch { refreshOfferings() }
            return InappifyResult.Success(cached, snapshot)
        }
        if (policy == InappifyFetchPolicy.CACHE_ONLY) return run { fail("CONFIGURATION", "CACHE_MISS") }
        val result = shared("offerings:${snapshot.appUserIdentifier}") { run {
            flushAttributesLocked()
            val response = sessionRequest("offerings", JsonObject().apply {
                addProperty("appVersion", snapshot.appVersion ?: metadata.get().versionName)
                addProperty("sdkVersion", BuildConfig.SDK_VERSION)
                addProperty("country", snapshot.country)
                // Only the native package renderer is advertised until the element schema is supplied.
                addProperty("paywallSchemaVersion", 1)
                addProperty("paywallRendererVersion", 1)
            })
            val safe = response.deepCopy().apply {
                remove("rules"); remove("forceVersion"); remove("hasForceUpdate")
                if (!has("currentOffering")) add("currentOffering", JsonNull.INSTANCE)
            }
            val parsed = InappifyDomainJsonCodec.parseOfferings(safe.toString())
            commit(document.deepCopy().apply { add("offerings", safe) })
            parsed
        } }
        return if (policy == InappifyFetchPolicy.NETWORK_FIRST && result is InappifyResult.Failure &&
            snapshot.offerings != null) InappifyResult.Success(snapshot.offerings!!, snapshot) else result
    }
    override suspend fun getCurrentOffering(placementIdentifier: String?, forceRefresh: Boolean,
        context: InappifyOfferingEvaluationContext?): InappifyResult<InappifyOffering?> {
        val fetched = if (forceRefresh) refreshOfferings() else getOfferings()
        if (fetched is InappifyResult.Failure) return fetched
        return run {
            val raw = document.getAsJsonObject("offerings")
            val items = snapshot.offerings?.offerings.orEmpty()
            val placement = raw?.getAsJsonObject("placements")?.get(placementIdentifier)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val current = raw?.get("currentOffering")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            items.firstOrNull { it.identifier == placement && placement != null }
                ?: items.firstOrNull { it.identifier == current && current != null }
                ?: items.firstOrNull { it.isDefault == true }
        }
    }
    override suspend fun setTargetingContext(country: String?, appVersion: String?): InappifyResult<Unit> = run {
        requireSession()
        if (country != null && !validCountry(country)) fail("VALIDATION", "INVALID_COUNTRY")
        if (appVersion != null && (appVersion.isBlank() || appVersion.length > 50)) fail("VALIDATION", "INVALID_VERSION")
        commit(document.deepCopy().apply {
            country?.let { addProperty("country", normalizeCountry(it)) }; appVersion?.let { addProperty("appVersion", it) }
            remove("offerings")
        })
    }

    override suspend fun validateDiscountCode(request: InappifyDiscountCodeRequest): InappifyResult<InappifyDiscountCodeResult> = run {
        val response = sessionRequest("validateDiscountCode", JsonObject().apply { addProperty("code", request.discountCode) })
        val data = response.getAsJsonObject("data") ?: fail("DECODING", "INVALID_DISCOUNT")
        data.flag("is_valid"); data.number("error_code")
        val result = InappifyDomainJsonCodec.parseDiscountCodeResult(data.toString())
        result.paymentLinks.orEmpty().forEach { if (!allowedUrl(it.url, config.paymentHosts)) fail("DECODING", "UNTRUSTED_PAYMENT_URL") }
        result
    }

    override suspend fun queueAttributes(values: Map<String, String?>): InappifyResult<Unit> = run {
        requireSession()
        values.forEach { (key, value) ->
            if (!validAttribute(key, value)) fail("VALIDATION", "ATTRIBUTE_INVALID")
        }
        val next = document.deepCopy()
        val queue = next.getAsJsonObject("attributes") ?: JsonObject().also { next.add("attributes", it) }
        values.forEach { (key, value) -> queue.add(key, if (value.isNullOrEmpty()) JsonNull.INSTANCE else JsonPrimitive(value)) }
        if (queue.size() > 1000) fail("VALIDATION", "ATTRIBUTE_QUEUE_FULL")
        next.remove("offerings")
        commit(next)
        if (backgroundRecovery) scope.launch { delay(300); flushAttributes() }
    }
    override suspend fun flushAttributes(): InappifyResult<Unit> = shared("attributes:${snapshot.appUserIdentifier}") { run { flushAttributesLocked() } }
    private suspend fun flushAttributesLocked() {
        requireSession()
        while (true) {
            val queue = document.getAsJsonObject("attributes") ?: return
            val entries = queue.entrySet().take(50)
            if (entries.isEmpty()) return
            val batch = JsonObject().apply { entries.forEach { add(it.key, it.value.deepCopy()) } }
            try { sessionRequest("attributes", JsonObject().apply { add("attributes", batch) }) }
            catch (e: V2Failure) {
                if (e.sdkError.details["httpStatus"] == 422) {
                    // No reliable field-level error contract: quarantine this rejected batch intact.
                    val next = document.deepCopy()
                    val quarantine = next.getAsJsonObject("quarantine") ?: JsonObject().also { next.add("quarantine", it) }
                    entries.forEach { quarantine.add(it.key, it.value); next.getAsJsonObject("attributes").remove(it.key) }
                    commit(next)
                }
                throw e
            }
            commit(document.deepCopy().apply { entries.forEach { getAsJsonObject("attributes").remove(it.key) } })
        }
    }
    override suspend fun setAttributes(request: InappifyAttributesRequest): InappifyResult<List<InappifyAttribute>> {
        val queued = queueAttributes(request.attributes.associate { (it.key ?: "") to it.value })
        return when (queued) {
            is InappifyResult.Failure -> queued
            is InappifyResult.Success -> when (val flushed = flushAttributes()) {
                is InappifyResult.Failure -> flushed
                is InappifyResult.Success -> InappifyResult.Success(emptyList(), snapshot)
            }
        }
    }
    override suspend fun deleteAttributes(request: InappifyDeleteAttributesRequest): InappifyResult<List<InappifyAttribute>> =
        setAttributes(InappifyAttributesRequest(request.keys.map { InappifyAttribute(it, null) }))
    override suspend fun syncAttributes(request: InappifyAttributesRequest?): InappifyResult<List<InappifyAttribute>> =
        if (request != null) setAttributes(request) else when (val result = flushAttributes()) {
            is InappifyResult.Failure -> result
            is InappifyResult.Success -> InappifyResult.Success(emptyList(), snapshot)
        }
    override suspend fun setReservedAttribute(request: InappifyReservedAttributeRequest): InappifyResult<Unit> =
        when (val result = setAttributes(InappifyAttributesRequest(listOf(InappifyAttribute(request.attribute.backendKey, request.value))))) {
            is InappifyResult.Failure -> result
            is InappifyResult.Success -> InappifyResult.Success(Unit, snapshot)
        }
    override suspend fun canSetReservedAttribute(key: String): InappifyResult<Boolean> = run { key in reservedKeys }

    override fun getEntitlement(identifier: String): InappifyEntitlement? = snapshot.customerInfo?.entitlements?.firstOrNull {
        it.identifier == identifier && it.isActive == true && (it.expirationDate.isNullOrBlank() ||
            runCatching { isoMillis(it.expirationDate) > trustedNow() }.getOrDefault(false))
    }
    override fun isActiveEntitlement(identifier: String): Boolean = getEntitlement(identifier) != null
    override fun hasEntitlement(identifier: String): Boolean = isActiveEntitlement(identifier)
    override fun isCustomerAnonymous(appUserIdentifier: String?): Boolean = appUserIdentifier?.let(::isAnonymous) == true
    override suspend fun checkEntitlement(identifier: String, forceRefresh: Boolean): InappifyResult<Boolean> =
        when (val result = getCustomerInfo(forceRefresh)) {
            is InappifyResult.Failure -> result
            is InappifyResult.Success -> InappifyResult.Success(isActiveEntitlement(identifier), snapshot)
        }

    override suspend fun bindLegacyPurchaseClient(client: InappifyClient): InappifyResult<Unit> = run {
        requireSession()
        if (client !is DefaultInappifyClient) fail("CONFIGURATION", "LARAVEL_CREDENTIAL_REQUIRED")
        val companion = client.createPurchaseCompanion()
        try { checkPurchaseScope(companion) } catch (e: Exception) { companion.close(); throw e }
        unbindPurchaseClient()
        purchaseClient = companion
        if (snapshot.storePlatform == "DirectAndroid")
            deliveryHandler?.let { handlerRegistration = companion.setConsumableDeliveryHandler(it) }
    }
    private fun checkPurchaseScope(client: InappifyClient) {
        val candidate = client.snapshot
        if (!candidate.isConfigured || candidate.appId != snapshot.appId || candidate.appUserIdentifier != snapshot.appUserIdentifier ||
            candidate.storePlatform != snapshot.storePlatform) fail("CONFIGURATION", "PURCHASE_CREDENTIAL_SCOPE_MISMATCH")
    }
    private fun requirePurchaseClient(): InappifyClient {
        if (!sdkApi.enabled) fail("CONFIGURATION", "V2_DISABLED")
        requireSession()
        val client = purchaseClient ?: fail("CONFIGURATION", "LARAVEL_CREDENTIAL_REQUIRED")
        checkPurchaseScope(client)
        return client
    }
    private fun unbindPurchaseClient() {
        handlerRegistration?.close(); handlerRegistration = null
        purchaseClient?.close(); purchaseClient = null
    }
    override suspend fun purchase(request: InappifyPurchaseRequest): InappifyResult<InappifyPurchase> = purchaseInternal(null, request)
    override suspend fun purchase(activity: Activity, request: InappifyPurchaseRequest): InappifyResult<InappifyPurchase> = purchaseInternal(activity, request)
    private suspend fun purchaseInternal(activity: Activity?, request: InappifyPurchaseRequest): InappifyResult<InappifyPurchase> {
        val result = run {
            if (request.apiKey != null || request.marketKey != null || request.isLostPurchase || request.dynamicPriceToken != null)
                fail("VALIDATION", "PURCHASE_OVERRIDE_NOT_ALLOWED")
            val offering = snapshot.offerings?.offerings?.firstOrNull { it.identifier == request.offeringIdentifier }
            val selectedPackage = offering?.packages?.firstOrNull { it.product?.identifier == request.productIdentifier &&
                (request.packageIdentifier == null || it.identifier == request.packageIdentifier) }
                ?: fail("VALIDATION", "PRODUCT_NOT_IN_OFFERING")
            val purchase = when (snapshot.storePlatform) {
                "DirectAndroid" -> purchaseDirectV2(request, selectedPackage.identifier)
                "Bazar" -> purchaseStoreV2(activity, request, selectedPackage.identifier)
                else -> fail("CONFIGURATION", "UNSUPPORTED_STORE_PLATFORM")
            }
            if (purchase.url != null && !allowedUrl(purchase.url, config.paymentHosts))
                fail("DECODING", "UNTRUSTED_PAYMENT_URL", diagnostics = mapOf("outcomeMayHaveCommitted" to true))
            purchase
        }
        if (result is InappifyResult.Success) refreshCustomerInfo()
        return result.withSnapshot()
    }
    private suspend fun purchaseDirectV2(request: InappifyPurchaseRequest, packageIdentifier: String?): InappifyPurchase {
        if (!sdkApi.enabled) fail("CONFIGURATION", "V2_DISABLED")
        requireSession()
        if (request.country != null || request.appVersion != null || request.market != InappifyMarket.NONE)
            fail("VALIDATION", "PURCHASE_OVERRIDE_NOT_ALLOWED")
        if (request.idempotencyKey != null && !request.idempotencyKey.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
            fail("VALIDATION", "PURCHASE_ATTEMPT_INVALID")
        val purchaseApi = commerceApi ?: fail("CONFIGURATION", "LARAVEL_V2_NOT_CONFIGURED")
        var session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        if (document.get("sessionInvalid")?.asBoolean == true || isoMillis(session.string("sessionExpiresAt")) <= now()) {
            renewSession(session.string("appUserId"))
            session = document.getAsJsonObject("session")
        }
        val response = try {
            // Payment creation has no idempotency key on Laravel; never automatically retry it.
            purchaseApi.request("purchase", session.string("sessionToken"), JsonObject().apply {
                addProperty("productIdentifier", request.productIdentifier)
                addProperty("offeringIdentifier", request.offeringIdentifier)
                addProperty("isCrypto", request.isCrypto)
                request.paywallId?.let { addProperty("paywallId", it) }
                request.paywallRevision?.let { addProperty("paywallRevision", it) }
            }, retry = false)
        } catch (e: V2Failure) {
            val category = e.sdkError.details["category"]
            val status = e.sdkError.details["httpStatus"] as? Int
            val uncertain = category in setOf("NETWORK", "TIMEOUT", "DECODING") ||
                (status != null && status >= 500)
            if (!uncertain) throw e
            throw V2Failure(InappifyError(e.sdkError.code, e.sdkError.message, e.sdkError.isRetryable,
                e.sdkError.details + mapOf("outcomeMayHaveCommitted" to true)))
        }
        val data = response.getAsJsonObject("data") ?: fail("DECODING", "INVALID_PURCHASE", diagnostics =
            mapOf("outcomeMayHaveCommitted" to true))
        val status = runCatching { InappifyPurchaseStatus.fromServerValue(data.string("purchaseStatus")) }
            .getOrNull() ?: fail("DECODING", "INVALID_PURCHASE_STATUS", diagnostics =
                mapOf("outcomeMayHaveCommitted" to true))
        val url = data.get("url")?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString)
                fail("DECODING", "INVALID_PAYMENT_URL", diagnostics = mapOf("outcomeMayHaveCommitted" to true))
            it.asString
        }
        if (status == InappifyPurchaseStatus.NEEDTOPAY && url.isNullOrBlank())
            fail("DECODING", "MISSING_PAYMENT_URL", diagnostics = mapOf("outcomeMayHaveCommitted" to true))
        return InappifyPurchase(request.idempotencyKey ?: UUID.randomUUID().toString(),
            request.productIdentifier, request.offeringIdentifier, InappifyMarket.NONE, status,
            packageIdentifier, url)
    }
    private suspend fun storeSessionToken(): String {
        requireSession()
        var session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        if (document.get("sessionInvalid")?.asBoolean == true || isoMillis(session.string("sessionExpiresAt")) <= now()) {
            renewSession(session.string("appUserId"))
            session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        }
        return session.string("sessionToken")
    }
    private suspend fun storeContext(): StoreV2Context {
        val opt = options ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        val session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        return StoreV2Context(opt.apiKey, storeSessionToken(), fingerprint(opt),
            digest(session.string("appUserId")), metadata.get().packageIdentifier,
            session.number("appId"), document.get("country")?.asString ?: "IR",
            document.get("appVersion")?.asString ?: metadata.get().versionName,
            null, opt.marketKey)
    }
    private suspend fun purchaseStoreV2(activity: Activity?, request: InappifyPurchaseRequest,
        packageIdentifier: String?): InappifyPurchase {
        if (request.country != null || request.appVersion != null || request.market != InappifyMarket.NONE ||
            request.isCrypto || request.discount != 0L || request.discountCode != null ||
            request.paywallId != null || request.paywallRevision != null)
            fail("VALIDATION", "PURCHASE_OVERRIDE_NOT_ALLOWED")
        val coordinator = storeCoordinator ?: fail("CONFIGURATION", "LARAVEL_V2_NOT_CONFIGURED")
        val context = storeContext()
        if (context.marketKey.isNullOrBlank()) fail("CONFIGURATION", "BAZAAR_PUBLIC_KEY_REQUIRED")
        val attemptId = request.idempotencyKey ?: UUID.randomUUID().toString()
        if (!attemptId.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) fail("VALIDATION", "PURCHASE_ATTEMPT_INVALID")
        val recovery = storage.loadPendingStoreRecoveryState() ?: fail("STORAGE", "STORE_RECOVERY_UNAVAILABLE")
        val existing = recovery.operations.firstOrNull { it.id == attemptId }
        if (existing != null) {
            if (existing.appId != context.appId || existing.apiKeyFingerprint != context.apiKeyFingerprint ||
                existing.customerIdentifierFingerprint != context.customerIdentifierFingerprint ||
                existing.productIdentifier != request.productIdentifier ||
                existing.offeringIdentifier != request.offeringIdentifier)
                fail("VALIDATION", "PURCHASE_ATTEMPT_CONFLICT")
            val outcome = coordinator.resume(existing, context, StoreV2Coordinator.MAX_FOREGROUND_POLLS)
            finalizeRejectedStoreOutcome(outcome, context)
            return storePurchaseResult(outcome, packageIdentifier)
        }
        val host = activity ?: fail("VALIDATION", "STORE_UI_HOST_REQUIRED")
        val adapter = billingFactory.create(InappifyMarket.BAZAAR, context.marketKey)
        val developerPayload = JsonObject().apply {
            addProperty("offeringIdentifier", request.offeringIdentifier)
            addProperty("productIdentifier", request.productIdentifier)
            addProperty("nativePackageIdentifier", packageIdentifier)
            addProperty("attemptId", attemptId)
            request.productType?.let { addProperty("productType", it.name) }
            addProperty("recoveryBinding", digest("${context.apiKeyFingerprint}:${context.customerIdentifierFingerprint}"))
        }.toString()
        val receipt = try {
            when (val billed = adapter.purchase(StoreUiHost.from(host), StorePurchaseRequest(
                request.productIdentifier,
                if (request.productType == InappifyProductType.SUBSCRIPTION) StoreProductType.SUBSCRIPTION
                else StoreProductType.IN_APP, developerPayload))) {
                is StoreBillingResult.Success -> billed.purchase
                is StoreBillingResult.Cancelled -> throw V2Failure(InappifyError(InappifyErrorCode.PURCHASE_CANCELLED,
                    "The marketplace purchase was cancelled."))
                is StoreBillingResult.Failure -> throw V2Failure(InappifyError(InappifyErrorCode.STORE_UNAVAILABLE,
                    billed.error.message, billed.error.isRetryable))
            }
        } finally { adapter.close() }
        if (receipt.productIdentifier != request.productIdentifier || receipt.packageName != context.appIdentifier)
            fail("VALIDATION", "STORE_RECEIPT_SCOPE_MISMATCH")
        val productType = when (request.productType) {
            InappifyProductType.CONSUMABLE -> PendingStoreProductType.CONSUMABLE
            InappifyProductType.NON_CONSUMABLE -> PendingStoreProductType.NON_CONSUMABLE
            InappifyProductType.SUBSCRIPTION -> PendingStoreProductType.SUBSCRIPTION
            null -> PendingStoreProductType.LEGACY_IN_APP
        }
        val operation = PendingStoreOperation(attemptId, PendingStoreOperationType.PURCHASE, "bazar",
            context.customerToken, context.customerIdentifierFingerprint, context.apiKeyFingerprint,
            context.appIdentifier, context.appId, request.productIdentifier, request.offeringIdentifier,
            productType, PendingStorePurchaseEvidence(receipt.purchaseToken,
                receipt.orderIdentifier.takeIf(String::isNotBlank), receipt.packageName,
                receipt.developerPayload.takeIf(String::isNotBlank),
                receipt.originalJson.takeIf(String::isNotBlank), receipt.signature.takeIf(String::isNotBlank),
                receipt.purchaseTimeMillis), createdAtEpochMillis = now())
        if (storeTombstone(operation, context) in recovery.rejectedEvidenceTombstones)
            fail("VALIDATION", "STORE_EVIDENCE_PREVIOUSLY_REJECTED")
        val outcome = coordinator.submit(operation, context)
        finalizeRejectedStoreOutcome(outcome, context)
        return storePurchaseResult(outcome, packageIdentifier)
    }
    private fun storeTombstone(operation: PendingStoreOperation, context: StoreV2Context): RejectedStoreEvidenceTombstone {
        fun combined(vararg values: Any?): String = digest(values.joinToString(":") { value ->
            val part = value?.toString().orEmpty()
            "${part.length}:$part"
        })
        val evidence = operation.evidence
        return RejectedStoreEvidenceTombstone(
            purchaseTokenFingerprint = digest(evidence.purchaseToken),
            apiKeyFingerprint = operation.apiKeyFingerprint,
            customerIdentifierFingerprint = operation.customerIdentifierFingerprint,
            appFingerprint = combined(operation.appIdentifier, operation.appId),
            productIdentifierFingerprint = digest(operation.productIdentifier),
            productTypeFingerprint = digest(operation.productType.name),
            offeringIdentifierFingerprint = combined(operation.offeringIdentifier),
            appVersionFingerprint = combined(context.appVersion),
            forceVersionFingerprint = combined(null),
            operationFingerprint = digest(operation.operation.name),
            countryFingerprint = combined(context.country),
            evidenceFingerprint = combined(evidence.purchaseTimeMillis, evidence.orderId,
                evidence.packageName, evidence.developerPayload, evidence.originalJson, evidence.signature),
        )
    }
    private suspend fun finalizeRejectedStoreOutcome(outcome: StoreV2Outcome, context: StoreV2Context) {
        val permanent = when (outcome) {
            is StoreV2Outcome.Rejected -> outcome.state?.status == StorePurchaseStatus.REJECTED ||
                outcome.requiresRejectionTombstone
            is StoreV2Outcome.Failure -> outcome.requiresRejectionTombstone
            else -> false
        }
        if (permanent && !storage.finalizePendingStoreOperationWithTombstone(outcome.operation.id,
                storeTombstone(outcome.operation, context))) fail("STORAGE", "STORE_REJECTION_NOT_PERSISTED")
    }
    private fun storePurchaseResult(outcome: StoreV2Outcome, packageIdentifier: String?): InappifyPurchase {
        val state = when (outcome) {
            is StoreV2Outcome.Terminal -> outcome.state
            is StoreV2Outcome.PendingDelivery -> outcome.state
            is StoreV2Outcome.Deferred -> outcome.state ?: com.inappify.sdk.internal.network.StorePurchaseState(
                StorePurchaseStatus.PROCESSING, null, null, outcome.operation.deliveryId,
                outcome.operation.verificationRequestId, null, null, null, false)
            is StoreV2Outcome.Rejected -> throw V2Failure(outcome.error)
            is StoreV2Outcome.Failure -> throw V2Failure(outcome.error)
        }
        return InappifyPurchase.storeResult(outcome.operation.id, outcome.operation.productIdentifier,
            outcome.operation.offeringIdentifier.orEmpty(), packageIdentifier,
            InappifyStorePurchaseStatus.fromServerValue(state.status.wireValue),
            state.deliveryId ?: outcome.operation.deliveryId,
            state.verificationRequestId ?: outcome.operation.verificationRequestId,
            state.alreadyProcessed == true || state.status == StorePurchaseStatus.ALREADY_PROCESSED)
    }
    private suspend fun reconcileStorePurchases(queryOwned: Boolean, explicitRestore: Boolean):
        Pair<List<InappifyPurchase>, InappifyRestoreResult> {
        if (snapshot.storePlatform != "Bazar") fail("CONFIGURATION", "UNSUPPORTED_STORE_PLATFORM")
        val coordinator = storeCoordinator ?: fail("CONFIGURATION", "LARAVEL_V2_NOT_CONFIGURED")
        val context = storeContext()
        if (context.marketKey.isNullOrBlank()) fail("CONFIGURATION", "BAZAAR_PUBLIC_KEY_REQUIRED")
        val recovery = storage.loadPendingStoreRecoveryState() ?: fail("STORAGE", "STORE_RECOVERY_UNAVAILABLE")
        val purchases = mutableListOf<InappifyPurchase>()
        var restored = 0
        var already = 0
        var failed = 0
        var queryFailures = 0
        val knownTokens = mutableSetOf<String>()
        for (pending in recovery.operations.filter { it.store == "bazar" &&
            it.apiKeyFingerprint == context.apiKeyFingerprint &&
            it.customerIdentifierFingerprint == context.customerIdentifierFingerprint &&
            it.appId == context.appId }) {
            knownTokens += pending.evidence.purchaseToken
            if (storeTombstone(pending, context) in recovery.rejectedEvidenceTombstones) {
                storage.removePendingStoreOperation(pending.id)
                continue
            }
            val outcome = coordinator.resume(pending, context)
            finalizeRejectedStoreOutcome(outcome, context)
            when (outcome) {
                is StoreV2Outcome.Terminal, is StoreV2Outcome.PendingDelivery, is StoreV2Outcome.Deferred -> {
                    purchases += storePurchaseResult(outcome, null)
                    if (outcome is StoreV2Outcome.Terminal && explicitRestore &&
                        pending.productType != PendingStoreProductType.CONSUMABLE) {
                        if (outcome.state.status == StorePurchaseStatus.ALREADY_PROCESSED) already++ else restored++
                    }
                }
                is StoreV2Outcome.Rejected, is StoreV2Outcome.Failure -> if (explicitRestore) failed++
            }
        }
        if (queryOwned) {
            val adapter = billingFactory.create(InappifyMarket.BAZAAR, context.marketKey)
            try {
                for ((storeType, pendingType) in listOf(
                    StoreProductType.SUBSCRIPTION to PendingStoreProductType.SUBSCRIPTION,
                    StoreProductType.IN_APP to PendingStoreProductType.LEGACY_IN_APP)) {
                    val query = if (adapter is PartialStorePurchaseQueryAdapter)
                        adapter.queryPurchasesPartially(storeType) else adapter.queryPurchases(storeType)
                    when (query) {
                        is StorePurchaseQueryResult.Failure -> { queryFailures++; if (explicitRestore) failed++ }
                        is StorePurchaseQueryResult.Success -> {
                            if (explicitRestore) failed += query.invalidPurchaseCount
                            for (receipt in query.purchases) {
                                if (receipt.packageName != context.appIdentifier ||
                                    !knownTokens.add(receipt.purchaseToken)) continue
                                val payload = runCatching { JsonParser.parseString(receipt.developerPayload).asJsonObject }
                                    .getOrNull()
                                val expectedBinding = digest("${context.apiKeyFingerprint}:${context.customerIdentifierFingerprint}")
                                if (payload == null || runCatching { payload.get("recoveryBinding")?.asString }
                                        .getOrNull() != expectedBinding) {
                                    if (explicitRestore) failed++
                                    continue
                                }
                                val declaredType = runCatching { payload.get("productType")?.asString }.getOrNull()
                                // Never restore a consumable merely because it appears in owned inventory.
                                if (explicitRestore && pendingType == PendingStoreProductType.LEGACY_IN_APP &&
                                    declaredType != InappifyProductType.NON_CONSUMABLE.name) continue
                                val offering = snapshot.offerings?.offerings?.firstOrNull { candidate ->
                                    candidate.packages.any { it.product?.identifier == receipt.productIdentifier }
                                }
                                if (offering == null) { if (explicitRestore) failed++; continue }
                                // Explicit restore never grants consumables from owned inventory.
                                val type = when (declaredType) {
                                    InappifyProductType.CONSUMABLE.name -> PendingStoreProductType.CONSUMABLE
                                    InappifyProductType.NON_CONSUMABLE.name -> PendingStoreProductType.NON_CONSUMABLE
                                    InappifyProductType.SUBSCRIPTION.name -> PendingStoreProductType.SUBSCRIPTION
                                    else -> pendingType
                                }
                                val pending = PendingStoreOperation(
                                    id = "restore:${digest(receipt.purchaseToken).take(64)}",
                                    operation = if (explicitRestore) PendingStoreOperationType.RESTORE
                                        else PendingStoreOperationType.PURCHASE,
                                    store = "bazar", customerToken = context.customerToken,
                                    customerIdentifierFingerprint = context.customerIdentifierFingerprint,
                                    apiKeyFingerprint = context.apiKeyFingerprint,
                                    appIdentifier = context.appIdentifier, appId = context.appId,
                                    productIdentifier = receipt.productIdentifier,
                                    offeringIdentifier = offering.identifier,
                                    productType = type,
                                    evidence = PendingStorePurchaseEvidence(receipt.purchaseToken,
                                        receipt.orderIdentifier.takeIf(String::isNotBlank), receipt.packageName,
                                        receipt.developerPayload.takeIf(String::isNotBlank),
                                        receipt.originalJson.takeIf(String::isNotBlank),
                                        receipt.signature.takeIf(String::isNotBlank), receipt.purchaseTimeMillis),
                                    createdAtEpochMillis = now())
                                if (storeTombstone(pending, context) in recovery.rejectedEvidenceTombstones) {
                                    if (explicitRestore) failed++
                                    continue
                                }
                                val outcome = coordinator.submit(pending, context)
                                finalizeRejectedStoreOutcome(outcome, context)
                                when (outcome) {
                                    is StoreV2Outcome.Terminal, is StoreV2Outcome.PendingDelivery,
                                    is StoreV2Outcome.Deferred -> {
                                        purchases += storePurchaseResult(outcome, null)
                                        if (explicitRestore && outcome is StoreV2Outcome.Terminal) {
                                            if (outcome.state.status == StorePurchaseStatus.ALREADY_PROCESSED) already++
                                            else restored++
                                        }
                                    }
                                    is StoreV2Outcome.Rejected, is StoreV2Outcome.Failure -> if (explicitRestore) failed++
                                }
                            }
                        }
                    }
                }
            } finally { adapter.close() }
        }
        if (queryOwned && queryFailures == 2 && purchases.isEmpty())
            throw V2Failure(InappifyError(InappifyErrorCode.STORE_UNAVAILABLE,
                "The marketplace could not return owned purchases.", isRetryable = true))
        return purchases to InappifyRestoreResult(restored, already, failed)
    }
    private suspend fun syncStoreConsumables(): InappifyConsumableSyncResult {
        if (snapshot.storePlatform != "Bazar") fail("CONFIGURATION", "UNSUPPORTED_STORE_PLATFORM")
        // Rebuild missing encrypted checkpoints from the store's owned receipts first.
        reconcileStorePurchases(queryOwned = true, explicitRestore = false)
        val coordinator = storeCoordinator ?: fail("CONFIGURATION", "LARAVEL_V2_NOT_CONFIGURED")
        val context = storeContext()
        val operations = storage.loadPendingStoreOperations().filter { it.store == "bazar" &&
            it.apiKeyFingerprint == context.apiKeyFingerprint &&
            it.customerIdentifierFingerprint == context.customerIdentifierFingerprint &&
            it.appId == context.appId }
        val pending = mutableListOf<InappifyConsumableDelivery>()
        var completed = 0
        for (operation in operations) {
            val outcome = coordinator.resume(operation, context)
            finalizeRejectedStoreOutcome(outcome, context)
            if (outcome is StoreV2Outcome.Terminal) { completed++; continue }
            if (outcome !is StoreV2Outcome.PendingDelivery) continue
            val deliveryId = outcome.state.deliveryId ?: continue
            val delivery = InappifyConsumableDelivery(deliveryId, operation.productIdentifier,
                null, InappifyDeliverySource.BAZAAR)
            val handler = deliveryHandler
            if (handler != null && runCatching { handler.deliver(delivery) }.getOrNull() == InappifyDeliveryResult.DELIVERED) {
                val confirmed = coordinator.confirmDelivery(outcome.operation, context)
                finalizeRejectedStoreOutcome(confirmed, context)
                if (confirmed is StoreV2Outcome.Terminal) { completed++; continue }
            }
            pending += delivery
        }
        return InappifyConsumableSyncResult(operations.size, completed, pending)
    }
    override suspend fun syncPurchases(): InappifyResult<List<InappifyPurchase>> {
        val result = run {
            if (snapshot.storePlatform == "DirectAndroid") unwrap(requirePurchaseClient().syncPurchases())
            else reconcileStorePurchases(queryOwned = true, explicitRestore = false).first
        }
        if (result is InappifyResult.Success) refreshCustomerInfo()
        return result.withSnapshot()
    }
    override suspend fun restorePurchasesV2(): InappifyResult<InappifyRestoreResult> {
        val result = run {
            if (snapshot.storePlatform == "DirectAndroid") unwrap(requirePurchaseClient().restorePurchases())
            else reconcileStorePurchases(queryOwned = true, explicitRestore = true).second
        }
        if (result is InappifyResult.Success) refreshCustomerInfo()
        return result.withSnapshot()
    }
    override suspend fun confirmDeliveryV2(deliveryId: Long): InappifyResult<InappifyPurchase> {
        val result = run {
            if (deliveryId <= 0) fail("VALIDATION", "INVALID_DELIVERY_ID")
            if (snapshot.storePlatform != "Bazar") fail("CONFIGURATION", "UNSUPPORTED_STORE_PLATFORM")
            val coordinator = storeCoordinator ?: fail("CONFIGURATION", "LARAVEL_V2_NOT_CONFIGURED")
            val context = storeContext()
            val pending = storage.loadPendingStoreOperations().firstOrNull { it.deliveryId == deliveryId &&
                it.apiKeyFingerprint == context.apiKeyFingerprint &&
                it.customerIdentifierFingerprint == context.customerIdentifierFingerprint &&
                it.appId == context.appId } ?: fail("VALIDATION", "DELIVERY_NOT_FOUND")
            val outcome = coordinator.confirmDelivery(pending, context)
            finalizeRejectedStoreOutcome(outcome, context)
            storePurchaseResult(outcome, null)
        }
        if (result is InappifyResult.Success) refreshCustomerInfo()
        return result.withSnapshot()
    }
    override suspend fun syncPendingConsumablesInternal(): InappifyResult<InappifyConsumableSyncResult> {
        val result = shared("consumables:${snapshot.appUserIdentifier}") {
            run {
                if (snapshot.storePlatform == "DirectAndroid") unwrap(requirePurchaseClient().syncPendingConsumables())
                else syncStoreConsumables()
            }
        }
        if (result is InappifyResult.Success) refreshCustomerInfo()
        return result.withSnapshot()
    }
    override fun setConsumableDeliveryHandlerInternal(handler: InappifyConsumableDeliveryHandler): InappifyListenerRegistration {
        check(!closed)
        deliveryHandler = handler
        handlerRegistration?.close()
        handlerRegistration = if (snapshot.storePlatform == "DirectAndroid")
            purchaseClient?.setConsumableDeliveryHandler(handler) else null
        return InappifyListenerRegistration.create(Runnable {
            if (deliveryHandler === handler) {
                deliveryHandler = null
                handlerRegistration?.close()
                handlerRegistration = null
            }
        })
    }
    override suspend fun recover(): InappifyResult<Unit> = shared("recover") {
        if (pendingLogout) return@shared logout()
        if (pendingLogin) {
            val loginRecovery = run { resumeLogin() }
            if (loginRecovery is InappifyResult.Failure) return@shared loginRecovery
        }
        val info = refreshCustomerInfo()
        if (info is InappifyResult.Failure) return@shared info
        val offerings = refreshOfferings()
        if (offerings is InappifyResult.Failure) return@shared offerings
        if (snapshot.storePlatform == "Bazar" || purchaseClient != null) {
            val sync = syncPendingConsumables()
            if (sync is InappifyResult.Failure) return@shared sync
        }
        InappifyResult.Success(Unit, snapshot)
    }
    override fun setNetworkEnabled(enabled: Boolean) { sdkApi.enabled = enabled; commerceApi?.enabled = enabled }
    private fun scheduleRecovery() { if (backgroundRecovery) scope.launch { recover() } }
    override fun addEventListener(listener: InappifyEventListener): InappifyListenerRegistration {
        check(!closed); listeners.add(listener)
        return InappifyListenerRegistration.create(Runnable { listeners.remove(listener) })
    }
    override fun addHttpTraceListenerInternal(listener: InappifyHttpTraceListener): InappifyListenerRegistration =
        sdkApi.addTraceListener(listener)
    override fun close() {
        closed = true; scope.cancel(); sdkApi.close(); commerceApi?.close(); listeners.clear(); unbindPurchaseClient()
    }
    private fun requireSession() {
        if (!snapshot.isConfigured || pendingLogout || pendingLogin)
            fail("CONFIGURATION", when {
                pendingLogout -> "LOGOUT_PENDING"
                pendingLogin -> "LOGIN_PENDING"
                else -> "NOT_CONFIGURED"
            })
    }
    private suspend fun commit(next: JsonObject) {
        currentCoroutineContext().ensureActive()
        if (closed) throw CancellationException("Closed")
        val opt = options ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        if (!storage.save(PersistedSession(null, null, null, boundScope(next)?.appId, null,
                fingerprint(opt), next.toString(), null, null))) fail("CONFIGURATION", "SECURE_STORAGE_FAILED")
        currentCoroutineContext().ensureActive()
        if (closed) throw CancellationException("Closed")
        document = next
        publish()
    }
    private fun publish() {
        val session = document.getAsJsonObject("session")
        pendingLogout = document.get("pendingLogout")?.asBoolean == true
        pendingLogin = document.has("pendingLogin")
        val rawOfferings = document.getAsJsonObject("offerings")
        val identity = session?.string("appUserId")
        val info = if (identity != null && !pendingLogout && !pendingLogin) verifier.verifyWithScope(
            document.getAsJsonObject("info") ?: session, identity, boundScope(), allowExpiredCache = true,
            expectedSubject = boundSubject()).info else null
        info?.get("requestDate")?.takeUnless { it.isJsonNull }?.let {
            val signedTime = isoMillis(info.string("requestDate"))
            trustedTimeFloor = maxOf(trustedNow(), signedTime)
            timeAnchorNanos = System.nanoTime()
        }
        val before = snapshot
        authenticatedScope = boundScope().takeIf { session != null && !pendingLogout && !pendingLogin }
        currentSnapshot = InappifySnapshot(
            before.revision + 1, session != null && !pendingLogout && !pendingLogin,
            identity != null && !isAnonymous(identity) && !pendingLogout && !pendingLogin,
            if (pendingLogout || pendingLogin) null else identity, options?.market,
            normalizeCountry(document.get("country")?.asString ?: options?.country ?: "IR"),
            document.get("appVersion")?.asString ?: options?.appVersion ?: metadata.get().versionName, BuildConfig.SDK_VERSION,
            session?.get("storeInfo")?.takeUnless { it.isJsonNull }?.asString, null, boundScope()?.appId,
            info?.let { InappifyDomainJsonCodec.parseCustomerInfo(it.toString()) },
            if (pendingLogout || pendingLogin) null else rawOfferings?.let { InappifyDomainJsonCodec.parseOfferings(it.toString()) },
            false, false, session?.number("storePlatform")?.let { platform ->
                mapOf(1L to "DirectIos", 2L to "DirectAndroid", 3L to "DirectWeb", 5L to "PlayStore",
                    6L to "AppStore", 10L to "Bazar", 11L to "MyKet", 12L to "SibApp")[platform] ?: platform.toString()
            },
        )
        val published = snapshot
        val types = buildList {
            add(InappifyEventType.STATE_CHANGED)
            if (before.appUserIdentifier != published.appUserIdentifier) add(InappifyEventType.AUTHENTICATION_CHANGED)
            if (before.customerInfo != published.customerInfo) add(InappifyEventType.CUSTOMER_INFO_CHANGED)
            if (before.offerings != published.offerings) add(InappifyEventType.OFFERINGS_CHANGED)
        }
        scope.launch(eventDispatcher) {
            // Suppress queued events belonging to a previous identity.
            if (!closed && snapshot.revision == published.revision)
                types.forEach { type -> listeners.forEach { listener -> runCatching { listener.onEvent(InappifyEvent.create(type, published)) } } }
        }
    }
    private fun validateOptions(value: InappifyOptions) {
        val app = metadata.get()
        if (value.apiKey.isBlank() || value.apiKey.length > 4096 || value.apiKey.any(Char::isISOControl) ||
            app.packageIdentifier.isBlank() || app.packageIdentifier.length > 255 ||
            (value.country != null && !validCountry(value.country)) ||
            (value.appVersion ?: app.versionName).let { it.isBlank() || it.length > 50 } || app.versionCode < 1 ||
            (value.appUserIdentifier != null && !validIdentity(value.appUserIdentifier))) fail("CONFIGURATION", "INVALID_OPTIONS")
    }
    private fun fingerprint(value: InappifyOptions): String {
        config.expectedScope?.let { return explicitFingerprint(value, it) }
        // Stable before tenant scope is known, so first-request failures cannot rotate the UUID.
        val pieces = listOf("go-auto-v2", config.sdkApiBaseUrl, value.apiKey, metadata.get().packageIdentifier)
        return digest(pieces.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" })
    }
    private fun explicitFingerprint(value: InappifyOptions, scope: InappifyV2SessionScope): String =
        digest("${config.sdkApiBaseUrl}|${scope.issuer}|${scope.appId}|${scope.projectId}|${value.apiKey}|${metadata.get().packageIdentifier}")
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun trustedNow(): Long = maxOf(now(), trustedTimeFloor +
        ((System.nanoTime() - timeAnchorNanos).coerceAtLeast(0) / 1_000_000))
    private fun <T> unwrap(result: InappifyResult<T>): T = when (result) {
        is InappifyResult.Success -> result.data
        is InappifyResult.Failure -> throw V2Failure(result.error)
    }
    private fun <T> InappifyResult<T>.withSnapshot(): InappifyResult<T> = when (this) {
        is InappifyResult.Success -> InappifyResult.Success(data, this@GoV2Client.snapshot)
        is InappifyResult.Failure -> InappifyResult.Failure(error, this@GoV2Client.snapshot)
    }
    internal companion object {
        internal fun create(context: Context): GoV2Client = create(context, GoV2Environment.production())
        internal fun create(context: Context, configuration: InappifyV2Configuration): GoV2Client =
            create(context, GoV2Environment.explicit(configuration))
        private fun create(context: Context, configuration: GoV2Environment): GoV2Client {
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).build()
            return GoV2Client(configuration, GoApi(OkHttpTransport.create(configuration.sdkApiBaseUrl.toHttpUrl(), client), System::currentTimeMillis),
                EncryptedSessionStateStore.createGoV2(context), AndroidAppMetadataProvider(context),
                countryResolver = IpWhoIsCountryResolver()::resolve,
                commerceApi = GoApi(OkHttpTransport.create(configuration.commerceApiBaseUrl.toHttpUrl(),
                    client.newBuilder().build()), System::currentTimeMillis),
                billingFactory = AndroidStoreBillingAdapterFactory(context))
        }
    }
}

internal fun anonymousId(): String = "\$INAAnonymousID:${UUID.randomUUID()}"
internal fun isAnonymous(value: String): Boolean = value.matches(Regex(
    "\\\$INAAnonymousID:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}|\\\$InaAnonymousId_[0-9A-HJKMNP-TV-Z]{26}"))
internal fun validIdentity(value: String): Boolean {
    if (isAnonymous(value)) return true
    // Go trims identifiers before storing them. Reject edge whitespace locally
    // so the response identity still matches the exact requested value.
    if (value.isBlank() || value != value.trim() || !Charsets.UTF_8.newEncoder().canEncode(value) ||
        value.codePointCount(0, value.length) > 100 ||
        value.any(Char::isISOControl)) return false
    val lower = value.lowercase(Locale.ROOT)
    return !lower.startsWith("\$inaanonymousid:") && !lower.startsWith("\$inaanonymousid_")
}
internal fun validCountry(value: String): Boolean = value.trim().matches(Regex("[A-Za-z]{2}"))
internal fun normalizeCountry(value: String): String = value.trim().uppercase(Locale.ROOT)
internal val reservedKeys = setOf("\$email", "\$displayName", "\$apnsTokens", "\$fcmTokens", "\$idfa", "\$idfv", "\$phoneNumber", "\$campaign", "\$keyword")
internal fun validAttribute(key: String, value: String?): Boolean =
    (key in reservedKeys || key.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,39}"))) &&
        (value == null || value.codePointCount(0, value.length) <= 500)
internal fun allowedUrl(raw: String, hosts: Set<String>): Boolean = runCatching {
    val url = raw.toHttpUrl()
    url.isHttps && url.port == 443 && url.host in hosts && url.username.isEmpty() && url.password.isEmpty()
}.getOrDefault(false)
