"""Endpoint and token discovery for the Lustro CLI.

Resolution order (first that yields a value wins, per field):

1. Explicit ``--token`` / ``--host`` / ``--port`` flags.
2. ``LUSTRO_TOKEN`` environment variable (token only).
3. The ``LustroToken`` logcat ready-line: ``adb [-s <serial>] logcat -d -s
   LustroToken`` parsed for the most recent
   ``Lustro ready endpoint=http://<host>:<port> token=<token>`` (this is the
   single source of truth for host/port/token, including a fallback port).
4. (best effort) ``adb [-s <serial>] shell run-as <pkg> cat`` of the app's
   private ``lustro_debug`` prefs.

If none of these determine the endpoint/token, a clear, actionable error is
raised.
"""

from __future__ import annotations

import os
import re
import subprocess
from typing import List, NamedTuple, Optional

# Matches the single machine-parseable line Lustro logs at tag LustroToken:
#   Lustro ready endpoint=http://127.0.0.1:8080 token=AbC123...
# Tolerant of a logcat prefix (date/pid/tag) before the message.
_READY_LINE = re.compile(
    r"Lustro\s+ready\s+endpoint=(?P<scheme>https?)://(?P<host>[^\s:/]+):(?P<port>\d+)\s+token=(?P<token>\S+)"
)

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8080


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
    """Raised when the endpoint/token cannot be determined."""


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


def discover_from_logcat(device: Optional[str] = None) -> Optional[Endpoint]:
    """Discover the endpoint+token by dumping the ``LustroToken`` logcat buffer."""
    out = _run(_adb_base(device) + ["logcat", "-d", "-s", "LustroToken"])
    if out is None:
        return None
    return parse_ready_line(out)


def discover_from_run_as(
    package: str, device: Optional[str] = None
) -> Optional[Endpoint]:
    """Best-effort token read from the app's private ``lustro_debug`` prefs.

    Only the token can be recovered here (host/port still come from flags/logcat);
    works only on debuggable builds where ``run-as <pkg>`` is permitted.
    """
    if not package:
        return None
    prefs_path = "shared_prefs/lustro_debug.xml"
    out = _run(
        _adb_base(device) + ["shell", "run-as", package, "cat", prefs_path]
    )
    if not out:
        return None
    # Prefs XML: <string name="token">AbC123...</string>
    match = re.search(
        r'<string\s+name="token">\s*([^<\s]+)\s*</string>', out
    )
    if not match:
        return None
    return Endpoint(host=DEFAULT_HOST, port=DEFAULT_PORT, token=match.group(1))


def resolve(
    *,
    host: Optional[str] = None,
    port: Optional[int] = None,
    token: Optional[str] = None,
    device: Optional[str] = None,
    package: Optional[str] = None,
    env: Optional[dict] = None,
) -> Endpoint:
    """Resolve the active endpoint+token following the documented order.

    Explicit args take precedence; otherwise ``LUSTRO_TOKEN`` (token), then the
    logcat ready line (host/port/token), then ``run-as`` (token). Defaults for
    host/port are filled in last. Raises :class:`DiscoveryError` if no token can
    be determined from any source.
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

    # run-as prefs (token only) as a final fallback.
    if r_token is None and package:
        ra = discover_from_run_as(package, device)
        if ra is not None and ra.token:
            r_token = ra.token

    # Fill host/port defaults last.
    if r_host is None:
        r_host = DEFAULT_HOST
    if r_port is None:
        r_port = DEFAULT_PORT

    if not r_token:
        raise DiscoveryError(
            "no token: set --token or LUSTRO_TOKEN, or ensure the app is "
            "running and `adb logcat -s LustroToken` shows the ready line "
            "(`Lustro ready endpoint=http://<host>:<port> token=<token>`). "
            "For a device, also try `--device <serial>` and "
            "`adb forward tcp:<port> tcp:<port>`."
        )

    return Endpoint(host=r_host, port=int(r_port), token=r_token)
