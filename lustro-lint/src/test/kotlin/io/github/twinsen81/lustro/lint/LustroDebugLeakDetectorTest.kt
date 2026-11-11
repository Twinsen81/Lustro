package io.github.twinsen81.lustro.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Severity

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

    private companion object {
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

        fun fakeTab(path: String): TestFile {
            val name = path.substringAfterLast('/').removeSuffix(".kt")
            return kotlin(
                path,
                """
                package com.example
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
