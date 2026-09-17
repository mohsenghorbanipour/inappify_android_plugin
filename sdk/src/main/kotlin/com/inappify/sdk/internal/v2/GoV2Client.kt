package com.inappify.sdk.internal.v2

import android.app.Activity
import android.content.Context
import com.google.gson.*
import com.inappify.sdk.*
import com.inappify.sdk.internal.domain.InappifyDomainJsonCodec
import com.inappify.sdk.internal.DefaultInappifyClient
import com.inappify.sdk.internal.platform.*
import com.inappify.sdk.internal.network.OkHttpTransport
import com.inappify.sdk.internal.storage.*
import java.security.MessageDigest
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
    private val api: GoApi,
    private val storage: SessionStateStore,
    private val metadata: AppMetadataProvider,
    private val now: () -> Long = System::currentTimeMillis,
    private val eventDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val backgroundRecovery: Boolean = true,
) : InappifyV2Client, StoreV2CapableClient, ConsumableFulfillmentCapableClient, HttpDiagnosticsCapableClient,
    InappifyV2RuntimeCapabilities {
    constructor(config: InappifyV2Configuration, api: GoApi, storage: SessionStateStore,
        metadata: AppMetadataProvider, now: () -> Long = System::currentTimeMillis,
        eventDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate, backgroundRecovery: Boolean = true) :
        this(GoV2Environment.explicit(config), api, storage, metadata, now, eventDispatcher, backgroundRecovery)

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
    @Volatile private var closed = false
    @Volatile private var currentSnapshot = InappifySnapshot.initial(BuildConfig.SDK_VERSION)
    @Volatile private var forceUpdate = false
    @Volatile private var pendingLogout = false
    @Volatile private var authenticatedScope: InappifyV2SessionScope? = null
    override val snapshot: InappifySnapshot get() = currentSnapshot
    override val hasForceUpdate: Boolean get() = forceUpdate
    override val isLogoutPending: Boolean get() = pendingLogout
    override val verifiedScope: InappifyV2SessionScope?
        get() = authenticatedScope.takeIf { snapshot.isConfigured && !pendingLogout }
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
                        verifier.refresh(api.request("public-keys", null))
                    }
                    val saved = verifiedCache(stored.customerInfoJson, null)
                    if (saved != null) {
                        document = saved
                        // Keep verified app scope even when Configure requests another customer,
                        // without first publishing that customer's predecessor into the UI.
                        val cachedIdentity = saved.getAsJsonObject("session")?.string("appUserId")
                        if (options.appUserIdentifier == null || cachedIdentity == options.appUserIdentifier) publish()
                        else pendingLogout = saved.get("pendingLogout")?.asBoolean == true
                    } else if (stored.apiKeyFingerprint == fingerprint(options)) {
                        // A stale/unknown signing kid must not rotate the locally persisted UUID.
                        // This does not accept a session, token, tenant scope or entitlements.
                        val cached = runCatching { jsonObject(stored.customerInfoJson) }.getOrNull()
                        if (cached?.get("pendingLogout")?.asBoolean == true) {
                            pendingLogout = true
                            fail("SIGNATURE", "INVALID_PENDING_LOGOUT_CACHE")
                        }
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
            val session = document.getAsJsonObject("session")
            if (session != null) {
                val country = options.country ?: document.get("country")?.asString ?: "IR"
                val appVersion = options.appVersion ?: metadata.get().versionName
                if (document.get("country")?.asString != country || document.get("appVersion")?.asString != appVersion)
                    commit(document.deepCopy().apply {
                        addProperty("country", country); addProperty("appVersion", appVersion); remove("offerings")
                    })
            }
            if (session != null && (options.appUserIdentifier == null || options.appUserIdentifier == session.string("appUserId")) &&
                document.get("sessionInvalid")?.asBoolean != true && isoMillis(session.string("sessionExpiresAt")) > now()) {
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
            session.number("forceVersion"); session.number("storePlatform"); isoMillis(session.string("sessionExpiresAt"))
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

    private suspend fun configureLocked(identity: String) {
        val opt = options ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        val app = metadata.get()
        val response = api.request("configure", opt.apiKey, JsonObject().apply {
            addProperty("appUserIdentifier", identity)
            addProperty("identifierValue", app.packageIdentifier)
            addProperty("versionName", opt.appVersion ?: app.versionName)
            addProperty("versionCode", app.versionCode)
        })
        verifiedResponse("configure", mayHaveCommitted = true) {
            acceptSession(response, identity, keepLogoutPending = pendingLogout)
        }
    }

    private suspend fun verifyResponse(response: JsonObject, identity: String,
        expectedSubject: String? = boundSubject()): VerifiedCustomerInfo {
        val verification = response.getAsJsonObject("verification") ?: fail("SIGNATURE", "MISSING_VERIFICATION")
        val kid = verification.string("keyId")
        if (!verifier.known(kid)) verifier.refresh(api.request("public-keys", null))
        return verifier.verifyWithScope(response, identity, boundScope(), expectedSubject = expectedSubject)
    }

    private suspend fun verify(response: JsonObject, identity: String): JsonObject = verifyResponse(response, identity).info

    private suspend fun acceptSession(response: JsonObject, expectedIdentity: String?, keepLogoutPending: Boolean = false,
        identityTransition: Boolean = false) {
        val identity = response.string("appUserId")
        if (expectedIdentity != null && identity != expectedIdentity)
            verificationMismatch("appUserId", "exact requested app-user match", response.get("appUserId"),
                JsonPrimitive(expectedIdentity), "request", "IDENTITY_MISMATCH", signatureVerified = false)
        if (expectedIdentity == null && !isAnonymous(identity)) fail("SIGNATURE", "EXPECTED_ANONYMOUS")
        if (response.number("appId") <= 0 || response.string("sessionType") != "public" ||
            response.string("sessionToken").isBlank() || isoMillis(response.string("sessionExpiresAt")) <= now())
            fail("SIGNATURE", "INVALID_SESSION")
        response.number("forceVersion")
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
        next.add("session", response.deepCopy())
        next.add("info", response.deepCopy())
        next.addProperty("infoFetchedAt", now())
        if (bindingChanged) {
            next.addProperty("country", options?.country ?: document.get("country")?.asString ?: "IR")
            next.addProperty("appVersion", options?.appVersion ?: document.get("appVersion")?.asString ?: metadata.get().versionName)
        }
        if (!keepLogoutPending) next.remove("pendingLogout")
        next.remove("sessionInvalid")
        if (document.getAsJsonObject("session")?.get("forceVersion") != response.get("forceVersion")) next.remove("offerings")
        if (isAnonymous(identity)) next.addProperty("anonymousId", identity)
        commit(next)
        if (bindingChanged) unbindPurchaseClient()
    }

    private suspend fun sessionRequest(endpoint: String, body: JsonObject = JsonObject(),
        retry: Boolean = true, allowLogout: Boolean = false): JsonObject {
        if (pendingLogout && !allowLogout) fail("AUTH", "LOGOUT_PENDING")
        var session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        if (document.get("sessionInvalid")?.asBoolean == true || isoMillis(session.string("sessionExpiresAt")) <= now()) {
            renewSession(session.string("appUserId"))
            session = document.getAsJsonObject("session")
        }
        if (endpoint == "offerings") body.addProperty("forceVersion", session.number("forceVersion"))
        return try { api.request(endpoint, session.string("sessionToken"), body, retry) }
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
            if (endpoint == "offerings") body.addProperty("forceVersion", document.getAsJsonObject("session").number("forceVersion"))
            api.request(endpoint, document.getAsJsonObject("session").string("sessionToken"), body, retry)
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
        val response = sessionRequest("login", JsonObject().apply {
            addProperty("appUserIdentifier", request.appUserIdentifier)
        }, retry = false)
        verifiedResponse("login", mayHaveCommitted = true) {
            acceptSession(response, request.appUserIdentifier, identityTransition = true)
        }
        scheduleRecovery()
    }
    override suspend fun logout(): InappifyResult<Unit> = run { logoutLocked() }
    private suspend fun logoutLocked() {
        try {
            val response = sessionRequest("logout", retry = false, allowLogout = true)
            verifiedResponse("logout", mayHaveCommitted = true) {
                acceptSession(response, null, identityTransition = true)
            }
            scheduleRecovery()
        } catch (e: V2Failure) {
            if (e.sdkError.isRetryable) {
                commit(document.deepCopy().apply { addProperty("pendingLogout", true) })
                unbindPurchaseClient()
            }
            throw e
        }
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
                addProperty("forceVersion", snapshot.forceVersion)
                // Only the native package renderer is advertised until the element schema is supplied.
                addProperty("paywallSchemaVersion", 1)
                addProperty("paywallRendererVersion", 1)
            })
            response.flag("hasForceUpdate")
            val force = response.number("forceVersion")
            val safe = response.deepCopy().apply {
                remove("rules")
                if (!has("currentOffering")) add("currentOffering", JsonNull.INSTANCE)
            }
            val parsed = InappifyDomainJsonCodec.parseOfferings(safe.toString())
            commit(document.deepCopy().apply {
                add("offerings", safe)
                getAsJsonObject("session").addProperty("forceVersion", force)
            })
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
        if (country != null && !country.matches(Regex("[A-Z]{2}"))) fail("VALIDATION", "INVALID_COUNTRY")
        if (appVersion != null && (appVersion.isBlank() || appVersion.length > 50)) fail("VALIDATION", "INVALID_VERSION")
        commit(document.deepCopy().apply {
            country?.let { addProperty("country", it) }; appVersion?.let { addProperty("appVersion", it) }
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
        deliveryHandler?.let { handlerRegistration = companion.setConsumableDeliveryHandler(it) }
    }
    private fun checkPurchaseScope(client: InappifyClient) {
        val candidate = client.snapshot
        if (!candidate.isConfigured || candidate.appId != snapshot.appId || candidate.appUserIdentifier != snapshot.appUserIdentifier ||
            candidate.storePlatform != snapshot.storePlatform) fail("CONFIGURATION", "PURCHASE_CREDENTIAL_SCOPE_MISMATCH")
    }
    private fun requirePurchaseClient(): InappifyClient {
        if (!api.enabled) fail("CONFIGURATION", "V2_DISABLED")
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
            val client = requirePurchaseClient()
            if (request.apiKey != null || request.marketKey != null || request.isLostPurchase || request.dynamicPriceToken != null)
                fail("VALIDATION", "PURCHASE_OVERRIDE_NOT_ALLOWED")
            val offering = snapshot.offerings?.offerings?.firstOrNull { it.identifier == request.offeringIdentifier }
            if (offering?.packages?.none { it.product?.identifier == request.productIdentifier &&
                    (request.packageIdentifier == null || it.identifier == request.packageIdentifier) } != false)
                fail("VALIDATION", "PRODUCT_NOT_IN_OFFERING")
            val purchase = unwrap(if (activity == null) client.purchase(request) else client.purchase(activity, request))
            if (purchase.url != null && !allowedUrl(purchase.url, config.paymentHosts)) fail("DECODING", "UNTRUSTED_PAYMENT_URL")
            purchase
        }
        if (result is InappifyResult.Success) refreshCustomerInfo()
        return result.withSnapshot()
    }
    override suspend fun syncPurchases(): InappifyResult<List<InappifyPurchase>> {
        val result = run { unwrap(requirePurchaseClient().syncPurchases()) }
        if (result is InappifyResult.Success) refreshCustomerInfo()
        return result.withSnapshot()
    }
    override suspend fun restorePurchasesV2(): InappifyResult<InappifyRestoreResult> {
        val result = run { unwrap(requirePurchaseClient().restorePurchases()) }
        if (result is InappifyResult.Success) refreshCustomerInfo()
        return result.withSnapshot()
    }
    override suspend fun confirmDeliveryV2(deliveryId: Long): InappifyResult<InappifyPurchase> {
        val result = run { unwrap(requirePurchaseClient().confirmDelivery(deliveryId)) }
        if (result is InappifyResult.Success) refreshCustomerInfo()
        return result.withSnapshot()
    }
    override suspend fun syncPendingConsumablesInternal(): InappifyResult<InappifyConsumableSyncResult> {
        val result = shared("consumables:${snapshot.appUserIdentifier}") {
            run { unwrap(requirePurchaseClient().syncPendingConsumables()) }
        }
        if (result is InappifyResult.Success) refreshCustomerInfo()
        return result.withSnapshot()
    }
    override fun setConsumableDeliveryHandlerInternal(handler: InappifyConsumableDeliveryHandler): InappifyListenerRegistration {
        check(!closed)
        deliveryHandler = handler
        handlerRegistration?.close()
        handlerRegistration = purchaseClient?.setConsumableDeliveryHandler(handler)
        return InappifyListenerRegistration.create(Runnable {
            if (deliveryHandler === handler) { deliveryHandler = null; handlerRegistration?.close(); handlerRegistration = null }
        })
    }
    override suspend fun recover(): InappifyResult<Unit> = shared("recover") {
        if (pendingLogout) return@shared logout()
        val info = refreshCustomerInfo()
        if (info is InappifyResult.Failure) return@shared info
        val offerings = refreshOfferings()
        if (offerings is InappifyResult.Failure) return@shared offerings
        if (purchaseClient != null) {
            val sync = syncPendingConsumables()
            if (sync is InappifyResult.Failure) return@shared sync
        }
        InappifyResult.Success(Unit, snapshot)
    }
    override fun setNetworkEnabled(enabled: Boolean) { api.enabled = enabled }
    private fun scheduleRecovery() { if (backgroundRecovery) scope.launch { recover() } }
    override fun addEventListener(listener: InappifyEventListener): InappifyListenerRegistration {
        check(!closed); listeners.add(listener)
        return InappifyListenerRegistration.create(Runnable { listeners.remove(listener) })
    }
    override fun addHttpTraceListenerInternal(listener: InappifyHttpTraceListener): InappifyListenerRegistration =
        api.addTraceListener(listener)
    override fun close() {
        closed = true; scope.cancel(); api.close(); listeners.clear(); unbindPurchaseClient()
    }
    private fun requireSession() {
        if (!snapshot.isConfigured || pendingLogout) fail("CONFIGURATION", if (pendingLogout) "LOGOUT_PENDING" else "NOT_CONFIGURED")
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
        val rawOfferings = document.getAsJsonObject("offerings")
        forceUpdate = !pendingLogout && rawOfferings?.get("hasForceUpdate")?.asBoolean == true
        val identity = session?.string("appUserId")
        val info = if (identity != null && !pendingLogout) verifier.verifyWithScope(
            document.getAsJsonObject("info") ?: session, identity, boundScope(), allowExpiredCache = true,
            expectedSubject = boundSubject()).info else null
        info?.get("requestDate")?.takeUnless { it.isJsonNull }?.let {
            val signedTime = isoMillis(info.string("requestDate"))
            trustedTimeFloor = maxOf(trustedNow(), signedTime)
            timeAnchorNanos = System.nanoTime()
        }
        val before = snapshot
        authenticatedScope = boundScope().takeIf { session != null && !pendingLogout }
        currentSnapshot = InappifySnapshot(
            before.revision + 1, session != null && !pendingLogout, identity != null && !isAnonymous(identity) && !pendingLogout,
            if (pendingLogout) null else identity, options?.market, document.get("country")?.asString ?: options?.country ?: "IR",
            document.get("appVersion")?.asString ?: options?.appVersion ?: metadata.get().versionName, BuildConfig.SDK_VERSION,
            session?.get("storeInfo")?.takeUnless { it.isJsonNull }?.asString, session?.number("forceVersion"), boundScope()?.appId,
            info?.let { InappifyDomainJsonCodec.parseCustomerInfo(it.toString()) },
            if (pendingLogout) null else rawOfferings?.let { InappifyDomainJsonCodec.parseOfferings(it.toString()) },
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
            (value.country != null && !value.country.matches(Regex("[A-Z]{2}"))) ||
            (value.appVersion ?: app.versionName).let { it.isBlank() || it.length > 50 } || app.versionCode < 1 ||
            (value.appUserIdentifier != null && !validIdentity(value.appUserIdentifier))) fail("CONFIGURATION", "INVALID_OPTIONS")
    }
    private fun fingerprint(value: InappifyOptions): String {
        config.expectedScope?.let { return explicitFingerprint(value, it) }
        // Stable before tenant scope is known, so first-request failures cannot rotate the UUID.
        val pieces = listOf("go-auto-v2", config.apiBaseUrl, value.apiKey, metadata.get().packageIdentifier)
        return digest(pieces.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" })
    }
    private fun explicitFingerprint(value: InappifyOptions, scope: InappifyV2SessionScope): String =
        digest("${config.apiBaseUrl}|${scope.issuer}|${scope.appId}|${scope.projectId}|${value.apiKey}|${metadata.get().packageIdentifier}")
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
            return GoV2Client(configuration, GoApi(OkHttpTransport.create(configuration.apiBaseUrl.toHttpUrl(), client), System::currentTimeMillis),
                EncryptedSessionStateStore.createGoV2(context), AndroidAppMetadataProvider(context))
        }
    }
}

internal fun anonymousId(): String = "\$INAAnonymousID:${UUID.randomUUID()}"
internal fun isAnonymous(value: String): Boolean = value.matches(Regex(
    "\\\$INAAnonymousID:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|\\\$InaAnonymousId_[0-9A-HJKMNP-TV-Z]{26}"))
internal fun validIdentity(value: String): Boolean = isAnonymous(value) ||
    (value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{15,99}")) && !value.all { it in '0'..'9' } &&
        value.lowercase() !in setOf("anonymousanonymous", "undefinedundefined", "nullnullnullnull"))
internal val reservedKeys = setOf("\$email", "\$displayName", "\$apnsTokens", "\$fcmTokens", "\$idfa", "\$idfv", "\$phoneNumber", "\$campaign", "\$keyword")
internal fun validAttribute(key: String, value: String?): Boolean =
    (key in reservedKeys || key.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,39}"))) &&
        (value == null || value.codePointCount(0, value.length) <= 500)
internal fun allowedUrl(raw: String, hosts: Set<String>): Boolean = runCatching {
    val url = raw.toHttpUrl()
    url.isHttps && url.port == 443 && url.host in hosts && url.username.isEmpty() && url.password.isEmpty()
}.getOrDefault(false)
