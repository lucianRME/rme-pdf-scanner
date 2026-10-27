# RME: Private PDF Scanner branding alignment

Audit date: 27 October 2026. Repository baseline: `152f621`.

The canonical product name is **RME: Private PDF Scanner**. **RME PDF Scanner** remains
an accepted compact name. The published stable release remains **v1.7.1 / code 20**;
repository branding changes do not publish an Android update.

## Occurrence classification

The audit covered tracked source, tests, configuration, maintained and historical documentation,
SVG text, asset names, raster assets, and URL-encoded metadata. Generated output, Git internals,
private local configuration, and signing material are excluded from branding replacement.

| Class | Treatment and locations |
| --- | --- |
| Current public identity | Update README heading, Obtainium display name, AGENTS product/status/scope, PRIVACY title/introduction, current trademark name, BRANDING, RELEASE_SIGNING, APP_LOCK_SECURITY, DECISIONS introduction, Play asset README, and SourceForge README source. Android resources and branding assertions belong to the separate release-preparation PR. |
| Historical references | Preserve released CHANGELOG entries, `docs/releases/v1.2.0*`, `BRANDING_MIGRATION.md`, the explicitly historical scanner/OCR/searchable-PDF/smart-output spike records, `BETA_MATRIX_RESULTS.md`, `PERFORMANCE_AND_DELIVERY.md`, recorded validation evidence, existing ADR bodies, and versioned 1.1.0/1.2.0 screenshots and their manifest. README naming history and the former name in TRADEMARKS are explicit. |
| Technical compatibility | Preserve `org.synapseworks.pageharbor`, namespace/packages/classes, FileProvider authority, Room schemas/database names, preferences, backup format, private storage paths, themes/resources, AVD/test identifiers, `PAGEHARBOR_RELEASE_*`, signing aliases/configuration, repository URLs, and existing asset filenames. |
| Compact product name | Retain RME PDF Scanner in constrained UI, prose, badges, notices, Gradle project name, SVG wordmarks and accessible asset descriptions; RME remains appropriate in established action labels. |

## README and Obtainium

The README still describes released v1.7.1 functionality, explicitly names that stable release,
and explains its installed label still uses the former full name. Primary links retain the
same Play application, website and GitHub Releases destination; GitBook is now included.
The Obtainium JSON was decoded before and after: **only `name` changes**. Application ID,
source URL, author, serialized additional settings, and `includePrereleases: false` are unchanged.
The warning about analytics/advertising on the third-party Obtainium redirect remains intact
and refers to that external redirect, not RME.

## Repository metadata and graphics

Live GitHub inspection found:

- Description: “Privacy-first Android document scanner with multilingual on-device OCR,
  full-library search and searchable PDFs.” Keep it.
- Homepage: `https://synapseworks.org/rme-pdf-scanner/`. Keep it.
- Topics already cover Android, OCR, searchable PDF, local processing and privacy. No change needed.
- README stars/latest-release badges use the unchanged repository and stable-release endpoint.
- The live social image is GitHub's generated repository card with repository name and description;
  no obsolete full product title is visible.
- `assets/github/pageharbor-github-social-preview.svg`, horizontal lockup and feature-graphic
  SVGs display **RME PDF Scanner**, the accepted short name. Their legacy filenames are retained.
- Visual inspection found **PageHarbor** in `assets/play-store/feature-graphic-1024x500.png`,
  despite the updated SVG source. This obsolete raster export is flagged in the asset README;
  it must not be reused for a current listing. No replacement artwork was generated.
- Versioned 1.1.0 and 1.2.0 screenshots intentionally retain their historical names. They are
  documented as historical assets and are not runtime resources or current marketing screenshots.

No artwork, repository name, live listing or remote metadata was changed. Updating the
SourceForge README source does not upload it or run synchronization.

## Privacy, legal wording and release boundary

PRIVACY changes only the current name; its effective date and all data-handling statements remain
unchanged. TRADEMARKS identifies the new name and retains the former name; Apache-2.0 rights and
all other wording remain unchanged. No substantive legal-policy issue was identified by this
name-only review; this does not establish trademark registration or legal clearance.

Release-signing guidance now reflects the existing explicit production-signing opt-in and
isolated instrumentation target. No signing behavior, key, alias, permission, dependency,
application identity or stored-data format changes in this documentation PR.

The separate v1.7.2 preparation must include Android validation and owner review. Neither PR
authorizes tags, release publication, artifact uploads, SourceForge sync or Play rollout.
