"""HTTP client for the Lustro wire protocol.

Stdlib only (``urllib.request``/``json``). The client adds the base URL and a
``Authorization: Bearer <token>`` header, encodes/decodes JSON, and raises a
typed :class:`LustroError` parsed from the uniform error envelope
``{error, message, code?, field?, hint?}``.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, Mapping, Optional

# The shared error-envelope status -> machine "error" type map, mirrored from the
# wire protocol (docs/AGENTS.md "Failure modes" / error-envelope.schema.json). Used as
# a fallback when a non-JSON / non-enveloped HTTP error comes back.
_STATUS_ERROR_TYPES = {
    400: "bad_request",
    401: "unauthorized",
    403: "forbidden",
    404: "not_found",
    405: "method_not_allowed",
    413: "payload_too_large",
    500: "internal_error",
    503: "unavailable",
    504: "timeout",
}


class LustroError(Exception):
    """A typed error parsed from the Lustro error envelope (or a transport failure).

    Attributes mirror the wire ``{error, message, code?, field?, hint?}`` shape,
    plus the HTTP ``status`` when one is available.
    """

    def __init__(
        self,
        error: str,
        message: str,
        *,
        status: Optional[int] = None,
        code: Optional[str] = None,
        field: Optional[str] = None,
        hint: Optional[str] = None,
    ) -> None:
        super().__init__(message or error)
        self.error = error
        self.message = message
        self.status = status
        self.code = code
        self.field = field
        self.hint = hint

    @classmethod
    def from_envelope(cls, body: Mapping[str, Any], *, status: Optional[int] = None) -> "LustroError":
        """Build from a decoded error envelope object."""
        return cls(
            error=str(body.get("error", "error")),
            message=str(body.get("message", "")),
            status=status,
            code=_opt_str(body.get("code")),
            field=_opt_str(body.get("field")),
            hint=_opt_str(body.get("hint")),
        )

    def __str__(self) -> str:
        parts = []
        if self.status is not None:
            parts.append(str(self.status))
        parts.append(self.error)
        line = " ".join(parts)
        if self.message:
            line += ": " + self.message
        if self.field:
            line += " (field: {})".format(self.field)
        if self.hint:
            line += "\n  hint: " + self.hint
        return line


def _opt_str(value: Any) -> Optional[str]:
    return None if value is None else str(value)


class LustroClient:
    """Minimal JSON-over-HTTP client for ``/api/v1/*`` routes.

    :param base_url: e.g. ``http://127.0.0.1:8080`` (no trailing ``/api/v1``).
    :param token: bearer token; sent as ``Authorization: Bearer <token>``.
    :param timeout: per-request socket timeout in seconds.
    """

    def __init__(self, base_url: str, token: Optional[str] = None, timeout: float = 30.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    # ── request plumbing ──────────────────────────────────────────────────────

    def _url(self, path: str, params: Optional[Mapping[str, Any]] = None) -> str:
        if not path.startswith("/"):
            path = "/" + path
        url = self.base_url + path
        if params:
            # Drop None values so callers can pass optional params uniformly.
            filtered = {k: v for k, v in params.items() if v is not None}
            if filtered:
                url += "?" + urllib.parse.urlencode(filtered)
        return url

    def _headers(self) -> Dict[str, str]:
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        return headers

    def request(
        self,
        method: str,
        path: str,
        *,
        params: Optional[Mapping[str, Any]] = None,
        json_body: Any = None,
    ) -> Any:
        """Perform a request and return the decoded JSON body.

        Raises :class:`LustroError` on any non-2xx response (parsing the error
        envelope when present) or on a transport failure.
        """
        url = self._url(path, params)
        headers = self._headers()
        data: Optional[bytes] = None
        if json_body is not None:
            data = json.dumps(json_body).encode("utf-8")
            headers["Content-Type"] = "application/json"

        req = urllib.request.Request(url, data=data, headers=headers, method=method.upper())
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read()
                return _decode_json(raw)
        except urllib.error.HTTPError as exc:  # non-2xx
            raise _error_from_http(exc) from None
        except urllib.error.URLError as exc:
            reason = getattr(exc, "reason", exc)
            raise LustroError(
                "connection_failed",
                "could not reach {}: {}".format(url, reason),
                hint=(
                    "Is the app running and foregrounded? For a device, run "
                    "`adb forward tcp:<port> tcp:<port>` and check the "
                    "`LustroToken` log line for the live endpoint."
                ),
            ) from None

    # ── convenience verbs ─────────────────────────────────────────────────────

    def get(self, path: str, params: Optional[Mapping[str, Any]] = None) -> Any:
        return self.request("GET", path, params=params)

    def post(self, path: str, json_body: Any = None, params: Optional[Mapping[str, Any]] = None) -> Any:
        return self.request("POST", path, params=params, json_body=json_body)


class CursorState:
    """Client-side state machine for the cursor-polling envelope.

    Mirrors the wire contract (docs/AGENTS.md "Live polling cursor" /
    cursor-envelope.schema.json) and the server's reference JS implementation:

    - ``reset``     → replace the whole list with ``items`` (or empty).
    - ``delta``     → ``items`` (when present) are the authoritative current
      list; absent items means no list mutation.
    - ``unchanged`` → no change; items are ignored even if present.
    - any UNKNOWN/missing status → treated as ``reset``.

    The returned ``cursor`` is persisted (when present) and echoed on the next
    poll. ``state`` (paused/overwriteMode/throttleDelayMs) is captured verbatim.
    """

    def __init__(self) -> None:
        self.cursor: Optional[str] = None
        self.items: list = []
        self.state: Dict[str, Any] = {}
        self.last_status: Optional[str] = None

    def apply(self, data: Optional[Mapping[str, Any]]) -> list:
        """Apply one poll response; return the list of items considered *new*.

        For ``reset``/``delta`` the "new" items are those not previously seen by
        id (when items carry an ``id``), so a poll loop can print only fresh
        rows. For ``unchanged`` the new list is empty.
        """
        if not data:
            return []

        status = data.get("status")
        self.last_status = status

        if "state" in data and isinstance(data["state"], dict):
            self.state = dict(data["state"])

        previous_ids = {
            item.get("id")
            for item in self.items
            if isinstance(item, dict) and item.get("id") is not None
        }

        new_items: list = []
        if status == "unchanged":
            # No list mutation; ignore any items defensively.
            pass
        elif status == "delta":
            if data.get("items") is not None:
                self.items = list(data["items"])
                new_items = self._diff(previous_ids)
        else:
            # 'reset' OR any unknown/missing status → replace the whole list.
            self.items = list(data.get("items") or [])
            new_items = self._diff(previous_ids)

        if data.get("cursor") is not None:
            self.cursor = data["cursor"]

        return new_items

    def _diff(self, previous_ids: set) -> list:
        fresh = []
        for item in self.items:
            if isinstance(item, dict) and item.get("id") is not None:
                if item.get("id") not in previous_ids:
                    fresh.append(item)
            else:
                fresh.append(item)
        return fresh


def _decode_json(raw: bytes) -> Any:
    if not raw:
        return None
    try:
        return json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as exc:
        raise LustroError("invalid_response", "server returned non-JSON body: {}".format(exc)) from None


def _error_from_http(exc: "urllib.error.HTTPError") -> LustroError:
    status = exc.code
    body_bytes = b""
    try:
        body_bytes = exc.read()
    except Exception:  # pragma: no cover - defensive
        pass
    # Prefer the structured error envelope when the server provides one.
    if body_bytes:
        try:
            decoded = json.loads(body_bytes.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            decoded = None
        if isinstance(decoded, dict) and "error" in decoded:
            return LustroError.from_envelope(decoded, status=status)
    # Fallback: synthesize from the status code.
    error_type = _STATUS_ERROR_TYPES.get(status, "http_error")
    message = body_bytes.decode("utf-8", "replace").strip() if body_bytes else (exc.reason or "")
    return LustroError(error_type, message or error_type, status=status)
