# <Tab name>

<!--
The template for the page of a plugin tab. Copy it to docs/plugins/<name>.md,
where <name> is the module suffix and the tab id: the page of lustro-work is
docs/plugins/work.md. Then replace each placeholder and remove these comments.

Keep the sections below, with these headings, in this order:
`./gradlew checkPluginDocs` fails on a page whose level-2 headings differ. Put
more detail in level-3 headings inside a section. When a section does not apply
to the tab, say so in one sentence, such as "The Screen tab has no console view."

The site is not the only reader: llms.txt links the raw Markdown of the page,
and agents read it without the rest of the site. So say each fact in full, and
link the related pages.
-->

The <Tab name> plugin <one sentence: what the tab gives a developer, an agent, or a script, and
where: over the API, in the console, or both>.

## What the tab shows

<!--
What the tab reads or does in the app, for an app that registers it: the data, where it comes
from, when the tab reads it, and how fresh it is. Give the facts as bullets with a bold lead,
such as "- **Events live on the heap.** ...".
-->

## Install

<!--
The two artifacts and how an app adds them. Keep the catalog entries and the dependencies block
as they are below, and add what the tab needs: a minimum version of a library, a `compileOnly`
dependency that the app brings, a permission. End with what the no-op half does.
-->

The plugin is a pair of artifacts, split by variant as `lustro` is:

```toml
lustro-<name>      = { group = "io.github.twinsen81", name = "lustro-<name>",      version.ref = "lustro" }
lustro-<name>-noop = { group = "io.github.twinsen81", name = "lustro-<name>-noop", version.ref = "lustro" }
```

```kotlin
dependencies {
    debugImplementation(libs.lustro.<name>)
    releaseImplementation(libs.lustro.<name>.noop)
}
```

In `lustro-<name>-noop`, <what the tab does not do>.

## Register the tab

<!--
The builder call with the tab's factory, and each parameter of the factory: what it takes, its
default, and when to change it. Show the shortest call first, then the full one.
-->

```kotlin
val lustro = Lustro.builder(application)
    .addTab(NetworkDebugTab.create(senderClient = client))
    .addTab(<Name>DebugTab.create(<arguments>))
    .build()
```

## Safety options

<!--
What a client with the token can read and change through the tab, and the options that limit it:
value policies, read-only flags, the data that the app must not register. Link the tab's part of
SECURITY.md. When the tab has no such options, say so in one sentence and say what it exposes.
-->

## Console view

<!--
What the tab shows in the console and what its controls do, with a screenshot of the sample
app in docs/images/<name>-tab.png. When the tab has no console view, say so in one sentence.
-->

## HTTP routes

<!--
Each route under /api/v1/<name>/ in one or two sentences: its parameters, its envelope, and what
it changes. Link the tab's section of docs/AGENTS.md for the details, and the tab's OpenAPI
document, lustro-<name>/src/main/assets/lustro/<name>.openapi.json.
-->

## CLI examples

<!--
`lustro` commands that an agent or a script runs against the tab: its own commands when it has
them, and `lustro api` for the others. One command for each common task, with a sentence on what
it prints.
-->

```bash
lustro api GET <name>/<route> --param <key>=<value>
```

## Limits

<!--
What the tab does not show or do, and why: platform limits, data that ends with the process,
data that another process writes. Say what to use instead when there is something.
-->
