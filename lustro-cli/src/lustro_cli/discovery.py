"""Endpoint and token discovery for the Lustro CLI.

Resolution order (first that yields a value wins, per field):

1. Explicit ``--token`` / ``--host`` / ``--port`` flags.
2. ``LUSTRO_TOKEN`` environment variable (token only).
3. The ``LustroToken`` logcat ready-line: ``adb [-s <serial>] logcat -d -s
   LustroToken`` parsed for the most recent
   ``Lustro ready endpoint=http://<host>:<port> token=<token>`` (this is the
   first source for host/port/token, including a fallback port).
4. (best effort) ``adb [-s <serial>] shell run-as <pkg> cat`` of the app's
   private ``lustro_debug`` prefs, which hold the token and, from the app's
   last bind, its host and port. ``<pkg>`` is ``--package``, or else the app in
   the foreground, because Lustro listens only while its app is in the
   foreground. This is what finds the app on a device that drops Info logs
   (``log.tag`` set to ``E``, for example), which drops the ready line.

If none of these determine the endpoint/token, a clear, actionable error is
raised. :func:`diagnose` first looks for the cause, with the exit code of each:
no device, an app that is not installed, an installed build without the Lustro
runtime, or an app that is not running or not in the foreground. A token from
the ready line can be stale, when the app was installed again and the device
dropped the new line; :func:`token_from_prefs` reads the current one, for a
client to retry with after a 401.

The port that these give is the app's port. On a device, the CLI reaches it
through an ``adb forward``, whose local port can differ: :func:`forwarded_ports`
finds it in ``adb forward --list``.
"""

from __future__ import annotations

import os
import re
import shlex
import subprocess
from typing import List, NamedTuple, Optional, Tuple

from . import exitcodes

# Matches the single machine-parseable line Lustro logs at tag LustroToken:
#   Lustro ready endpoint=http://127.0.0.1:8080 token=AbC123...
# Tolerant of a logcat prefix (date/pid/tag) before the message.
_READY_LINE = re.compile(
    r"Lustro\s+ready\s+endpoint=(?P<scheme>https?)://(?P<host>[^\s:/]+):(?P<port>\d+)\s+token=(?P<token>\S+)"
)

# One ``adb forward --list`` line: ``<serial> tcp:<local port> tcp:<device port>``.
_FORWARD_LINE = re.compile(r"^(?P<serial>\S+)\s+tcp:(?P<local>\d+)\s+tcp:(?P<remote>\d+)\s*$")

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8080

# Hosts that the computer reaches through an adb forward. An app that binds
# 0.0.0.0 on the device listens on its loopback too.
LOOPBACK_HOSTS = ("127.0.0.1", "localhost", "::1", "0.0.0.0")


class Endpoint(NamedTuple):
    """A resolved Lustro endpoint."""

    host: str
    port: int
    token: Optional[str]
    scheme: str = "http"

    @property
    def base_url(self) -> str:
        return "{}://{}:{}".format(self.scheme, self.host, self.port)


class DiscoveryError(Exception):
    """Raised when the endpoint/token cannot be determined, or the app cannot be
    reached. ``exit_code`` is the code of the cause, from :mod:`exitcodes`."""

    def __init__(self, message: str, exit_code: int = exitcodes.NOT_REACHABLE) -> None:
        super().__init__(message)
        self.exit_code = exit_code


def parse_ready_line(text: str) -> Optional[Endpoint]:
    """Parse the *most recent* ``Lustro ready ...`` line from a logcat dump.

    Returns ``None`` if no ready line is present. The last match wins so that a
    rebind (e.g. onto a fallback port) supersedes earlier lines.
    """
    last: Optional[Endpoint] = None
    for match in _READY_LINE.finditer(text):
        last = Endpoint(
            host=match.group("host"),
            port=int(match.group("port")),
            token=match.group("token"),
            scheme=match.group("scheme"),
        )
    return last


def _adb_base(device: Optional[str]) -> List[str]:
    cmd = ["adb"]
    if device:
        cmd += ["-s", device]
    return cmd


