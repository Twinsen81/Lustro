package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import java.nio.charset.Charset
import okhttp3.MediaType.Companion.toMediaTypeOrNull

/**
 * Adapters between the `:lustro-api` HTTP value types ([Headers], [MediaType])
 * and OkHttp's own types, plus body-classification helpers.
 * All `internal` — OkHttp types never appear in the public surface here.
 */

/** Converts OkHttp [okhttp3.Headers] into the api [Headers], preserving order and duplicates. */
internal fun okhttp3.Headers.toApiHeaders(): Headers {
    if (size == 0) return Headers.EMPTY
    val builder = Headers.Builder()
    for (i in 0 until size) {
        builder.add(name(i), value(i))
    }
    return builder.build()
}

/** Converts the api [Headers] into OkHttp [okhttp3.Headers]. */
internal fun Headers.toOkHttpHeaders(): okhttp3.Headers {
    val builder = okhttp3.Headers.Builder()
    forEach { name, value -> builder.add(name, value) }
    return builder.build()
}

/** Converts an OkHttp [okhttp3.MediaType] into the api [MediaType], or `null`. */
internal fun okhttp3.MediaType?.toApiMediaType(): MediaType? =
    this?.let { MediaType.parse(it.toString()) }

/** Converts an api [MediaType] into an OkHttp [okhttp3.MediaType], or `null`. */
internal fun MediaType?.toOkHttpMediaType(): okhttp3.MediaType? =
    this?.toString()?.toMediaTypeOrNull()

/** True when the media type is one we capture body text for. */
internal fun okhttp3.MediaType?.isTextLike(): Boolean {
    if (this == null) return false
    val type = this.type
    val subtype = this.subtype
    return type == "text" ||
        subtype == "json" ||
        subtype == "xml" ||
        subtype == "x-www-form-urlencoded" ||
        subtype.endsWith("+json") ||
        subtype.endsWith("+xml")
}

/** True for a `text/event-stream` body. */
internal fun okhttp3.MediaType?.isEventStream(): Boolean =
    this?.type == "text" && subtype == "event-stream"

/** Resolves the charset of a body, defaulting to UTF-8. */
internal fun okhttp3.MediaType?.resolvedCharset(): Charset =
    this?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
