# Contributing to Lustro

Thanks for your interest in improving Lustro. This guide covers building,
testing, the module layout, coding standards, the sign-off requirement, and the
pull-request process. By contributing you agree your work is licensed under the
project's [Apache License 2.0](LICENSE).

## Prerequisites

- JDK 21 (the Kotlin toolchain targets JVM 17 bytecode).
- The Android SDK with `compileSdk 35`. `minSdk` is 24.
- Use the Gradle wrapper (`./gradlew`); do not install a separate Gradle.
- Node 18+ **only** to run the debug console's JavaScript tests. It is not
  needed to build the library, and those tests are deliberately outside
  Gradle `check` so they never block a build.

## Building

Build the runtime library AAR:

```bash
./gradlew :lustro:assembleDebug
```

Other useful targets:

```bash
./gradlew :lustro-api:compileKotlin          # public SPI module
./gradlew :lustro-noop:compileDebugKotlin     # no-op release artifact
./gradlew :sample:assembleDebug :sample:assembleRelease   # cross-variant sample
```

## Running tests

```bash
./gradlew :lustro:testDebugUnitTest      # unit + Robolectric tests for :lustro
./gradlew test                           # all module unit tests
./gradlew :build-logic:test              # the convention plugins, checkFacadeParity and the API baselines among them
```

Tests use JUnit 4, Robolectric for the Android pieces, and MockWebServer for
OkHttp-side tests. Wire-protocol changes must keep the contract tests and JSON
Schema validation green. CI does not require internet access — network tests
run against MockWebServer fixtures.

Robolectric downloads nothing while the tests run. Gradle resolves the
`android-all` jar of each SDK level that the tests run on, and the unit tests
of the Android modules read the jars offline from `build/robolectric-jars`. A
test on a level that `gradle/robolectric.gradle.kts` does not list fails with
the name of the jar that Robolectric could not find: add the level there.

A change of the wire protocol version in `lustro/build.gradle.kts` also sets
`PROTOCOL_VERSION` in `lustro-cli/src/lustro_cli/__init__.py`, which
`lustro --version` prints: `./gradlew checkCliVersion` fails until the two are
the same.

A pull request that changes a file under `lustro-cli/src/` increases `REVISION`
in `lustro-cli/src/lustro_cli/__init__.py` by one, and its CHANGELOG entry names
the new revision. A wrapper script that installs the CLI from `main` requires the
revision of the fix that it needs. CI fails the pull request when the revision is
not the base branch's plus one; run the same check before you push:

```bash
python3 .github/scripts/check_cli_revision.py origin/main
```

When another pull request with a change to the CLI merges first, update your
branch with `main` and take the next number before you merge. Git does not
report a conflict when both pull requests set the same number, so the check
also runs on each push to `main`, and fails there when the push did not
increase the revision by one. Then increase it in a new pull request, and
correct the revision in the CHANGELOG entry of the change.

The modules compile against OkHttp 4.12.0, the floor an app can stay on. To run
the suite with OkHttp 5 on the test runtime classpath, as CI does:

```bash
./gradlew :lustro:testDebugUnitTest :sample:testDebugUnitTest -PlustroTestOkHttpVersion=5.5.0
```

The debug console's browser JavaScript ships inside the runtime AAR but is not
built by Gradle, so it has its own runner — the one built into Node, with no
`package.json` and no dependencies:

```bash
node --test */src/test/js/*.test.js
```

Those tests cover the code worth pinning rather than the file as a whole: the
body viewers (`debugScanJsonSource` and the JSON formatting, highlighting, and
tree on top of it, the XML and HTML printer, the line-numbered text, the form
table, and the hex dump), the HTML escaping that decides whether a captured
body renders as text or as markup, and how a search's matches are marked. DOM wiring, resizers, toasts, modals, and one-line helpers are
deliberately left untested. Please keep it that way when adding to them, and
keep the suite dependency-free.

A plugin's view has its tests in its own module, in `src/test/js` next to
`src/main/assets/lustro`, as `lustro-logs` has: they load the plugin's script
after `shared.js` with `loadTabJs(module, id)` from
`lustro/src/test/js/harness.js`, and read the wire goldens with `golden(name)`.

The platform `HttpURLConnection` capture gets the platform's URL handlers by
reflection, so it has an instrumented test that needs a device or emulator:

```bash
./gradlew connectedCheck
```

