# Lifecycle and Failure Validation

Status: `v0.6.0-dev` lifecycle closure with `v0.8.0-dev` beta-readiness follow-up complete.

## Ownership model

Searchable-PDF preparation, destination writing, and prepared temporary output are owned by the current `MainActivity` instance. `SearchablePdfOperationTracker` issues an in-memory operation token for each preparation. Progress applies only while the token is current and preparation has not completed. Exactly one completion may publish a prepared export; a superseded completion discards only its own prepared output, while a duplicate completion does nothing. It therefore cannot replace or delete a newer prepared export.

On scan replacement, Discard, or Activity destruction, the activity invalidates the token, cancels its searchable-PDF job, and discards only its currently owned prepared output. A later picker result with no owned prepared export is ignored. The coordinator uses a distinct private random temporary file for each prepared export and cleanup is idempotent.

## Cancellation and picker behavior

Cancellation propagates through PDF generation and streamed destination copy. It is not mapped to a generic provider or generation failure. Picker cancellation discards the prepared output and leaves the Scan Result retryable. A stale Activity Result callback has no owned prepared export and is ignored safely; it cannot initiate a write for a newer export.

No active-operation recovery is attempted after process death or Activity recreation. On recreation, stable scan/OCR state remains available in memory, while active work is cleaned up and the user can retry from the restored Scan Result. Process death starts a new active scan session.

## Update and process-reset boundary

An APK update preserves Android app-private storage and cache unless Android or the user clears it,
but RME PDF Scanner persists no scan session, OCR result, picker ownership, or document library. A fresh
process after an update therefore starts at Home; it must not restore document content or progress.
User-selected SAF exports remain outside RME PDF Scanner ownership. Shared-PDF cache files older than 24
hours and searchable-PDF cache files matching the app-owned `searchable-*.pdf` naming boundary are
cleaned asynchronously at a later startup. The cleanup never traverses directories, deletes active
files, or touches SAF destinations.

### `v0.8.0-dev` local update evidence

On Samsung SM-S938B / Android 16, a temporary detached `v0.7.0-dev` worktree built the
debug-signed minified `releaseVerification` APK. Android installed that APK, then accepted the
`v0.8.0-dev` `releaseVerification` APK over the same package without uninstalling. The updated
package reported version code 8/version name `0.8.0-dev` and launched `MainActivity` after a
force-stop. This was deliberately performed with no active operation; no migration or persistent
session is required. A subsequent uninstall and clean `v0.8.0-dev` install also launched normally,
and its app-private cache/files/database/shared-preferences inventory was empty. Normal downgrade
was not attempted and is not a user-supported path.

The v0.8 beta smoke protocol retains manual checks for process kill during scanner/OCR/PDF work,
two sequential document sessions, and five repeated sessions using non-sensitive documents. They
are not inferred from the update installation check.

## Deterministic coverage

`SearchablePdfOperationTrackerTest` verifies active progress, token supersession, single completion claims, duplicate completion rejection, and invalidation. The Activity uses those claims before publishing or discarding a prepared export, so old progress or completion cannot overwrite a new operation.

`LocalSearchablePdfExportCoordinatorTest` uses in-memory output streams at the SAF boundary. It verifies cleanup and safe error categories for a null output stream, provider-open exception, immediate write failure, mid-copy failure, flush failure, close failure, missing prepared output, and copy cancellation. In every tested failure path the private prepared PDF is deleted and the source scan remains untouched. A simulated private-cache deletion refusal is also non-crashing and never reports success; cleanup remains explicitly best-effort in that exceptional filesystem condition. Generator tests cover first- and later-page JPEG failures, generation cancellation, and partial-output cleanup. Repeated discard and cleanup of an already missing output are harmless.

