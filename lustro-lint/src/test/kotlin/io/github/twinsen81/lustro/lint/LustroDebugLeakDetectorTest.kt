package io.github.twinsen81.lustro.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Severity
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [LustroDebugLeakDetector] using the lint-tests harness. Each test
 * compiles a tiny stub of the Lustro debug API plus a consumer file placed in a
 * specific source set, then asserts the leak check fires only for `DebugTab`
 * subclasses outside `src/debug`.
 */
@Suppress("UnstableApiUsage")
class LustroDebugLeakDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = LustroDebugLeakDetector()

    override fun getIssues(): List<Issue> = listOf(LustroDebugLeakDetector.ISSUE)

    fun testDebugTabSubclassInMainIsFlagged() {
        lint()
            .allowMissingSdk()
            .files(
                debugTabStub,
                kotlin(
                    "src/main/kotlin/com/example/MyTab.kt",
                    """
                    package com.example
                    import io.github.twinsen81.lustro.DebugTab
                    class MyTab : DebugTab() {
                        override val id = "my"
                        override val title = "My"
                        override val icon = "x"
                    }
                    """,
                ).indented(),
            )
            .run()
            .expectErrorCount(1)
    }

    fun testDebugTabSubclassInReleaseIsFlagged() {
        lint()
            .allowMissingSdk()
            .files(
                debugTabStub,
                kotlin(
                    "src/release/kotlin/com/example/MyTab.kt",
                    """
                    package com.example
                    import io.github.twinsen81.lustro.DebugTab
                    class MyTab : DebugTab() {
                        override val id = "my"
                        override val title = "My"
                        override val icon = "x"
                    }
                    """,
                ).indented(),
            )
            .run()
            .expectErrorCount(1)
    }

    // assembleRelease runs lintVital, which runs only the FATAL issues. The lint
    // test harness does not filter by severity in vital mode, so this is checked
    // here, and end to end by the consumer check.
    fun testIssueIsFatalSoTheReleaseBuildFails() {
        assertEquals(Severity.FATAL, LustroDebugLeakDetector.ISSUE.defaultSeverity)
    }

    fun testDebugTabSubclassInDebugIsClean() {
        lint()
            .allowMissingSdk()
            .files(
                debugTabStub,
                kotlin(
                    "src/debug/kotlin/com/example/MyTab.kt",
                    """
                    package com.example
                    import io.github.twinsen81.lustro.DebugTab
                    class MyTab : DebugTab() {
                        override val id = "my"
                        override val title = "My"
                        override val icon = "x"
                    }
                    """,
                ).indented(),
            )
            .run()
            .expectClean()
    }

    /**
     * The documented quick start: registration lives in a shared `Application`
     * (`src/main`) while the custom tab stays in `src/debug`. `:lustro-noop` makes
     * the builder/`addTab` calls inert in release, so only the tab's source set
     * matters and nothing is reported.
     */
    fun testBuilderAndAddTabInMainAreNotFlagged() {
        lint()
            .allowMissingSdk()
            .files(
                debugTabStub,
                lustroStub,
                kotlin(
                    "src/debug/kotlin/com/example/MyTab.kt",
                    """
                    package com.example
                    import io.github.twinsen81.lustro.DebugTab
                    object MyTab : DebugTab() {
                        override val id = "my"
                        override val title = "My"
                        override val icon = "x"
                    }
                    """,
                ).indented(),
                kotlin(
                    "src/main/kotlin/com/example/Setup.kt",
                    """
                    package com.example
                    import android.app.Application
                    import io.github.twinsen81.lustro.Lustro
                    fun init(app: Application) {
                        val b = Lustro.builder(app)
                        b.addTab(MyTab)
                        b.build()
                    }
                    """,
                ).indented(),
            )
            .run()
            .expectClean()
    }

    /**
     * Registration in `src/main` does not excuse a `DebugTab` subclass that also
     * sits in `src/main`: only the subclass is reported.
     */
    fun testOnlyTheSubclassIsReportedWhenBothAreInMain() {
        lint()
            .allowMissingSdk()
            .files(
                debugTabStub,
                lustroStub,
                kotlin(
                    "src/main/kotlin/com/example/MyTab.kt",
                    """
                    package com.example
                    import io.github.twinsen81.lustro.DebugTab
                    object MyTab : DebugTab() {
                        override val id = "my"
                        override val title = "My"
                        override val icon = "x"
                    }
                    """,
                ).indented(),
                kotlin(
                    "src/main/kotlin/com/example/Setup.kt",
                    """
                    package com.example
                    import android.app.Application
                    import io.github.twinsen81.lustro.Lustro
                    fun init(app: Application) {
                        val b = Lustro.builder(app)
                        b.addTab(MyTab)
                        b.build()
                    }
                    """,
                ).indented(),
            )
            .run()
            .expectErrorCount(1)
    }

    /**
     * An internal build type that is not debuggable and opts into the runtime
     * keeps its tabs in its own source set, and suppresses the check on them.
     */
    fun testSuppressedSubclassIsClean() {
        lint()
            .allowMissingSdk()
            .files(
                debugTabStub,
                kotlin(
                    "src/internal/kotlin/com/example/MyTab.kt",
                    """
                    package com.example
                    import io.github.twinsen81.lustro.DebugTab
                    @Suppress("LustroDebugUsageInRelease")
                    class MyTab : DebugTab() {
                        override val id = "my"
                        override val title = "My"
                        override val icon = "x"
                    }
                    """,
                ).indented(),
            )
            .run()
            .expectClean()
    }

    // Test code never goes into an APK, so a fake tab that a unit test or an
    // instrumented test uses is not a leak. These tests have a Gradle build file:
    // without one, the lint harness puts every file in the main source set.
    fun testDebugTabSubclassInUnitTestSourcesIsClean() {
        lint()
            .allowMissingSdk()
            .files(
                gradleBuild(checkTestSources = false),
                debugTabStubInMain,
                fakeTab("src/test/kotlin/com/example/FakeTab.kt"),
            )
            .run()
            .expectClean()
    }

    fun testDebugTabSubclassInAndroidTestSourcesIsClean() {
        lint()
            .allowMissingSdk()
            .files(
                gradleBuild(checkTestSources = false),
                debugTabStubInMain,
                fakeTab("src/androidTest/kotlin/com/example/FakeTab.kt"),
            )
            .run()
            .expectClean()
    }

    // With `checkTestSources`, lint runs every issue on test sources, whatever the
    // scope of the issue.
    fun testDebugTabSubclassInTestSourcesIsCleanWithCheckTestSources() {
        lint()
            .allowMissingSdk()
            .files(
                gradleBuild(checkTestSources = true),
                debugTabStubInMain,
                fakeTab("src/test/kotlin/com/example/FakeTab.kt"),
                fakeTab("src/androidTest/kotlin/com/example/FakeAndroidTab.kt"),
            )
            .run()
            .expectClean()
    }

    fun testDebugTabSubclassInMainIsFlaggedWithCheckTestSources() {
        lint()
            .allowMissingSdk()
            .files(
                gradleBuild(checkTestSources = true),
                debugTabStubInMain,
                fakeTab("src/main/kotlin/com/example/MyTab.kt"),
                fakeTab("src/test/kotlin/com/example/FakeTab.kt"),
            )
            .run()
            .expectErrorCount(1)
            .expectContains("src/main/kotlin/com/example/MyTab.kt:3: Error")
    }

    // The source set comes from the path below the module directory. A folder
    // named `src` above the project, such as `~/src/MyApp/`, must not be read as
    // the `src` folder of the module.
    fun testDebugTabSubclassInDebugIsCleanUnderParentFolderNamedSrc() {
        withRootUnderParentFolderNamedSrc { root ->
            lint()
                .allowMissingSdk()
                .rootDirectory(root)
                .files(debugTabStub, fakeTab("src/debug/kotlin/com/example/MyTab.kt"))
                .run()
                .expectClean()
        }
    }

    fun testDebugTabSubclassInMainIsFlaggedUnderParentFolderNamedSrc() {
        withRootUnderParentFolderNamedSrc { root ->
            lint()
                .allowMissingSdk()
                .rootDirectory(root)
                .files(debugTabStub, fakeTab("src/main/kotlin/com/example/MyTab.kt"))
                // With a root directory from the test, the report shows the
                // absolute path, which contains the folder of each test mode.
                .expectIdenticalTestModeOutput(false)
                .run()
                .expectErrorCount(1)
        }
    }

    // A package folder named `src`, `java`, or `kotlin` comes after the source
    // folder of the module, so it does not change the source set.
    fun testDebugTabSubclassInPackageNamedSrcInMainIsFlagged() {
        lint()
            .allowMissingSdk()
            .files(
                debugTabStub,
                fakeTab("src/main/kotlin/com/example/src/MyTab.kt", "com.example.src"),
                fakeTab(
                    "src/main/kotlin/com/example/src/debug/kotlin/OtherTab.kt",
                    "com.example.src.debug.kotlin",
                ),
            )
            .run()
            .expectErrorCount(2)
    }

    fun testDebugTabSubclassInPackageNamedSrcInDebugIsClean() {
        lint()
            .allowMissingSdk()
            .files(
                debugTabStub,
                fakeTab("src/debug/kotlin/com/example/src/MyTab.kt", "com.example.src"),
                fakeTab(
                    "src/debug/java/com/example/src/release/java/OtherTab.kt",
                    "com.example.src.release.java",
                ),
            )
            .run()
            .expectClean()
    }

    fun testDebugTabSubclassInFlavorDebugIsClean() {
        lint()
            .allowMissingSdk()
            .files(debugTabStub, fakeTab("src/freeDebug/kotlin/com/example/MyTab.kt"))
            .run()
            .expectClean()
    }

    fun testDebugTabSubclassInFlavorIsFlagged() {
        lint()
            .allowMissingSdk()
            .files(debugTabStub, fakeTab("src/free/kotlin/com/example/MyTab.kt"))
            .run()
            .expectErrorCount(1)
    }

    fun testSourceSetNameBelowModuleDir() {
        val module = File("/Users/me/src/MyApp/app")
        assertEquals(
            "debug",
            LustroDebugLeakDetector.sourceSetName(
                File(module, "src/debug/kotlin/com/example/MyTab.kt"),
                module,
            ),
        )
        assertEquals(
            "main",
            LustroDebugLeakDetector.sourceSetName(
                File(module, "src/main/java/com/example/src/debug/kotlin/MyTab.kt"),
                module,
            ),
        )
        assertNull(
            LustroDebugLeakDetector.sourceSetName(File(module, "debug/MyTab.kt"), module),
        )
    }

    // A source folder that the build adds from outside the module: the absolute
    // path is the only data, and the last match wins.
    fun testSourceSetNameOutsideModuleDir() {
        val module = File("/Users/me/src/MyApp/app")
        assertEquals(
            "debug",
            LustroDebugLeakDetector.sourceSetName(
                File("/Users/me/src/kotlin/MyApp/shared/src/debug/kotlin/com/example/MyTab.kt"),
                module,
            ),
        )
        assertEquals(
            "main",
            LustroDebugLeakDetector.sourceSetName(
                File("/Users/me/src/MyApp/shared/src/main/kotlin/com/example/MyTab.kt"),
                null,
            ),
        )
    }

    private companion object {
        // The lint harness does not delete a root directory that a test gives it.
        fun withRootUnderParentFolderNamedSrc(block: (File) -> Unit) {
            val tmp = Files.createTempDirectory("lustro-lint").toFile()
            try {
                block(File(tmp, "src/MyApp").apply { mkdirs() })
            } finally {
                tmp.deleteRecursively()
            }
        }

        fun gradleBuild(checkTestSources: Boolean): TestFile =
            gradle(
                """
                apply plugin: 'com.android.application'
                android {
                    lintOptions {
                        checkTestSources $checkTestSources
                    }
                }
                """,
            ).indented()

        fun fakeTab(path: String, packageName: String = "com.example"): TestFile {
            val name = path.substringAfterLast('/').removeSuffix(".kt")
            return kotlin(
                path,
                """
                package $packageName
                import io.github.twinsen81.lustro.DebugTab
                class $name : DebugTab() {
                    override val id = "fake"
                    override val title = "Fake"
                    override val icon = "x"
                }
                """,
            ).indented()
        }

        val debugTabStub: TestFile =
            kotlin(
                """
                package io.github.twinsen81.lustro
                abstract class DebugTab {
                    abstract val id: String
                    abstract val title: String
                    abstract val icon: String
                }
                """,
            ).indented()

        val debugTabStubInMain: TestFile =
            kotlin(
                "src/main/kotlin/io/github/twinsen81/lustro/DebugTab.kt",
                """
                package io.github.twinsen81.lustro
                abstract class DebugTab {
                    abstract val id: String
                    abstract val title: String
                    abstract val icon: String
                }
                """,
            ).indented()

        val lustroStub: TestFile =
            kotlin(
                """
                package io.github.twinsen81.lustro
                import android.app.Application
                class Lustro private constructor() {
                    class Builder internal constructor() {
                        fun addTab(tab: DebugTab): Builder = this
                        fun build(): Lustro = Lustro()
                    }
                    companion object {
                        fun builder(application: Application): Builder = Builder()
                    }
                }
                """,
            ).indented()
    }
}
