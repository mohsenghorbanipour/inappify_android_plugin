# Upgrading to 3.2.0

Version 3.1.0 adds automatic selected-store reconciliation after Configure.
Android integrations on 3.0.0 only need the dependency upgrade; the public API,
MethodChannel and existing V2 journal formats are unchanged. Flutter must use
package/native dependency and Dart runtime version 3.2.0 together. Background
recovery resumes pending checkpoints and checks new owned receipts against
verified CustomerInfo before submitting missing evidence. Real store/backend
acceptance remains required.

Version 3.2.0 also accepts bound subscription receipts without `productType`;
the marketplace subscription query establishes that type. Original receipt
evidence is preserved and unknown in-app purchases retain their server workflow.
Other account/app payload guards still apply, so this does not migrate unbound
V1 receipts. No new MethodChannel API is required.

## Changes introduced in 3.0.0

Version 3.0.0 uses V2 exclusively. The default `InappifyClient.create(context)`
now returns `InappifyV2Client`; the explicit V2 factory remains available. The
old default implementation, V1 service and routes, and
`bindLegacyPurchaseClient` have been removed. Recompile integrations against the
new major release; binary compatibility with the old factory descriptor and
removed API is intentionally not promised.

## Before upgrading

Complete pre-existing V1 paid operations with their original client,
credentials, identity and inventory ledger. The version 3 client does not import
V1 sessions or turn an old delivery/receipt into a V2 operation. Old state is
neither cleared nor relabeled by this migration. Already-created V2 sessions,
Store journals and Direct delivery checkpoints keep their existing formats and
namespaces.

## Configuration and payment

Keep the public app key for Configure/renewal and the selected market's RSA
public key for billing. All later requests use the current verified session
Bearer. Remove purchase companion creation and binding. SDK/session APIs use
`service.inappify.com/app/v2/`; commerce uses `api.inappify.com/app/v2/`.

The backend's signed store route is authoritative. Configure the registered
Android application for DirectAndroid, Bazar or MyKet as appropriate. Myket
supports consumable and non-consumable in-app products only. Use explicit product
types for purchases and keep durable, idempotent host fulfillment before ACK.

## Attributes and current offering

V2 attributes are write-only, durable queued changes. Null or an empty value
removes a key. Use `queueAttributes` followed by
`syncAttributesAndOfferingsIfNeeded` before selecting a targeted paywall.
Offerings include server-selected `currentOffering` and `placements`; no V1 rule
fallback is run by the V2 client. Configure and Offerings report SDK version
`3.2.0` exactly. App version is a separate value.

## Flutter and iOS

Flutter package/native dependency and Dart SDK version are 3.2.0.
`InappifyMobileProtocol` contains only `goV2`, which is the default. Android uses
the native SDK through MethodChannel. iOS uses the V2 Dart implementation and
Keychain storage; Bazaar/Myket Android billing is not invoked on iOS. The legacy
Dart HTTP client and iOS purchase companion are removed. Server offering
selection survives the Android bridge and Dart snapshot/cache model. Web keeps
its dedicated JavaScript adapter; mobile protocol selection does not switch a
Web session to a mobile HTTP backend.

## Acceptance

Check login/logout recovery, custom `family` targeting, SDK-version targeting,
store cancellation/purchase/restore, process restart, durable delivery and
consume/ACK failures with the real registered application. Build and synthetic
HTTP tests cannot confirm backend targeting configuration or real payment.
