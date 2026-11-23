"""Endpoint and token discovery for the Lustro CLI.

Resolution order (first that yields a value wins, per field):

1. Explicit ``--token`` / ``--host`` / ``--port`` flags.
2. ``LUSTRO_TOKEN`` environment variable (token only).
3. With ``--package``, the app's private ``lustro_debug`` prefs, through ``adb
   [-s <serial>] shell run-as <pkg> cat``. They hold the token, and the host and
   port of the app's last bind: each process of the app writes its token, and
   each bind its host and port. One adb call reads them, the app's uid, and
   the listening sockets of the device (:func:`read_app`), where the dump of
   the log in 4 grows with the amount of log on the device, and takes seconds
   when its buffers are full. A build that is not debuggable fails ``run-as``,
   and an app that has not written the prefs yet has none: step 4 then gives
   what they did not.
4. The ``LustroToken`` logcat ready-line: ``adb [-s <serial>] logcat -d -v
   threadtime -s LustroToken`` parsed for the most recent
   ``Lustro ready endpoint=http://<host>:<port> token=<token>``, with the
   process ID of the line (without ``--package``, this is the first source for
   host/port/token, including a fallback port). With ``--package``, only the
   lines of that app's current process count: the lines with one of the process
   IDs of ``pidof <pkg>``, which :func:`app_process_uids` reads with their uids.
   Without it, the last line counts, whatever app logged it.
5. (best effort, without ``--package``) the ``run-as`` read of step 3, of the
   prefs of the app in the foreground, because Lustro listens only while its app
   is in the foreground. This is what finds the app on a device that drops Info
   logs (``log.tag`` set to ``E``, for example), which drops the ready line. It
   also replaces a ready line whose app does not listen on the line's port, or
   whose process has ended, when the app in the foreground listens on its port.

If none of these determine the endpoint/token, a clear, actionable error is
raised. :func:`diagnose` first looks for the cause, with the exit code of each:
no device, an app that is not installed, an installed build without the Lustro
runtime, or an app that is not running or not in the foreground. A token from
the ready line can be stale, when the app was installed again and the device
dropped the new line. A token from the prefs can be stale too: until a new
process of the app writes its token, they keep the one of its last process.
:func:`token_from_prefs` reads the current one, for a client to retry with
after a 401.

A token that steps 3 to 5 find goes only to a server of the app that owns it:
the app of the prefs, or the process that logged the line. Any app can log a
line with the tag, and bind the app's port while the app is in the background.
:func:`withheld` compares the uid that listens on the port of the device with
the owner's uid, and the endpoint's ``owner`` carries what it needs.

The port that these give is the app's port. On a device, the CLI reaches it
through an ``adb forward``, whose local port can differ: :func:`forwarded_ports`
finds it in ``adb forward --list``.
"""

from __future__ import annotations

import os
import re
import shlex
import struct
import subprocess
from typing import Callable, Dict, Iterable, List, NamedTuple, Optional, Tuple

from . import exitcodes
from .client import LustroError

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


class Owner(NamedTuple):
    """The app that owns a token that the CLI found, and what :func:`withheld` needs to
    check a server before the CLI sends it the token.

    ``uid`` is the uid of the app's processes: the one that ``run-as`` runs with, for a
    token from the prefs, or the one of the process that logged the ready line (``pid``),
    for a token from the log. It is ``None`` when adb can't tell. ``name`` is the app's
    package, ``None`` for the process of a line without ``--package``. ``prefs`` are the
    prefs that gave the token. ``net`` is the text of ``/proc/net/tcp`` and
    ``/proc/net/tcp6``, read in the same adb call as the uid."""

    uid: Optional[int] = None
    name: Optional[str] = None
    pid: Optional[str] = None
    prefs: Optional["Prefs"] = None
    net: Optional[str] = None


class Endpoint(NamedTuple):
    """A resolved Lustro endpoint. ``owner`` is set for a token that the CLI found and must
    check before it sends it (see :func:`withheld`)."""

    host: str
    port: int
    token: Optional[str]
    scheme: str = "http"
    owner: Optional[Owner] = None

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
    out = _run_bytes(cmd, timeout)
    return None if out is None else out.decode("utf-8", "replace")


def _run_bytes(cmd: List[str], timeout: float = 10.0) -> Optional[bytes]:
    """:func:`_run`, with stdout as the bytes that the command wrote."""
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
    return proc.stdout


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


