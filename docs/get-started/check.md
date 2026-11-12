# Check the integration

A build that compiles does not show that Lustro captures anything, or that the release build is
free of it. After you add Lustro, or after a coding agent does, check these. The commands use the
[`lustro` CLI](install.md#the-lustro-cli) and an app module named `app`. With product flavors, use the
names of one variant, such as `assembleFreeDebug` and `freeReleaseRuntimeClasspath`.

1. **Both build types build:** `./gradlew :app:assembleDebug :app:assembleRelease`.
2. **The release build has no runtime:**
    `./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath | grep -E 'twinsen81|nanohttpd'`
    lists only `lustro-api` and the `-noop` artifacts. Each other build type also needs one of the
    two, such as `stagingImplementation(libs.lustro.noop)`.
3. **The server starts.** Install the debug build, start the app, and keep it in the foreground.
    `adb logcat -d -s LustroToken` has the line `Lustro ready endpoint=... token=...`, and
    `lustro meta` lists the tabs that the app registers.
4. **Capture works.** Use the app so that it sends a request: `lustro net list --last 5` shows it.
    When it does not, the interceptor is on another `OkHttpClient` than the one that sends: see
    [Troubleshooting](../reference/troubleshooting.md).
5. **Each plugin tab has data.** For the tabs that the app registers:
    - `lustro api GET logs/text --param limit=20` prints the app's last log lines.
    - `lustro api GET prefs/files` lists the app's SharedPreferences files and DataStores.
    - `lustro api GET sqlite/databases` lists the databases, and none has an `error`.
    - `lustro api GET work/items | jq .state` shows `"listeners": "installed"`.
6. **Custom tabs are in `src/debug`.** When you wrote a `DebugTab`, it is in `src/debug`, and
    `./gradlew :app:lintRelease` reports no `LustroDebugUsageInRelease`. See
    [Release safety](../concepts/release-safety.md#the-lint-check) for what the check reports.
