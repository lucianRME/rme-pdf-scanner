# Beta Matrix Results

> Historical record for RME: PDF & Document Scanner, formerly PageHarbor. Product names below reflect the investigation or validation at the time.

Status: `v0.8.0-dev` closure evidence on 27 July 2026. This records only observed behavior and
separates deterministic coverage from physical-device claims.

## Executed device matrix

| Target | Configuration | Result |
| --- | --- | --- |
| Samsung SM-S938B | Android 16, 1080×2340, releaseVerification | Five-session manual smoke and 103/103 debug connected tests passed; physical active-work process kill remains unverified |
| API 36 emulator | Android 16/API 36 Google Play ARM64, releaseVerification | Recovered; Home and system-scanner entry passed; 103/103 debug connected tests passed |
| API 31 emulator | Android 12/API 31 Google APIs ARM64, 2 GB RAM/two cores at runtime, releaseVerification | Established; Home passed; 103/103 debug connected tests passed |
| Pixel Tablet emulator | Android 12/API 31 Google APIs ARM64, 2560×1600 at 320 dpi, releaseVerification | Stable; Home/dialogs/large-window manual checks and 103/103 debug connected tests passed; scanner-return flow limited by headless input |
| API 26/27 boundary | No installed or attached target | Blocked |

## Samsung results

- The debug-signed, minified/resource-shrunk `releaseVerification` build installed and showed
  `0.8.0-dev` / code `8` with the `releaseVerification` build label.
- Home launched without a PageHarbor permission prompt.
- ML Kit Document Scanner launched successfully and exposed gallery import without PageHarbor adding
  camera or storage permissions.
- A temporary synthetic, non-sensitive single-page gallery fixture completed scanner review and
  returned a clean Scan Result with one page and all expected actions.
- JPEG export opened the Android SAF destination picker with `Document-1.jpg`. Cancelling it
  returned to Scan Result with the distinct `Page export cancelled.` state and no crash.
- Five consecutive real scanner sessions were run without clearing app data, uninstalling, or
  restarting the device. They covered a normal-PDF save, sharesheet cancellation, three-page JPEG
  export, three-page OCR navigation with page-2 rotation retention, searchable-PDF save, and a
  final three-page OCR/Copy Text flow. Each completed Discard returned to a clean Home; the next
  session showed no old preview, OCR text, selected page, success feedback, or stuck progress.
- OCR completed on one and three non-sensitive temporary fixtures. The three-page result reported
  text on all pages, changed with page navigation, preserved page 2 across portrait/landscape/
  portrait, and Copy Text gave product feedback. No ML Kit or R8 failure occurred.
- Normal PDF saved through DocumentsUI, sharing opened the Android sharesheet with one attachment,
  JPEG export completed for all three pages, and searchable PDF saved successfully. A rendered
  output visually opened; host tooling available in this run could not independently extract its
  embedded text layer, so this run does not add a Unicode-extraction claim.
- A fresh three-page Scan Result survived both a 15-second and a one-minute background/foreground
  interval. A deliberate process kill while normal-PDF DocumentsUI was open relaunched safely to
  Home without scan state or false-success feedback. No crash, provider, or security exception was
  observed. This is expected non-persistent process-loss behavior.
- The debug-signed releaseVerification package is intentionally non-debuggable. Android therefore
  denied `run-as` access to its private cache/files directories, so no app-private inventory was
  taken and no security boundary was bypassed. Deterministic ownership/age cleanup tests remain the
  evidence for those directories.
- The final three-page Session 5 sequence passed on releaseVerification: OCR visited all pages,
  searchable-PDF SAF cancellation retained the Scan Result, retry saved successfully, the Android
  Sharesheet showed one attachment and cancelled safely, Discard returned Home, and a fresh scanner
  launch/cancel showed no stale document state. Copy Text remains covered by the earlier completed
  Samsung session; this final sequence did not retain a separate feedback capture.

The transient gallery fixtures, screenshots, external scanner UI hierarchy dumps, and device copies
were removed immediately after this run.

## Emulator recovery and API compatibility increment

- The host supports Android Emulator acceleration. A recovered Android 16/API 36 Google Play ARM64
  profile and a newly created Android 12/API 31 Google APIs ARM64 profile both cold-booted, reached
  ADB, and remained running. The API 31 profile was run at 2 GB RAM and two cores; it is an
  emulator resource profile, not a physical low-memory-device claim.
- The local minified, debug-signed `releaseVerification` artifact installed and launched on both
  emulators. Both Home screens displayed `v0.8.0-dev (8) · releaseVerification`; package metadata
  reported `minSdk 26`, `targetSdk 36`, and no `INTERNET` permission.
- API 36 opened ML Kit Document Scanner with capture and gallery-import controls. No scanner,
  ML Kit initialization, or PageHarbor crash was observed. Its headless camera surface did not
  settle sufficiently for deterministic coordinate/hierarchy automation, so this is not a claim
  that an emulator gallery import completed.
- The full debug connected suite subsequently passed on both targets with the added interruption
  tests: API 31: 103 total, 103 completed, 0 failures, 0 errors, 0 skipped; API 36: 103 total,
  103 completed, 0 failures, 0 errors, 0 skipped. This includes deterministic OCR-engine, searchable-PDF,
  FileProvider share-intent, lifecycle/session-reset, and 200% font reachability coverage.
- No PageHarbor defect was reproduced. The original API 36 failure was local emulator process
  handling; the API 31 image first landed under the command-line-tools SDK root rather than the
  emulator SDK root, then was installed into the configured SDK root and booted normally.

