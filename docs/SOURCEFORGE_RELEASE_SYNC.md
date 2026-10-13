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

3. In SourceForge **Account Settings**, generate a dedicated **Releases API Key**. Treat it as a password. This key is used only over HTTPS after uploaded files have passed remote and public checksum verification.

4. In GitHub, open **lucianRME/rme-pdf-scanner → Settings → Secrets and variables → Actions** and create these repository secrets:

   - `SOURCEFORGE_USERNAME`: the SourceForge account username, currently expected to be `lucianrme`;
   - `SOURCEFORGE_SSH_PRIVATE_KEY`: the complete contents of `rme_sourceforge_actions`, including its `BEGIN` and `END` lines;
   - `SOURCEFORGE_RELEASE_API_KEY`: the SourceForge Releases API key.

   The same setup can be completed with GitHub CLI without printing the private key:

   ```sh
   gh secret set SOURCEFORGE_USERNAME --repo lucianRME/rme-pdf-scanner --body "lucianrme"
   gh secret set SOURCEFORGE_SSH_PRIVATE_KEY --repo lucianRME/rme-pdf-scanner < ./rme_sourceforge_actions
   gh secret set SOURCEFORGE_RELEASE_API_KEY --repo lucianRME/rme-pdf-scanner
   ```

The final command prompts securely for the API key. Never pass it with `--body`, where it could enter shell history. Never commit either key, paste a key into an issue or workflow log, or store a SourceForge password in GitHub.

## Initial v1.7.0 synchronization

After all three secrets are present, run the existing-release path:

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

## Default download policy

Only after SSH upload verification and public SHA-256 verification succeed, the workflow calls SourceForge's [official Release API](https://sourceforge.net/p/forge/documentation/Using%20the%20Release%20API/) over HTTPS. The source archive is set as the default for the documented `windows`, `mac`, `linux`, `bsd`, and `solaris` platform identifiers. Repeating the same PUT is idempotent.

SourceForge documents an `others` identifier but does not document an Android identifier. RME does not assign `others` to the source archive because that could displace the APK for an Android user. The workflow never guesses an undocumented `android` value. When exactly one APK is attached, SourceForge's normal APK detection is retained and verified with an Android User-Agent. With no APK, Android metadata is left unchanged. With multiple APKs, the workflow fails before upload rather than guessing which APK is official.

Release notes and `SHA256SUMS.txt` are never marked as defaults.

After updating metadata, the workflow polls `best_release.json` with bounded retries to allow for SourceForge caching. It verifies the source archive name and SHA-256 for Windows, macOS, and Linux User-Agents, and the APK name and SHA-256 for an Android User-Agent. Authentication failures, permission failures, unexpected metadata, and verification timeouts fail the job without printing the API key.

The API key can be rotated without changing the workflow:

1. Generate a replacement under SourceForge **Account Settings → Releases API Key**.
2. Replace the `SOURCEFORGE_RELEASE_API_KEY` GitHub Actions secret using the prompt-based `gh secret set` command above.
3. Revoke or regenerate the previous key in SourceForge.
4. Manually dispatch a known stable release and confirm all defaults through `best_release.json`.

## Transport security

Transfers use rsync over SSH to:

`SOURCEFORGE_USERNAME@frs.sourceforge.net:/home/frs/project/rme-pdf-scanner/vMAJOR.MINOR.PATCH/`

Strict host-key checking is enabled. The workflow accepts only the ED25519 key whose fingerprint is published in the official SourceForge SSH documentation:

`SHA256:209BDmH3jsRyO9UeGPPgLWPSegKmYCBIya0nR/AWWCY`

If SourceForge rotates that key, the workflow fails closed. Verify the new fingerprint against [SourceForge's SSH host-key documentation](https://sourceforge.net/p/forge/documentation/SSH%20Key%20Fingerprints/) before updating the workflow.

Rotate or revoke either dedicated key immediately if it may have been exposed. Removing any required GitHub secret stops future publication before new files are uploaded.

## Local preparation check

The release can be prepared and validated without SourceForge credentials or publication:

```sh
python3 .github/scripts/sourceforge_release_sync.py \
  --tag v1.7.0 \
  --prepare-only \
  --output /tmp/rme-sourceforge-v1.7.0
```

This downloads the attached APK and validates its GitHub size and SHA-256 digest, but it does not contact SourceForge over SSH.