The test asserts which way the capture got the handlers, so a release that
blocks it fails the test instead of turning capture off. When a new release
fails it, update the expectation in `PlatformHttpCaptureTest`. An instrumented
test that runs longer than 60 s fails with its name (`timeout_msec` in
`lustro/build.gradle.kts`). `connectedCheck` can pass with no test run, for
example when the install of the test APK fails, so CI then runs
`python3 .github/scripts/check_connected_results.py`: it fails when a module
with a `.kt` or `.java` file in `src/androidTest` has no result file under
`build/outputs/androidTest-results/connected/`, or a result file with 0 tests,
and names those modules.

`.github/scripts/cli-e2e.sh` runs the CLI against the debug sample on the
connected device. It installs the sample, installs the CLI from the checkout
into a virtual environment, and checks the output of the main commands against
the CLI's wire schemas. No command passes `--token`, so it also tests discovery
through logcat. It needs `adb` and Python 3.9 or later. `lustro open` forwards
host port 8080 when it is free, or else uses a forward on a port that adb chose;
`CLI_E2E_LOCAL_PORT` names another port for it, and `0` lets adb choose one. The run changes the device and does not change it back: it
replaces the sample's mock rules with one rule that mocks `GET /get`, and it
sets the device to stay awake while it charges. CI runs `connectedCheck` on API
24, 35, and 36 emulators, and the end to end on API 24 and 35: on API 36 the
runner's emulator often stops responding before the end to end reads the
screen.

## Documentation site

