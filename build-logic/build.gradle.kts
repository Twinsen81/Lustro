plugins {
    `kotlin-dsl`
}

// The plugins that the convention plugins configure are compileOnly: the root
// build declares them with `apply false`, so at run time their classes come
// from the root project's classloader, which every module shares.
dependencies {
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.detekt.gradle.plugin)
    compileOnly(libs.vanniktech.gradle.plugin)
    compileOnly(libs.binary.compatibility.validator)

    testImplementation(libs.junit)
    // The tests make API dumps of fixture classes with BCV's library, as its worker does.
    testImplementation(libs.binary.compatibility.validator)
    testRuntimeOnly(libs.asm)
    testRuntimeOnly(libs.asm.tree)
    testRuntimeOnly(libs.kotlin.metadata.jvm)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "lustro.android-library"
            implementationClass = "io.github.twinsen81.lustro.gradle.AndroidLibraryPlugin"
        }
        register("kotlinLibrary") {
            id = "lustro.kotlin-library"
            implementationClass = "io.github.twinsen81.lustro.gradle.KotlinLibraryPlugin"
        }
        register("publishing") {
            id = "lustro.publishing"
            implementationClass = "io.github.twinsen81.lustro.gradle.PublishingPlugin"
        }
        register("facadeParity") {
            id = "lustro.facade-parity"
            implementationClass = "io.github.twinsen81.lustro.gradle.FacadeParityPlugin"
        }
    }
}
