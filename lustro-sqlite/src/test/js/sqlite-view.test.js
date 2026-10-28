// The SQLite tab's view: the stack of cursors that Previous needs, the hidden
// columns and the widths of each table, where a console statement goes, and the
// SQL and the bodies that the page builds to name a row.
//
// These are where the page can go wrong without an error: a stack that loses a
// cursor shows another page as the page before, a statement that goes to exec
// writes what the user meant to read, and a WHERE that names another row reads,
// edits, or deletes the wrong one.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { loadTabJs, golden } = require('../../../../lustro/src/test/js/harness.js');

const sqlite = loadTabJs('lustro-sqlite', 'sqlite');

// The test's own values: the script's come from another context, which deepStrictEqual tells apart.
const plain = (value) => JSON.parse(JSON.stringify(value));

const notesSchema = () => golden('sqlite-schema.json');
const notesRows = () => golden('sqlite-rows.json').items;
const database = (name) => golden('sqlite-databases.json').items.find((item) => item.name === name);

// A table WITHOUT ROWID, named by its primary key over two columns, as the sample's note_tags.
const tagsSchema = {
    database: 'app', table: 'note_tags', type: 'table', internal: false, hasRowId: false, rowCount: 2,
    columns: [
        { name: 'note_id', type: 'INTEGER', notNull: true, defaultValue: null, primaryKey: 1, generated: false },
        { name: 'tag', type: 'TEXT', notNull: true, defaultValue: null, primaryKey: 2, generated: false },
    ],
    foreignKeys: [],
};
const tagRow = (tag) => ({ cells: { note_id: { type: 'integer', value: 1 }, tag } });

function storageOf(entries) {
    const map = new Map(Object.entries(entries || {}));
    return { getItem: (key) => (map.has(key) ? map.get(key) : null) };
}

// ── The cursor stack ────────────────────────────────────────────

test('Next keeps the cursor of each page, and Previous goes back through them in order', () => {
    const paging = sqlite.sqliteNewPaging();
    paging.next = 'c50';
    assert.strictEqual(sqlite.sqlitePageForward(paging), true);
    assert.deepStrictEqual(plain(paging), { cursor: 'c50', stack: [null], next: null });

    paging.next = 'c100';
    sqlite.sqlitePageForward(paging);
    assert.deepStrictEqual(plain(paging.stack), [null, 'c50']);
    assert.strictEqual(paging.cursor, 'c100');

    assert.strictEqual(sqlite.sqlitePageBack(paging), true);
    assert.strictEqual(paging.cursor, 'c50');
    assert.strictEqual(sqlite.sqlitePageBack(paging), true);
    // The first page has no cursor: GET rows reads it without one.
    assert.strictEqual(paging.cursor, null);
    assert.strictEqual(sqlite.sqlitePageBack(paging), false);
    assert.strictEqual(paging.cursor, null);
});

test('Next does nothing on the last page, whose nextCursor is null', () => {
    const paging = sqlite.sqliteNewPaging();
    assert.strictEqual(sqlite.sqlitePageForward(paging), false);
    assert.deepStrictEqual(plain(paging), { cursor: null, stack: [], next: null });
});

test('the next cursor is unknown until its page comes back, so a double Next goes one page', () => {
    const paging = sqlite.sqliteNewPaging();
    paging.next = golden('sqlite-rows.json').nextCursor;
    sqlite.sqlitePageForward(paging);
    assert.strictEqual(sqlite.sqlitePageForward(paging), false);
    assert.strictEqual(paging.stack.length, 1);
});

test('a new sort or filter starts at the first page again', () => {
    const paging = sqlite.sqliteNewPaging();
    paging.next = 'c50';
    sqlite.sqlitePageForward(paging);
    paging.next = 'c100';

    sqlite.sqlitePageReset(paging);

    assert.deepStrictEqual(plain(paging), { cursor: null, stack: [], next: null });
});

test('the range counts the full pages before, and says the total only without a filter', () => {
    const paging = sqlite.sqliteNewPaging();
    paging.next = 'c50';
    sqlite.sqlitePageForward(paging);
    paging.next = 'c100';
    sqlite.sqlitePageForward(paging);
    paging.next = 'c150';

    assert.deepStrictEqual(plain(sqlite.sqlitePageRange(paging, 50, 50)), { first: 101, last: 150 });
    assert.strictEqual(sqlite.sqliteRangeLabel(paging, 50, 50, 2001, false), 'rows 101 to 150 of 2,001');
    // With a filter, the table's count is not the count of the matches.
    assert.strictEqual(sqlite.sqliteRangeLabel(paging, 50, 50, 2001, true), 'rows 101 to 150 · more');
    paging.next = null;
    assert.strictEqual(sqlite.sqliteRangeLabel(paging, 7, 50, 2001, true), 'rows 101 to 107');
});

