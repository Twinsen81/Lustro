"""``lustro`` command-line interface.

Argparse-based dispatch over the Lustro HTTP wire protocol. Every subcommand
maps to a route in ``network.openapi.json`` or a framework route
(``/api/v1/_meta``, ``/api/v1/_schema``, ``/api/v1/<id>/_schema``).

The output is small by default, because agents read all of it: ``net list``
prints the newest rows only, a row shows a short id, ``net get`` cuts long
bodies, and JSON is compact unless stdout is a terminal.
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
    LOOPBACK_HOSTS,
    DiscoveryError,
    Endpoint,
    forwarded_ports,
    resolve,
    token_from_prefs,
)

NETWORK = "/api/v1/network"
TRANSACTIONS = NETWORK + "/transactions"
EXPORT = TRANSACTIONS + "/_/export"
WEBSOCKETS = NETWORK + "/websockets"

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
SHORT_ID_LENGTH = 8
# How many of the matches an ambiguous id prints.
AMBIGUOUS_ROWS = 10
ID_HELP = "a transaction id, or its start, such as the short id of a row"
WS_ID_HELP = "a connection id, or its start, such as the short id of a row"
# More than a log can hold, so that the server sends all of it.
ALL_EVENTS = 1_000_000
# How many ids one export request names. The server takes a request line and
# headers of up to 8 KB, and a full id is 36 characters.
EXPORT_BATCH = 50

# The Lustro runtime makes each transaction id with UUID.randomUUID(). An id of
# this shape goes to the server as it is, without the list request that a
# shorter one needs.
_FULL_ID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


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


def _short_id_length(items: Iterable[Any]) -> int:
    """How many characters of each id the rows show: 8, or more when two of the
    listed ids start with the same 8, so that each row's id names one transaction."""
    ids = sorted({tx["id"] for tx in items if isinstance(tx, dict) and isinstance(tx.get("id"), str)})
    length = SHORT_ID_LENGTH
    # In sorted order, the two ids with the longest common start are neighbours.
    for first, second in zip(ids, ids[1:]):
        length = max(length, len(os.path.commonprefix([first, second])) + 1)
    return length


def _format_row(tx: dict, id_length: Optional[int] = None, *, update: bool = False) -> str:
    """One table row. The id keeps its first ``id_length`` characters, or all of them."""
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
    if tx.get("throttledMs"):
        flags += " [throttled {}ms]".format(tx["throttledMs"])
    if status is not None and not _is_complete(tx):
        flags += " [streaming]"
    if update:
        flags += " [update]"
    return "{id}  {ts:>12}  {method:<6} {status:>4} {duration:>7}  {url}{flags}".format(
        id=str(tx.get("id", ""))[:id_length],
        ts=tx.get("timestamp", ""),
        method=tx.get("method", ""),
        status=status_str,
        duration="-" if duration is None else "{}ms".format(duration),
        url=tx.get("url", ""),
        flags=flags,
    )


def _prints_json_lines(args: argparse.Namespace) -> bool:
    return args.json or args.fields is not None


def _print_transaction(tx: dict, args: argparse.Namespace, id_length: int, *, update: bool = False) -> None:
    """Print one transaction as a table row with a short id, or as one compact
    JSON line with the full id. A JSON line has no update mark: the later line
    for an id replaces the earlier one."""
    if _prints_json_lines(args):
        if args.fields is not None:
            tx = {name: tx.get(name) for name in args.fields}
        print(_dumps(tx, indent=False))
    else:
        print(_format_row(tx, id_length, update=update))


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


def _check_fields(
    args: argparse.Namespace, items: Iterable[Any], *, component: str = "Transaction", noun: str = "transactions"
) -> None:
    """Reject a ``--fields`` key that no item has, so that a typo such as
    ``status`` for ``statusCode`` fails instead of printing null on every line.
    A key that the server sends but this CLI doesn't know yet passes."""
    if args.fields is None:
        return
    known = list(wire.load_openapi()["components"]["schemas"][component]["properties"])
    seen = set(known)
    for tx in items:
        for key in tx if isinstance(tx, dict) else ():
            if key not in seen:
                seen.add(key)
                known.append(key)
    unknown = [name for name in args.fields if name not in seen]
    if unknown:
        raise UsageError(
            "--fields: {} have no {}. They have: {}".format(noun, ", ".join(unknown), ",".join(known))
        )


