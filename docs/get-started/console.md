# Accessing the UI

The server is bound to loopback (`127.0.0.1`) on the device, so reach it from your desktop over
adb port forwarding:

1. Forward the port from your machine to the device:

    ```bash
    adb forward tcp:8080 tcp:8080
    ```

2. Open <http://localhost:8080> in a desktop browser.

3. The server is **token-authenticated** (always on). Read the token from logcat — Lustro logs
    one machine-parseable line at the `LustroToken` tag on every successful bind:

    ```bash
    adb logcat -s LustroToken
    # Lustro ready endpoint=http://127.0.0.1:8080 token=<token>
    ```

    Some devices drop Info logs: with `adb shell getprop log.tag` set to `E`, the line never
    reaches logcat. `adb shell setprop log.tag.LustroToken I` lets it through until the device
    restarts, and the line comes again when the app returns to the foreground. The token, and the
    host and port of the last bind, are also in the app's prefs, which a debuggable app lets you
    read: `adb shell run-as <package> cat shared_prefs/lustro_debug.xml`. The `lustro` CLI reads
    them on its own when the log has no line.

4. Authenticate the browser with that token, either by:
    - using the `lustro` CLI (`lustro open`, see [The `lustro` CLI](install.md#the-lustro-cli)); or
    - appending `#lustro_token=<token>` to the URL **once**
      (`http://localhost:8080/#lustro_token=<token>`). The page posts the token to set an
      `HttpOnly; SameSite=Strict` cookie, then strips the fragment from the address bar.

The server runs only while the app is **foregrounded**. When the app goes to the background the
server drains in-flight requests and closes the socket; it rebinds when the app returns to the
foreground.
