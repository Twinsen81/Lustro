"""Validate every golden fixture against its JSON Schema / OpenAPI component."""

from __future__ import annotations

import pytest
from jsonschema import Draft202012Validator
from jsonschema.validators import validator_for

from lustro_cli import wire

# ── shared JSON Schema validation ─────────────────────────────────────────────

SHARED_SCHEMA_CASES = [
    ("meta.json", "meta.schema.json"),
    ("error-envelope.json", "error-envelope.schema.json"),
    ("cursor-reset.json", "cursor-envelope.schema.json"),
    ("cursor-delta.json", "cursor-envelope.schema.json"),
    ("cursor-unchanged.json", "cursor-envelope.schema.json"),
    ("websockets-reset.json", "cursor-envelope.schema.json"),
    ("stream-reset.json", "stream-envelope.schema.json"),
    ("stream-delta.json", "stream-envelope.schema.json"),
    ("stream-unchanged.json", "stream-envelope.schema.json"),
    ("logs-reset.json", "stream-envelope.schema.json"),
    ("logs-delta.json", "stream-envelope.schema.json"),
    ("logs-unchanged.json", "stream-envelope.schema.json"),
    ("prefs-reset.json", "cursor-envelope.schema.json"),
    ("prefs-delta.json", "cursor-envelope.schema.json"),
    ("prefs-unchanged.json", "cursor-envelope.schema.json"),
]


@pytest.mark.parametrize("fixture, schema_file", SHARED_SCHEMA_CASES)
def test_fixture_matches_shared_schema(fixture, schema_file):
    schema = wire.load_schema(schema_file)
    instance = wire.load_golden(fixture)
    cls = validator_for(schema)
    cls.check_schema(schema)
    cls(schema).validate(instance)


def test_all_shared_schemas_are_valid_draft202012():
    for name in (
        "error-envelope.schema.json",
        "cursor-envelope.schema.json",
        "stream-envelope.schema.json",
        "pagination.schema.json",
        "meta.schema.json",
    ):
        Draft202012Validator.check_schema(wire.load_schema(name))


# ── OpenAPI component validation ──────────────────────────────────────────────


def _component_validator(openapi, component_name):
    """Build a validator for a named component, resolving local $refs against the
    OpenAPI document. OpenAPI 3.1 schemas are JSON Schema 2020-12 compatible."""
    schema = dict(openapi["components"]["schemas"][component_name])
    schema["components"] = openapi["components"]
    # Use 2020-12 (OpenAPI 3.1 aligns with it).
    return Draft202012Validator(schema)


OPENAPI_COMPONENT_CASES = [
    ("cursor-reset.json", "TransactionCursorEnvelope"),
    ("cursor-delta.json", "TransactionCursorEnvelope"),
    ("cursor-unchanged.json", "TransactionCursorEnvelope"),
    ("transaction.json", "Transaction"),
    ("transaction-image.json", "Transaction"),
    ("error-envelope.json", "ErrorEnvelope"),
    ("export-har.json", "HarDocument"),
    ("export-har-websocket.json", "HarDocument"),
    ("websockets-reset.json", "WebSocketCursorEnvelope"),
    ("websocket.json", "WebSocketConnection"),
    ("stream-reset.json", "WebSocketEventStreamEnvelope"),
    ("stream-delta.json", "WebSocketEventStreamEnvelope"),
    ("stream-unchanged.json", "WebSocketEventStreamEnvelope"),
]


@pytest.mark.parametrize("fixture, component", OPENAPI_COMPONENT_CASES)
def test_fixture_matches_openapi_component(fixture, component):
    openapi = wire.load_openapi()
    validator = _component_validator(openapi, component)
    validator.validate(wire.load_golden(fixture))


def _inline_response_schema(openapi, path, method, status):
    op = openapi["paths"][path][method]
    schema = op["responses"][status]["content"]["application/json"]["schema"]
    schema = dict(schema)
    schema["components"] = openapi["components"]
    return Draft202012Validator(schema)


