# Product Flow

RME PDF Scanner uses one active, local document workflow.

## Start

Library Home answers “What do you want to do?” with two immediate actions:

1. **Scan document** opens the ML Kit system scanner.
2. **Import files** opens the Android system document picker for one or more supported images or PDFs.

Android sharing is a third standards-based entry point. Sharing supported JPEG, PNG, WebP, or PDF
content to RME enters the same workflow. Saved documents can be searched, sorted, organized in
one-level folders, merged in a chosen order, or reopened. If useful pages already exist, new scan,
picker, or share pages append in deterministic order. Resume appears on Home only for a non-empty
active session.

## Review and continue

Scanner pages, selected images, shared images, and rendered PDF pages appear in the same Document review screen. The user can:

- move between pages;
- apply a local page filter or rotate a page;
- move a page earlier or later;
- remove a page;
- add more scanned pages or files, up to 20 pages;
- run local OCR and copy recognized text;
- save a normal or searchable PDF through SAF;
- share a PDF through Android's share sheet;
- export individual JPEG pages through SAF;
- explicitly save a new editable copy to the RME library or save changes to an existing copy; or
- extract selected saved pages as a new document or move them into a new document.

Discard removes the active session and its temporary page files. It does not delete a saved library
document. Unsaved work is not recovered after process death. **Save to RME** creates a persistent
app-private copy; deleting that saved document removes only RME's copy. Files explicitly exported or
shared by the user belong to the chosen destination or recipient.

## Import behavior

- The picker and share receiver accept JPEG, PNG, WebP, and PDF only.
- Actual signatures and image/PDF readability are validated locally.
- A PDF is copied temporarily, rendered sequentially with `PdfRenderer`, and never modified.
- Meaningful work shows progress and can be cancelled.
- Invalid items in a multi-file selection are skipped when at least one item succeeds; the completion message reports the skipped count.
- Unsupported, inaccessible, oversized, encrypted/damaged PDF, page-limit, temporary-file, cancellation, and stale-operation outcomes are concise and retryable.
- Cancelling an append keeps the existing document unchanged.

## Lifecycle and privacy

A stable completed session survives ordinary Activity recreation. Active import, OCR, generation, or
write work is cancelled and may be retried; stale callbacks cannot mutate a newer document. Unsaved
process-death recovery is intentionally unsupported, while explicitly saved library documents remain
available. Orphaned private cache files are cleaned later.

All processing, library storage, OCR indexing, and search are local. RME uses system pickers and URI
grants, requests no broad storage access, and does not operate accounts, analytics, advertising,
telemetry, cloud storage, or a document backend. Android backup and device transfer remain disabled.
