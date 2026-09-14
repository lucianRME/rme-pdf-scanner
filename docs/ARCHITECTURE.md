# Architecture

RME PDF Scanner uses a small, session-local architecture for scanning, import, review, OCR, and export. Documents remain on the device and RME keeps no persistent document library.

## UI and state

The Compose UI has three surfaces: Home, Document review, and OCR Result. Home presents one-tap Scan and Import actions and shows Resume only while a useful active session exists. Document review presents the ordered pages, non-destructive filters, rotation, move, remove, add, OCR, save, share, and JPEG export actions.

`MainActivity` owns Android result launchers and active asynchronous jobs. It starts the ML Kit scanner, the Storage Access Framework picker, local import preparation, OCR, PDF preparation, SAF writes, and the Android share sheet. Activity recreation cancels active work and rejects stale completion, while the Activity-scoped `PageHarborSessionViewModel` retains only stable in-memory session and completed OCR state.

The ViewModel deliberately has no `SavedStateHandle`, database, retained document copy, `Context`, stream, bitmap, or background job. Configuration changes retain a stable result; process death starts a fresh session.

## Unified document session

`DocumentSession` is the downstream source of truth. It contains ordered `DocumentPage` values with stable session-only IDs, opaque resource references, source category, MIME type, dimensions, rotation, and filter state. Source categories distinguish scanner pages, selected images, inbound shares, and locally rendered PDF pages without changing review, OCR, or export behavior.

`DocumentAcquisitionCoordinator` serializes replace or append operations, applies the shared 20-page and image-size limits, assigns page IDs, rejects stale tokens, and transfers only validated resources into the session. Scanner, picker, share intent, and PDF import therefore converge before the UI and processing pipelines.

An existing scanner PDF is only a direct-copy optimization while its pages remain unchanged and in their original order. Imported, appended, removed, reordered, rotated, or filtered pages use the existing local PDF recomposition path.

## Import adapters

The picker uses `OpenMultipleDocuments` with JPEG, PNG, WebP, and PDF MIME types. Inbound `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents advertise the same exact types. Both preserve incoming order and append to an existing session; they never silently replace useful pages.

The import processor checks file signatures and readability instead of trusting extensions or provider labels. External image URIs remain user/provider owned. PDF input is copied with a 128 MiB bound to a seekable app-private file, opened with Android `PdfRenderer`, and rendered sequentially to bounded JPEG pages. A document contains at most 20 pages. Rendering targets 144 dpi where possible and never exceeds 4,096 pixels on an edge or 12.5 megapixels.

## Ownership and cleanup

Every resource is either user/external or RME-owned temporary data. RME never deletes an external URI. App-created PDF import files are registered with the active acquisition token immediately and live only below `cache/document-imports`. Source copies are deleted after preparation; rendered pages transfer to the active session and are deleted on removal, replacement, discard, cancellation, lifecycle invalidation, or final ViewModel cleanup. Old orphaned import files are removed on a later cold session.

Short-lived session leases keep owned page sources alive while OCR or export code is reading them. Canonical-path checks constrain deletion to the private root that created each file. Share copies, normal-PDF preparation, and searchable-PDF preparation use separate private cache roots and lifetimes.

## Dependency and privacy boundaries

- Compose does not perform document I/O.
- Scanner-specific types stop at the Activity adapter.
- UI and downstream processors use the common page model.
- OCR text remains active-session data and is never logged.
- SAF and the Android share sheet keep destinations under user control.
- RME declares no `INTERNET` or broad-storage permission and adds no account, analytics, advertising, telemetry, backend, or cloud-provider SDK.

## Validation

Pure state, ordering, limits, errors, and ownership are unit tested. Android instrumentation covers Compose behavior, content URIs, local image/PDF processing, OCR/export integration, lifecycle invalidation, and temporary-file cleanup. Physical-device smoke testing uses only synthetic files.
