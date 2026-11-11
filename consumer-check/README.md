# Consumer check

A small app that adds Lustro the way the [docs](../docs/get-started/install.md) say to, built on
the oldest toolchain and dependency versions that Lustro supports. CI builds it
on every pull request, and it fails when a published Lustro artifact would raise
the app's own versions: a debug tool must not change the build it inspects.

It is a separate Gradle build, with its own wrapper, because the repository root
uses the current toolchain and this one has to stay on the floors.
`gradle.properties` here holds them: the Android Gradle plugin, Kotlin,
`compileSdk`, and the app's own OkHttp, lifecycle, coroutines, and annotation
versions. They are deliberately not read from the root version catalog, so a
floor that moves up there makes this build fail until the floor is a decision.

## What it checks

- `assembleWithLustroDebug` builds the app with `lustro` and the Logs,
  Preferences, and SQLite plugins, `lustro-logs`, `lustro-prefs`, and
  `lustro-sqlite`, which runs AGP's AAR metadata check (`compileSdk`), and
  compiles the app's Kotlin against the artifacts' metadata with the floor's
  compiler. The app has no `androidx.sqlite`, which the SQLite plugin needs only
  for a `SupportSQLiteDatabase`. It also registers the WorkManager and Screen
  plugins; it has no Compose, which the Screen plugin needs only for the
  semantics tree, so the Screen plugin reads its Views.
- `assembleWithLustroRelease` builds it with `lustro-noop`, `lustro-logs-noop`,
  `lustro-prefs-noop`, `lustro-sqlite-noop`, `lustro-work-noop`,
  `lustro-screen-noop`, and R8.
- With `-PappMinSdk=23`, below the runtime's 24, the release build takes the no-op halves at that
  level, and the debug build raises its own to 24 with `androidComponents.beforeVariants`, as
  README.md tells such an app. WorkManager 2.12.0 needs 24, so CI runs it with
  `-PworkVersion=2.10.3`.
- `JavaSetup.java` registers every tab from Java, with the overloads that a Java app calls,
  against the runtime in debug and the no-op halves in release. The app has no work-analytics,
  so a factory overload whose parameter is a work-analytics type fails the Java build here.
- `checkLustroKeepsAppVersions` resolves the compile and runtime classpaths of
  each `withLustro` variant and of the `baseline` flavor, the same app without
  Lustro, and fails on any module that is in both at different versions. It
  also lists the modules that only Lustro brings.
- `check-release-lint.sh` puts a `DebugTab` subclass in `src/release`, then in
  `src/main`, and expects `lintVitalWithLustroRelease`, the lint that
  `assembleWithLustroRelease` runs, to fail with `LustroDebugUsageInRelease`,
  once. The app takes `lustro` in debug only, so this lint has the check only
  because `lustro-noop` ships it too. The script removes the class when it
  ends.

## Running it

Publish the current checkout to the local Maven repository, then build here:

```bash
./gradlew publishToMavenLocal
cd consumer-check
./gradlew :app:assembleWithLustroDebug :app:assembleWithLustroRelease :app:checkLustroKeepsAppVersions
./check-release-lint.sh
```

The `withLustro` debug APK also runs on a device, and it is the one build that
runs Lustro on the floor's kotlin-stdlib: install
`app/build/outputs/apk/withLustro/debug/app-withLustro-debug.apk`, open the app,
and `adb logcat -s LustroToken` shows the ready line while `lustro --json meta`
answers. Stop other Lustro apps first; they share port 8080.

Every value in `gradle.properties` can be overridden with `-P`. CI runs the
build once as is, and once on a current toolchain with OkHttp 5 and the
lifecycle Compose artifacts; see the `consumer` job in
[ci.yml](../.github/workflows/ci.yml) for those values.
