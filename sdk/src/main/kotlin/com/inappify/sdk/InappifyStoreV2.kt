package com.inappify.sdk

/** Internal capability implemented by the production client without changing the V1 interface. */
internal interface StoreV2CapableClient {
    suspend fun restorePurchasesV2(): InappifyResult<InappifyRestoreResult>

    suspend fun confirmDeliveryV2(deliveryId: Long): InappifyResult<InappifyPurchase>
}

/**
 * Restores owned subscriptions and non-consumables independently.
 *
 * V2-capable clients continue after an individual item fails and refresh
 * customer state after reconciliation. Custom V1 clients retain a compatible
 * fallback through [InappifyClient.syncPurchases].
 */
public suspend fun InappifyClient.restorePurchases(): InappifyResult<InappifyRestoreResult> =
    if (this is StoreV2CapableClient) {
        restorePurchasesV2()
    } else {
        when (val result = syncPurchases()) {
            is InappifyResult.Success -> InappifyResult.Success(
                data = InappifyRestoreResult(
                    restoredCount = result.data.size,
                    alreadyProcessedCount = 0,
                    failedCount = 0,
                ),
                snapshot = result.snapshot,
            )

            is InappifyResult.Failure -> result
        }
    }

/**
 * Confirms that the host delivered a verified consumable idempotently.
 *
 * For Direct, the SDK acknowledges the durable host grant and completes. For
 * Bazaar, it then consumes the same purchase receipt and reports that result.
 * Clients without native fulfillment capability return an explicit failure.
 */
public suspend fun InappifyClient.confirmDelivery(
    deliveryId: Long,
): InappifyResult<InappifyPurchase> =
    if (this is StoreV2CapableClient) {
        confirmDeliveryV2(deliveryId)
    } else {
        InappifyResult.Failure(
            error = InappifyError(
                code = InappifyErrorCode.UNSUPPORTED_OPERATION,
                message = "Consumable delivery confirmation is not supported by this client.",
                details = mapOf("operation" to "confirmDelivery"),
            ),
            snapshot = snapshot,
        )
    }
