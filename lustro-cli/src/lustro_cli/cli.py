"""``lustro`` command-line interface.

Argparse-based dispatch over the Lustro HTTP wire protocol. Every subcommand
maps to a route in ``network.openapi.json`` or a framework route
(``/api/v1/_meta``, ``/api/v1/_schema``, ``/api/v1/<id>/_schema``).
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.parse
import webbrowser
from typing import Any, List, Optional

from . import __version__
from .client import CursorState, LustroClient, LustroError
from .discovery import (
    DEFAULT_HOST,
    DEFAULT_PORT,
    DiscoveryError,
    Endpoint,
    resolve,
)

NETWORK = "/api/v1/network"


# ── output helpers ────────────────────────────────────────────────────────────


def _emit(obj: Any, *, raw_json: bool) -> None:
    """Print a result. ``--json`` always prints compact-ish JSON; otherwise a
    human-readable form (which for dict/list also defaults to pretty JSON)."""
    if raw_json or isinstance(obj, (dict, list)):
        print(json.dumps(obj, indent=2, ensure_ascii=False))
    elif obj is None:
        pass
    else:
        print(obj)


def _print_transaction_row(tx: dict) -> None:
    status = tx.get("statusCode")
    status_str = "ERR" if tx.get("error") else (str(status) if status is not None else "...")
    flags = ""
    if tx.get("isMocked"):
        flags += " [mock]"
    if tx.get("responseComplete") is False:
        flags += " [streaming]"
    print(
        "{ts:>12}  {method:<6} {status:>4}  {url}{flags}".format(
            ts=tx.get("timestamp", ""),
            method=tx.get("method", ""),
            status=status_str,
            url=tx.get("url", ""),
            flags=flags,
        )
    )


# ── client construction (discovery) ───────────────────────────────────────────


def _build_endpoint(args: argparse.Namespace) -> Endpoint:
    return resolve(
        host=args.host,
        port=args.port,
        token=args.token,
        device=args.device,
        package=getattr(args, "package", None),
    )


def _build_client(args: argparse.Namespace) -> LustroClient:
    endpoint = _build_endpoint(args)
    return LustroClient(endpoint.base_url, endpoint.token)


# ── adb helpers ────────────────────────────────────────────────────────────────


def _adb_forward(port: int, device: Optional[str]) -> Optional[str]:
    """Run ``adb forward tcp:<port> tcp:<port>``. Returns an error string or None."""
    cmd = ["adb"]
    if device:
        cmd += ["-s", device]
    cmd += ["forward", "tcp:{}".format(port), "tcp:{}".format(port)]
    try:
        proc = subprocess.run(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False, timeout=10
        )
    except (OSError, subprocess.SubprocessError) as exc:
        return "adb forward failed: {}".format(exc)
    if proc.returncode != 0:
        return proc.stderr.decode("utf-8", "replace").strip() or "adb forward returned non-zero"
    return None


# ── command implementations ────────────────────────────────────────────────────


def cmd_open(args: argparse.Namespace) -> int:
    """Discover token+endpoint, optionally adb-forward, print/open the browser URL."""
    endpoint = _build_endpoint(args)

    # adb forward by default for a device unless explicitly skipped.
    if not args.no_forward:
        err = _adb_forward(endpoint.port, args.device)
        if err and args.device:
            # Only surface forward errors when a device was explicitly targeted;
            # for local-only use it's expected to be absent.
            print("warning: {}".format(err), file=sys.stderr)

    # Browser uses the loopback host (forwarded), regardless of the device bind host.
    browser_host = "localhost" if endpoint.host in ("127.0.0.1", "0.0.0.0", "::1") else endpoint.host
    # Percent-encode the token so special chars (#, &, %, =) can't corrupt the fragment.
    encoded_token = urllib.parse.quote(endpoint.token, safe="")
    url = "{}://{}:{}/#lustro_token={}".format(
        endpoint.scheme, browser_host, endpoint.port, encoded_token
    )
    if args.json:
        _emit({"url": url, "token": endpoint.token, "host": browser_host, "port": endpoint.port}, raw_json=True)
    else:
        print(url)
    if not args.print_only:
        try:
            webbrowser.open(url)
        except Exception:  # pragma: no cover - environment dependent
            pass
    return 0


def cmd_meta(args: argparse.Namespace) -> int:
    client = _build_client(args)
    _emit(client.get("/api/v1/_meta"), raw_json=args.json)
    return 0


def cmd_schema(args: argparse.Namespace) -> int:
    client = _build_client(args)
    if args.tab_id:
        path = "/api/v1/{}/_schema".format(args.tab_id)
    else:
        path = "/api/v1/_schema"
    _emit(client.get(path), raw_json=args.json)
    return 0


# ── net subcommands ────────────────────────────────────────────────────────────


def cmd_net_list(args: argparse.Namespace) -> int:
    client = _build_client(args)
    data = client.get(NETWORK + "/transactions", params={"search": args.search})
    if args.json:
        _emit(data, raw_json=True)
        return 0
    state = data.get("state", {}) if isinstance(data, dict) else {}
    print(
        "state: paused={} overwrite={} throttleMs={}".format(
            state.get("paused"), state.get("overwriteMode"), state.get("throttleDelayMs")
        )
    )
    for tx in data.get("items") or []:
        _print_transaction_row(tx)
    return 0


def cmd_net_poll(args: argparse.Namespace) -> int:
    client = _build_client(args)
    poller = CursorState()
    interval = args.interval
    try:
        while True:
            params = {"cursor": poller.cursor, "search": args.search}
            try:
                data = client.get(NETWORK + "/transactions", params=params)
            except LustroError as exc:
                print("poll error: {}".format(exc), file=sys.stderr)
                if args.once:
                    return 1
                time.sleep(interval)
                continue
            new_items = poller.apply(data)
            if args.json:
                for tx in new_items:
                    print(json.dumps(tx, ensure_ascii=False))
            else:
                for tx in new_items:
                    _print_transaction_row(tx)
            if args.once:
                break
            time.sleep(interval)
    except KeyboardInterrupt:  # pragma: no cover - interactive
        return 0
    return 0


def cmd_net_get(args: argparse.Namespace) -> int:
    client = _build_client(args)
    _emit(client.get(NETWORK + "/transactions/" + args.id), raw_json=args.json)
    return 0


def cmd_net_clear(args: argparse.Namespace) -> int:
    client = _build_client(args)
    _emit(client.post(NETWORK + "/clear"), raw_json=args.json)
    return 0


def cmd_net_pause(args: argparse.Namespace) -> int:
    client = _build_client(args)
    _emit(client.post(NETWORK + "/pause"), raw_json=args.json)
    return 0


def cmd_net_overwrite(args: argparse.Namespace) -> int:
    client = _build_client(args)
    _emit(client.post(NETWORK + "/overwrite-mode"), raw_json=args.json)
    return 0


def cmd_net_throttle(args: argparse.Namespace) -> int:
    client = _build_client(args)
    _emit(client.post(NETWORK + "/throttle", json_body={"delayMs": args.ms}), raw_json=args.json)
    return 0


# ── mock subcommands ───────────────────────────────────────────────────────────


def cmd_mock_list(args: argparse.Namespace) -> int:
    client = _build_client(args)
    data = client.get(NETWORK + "/rules")
    if args.json:
        _emit(data, raw_json=True)
        return 0
    for rule in (data.get("items") or []):
        enabled = "on " if rule.get("enabled") else "off"
        print(
            "{id}  [{enabled}]  {method} {pattern} -> {status}  hits={hits}".format(
                id=rule.get("id"),
                enabled=enabled,
                method=rule.get("method") or "*",
                pattern=rule.get("urlPattern"),
                status=rule.get("statusCode"),
                hits=rule.get("hitCount", 0),
            )
        )
    return 0


def cmd_mock_add(args: argparse.Namespace) -> int:
    client = _build_client(args)
    rule: dict = {"urlPattern": args.url_pattern}
    if args.id is not None:
        rule["id"] = args.id
    if args.name is not None:
        rule["name"] = args.name
    if args.method is not None:
        rule["method"] = args.method
    if args.status is not None:
        rule["statusCode"] = args.status
    if args.body is not None:
        rule["responseBody"] = args.body
    if args.header:
        rule["responseHeaders"] = _parse_headers(args.header)
    _emit(client.post(NETWORK + "/rules", json_body=rule), raw_json=args.json)
    return 0


def cmd_mock_delete(args: argparse.Namespace) -> int:
    client = _build_client(args)
    _emit(client.post(NETWORK + "/rules/delete", json_body={"id": args.id}), raw_json=args.json)
    return 0


def cmd_mock_toggle(args: argparse.Namespace) -> int:
    client = _build_client(args)
    _emit(client.post(NETWORK + "/rules/toggle", json_body={"id": args.id}), raw_json=args.json)
    return 0


def cmd_mock_sync(args: argparse.Namespace) -> int:
    client = _build_client(args)
    try:
        with open(args.file, "r", encoding="utf-8") as fh:
            rules = json.load(fh)
    except (OSError, ValueError) as exc:
        print("could not read rules file {}: {}".format(args.file, exc), file=sys.stderr)
        return 2
    if not isinstance(rules, list):
        print("rules file must contain a JSON array of MockRuleInput objects", file=sys.stderr)
        return 2
    _emit(client.post(NETWORK + "/rules/_/sync", json_body=rules), raw_json=args.json)
    return 0


# ── send ───────────────────────────────────────────────────────────────────────


def cmd_send(args: argparse.Namespace) -> int:
    client = _build_client(args)
    payload: dict = {"url": args.url, "method": args.method}
    if args.header:
        payload["headers"] = _parse_headers(args.header)
    if args.body is not None:
        payload["body"] = args.body
    _emit(client.post(NETWORK + "/send", json_body=payload), raw_json=args.json)
    return 0


def _parse_headers(pairs: List[str]) -> dict:
    headers: dict = {}
    for pair in pairs:
        if ":" not in pair:
            raise SystemExit("invalid --header '{}': expected 'Key: value'".format(pair))
        key, _, value = pair.partition(":")
        headers[key.strip()] = value.strip()
    return headers


# ── argparse wiring ────────────────────────────────────────────────────────────


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="lustro",
        description="CLI client for the Lustro Android debug server (HTTP wire protocol).",
    )
    parser.add_argument("--version", action="version", version="lustro " + __version__)

    # Global flags (shared by all subcommands).
    parser.add_argument("--host", default=None, help="server host (default {})".format(DEFAULT_HOST))
    parser.add_argument("--port", type=int, default=None, help="server port (default {})".format(DEFAULT_PORT))
    parser.add_argument("--token", default=None, help="bearer token (else $LUSTRO_TOKEN, else discovery)")
    parser.add_argument("--device", default=None, metavar="SERIAL", help="adb device serial (adb -s)")
    parser.add_argument("--package", default=None, help="app package id (for run-as token discovery)")
    parser.add_argument("--json", action="store_true", help="raw JSON output")

    sub = parser.add_subparsers(dest="command", metavar="<command>")
    sub.required = True

    # open
    p_open = sub.add_parser("open", help="discover endpoint+token, adb-forward, open browser URL")
    p_open.add_argument("--no-forward", action="store_true", help="skip `adb forward`")
    p_open.add_argument("--print-only", action="store_true", help="print the URL without opening a browser")
    p_open.set_defaults(func=cmd_open)

    # meta
    p_meta = sub.add_parser("meta", help="GET /api/v1/_meta")
    p_meta.set_defaults(func=cmd_meta)

    # schema [tabId]
    p_schema = sub.add_parser("schema", help="GET /api/v1/_schema or /api/v1/<tab>/_schema")
    p_schema.add_argument("tab_id", nargs="?", default=None, metavar="[tabId]")
    p_schema.set_defaults(func=cmd_schema)

    # net ...
    p_net = sub.add_parser("net", help="network tab operations")
    net_sub = p_net.add_subparsers(dest="net_command", metavar="<op>")
    net_sub.required = True

    n_list = net_sub.add_parser("list", help="poll transactions once (GET transactions)")
    n_list.add_argument("--search", default=None, help="filter over URL/method/bodies")
    n_list.set_defaults(func=cmd_net_list)

    n_poll = net_sub.add_parser("poll", help="cursor loop: print new transactions")
    n_poll.add_argument("--search", default=None, help="filter over URL/method/bodies")
    n_poll.add_argument("--interval", type=float, default=1.0, help="seconds between polls (default 1.0)")
    n_poll.add_argument("--once", action="store_true", help="single poll then exit")
    n_poll.set_defaults(func=cmd_net_poll)

    n_get = net_sub.add_parser("get", help="GET transactions/<id>")
    n_get.add_argument("id")
    n_get.set_defaults(func=cmd_net_get)

    n_clear = net_sub.add_parser("clear", help="POST clear")
    n_clear.set_defaults(func=cmd_net_clear)

    n_pause = net_sub.add_parser("pause", help="POST pause (toggle capture-only pause)")
    n_pause.set_defaults(func=cmd_net_pause)

    n_overwrite = net_sub.add_parser("overwrite", help="POST overwrite-mode (toggle)")
    n_overwrite.set_defaults(func=cmd_net_overwrite)

    n_throttle = net_sub.add_parser("throttle", help="POST throttle <ms>")
    n_throttle.add_argument("ms", type=int, help="delay in ms (>= 0)")
    n_throttle.set_defaults(func=cmd_net_throttle)

    # mock ...
    p_mock = sub.add_parser("mock", help="mock rule operations")
    mock_sub = p_mock.add_subparsers(dest="mock_command", metavar="<op>")
    mock_sub.required = True

    m_list = mock_sub.add_parser("list", help="GET rules")
    m_list.set_defaults(func=cmd_mock_list)

    m_add = mock_sub.add_parser("add", help="POST rules (add/upsert)")
    m_add.add_argument("--url-pattern", required=True, dest="url_pattern", help="substring, or regex: prefix")
    m_add.add_argument("--id", default=None, help="stable id (makes the write idempotent/upsert)")
    m_add.add_argument("--name", default=None)
    m_add.add_argument("--method", default=None, help="HTTP method to match (omit = any)")
    m_add.add_argument("--status", type=int, default=None, help="mocked response status code")
    m_add.add_argument("--body", default=None, help="mocked response body")
    m_add.add_argument("--header", action="append", metavar="K:V", help="response header (repeatable)")
    m_add.set_defaults(func=cmd_mock_add)

    m_delete = mock_sub.add_parser("delete", help="POST rules/delete <id>")
    m_delete.add_argument("id")
    m_delete.set_defaults(func=cmd_mock_delete)

    m_toggle = mock_sub.add_parser("toggle", help="POST rules/toggle <id>")
    m_toggle.add_argument("id")
    m_toggle.set_defaults(func=cmd_mock_toggle)

    m_sync = mock_sub.add_parser("sync", help="POST rules/_/sync <file.json> (atomic replace)")
    m_sync.add_argument("file", help="path to a JSON array of MockRuleInput objects")
    m_sync.set_defaults(func=cmd_mock_sync)

    # send
    p_send = sub.add_parser("send", help="POST send (synchronous replay through NetworkSender)")
    p_send.add_argument("--url", required=True, help="absolute URL or path resolved against app base")
    p_send.add_argument("--method", default="GET")
    p_send.add_argument("--header", action="append", metavar="K:V", help="request header (repeatable)")
    p_send.add_argument("--body", default=None, help="request body")
    p_send.set_defaults(func=cmd_send)

    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except DiscoveryError as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 2
    except LustroError as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
