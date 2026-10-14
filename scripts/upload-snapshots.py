"""Upload prepared Maven snapshots without executing project or dependency code."""

import argparse
import base64
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path, PurePosixPath
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

REPOSITORY = "https://repo.clojars.org"
TOKEN_NAMES = {"cloverage": "CLOJARS_CLOVERAGE_TOKEN",
               "lein-cloverage": "CLOJARS_LEIN_CLOVERAGE_TOKEN"}


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def check_packages(root, commit=None):
    root = Path(root).resolve()
    manifest = json.loads((root / "manifest.json").read_text())
    version = manifest["version"]
    if not version.endswith("-SNAPSHOT"):
        raise ValueError("Only SNAPSHOT versions may be uploaded")
    if commit and manifest["commit"] != commit:
        raise ValueError("Snapshot packages do not match the workflow commit")
    artifacts = manifest["artifacts"]
    if [a["name"] for a in artifacts] != list(TOKEN_NAMES):
        raise ValueError("Expected the library followed by the plugin")
    for artifact in artifacts:
        name = artifact["name"]
        paths = set()
        for entry in artifact["files"]:
            relative = PurePosixPath(entry["path"])
            parts = relative.parts
            if (relative.is_absolute() or ".." in parts
                    or parts[:2] != (name, name) or len(parts) not in (3, 4)
                    or (len(parts) == 4 and parts[2] != version)
                    or (len(parts) == 3 and not parts[2].startswith("maven-metadata.xml"))):
                raise ValueError("Package path is outside its snapshot coordinates")
            path = (root / relative).resolve()
            path.relative_to(root)
            if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
                raise ValueError("Snapshot package checksum mismatch")
            paths.add(relative)
        required = PurePosixPath(name, name, version, "maven-metadata.xml")
        if required not in paths or PurePosixPath(name, name, "maven-metadata.xml") not in paths:
            raise ValueError("Snapshot Maven metadata is missing")
        for extension in (".jar", ".pom"):
            if len([p for p in paths if p.suffix == extension]) != 1:
                raise ValueError("Expected one snapshot JAR and POM per artifact")
        pom_path = next(p for p in paths if p.suffix == ".pom")
        pom = ET.parse(root / pom_path).getroot()
        prefix = pom.tag.partition("}")[0] + "}" if pom.tag.startswith("{") else ""
        if [pom.findtext(prefix + k) for k in ("groupId", "artifactId", "version")] != [name, name, version]:
            raise ValueError("POM coordinates do not match the snapshot manifest")
    return manifest


def upload(root, manifest, username, tokens):
    if not username or any(not tokens.get(name) for name in TOKEN_NAMES):
        raise ValueError("Set CLOJARS_USERNAME and both artifact-scoped deploy tokens")
    opener = urllib.request.build_opener(
        NoRedirects(), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    for artifact in manifest["artifacts"]:
        name = artifact["name"]
        auth = base64.b64encode((username + ":" + tokens[name]).encode()).decode()
        # The artifact-level metadata finalizes a Clojars deployment; send it last.
        final_metadata = f"{name}/{name}/maven-metadata.xml"
        entries = sorted(artifact["files"], key=lambda e: (
            len(PurePosixPath(e["path"]).parts) == 3,
            e["path"] == final_metadata, e["path"]))
        for entry in entries:
            relative = entry["path"]
            request = urllib.request.Request(
                REPOSITORY + "/" + urllib.parse.quote(relative, safe="/"),
                data=(Path(root) / relative).read_bytes(), method="PUT",
                headers={"Authorization": "Basic " + auth,
                         "Content-Type": "application/octet-stream"})
            try:
                with opener.open(request, timeout=60):
                    pass
            except urllib.error.HTTPError as error:
                raise RuntimeError(f"Upload of {relative} failed with HTTP {error.code}") from None
        print(f"Published {name} {manifest['version']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path)
    parser.add_argument("--check", action="store_true", help="Validate packages without credentials or uploads")
    args = parser.parse_args()
    manifest = check_packages(args.repository, os.environ.get("GITHUB_SHA"))
    if args.check:
        print(f"Validated both artifacts at {manifest['version']}")
    else:
        upload(args.repository, manifest, os.environ.get("CLOJARS_USERNAME"),
               {name: os.environ.get(env) for name, env in TOKEN_NAMES.items()})
        if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(summary, "a") as output:
                output.write(f"Published both artifacts at `{manifest['version']}` from `{manifest['commit']}`.\n")


if __name__ == "__main__":
    main()