test('an empty first page says whether a filter left the rows out', () => {
    const paging = sqlite.sqliteNewPaging();
    assert.strictEqual(sqlite.sqliteRangeLabel(paging, 0, 50, 0, false), 'no rows');
    assert.strictEqual(sqlite.sqliteRangeLabel(paging, 0, 50, 12, true), 'no row matches');
});

test('the rows path carries the cursor, the filter, and the sort, encoded', () => {
    const path = sqlite.sqliteRowsPath({
        db: 'app', table: 'note tags', cursor: 'Mw==', filter: '50% & more', sort: { column: 'created_at', dir: 'desc' }, limit: 50,
    });
    assert.strictEqual(path, 'rows?db=app&table=note%20tags&limit=50&cursor=Mw%3D%3D&filter=50%25%20%26%20more&sort=created_at&dir=desc');
    assert.strictEqual(sqlite.sqliteRowsPath({ db: 'app', table: 'notes', cursor: null, filter: '', sort: null, limit: 50 }), 'rows?db=app&table=notes&limit=50');
});

test('a header click sorts ascending, then descending, then not at all', () => {
    const asc = sqlite.sqliteNextSort(null, 'title');
    assert.deepStrictEqual(plain(asc), { column: 'title', dir: 'asc' });
    const desc = sqlite.sqliteNextSort(asc, 'title');
    assert.deepStrictEqual(plain(desc), { column: 'title', dir: 'desc' });
    assert.strictEqual(sqlite.sqliteNextSort(desc, 'title'), null);
    // Another column starts ascending.
    assert.deepStrictEqual(plain(sqlite.sqliteNextSort(desc, 'score')), { column: 'score', dir: 'asc' });
});

// ── The columns of each table ───────────────────────────────────

test('the hidden columns and the widths are kept for each database and table', () => {
    const all = {};
    const notes = sqlite.sqliteColumnState(all, 'app', 'notes');
    const columns = sqlite.sqliteGridColumns(notesSchema());
    assert.strictEqual(sqlite.sqliteSetHidden(columns, notes, 'body', true), true);
    notes.widths.title = 300;
    sqlite.sqlitePutColumnState(all, 'app', 'notes', notes);

    assert.deepStrictEqual(plain(sqlite.sqliteColumnState(all, 'app', 'notes')), { hidden: ['body'], widths: { title: 300 } });
    // Another table, and the same table in another database, keep their own.
    assert.deepStrictEqual(plain(sqlite.sqliteColumnState(all, 'app', 'folders')), { hidden: [], widths: {} });
    assert.deepStrictEqual(plain(sqlite.sqliteColumnState(all, 'catalog', 'notes')), { hidden: [], widths: {} });
});

test('the key of a table can\'t be another table\'s, whatever the names hold', () => {
    assert.notStrictEqual(sqlite.sqliteColumnsKey('a', 'b\u0000c'), sqlite.sqliteColumnsKey('a\u0000b', 'c'));
    assert.notStrictEqual(sqlite.sqliteColumnsKey('a","b', 'c'), sqlite.sqliteColumnsKey('a', 'b","c'));
});

test('a table with nothing hidden and the default widths leaves no entry', () => {
    const all = { [sqlite.sqliteColumnsKey('app', 'notes')]: { hidden: ['body'], widths: {} } };
    const state = sqlite.sqliteColumnState(all, 'app', 'notes');
    sqlite.sqliteSetHidden(sqlite.sqliteGridColumns(notesSchema()), state, 'body', false);

    sqlite.sqlitePutColumnState(all, 'app', 'notes', state);

    assert.deepStrictEqual(plain(all), {});
});

test('the last column that shows can\'t be hidden', () => {
    const columns = [{ name: 'a' }, { name: 'b' }];
    const state = { hidden: [], widths: {} };
    assert.strictEqual(sqlite.sqliteSetHidden(columns, state, 'a', true), true);
    assert.strictEqual(sqlite.sqliteCanHide(columns, state, 'b'), false);
    assert.strictEqual(sqlite.sqliteSetHidden(columns, state, 'b', true), false);
    assert.deepStrictEqual(plain(state.hidden), ['a']);
});

