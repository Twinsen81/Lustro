import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Lint checks are plain JVM artifacts: NO Android plugin here. The compiled
    // jar is shipped to consumers by :lustro via `lintPublish(project(":lustro-lint"))`.
    alias(libs.plugins.kotlin.jvm)
}

// Match the rest of the project's toolchain: JVM toolchain 21, JVM 17
// bytecode. Lint 32.x runs on JDK 17 bytecode, so this is compatible.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // lint-api / lint-checks are provided by the lint runtime at analysis time, so
    // they are compileOnly (must NOT leak into the published lint.jar). Version is
    // pinned to 32.2.1 to match AGP 9.2.1 (CURRENT_API = 16).
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)


    // lint + lint-tests power the LintDetectorTest harness (lint().files(...).run()).
    testImplementation(libs.lint.api)
    testImplementation(libs.lint.checks)
    testImplementation(libs.lint)
    testImplementation(libs.lint.tests)
    testImplementation(libs.junit)
}

// AGP's `lintPublish` consumes EXACTLY ONE jar from this module's runtime
// classpath (it copies it in as the AAR's `lint.jar`). The Kotlin Gradle plugin
// adds `kotlin-stdlib` (which drags in `org.jetbrains:annotations`) to the
// `runtimeElements` we publish, which would make that "more than one jar". The
// lint runtime already provides the Kotlin stdlib at analysis time, so we exclude
// it (and its transitive annotations) from the consumable runtime variant.
configurations.named("runtimeElements") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
