import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun property(name: String): String = providers.gradleProperty(name).get()

val compileSdkLevel = property("compileSdk").toInt()

// The Lustro version to build against: -PlustroVersion, or VERSION_NAME from the
// root gradle.properties, which is what `./gradlew publishToMavenLocal` publishes.
val lustroVersion: String =
    providers.gradleProperty("lustroVersion")
        .filter { it.isNotBlank() }
        .orElse(
            providers.fileContents(rootProject.layout.projectDirectory.file("../gradle.properties")).asText
                .map { text ->
                    Regex("""^VERSION_NAME=(.+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
                        ?: error("VERSION_NAME is not in the root gradle.properties")
                },
        )
        .get()

android {
    namespace = "io.github.twinsen81.lustro.consumercheck"
    compileSdk = compileSdkLevel

    defaultConfig {
        applicationId = "io.github.twinsen81.lustro.consumercheck"
        minSdk = 24
        targetSdk = compileSdkLevel
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // R8 processes lustro-noop and its consumer rules, as an app's release build does.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    // `baseline` is the app without Lustro, the control that
    // checkLustroKeepsAppVersions compares against. `withLustro` adds `lustro`
    // to debug and `lustro-noop` to release, as README.md tells an app to.
    flavorDimensions += "lustro"
    productFlavors {
        create("baseline") { dimension = "lustro" }
        create("withLustro") { dimension = "lustro" }
    }

    // Java 11 bytecode, the Android Studio template's default. Lustro's own
    // classes are Java 17 bytecode, which D8 accepts whatever the app targets.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// AGP creates the flavor-and-build-type configurations only once the variants
// exist, so the two Lustro artifacts are added when theirs appear.
configurations.configureEach {
    when (name) {
        "withLustroDebugImplementation" ->
            dependencies.add(project.dependencies.create("io.github.twinsen81:lustro:$lustroVersion"))
        "withLustroReleaseImplementation" ->
            dependencies.add(project.dependencies.create("io.github.twinsen81:lustro-noop:$lustroVersion"))
    }
}

dependencies {
    // The app's own dependencies. The check below fails when Lustro moves any of
    // them, or anything they bring in. lifecycle-process is deliberately absent:
    // the runtime adds it, and the app's lifecycle version has to win.
    implementation("com.squareup.okhttp3:okhttp:${property("okhttpVersion")}")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:${property("lifecycleVersion")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${property("coroutinesVersion")}")
    implementation("androidx.annotation:annotation:${property("annotationVersion")}")
    property("extraDependencies").split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
        implementation(it)
    }
}

// --- checkLustroKeepsAppVersions ---------------------------------------------
// Adding Lustro must not change the version of any module the app resolves
// without it. The task compares the compile and runtime classpaths of each
// withLustro variant with those of the baseline variant, and fails on every
// module that is in both at different versions. It also lists what Lustro alone
// brings, so a new transitive dependency is visible in the build log.
abstract class CheckLustroKeepsAppVersions : DefaultTask() {
    /** Keyed by `<buildType>/<compile|runtime>`. */
    @get:Internal
    abstract val baseline: MapProperty<String, ResolvedComponentResult>

    @get:Internal
    abstract val withLustro: MapProperty<String, ResolvedComponentResult>

    @TaskAction
    fun check() {
        val raised = mutableListOf<String>()
        for ((key, withLustroRoot) in withLustro.get().toSortedMap()) {
            val before = versions(baseline.get().getValue(key))
            val after = versions(withLustroRoot)
            val added = after.filterKeys { it !in before }
            logger.lifecycle("$key: ${after.size} modules, ${added.size} from Lustro only")
            added.forEach { (module, version) -> logger.lifecycle("  + $module:$version") }
            before.forEach { (module, version) ->
                val now = after[module]
                if (now != null && now != version) raised += "$key: $module $version -> $now"
            }
        }
        if (raised.isNotEmpty()) {
            throw GradleException(
                "Lustro changed the version of a module the app had without it:\n" +
                    raised.joinToString("\n") { "  $it" },
            )
        }
    }

    private fun versions(root: ResolvedComponentResult): Map<String, String> {
        val result = sortedMapOf<String, String>()
        val seen = HashSet<ResolvedComponentResult>()
        fun walk(component: ResolvedComponentResult) {
            if (!seen.add(component)) return
            val id = component.moduleVersion
            if (id != null && component.id !is ProjectComponentIdentifier) {
                result["${id.group}:${id.name}"] = id.version
            }
            for (dependency in component.dependencies) {
                when (dependency) {
                    is ResolvedDependencyResult -> walk(dependency.selected)
                    is UnresolvedDependencyResult -> throw GradleException(
                        "Unresolved dependency ${dependency.attempted}: ${dependency.failure.message}",
                        dependency.failure,
                    )
                }
            }
        }
        walk(root)
        return result
    }
}

val checkLustroKeepsAppVersions =
    tasks.register<CheckLustroKeepsAppVersions>("checkLustroKeepsAppVersions") {
        group = "verification"
        description = "Fails when a Lustro artifact changes the version of a module the app resolves without it."
    }

androidComponents {
    onVariants { variant ->
        val roots =
            mapOf(
                "${variant.buildType}/compile" to variant.compileConfiguration.incoming.resolutionResult.rootComponent,
                "${variant.buildType}/runtime" to variant.runtimeConfiguration.incoming.resolutionResult.rootComponent,
            )
        checkLustroKeepsAppVersions.configure {
            val side = if (variant.flavorName == "withLustro") withLustro else baseline
            roots.forEach { (key, root) -> side.put(key, root) }
        }
    }
}