For `v0.7.0-dev`, the coordinator also injects private-cache creation and source-stream boundaries
for deterministic tests. An unavailable cache returns `TEMPORARY_STORAGE_UNAVAILABLE` before
generation; a null source stream returns the existing safe preparation failure and deletes the
partial private output. Normal PDF copy tests cover short source reads and closing an already-open
destination when the source is unavailable. `MainActivity` maps malformed or unavailable SAF
source and destination URIs to existing safe source/destination categories rather than allowing a
provider exception to escape. These are controlled boundary tests, not a claim that every external
SAF provider or physical low-storage condition has been reproduced.

`MainActivityLifecycleTest` uses `ActivityScenario` to recreate a deterministic completed Scan Result, completed OCR Result, and controlled active searchable-PDF state without arbitrary sleeps. It confirms that scan/OCR state remains available after recreation, active searchable-PDF state resets to retryable idle, Discard clears the retained session, and a replacement scan replaces the old state. `PageHarborSessionViewModelTest` covers the same stable-state boundary directly. Existing token and coordinator tests cover stale completion rejection and prepared-output cleanup beneath that boundary.

## Remaining validation

## Samsung manual smoke

Fresh independent one-page scanner sessions on Samsung SM-S938B / Android 16 confirmed that searchable-PDF preparation opens DocumentsUI with the category-safe `document.pdf` title for an unclassified scan. Picker cancellation returns to Scan Result with the accessible product-facing cancellation feedback and permits a successful retry. A manually confirmed retry returned the accessible success feedback. Backgrounding and foregrounding PageHarbor preserved the active Scan Result without a crash or stale feedback.

The prior rotation defect is fixed in production: an Activity-scoped in-memory session ViewModel retains the active Scan Result screen, scan summary, ordered scanner URIs, and completed OCR result across configuration changes. Active OCR, searchable-PDF preparation/write, SAF picker ownership, progress, and transient success/error/cancellation feedback are cancelled or reset to idle; they are never resumed automatically. Prepared private searchable-PDF output is discarded when its original Activity loses ownership. No process-death recovery or persistent document storage is supported.

After the fix, a Samsung manual rotation sequence passed: a fresh Scan Result survived portrait-to-landscape-to-portrait rotation with its actions available; searchable-PDF picker cancellation returned to Scan Result; rotation before retry did not cause a duplicate picker or false error; and a retry saved successfully. Completed OCR Result also survived rotation, Back returned to Scan Result, Discard returned Home, and a fresh scan showed no old scan/OCR feedback. No crash or permission prompt occurred.

Physical third-party provider behavior and a physical mid-copy provider failure remain unvalidated. No physical process-death recovery is supported or planned.

## v0.8 beta matrix follow-up

The Samsung execution recorded in [BETA_MATRIX_RESULTS.md](BETA_MATRIX_RESULTS.md) subsequently
completed repeated real scanner sessions, normal/searchable PDF, share cancellation, JPEG export,
one/three-page OCR, Copy Text, rotation, and ordinary background/foreground checks on
releaseVerification. A process kill while normal-PDF DocumentsUI was open reset safely to Home with
no false success. Active OCR/searchable-PDF interruption remains unverified because the temporary
fixtures completed too quickly to guarantee an in-progress physical interruption. Deterministic
instrumentation now pauses fake OCR after start and fake PDF generation after private output setup:
background/foreground completes once, Discard/recreation rejects stale completion, no duplicate
destination request is emitted, and invalidated output is deleted. This does not claim a physical
OCR/searchable-PDF process kill. App-private inventory is not available through `run-as` on the
intentionally non-debuggable releaseVerification artifact.

### Tablet deterministic evidence

On a stable Android 12/API 31 Pixel Tablet emulator, the complete 103-test connected suite passed.
Its deterministic `ActivityScenario` lifecycle coverage continued to verify completed Scan Result
and OCR Result recreation, active searchable-PDF reset, Discard cleanup, and replacement-session
state boundaries at tablet density. The large-screen manual Home checks also survived reversible
display-size changes and Home background/foreground without a crash. Headless scanner input did not
complete a real tablet Scan Result or OCR Result, so active-operation rotation/background and
selected-page persistence remain manual matrix gaps rather than inferred tablet claims.
