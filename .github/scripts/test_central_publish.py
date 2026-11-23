"""Tests of central_publish.py: a throwaway gpg key, and a fake Central server on localhost.

CI runs them in the job of the CLI's tests: python -m pytest -q .github/scripts
"""

from __future__ import annotations

import base64
import datetime
import email
import email.policy
import hashlib
import io
import json
import shutil
import subprocess
import sys
import tempfile
import threading
import xml.etree.ElementTree as ET
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from urllib.parse import parse_qs, urlparse

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import central_publish as cp  # noqa: E402

GROUP = "io.github.twinsen81"
GROUP_PATH = GROUP.replace(".", "/")
PASSPHRASE = "throwaway passphrase"

needs_gpg = pytest.mark.skipif(shutil.which("gpg") is None, reason="gpg is not installed")


# --- A throwaway key ----------------------------------------------------------------------------


def gnupg_home(prefix: str) -> Path:
    """A new GnuPG home with a short path: the socket of gpg-agent in it has a length limit."""
    return Path(tempfile.mkdtemp(prefix=prefix))


def remove_gnupg_home(home: Path) -> None:
    subprocess.run(["gpgconf", "--homedir", str(home), "--kill", "all"], capture_output=True, check=False)
    shutil.rmtree(home, ignore_errors=True)


def gpg(home: Path, *args: str, stdin: bytes = b"") -> subprocess.CompletedProcess:
    return subprocess.run(
        ["gpg", "--batch", "--no-tty", "--homedir", str(home), *args],
        input=stdin,
        capture_output=True,
        check=True,
    )


class Key:
    def __init__(self, signing_subkey: bool):
        self.home = home = gnupg_home("lustro-test-key-")
        self.verify_home: Optional[Path] = None
        gpg(home, "--pinentry-mode", "loopback", "--passphrase", PASSPHRASE,
            "--quick-gen-key", "Lustro Test <test@example.invalid>", "ed25519", "sign,cert", "never")
        self.fingerprint = self._fingerprints("sec")[0]
        if signing_subkey:
            gpg(home, "--pinentry-mode", "loopback", "--passphrase", PASSPHRASE,
                "--quick-add-key", self.fingerprint, "ed25519", "sign", "never")
        self.armored = gpg(home, "--pinentry-mode", "loopback", "--passphrase", PASSPHRASE,
                           "--armor", "--export-secret-keys", self.fingerprint).stdout.decode()
        self.public = gpg(home, "--armor", "--export", self.fingerprint).stdout

    def _fingerprints(self, record: str) -> List[str]:
        lines = gpg(self.home, "--list-secret-keys", "--with-colons").stdout.decode().splitlines()
        found = []
        for index, line in enumerate(lines):
            if line.startswith(record + ":"):
                found.append(next(l.split(":")[9] for l in lines[index + 1:] if l.startswith("fpr:")))
        return found

    def stripped(self) -> str:
        """The key as the vanniktech docs make it: no armor lines, no checksum, no newlines."""
        lines = self.armored.splitlines()
        return "".join(l for l in lines if "--" not in l and not (l.startswith("=") and len(l) > 1))

    def verify(self, path: Path) -> str:
        """The fingerprint of the key that made the signature of path, after gpg checked it.

        The check uses a GnuPG home with the public key only.
        """
        if self.verify_home is None:
            self.verify_home = gnupg_home("lustro-test-verify-")
            gpg(self.verify_home, "--import", stdin=self.public)
        status = gpg(self.verify_home, "--status-fd", "1", "--verify", f"{path}.asc", str(path)).stdout.decode()
        valid = [l for l in status.splitlines() if l.startswith("[GNUPG:] VALIDSIG ")]
        assert valid, status
        return valid[0].split()[2]

    def remove(self) -> None:
        for home in (self.home, self.verify_home):
            if home is not None:
                remove_gnupg_home(home)


@pytest.fixture(scope="module")
def key():
    if shutil.which("gpg") is None:
        pytest.skip("gpg is not installed")
    made = Key(signing_subkey=False)
    yield made
    made.remove()


@pytest.fixture(scope="module")
def key_with_subkey():
    if shutil.which("gpg") is None:
        pytest.skip("gpg is not installed")
    made = Key(signing_subkey=True)
    yield made
    made.remove()


