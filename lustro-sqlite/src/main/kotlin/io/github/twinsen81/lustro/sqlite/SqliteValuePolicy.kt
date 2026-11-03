package io.github.twinsen81.lustro.sqlite

/**
 * What the SQLite tab shows of the values in a database. The tables, the
 * columns, the row counts, and the storage class of each cell always show.
 */
public enum class SqliteValuePolicy {
    /** Every value. */
    VISIBLE,

    /**
     * Every value, except the values of a column whose name looks like it holds
     * a secret, such as `access_token`, `clientSecret`, or `password`. The check
     * is [SensitiveKeys.isSensitive][io.github.twinsen81.lustro.SensitiveKeys.isSensitive],
     * the same as the Network tab's redaction and the Preferences tab's. It goes
     * by the column's name only. The app can show the values of some of these
     * columns with the `unmasked` names of the [SqliteDb] factories: a name there
     * matches a column with exactly that name, in any case. A column named
     * only `key`, the name column of a key-value table, is not masked.
     *
     * `POST query` masks a column of its result by the result's column name, as
     * the statement names it, because Android does not say which table column a
     * result column comes from. SQLite names an expression without an alias by
     * its text, such as `length(access_token)`, which is masked too, but an alias,
     * such as `SELECT access_token AS t`, shows the value. The policy keeps
     * secrets off the screen and out of an agent's output; it does not stop a
     * client that has the token and wants to read them. To keep a database's
     * values from such a client, do not register the database.
     */
    MASK_SENSITIVE,
}
