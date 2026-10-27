# AGENTS.md

## Project purpose

RME: Private PDF Scanner is an open-source, privacy-first Android document scanner developed under the SynapseWorks brand. It is a released application; the current published stable release is v1.7.1. Build it around local document processing and user-controlled storage and sharing. Use RME PDF Scanner as the compact product name.

## Privacy principles

- Keep document processing on the device.
- Do not require an account or login.
- Do not introduce ads, tracking, analytics, or telemetry.
- Let users choose where files are saved or shared through Android system interfaces.
- Do not operate proprietary cloud storage or send documents to a backend.
- Prefer native Android system components over custom cloud integrations.
- Document decisions that affect privacy, permissions, document handling, or data retention.
- Do not weaken an existing privacy guarantee to simplify implementation.

## Approved technology direction

- Kotlin
- Jetpack Compose
- Material 3
- Gradle Kotlin DSL
- Android Storage Access Framework for user-selected file destinations
- Android share sheet for sharing
- Existing Google ML Kit Document Scanner and on-device OCR integration, subject to the documented privacy boundaries in `PRIVACY.md` and `docs/PRIVACY_BOUNDARIES.md`

Treat this list as direction, not authorization to add dependencies before they are needed and reviewed.

## Released functionality and change scope

The released application supports:

- Multi-page scanning and PDF/image import through Android system interfaces
- Page review, reordering, rotation, and enhancement
- On-device OCR, saved-text search, and searchable PDF export
- An app-private document library with folders and document metadata
- PDF/JPEG export, merge, split, and size-targeted copies
- Share-Safe redaction and two-sided card copies
- User-controlled portable backup and restore, with optional backup encryption
- Optional app access protection through Android device authentication
- Saving through the Android system file picker and sharing through the Android share sheet

Keep changes focused on the requested work. Do not add unrelated features or speculative extensibility.

## Architecture guidance

- Use a simple, maintainable architecture with clear separation between UI, document-processing, and storage responsibilities.
- Prefer platform APIs and native Android system components where they satisfy the requirement.
- Keep document data local and pass only the minimum data required between components.
- Avoid unnecessary abstractions, indirection, and premature framework choices.
- Make ownership and cleanup of temporary files explicit.
- Preserve user control over output locations and sharing destinations.

## Dependency rules

- Add dependencies only when they are necessary for approved scope.
- Prefer AndroidX, platform APIs, and well-maintained open-source libraries with compatible licenses.
- Evaluate dependencies for network behavior, data collection, transitive dependencies, permissions, and privacy impact.
- Record privacy-sensitive dependency decisions in the relevant documentation or change description.
- Do not add cloud-provider SDKs or backend client libraries without explicit repository-owner approval.

## Permission rules

- Request only permissions required for a user-initiated core feature, and request them as late as practical.
- Use Android system pickers and scoped access instead of broad storage permissions.
- Camera and file access must be limited to the operation the user requested.
- Avoid the Android `INTERNET` permission.
- Any new permission requires a documented justification and review of a platform alternative.

## Logging and sensitive data

- Never log document content, file names, file paths, OCR text, images, or sensitive metadata.
- Do not include sensitive data in exceptions, analytics, debug output, test fixtures, screenshots, or crash reports.
- Use minimal diagnostic logging and remove temporary debug logging before committing.
- Clean up temporary files promptly when they are no longer required, including after failures or cancellation.
- Verify temporary-file cleanup with tests where practical.

## Testing and quality checks

- Add focused tests for new behavior, including error, cancellation, and cleanup paths.
- Test permission behavior and storage interactions without assuming a specific file provider.
- Review changes for accidental network access, sensitive logging, and retained temporary files.
- Run the relevant build, unit and instrumentation tests, and lint before handing off changes.
- Report any checks that could not be run and why.

## Git and commit practices

- Keep commits small, focused, and descriptive.
- Do not mix unrelated refactoring with feature or documentation changes.
- Review the diff for generated files, secrets, local configuration, document samples, and sensitive metadata before committing.
- Do not rewrite or discard repository-owner changes without explicit approval.
- Update documentation when behavior, permissions, dependencies, or privacy assumptions change.

## Actions requiring explicit approval

Agents must not add any of the following without explicit approval from the repository owner:

- Analytics
- Advertising
- Tracking
- Telemetry
- Backend services
- The Android `INTERNET` permission
- Cloud-provider SDKs
- Any change that weakens the project's privacy guarantees
