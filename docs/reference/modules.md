# Modules

| Module | Coordinates | What it is |
| --- | --- | --- |
| `:lustro` | `io.github.twinsen81:lustro` | Debug runtime AAR: embedded server, capture, built-in Network tab, mock storage, OkHttp adapters. |
| `:lustro-noop` | `io.github.twinsen81:lustro-noop` | Release-safe no-op AAR mirroring `:lustro`'s public facades with empty bodies. |
| `:lustro-api` | `io.github.twinsen81:lustro-api` | Pure-Kotlin public SPI (`DebugTab`, `DebugRequest`/`DebugResponse`, `Headers`, `MediaType`, network seams). |
| `:lustro-logs` | `io.github.twinsen81:lustro-logs` | The [Logs](../plugins/logs.md) plugin: the app's own logcat under `/api/v1/logs/`. |
| `:lustro-logs-noop` | `io.github.twinsen81:lustro-logs-noop` | Release-safe no-op half of `:lustro-logs`. |
| `:lustro-prefs` | `io.github.twinsen81:lustro-prefs` | The [Preferences](../plugins/prefs.md) plugin: the app's SharedPreferences and Preferences DataStores under `/api/v1/prefs/`. |
| `:lustro-prefs-noop` | `io.github.twinsen81:lustro-prefs-noop` | Release-safe no-op half of `:lustro-prefs`. |
| `:lustro-sqlite` | `io.github.twinsen81:lustro-sqlite` | The [SQLite](../plugins/sqlite.md) plugin: the app's SQLite and Room databases under `/api/v1/sqlite/`. |
| `:lustro-sqlite-noop` | `io.github.twinsen81:lustro-sqlite-noop` | Release-safe no-op half of `:lustro-sqlite`. |
| `:lustro-work` | `io.github.twinsen81:lustro-work` | The [WorkManager](../plugins/work.md) plugin: the app's WorkManager work, its events, and cancel and prune under `/api/v1/work/`. |
| `:lustro-work-noop` | `io.github.twinsen81:lustro-work-noop` | Release-safe no-op half of `:lustro-work`. |
| `:lustro-screen` | `io.github.twinsen81:lustro-screen` | The [Screen](../plugins/screen.md) plugin: the app's windows and UI tree under `/api/v1/screen/`. |
| `:lustro-screen-noop` | `io.github.twinsen81:lustro-screen-noop` | Release-safe no-op half of `:lustro-screen`. |
| `lustro-cli/` | `lustro-cli` (PyPI) | Python CLI that wraps the HTTP API, installing a `lustro` command; published alongside each release. |