def discover_from_logcat(
    device: Optional[str] = None, package: Optional[str] = None, pids: Optional[List[str]] = None
) -> Optional[Endpoint]:
    """Discover the endpoint+token by dumping the ``LustroToken`` logcat buffer. The
    endpoint's ``owner`` has the PID of the process that logged the line, which the
    ``threadtime`` format gives, and no uid.

    With ``package``, only the lines of that app's current process count: the last
    line in the buffer can be another app's, and the line of an earlier process of
    the app can have a port and a token that are no longer valid. ``pids`` are the
    app's process IDs, when the caller has them. An app that does not run, or whose
    process adb can't tell, gives ``None``.
    """
    if package:
        pids = app_pids(package, device) if pids is None else pids
        if not pids:
            return None
    # One dump with the PID of each line, not one `logcat --pid` per process: pidof
    # can print an old process that still exits, and the last line in the buffer,
    # which is the newest, must win.
    out = _run(_adb_base(device) + ["logcat", "-d", "-v", "threadtime", "-s", "LustroToken"])
    if out is None:
        return None
    last: Optional[Endpoint] = None
    for line in out.splitlines():
        pid = _line_pid(line)
        if package and pid not in (pids or []):
            continue
        endpoint = parse_ready_line(line)
        if endpoint is not None:
            last = endpoint._replace(owner=Owner(pid=pid))
    return last


# A ``threadtime`` line: ``<date> <time> <pid> <tid> <level> <tag>: <message>``.
_THREADTIME_PID = re.compile(r"^\s*\S+\s+\S+\s+(?P<pid>\d+)\s+\d+\s")


def _line_pid(line: str) -> Optional[str]:
    match = _THREADTIME_PID.match(line)
    return match.group("pid") if match else None


class Prefs(NamedTuple):
    """What the app's ``lustro_debug`` prefs hold: the token, and the host and the
    port of its last bind, ``None`` for an app that stored none."""

    token: str
    host: Optional[str]
    port: Optional[int]


def parse_prefs(text: str) -> Optional[Prefs]:
    """The token, the host, and the port in the text of the ``lustro_debug`` prefs file,
    or ``None`` when it holds no token."""
    # Prefs XML: <string name="lustro_token">AbC123...</string>
    match = re.search(r'<string\s+name="lustro_token">\s*([^<\s]+)\s*</string>', text)
    if not match:
        return None
    host = re.search(r'<string\s+name="lustro_host">\s*([^<\s]+)\s*</string>', text)
    port = re.search(r'<int\s+name="lustro_port"\s+value="(\d+)"', text)
    return Prefs(
        token=match.group(1),
        host=host.group(1) if host else None,
        port=int(port.group(1)) if port else None,
    )


# Separates the outputs of the commands that one ``adb shell`` call runs.
_PART = "--lustro-part--"
_PREFS_PATH = "shared_prefs/lustro_debug.xml"
_PROC_NET = "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null"


def _shell_parts(commands: List[str], device: Optional[str]) -> Optional[List[str]]:
    """Runs ``commands`` one after the other in one ``adb shell`` call, which costs one
    round trip to the device, and returns the output of each, or ``None`` when adb fails."""
    script = "".join("{}; echo; echo {}; ".format(command, _PART) for command in commands) + "true"
    out = _run(_adb_base(device) + ["shell", script])
    if out is None:
        return None
    parts = [""]
    for line in out.splitlines(keepends=True):
        if line.strip() == _PART:
            parts.append("")
        else:
            parts[-1] += line
    if len(parts) <= len(commands):
        return None
    # Without the line end that the echo before each separator adds.
    return [part[:-1] if part.endswith("\n") else part for part in parts[: len(commands)]]


def _number(text: str) -> Optional[int]:
    word = text.strip()
    return int(word) if word.isdigit() else None


def _net(text: str) -> Optional[str]:
    """The text of ``/proc/net/tcp`` and ``tcp6``, or ``None`` when the device did not let
    the shell read them."""
    return text if "local_address" in text else None


class AppRead(NamedTuple):
    """What one adb call reads about an app. ``uid`` is the uid that ``run-as`` runs the
    app's commands with, ``None`` when ``run-as`` fails, as for a build that is not
    debuggable. ``prefs`` are its ``lustro_debug`` prefs, ``None`` when they hold no token.
    ``net`` is ``/proc/net/tcp`` and ``tcp6``."""

    uid: Optional[int]
    prefs: Optional[Prefs]
    net: Optional[str]


