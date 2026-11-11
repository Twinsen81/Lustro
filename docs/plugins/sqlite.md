# SQLite

The SQLite plugin shows your app's SQLite and Room databases over the API: the tables and views,
their schemas, and pages of rows, sorted and filtered. It runs a client's own statements, read
only, on every database, and changes the rows of the databases that you make writable. It is a
pair of artifacts, split by variant as `lustro` is:

```toml
lustro-sqlite      = { group = "io.github.twinsen81", name = "lustro-sqlite",      version.ref = "lustro" }
lustro-sqlite-noop = { group = "io.github.twinsen81", name = "lustro-sqlite-noop", version.ref = "lustro" }
```

```kotlin
dependencies {
    debugImplementation(libs.lustro.sqlite)
    releaseImplementation(libs.lustro.sqlite.noop)
}

val lustro = Lustro.builder(application)
    .addTab(NetworkDebugTab.create(senderClient = client))
    .addTab(
        SqliteDebugTab.create(
            databases = listOf(
                SqliteDb.support(
                    "app",
                    writable = true,
                    tableHints = mapOf("notes" to TableHint("NoteEntity", "A note that the user wrote.")),
                    onWrite = { appDatabase.invalidationTracker.refreshVersionsAsync() },
                    unmasked = setOf("oauthScopes"),
                ) { appDatabase.openHelper.writableDatabase },
                SqliteDb.file("cache", application.getDatabasePath("cache.db"), valuePolicy = SqliteValuePolicy.VISIBLE),
            ),
            discoverFrom = application,
        ),
    )
    .build()
```

- **The app's open handle.** `SqliteDb.support` takes a function that returns the app's
    `SupportSQLiteDatabase`: for Room, `roomDb.openHelper.writableDatabase`, which works for an
    encrypted Room database too. `SqliteDb.framework` takes one that returns a framework
    `SQLiteDatabase`, such as a `SQLiteOpenHelper`'s. The tab calls the function on a request's
    thread for each read, reads through the app's connection pool, and never closes the handle.
    Use a handle for each database that the app has open: a second connection that stays open to
    the same file can conflict with the app's, mostly in WAL mode. `androidx.sqlite` is
    `compileOnly` in both artifacts: an app that registers no `SupportSQLiteDatabase` needs none,
    and Room brings it.
