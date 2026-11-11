# Install

Lustro ships as two interchangeable runtime artifacts that you split by build variant:

- `io.github.twinsen81:lustro` — the real debug runtime (the embedded server, capture, and UI).
- `io.github.twinsen81:lustro-noop` — a release-safe no-op AAR that mirrors the same public
    facades with empty bodies, so your integration code compiles and runs in release with **no
    server, no capture, and no open socket**.

Both modules declare the same Gradle capability (`io.github.twinsen81:lustro-runtime`), so you
**cannot** resolve both on one configuration — you must split them by variant
(`debugImplementation` / `releaseImplementation`):

```toml
# gradle/libs.versions.toml
[versions]
lustro = "0.1.0-SNAPSHOT"

[libraries]
lustro      = { group = "io.github.twinsen81", name = "lustro",      version.ref = "lustro" }
lustro-noop = { group = "io.github.twinsen81", name = "lustro-noop", version.ref = "lustro" }
```

```kotlin
// build.gradle.kts (app module)
dependencies {
    debugImplementation(libs.lustro)
    releaseImplementation(libs.lustro.noop)
}
```

`-SNAPSHOT` versions resolve from the Sonatype Central snapshots repository, so add it (only
needed while Lustro is pre-release):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
}
```

Lustro declares the `INTERNET` permission in its manifest.

## Minimum versions

Adding Lustro does not raise the versions of your app's own dependencies. Each published
artifact declares the oldest version of a dependency that has the APIs it uses, and Gradle
keeps your newer one.

| | Minimum | Notes |
| --- | --- | --- |
| Android Gradle Plugin | 8.6 | The oldest that supports `compileSdk` 35. |
| `compileSdk` | 35 | |
| `minSdk` | 24 | 21 for the no-op halves, which call no Android API. An app with a lower `minSdk` raises it for its debug variant only: see below. |
| Kotlin | 2.0 | The artifacts carry Kotlin 2.0 metadata and need `kotlin-stdlib` 2.0.21 or newer. |
| OkHttp | 4.12.0 | 4.x or 5.x. CI runs Lustro's tests against OkHttp 5 as well. |
| `androidx.lifecycle` | 2.4.1 | `lifecycle-process` only, and only in `lustro`. |
| `kotlinx.coroutines` | none | Lustro has no coroutines dependency. `lustro-prefs` calls the app's own coroutines only for a registered DataStore. |
| `androidx.datastore` | none | `compileOnly` in `lustro-prefs` and `lustro-prefs-noop`. A registered `PrefsDataStore` needs DataStore 1.0, and a `bytes` value 1.1. |
| `androidx.sqlite` | none | `compileOnly` in `lustro-sqlite` and `lustro-sqlite-noop`. A registered `SqliteDb.support` needs `androidx.sqlite` 2.0, which Room brings. |
| `androidx.work` | none | `compileOnly` in `lustro-work` and `lustro-work-noop`. The WorkManager tab needs `work-runtime` 2.12.0, and its history `work-analytics` 2.12.0. |
| `androidx.compose.ui` | none | `compileOnly` in `lustro-screen`. The Screen tab reads the Compose semantics tree with Compose UI 1.6.0 or newer, and the View tree in any app. |

The runtime needs API 24, for `logcat --pid` among others. An app whose `minSdk` is lower keeps it
for its release build, which takes the no-op halves, and raises it for the debug variant, which
takes the runtime. WorkManager 2.12.0, which the WorkManager tab needs, needs API 24 itself:
with an older WorkManager, the tab answers `work_unsupported`.

```kotlin
// build.gradle.kts (app module)
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variant ->
        variant.minSdk = 24
    }
}
```

```groovy
// build.gradle (app module), the same in Groovy
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variant ->
        variant.minSdk = 24
    }
}
```

`lustro` also brings NanoHTTPD 2.3.1, its embedded server. CI builds a small app in
[`consumer-check/`](https://github.com/Twinsen81/Lustro/tree/main/consumer-check) with exactly these versions, and again on a current
toolchain, and fails when a published artifact would change any version the app has. CI also
builds it with `minSdk` 23, with its debug variant raised as above, and it registers every tab
from Java too.

## The `lustro` CLI

The `lustro` command opens the console with its token, and drives every tab from a terminal, a
script, or a coding agent: see [Agents and automation](../AGENTS.md#use-the-lustro-cli). It is a
Python package on PyPI, `lustro-cli`, that needs Python 3.9 or newer and no other package. Each
release publishes it with the library's version.

```bash
uv tool install lustro-cli    # or: pipx install lustro-cli
lustro open                   # forwards the port, and opens the console with the token
uvx lustro-cli net list       # runs the CLI without an install
```

The PyPI package `lustro` is a different project that also installs a `lustro` command, so do not
install it. Until the first release, the CLI is not on PyPI. Install it from GitHub with
`uv tool install "git+https://github.com/Twinsen81/Lustro#subdirectory=lustro-cli"`, which pipx also takes, or from a checkout
of this repository with `uv tool install ./lustro-cli`.
