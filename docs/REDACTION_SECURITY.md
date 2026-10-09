# Irreversible local redaction security boundary

Milestone 7 treats redaction as destructive derivative creation, not as an annotation. The original
image, PDF, library page, OCR artifact, and correction remain read-only. A redacted result is eligible
for Save or Share only after RME reconstructs a new PDF from sanitized pixels and safe positioned text,
closes it, reopens it, and verifies the resulting objects and rendered pages.

This document describes an internal engineering boundary. It is not a certification or a public claim
that every possible file-recovery technique is impossible.

## Existing-pipeline mapping

| M7 requirement | Existing component | Treatment | Security reason |
| --- | --- | --- | --- |
| Stable page/card ownership | `DocumentSession`, `CardCopySource` | Extend with operation-local stable source identities and immutable source-normalized regions | A layout, page-size, alignment, spacing, side swap, or session rotation cannot retarget a region to screen coordinates. |
| Typed coordinate conversion | M6 card placement geometry | Reuse placement geometry through `RedactionCoordinateMapper`; add explicit source, composition, and final-page spaces | Invalid or ambiguous conversions fail instead of exporting an approximate overlay. |
| Rotation and crop | Existing EXIF-upright source plus session rotation model | Store regions before session rotation; use tested forward/inverse right-angle transforms and an explicit crop remap seam | The same source content stays covered through 0, 90, 180, and 270 degree rotations. A future post-redaction crop must remap or invalidate review. |
| Final image transformation | `FilteredJpegPageWriter` | Extend with a final mutable-pixel sanitizer after EXIF, rotation, scaling, and filtering and before encoding | Redacted pixels replace source pixels in the only image that enters the result. No PDF black overlay is used. |
| Card-copy composition | `CardCopyPdfComposer` | Reuse the one-page M6 compositor with two sanitized JPEG inputs | Avoids an unrelated compositor and never imports original image/PDF objects. |
| Searchable text | `OcrPageResult`, effective OCR selection, `PdfBoxSearchablePdfGenerator` | Bind geometry to the recognized source digest, effective dimensions, and rotation; remove intersecting positioned units; omit the affected page text layer when correction placement, element containment/text consistency, transform, or provenance is unsafe | Hidden OCR, stale geometry, or authoritative correction text must not disclose covered content. |
| General scanned pages | Existing page writer and searchable-PDF generator | Process one page at a time into a sanitized JPEG and rebuilt safe OCR model, then generate a fresh PDF | Bounded reconstruction removes source annotations, attachments, alternate images, and arbitrary content streams. |
| Prepared ownership | M5 generation/cancellation model | Edits advance the operation generation and invalidate/discard any older prepared result | Save and Share cannot select a stale pre-edit derivative. |
| PDF acceptance | Existing PdfBox and `PdfRenderer` dependencies | Dedicated strict verifier with exact on-page image placements, sanitized-JPEG hashes/dimensions, native XObject pixel checks, structural/text/metadata allowlists, digest checks, and rendering | Ordinary PDF readability is not evidence that hidden content was removed, and downsampled rendering cannot prove native pixels are gone. |
| SAF destination | Existing destination writer | Require an attested prepared digest, exact-copy verification, and direct destination PDF verification | A private-cache check alone cannot establish what reached the selected provider. |
| Share | Existing `FileProvider` flow | Expose only the current digest-attested verified PDF from the dedicated verified-output root | Sources and workspaces remain outside provider roots. |
| Fit Upload Limit | M4 bounded reconstruction engine | Accept only a rendered sanitized raster and sanitized/null OCR from a verified M7 derivative | Compression can change pixels but cannot reintroduce source content it never receives. |

## Fail-closed rules

- Export accepts only finite, non-empty, in-bounds regions in their declared coordinate space and for
  the exact stable target identity.
- Pixel bounds use outward floor/ceil conversion plus one deterministic JPEG-block safety margin. The covered
  buffer is overwritten with opaque black before JPEG/PDF encoding.
- A redacted page preserves searchable text only when every retained unit has strict, trustworthy
  geometry in the effective upright image space. Freeform or otherwise unsafe correction/import text
  causes the whole affected page text layer to be omitted.
- Redacted PDFs are newly reconstructed. Source PDF objects, crop tricks, annotations, attachments,
  forms, actions, metadata, optional content, alternate images, and original streams are not copied.
- The verified fresh-writer profile constrains the exact header/EOF envelope, trailer, catalog,
  flat page tree, resource names, decoded page operators, supported compression bytes, blank Type 3
  glyphs, and Unicode maps. Page-content comments, altered header comments, incremental/trailing
  revisions, and payload bytes after the terminal `%%EOF` are rejected even without a test sentinel.
- This is a structural fresh-generation profile, not a claim that every parser-equivalent PDF byte
  sequence is canonical. The export path never imports source PDF syntax or objects into that profile,
  and the attested digest rejects any mutation after verification.
- Every image must match the exact sanitized JPEG hash and native dimensions supplied to the fresh
  compositor, appear once at its allowlisted non-overlapping in-page placement, and contain opaque
  black native pixels throughout each requested redaction region.
- Source digests are checked before and after preparation. Prepared and destination bytes are attested
  separately. A same-length mutation invalidates Save and Share.
- Failure or cancellation removes the private operation workspace and never falls back to exporting
  an original with a visual rectangle.

## Temporary ownership

Source staging, sanitized page JPEGs, PdfBox scratch data, and incomplete PDFs remain in private cache.
Unredacted full-resolution data exists only in bounded bitmap memory while the final transform is being
performed. Encoded page intermediates are already sanitized. Only the closed and verified PDF moves
under the narrow FileProvider output root; cancellation and discard remove owned derivatives. A
process-once startup sweep removes pre-existing M7 workspaces and verified-cache outputs, while an
age-based sweep handles later abandoned files without deleting work owned by a recreated activity.

## Validation boundary

The focused adversarial tests use synthetic sentinel pixels and strings. They inspect extracted text,
decompressed non-image streams and strings, metadata, annotations/attachments, image XObjects, rendered
regions at multiple sizes, source hashes, exact destination bytes, and a forced M4 reconstruction. The
verifier is intentionally stricter than the ordinary target-size input classifier. Raw byte searches are
supplemental because PDF stream compression and encoding can make them incomplete.
