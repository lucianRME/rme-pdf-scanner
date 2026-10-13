# SourceForge release synchronization

The `Sync stable release to SourceForge` GitHub Actions workflow mirrors official stable GitHub releases to the `rme-pdf-scanner` SourceForge File Release System project.

## Scope

The workflow runs automatically only for the GitHub `release.published` event. It then independently verifies that the requested release:

- exists in `lucianRME/rme-pdf-scanner`;
- is published, not a draft, and not a prerelease;
- uses an exact `vMAJOR.MINOR.PATCH` tag; and
- resolves to a local Git tag before the source archive is created.

Commits, branches, development tags, draft releases, prereleases, APKs that are not attached to the verified release, and AAB files are never synchronized.

Each SourceForge version folder contains:

- `rme-pdf-scanner-vMAJOR.MINOR.PATCH-source.tar.gz`, generated with `git archive` from the exact release tag;
- every APK attached to that GitHub release, if any;
- `README.md`, containing the GitHub release notes and source provenance; and
- `SHA256SUMS.txt`, covering the files prepared for SourceForge.

## One-time authorization

The workflow intentionally cannot publish until a SourceForge project member authorizes a dedicated SSH key.

1. Generate a dedicated ED25519 key pair on a trusted computer. Do not reuse a personal SSH key.

   ```sh
   ssh-keygen -t ed25519 -C "rme-pdf-scanner GitHub Actions" -f ./rme_sourceforge_actions
   ```

   Automated GitHub Actions access requires a key without an interactive passphrase. Protect the generated private key carefully and delete the local copy after it is stored securely.

2. Sign in to SourceForge as the account that manages `rme-pdf-scanner`. Add the contents of `rme_sourceforge_actions.pub` under **Account Settings → SSH Settings**. Confirm that the account is a project member with **release technician** or administrator permission for `rme-pdf-scanner`.

3. In GitHub, open **lucianRME/rme-pdf-scanner → Settings → Secrets and variables → Actions** and create these repository secrets:

   - `SOURCEFORGE_USERNAME`: the SourceForge account username, currently expected to be `lucianrme`;
   - `SOURCEFORGE_SSH_PRIVATE_KEY`: the complete contents of `rme_sourceforge_actions`, including its `BEGIN` and `END` lines.

   The same setup can be completed with GitHub CLI without printing the private key:

   ```sh
   gh secret set SOURCEFORGE_USERNAME --repo lucianRME/rme-pdf-scanner --body "lucianrme"
   gh secret set SOURCEFORGE_SSH_PRIVATE_KEY --repo lucianRME/rme-pdf-scanner < ./rme_sourceforge_actions
   ```

Never commit either key, paste the private key into an issue or workflow log, or store a SourceForge password in GitHub.

## Initial v1.7.0 synchronization

After both secrets are present, run the existing-release path:

```sh
gh workflow run sourceforge-release-sync.yml \
  --repo lucianRME/rme-pdf-scanner \
  --ref main \
  -f tag=v1.7.0
```

Follow the run without exposing secrets:

```sh
gh run list --repo lucianRME/rme-pdf-scanner --workflow sourceforge-release-sync.yml --limit 1
gh run watch --repo lucianRME/rme-pdf-scanner
```

The successful run summary reports each public SourceForge download URL. The v1.7.0 folder will be available at:

`https://sourceforge.net/projects/rme-pdf-scanner/files/v1.7.0/`

Artifact links use SourceForge's public download host, for example:

`https://downloads.sourceforge.net/project/rme-pdf-scanner/v1.7.0/rme-pdf-scanner-v1.7.0.apk`

## Idempotency and overwrite protection

Before uploading, the workflow authenticates over SSH, lists the existing project folders, downloads the complete target version folder with rsync, and compares SHA-256 hashes locally.

- An identical existing file is retained and skipped.
- A missing file is uploaded with rsync `--ignore-existing`.
- An existing filename with different bytes stops the workflow before upload.
- Unrelated existing files are preserved.
- The version folder is downloaded again after upload and every expected SHA-256 is verified.
- Every public download is then downloaded over HTTPS and compared with the prepared file.

This makes manual reruns and duplicate `release.published` deliveries safe. The workflow uses a repository-wide concurrency group so two SourceForge synchronizations cannot run concurrently.

## Transport security

Transfers use rsync over SSH to:

`SOURCEFORGE_USERNAME@frs.sourceforge.net:/home/frs/project/rme-pdf-scanner/vMAJOR.MINOR.PATCH/`

Strict host-key checking is enabled. The workflow accepts only the ED25519 key whose fingerprint is published in the official SourceForge SSH documentation:

`SHA256:209BDmH3jsRyO9UeGPPgLWPSegKmYCBIya0nR/AWWCY`

If SourceForge rotates that key, the workflow fails closed. Verify the new fingerprint against [SourceForge's SSH host-key documentation](https://sourceforge.net/p/forge/documentation/SSH%20Key%20Fingerprints/) before updating the workflow.

Rotate or revoke the dedicated key immediately if it may have been exposed. Removing either GitHub secret also stops future publication.

## Local preparation check

The release can be prepared and validated without SourceForge credentials or publication:

```sh
python3 .github/scripts/sourceforge_release_sync.py \
  --tag v1.7.0 \
  --prepare-only \
  --output /tmp/rme-sourceforge-v1.7.0
```

This downloads the attached APK and validates its GitHub size and SHA-256 digest, but it does not contact SourceForge over SSH.
