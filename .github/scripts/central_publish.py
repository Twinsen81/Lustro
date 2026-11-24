#!/usr/bin/env python3
"""Sign the Maven publications that Gradle wrote, and publish them to Maven Central.

Usage:
  central_publish.py prepare --repo DIR --out DIR --properties FILE [--expect-version VERSION]
  central_publish.py bundle --dir DIR --out FILE
  central_publish.py release --bundle FILE [--name NAME] [--publishing-type TYPE]
  central_publish.py snapshot --dir DIR --properties FILE

release.yml and snapshot.yml run this script in a publish job that runs no Gradle and no
other third-party build code. The build job runs `./gradlew
publishAllPublicationsToStagingRepository` with no secrets, which writes the unsigned
publications of all published modules to build/staging-deploy, and uploads that directory as a
workflow artifact. Only the publish job gets the signing key and the Central credentials. See
SECURITY.md, "Release Publishing".

prepare   Reads the staging repository of Gradle. It checks that it holds only the group and
          the version of gradle.properties (FILE), and that each module has the files and the
          POM elements that Central requires. Then it copies the artifacts of each module to a
          new directory in the Maven repository layout, signs each one with gpg, and writes
          its .md5, .sha1, .sha256, and .sha512 checksums. A -SNAPSHOT version keeps its
          -SNAPSHOT file names there. Environment: SIGNING_KEY, the secret key, ASCII-armored,
          or only the base64 lines of the armor; SIGNING_KEY_ID, the key to sign with
          (optional: the primary key of SIGNING_KEY by default); SIGNING_KEY_PASSWORD
          (optional).
bundle    Makes the zip of a prepared release directory that the Central Publisher API takes,
          after it checks that each artifact has its signature and its checksums.
release   Uploads the bundle through the Central Publisher API and waits until Central has
          validated it: until the deployment is PUBLISHING or PUBLISHED, or VALIDATED with the
          publishing type USER_MANAGED. Environment: CENTRAL_USERNAME and CENTRAL_PASSWORD,
          the user token of the Central Portal.
snapshot  Uploads a prepared -SNAPSHOT directory to the Central snapshot repository, with
          timestamped file names, the next build number of each module, and the
          maven-metadata.xml files that a Maven or Gradle client reads. It uploads the files of
          all modules first and the metadata last, so that a client sees a new snapshot only
          when its files are there. Environment: CENTRAL_USERNAME and CENTRAL_PASSWORD.

Central verifies a signature only with a primary key, so the signing key is the primary key
unless SIGNING_KEY_ID names another one.

Python standard library and the gpg command only.
"""

from __future__ import annotations

import argparse
import base64
import datetime
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Dict, Iterable, List, Optional, Tuple

CENTRAL_URL = "https://central.sonatype.com"
SNAPSHOT_REPOSITORY_URL = "https://central.sonatype.com/repository/maven-snapshots/"

# The checksums that prepare writes for each artifact. Central requires .md5 and .sha1, and
# takes .sha256 and .sha512, which Gradle also publishes. A signature needs no checksum.
CHECKSUMS = ("md5", "sha1", "sha256", "sha512")
REQUIRED_CHECKSUMS = ("md5", "sha1")
SIDECAR_SUFFIXES = tuple("." + name for name in CHECKSUMS) + (".asc",)

POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
# The POM elements that Central requires (https://central.sonatype.org/publish/requirements/).
REQUIRED_POM_ELEMENTS = (
    "m:name",
    "m:description",
    "m:url",
    "m:licenses/m:license/m:name",
    "m:licenses/m:license/m:url",
    "m:developers/m:developer",
    "m:scm/m:url",
    "m:scm/m:connection",
    "m:scm/m:developerConnection",
)

SUCCESS_STATES = {"AUTOMATIC": {"PUBLISHING", "PUBLISHED"}, "USER_MANAGED": {"VALIDATED"}}
WAIT_STATES = {"PENDING", "VALIDATING", "VALIDATED", "PUBLISHING"}


class PublishError(Exception):
    """A failure that the script reports in one line, without a stack trace."""


# --- Artifacts ----------------------------------------------------------------------------------


@dataclass(frozen=True)
class Artifact:
    """One file of a module's publication, such as the AAR, the POM, or the sources jar."""

    artifact_id: str
    classifier: str  # "" when the file has no classifier
    extension: str
    path: Path

    def file_name(self, version: str) -> str:
        classifier = "-" + self.classifier if self.classifier else ""
        return f"{self.artifact_id}-{version}{classifier}.{self.extension}"