def _note_if_paused(data: Any, what: str = "new requests are not listed") -> None:
    state = data.get("state") if isinstance(data, dict) else None
    if isinstance(state, dict) and state.get("paused") is True:
        print(
            "note: capture is paused, so {}; `lustro net pause` resumes it".format(what),
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


def _resolve_id(client: LustroClient, given: str) -> str:
    """The full id of the transaction that ``given`` names: the id itself, or
    the start of only one id, such as the short id of a row. A start costs one
    list request, because the wire routes take only a full id."""
    if not given:
        raise UsageError("expected a transaction id, or the start of one")
    return _resolve_ids(client, [given])[0]


def _resolve_ids(client: LustroClient, given: List[str]) -> List[str]:
    """The full id of each transaction in ``given``, as _resolve_id finds it.
    One list request covers every start."""
    items: Optional[List[dict]] = None
    resolved = []
    for start in given:
        if _FULL_ID.fullmatch(start):
            resolved.append(start)
            continue
        if items is None:
            items = _list_items(client.get(TRANSACTIONS))
        resolved.append(_match_id(items, start))
    return resolved


def _match_id(
    items: List[dict], given: str, *, noun: str = "transaction", list_command: str = "lustro net list", row=None
) -> str:
    matches = [tx for tx in items if isinstance(tx.get("id"), str) and tx["id"].startswith(given)]
    # An id of another shape can also be the start of a longer one.
    if any(tx["id"] == given for tx in matches):
        return given
    if len(matches) == 1:
        return matches[0]["id"]
    if not matches:
        raise LustroError(
            "not_found",
            "no {} id starts with {}".format(noun, given),
            hint="`{}` prints the {}s that the app has now".format(list_command, noun),
        )
    rows = [(row or _format_row)(tx) for tx in matches[:AMBIGUOUS_ROWS]]
    if len(matches) > AMBIGUOUS_ROWS:
        rows.append("and {} more".format(len(matches) - AMBIGUOUS_ROWS))
    raise UsageError(
        "{} {} ids start with {}. Give more characters of the id:\n  {}".format(
            len(matches), noun, given, "\n  ".join(rows)
        )
    )


# ── WebSockets: rows ───────────────────────────────────────────────────────────


def _ws_state(connection: dict) -> str:
    """The state as a row shows it: with the close code, or with what made the socket fail."""
    state = str(connection.get("state", ""))
    if state == "closed" and connection.get("closeCode") is not None:
        return "closed {}".format(connection["closeCode"])
    if state == "failed":
        if connection.get("canceled"):
            return "canceled"
        status = connection.get("statusCode")
        if isinstance(status, int) and status != 101:
            return "failed {}".format(status)
    return state


def _format_ws_row(connection: dict, id_length: Optional[int] = None) -> str:
    """One table row. The id keeps its first ``id_length`` characters, or all of them."""
    return "{id}  {ts:>12}  {state:<11} sent={sent} received={received}  {url}".format(
        id=str(connection.get("id", ""))[:id_length],
        ts=connection.get("timestamp", ""),
        state=_ws_state(connection),
        sent=connection.get("sentCount", 0),
        received=connection.get("receivedCount", 0),
        url=connection.get("url", ""),
    )


def _one_line(text: str) -> str:
    return " ".join(text.split())


def _format_event_row(event: dict, connection_id: str) -> str:
    """One row of a connection's log. A message shows its direction, type, size,
    and the start of its payload; a lifecycle event shows what happened."""
    head = "{seq:>5}  {ts:>12}  ".format(seq=event.get("seq", ""), ts=event.get("timestamp", ""))
    kind = event.get("kind")
    sent = event.get("direction") == "sent"
    if kind != "message":
        parts = [str(kind)]
        if kind == "close":
            parts.append("sent" if sent else "received")
        for key in ("code", "statusCode", "reason", "error"):
            if event.get(key) not in (None, ""):
                parts.append(str(event[key]))
        if event.get("enqueued") is False:
            parts.append("[refused]")
        return head + "--  " + " ".join(parts)
    if event.get("type") == "binary":
        text = str(event.get("hexPreview", ""))
    else:
        text = _one_line(str(event.get("preview", "")))
        if event.get("previewComplete") is False:
            # The same marker as a cut body: what is left out, and the command that prints it.
            more = int(event.get("payloadBytes") or 0) - len(str(event.get("preview", "")).encode("utf-8", "surrogatepass"))
            text += "[... {} more bytes: lustro net ws payload {} {}]".format(
                max(more, 0), connection_id[:SHORT_ID_LENGTH], event.get("seq")
            )
    flags = ""
    if event.get("enqueued") is False:
        flags += " [not sent]"
    if event.get("truncated"):
        flags += " [truncated]"
    if event.get("stored") is False:
        flags += " [not stored]"
    return head + "{arrow}  {type:<6} {size:>9}  {text}{flags}".format(
        arrow="->" if sent else "<-",
        type=event.get("type", ""),
        size="{}B".format(event.get("payloadBytes", 0)),
        text=text,
        flags=flags,
    )


def _resolve_ws_id(client: LustroClient, given: str) -> str:
    """The full id of the connection that ``given`` names, as _resolve_id finds a transaction's."""
    if not given:
        raise UsageError("expected a connection id, or the start of one")
    if _FULL_ID.fullmatch(given):
        return given
    return _match_id(
        _list_items(client.get(WEBSOCKETS)),
        given,
        noun="connection",
        list_command="lustro net ws list",
        row=_format_ws_row,
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


def _id_list(value: str) -> List[str]:
    ids = [part.strip() for part in value.split(",") if part.strip()]
    if not ids:
        raise argparse.ArgumentTypeError("expected transaction ids, or their starts, separated by commas")
    return ids


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


def _port_number(value: str) -> int:
    try:
        number = int(value)
    except ValueError:
        number = -1
    if not 0 <= number <= 65535:
        raise argparse.ArgumentTypeError("expected a port number from 0 to 65535")
    return number


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
    """The app's endpoint: from the flags, $LUSTRO_TOKEN, and the LustroToken line."""
    return resolve(
        host=args.host,
        port=args.port,
        token=args.token,
        device=args.device,
        package=getattr(args, "package", None),
    )


def _token_was_discovered(args: argparse.Namespace) -> bool:
    return args.token is None and not os.environ.get("LUSTRO_TOKEN")


def _with_current_token(args: argparse.Namespace, endpoint: Endpoint) -> Endpoint:
    """The endpoint with the app's current token, from its prefs, when that
    differs from a discovered one. The ready line in the log is from an earlier
    install when the device dropped the new one. The browser gets no second try,
    so ``open`` checks before it prints the URL."""
    if not _token_was_discovered(args):
        return endpoint
    current = token_from_prefs(args.device, getattr(args, "package", None))
    return endpoint._replace(token=current) if current and current != endpoint.token else endpoint


def _build_client(args: argparse.Namespace) -> LustroClient:
    endpoint = _build_endpoint(args)
    # The app's port is on the device. Without --port, connect through the adb
    # forward to it, which `lustro open --local-port` can put on another local port.
    if args.port is None and endpoint.host in LOOPBACK_HOSTS:
        local_ports = forwarded_ports(endpoint.port, args.device)
        if local_ports:
            endpoint = endpoint._replace(host=DEFAULT_HOST, port=local_ports[0])
    # A token from the ready line is stale when the app was installed again and
    # the device dropped the new line. On a 401, the client tries the app's prefs.
    discovered = _token_was_discovered(args)
    refresh = (lambda: token_from_prefs(args.device, getattr(args, "package", None))) if discovered else None
    return LustroClient(endpoint.base_url, endpoint.token, refresh_token=refresh)


# ── adb helpers ────────────────────────────────────────────────────────────────


def _adb_forward(local_port: int, device_port: int, device: Optional[str]) -> Tuple[int, Optional[str]]:
    """Run ``adb forward --no-rebind tcp:<local_port> tcp:<device_port>``. Returns
    the local port, which adb chooses when ``local_port`` is 0, and the error, or
    None when the forward works. Raises FileNotFoundError when adb isn't installed.

    --no-rebind keeps adb from taking over a local port that another device's
    forward holds."""
    cmd = ["adb"]
    if device:
        cmd += ["-s", device]
    cmd += ["forward", "--no-rebind", "tcp:{}".format(local_port), "tcp:{}".format(device_port)]
    try:
        proc = subprocess.run(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False, timeout=10
        )
    except FileNotFoundError:
        raise
    except (OSError, subprocess.SubprocessError) as exc:
        return local_port, str(exc)
    if proc.returncode != 0:
        return local_port, (
            proc.stderr.decode("utf-8", "replace").strip() or "adb exited with status {}".format(proc.returncode)
        )
    if local_port == 0:
        chosen = proc.stdout.decode("utf-8", "replace").strip()
        if not chosen.isdigit():
            return 0, "adb did not print the local port that it chose: {!r}".format(chosen)
        return int(chosen), None
    return local_port, None


def _forward_local_port(args: argparse.Namespace, device_port: int) -> Optional[int]:
    """Choose the local port for the console URL, and forward it to the app's
    ``device_port``. Returns the local port, or None after it prints why the
    forward failed."""
    if args.no_forward:
        if args.local_port == 0:
            raise UsageError("--local-port 0 lets adb choose the port, so it can't go with --no-forward")
        return device_port if args.local_port is None else args.local_port
    # Without a port number from --local-port, keep an existing forward to the app.
    forwarded = forwarded_ports(device_port, args.device)
    if args.local_port:
        local_port = args.local_port
    elif forwarded:
        local_port = forwarded[0]
    else:
        local_port = device_port if args.local_port is None else 0
    # adb forward --no-rebind fails for a forward that is there already.
    if local_port in forwarded:
        return local_port
    try:
        local_port, err = _adb_forward(local_port, device_port, args.device)
    except FileNotFoundError:
        if args.local_port is not None:
            print("error: adb is not installed, so --local-port can't forward a port", file=sys.stderr)
            return None
        # Without adb there is no device to forward to: the server is local.
        print("warning: adb is not installed, so the port was not forwarded", file=sys.stderr)
        return local_port
    if err:
        # The URL would reach whatever holds the local port, not the app.
        print("error: adb forward tcp:{} tcp:{} failed: {}".format(local_port, device_port, err), file=sys.stderr)
        if local_port:
            print(
                "  hint: the console URL would not reach the app. If local port {} is taken, pass "
                "--local-port 0 to let adb choose a free port, or --local-port N. Use --no-forward when "
                "you forward the port yourself.".format(local_port),
                file=sys.stderr,
            )
        return None
    return local_port


# ── command implementations ────────────────────────────────────────────────────


def cmd_open(args: argparse.Namespace) -> int:
    """Discover token+endpoint, forward a local port to the app, print/open the browser URL."""
    endpoint = _with_current_token(args, _build_endpoint(args))
    browser_host = endpoint.host
    port = endpoint.port
    # A LAN host reaches the device without a forward, so only a loopback URL needs one.
    if endpoint.host in LOOPBACK_HOSTS:
        # The browser uses the local end of the forward, whatever host the app binds on the device.
        browser_host = "localhost"
        port = _forward_local_port(args, endpoint.port)
        if port is None:
            return 1

    # Percent-encode the token so special chars (#, &, %, =) can't corrupt the fragment.
    encoded_token = urllib.parse.quote(endpoint.token, safe="")
    url = "{}://{}:{}/#lustro_token={}".format(
        endpoint.scheme, browser_host, port, encoded_token
    )
    if args.json:
        _emit({"url": url, "token": endpoint.token, "host": browser_host, "port": port}, raw_json=True)
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
    id_length = _short_id_length(items)
    for tx in shown:
        _print_transaction(tx, args, id_length)
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
    id_length = _short_id_length(items)
    for tx in reversed(items):
        if not isinstance(tx, dict) or not _matches(tx, args):
            continue
        tx_id = tx.get("id")
        complete = _is_complete(tx)
        if tx_id not in printed:
            _print_transaction(tx, args, id_length)
        elif complete and not printed[tx_id]:
            _print_transaction(tx, args, id_length, update=True)
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
            _print_transaction(match, args, _short_id_length(poller.items))
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
    tx = client.get(TRANSACTIONS + "/" + _resolve_id(client, args.id))
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
    tx_path = TRANSACTIONS + "/" + _resolve_id(client, args.id)
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


def _export_entries(har: Any) -> List[Any]:
    entries = har.get("log", {}).get("entries") if isinstance(har, dict) else None
    if not isinstance(entries, list):
        raise LustroError("invalid_response", "the export is not a HAR document: it has no log.entries")
    return entries


def _export_har(client: LustroClient, ids: Optional[List[str]]) -> Any:
    """The HAR document of the transactions that ``ids`` names, or of every one.
    Many ids go in batches, and the entries of each batch join the first one's."""
    if ids is None:
        har = client.get(EXPORT, params={"format": "har"})
        _export_entries(har)
        return har
    har = None
    for start in range(0, len(ids), EXPORT_BATCH):
        batch = client.get(EXPORT, params={"format": "har", "ids": ",".join(ids[start : start + EXPORT_BATCH])})
        entries = _export_entries(batch)
        if har is None:
            har = batch
        else:
            _export_entries(har).extend(entries)
    # Each batch is oldest first, and the times are ISO 8601 in UTC, so they sort as text.
    _export_entries(har).sort(key=lambda entry: str(entry.get("startedDateTime", "")))
    return har


def cmd_net_export(args: argparse.Namespace) -> int:
    """Save captured transactions as a HAR file, or write it to stdout."""
    client = _build_client(args)
    ids = None
    if args.ids is not None:
        # In the order given, each id once.
        ids = list(dict.fromkeys(_resolve_ids(client, [given for group in args.ids for given in group])))
    har = _export_har(client, ids)
    entries = _export_entries(har)
    # Indented, as browser devtools write a HAR file, and as the console saves one.
    data = (json.dumps(har, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    if args.har == "-":
        sys.stdout.buffer.write(data)
        sys.stdout.flush()
    else:
        try:
            with open(args.har, "wb") as fh:
                fh.write(data)
        except OSError as exc:
            print("could not write HAR file {}: {}".format(args.har, exc), file=sys.stderr)
            return 2
    exported = {entry.get("_lustro", {}).get("id") for entry in entries if isinstance(entry, dict)}
    missing = [tx_id for tx_id in ids or [] if tx_id not in exported]
    if missing:
        print(
            "warning: the app no longer has {} of the transactions, so the file leaves them out: {}".format(
                len(missing), ", ".join(missing)
            ),
            file=sys.stderr,
        )
    if args.har != "-":
        if args.json:
            _emit({"path": args.har, "entries": len(entries), "bytes": len(data), "missing": missing}, raw_json=True)
        else:
            print("saved {} transactions ({} bytes) to {}".format(len(entries), len(data), args.har))
    return 0


# ── net ws subcommands ─────────────────────────────────────────────────────────


def cmd_net_ws_list(args: argparse.Namespace) -> int:
    client = _build_client(args)
    data = client.get(WEBSOCKETS)
    _note_if_paused(data, "no new socket is listed and no message is recorded")
    items = _list_items(data)
    _check_fields(args, items, component="WebSocketConnection", noun="connections")
    id_length = _short_id_length(items)
    # The server lists the newest connection first.
    for connection in items:
        if _prints_json_lines(args):
            if args.fields is not None:
                connection = {name: connection.get(name) for name in args.fields}
            print(_dumps(connection, indent=False))
        else:
            print(_format_ws_row(connection, id_length))
    return 0


def cmd_net_ws_get(args: argparse.Namespace) -> int:
    client = _build_client(args)
    _emit(client.get(WEBSOCKETS + "/" + _resolve_ws_id(client, args.id)), raw_json=args.json)
    return 0


def _print_events(items: List[dict], connection_id: str, args: argparse.Namespace) -> None:
    for event in items:
        if _prints_json_lines(args):
            if args.fields is not None:
                event = {name: event.get(name) for name in args.fields}
            print(_dumps(event, indent=False))
        else:
            print(_format_event_row(event, connection_id))


def cmd_net_ws_events(args: argparse.Namespace) -> int:
    """Print a connection's log, oldest first, and with --follow each new event as it comes."""
    client = _build_client(args)
    connection_id = _resolve_ws_id(client, args.id)
    path = WEBSOCKETS + "/" + connection_id + "/events"
    filters = {
        "direction": "sent" if args.sent else "received" if args.received else None,
        "search": args.search,
    }
    data = client.get(path, params=dict(filters, limit=ALL_EVENTS if args.all else args.last))
    items = _list_items(data)
    _check_fields(args, items, component="WebSocketEvent", noun="events")
    _print_events(items, connection_id, args)
    if not args.follow:
        return 0
    cursor = data.get("cursor") if isinstance(data, dict) else None
    try:
        while True:
            # A reader at the other end of a pipe gets each batch as it comes.
            sys.stdout.flush()
            time.sleep(args.interval)
            try:
                data = client.get(path, params=dict(filters, cursor=cursor, limit=ALL_EVENTS))
            except LustroError as exc:
                if exc.error == "not_found":
                    # The app cleared or evicted the connection.
                    raise
                print("poll error: {}".format(exc), file=sys.stderr)
                continue
            if not isinstance(data, dict):
                continue
            status = data.get("status")
            if status != "unchanged":
                if status != "delta":
                    print("note: the log started again, so these are its last events", file=sys.stderr)
                elif data.get("dropped"):
                    print(
                        "note: the log evicted {} events before this command got them".format(data["dropped"]),
                        file=sys.stderr,
                    )
                _print_events(_list_items(data), connection_id, args)
            cursor = data.get("cursor", cursor)
    except KeyboardInterrupt:  # pragma: no cover - interactive
        return 0


def cmd_net_ws_payload(args: argparse.Namespace) -> int:
    """Save one message's payload to a file, or write it to stdout."""
    client = _build_client(args)
    connection_id = _resolve_ws_id(client, args.id)
    payload = client.get_raw("{}/{}/events/{}/payload".format(WEBSOCKETS, connection_id, args.seq))
    binary = not str(payload.content_type or "").startswith("text/")
    if args.output is None and binary and sys.stdout.isatty():
        print("error: the payload is binary; save it with -o FILE or redirect stdout", file=sys.stderr)
        return 2
    if args.output is None:
        sys.stdout.buffer.write(payload.data)
        sys.stdout.flush()
        return 0
    try:
        with open(args.output, "wb") as fh:
            fh.write(payload.data)
    except OSError as exc:
        print("could not write payload file {}: {}".format(args.output, exc), file=sys.stderr)
        return 2
    if args.json:
        _emit({"path": args.output, "bytes": len(payload.data), "binary": binary}, raw_json=True)
    else:
        print("saved {} bytes ({}) to {}".format(len(payload.data), "binary" if binary else "text", args.output))
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


def _fields_option() -> argparse.ArgumentParser:
    """--fields, for the commands that print a list of other items."""
    parent = argparse.ArgumentParser(add_help=False)
    parent.add_argument_group("output").add_argument(
        "--fields",
        type=_field_names,
        default=None,
        metavar="KEYS",
        help="print JSON Lines with only these keys, such as id,state,url",
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
    p_open.add_argument(
        "--local-port",
        type=_port_number,
        default=None,
        metavar="N",
        help="forward local port N to the app's port; 0 lets adb choose a free port "
        "(default: the local port of an existing forward to the app, else the app's port)",
    )
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
    n_get.add_argument("id", help=ID_HELP)
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
    n_body.add_argument("id", help=ID_HELP)
    n_body.add_argument(
        "direction", nargs="?", default="response", choices=["request", "response"], help="default: response"
    )
    n_body.add_argument("-o", "--output", default=None, metavar="FILE", help="write the body to FILE (default: stdout)")
    n_body.set_defaults(func=cmd_net_body)

    n_export = net_sub.add_parser(
        "export",
        parents=[common],
        help="GET transactions/_/export: save transactions as a HAR file",
        description="Save the captured transactions as a HAR 1.2 file, which browser devtools and HTTP tools "
        "import. Headers and bodies are redacted as in the app. Each entry has the transaction id, and what HAR "
        "has no field for, in _lustro.",
    )
    n_export.add_argument(
        "--har", required=True, metavar="FILE", help="write the HAR document to FILE, or to stdout for -"
    )
    n_export.add_argument(
        "--ids",
        nargs="+",
        type=_id_list,
        default=None,
        metavar="ID",
        help="only these transactions, separated by spaces or commas; each is {} (default: every one)".format(ID_HELP),
    )
    n_export.set_defaults(func=cmd_net_export)

    n_ws = net_sub.add_parser(
        "ws",
        parents=[common],
        help="WebSocket connections and their messages",
        description="Inspect the WebSockets that the app creates with the factory from Lustro.webSocketFactory. "
        "A socket that the app creates in another way shows only its handshake, in `lustro net list`.",
    )
    ws_sub = n_ws.add_subparsers(dest="ws_command", metavar="<op>")
    ws_sub.required = True
    fields = _fields_option()

    w_list = ws_sub.add_parser("list", parents=[common, fields], help="the connections, newest first (GET websockets)")
    w_list.set_defaults(func=cmd_net_ws_list)

    w_get = ws_sub.add_parser(
        "get", parents=[common], help="GET websockets/<id>: one connection, with the headers of its handshake"
    )
    w_get.add_argument("id", help=WS_ID_HELP)
    w_get.set_defaults(func=cmd_net_ws_get)

    w_events = ws_sub.add_parser(
        "events",
        parents=[common, fields],
        help="GET websockets/<id>/events: a connection's messages and lifecycle events, oldest first",
        description="Print a connection's log, oldest first: each message with its direction, type, size, and the "
        "start of its payload, and each lifecycle event. `->` is a message the app sent. OkHttp queued it, which "
        "does not show that the server received it.",
    )
    w_events.add_argument("id", help=WS_ID_HELP)
    event_rows = w_events.add_mutually_exclusive_group()
    event_rows.add_argument(
        "--last",
        type=_whole_number(1),
        default=DEFAULT_LAST,
        metavar="N",
        help="print the last N (default {})".format(DEFAULT_LAST),
    )
    event_rows.add_argument("--all", action="store_true", help="print every event the log has")
    event_direction = w_events.add_mutually_exclusive_group()
    event_direction.add_argument("--sent", action="store_true", help="only what the app sent")
    event_direction.add_argument("--received", action="store_true", help="only what the app received")
    w_events.add_argument(
        "--search", default=None, metavar="TEXT", help="only the text messages that contain TEXT (on the server)"
    )
    w_events.add_argument("--follow", action="store_true", help="then print each new event as it comes, until Ctrl-C")
    w_events.add_argument(
        "--interval",
        type=_positive_seconds,
        default=1.0,
        metavar="SECONDS",
        help="with --follow: seconds between polls (default 1)",
    )
    w_events.set_defaults(func=cmd_net_ws_events)

    w_payload = ws_sub.add_parser(
        "payload",
        parents=[common],
        help="GET websockets/<id>/events/<seq>/payload: save one message's payload as it is stored",
    )
    w_payload.add_argument("id", help=WS_ID_HELP)
    w_payload.add_argument("seq", type=_whole_number(1), help="the message's seq, the first column of `net ws events`")
    w_payload.add_argument(
        "-o", "--output", default=None, metavar="FILE", help="write the payload to FILE (default: stdout)"
    )
    w_payload.set_defaults(func=cmd_net_ws_payload)

    n_clear = net_sub.add_parser("clear", parents=[common], help="POST clear (transactions and WebSocket connections)")
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
    m_add.add_argument("--url-pattern", required=True, dest="url_pattern", help="looked for anywhere in the URL: a substring, or a regex with the regex: prefix")
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
