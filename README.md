<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="art/lustro-wordmark-dark.png">
    <img src="art/lustro-wordmark.png" alt="Lustro" width="420">
  </picture>
</p>

<p align="center"><b>A browser-based, agent-friendly debugging library for Android.</b></p>

Lustro embeds a small web server in your app's debug builds and serves its tools as tabs you
open in a desktop browser — so you inspect on a full screen instead of a cramped on-device
overlay. The built-in network inspector captures traffic and lets you mock responses, throttle
connections, and replay requests, and it shows the messages of WebSocket connections. Every tab is
also a JSON API, so AI agents and scripts can drive Lustro directly rather than scraping HTML.

It's built as an extensible tab platform: the network inspector ships in the box, and you add
your own tabs against a stable plugin contract.

> **Status:** early development. Pre-1.0 and not yet published — APIs and the wire protocol may
> change between releases. Only the `0.1.0-SNAPSHOT` line exists today (Sonatype Central
> snapshots); there is no stable release yet.

**Documentation: <https://twinsen81.github.io/Lustro/>**

## Quick start

1. Add the snapshots repository to `settings.gradle.kts`:

    ```kotlin
    dependencyResolutionManagement {
        repositories {
            mavenCentral()
            maven("https://central.sonatype.com/repository/maven-snapshots/")
        }
    }
    ```

2. Add the runtime for debug builds and the no-op half for release builds, in
   the app module's `build.gradle.kts`:

    ```kotlin
    dependencies {
        debugImplementation("io.github.twinsen81:lustro:0.1.0-SNAPSHOT")
        releaseImplementation("io.github.twinsen81:lustro-noop:0.1.0-SNAPSHOT")
    }
    ```

3. Build Lustro once, in `Application.onCreate()`, add its interceptor to your
   OkHttp client, and start it on the main thread:

    ```kotlin
    val lustro = Lustro.builder(application)
        .addTab(NetworkDebugTab.create())
        .build()

    val httpClient = OkHttpClient.Builder()
        .addInterceptor(lustro.networkInterceptor())
        .build()

    lustro.start()
    ```

4. Run the debug build, keep the app in the foreground, and open the console.
   The [`lustro` CLI](https://twinsen81.github.io/Lustro/get-started/install/#the-lustro-cli) forwards the
   port and opens the browser with the token:

    ```bash
    lustro open
    ```

    Without the CLI, forward the port with `adb forward tcp:8080 tcp:8080`,
    read the token with `adb logcat -d -s LustroToken`, and open
    `http://localhost:8080/#lustro_token=<token>`.

Lustro needs `minSdk` 24, `compileSdk` 35, Android Gradle Plugin 8.6, and Kotlin 2.0. It does
not raise the versions of your app's own dependencies: see
[Minimum versions](https://twinsen81.github.io/Lustro/get-started/install/#minimum-versions). The Logs, Preferences, SQLite,
WorkManager, and Screen tabs are plugins, each with a pair of artifacts of its own: see
[Modules](https://twinsen81.github.io/Lustro/reference/modules/).

## With a coding agent

Give a coding agent the repository, for example "Add Lustro to this app, following
https://github.com/Twinsen81/Lustro, and check the integration". [`llms.txt`](llms.txt) summarizes
Lustro for agents and links the rest of the docs. The [`lustro` skill](skills/lustro/SKILL.md)
teaches an agent to debug the app through the `lustro` CLI, and to add Lustro and check the
integration. Install it into your project:

```bash
npx skills add Twinsen81/Lustro --skill lustro
```

See [With a coding agent](https://twinsen81.github.io/Lustro/get-started/coding-agent/) for the other ways to install it.

## Documentation

| Section | What it has |
| --- | --- |
| [Get started](https://twinsen81.github.io/Lustro/get-started/install/) | Install, the minimum versions, the `lustro` CLI, the quick start, the console, and a check of the integration. |
| [Network tab](https://twinsen81.github.io/Lustro/network/okhttp/) | OkHttp capture, WebSocket messages, Send Request, export, mock rules, `HttpURLConnection`, and other HTTP clients. |
| [Plugins](https://twinsen81.github.io/Lustro/plugins/logs/) | The Logs, Preferences, SQLite, WorkManager, and Screen tabs. |
| [Custom tabs](https://twinsen81.github.io/Lustro/custom-tabs/) | Your own `DebugTab`, and the styleguide for its view. |
| [Agents and automation](https://twinsen81.github.io/Lustro/AGENTS/) | The `lustro` CLI, token discovery, the HTTP routes of each tab, and the wire protocol. |
| [Concepts](https://twinsen81.github.io/Lustro/concepts/security/) | The security model, and LAN exposure. |
| [Reference](https://twinsen81.github.io/Lustro/reference/troubleshooting/) | Troubleshooting, the modules, and the [Kotlin API reference](https://twinsen81.github.io/Lustro/api/). |

The pages are Markdown files in [`docs/`](docs/), so an agent can also read them in the
repository.

See also: [CONTRIBUTING.md](CONTRIBUTING.md) · [SECURITY.md](SECURITY.md) ·
[CHANGELOG.md](CHANGELOG.md) · [DECISIONS.md](DECISIONS.md)

## License

[Apache License 2.0](LICENSE)