# The real uid in /proc/<pid>/status: ``Uid:`` and the real, effective, saved, and file
# system uids, separated by tabs.
_STATUS_UID = re.compile(r"^Uid:\s+(\d+)", re.MULTILINE)


def read_app(package: str, device: Optional[str] = None) -> Optional[AppRead]:
    """The app's prefs, its uid, and the sockets of the device, in one adb call, or
    ``None`` when adb fails.

    The uid comes from ``run-as``, which runs with the app's uid: the ``cat`` that reads
    the prefs also reads its own ``/proc/self/status``. It does not come from the app's
    processes: ``pidof`` finds them by name, and another app can give its process any
    name, with ``android:process`` in its manifest. A process of the app in another
    user, such as a work profile, has another uid and its own prefs."""
    quoted = shlex.quote(package)
    parts = _shell_parts(
        ["run-as {} cat {} /proc/self/status 2>/dev/null".format(quoted, _PREFS_PATH), _PROC_NET], device
    )
    if parts is None:
        return None
    uid = _STATUS_UID.search(parts[0])
    return AppRead(uid=int(uid.group(1)) if uid else None, prefs=parse_prefs(parts[0]), net=_net(parts[1]))


def app_process_uids(package: str, device: Optional[str] = None) -> Optional[Dict[str, Optional[int]]]:
    """The uid of each process with the name ``package``, by its PID, ``None`` for a
    process whose uid ``stat`` can't read, in one adb call, or ``None`` when adb fails."""
    out = _run(
        _adb_base(device)
        + ["shell", "for p in $(pidof {}); do echo $p $(stat -c %u /proc/$p 2>/dev/null); done".format(
            shlex.quote(package)
        )]
    )
    if out is None:
        return None
    uids: Dict[str, Optional[int]] = {}
    for line in out.splitlines():
        fields = line.split()
        if fields and fields[0].isdigit():
            uids[fields[0]] = int(fields[1]) if len(fields) > 1 and fields[1].isdigit() else None
    return uids


def read_process(pid: str, device: Optional[str] = None) -> Tuple[Optional[int], Optional[str]]:
    """The uid of process ``pid``, ``None`` when it has ended, and ``/proc/net/tcp`` and
    ``tcp6``, in one adb call."""
    if not pid.isdigit():
        return None, None
    parts = _shell_parts(["stat -c %u /proc/{} 2>/dev/null".format(pid), _PROC_NET], device)
    if parts is None:
        return None, None
    return _number(parts[0]), _net(parts[1])


def read_net(device: Optional[str] = None) -> Optional[str]:
    """``/proc/net/tcp`` and ``tcp6`` of the device, or ``None`` when adb can't read them."""
    parts = _shell_parts([_PROC_NET], device)
    return None if parts is None else _net(parts[0])


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
# with only the no-op artifacts has none.
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


# The ZIP records that :func:`zip_entry_names` reads. The end of central directory
# record (EOCD) is 22 bytes and a comment of up to 65535 bytes, at the end of the file.
_EOCD_SIGNATURE = b"PK\x05\x06"
_EOCD_SIZE = 22
_EOCD_SEARCH = _EOCD_SIZE + 0xFFFF
_CENTRAL_ENTRY_SIGNATURE = b"PK\x01\x02"
_CENTRAL_ENTRY_SIZE = 46
# Far above the central directory of a large APK, which is less than 1 MB: a larger
# size is a damaged record, and its read would copy most of the APK.
_MAX_CENTRAL_DIRECTORY = 16 * 1024 * 1024


