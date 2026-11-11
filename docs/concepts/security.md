# Security model

Lustro deliberately surfaces app internals, so its defaults are conservative. See
[SECURITY.md](https://github.com/Twinsen81/Lustro/blob/main/SECURITY.md) for the full threat model.

- **Loopback by default.** The server binds to `127.0.0.1`; it is reachable only from the device
    itself (and your desktop via `adb forward`).
- **Token auth, always on.** A 256-bit token is generated on first run and stored in private
    debug preferences. Programmatic clients send `Authorization: Bearer <token>`; browsers use an
    `HttpOnly; SameSite=Strict` cookie set via `/api/v1/_auth`. Before auth, only framework chrome
    is served — no tab output or captured data. The token is logged at the `LustroToken` tag.
- **Tabs never see the credentials.** The server authenticates a request before dispatching it,
    and the `DebugRequest` a tab receives carries no `Authorization` or `Cookie` header, so a tab
    that logs or echoes its request can't leak the token.
- **Content Security Policy.** Chrome and tab views ship a CSP (`default-src 'self'; script-src
    'self'; style-src 'self' 'unsafe-inline'; ...`) plus `X-Content-Type-Options: nosniff`.
    **Scripts are `'self'`-only — no inline scripts**, so tab JS loads as an external same-origin
    resource and there are no inline handlers; **styles allow `'unsafe-inline'`** so tabs can use
    inline `style=` attributes and `<style>` blocks. Every API request, whatever its method, passes
    an Origin / `Sec-Fetch-Site` check: the server's own origin is always allowed, and any other
    cross-origin caller must be listed in `DebugConfig.allowedOrigins` (other localhost ports are not
    trusted by default). A request with neither an `Origin` nor a `Sec-Fetch-Site` header passes the
    check, because the CLI and older clients send neither. A browser can still send a cross-origin
    `GET` with neither header, so a tab must not change state on `GET` or `HEAD`.
- **Capture-time redaction, best-effort.** A `Redactor` masks sensitive headers, URL/query
    params, and JSON/form body fields **before** anything is stored, so what it masks never reaches
    the API, UI, or fixtures. It matches on names, so it cannot find a secret that isn't keyed by a
    name it recognizes: see [SECURITY.md](https://github.com/Twinsen81/Lustro/blob/main/SECURITY.md#threat-model) for the known gaps, and pass
    your own `Redactor` when your traffic needs more, or a capture filter to keep a request out of
    capture entirely. A JSON body with no sensitive field in it is stored exactly as it arrived, so
    what you inspect and copy is what was on the wire. The text of a WebSocket message is redacted as
    a body is; a binary message is stored as it arrived (see [WebSocket messages](../network/websockets.md)).
- **Log lines are not redacted.** The [Logs](../plugins/logs.md) plugin serves the app's logcat as it is.
- **Captured data stays in memory.** Captured traffic and log lines live only in bounded
    in-memory ring buffers and are lost when the process dies. The browser keeps no copy of them.
    Lustro writes to disk only in these cases:
    - The token, and the host and port that the server listens on, are in the app's private
      `lustro_debug` SharedPreferences file.
    - Mock rules are saved when you give the Network tab a `MockRuleStorage` (see
      [Mock rules](../network/mocks.md)).
    - The [Preferences](../plugins/prefs.md) tab changes a file that is not read-only, and the
      [SQLite](../plugins/sqlite.md) tab changes a database that is `writable`, when a client asks
      for an edit. The [WorkManager](../plugins/work.md) tab changes WorkManager's database when a
      client cancels or prunes work.
- **Release builds are inert.** Release variants depend on `:lustro-noop`, whose runtime bodies
    are empty — no server ships to production. As a backstop for a misconfigured dependency graph,
    the real runtime also refuses to start in a build that is not marked debuggable
    (`DebugConfig.allowNonDebuggableBuilds` opts an internal build back in).
