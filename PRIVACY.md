# RME: PDF & Document Scanner Privacy Policy

Effective date: 17 September 2026

RME: PDF & Document Scanner is an Android document scanner published by SynapseWorks. This policy describes the
`org.synapseworks.pageharbor` application, version `1.0.0` and later versions that retain the
same practices. It is written for users and for a future Google Play listing.

## What RME PDF Scanner does with documents

RME PDF Scanner processes scan images, OCR text, and searchable-PDF text layers on the device. It does
not operate a document backend, cloud storage service, account system, advertising service, or its
own analytics or tracking service. RME PDF Scanner does not send document images, OCR text, or generated
PDF content to an RME PDF Scanner server because it has no such server.

Scanned pages, user-selected images, Android-shared images, locally rendered PDF pages, and OCR
results begin as active-session data. External content URIs remain user/provider owned and RME never
deletes them. An unsaved active session is not recovered after process death. PDF import uses a
bounded app-private source copy and temporary rendered page files;
the source copy is removed after preparation and rendered pages are removed on cancellation,
replacement, removal, discard, or session cleanup. Orphaned cache files from process death are
removed when stale.

When the user explicitly chooses **Save to RME**, RME creates a private persistent library copy of
each page and stores its title, timestamps, page order, rotation/filter state, folder assignment,
thumbnail, and OCR indexing status in a local Room database. If the user has explicitly run OCR,
the recognized page text and safe error category are also stored locally so title and OCR search
work after restart. This private library data is not uploaded, shared, or backed up by RME.

A temporary searchable PDF or share copy may be held in the app cache only while it is needed for
the requested save, share, retry, or cleanup operation. RME PDF Scanner removes prepared searchable
PDFs after success, failure, cancellation, or discard; completed share copies are cache data and are
cleaned when stale. RME PDF Scanner disables Android backup and excludes its private data from cloud
backup and device transfer.

## Saving and sharing

RME PDF Scanner uses Android's system file picker for selecting imports and save destinations, and
Android's share sheet for receiving supported user-shared files and sending exports. The user chooses
the source, destination, or receiving app. If the user selects a third-party storage provider or share
target, that provider handles the selected file under its own terms and privacy policy. RME PDF
Scanner does not receive that provider's account credentials and requests no broad storage access.

## Google ML Kit and Google Play services

RME PDF Scanner uses Google ML Kit Document Scanner and bundled ML Kit Text Recognition. Document
scanning and OCR content processing occur on-device. According to Google's ML Kit documentation,
the SDK does not send document images, video, text input, or text-recognition output to Google.

ML Kit may send encrypted technical diagnostics to Google. Google documents these as device and app
information, feature/configuration information, performance and error data, and per-installation
identifiers used for diagnostics, reliability, compatibility, and improvement. This is SDK data
collection outside RME PDF Scanner's own code; it is not advertising and does not include document
content. ML Kit and Google Play services are governed by Google's terms and privacy practices.

The document scanner may obtain scanner resources through Google Play services. Consequently,
RME PDF Scanner does not promise that a device can acquire or update every Google-provided component
without network access, even though the app declares no `INTERNET` permission and does not itself
send document content to a server.

## Permissions and security

RME PDF Scanner declares no `INTERNET`, broad-storage, account, location, contacts, microphone, phone,
or advertising permissions. The Google-provided scanner flow owns its camera interaction; RME PDF Scanner
does not request camera permission directly. Files shared from RME PDF Scanner use a non-exported
FileProvider with temporary read access limited to the exact URI granted by the app. Its configured
roots cover private cache areas required by imports/exports and the private library used to reopen
saved pages; no directory is independently browsable through the provider. The exported launcher Activity accepts only supported image/PDF send
intents and validates every incoming URI before it enters the active document.

## Retention and deletion

Saved library documents remain in app-private storage until the user deletes the document, clears
the app's data, or uninstalls the app. Deleting a saved document permanently removes RME's page copies,
thumbnail, metadata, and indexed OCR text; it never deletes the external files originally imported.
Deleting a folder keeps its documents and moves them out of that folder. This version has no trash or
restore layer. Users separately control files they save or send through Android and can delete those
files from the chosen destination. Android or third-party destination providers may apply their own
retention policies. For ML Kit diagnostic data, consult Google's applicable privacy documentation and
request mechanisms.

## Contact and publication requirement

For source and pre-release questions, use the project's issue tracker:
<https://github.com/lucianRME/rme-pdf-scanner/issues>.

Before any Google Play upload, the release owner must publish this policy at a stable, public,
non-geofenced HTTPS URL and add a monitored privacy contact for SynapseWorks. The same URL must be
entered in Play Console and made reachable from the app or its store listing. This repository file
is the reviewed policy source; it is not itself the final hosted Play URL.

## Changes

If RME PDF Scanner changes its permissions, data handling, dependencies, storage, or sharing behavior,
SynapseWorks will update this policy and its Google Play Data safety declaration before distributing
the changed build.
