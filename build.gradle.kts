plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    // Declared (unapplied) at the root so all publishing modules share ONE
    // classloader copy of the publish plugin. Its SonatypeRepositoryBuildService
    // is a cross-project shared build service; with per-module plugin
    // classloaders the service type mismatches when the publish task graph is
    // resolved ("Cannot set the value of task ... property 'buildService'").
    alias(libs.plugins.vanniktech.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
    // Dokka at the root aggregates the per-module HTML into a single site at
    // build/dokka/html, which release.yml deploys to gh-pages. The publishable
    // modules are registered as Dokka aggregation dependencies below.
    alias(libs.plugins.dokka)
}

// Binary Compatibility Validator. The `sample` app is not a published artifact,
// so it is excluded from API tracking. `lustro-lint` ships a lint-check jar (bundled
// into the :lustro AAR via lintPublish), not a consumable Kotlin/Java API, so it is
// excluded too. Whether BCV registers its dump/check tasks for the AGP-built-Kotlin
// Android modules (:lustro / :lustro-noop) is recorded in DECISIONS.md
// ("BCV / apiCheck").
apiValidation {
    ignoredProjects.add("sample")
    ignoredProjects.add("lustro-lint")
    // :lustro-wire-schema ships only packaged JSON resources (schemas + OpenAPI +
    // golden fixtures), not a consumable Kotlin/Java API, so it is excluded too.
    ignoredProjects.add("lustro-wire-schema")
}

// Maven coordinates are defined once in gradle.properties and propagated to
// every project so `project.version` is available before the publish plugin loads.
allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}

// The JVM toolchain (21) / JVM 17 bytecode target is applied per-module
// via a top-level `kotlin { }` block. AGP 9 ships built-in Kotlin, so Android
// modules apply only the Android plugin (no org.jetbrains.kotlin.android).

// Facade-parity gate (registers the `checkFacadeParity` task). This is the
// BCV-Android follow-up from DECISIONS.md: since BCV can't dump the AGP-built-in
// Kotlin Android facades, this task diffs the compiled public facades of :lustro
// and :lustro-noop directly. See gradle/facade-parity.gradle.kts.
apply(from = "gradle/facade-parity.gradle.kts")

// Dokka multi-module aggregation: include the three published API modules (the
// wire-schema module has no Kotlin API to document). `./gradlew dokkaGenerate`
// then renders the combined site into build/dokka/html for the gh-pages deploy.
dependencies {
    dokka(project(":lustro-api"))
    dokka(project(":lustro"))
    dokka(project(":lustro-noop"))
}
