package io.github.twinsen81.lustro.network

import androidx.annotation.RestrictTo

/**
 * An opaque handle identifying a captured network transaction.
 *
 * Library-constructed: both the constructor and the [value] accessor are
 * restricted to the `io.github.twinsen81` library group, so only `:lustro`
 * constructs and reads it while consumers treat it as an opaque token. Not a
 * value class, so it remains a stable, mockable reference type.
 */
public class TransactionId @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP) constructor(
    /** The underlying identifier string; for library-group use only. */
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public val value: String,
) {
    /** Returns the underlying identifier string. */
    override fun toString(): String = value

    /** Equality by the underlying [value]. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransactionId) return false
        return value == other.value
    }

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int = value.hashCode()
}
