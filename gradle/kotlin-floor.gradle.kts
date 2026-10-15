// Registers `checkKotlinFloor` in a published module and wires it into `check`.
//
// The module declares the floor's kotlin-stdlib (coreLibrariesVersion) and
// compiles as that Kotlin version, but a dependency can still put a newer
// stdlib on the compile classpath, and Gradle keeps the higher one. The
// compiler would then inline that stdlib's function bodies and see its
// declarations while the POM still names the floor, so the artifact could call
// what an app on the floor does not have. The consumer check cannot see that:
// the POM is right. This task fails when the compile classpath resolves
// kotlin-stdlib to anything but the floor. See DECISIONS.md ("Dependency floors").
//
// The module sets two extra properties before it applies this script:
//   kotlinFloorVersion    the floor, from gradle/libs.versions.toml
//   kotlinFloorClasspath  the compile classpath of what it publishes:
//                         `compileClasspath` for a JVM module,
//                         `releaseCompileClasspath` for an Android library

val kotlinFloorVersion: String by extra
val kotlinFloorClasspath: String by extra

val checkKotlinFloor =
    tasks.register("checkKotlinFloor") {
        group = "verification"
        description =
            "Fails when $kotlinFloorClasspath resolves kotlin-stdlib to a version other than " +
                "the floor ($kotlinFloorVersion)."
        doLast {
            val resolved =
                configurations.getByName(kotlinFloorClasspath).incoming.resolutionResult.allComponents
                    .mapNotNull { it.moduleVersion }
                    .filter { it.group == "org.jetbrains.kotlin" && it.name == "kotlin-stdlib" }
                    .map { it.version }
            if (resolved.isEmpty()) {
                throw GradleException("checkKotlinFloor: $kotlinFloorClasspath has no kotlin-stdlib.")
            }
            if (resolved.any { it != kotlinFloorVersion }) {
                throw GradleException(
                    "checkKotlinFloor: $kotlinFloorClasspath resolves kotlin-stdlib to " +
                        "${resolved.joinToString()}, not the floor $kotlinFloorVersion. A dependency " +
                        "requests the newer one: exclude org.jetbrains.kotlin from it, or move the " +
                        "floor (DECISIONS.md, \"Dependency floors\").",
                )
            }
            logger.lifecycle("checkKotlinFloor: $kotlinFloorClasspath has kotlin-stdlib $kotlinFloorVersion")
        }
    }

tasks.named("check") {
    dependsOn(checkKotlinFloor)
}
