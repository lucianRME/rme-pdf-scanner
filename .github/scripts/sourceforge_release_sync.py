#!/usr/bin/env python3
"""Synchronize one published stable GitHub release to SourceForge."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


SOURCEFORGE_HOST = "frs.sourceforge.net"
SOURCEFORGE_ED25519_FINGERPRINT = "SHA256:209BDmH3jsRyO9UeGPPgLWPSegKmYCBIya0nR/AWWCY"
STABLE_TAG = re.compile(r"v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)")
SAFE_REMOTE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+@= -]*")


class SyncError(RuntimeError):
    """A safe, actionable synchronization failure."""


def run(
    command: list[str],
    *,
    cwd: Path | None = None,
    input_text: str | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=cwd,
        input=input_text,
        text=True,
        capture_output=True,
        check=check,
    )


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def request_bytes(
    url: str,
    token: str | None = None,
    accept: str = "application/vnd.github+json",
) -> bytes:
    headers = {
        "Accept": accept,
        "User-Agent": "rme-sourceforge-release-sync",
    }
    if url.startswith("https://api.github.com/"):
        headers["X-GitHub-Api-Version"] = "2022-11-28"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        with urlopen(Request(url, headers=headers), timeout=60) as response:
            return response.read()
    except (HTTPError, URLError) as error:
        raise SyncError(f"Request failed for {url}: {error}") from error


def load_release(repository: str, tag: str, token: str | None) -> dict[str, Any]:
    encoded_tag = quote(tag, safe="")
    url = f"https://api.github.com/repos/{repository}/releases/tags/{encoded_tag}"
    release = json.loads(request_bytes(url, token))
    if release.get("draft"):
        raise SyncError(f"{tag} is a draft GitHub release")
    if release.get("prerelease"):
        raise SyncError(f"{tag} is a prerelease")
    if not release.get("published_at"):
        raise SyncError(f"{tag} is not a published GitHub release")
    if release.get("tag_name") != tag:
        raise SyncError("GitHub release tag does not match the requested tag")
    return release


def validate_tag(tag: str) -> None:
    if not STABLE_TAG.fullmatch(tag):
        raise SyncError(
            f"Refusing non-stable tag {tag!r}; expected an exact vMAJOR.MINOR.PATCH tag"
        )


def validate_remote_name(name: str) -> None:
    if not SAFE_REMOTE_NAME.fullmatch(name) or name in {".", ".."}:
        raise SyncError(f"Unsafe SourceForge filename: {name!r}")


def prepare_release(
    repo_root: Path,
    output_dir: Path,
    repository: str,
    tag: str,
    token: str | None,
) -> tuple[dict[str, Any], list[Path]]:
    validate_tag(tag)
    release = load_release(repository, tag, token)

    tag_ref = f"refs/tags/{tag}"
    tag_check = run(["git", "rev-parse", "--verify", f"{tag_ref}^{{commit}}"], cwd=repo_root)
    release_commit = tag_check.stdout.strip()
    if not re.fullmatch(r"[0-9a-f]{40}", release_commit):
        raise SyncError(f"Could not resolve {tag} to a commit")

    output_dir.mkdir(parents=True, exist_ok=True)
    archive_name = f"rme-pdf-scanner-{tag}-source.tar.gz"
    archive_path = output_dir / archive_name
    run(
        [
            "git",
            "archive",
            "--format=tar.gz",
            f"--prefix=rme-pdf-scanner-{tag}/",
            f"--output={archive_path}",
            tag_ref,
        ],
        cwd=repo_root,
    )

    notes_path = output_dir / "README.md"
    title = release.get("name") or tag
    body = (release.get("body") or "").rstrip()
    notes = (
        f"# {title}\n\n"
        f"GitHub release: {release['html_url']}\n\n"
        f"Source tag: `{tag}` (`{release_commit}`)\n\n"
        f"Published: {release['published_at']}\n"
    )
    if body:
        notes += f"\n{body}\n"
    notes_path.write_text(notes, encoding="utf-8")

    prepared = [archive_path, notes_path]
    apk_assets = [
        asset for asset in release.get("assets", []) if asset.get("name", "").endswith(".apk")
    ]
    for asset in apk_assets:
        name = asset["name"]
        validate_remote_name(name)
        destination = output_dir / name
        # Release downloads are public. Do not forward GITHUB_TOKEN through the
        # release-download redirect to GitHub's object-storage host.
        destination.write_bytes(
            request_bytes(asset["browser_download_url"], accept="application/octet-stream")
        )
        expected_size = asset.get("size")
        if expected_size is not None and destination.stat().st_size != expected_size:
            raise SyncError(f"Downloaded APK size mismatch for {name}")
        digest = asset.get("digest")
        if digest and digest.startswith("sha256:"):
            expected_digest = digest.removeprefix("sha256:")
            if sha256(destination) != expected_digest:
                raise SyncError(f"Downloaded APK SHA-256 mismatch for {name}")
        prepared.append(destination)

    checksum_path = output_dir / "SHA256SUMS.txt"
    checksum_lines = [f"{sha256(path)}  {path.name}" for path in sorted(prepared)]
    checksum_path.write_text("\n".join(checksum_lines) + "\n", encoding="utf-8")
    prepared.append(checksum_path)

    for path in prepared:
        validate_remote_name(path.name)

    print(f"Validated published stable release {tag} at {release_commit}")
    if apk_assets:
        print(f"Prepared {len(apk_assets)} attached APK file(s)")
    else:
        print("No APK is attached to this GitHub release; continuing without one")
    for path in prepared:
        print(f"Prepared {path.name}: sha256:{sha256(path)}")
    return release, prepared


def sourceforge_ssh(temp_dir: Path, private_key: str) -> tuple[Path, Path, str]:
    key_path = temp_dir / "sourceforge_ed25519"
    key_fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(key_fd, "w", encoding="utf-8") as key_file:
        key_file.write(private_key.replace("\r\n", "\n").rstrip("\n") + "\n")
    key_path.chmod(stat.S_IRUSR | stat.S_IWUSR)

    keyscan = run(["ssh-keyscan", "-T", "15", "-t", "ed25519", SOURCEFORGE_HOST])
    if not keyscan.stdout.strip():
        raise SyncError("SourceForge did not return an ED25519 host key")
    fingerprint = run(
        ["ssh-keygen", "-E", "sha256", "-lf", "-"], input_text=keyscan.stdout
    ).stdout
    if SOURCEFORGE_ED25519_FINGERPRINT not in fingerprint:
        raise SyncError("SourceForge ED25519 host key fingerprint did not match the pinned value")

    known_hosts = temp_dir / "known_hosts"
    known_hosts.write_text(keyscan.stdout, encoding="utf-8")
    ssh_command = (
        f"ssh -i {key_path} -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes "
        f"-o UserKnownHostsFile={known_hosts}"
    )
    return key_path, known_hosts, ssh_command


def sftp_batch(
    username: str,
    key_path: Path,
    known_hosts: Path,
    commands: str,
) -> subprocess.CompletedProcess[str]:
    return run(
        [
            "sftp",
            "-q",
            "-b",
            "-",
            "-i",
            str(key_path),
            "-o",
            "IdentitiesOnly=yes",
            "-o",
            "StrictHostKeyChecking=yes",
            "-o",
            f"UserKnownHostsFile={known_hosts}",
            f"{username}@{SOURCEFORGE_HOST}",
        ],
        input_text=commands,
    )


def copy_remote_directory(
    username: str,
    ssh_command: str,
    remote_dir: str,
    destination: Path,
) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    run(
        [
            "rsync",
            "--archive",
            "-e",
            ssh_command,
            f"{username}@{SOURCEFORGE_HOST}:{remote_dir}/",
            f"{destination}/",
        ]
    )


def verify_existing(prepared: list[Path], remote_copy: Path) -> list[Path]:
    missing: list[Path] = []
    for local in prepared:
        remote = remote_copy / local.name
        if not remote.exists():
            missing.append(local)
            continue
        if not remote.is_file() or sha256(local) != sha256(remote):
            raise SyncError(
                f"Refusing to overwrite SourceForge file with different content: {local.name}"
            )
        print(f"Already synchronized and identical: {local.name}")
    return missing


def sync_sourceforge(tag: str, prepared: list[Path]) -> list[str]:
    username = os.environ.get("SOURCEFORGE_USERNAME", "").strip()
    private_key = os.environ.get("SOURCEFORGE_SSH_PRIVATE_KEY", "")
    project = os.environ.get("SOURCEFORGE_PROJECT", "rme-pdf-scanner").strip()
    if not username or not private_key:
        raise SyncError(
            "SourceForge authorization is not configured. Add GitHub Actions secrets "
            "SOURCEFORGE_USERNAME and SOURCEFORGE_SSH_PRIVATE_KEY."
        )
    if not re.fullmatch(r"[A-Za-z0-9._-]+", username):
        raise SyncError("SOURCEFORGE_USERNAME contains unsupported characters")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", project):
        raise SyncError("SOURCEFORGE_PROJECT contains unsupported characters")

    remote_parent = f"/home/frs/project/{project}"
    remote_dir = f"{remote_parent}/{tag}"
    with tempfile.TemporaryDirectory(prefix="rme-sourceforge-ssh-") as temporary:
        temp_dir = Path(temporary)
        key_path, known_hosts, ssh_command = sourceforge_ssh(temp_dir, private_key)

        listing = sftp_batch(username, key_path, known_hosts, f"ls -1 {remote_parent}\n")
        existing_names = {Path(line.strip()).name for line in listing.stdout.splitlines() if line.strip()}
        if tag not in existing_names:
            sftp_batch(username, key_path, known_hosts, f"mkdir {remote_dir}\n")

        remote_before = temp_dir / "remote-before"
        copy_remote_directory(username, ssh_command, remote_dir, remote_before)
        missing = verify_existing(prepared, remote_before)

        for local in missing:
            run(
                [
                    "rsync",
                    "--archive",
                    "--ignore-existing",
                    "-e",
                    ssh_command,
                    str(local),
                    f"{username}@{SOURCEFORGE_HOST}:{remote_dir}/",
                ]
            )
            print(f"Uploaded missing file: {local.name}")

        remote_after = temp_dir / "remote-after"
        copy_remote_directory(username, ssh_command, remote_dir, remote_after)
        remaining = verify_existing(prepared, remote_after)
        if remaining:
            raise SyncError(
                "SourceForge verification did not find: "
                + ", ".join(path.name for path in remaining)
            )

    encoded_tag = quote(tag, safe="")
    return [
        "https://sourceforge.net/projects/"
        f"{quote(project, safe='')}/files/{encoded_tag}/{quote(path.name, safe='')}/download"
        for path in prepared
    ]


def verify_public_downloads(urls: list[str], prepared: list[Path]) -> None:
    by_name = {path.name: path for path in prepared}
    with tempfile.TemporaryDirectory(prefix="rme-sourceforge-public-") as temporary:
        destination_dir = Path(temporary)
        for url in urls:
            name = url.split("/")[-2]
            from urllib.parse import unquote

            name = unquote(name)
            destination = destination_dir / name
            last_error: Exception | None = None
            for attempt in range(12):
                try:
                    destination.write_bytes(
                        request_bytes(url, accept="application/octet-stream")
                    )
                    if sha256(destination) == sha256(by_name[name]):
                        last_error = None
                        break
                    last_error = SyncError(f"Public checksum mismatch for {name}")
                except Exception as error:  # retry SourceForge publication propagation
                    last_error = error
                if attempt < 11:
                    time.sleep(10)
            if last_error:
                raise SyncError(f"Public download verification failed for {name}: {last_error}")
            print(f"Verified public download: {url}")


def write_summary(tag: str, urls: list[str]) -> None:
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary:
        return
    with Path(summary).open("a", encoding="utf-8") as output:
        output.write(f"## SourceForge synchronization: {tag}\n\n")
        for url in urls:
            output.write(f"- {url}\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo_root = Path(__file__).resolve().parents[2]
    repository = os.environ.get("GITHUB_REPOSITORY", "lucianRME/rme-pdf-scanner")
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")

    if args.output:
        output_dir = args.output.resolve()
        output_dir.mkdir(parents=True, exist_ok=True)
        release, prepared = prepare_release(
            repo_root, output_dir, repository, args.tag, token
        )
        if args.prepare_only:
            return 0
        urls = sync_sourceforge(args.tag, prepared)
        verify_public_downloads(urls, prepared)
        write_summary(args.tag, urls)
        print(f"Synchronized {release['html_url']} to SourceForge")
        return 0

    with tempfile.TemporaryDirectory(prefix="rme-sourceforge-release-") as temporary:
        output_dir = Path(temporary)
        release, prepared = prepare_release(
            repo_root, output_dir, repository, args.tag, token
        )
        if args.prepare_only:
            return 0
        urls = sync_sourceforge(args.tag, prepared)
        verify_public_downloads(urls, prepared)
        write_summary(args.tag, urls)
        print(f"Synchronized {release['html_url']} to SourceForge")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (SyncError, subprocess.CalledProcessError) as error:
        if isinstance(error, subprocess.CalledProcessError):
            detail = (error.stderr or error.stdout or str(error)).strip()
            print(f"ERROR: command failed: {detail}", file=sys.stderr)
        else:
            print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
