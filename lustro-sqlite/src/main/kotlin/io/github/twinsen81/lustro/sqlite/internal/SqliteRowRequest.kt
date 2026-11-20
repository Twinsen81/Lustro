package io.github.twinsen81.lustro.sqlite.internal

import org.json.JSONObject
import org.json.JSONTokener

/**
 * The row and the values in the body of `POST edit` and `POST delete`. Each
 * name is found in the table's schema, and each value is bound.
 *
 * The route gives `rowid`, `key`, and `values` as the JSON text of their
 * values, or `null` for one that the body does not have or that is `null`:
 * `rowid` is a whole number or a string of one, and a declared field has one
 * type. Each is parsed as Android's `JSONObject` reads a body.
 */
internal object SqliteRowRequest {
    fun target(rowidJson: String?, keyJson: String?, table: Table, columns: List<Column>, rowid: String?): RowTarget {
        val hasRowid = rowidJson != null
        if (hasRowid == (keyJson != null)) {
            throw RouteException(if (hasRowid) "Name the row by rowid or by key, not both" else "rowid or key is required, to name the row", field = "rowid")
        }
        return if (rowidJson != null) byRowid(rowidJson, table, columns, rowid) else byKey(keyJson!!, table, columns)
    }

    fun values(valuesJson: String, columns: List<Column>): List<Pair<Column, Any?>> {
        val given =
            (parse(valuesJson) as? JSONObject)?.takeIf { it.length() > 0 }
                ?: throw RouteException("values is required, as a JSON object with at least one column and its value", field = "values")
        return given.keys().asSequence().map { name -> column(columns, name) to bindable(given, name, "values") }.toList()
    }

    private fun byRowid(rowidJson: String, table: Table, columns: List<Column>, rowid: String?): RowTarget {
        if (rowid == null) {
            val key = names(primaryKey(columns))
            throw RouteException("${table.name} has no rowid: name the row by key, with the primary key's columns: $key", field = "rowid")
        }
        val id =
            when (val value = parse(rowidJson)) {
                is Int -> value.toLong()
                is Long -> value
                is String -> value.toLongOrNull()
                else -> null
            } ?: throw RouteException("rowid must be a whole number, or a string of one, as GET rows gives it", field = "rowid")
        return RowTarget(table, "$rowid = ?", arrayOf(id), "rowid")
    }

    private fun byKey(keyJson: String, table: Table, columns: List<Column>): RowTarget {
        val key = primaryKey(columns)
        if (key.isEmpty()) throw RouteException("${table.name} has no primary key: name the row by rowid", field = "key")
        val given = parse(keyJson) as? JSONObject
        val names = given?.keys()?.asSequence()?.toList().orEmpty()
        val matched = key.map { column -> find(names, column.name) { it } }
        if (given == null || names.size != key.size || null in matched) {
            throw RouteException("key must be a JSON object with the primary key's columns, and only them: ${names(key)}", field = "key")
        }
        val values = matched.map { bindable(given, it!!, "key") }
        // IS, so that a NULL in a key column, which SQLite allows in a table with a rowid, matches a NULL.
        return RowTarget(table, key.joinToString(" AND ") { "${SqliteReader.quote(it.name)} IS ?" }, values.toTypedArray(), "key")
    }

    private fun column(columns: List<Column>, name: String): Column {
        val column = find(columns, name) { it.name } ?: throw RouteException("The table has no column named $name", field = "values")
        if (column.generated) throw RouteException("${column.name} is a generated column: SQLite computes it", field = "values")
        return column
    }

    // A JSON value as SQLite binds it. The column's affinity converts a text, such as "42" in an INTEGER column.
    private fun bindable(json: JSONObject, name: String, field: String): Any? =
        when (val value = json.get(name)) {
            JSONObject.NULL -> null
            is String -> value
            is Int -> value.toLong()
            is Long -> value
            is Number -> value.toDouble().takeIf { it.isFinite() } ?: throw RouteException("$name is not a finite number", field = field)
            else -> throw RouteException("The value of $name must be a string, a number, or null; a blob is written with exec", field = field)
        }

    // The router has read the text as strict JSON, so it parses.
    private fun parse(json: String): Any? = JSONTokener(json).nextValue()

    private fun primaryKey(columns: List<Column>): List<Column> = columns.filter { it.primaryKey > 0 }.sortedBy { it.primaryKey }

    private fun names(columns: List<Column>): String = columns.joinToString { it.name }

    // SQLite names do not depend on case, but one with the same case comes first.
    private fun <T> find(items: List<T>, name: String, nameOf: (T) -> String): T? =
        items.firstOrNull { nameOf(it) == name } ?: items.firstOrNull { nameOf(it).equals(name, ignoreCase = true) }
}
