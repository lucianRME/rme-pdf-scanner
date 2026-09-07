# Product Flow

RME PDF Scanner is in early development. This document describes the intended MVP flow; it does not describe implemented scanning behavior.

## Intended MVP Journey

1. User opens RME PDF Scanner.
2. User selects Scan document.
3. The system scanner captures one or more pages.
4. User reviews the captured pages.
5. User can reorder or remove pages if supported by the scanner result.
6. RME PDF Scanner prepares a PDF locally.
7. User selects where to save the PDF through the Android system file picker.
8. User may optionally share the resulting PDF through the Android share sheet.
9. Temporary files are removed when they are no longer needed.

## MVP

- Launch document scanning from a user action.
- Receive one or more scanned pages.
- Show a simple review summary before export.
- Export a PDF locally.
- Save through the Android Storage Access Framework.
- Share through the Android share sheet.
- Handle cancellation and errors without retaining unnecessary temporary files.

## Not In The Initial MVP

- Accounts.
- Proprietary cloud storage.
- Sync between devices.
- OCR.
- Searchable PDFs.
- Document library.
- Tags or folders managed by RME PDF Scanner.
- Biometric vault.
- Editing individual page filters after scanner completion.
- Subscriptions.
- Analytics.
- Background uploads.
- Automatic backup.

## Success Flow

1. The user starts a scan from the Home screen.
2. The scanner returns one or more pages.
3. RME PDF Scanner shows a review summary and available page actions.
4. The user confirms export.
5. RME PDF Scanner prepares a PDF on the device.
6. The user chooses a save destination through the system file picker.
7. RME PDF Scanner writes the PDF to the selected destination.
8. RME PDF Scanner offers completion feedback and an optional share action.
9. Temporary scan and export files are deleted when no longer needed.

## Failure And Cancellation Flows

- If the user cancels the scanner, RME PDF Scanner returns to a neutral state without creating an export.
- If the scanner component is unavailable, RME PDF Scanner explains that scanning cannot start and avoids requesting unrelated permissions.
- If PDF generation fails, RME PDF Scanner reports the failure and removes temporary files where possible.
- If the user cancels file destination selection, RME PDF Scanner keeps the prepared document only as long as needed for retry or cancellation handling.
- If file write fails, RME PDF Scanner reports the error without exposing file paths or document details in logs.
- If the share sheet is dismissed, RME PDF Scanner treats sharing as cancelled and keeps the saved export unchanged.

## Expected Cancellation Points

- User cancels scanner.
- Scanner component is unavailable.
- PDF generation fails.
- User cancels file destination selection.
- File write fails.
- Share sheet is dismissed.

These flows must be validated during implementation because platform scanner, file picker, and share sheet behavior can vary by device and Android version.
