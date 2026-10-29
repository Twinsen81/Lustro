# Security Policy

Lustro is a **debug-only** Android library: a small on-device web server that
exposes app internals to a developer's browser and to local tooling. Because it
deliberately surfaces application data, its security posture is taken seriously.
This document describes what is supported, how to report a vulnerability, the
threat model, and the process for handling the vendored NanoHTTPD dependency.

## Supported Versions

Lustro is pre-1.0 and under active development. Until `1.0.0`, only the latest
published version (currently the `0.1.0-SNAPSHOT` line) receives security fixes.
After `1.0.0`, the most recent minor release of the current major will be
supported.

| Version | Supported |
| ------- | --------- |
| `0.1.0-SNAPSHOT` (latest) | Yes |
| Older snapshots | No |

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security problems.** Public
disclosure before a fix is available puts every consumer at risk.

Report privately through **GitHub Security Advisories**:

1. Go to <https://github.com/Twinsen81/Lustro/security/advisories/new>.
2. Describe the issue, affected versions, and a reproduction if you have one.
3. We will acknowledge the report and coordinate a fix and disclosure with you.

If you cannot use Security Advisories, open a minimal private channel with the
repository maintainer via GitHub rather than posting details publicly. Do **not**
include working exploit payloads, tokens, or captured traffic in any public
location.

## Threat Model

Lustro's security rests on the principle that it must never reach a production
build, and that even in debug builds it stays bound to the local device.

- **Debug-only by construction, with a runtime backstop.** Release builds depend
  on `:lustro-noop`, whose runtime bodies are empty — no server, no capture, and
  no open socket ship to production. Independently of that Gradle wiring, the
  real runtime reads `ApplicationInfo.FLAG_DEBUGGABLE` at startup: in a build
  that is not marked debuggable, `Lustro.start()` logs a WARN and returns
  `LustroStatus.DISABLED` without binding a socket, unless the consumer opts in
  with `DebugConfig.allowNonDebuggableBuilds(true)` for an internal build. A
  published lint check (`LustroDebugUsageInRelease`, severity ERROR) additionally
  flags `DebugTab` subclasses — consumer code the no-op swap cannot remove from
  an APK — outside a `debug` source set. Lustro does not inspect the consumer's
  dependency graph; the shared `io.github.twinsen81:lustro-runtime` capability
  only prevents `:lustro` and `:lustro-noop` landing on the same configuration,
  and `lustro-logs-runtime` does the same for `:lustro-logs` and
  `:lustro-logs-noop`, as `lustro-prefs-runtime`, `lustro-sqlite-runtime`, and
  `lustro-work-runtime` do for their pairs.
- **Loopback-bound by default.** The server binds to `127.0.0.1` and is
  reachable only while the app is foregrounded; the socket is torn down on
  background after draining in-flight requests. LAN exposure is strictly opt-in
  through `DebugConfig.bindAddress`.
- **Token auth is always on.** A token is generated on first run and stored in
  private debug preferences. Programmatic clients authenticate with
  `Authorization: Bearer <token>`; browsers use an `HttpOnly; SameSite=Strict`
  cookie. The token is logged at server start under the tag `LustroToken`. It is
  generated once and then persists: there is no rotation API, so the same token
  stays valid until the app's data is cleared or the app is reinstalled. Before
  authentication, the server serves only framework chrome — no tab-authored
  output or captured data.
- **Credentials stop at the server.** The server authenticates a request before
  dispatching it to a tab and strips the `Authorization` and `Cookie` headers
  from the `DebugRequest` that `DebugTab.handle()` receives. Tab code, including
  third-party tabs, never sees the token, so a tab that logs or echoes its
  request headers cannot leak it.
- **Content Security Policy.** Chrome and tab views are served with
  `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
  connect-src 'self'; img-src 'self' data:; form-action 'none'; object-src
  'none'; base-uri 'none'`. A policy a tab sets on its own response is enforced
  alongside this one, never instead of it. Every API request, whatever its
  method, passes an Origin / `Sec-Fetch-Site` check driven by
  `DebugConfig.allowedOrigins`. A browser can still send a cross-origin `GET`
  with neither header, so tabs must not change state on `GET` or `HEAD`.
