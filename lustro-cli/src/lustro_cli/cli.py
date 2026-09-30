"""``lustro`` command-line interface.

Argparse-based dispatch over the Lustro HTTP wire protocol. Every subcommand
maps to a route in ``network.openapi.json`` or a framework route
(``/api/v1/_meta``, ``/api/v1/_schema``, ``/api/v1/<id>/_schema``).

The output is small by default, because agents read all of it: ``net list``
prints the newest rows only, ``net get`` cuts long bodies, and JSON is compact
unless stdout is a terminal.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import subprocess
import sys
import time
import urllib.parse
import webbrowser
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

from . import __version__, wire
from .client import CursorState, LustroClient, LustroError
from .discovery import (
    DEFAULT_HOST,
    DEFAULT_PORT,
    DiscoveryError,
    Endpoint,
    resolve,
)

NETWORK = "/api/v1/network"
TRANSACTIONS = NETWORK + "/transactions"

# The flags that every command takes, before or after its name, and their values
# when they are not given. See _global_options for why main() sets these.
GLOBAL_DEFAULTS: Dict[str, Any] = {
    "host": None,
    "port": None,
    "token": None,
    "device": None,
    "package": None,
    "json": False,
}

DEFAULT_LAST = 50
DEFAULT_MAX_BODY = 2048


class UsageError(Exception):
    """A request that the command can't carry out as given. main() prints it and exits 2."""


# ── output helpers ────────────────────────────────────────────────────────────


def _dumps(obj: Any, *, indent: bool) -> str:
    if indent:
        return json.dumps(obj, indent=2, ensure_ascii=False)
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def _emit(obj: Any, *, raw_json: bool) -> None:
    """Print a result. ``--json`` output, and any dict or list, prints as JSON:
    indented for a person at a terminal, compact for a pipe or a file."""
    if raw_json or isinstance(obj, (dict, list)):
        print(_dumps(obj, indent=sys.stdout.isatty()))
    elif obj is None:
        pass
    else:
        print(obj)


def _seconds(value: float) -> str:
    return "{:g}".format(value)


# ── transactions: rows, filters, fields ───────────────────────────────────────


def _is_complete(tx: dict) -> bool:
    """Whether the request is over: its response is complete, or it failed.

    ``completedAt`` says so from protocol 1.2; the other two cover a 1.1 server.
    """
    return tx.get("completedAt") is not None or bool(tx.get("error")) or tx.get("responseComplete") is True


def _is_error(tx: dict) -> bool:
    status = tx.get("statusCode")
    return bool(tx.get("error")) or (isinstance(status, int) and status >= 400)


def _format_row(tx: dict, *, update: bool = False) -> str:
    status = tx.get("statusCode")
    if tx.get("error"):
        status_str = "ERR"
    elif status is not None:
        status_str = str(status)
    else:
        status_str = "..."
    duration = tx.get("durationMs")
    flags = ""
    if tx.get("isMocked"):
        flags += " [mock]"
    if status is not None and not _is_complete(tx):
        flags += " [streaming]"
    if update:
        flags += " [update]"
    return "{id}  {ts:>12}  {method:<6} {status:>4} {duration:>7}  {url}{flags}".format(
        id=tx.get("id", ""),
        ts=tx.get("timestamp", ""),
        method=tx.get("method", ""),
        status=status_str,
        duration="-" if duration is None else "{}ms".format(duration),
        url=tx.get("url", ""),
        flags=flags,
    )


def _prints_json_lines(args: argparse.Namespace) -> bool:
    return args.json or args.fields is not None


def _print_transaction(tx: dict, args: argparse.Namespace, *, update: bool = False) -> None:
    """Print one transaction as a table row, or as one compact JSON line. A JSON
    line has no update mark: the later line for an id replaces the earlier one."""
    if _prints_json_lines(args):
        if args.fields is not None:
            tx = {name: tx.get(name) for name in args.fields}
        print(_dumps(tx, indent=False))
    else:
        print(_format_row(tx, update=update))