The user docs are Markdown in `docs/`, and [Zensical](https://zensical.org)
builds them into the site that `.github/workflows/docs.yml` publishes to
GitHub Pages; `zensical.toml` sets the navigation and the theme. To preview a
change, with Python 3.11 or later:

```bash
python -m pip install -r docs/requirements.txt
zensical serve                  # http://localhost:8000, rebuilt on each save
zensical build --clean --strict # what CI runs: fails on a broken link or anchor
```

A link between pages is relative and names the Markdown file, such as
`[Agents](AGENTS.md#use-the-lustro-cli)`, so that the strict build checks it. A
link to a file outside `docs/`, such as a source file, is a full GitHub URL.
A new page needs a line in the `nav` of `zensical.toml`, so that people find
it. When it documents a tab, an API, or a task, it also needs a link in
`llms.txt`, so that agents find it. The README keeps only the
quick start and links to the site.
The strict build does not read `llms.txt` or the README. `./gradlew
checkPluginDocs` does: a link there to a raw GitHub file must name a file in
the repository, and a link to the site must name a page in `docs/`. So when you
rename or move a page, update those links too.
The Kotlin API reference and the wire-protocol schemas are not in the preview:
CI adds the Dokka output under `api/` and `wire-protocol/` when it publishes.

## Module layout

- **`:lustro-api`** — pure-Kotlin JAR with the public SPI only: `DebugTab`,
  `DebugRequest`, `DebugResponse`, `Headers`, `MediaType`, and the network seams
  (`NetworkCaptureSink`, `NetworkCaptureFilter`, `NetworkCaptureRequest`,
  `NetworkSender`, `NetworkClassifier`, `Redactor`, `MockRule`,
  `NetworkSendRequest`, `NetworkSendResult`, `TransactionId`, `CapturedBody`,
  `CapturedResponse`).
  Consumer-constructed types carry their concrete implementations here; no
  OkHttp dependency.
- **`:lustro`** — the runtime AAR: NanoHTTPD-backed server, tab registry, asset
  loader, the built-in Network plugin, mock-rule storage, and OkHttp adapters.
  All server/runtime code is Kotlin `internal`.
- **`:lustro-noop`** — the release-side no-op AAR. It mirrors the public facades
  of `:lustro` with empty bodies so release builds ship no server. `:lustro` and
  `:lustro-noop` declare the same Gradle capability, so a consumer cannot pull
  both.
- **`:lustro-logs`** and **`:lustro-logs-noop`**: the Logs plugin pair, the
  app's own logcat under `/api/v1/logs/` and its release-safe no-op half.
- **`:lustro-prefs`** and **`:lustro-prefs-noop`**: the Preferences plugin
  pair, the app's SharedPreferences under `/api/v1/prefs/` and its
  release-safe no-op half.
- **`:lustro-sqlite`** and **`:lustro-sqlite-noop`**: the SQLite plugin pair,
  the app's SQLite and Room databases under `/api/v1/sqlite/` and its
  release-safe no-op half.
- **`:lustro-work`** and **`:lustro-work-noop`**: the WorkManager plugin pair,
  the app's WorkManager work and the events of each run under `/api/v1/work/`,
  and its release-safe no-op half. Its unit tests run on the newest WorkManager
  of the catalog (`work`), while the modules compile against the floor
  (`workFloor`).
- **`:lustro-screen`** and **`:lustro-screen-noop`**: the Screen plugin pair,
  the app's windows and UI tree under `/api/v1/screen/`, and its release-safe
  no-op half. The runtime compiles against Compose UI's floor
  (`composeUiFloor`), and its unit tests compose screens with the catalog's
  Compose BOM; only the test compilations run Compose's compiler.
- **`:sample`** — demo app and public-API regression target; debug uses
  `:lustro`, `:lustro-logs`, `:lustro-prefs`, `:lustro-sqlite`,
  `:lustro-work`, and `:lustro-screen`, release uses their no-op halves.
- **`:lustro-cli`** — the Python CLI that drives Lustro over the HTTP API.
- **`build-logic/`**: an included build with the convention plugins that the
  published modules apply, so their build files hold only what is their own:
  - `lustro.android-library`: an Android library module. compileSdk and minSdk
    from the catalog, the `lustro_` resource prefix, no BuildConfig, toolchain
    21 with JVM 17 bytecode, explicit API, the Kotlin floor and
    `checkKotlinFloor`, detekt, and the API baseline (`apiCheck`, `apiDump`).
    Its `lustroLibrary { pairCapability(...) }`
    declares the capability that both halves of a pair share.
  - `lustro.kotlin-library`: the same for a Kotlin/JVM module, such as
    `:lustro-api`.
  - `lustro.publishing`: the vanniktech publishing, after one of the two
    above (or `java-library` alone). Each module with it asks for its own
    version of every other module with it, with a dependency constraint, and
    `checkPublishedConstraints` checks that its Gradle module metadata has
    them. The root build adds every module with it to the Dokka site and to
    `collectReleaseArtifacts`, which collects the files of the GitHub Release.
  - `lustro.facade-parity`: `checkFacadeParity`, at the root.

The wire protocol is versioned under `wire-protocol/v1/`. Each tab's OpenAPI
document is in its own module. Every tab of this repository declares its
routes, and its module commits the generated document as
`openapi/<id>.openapi.json` (`lustro/openapi/network.openapi.json` for the
Network tab). A hand-written document in `src/main/assets/lustro/<id>.openapi.json`
is an option for an app's custom tab, not for a plugin tab here.

### Adding a plugin module pair

A plugin tab ships as two artifacts, `:lustro-<name>` and `:lustro-<name>-noop`
(DECISIONS.md, row 7). Both depend only on `:lustro-api`, and an app adds them
as it adds `:lustro`: `debugImplementation` for the runtime and
`releaseImplementation` for the no-op half.

1. Add both modules to `settings.gradle.kts`.
2. Give each a `gradle.properties` with `POM_NAME` and `POM_DESCRIPTION`, and a
   `build.gradle.kts` such as:

   ```kotlin
   plugins {
       id("lustro.android-library")
       id("lustro.publishing")
   }

   android {
       namespace = "io.github.twinsen81.lustro.<name>" // ".<name>.noop" for the no-op half
   }

   dependencies {
       api(project(":lustro-api"))
   }

   lustroLibrary {
       // The same name in both halves, so that an app can't resolve both.
       pairCapability("lustro-<name>-runtime")
   }
   ```

   The no-op half keeps its unused-parameter findings in `detekt-baseline.xml`
   (`./gradlew :lustro-<name>-noop:detektBaseline`), as `:lustro-noop` does.
3. Add the pair and its facade packages to the `facadeParity { }` block of the
   root `build.gradle.kts`.
4. Put the tab's assets in the runtime module: `src/main/assets/lustro/<id>.js`
   and `<id>.css`. The server serves them from the app's merged assets, with no
   change to `:lustro`. The no-op half ships no assets. Give the tab an OpenAPI
   document with its own `info.version` (DECISIONS.md, row 9): declare the
   routes in `routes()` (DECISIONS.md, row 19), as every plugin tab does, such
   as `:lustro-prefs`. The tab then makes its document, and the module commits
   a copy of it as `openapi/<id>.openapi.json`, outside the assets. A unit test
   compares the copy with the declarations, and writes it when the tests run
   with `LUSTRO_UPDATE_OPENAPI=1` in the environment (see
   `PrefsOpenApiDocumentTest`).

   Then add the document to the list in `lustro-wire-schema/build.gradle.kts`
   and to `lustro-cli/src/lustro_cli/wire/sync.py`, and run the sync
   (`PYTHONPATH=src python -m lustro_cli.wire.sync` in `lustro-cli/`), so that
   the wire schema artifact and the CLI have it.
5. Write the docs of the tab. `<name>` is the module suffix and the tab id, so
   the page of `:lustro-work` is `docs/plugins/work.md`.
   1. Copy `docs/plugins/_template.md` to `docs/plugins/<name>.md`, and fill
      in each section: what the plugin does, install, register the tab, safety
      options, the console view, the HTTP routes, CLI examples, and limits.
      Keep the headings and their order. When a section does not apply to the
      tab, say so in one sentence.
   2. Add the page to the "Plugins" section of the `nav` in `zensical.toml`.
   3. Add a link to the page to the "## Docs" list of `llms.txt`, as a raw
      GitHub URL such as the other plugin pages have, and a fact about the tab
      to the list at the top of the file. Add a link to the tab's OpenAPI
      document to the "## API contracts" list.
   4. Add `lustro-<name>/` to `folders` in `context7.json`.
   5. Add a row for each half to the modules table in
      `docs/reference/modules.md`.
   6. When the tab has a console view, add a screenshot of it in the sample
      app as `docs/images/<name>-tab.png`, and show it in the "Console view"
      section.

   `./gradlew checkPluginDocs` enforces steps 5.1 to 5.5: it fails when the
   page is missing or its level-2 headings differ from the template's, when
   the nav, `llms.txt`, or `context7.json` does not name the page or the
   module, or when the modules table has no row for a half. It does not check
   what the sections say, the fact and the OpenAPI link in `llms.txt`, or the
   screenshot: the reviewer checks those. It finds the plugin modules from the
   build, so the pair needs no entry in it.

Apart from the OpenAPI document lists of step 4, nothing else in the build
names the modules: the Dokka site and the GitHub Release take every module that applies `lustro.publishing`. Until the pair has commands of
its own, `lustro api` reaches its routes, for example
`lustro api GET <id>/entries --param level=W`.

## Coding standards

- **Explicit API mode.** The library is compiled with
  `-Xexplicit-api=strict`: every public declaration needs an explicit
  visibility and an explicit return type.
- **No public `data class`**, no public sealed types, no public `value class` in
  signatures, no public suspending functions, and no public `inline reified`
  APIs. Library-constructed types are read-only interfaces or opaque final
  classes; consumer-constructed types get factories in `:lustro-api`.
- **No third-party types in public signatures** except the committed OkHttp
  convenience facades. No NanoHTTPD, Hilt, router, or server-engine types are
  ever public.
- **KDoc on every public member.** Document behavior, threading, and nullability.
- **`internal` / `private` for everything not in the published surface.**
  `@RestrictTo(LIBRARY_GROUP)` is only for cross-module helpers consumers should
  not call; use `@RequiresOptIn` for unstable SPI.
- **detekt** runs in CI with the library rule set (explicit return types, no
  public `data class`, public-API documentation). Run it locally before
  pushing:

  ```bash
  ./gradlew detekt
  ```

## Developer Certificate of Origin (DCO)

All commits must be signed off. Lustro uses the
[Developer Certificate of Origin 1.1](https://developercertificate.org/): by
adding a `Signed-off-by` line you certify that you wrote the contribution or
otherwise have the right to submit it under the project's open-source license.

Sign off when committing:

```bash
git commit -s -m "Your message"
```

This appends a trailer like:

```
Signed-off-by: Your Name <you@example.com>
```

Use your real name and an email address you can be reached at. The full text of
the DCO (version 1.1):

> By making a contribution to this project, I certify that:
>
> (a) The contribution was created in whole or in part by me and I have the
>     right to submit it under the open source license indicated in the file; or
>
> (b) The contribution is based upon previous work that, to the best of my
>     knowledge, is covered under an appropriate open source license and I have
>     the right under that license to submit that work with modifications,
>     whether created in whole or in part by me, under the same open source
>     license (unless I am permitted to submit under a different license), as
>     indicated in the file; or
>
> (c) The contribution was provided directly to me by some other person who
>     certified (a), (b) or (c) and I have not modified it.
>
> (d) I understand and agree that this project and the contribution are public
>     and that a record of the contribution (including all personal information
>     I submit with it, including my sign-off) is maintained indefinitely and
>     may be redistributed consistent with this project or the open source
>     license(s) involved.

Every commit in a pull request must carry a sign-off line.

## Pull request process

1. Fork and branch from `main`. Keep changes focused; one logical change per PR.
2. Add or update tests for your change.
3. Update [`CHANGELOG.md`](CHANGELOG.md) under `## [Unreleased]`.
4. Update docs (KDoc, the pages in `docs/`, or wire-protocol files) when behavior
   or the public surface changes. When the integration steps change, update
   `README.md`, `docs/index.md`, `llms.txt`, and the agent skill in `skills/lustro/`
   with the pages in `docs/get-started/`. `checkDocsVersion` fails when the Maven
   coordinates in them, or in any page in `docs/`, differ from `gradle.properties`.
5. Make sure the full local gate passes:

   ```bash
   ./gradlew test :build-logic:test detekt apiCheck checkFacadeParity checkPublishedConstraints checkDocsVersion checkPluginDocs checkCliVersion
   ```

6. Open the PR, fill in the template, and confirm every commit is signed off.
   CI must be green before review.

## API compatibility expectations

Lustro follows [Semantic Versioning](https://semver.org/): **major** for
breaking changes, **minor** for additive changes, **patch** for fixes.

- **Binary Compatibility Validator (`apiCheck`) must pass.** Every published
  module with a Kotlin API has a committed baseline, `<module>/api/<module>.api`:
  `:lustro-api`, the SPI that both halves of each pair depend on, and both
  halves of each runtime and no-op pair. BCV registers its tasks for
  `:lustro-api` itself, and the `lustro.android-library` convention plugin
  registers them for the Android modules, which BCV does not support under
  AGP's built-in Kotlin (see [DECISIONS.md](DECISIONS.md#bcv--apicheck)). If you
  change a public API on purpose, write the baselines again
  (`./gradlew apiDump`), commit them, and explain the change in your PR.
- **`checkFacadeParity` must pass.** It catches an API that only one half of a
  pair has, such as a member of `Lustro`, `DebugConfig`, or `NetworkDebugTab`
  in `:lustro` that `:lustro-noop` does not have, for every pair in the
  `facadeParity { }` block of the root build. Code that uses such an API
  compiles in the debug build and fails in the release build. It compares the
  API dumps of the two halves: the types, their supertypes, and their members,
  constructors and protected members included. A public member that a class
  gets from an internal interface counts too: keep such an interface off the
  public class, as `NetworkDebugTab` does with an internal object that it holds.
  The cross-variant `:sample` compile (debug with `:lustro`, release with
  `:lustro-noop`) is a third check, for what the sample calls.
- Do not promote public functions to `inline` or rename public default-value
  parameters.
- Deprecate for a full minor cycle (with `ReplaceWith` where the migration is
  mechanical) before removing public API.
- The wire-protocol major version lives in the route path (`/api/v1`); an old
  major stays alive for at least one major release.

## Dependency and license audit

Lustro ships with a deliberately small dependency set. Before adding or bumping a
dependency, run the audit baseline and confirm the new dependency's license is
compatible with Apache-2.0:

```bash
# Resolved dependency tree for the published runtime artifact:
./gradlew :lustro:dependencies --configuration releaseRuntimeClasspath

# Public-facing (api) dependencies that leak into consumer POMs:
./gradlew :lustro:dependencies --configuration releaseApiElements
```

No automated license check runs in CI: the commands above and a manual review
of each new license are the check. New runtime dependencies must be justified in
the PR description.

Every runtime dependency of a published artifact is a floor: the oldest version
with the APIs Lustro uses, so that adding Lustro does not raise an app's own
versions (see [DECISIONS.md](DECISIONS.md#dependency-floors)). Dependabot leaves
them alone, and a bump is a change to the minimum versions that
[Install](docs/get-started/install.md#minimum-versions) documents.
`./gradlew checkKotlinFloor` fails when a dependency puts a kotlin-stdlib newer
than the floor on a published module's compile classpath. Before you change a
floor, or anything the published POMs carry, publish the checkout locally and
build the app in [`consumer-check/`](consumer-check/), which is what CI does:

```bash
./gradlew publishToMavenLocal
cd consumer-check
./gradlew :app:assembleWithLustroDebug :app:assembleWithLustroRelease :app:checkLustroKeepsAppVersions
```

### Pinned actions

A workflow or a composite action uses an action at the full commit SHA of a
release, with the version in a comment:

```yaml
- uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
```

Take the SHA from the action's own repository. `gh api
repos/<owner>/<repo>/git/ref/tags/<tag>` gives the object of the tag. When its
type is `tag`, the tag is annotated: `gh api
repos/<owner>/<repo>/git/tags/<sha>` gives the commit. Dependabot updates the
SHA and the comment. `python3 .github/scripts/check_action_pins.py` fails on a
`uses:` with a tag or a branch, and CI runs it.

### Python lock files

CI and the docs build install Python packages only from lock files with
hashes. Each lock is the output of `pip-compile`, from
[pip-tools](https://pip-tools.readthedocs.io), for the `.in` file next to it,
and Dependabot updates both:

| Lock | Its `.in` file has | Installed by |
|---|---|---|
| `lustro-cli/requirements-test.txt` | the test extras of `pyproject.toml`, and hatchling, the build backend | the CLI's job in `ci.yml`, and the CLI end to end |
| `docs/requirements.txt` | Zensical | `docs.yml` |

To change a version or add a package, edit the `.in` file, and compile the lock
again in its directory, with Python 3.15. That is the `python-version` of the
three jobs that install from a lock: the CLI's job and the instrumented tests in
`ci.yml`, and the build in `docs.yml`. pip-compile resolves for the Python that
runs it, and some locked packages, such as rpds-py, markupsafe, pyyaml, and
zensical, have compiled wheels for each Python version. So the Python of those
jobs and the locks move together: a pull request that changes that
`python-version` also compiles both locks again with the new version.

```bash
python -m pip install pip-tools   # in a virtual environment
cd lustro-cli
pip-compile --generate-hashes --strip-extras --output-file=requirements-test.txt requirements-test.in
cd ../docs
pip-compile --generate-hashes --strip-extras --output-file=requirements.txt requirements.in
```

pip-compile keeps each locked version that still fits, so the diff shows only
what changed. `--upgrade-package <name>` moves one package to its newest
version. A new test dependency goes into `pyproject.toml` and
`requirements-test.in`, because CI installs the CLI with `--no-deps`.

## Releasing

One tag publishes the library to Maven Central and the CLI to PyPI, with one
version. `main` takes changes only through pull requests.

1. In a pull request, set `VERSION_NAME` in `gradle.properties` to the release,
   such as `0.2.0`, and `__version__` in `lustro-cli/src/lustro_cli/__init__.py`
   to the same version. Update the coordinates in the docs (`checkDocsVersion`
   says where), and move the `[Unreleased]` entries of `CHANGELOG.md` under the
   version.
2. After the merge, wait until the CI run of the merge commit on `main` has
   passed, then push the tag `v<version>` on that commit. Before it publishes
   anything, `release.yml` stops when the tag's commit is not on `main`, when
   it has no passed CI run of a push to `main`
   (`.github/scripts/check_release_commit.py`), when the tag differs from
   `VERSION_NAME`, when the version ends with `-SNAPSHOT`, or when
   `checkCliVersion` fails. When the tag came before the CI run finished, run
   the release workflow again after the CI run has passed. The publish job runs
   in the `release` environment, so it waits for the protection rules of that
   environment, such as a required reviewer. Those rules, and a ruleset that
   limits who can push `v*` tags, are repository settings: the workflow can't
   set them.
3. In the next pull request, set `VERSION_NAME` to the next `-SNAPSHOT`, such as
   `0.2.1-SNAPSHOT`, and `__version__` to the same version without `-SNAPSHOT`.
   The docs can keep the release until the next release.

A pull request that changes `__version__` also increases `REVISION` in the same
file by one, because `check_cli_revision.py` fails each change under
`lustro-cli/src/` that does not.

`lustro-cli/README.md` is also the CLI's page on PyPI.

## Code of Conduct

By participating you agree to abide by our
[Code of Conduct](CODE_OF_CONDUCT.md).
