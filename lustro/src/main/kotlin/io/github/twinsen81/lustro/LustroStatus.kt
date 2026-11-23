package io.github.twinsen81.lustro

/** The result of [Lustro.start]: whether the debug server is armed. */
public enum class LustroStatus {
    /**
     * The server is armed: it binds its socket each time the app comes to the
     * foreground, and closes it when the app goes to the background. It does not
     * say that the server listens: [Lustro.start] also returns it while the app is
     * in the background, before any bind, and a bind can fail, such as on a port
     * that another app holds. [Lustro.endpoint] is not `null` while the server
     * listens.
     */
    ENABLED,

    /**
     * The server is not armed and opens no socket: the app is not marked
     * debuggable, arming failed, such as for a call off the main thread or two
     * tabs with one id, or the build has the no-op runtime.
     */
    DISABLED,
}
