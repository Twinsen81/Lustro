# Screen

The Screen plugin gives an agent or a script your app's UI as a tree, over the API: each window
of your app's process, and in each, the classic Views and the Compose semantics nodes, with their
test tags, text, bounds, states, and actions. The tab reads them in your app's process, in
milliseconds, where `uiautomator dump` takes a second or more for each read.

## What the plugin does

- **Every window of the app.** `GET /api/v1/screen/tree` returns the activity's window and each
    dialog, popup, and bottom sheet over it, in the order your app added them. From API 29 the tab
    lists them with `WindowInspector`; before that it reads `WindowManagerGlobal` by reflection, as
    Espresso does, and when that fails, the windows of the started activities, without their
    dialogs and popups. Each window says whether it has the window focus. When none has it, a
    window of another app is on top, such as a permission dialog or the chooser, and the tree does
    not have that window.
- **Compose and Views.** The tab reads the View tree of every window, and the semantics tree of
    each Compose host in it: the merged tree by default, as accessibility services see it, and the
    unmerged one with `merged=false`. Every test tag is in it, whether your app sets
    `testTagsAsResourceId` or not.
- **What a node has.** Its id, which stays the same from read to read; its test tag, text,
    content description, editable text, and role; its bounds in the window and on the screen; the
    part of it that is visible on the screen, or `null` when no part of it is, as for a row
    scrolled out of view; whether it is enabled, focused, selected, and on or off; the actions that
    drive it, such as `OnClick`, `SetText`, and `ScrollBy`; and its scroll position and range. A
    password field has one dot for each character, as the screen shows it.
- **On the main thread.** `settle=500` reads the tree on each frame until two reads in a row are
    the same and the window focus is at rest, for up to 500 ms, for a screen that still changes:
    the window manager moves the focus some frames after a dialog opens or closes, 200 ms and more
    on a physical device. A main thread that does not run the read within five seconds gets the
    client a `504` with the code `main_thread_blocked`, so a script does not wait for an app that
    does not respond.

## Install

The plugin is a pair of artifacts, split by variant as `lustro` is:

```toml
lustro-screen      = { group = "io.github.twinsen81", name = "lustro-screen",      version.ref = "lustro" }
lustro-screen-noop = { group = "io.github.twinsen81", name = "lustro-screen-noop", version.ref = "lustro" }
```

```kotlin
dependencies {
    debugImplementation(libs.lustro.screen)
    releaseImplementation(libs.lustro.screen.noop)
}
```

Compose is optional: the tab compiles against Compose UI 1.6.0 as `compileOnly`, checks at
runtime that your app has Compose, and in an app without it reads the Views only.

In `lustro-screen-noop`, the tab reads no window and never calls the provider.

## Register the tab

```kotlin
val lustro = Lustro.builder(application)
    .addTab(NetworkDebugTab.create(senderClient = client))
    // The lambda is optional: what only your app knows about the screen, as JSON.
    .addTab(ScreenDebugTab.create(application) { """{"route":"${navigator.currentRoute}"}""" })
    .build()
```

- **Your app's context.** A `ScreenContextProvider` returns what only your app knows, such as
    its navigation back stack, as JSON. The tab calls it on the main thread after each read, and
    the tree carries it as `context`.

## Safety options

The Screen tab has no safety options: it only reads, and it has no route that changes the app.
Every client with the token reads the screen, with what it shows, but the text of password
fields. See [SECURITY.md](https://github.com/Twinsen81/Lustro/blob/main/SECURITY.md#the-screen).

## Console view

The Screen tab has no console view yet: it is not in the tab bar, and `_meta` lists it.

## HTTP routes

`GET /api/v1/screen/tree` returns the windows and their nodes. `merged=false` gives the unmerged
semantics tree. `settle=MS` goes from 0 to 10000: `0`, the default, reads once, and a larger
value reads until the screen is at rest, for up to MS. See [Agents and automation](../AGENTS.md#screen-tab-operations) and
[`screen.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro-screen/src/main/assets/lustro/screen.openapi.json).

## CLI examples

`lustro screen tree` prints each window and the nodes on the screen that have a text, a tag, a
role, an action, or a state, one line each, with their bounds in uiautomator's format.

```bash
# The windows, and the nodes on the screen.
lustro screen tree

# Read after a tap, when the screen is at rest.
lustro screen tree --settle 500

# Every node of the unmerged tree.
lustro screen tree --unmerged --all

# The whole response, as JSON.
lustro screen tree --json
```

A script that reads the tree in a loop sends the GET itself, with a token and a forward that it
keeps: see [Agents and automation](../AGENTS.md#screen-tab-operations).

## Limits

- **Only the app's windows.** Permission dialogs, the chooser and the share sheet, the keyboard,
    notifications, the text toasts that the system shows from API 30, and the page in a WebView
    are not in the tree: read them with `uiautomator`.