test('saved columns that the table no longer has don\'t hide all of it', () => {
    const columns = [{ name: 'a' }, { name: 'b' }];
    // As after a migration that renamed both columns, or a state written by hand.
    const shown = sqlite.sqliteVisibleColumns(columns, { hidden: ['a', 'b'], widths: {} });
    assert.deepStrictEqual(plain(shown.map((column) => column.name)), ['a', 'b']);
});

test('a column has a width by its type until the user drags it, and a width below the minimum is ignored', () => {
    const state = { hidden: [], widths: { title: 333, score: 10 } };
    const [rowid, id, title, , attachment, score] = sqlite.sqliteGridColumns(notesSchema());
    assert.strictEqual(sqlite.sqliteColumnWidth(state, title), 333);
    assert.strictEqual(sqlite.sqliteColumnWidth(state, score), 110);
    assert.strictEqual(sqlite.sqliteColumnWidth(state, id), 120);
    assert.strictEqual(sqlite.sqliteColumnWidth(state, attachment), 130);
    assert.strictEqual(sqlite.sqliteColumnWidth(state, rowid), 90);
});

test('the grid shows the rowid first, under a name that no column has', () => {
    const names = (schema) => sqlite.sqliteGridColumns(schema).map((column) => column.name);
    assert.deepStrictEqual(plain(names(notesSchema())), ['rowid', 'id', 'title', 'body', 'attachment', 'score', 'created_at', 'folder_id']);
    assert.deepStrictEqual(plain(names(tagsSchema)), ['note_id', 'tag']);

    assert.strictEqual(sqlite.sqliteRowidAlias(['id', 'ROWID']), '_rowid_');
    assert.strictEqual(sqlite.sqliteRowidAlias(['rowid', '_rowid_']), 'oid');
    assert.strictEqual(sqlite.sqliteRowidAlias(['rowid', '_rowid_', 'OID']), null);
});

// ── Where a console statement goes ──────────────────────────────

test('Run sends a statement to the read-only query route, also on a writable database', () => {
    const sql = 'DELETE FROM notes';
    assert.deepStrictEqual(plain(sqlite.sqliteConsoleRequest('read', database('app'), sql)), { route: 'query', body: { db: 'app', sql } });
    assert.deepStrictEqual(plain(sqlite.sqliteConsoleRequest('read', database('catalog'), sql)), { route: 'query', body: { db: 'catalog', sql } });
});

test('Run as write sends it to exec, only for a writable database', () => {
    const sql = 'UPDATE notes SET score = 1';
    assert.deepStrictEqual(plain(sqlite.sqliteConsoleRequest('write', database('app'), sql)), { route: 'exec', body: { db: 'app', sql } });

    const catalog = sqlite.sqliteConsoleRequest('write', database('catalog'), sql);
    assert.strictEqual(catalog.route, undefined);
    assert.match(catalog.error, /as a file, which the tab opens read-only/);
    const found = sqlite.sqliteConsoleRequest('write', database('sdk_cache.db'), sql);
    assert.strictEqual(found.route, undefined);
    assert.match(found.error, /found this database on disk/);
});

test('a statement goes nowhere without a database or a statement', () => {
    assert.ok(sqlite.sqliteConsoleRequest('read', null, 'SELECT 1').error);
    assert.ok(sqlite.sqliteConsoleRequest('read', Object.assign({}, database('app'), { error: 'gone' }), 'SELECT 1').error);
    assert.ok(sqlite.sqliteConsoleRequest('read', database('app'), '  \n ').error);
});

test('a statement goes as typed: the route, not the page, finds its first keyword', () => {
    const sql = '  -- why\n  BEGIN;';
    assert.strictEqual(sqlite.sqliteConsoleRequest('read', database('app'), sql).body.sql, sql);
});

test('the recent statements keep the newest first, once each, and ten at most', () => {
    let list = [];
    for (let i = 1; i <= 12; i++) list = sqlite.sqlitePushRecent(list, `SELECT ${i}`);
    assert.strictEqual(list.length, 10);
    assert.strictEqual(list[0], 'SELECT 12');
    assert.strictEqual(list[9], 'SELECT 3');

    list = sqlite.sqlitePushRecent(list, '  SELECT 5\n');
    assert.strictEqual(list[0], 'SELECT 5');
    assert.strictEqual(list.filter((sql) => sql === 'SELECT 5').length, 1);
    assert.deepStrictEqual(plain(sqlite.sqlitePushRecent(['a'], '   ')), ['a']);
});