# --- A staging repository as Gradle writes it ---------------------------------------------------


def pom(artifact_id: str, version: str, packaging: str, drop: Tuple[str, ...] = ()) -> bytes:
    parts = {
        "name": "<name>Lustro</name>",
        "description": "<description>A debugging library.</description>",
        "url": "<url>https://github.com/Twinsen81/Lustro</url>",
        "licenses": "<licenses><license><name>Apache-2.0</name>"
        "<url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses>",
        "developers": "<developers><developer><id>twinsen81</id></developer></developers>",
        "scm": "<scm><connection>scm:git:git://github.com/Twinsen81/Lustro.git</connection>"
        "<developerConnection>scm:git:ssh://git@github.com/Twinsen81/Lustro.git</developerConnection>"
        "<url>https://github.com/Twinsen81/Lustro</url></scm>",
    }
    body = "".join(v for k, v in parts.items() if k not in drop)
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        f"<modelVersion>4.0.0</modelVersion><groupId>{GROUP}</groupId>"
        f"<artifactId>{artifact_id}</artifactId><version>{version}</version>"
        f"<packaging>{packaging}</packaging>{body}</project>\n"
    ).encode()


MODULES = {"lustro": "aar", "lustro-api": "jar"}


def stage(
    repo: Path,
    version: str,
    modules: Dict[str, str] = MODULES,
    *,
    drop_files: Tuple[str, ...] = (),
    drop_pom: Tuple[str, ...] = (),
    builds: Tuple[str, ...] = ("20261101.101010-1", "20261123.120000-2"),
) -> Path:
    """Writes what publishAllPublicationsToStagingRepository writes, checksums and metadata included.

    For a -SNAPSHOT version, Gradle writes timestamped files, and a staging repository that was
    not deleted keeps the files of the builds before: `builds` are those, the last one current.
    """
    for artifact_id, packaging in modules.items():
        version_dir = repo / GROUP_PATH / artifact_id / version
        version_dir.mkdir(parents=True, exist_ok=True)
        values = [f"{cp.snapshot_base(version)}-{b}" for b in builds] if version.endswith("-SNAPSHOT") else [version]
        for value in values:
            files = {
                f"{artifact_id}-{value}.{packaging}": f"{artifact_id} {value} {packaging}".encode(),
                f"{artifact_id}-{value}.pom": pom(artifact_id, version, packaging, drop_pom),
                f"{artifact_id}-{value}.module": json.dumps({"url": f"{artifact_id}-{version}.{packaging}"}).encode(),
                f"{artifact_id}-{value}-sources.jar": f"{artifact_id} sources".encode(),
                f"{artifact_id}-{value}-javadoc.jar": f"{artifact_id} javadoc".encode(),
            }
            for name, content in files.items():
                if any(name.endswith(d) for d in drop_files):
                    continue
                (version_dir / name).write_bytes(content)
                for algorithm in ("md5", "sha1", "sha256", "sha512"):
                    (version_dir / f"{name}.{algorithm}").write_text(hashlib.new(algorithm, content).hexdigest())
        if version.endswith("-SNAPSHOT"):
            timestamp, build = builds[-1].rsplit("-", 1)
            (version_dir / "maven-metadata.xml").write_text(
                f"<metadata><groupId>{GROUP}</groupId><artifactId>{artifact_id}</artifactId>"
                f"<versioning><snapshot><timestamp>{timestamp}</timestamp><buildNumber>{build}</buildNumber>"
                f"</snapshot></versioning><version>{version}</version></metadata>"
            )
        (repo / GROUP_PATH / artifact_id / "maven-metadata.xml").write_text("<metadata/>")
    return repo


def properties(tmp: Path, version: str) -> Path:
    path = tmp / "gradle.properties"
    path.write_text(f"# Lustro\nGROUP={GROUP}\nVERSION_NAME={version}\nPOM_NAME=Lustro\n")
    return path


def signer(key: Key, key_id: Optional[str] = None, form: str = "armored") -> cp.Signer:
    text = key.armored if form == "armored" else key.stripped()
    return cp.Signer(text, key_id, PASSPHRASE)


