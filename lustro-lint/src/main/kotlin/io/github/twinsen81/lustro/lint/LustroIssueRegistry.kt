package io.github.twinsen81.lustro.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/**
 * Registers the Lustro release-safety lint checks. Shipped to consumers from
 * `:lustro` via `lintPublish(project(":lustro-lint"))` and discovered through the
 * `META-INF/services/com.android.tools.lint.client.api.IssueRegistry` resource.
 */
public class LustroIssueRegistry : IssueRegistry() {

    override val issues: List<Issue> = listOf(LustroDebugLeakDetector.ISSUE)

    // Built against lint 32.2.1 (AGP 9.2.1). CURRENT_API pins the API this
    // registry was compiled against; minApi keeps it loadable on a slightly older
    // lint that still understands the same Detector contract.
    override val api: Int = CURRENT_API

    override val minApi: Int = MIN_API

    override val vendor: Vendor =
        Vendor(
            vendorName = "Lustro",
            identifier = "io.github.twinsen81:lustro-lint",
            feedbackUrl = "https://github.com/Twinsen81/Lustro/issues",
        )

    private companion object {
        // Oldest lint API this registry is known to work against. Lint 32.x all
        // share the same Detector/UAST contract used here.
        private const val MIN_API = 14
    }
}
