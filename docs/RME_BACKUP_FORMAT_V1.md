# RME Backup Format Version 1

Status: approved v1.5 portability contract. This document specifies the public backup format; it is
independent of the Room schema and the encrypted-envelope version.

## Scope

RME Backup v1 reconstructs the complete local library from stable metadata and original bytes. It is
not a Room database dump and is separate from the human-readable **Export library** feature. It
preserves nested folders, documents, editable page assets, page order and edits, saved OCR state, and
original imported source assets such as PDFs. It does not contain Room row IDs, private database or
revision paths, FTS rows, thumbnails, caches, preferences, app-lock material, review state, or unsaved
sessions.

An unencrypted backup is a standard ZIP. A password-protected backup wraps that complete ZIP byte
stream, including every metadata stream, in the separately versioned authenticated envelope below.

## ZIP layout

Entries use UTF-8 forward-slash paths and stable IDs rather than sensitive titles:

```text
manifest.json
metadata/folders.jsonl
metadata/documents.jsonl
metadata/pages.jsonl
metadata/source-assets.jsonl
documents/<document-id>/pages/<position>-<page-id>.jpg|png|webp
documents/<document-id>/sources/<source-id>.pdf
checksums.sha256
```

`metadata/source-assets.jsonl` is required but may be empty. Source assets may use an extension other
than `.pdf` only when a future optional feature defines that MIME type; v1 requires PDF support.
ZIP entry order and compression method are not semantic.

A reader rejects absolute paths, empty segments, `.` or `..`, backslashes, NULs, duplicate entry
names, canonical-name collisions, symbolic links, undeclared entries, and entries outside the roots
above. Each required metadata entry and `checksums.sha256` occurs exactly once.

## Manifest and capability negotiation

`manifest.json` is a single UTF-8 JSON object with these required fields:

| Field | Type | v1 value or meaning |
| --- | --- | --- |
| `formatVersion` | integer | `1` |
| `minimumReaderVersion` | integer | `1` |
| `requiredFeatures` | string array | Empty for the base format |
| `backupId` | string | UUID for this completed attempt |
| `createdAtEpochMillis` | integer | Time creation began |
| `producer` | object | `applicationId`, `versionName`, positive `versionCode` |
| `summary` | object | Folder, document, page, source-asset, and content-byte totals |
| `metadata` | object | Exact paths of the four JSONL metadata streams |
| `integrity` | object | Algorithm `SHA-256`, entry `checksums.sha256` |

Readers ignore unknown optional fields, reject unknown required features, and reject a future
`formatVersion` or incompatible `minimumReaderVersion` before reading assets or mutating the library.

## JSONL metadata

Every non-empty JSONL line is one self-contained UTF-8 JSON object. A line has a bounded maximum
length, and readers process lines incrementally. Unknown optional fields are ignored.

Folder records require:

- `folderId`, `name`, nullable `parentFolderId`;
- `createdAtEpochMillis`, `modifiedAtEpochMillis`.

Folder IDs are unique, parent IDs resolve, and the graph is acyclic. Nested hierarchy is preserved;
it is never silently flattened.

Document records require:

- `documentId`, nullable `folderId`, `title`;
- `createdAtEpochMillis`, `modifiedAtEpochMillis`;
- `contentHashVersion`, nullable `contentSha256`;
- `pageCount`, `sourceAssetCount`.

Page records require:

- `pageId`, `documentId`, zero-based contiguous `position`;
- `relativePath`, verified image `mimeType`, `sha256`, `byteLength`;
- measured positive `width` and `height`;
- `rotationDegrees` in `0`, `90`, `180`, or `270`;
- a supported `filterName`;
- nullable `ocrText` and nullable portable `ocrError`;
- optional non-negative `sourcePageIndex` linking a page to an original PDF page.

Source-asset records require:

- `sourceId`, `documentId`, `role`, `relativePath`;
- verified `mimeType`, `sha256`, `byteLength`;
- nullable `sourceModifiedAtEpochMillis`;
- `matchesCurrentRevision`.

The initial role is `ORIGINAL_DOCUMENT`, with `application/pdf`. A retained original does not replace
the editable page assets: the page assets, order, rotation, and filter are authoritative for current
state. Dimensions, MIME signatures, byte lengths, and hashes are remeasured during restore rather
than trusted blindly. Page counts, OCR aggregate status, normalized names, thumbnails, and FTS rows
are derived and rebuilt.

The portable format intentionally has no 20-page limit. The ML Kit scanner may retain its interactive
20-page limit, but migration, import, backup, and restore must preserve longer documents without
truncation or silent splitting.

## Integrity ledger

`checksums.sha256` is UTF-8 text. Every other archive entry has exactly one line:

```text
<64 lowercase hexadecimal SHA-256><two spaces><decimal byte length><two spaces><canonical path>
```

