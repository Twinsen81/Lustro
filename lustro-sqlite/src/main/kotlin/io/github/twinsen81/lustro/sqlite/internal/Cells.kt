package io.github.twinsen81.lustro.sqlite.internal

import io.github.twinsen81.lustro.escapeForJson

/** How a page of rows writes a cell: `{"type":..}` with its value, as JSON has it. */
internal object Cells {
    /** The largest integer that a JavaScript number holds exactly: 2^53 - 1. */
    const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L

    fun append(out: StringBuilder, cell: Cell) {
        when (cell) {
            is Cell.Null -> out.append("{\"type\":\"null\"}")
            is Cell.Integer -> {
                out.append("{\"type\":\"integer\",\"value\":")
                // A larger one is a string, so that a JavaScript client does not round it.
                if (cell.value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) out.append(cell.value) else out.append('"').append(cell.value).append('"')
                out.append('}')
            }
            is Cell.Real -> {
                out.append("{\"type\":\"real\",\"value\":")
                // JSON has no number for an infinity, which a REAL can hold. SQLite stores a NaN as NULL.
                if (cell.value.isFinite()) out.append(cell.value) else out.append('"').append(cell.value).append('"')
                out.append('}')
            }
            is Cell.Text -> {
                out.append("{\"type\":\"text\",\"value\":\"").append(cell.value.escapeForJson()).append('"')
                if (cell.length != null) out.append(",\"truncated\":true,\"length\":").append(cell.length)
                out.append('}')
            }
            is Cell.Blob -> out.append("{\"type\":\"blob\",\"bytes\":").append(cell.bytes).append('}')
        }
    }
}