def zip_entry_names(read_tail: Callable[[int], Optional[bytes]]) -> Optional[List[str]]:
    """The entry names in the central directory of a ZIP, or ``None`` when it can't
    be read. ``read_tail(n)`` gives the last ``n`` bytes of the file, or the whole
    file when it is smaller.

    The central directory ends where the EOCD starts: in an APK, the signing block
    comes before it. One read of 64 KB gets the EOCD, and the central directory of
    most APKs with it. A second read gets a central directory that is larger. A
    ZIP64 file, which an APK can't be, gets ``None``.
    """
    tail = read_tail(_EOCD_SEARCH)
    if tail is None or len(tail) < _EOCD_SIZE:
        return None
    # The comment can contain the signature, so the EOCD is the one whose comment ends the file.
    eocd = tail.rfind(_EOCD_SIGNATURE, 0, len(tail) - _EOCD_SIZE + len(_EOCD_SIGNATURE))
    while eocd >= 0 and eocd + _EOCD_SIZE + struct.unpack_from("<H", tail, eocd + 20)[0] != len(tail):
        eocd = tail.rfind(_EOCD_SIGNATURE, 0, eocd)
    if eocd < 0:
        return None
    entries, size = struct.unpack_from("<HI", tail, eocd + 10)
    if size == 0xFFFFFFFF or size > _MAX_CENTRAL_DIRECTORY:
        return None
    if size <= eocd:
        directory = tail[eocd - size : eocd]
    else:
        whole = read_tail(size + len(tail) - eocd)
        if whole is None or len(whole) != size + len(tail) - eocd:
            return None
        directory = whole[:size]
    names = []
    offset = 0
    while len(names) < entries:
        if offset + _CENTRAL_ENTRY_SIZE > size or directory[offset : offset + 4] != _CENTRAL_ENTRY_SIGNATURE:
            return None
        name_length, extra_length, comment_length = struct.unpack_from("<HHH", directory, offset + 28)
        start = offset + _CENTRAL_ENTRY_SIZE
        names.append(directory[start : start + name_length].decode("utf-8", "replace"))
        offset = start + name_length + extra_length + comment_length
    return names


def apk_has_runtime(apk: str, device: Optional[str] = None) -> Optional[bool]:
    """Whether the installed APK at ``apk`` on the device contains the Lustro runtime,
    or ``None`` when adb can't tell.

    It reads the entry names from the APK's central directory, with ``tail -c``
    through ``adb exec-out``, which does not change binary bytes. Toybox ``tail`` is
    on every API level, and API 28 emulator images have no ``unzip``. A ``grep`` of
    the whole APK is not reliable: the toybox ``grep`` of API 30 to 33 finds no
    match after a NUL byte on the same line, and a ZIP has such bytes before each
    entry name.
    """

    def read_tail(size: int) -> Optional[bytes]:
        return _run_bytes(
            _adb_base(device) + ["exec-out", "tail -c {} {}".format(size, shlex.quote(apk))], timeout=30.0
        )

    names = zip_entry_names(read_tail)
    if names is None:
        return None
    return any(name.startswith(RUNTIME_ASSET_ENTRY) for name in names)


def app_pids(package: str, device: Optional[str] = None) -> Optional[List[str]]:
    """The process IDs of ``package`` on the device, or ``None`` when adb can't tell.
    ``|| true`` because pidof exits 1 when it finds none."""
    out = _run(_adb_base(device) + ["shell", "pidof {} || true".format(shlex.quote(package))])
    if out is None:
        return None
    return [pid for pid in out.split() if pid.isdigit()]


def is_running(package: str, device: Optional[str] = None) -> Optional[bool]:
    """Whether a process of ``package`` runs on the device, or ``None`` when adb can't tell."""
    pids = app_pids(package, device)
    return None if pids is None else bool(pids)


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


# A listening socket in /proc/net/tcp and /proc/net/tcp6. A line is
# ``<sl>: <local address>:<port> <remote address>:<port> <state> ... <uid> ...``,
# with the port in hex, and the uid in the eighth column.
_TCP_LISTEN = "0A"
_TCP_UID_COLUMN = 7


def parse_listening_uids(text: str, port: int) -> List[int]:
    """The uids of the sockets in ``/proc/net/tcp`` or ``/proc/net/tcp6`` text that
    listen on TCP ``port``, in the order of the lines, each once."""
    suffix = ":{:04X}".format(port)
    uids: List[int] = []
    for line in text.splitlines():
        fields = line.split()
        if len(fields) <= _TCP_UID_COLUMN or fields[3] != _TCP_LISTEN or not fields[1].upper().endswith(suffix):
            continue
        if fields[_TCP_UID_COLUMN].isdigit() and int(fields[_TCP_UID_COLUMN]) not in uids:
            uids.append(int(fields[_TCP_UID_COLUMN]))
    return uids


# A line of ``pm list packages -U``: ``package:<name> uid:<uid>``, with one uid for
# each user that has the app, comma-separated, such as ``uid:10773,9510773``.
_PACKAGE_UIDS = re.compile(r"^package:(?P<package>\S+)\s+uid:(?P<uids>[\d,]+)\s*$")

