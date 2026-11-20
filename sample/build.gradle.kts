import com.android.build.api.artifact.SingleArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 provides built-in Kotlin; no org.jetbrains.kotlin.android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    // Room's code generation, for the sample's Room database.
    alias(libs.plugins.ksp)
}

// :sample is the public integration target, the cross-variant parity check, AND
// the idiomatic debug-only-usage demo:
//   - the debug variant consumes :lustro (real runtime), the release variant
//     consumes :lustro-noop; both must compile against the same public facades;
//   - ALL Lustro registration lives in the variant-split src/debug + src/release
//     LustroBootstrap, so the published LustroDebugUsageInRelease lint check is
//     clean WITHOUT being disabled (src/main mentions no Lustro type).

android {
    namespace = "io.github.twinsen81.lustro.sample"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.twinsen81.lustro.sample"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 processes the no-op halves and their consumer rules, as an app's release
            // build does. checkReleaseHasNoLustroClasses below reads its mapping file.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Note: there is NO `lint { disable += "LustroDebugUsageInRelease" }` here.
    // The sample now follows the recommended pattern — all Lustro registration
    // (Lustro.builder/.addTab and the SampleFlagsTab DebugTab subclass) lives in
    // the variant-split src/debug LustroBootstrap, so the published check finds
    // nothing to flag in src/main/src/release and :sample:lintDebug is clean.

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Detekt on the sample's Kotlin sources, using the shared library config. The
// UndocumentedPublic* rules are scoped in the config so they do not fire on this
// app's public surface (the sample is not a published library). detekt.yml here
// adds the overrides that the Compose UI needs.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"), file("detekt.yml"))
    source.setFrom("src/main/kotlin")
}

dependencies {
    // Debug builds get the real runtime; release builds get the no-op artifact.
    debugImplementation(project(":lustro"))
    releaseImplementation(project(":lustro-noop"))
    // The Logs plugin pair, split the same way.
    debugImplementation(project(":lustro-logs"))
    releaseImplementation(project(":lustro-logs-noop"))
    // The Preferences plugin pair, split the same way.
    debugImplementation(project(":lustro-prefs"))
    releaseImplementation(project(":lustro-prefs-noop"))
    // The SQLite plugin pair, split the same way.
    debugImplementation(project(":lustro-sqlite"))
    releaseImplementation(project(":lustro-sqlite-noop"))
    // The WorkManager plugin pair, split the same way.
    debugImplementation(project(":lustro-work"))
    releaseImplementation(project(":lustro-work-noop"))
    // The Screen plugin pair, split the same way.
    debugImplementation(project(":lustro-screen"))
    releaseImplementation(project(":lustro-screen-noop"))

    // A mainstream HTTP client whose default transport is java.net.HttpURLConnection
    // (not OkHttp). The demo fires a request through it to show that platform-HTTP
    // capture picks up traffic that never touches the app's OkHttpClient.
    implementation(libs.volley)
    implementation(libs.datastore.preferences)
    // The demo notes database is opened through the SupportSQLiteOpenHelper that Room
    // uses, so the SQLite tab reads it as it reads a Room database.
    implementation(libs.sqlite.framework)
    // A Room database whose Flow the SQLite screen shows: the SQLite tab's onWrite
    // hook refreshes Room's InvalidationTracker after each write of the tab.
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    // The demo work of the WorkManager tab, with the history that work-analytics keeps.
    implementation(libs.work.runtime)
    implementation(libs.work.analytics)

    // The sample's own UI. Lustro does not need Compose.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    // CI network test (testDebugUnitTest): exercises the real interceptor against
    // an offline MockWebServer. The real :lustro runtime is already on the debug
    // unit-test classpath via debugImplementation; the test-only libs are added
    // here. Robolectric provides the Android stubs the runtime touches (Base64,
    // SharedPreferences) without an emulator.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

// --- checkReleaseHasNoLustroClasses -------------------------------------------
// The release build takes the no-op halves, which ship no keep rule. R8 removes
// the no-op classes that LustroBootstrap does not use, and moves the few that it
// uses, such as the pass-through interceptor, out of the Lustro package with a
// short name. R8's mapping file has a line `<name> -> <name in the APK>:` for
// each class in the APK, so the task fails on a class whose name in the APK is
// in the Lustro package, as a -keep rule leaves it. The sample's own package is
// below the Lustro package, so the task skips it.
abstract class CheckNoLustroClasses : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    @get:Input
    abstract val appPackage: Property<String>

    @TaskAction
    fun check() {
        val lustroPrefix = "io.github.twinsen81.lustro."
        val appPrefix = "${appPackage.get()}."
        val mapping = mappingFile.get().asFile
        val allClasses =
            mapping.useLines { lines ->
                lines
                    .filter { it.isNotEmpty() && !it[0].isWhitespace() && !it.startsWith("#") }
                    .filter { " -> " in it && it.endsWith(":") }
                    .map { it.substringBefore(" -> ") to it.substringAfter(" -> ").removeSuffix(":") }
                    .toList()
            }
        // The app's Application and launcher Activity are always in the APK. When no class
        // line names them, the file is empty or in a format that this task does not read,
        // and the check below would pass on nothing.
        if (allClasses.none { (name, _) -> name.startsWith(appPrefix) }) {
            throw GradleException("$mapping has no class of the app ($appPrefix*): R8 wrote no mapping that this check can read.")
        }
        val classes = allClasses.filter { (name, _) -> name.startsWith(lustroPrefix) && !name.startsWith(appPrefix) }
        val named = classes.filter { (_, apkName) -> apkName.startsWith(lustroPrefix) }
        if (named.isNotEmpty()) {
            throw GradleException(
                "The minified release build has ${named.size} classes with a Lustro name. Look for " +
                    "a -keep rule that names them:\n" + named.joinToString("\n") { "  ${it.first}" },
            )
        }
        val renamed = classes.filterNot { (_, apkName) -> apkName.startsWith("R8\$\$REMOVED") }
        logger.lifecycle("No class with a Lustro name; ${renamed.size} renamed Lustro classes that the app uses.")
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        tasks.register<CheckNoLustroClasses>("checkReleaseHasNoLustroClasses") {
            group = "verification"
            description = "Fails when the minified release build has a class with a Lustro name."
            mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
            appPackage.set(variant.namespace)
        }
    }
}
