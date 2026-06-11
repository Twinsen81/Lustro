"""Bundled wire-protocol artifacts.

These JSON files are vendored copies of the SemVer-governed wire protocol:

- the shared JSON Schemas from ``wire-protocol/v1/*.schema.json``,
- the authoritative Network OpenAPI document
  (``lustro/src/main/assets/lustro/network.openapi.json``), and
- the golden response fixtures from ``wire-protocol/v1/golden/``.

They are packaged into the wheel so the installed CLI ships its own schemas and
contract corpus without a source checkout. Regenerate them with
``python -m lustro_cli.wire.sync`` after the upstream protocol changes.
"""

from __future__ import annotations

import json
from typing import Any

try:  # Python 3.9+: importlib.resources.files
    from importlib.resources import files as _files
except ImportError:  # pragma: no cover - <3.9 not supported
    _files = None  # type: ignore[assignment]

__all__ = ["load_schema", "load_openapi", "load_golden", "read_text"]


def read_text(name: str) -> str:
    """Read a bundled artifact (e.g. ``"meta.schema.json"`` or
    ``"golden/cursor-reset.json"``) as text."""
    resource = _files("lustro_cli.wire")
    for part in name.split("/"):
        resource = resource.joinpath(part)
    return resource.read_text(encoding="utf-8")


def _load(name: str) -> Any:
    return json.loads(read_text(name))


def load_schema(name: str) -> Any:
    """Load a shared JSON Schema by file name (e.g. ``"meta.schema.json"``)."""
    return _load(name)


def load_openapi() -> Any:
    """Load the bundled Network OpenAPI document."""
    return _load("network.openapi.json")


def load_golden(name: str) -> Any:
    """Load a golden fixture by file name (e.g. ``"cursor-reset.json"``)."""
    return _load("golden/" + name)
