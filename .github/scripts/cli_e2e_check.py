#!/usr/bin/env python3
"""Checks the output of one ``lustro`` command against the wire schemas the CLI ships.

``cli-e2e.sh`` pipes each command's stdout into one check::

    cli_e2e_check.py CHECK [RULES_FILE] < output

A check exits 0 when the output matches and 1, with the reason, when it does not.
The schemas come from the installed ``lustro_cli`` package, so the checks test
what the CLI ships. RULES_FILE is the mock rule file that the script synced; the
checks after the sync compare the output with it.
"""

from __future__ import annotations

import json
import re
import sys
from typing import Any, Callable, Dict, List

from jsonschema import Draft202012Validator
from jsonschema.exceptions import ValidationError

from lustro_cli import wire

OPENAPI = wire.load_openapi()
LOGS_OPENAPI = wire.load_openapi("logs")


class CheckFailed(Exception):
    pass


def expect(condition: bool, message: str) -> None:
    if not condition:
        raise CheckFailed(message)


def _resolving(schema: Dict[str, Any]) -> Draft202012Validator:
    # OpenAPI 3.1 schemas are JSON Schema 2020-12. Their $refs point into the
    # document's components, so each schema carries a copy of them.
    return Draft202012Validator(dict(schema, components=OPENAPI["components"]))


def component(name: str) -> Draft202012Validator:
    return _resolving(OPENAPI["components"]["schemas"][name])


def response(path: str, method: str) -> Draft202012Validator:
    """Validates the 200 JSON response of the operation at ``path``."""
    ok = OPENAPI["paths"][path][method]["responses"]["200"]
    return _resolving(ok["content"]["application/json"]["schema"])


def shared(name: str) -> Draft202012Validator:
    return Draft202012Validator(wire.load_schema(name))


def check_open(text: str, rules: List[dict]) -> None:
    url = text.strip()
    expect(
        re.fullmatch(r"http://localhost:\d+/#lustro_token=\S+", url) is not None,
        "not a console URL with a token: {!r}".format(url),
    )


def check_meta(text: str, rules: List[dict]) -> None:
    meta = json.loads(text)
    shared("meta.schema.json").validate(meta)
    expect(any(tab["id"] == "network" for tab in meta["tabs"]), "the network tab is not listed")
    shipped = OPENAPI["info"]["version"]
    expect(
        meta["protocolVersion"] == shipped,
        "the app speaks protocol {} and the CLI ships {}".format(meta["protocolVersion"], shipped),
    )


def check_schema_network(text: str, rules: List[dict]) -> None:
    expect(json.loads(text) == OPENAPI, "the app serves a Network schema that differs from the CLI's copy")


def check_schema_logs(text: str, rules: List[dict]) -> None:
    expect(json.loads(text) == LOGS_OPENAPI, "the app serves a Logs schema that differs from the CLI's copy")


def _logs_envelope(text: str) -> dict:
    envelope = json.loads(text)
    schema = LOGS_OPENAPI["components"]["schemas"]["LogStreamEnvelope"]
    Draft202012Validator(dict(schema, components=LOGS_OPENAPI["components"])).validate(envelope)
    expect(envelope["state"]["reader"] == "running", "the reader is {}: {}".format(
        envelope["state"]["reader"], envelope["state"].get("readerError")))
    return envelope


def check_logs_startup(text: str, rules: List[dict]) -> None:
    """The first poll has the line the sample writes before Lustro.start(), and the one after."""
    envelope = _logs_envelope(text)
    expect(envelope["status"] == "reset", "status {}".format(envelope["status"]))
    messages = [item["message"] for item in envelope["items"]]
    for expected in ("Application.onCreate, before Lustro starts", "Lustro start() returned ENABLED"):
        expect(expected in messages, "no {!r} in {}".format(expected, messages))


def check_logs_delta(text: str, rules: List[dict]) -> None:
    """A poll after clear has the lines the sample wrote since, and no dropped count."""
    envelope = _logs_envelope(text)
    expect(envelope["status"] == "delta", "status {}".format(envelope["status"]))
    expect(envelope["dropped"] == 0, "dropped {}".format(envelope["dropped"]))
    repeated = [item for item in envelope["items"] if item["message"] == "the same warning, again"]
    expect(bool(repeated), "no repeated warning in {}".format([item["message"] for item in envelope["items"]]))


def check_logs_text(text: str, rules: List[dict]) -> None:
    lines = [line for line in text.splitlines() if line]
    expect(bool(lines), "no line")
    for line in lines:
        expect(re.match(r"\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} +\d+ +\d+ [EF] ", line) is not None, "not an E or F threadtime line: {!r}".format(line))