- **A file.** `SqliteDb.file` takes a database file that the tab opens read-only for each
    request, and closes after it, such as the database of a library. The engine refuses every
    write on that connection, and in WAL mode it does not block the app's writer. A file in which
    SQLite finds corruption is reported and left on disk, where the platform's default error
    handler would delete it. In WAL mode,
    SQLite can leave the empty `-wal` and `-shm` files of the database after the tab reads it.
    The tab does not see the file of a live handle before it reads, so register each file once.
    The tab opens a file with the platform's SQLite, which keeps the locks of the app's other
    connections to it through that SQLite. Do not register a file that the app opens with another
    copy of SQLite, such as Room's bundled driver or SQLCipher: on Android, closing any descriptor
    of a file drops the process's POSIX locks on it, and only the SQLite that holds them works
    around that. See [How To Corrupt An SQLite Database File](https://www.sqlite.org/howtocorrupt.html#_posix_advisory_locks_canceled_by_a_separate_thread_doing_close_).
- **Discovery.** With `discoverFrom = context`, the tab also lists the other database files in the
    app's `databases` directory, such as those of your libraries, by file name. It looks in no
    other directory: WorkManager, for one, keeps `androidx.work.workdb` in `noBackupFilesDir`, so
    register that file with `SqliteDb.file`, as the [WorkManager](work.md) page shows.
    It lists the directory on each request, so it finds a file that the app creates later, and
    leaves out a file that a registration stands for, SQLite's journal, WAL, and lock files, and a
    file that is not a SQLite database. It asks the platform's SQLite about a file, and never
    reads one itself, and it asks only about the files that no registration stands for. A
    discovered file is read as a `SqliteDb.file`, so leave discovery off when the directory has a
    database that the app opens with another copy of SQLite. Discovery is off by default.
- **Hints and the writable flag.** `tableHints` gives a table the name of the class that maps it
    and one line on what a row is, for an agent that reads the database without your code.
    `writable`, `false` by default, says whether a client may change the database through the tab.
    A file is never writable.
- **Value policies.** `valuePolicy` is `SqliteValuePolicy.MASK_SENSITIVE` by default, as in the
    Preferences tab: the tab leaves out the values of a column whose name looks like it holds a
    secret, such as `access_token`, `clientSecret`, or `password`, by the same name check as the
    Network tab's redaction (`SensitiveKeys.isSensitive`). `VISIBLE` shows every value. A
    discovered file is `MASK_SENSITIVE`. The check reads only the column's name, so a table that
    keeps the name of a secret in a row, such as HTTP headers in `name` and `value` columns, shows
    the value of an `Authorization` row. A masked cell has only its storage class,
    `{ "type": "text", "masked": true }`, and the tab never sends its value (`GET rows` does not
    read it at all); a `NULL` stays `NULL`. `GET rows` masks by the table's column, and its filter
    does not search a masked column.
    `POST query` masks by the result's column name, because Android does not say which table column
    a result column comes from: `length(access_token)` is masked by its name, but an alias, such as
    `SELECT access_token AS t`, shows the value. So the policy keeps secrets off the screen and out
    of an agent's output, but it does not stop a client that has the token: to keep a database's
    values from every client, do not register it. The console does not edit a masked column.
    A column named only `key`, as in a key-value table such as WorkManager's `Preference`, is not
    masked, so that the rows show their names. `GET schema` always shows a column's default value
    and the `CREATE` statement, also of a masked column.
- **Exceptions to the mask.** `unmasked` on each `SqliteDb` factory lists the columns whose values
    `MASK_SENSITIVE` shows although their names look like they hold a secret, such as an
    `oauthScopes` column, which has `auth` in its name. A name there matches a column with exactly
    that name, in any case; it is no pattern. Such a column is not `masked` in `GET schema`, `GET rows`
    sends and filters its values, the console shows, copies, and edits them, and `POST query` shows
    a result column of that name. An expression of the column, such as `length(oauthScopes)`, has
    another name, so it stays masked. `VISIBLE` shows every value anyway, and a discovered file has no
    exceptions.
- **Browsing runs only the SQL that the tab builds.** A client names a database, a table, and a
    column, and the tab checks each name against the database's schema before it puts it in a
    statement, as a quoted identifier. A filter value is a bound parameter. A page of rows reads
    each cell by its own type, as SQLite stores any type in any column: at most 4096 characters of
    a text, with the whole length when it is cut, and only the size of a blob, also of a blob in a
    `TEXT` column. A blob of megabytes does not fill a page or the memory. Each read stops when the
    request times out (`DebugConfig.requestTimeoutMs`, 30 s by default). On a file database that
    is not in WAL mode, a statement also stops after 2 s: the app's writes wait for the lock of the
    tab's connection, and Android fails a write that waited 2.5 s.
- **A client's statement reads through a read-only connection.** `POST query` runs it on a
    connection that SQLite opens with `OPEN_READONLY` to the database's file, for one request, and
    never on the app's handle, on which Android's `rawQuery` would run a `DELETE` too. SQLite
    refuses every write there, for every database. A result has at most 1000 rows, with each text
    whole. A database in memory, and a live handle on another copy of SQLite than the platform's,
    such as SQLCipher's, take no `query`: browse them with `GET rows`. On a database that is not in
    WAL mode, a statement stops after 2 s, because the app's writes wait for its lock, and Android
    fails a write that waited 2.5 s.
- **Writes are opt-in for each database.** `POST exec` runs a statement that writes, and
    `POST edit` and `POST delete` change or delete one row by its rowid or primary key, through the
    app's handle, in a transaction of their own, and only on a `writable` database: any other
    answers 403. `exec` refuses a statement that returns rows, such as a `SELECT`, before it runs:
    read it with `query`. Both `query` and `exec` refuse the statements that would leave a transaction or a
    setting on the app's connection (`BEGIN`, `SAVEPOINT`, and the other transaction statements,
    `ATTACH`, `DETACH`, `VACUUM`, and, in `exec`, every `PRAGMA`), and `query` refuses the pragmas
    that set a value for the whole process, such as `hard_heap_limit`.
