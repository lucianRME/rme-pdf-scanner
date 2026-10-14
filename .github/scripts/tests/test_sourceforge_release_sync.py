from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "sourceforge_release_sync.py"
SPEC = importlib.util.spec_from_file_location("sourceforge_release_sync", SCRIPT)
assert SPEC and SPEC.loader
sync = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sync)


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0
        self.sleeps: list[float] = []

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.now += seconds


class SourceForgeReleaseSyncTest(unittest.TestCase):
    def test_selects_one_source_and_one_apk(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            source = directory / "rme-pdf-scanner-v1.7.0-source.tar.gz"
            apk = directory / "rme-pdf-scanner-v1.7.0.apk"
            source.touch()
            apk.touch()

            self.assertEqual(
                sync.select_default_downloads("v1.7.0", [source, apk]),
                (source, apk),
            )

    def test_refuses_to_guess_between_multiple_apks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            source = directory / "rme-pdf-scanner-v1.7.0-source.tar.gz"
            source.touch()
            prepared = [source, directory / "one.apk", directory / "two.apk"]

            with self.assertRaisesRegex(sync.SyncError, "refusing to guess"):
                sync.select_default_downloads("v1.7.0", prepared)

    def test_idempotent_rerun_skips_identical_remote_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            prepared_directory = directory / "prepared"
            remote_directory = directory / "remote"
            prepared_directory.mkdir()
            remote_directory.mkdir()
            prepared = prepared_directory / "rme-pdf-scanner-v1.7.0.apk"
            remote = remote_directory / prepared.name
            prepared.write_bytes(b"signed-apk")
            remote.write_bytes(prepared.read_bytes())

            self.assertEqual(sync.verify_existing([prepared], remote_directory), [])

    def test_release_api_put_is_idempotent_and_key_is_not_in_url(self) -> None:
        source = Path("rme-pdf-scanner-v1.7.0-source.tar.gz")
        response = {
            "result": {
                "x_sf": {
                    "default": ",".join(sync.SOURCE_ARCHIVE_DEFAULT_PLATFORMS)
                }
            }
        }
        with patch.object(sync, "request_json", return_value=response) as request:
            sync.update_sourceforge_defaults(
                "rme-pdf-scanner",
                "v1.7.0",
                source,
                "secret-value",
                attempts=1,
                delay_seconds=0,
            )

        _, kwargs = request.call_args
        self.assertEqual(kwargs["method"], "PUT")
        self.assertNotIn("secret-value", request.call_args.args[0])
        self.assertIn(b"api_key=secret-value", kwargs["data"])
        self.assertNotIn("others", sync.SOURCE_ARCHIVE_DEFAULT_PLATFORMS)

    def test_release_api_error_does_not_expose_key(self) -> None:
        with patch.object(
            sync, "request_json", return_value={"error": "secret-value is invalid"}
        ):
            with self.assertRaises(sync.SyncError) as raised:
                sync.update_sourceforge_defaults(
                    "rme-pdf-scanner",
                    "v1.7.0",
                    Path("rme-pdf-scanner-v1.7.0-source.tar.gz"),
                    "secret-value",
                    attempts=1,
                    delay_seconds=0,
                )

        self.assertNotIn("secret-value", str(raised.exception))

    def test_missing_api_key_stops_before_sourceforge_upload(self) -> None:
        source = Path("rme-pdf-scanner-v1.7.0-source.tar.gz")
        release = {"html_url": "https://example.invalid/release"}
        with (
            patch.dict(sync.os.environ, {}, clear=True),
            patch.object(sync, "sync_sourceforge") as upload,
            self.assertRaisesRegex(sync.SyncError, "SOURCEFORGE_RELEASE_API_KEY"),
        ):
            sync.synchronize_prepared_release("v1.7.0", release, [source])

        upload.assert_not_called()

    def test_best_release_verification_uses_platform_user_agents(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            source = directory / "rme-pdf-scanner-v1.7.0-source.tar.gz"
            apk = directory / "rme-pdf-scanner-v1.7.0.apk"
            source.write_bytes(b"source")
            apk.write_bytes(b"apk")

            def fake_best_release(
                project: str, user_agent: str, **_kwargs: object
            ) -> dict[str, str]:
                self.assertEqual(project, "rme-pdf-scanner")
                if "Android" in user_agent:
                    selected = apk
                else:
                    selected = source
                return {
                    "filename": f"/v1.7.0/{selected.name}",
                    "sha256sum": sync.sha256(selected),
                }

            with patch.object(sync, "best_release", side_effect=fake_best_release) as check:
                clock = FakeClock()
                sync.verify_default_downloads(
                    "rme-pdf-scanner",
                    "v1.7.0",
                    source,
                    apk,
                    max_wait_seconds=1,
                    monotonic=clock.monotonic,
                    sleep=clock.sleep,
                )

        self.assertEqual(check.call_count, 4)
        self.assertEqual(clock.sleeps, [])

    def test_best_release_uses_curl_and_parses_json(self) -> None:
        selected = {
            "release": {
                "filename": "/v1.7.0/rme-pdf-scanner-v1.7.0.apk",
                "sha256sum": "abc123",
            }
        }
        completed = sync.subprocess.CompletedProcess(
            args=[], returncode=0, stdout=json.dumps(selected) + "\n200", stderr=""
        )
        with patch.object(sync, "run", return_value=completed) as request:
            release = sync.best_release(
                "rme-pdf-scanner", sync.BEST_RELEASE_USER_AGENTS["Android"]
            )

        self.assertEqual(release, selected["release"])
        command = request.call_args.args[0]
        self.assertEqual(command[0], "curl")
        self.assertIn("--fail", command)
        self.assertIn("--write-out", command)
        self.assertIn("--user-agent", command)
        self.assertIn("rme-sourceforge-release-sync/1.0", " ".join(command))
        self.assertNotIn("api_key", " ".join(command))

    def test_no_apk_leaves_android_selection_unchecked_and_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "rme-pdf-scanner-v1.7.0-source.tar.gz"
            source.write_bytes(b"source")
            selected = {
                "filename": f"/v1.7.0/{source.name}",
                "sha256sum": sync.sha256(source),
            }
            with patch.object(sync, "best_release", return_value=selected) as check:
                clock = FakeClock()
                sync.verify_default_downloads(
                    "rme-pdf-scanner",
                    "v1.7.0",
                    source,
                    None,
                    max_wait_seconds=1,
                    monotonic=clock.monotonic,
                    sleep=clock.sleep,
                )

        self.assertEqual(check.call_count, 3)
        used_agents = {call.args[1] for call in check.call_args_list}
        self.assertNotIn(sync.BEST_RELEASE_USER_AGENTS["Android"], used_agents)

    def test_missing_checksum_metadata_is_retried_then_succeeds(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "rme-pdf-scanner-v1.7.0-source.tar.gz"
            source.write_bytes(b"source")
            calls = 0

            def delayed_checksum(
                *_args: object, **_kwargs: object
            ) -> dict[str, str | None]:
                nonlocal calls
                calls += 1
                return {
                    "filename": f"/v1.7.0/{source.name}",
                    "sha256sum": None if calls <= 3 else sync.sha256(source),
                }

            clock = FakeClock()
            with patch.object(sync, "best_release", side_effect=delayed_checksum):
                sync.verify_default_downloads(
                    "rme-pdf-scanner",
                    "v1.7.0",
                    source,
                    None,
                    max_wait_seconds=30,
                    monotonic=clock.monotonic,
                    sleep=clock.sleep,
                )

        self.assertEqual(clock.sleeps, [5])

    def test_delayed_metadata_uses_capped_exponential_backoff(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "rme-pdf-scanner-v1.7.0-source.tar.gz"
            source.write_bytes(b"source")
            calls = 0

            def delayed_checksum(
                *_args: object, **_kwargs: object
            ) -> dict[str, str | None]:
                nonlocal calls
                calls += 1
                attempt = (calls - 1) // 3 + 1
                return {
                    "filename": f"/v1.7.0/{source.name}",
                    "sha256sum": None if attempt <= 6 else sync.sha256(source),
                }

            clock = FakeClock()
            with patch.object(sync, "best_release", side_effect=delayed_checksum):
                sync.verify_default_downloads(
                    "rme-pdf-scanner",
                    "v1.7.0",
                    source,
                    None,
                    max_wait_seconds=300,
                    monotonic=clock.monotonic,
                    sleep=clock.sleep,
                )

        self.assertEqual(clock.sleeps, [5, 10, 20, 40, 60, 60])

    def test_old_default_metadata_is_retried_then_succeeds(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "rme-pdf-scanner-v1.7.0-source.tar.gz"
            source.write_bytes(b"source")
            calls = 0

            def delayed_default(
                *_args: object, **_kwargs: object
            ) -> dict[str, str]:
                nonlocal calls
                calls += 1
                filename = (
                    "/v1.6.0/rme-pdf-scanner-v1.6.0-source.tar.gz"
                    if calls <= 3
                    else f"/v1.7.0/{source.name}"
                )
                return {"filename": filename, "sha256sum": sync.sha256(source)}

            clock = FakeClock()
            with patch.object(sync, "best_release", side_effect=delayed_default):
                sync.verify_default_downloads(
                    "rme-pdf-scanner",
                    "v1.7.0",
                    source,
                    None,
                    max_wait_seconds=30,
                    monotonic=clock.monotonic,
                    sleep=clock.sleep,
                )

        self.assertEqual(clock.sleeps, [5])

    def test_temporary_http_5xx_is_retryable(self) -> None:
        error = subprocess.CalledProcessError(
            22, ["curl"], output="\n503", stderr="curl: server error"
        )
        with (
            patch.object(sync, "run", side_effect=error),
            self.assertRaisesRegex(sync.TransientSourceForgeError, "HTTP 503"),
        ):
            sync.best_release("rme-pdf-scanner", "test-agent")

    def test_temporary_network_failure_is_retryable(self) -> None:
        error = subprocess.CalledProcessError(
            28, ["curl"], output="\n000", stderr="curl: timeout"
        )
        with (
            patch.object(sync, "run", side_effect=error),
            self.assertRaisesRegex(
                sync.TransientSourceForgeError, "temporarily failed with curl exit 28"
            ),
        ):
            sync.best_release("rme-pdf-scanner", "test-agent")

    def test_authorization_failure_is_not_retried(self) -> None:
        error = subprocess.CalledProcessError(
            22, ["curl"], output="\n403", stderr="curl: forbidden"
        )
        with patch.object(sync, "run", side_effect=error):
            with self.assertRaises(sync.SyncError) as raised:
                sync.best_release("rme-pdf-scanner", "test-agent")

        self.assertNotIsInstance(raised.exception, sync.TransientSourceForgeError)
        self.assertIn("HTTP 403", str(raised.exception))

    def test_invalid_api_response_is_not_retried(self) -> None:
        completed = sync.subprocess.CompletedProcess(
            args=[], returncode=0, stdout="not-json\n200", stderr=""
        )
        with patch.object(sync, "run", return_value=completed):
            with self.assertRaises(sync.SyncError) as raised:
                sync.best_release("rme-pdf-scanner", "test-agent")

        self.assertNotIsInstance(raised.exception, sync.TransientSourceForgeError)
        self.assertIn("invalid JSON", str(raised.exception))

    def test_persistent_checksum_mismatch_fails_at_deadline(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "rme-pdf-scanner-v1.7.0-source.tar.gz"
            source.write_bytes(b"source")
            selected = {
                "filename": f"/v1.7.0/{source.name}",
                "sha256sum": "0" * 64,
            }
            clock = FakeClock()
            with patch.object(sync, "best_release", return_value=selected):
                with self.assertRaises(sync.SyncError) as raised:
                    sync.verify_default_downloads(
                        "rme-pdf-scanner",
                        "v1.7.0",
                        source,
                        None,
                        max_wait_seconds=15,
                        monotonic=clock.monotonic,
                        sleep=clock.sleep,
                    )

        message = str(raised.exception)
        self.assertIn("deadline exceeded after 2 attempts and 15s", message)
        self.assertIn("filename selection correct", message)
        self.assertIn("SHA-256 metadata incorrect", message)
        self.assertIn("already uploaded and publicly verified", message)

    def test_wrong_current_release_artifact_fails_without_retry(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "rme-pdf-scanner-v1.7.0-source.tar.gz"
            source.write_bytes(b"source")
            selected = {
                "filename": "/v1.7.0/README.md",
                "sha256sum": sync.sha256(source),
            }
            clock = FakeClock()
            with patch.object(sync, "best_release", return_value=selected):
                with self.assertRaisesRegex(sync.SyncError, "unexpected file"):
                    sync.verify_default_downloads(
                        "rme-pdf-scanner",
                        "v1.7.0",
                        source,
                        None,
                        max_wait_seconds=480,
                        monotonic=clock.monotonic,
                        sleep=clock.sleep,
                    )

        self.assertEqual(clock.sleeps, [])

    def test_missing_metadata_reports_retry_deadline_details(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "rme-pdf-scanner-v1.7.0-source.tar.gz"
            source.write_bytes(b"source")
            selected = {
                "filename": f"/v1.7.0/{source.name}",
                "sha256sum": None,
            }
            clock = FakeClock()
            with patch.object(sync, "best_release", return_value=selected):
                with self.assertRaises(sync.SyncError) as raised:
                    sync.verify_default_downloads(
                        "rme-pdf-scanner",
                        "v1.7.0",
                        source,
                        None,
                        max_wait_seconds=5,
                        monotonic=clock.monotonic,
                        sleep=clock.sleep,
                    )

        message = str(raised.exception)
        self.assertIn("Windows: filename selection correct", message)
        self.assertIn("SHA-256 metadata missing", message)
        self.assertIn("1 attempt and 5s", message)

    def test_metadata_retries_do_not_repeat_sourceforge_upload(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "rme-pdf-scanner-v1.7.0-source.tar.gz"
            source.write_bytes(b"source")
            release = {"html_url": "https://example.invalid/release"}
            calls = 0

            def delayed_checksum(
                *_args: object, **_kwargs: object
            ) -> dict[str, str | None]:
                nonlocal calls
                calls += 1
                return {
                    "filename": f"/v1.7.0/{source.name}",
                    "sha256sum": None if calls <= 3 else sync.sha256(source),
                }

            clock = FakeClock()
            with (
                patch.dict(
                    sync.os.environ,
                    {"SOURCEFORGE_RELEASE_API_KEY": "secret-value"},
                    clear=True,
                ),
                patch.object(sync, "sync_sourceforge", return_value=[]) as upload,
                patch.object(sync, "verify_public_downloads"),
                patch.object(sync, "update_sourceforge_defaults"),
                patch.object(sync, "best_release", side_effect=delayed_checksum),
                patch.object(sync, "write_summary"),
                patch.object(sync.time, "monotonic", side_effect=clock.monotonic),
                patch.object(sync.time, "sleep", side_effect=clock.sleep),
            ):
                sync.synchronize_prepared_release("v1.7.0", release, [source])

        upload.assert_called_once()


if __name__ == "__main__":
    unittest.main()