# A package in ``dumpsys package packages``: ``Package [<name>] (<hash>):``, and some
# lines later its app ID, the uid in user 0, as ``userId=<id>`` (``appId=`` on newer
# releases). The uid in user N is N * 100000 + the app ID.
_DUMPSYS_PACKAGE = re.compile(r"^\s*Package \[(?P<package>[^\]]+)\]")
_DUMPSYS_APP_ID = re.compile(r"^\s*(?:userId|appId)=(?P<id>\d+)")
_PER_USER_RANGE = 100000


def packages_of_uid(uid: int, device: Optional[str] = None) -> List[str]:
    """The packages that have ``uid``: more than one for a shared uid, and none when
    adb can't tell, as for a uid that is not an app's.

    ``pm list packages -U`` lists them from API 26. It can print an error first, such
    as a ``SecurityException`` for a user that the shell can't access, and then the
    packages of the other users. An older ``pm`` prints ``Unknown option: -U``: then
    ``dumpsys package packages`` gives the app ID of each package."""
    _, out = _run_status(_adb_base(device) + ["shell", "pm", "list", "packages", "-U"])
    listed = [match for match in (_PACKAGE_UIDS.match(line.strip()) for line in out.splitlines()) if match]
    if listed:
        return _unique(match.group("package") for match in listed if str(uid) in match.group("uids").split(","))
    packages = []
    current = None
    for line in (_run(_adb_base(device) + ["shell", "dumpsys", "package", "packages"]) or "").splitlines():
        package = _DUMPSYS_PACKAGE.match(line)
        app_id = _DUMPSYS_APP_ID.match(line) if current else None
        if package:
            current = package.group("package")
        elif app_id:
            if int(app_id.group("id")) == uid % _PER_USER_RANGE:
                packages.append(current)
            current = None
    return _unique(packages)


def _unique(items: Iterable[str]) -> List[str]:
    return list(dict.fromkeys(items))


_WITHHELD_HINT = (
    "To send a token to a server that you choose, pass --token, and --port with the local port of an adb "
    "forward that you made."
)


class TokenWithheld(LustroError):
    """The CLI found a token and did not send it: it could not tell that the server on the
    port is a server of the app that owns the token (see :func:`withheld`)."""

    def __init__(self, message: str, hint: Optional[str] = None, error: str = "token_withheld") -> None:
        super().__init__(error, message, hint=hint)


class PortHeld(TokenWithheld):
    """Another app listens on the port of the device where the CLI would send the token,
    such as another app with Lustro whose server did not close yet."""

    def __init__(self, port: int, owner: Owner, holder_uid: int) -> None:
        self.port = port
        self.owner = owner
        self.holder_uid = holder_uid
        self.holder_packages: List[str] = []
        self.owner_packages: List[str] = [owner.name] if owner.name else []
        super().__init__(self.describe(), error="port_held")

    def name_apps(self, device: Optional[str]) -> None:
        """Names the app that holds the port, and the app of a log line, from ``pm``."""
        self.holder_packages = packages_of_uid(self.holder_uid, device)
        if not self.owner_packages and self.owner.uid is not None:
            self.owner_packages = packages_of_uid(self.owner.uid, device)
        self.message = self.describe()

    def describe(self, *, waits: bool = True) -> str:
        """The message. ``waits`` adds that ``lustro ready`` waits until the app gets the port."""
        if self.holder_packages:
            other = "{} (uid {})".format(", ".join(self.holder_packages), self.holder_uid)
        else:
            other = "a process with uid {}".format(self.holder_uid)
        app = ", ".join(self.owner_packages) or "the app that logged the LustroToken line"
        message = (
            "port {port} of the device is held by {other}, not by {app} (uid {uid}), so the CLI did not send it "
            "the token of {app}. Both apps use port {port}: the server of {app} binds it when the other app's "
            "server closes, which it does when that app goes to the background."
        ).format(port=self.port, other=other, app=app, uid=self.owner.uid)
        if waits:
            package = self.owner_packages[0] if len(self.owner_packages) == 1 else "<package>"
            message += " `lustro ready --package {}` waits for that.".format(package)
        return message + " To prevent this, give each app with Lustro on the device its own serverPort."