def is_sidecar(name: str) -> bool:
    return name.endswith(SIDECAR_SUFFIXES)


def split_file_name(name: str, artifact_id: str, version: str) -> Optional[Tuple[str, str]]:
    """The (classifier, extension) of `<artifactId>-<version>[-<classifier>].<extension>`."""
    prefix = f"{artifact_id}-{version}"
    if not name.startswith(prefix):
        return None
    rest = name[len(prefix):]
    if rest.startswith("."):
        classifier, extension = "", rest[1:]
    elif rest.startswith("-"):
        classifier, _, extension = rest[1:].partition(".")
    else:
        return None
    if not extension or (rest.startswith("-") and not classifier):
        return None
    return classifier, extension


def snapshot_base(version: str) -> str:
    return version[: -len("-SNAPSHOT")]


def read_properties(path: Path) -> Dict[str, str]:
    """The key=value lines of a gradle.properties file."""
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return values


def coordinates(properties: Path) -> Tuple[str, str]:
    values = read_properties(properties)
    group, version = values.get("GROUP"), values.get("VERSION_NAME")
    if not group or not version:
        raise PublishError(f"{properties} has no GROUP or no VERSION_NAME.")
    return group, version


# --- Reading the staging repository of Gradle ---------------------------------------------------


def staged_version_value(version_dir: Path, artifact_id: str, version: str) -> str:
    """The version in the file names: the timestamped one of the last build for a snapshot.

    For a -SNAPSHOT version, Gradle writes timestamped files to a file repository, such as
    lustro-0.1.0-20261123.120000-1.aar, and names the last build in maven-metadata.xml.
    """
    if not version.endswith("-SNAPSHOT"):
        return version
    metadata = version_dir / "maven-metadata.xml"
    if not metadata.is_file():
        raise PublishError(f"{version_dir}: no maven-metadata.xml for the -SNAPSHOT version.")
    root = ET.parse(metadata).getroot()
    timestamp = root.findtext("versioning/snapshot/timestamp")
    build = root.findtext("versioning/snapshot/buildNumber")
    if not timestamp or not build:
        raise PublishError(f"{metadata}: no snapshot timestamp or build number.")
    return f"{snapshot_base(version)}-{timestamp}-{build}"


def read_staging(repo: Path, group: str, version: str) -> Dict[str, List[Artifact]]:
    """The artifacts of each module in the staging repository, by artifactId.

    Fails on a file that is not in a module of `group` at `version`, so that a build can't add
    another group or another version to what the publish job signs. Fails on a symbolic link
    too: prepare runs with the signing key in its environment, and a link to a file such as
    /proc/self/environ would copy the key into a published file.
    """
    group_parts = tuple(group.split("."))
    group_dir = repo.joinpath(*group_parts)
    if not group_dir.is_dir():
        raise PublishError(f"{repo} has no {group} publications.")
    links = [
        Path(top, name).relative_to(repo)
        for top, dirs, files in os.walk(repo)
        for name in dirs + files
        if os.path.islink(os.path.join(top, name))
    ]
    if links:
        raise PublishError(f"{repo} has symbolic links: " + ", ".join(str(l) for l in sorted(links)))
    problems = []
    for path in sorted(p for p in repo.rglob("*") if p.is_file()):
        relative = path.relative_to(repo)
        parts = relative.parts
        # <group>/<artifactId>/maven-metadata.xml*, or <group>/<artifactId>/<version>/<file>
        depth = len(parts) - len(group_parts)
        if parts[: len(group_parts)] != group_parts or depth not in (2, 3):
            problems.append(f"{relative}: not in a module of {group}")
        elif depth == 2 and not parts[-1].startswith("maven-metadata.xml"):
            problems.append(f"{relative}: not in a version directory")
        elif depth == 3 and parts[-2] != version:
            problems.append(f"{relative}: version {parts[-2]}, not {version}")
    if problems:
        raise PublishError(
            "The staging repository holds other files than the publications of "
            f"{group}:{version}. Delete it and publish again.\n  " + "\n  ".join(problems)
        )

    modules: Dict[str, List[Artifact]] = {}
    for module_dir in sorted(p for p in group_dir.iterdir() if p.is_dir()):
        artifact_id = module_dir.name
        version_dir = module_dir / version
        if not version_dir.is_dir():
            raise PublishError(f"{module_dir} has no {version} directory.")
        value = staged_version_value(version_dir, artifact_id, version)
        artifacts = []
        for path in sorted(version_dir.iterdir()):
            name = path.name
            if is_sidecar(name) or name.startswith("maven-metadata.xml"):
                continue
            split = split_file_name(name, artifact_id, value)
            if split is None:
                # An older build of the same snapshot in a staging repository that was not
                # deleted: Gradle adds a build, with a new timestamp, to the version directory.
                if version.endswith("-SNAPSHOT") and name.startswith(
                    f"{artifact_id}-{snapshot_base(version)}-"
                ):
                    continue
                raise PublishError(f"{path}: not a file of {artifact_id} {value}.")
            artifacts.append(Artifact(artifact_id, split[0], split[1], path))
        if not artifacts:
            raise PublishError(f"{version_dir} has no files of {artifact_id} {value}.")
        modules[artifact_id] = artifacts
    if not modules:
        raise PublishError(f"{group_dir} has no modules.")
    return modules


