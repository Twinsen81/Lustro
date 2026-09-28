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
        subtype.endsWith("+xml")

// A body kept as bytes is stored as it arrived, because the Redactor can't read
// it. So only types that seldom carry a secret belong here. SVG is XML, so the
// Redactor reads it as text.
internal fun isRetainedBinary(type: String, subtype: String): Boolean =
    type == "image" && !isTextLike(type, subtype)

internal fun MediaType?.isRetainedBinary(): Boolean = this != null && isRetainedBinary(type, subtype)