def test_send_result_matches_inline_schema():
    openapi = wire.load_openapi()
    validator = _inline_response_schema(
        openapi, "/api/v1/network/send", "post", "200"
    )
    validator.validate(wire.load_golden("send-result.json"))


def test_senders_list_matches_inline_schema():
    openapi = wire.load_openapi()
    validator = _inline_response_schema(
        openapi, "/api/v1/network/senders", "get", "200"
    )
    validator.validate(wire.load_golden("senders-list.json"))


def test_rules_list_matches_inline_schema():
    openapi = wire.load_openapi()
    validator = _inline_response_schema(
        openapi, "/api/v1/network/rules", "get", "200"
    )
    validator.validate(wire.load_golden("rules-list.json"))


def test_every_golden_transaction_matches_transaction_component():
    """The transactions inside the cursor fixtures must each be valid Transactions."""
    openapi = wire.load_openapi()
    validator = _component_validator(openapi, "Transaction")
    for fixture in ("cursor-reset.json", "cursor-delta.json"):
        data = wire.load_golden(fixture)
        for tx in data["items"]:
            validator.validate(tx)


def test_every_mock_rule_matches_mockrule_component():
    openapi = wire.load_openapi()
    validator = _component_validator(openapi, "MockRule")
    for rule in wire.load_golden("rules-list.json")["items"]:
        validator.validate(rule)


# ── protocol version ──────────────────────────────────────────────────────────

# Added in 1.2. The server sends each of them on every transaction, list and
# detail alike, as null when the value isn't known; the schema doesn't require
# them because a 1.1 server leaves them out.
TRANSACTION_FIELDS_SINCE_1_2 = (
    "startedAt",
    "completedAt",
    "protocol",
    "requestContentType",
    "responseContentType",
)

# Also added in 1.2, and sent on the detail only.
DETAIL_FIELDS_SINCE_1_2 = ("requestBodyBinary", "responseBodyBinary")

# Added in 1.3, on every transaction: null unless it is a WebSocket's handshake.
TRANSACTION_FIELDS_SINCE_1_3 = ("webSocketId",)

DETAIL_FIXTURES = ("transaction.json", "transaction-image.json")


def _golden_transactions():
    for fixture in ("cursor-reset.json", "cursor-delta.json"):
        for tx in wire.load_golden(fixture)["items"]:
            yield fixture, tx
    for fixture in DETAIL_FIXTURES:
        yield fixture, wire.load_golden(fixture)


def test_the_network_schema_version_matches_meta():
    meta = wire.load_golden("meta.json")
    network = next(tab for tab in meta["tabs"] if tab["id"] == "network")
    assert wire.load_openapi()["info"]["version"] == meta["protocolVersion"] == network["version"]


def test_the_transaction_schema_declares_the_1_2_and_1_3_fields():
    properties = wire.load_openapi()["components"]["schemas"]["Transaction"]["properties"]
    for field in TRANSACTION_FIELDS_SINCE_1_2 + DETAIL_FIELDS_SINCE_1_2 + TRANSACTION_FIELDS_SINCE_1_3:
        assert field in properties, field


def test_every_golden_transaction_carries_the_1_2_and_1_3_fields():
    for fixture, tx in _golden_transactions():
        for field in TRANSACTION_FIELDS_SINCE_1_2 + TRANSACTION_FIELDS_SINCE_1_3:
            assert field in tx, "{}: {} has no {}".format(fixture, tx["id"], field)


def test_every_golden_detail_carries_the_binary_flags():
    for fixture in DETAIL_FIXTURES:
        tx = wire.load_golden(fixture)
        for direction in ("request", "response"):
            binary = tx[direction + "BodyBinary"]
            assert isinstance(binary, bool), fixture
            # A body is text or bytes, never both: a binary one has no text.
            if binary:
                assert tx[direction + "Body"] is None, fixture