def check_module(group: str, version: str, artifacts: List[Artifact]) -> List[str]:
    """What Central would refuse in a module: a missing file, or a POM without a required element."""
    artifact_id = artifacts[0].artifact_id
    by_kind = {(a.classifier, a.extension): a for a in artifacts}
    pom = by_kind.get(("", "pom"))
    if pom is None:
        return [f"{artifact_id}: no POM"]
    problems = []
    try:
        root = ET.parse(pom.path).getroot()
    except ET.ParseError as error:
        return [f"{artifact_id}: the POM is not XML: {error}"]
    for element, expected in (("groupId", group), ("artifactId", artifact_id), ("version", version)):
        found = root.findtext(f"m:{element}", namespaces=POM_NS)
        if found != expected:
            problems.append(f"{artifact_id}: the POM has {element} {found}, not {expected}")
    for path in REQUIRED_POM_ELEMENTS:
        element = root.find(path, POM_NS)
        if element is None or (len(element) == 0 and not (element.text or "").strip()):
            problems.append(f"{artifact_id}: the POM has no {path.replace('m:', '')}")
    packaging = root.findtext("m:packaging", default="jar", namespaces=POM_NS)
    if packaging != "pom":
        main = [a for a in artifacts if not a.classifier and a.extension not in ("pom", "module")]
        if not main:
            problems.append(f"{artifact_id}: no main artifact for the packaging {packaging}")
        for classifier in ("sources", "javadoc"):
            if (classifier, "jar") not in by_kind:
                problems.append(f"{artifact_id}: no -{classifier}.jar")
    return problems


# --- Signing and checksums ---------------------------------------------------------------------


def key_bytes(key: str) -> bytes:
    """The secret key for `gpg --import`: the armor as it is, or the decoded base64 lines.

    The vanniktech plugin documents a key without its armor lines, made with
    `gpg --export-secret-keys --armor KEY | grep -v '\\-\\-' | grep -v '^=.' | tr -d '\\n'`.
    """
    key = key.strip()
    if not key:
        raise PublishError("SIGNING_KEY is empty.")
    if "-----BEGIN PGP" in key:
        return key.encode("ascii")
    try:
        return base64.b64decode("".join(key.split()), validate=True)
    except ValueError:
        raise PublishError("SIGNING_KEY is neither an ASCII-armored key nor base64.") from None


