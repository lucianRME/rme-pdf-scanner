# Offline language installation

2026-11-26 implementation note. Translation pack delivery is an explicit optional
build profile. The default app does not acquire this dependency or embed the
roughly 1 GiB of model archives.

## User flow and data boundary

More → Offline languages lists languages first. A language tap requests its
download size from Google Play; the user confirms installation before fetching.
Google Play handles transport. RME receives the delivered archive and passes it
through its existing SHA-256 pin, metadata, bounded extraction, free-space,
cancellation and atomic-publication checks. The result lives in no-backup private
storage and existing translation code consumes it offline. No source document,
OCR or translation draft enters the delivery interface.

French, German, Italian, Spanish, Japanese, Korean, Chinese, Hindi, Marathi and
Nepali share one multilingual pack, so installing any one makes all of these
available. Romanian remains English → Romanian; Irish is bidirectional with
English. The UI discloses the shared download rather than pretending these are
small independent models. Removal of the shared pack removes all its languages.

Translation holds its existing model lease; removal cannot delete a pack while
the translation worker uses it. Close cancels the delivery request (best effort
under the Play API), and app backgrounding cancels local import as before. A
user-initiated Play download can continue while the app is temporarily backgrounded;
installation waits for an unlocked foreground Activity. No automatic download is
started by merely opening the language list. The app releases the Play archive
after a verified private installation.

Builds lacking the translation runtime or the delivery profile show concise
availability. Existing local language ZIP imports and text-resource ZIPs remain
under Advanced. Unknown ZIPs are still rejected. Importing a model cannot add a
missing native engine.

## Explicit build inputs

Enable `-PrmeTranslationPlayDelivery=true` together with the already qualified
`-PrmeModelRuntimeRoot=/path/to/runtime-complete`. Supply these three properties;
each archive is verified at build preparation and again during installation:

| Property | On-demand module / archive | SHA-256 |
| --- | --- | --- |
| `rmeRomanianPackArchive` | `translation_ro` / `opus-en-ro-v1.zip` | `9afe2f31534370774c0ee487ba617fe81de7a81d8599a93cb9d8ab36477c865f` |
| `rmeMultilingualPackArchive` | `translation_multi` / `rme-multilingual-v1.zip` | `d6768346c72f7d2ad9402257b6379fe0639f0a4e9d5f60ce79c6ff3cb4fe1b6f` |
| `rmeIrishPackArchive` | `translation_ga` / `rme-irish-v1.zip` | `f8de4aaf0170fab68e22264a0cb968e36e833047f22c7176fa9dbe83634ee4a3` |

Each asset module materializes only its pinned archive into an ignored
`src/main/assets` directory for the asset-pack plugin. No archive is checked in.
The optional app source set uses
`com.google.android.play:asset-delivery:2.3.0` and its Java task API. The default
profile does not resolve that library or include these modules. The main manifest
already explicitly removes `INTERNET` and `ACCESS_NETWORK_STATE`.

## Reproducible local delivery check

1. Build an isolated validation App Bundle with the properties above and the
   exact approved runtime. Inspect the bundle: three asset-pack manifests must
   declare `on-demand`, and each module's archive must have the pin above. The
   base must not contain the model archives.
2. Use the retained bundletool wrapper (or official bundletool) to run
   `build-apks --bundle=<bundle.aab> --output=<local.apks> --local-testing`, then
   `install-apks --apks=<local.apks> --device-id=<owned-device>` using an isolated
   validation package. Follow the project's validation signing policy; do not
   access production signing material.
3. Inspect the effective installed package permissions: no `INTERNET` and no
   `ACCESS_NETWORK_STATE`. Open Offline languages, choose Romanian, confirm the
   size and install. Observe progress → verification → Ready offline without a
   document picker. Repeat at least one shared multilingual language and Irish.
4. Disable connectivity after installation, restart the app, and exercise the
   corresponding real translation directions. Test cancellation, retry, active
   worker removal refusal, post-close removal, and reinstall. Confirm Advanced
   still permits the existing local-import recovery flow in non-Play builds.

The [official local-testing guide](https://developer.android.com/guide/playcore/asset-delivery/test)
states that bundletool fetches local packs from external storage and does not
exercise network errors or Wi-Fi waiting. Passing this check establishes local
delivery and installation only. Production delivery requires an authorized Play
upload and a Play-installed build; no upload, signing for publication, or public
download availability is claimed here. A standalone APK cannot supply these
on-demand packs.

## Why this route

The [Android DownloadManager reference](https://developer.android.com/reference/android/app/DownloadManager)
requires the calling app to hold `INTERNET`. ML Kit's
[installation-path table](https://developers.google.com/ml-kit/tips/installation-paths)
lists translation as dynamically downloaded, with no Play-services-managed
unbundled option. It is not a verified substitute for RME's no-network-permission
contract or its existing pinned ONNX engines. No such backend was added.

The public repository release API was checked on 2026-11-26: the eight listed
releases, through v1.7.2, had APK/AAB/checksum assets and no pinned language-pack
archives. No pack URL was invented. The exact retained local archives made the
Play asset-pack and bundletool route possible without publication.

The [official integration guide](https://developer.android.com/guide/playcore/asset-delivery/integrate-java)
documents on-demand modules, size queries, progress, user confirmation, file
locations, cancellation and removal. SDK redistribution follows the
[Play Core SDK Terms](https://developer.android.com/guide/playcore#play-core-software-development-kit-terms-of-service)
and referenced API terms. See `THIRD_PARTY_NOTICES.md`; the optional SDK is not
covered by RME's Apache-2.0 license. Exact model notices and licenses remain
unchanged within their pinned source material.

## Validation status

The optimized 1.8.0 (23) Samsung review build passed local delivery checks. Its App Bundle SHA-256 is `1833596191dec1b14cc554399bd37c3ea34e95e096b06463d7420202f860e4ab`. Each of the three optional module archives matched its pinned SHA-256. The base included the Play delivery adapter and requested neither `INTERNET` nor `ACCESS_NETWORK_STATE`.

On a Galaxy S25 Ultra, choosing Romanian, Irish and French installed all three packs without a file picker through Play Asset Delivery's local-testing mode. All twelve language choices reported **Ready offline**. Cancelling the shared removal confirmation kept the ten languages installed. After the final review update, the packs remained available, removing and downloading the test-installed Romanian pack worked again, and both frozen English-to-Romanian translations passed with the device network disabled. These checks establish pack installation and Romanian inference on the review build. A production Play Store download and fresh translation-quality checks for the other directions remain untested in this pass.
