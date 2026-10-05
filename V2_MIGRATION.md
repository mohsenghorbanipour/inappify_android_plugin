# V2 sessions and commerce in 3.0.0

The 3.0.0 major release follows the supplied Android purchase migration contract
(version 1.1). V2 is the only mobile HTTP implementation and is the default for
both Android factories and Flutter. Read the [upgrade guide](docs/MIGRATION.md)
before replacing an installation with pending V1 payments.

The SDK factory bundles official signing trust. Configure accepts the public SDK
app key; app/project scope is learned only from a verified response. A custom
`InappifyV2Configuration` can constrain issuer, appId/projectId, pinned signing
keys and exact payment/asset hosts beforehand. `apiBaseUrl` remains an alias for
`sdkApiBaseUrl`. SDK and commerce origins can be set independently; both require
HTTPS with the exact `/app/v2/` base path.

SDK sessions/resources use `https://service.inappify.com/app/v2/`; Direct/store
commerce uses `https://api.inappify.com/app/v2/`. Both authenticate after
Configure with the same opaque session Bearer. Commerce JSON contains neither
legacy credentials nor app/country/version context. Receipt token evidence is
kept inside `purchase`. No V1 companion exists.

Signed CustomerInfo checks Ed25519, audience, contract version, subject, scope,
expiry and clock bounds before publication. Runtime JWKS cannot replace a pinned
key with different material. Login/logout attempts and anonymous IDs are durable;
unknown transitions do not expose a predecessor customer's state. Session expiry
renews the same identity under the mutex and allows at most one defined replay.
Invalid or revoked sessions do not loop through Configure.

Attributes are durable write-only batches. Offerings carry server-selected
currentOffering/placements and SDK version `3.0.0`; use sync/fetch before a
custom-attribute paywall. Flutter forwards server selection through the native
bridge and preserves it in the cached Dart model. Full remote rendering remains
subject to the SDK's presentation compatibility gates.

V2 Store journals and Direct delivery documents preserve their 2.5 formats and
namespaces. The host supplies a durable idempotent ledger before delivery ACK,
then the selected store consumes and reports. A major version upgrade never
relabels a V1 receipt or copies its credential into a V2 request.