def test_the_body_route_is_declared_with_both_directions():
    op = wire.load_openapi()["paths"]["/api/v1/network/transactions/{id}/body/{direction}"]["get"]
    direction = next(p for p in op["parameters"] if p["name"] == "direction")
    assert direction["schema"]["enum"] == ["request", "response"]
    assert "404" in op["responses"]


def test_the_export_route_is_declared_with_its_format_and_ids():
    op = wire.load_openapi()["paths"]["/api/v1/network/transactions/_/export"]["get"]
    params = {p["name"]: p for p in op["parameters"]}
    assert params["format"]["schema"]["enum"] == ["har"]
    assert params["ids"]["required"] is False
    assert "400" in op["responses"]


def test_the_golden_har_marks_what_har_has_no_field_for():
    entries = wire.load_golden("export-har.json")["log"]["entries"]
    by_id = {entry["_lustro"]["id"]: entry for entry in entries}
    # Oldest first, as HAR readers prefer.
    started = [entry["startedDateTime"] for entry in entries]
    assert started == sorted(started)
    assert by_id["tx_77e2c014"]["_lustro"]["isMocked"] is True
    assert by_id["tx_5e8a2f61"]["_lustro"]["responseBodyTruncated"] is True
    assert by_id["tx_d03a9b44"]["_lustro"]["error"] == by_id["tx_d03a9b44"]["response"]["_error"]
    # A body kept as bytes is base64.
    assert by_id["tx_e6b0d413"]["response"]["content"]["encoding"] == "base64"
    # Each entry spends its duration waiting, so time adds up as HAR requires.
    for entry in entries:
        timings = entry["timings"]
        assert entry["time"] == sum(value for value in timings.values() if value != -1)


# ── WebSockets (1.3) ──────────────────────────────────────────────────────────


def test_every_golden_connection_and_event_matches_its_component():
    openapi = wire.load_openapi()
    connection = _component_validator(openapi, "WebSocketConnection")
    for item in wire.load_golden("websockets-reset.json")["items"]:
        connection.validate(item)
    event = _component_validator(openapi, "WebSocketEvent")
    for fixture in ("stream-reset.json", "stream-delta.json"):
        for item in wire.load_golden(fixture)["items"]:
            event.validate(item)


def test_a_golden_event_has_only_the_keys_of_its_kind():
    events = wire.load_golden("stream-reset.json")["items"] + wire.load_golden("stream-delta.json")["items"]
    kinds = {event["kind"] for event in events}
    assert {"open", "message", "close", "closed"} <= kinds
    for event in events:
        if event["kind"] != "message":
            assert "payloadBytes" not in event and "preview" not in event, event
        elif event["type"] == "binary":
            assert "preview" not in event, event
        # Only what the app sent has a return value to report.
        assert ("enqueued" in event) == (event.get("direction") == "sent"), event


def test_the_golden_delta_reports_what_the_client_missed():
    delta = wire.load_golden("stream-delta.json")
    assert delta["dropped"] > 0
    seqs = [event["seq"] for event in delta["items"]]
    assert seqs == sorted(seqs)
    assert "items" not in wire.load_golden("stream-unchanged.json")


def test_the_websocket_routes_are_declared():
    paths = wire.load_openapi()["paths"]
    events = paths["/api/v1/network/websockets/{id}/events"]["get"]
    params = {p["name"]: p for p in events["parameters"]}
    assert params["direction"]["schema"]["enum"] == ["sent", "received"]
    assert params["limit"]["schema"]["minimum"] == 1
    assert "400" in events["responses"] and "404" in events["responses"]
    assert "404" in paths["/api/v1/network/websockets/{id}"]["get"]["responses"]
    assert "404" in paths["/api/v1/network/websockets/{id}/events/{seq}/payload"]["get"]["responses"]
    assert "/api/v1/network/websockets" in paths