- **Captured bodies can't run script under the console's origin.** A captured
  body is content from the app's servers, and
  `GET /api/v1/network/transactions/{id}/body/{request|response}` serves it
  from the console's own origin. Only PNG, JPEG, GIF, and WebP images are
  served inline, and with `nosniff` a browser never renders a body labelled as
  one of them as a page. Every other type, SVG and HTML included, is sent with
  `Content-Disposition: attachment` and the policy
  `default-src 'none'; sandbox` as well, so no script in it runs even if a
  browser renders it. The payload of a WebSocket message, from
  `GET /api/v1/network/websockets/{id}/events/{seq}/payload`, is always sent that way, as plain
  text or as bytes.
- **Capture-time redaction, best-effort.** A `Redactor` SPI runs at capture
  time, before anything is stored, so whatever it masks never enters the
  in-memory capture store and cannot leak through the API, the UI, or fixtures.
  The default implementation matches on *names*: sensitive request/response
  headers, and URL query parameters, JSON object fields, and form fields whose
  name contains a fragment such as `token`, `key`, `secret`, `password`, `auth`,
  or `signature`. A fragment that only sits inside an ordinary word, such as
  `auth` in `author_name`, `sig` in `assignments`, or `key` in `keywords`,
  doesn't count, and neither do names that hold no secret: `Idempotency-Key`,
  `public_key`, and `vapid_key`; a token count or limit, such as
  `prompt_tokens`, `max_tokens`, or `x-ratelimit-remaining-tokens`; and a key
  that names a column, such as `primary_key`. A name-based rule cannot see a
  secret that is not keyed by a name it recognizes, so the default redactor
  reduces exposure rather than guaranteeing there is none. Known gaps, where
  the value is stored as it arrived:
  - a credential in an object that names its value in a sibling field, such as
    `{"key": "api_key", "value": "..."}` or `{"name": "token", "value": "..."}`.
    The rules see the field names `key`, `name`, and `value`, so `value` is
    stored as it arrived, while a field named `key` is masked although it
    holds only the name;
  - a credential inside a string value under an ordinary key, such as a `url`,
    `next`, or `download` field holding a presigned URL, a paging link, or a
    query string carrying an `access_token`;
  - a credential in a URL's **path** or **fragment**
    (`/password-reset/<token>`, `#access_token=...`). Only the query string is
    scanned, and only by parameter name, so a path segment keeps its value even
    where the segment before it is a sensitive name;
  - userinfo in a URL (`https://user:password@host/...`);
  - URL-valued headers such as `Location` and `Referer`, including any
    credential in their query string;
  - a credential in a `multipart/form-data` part whose field name is ordinary,
    such as a text file whose content holds a key. OkHttp capture keeps the
    value of each text part and masks a part whose name is sensitive; it does
    not keep the bytes of a file or any other part that is not text;
  - the error text of a failed request, which is stored verbatim. This one is
    not a heuristic miss: the `Redactor` SPI has no hook for an error, so a
    custom redactor cannot cover it either. It matters because the platform puts
    the full request URL into some `HttpURLConnection` exception messages;
  - an image body. The capture keeps a request or response body whose type is
    an image as bytes, up to the capture cap, and the `Redactor` reads text
    only, so it never sees one, and a custom redactor cannot cover it either.
    An image can carry a secret in its pixels or its metadata, such as a photo's
    location. SVG is XML, so it is captured as text and redacted like any other.
    OkHttp capture keeps no other binary body, such as protobuf or an octet
    stream; platform `HttpURLConnection` capture decodes one as text;
  - a binary WebSocket message. The capture keeps it as bytes, up to the capture
    cap, and the name-based rules can't read it. A binary protocol can carry a
    credential, such as the password in an MQTT `CONNECT` packet. Unlike an
    image, a custom redactor can cover it: `Redactor.redactWebSocketBinary`
    gets the bytes, and can change them or keep them out of the store;
  - a credential in a WebSocket text message that no sensitive name points at,
    such as a message that is a bare token. The text of a message is redacted
    as a body with no content type, with the same gaps as a body;
  - the `Sec-WebSocket-Protocol` header, which some apps use to carry a token,
    because a browser can set no other header on a WebSocket;
  - the close reason of a WebSocket and the exception of a failed one, which
    are stored verbatim, as the error text of a failed request is.

  Treat a capture as sensitive. For everything above except the error text,
  image bodies, and a WebSocket's close reason, a stricter `Redactor` passed to
  `NetworkDebugTab.create(...)` closes the gap for traffic whose secrets the
  name heuristic will not find. For a request that no redactor can make safe, a
  `NetworkCaptureFilter` passed as `captureFilter` keeps the whole request out
  of capture, error text and image bodies included. It does the same for a
  WebSocket: a socket it skips has neither its handshake nor a message
  captured.
