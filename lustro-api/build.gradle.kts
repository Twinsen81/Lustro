plugins {
    // Kotlin/JVM, toolchain 21 with JVM 17 bytecode, explicit API, the Kotlin
    // floor, and detekt: see build-logic/.../KotlinLibraryPlugin.kt.
    id("lustro.kotlin-library")
    // The jar, a sources jar, and a Dokka Javadoc jar; POM_NAME and
    // POM_DESCRIPTION come from gradle.properties here.
    id("lustro.publishing")
}

// :lustro-api is the pure-Kotlin public SPI. It carries NO Android, OkHttp, or
// server-engine dependencies — only consumer-facing contract types and the
// concrete types consumers construct themselves (responses, send results).
// Explicit-API strict mode (every public declaration needs explicit visibility
// and an explicit return type) and binary-compatibility validation are enforced
// for this module.

kotlin {
    compilerOptions {
        // kotlin.io.encoding.Base64 is stable since Kotlin 2.2, but experimental
        // in the stdlib 2.0 this module compiles against.
        optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
    }
}

dependencies {
    // @RestrictTo only — CLASS retention, so it stays off the runtime classpath
    // and :lustro-api remains a dependency-free pure-Kotlin SPI jar at runtime.
    // Its own kotlin-stdlib stays off the compile classpath too: it is newer
    // than the floor, and Gradle would keep the higher one (checkKotlinFloor).
    compileOnly(libs.androidx.annotation) {
        exclude(group = "org.jetbrains.kotlin")
    }
}