def _matches(tx: dict, args: argparse.Namespace) -> bool:
    """Whether a transaction passes the client-side filters. ``--search`` ran on the server."""
    if args.method is not None and str(tx.get("method", "")).upper() != args.method.upper():
        return False
    if args.url is not None and args.url.lower() not in str(tx.get("url", "")).lower():
        return False
    if args.status is not None:
        low, high = args.status
        status = tx.get("statusCode")
        if not isinstance(status, int) or not low <= status <= high:
            return False
    return not args.errors or _is_error(tx)


def _check_fields(args: argparse.Namespace, items: Iterable[Any]) -> None:
    """Reject a ``--fields`` key that no transaction has, so that a typo such as
    ``status`` for ``statusCode`` fails instead of printing null on every line.
    A key that the server sends but this CLI doesn't know yet passes."""
    if args.fields is None:
        return
    known = list(wire.load_openapi()["components"]["schemas"]["Transaction"]["properties"])
    seen = set(known)
    for tx in items:
        for key in tx if isinstance(tx, dict) else ():
            if key not in seen:
                seen.add(key)
                known.append(key)
    unknown = [name for name in args.fields if name not in seen]
    if unknown:
        raise UsageError(
            "--fields: transactions have no {}. They have: {}".format(", ".join(unknown), ",".join(known))
        )


def _note_if_paused(data: Any) -> None:
    state = data.get("state") if isinstance(data, dict) else None
    if isinstance(state, dict) and state.get("paused") is True:
        print(
            "note: capture is paused, so new requests are not listed; `lustro net pause` resumes it",
            file=sys.stderr,
        )


def _list_items(data: Any) -> List[dict]:
    items = data.get("items") if isinstance(data, dict) else None
    return [tx for tx in items or [] if isinstance(tx, dict)]


def _cut_bodies(tx: dict, limit: int) -> None:
    """Cut each text body to ``limit`` UTF-8 bytes, and end it with a marker that
    says how many bytes are left out and which command prints them."""
    for direction in ("request", "response"):
        key = direction + "Body"
        text = tx.get(key)
        if not isinstance(text, str):
            continue
        data = text.encode("utf-8", "surrogatepass")
        if len(data) <= limit:
            continue
        cut = limit
        # Keep whole characters: move back to the first byte of the one at the cut.
        while cut > 0 and (data[cut] & 0xC0) == 0x80:
            cut -= 1
        tx[key] = "{}[... {} more bytes: lustro net body {} {}]".format(
            data[:cut].decode("utf-8", "surrogatepass"), len(data) - cut, tx.get("id"), direction
        )


# ── argument types ─────────────────────────────────────────────────────────────


def _status_range(value: str) -> Tuple[int, int]:
    """``--status``: a code such as 404, or a class such as 4xx."""
    text = value.strip().lower()
    if re.fullmatch(r"[1-5]xx", text):
        low = int(text[0]) * 100
        return low, low + 99
    if re.fullmatch(r"[1-5][0-9][0-9]", text):
        return int(text), int(text)
    raise argparse.ArgumentTypeError("expected a status code such as 404, or a class such as 4xx")


def _field_names(value: str) -> List[str]:
    names = [name.strip() for name in value.split(",") if name.strip()]
    if not names:
        raise argparse.ArgumentTypeError("expected keys separated by commas, such as id,statusCode,url")
    return names


def _whole_number(minimum: int):
    def parse(value: str) -> int:
        try:
            number = int(value)
        except ValueError:
            number = minimum - 1
        if number < minimum:
            raise argparse.ArgumentTypeError("expected a whole number of {} or more".format(minimum))
        return number

    return parse


def _positive_seconds(value: str) -> float:
    try:
        seconds = float(value)
    except ValueError:
        seconds = 0.0
    if not (seconds > 0 and math.isfinite(seconds)):
        raise argparse.ArgumentTypeError("expected a number of seconds greater than 0")
    return seconds


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
    """Run ``adb forward tcp:<port> tcp:<port>``. Returns the error, or None when
    the forward works. Raises FileNotFoundError when adb isn't installed."""
    cmd = ["adb"]
    if device:
        cmd += ["-s", device]
    cmd += ["forward", "tcp:{}".format(port), "tcp:{}".format(port)]
    try:
        proc = subprocess.run(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False, timeout=10
        )
    except FileNotFoundError:
        raise
    except (OSError, subprocess.SubprocessError) as exc:
        return str(exc)
    if proc.returncode != 0:
        return proc.stderr.decode("utf-8", "replace").strip() or "adb exited with status {}".format(proc.returncode)
    return None