def required_files(artifact_id: str, version: str, packaging: str) -> List[str]:
    names = [f"{artifact_id}-{version}{s}" for s in (f".{packaging}", ".pom", ".module", "-sources.jar", "-javadoc.jar")]
    return [n + s for n in names for s in ("", ".asc", ".md5", ".sha1")]


# --- prepare ------------------------------------------------------------------------------------


@needs_gpg
def test_prepare_signs_each_artifact_with_the_primary_key(signed_release, key):
    out = signed_release
    for artifact_id, packaging in MODULES.items():
        version_dir = out / GROUP_PATH / artifact_id / "0.1.0"
        names = {p.name for p in version_dir.iterdir()}
        assert set(required_files(artifact_id, "0.1.0", packaging)) <= names
        for path in version_dir.iterdir():
            if cp.is_sidecar(path.name):
                continue
            assert key.verify(path) == key.fingerprint
            content = path.read_bytes()
            for algorithm in cp.CHECKSUMS:
                assert Path(f"{path}.{algorithm}").read_text() == hashlib.new(algorithm, content).hexdigest()


@needs_gpg
def test_a_key_without_its_armor_lines_signs_too(tmp_path, key):
    repo = stage(tmp_path / "staging", "0.1.0", {"lustro-api": "jar"})
    with signer(key, form="stripped") as s:
        assert cp.prepare(repo, tmp_path / "out", GROUP, "0.1.0", s) == 5
    jar = tmp_path / "out" / GROUP_PATH / "lustro-api/0.1.0/lustro-api-0.1.0.jar"
    assert key.verify(jar) == key.fingerprint


@needs_gpg
def test_prepare_signs_with_the_primary_key_when_the_key_has_a_signing_subkey(tmp_path, key_with_subkey):
    # gpg would take the newest signing subkey, and Central verifies only with a primary key.
    repo = stage(tmp_path / "staging", "0.1.0", {"lustro-api": "jar"})
    with signer(key_with_subkey) as s:
        cp.prepare(repo, tmp_path / "out", GROUP, "0.1.0", s)
    jar = tmp_path / "out" / GROUP_PATH / "lustro-api/0.1.0/lustro-api-0.1.0.jar"
    assert key_with_subkey.verify(jar) == key_with_subkey.fingerprint


@needs_gpg
def test_prepare_signs_with_the_key_that_the_key_id_names(tmp_path, key):
    repo = stage(tmp_path / "staging", "0.1.0", {"lustro-api": "jar"})
    with signer(key, key_id=key.fingerprint[-8:]) as s:
        cp.prepare(repo, tmp_path / "out", GROUP, "0.1.0", s)
    jar = tmp_path / "out" / GROUP_PATH / "lustro-api/0.1.0/lustro-api-0.1.0.jar"
    assert key.verify(jar) == key.fingerprint


@needs_gpg
def test_signing_fails_with_a_wrong_passphrase_and_deletes_its_gnupg_home(tmp_path, key):
    repo = stage(tmp_path / "staging", "0.1.0", {"lustro-api": "jar"})
    s = cp.Signer(key.armored, None, "not the passphrase")
    with pytest.raises(cp.PublishError, match="could not sign"):
        with s:
            home = Path(s._home)
            cp.prepare(repo, tmp_path / "out", GROUP, "0.1.0", s)
    assert not home.exists()


def test_a_key_that_is_not_a_key_fails():
    with pytest.raises(cp.PublishError, match="neither"):
        cp.key_bytes("not a key!")
    with pytest.raises(cp.PublishError, match="empty"):
        cp.key_bytes("  ")


@needs_gpg
def test_prepare_keeps_the_snapshot_names_of_the_last_build(signed_snapshot):
    out = signed_snapshot
    names = {p.name for p in (out / GROUP_PATH / "lustro/0.2.0-SNAPSHOT").iterdir()}
    assert set(required_files("lustro", "0.2.0-SNAPSHOT", "aar")) <= names
    aar = out / GROUP_PATH / "lustro/0.2.0-SNAPSHOT/lustro-0.2.0-SNAPSHOT.aar"
    assert aar.read_bytes() == b"lustro 0.2.0-20261123.120000-2 aar"


