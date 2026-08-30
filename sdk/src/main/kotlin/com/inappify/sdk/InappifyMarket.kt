package com.inappify.sdk

/**
 * Purchase route selected for an operation.
 *
 * [NONE] submits a purchase directly to Inappify. [BAZAAR] uses Cafe Bazaar
 * for non-trial purchases and maps to the backend's `bazar` identifier.
 */
public enum class InappifyMarket {
    BAZAAR,
    NONE,
}