def check_sync(text: str, rules: List[dict]) -> None:
    result = json.loads(text)
    response("/api/v1/network/rules/_/sync", "post").validate(result)
    expect(result["count"] == len(rules), "synced {} rules, the file has {}".format(result["count"], len(rules)))


def check_rules(text: str, rules: List[dict]) -> None:
    listed = json.loads(text)
    response("/api/v1/network/rules", "get").validate(listed)
    # The app lists its rules in no fixed order.
    ids = sorted(rule["id"] for rule in listed["items"])
    expected = sorted(rule["id"] for rule in rules)
    expect(ids == expected, "the app lists rules {}, the file has {}".format(ids, expected))


def json_lines(text: str) -> List[Any]:
    return [json.loads(line) for line in text.splitlines()]


def check_list(text: str, rules: List[dict]) -> None:
    """``--json net list`` and ``net wait`` print JSON Lines: one list item per line."""
    items = json_lines(text)
    expect(len(items) <= 50, "{} lines, and net list prints the newest 50 by default".format(len(items)))
    for item in items:
        component("Transaction").validate(item)


def check_state(text: str, rules: List[dict]) -> None:
    state = json.loads(text)
    _resolving(OPENAPI["components"]["schemas"]["TransactionCursorEnvelope"]["properties"]["state"]).validate(state)
    expect(state.get("paused") is False, "capture is paused")


def find(text: str, rules: List[dict]) -> None:
    """Prints the id of the first listed transaction that the first rule matches."""
    pattern = rules[0]["urlPattern"]
    matches = [tx for tx in json_lines(text) if pattern in tx["url"]]
    expect(bool(matches), "no transaction matches {}".format(pattern))
    # The CLI lists the newest transaction first.
    print(matches[0]["id"])


def check_transaction(text: str, rules: List[dict]) -> None:
    tx = json.loads(text)
    component("Transaction").validate(tx)
    rule = rules[0]
    expect(rule["urlPattern"] in tx["url"], "{} does not match {}".format(tx["url"], rule["urlPattern"]))
    expect(tx["isMocked"] is True, "the rule did not serve the request")
    expect(tx["statusCode"] == rule["statusCode"], "status {}, the rule sets {}".format(tx["statusCode"], rule["statusCode"]))
    expect(tx["responseBody"] == rule["responseBody"], "body {!r}, the rule sets {!r}".format(tx["responseBody"], rule["responseBody"]))


def check_api_transactions(text: str, rules: List[dict]) -> None:
    """``lustro api GET network/transactions`` prints the route's response as it is."""
    listed = json.loads(text)
    response("/api/v1/network/transactions", "get").validate(listed)
    pattern = rules[0]["urlPattern"]
    expect(any(pattern in tx["url"] for tx in listed["items"]), "no listed transaction matches {}".format(pattern))


def check_api_throttle(text: str, rules: List[dict]) -> None:
    result = json.loads(text)
    response("/api/v1/network/throttle", "post").validate(result)
    expect(result["delayMs"] == 0, "the throttle is {} ms, the request set 0".format(result["delayMs"]))


def check_senders(text: str, rules: List[dict]) -> None:
    listed = json.loads(text)
    response("/api/v1/network/senders", "get").validate(listed)
    expect(bool(listed["items"]), "the sample registers no sender")
    expect(listed["items"][0]["baseUrl"] is not None, "the sample's default sender has no base URL")


def check_send(text: str, rules: List[dict]) -> None:
    """``send`` of the path the first rule mocks: prints the id of the captured transaction."""
    result = json.loads(text)
    response("/api/v1/network/send", "post").validate(result)
    rule = rules[0]
    expect(result["ok"] is True, "the send failed: {}".format(result.get("error")))
    expect(result["url"] == rule["urlPattern"], "sent to {}, the rule mocks {}".format(result["url"], rule["urlPattern"]))
    expect(result["statusCode"] == rule["statusCode"], "status {}, the rule sets {}".format(result["statusCode"], rule["statusCode"]))
    # The sample's sender is the client with the interceptor, so the request is captured.
    expect(result["transactionId"] is not None, "the sent request was not captured")
    print(result["transactionId"])


def check_har(text: str, rules: List[dict]) -> None:
    """``net export --har`` writes a HAR document; the request the first rule served is in it."""
    har = json.loads(text)
    component("HarDocument").validate(har)
    rule = rules[0]
    entries = [entry for entry in har["log"]["entries"] if rule["urlPattern"] in entry["request"]["url"]]
    expect(bool(entries), "no entry matches {}".format(rule["urlPattern"]))
    for entry in entries:
        expect(entry["_lustro"]["isMocked"] is True, "the rule did not serve {}".format(entry["_lustro"]["id"]))
        expect(entry["response"]["status"] == rule["statusCode"], "status {}".format(entry["response"]["status"]))
        expect(entry["response"]["content"].get("text") == rule["responseBody"], "body {!r}".format(entry["response"]["content"].get("text")))