@pytest.mark.parametrize(
    "drop_files, drop_pom, message",
    [
        (("-javadoc.jar",), (), "lustro-api: no -javadoc.jar"),
        (("-sources.jar",), (), "lustro: no -sources.jar"),
        ((".pom",), (), "lustro: no POM"),
        ((), ("description",), "lustro: the POM has no description"),
        ((), ("scm",), "lustro-api: the POM has no scm/url"),
        ((), ("licenses",), "the POM has no licenses/license/name"),
    ],
)
def test_prepare_refuses_what_central_would_refuse(tmp_path, drop_files, drop_pom, message):
    repo = stage(tmp_path / "staging", "0.1.0", drop_files=drop_files, drop_pom=drop_pom)
    with pytest.raises(cp.PublishError, match=message):
        cp.prepare(repo, tmp_path / "out", GROUP, "0.1.0", signer=None)  # type: ignore[arg-type]


@pytest.mark.parametrize(
    "extra, message",
    [
        ("com/example/evil/1.0/evil-1.0.jar", "not in a module of io.github.twinsen81"),
        (f"{GROUP_PATH}/lustro/0.0.9/lustro-0.0.9.aar", "version 0.0.9, not 0.1.0"),
        (f"{GROUP_PATH}/lustro/stray.txt", "not in a version directory"),
        (f"{GROUP_PATH}/lustro/0.1.0/other-0.1.0.jar", "not a file of lustro 0.1.0"),
    ],
)
def test_prepare_refuses_a_file_of_another_group_or_version(tmp_path, extra, message):
    repo = stage(tmp_path / "staging", "0.1.0")
    (repo / extra).parent.mkdir(parents=True, exist_ok=True)
    (repo / extra).write_bytes(b"x")
    with pytest.raises(cp.PublishError, match=message):
        cp.prepare(repo, tmp_path / "out", GROUP, "0.1.0", signer=None)  # type: ignore[arg-type]


@pytest.mark.parametrize("link", ["lustro-0.1.0-sources.jar", "../../lustro-api/0.1.0"])
def test_prepare_refuses_a_symbolic_link(tmp_path, link):
    # prepare has the signing key in its environment: a link could copy it into a published file.
    repo = stage(tmp_path / "staging", "0.1.0")
    target = repo / GROUP_PATH / "lustro/0.1.0" / link
    if target.is_dir():
        shutil.rmtree(target)
        target.symlink_to(repo / GROUP_PATH / "lustro/0.1.0", target_is_directory=True)
    else:
        target.unlink()
        target.symlink_to("/etc/hosts")
    with pytest.raises(cp.PublishError, match="has symbolic links"):
        cp.prepare(repo, tmp_path / "out", GROUP, "0.1.0", signer=None)  # type: ignore[arg-type]


def test_prepare_refuses_a_module_without_files(tmp_path):
    repo = stage(tmp_path / "staging", "0.1.0")
    for path in (repo / GROUP_PATH / "lustro/0.1.0").iterdir():
        path.unlink()
    with pytest.raises(cp.PublishError, match="has no files of lustro 0.1.0"):
        cp.prepare(repo, tmp_path / "out", GROUP, "0.1.0", signer=None)  # type: ignore[arg-type]


def test_prepare_command_refuses_a_version_that_is_not_the_tags(tmp_path, capsys, monkeypatch):
    monkeypatch.setenv("SIGNING_KEY", "unused")
    code = cp.main(["prepare", "--repo", str(tmp_path), "--out", str(tmp_path / "out"),
                    "--properties", str(properties(tmp_path, "0.1.0")), "--expect-version", "0.1.1"])
    assert code == 1
    assert "VERSION_NAME is 0.1.0, not 0.1.1" in capsys.readouterr().err


# --- A fake Central -----------------------------------------------------------------------------


