# Logs

The Logs plugin serves your app's own logcat: a live tail, filters, and a plain-text export. Your
app gives it no data source: the tab runs `logcat --pid=<your app's pid>` itself. It is a pair of
artifacts, split by variant as `lustro` is:

```toml
lustro-logs      = { group = "io.github.twinsen81", name = "lustro-logs",      version.ref = "lustro" }
lustro-logs-noop = { group = "io.github.twinsen81", name = "lustro-logs-noop", version.ref = "lustro" }
```

```kotlin
dependencies {
    debugImplementation(libs.lustro.logs)
    releaseImplementation(libs.lustro.logs.noop)
}

val lustro = Lustro.builder(application)
    .addTab(NetworkDebugTab.create(senderClient = client))
    .addTab(LogsDebugTab.create())
    .build()
```

- **From the start of the process.** The tab reads logcat from `start()` to `stop()`, also while
    the app is in the background, and starts with the lines that your process wrote before
    `start()`. `stop()` ends the `logcat` process; the next `start()` goes on after the newest line.
    A line that never reached logd is not in the tab, as it is not in `adb logcat`: one physical
    Android 16 device sets `log.tag` to `E` for about a second while an app starts, and drops the
    lines below Error of every process in that time. The tab's `logcat` runs as your app, so it
    also leaves out the lines that the process wrote as root before it became your app, such as
    `libprocessgroup: Created cgroup ...`, which `adb logcat --pid` shows.
- **A bounded buffer.** The newest lines stay in memory, at most `bufferBytes` (default 4 MiB, by
    the size of their text on the heap, about 12,000 lines of 100 characters):
    `LogsDebugTab.create(bufferBytes = 8 * 1024 * 1024)`. A line that repeats the line before folds
    into it as a count, so a flood of one line costs almost nothing.
- **A live tail in the console.** The **Logs** tab follows the newest lines, with a lowest level,
    a tag regex, and a message search, which the app applies to its whole buffer. It can pause,
    fold repeated lines, clear the buffer, copy the lines it shows as text, and show or hide the
    Time, Level, Tag, and PID / TID columns. The **Fatal** chip shows the lines at level F
    (`Log.ASSERT`); a Java crash and `Log.wtf()` are at level E, and a crash that ends the app's
    process ends the server too, so the tab never shows it. A line with JSON in it opens indented from the toolbar
    that shows on a row after a second. A link such as `/tab/logs#from=<ms>&to=<ms>`, which the
    WorkManager tab has for each run of a worker, opens the tab with only the lines of that time
    range, and a chip that clears it.
- **Routes.** `GET /api/v1/logs/entries` polls the log in the stream envelope, with `level`, `tag`,
    `q`, and `mq` filters, and `from` and `to`, a time range in epoch milliseconds, such as the run
    of a worker; `GET /api/v1/logs/text` exports it as logcat's threadtime text; and
    `POST clear`, `pause`, and `compact` control it. The `lustro` CLI reaches them with `lustro api`,
    for example `lustro api GET logs/text --param level=W`. See [Agents and automation](../AGENTS.md#logs-tab-operations)
    and [`logs.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro-logs/src/main/assets/lustro/logs.openapi.json).
- **Not redacted.** Log lines are served as the app wrote them, tokens that an
    `HttpLoggingInterceptor` logs among them. See [SECURITY.md](https://github.com/Twinsen81/Lustro/blob/main/SECURITY.md#threat-model).
- **Only logcat.** A message longer than logcat's limit of about 4 KB is cut, as in `adb logcat`.
    So is a tag longer than about 88 bytes: logcat prints the first part of it, and the message after.
- In `lustro-logs-noop`, the tab reads no logs.