test('a refused statement shows the error envelope: status, type, code, and message', () => {
    const html = sqlite.sqliteErrorHtml({
        status: 400,
        envelope: { error: 'bad_request', message: 'BEGIN would leave a transaction open', code: 'statement_refused', field: 'sql' },
    });
    assert.match(html, /400 bad_request · statement_refused/);
    assert.match(html, /BEGIN would leave a transaction open/);
    assert.strictEqual(sqlite.sqliteErrorText({ status: 0, envelope: null }), 'The app did not answer. Is it in the foreground? Try again.');
    assert.strictEqual(sqlite.sqliteErrorText({ status: 502, envelope: null }), 'HTTP 502');
});

test('the error envelope is escaped', () => {
    const html = sqlite.sqliteErrorHtml({ status: 400, envelope: { error: 'bad_request', message: 'near "<b>": syntax error' } });
    assert.ok(!html.includes('<b>'));
    assert.match(html, /near &quot;&lt;b&gt;&quot;: syntax error/);
});

// ── Naming a row ────────────────────────────────────────────────

test('a row of a table with a rowid is named by it, as a string that keeps every digit', () => {
    const [synced] = notesRows();
    assert.deepStrictEqual(plain(sqlite.sqliteRowTarget(synced, notesSchema())), { rowid: '9007199254740993' });
    assert.strictEqual(sqlite.sqliteWhereSql(synced, notesSchema()), 'rowid = 9007199254740993');
});

test('a row of a table WITHOUT ROWID is named by its primary key, in the key\'s order', () => {
    const row = tagRow({ type: 'text', value: 'it\'s' });
    assert.deepStrictEqual(plain(sqlite.sqliteRowTarget(row, tagsSchema)), { key: { note_id: 1, tag: 'it\'s' } });
    assert.strictEqual(sqlite.sqliteWhereSql(row, tagsSchema), '"note_id" IS 1 AND "tag" IS \'it\'\'s\'');
    assert.strictEqual(sqlite.sqliteTargetLabel(sqlite.sqliteRowTarget(row, tagsSchema)), 'note_id = 1, tag = \'it\'s\'');
});

test('a key that holds a blob or a cut text can\'t name its row', () => {
    assert.strictEqual(sqlite.sqliteRowTarget(tagRow({ type: 'blob', bytes: 4 }), tagsSchema), null);
    assert.strictEqual(sqlite.sqliteWhereSql(tagRow({ type: 'blob', bytes: 4 }), tagsSchema), null);
    const cut = { type: 'text', value: 'x'.repeat(4096), truncated: true, length: 5000 };
    assert.strictEqual(sqlite.sqliteRowTarget(tagRow(cut), tagsSchema), null);
});

test('a row of a view has neither, and nothing can name it', () => {
    const view = { database: 'app', table: 'recent_notes', type: 'view', hasRowId: false, columns: [{ name: 'id', type: 'INTEGER', primaryKey: 0 }] };
    const row = { cells: { id: { type: 'integer', value: 2 } } };
    assert.strictEqual(sqlite.sqliteRowTarget(row, view), null);
    assert.strictEqual(sqlite.sqliteWholeValueSql('recent_notes', ['id'], row, view), null);
});

test('the whole value of a cut text is read again with the row\'s rowid, under its alias', () => {
    const trip = notesRows()[2];
    assert.strictEqual(trip.cells.body.truncated, true);
    assert.strictEqual(sqlite.sqliteWholeValueSql('notes', ['body'], trip, notesSchema()), 'SELECT "body" FROM "notes" WHERE rowid = 2');

    // A table with a column named rowid: SQLite's rowid is _rowid_ there.
    const schema = { hasRowId: true, type: 'table', columns: [{ name: 'rowid', primaryKey: 0 }, { name: 'b"ody', primaryKey: 0 }] };
    assert.strictEqual(sqlite.sqliteWholeValueSql('my "t"', ['b"ody'], { rowid: '7', cells: {} }, schema), 'SELECT "b""ody" FROM "my ""t""" WHERE _rowid_ = 7');
});