class FakeCentral:
    """The Publisher API and the snapshot repository, in memory, on a free port of localhost."""

    def __init__(self, username: str, password: str, states: List[str], errors=None):
        self.bearer = "Bearer " + base64.b64encode(f"{username}:{password}".encode()).decode()
        self.basic = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode()
        self.states = list(states)
        self.errors = errors
        self.bundles: List[Tuple[Dict[str, List[str]], str, bytes]] = []
        self.status_requests = 0
        self.files: Dict[str, bytes] = {}
        self.puts: List[str] = []
        self.unauthorized: List[str] = []
        central = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def _answer(self, code: int, body: bytes = b"", content_type: str = "text/plain") -> None:
                self.send_response(code)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _body(self) -> bytes:
                return self.rfile.read(int(self.headers.get("Content-Length") or 0))

            def _authorized(self, expected: str) -> bool:
                if self.headers.get("Authorization") != expected:
                    central.unauthorized.append(self.path)
                    self._answer(401, b"unauthorized")
                    return False
                return True

            def do_POST(self):
                url = urlparse(self.path)
                body = self._body()
                if not self._authorized(central.bearer):
                    return
                if url.path == "/api/v1/publisher/upload":
                    message = email.message_from_bytes(
                        b"Content-Type: " + self.headers["Content-Type"].encode() + b"\r\n\r\n" + body,
                        policy=email.policy.HTTP,
                    )
                    parts = list(message.iter_parts())
                    assert len(parts) == 1
                    part = parts[0]
                    assert part.get_param("name", header="content-disposition") == "bundle"
                    central.bundles.append((parse_qs(url.query), part.get_filename(), part.get_payload(decode=True)))
                    self._answer(201, b"deployment-1234")
                elif url.path == "/api/v1/publisher/status":
                    assert parse_qs(url.query) == {"id": ["deployment-1234"]}
                    central.status_requests += 1
                    state = central.states.pop(0) if len(central.states) > 1 else central.states[0]
                    status = {"deploymentId": "deployment-1234", "deploymentState": state,
                              "purls": [f"pkg:maven/{GROUP}/lustro@0.1.0"]}
                    if central.errors is not None:
                        status["errors"] = central.errors
                    self._answer(200, json.dumps(status).encode(), "application/json")
                else:
                    self._answer(404)

            def do_GET(self):
                if not self._authorized(central.basic):
                    return
                path = urlparse(self.path).path
                if path in central.files:
                    self._answer(200, central.files[path], "application/xml")
                else:
                    self._answer(404, b"not found")

            def do_PUT(self):
                body = self._body()
                if not self._authorized(central.basic):
                    return
                path = urlparse(self.path).path
                central.files[path] = body
                central.puts.append(path)
                self._answer(201)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self) -> "FakeCentral":
        self.thread.start()
        return self

    def __exit__(self, *exc) -> None:
        self.server.shutdown()
        self.server.server_close()


def prepared(tmp_path_factory, key: Key, version: str) -> Path:
    tmp = tmp_path_factory.mktemp("prepared")
    repo = stage(tmp / "staging", version)
    with signer(key) as s:
        cp.prepare(repo, tmp / "out", GROUP, version, s)
    return tmp / "out"


# Signed once for the module: each test takes a copy that it can change.
@pytest.fixture(scope="module")
def signed_release(tmp_path_factory, key) -> Path:
    return prepared(tmp_path_factory, key, "0.1.0")


@pytest.fixture(scope="module")
def signed_snapshot(tmp_path_factory, key) -> Path:
    return prepared(tmp_path_factory, key, "0.2.0-SNAPSHOT")


@pytest.fixture
def prepared_release(tmp_path, signed_release) -> Path:
    return Path(shutil.copytree(signed_release, tmp_path / "release"))


@pytest.fixture
def prepared_snapshot(tmp_path, signed_snapshot) -> Path:
    return Path(shutil.copytree(signed_snapshot, tmp_path / "snapshot"))


# --- bundle and release -------------------------------------------------------------------------


@needs_gpg
def test_release_uploads_a_bundle_with_every_file_that_central_requires(tmp_path, prepared_release, capsys):
    bundle = tmp_path / "lustro-0.1.0.zip"
    cp.bundle(prepared_release, bundle)
    sleeps = []
    with FakeCentral("user", "pass word", ["PENDING", "VALIDATING", "VALIDATED", "PUBLISHING"]) as central:
        state = cp.release(bundle, "lustro 0.1.0", "AUTOMATIC", central.url, "user", "pass word",
                           interval=7, sleep=sleeps.append)
    assert state == "PUBLISHING"
    assert central.unauthorized == []
    assert central.status_requests == 4 and sleeps == [7, 7, 7]
    [(query, file_name, content)] = central.bundles
    assert query == {"name": ["lustro 0.1.0"], "publishingType": ["AUTOMATIC"]}
    assert file_name == "lustro-0.1.0.zip"
    names = set(zipfile.ZipFile(io.BytesIO(content)).namelist())
    for artifact_id, packaging in MODULES.items():
        for name in required_files(artifact_id, "0.1.0", packaging):
            assert f"{GROUP_PATH}/{artifact_id}/0.1.0/{name}" in names
    assert not any("maven-metadata" in n for n in names)
    assert "deployment-1234: PUBLISHING" in capsys.readouterr().out


