package com.inappify.sdk.internal.storage

internal class PersistedSession(
    internal val token: String?,
    internal val appUserIdentifier: String?,
    internal val forceVersion: Long?,
    internal val appId: Long?,
    internal val storeInfo: String?,
    internal val apiKeyFingerprint: String?,
    internal val customerInfoJson: String?,
    internal val offeringsJson: String?,
    internal val customerInfoUpdatedAt: String?,
    internal val cacheContextFingerprint: String? = null,
    internal val purchaseRecoveryId: String? = null,
) {
    override fun toString(): String =
        "PersistedSession(" +
            "token=${token.redacted()}, " +
            "appUserIdentifier=${appUserIdentifier.redacted()}, " +
            "forceVersion=$forceVersion, " +
            "appId=$appId, " +
            "storeInfo=$storeInfo, " +
            "apiKeyFingerprint=${apiKeyFingerprint.redacted()}, " +
            "cacheContextFingerprint=${cacheContextFingerprint.redacted()}, " +
            "purchaseRecoveryId=${purchaseRecoveryId.redacted()}, " +
            "customerInfoJson=${customerInfoJson.redacted()}, " +
            "offeringsJson=${offeringsJson.redacted()}, " +
            "customerInfoUpdatedAt=$customerInfoUpdatedAt" +
            ")"
}

internal interface SessionStateStore {
    suspend fun load(): PersistedSession?

    suspend fun save(session: PersistedSession): Boolean

    suspend fun clear(): Boolean
}

private fun String?.redacted(): String = if (this == null) "null" else "<redacted>"