# ── command implementations ────────────────────────────────────────────────────


def cmd_open(args: argparse.Namespace) -> int:
    """Discover token+endpoint, adb-forward the port, print/open the browser URL."""
    endpoint = _build_endpoint(args)
    # Browser uses the loopback host (forwarded), regardless of the device bind host.
    browser_host = "localhost" if endpoint.host in ("127.0.0.1", "0.0.0.0", "::1") else endpoint.host

    # A LAN host reaches the device without the forward, so only a loopback URL needs it.
    if not args.no_forward and browser_host == "localhost":
        try:
            err = _adb_forward(endpoint.port, args.device)
        except FileNotFoundError:
            # Without adb there is no device to forward to: the server is local.
            print("warning: adb is not installed, so the port was not forwarded", file=sys.stderr)
            err = None
        if err:
            # The URL would reach whatever holds the local port, not the app.
            print("error: adb forward tcp:{0} tcp:{0} failed: {1}".format(endpoint.port, err), file=sys.stderr)
            print(
                "  hint: the console URL would not reach the app. If another process holds "
                "local port {}, stop it. Use --no-forward when you forward the port yourself.".format(endpoint.port),
                file=sys.stderr,
            )
            return 1

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
    data = client.get(TRANSACTIONS, params={"search": args.search})
    _note_if_paused(data)
    items = _list_items(data)
    _check_fields(args, items)
    # The server lists the newest transaction first.
    matched = [tx for tx in items if _matches(tx, args)]
    shown = matched if args.all else matched[: args.last]
    for tx in shown:
        _print_transaction(tx, args)
    if len(shown) < len(matched):
        # stderr, so that the JSON Lines on stdout stay one object per line. The
        # flush puts it after the rows when a reader merges the two streams.
        sys.stdout.flush()
        print(
            "showing the newest {} of {}, use --all for every row".format(len(shown), len(matched)),
            file=sys.stderr,
        )
    return 0


def cmd_net_state(args: argparse.Namespace) -> int:
    """Print the capture state. The wire sends it only with the transaction list."""
    client = _build_client(args)
    data = client.get(TRANSACTIONS)
    state = data.get("state") if isinstance(data, dict) else None
    if not isinstance(state, dict):
        state = {}
    if args.json:
        _emit(state, raw_json=True)
        return 0
    print(
        "paused={} overwriteMode={} throttleDelayMs={}".format(
            *(json.dumps(state.get(key)) for key in ("paused", "overwriteMode", "throttleDelayMs"))
        )
    )
    capture_filter = state.get("captureFilter")
    if isinstance(capture_filter, dict):
        print(
            "capture filter: skipped={} failed={} ({})".format(
                capture_filter.get("skipped"), capture_filter.get("failed"), capture_filter.get("description")
            )
        )
    else:
        print("capture filter: none")
    return 0


def _print_changes(items: List[Any], printed: Dict[Any, bool], args: argparse.Namespace) -> Dict[Any, bool]:
    """Print, oldest first, the matching transactions that aren't printed yet, and
    again those that were in flight when printed and are complete now.

    ``printed`` maps each printed id to whether it was complete. Returns it for
    the transactions still listed.
    """
    listed: Dict[Any, bool] = {}
    for tx in reversed(items):
        if not isinstance(tx, dict) or not _matches(tx, args):
            continue
        tx_id = tx.get("id")
        complete = _is_complete(tx)
        if tx_id not in printed:
            _print_transaction(tx, args)
        elif complete and not printed[tx_id]:
            _print_transaction(tx, args, update=True)
        listed[tx_id] = complete
    return listed


