# Architecture

RME PDF Scanner should use a minimal architecture for the MVP. The goal is clear ownership of UI, document handling, platform integrations, and cleanup without adding framework ceremony before the scanner and export behavior are validated.

This document records the implemented MVP architecture and guidance for narrowly scoped future work. It does not require specific class names.

## UI Layer

Responsibilities:

- Compose screens.
- Displaying state.
- Triggering user actions.
- Showing loading, errors, cancellation, and completion feedback.

Possible screens:

- Home.
- Scan result review.
- Export result or completion state.

Current UI uses local screen-state navigation for three simple surfaces: Home, Scan Result, and OCR Result. Scan Result owns save/share/export/OCR feedback, including the user-initiated searchable-PDF save flow; OCR Result owns page-specific text viewing and copy actions. For a multipage completed result, it displays only the selected scanner JPEG preview and corresponding recognized text, with local previous/next controls. This deliberately avoids Navigation Compose, bottom navigation, drawers, and tabs. `MainActivity` uses one Activity-scoped `PageHarborSessionViewModel` only for the active in-memory scan session: the current screen, scan summary, scanner-returned page/PDF URIs, completed OCR result, and selected OCR page index. That ViewModel survives configuration changes but has no `SavedStateHandle`, database, file persistence, or process-death recovery.

Static screens should not receive ViewModels by default. A coordinator or ViewModel should be introduced only when a screen has meaningful state, asynchronous work, or platform result handling that would otherwise make the composable difficult to test or maintain.

## Application Coordination

The scan flow uses one small Activity-scoped ViewModel responsible for retaining stable active-session state across configuration changes. `MainActivity` continues to own Activity-bound work and platform callbacks:

- Launching the scanner.
- Interpreting scanner results.
- Holding stable in-memory scan-session state across Activity recreation.
- Starting PDF preparation.
- Coordinating save and share actions.
- Surfacing errors to the UI.

Keep this coordination local to the scan flow. Avoid global mutable state, service locators, and broad application-level managers unless a concrete need appears.

Active OCR, PDF generation, SAF writes, picker ownership, progress, success/error feedback, coroutine jobs, streams, preview bitmaps, and prepared private searchable-PDF output are Activity-owned. They are cancelled or reset when an Activity is recreated and are never resumed automatically. OCR Result decodes only the currently selected JPEG on a background dispatcher using bounded sampling; it keeps no memory or disk image cache. The retained ViewModel does not hold an `Activity`, `Context`, streams, bitmaps, PdfBox objects, prepared outputs, or active jobs. A final Activity/task destruction also clears Activity-owned temporary output; process death starts a fresh Home state.

## Platform Integrations

Future platform integrations should have narrow responsibilities:

- Document scanner adapter: launches the selected scanner and converts scanner-specific results into RME PDF Scanner concepts.
- PDF generator: prepares a PDF locally from scanned page data.
- Searchable-PDF generator: rebuilds a PDF locally from active-session JPEG page streams and engine-neutral OCR geometry, embedding an invisible Unicode text layer.
- Searchable-PDF export coordinator: combines active-session page URIs and local OCR, owns a prepared private-cache PDF, copies it to a caller-selected SAF destination, and deletes it after use, failure, or cancellation.
- Smart-output boundary: active in-memory OCR text flows to `DocumentClassifier`, then only its `DocumentCategory` flows to `FilenameSuggestionEngine`, which supplies a fixed safe category-only suggestion to the searchable-PDF SAF picker. The user confirms or edits that value; the provider controls the final destination and name. This boundary retains neither OCR text nor filename history, adds no PDF metadata, and has no network or backend dependency.
- Temporary file manager: owns temporary file creation, lifetime, and cleanup.
- File export writer: writes a prepared document to the user-selected destination.
- Android share launcher: starts the system share sheet for a prepared or saved PDF.

Platform APIs and third-party APIs should be isolated behind small components only when isolation improves testability or keeps Android-specific code out of UI code. Do not require an interface for every class.

## Suggested Data Concepts

- ScanSession: represents an active scan workflow and the temporary resources associated with it.
- ScannedPage: represents one captured page returned by the scanner in a form RME PDF Scanner can review or export.
- PreparedDocument: represents a locally prepared export, such as a generated searchable PDF awaiting a SAF write.
- ExportResult: records whether a save or share preparation completed, was cancelled, or failed.
- ScanError: describes scanner startup, cancellation, availability, or result errors without document content.
- ExportError: describes PDF generation or file writing failures without file paths or document content.

These are concepts only. Avoid detailed schemas until the scanner API and PDF implementation are validated.

## Possible State Model

A scan flow may use a state model similar to:

- Idle.
- LaunchingScanner.
- Reviewing.
- PreparingPdf.
- AwaitingSaveDestination.
- Saving.
- Completed.
- Cancelled.
- Error.

Implementation may simplify this model where appropriate. For example, cancellation may return directly to Idle if no user-visible cancelled state is useful.

## Dependency Direction

- Compose UI should not directly perform file I/O.
- Document content should not be stored in long-lived global state.
- Scanner-specific result types should not leak throughout the application.
- PDF and file-export logic should remain independent of screen rendering.
- OCR text must not cross from classification into the filename-suggestion API; only the broad category may do so.
- Android Context should be passed only where platform APIs require it.
- Avoid service locators and global mutable singletons.
- Avoid Clean Architecture ceremony that does not provide practical value.

## Testing Strategy

- Use Compose UI tests for visible states and user actions.
- Use unit tests for state transitions and error handling.
- Use integration tests for temporary files, searchable-PDF generation, Unicode text extraction, cleanup, cancellation, and SAF export where practical.
- Use instrumentation tests for Storage Access Framework and scanner integration where possible.
- Manually validate scanner, save, share, cancellation, and cleanup behavior on at least one physical device before release.
