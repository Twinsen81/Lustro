plugins {
    // The convention plugins in build-logic/ apply AGP, Kotlin, detekt, and the
    // vanniktech plugin, and compile against them. They are declared here so that
    // their classes load once, in the root classloader, which every module shares.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
    // Unapplied at the root so all publishing modules share one plugin classloader:
    // its SonatypeRepositoryBuildService is a shared build service, and per-module
    // classloaders make it mismatch ("Cannot set ... property 'buildService'").
    alias(libs.plugins.vanniktech.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
    // Applied at the root to aggregate per-module API docs (see the published
    // modules below).
    alias(libs.plugins.dokka)
    id("lustro.facade-parity")
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

// `-PlustroTestOkHttpVersion=<version>` runs every module's unit tests with that
// OkHttp on the test runtime classpath, MockWebServer included. The modules
// still compile against the published floor (4.12.0), which is what an app on
// OkHttp 5 runs. CI uses it to run the suite against OkHttp 5. OkHttp 5 is
// multiplatform, and the Android variant of `okhttp` is the `okhttp-android`
// AAR, whose metadata asks for a compileSdk above this repository's. The unit
// tests run on the JVM, so they take the JVM variant, `okhttp-jvm`, as they
// took the 4.x jar; the consumer check builds a real app with the Android one.
val testOkHttpVersion = providers.gradleProperty("lustroTestOkHttpVersion")
if (testOkHttpVersion.isPresent) {
    val version = testOkHttpVersion.get()
    val multiplatform = version.substringBefore('.').toInt() >= 5
    subprojects {
        configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
            resolutionStrategy {
                if (multiplatform) {
                    dependencySubstitution {
                        substitute(module("com.squareup.okhttp3:okhttp"))
                            .using(module("com.squareup.okhttp3:okhttp-jvm:$version"))
                    }
                }
                eachDependency {
                    if (requested.group == "com.squareup.okhttp3") useVersion(version)
                }
            }
        }
    }
}

// checkFacadeParity: the runtime and no-op halves of each pair must expose the
// same public facades. BCV can't dump the AGP-built Kotlin Android modules, so
// the task compares their compiled release classes directly. A new pair adds an
// entry. See build-logic/.../FacadeParityTask.kt and DECISIONS.md ("BCV / apiCheck").
facadeParity {
    pair(
        runtime = ":lustro",
        noop = ":lustro-noop",
        packages = listOf("io.github.twinsen81.lustro", "io.github.twinsen81.lustro.network"),
    )
    pair(
        runtime = ":lustro-logs",
        noop = ":lustro-logs-noop",
        packages = listOf("io.github.twinsen81.lustro.logs"),
    )
    pair(
        runtime = ":lustro-prefs",
        noop = ":lustro-prefs-noop",
        packages = listOf("io.github.twinsen81.lustro.prefs"),
    )
}

// Registers `checkDocsVersion`: the coordinates that README.md, llms.txt, and
// context7.json give to people and agents must match GROUP and VERSION_NAME.
// See gradle/docs-version.gradle.kts.
apply(from = "gradle/docs-version.gradle.kts")

// The published modules are the ones that apply lustro.publishing, so a new
// module needs no entry here or in release.yml:
//   - `./gradlew dokkaGenerate` renders the API docs of those with Dokka (all but
//     lustro-wire-schema, which has no Kotlin API) into one site in
//     build/dokka/html, which release.yml deploys to gh-pages;
//   - `./gradlew collectReleaseArtifacts` copies the AAR of each Android module and
//     the jar of each JVM module into build/release-artifacts, which release.yml
//     attaches to the GitHub Release.
val collectReleaseArtifacts = tasks.register<Sync>("collectReleaseArtifacts") {
    group = "distribution"
    description = "Copies the AAR or jar of every published module into build/release-artifacts."
    into(layout.buildDirectory.dir("release-artifacts"))
}

subprojects {
    val module = this
    pluginManager.withPlugin("lustro.publishing") {
        pluginManager.withPlugin("org.jetbrains.dokka") {
            rootProject.dependencies { dokka(project(module.path)) }
        }
        collectReleaseArtifacts.configure {
            val android = module.pluginManager.hasPlugin("com.android.library")
            from(module.tasks.named(if (android) "bundleReleaseAar" else "jar"))
        }
    }
}
