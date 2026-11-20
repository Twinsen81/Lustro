"""``lustro mcp``: a Model Context Protocol server on stdin and stdout.

It is for an agent that can only call MCP tools, such as Claude Desktop. The
agent's client starts ``lustro mcp`` as a child process when a session starts,
and closes its stdin at the end. Each message is
one line of JSON-RPC 2.0. Each tool calls the app's HTTP API with the discovery
and the ``adb forward`` of the other commands, so the app needs nothing new.

The protocol is implemented with the standard library, so that the CLI keeps
its zero runtime dependencies and Python 3.9: the official ``mcp`` SDK needs
Python 3.10 and several packages.

The server serves both eras of the protocol. A legacy client (protocol
2025-11-25 and earlier) opens with ``initialize``. A modern client (2026-07-28)
sends the protocol version in the ``_meta`` of each request, and can call
``server/discover`` first.

Only protocol messages go to stdout: while the server runs, ``sys.stdout`` is
stderr, so that a stray ``print`` can't break the stream.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import traceback
import urllib.parse
from typing import Any, BinaryIO, Callable, Dict, List, MutableMapping, NamedTuple, Optional, Tuple

from . import __version__, cli
from .client import LustroClient, LustroError, RawBody
from .discovery import DiscoveryError

MODERN_VERSIONS = ("2026-07-28",)
LEGACY_VERSIONS = ("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
SUPPORTED_VERSIONS = MODERN_VERSIONS + LEGACY_VERSIONS

META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"

PARSE_ERROR = -32700
INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602
INTERNAL_ERROR = -32603
UNSUPPORTED_PROTOCOL_VERSION = -32022

SERVER_INFO = {"name": "lustro", "title": "Lustro", "version": __version__}
# The tools are the same for the life of the process.
CACHE = {"ttlMs": 3_600_000, "cacheScope": "public"}

DEFAULT_LIST_ROWS = cli.DEFAULT_LAST
DEFAULT_LOG_LINES = 100
DEFAULT_MAX_BYTES = 20_000

INSTRUCTIONS = (
    "Lustro debugs the debug build of an Android app over adb. The app must run in the foreground on a "
    "connected device or emulator. list_requests and get_request read the app's captured HTTP requests; "
    "add_mock_rule, delete_mock_rule, and set_throttle change how the app's requests are answered; read_logs "
    "reads the app's log. get_schema lists the app's tabs, and with a tab returns its routes; call_api sends a "
    "request to any route, such as those of the prefs, sqlite, work, and screen tabs."
)


class RpcError(Exception):
    """A JSON-RPC error response."""

    def __init__(self, code: int, message: str, data: Any = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.data = data


class ToolError(Exception):
    """A tool call that can't run as given: the result says why, with ``isError``."""


Handler = Callable[[argparse.Namespace, Dict[str, Any]], str]


class Tool(NamedTuple):
    name: str
    title: str
    description: str
    input_schema: Dict[str, Any]
    annotations: Dict[str, Any]
    handler: Handler

    def describe(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "title": self.title,
            "description": self.description,
            "inputSchema": self.input_schema,
            "annotations": self.annotations,
        }


def _schema(properties: Dict[str, Any], required: Tuple[str, ...] = ()) -> Dict[str, Any]:
    schema: Dict[str, Any] = {"type": "object", "properties": properties, "additionalProperties": False}
    if required:
        schema["required"] = list(required)
    return schema


def _any_of(*kinds: str) -> Dict[str, Any]:
    # anyOf with one type in each branch, not a list of types: clients that map tool
    # schemas onto a dialect with a single type, such as Gemini's, drop a list.
    return {"anyOf": [{"type": kind} for kind in kinds]}


READ_ONLY = {"readOnlyHint": True, "openWorldHint": False}


def _changes(*, destructive: bool, idempotent: bool) -> Dict[str, Any]:
    return {
        "readOnlyHint": False,
        "destructiveHint": destructive,
        "idempotentHint": idempotent,
        "openWorldHint": False,
    }


# ── tools ─────────────────────────────────────────────────────────────────────


# The warnings of the tool call in progress, from the app's Lustro-Warning headers, and its
# notes, each line with its prefix. The server runs one call at a time, and the result of
# the call passes them on.
_call_warnings: List[str] = []