def _ws_path(rules: List[dict]) -> str:
    """The path of the socket whose handshake the second rule answers."""
    return rules[1]["urlPattern"].split("//", 1)[1].split("/", 1)[1]


def _refused(connection: dict, rules: List[dict]) -> bool:
    return connection["url"].endswith("/" + _ws_path(rules)) and connection["state"] == "failed"


def check_ws_list(text: str, rules: List[dict]) -> None:
    """``--json net ws list`` prints JSON Lines: one connection per line."""
    for item in json_lines(text):
        component("WebSocketConnection").validate(item)


def ws_find(text: str, rules: List[dict]) -> None:
    """Prints the id of the newest failed connection to the URL that the second rule answers."""
    matches = [connection for connection in json_lines(text) if _refused(connection, rules)]
    expect(bool(matches), "no failed connection to {}".format(rules[1]["urlPattern"]))
    print(matches[0]["id"])


def check_ws_connection(text: str, rules: List[dict]) -> None:
    connection = json.loads(text)
    component("WebSocketConnection").validate(connection)
    rule = rules[1]
    expect(_refused(connection, rules), "{} {} is not the refused socket".format(connection["state"], connection["url"]))
    expect(connection["url"].startswith("wss://"), "{} does not have the scheme the app asked for".format(connection["url"]))
    expect(connection["statusCode"] == rule["statusCode"], "status {}, the rule sets {}".format(connection["statusCode"], rule["statusCode"]))
    expect(bool(connection["transactionId"]), "the connection has no handshake transaction")
    expect("Expected HTTP 101" in (connection["error"] or ""), "error {!r}".format(connection["error"]))
    expect(isinstance(connection.get("requestHeaders"), dict), "the detail has no requestHeaders")


def check_ws_events(text: str, rules: List[dict]) -> None:
    """``--json net ws events`` prints JSON Lines: one event per line. A refused socket has one, its failure."""
    events = json_lines(text)
    for event in events:
        component("WebSocketEvent").validate(event)
    expect([event["kind"] for event in events] == ["failure"], "events {}".format([event["kind"] for event in events]))


def check_ws_har(text: str, rules: List[dict]) -> None:
    """The export has the handshake of the refused socket as a websocket entry, with no message."""
    har = json.loads(text)
    component("HarDocument").validate(har)
    entries = [entry for entry in har["log"]["entries"] if entry["_resourceType"] == "websocket"]
    expect(bool(entries), "no entry is a websocket")
    for entry in entries:
        expect(entry["_webSocketMessages"] == [], "messages {}".format(entry["_webSocketMessages"]))
        expect(entry["_lustro"]["webSocket"]["state"] == "failed", "state {}".format(entry["_lustro"]["webSocket"]["state"]))


CHECKS: Dict[str, Callable[[str, List[dict]], None]] = {
    "open": check_open,
    "meta": check_meta,
    "schema-network": check_schema_network,
    "schema-logs": check_schema_logs,
    "logs-startup": check_logs_startup,
    "logs-delta": check_logs_delta,
    "logs-text": check_logs_text,
    "sync": check_sync,
    "rules": check_rules,
    "list": check_list,
    "state": check_state,
    "find": find,
    "transaction": check_transaction,
    "api-transactions": check_api_transactions,
    "api-throttle": check_api_throttle,
    "har": check_har,
    "senders": check_senders,
    "send": check_send,
    "ws-list": check_ws_list,
    "ws-find": ws_find,
    "ws-connection": check_ws_connection,
    "ws-events": check_ws_events,
    "ws-har": check_ws_har,
}


def main(argv: List[str]) -> int:
    if not 1 <= len(argv) <= 2 or argv[0] not in CHECKS:
        print("usage: cli_e2e_check.py {{{}}} [RULES_FILE] < output".format(",".join(CHECKS)), file=sys.stderr)
        return 2
    rules: List[dict] = []
    if len(argv) == 2:
        with open(argv[1], encoding="utf-8") as fh:
            rules = json.load(fh)
    try:
        CHECKS[argv[0]](sys.stdin.read(), rules)
    except ValidationError as exc:
        print("{}: {} at /{}".format(argv[0], exc.message, "/".join(str(p) for p in exc.absolute_path)), file=sys.stderr)
        return 1
    except (CheckFailed, ValueError) as exc:
        print("{}: {}".format(argv[0], exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