- **Log lines are not redacted.** The Logs plugin (`:lustro-logs`) serves the
  app's own logcat, `logcat --pid=<the app's pid>`, as the app wrote it, from
  `GET /api/v1/logs/entries` and `GET /api/v1/logs/text`. No `Redactor` runs on
  it. A log can hold what the network redaction masks: an `HttpLoggingInterceptor`
  at the `HEADERS` or `BODY` level writes the `Authorization` header and the
  bodies, and an app can log a token or a user's data itself. With the default
  loopback binding this adds little, because `adb logcat` can already read the
  same lines. With `DebugConfig.bindAddress` set for LAN access, every client
  with the token can read them. Treat the Logs tab as sensitive as the log: keep
  secrets out of the app's logs, or do not register the tab in a build whose
  server listens on a network. It reads only the app's own process, which
  logcat allows an app without any permission.
- **SharedPreferences values show as their policy lets them.** The Preferences
  plugin (`:lustro-prefs`) serves and edits the app's SharedPreferences files
  and Preferences DataStores under `/api/v1/prefs/`. A file's `ValuePolicy` decides what of its values a
  client sees. The default, `MASK_SENSITIVE`, goes by the key's name only, with
  the same check as the network redaction, so a secret under a key such as
  `cache` or `blob` shows. A registered `EncryptedSharedPreferences` that
  decrypts shows its plain values to every client with the token: register it
  with `KEYS_ONLY` when its values must stay hidden. The files that the tab
  discovers on disk are masked by key and read-only by default, and a file that
  has the keysets of `EncryptedSharedPreferences`, read without its keys, shows
  its keys only and is always read-only. A DataStore that the tab discovers is
  always read-only. Every other file can be changed: a
  client with the token can edit, delete, and clear its values, such as a
  session or a feature switch, and so change what the app does. Register a file
  or a data store with `readOnly = true` when no client may change it. With the default
  loopback binding this adds little, because `adb shell run-as` reads and
  writes the same files of a debuggable app. With `DebugConfig.bindAddress` set
  for LAN access, every client with the token can read and change them.
- **A registered database shows all of its contents.** See
  [SQLite databases](#sqlite-databases) below.
- **The WorkManager tab shows the output and the exceptions of the app's work,
  and cancels and prunes it.** See [WorkManager work](#workmanager-work) below.
- **Nothing persisted to disk except mock rules.** Captured traffic, WebSocket
  messages included, and log lines live only in bounded in-memory buffers and
  are lost when the process dies, as are the WorkManager tab's events. The sole
  persisted state is user-authored mock rules. The Preferences tab writes to
  disk only the app's own SharedPreferences files and the files of the
  DataStores that the app registers, when a client edits them.
- **Exceptions are contained.** Library exceptions do not escape into the host
  app; server-level errors are logged through `android.util.Log` at WARN.

### SQLite databases

The SQLite plugin (`:lustro-sqlite`) serves the tables, schemas, and rows of the
databases that the app registers, and with `discoverFrom` of every other
database file in the app's `databases` directory, under `/api/v1/sqlite/`.

- **Every client with the token reads every registered database, whole.** The
  tab has no value policy: a column that holds a token, a password hash, or a
  user's data shows as it is stored, and the schema of every table shows too.
  The live handle of an encrypted database, such as a Room database that
  SQLCipher opens, shows its plain contents. Register only the databases that a
  client may read, and turn discovery on only when the libraries' databases may
  show too. With the default loopback binding this adds little, because
  `adb shell run-as` reads the same files of a debuggable app. With
  `DebugConfig.bindAddress` set for LAN access, every client with the token can
  read them.
- **The routes that browse run only the SQL that the tab builds.** A client
  names a database, a table, and a column. The tab finds each name in the
  database's own schema before it puts it in a statement, as a quoted
  identifier, and a filter value is a bound parameter, so a request can't add
  SQL of its own. A database file, registered or discovered, is opened with
  `OPEN_READONLY` for one request, so the engine refuses a write there too, and
  a file in which SQLite finds corruption is left on disk, not deleted.
- **`POST query` is read-only for every database, and SQLite enforces it, not
  the API.** It runs a client's statement, and Android's `rawQuery` runs any
  statement that it is given, a `DELETE` too. So the statement never runs on
  the app's handle: the tab opens a connection with `OPEN_READONLY` to the
  database's file for the request, and closes it after. A write, also one that
  a statement classifier would read as a `SELECT`, such as
  ``WITH `x(` AS (SELECT 1) DELETE FROM t``, fails there with `SQLITE_READONLY`,
  and a pragma that the statement sets stays on that connection. A database in
  memory, and a live handle on another copy of SQLite than the platform's,
  take no `query`, because the tab's connection would not see that copy's
  locks.
- **Writes are opt-in for each database.** `POST exec`, `edit`, and `delete`
  write through the app's handle, and only to a database that the app
  registers with `writable = true`. Every other database answers `403` at the
  route, before the statement is read. Every client with the token can change
  a writable database: its rows, and its schema, `DROP TABLE` too. Make a
  database writable only when the clients may change it.
- **No request leaves state on a connection that the app shares.** Both
  `query` and `exec` refuse `BEGIN`, `COMMIT`, `END`, `ROLLBACK`, `SAVEPOINT`,
  `RELEASE`, `ATTACH`, `DETACH`, and `VACUUM` before they run, `exec` every
  `PRAGMA` too, and `query` the pragmas that set a value for every connection of
  the process, such as `hard_heap_limit`, which would make the app's own
  statements fail. A `BEGIN` would leave a transaction open on a pooled request
  thread, which blocks the app's writes; a pragma such as `query_only` or
  `foreign_keys` would change the app's connection; `ATTACH` changes its
  databases, and `ATTACH` and `VACUUM INTO` can create files. The check finds
  the statement's first keyword past comments, white space (a byte order mark
  too), empty statements, and `EXPLAIN`, as SQLite's tokenizer does; SQLite runs only the first
  statement of a text. `exec` also rolls back a statement that creates or
  drops a `TEMP` object, which would stay on the app's connection, but for a
  `TEMP` trigger that SQLite drops or rewrites with its table. Each write runs
  in a transaction of its own, which ends before the request does, and a write
  that breaks a deferred foreign key is rolled back before its commit: a
  failed commit would keep the transaction open on the app's connection, and
  the app's later writes would never commit.
- **The console shows a value as text.** The SQLite tab escapes every name and
  every value that it draws, in the grid, the row dialog, and the results of a
  statement, so a cell that holds HTML shows as text and can't run script
  under the console's origin.
- **A read is bounded.** A page has at most 500 rows, a text cell at most 4096
  characters, and a blob only its size. A `query` result has at most 1000
  rows, and fewer when their texts pass 4 Mi characters. Each read stops at
  the request's timeout, so that a request can't hold a lock that the app
  waits for, or fill the app's memory. Out of WAL mode, a reader's lock on a
  connection of its own makes the app's writer wait, and Android's SQLite fails
  a write that waited 2.5 s. So on a connection that the tab opens itself, for
  every `query` and for the routes that browse a file database, a statement
  there stops after 2 s. Through the app's own handle, a read waits in the
  app's connection pool, which has no such time limit.

An external review of the auth, CSP, and capture implementation is part of the
work remaining before the first public release (`0.1.0`), alongside the
publishing items listed in `CHANGELOG.md`.

### WorkManager work

The WorkManager plugin (`:lustro-work`) serves the app's WorkManager work and
the events of each run under `/api/v1/work/`.

- **Every client with the token reads the output, the progress, and the
  exceptions of the app's work.** A worker's output data can hold a user's data,
  such as an id or a server's answer, and an exception's message and stack trace
  can hold what the worker was working on. The tab has no value policy for them.
  It never reads a work's input data, which WorkManager's public API does not
  give. With the default loopback binding this adds little, because
  `adb shell run-as` reads WorkManager's database of a debuggable app, and
  `adb logcat` shows a worker's exception. With `DebugConfig.bindAddress` set for
  LAN access, every client with the token can read them.
- **Every client with the token can cancel work and prune finished work.**
  `POST cancel` cancels by id, unique name, or tag, and `POST prune` deletes the
  history of finished work, as the app's own calls to WorkManager would. Both
  are `POST`, so the same-origin checks apply. In an app that sets a
  work-analytics 2.12.0 repository as its listeners, cancelling work that runs
  ends the app's process through a bug of work-analytics, as the app's own
  cancel would, so a client with the token can stop such an app.
- **The tab adds listeners to the app's WorkManager configuration.** Each one
  records the event and calls the listener that the app set, with the same
  arguments; recording never throws, and what the app's listener throws reaches
  WorkManager as without the tab. The tab records only from `Lustro.start()` to
  `Lustro.stop()`, so it records nothing in a build that the runtime guard
  disables. The events stay on the heap, in a bounded buffer, and end with the
  process. The tab never initializes WorkManager.
- **The console shows a value as text.** The WorkManager tab escapes every
  tag, name, class, output value, exception message, and stack trace that it
  draws, so a value that holds HTML shows as text and can't run script under
  the console's origin. Cancel and Prune ask before they send.
- **Release builds get none of it.** `lustro-work-noop`'s `configure()` returns
  the app's builder as it is, so release WorkManager runs the app's own
  configuration. Neither artifact depends on WorkManager at runtime.

## NanoHTTPD Vendor-Patch Process

Lustro's server engine is **NanoHTTPD, pinned at version 2.3.1**. NanoHTTPD is
effectively unmaintained upstream, so we treat it as a vendored dependency and
own its security lifecycle:

- **Pinning.** The version is pinned exactly (no version ranges) in
  `gradle/libs.versions.toml`, and NanoHTTPD never appears in Lustro's public
  API — it stays `internal` behind `LustroServer`. This keeps the attack
  surface narrow and the dependency swappable.
- **Tracking.** NanoHTTPD CVEs and security discussions (its repository, the
  GitHub Advisory Database, and OSV) are monitored. Dependabot is configured for
  the Gradle ecosystem so any upstream release or advisory is surfaced promptly.
- **Patching.** When a relevant security fix is needed:
  1. If upstream ships a patched release, we bump the pin and run the
     wire-protocol contract tests and emulator matrix before publishing.
  2. If upstream is unresponsive (the expected case), we apply the fix to a
     **vendored / shadowed copy** of the affected source maintained in this
     repository, keep it pinned to the audited revision, and document the patch
     in `CHANGELOG.md` with a link to the originating advisory.
- **Containment.** Because the engine is internal, loopback-bound, behind token
  auth, and absent from release builds, the practical exposure of any NanoHTTPD
  defect is limited to a developer's local debug session.

## Disclosure Policy

We follow coordinated disclosure:

- We aim to acknowledge a report within **3 business days** and to provide an
  initial assessment within **10 business days**.
- We will agree on a disclosure timeline with the reporter, targeting a fix and
  public advisory within **90 days** of the report, sooner for actively
  exploited issues.
- Fixes are released in a new version, documented in `CHANGELOG.md`, and
  announced through a published GitHub Security Advisory that credits the
  reporter unless anonymity is requested.