def _client(args: argparse.Namespace) -> LustroClient:
    client = cli._build_client(args)
    client.on_warning = _keep_warning
    return client


def _keep_warning(warning: str, prefix: str = "warning") -> None:
    line = "{}: {}".format(prefix, warning)
    if line not in _call_warnings:
        _call_warnings.append(line)


def _dumps(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def _list_requests(args: argparse.Namespace, a: Dict[str, Any]) -> str:
    search = a.get("search")
    if a.get("regex") and not search:
        raise ToolError("regex makes search a regular expression, so it needs search")
    status = None
    if a.get("status") is not None:
        try:
            status = cli._status_range(a["status"])
        except argparse.ArgumentTypeError as exc:
            raise ToolError("status: {}".format(exc))
    client = _client(args)
    if a.get("regex"):
        cli.require_protocol(client, "list_requests", "regex")
    data = client.get(cli.TRANSACTIONS, params={"search": search, "regex": "true" if a.get("regex") else None})
    items = cli._list_items(data)
    filters = argparse.Namespace(
        method=a.get("method"), url=a.get("url"), status=status, errors=bool(a.get("errors"))
    )
    matched = [tx for tx in items if cli._matches(tx, filters)]
    shown = matched[: a.get("last", DEFAULT_LIST_ROWS)]
    lines = [_state_line(data)]
    id_length = cli._short_id_length(items)
    lines += [cli._format_row(tx, id_length) for tx in shown]
    if not matched:
        lines.append("no captured request matches" if items else "no captured requests")
    elif len(shown) < len(matched):
        lines.append("showing the newest {} of {}; raise last for more".format(len(shown), len(matched)))
    return "\n".join(lines)


def _state_line(data: Any) -> str:
    state = data.get("state") if isinstance(data, dict) else None
    if not isinstance(state, dict):
        return "state: unknown"
    line = "state: paused={} overwriteMode={} throttleDelayMs={}".format(
        *(json.dumps(state.get(key)) for key in ("paused", "overwriteMode", "throttleDelayMs"))
    )
    mock_rules = state.get("mockRules")
    if isinstance(mock_rules, dict):
        line += " enabledMockRules={}".format(mock_rules.get("enabled"))
    if state.get("paused") is True:
        line += " (capture is paused, so new requests are not listed; call_api POST network/pause resumes it)"
    return line


def _get_request(args: argparse.Namespace, a: Dict[str, Any]) -> str:
    client = _client(args)
    tx = client.get(cli.TRANSACTIONS + "/" + _full_id(client, a["id"]))
    if isinstance(tx, dict):
        if a.get("no_body"):
            tx.pop("requestBody", None)
            tx.pop("responseBody", None)
        elif not a.get("full"):
            cli._cut_bodies(
                tx,
                a.get("max_body", cli.DEFAULT_MAX_BODY),
                more="call_api GET network/transactions/{id}/body/{direction}",
            )
    return _dumps(tx)


def _full_id(client: LustroClient, given: str) -> str:
    """The full id of the one transaction whose id starts with ``given``."""
    if not given:
        raise ToolError("id is empty: give a transaction id, or its start, such as a row's short id")
    if cli._FULL_ID.fullmatch(given):
        return given
    return cli._match_id(cli._list_items(client.get(cli.TRANSACTIONS)), given, list_command="list_requests")


def _list_mock_rules(args: argparse.Namespace, a: Dict[str, Any]) -> str:
    data = _client(args).get(cli.NETWORK + "/rules")
    for rule in cli._list_items(data):
        cli._cut_bodies(rule, cli.DEFAULT_MAX_BODY, more="call_api GET network/rules")
    return _dumps(data)


def _add_mock_rule(args: argparse.Namespace, a: Dict[str, Any]) -> str:
    # The app ignores the response of a failure rule, so a response given with one is a mistake.
    if a.get("failure") is not None and any(a.get(name) is not None for name in ("status", "body", "headers")):
        raise ToolError("failure fails the request, so it takes no status, body, or headers")
    rule: Dict[str, Any] = {"urlPattern": a["url_pattern"]}
    for name, wire_name in (
        ("id", "id"),
        ("name", "name"),
        ("method", "method"),
        ("status", "statusCode"),
        ("body", "responseBody"),
        ("headers", "responseHeaders"),
        ("failure", "failure"),
        ("enabled", "enabled"),
    ):
        if a.get(name) is not None:
            rule[wire_name] = a[name]
    client = _client(args)
    if "failure" in rule:
        cli.require_protocol(client, "add_mock_rule", "failure")
    return _dumps(client.post(cli.NETWORK + "/rules", json_body=rule))


def _delete_mock_rule(args: argparse.Namespace, a: Dict[str, Any]) -> str:
    return _dumps(_client(args).post(cli.NETWORK + "/rules/delete", json_body={"id": a["id"]}))


def _set_throttle(args: argparse.Namespace, a: Dict[str, Any]) -> str:
    return _dumps(_client(args).post(cli.NETWORK + "/throttle", json_body={"delayMs": a["delay_ms"]}))


def _read_logs(args: argparse.Namespace, a: Dict[str, Any]) -> str:
    params = {
        "level": a.get("level"),
        "tag": a.get("tag"),
        "q": a.get("text"),
        "mq": a.get("message_regex"),
        "from": a.get("from_ms"),
        "to": a.get("to_ms"),
        "limit": a.get("limit", DEFAULT_LOG_LINES),
    }
    client = _client(args)
    times = [name for name in ("from_ms", "to_ms") if a.get(name) is not None]
    if times:
        cli.require_protocol(client, "read_logs", *times)
    text = client.request_raw("GET", "/api/v1/logs/text", params=params).data.decode("utf-8", "replace")
    if not text.strip():
        return "no log line matches"
    return _cut_head(text, a.get("max_bytes", DEFAULT_MAX_BYTES))


def _call_api(args: argparse.Namespace, a: Dict[str, Any]) -> str:
    try:
        method = cli._http_method(a["method"])
    except argparse.ArgumentTypeError as exc:
        raise ToolError("method: {}".format(exc))
    try:
        path = cli._api_path(a["path"])
    except cli.UsageError as exc:
        raise ToolError(str(exc))
    body = a.get("body")
    if isinstance(body, str):
        # Models often send a JSON body as a string. The routes take an object or an array.
        try:
            body = json.loads(body)
        except ValueError as exc:
            raise ToolError("body is a string that is not JSON: {}".format(exc))
        if not isinstance(body, (dict, list)):
            raise ToolError("body must be a JSON object or array")
    params = _query_pairs(a.get("params"))
    client = _client(args)
    if params or urllib.parse.urlsplit(path).query:
        note = cli.unwarned_parameters_note(client, "get_schema with the tab")
        if note:
            _keep_warning(note, prefix="note")
    response = client.request_raw(method, path, params=params, json_body=body)
    return _cut_text(_body_text(response), a.get("max_bytes", DEFAULT_MAX_BYTES))


def _query_pairs(params: Optional[Dict[str, Any]]) -> Optional[List[Tuple[str, str]]]:
    """The query parameters as (key, value) pairs: an array repeats its key."""
    if not params:
        return None
    pairs = []
    for key, value in params.items():
        for item in value if isinstance(value, list) else [value]:
            if isinstance(item, bool):
                item = "true" if item else "false"
            elif item is None or isinstance(item, (dict, list)):
                raise ToolError(
                    "params.{}: a value is a string, a number, a boolean, or an array of them".format(key)
                )
            pairs.append((key, str(item)))
    return pairs


def _body_text(response: RawBody) -> str:
    if not response.data:
        return "(the response has no body)"
    if cli._is_json_type(response.content_type):
        try:
            return _dumps(json.loads(response.data.decode("utf-8")))
        except (ValueError, UnicodeDecodeError):
            pass
    try:
        return response.data.decode("utf-8")
    except UnicodeDecodeError:
        return "(a binary body of {} bytes, Content-Type {})".format(
            len(response.data), response.content_type or "unknown"
        )


def _cut_text(text: str, limit: int) -> str:
    data = text.encode("utf-8", "surrogatepass")
    if len(data) <= limit:
        return text
    cut = limit
    # Keep whole characters: move back to the first byte of the one at the cut.
    while cut > 0 and (data[cut] & 0xC0) == 0x80:
        cut -= 1
    return "{}\n[... {} more bytes: call again with a larger max_bytes]".format(
        data[:cut].decode("utf-8", "surrogatepass"), len(data) - cut
    )


def _cut_head(text: str, limit: int) -> str:
    """Keeps the end of a log, its newest lines, in at most limit bytes: whole lines when one fits."""
    data = text.encode("utf-8", "surrogatepass")
    if len(data) <= limit:
        return text
    cut = len(data) - limit
    # From the byte before the cut, so that a line that starts at the cut stays. cut >= 1 here.
    line_start = data.find(b"\n", cut - 1, len(data) - 1)
    if line_start >= 0:
        cut = line_start + 1
    else:
        # One line longer than limit: keep whole characters, from the first byte of one.
        while cut < len(data) and (data[cut] & 0xC0) == 0x80:
            cut += 1
    return "[... {} earlier bytes: call again with a larger max_bytes or a smaller limit]\n{}".format(
        cut, data[cut:].decode("utf-8", "surrogatepass")
    )


def _get_schema(args: argparse.Namespace, a: Dict[str, Any]) -> str:
    tab = a.get("tab")
    if tab:
        return _dumps(_client(args).get("/api/v1/{}/_schema".format(urllib.parse.quote(tab, safe=""))))
    meta = _client(args).get(cli.META)
    warning = cli.newer_protocol_warning(meta)
    if warning:
        _keep_warning(warning)
    return _dumps(meta)


TOOLS: Tuple[Tool, ...] = (
    Tool(
        "list_requests",
        "List captured requests",
        "Read-only. The HTTP requests that the app captured, newest first. The first line is the capture state. "
        "Then one row for each request: a short id, the start time, the method, the status ('...' in flight, "
        "ERR failed), the duration, the URL, and marks such as [mock]. get_request takes the short id.",
        _schema(
            {
                "search": {
                    "type": "string",
                    "description": "Text that the URL, the method, a header, or a body contains, ignoring case.",
                },
                "regex": {
                    "type": "boolean",
                    "description": "Each word of search is a Java regular expression; -word leaves out.",
                },
                "url": {"type": "string", "description": "Text that the URL contains, ignoring case."},
                "method": {"type": "string", "description": "The HTTP method, such as POST."},
                "status": {"type": "string", "description": "A status code such as 404, or a class such as 5xx."},
                "errors": {"type": "boolean", "description": "Only status 400 or higher, and failed requests."},
                "last": {
                    "type": "integer",
                    "minimum": 1,
                    "default": DEFAULT_LIST_ROWS,
                    "description": "How many of the newest matching requests to list.",
                },
            }
        ),
        READ_ONLY,
        _list_requests,
    ),
    Tool(
        "get_request",
        "Get a captured request",
        "Read-only. One captured request as JSON: the URL, the headers, the status, the timings, and the bodies. "
        "Each body is cut at max_body bytes, with a marker that says how to read the rest.",
        _schema(
            {
                "id": {"type": "string", "description": "A transaction id, or its start, such as a row's short id."},
                "max_body": {
                    "type": "integer",
                    "minimum": 0,
                    "default": cli.DEFAULT_MAX_BODY,
                    "description": "Cut each body at this many bytes.",
                },
                "full": {"type": "boolean", "description": "Return the bodies whole."},
                "no_body": {"type": "boolean", "description": "Leave the bodies out."},
            },
            ("id",),
        ),
        READ_ONLY,
        _get_request,
    ),
    Tool(
        "list_mock_rules",
        "List mock rules",
        "Read-only. The mock rules as JSON, in the order that the app tries them: the first enabled rule that "
        "matches a request answers it. Each response body is cut at 2 KB.",
        _schema({}),
        READ_ONLY,
        _list_mock_rules,
    ),
    Tool(
        "add_mock_rule",
        "Add a mock rule",
        "Changes the app. Adds a mock rule, or replaces the rule that has the same id. While the rule is enabled, "
        "an OkHttp request whose URL contains url_pattern gets the mocked response, or fails with the "
        "connection error that failure names, and does not reach the network. Returns the rule's id.",
        _schema(
            {
                "url_pattern": {
                    "type": "string",
                    "description": "Text that the URL contains, or a regular expression after the prefix regex:.",
                },
                "id": {
                    "type": "string",
                    "description": "A stable id: a second call with it replaces the rule. "
                    "Without it, the app makes one.",
                },
                "name": {"type": "string"},
                "method": {"type": "string", "description": "The HTTP method to match. Without it, any method."},
                "status": {"type": "integer", "minimum": 100, "maximum": 599, "description": "Default 200."},
                "body": {"type": "string", "description": "The mocked response body."},
                "headers": {
                    "type": "object",
                    "additionalProperties": {"type": "string"},
                    "description": "The mocked response headers, such as {\"Content-Type\": \"application/json\"}.",
                },
                # Not an enum: the app checks the name, and a newer app can know more.
                "failure": {
                    "type": "string",
                    "description": "Fail the request with a connection error instead of a response: "
                    + ", ".join(cli.MOCK_FAILURES)
                    + ". Takes no status, body, or headers.",
                },
                "enabled": {"type": "boolean", "description": "Default true."},
            },
            ("url_pattern",),
        ),
        _changes(destructive=True, idempotent=False),
        _add_mock_rule,
    ),
    Tool(
        "delete_mock_rule",
        "Delete a mock rule",
        "Changes the app. Deletes the mock rule with this id.",
        _schema({"id": {"type": "string", "description": "The rule's id, from list_mock_rules."}}, ("id",)),
        _changes(destructive=True, idempotent=True),
        _delete_mock_rule,
    ),
    Tool(
        "set_throttle",
        "Set the throttle",
        "Changes the app. Holds each OkHttp request for delay_ms milliseconds before it is sent; 0 turns the "
        "throttle off. The first line of list_requests shows the delay.",
        _schema({"delay_ms": {"type": "integer", "minimum": 0, "maximum": 600000}}, ("delay_ms",)),
        _changes(destructive=False, idempotent=True),
        _set_throttle,
    ),
    Tool(
        "read_logs",
        "Read the app's log",
        "Read-only. The app's own log lines as logcat's threadtime text, oldest first: the newest limit entries "
        "that pass the filters. An entry that the app's logger appended can have many lines. The text keeps its "
        "newest max_bytes. Needs the Logs tab in the app.",
        _schema(
            {
                "level": {
                    "type": "string",
                    "enum": ["V", "D", "I", "W", "E", "F"],
                    "description": "The lowest level: W gives W, E, and F.",
                },
                "tag": {
                    "type": "string",
                    "description": "A Java regular expression found in the tag, such as ^OkHttp$.",
                },
                "text": {"type": "string", "description": "Text that the message contains, ignoring case."},
                "message_regex": {"type": "string", "description": "A Java regular expression found in the message."},
                "from_ms": {"type": "integer", "description": "Only the lines at or after this epoch millisecond."},
                "to_ms": {"type": "integer", "description": "Only the lines at or before this epoch millisecond."},
                "limit": {"type": "integer", "minimum": 1, "maximum": 5000, "default": DEFAULT_LOG_LINES},
                "max_bytes": {
                    "type": "integer",
                    "minimum": 1,
                    "default": DEFAULT_MAX_BYTES,
                    "description": "Keep the newest this many bytes of the text, and say how many earlier bytes were cut.",
                },
            }
        ),
        READ_ONLY,
        _read_logs,
    ),
    Tool(
        "call_api",
        "Call any route",
        "Can change the app: a POST, PUT, or DELETE route can change or delete the app's data, and a GET route "
        "only reads. Sends one request to any route of the app's HTTP API, also of a tab that has no tool of its "
        "own, such as prefs, sqlite, work, screen, or the app's own tabs, and returns the response body. "
        "get_schema with a tab returns its routes.",
        _schema(
            {
                "method": {"type": "string", "description": "GET, POST, or another HTTP method."},
                "path": {
                    "type": "string",
                    "description": "A route such as network/transactions, which is under /api/v1/, or /api/v1/_meta.",
                },
                "params": {
                    "type": "object",
                    "additionalProperties": _any_of("string", "number", "boolean", "array"),
                    "description": "Query parameters, such as {\"level\": \"W\"}. An array repeats the key.",
                },
                "body": dict(
                    _any_of("object", "array", "string"),
                    description="The JSON request body: an object or an array, or the same as a JSON string.",
                ),
                "max_bytes": {
                    "type": "integer",
                    "minimum": 1,
                    "default": DEFAULT_MAX_BYTES,
                    "description": "Cut the response at this many bytes.",
                },
            },
            ("method", "path"),
        ),
        _changes(destructive=True, idempotent=False),
        _call_api,
    ),
    Tool(
        "get_schema",
        "Get the tabs or a tab's routes",
        "Read-only. Without tab: the tabs that the app registered, and the library and protocol versions. With "
        "tab: the tab's OpenAPI document, which describes the routes that call_api reaches (20 to 60 KB).",
        _schema({"tab": {"type": "string", "description": "A tab id, such as network, logs, prefs, or sqlite."}}),
        READ_ONLY,
        _get_schema,
    ),
)


# ── argument checks ───────────────────────────────────────────────────────────


_JSON_TYPES: Dict[str, Callable[[Any], bool]] = {
    "string": lambda v: isinstance(v, str),
    "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
    "boolean": lambda v: isinstance(v, bool),
    "object": lambda v: isinstance(v, dict),
    "array": lambda v: isinstance(v, list),
}


def check_arguments(schema: Dict[str, Any], arguments: Dict[str, Any]) -> Dict[str, Any]:
    """The arguments, checked against the subset of JSON Schema that the tools use.
    A null counts as a missing argument, and a whole float as an integer."""
    properties = schema.get("properties", {})
    checked: Dict[str, Any] = {}
    for name, value in arguments.items():
        if name not in properties:
            raise ToolError(
                "unknown argument {}; the arguments are: {}".format(name, ", ".join(properties) or "none")
            )
        if value is not None:
            checked[name] = _check_value(name, properties[name], value)
    missing = [name for name in schema.get("required", []) if name not in checked]
    if missing:
        raise ToolError("missing argument: {}".format(", ".join(missing)))
    return checked


def _check_value(name: str, spec: Dict[str, Any], value: Any) -> Any:
    if "anyOf" in spec:
        kinds = [branch["type"] for branch in spec["anyOf"]]
    elif "type" in spec:
        kinds = [spec["type"]]
    else:
        kinds = []
    if kinds and "integer" in kinds and isinstance(value, float) and value.is_integer():
        value = int(value)
    if kinds and not any(_JSON_TYPES[kind](value) for kind in kinds):
        raise ToolError("{} must be of type {}, not {}".format(name, " or ".join(kinds), type(value).__name__))
    if "enum" in spec and value not in spec["enum"]:
        raise ToolError("{} must be one of {}".format(name, ", ".join(map(str, spec["enum"]))))
    if "minimum" in spec and value < spec["minimum"]:
        raise ToolError("{} must be at least {}".format(name, spec["minimum"]))
    if "maximum" in spec and value > spec["maximum"]:
        raise ToolError("{} must be at most {}".format(name, spec["maximum"]))
    items = spec.get("additionalProperties")
    if isinstance(value, dict) and isinstance(items, dict):
        value = {key: _check_value("{}.{}".format(name, key), items, item) for key, item in value.items()}
    return value


# ── the protocol ──────────────────────────────────────────────────────────────


class Server:
    """Answers one JSON-RPC message at a time. It holds no state between requests."""

    def __init__(self, args: argparse.Namespace, tools: Optional[Tuple[Tool, ...]] = None) -> None:
        self.args = args
        self.tools = {tool.name: tool for tool in (TOOLS if tools is None else tools)}
        self.methods: Dict[str, Callable[[Dict[str, Any]], Dict[str, Any]]] = {
            "initialize": self._initialize,
            "server/discover": self._discover,
            "ping": lambda params: {},
            "tools/list": self._tools_list,
            "tools/call": self._tools_call,
        }

    def handle_line(self, line: bytes) -> Any:
        """The reply to one line: a response, a list of them for a batch, or None."""
        try:
            message = json.loads(line.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as exc:
            return _error(None, PARSE_ERROR, "Parse error: {}".format(exc))
        if isinstance(message, list):
            # Batches are in 2025-03-26 only, and cost little to serve.
            if not message:
                return _error(None, INVALID_REQUEST, "Invalid Request: empty batch")
            replies = [reply for reply in map(self.handle, message) if reply is not None]
            return replies or None
        return self.handle(message)

    def handle(self, message: Any) -> Optional[Dict[str, Any]]:
        if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
            request_id = message.get("id") if isinstance(message, dict) else None
            return _error(request_id, INVALID_REQUEST, "Invalid Request: not a JSON-RPC 2.0 message")
        method = message.get("method")
        if method is None:
            # A response: this server sends no requests, so there is nothing to match it to.
            return None
        if "id" not in message:
            # A notification, such as notifications/initialized or notifications/cancelled. The
            # server answers each request before it reads the next line, so there is nothing to cancel.
            return None
        request_id = message["id"]
        if not isinstance(method, str) or request_id is None or isinstance(request_id, (bool, dict, list)):
            return _error(
                request_id, INVALID_REQUEST, "Invalid Request: method must be a string and id a string or a number"
            )
        params = message.get("params")
        if params is None:
            params = {}
        try:
            if not isinstance(params, dict):
                raise RpcError(INVALID_PARAMS, "Invalid params: params must be an object")
            handler = self.methods.get(method)
            if handler is None:
                raise RpcError(METHOD_NOT_FOUND, "Method not found: {}".format(method))
            modern = self._check_meta(method, params)
            result = handler(params)
        except RpcError as exc:
            return _error(request_id, exc.code, exc.message, exc.data)
        except Exception as exc:  # a bug: report it, and keep serving
            traceback.print_exc(file=sys.stderr)
            return _error(request_id, INTERNAL_ERROR, "Internal error: {}".format(exc))
        if modern:
            # Fields of 2026-07-28 only: a legacy client can check a result strictly, as
            # the TypeScript SDK does for the empty result of ping.
            result = dict(result, resultType="complete", _meta={META_SERVER_INFO: SERVER_INFO})
            if method == "tools/list":
                result.update(CACHE)
        return {"jsonrpc": "2.0", "id": request_id, "result": result}

    def _check_meta(self, method: str, params: Dict[str, Any]) -> bool:
        """Whether the request is modern, from its ``_meta``: it names its protocol version
        there. A legacy request, after ``initialize``, names none."""
        meta = params.get("_meta")
        version = meta.get(META_PROTOCOL_VERSION) if isinstance(meta, dict) else None
        if version is None or method == "initialize":
            return False
        if version not in SUPPORTED_VERSIONS:
            raise RpcError(
                UNSUPPORTED_PROTOCOL_VERSION,
                "Unsupported protocol version",
                {"supported": list(SUPPORTED_VERSIONS), "requested": version},
            )
        if not isinstance(meta.get(META_CLIENT_CAPABILITIES), dict):
            raise RpcError(INVALID_PARAMS, "Invalid params: _meta has no {}".format(META_CLIENT_CAPABILITIES))
        return True

    def _initialize(self, params: Dict[str, Any]) -> Dict[str, Any]:
        requested = params.get("protocolVersion")
        return {
            "protocolVersion": requested if requested in LEGACY_VERSIONS else LEGACY_VERSIONS[0],
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": SERVER_INFO,
            "instructions": INSTRUCTIONS,
        }

    def _discover(self, params: Dict[str, Any]) -> Dict[str, Any]:
        # A modern method: its result has the modern fields also for a request without _meta.
        return dict(
            {
                "resultType": "complete",
                "supportedVersions": list(SUPPORTED_VERSIONS),
                "capabilities": {"tools": {"listChanged": False}},
                "_meta": {META_SERVER_INFO: SERVER_INFO},
                "instructions": INSTRUCTIONS,
            },
            **CACHE,
        )

    def _tools_list(self, params: Dict[str, Any]) -> Dict[str, Any]:
        return {"tools": [tool.describe() for tool in self.tools.values()]}

    def _tools_call(self, params: Dict[str, Any]) -> Dict[str, Any]:
        name = params.get("name")
        tool = self.tools.get(name) if isinstance(name, str) else None
        if tool is None:
            raise RpcError(INVALID_PARAMS, "Unknown tool: {}".format(name))
        arguments = params.get("arguments")
        if arguments is None:
            arguments = {}
        if not isinstance(arguments, dict):
            raise RpcError(INVALID_PARAMS, "Invalid params: arguments must be an object")
        del _call_warnings[:]
        try:
            text = tool.handler(self.args, check_arguments(tool.input_schema, arguments))
        except (ToolError, cli.UsageError) as exc:
            return _text_result("error: {}".format(exc), is_error=True)
        except (LustroError, DiscoveryError) as exc:
            # The cause that adb finds, as a command prints it: a build without the
            # Lustro runtime, or an app that is not running, for example.
            _, message = cli.failure(self.args, exc)
            # A missing adb can only be why the app was not found or not reached.
            reached = isinstance(exc, LustroError) and exc.error != "connection_failed"
            note = "" if reached else _adb_note()
            return _with_warnings(_text_result("error: {}{}".format(message, note), is_error=True))
        return _with_warnings(_text_result(text))


def _with_warnings(result: Dict[str, Any]) -> Dict[str, Any]:
    """The result with the app's warnings, such as an unknown query parameter, in a text
    block of their own after the output, so that a cut in the output can't drop them."""
    if _call_warnings:
        text = "\n".join(_call_warnings)
        result["content"].append({"type": "text", "text": text})
    return result


def _text_result(text: str, *, is_error: bool = False) -> Dict[str, Any]:
    return {"content": [{"type": "text", "text": text}], "isError": is_error}


def _error(request_id: Any, code: int, message: str, data: Any = None) -> Dict[str, Any]:
    error: Dict[str, Any] = {"code": code, "message": message}
    if data is not None:
        error["data"] = data
    return {"jsonrpc": "2.0", "id": request_id, "error": error}


def _adb_note() -> str:
    if shutil.which("adb"):
        return ""
    return (
        "\nnote: adb is not on the PATH of the MCP server, so it can't find the app. Set PATH or "
        "ANDROID_HOME in the env of the server's config."
    )


# ── adb on the PATH ───────────────────────────────────────────────────────────


def add_sdk_adb_to_path(environ: Optional[MutableMapping[str, str]] = None) -> Optional[str]:
    """Put the Android SDK's platform-tools on the PATH when adb is not on it, and
    return that directory. A desktop app, such as Claude Desktop started from the
    Dock, starts the server with a short PATH that has no adb."""
    environ = os.environ if environ is None else environ
    if shutil.which("adb", path=environ.get("PATH", "")):
        return None
    home = os.path.expanduser("~")
    candidates = [
        os.path.join(environ[var], "platform-tools") for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if environ.get(var)
    ]
    candidates += [
        os.path.join(home, "Library", "Android", "sdk", "platform-tools"),  # macOS
        os.path.join(home, "Android", "Sdk", "platform-tools"),  # Linux
    ]
    if environ.get("LOCALAPPDATA"):
        candidates.append(os.path.join(environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools"))  # Windows
    for directory in candidates:
        if shutil.which("adb", path=directory):
            path = environ.get("PATH")
            environ["PATH"] = directory + os.pathsep + path if path else directory
            return directory
    return None


# ── stdio ─────────────────────────────────────────────────────────────────────


def serve(args: argparse.Namespace, stdin: Optional[BinaryIO] = None, stdout: Optional[BinaryIO] = None) -> int:
    """Answer the messages on stdin until it closes."""
    in_stream = stdin if stdin is not None else sys.stdin.buffer
    out_stream = stdout if stdout is not None else sys.stdout.buffer
    print("lustro {}: MCP server on stdin and stdout".format(__version__), file=sys.stderr)
    added = add_sdk_adb_to_path()
    if added:
        print("adb is not on the PATH: using {}".format(added), file=sys.stderr)
    elif not shutil.which("adb"):
        print("warning: adb is not on the PATH, so the tools can't reach a device", file=sys.stderr)
    server = Server(args)
    protocol_stdout, sys.stdout = sys.stdout, sys.stderr
    try:
        for line in iter(in_stream.readline, b""):
            if not line.strip():
                continue
            reply = server.handle_line(line)
            if reply is not None:
                # ASCII escapes keep the line valid UTF-8, also for a lone surrogate in a body.
                out_stream.write(json.dumps(reply, separators=(",", ":")).encode("ascii") + b"\n")
                out_stream.flush()
    finally:
        sys.stdout = protocol_stdout
    return 0
