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
from urllib.parse import quote, unquote, urlencode, urlparse
from urllib.request import Request, urlopen


SOURCEFORGE_HOST = "frs.sourceforge.net"
SOURCEFORGE_ED25519_FINGERPRINT = "SHA256:209BDmH3jsRyO9UeGPPgLWPSegKmYCBIya0nR/AWWCY"
STABLE_TAG = re.compile(r"v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)")
SAFE_REMOTE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+@= -]*")
SOURCEFORGE_RELEASE_API_ATTEMPTS = 6
SOURCEFORGE_RELEASE_API_DELAY_SECONDS = 10
SOURCEFORGE_DEFAULT_VERIFY_ATTEMPTS = 12
SOURCEFORGE_DEFAULT_VERIFY_DELAY_SECONDS = 10
# These are the explicit platform identifiers documented by SourceForge that do
# not overlap its Android user-agent detection. The API has no Android-specific
# identifier; "others" is intentionally excluded so an APK is never displaced
# by source code for Android users.
SOURCE_ARCHIVE_DEFAULT_PLATFORMS = ("windows", "mac", "linux", "bsd", "solaris")
BEST_RELEASE_USER_AGENTS = {
    "Android": "rme-sourceforge-release-sync/1.0 (Linux; Android 16)",
    "Windows": "rme-sourceforge-release-sync/1.0 (Windows NT 10.0; Win64; x64)",
    "macOS": "rme-sourceforge-release-sync/1.0 (Macintosh; Intel Mac OS X 10_15_7)",
    "Linux": "rme-sourceforge-release-sync/1.0 (X11; Linux x86_64)",
}


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
    *,
    method: str | None = None,
    data: bytes | None = None,
    content_type: str | None = None,
    user_agent: str = "rme-sourceforge-release-sync",
) -> bytes:
    headers = {
        "Accept": accept,
        "User-Agent": user_agent,
    }
    if content_type:
        headers["Content-Type"] = content_type
    if url.startswith("https://api.github.com/"):
        headers["X-GitHub-Api-Version"] = "2022-11-28"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        request = Request(url, data=data, headers=headers, method=method)
        with urlopen(request, timeout=60) as response:
            return response.read()
    except (HTTPError, URLError) as error:
        raise SyncError(f"Request failed for {url}: {error}") from error


def request_json(
    url: str,
    *,
    method: str | None = None,
    data: bytes | None = None,
    content_type: str | None = None,
    user_agent: str = "rme-sourceforge-release-sync",
) -> dict[str, Any]:
    raw = request_bytes(
        url,
        accept="application/json",
        method=method,
        data=data,
        content_type=content_type,
        user_agent=user_agent,
    )
    try:
        payload = json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise SyncError(f"Invalid JSON response from {url}") from error
    if not isinstance(payload, dict):
        raise SyncError(f"Unexpected JSON response from {url}")
    return payload


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
        "https://downloads.sourceforge.net/project/"
        f"{quote(project, safe='')}/{encoded_tag}/{quote(path.name, safe='')}"
        for path in prepared
    ]


def verify_public_downloads(urls: list[str], prepared: list[Path]) -> None:
    by_name = {path.name: path for path in prepared}
    with tempfile.TemporaryDirectory(prefix="rme-sourceforge-public-") as temporary:
        destination_dir = Path(temporary)
        for url in urls:
            name = unquote(Path(urlparse(url).path).name)
            if name not in by_name:
                raise SyncError(f"Unexpected public download filename: {name!r}")
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


def select_default_downloads(tag: str, prepared: list[Path]) -> tuple[Path, Path | None]:
    source_name = f"rme-pdf-scanner-{tag}-source.tar.gz"
    source_matches = [path for path in prepared if path.name == source_name]
    if len(source_matches) != 1:
        raise SyncError(f"Expected exactly one source archive named {source_name}")

    apk_matches = [path for path in prepared if path.suffix.lower() == ".apk"]
    if len(apk_matches) > 1:
        raise SyncError(
            "More than one APK is attached; refusing to guess which APK is the "
            "official Android default"
        )
    return source_matches[0], apk_matches[0] if apk_matches else None


def require_release_api_key() -> str:
    api_key = os.environ.get("SOURCEFORGE_RELEASE_API_KEY", "").strip()
    if not api_key:
        raise SyncError(
            "SourceForge Release API authorization is not configured. Add the GitHub "
            "Actions secret SOURCEFORGE_RELEASE_API_KEY."
        )
    if any(character.isspace() for character in api_key):
        raise SyncError("SOURCEFORGE_RELEASE_API_KEY has an invalid format")
    return api_key


def sourceforge_file_api_url(project: str, tag: str, name: str) -> str:
    return (
        "https://sourceforge.net/projects/"
        f"{quote(project, safe='')}/files/{quote(tag, safe='')}/{quote(name, safe='')}"
    )


def parse_api_defaults(payload: dict[str, Any]) -> set[str]:
    result = payload.get("result")
    if not isinstance(result, dict):
        raise SyncError("SourceForge Release API returned no result")
    x_sf = result.get("x_sf")
    if not isinstance(x_sf, dict):
        raise SyncError("SourceForge Release API returned no default metadata")
    defaults = x_sf.get("default", "")
    if isinstance(defaults, str):
        return {item for item in defaults.split(",") if item}
    if isinstance(defaults, list) and all(isinstance(item, str) for item in defaults):
        return set(defaults)
    raise SyncError("SourceForge Release API returned invalid default metadata")