test('a literal keeps a big integer\'s digits, quotes a text, and refuses what the page does not hold', () => {
    assert.strictEqual(sqlite.sqliteLiteral({ type: 'integer', value: '9007199254740993' }), '9007199254740993');
    assert.strictEqual(sqlite.sqliteLiteral({ type: 'real', value: 0.25 }), '0.25');
    assert.strictEqual(sqlite.sqliteLiteral({ type: 'real', value: '-Infinity' }), '-9e999');
    assert.strictEqual(sqlite.sqliteLiteral({ type: 'null' }), 'NULL');
    assert.strictEqual(sqlite.sqliteLiteral({ type: 'text', value: 'a\'b' }), '\'a\'\'b\'');
    assert.strictEqual(sqlite.sqliteLiteral({ type: 'blob', bytes: 4 }), null);
    assert.strictEqual(sqlite.sqliteLiteral({ type: 'integer', value: '1 OR 1=1' }), null);
});

// ── The row editor ──────────────────────────────────────────────

test('an edit sends only the changed values, and NULL as null', () => {
    const [synced] = notesRows();
    const field = (column, text, extra) => Object.assign({ column, original: synced.cells[column], whole: null, text, isNull: false, editable: true }, extra);

    const values = sqlite.sqliteEditChanges([
        field('title', 'Synced'),
        field('id', '9007199254740993'),
        field('score', '0.5'),
        field('created_at', '1792419770040', { isNull: true }),
        field('body', 'now a body'),
        field('folder_id', '', { isNull: true }),
    ]);

    assert.deepStrictEqual(plain(values), { score: 0.5, created_at: null, body: 'now a body' });
});

test('a number goes back as a number, but an integer past 2^53 as its digits', () => {
    const integer = { type: 'integer', value: 3 };
    assert.strictEqual(sqlite.sqliteEditValue('42', integer), 42);
    assert.strictEqual(sqlite.sqliteEditValue('9007199254740995', integer), '9007199254740995');
    assert.strictEqual(sqlite.sqliteEditValue('4.5', integer), '4.5');
    assert.strictEqual(sqlite.sqliteEditValue('1e3', { type: 'real', value: 1 }), 1000);
    assert.strictEqual(sqlite.sqliteEditValue('12', { type: 'text', value: 'x' }), '12');
    assert.strictEqual(sqlite.sqliteEditValue('12', { type: 'null' }), '12');
});

test('a cut text compares with its whole value, so an untouched one is not sent', () => {
    const trip = notesRows()[2];
    const whole = trip.cells.body.value + 'y'.repeat(trip.cells.body.length - trip.cells.body.value.length);
    const field = { column: 'body', original: trip.cells.body, whole, text: whole, isNull: false, editable: true };
    assert.deepStrictEqual(plain(sqlite.sqliteEditChanges([field])), {});
    assert.deepStrictEqual(plain(sqlite.sqliteEditChanges([Object.assign({}, field, { text: 'short' })])), { body: 'short' });
});

test('a field that can\'t change sends nothing, whatever it holds', () => {
    const values = sqlite.sqliteEditChanges([{ column: 'body', original: { type: 'blob', bytes: 4 }, whole: null, text: 'x', isNull: true, editable: false }]);
    assert.deepStrictEqual(plain(values), {});
});

