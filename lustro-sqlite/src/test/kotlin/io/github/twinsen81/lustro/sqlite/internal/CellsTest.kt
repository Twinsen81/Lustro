package io.github.twinsen81.lustro.sqlite.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class CellsTest {
    @Test
    fun `each storage class is written with its type`() {
        assertEquals("{\"type\":\"null\"}", json(Cell.Null))
        assertEquals("{\"type\":\"integer\",\"value\":-42}", json(Cell.Integer(-42)))
        assertEquals("{\"type\":\"real\",\"value\":1.5}", json(Cell.Real(1.5)))
        assertEquals("{\"type\":\"text\",\"value\":\"a \\\"b\\\"\\n\"}", json(Cell.Text("a \"b\"\n", length = null)))
        assertEquals("{\"type\":\"blob\",\"bytes\":2048}", json(Cell.Blob(2048)))
    }

    @Test
    fun `a cut text says so and has its whole length`() {
        assertEquals("{\"type\":\"text\",\"value\":\"abc\",\"truncated\":true,\"length\":5000}", json(Cell.Text("abc", length = 5000)))
    }

    @Test
    fun `an integer beyond what a JavaScript number holds, and an infinity, are strings`() {
        assertEquals("{\"type\":\"integer\",\"value\":9007199254740991}", json(Cell.Integer(Cells.MAX_SAFE_INTEGER)))
        assertEquals("{\"type\":\"integer\",\"value\":-9007199254740991}", json(Cell.Integer(-Cells.MAX_SAFE_INTEGER)))
        assertEquals("{\"type\":\"integer\",\"value\":\"9007199254740992\"}", json(Cell.Integer(Cells.MAX_SAFE_INTEGER + 1)))
        assertEquals("{\"type\":\"integer\",\"value\":\"-9223372036854775808\"}", json(Cell.Integer(Long.MIN_VALUE)))
        assertEquals("{\"type\":\"real\",\"value\":\"Infinity\"}", json(Cell.Real(Double.POSITIVE_INFINITY)))
        assertEquals("{\"type\":\"real\",\"value\":\"-Infinity\"}", json(Cell.Real(Double.NEGATIVE_INFINITY)))
    }

    @Test
    fun `identifiers are quoted with their quotes doubled, and LIKE wildcards are escaped`() {
        assertEquals("\"we\"\"ird\"", SqliteReader.quote("we\"ird"))
        assertEquals("100\\% a\\_b c\\\\d", SqliteReader.escapeLike("100% a_b c\\d"))
    }

    private fun json(cell: Cell): String = StringBuilder().also { Cells.append(it, cell) }.toString()
}
