package io.github.twinsen81.lustro.internal

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Internal-to-:lustro JSON content-type constant. NanoHTTPD 2.3.x defaults to
// US-ASCII for any mime type that's not text/* and doesn't carry an explicit
// charset, so non-ASCII chars in the body get encoded as `?`. Pin charset=utf-8
// on every JSON response so multi-byte chars (e.g. an ellipsis "…" in an
// auto-generated rule name) round-trip cleanly to the JS UI.
internal const val JSON_CONTENT_TYPE: String = "application/json; charset=utf-8"

private val DEBUG_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

private val DEBUG_DATETIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
        .withZone(ZoneId.systemDefault())

internal fun Long.toDebugTimestamp(): String =
    DEBUG_TIME_FORMATTER.format(Instant.ofEpochMilli(this))

internal fun Long.toDebugDateTime(): String =
    DEBUG_DATETIME_FORMATTER.format(Instant.ofEpochMilli(this))
