"""Regenerate the vendored wire-protocol artifacts from the repo source of truth.

Run from a source checkout:

    python -m lustro_cli.wire.sync

This copies the canonical schemas, the OpenAPI documents of the Network, Logs,
Preferences, SQLite, and WorkManager tabs, and the golden fixtures into ``lustro_cli/wire/`` so
the next wheel build bundles up-to-date copies. It is a development-time helper and is not needed at runtime.
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

# lustro_cli/wire/sync.py -> repo root is five levels up
# (.../lustro-cli/src/lustro_cli/wire/sync.py).
_HERE = Path(__file__).resolve()
_WIRE_DIR = _HERE.parent
_REPO_ROOT = _HERE.parents[4]

_SCHEMAS = [
    "error-envelope.schema.json",
    "cursor-envelope.schema.json",
    "stream-envelope.schema.json",
    "pagination.schema.json",
    "meta.schema.json",
]


def main() -> int:
    wire_v1 = _REPO_ROOT / "wire-protocol" / "v1"
    assets = _REPO_ROOT / "lustro" / "src" / "main" / "assets" / "lustro"
    openapi = assets / "network.openapi.json"
    logs_openapi = _REPO_ROOT / "lustro-logs" / "src" / "main" / "assets" / "lustro" / "logs.openapi.json"
    prefs_openapi = _REPO_ROOT / "lustro-prefs" / "src" / "main" / "assets" / "lustro" / "prefs.openapi.json"
    sqlite_openapi = _REPO_ROOT / "lustro-sqlite" / "src" / "main" / "assets" / "lustro" / "sqlite.openapi.json"
    work_openapi = _REPO_ROOT / "lustro-work" / "src" / "main" / "assets" / "lustro" / "work.openapi.json"
    served_schema = assets / "_schema.json"
    golden_src = wire_v1 / "golden"

    sources = (openapi, logs_openapi, prefs_openapi, sqlite_openapi, work_openapi, served_schema)
    if not wire_v1.is_dir() or not all(source.is_file() for source in sources):
        print(
            "error: cannot find repo sources (expected {} and {}). "
            "Run from a source checkout.".format(wire_v1, ", ".join(str(source) for source in sources)),
            file=sys.stderr,
        )
        return 1

    golden_dst = _WIRE_DIR / "golden"
    golden_dst.mkdir(exist_ok=True)

    for name in _SCHEMAS:
        shutil.copy2(wire_v1 / name, _WIRE_DIR / name)
    shutil.copy2(openapi, _WIRE_DIR / "network.openapi.json")
    shutil.copy2(logs_openapi, _WIRE_DIR / "logs.openapi.json")
    shutil.copy2(prefs_openapi, _WIRE_DIR / "prefs.openapi.json")
    shutil.copy2(sqlite_openapi, _WIRE_DIR / "sqlite.openapi.json")
    shutil.copy2(work_openapi, _WIRE_DIR / "work.openapi.json")
    shutil.copy2(served_schema, _WIRE_DIR / "_schema.json")
    for fixture in sorted(golden_src.glob("*.json")):
        shutil.copy2(fixture, golden_dst / fixture.name)

    print("synced wire artifacts into {}".format(_WIRE_DIR))
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
