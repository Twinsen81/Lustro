"""Shared test fixtures for the lustro CLI contract tests."""

from __future__ import annotations

import json

import pytest

from lustro_cli import wire

# Map each golden fixture to the JSON Schema / OpenAPI component it must satisfy.
# (component name resolves against network.openapi.json #/components/schemas/...)
GOLDEN_SCHEMA_FILES = {
    "meta.json": ("schema", "meta.schema.json"),
    "error-envelope.json": ("schema", "error-envelope.schema.json"),
    "cursor-reset.json": ("schema", "cursor-envelope.schema.json"),
    "cursor-delta.json": ("schema", "cursor-envelope.schema.json"),
    "cursor-unchanged.json": ("schema", "cursor-envelope.schema.json"),
    "websockets-reset.json": ("schema", "cursor-envelope.schema.json"),
    "stream-reset.json": ("schema", "stream-envelope.schema.json"),
    "stream-delta.json": ("schema", "stream-envelope.schema.json"),
    "stream-unchanged.json": ("schema", "stream-envelope.schema.json"),
}

# Golden fixtures validated against an OpenAPI component schema.
GOLDEN_OPENAPI_COMPONENTS = {
    "cursor-reset.json": "TransactionCursorEnvelope",
    "cursor-delta.json": "TransactionCursorEnvelope",
    "cursor-unchanged.json": "TransactionCursorEnvelope",
    "transaction.json": "Transaction",
    "transaction-image.json": "Transaction",
    "send-result.json": None,  # validated against the inline sendRequest 200 schema
    "rules-list.json": None,  # validated against the inline listMockRules 200 schema
    "error-envelope.json": "ErrorEnvelope",
    "export-har.json": "HarDocument",
    "export-har-websocket.json": "HarDocument",
    "websockets-reset.json": "WebSocketCursorEnvelope",
    "websocket.json": "WebSocketConnection",
    "stream-reset.json": "WebSocketEventStreamEnvelope",
    "stream-delta.json": "WebSocketEventStreamEnvelope",
    "stream-unchanged.json": "WebSocketEventStreamEnvelope",
}


@pytest.fixture
def openapi():
    return wire.load_openapi()


@pytest.fixture
def golden():
    def _load(name):
        return wire.load_golden(name)

    return _load