def update_sourceforge_defaults(
    project: str,
    tag: str,
    source_archive: Path,
    api_key: str,
    *,
    attempts: int = SOURCEFORGE_RELEASE_API_ATTEMPTS,
    delay_seconds: int = SOURCEFORGE_RELEASE_API_DELAY_SECONDS,
) -> None:
    url = sourceforge_file_api_url(project, tag, source_archive.name)
    expected = set(SOURCE_ARCHIVE_DEFAULT_PLATFORMS)
    form_fields = [("default", platform) for platform in SOURCE_ARCHIVE_DEFAULT_PLATFORMS]
    form_fields.append(("api_key", api_key))
    body = urlencode(form_fields).encode("utf-8")
    last_error: Exception | None = None

    for attempt in range(attempts):
        try:
            payload = request_json(
                url,
                method="PUT",
                data=body,
                content_type="application/x-www-form-urlencoded",
            )
            if payload.get("error"):
                raise SyncError(
                    "SourceForge Release API rejected the default-download update; "
                    "check the API key and project permissions"
                )
            actual = parse_api_defaults(payload)
            if actual == expected:
                print(
                    "Confirmed SourceForge source defaults: "
                    + ", ".join(SOURCE_ARCHIVE_DEFAULT_PLATFORMS)
                )
                return
            last_error = SyncError("SourceForge did not return the requested defaults")
        except SyncError as error:
            last_error = error
        if attempt < attempts - 1:
            time.sleep(delay_seconds)

    raise SyncError(
        "SourceForge Release API default-download update failed after bounded retries: "
        f"{last_error}"
    )


def best_release(project: str, user_agent: str) -> dict[str, Any]:
    url = (
        "https://sourceforge.net/projects/"
        f"{quote(project, safe='')}/best_release.json"
    )
    try:
        response = run(
            [
                "curl",
                "--fail",
                "--silent",
                "--show-error",
                "--location",
                "--max-time",
                "60",
                "--user-agent",
                user_agent,
                url,
            ]
        )
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or "").strip()
        suffix = f": {detail}" if detail else ""
        raise SyncError(
            "SourceForge best_release.json request failed with curl exit "
            f"{error.returncode}{suffix}"
        ) from error
    try:
        payload = json.loads(response.stdout)
    except json.JSONDecodeError as error:
        raise SyncError("SourceForge best_release.json returned invalid JSON") from error
    release = payload.get("release")
    if not isinstance(release, dict):
        raise SyncError("SourceForge best_release.json returned no release")
    return release


def verify_default_downloads(
    project: str,
    tag: str,
    source_archive: Path,
    apk: Path | None,
    *,
    attempts: int = SOURCEFORGE_DEFAULT_VERIFY_ATTEMPTS,
    delay_seconds: int = SOURCEFORGE_DEFAULT_VERIFY_DELAY_SECONDS,
) -> None:
    expected: dict[str, Path] = {
        "Windows": source_archive,
        "macOS": source_archive,
        "Linux": source_archive,
    }
    if apk:
        expected["Android"] = apk
    else:
        print("No APK is attached; Android default metadata was left unchanged")

    last_failures: list[str] = []
    for attempt in range(attempts):
        failures: list[str] = []
        for platform, expected_file in expected.items():
            try:
                release = best_release(project, BEST_RELEASE_USER_AGENTS[platform])
                expected_name = f"/{tag}/{expected_file.name}"
                if release.get("filename") != expected_name:
                    failures.append(platform)
                    continue
                if release.get("sha256sum") != sha256(expected_file):
                    failures.append(f"{platform} checksum")
            except SyncError:
                failures.append(f"{platform} request")
        if not failures:
            for platform, expected_file in expected.items():
                print(f"Verified {platform} default download: {expected_file.name}")
            if apk:
                print(
                    "SourceForge has no Android Release API platform identifier; "
                    "the APK selection was verified through Android user-agent detection"
                )
            return
        last_failures = failures
        if attempt < attempts - 1:
            time.sleep(delay_seconds)

    raise SyncError(
        "SourceForge default-download verification failed after bounded retries for: "
        + ", ".join(last_failures)
    )


def write_summary(
    tag: str, urls: list[str], source_archive: Path, apk: Path | None
) -> None:
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary:
        return
    with Path(summary).open("a", encoding="utf-8") as output:
        output.write(f"## SourceForge synchronization: {tag}\n\n")
        for url in urls:
            output.write(f"- {url}\n")
        output.write("\n### Default downloads\n\n")
        output.write(
            f"- Windows, macOS, Linux, BSD, Solaris: `{source_archive.name}`\n"
        )
        if apk:
            output.write(
                f"- Android (user-agent selection; no API identifier): `{apk.name}`\n"
            )
        else:
            output.write("- Android: unchanged because the release has no APK\n")


def synchronize_prepared_release(
    tag: str, release: dict[str, Any], prepared: list[Path]
) -> None:
    source_archive, apk = select_default_downloads(tag, prepared)
    api_key = require_release_api_key()
    project = os.environ.get("SOURCEFORGE_PROJECT", "rme-pdf-scanner").strip()

    urls = sync_sourceforge(tag, prepared)
    verify_public_downloads(urls, prepared)
    update_sourceforge_defaults(project, tag, source_archive, api_key)
    verify_default_downloads(project, tag, source_archive, apk)
    write_summary(tag, urls, source_archive, apk)
    print(f"Synchronized {release['html_url']} to SourceForge")


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
        synchronize_prepared_release(args.tag, release, prepared)
        return 0

    with tempfile.TemporaryDirectory(prefix="rme-sourceforge-release-") as temporary:
        output_dir = Path(temporary)
        release, prepared = prepare_release(
            repo_root, output_dir, repository, args.tag, token
        )
        if args.prepare_only:
            return 0
        synchronize_prepared_release(args.tag, release, prepared)
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
