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
}

# Golden fixtures validated against an OpenAPI component schema.
GOLDEN_OPENAPI_COMPONENTS = {
    "cursor-reset.json": "TransactionCursorEnvelope",
    "cursor-delta.json": "TransactionCursorEnvelope",
    "cursor-unchanged.json": "TransactionCursorEnvelope",
    "transaction.json": "Transaction",
    "send-result.json": None,  # validated against the inline sendRequest 200 schema
    "rules-list.json": None,  # validated against the inline listMockRules 200 schema
    "error-envelope.json": "ErrorEnvelope",
}


@pytest.fixture
def openapi():
    return wire.load_openapi()


@pytest.fixture
def golden():
    def _load(name):
        return wire.load_golden(name)

    return _load
