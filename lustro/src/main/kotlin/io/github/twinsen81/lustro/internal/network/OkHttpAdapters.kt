package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import java.nio.charset.Charset
import okhttp3.MediaType.Companion.toMediaTypeOrNull

internal fun okhttp3.Headers.toApiHeaders(): Headers {
    if (size == 0) return Headers.EMPTY
    val builder = Headers.Builder()
    for (i in 0 until size) {
        builder.add(name(i), value(i))
    }
    return builder.build()
}

internal fun Headers.toOkHttpHeaders(): okhttp3.Headers {
    val builder = okhttp3.Headers.Builder()
    forEach { name, value -> builder.add(name, value) }
    return builder.build()
}

internal fun okhttp3.MediaType?.toApiMediaType(): MediaType? =
    this?.let { MediaType.parse(it.toString()) }

internal fun MediaType?.toOkHttpMediaType(): okhttp3.MediaType? =
    this?.toString()?.toMediaTypeOrNull()

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

internal fun okhttp3.MediaType?.isEventStream(): Boolean =
    this?.type == "text" && subtype == "event-stream"

internal fun okhttp3.MediaType?.resolvedCharset(): Charset =
    this?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