def cmd_net_poll(args: argparse.Namespace) -> int:
    client = _build_client(args)
    poller = CursorState()
    printed: Dict[Any, bool] = {}
    first = True
    try:
        while True:
            params = {"cursor": poller.cursor, "search": args.search}
            try:
                data = client.get(TRANSACTIONS, params=params)
            except LustroError as exc:
                print("poll error: {}".format(exc), file=sys.stderr)
                if args.once:
                    return 1
                time.sleep(args.interval)
                continue
            poller.apply(data)
            if first:
                _note_if_paused(data)
                _check_fields(args, poller.items)
                first = False
            printed = _print_changes(poller.items, printed, args)
            # A reader at the other end of a pipe gets each batch as it comes.
            sys.stdout.flush()
            if args.once:
                break
            time.sleep(args.interval)
    except KeyboardInterrupt:  # pragma: no cover - interactive
        return 0
    return 0


def _run_action(command: List[str], timeout: float) -> Optional[str]:
    """Run the command that makes the app send the awaited request. Returns what
    went wrong, or None. Its output shows only when it fails, so that stdout
    keeps only the match."""
    try:
        proc = subprocess.run(
            command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False, timeout=timeout
        )
    except subprocess.TimeoutExpired:
        return "{} did not finish within {} s".format(command[0], _seconds(timeout))
    except OSError as exc:
        return "could not run {}: {}".format(command[0], exc)
    if proc.returncode != 0:
        output = proc.stdout.decode("utf-8", "replace").strip()
        if output:
            print(output, file=sys.stderr)
        return "{} exited with status {}".format(command[0], proc.returncode)
    return None


def _first_new_match(items: List[Any], finished_before: Set[Any], args: argparse.Namespace) -> Optional[dict]:
    """The matching request that finished first, of those not finished at the first poll."""
    candidates = [
        tx
        for tx in reversed(items)
        if isinstance(tx, dict)
        and tx.get("id") not in finished_before
        and _is_complete(tx)
        and _matches(tx, args)
    ]
    if not candidates:
        return None
    return min(candidates, key=lambda tx: tx.get("completedAt") or 0)


def cmd_net_wait(args: argparse.Namespace) -> int:
    """Wait until a matching request finishes, print it, and exit 0; exit 1 at the timeout."""
    client = _build_client(args)
    deadline = time.monotonic() + args.timeout

    def time_left() -> float:
        # At least one interval, so that the last poll can still get an answer.
        return max(deadline - time.monotonic(), args.interval)

    def poll(cursor: Optional[str]) -> Any:
        # An app that doesn't answer, such as one stopped at a breakpoint, would
        # otherwise hold each request for the client's own timeout.
        client.timeout = time_left()
        return client.get(TRANSACTIONS, params={"cursor": cursor, "search": args.search})

    poller = CursorState()
    data = poll(None)
    _note_if_paused(data)
    poller.apply(data)
    _check_fields(args, poller.items)
    finished_before = {tx.get("id") for tx in _list_items(data) if _is_complete(tx)}
    if args.action:
        error = _run_action(args.action, time_left())
        if error:
            print("error: {}".format(error), file=sys.stderr)
            return 2
    last_error = None
    while True:
        match = _first_new_match(poller.items, finished_before, args)
        if match is not None:
            _print_transaction(match, args)
            return 0
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(args.interval, remaining))
        try:
            poller.apply(poll(poller.cursor))
        except LustroError as exc:
            # The action can put the app in the background for a moment, so keep polling.
            if str(exc) != last_error:
                print("poll error: {}".format(exc), file=sys.stderr)
                last_error = str(exc)
    print("error: no matching request finished within {} s".format(_seconds(args.timeout)), file=sys.stderr)
    earlier = [
        tx for tx in poller.items if isinstance(tx, dict) and tx.get("id") in finished_before and _matches(tx, args)
    ]
    if earlier and not args.action:
        print(
            "  hint: wait ignores requests that finished before it started, and {} of them match "
            "(newest: {}). To catch a fast request, give the action after --, so that wait "
            "starts first.".format(len(earlier), earlier[0].get("id")),
            file=sys.stderr,
        )
    return 1


def cmd_net_get(args: argparse.Namespace) -> int:
    client = _build_client(args)
    tx = client.get(TRANSACTIONS + "/" + args.id)
    if isinstance(tx, dict):
        if args.no_body:
            tx.pop("requestBody", None)
            tx.pop("responseBody", None)
        elif not args.full:
            _cut_bodies(tx, args.max_body)
    _emit(tx, raw_json=args.json)
    return 0


