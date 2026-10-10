# Payloadless Bazaar subscription renewal recovery (staged)

This is the Android half of a coordinated commerce-service change. It is **off
by default**. The Laravel endpoint below is a required contract, not an endpoint
implemented by this SDK change. Do not enable it against the existing backend.
No server-side polling or marketplace notification is required: reconciliation
runs after Configure/on explicit recovery when the application is opened.

## Rollout

After the commerce endpoint and its ownership tests are deployed:

```kotlin
client.configure(InappifyOptions(
    apiKey = "<public app key>",
    appUserIdentifier = "<current app user>", // optional for anonymous sessions
    enableSubscriptionRecoveryWithoutPayload = true,
))
```

The original `InappifyOptions` constructor and its default-argument ABI remain
available. The opt-in constructor adds no wire field to Configure. Disabling
the option stops both discovery/submission and polling of these operations,
without deleting already persisted recovery checkpoints.

## Eligibility and security boundary

- Only receipts returned by the Bazaar **subscription** inventory query qualify.
- `developerPayload` must actually be empty/whitespace. Nonempty malformed JSON,
  a missing/wrong `recoveryBinding`, or a malformed/wrong `purchaseBinding` does
  **not** qualify and cannot downgrade into this workflow.
- The original JSON, signature, positive purchase time, token, product and host
  package must be available. Original JSON/signature/payload are not rewritten.
- Existing bound receipts keep `store/purchases` and all current binding checks.
  Myket, in-app consumables and non-consumables keep their existing workflows.
- No entitlement is granted from a native receipt or a success status alone.
  Access still comes from verified, signed CustomerInfo.

## Required Laravel contract

```http
POST https://api.inappify.com/app/v2/store/subscriptions/recover
Authorization: Bearer <current matching Go session>
Content-Type: application/json
```

```json
{
  "productIdentifier": "subscription_sku",
  "purchase": {
    "token": "<Bazaar token>",
    "purchaseTime": 1780000000000,
    "orderId": "<Bazaar order ID>",
    "packageName": "com.example.app",
    "developerPayload": "",
    "originalJson": "<original signed receipt JSON>",
    "signature": "<Bazaar signature>"
  }
}
```

There is deliberately no client-selected customer, offering, package,
entitlement, `operation`, API key, country, app version or force version.

The backend **must**:

1. Authenticate the current V2 session and restrict every lookup to its app and
   project. Accept only a Bazaar subscription for the authenticated package.
2. Verify original receipt/signature and field consistency using the app's trusted
   Bazaar key, then obtain authoritative subscription validity/linkage from Bazaar.
   Do not trust client `purchaseTime` or `developerPayload` as ownership evidence.
3. Prove a connection to a previously verified purchase using a known token or
   authoritative linked-token chain. Resolve the original owner and catalog context
   from that purchase. Never guess an offering or assign an unknown receipt to the
   current user just because the request bears that user's session. A timestamp
   match alone must not authorize an ownership association.
4. Check the original owner against the authenticated customer/canonical identity.
   Unknown linkage or a different owner must fail closed; no implicit transfers.
5. Verify and commit a genuinely new period idempotently, with concurrent requests
   protected. Final event identity must use the store-verified period/expiry, not
   the client time alone. Replays must not create duplicate payments or grants.
6. Keep ordinary purchase-binding verification enabled. This dedicated path must
   not become an exception for receipts with a present but invalid binding.

Both initial and existing verification-status polling responses must include
the scope fields below; the SDK rejects missing/wrong source or product:

```json
{
  "status": true,
  "data": {
    "purchase": {
      "status": "PROCESSING",
      "source": "bazar",
      "productIdentifier": "subscription_sku",
      "verificationRequestId": 123,
      "retryAfter": 2
    }
  }
}
```

`COMPLETED`, `RESTORED` and `ALREADY_PROCESSED` are terminal. `PROCESSING` polls
`store/verifications/{id}/status` through the existing durable coordinator.
`DELIVERY_REQUIRED`/`CONSUME_REQUIRED` are invalid for a subscription and must
never cause host consumable delivery or native consumption.

| Result | SDK behavior |
| --- | --- |
| Network/timeout, HTTP 429/5xx | Retain checkpoint, respect retry delay/backoff, resume on recovery/restart |
| Session expired | Existing shared session renewal and bounded replay; never change queued identity |
| HTTP 422 `SUBSCRIPTION_NOT_LINKED` | Quarantine unchanged evidence for this app/customer/context |
| HTTP 422 `SUBSCRIPTION_OWNER_MISMATCH` | Same; never transfer ownership |
| HTTP 422 `SUBSCRIPTION_EVIDENCE_INVALID` | Same; never fabricate a replacement receipt |
| HTTP 422 `SUBSCRIPTION_BINDING_INVALID` | Same; never bypass binding validation |
| Scoped terminal success | Finalize checkpoint, refresh signed CustomerInfo |

If linkage is temporarily unavailable/uncertain, the backend must use a retryable
response, not a permanent 422. A permanent business rejection can also use the
existing scoped `REJECTED` status, whose evidence is quarantined.

## Durable storage and acceptance

`RECOVER_SUBSCRIPTION` uses the existing encrypted, atomic store queue. Its stable
ID includes the app, endpoint fingerprint, customer, product, token and purchase
time. Different tokens or periods cannot overwrite one another. Replays use a
fresh session only when the saved app/customer fingerprints still match.

Automatic discovery still skips receipts when fresh verified server entitlement
metadata matches **both** token hash and purchase time. Explicit restore does not
use that suppression. Unchanged token and time cannot reveal an otherwise new
period on-device; no broader re-verification schedule is introduced here.

Older SDK versions do not understand this new operation kind. Do not downgrade
after opt-in with pending recovery operations; drain them or preserve the original
encrypted queue before a coordinated rollback. Never log/export raw receipts,
purchase tokens, session tokens or encrypted-storage keys for diagnostics.

Before enabling: test real Bazaar marketplace renewals without payload, known and
linked/new tokens, wrong-account/unlinked receipts, concurrent duplicates, expired
sessions, restart/offline replay, and signed CustomerInfo refresh. SDK unit tests
use synthetic store receipts and do not establish backend or marketplace acceptance.
