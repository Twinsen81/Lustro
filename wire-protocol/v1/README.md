# Lustro wire protocol — v1

This directory holds the **versioned, SemVer-governed** artifacts that define
Lustro's HTTP wire protocol. Non-Kotlin tooling (the `lustro` CLI, agents,
scripts) talks only to the HTTP API and is governed by these schemas — making
the protocol a stable public contract independent of the library's Kotlin API.

## Versioning

- The **major** version lives in the route path: all routes are under `/api/v1/`.
  A future incompatible revision becomes `/api/v2/`, and the old major stays
  alive for at least one major release.
- The **minor** protocol version is reported at runtime in
  `GET /api/v1/_meta` as `_meta.protocolVersion` (currently `1.0`).

## Contents

- **Global JSON Schemas** (this directory): the shared envelope shapes that every
  tab and framework route reuses.
- **Per-tab OpenAPI fragments**: live next to the tab assets at
  `lustro/src/main/assets/lustro/<id>.openapi.json` and are bundled into the AAR.
  They are served at `GET /api/v1/<id>/_schema`.
- **Golden response fixtures**: published alongside the schemas for contract
  tests between the server and the CLI.

## Shared envelope shapes

| Shape | Used by | JSON |
| --- | --- | --- |
| Error envelope | every `/api/v1/*` error | `{ error, message, code, field?, hint? }` |
| List pagination | list endpoints | `{ items: [...], nextCursor: "<opaque>" \| null }` |
| Live polling cursor | observable endpoints | `{ cursor, status: "delta" \| "unchanged" \| "reset", items? }` |
| Framework metadata | `GET /api/v1/_meta` | `{ libraryVersion, protocolVersion, tabs: [...] }` |

Cursor tokens are opaque; every mutation advances the cursor; clients treat
unknown `status` values as `reset`.

## Status

The concrete schema files (`*.schema.json`), the Network OpenAPI document, and
the golden fixtures (`golden/`) are present: envelopes + Network tab schemas,
and the golden response corpus (CLI contract tests). See
`SCHEMA_INDEX.md` for the full set. The golden fixtures are also vendored into
the `lustro-cli` wheel under `lustro_cli/wire/` so the installed CLI ships its
own contract corpus.
