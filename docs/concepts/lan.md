# LAN exposure and port forwarding

The default workflow is loopback + `adb forward` (see [Accessing the UI](../get-started/console.md)); it
needs no LAN exposure.

To reach the server from another machine on the network, opt in by binding all interfaces:

```kotlin
val lustro = Lustro.builder(application)
    .config(DebugConfig.builder().bindAddress("0.0.0.0").build())
    .addTab(NetworkDebugTab.create(senderClient = client))
    .build()
```

> **Risk:** `bindAddress = "0.0.0.0"` exposes the debug server (and your app's captured traffic)
> to everyone on the same network. Token auth still applies, but you lose the loopback boundary.
> Use it only on trusted networks, and prefer `adb forward` whenever you can.

> **Browser login over LAN:** add the browser's origin to `DebugConfig.allowedOrigins`, e.g.
> `allowedOrigins(listOf("http://192.168.1.42:8080"))` for the address the browser shows. The
> `/api/v1/_auth` route is origin-checked like every other API route, and only a loopback host on
> the listening port counts as the server's own origin, so a page loaded over LAN is rejected with
> `403` until its origin is listed. Programmatic clients that send neither `Origin` nor
> `Sec-Fetch-Site`, such as `curl` or the CLI, are unaffected.

A network security config is **not** needed for any of this: it governs the connections your app
makes, not the socket Lustro listens on. Lustro serves plain HTTP and needs no
`cleartextTrafficPermitted` entry and no `usesCleartextTraffic` in either workflow.