def cmd_net_body(args: argparse.Namespace) -> int:
    """Save one captured body to a file, or write it to stdout."""
    client = _build_client(args)
    tx_path = TRANSACTIONS + "/" + args.id
    # The detail says whether the body is binary and whether capture cut it off.
    tx = client.get(tx_path)
    direction = args.direction
    binary = bool(tx.get(direction + "BodyBinary"))
    if args.output is None and binary and sys.stdout.isatty():
        print(
            "error: the {} body is binary ({}); save it with -o FILE or redirect stdout".format(
                direction, tx.get(direction + "ContentType") or "unknown type"
            ),
            file=sys.stderr,
        )
        return 2
    body = client.get_raw(tx_path + "/body/" + direction)
    if args.output is None:
        sys.stdout.buffer.write(body.data)
        sys.stdout.flush()
    else:
        try:
            with open(args.output, "wb") as fh:
                fh.write(body.data)
        except OSError as exc:
            print("could not write body file {}: {}".format(args.output, exc), file=sys.stderr)
            return 2
    truncated = bool(tx.get(direction + "BodyTruncated"))
    if truncated:
        full = tx.get(direction + "BodyBytes")
        print(
            "warning: capture kept only the first {} bytes of the {} body{}".format(
                len(body.data), direction, "" if full is None else " ({} bytes in full)".format(full)
            ),
            file=sys.stderr,
        )
    if args.output is not None:
        summary = {
            "path": args.output,
            "bytes": len(body.data),
            "contentType": body.content_type,
            "binary": binary,
            "truncated": truncated,
        }
        if args.json:
            _emit(summary, raw_json=True)
        else:
            print("saved {} bytes ({}) to {}".format(len(body.data), body.content_type or "no type", args.output))
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


def _global_options() -> argparse.ArgumentParser:
    """The flags that every command takes, before or after its name.

    Every parser gets them, and each defaults to SUPPRESS: argparse copies a
    subcommand's parsed values over the values parsed before it, so a real
    default there would reset a flag given before the subcommand. main() sets
    the flags that no parser saw to GLOBAL_DEFAULTS.
    """
    parent = argparse.ArgumentParser(add_help=False)
    group = parent.add_argument_group("global options (before or after the command)")
    group.add_argument("--host", default=argparse.SUPPRESS, help="server host (default {})".format(DEFAULT_HOST))
    group.add_argument(
        "--port", type=int, default=argparse.SUPPRESS, help="server port (default {})".format(DEFAULT_PORT)
    )
    group.add_argument("--token", default=argparse.SUPPRESS, help="bearer token (else $LUSTRO_TOKEN, else discovery)")
    group.add_argument("--device", default=argparse.SUPPRESS, metavar="SERIAL", help="adb device serial (adb -s)")
    group.add_argument("--package", default=argparse.SUPPRESS, help="app package id (for run-as token discovery)")
    group.add_argument("--json", action="store_true", default=argparse.SUPPRESS, help="JSON output")
    return parent


def _selection_options() -> argparse.ArgumentParser:
    """Filters and --fields, for the commands that print transactions."""
    parent = argparse.ArgumentParser(add_help=False)
    filters = parent.add_argument_group("filters (they combine)")
    filters.add_argument(
        "--search", default=None, metavar="TEXT", help="the URL, the method, or a body contains TEXT (on the server)"
    )
    filters.add_argument("--url", default=None, metavar="TEXT", help="the URL contains TEXT (ignores case)")
    filters.add_argument("--method", default=None, help="the HTTP method, such as POST")
    filters.add_argument(
        "--status",
        type=_status_range,
        default=None,
        metavar="CODE",
        help="a status code such as 404, or a class such as 4xx",
    )
    filters.add_argument("--errors", action="store_true", help="status 400 or higher, or a failed request")
    output = parent.add_argument_group("output")
    output.add_argument(
        "--fields",
        type=_field_names,
        default=None,
        metavar="KEYS",
        help="print JSON Lines with only these keys, such as id,method,statusCode,url",
    )
    return parent


