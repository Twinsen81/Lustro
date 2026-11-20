package io.github.twinsen81.lustro.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The paths of a tab's document: its root, `/api/v1/<tab>`, counts as a path of the tab, and
 * one slash at the end of a path does not count, in the document or in the request.
 */
class DeclaredQueryParametersTest {
    private val declared =
        DeclaredQueryParameters.parse(
            "journals",
            """
            {"openapi":"3.1.0","paths":{
              "/api/v1/journals":{
                "get":{"parameters":[{"name":"limit","in":"query"}]},
                "post":{}
              },
              "/api/v1/journals/{id}":{"get":{"parameters":[{"name":"full","in":"query"}]},"delete":{}},
              "/api/v1/journals/seed/":{"post":{"parameters":[{"name":"count","in":"query"}]}},
              "/api/v1/journalsx":{"put":{"parameters":[{"name":"other","in":"query"}]}},
              "/api/v1/other/journals":{"patch":{}}
            }}
            """.trimIndent(),
        )!!

    @Test
    fun `the root path of the tab is read, and a path that only starts with the tab id is not`() {
        assertEquals(listOf("GET", "POST"), declared.methods(""))
        assertEquals(emptyList<String>(), declared.undeclared("GET", "", listOf("limit")))
        assertEquals(listOf("cursor"), declared.undeclared("GET", "", listOf("cursor", "limit")))
        assertEquals(listOf("other"), declared.undeclared("POST", "", listOf("other")))
        // No operation for PUT at the root: the document says nothing about the route.
        assertEquals(emptyList<String>(), declared.undeclared("PUT", "", listOf("other")))
    }

    @Test
    fun `one slash at the end of a path does not count, in the request and in the document`() {
        assertEquals(listOf("GET", "DELETE"), declared.methods("7/"))
        assertEquals(listOf("full2"), declared.undeclared("GET", "7/", listOf("full", "full2")))
        assertEquals(listOf("POST"), declared.methods("seed"))
        assertEquals(listOf("POST"), declared.methods("seed/"))
        assertEquals(listOf("x"), declared.undeclared("POST", "seed", listOf("count", "x")))
        // Only one slash: "seed//" and "/" keep an empty segment, which no path has.
        assertEquals(emptyList<String>(), declared.methods("seed//"))
        assertEquals(emptyList<String>(), declared.methods("/"))
        assertEquals(emptyList<String>(), declared.methods("7/full"))
    }

    @Test
    fun `a document of another shape is not read`() {
        assertNull(DeclaredQueryParameters.parse("journals", "not json"))
        assertNull(DeclaredQueryParameters.parse("journals", """{"openapi":"3.1.0"}"""))
    }
}