def test_the_golden_websocket_har_carries_the_messages_chrome_reads():
    entry = wire.load_golden("export-har-websocket.json")["log"]["entries"][0]
    assert entry["_resourceType"] == "websocket"
    messages = entry["_webSocketMessages"]
    assert [m["type"] for m in messages] == ["send", "receive", "send", "receive"]
    assert [m["opcode"] for m in messages] == [1, 1, 2, 1]
    # The connection sent a message after close() that send() refused: it is not in the export.
    assert all(m["data"] != "too late" for m in messages)
    assert messages[3]["_truncated"] is True and messages[3]["_payloadBytes"] > len(messages[3]["data"])
    assert entry["_lustro"]["webSocket"]["state"] == "closed"



# ── Logs tab ──────────────────────────────────────────────────────────────────

LOGS_FIXTURES = ("logs-reset.json", "logs-delta.json", "logs-unchanged.json")


@pytest.mark.parametrize("fixture", LOGS_FIXTURES)
def test_logs_fixture_matches_the_logs_stream_envelope(fixture):
    validator = _component_validator(wire.load_openapi("logs"), "LogStreamEnvelope")
    validator.validate(wire.load_golden(fixture))


def test_the_logs_schema_is_valid_and_has_its_own_version():
    logs = wire.load_openapi("logs")
    assert logs["openapi"].startswith("3.1")
    assert logs["info"]["version"] == "1.0"
    for name, schema in logs["components"]["schemas"].items():
        Draft202012Validator.check_schema(schema)
    # The error envelope is the same everywhere.
    assert logs["components"]["schemas"]["ErrorEnvelope"] == wire.load_openapi()["components"]["schemas"]["ErrorEnvelope"]


def test_the_logs_routes_are_declared():
    paths = wire.load_openapi("logs")["paths"]
    entries = paths["/api/v1/logs/entries"]["get"]
    params = {p["name"]: p for p in entries["parameters"]}
    assert set(params) == {"cursor", "level", "tag", "q", "mq", "limit"}
    assert params["level"]["schema"]["enum"] == ["V", "D", "I", "W", "E", "F"]
    assert params["tag"]["schema"]["maxLength"] == params["mq"]["schema"]["maxLength"] == 200
    assert params["limit"]["schema"]["maximum"] == 5000
    assert "400" in entries["responses"]
    text = paths["/api/v1/logs/text"]["get"]
    assert "text/plain" in text["responses"]["200"]["content"]
    assert {p["name"] for p in text["parameters"]} == set(params)
    for route in ("clear", "pause", "compact"):
        assert "post" in paths["/api/v1/logs/" + route]


def test_a_golden_log_line_comes_again_with_its_seq_when_it_repeats():
    reset = wire.load_golden("logs-reset.json")
    delta = wire.load_golden("logs-delta.json")
    by_seq = {item["seq"]: item for item in reset["items"]}
    repeated = [item for item in delta["items"] if item["seq"] in by_seq]
    assert repeated, "the delta repeats no line of the reset"
    for item in repeated:
        assert item["count"] > by_seq[item["seq"]].get("count", 1)
        assert item["lastAt"] > item["at"]
    # A line that came once has neither key.
    assert all(("count" in item) == ("lastAt" in item) for item in reset["items"] + delta["items"])
    assert "items" not in wire.load_golden("logs-unchanged.json")
    assert wire.load_golden("logs-unchanged.json")["state"]["paused"] is True


# ── Preferences tab ───────────────────────────────────────────────────────────

PREFS_ENTRIES_FIXTURES = ("prefs-reset.json", "prefs-delta.json", "prefs-unchanged.json")


def _inline_validator(openapi, path, method, status):
    schema = dict(openapi["paths"][path][method]["responses"][status]["content"]["application/json"]["schema"])
    schema["components"] = openapi["components"]
    return Draft202012Validator(schema)


@pytest.mark.parametrize("fixture", PREFS_ENTRIES_FIXTURES)
def test_prefs_fixture_matches_the_prefs_cursor_envelope(fixture):
    validator = _component_validator(wire.load_openapi("prefs"), "PrefsCursorEnvelope")
    validator.validate(wire.load_golden(fixture))