def build_parser() -> argparse.ArgumentParser:
    common = _global_options()
    selection = _selection_options()
    parser = argparse.ArgumentParser(
        prog="lustro",
        description="CLI client for the Lustro Android debug server (HTTP wire protocol).",
        parents=[common],
    )
    parser.add_argument("--version", action="version", version="lustro " + __version__)

    sub = parser.add_subparsers(dest="command", metavar="<command>")
    sub.required = True

    # open
    p_open = sub.add_parser("open", parents=[common], help="discover endpoint+token, adb-forward, open browser URL")
    p_open.add_argument("--no-forward", action="store_true", help="skip `adb forward`")
    p_open.add_argument("--print-only", action="store_true", help="print the URL without opening a browser")
    p_open.set_defaults(func=cmd_open)

    # meta
    p_meta = sub.add_parser("meta", parents=[common], help="GET /api/v1/_meta")
    p_meta.set_defaults(func=cmd_meta)

    # schema [tabId]
    p_schema = sub.add_parser("schema", parents=[common], help="GET /api/v1/_schema or /api/v1/<tab>/_schema")
    p_schema.add_argument("tab_id", nargs="?", default=None, metavar="[tabId]")
    p_schema.set_defaults(func=cmd_schema)

    # net ...
    p_net = sub.add_parser("net", parents=[common], help="network tab operations")
    net_sub = p_net.add_subparsers(dest="net_command", metavar="<op>")
    net_sub.required = True

    n_list = net_sub.add_parser(
        "list", parents=[common, selection], help="the newest transactions, newest first (GET transactions)"
    )
    rows = n_list.add_mutually_exclusive_group()
    rows.add_argument(
        "--last",
        type=_whole_number(1),
        default=DEFAULT_LAST,
        metavar="N",
        help="print the newest N (default {})".format(DEFAULT_LAST),
    )
    rows.add_argument("--all", action="store_true", help="print every transaction")
    n_list.set_defaults(func=cmd_net_list)

    n_state = net_sub.add_parser(
        "state", parents=[common], help="capture state: paused, overwrite mode, throttle, capture filter"
    )
    n_state.set_defaults(func=cmd_net_state)

    n_poll = net_sub.add_parser(
        "poll",
        parents=[common, selection],
        help="cursor loop: print transactions as they come, and again when they finish",
    )
    n_poll.add_argument(
        "--interval", type=_positive_seconds, default=1.0, metavar="SECONDS", help="seconds between polls (default 1)"
    )
    n_poll.add_argument("--once", action="store_true", help="single poll then exit")
    n_poll.set_defaults(func=cmd_net_poll)

    n_wait = net_sub.add_parser(
        "wait",
        parents=[common, selection],
        help="wait until a matching request finishes, print it, and exit",
        description="Wait until a matching request finishes, print it, and exit 0; exit 1 at the timeout. "
        "Requests that finished before wait started don't count, so start it before the action that sends "
        "the request, or give that action after --: wait then runs it after it reads the list.",
    )
    n_wait.add_argument(
        "--timeout", type=_positive_seconds, default=30.0, metavar="SECONDS", help="exit 1 after this time (default 30)"
    )
    n_wait.add_argument(
        "--interval", type=_positive_seconds, default=0.5, metavar="SECONDS", help="seconds between polls (default 0.5)"
    )
    n_wait.add_argument(
        "action",
        nargs="*",
        metavar="-- COMMAND",
        help="a command that makes the app send the request, run after the first poll",
    )
    n_wait.set_defaults(func=cmd_net_wait)

    n_get = net_sub.add_parser("get", parents=[common], help="GET transactions/<id>, with long bodies cut")
    n_get.add_argument("id")
    bodies = n_get.add_mutually_exclusive_group()
    bodies.add_argument(
        "--max-body",
        type=_whole_number(0),
        default=DEFAULT_MAX_BODY,
        metavar="BYTES",
        help="cut each body after BYTES (default {})".format(DEFAULT_MAX_BODY),
    )
    bodies.add_argument("--no-body", action="store_true", help="leave the bodies out")
    bodies.add_argument("--full", action="store_true", help="print the bodies whole")
    n_get.set_defaults(func=cmd_net_get)

    n_body = net_sub.add_parser(
        "body",
        parents=[common],
        help="GET transactions/<id>/body/<direction>: save a captured body as it is stored",
    )
    n_body.add_argument("id")
    n_body.add_argument(
        "direction", nargs="?", default="response", choices=["request", "response"], help="default: response"
    )
    n_body.add_argument("-o", "--output", default=None, metavar="FILE", help="write the body to FILE (default: stdout)")
    n_body.set_defaults(func=cmd_net_body)

    n_clear = net_sub.add_parser("clear", parents=[common], help="POST clear")
    n_clear.set_defaults(func=cmd_net_clear)

    n_pause = net_sub.add_parser("pause", parents=[common], help="POST pause (toggle capture-only pause)")
    n_pause.set_defaults(func=cmd_net_pause)

    n_overwrite = net_sub.add_parser("overwrite", parents=[common], help="POST overwrite-mode (toggle)")
    n_overwrite.set_defaults(func=cmd_net_overwrite)

    n_throttle = net_sub.add_parser("throttle", parents=[common], help="POST throttle <ms>")
    n_throttle.add_argument("ms", type=int, help="delay in ms (>= 0)")
    n_throttle.set_defaults(func=cmd_net_throttle)

    # mock ...
    p_mock = sub.add_parser("mock", parents=[common], help="mock rule operations")
    mock_sub = p_mock.add_subparsers(dest="mock_command", metavar="<op>")
    mock_sub.required = True

    m_list = mock_sub.add_parser("list", parents=[common], help="GET rules")
    m_list.set_defaults(func=cmd_mock_list)

    m_add = mock_sub.add_parser("add", parents=[common], help="POST rules (add/upsert)")
    m_add.add_argument("--url-pattern", required=True, dest="url_pattern", help="substring, or regex: prefix")
    m_add.add_argument("--id", default=None, help="stable id (makes the write idempotent/upsert)")
    m_add.add_argument("--name", default=None)
    m_add.add_argument("--method", default=None, help="HTTP method to match (omit = any)")
    m_add.add_argument("--status", type=int, default=None, help="mocked response status code")
    m_add.add_argument("--body", default=None, help="mocked response body")
    m_add.add_argument("--header", action="append", metavar="K:V", help="response header (repeatable)")
    m_add.set_defaults(func=cmd_mock_add)

    m_delete = mock_sub.add_parser("delete", parents=[common], help="POST rules/delete <id>")
    m_delete.add_argument("id")
    m_delete.set_defaults(func=cmd_mock_delete)

    m_toggle = mock_sub.add_parser("toggle", parents=[common], help="POST rules/toggle <id>")
    m_toggle.add_argument("id")
    m_toggle.set_defaults(func=cmd_mock_toggle)

    m_sync = mock_sub.add_parser("sync", parents=[common], help="POST rules/_/sync <file.json> (atomic replace)")
    m_sync.add_argument("file", help="path to a JSON array of MockRuleInput objects")
    m_sync.set_defaults(func=cmd_mock_sync)

    # send
    p_send = sub.add_parser("send", parents=[common], help="POST send (synchronous replay through NetworkSender)")
    p_send.add_argument("--url", required=True, help="absolute URL or path resolved against app base")
    p_send.add_argument("--method", default="GET")
    p_send.add_argument("--header", action="append", metavar="K:V", help="request header (repeatable)")
    p_send.add_argument("--body", default=None, help="request body")
    p_send.set_defaults(func=cmd_send)

    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    for name, default in GLOBAL_DEFAULTS.items():
        if not hasattr(args, name):
            setattr(args, name, default)
    try:
        code = args.func(args)
        # Flush here, not at exit, so that a reader that closed the pipe early
        # reaches the BrokenPipeError handler below.
        sys.stdout.flush()
        return code
    except UsageError as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 2
    except DiscoveryError as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 2
    except LustroError as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 1
    except BrokenPipeError:
        # The reader closed the pipe early, as `head` does. Send the rest of the
        # output to devnull, so that the flush at exit doesn't fail again.
        devnull = os.open(os.devnull, os.O_WRONLY)
        os.dup2(devnull, sys.stdout.fileno())
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
