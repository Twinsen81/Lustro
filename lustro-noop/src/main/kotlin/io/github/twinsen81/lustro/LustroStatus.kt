package io.github.twinsen81.lustro

/** The result of [Lustro.start]: whether the debug server is serving. */
public enum class LustroStatus {
    /** The server bound successfully and is serving. */
    ENABLED,

    /** The server is not serving (bind failed, or stopped). */
    DISABLED,
}
