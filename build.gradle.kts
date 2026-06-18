plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    // Unapplied at the root so all publishing modules share one plugin classloader:
    // its SonatypeRepositoryBuildService is a shared build service, and per-module
    // classloaders make it mismatch ("Cannot set ... property 'buildService'").
    alias(libs.plugins.vanniktech.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
    // Applied at the root to aggregate per-module API docs (see dokka(...) deps below).
    alias(libs.plugins.dokka)
}

// Binary Compatibility Validator tracks the public API. Excluded projects have no
// consumable Kotlin/Java API: `sample` is an app, `lustro-lint` ships a lint jar
// (lintPublish'd into the :lustro AAR), `lustro-wire-schema` ships only JSON
// resources. BCV's handling of the AGP-built Android modules is in DECISIONS.md
// ("BCV / apiCheck").
apiValidation {
    ignoredProjects.add("sample")
    ignoredProjects.add("lustro-lint")
    ignoredProjects.add("lustro-wire-schema")
}

// Propagate the coordinates from gradle.properties to every project so
// `project.version` is set before the publish plugin loads.
allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}

// Registers `checkFacadeParity`: BCV can't dump the AGP-built Kotlin Android
// facades, so this task diffs the compiled :lustro / :lustro-noop facades directly.
// See gradle/facade-parity.gradle.kts and DECISIONS.md ("BCV / apiCheck").
apply(from = "gradle/facade-parity.gradle.kts")

// Dokka aggregation: the three published API modules (wire-schema has no Kotlin
// API). `./gradlew dokkaGenerate` renders the combined site into build/dokka/html,
// which release.yml deploys to gh-pages.
dependencies {
    dokka(project(":lustro-api"))
    dokka(project(":lustro"))
    dokka(project(":lustro-noop"))
}
