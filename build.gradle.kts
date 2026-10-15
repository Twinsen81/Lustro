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

// Registers `checkFacadeParity`: BCV can't dump the AGP-built Kotlin Android
// facades, so this task diffs the compiled :lustro / :lustro-noop facades directly.
// See gradle/facade-parity.gradle.kts and DECISIONS.md ("BCV / apiCheck").
apply(from = "gradle/facade-parity.gradle.kts")

// Registers `checkDocsVersion`: the coordinates that README.md, llms.txt, and
// context7.json give to people and agents must match GROUP and VERSION_NAME.
// See gradle/docs-version.gradle.kts.
apply(from = "gradle/docs-version.gradle.kts")

// Dokka aggregation: the three published API modules (wire-schema has no Kotlin
// API). `./gradlew dokkaGenerate` renders the combined site into build/dokka/html,
// which release.yml deploys to gh-pages.
dependencies {
    dokka(project(":lustro-api"))
    dokka(project(":lustro"))
    dokka(project(":lustro-noop"))
}
