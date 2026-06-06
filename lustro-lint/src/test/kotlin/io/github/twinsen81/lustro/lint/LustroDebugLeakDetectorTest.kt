package io.github.twinsen81.lustro.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

/**
 * Unit tests for [LustroDebugLeakDetector] using the lint-tests harness. Each test
 * compiles a tiny stub of the Lustro debug API plus a consumer file placed in a
 * specific source set, then asserts the leak check fires only outside `src/debug`.
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

    fun testBuilderAndAddTabInMainAreFlagged() {
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
            // One report each for builder(...) and addTab(...). The DebugTab subclass
            // (object MyTab) in src/main adds a third report.
            .expectErrorCount(3)
    }

    fun testBuilderInDebugIsClean() {
        lint()
            .allowMissingSdk()
            .files(
                debugTabStub,
                lustroStub,
                kotlin(
                    "src/debug/kotlin/com/example/Setup.kt",
                    """
                    package com.example
                    import android.app.Application
                    import io.github.twinsen81.lustro.Lustro
                    fun init(app: Application) {
                        Lustro.builder(app).build()
                    }
                    """,
                ).indented(),
            )
            .run()
            .expectClean()
    }

    private companion object {
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