test('a cut text waits for its whole value before it can change, and a blob never can', () => {
    const trip = notesRows()[2];
    const loading = sqlite.sqliteFieldHtml({ column: 'body', info: { type: 'TEXT' }, original: trip.cells.body, whole: null }, 3, false);
    assert.match(loading, / readonly/);
    assert.match(loading, /Reading the whole value, 4,377 characters/);
    assert.ok(!loading.includes('data-action="copyField"'));

    const loaded = sqlite.sqliteFieldHtml({ column: 'body', info: { type: 'TEXT' }, original: trip.cells.body, whole: 'whole' }, 3, false);
    assert.ok(!loaded.includes(' readonly'));
    assert.match(loaded, /data-action="copyField"/);
    assert.match(loaded, /data-action="toggleNull"/);

    const blob = sqlite.sqliteFieldHtml({ column: 'attachment', info: { type: 'BLOB' }, original: trip.cells.attachment, whole: null }, 4, false);
    assert.ok(!blob.includes('data-field-input'));
    assert.ok(!blob.includes('toggleNull'));
    assert.match(blob, /x&#39;00ff&#39;/);
});

test('a read-only row has no NULL toggles, and its fields are read-only', () => {
    const [synced] = notesRows();
    const html = sqlite.sqliteFieldHtml({ column: 'title', info: { type: 'TEXT', notNull: true }, original: synced.cells.title, whole: null }, 2, true);
    assert.match(html, / readonly/);
    assert.ok(!html.includes('toggleNull'));
    assert.match(html, /data-action="copyField"/);
});

test('a row can change only in a writable table, and the reason says why not', () => {
    const [synced] = notesRows();
    assert.strictEqual(sqlite.sqliteRowLockReason(database('app'), notesSchema(), synced), null);
    assert.match(sqlite.sqliteRowLockReason(database('catalog'), notesSchema(), synced), /as a file/);
    assert.match(sqlite.sqliteRowLockReason(database('sdk_cache.db'), notesSchema(), synced), /found this database on disk/);
    assert.match(sqlite.sqliteDbLockReason(Object.assign({}, database('app'), { source: 'framework', writable: false })), /without writable = true/);

    const view = Object.assign({}, notesSchema(), { type: 'view', hasRowId: false });
    assert.match(sqlite.sqliteRowLockReason(database('app'), view, { cells: {} }), /view/);
    assert.match(sqlite.sqliteRowLockReason(database('app'), tagsSchema, tagRow({ type: 'blob', bytes: 1 })), /primary key holds a blob/);
});

// ── Cells and lists ─────────────────────────────────────────────

test('each cell says its own type: a number, NULL, a blob\'s size, and a cut text\'s length', () => {
    const trip = notesRows()[2];
    const columns = sqlite.sqliteGridColumns(notesSchema());
    const html = sqlite.sqliteRowHtml(trip, 2, columns, { actions: false });

    assert.match(html, /<td class="dc-cell dc-cell--muted" data-col="0">2<\/td>/);
    assert.match(html, /<td class="dc-cell dc-cell--num" data-col="1">2<\/td>/);
    assert.match(html, /<td class="dc-cell dc-cell--null" data-col="5">NULL<\/td>/);
    assert.match(html, /BLOB · 20.0 KB/);
    assert.match(html, /class="dc-cell sqlite-cell--cut"/);
    assert.match(html, /4,377 chars/);
    assert.ok(!html.includes('deleteRow'));
});

test('a blob in a TEXT column is a blob, and a cell is escaped', () => {
    const imported = notesRows()[1];
    assert.strictEqual(imported.cells.body.type, 'blob');
    assert.match(sqlite.sqliteCellView(imported.cells.body).html, /BLOB · 4 B/);
    assert.strictEqual(sqlite.sqliteCellView({ type: 'text', value: '<img src=x onerror=alert(1)>' }).html, '&lt;img src=x onerror=alert(1)&gt;');
    assert.strictEqual(sqlite.sqliteCellText({ type: 'blob', bytes: 4 }), null);
    assert.strictEqual(sqlite.sqliteCellText({ type: 'integer', value: '9007199254740993' }), '9007199254740993');
});

test('a row that can\'t be named has its Delete off, with the reason', () => {
    const columns = sqlite.sqliteGridColumns(tagsSchema);
    const html = sqlite.sqliteRowHtml(tagRow({ type: 'blob', bytes: 1 }), 0, columns, { actions: true, lock: 'The row\'s primary key holds a blob.' });
    assert.match(html, /data-action="deleteRow" data-row="0" disabled title="The row&#39;s primary key holds a blob."/);
});

test('the headers mark the sorted column with its direction, and escape the names', () => {
    const columns = [{ name: 'a<b' }, { name: 'score', type: 'REAL' }];
    const html = sqlite.sqliteHeadHtml(columns, { hidden: [], widths: { 'a<b': 200 } }, { column: 'score', dir: 'desc' }, true);
    assert.match(html, /<col style="width:200px"><col style="width:110px"><col style="width:92px">/);
    assert.match(html, /a&lt;b/);
    assert.match(html, /class="dc-th dc-th--sortable dc-th--sorted" data-col="1"[^>]*aria-sort="descending"/);
    assert.match(html, /<span class="dc-th__arrow">▼<\/span>/);
    assert.match(html, /sqlite-th--actions/);
});

test('the sidebar lists the pinned tables first, and hides the internal ones until asked', () => {
    const tables = golden('sqlite-tables.json').items;
    const names = (list) => list.map((table) => table.name);

    assert.deepStrictEqual(plain(names(sqlite.sqliteShownTables(tables, '', false, []))), ['folders', 'note_tags', 'notes', 'recent_notes']);
    assert.deepStrictEqual(plain(names(sqlite.sqliteShownTables(tables, '', false, ['recent_notes', 'gone']))), ['recent_notes', 'folders', 'note_tags', 'notes']);
    assert.deepStrictEqual(plain(names(sqlite.sqliteShownTables(tables, 'NOTE', false, []))), ['note_tags', 'notes', 'recent_notes']);
    assert.strictEqual(sqlite.sqliteShownTables(tables, '', true, []).length, tables.length);
});

test('a table\'s item has the hint in its tooltip, a view tag, and a pin', () => {
    const tables = golden('sqlite-tables.json').items;
    const notes = sqlite.sqliteTableItemHtml(tables.find((table) => table.name === 'notes'), 2, true, true);
    assert.match(notes, /dc-listitem--active/);
    assert.match(notes, /NoteEntity: A note that the user wrote, in a folder\./);
    assert.match(notes, /class="dc-star sqlite-star dc-star--on"/);
    assert.match(notes, /<span class="dc-listitem__count">4<\/span>/);

    const view = sqlite.sqliteTableItemHtml(tables.find((table) => table.name === 'recent_notes'), 3, false, false);
    assert.match(view, />View<\/span>/);
    assert.ok(!view.includes('dc-listitem__count'));
});

test('a database\'s item has its writable or read-only tag and its schema version', () => {
    const app = sqlite.sqliteDbItemHtml(database('app'), 0, true);
    assert.match(app, />Writable<\/span>/);
    assert.match(app, />v3<\/span>/);
    assert.match(app, /SupportSQLite · 56\.0 KB/);
    const found = sqlite.sqliteDbItemHtml(database('sdk_cache.db'), 2, false);
    assert.match(found, />Read-only<\/span>/);
    assert.match(found, /found on disk · 16\.0 KB/);

    const broken = sqlite.sqliteDbItemHtml(Object.assign({}, database('catalog'), { error: 'file is not a database' }), 1, false);
    assert.match(broken, /aria-disabled="true"/);
    assert.match(broken, />Error<\/span>/);
});

test('the settings read what the page stored, and ignore what it did not', () => {
    const settings = sqlite.sqliteReadSettings(storageOf({
        'debug-sqlite': JSON.stringify({
            db: 'app', lastTable: { app: 'notes', bad: 3 }, view: 'sql', showInternal: true, autoRefresh: true,
            pins: { app: ['notes', 7], other: 'x' }, recent: ['SELECT 1', 2, 'SELECT 3'],
        }),
    }));
    assert.deepStrictEqual(plain(settings), {
        db: 'app', lastTable: { app: 'notes' }, view: 'sql', showInternal: true, autoRefresh: true, foundOpen: false,
        pins: { app: ['notes'] }, recent: ['SELECT 1', 'SELECT 3'],
    });

    const empty = sqlite.sqliteReadSettings(storageOf({ 'debug-sqlite': '{not json' }));
    assert.deepStrictEqual(plain(empty), {
        db: null, lastTable: {}, view: 'rows', showInternal: false, autoRefresh: false, foundOpen: false, pins: {}, recent: [],
    });
});

test('before the schema comes, no row is named again: the page does not know the rowid\'s name', () => {
    const [synced] = notesRows();
    assert.strictEqual(sqlite.sqliteWhereSql(synced, null), null);
    assert.strictEqual(sqlite.sqliteWholeValueSql('notes', ['body'], synced, null), null);
});

test('an untouched text compares with what its field showed, so a CR LF or a first line break is not rewritten', () => {
    const field = (value, initial, text) => ({ column: 'body', original: { type: 'text', value }, whole: null, initial, text, isNull: false, editable: true });
    // A text field turns CR LF into LF; the HTML parser would also drop a text area's first line break.
    assert.deepStrictEqual(plain(sqlite.sqliteEditChanges([field('a\r\nb', 'a\nb', 'a\nb')])), {});
    assert.deepStrictEqual(plain(sqlite.sqliteEditChanges([field('\nhello', '\nhello', '\nhello')])), {});
    assert.deepStrictEqual(plain(sqlite.sqliteEditChanges([field('a\r\nb', 'a\nb', 'a\nc')])), { body: 'a\nc' });
});
