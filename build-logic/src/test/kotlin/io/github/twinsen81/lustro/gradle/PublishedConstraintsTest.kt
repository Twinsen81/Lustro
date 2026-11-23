package io.github.twinsen81.lustro.gradle

import org.junit.Assert.assertEquals
import org.junit.Test

class PublishedConstraintsTest {
    private val group = "io.github.twinsen81"

    private fun constraint(module: String, version: String) =
        """{"group":"$group","module":"$module","version":{"requires":"$version"}}"""

    private fun metadata(apiConstraints: List<String>, runtimeConstraints: List<String> = apiConstraints) = """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "releaseVariantReleaseApiPublication",
              "attributes": {"org.gradle.category": "library", "org.gradle.usage": "java-api"},
              "dependencyConstraints": [${apiConstraints.joinToString(",")}]
            },
            {
              "name": "releaseVariantReleaseRuntimePublication",
              "attributes": {"org.gradle.category": "library", "org.gradle.usage": "java-runtime"},
              "dependencyConstraints": [${runtimeConstraints.joinToString(",")}]
            },
            {
              "name": "releaseVariantReleaseSourcePublication",
              "attributes": {"org.gradle.category": "documentation", "org.gradle.docstype": "sources"}
            }
          ]
        }
    """

    @Test
    fun `metadata with a constraint on every other module at the same version passes`() {
        val json = metadata(listOf(constraint("lustro-api", "0.2.0"), constraint("lustro-logs", "0.2.0")))

        assertEquals(emptyList<String>(), missingConstraints(json, group, "0.2.0", setOf("lustro-api", "lustro-logs")))
    }

    @Test
    fun `a module without a constraint in one variant fails`() {
        val json = metadata(
            apiConstraints = listOf(constraint("lustro-api", "0.2.0"), constraint("lustro-logs", "0.2.0")),
            runtimeConstraints = listOf(constraint("lustro-api", "0.2.0")),
        )

        assertEquals(
            listOf("releaseVariantReleaseRuntimePublication: no constraint on $group:lustro-logs"),
            missingConstraints(json, group, "0.2.0", setOf("lustro-api", "lustro-logs")),
        )
    }

    @Test
    fun `a constraint on another version fails`() {
        val json = metadata(listOf(constraint("lustro-api", "0.1.0")))

        assertEquals(
            listOf(
                "releaseVariantReleaseApiPublication: $group:lustro-api requires 0.1.0, not 0.2.0",
                "releaseVariantReleaseRuntimePublication: $group:lustro-api requires 0.1.0, not 0.2.0",
            ),
            missingConstraints(json, group, "0.2.0", setOf("lustro-api")),
        )
    }

    @Test
    fun `metadata without a library variant fails`() {
        assertEquals(listOf("no library variant"), missingConstraints("""{"variants": []}""", group, "0.2.0", setOf("lustro-api")))
    }
}
