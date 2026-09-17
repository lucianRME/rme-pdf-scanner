# Architecture

RME PDF Scanner uses one local document pipeline for scanning, import, persistent-library storage,
review, OCR, search, and export. Documents remain on the device.

## UI and state

The Compose UI has three surfaces: the adaptive Library Home, Document review, and OCR Result. Home
presents one-tap Scan and Import actions, title/OCR search, folder and sort controls, saved-document
cards, and Resume while a useful active session exists. Document review presents ordered pages,
non-destructive filters, rotation, move, remove, add, local-library save/update, extract/split, OCR,
save, share, and JPEG export actions.

`MainActivity` owns Android result launchers and active asynchronous jobs. It starts the ML Kit scanner, the Storage Access Framework picker, local import preparation, OCR, PDF preparation, SAF writes, and the Android share sheet. Activity recreation cancels active work and rejects stale completion, while the Activity-scoped `PageHarborSessionViewModel` retains only stable in-memory session and completed OCR state.

`PageHarborSessionViewModel` deliberately has no `SavedStateHandle`, database, `Context`, stream,
bitmap, or background job. Configuration changes retain a stable active result; process death drops
only the unsaved active session. `LibraryViewModel` exposes Room-backed flows and runs bounded file and
database operations off the main thread.

## Unified document session

`DocumentSession` is the downstream source of truth. It contains ordered `DocumentPage` values with
stable runtime IDs, opaque resource references, source category, MIME type, dimensions, rotation, and
filter state. Saved pages also carry their durable library page ID. Scanner pages, selected images,
inbound shares, rendered PDF pages, and reopened library pages use the same review/OCR/export behavior.

`DocumentAcquisitionCoordinator` serializes replace or append operations, applies the shared 20-page and image-size limits, assigns page IDs, rejects stale tokens, and transfers only validated resources into the session. Scanner, picker, share intent, and PDF import therefore converge before the UI and processing pipelines.

An existing scanner PDF is only a direct-copy optimization while its pages remain unchanged and in their original order. Imported, appended, removed, reordered, rotated, or filtered pages use the existing local PDF recomposition path.

## Import adapters

The picker uses `OpenMultipleDocuments` with JPEG, PNG, WebP, and PDF MIME types. Inbound `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents advertise the same exact types. Both preserve incoming order and append to an existing session; they never silently replace useful pages.

The import processor checks file signatures and readability instead of trusting extensions or provider labels. External image URIs remain user/provider owned. PDF input is copied with a 128 MiB bound to a seekable app-private file, opened with Android `PdfRenderer`, and rendered sequentially to bounded JPEG pages. A document contains at most 20 pages. Rendering targets 144 dpi where possible and never exceeds 4,096 pixels on an edge or 12.5 megapixels.

## Ownership and cleanup

Every resource is user/external, RME-owned temporary data, or RME-owned persistent library data. RME
never deletes an external URI. App-created PDF import files are registered with the active acquisition
token immediately and live only below `cache/document-imports`. Source copies are deleted after
preparation; rendered pages transfer to the active session and are deleted on removal, replacement,
discard, cancellation, lifecycle invalidation, or final ViewModel cleanup. Old orphaned import files
are removed on a later cold session.

Short-lived session leases keep owned page sources alive while OCR or export code is reading them. Canonical-path checks constrain deletion to the private root that created each file. Share copies, normal-PDF preparation, and searchable-PDF preparation use separate private cache roots and lifetimes.

## Persistent local library

Room version 1 stores normalized document, page, folder, and FTS4 search records. Page bytes and
bounded thumbnails live below `files/document-library/<document-id>/revisions`. A save writes a full
new revision first, switches the database transaction to that revision, reopens it as the active
`DocumentSession`, releases its session lease, and only then removes older revisions. Interrupted
preparation never replaces the last complete database record. The first persistent schema needs no
migration from v1.3 because earlier releases had no library database; the schema is exported so every
future version change can carry an explicit migration test.

Search indexes normalized titles and only OCR text created by an explicit recognition/save action.
Deleting a document removes its Room/FTS records and app-owned directory. Deleting a one-level folder
moves its documents to no folder. Backups and device transfer exclude both the database and page files.

## Dependency and privacy boundaries

- Compose does not perform document I/O.
- Scanner-specific types stop at the Activity adapter.
- UI and downstream processors use the common page model.
- OCR text remains local and is never logged; it is persistent only in an explicitly saved document's
  local search index.
- SAF and the Android share sheet keep destinations under user control.
- RME declares no `INTERNET` or broad-storage permission and adds no account, analytics, advertising, telemetry, backend, or cloud-provider SDK.

## Validation

Pure state, ordering, limits, errors, and ownership are unit tested. Android instrumentation covers Compose behavior, content URIs, local image/PDF processing, OCR/export integration, lifecycle invalidation, and temporary-file cleanup. Physical-device smoke testing uses only synthetic files.
