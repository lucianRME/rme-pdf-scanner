from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "sourceforge_release_sync.py"
SPEC = importlib.util.spec_from_file_location("sourceforge_release_sync", SCRIPT)
assert SPEC and SPEC.loader
sync = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sync)


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

            def fake_best_release(project: str, user_agent: str) -> dict[str, str]:
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
                sync.verify_default_downloads(
                    "rme-pdf-scanner",
                    "v1.7.0",
                    source,
                    apk,
                    attempts=1,
                    delay_seconds=0,
                )

        self.assertEqual(check.call_count, 4)

    def test_best_release_uses_curl_and_parses_json(self) -> None:
        selected = {
            "release": {
                "filename": "/v1.7.0/rme-pdf-scanner-v1.7.0.apk",
                "sha256sum": "abc123",
            }
        }
        completed = sync.subprocess.CompletedProcess(
            args=[], returncode=0, stdout=json.dumps(selected), stderr=""
        )
        with patch.object(sync, "run", return_value=completed) as request:
            release = sync.best_release(
                "rme-pdf-scanner", sync.BEST_RELEASE_USER_AGENTS["Android"]
            )

        self.assertEqual(release, selected["release"])
        command = request.call_args.args[0]
        self.assertEqual(command[0], "curl")
        self.assertIn("--fail", command)
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
                sync.verify_default_downloads(
                    "rme-pdf-scanner",
                    "v1.7.0",
                    source,
                    None,
                    attempts=1,
                    delay_seconds=0,
                )

        self.assertEqual(check.call_count, 3)
        used_agents = {call.args[1] for call in check.call_args_list}
        self.assertNotIn(sync.BEST_RELEASE_USER_AGENTS["Android"], used_agents)


if __name__ == "__main__":
    unittest.main()
