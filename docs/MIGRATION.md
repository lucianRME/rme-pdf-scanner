# Moving Documents From Another Scanner

Status: v1.5 interoperability guide. RME uses ordinary Android file and sharing interfaces; it does
not integrate with another scanner's private database or account.

## Supported interoperability model

From CamScanner, Adobe Scan/Acrobat, Genius Scan, or another Android scanner:

1. Select the documents in the source app.
2. Choose its Share, Export, or Send a copy action and select RME from Android's share sheet, or
   save standard files and choose **Select files**/**Select folder** in RME.
3. Review RME's preview and choose Import.

After source selection, this is at most three meaningful user actions: choose the standard export,
choose RME (or select the saved files), then review and confirm import. The source-app cards in RME
provide guidance only; selecting CamScanner, Adobe Scan/Acrobat, or Genius Scan does not grant RME
private access or invoke a proprietary integration.

RME accepts standard PDFs and supported images. It supports Android `ACTION_SEND` and
`ACTION_SEND_MULTIPLE`, URI streams in `EXTRA_STREAM`, and URI grants supplied through `ClipData`.
For bulk migration, Android's Storage Access Framework can provide multiple files or a user-selected
folder. RME reads only the URIs and grants the user explicitly selects.

## Review before import

RME inspects selected items before changing the library and reports documents, pages, folders,
estimated storage, exact duplicates, possible duplicates, and unsupported items. PDFs remain
separate documents. Images are grouped only when the importer can establish a safe sequence;
uncertain groups are shown for review.

Exact content duplicates can be skipped. Possible duplicates are never silently discarded: the
review surface allows the user to choose whether to import them. Duplicate classification does not
delete or overwrite an existing document.

## Progress, cancellation, and failures

Migration runs one document at a time with bounded reads and reports the current document, stage,
completed count, skipped items, and failures. Cancellation stops at safe boundaries and leaves the
existing library consistent. A completed document is published atomically; an incomplete document
is not made visible as a partial library item.

Unsupported files and unreadable providers are reported as skipped or failed items while valid
selected items can continue. Retryable issues are identified in the completion report. Encrypted,
damaged, or unsupported PDFs receive a safe unreadable-file result; RME does not attempt to bypass
source-app protection.

## Metadata and folder limits

RME preserves names, page order, and folder information where the selected provider exposes them.
Provider timestamps, display names, MIME declarations, and relative folder paths may be missing or
unreliable; RME validates content rather than trusting metadata and uses safe fallback values.
Original imported PDFs are retained as source assets when saved to the RME library, alongside the
editable page assets.

RME does not access another app's private storage, require root, parse undocumented proprietary
databases, or require an account. A source app that cannot export standard PDFs/images must provide
its own supported export or share action first.