@needs_gpg
def test_release_fails_when_central_fails_the_deployment(tmp_path, prepared_release):
    bundle = tmp_path / "b.zip"
    cp.bundle(prepared_release, bundle)
    errors = {"pkg:maven/io.github.twinsen81/lustro@0.1.0": ["Invalid signature"]}
    with FakeCentral("u", "p", ["VALIDATING", "FAILED"], errors) as central:
        with pytest.raises(cp.PublishError, match="(?s)FAILED.*Invalid signature"):
            cp.release(bundle, "b", "AUTOMATIC", central.url, "u", "p", sleep=lambda _: None)


@needs_gpg
def test_release_stops_waiting_after_the_timeout(tmp_path, prepared_release):
    bundle = tmp_path / "b.zip"
    cp.bundle(prepared_release, bundle)
    with FakeCentral("u", "p", ["VALIDATING"]) as central:
        with pytest.raises(cp.PublishError, match="still VALIDATING"):
            cp.release(bundle, "b", "AUTOMATIC", central.url, "u", "p", timeout=0, sleep=lambda _: None)


@needs_gpg
def test_a_user_managed_release_waits_only_for_the_validation(tmp_path, prepared_release):
    bundle = tmp_path / "b.zip"
    cp.bundle(prepared_release, bundle)
    with FakeCentral("u", "p", ["VALIDATING", "VALIDATED"]) as central:
        assert cp.release(bundle, "b", "USER_MANAGED", central.url, "u", "p", sleep=lambda _: None) == "VALIDATED"
    assert central.bundles[0][0]["publishingType"] == ["USER_MANAGED"]


@needs_gpg
def test_bundle_refuses_a_file_without_its_signature_or_with_a_wrong_checksum(tmp_path, prepared_release):
    jar = prepared_release / GROUP_PATH / "lustro-api/0.1.0/lustro-api-0.1.0.jar"
    Path(f"{jar}.asc").unlink()
    Path(f"{jar.with_name('lustro-api-0.1.0.pom')}.sha1").write_text("0" * 40)
    with pytest.raises(cp.PublishError) as error:
        cp.bundle(prepared_release, tmp_path / "b.zip")
    assert "lustro-api-0.1.0.jar: no signature" in str(error.value)
    assert "lustro-api-0.1.0.pom: the .sha1 does not match the file" in str(error.value)


def test_the_upload_commands_need_the_credentials(tmp_path, capsys, monkeypatch):
    monkeypatch.delenv("CENTRAL_USERNAME", raising=False)
    monkeypatch.delenv("CENTRAL_PASSWORD", raising=False)
    assert cp.main(["release", "--bundle", str(tmp_path / "b.zip")]) == 1
    assert "CENTRAL_USERNAME and CENTRAL_PASSWORD must be set" in capsys.readouterr().err


# --- snapshot -----------------------------------------------------------------------------------


NOW = datetime.datetime(2026, 11, 23, 14, 5, 9, tzinfo=datetime.timezone.utc)
REPO_PATH = "/repository/maven-snapshots"


