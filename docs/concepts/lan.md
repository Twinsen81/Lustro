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

The `LustroToken` line then says `endpoint=http://0.0.0.0:<port>`, which the CLI reads as the
device's loopback, and adds the URLs that a computer on the same network uses after the token:

```
Lustro ready endpoint=http://0.0.0.0:8080 token=<token> lan=http://192.168.1.42:8080
```

`lan=` lists the IPv4 addresses of the device's interfaces that are up, Wi-Fi first,
comma-separated, or the IPv6 ones when the device has no IPv4 address. App code gets the same URLs from `lustro.endpoint()?.lanUrls`, for example for a
developer screen (see [Use Lustro from your app's code](../get-started/app-code.md#show-how-to-connect)).

> **Risk:** `bindAddress = "0.0.0.0"` exposes the debug server (and your app's captured traffic)
> to everyone on the same network. Token auth still applies, but you lose the loopback boundary,
> and the token does not replace it: the server speaks plain HTTP, so each request of a client on
> the network carries the token, and a person who can record that network's traffic reads it.
> Use it only on a network where you trust every device, and prefer `adb forward` whenever you
> can. Wireless debugging (`adb connect`, then `adb forward`) needs no cable and no LAN exposure.

> **Browser login over LAN:** add the browser's origin to `DebugConfig.allowedOrigins`, such as
> `allowedOrigins(listOf("http://192.168.1.42:8080"))` for the address the browser shows. The
> `/api/v1/_auth` route is origin-checked like every other API route, and only a loopback host on
> the listening port counts as the server's own origin, so a page loaded over LAN is rejected with
> `403` until its origin is listed. Programmatic clients that send neither `Origin` nor
> `Sec-Fetch-Site`, such as `curl` or the CLI, are unaffected.

## A new token with each process

When the bind address is not loopback, the server makes a new token each time the app's process
starts, the first time it listens. The token of the process before gets `401`. So a token that
recorded traffic, a screenshot, or a log gave away is valid only until that process ends, not
until the app's data is cleared. It does not stop a person who records the traffic while the app
runs.

- **A browser** signs in again after the app restarts. A console tab that is open shows
  "Signed out" above its content, which stays as it was. Append the new `#lustro_token=<token>`
  to its URL, and the tab signs in without a reload. Each start of a new process is a restart:
  each install of a new build, and each start after Android ended the process in the background.
- **The CLI and agents with adb** find the token on each call, so they need no change.
- **The CLI without adb** uses the token of `--token` or `LUSTRO_TOKEN` as it is, and does not
  look for another one. That is each client that connects with `--host` from the network. After
  a restart it gets `401` until you give it the new token. A `LUSTRO_TOKEN` in a shell profile
  has the same effect with adb too, because it comes before the token that the CLI finds: do not
  set one for an app with LAN exposure. A script that keeps the token reads the last
  `LustroToken` line again after a `401`.
- **App code** calls `lustro.endpoint()` each time it shows the token, and does not keep it across
  processes.

With the default loopback binding, the token stays the same until the app's data is cleared.
From outside the device, a client reaches the server only through adb, and adb reads the same
data of a debuggable app without the token. An app on the device can connect to the port, and
there the token is the boundary. Such an app has no way to get it: the token is in the app's
private storage, an app can't read the log of another app, and the traffic stays on the device.

## No network security config

A network security config is **not** needed for any of this: it governs the connections your app
makes, not the socket Lustro listens on. Lustro serves plain HTTP and needs no
`cleartextTrafficPermitted` entry and no `usesCleartextTraffic` in either workflow.