## Tablet and large-screen increment

- A dedicated Pixel Tablet Android 12/API 31 Google APIs ARM64 profile was created at 2560×1600,
  320 dpi. The initial cold boot used 3 GB RAM, two cores, software graphics, disabled snapshots,
  and no window: `emulator -avd PageHarbor_Tablet_API_31 -port 5558 -no-snapshot -wipe-data
  -no-boot-anim -no-audio -no-window -gpu swiftshader_indirect -memory 3072 -cores 2`.
  The later cold-restart check used the same command without `-wipe-data`. These are local AVD
  instructions, not committed host paths or a release requirement.
- The target reached ADB and `sys.boot_completed=1`, remained connected for 617 seconds before
  app installation, accepted rotation settings, shut down cleanly through ADB, and cold-booted
  back to ADB. The headless profile retained its natural landscape orientation; a reversible
  1600×2560 display-size override provided portrait-equivalent layout evidence, not a claim of
  physical-sensor portrait rotation.
- The debug build installed first, then the minified debug-signed `releaseVerification` build.
  Home showed `v0.8.0-dev (8) · releaseVerification`; About and Privacy opened and dismissed
  with Back; package metadata remained `minSdk 26`, `targetSdk 36`, and contained no INTERNET
  permission or PageHarbor runtime permission prompt.
- Home was manually checked in native 2560×1600, 1600×2560 portrait-equivalent, 1600×1200 medium,
  and 1200×1600 narrow windows. Default and 200% font plus light and dark theme retained the title,
  scan action, Privacy, About, and version identity without clipping or overlap. Background/foreground
  from Home remained coherent. This was not a broad tablet redesign and no product code changed.
- The complete tablet-selected debug connected suite subsequently passed: 103 total, 103 completed,
  0 failures, 0 errors, 0 skipped. It executed the Scan Result large-font and OCR Result 200% font
  reachability tests plus deterministic OCR, searchable-PDF, FileProvider, multipage, lifecycle,
  session-reset, dialog, and live-feedback coverage.
- No PageHarbor defect was reproduced. In this headless tablet environment, a visible and focusable
  scanner action did not dispatch from either coordinate or keyboard activation, so no gallery-import
  return, manual Scan/OCR Result, SAF cancellation, multipage session, or active-operation
  background/rotation result is claimed from the tablet. The deterministic state coverage is not a
  substitute for those remaining manual beta checks.

## Interruption and recovery

- Scanner launch/cancel, SAF cancellation, Scan Result/OCR Result rotation, and ordinary
  background/foreground have manual evidence. Process kill while the normal-PDF picker was open
  has manual evidence of the documented safe Home reset.
- A test-only controllable gate pauses fake OCR after it starts and fake PDF generation after the
  private output is created but before completion. On Samsung, API 31, API 36, and the tablet,
  deterministic instrumentation covered OCR/background completion, OCR discard/recreation stale
  completion rejection, searchable-PDF/background completion with one destination request, and
  searchable-PDF discard cleanup with no destination request.
- Physical OCR/searchable-PDF process kill during active work remains unverified because real work
  still completes too quickly. The normal-PDF-picker process-loss result remains the only physical
  process-kill claim.
- The external scanner, picker, and Samsung system UI were usable for the completed flows. Their
  short operation times, rather than picker availability, prevented a guaranteed in-progress
  OCR/searchable-PDF interruption.
- Existing deterministic lifecycle/coordinator coverage remains the evidence for stale callbacks,
  cancellation, destination failure, prepared-output cleanup, and process-reset defaults. It is not
  substituted for the missing manual matrix.

## Temporary-file inventory

- App-private paths were not inspectable on the non-debuggable releaseVerification artifact; this
  is an Android security boundary, not evidence that the directories are empty.
- Temporary fixture/gallery files, user-selected validation PDFs/JPEGs, rendered PDF checks, UI
  dumps, and diagnostics created for this run were removed after validation. SAF destinations were
  deleted only when they were explicitly created by this run.
- The ownership/age cleanup boundaries for stale `searchable-*.pdf` files and stale share copies are
  covered deterministically. A manual active searchable-PDF/process-kill inventory remains planned.

## Defects and limitations

No PageHarbor defect was found in the executed flow. The original API 36 emulator launch failure
was recovered and is not evidence of API 36 incompatibility. The unexecuted physical lower-memory,
tablet, lower-API, third-party-provider, physical active-operation process kill, app-private cache
inventory, and text-layer extraction checks remain beta gaps.

## Closure classification

| Remaining limit | Classification | Closure rationale |
| --- | --- | --- |
| Physical kill during active OCR/searchable-PDF work | Non-blocking known limitation | Test-only gates deterministically cover invalidation, stale completion rejection, and cleanup; no physical active-work kill is claimed. |
| App-private cache inventory on releaseVerification | Non-blocking known limitation | `run-as` denial on a non-debuggable verification build is expected; deterministic owned-file cleanup tests cover the boundary. |
| Physical lower API/manufacturer coverage | Optional future coverage | API 31/API 36 automation and Samsung Android 16 are evidence, not a physical Android 8–11 or universal-OEM claim. |
| Third-party SAF providers | Blocker before public release | DocumentsUI/local-provider flows passed; cloud, enterprise, and OEM providers remain untested. |
| Latest Samsung searchable-PDF extraction | Non-blocking known limitation | Earlier v0.4 deterministic/Android/desktop validation covered Unicode extraction; the latest Samsung run was visual-only and does not repeat that claim. |