@needs_gpg
def test_snapshot_uploads_timestamped_files_and_then_the_metadata(prepared_snapshot):
    base = f"{REPO_PATH}/{GROUP_PATH}"
    with FakeCentral("user", "pw", ["PENDING"]) as central:
        # The repository has build 4 of lustro and its versions; lustro-api is new.
        central.files[f"{base}/lustro/0.2.0-SNAPSHOT/maven-metadata.xml"] = (
            b"<metadata><versioning><snapshot><timestamp>20261120.010101</timestamp>"
            b"<buildNumber>4</buildNumber></snapshot></versioning></metadata>"
        )
        central.files[f"{base}/lustro/maven-metadata.xml"] = (
            b"<metadata><versioning><latest>0.1.1-SNAPSHOT</latest><versions>"
            b"<version>0.1.1-SNAPSHOT</version></versions></versioning></metadata>"
        )
        builds = cp.snapshot(prepared_snapshot, GROUP, "0.2.0-SNAPSHOT", central.url + REPO_PATH + "/",
                             "user", "pw", now=NOW)
    assert builds == {"lustro": 5, "lustro-api": 1}
    assert central.unauthorized == []

    aar = f"{base}/lustro/0.2.0-SNAPSHOT/lustro-0.2.0-20261123.140509-5.aar"
    assert central.files[aar] == b"lustro 0.2.0-20261123.120000-2 aar"
    for suffix in (".asc", ".md5", ".sha1", ".sha256", ".sha512"):
        assert aar + suffix in central.files
    assert f"{base}/lustro-api/0.2.0-SNAPSHOT/lustro-api-0.2.0-20261123.140509-1-javadoc.jar.asc" in central.files

    # Every file first, then the metadata of the versions, then the metadata of the modules.
    metadata = [i for i, p in enumerate(central.puts) if "maven-metadata.xml" in p]
    assert metadata and min(metadata) > max(i for i, p in enumerate(central.puts) if "maven-metadata.xml" not in p)
    assert all("/0.2.0-SNAPSHOT/maven-metadata.xml" in p for p in central.puts[metadata[0]:-10])
    assert {p.rsplit("/", 2)[1] for p in central.puts[-10:]} == {"lustro", "lustro-api"}
    assert all("/maven-metadata.xml" in p and "SNAPSHOT" not in p for p in central.puts[-10:])

    version_meta = ET.fromstring(central.files[f"{base}/lustro/0.2.0-SNAPSHOT/maven-metadata.xml"])
    assert version_meta.findtext("version") == "0.2.0-SNAPSHOT"
    assert version_meta.findtext("versioning/snapshot/timestamp") == "20261123.140509"
    assert version_meta.findtext("versioning/snapshot/buildNumber") == "5"
    assert version_meta.findtext("versioning/lastUpdated") == "20261123140509"
    entries = {
        (e.findtext("classifier") or "", e.findtext("extension")): e.findtext("value")
        for e in version_meta.findall("versioning/snapshotVersions/snapshotVersion")
    }
    assert entries[("", "aar")] == "0.2.0-20261123.140509-5"
    assert entries[("sources", "jar")] == "0.2.0-20261123.140509-5"
    assert entries[("", "pom")] == entries[("", "module")] == entries[("javadoc", "jar.asc")]
    content = central.files[f"{base}/lustro/0.2.0-SNAPSHOT/maven-metadata.xml"]
    assert central.files[f"{base}/lustro/0.2.0-SNAPSHOT/maven-metadata.xml.sha1"] == hashlib.sha1(content).hexdigest().encode()

    module_meta = ET.fromstring(central.files[f"{base}/lustro/maven-metadata.xml"])
    assert [v.text for v in module_meta.findall("versioning/versions/version")] == ["0.1.1-SNAPSHOT", "0.2.0-SNAPSHOT"]
    assert module_meta.findtext("versioning/latest") == "0.2.0-SNAPSHOT"
    new_module = ET.fromstring(central.files[f"{base}/lustro-api/maven-metadata.xml"])
    assert [v.text for v in new_module.findall("versioning/versions/version")] == ["0.2.0-SNAPSHOT"]


@needs_gpg
def test_snapshot_refuses_a_release_version(prepared_release):
    with pytest.raises(cp.PublishError, match="not a -SNAPSHOT version"):
        cp.snapshot(prepared_release, GROUP, "0.1.0", "http://127.0.0.1:9/", "u", "p")


@needs_gpg
def test_bundle_refuses_a_snapshot(tmp_path, prepared_snapshot):
    with pytest.raises(cp.PublishError, match="a release can't be a -SNAPSHOT"):
        cp.bundle(prepared_snapshot, tmp_path / "b.zip")


@needs_gpg
def test_snapshot_fails_on_a_refused_upload(prepared_snapshot):
    with FakeCentral("user", "pw", ["PENDING"]) as central:
        with pytest.raises(cp.PublishError, match="HTTP 401"):
            cp.snapshot(prepared_snapshot, GROUP, "0.2.0-SNAPSHOT", central.url + REPO_PATH, "user", "wrong")
    assert not any("maven-metadata" in p for p in central.puts)