- **The app's observers.** Room's `InvalidationTracker` looks for changes after Room's own
    transactions only, so an open screen that a DAO's `Flow` or `LiveData` fills does not show a
    change that the tab writes until the app's next write through Room. An app on a
    `SQLiteOpenHelper` of its own reloads a screen on an event of its own, which a write through the
    tab does not send either. `SqliteDb.support` and `SqliteDb.framework` take `onWrite`, a
    `Runnable`, which the tab calls after each `exec`, `edit`, or `delete` that committed. For Room,
    pass Room's public refresh call, which is there for writes that do not go through Room:

    ```kotlin
    SqliteDb.support(
        "app",
        writable = true,
        onWrite = { appDatabase.invalidationTracker.refreshVersionsAsync() },
    ) { appDatabase.openHelper.writableDatabase }
    ```

    For an app on its own `SQLiteOpenHelper`, post the event that its screens reload on. From
    Java, which has no named arguments, give the parameters before `onWrite` too:

    ```java
    SqliteDb.framework("app", true, Collections.emptyMap(), SqliteValuePolicy.MASK_SENSITIVE,
            () -> changes.notifyDatabaseChanged(),
            () -> openHelper.getWritableDatabase())
    ```

    The sample registers its Room database so, and its SQLite screen shows a Room `Flow` that
    changes at once when the console edits a row. What `onWrite` throws is logged, and the write
    stays committed.
- **Routes.** `GET /api/v1/sqlite/databases` lists the databases with their source, file, size,
    the size of the `-wal` file, and schema version (`PRAGMA user_version`). In WAL mode, as Room
    keeps a database, the `-wal` file holds the changes until a checkpoint, so it can be larger than
    the database file. `GET tables?db=` lists the tables and views, with
    the row count of each table, and flags the internal ones (`sqlite_*`, `android_metadata`,
    `room_master_table`, and the shadow tables of a full-text index). `GET schema?db=&table=` gives
    the columns, the foreign keys, the row count, the `CREATE` statement, and the hint.
    `GET rows?db=&table=` returns a page in the pagination envelope `{ items, nextCursor }`: 50 rows
    by default, `limit` up to 500, a `filter` text that any cell can contain, and a `sort` column
    with `dir=asc|desc`. `POST query` and `POST exec` take `{ db, sql }`, and `POST edit` and
    `POST delete` take `{ db, table, rowid }` or `{ db, table, key }`, with `values` for an edit.
    The `lustro` CLI reaches them with `lustro api`, for example
    `lustro api GET sqlite/rows --param db=app --param table=notes --param sort=created_at --param dir=desc`
    or `lustro api POST sqlite/query --body '{"db":"app","sql":"SELECT count(*) FROM notes"}'`.
    See [Agents and automation](../AGENTS.md#sqlite-tab-operations) and
    [`sqlite.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro-sqlite/src/main/assets/lustro/sqlite.openapi.json).
- **In the console.** The **SQLite** tab lists the databases in the app's order, with the found
    ones in a group of their own, each with its size and the size of its `-wal` file, and each
    one's tables, with a filter, the internal tables behind
    **Show internal**, and a ★ to pin a table to the top. A table's rows show a page at a time:
    **Prev** and **Next** page through them, a filter finds the rows in which a cell contains a
    text, a click on a header sorts ascending, descending, then not, and a right-click or the
    **Columns** menu hides a column. Your browser keeps the hidden columns and the widths for each
    table. A cut text opens whole, read with `query`. A masked cell shows `masked` and its type. A
    click on a row opens it, to edit, set a column to `NULL`, or delete the row in a writable
    database, and to read and copy in any other; a masked column is locked there.
    **Auto refresh** reads the page again every 2 s. The **SQL** view runs a statement with
    `query`; **Run as write**, only for a writable database, sends it to `exec`. A refused
    statement shows the route's error.
- **Every client with the token reads every registered database, and writes every writable one.**
    Register only the databases that you want to show. See [SECURITY.md](https://github.com/Twinsen81/Lustro/blob/main/SECURITY.md#sqlite-databases).
- In `lustro-sqlite-noop`, the tab opens no database and never calls the functions.