def withheld(owner: Owner, host: str, port: int, net: Optional[str]) -> Optional[LustroError]:
    """Why the CLI must not send the token of ``owner`` to the server at ``host``:``port``,
    or ``None`` when that server is the owner's. ``net`` is ``/proc/net/tcp`` and ``tcp6``
    of the device.

    The server is the owner's when each socket that listens on ``port`` of the device has
    the owner's uid. The host is loopback, which the CLI reaches through an adb forward,
    or the address of the device on a network, which the runtime writes in the ready
    line. A token from the prefs goes to a host that is not loopback only when the prefs
    name that host and port: a ready line of another app can name any host. When nothing
    listens, the error is ``connection_failed``, as the connection's would be.
    """
    app = owner.name or "the app that logged the LustroToken line"
    prefs = owner.prefs
    if prefs is not None and host not in LOOPBACK_HOSTS and (prefs.host, prefs.port) != (host, port):
        return TokenWithheld(
            "the prefs of {} name {}:{}, not {}:{}, so the CLI did not send their token to {}:{}".format(
                app, prefs.host, prefs.port, host, port, host, port
            ),
            hint=_WITHHELD_HINT,
        )
    if owner.uid is None:
        if owner.pid is not None:
            return TokenWithheld(
                "the process that logged the last LustroToken line, PID {}, has ended, so the CLI did not send its "
                "token: start the app, and bring it to the front".format(owner.pid)
            )
        return TokenWithheld(
            "adb can't tell the uid of {}, so the CLI did not send its token".format(app), hint=_WITHHELD_HINT
        )
    listeners = None if net is None else parse_listening_uids(net, port)
    if listeners is None:
        return TokenWithheld(
            "adb can't tell which app listens on port {} of the device, so the CLI did not send the token of {} "
            "there".format(port, app),
            hint=_WITHHELD_HINT,
        )
    if not listeners:
        return LustroError(
            "connection_failed",
            "nothing listens on port {} of the device, so the CLI did not send the token of {} there".format(port, app),
            hint="Is the app running and foregrounded? Lustro listens only while its app is in the foreground.",
        )
    others = [uid for uid in listeners if uid != owner.uid]
    if others:
        return PortHeld(port, owner, others[0])
    return None


class TokenGuard:
    """Checks, before a client sends the token of ``endpoint.owner``, that the server on the
    endpoint's port of the device is the owner's (see :func:`withheld`), and raises the
    error when it is not. The first check uses ``/proc/net`` from discovery, and each later
    check reads it again: when the app goes to the background, its server closes, and
    another app can bind the port."""

    def __init__(self, endpoint: Endpoint, device: Optional[str] = None) -> None:
        if endpoint.owner is None:
            raise ValueError("an endpoint without an owner needs no check")
        self.owner = endpoint.owner
        self.host = endpoint.host
        self.port = endpoint.port
        self.device = device
        self._net = endpoint.owner.net

    def __call__(self) -> None:
        net, self._net = self._net, None
        if net is None:
            net = read_net(self.device)
        error = withheld(self.owner, self.host, self.port, net)
        if error is None:
            return
        if isinstance(error, PortHeld):
            error.name_apps(self.device)
        raise error


