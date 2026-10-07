package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.MediaType

// Which bodies the adapters capture, by lower-cased type and subtype. A body the
// Redactor can read is captured as text.
internal fun isTextLike(type: String, subtype: String): Boolean =
    type == "text" ||
        subtype == "json" ||
        subtype == "xml" ||
        subtype == "x-www-form-urlencoded" ||
        subtype.endsWith("+json") ||
        subtype.endsWith("+xml") ||
        subtype in HLS_PLAYLIST_SUBTYPES ||
        subtype in JSON_STREAM_SUBTYPES

// An HLS playlist is text, under any of the types that servers send it with.
private val HLS_PLAYLIST_SUBTYPES = setOf("vnd.apple.mpegurl", "x-mpegurl", "mpegurl")

// JSON values one after another, which a server can keep sending for as long as
// the connection lasts: newline-delimited JSON, JSON Lines, and JSON text sequences.
private val JSON_STREAM_SUBTYPES = setOf("x-ndjson", "ndjson", "jsonl", "x-jsonl", "jsonlines", "x-jsonlines", "json-seq", "stream+json")

// A body that can stay open with no end, such as a subscription to events. The
// OkHttp adapter captures it as the app reads it: waiting for the body to end
// before the app gets the response would hold the app's call.
internal fun isTextStream(type: String, subtype: String): Boolean =
    (type == "text" && subtype == "event-stream") || subtype in JSON_STREAM_SUBTYPES

// A body kept as bytes is stored as it arrived, because the Redactor can't read
// it. So only types that seldom carry a secret belong here. SVG is XML, so the
// Redactor reads it as text.
internal fun isRetainedBinary(type: String, subtype: String): Boolean =
    type == "image" && !isTextLike(type, subtype)

internal fun MediaType?.isRetainedBinary(): Boolean = this != null && isRetainedBinary(type, subtype)

// A body whose declared type is neither text nor kept as bytes, such as audio or
// video, isn't captured. A body with no type is, as text: platform HttpURLConnection
// callers often send JSON without a Content-Type.
internal fun MediaType?.isUnkeptType(): Boolean = this != null && !isTextLike(type, subtype) && !isRetainedBinary(type, subtype)