class Signer:
    """Signs files with gpg, in a new GnuPG home that it deletes when the signing is done."""

    def __init__(self, key: str, key_id: Optional[str], passphrase: Optional[str], gpg: str = "gpg"):
        self._key = key_bytes(key)
        self._key_id = (key_id or "").strip()
        self._passphrase = (passphrase or "").encode("utf-8")
        self._gpg = gpg
        self._home: Optional[str] = None
        self._user = ""

    def __enter__(self) -> "Signer":
        self._home = tempfile.mkdtemp(prefix="lustro-gpg-")
        os.chmod(self._home, 0o700)
        try:
            self._run(["--import"], self._key, "import the signing key")
            listing = self._run(
                ["--list-secret-keys", "--with-colons", "--fingerprint"], b"", "list the key"
            ).decode("utf-8", "replace")
            primaries = []
            lines = listing.splitlines()
            for index, line in enumerate(lines):
                if line.startswith("sec:"):
                    fingerprint = next(
                        (l.split(":")[9] for l in lines[index + 1:] if l.startswith("fpr:")), ""
                    )
                    primaries.append(fingerprint)
            if not primaries:
                raise PublishError("SIGNING_KEY holds no secret key.")
            if self._key_id:
                # "!" makes gpg take this key, and not a signing subkey of it.
                self._user = self._key_id + "!"
            elif len(primaries) == 1:
                self._user = primaries[0] + "!"
            else:
                raise PublishError("SIGNING_KEY holds more than one key: set SIGNING_KEY_ID.")
        except BaseException:
            self.__exit__(None, None, None)
            raise
        return self

    def __exit__(self, *exc) -> None:
        if self._home is None:
            return
        # Stop the gpg-agent of this home, which holds the unlocked key in its memory.
        if shutil.which("gpgconf"):
            subprocess.run(
                ["gpgconf", "--homedir", self._home, "--kill", "all"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        shutil.rmtree(self._home, ignore_errors=True)
        self._home = None

    def _run(self, args: List[str], stdin: bytes, what: str) -> bytes:
        assert self._home is not None
        result = subprocess.run(
            [self._gpg, "--batch", "--no-tty", "--homedir", self._home, *args],
            input=stdin,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if result.returncode != 0:
            stderr = result.stderr.decode("utf-8", "replace").strip()
            raise PublishError(f"gpg could not {what}: {stderr}")
        return result.stdout

    def sign(self, path: Path) -> Path:
        signature = path.with_name(path.name + ".asc")
        self._run(
            [
                "--yes",
                "--pinentry-mode",
                "loopback",
                "--passphrase-fd",
                "0",
                "--local-user",
                self._user,
                "--armor",
                "--detach-sign",
                "--output",
                str(signature),
                str(path),
            ],
            self._passphrase,
            f"sign {path.name}",
        )
        return signature


def digest(path: Path, algorithm: str) -> str:
    hasher = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def write_checksums(path: Path) -> None:
    for algorithm in CHECKSUMS:
        path.with_name(f"{path.name}.{algorithm}").write_text(digest(path, algorithm), encoding="ascii")


def prepare(repo: Path, out: Path, group: str, version: str, signer: Signer) -> int:
    modules = read_staging(repo, group, version)
    problems = [p for artifacts in modules.values() for p in check_module(group, version, artifacts)]
    if problems:
        raise PublishError("Central would refuse these modules:\n  " + "\n  ".join(problems))
    if out.exists() and any(out.iterdir()):
        raise PublishError(f"{out} is not empty.")
    count = 0
    for artifact_id, artifacts in modules.items():
        target_dir = out.joinpath(*group.split("."), artifact_id, version)
        target_dir.mkdir(parents=True, exist_ok=True)
        for artifact in artifacts:
            target = target_dir / artifact.file_name(version)
            shutil.copyfile(artifact.path, target)
            signer.sign(target)
            write_checksums(target)
            count += 1
    print(f"Signed {count} files of {len(modules)} modules of {group}:{version}.")
    return count


# --- The release bundle -------------------------------------------------------------------------


def primary_files(directory: Path) -> List[Path]:
    return sorted(p for p in directory.rglob("*") if p.is_file() and not is_sidecar(p.name))


def check_signed(directory: Path) -> List[Path]:
    """The artifacts of a prepared directory, after it checks their signatures and checksums."""
    files = primary_files(directory)
    if not files:
        raise PublishError(f"{directory} has no artifacts.")
    problems = []
    for path in files:
        relative = path.relative_to(directory)
        if path.name.startswith("maven-metadata.xml"):
            problems.append(f"{relative}: maven-metadata.xml is not part of a release")
            continue
        if not path.with_name(path.name + ".asc").is_file():
            problems.append(f"{relative}: no signature")
        for algorithm in REQUIRED_CHECKSUMS:
            checksum = path.with_name(f"{path.name}.{algorithm}")
            if not checksum.is_file():
                problems.append(f"{relative}: no .{algorithm}")
            elif checksum.read_text(encoding="ascii").strip() != digest(path, algorithm):
                problems.append(f"{relative}: the .{algorithm} does not match the file")
    if problems:
        raise PublishError(f"{directory} is not ready for Central:\n  " + "\n  ".join(problems))
    return files


def bundle(directory: Path, out: Path) -> int:
    files = check_signed(directory)
    for path in files:
        if path.parent.name.endswith("-SNAPSHOT"):
            raise PublishError(f"{path.relative_to(directory)}: a release can't be a -SNAPSHOT.")
    entries = sorted(p for p in directory.rglob("*") if p.is_file())
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in entries:
            info = zipfile.ZipInfo(path.relative_to(directory).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes())
    print(f"Wrote {out}: {len(entries)} files, {len(files)} of them artifacts.")
    return len(entries)


# --- HTTP ---------------------------------------------------------------------------------------


def credentials() -> Tuple[str, str]:
    username = os.environ.get("CENTRAL_USERNAME", "")
    password = os.environ.get("CENTRAL_PASSWORD", "")
    if not username or not password:
        raise PublishError("CENTRAL_USERNAME and CENTRAL_PASSWORD must be set.")
    return username, password


def token(username: str, password: str) -> str:
    return base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")


def request(
    method: str,
    url: str,
    headers: Dict[str, str],
    data: Optional[bytes] = None,
    *,
    missing_ok: bool = False,
    attempts: int = 3,
    timeout: float = 120,
) -> Tuple[int, bytes]:
    """One HTTP request. It tries again after a server error or a network error.

    Returns (404, b"") for a missing file when missing_ok. Any other answer that is not 2xx
    fails with its status and the start of its body.
    """
    for attempt in range(1, attempts + 1):
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            body = error.read()
            if error.code == 404 and missing_ok:
                return 404, b""
            if error.code < 500 or attempt == attempts:
                text = body.decode("utf-8", "replace")[:2000]
                raise PublishError(f"{method} {url}: HTTP {error.code}: {text}") from None
        except (urllib.error.URLError, OSError) as error:
            if attempt == attempts:
                raise PublishError(f"{method} {url}: {error}") from None
        time.sleep(2 ** attempt)
    raise AssertionError("unreachable")


# --- Release through the Central Publisher API --------------------------------------------------


def multipart(field: str, file_name: str, content: bytes) -> Tuple[str, bytes]:
    boundary = "lustro-" + uuid.uuid4().hex
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{field}"; filename="{file_name}"\r\n'
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode("ascii")
    tail = f"\r\n--{boundary}--\r\n".encode("ascii")
    return f"multipart/form-data; boundary={boundary}", head + content + tail


def release(
    bundle_path: Path,
    name: str,
    publishing_type: str,
    central_url: str,
    username: str,
    password: str,
    *,
    timeout: float = 1800,
    interval: float = 10,
    sleep: Callable[[float], None] = time.sleep,
) -> str:
    auth = {"Authorization": "Bearer " + token(username, password)}
    content_type, body = multipart("bundle", bundle_path.name, bundle_path.read_bytes())
    query = urllib.parse.urlencode({"name": name, "publishingType": publishing_type})
    # One attempt only: a second upload of the same version would make a second deployment.
    _, answer = request(
        "POST",
        f"{central_url}/api/v1/publisher/upload?{query}",
        {**auth, "Content-Type": content_type},
        body,
        attempts=1,
        timeout=600,
    )
    deployment = answer.decode("utf-8").strip()
    if not deployment:
        raise PublishError("Central answered the upload without a deployment id.")
    print(f"Uploaded {bundle_path.name} to Central: deployment {deployment} ({publishing_type}).")

    status_url = f"{central_url}/api/v1/publisher/status?" + urllib.parse.urlencode({"id": deployment})
    deadline = time.monotonic() + timeout
    last = None
    while True:
        _, answer = request("POST", status_url, auth, b"")
        status = json.loads(answer.decode("utf-8"))
        state = status.get("deploymentState")
        if state != last:
            print(f"Deployment {deployment}: {state}")
            last = state
        if state in SUCCESS_STATES[publishing_type]:
            for purl in status.get("purls") or []:
                print(f"  {purl}")
            return state
        if state not in WAIT_STATES:
            errors = json.dumps(status.get("errors"), indent=2, sort_keys=True)
            raise PublishError(f"Central did not take deployment {deployment}: {state}\n{errors}")
        if time.monotonic() >= deadline:
            raise PublishError(
                f"Deployment {deployment} is still {state} after {int(timeout)} s: "
                "see https://central.sonatype.com/publishing/deployments."
            )
        sleep(interval)


# --- Snapshot to the snapshot repository --------------------------------------------------------


def metadata_xml(root: ET.Element) -> bytes:
    ET.indent(root, space="  ")
    return b'<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(root, encoding="utf-8") + b"\n"


def sub(parent: ET.Element, tag: str, text: Optional[str] = None) -> ET.Element:
    element = ET.SubElement(parent, tag)
    if text is not None:
        element.text = text
    return element


def version_metadata(
    group: str, artifact_id: str, version: str, timestamp: str, build: int, updated: str,
    files: Iterable[Tuple[str, str]],
) -> bytes:
    """The maven-metadata.xml of a snapshot version, with an entry for each file kind."""
    root = ET.Element("metadata", modelVersion="1.1.0")
    sub(root, "groupId", group)
    sub(root, "artifactId", artifact_id)
    sub(root, "version", version)
    versioning = sub(root, "versioning")
    snapshot = sub(versioning, "snapshot")
    sub(snapshot, "timestamp", timestamp)
    sub(snapshot, "buildNumber", str(build))
    sub(versioning, "lastUpdated", updated)
    entries = sub(versioning, "snapshotVersions")
    value = f"{snapshot_base(version)}-{timestamp}-{build}"
    for classifier, extension in files:
        entry = sub(entries, "snapshotVersion")
        if classifier:
            sub(entry, "classifier", classifier)
        sub(entry, "extension", extension)
        sub(entry, "value", value)
        sub(entry, "updated", updated)
    return metadata_xml(root)


def artifact_metadata(group: str, artifact_id: str, version: str, updated: str, existing: bytes) -> bytes:
    """The maven-metadata.xml of a module, with the versions that it had and this one."""
    versions: List[str] = []
    release_version = None
    if existing:
        try:
            old = ET.fromstring(existing)
        except ET.ParseError as error:
            raise PublishError(f"{artifact_id}: the maven-metadata.xml of the repository: {error}") from None
        versions = [v.text for v in old.findall("versioning/versions/version") if v.text]
        release_version = old.findtext("versioning/release")
    if version not in versions:
        versions.append(version)
    root = ET.Element("metadata")
    sub(root, "groupId", group)
    sub(root, "artifactId", artifact_id)
    versioning = sub(root, "versioning")
    sub(versioning, "latest", version)
    if release_version:
        sub(versioning, "release", release_version)
    listed = sub(versioning, "versions")
    for v in versions:
        sub(listed, "version", v)
    sub(versioning, "lastUpdated", updated)
    return metadata_xml(root)


def snapshot(
    directory: Path,
    group: str,
    version: str,
    repository_url: str,
    username: str,
    password: str,
    *,
    now: Optional[datetime.datetime] = None,
) -> Dict[str, int]:
    if not version.endswith("-SNAPSHOT"):
        raise PublishError(f"{version} is not a -SNAPSHOT version.")
    files = check_signed(directory)
    base = repository_url.rstrip("/") + "/" + "/".join(group.split("."))
    auth = {"Authorization": "Basic " + token(username, password)}
    now = now or datetime.datetime.now(datetime.timezone.utc)
    timestamp, updated = now.strftime("%Y%m%d.%H%M%S"), now.strftime("%Y%m%d%H%M%S")

    modules: Dict[str, List[Path]] = {}
    for path in files:
        artifact_dir, version_dir = path.parent.parent, path.parent
        if version_dir.name != version or artifact_dir.parent != directory.joinpath(*group.split(".")):
            raise PublishError(f"{path.relative_to(directory)}: not a file of {group}:{version}.")
        modules.setdefault(artifact_dir.name, []).append(path)

    # The next build number of each module, from the metadata in the repository.
    builds: Dict[str, int] = {}
    for artifact_id in modules:
        _, existing = request("GET", f"{base}/{artifact_id}/{version}/maven-metadata.xml", auth, missing_ok=True)
        number = 0
        if existing:
            text = ET.fromstring(existing).findtext("versioning/snapshot/buildNumber")
            number = int(text) if text and text.strip().isdigit() else 0
        builds[artifact_id] = number + 1

    def put(url: str, content: bytes) -> None:
        request("PUT", url, {**auth, "Content-Type": "application/octet-stream"}, content)

    def put_with_checksums(url: str, content: bytes) -> None:
        put(url, content)
        for algorithm in CHECKSUMS:
            put(f"{url}.{algorithm}", hashlib.new(algorithm, content).hexdigest().encode("ascii"))

    # First the files of every module, then the metadata, which makes them the new snapshot.
    kinds: Dict[str, List[Tuple[str, str]]] = {}
    for artifact_id, paths in modules.items():
        value = f"{snapshot_base(version)}-{timestamp}-{builds[artifact_id]}"
        kinds[artifact_id] = []
        for path in paths:
            split = split_file_name(path.name, artifact_id, version)
            if split is None:
                raise PublishError(f"{path.relative_to(directory)}: not a file of {artifact_id} {version}.")
            classifier, extension = split
            kinds[artifact_id] += [(classifier, extension), (classifier, extension + ".asc")]
            remote = f"{base}/{artifact_id}/{version}/" + Artifact(artifact_id, classifier, extension, path).file_name(value)
            for local, suffix in [(path, "")] + [
                (path.with_name(path.name + s), s) for s in SIDECAR_SUFFIXES
            ]:
                if local.is_file():
                    put(remote + suffix, local.read_bytes())
        print(f"Uploaded {artifact_id} {value}.")
    for artifact_id in modules:
        put_with_checksums(
            f"{base}/{artifact_id}/{version}/maven-metadata.xml",
            version_metadata(group, artifact_id, version, timestamp, builds[artifact_id], updated, kinds[artifact_id]),
        )
    for artifact_id in modules:
        url = f"{base}/{artifact_id}/maven-metadata.xml"
        _, existing = request("GET", url, auth, missing_ok=True)
        put_with_checksums(url, artifact_metadata(group, artifact_id, version, updated, existing))
    print(f"Published {len(modules)} modules of {group}:{version} to {repository_url}.")
    return builds


# --- Command line -------------------------------------------------------------------------------


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)

    p = commands.add_parser("prepare", help="sign the staged publications and write their checksums")
    p.add_argument("--repo", type=Path, required=True, help="the staging repository of Gradle")
    p.add_argument("--out", type=Path, required=True, help="a new directory for the signed files")
    p.add_argument("--properties", type=Path, required=True, help="gradle.properties, for GROUP and VERSION_NAME")
    p.add_argument("--expect-version", help="fail unless VERSION_NAME is this version, such as the tag's")

    b = commands.add_parser("bundle", help="zip a prepared release directory for the Publisher API")
    b.add_argument("--dir", type=Path, required=True)
    b.add_argument("--out", type=Path, required=True)

    r = commands.add_parser("release", help="upload a bundle and wait until Central has validated it")
    r.add_argument("--bundle", type=Path, required=True)
    r.add_argument("--name", help="the deployment's name on the Central Portal")
    r.add_argument("--publishing-type", choices=sorted(SUCCESS_STATES), default="AUTOMATIC")
    r.add_argument("--central-url", default=CENTRAL_URL)
    r.add_argument("--timeout", type=float, default=1800, help="seconds to wait for the validation")
    r.add_argument("--interval", type=float, default=10, help="seconds between two status requests")

    s = commands.add_parser("snapshot", help="upload a prepared -SNAPSHOT directory with its metadata")
    s.add_argument("--dir", type=Path, required=True)
    s.add_argument("--properties", type=Path, required=True, help="gradle.properties, for GROUP and VERSION_NAME")
    s.add_argument("--repository-url", default=SNAPSHOT_REPOSITORY_URL)

    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            group, version = coordinates(args.properties)
            if args.expect_version is not None and args.expect_version != version:
                raise PublishError(f"VERSION_NAME is {version}, not {args.expect_version}.")
            key = os.environ.get("SIGNING_KEY", "")
            with Signer(key, os.environ.get("SIGNING_KEY_ID"), os.environ.get("SIGNING_KEY_PASSWORD")) as signer:
                prepare(args.repo, args.out, group, version, signer)
        elif args.command == "bundle":
            bundle(args.dir, args.out)
        elif args.command == "release":
            username, password = credentials()
            release(
                args.bundle,
                args.name or args.bundle.stem,
                args.publishing_type,
                args.central_url.rstrip("/"),
                username,
                password,
                timeout=args.timeout,
                interval=args.interval,
            )
        elif args.command == "snapshot":
            group, version = coordinates(args.properties)
            username, password = credentials()
            snapshot(args.dir, group, version, args.repository_url, username, password)
    except PublishError as error:
        print(f"error: {error}", file=sys.stderr)
        if os.environ.get("GITHUB_ACTIONS"):
            print(f"::error::{str(error).splitlines()[0]}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