def test_prefs_files_fixture_matches_the_files_route():
    prefs = wire.load_openapi("prefs")
    _inline_validator(prefs, "/api/v1/prefs/files", "get", "200").validate(wire.load_golden("prefs-files.json"))


def test_the_prefs_schema_is_valid_and_has_its_own_version():
    prefs = wire.load_openapi("prefs")
    assert prefs["openapi"].startswith("3.1")
    assert prefs["info"]["version"] == "1.0"
    for schema in prefs["components"]["schemas"].values():
        Draft202012Validator.check_schema(schema)
    # The error envelope is the same everywhere.
    assert prefs["components"]["schemas"]["ErrorEnvelope"] == wire.load_openapi()["components"]["schemas"]["ErrorEnvelope"]


def test_the_prefs_routes_are_declared():
    paths = wire.load_openapi("prefs")["paths"]
    assert set(paths) == {"/api/v1/prefs/" + route for route in ("files", "entries", "entry", "edit", "delete", "clear")}
    entries = {p["name"]: p for p in paths["/api/v1/prefs/entries"]["get"]["parameters"]}
    assert set(entries) == {"store", "file", "cursor"} and entries["file"]["required"]
    # Without a store, a request names a SharedPreferences file, as before there were others.
    assert not entries["store"]["required"]
    entry = {p["name"]: p for p in paths["/api/v1/prefs/entry"]["get"]["parameters"]}
    assert set(entry) == {"store", "file", "key"}
    for route, required in (("edit", ["file", "key", "type", "value"]), ("delete", ["file", "key"]), ("clear", ["file", "confirm"])):
        post = paths["/api/v1/prefs/" + route]["post"]
        schema = post["requestBody"]["content"]["application/json"]["schema"]
        assert schema["required"] == required
        assert "store" in schema["properties"]
        # Every mutation answers 403 for a read-only file.
        assert "403" in post["responses"] and "read_only" in post["description"]
    schemas = wire.load_openapi("prefs")["components"]["schemas"]
    assert schemas["PrefsStore"]["enum"] == ["shared_prefs", "preferences_datastore"]
    assert schemas["PrefWriteType"]["enum"] == ["string", "int", "long", "float", "double", "boolean", "string_set", "bytes"]


def test_the_golden_prefs_files_show_every_policy():
    files = wire.load_golden("prefs-files.json")["items"]
    by_name = {f["name"]: f for f in files}
    assert [f["registered"] for f in files] == sorted((f["registered"] for f in files), reverse=True), "registered files come first"
    assert {"mask_sensitive", "keys_only"} <= {f["policy"] for f in files}
    discovered = [f for f in files if not f["registered"]]
    assert discovered and all(f["readOnly"] for f in discovered)
    encrypted = [f for f in files if f["encrypted"]]
    assert encrypted and all(f["policy"] == "keys_only" and f["readOnly"] for f in encrypted)
    assert {f["store"] for f in files} == {"shared_prefs", "preferences_datastore"}
    # The tab reads a DataStore that the app does not register from its file, and never writes it.
    assert all(f["readOnly"] for f in discovered if f["store"] == "preferences_datastore")
    # The cursor of a file is the cursor its entries return.
    assert by_name["settings"]["cursor"] == wire.load_golden("prefs-reset.json")["cursor"]


def test_a_golden_masked_entry_has_no_value_and_the_delta_has_the_whole_file():
    reset = wire.load_golden("prefs-reset.json")
    delta = wire.load_golden("prefs-delta.json")
    for item in reset["items"] + delta["items"]:
        assert ("value" in item) != item.get("masked", False), item
    assert [i["key"] for i in reset["items"]] == sorted(i["key"] for i in reset["items"])
    assert [i["key"] for i in delta["items"]] == [i["key"] for i in reset["items"]]
    assert reset["cursor"] != delta["cursor"]
    unchanged = wire.load_golden("prefs-unchanged.json")
    assert "items" not in unchanged and unchanged["cursor"] == delta["cursor"]
