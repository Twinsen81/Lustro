import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    // Pure-JVM resource jar: no Kotlin, no Android. It only PACKAGES the wire
    // protocol artifacts (JSON Schemas + Network OpenAPI + golden fixtures) so
    // tools and contract tests can resolve them as a published Maven dependency.
    `java-library`
    alias(libs.plugins.vanniktech.publish)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// --- Sync the canonical wire artifacts into this module's resources ----------
// Source of truth lives elsewhere in the repo:
//   - JSON Schemas + golden fixtures: wire-protocol/v1/
//   - Network OpenAPI document:       lustro/src/main/assets/lustro/network.openapi.json
// We copy them into build/generated/wire-resources/lustro/wire/v1/ at build time
// (NOT into committed src/) so the jar always reflects the current source of
// truth and there is a single canonical copy in the repo. Packaged under the
// resource path `lustro/wire/v1/` to avoid collisions on a consumer classpath.
val wireProtocolDir = rootProject.layout.projectDirectory.dir("wire-protocol/v1")
val openApiFile =
    rootProject.layout.projectDirectory
        .file("lustro/src/main/assets/lustro/network.openapi.json")

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
        // Network OpenAPI document (lives under the runtime assets).
        from(openApiFile)
    }

sourceSets {
    named("main") {
        resources.srcDir(syncWireResources.map { wireResourcesRoot.get().asFile })
    }
}

// --- Publishing (Vanniktech → Sonatype Central Portal) -----------------------
// Coordinates: io.github.twinsen81:lustro-wire-schema (artifactId = project name).
// POM name/description come from this module's gradle.properties; the rest
// (license, SCM, developers, URL) inherit from the root POM_* properties.
// Signing is OPT-IN (CI only) so `publishToMavenLocal` works without GPG keys.
mavenPublishing {
    publishToMavenCentral()
    configure(
        JavaLibrary(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = true,
        ),
    )
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
    ) {
        signAllPublications()
    }
}
