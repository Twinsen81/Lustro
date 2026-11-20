plugins {
    // Pure-JVM resource jar: no Kotlin, no Android. It only PACKAGES the wire
    // protocol artifacts (JSON Schemas + the Network, Logs, Preferences, SQLite,
    // WorkManager, and Screen OpenAPI documents + golden fixtures) so tools and contract tests can resolve
    // them as a published Maven dependency.
    `java-library`
    // The jar, a sources jar, and an empty Javadoc jar (there is no API to
    // document). Coordinates: io.github.twinsen81:lustro-wire-schema; POM_NAME and
    // POM_DESCRIPTION come from gradle.properties here.
    id("lustro.publishing")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// --- Sync the canonical wire artifacts into this module's resources ----------
// Source of truth lives elsewhere in the repo:
//   - JSON Schemas + golden fixtures: wire-protocol/v1/
//   - Network OpenAPI document:       lustro/openapi/network.openapi.json (generated from the
//                                     tab's route declarations; a test keeps it current)
//   - Logs OpenAPI document:          lustro-logs/openapi/logs.openapi.json (generated from the
//                                     tab's route declarations; a test keeps it current)
//   - Preferences OpenAPI document:   lustro-prefs/openapi/prefs.openapi.json (generated from
//                                     the tab's route declarations; a test keeps it current)
//   - SQLite OpenAPI document:        lustro-sqlite/openapi/sqlite.openapi.json (generated from
//                                     the tab's route declarations; a test keeps it current)
//   - WorkManager OpenAPI document:   lustro-work/openapi/work.openapi.json (generated from the
//                                     tab's route declarations; a test keeps it current)
//   - Screen OpenAPI document:        lustro-screen/openapi/screen.openapi.json (generated from
//                                     the tab's route declarations; a test keeps it current)
// We copy them into build/generated/wire-resources/lustro/wire/v1/ at build time
// (NOT into committed src/) so the jar always reflects the current source of
// truth and there is a single canonical copy in the repo. Packaged under the
// resource path `lustro/wire/v1/` to avoid collisions on a consumer classpath.
val wireProtocolDir = rootProject.layout.projectDirectory.dir("wire-protocol/v1")
val openApiFile =
    rootProject.layout.projectDirectory
        .file("lustro/openapi/network.openapi.json")
val logsOpenApiFile =
    rootProject.layout.projectDirectory
        .file("lustro-logs/openapi/logs.openapi.json")
val prefsOpenApiFile =
    rootProject.layout.projectDirectory
        .file("lustro-prefs/openapi/prefs.openapi.json")
val sqliteOpenApiFile =
    rootProject.layout.projectDirectory
        .file("lustro-sqlite/openapi/sqlite.openapi.json")
val workOpenApiFile =
    rootProject.layout.projectDirectory
        .file("lustro-work/openapi/work.openapi.json")
val screenOpenApiFile =
    rootProject.layout.projectDirectory
        .file("lustro-screen/openapi/screen.openapi.json")

// Root of the generated resource tree; the jar entries live under
// <root>/lustro/wire/v1/... so they don't collide on a consumer classpath.
val wireResourcesRoot = layout.buildDirectory.dir("generated/wire-resources")

val syncWireResources =
    tasks.register<Copy>("syncWireResources") {
        into(wireResourcesRoot.map { it.dir("lustro/wire/v1") })
        // Schemas + golden fixtures (preserves the golden/ subdirectory).
        from(wireProtocolDir) {
            include("*.schema.json")
            include("golden/*.json")
        }
        // The tabs' OpenAPI documents, which each module generates and commits under openapi/.
        from(openApiFile)
        from(logsOpenApiFile)
        from(prefsOpenApiFile)
        from(sqliteOpenApiFile)
        from(workOpenApiFile)
        from(screenOpenApiFile)
    }

sourceSets {
    named("main") {
        resources.srcDir(syncWireResources.map { wireResourcesRoot.get().asFile })
    }
}