def _run(cmd: List[str], timeout: float = 10.0) -> Optional[str]:
    """Run a command, returning stdout text or ``None`` on any failure.

    Failures (missing adb, no device, non-zero exit) are swallowed so discovery
    can fall through to the next source.
    """
    try:
        proc = subprocess.run(
            cmd,
            # Not the CLI's stdin: for `lustro mcp` it carries the client's messages,
            # and `adb shell` can forward its stdin to the device.
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if proc.returncode != 0:
        return None
    return proc.stdout.decode("utf-8", "replace")


# The status of a command that could not start, as a shell gives it.
_NOT_FOUND = 127


def _run_status(cmd: List[str], timeout: float = 10.0) -> Tuple[int, str]:
    """Run a command, returning its exit status and its stdout and stderr together.

    The status is 127 when the command is not installed, and -1 when it could not
    run for another reason or did not finish in time.
    """
    try:
        proc = subprocess.run(
            cmd,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError:
        return _NOT_FOUND, ""
    except (OSError, subprocess.SubprocessError):
        return -1, ""
    return proc.returncode, proc.stdout.decode("utf-8", "replace")


def discover_from_logcat(device: Optional[str] = None) -> Optional[Endpoint]:
    """Discover the endpoint+token by dumping the ``LustroToken`` logcat buffer."""
    out = _run(_adb_base(device) + ["logcat", "-d", "-s", "LustroToken"])
    if out is None:
        return None
    return parse_ready_line(out)


def discover_from_run_as(
    package: str, device: Optional[str] = None
) -> Optional[Endpoint]:
    """Best-effort read of the app's private ``lustro_debug`` prefs.

    Returns the token, with the host and port the app stored at its last bind,
    or the defaults for an app that stored none. Works only on debuggable builds
    where ``run-as <pkg>`` is permitted.
    """
    if not package:
        return None
    prefs_path = "shared_prefs/lustro_debug.xml"
    out = _run(
        _adb_base(device) + ["shell", "run-as", package, "cat", prefs_path]
    )
    if not out:
        return None
    # Prefs XML: <string name="lustro_token">AbC123...</string>
    match = re.search(
        r'<string\s+name="lustro_token">\s*([^<\s]+)\s*</string>', out
    )
    if not match:
        return None
    host = re.search(r'<string\s+name="lustro_host">\s*([^<\s]+)\s*</string>', out)
    port = re.search(r'<int\s+name="lustro_port"\s+value="(\d+)"', out)
    return Endpoint(
        host=host.group(1) if host else DEFAULT_HOST,
        port=int(port.group(1)) if port else DEFAULT_PORT,
        token=match.group(1),
    )


# The resumed activity in ``dumpsys activity activities``, which releases name
# ``topResumedActivity=``, ``mResumedActivity:``, or ``ResumedActivity:``, then
# ``ActivityRecord{<hash> u0 <package>/<activity> ...}``.
_RESUMED_ACTIVITY = re.compile(
    r"(?:top|m)?ResumedActivity[=:]\s*ActivityRecord\{[^}]*?\s(?P<package>[A-Za-z0-9_.]+)/"
)


def foreground_package(device: Optional[str] = None) -> Optional[str]:
    """The package of the app in the foreground, or ``None`` when adb can't tell."""
    out = _run(_adb_base(device) + ["shell", "dumpsys activity activities | grep ResumedActivity"])
    match = _RESUMED_ACTIVITY.search(out or "")
    return match.group("package") if match else None


# Every build with the Lustro runtime has assets under this directory, and a build
# with only the no-op artifacts has none. A zip stores its entry names as plain text.
RUNTIME_ASSET_ENTRY = "assets/lustro/"


def package_paths(package: str, device: Optional[str] = None) -> Optional[List[str]]:
    """The paths of the package's APKs on the device: empty when it is not installed,
    and ``None`` when adb can't tell.

    ``pm path`` prints nothing for a package that is not installed, and exits 1 on
    newer releases. An error text, such as the one of a package manager that does
    not run yet while the device boots, or an adb that fails, says nothing about
    the package.
    """
    status, out = _run_status(_adb_base(device) + ["shell", "pm", "path", package])
    lines = [line.strip() for line in out.splitlines() if line.strip()]
    if not lines:
        return [] if status in (0, 1) else None
    paths = [line[len("package:") :] for line in lines if line.startswith("package:")]
    return paths or None


def apk_has_runtime(apk: str, device: Optional[str] = None) -> Optional[bool]:
    """Whether the installed APK at ``apk`` on the device contains the Lustro runtime,
    or ``None`` when adb can't tell.

    It searches the APK for the entry name ``assets/lustro/`` with ``grep -c -F``,
    not ``unzip``: API 28 emulator images have no ``unzip``, and toybox ``grep`` is on
    every API level. ``|| true`` because grep exits 1 when it counts 0.
    """
    out = _run(
        _adb_base(device)
        + ["shell", "grep -c -F {} {} || true".format(shlex.quote(RUNTIME_ASSET_ENTRY), shlex.quote(apk))],
        timeout=30.0,
    )
    count = (out or "").strip()
    if not count.isdigit():
        return None
    return int(count) > 0


def is_running(package: str, device: Optional[str] = None) -> Optional[bool]:
    """Whether a process of ``package`` runs on the device, or ``None`` when adb can't
    tell. ``|| true`` because pidof exits 1 when it finds none."""
    out = _run(_adb_base(device) + ["shell", "pidof {} || true".format(shlex.quote(package))])
    if out is None:
        return None
    return bool(out.strip())


class AdbMissing(DiscoveryError):
    """adb is not installed, or not on the PATH."""


def device_problem(device: Optional[str] = None) -> Optional[DiscoveryError]:
    """Why adb reaches no device, or ``None`` when it reaches one or can't tell.

    ``adb get-state`` prints ``device`` for a device that adb can use, and fails for no
    device, more than one with none selected, and a device that is offline or not
    authorized.
    """
    status, out = _run_status(_adb_base(device) + ["get-state"])
    if status == _NOT_FOUND:
        return AdbMissing(
            "adb is not installed, or not on the PATH, so the CLI can't find the app on a device. Install the "
            "Android SDK Platform-Tools, or pass --token and --port for a server that adb does not reach.",
            exitcodes.NO_DEVICE,
        )
    # adb can print its own lines first, such as "* daemon started successfully", or
    # the restart of a server of another adb version: the state is the last line.
    lines = [line.strip() for line in out.splitlines() if line.strip()]
    if status < 0 or (status == 0 and lines and lines[-1] == "device"):
        return None
    said = " ".join(out.split()) or "adb get-state exited with status {}".format(status)
    if "more than one" in said:
        return DiscoveryError(
            "more than one device is connected ({}): pass --device SERIAL, or set ANDROID_SERIAL. `adb devices` "
            "lists them.".format(said),
            exitcodes.USAGE,
        )
    return DiscoveryError(
        "no device that adb can use ({}): connect a device or start an emulator. `adb devices` lists each one "
        "with its state, which must be `device`, not `offline` or `unauthorized`.".format(said),
        exitcodes.NO_DEVICE,
    )


def diagnose(
    device: Optional[str] = None, package: Optional[str] = None, *, adb_required: bool = True
) -> Optional[DiscoveryError]:
    """Why the app's server can't be reached, as far as adb can tell, or ``None``.

    The checks go from the device to the app: is there a device, is the app
    installed, does its build contain the Lustro runtime, does it run, and is it in
    the foreground. Without ``package``, the app is the one in the foreground, and
    only its runtime is checked: that app can be the launcher, or another app, so
    an app without the runtime there gets the code of an app that is not reachable.
    With ``adb_required`` false, a computer without adb gets ``None``: the CLI then
    connects to a server on the computer, not on a device.
    """
    problem = device_problem(device)
    if problem is not None:
        return None if isinstance(problem, AdbMissing) and not adb_required else problem
    app = package or foreground_package(device)
    if not app:
        return None
    paths = package_paths(app, device)
    if paths is None:
        return None
    if not paths:
        if package:
            return DiscoveryError(
                "{} is not installed on the device: install a debug build that uses the Lustro artifacts, and "
                "start it.".format(package),
                exitcodes.NOT_INSTALLED,
            )
        return None
    apk = next((path for path in paths if path.endswith("/base.apk")), None)
    if apk is not None and apk_has_runtime(apk, device) is False:
        code = exitcodes.NO_RUNTIME if package else exitcodes.NOT_REACHABLE
        return DiscoveryError(_no_runtime_message(app, from_foreground=not package), code)
    if not package:
        return None
    if is_running(package, device) is False:
        return DiscoveryError(
            "{} is not running: start it. Lustro listens while the app is in the foreground.".format(package),
            exitcodes.NOT_REACHABLE,
        )
    front = foreground_package(device)
    if front is not None and front != package:
        return DiscoveryError(
            "{} runs, but {} is in the foreground: bring the app to the front. Lustro listens only while its app "
            "is in the foreground, and a locked screen stops it.".format(package, front),
            exitcodes.NOT_REACHABLE,
        )
    return None


def token_from_prefs(device: Optional[str] = None, package: Optional[str] = None) -> Optional[str]:
    """The token in the prefs of ``package``, or of the app in the foreground."""
    ra = discover_from_run_as(package or foreground_package(device) or "", device)
    return ra.token if ra is not None else None


def parse_forwards(text: str, device_port: int) -> List[Tuple[str, int]]:
    """The device serial and the local port of each forward to ``tcp:<device_port>``
    in ``adb forward --list`` output, in the order that adb lists them."""
    forwards = []
    for line in text.splitlines():
        match = _FORWARD_LINE.match(line)
        if match and int(match.group("remote")) == device_port:
            forwards.append((match.group("serial"), int(match.group("local"))))
    return forwards


def forwarded_ports(device_port: int, device: Optional[str] = None) -> List[int]:
    """The local ports of the ``adb forward``s to ``tcp:<device_port>`` on the
    selected device, in the order that adb lists them. Empty when there is none,
    or adb can't tell.

    ``adb forward --list`` lists the forwards of every device, so this asks adb
    which device it selects: ``device``, else ``ANDROID_SERIAL``, else the only one.
    """
    forwards = parse_forwards(_run(["adb", "forward", "--list"]) or "", device_port)
    if not forwards:
        return []
    serial = _run(_adb_base(device) + ["get-serialno"])
    if not serial:
        return []
    return [local for listed, local in forwards if listed == serial.strip()]


def resolve(
    *,
    host: Optional[str] = None,
    port: Optional[int] = None,
    token: Optional[str] = None,
    device: Optional[str] = None,
    package: Optional[str] = None,
    env: Optional[dict] = None,
    check_app: bool = True,
) -> Endpoint:
    """Resolve the active endpoint+token following the documented order.

    Explicit args take precedence; otherwise ``LUSTRO_TOKEN`` (token), then the
    logcat ready line (host/port/token), then ``run-as`` (token). Defaults for
    host/port are filled in last. Raises :class:`DiscoveryError` if no token can
    be determined from any source: with the cause that :func:`diagnose` finds, or,
    with ``check_app`` false, without those adb calls, for a caller that tries again.
    """
    env = os.environ if env is None else env

    r_host = host
    r_port = port
    r_token = token

    # LUSTRO_TOKEN env (token only).
    if r_token is None:
        env_token = env.get("LUSTRO_TOKEN")
        if env_token:
            r_token = env_token

    # Logcat ready line: fills any field still unknown.
    need_logcat = r_token is None or r_host is None or r_port is None
    if need_logcat:
        discovered = discover_from_logcat(device)
        if discovered is not None:
            if r_host is None:
                r_host = discovered.host
            if r_port is None:
                r_port = discovered.port
            if r_token is None:
                r_token = discovered.token

    # The app's prefs, through run-as, for whatever the log line didn't give: a
    # device that drops Info logs drops the line. They have the host and port of
    # the last bind too, unless the app predates storing them, so a token from
    # --token or LUSTRO_TOKEN still gets the app's own port.
    app = package
    if r_token is None or r_host is None or r_port is None:
        app = package or foreground_package(device)
        ra = discover_from_run_as(app or "", device)
        if ra is not None and ra.token:
            if r_token is None:
                r_token = ra.token
            if r_host is None:
                r_host = ra.host
            if r_port is None:
                r_port = ra.port

    # Fill host/port defaults last.
    if r_host is None:
        r_host = DEFAULT_HOST
    if r_port is None:
        r_port = DEFAULT_PORT

    if not r_token:
        cause = diagnose(device, package) if check_app else None
        if cause is not None:
            raise cause
        raise DiscoveryError(
            "no token: set --token or LUSTRO_TOKEN, or ensure the app is "
            "running in the foreground and `adb logcat -d -s LustroToken` shows the ready line "
            "(`Lustro ready endpoint=http://<host>:<port> token=<token>`). "
            "A device with `adb shell getprop log.tag` set to E drops that line: "
            "`adb shell setprop log.tag.LustroToken I` lets it through until the device "
            "restarts. For a debuggable app, `--package <id>` reads the token through run-as. "
            "For a device, also try `--device <serial>` and "
            "`adb forward tcp:<port> tcp:<port>`."
        )

    return Endpoint(host=r_host, port=int(r_port), token=r_token)


def _no_runtime_message(package: str, *, from_foreground: bool) -> str:
    """The error for an app whose APK has no Lustro runtime. Without --package, the app
    is the one in the foreground, which is the launcher when the user's app is closed."""
    causes = (
        "a build with the -noop artifacts, which have no server and log nothing, or a build from before the app "
        "added Lustro. Install a debug build that uses the Lustro artifacts, and start it"
    )
    if from_foreground:
        return (
            "the app in the foreground, {pkg}, does not contain the Lustro runtime: its APK has no {entry} entries. "
            "If it is your app, it is {causes}. If it is not, start your app. Pass --package <id> to check your app."
        ).format(pkg=package, entry=RUNTIME_ASSET_ENTRY, causes=causes)
    return (
        "the installed build of {pkg} does not contain the Lustro runtime: its APK has no {entry} entries. "
        "It is {causes}."
    ).format(pkg=package, entry=RUNTIME_ASSET_ENTRY, causes=causes)