def token_from_prefs(
    device: Optional[str] = None, package: Optional[str] = None, endpoint: Optional[Endpoint] = None
) -> Optional[str]:
    """The token in the prefs of ``package``, or of the app in the foreground. For an
    ``endpoint`` with an ``owner``, only when the server at the endpoint is that app's (see
    :func:`withheld`): the app in the foreground can be another app than the one that
    logged the ready line."""
    app = package or foreground_package(device)
    if not app:
        return None
    read = read_app(app, device)
    if read is None or read.prefs is None:
        return None
    if endpoint is not None and endpoint.owner is not None:
        owner = Owner(uid=read.uid, name=app, prefs=read.prefs)
        if withheld(owner, endpoint.host, endpoint.port, read.net) is not None:
            return None
    return read.prefs.token


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

    Explicit args take precedence; otherwise ``LUSTRO_TOKEN`` (token). With
    ``package``, the app's prefs come next (host/port/token), then the logcat
    ready line of the app's current process for what the prefs did not give.
    Without it, the last logcat ready line comes next, then the prefs of the app
    in the foreground. Defaults for host/port are filled in last. Raises
    :class:`DiscoveryError` if no token can be determined from any source: with
    the cause that :func:`diagnose` finds, or, with ``check_app`` false, without
    those adb calls, for a caller that tries again.

    A token that this finds, with a port that it finds too, gets an ``owner`` on
    the endpoint, which a client checks before it sends the token (see
    :class:`TokenGuard`). A port from ``port`` is a local port, and a ``host`` that
    is not loopback is the user's choice: these get no check. Without ``package``,
    a ready line whose app does not listen on its port, or whose process has
    ended, gives way to the prefs of the app in the foreground when that app's
    server listens on the port that its prefs name.
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

    checks = r_token is None and port is None and (host is None or host in LOOPBACK_HOSTS)
    owner: Optional[Owner] = None

    def missing() -> bool:
        return r_token is None or r_host is None or r_port is None

    def take(found_host: Optional[str], found_port: Optional[int], found_token: Optional[str], found: Owner) -> None:
        nonlocal r_host, r_port, r_token, owner
        if r_token is None and found_token:
            r_token = found_token
            owner = found
        if r_host is None:
            r_host = found_host
        if r_port is None:
            r_port = found_port

    if package:
        # The app's prefs: one adb call, where the dump of the log takes seconds on a
        # device with a full log buffer. They are the named app's own. Each process of
        # the app writes its token, and each bind its host and port, so while its server
        # listens they are the current process's; before that, a connection to them
        # fails or gets 401, and the caller tries again or prints the cause. A build that
        # is not debuggable fails run-as, and an app that has not written them yet has
        # none: the log line gives those. The same call reads the uid and the sockets that
        # the check of the token needs.
        app = read_app(package, device) if missing() else None
        if app is not None and app.prefs is not None:
            take(app.prefs.host, app.prefs.port, app.prefs.token, Owner(app.uid, package, None, app.prefs, app.net))
        if missing():
            # The PIDs that keep the app's lines, with the uid of each: the uid of the
            # process that logged the line is the owner's.
            uids = app_process_uids(package, device)
            line = discover_from_logcat(device, package, None if uids is None else list(uids))
            if line is not None:
                pid = line.owner.pid if line.owner is not None else None
                uid = uids.get(pid or "") if uids is not None else None
                take(line.host, line.port, line.token, Owner(uid, package, pid, None, None if app is None else app.net))
    else:
        if missing():
            line = discover_from_logcat(device)
            if line is not None:
                pid = line.owner.pid if line.owner is not None else None
                uid, net = read_process(pid, device) if checks and pid else (None, None)
                take(line.host, line.port, line.token, Owner(uid=uid, pid=pid, net=net))
                if checks and owner is not None and withheld(owner, r_host or DEFAULT_HOST, r_port, net) is not None:
                    fallback = _from_foreground(host, device)
                    if fallback is not None and (owner.uid is None or fallback[1] is None):
                        found = fallback[0]
                        r_host, r_port, r_token, owner = found.host, found.port, found.token, found.owner
        # The prefs of the app in the foreground, for whatever the log line didn't give:
        # a device that drops Info logs drops the line. They have the host and port of
        # the last bind too, unless the app predates storing them, so a token from
        # --token or LUSTRO_TOKEN still gets the app's own port.
        if missing():
            app_name = foreground_package(device)
            app = read_app(app_name, device) if app_name else None
            if app is not None and app.prefs is not None:
                prefs = app.prefs
                take(prefs.host, prefs.port, prefs.token, Owner(app.uid, app_name, None, prefs, app.net))

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
            "For a device, also try `--device <serial>`."
        )

    return Endpoint(host=r_host, port=int(r_port), token=r_token, owner=owner if checks else None)


def _from_foreground(
    host: Optional[str], device: Optional[str]
) -> Optional[Tuple[Endpoint, Optional[LustroError]]]:
    """The endpoint in the prefs of the app in the foreground, with its owner, and why the
    CLI must not send it the token (see :func:`withheld`), or ``None`` without such prefs.
    ``host`` is the one from --host, when it is given."""
    app_name = foreground_package(device)
    app = read_app(app_name, device) if app_name else None
    if app is None or app.prefs is None:
        return None
    endpoint = Endpoint(
        host=host or app.prefs.host or DEFAULT_HOST,
        port=app.prefs.port or DEFAULT_PORT,
        token=app.prefs.token,
        owner=Owner(app.uid, app_name, None, app.prefs, app.net),
    )
    return endpoint, withheld(endpoint.owner, endpoint.host, endpoint.port, app.net)


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
