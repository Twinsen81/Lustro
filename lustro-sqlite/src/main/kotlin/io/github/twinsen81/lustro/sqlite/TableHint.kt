package io.github.twinsen81.lustro.sqlite

/**
 * What a table or a view holds, in words, for a reader who has no schema docs,
 * such as an agent. The tab adds it to the table in `GET tables` and
 * `GET schema`.
 *
 * @property entity the name of the class that maps the table, such as a Room
 *   entity.
 * @property purpose one line on what a row is.
 */
public class TableHint @JvmOverloads constructor(
    public val entity: String? = null,
    public val purpose: String? = null,
)
