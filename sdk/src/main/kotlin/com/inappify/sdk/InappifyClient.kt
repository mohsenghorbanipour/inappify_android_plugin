package com.inappify.sdk

import android.app.Activity
import android.content.Context
import com.inappify.sdk.internal.DefaultInappifyClient

/**
 * Public entry point for an Inappify session.
 *
 * Implementations must serialize state-changing operations and expose the
 * latest authoritative state through [snapshot]. A client must not be reused
 * after [close] has been called.
 *
 * Semantic SDK failures are returned as [InappifyResult.Failure]. Exceptions
 * are reserved for invalid client lifecycle usage and programming errors.
 */
public interface InappifyClient : AutoCloseable {

    public companion object {

        /**
         * Creates an independent client backed by the production Inappify
         * service and application-private session storage.
         *
         * No network request is made until [configure] is called.
         * Applications should create one client in their application or
         * dependency-injection scope, retain it for that scope's lifetime, and
         * call [close] when the owner is permanently released.
         */
        @JvmStatic
        public fun create(context: Context): InappifyClient =
            DefaultInappifyClient.create(context.applicationContext)
    }

    /** Latest authoritative, token-free state owned by this client. */
    public val snapshot: InappifySnapshot

    /** Configures the session for the application represented by [options]. */
    public suspend fun configure(options: InappifyOptions): InappifyResult<Unit>

    /** Changes the customer identity owned by this session. */
    public suspend fun login(request: InappifyLoginRequest): InappifyResult<Unit>

    /** Clears the authenticated customer and returns the resulting state. */
    public suspend fun logout(): InappifyResult<Unit>

    /**
     * Returns the current customer information.
     *
     * [forceRefresh] defaults to true. Pass false to reuse a response cached
     * for less than five minutes.
     */
    public suspend fun getCustomerInfo(
        forceRefresh: Boolean = true,
    ): InappifyResult<InappifyCustomerInfo>

    /** Fetches the current customer information from the Inappify service. */
    public suspend fun refreshCustomerInfo(): InappifyResult<InappifyCustomerInfo>

    /**
     * Returns the offerings for the active session, reusing the session-bound
     * cache when it is available.
     */
    public suspend fun getOfferings(): InappifyResult<InappifyOfferings>

    /** Fetches offerings for the active session from the Inappify service. */
    public suspend fun refreshOfferings(): InappifyResult<InappifyOfferings>

    /**
     * Validates one discount code for the active customer.
     *
     * [InappifyDiscountCodeRequest.offeringIdentifier] is accepted for API
     * consistency but is not sent by the current endpoint. Validation uses the
     * active session.
     */
    public suspend fun validateDiscountCode(
        request: InappifyDiscountCodeRequest,
    ): InappifyResult<InappifyDiscountCodeResult>

    /**
     * Updates the local targeting values and invalidates cached offerings.
     * A null value leaves the corresponding field unchanged.
     */
    public suspend fun setTargetingContext(
        country: String? = null,
        appVersion: String? = null,
    ): InappifyResult<Unit>

    /**
     * Resolves the current offering using ordered server targeting rules.
     *
     * When [forceRefresh] is true, offerings are refreshed before evaluation.
     * If [context] is null, the client builds it from its authoritative state.
     * A successful result may contain null when no offering can be selected.
     */
    public suspend fun getCurrentOffering(
        placementIdentifier: String? = null,
        forceRefresh: Boolean = false,
        context: InappifyOfferingEvaluationContext? = null,
    ): InappifyResult<InappifyOffering?>

    /** Returns whether the latest snapshot contains the named active entitlement. */
    public fun isActiveEntitlement(identifier: String): Boolean =
        snapshot.isActiveEntitlement(identifier)

    /** Convenience alias for [isActiveEntitlement]. */
    public fun hasEntitlement(identifier: String): Boolean =
        isActiveEntitlement(identifier)

    /** Returns the named active entitlement from the latest snapshot. */
    public fun getEntitlement(identifier: String): InappifyEntitlement? =
        snapshot.getEntitlement(identifier)

    /** Returns whether an identifier uses Inappify's anonymous-customer marker. */
    public fun isCustomerAnonymous(appUserIdentifier: String?): Boolean =
        com.inappify.sdk.isCustomerAnonymous(appUserIdentifier)

    /**
     * Checks whether [identifier] is active. Customer information is refreshed
     * first by default.
     */
    public suspend fun checkEntitlement(
        identifier: String,
        forceRefresh: Boolean = true,
    ): InappifyResult<Boolean>

    /** Stores or removes valid custom attributes and silently ignores invalid entries. */
    public suspend fun setAttributes(
        request: InappifyAttributesRequest,
    ): InappifyResult<List<InappifyAttribute>>

