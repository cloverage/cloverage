import base64
import hashlib
import http.server
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("uploader", Path(__file__).with_name("upload-snapshots.py"))
uploader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(uploader)


class UploadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.version = "1.3.0-SNAPSHOT"
        self.manifest = {"version": self.version, "commit": "a" * 40, "artifacts": []}
        self.tokens = {"cloverage": "library-test-token", "lein-cloverage": "plugin-test-token"}
        for name in uploader.TOKEN_NAMES:
            base = f"{name}/{name}"
            stem = f"{base}/{self.version}/{name}-1.3.0-20261013.000000-1"
            files = {
                stem + ".jar": b"artifact bytes, never executed",
                stem + ".pom": f"<project><groupId>{name}</groupId><artifactId>{name}</artifactId><version>{self.version}</version></project>".encode(),
                f"{base}/{self.version}/maven-metadata.xml": b"<metadata/>",
                f"{base}/maven-metadata.xml": b"<metadata/>"}
            for relative, content in list(files.items()):
                files[relative + ".md5"] = hashlib.md5(content).hexdigest().encode()
                files[relative + ".sha1"] = hashlib.sha1(content).hexdigest().encode()
            entries = []
            for relative, content in files.items():
                path = self.root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
                entries.append({"path": relative, "sha256": hashlib.sha256(content).hexdigest()})
            self.manifest["artifacts"].append({"name": name, "files": entries})
        self.write_manifest()
        self.received = []

    def write_manifest(self):
        (self.root / "manifest.json").write_text(json.dumps(self.manifest))

    def server(self, status=201, redirect=None):
        received = self.received

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_PUT(self):
                body = self.rfile.read(int(self.headers["Content-Length"]))
                received.append((self.path, self.headers["Authorization"], body))
                self.send_response(status)
                if redirect:
                    self.send_header("Location", redirect)
                self.end_headers()

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return f"http://127.0.0.1:{server.server_port}"

    def test_uploads_both_artifacts_with_separate_tokens_and_metadata_last(self):
        manifest = uploader.check_packages(self.root, "a" * 40)
        with patch.object(uploader, "REPOSITORY", self.server()):
            uploader.upload(self.root, manifest, "test-user", self.tokens)
        self.assertEqual(24, len(self.received))
        for name in uploader.TOKEN_NAMES:
            requests = [r for r in self.received if r[0].startswith(f"/{name}/")]
            expected = "Basic " + base64.b64encode(f"test-user:{self.tokens[name]}".encode()).decode()
            self.assertTrue(all(r[1] == expected for r in requests))
            self.assertEqual(f"/{name}/{name}/maven-metadata.xml", requests[-1][0])
        self.assertTrue(all(r[0].startswith("/cloverage/") for r in self.received[:12]))

    def test_rejects_pom_coordinates_even_when_checksums_match(self):
        entry = next(e for e in self.manifest["artifacts"][1]["files"] if e["path"].endswith(".pom"))
        path = self.root / entry["path"]
        data = path.read_bytes().replace(b"1.3.0-SNAPSHOT", b"1.3.0")
        path.write_bytes(data)
        entry["sha256"] = hashlib.sha256(data).hexdigest()
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "POM coordinates"):
            uploader.check_packages(self.root)

    def test_rejects_release_version(self):
        self.manifest["version"] = "1.3.0"
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "Only SNAPSHOT"):
            uploader.check_packages(self.root)

    def test_rejects_packages_from_another_commit(self):
        with self.assertRaisesRegex(ValueError, "workflow commit"):
            uploader.check_packages(self.root, "b" * 40)

    def test_rejects_tampered_artifact(self):
        (self.root / self.manifest["artifacts"][0]["files"][0]["path"]).write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "checksum"):
            uploader.check_packages(self.root)

    def test_rejects_artifact_paths_outside_allowed_coordinates(self):
        self.manifest["artifacts"][0]["files"][0]["path"] = "../outside.jar"
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "coordinates"):
            uploader.check_packages(self.root)

    def test_requires_both_tokens_before_uploading_either_artifact(self):
        with patch.object(uploader, "REPOSITORY", self.server()):
            with self.assertRaisesRegex(ValueError, "both artifact-scoped"):
                uploader.upload(self.root, self.manifest, "test-user", {"cloverage": "test-token"})
        self.assertEqual([], self.received)

    def test_does_not_follow_redirects_with_credentials(self):
        with patch.object(uploader, "REPOSITORY", self.server(307, "http://127.0.0.1:1/redirected")):
            with self.assertRaisesRegex(RuntimeError, "HTTP 307"):
                uploader.upload(self.root, self.manifest, "test-user", self.tokens)
        self.assertEqual(1, len(self.received))

    def test_upload_failure_stops_before_plugin(self):
        with patch.object(uploader, "REPOSITORY", self.server(403)):
            with self.assertRaisesRegex(RuntimeError, "HTTP 403"):
                uploader.upload(self.root, self.manifest, "test-user", self.tokens)
        self.assertEqual(1, len(self.received))
        self.assertTrue(self.received[0][0].startswith("/cloverage/"))


if __name__ == "__main__":
    unittest.main()