Lines are sorted by canonical path. Paths cannot contain CR, LF, or leading/trailing whitespace. No
extra or missing records are permitted. Writers hash while streaming, write the ledger last, close
the ZIP, reopen the user-selected SAF URI, and verify the container, manifest, entries, sizes, hashes,
relationships, totals, and physical end-of-file. In an unencrypted backup these hashes detect
accidental corruption; they do not make the archive tamper-resistant.

## Password-protected envelope

Encrypted backups use the binary `RMEENC01` envelope. The whole inner ZIP is encrypted, so document
titles, folder names, OCR text, counts, and identity-revealing paths are unavailable without the
password. The password is never stored or logged, and portability never depends on Android Keystore.
All multibyte integers are unsigned big-endian.

The 64-byte header is:

| Offset | Size | Value |
| ---: | ---: | --- |
| 0 | 8 | ASCII `RMEENC01` |
| 8 | 2 | envelope version `1` |
| 10 | 2 | header length `64` |
| 12 | 1 | KDF ID `1` = PBKDF2-HMAC-SHA256 |
| 13 | 1 | KDF semantic version `1` |
| 14 | 1 | AEAD ID `1` = AES-256-GCM |
| 15 | 1 | nonce mode `1` |
| 16 | 4 | iteration count; v1 writers use at least `600000` |
| 20 | 4 | data chunk size; v1 writers use `4194304` bytes |
| 24 | 1 | salt length `16` |
| 25 | 1 | nonce length `12` |
| 26 | 1 | tag length `16` |
| 27 | 1 | flags `0` |
| 28 | 16 | random salt |
| 44 | 12 | random base nonce |
| 56 | 8 | reserved zero bytes |

The KDF identifier, KDF semantic version, and concrete iteration count are stored in every header. A
reader enforces bounded parameters before KDF work or allocation. A future KDF such as Argon2id
receives a new identifier and version without changing older semantics.

The password is used exactly as entered without Unicode normalization. Encode it as UTF-8, then RFC
4648 Base64 without padding, and pass those ASCII characters to `PBEKeySpec`. Derive a 256-bit key.
Implementations enforce a password-byte ceiling and clear mutable password, intermediate, and key
buffers best-effort.

Each independently authenticated frame has a 16-byte prefix: one-byte type, three zero
flag/reserved bytes, unsigned 64-bit sequence, and unsigned 32-bit plaintext length. Types are
`KEY_CHECK = 0`, `DATA = 1`, and `END = 127`. Associated data is ASCII
`RME-BACKUP-ENVELOPE-V1`, one NUL byte, the exact header, and the exact frame prefix. The 12-byte nonce
is the first four base-nonce bytes followed by the last eight base-nonce bytes XOR the big-endian
sequence.

Sequence zero is a key-check frame containing a fixed 32-byte versioned value. Data frames use
strictly consecutive sequences `1..N`. Sequence `N+1` is the end frame containing ASCII `RME-END1`,
the total plaintext ZIP byte count, the data-frame count, SHA-256 of the complete plaintext ZIP, and
reserved zero bytes. Every frame has a 128-bit GCM tag. A valid end tag and physical EOF immediately
after it are mandatory.

Wrong password, modified header or frame, frame deletion/reordering, nonce reuse, truncation, a
missing end marker, digest mismatch, or appended bytes fails authentication. The UI may combine wrong
password and damaged-backup errors. A failed writer is permanently unusable; retry starts a new file
with a fresh salt and base nonce.

## Restore and activation

Restore is staged and recoverable:

1. Inspect the minimal outer header and obtain a password only when required.
2. Stream-decrypt and extract into an operation-owned private directory with entry, byte, depth,
   compression-ratio, KDF-cost, and metadata-size limits.
3. Validate the complete envelope, ZIP, manifest compatibility, signatures, measured metadata,
   checksums, IDs, hierarchy, relationships, positions, and totals.
4. Present a preview and explicit duplicate/conflict choices without touching the visible library.
5. Prepare immutable page/source revisions and regenerated thumbnails.
6. Activate folders, documents, pages, source assets, and rebuilt FTS in one Room transaction.
7. Mark the journal committed, then perform idempotent cleanup.

Cancellation or failure before activation removes only operation-owned staging. Existing documents
remain untouched. Startup recovery removes abandoned pre-commit state or completes post-commit
cleanup. Backup holds a consistent library snapshot so concurrent revision cleanup cannot remove a
streamed asset.

## Golden resources

Synthetic resources under `app/src/test/resources/backup-format-v1/` lock down required fields,
nested folders, a 21-page document, source-PDF metadata, and negative capability cases. The production
archive tests generate tiny deterministic assets from these records; no real document data or large
fixture is committed.