    /** Map-style convenience overload for [setAttributes]. */
    public suspend fun updateAttributes(
        values: Map<String, String?>,
    ): InappifyResult<List<InappifyAttribute>> = setAttributes(
        InappifyAttributesRequest(
            values.entries.map { entry ->
                InappifyAttribute(key = entry.key, value = entry.value)
            },
        ),
    )

    /** Deletes custom attributes by key. */
    public suspend fun deleteAttributes(
        request: InappifyDeleteAttributesRequest,
    ): InappifyResult<List<InappifyAttribute>>

    /** Stores one reserved customer attribute. */
    public suspend fun setReservedAttribute(
        request: InappifyReservedAttributeRequest,
    ): InappifyResult<Unit>

    /** Synchronizes a full attribute list, or the latest customer snapshot when omitted. */
    public suspend fun syncAttributes(
        request: InappifyAttributesRequest? = null,
    ): InappifyResult<List<InappifyAttribute>>

    /** Returns whether a reserved attribute key can be written. */
    public suspend fun canSetReservedAttribute(key: String): InappifyResult<Boolean>

    /** Convenience wrapper for the reserved `$email` attribute. */
    public suspend fun setEmail(email: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.EMAIL, email),
        )

    /** Convenience wrapper for the reserved `$apnsTokens` attribute. */
    public suspend fun setApnsToken(apnsToken: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.APNS_TOKEN, apnsToken),
        )

    /** Deprecated spelling retained for source compatibility. */
    @Deprecated(
        message = "Use setDisplayName.",
        replaceWith = ReplaceWith("setDisplayName(displayName)"),
    )
    public suspend fun setDisplayname(displayName: String): InappifyResult<Unit> =
        setDisplayName(displayName)

    /** Convenience wrapper for the reserved `$displayName` attribute. */
    public suspend fun setDisplayName(displayName: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(
                InappifyReservedAttribute.DISPLAY_NAME,
                displayName,
            ),
        )

    /** Convenience wrapper for the reserved `$fcmTokens` attribute. */
    public suspend fun setFcmToken(fcmToken: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.FCM_TOKEN, fcmToken),
        )

    /** Convenience wrapper for the reserved `$idfa` attribute. */
    public suspend fun setIdfa(idfa: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.IDFA, idfa),
        )

    /** Convenience wrapper for the reserved `$idfv` attribute. */
    public suspend fun setIdfv(idfv: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.IDFV, idfv),
        )

    /** Convenience wrapper for the reserved `$ip` attribute. */
    public suspend fun setIp(ip: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.IP, ip),
        )

    /** Convenience wrapper for the reserved `$phoneNumber` attribute. */
    public suspend fun setPhoneNumber(phoneNumber: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(
                InappifyReservedAttribute.PHONE_NUMBER,
                phoneNumber,
            ),
        )

    /** Convenience wrapper for the reserved `$campaign` attribute. */
    public suspend fun setCampaign(campaign: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.CAMPAIGN, campaign),
        )

    /** Convenience wrapper for the reserved `$keyword` attribute. */
    public suspend fun setKeyword(keyword: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.KEYWORD, keyword),
        )

    /**
     * Purchases one product through the marketplace selected by
     * [InappifyPurchaseRequest.market] for this call.
     *
     * This overload supports direct, lost-purchase, and trial flows which do
     * not need marketplace UI. A non-trial Bazaar request fails with
     * [InappifyErrorCode.STORE_UNAVAILABLE] when no foreground Activity is
     * supplied.
     */
    public suspend fun purchase(
        request: InappifyPurchaseRequest,
    ): InappifyResult<InappifyPurchase>

    /**
     * Purchases one product through the marketplace selected by
     * [InappifyPurchaseRequest.market] for this call.
     *
     * [activity] must be the current foreground activity and must implement
     * AndroidX `ActivityResultRegistryOwner` and `LifecycleOwner`. It is used
     * only to launch the marketplace UI and is never retained after the
     * operation finishes.
     */
    public suspend fun purchase(
        activity: Activity,
        request: InappifyPurchaseRequest,
    ): InappifyResult<InappifyPurchase>

    /**
     * Reconciles locally verified, owned marketplace purchases with Inappify.
     *
     * This operation never opens marketplace UI. It is intended for recovery
     * when the store completed a purchase but server verification was
     * interrupted. Only purchases created by a compatible Inappify payload are
     * submitted, and raw store evidence is never returned to the application.
     */
    public suspend fun syncPurchases(): InappifyResult<List<InappifyPurchase>>

    /**
     * Registers an observer for authoritative state changes from this client.
     * Closing the returned registration removes only this listener.
     */
    public fun addEventListener(
        listener: InappifyEventListener,
    ): InappifyListenerRegistration

    /**
     * Releases resources owned by this client.
     *
     * Closing a client is not equivalent to [logout] and must not perform a
     * remote logout implicitly.
     */
    public override fun close(): Unit
}
