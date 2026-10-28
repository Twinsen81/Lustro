// The SQLite tab: the app's SQLite and Room databases, their tables, and pages
// of rows, with a console for the client's own statements. GET rows gives only
// the cursor of the next page, so the page keeps the cursors of the pages
// before for Previous. A statement goes to POST query, on a connection that
// SQLite opens read-only, unless the user asks for a write, which goes to
// POST exec. sqlite.openapi.json describes the routes.
(function() {
    var STORAGE_KEY = 'debug-sqlite';
    var COLUMNS_KEY = 'debug-sqlite-columns';
    var PAGE_LIMIT = 50;
    var MAX_RECENT = 10;
    var AUTO_REFRESH_MS = 2000;
    var FILTER_DEBOUNCE_MS = 300;
    var MIN_WIDTH = 48;
    var ACTIONS_WIDTH = 92;
    // A text in the results table shows this much; a click opens all of it.
    var PREVIEW_CHARS = 300;
    // A longer value opens as plain text: the folding tree of a large JSON text is slow to draw.
    var JSON_TREE_MAX_CHARS = 1000000;
    // The names that SQLite gives the rowid, in the order the routes try them:
    // the first one that no column has.
    var ROWID_ALIASES = ['rowid', '_rowid_', 'oid'];
    var DECIMAL = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;
    var WHOLE = /^-?\d+$/;

    function esc(text) {
        return window.debugEscapeHtml(text);
    }

    function count(n) {
        return Number(n).toLocaleString('en-US');
    }

    function plural(n, one, many) {
        return count(n) + ' ' + (Number(n) === 1 ? one : many);
    }

    function bytesLabel(bytes) {
        if (bytes == null) return '?';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    }

    function tag(text, title, color, cls) {
        return '<span class="dc-tag' + (cls ? ' ' + cls : '') + '"' + (color ? ' style="--c: var(' + color + ')"' : '')
            + (title ? ' title="' + esc(title) + '"' : '') + '>' + esc(text) + '</span>';
    }

    // ── SQL text that the page builds ───────────────────────────

    function quote(name) {
        return '"' + String(name).replace(/"/g, '""') + '"';
    }

    // A cell as a SQL literal, or null when the page can't write it: a blob is
    // only its size, and a cut text only its start.
    function literal(cell) {
        if (!cell || cell.type === 'null') return 'NULL';
        if (cell.type === 'integer') return WHOLE.test(String(cell.value)) ? String(cell.value) : null;
        if (cell.type === 'real') {
            if (cell.value === 'Infinity') return '9e999';
            if (cell.value === '-Infinity') return '-9e999';
            return typeof cell.value === 'number' && isFinite(cell.value) ? String(cell.value) : null;
        }
        if (cell.type === 'text' && !cell.truncated) return '\'' + String(cell.value).replace(/'/g, '\'\'') + '\'';
        return null;
    }

    function rowidAlias(names) {
        var lower = names.map(function(name) { return String(name).toLowerCase(); });
        for (var i = 0; i < ROWID_ALIASES.length; i++) {
            if (lower.indexOf(ROWID_ALIASES[i]) < 0) return ROWID_ALIASES[i];
        }
        return null;
    }

    function columnNames(schema) {
        return schema && Array.isArray(schema.columns) ? schema.columns.map(function(column) { return column.name; }) : [];
    }

    function primaryKey(schema) {
        if (!schema || !Array.isArray(schema.columns)) return [];
        return schema.columns.filter(function(column) { return column.primaryKey > 0; })
            .sort(function(a, b) { return a.primaryKey - b.primaryKey; })
            .map(function(column) { return column.name; });
    }

    // The grid's columns: the rowid first, when the table has one, under the
    // name that SQLite gives it here, and then the schema's. Without a schema,
    // the cells of the first row name them.
    function gridColumns(schema, firstRow) {
        var names = columnNames(schema);
        if (!names.length && firstRow && firstRow.cells) names = Object.keys(firstRow.cells);
        var types = {};
        if (schema && Array.isArray(schema.columns)) schema.columns.forEach(function(column) { types[column.name] = column.type; });
        var columns = names.map(function(name) { return { name: name, type: types[name] || '' }; });
        var hasRowId = schema ? schema.hasRowId : !!(firstRow && firstRow.rowid != null);
        var alias = hasRowId ? rowidAlias(names) : null;
        if (alias) columns.unshift({ name: alias, rowid: true, type: 'INTEGER' });
        return columns;
    }

    function cellJson(cell) {
        if (!cell || cell.type === 'null') return null;
        return cell.value;
    }

    // How POST edit and POST delete name the row: by its rowid, or by its
    // primary key. Null when the row has neither, or its key holds a value that
    // the page can't send back.
    function rowTarget(row, schema) {
        if (!row) return null;
        if (row.rowid != null) return { rowid: String(row.rowid) };
        var key = primaryKey(schema);
        if (!key.length) return null;
        var out = {};
        for (var i = 0; i < key.length; i++) {
            var cell = row.cells ? row.cells[key[i]] : null;
            if (!cell || cell.type === 'blob' || cell.truncated) return null;
            out[key[i]] = cellJson(cell);
        }
        return { key: out };
    }

    // The WHERE that finds the row again in POST query, or null when it can't.
    // Without the schema, the page does not know which name the rowid has.
    function whereSql(row, schema) {
        if (!row || !schema) return null;
        if (row.rowid != null) {
            var alias = rowidAlias(columnNames(schema));
            return alias && WHOLE.test(String(row.rowid)) ? alias + ' = ' + row.rowid : null;
        }
        var key = primaryKey(schema);
        if (!key.length) return null;
        var parts = [];
        for (var i = 0; i < key.length; i++) {
            var value = literal(row.cells ? row.cells[key[i]] : null);
            if (value == null) return null;
            // IS, as POST edit names a key: a NULL in a key column matches a NULL.
            parts.push(quote(key[i]) + ' IS ' + value);
        }
        return parts.join(' AND ');
    }

    // The statement that reads the whole values of columns of one row, which a
    // page of rows cuts at 4096 characters.
    function wholeValueSql(table, columns, row, schema) {
        var where = whereSql(row, schema);
        if (!where || !columns.length) return null;
        return 'SELECT ' + columns.map(quote).join(', ') + ' FROM ' + quote(table) + ' WHERE ' + where;
    }

    function targetLabel(target) {
        if (!target) return '';
        if (target.rowid != null) return 'rowid ' + target.rowid;
        return Object.keys(target.key).map(function(name) {
            var value = target.key[name];
            var text = value === null ? 'NULL' : typeof value === 'string' ? '\'' + value + '\'' : String(value);
            return name + ' = ' + text;
        }).join(', ');
    }

    // ── Paging ──────────────────────────────────────────────────

    // GET rows gives the cursor of the next page only, so Previous takes the
    // cursor of the page before from a stack.
    function newPaging() {
        return { cursor: null, stack: [], next: null };
    }

    function pageForward(paging) {
        if (paging.next == null) return false;
        paging.stack.push(paging.cursor);
        paging.cursor = paging.next;
        paging.next = null;
        return true;
    }

    function pageBack(paging) {
        if (!paging.stack.length) return false;
        paging.cursor = paging.stack.pop();
        paging.next = null;
        return true;
    }

    function pageReset(paging) {
        paging.cursor = null;
        paging.stack = [];
        paging.next = null;
    }

    // Each page before this one held a whole page of rows.
    function pageRange(paging, shown, limit) {
        if (!shown) return null;
        var first = paging.stack.length * limit + 1;
        return { first: first, last: first + shown - 1 };
    }

    function rangeLabel(paging, shown, limit, total, filtered) {
        var range = pageRange(paging, shown, limit);
        if (!range) {
            if (paging.stack.length) return 'no rows on this page';
            return filtered ? 'no row matches' : 'no rows';
        }
        var text = 'rows ' + count(range.first) + ' to ' + count(range.last);
        if (!filtered && total != null) return text + ' of ' + count(total);
        return paging.next != null ? text + ' · more' : text;
    }

    function nextSort(sort, column) {
        if (!sort || sort.column !== column) return { column: column, dir: 'asc' };
        if (sort.dir === 'asc') return { column: column, dir: 'desc' };
        return null;
    }

    function rowsPath(q) {
        var parts = ['db=' + encodeURIComponent(q.db), 'table=' + encodeURIComponent(q.table), 'limit=' + (q.limit || PAGE_LIMIT)];
        if (q.cursor) parts.push('cursor=' + encodeURIComponent(q.cursor));
        if (q.filter) parts.push('filter=' + encodeURIComponent(q.filter));
        if (q.sort) parts.push('sort=' + encodeURIComponent(q.sort.column), 'dir=' + q.sort.dir);
        return 'rows?' + parts.join('&');
    }

    // ── The columns of each table ───────────────────────────────

    // The hidden columns and the widths, for each database and table.
    function columnsKey(db, table) {
        return JSON.stringify([db, table]);
    }

    function columnState(all, db, table) {
        var saved = all ? all[columnsKey(db, table)] : null;
        return {
            hidden: saved && Array.isArray(saved.hidden) ? saved.hidden.filter(function(name) { return typeof name === 'string'; }) : [],
            widths: saved && saved.widths && typeof saved.widths === 'object' ? Object.assign({}, saved.widths) : {}
        };
    }

    function putColumnState(all, db, table, state) {
        var key = columnsKey(db, table);
        if (!state.hidden.length && !Object.keys(state.widths).length) delete all[key];
        else all[key] = { hidden: state.hidden.slice(), widths: Object.assign({}, state.widths) };
        return all;
    }

    // A state with every column hidden, as after the table's columns changed,
    // shows them all.
    function visibleColumns(columns, state) {
        var shown = columns.filter(function(column) { return state.hidden.indexOf(column.name) < 0; });
        return shown.length ? shown : columns.slice();
    }

    // The last column that shows stays.
    function canHide(columns, state, name) {
        var shown = visibleColumns(columns, state);
        return shown.length > 1 && shown.some(function(column) { return column.name === name; });
    }

    function setHidden(columns, state, name, hidden) {
        var at = state.hidden.indexOf(name);
        if (hidden) {
            if (at >= 0 || !canHide(columns, state, name)) return false;
            state.hidden.push(name);
            return true;
        }
        if (at < 0) return false;
        state.hidden.splice(at, 1);
        return true;
    }

    function defaultWidth(column) {
        if (column.rowid) return 90;
        var type = String(column.type || '').toUpperCase();
        if (type.indexOf('INT') >= 0) return 120;
        if (/REAL|FLOA|DOUB|NUM|DEC|BOOL/.test(type)) return 110;
        if (/CHAR|CLOB|TEXT/.test(type)) return 240;
        if (type.indexOf('BLOB') >= 0) return 130;
        return 160;
    }

    function columnWidth(state, column) {
        var width = state.widths[column.name];
        return typeof width === 'number' && width >= MIN_WIDTH ? width : defaultWidth(column);
    }

    // ── Cells ───────────────────────────────────────────────────

    // A cell as text, or null for a NULL and a blob.
    function cellText(cell) {
        if (!cell || cell.type === 'null' || cell.type === 'blob') return null;
        return String(cell.value);
    }

    // A cell in the grid: its class and its HTML. A cut text has a tag with its
    // length, and a blob is a tag with its size.
    function cellView(cell, previewChars) {
        if (!cell || cell.type === 'null') return { cls: 'dc-cell--null', html: 'NULL' };
        if (cell.type === 'integer' || cell.type === 'real') return { cls: 'dc-cell--num', html: esc(String(cell.value)) };
        if (cell.type === 'blob') {
            return { cls: '', html: tag('BLOB · ' + bytesLabel(cell.bytes), 'A blob of ' + plural(cell.bytes, 'byte', 'bytes') + ': the tab reads only its size.', null, 'sqlite-tag--blob') };
        }
        var text = String(cell.value);
        if (cell.truncated) {
            return {
                cls: 'sqlite-cell--cut',
                html: tag(count(cell.length) + ' chars', 'The page holds the first ' + count(text.length) + ' of ' + count(cell.length)
                    + ' characters. Click to read the whole value.', '--accent-2', 'sqlite-tag--cut') + esc(text.slice(0, PREVIEW_CHARS))
            };
        }
        if (previewChars && text.length > previewChars) {
            return { cls: 'sqlite-cell--long', html: esc(text.slice(0, previewChars)) + '…' };
        }
        return { cls: '', html: esc(text) };
    }

    function headHtml(columns, state, sort, actions) {
        var cols = columns.map(function(column) {
            return '<col style="width:' + columnWidth(state, column) + 'px">';
        }).join('') + (actions ? '<col style="width:' + ACTIONS_WIDTH + 'px">' : '');
        var cells = columns.map(function(column, index) {
            var sorted = sort && sort.column === column.name;
            var arrow = sorted ? (sort.dir === 'asc' ? '▲' : '▼') : '';
            var title = (column.rowid ? 'The rowid of the row. ' : column.type ? column.type + '. ' : '')
                + 'Click to sort: ascending, descending, then the table\'s order. Right-click to hide the column.';
            return '<th class="dc-th dc-th--sortable' + (sorted ? ' dc-th--sorted' : '') + (column.rowid ? ' sqlite-th--rowid' : '') + '"'
                + ' data-col="' + index + '" title="' + esc(title) + '"'
                + ' aria-sort="' + (sorted ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none') + '">'
                + '<span class="sqlite-th"><span class="sqlite-th__name">' + esc(column.name) + '</span>'
                + '<span class="dc-th__arrow">' + arrow + '</span></span>'
                + '<span class="sqlite-resize" data-col-resize="' + index + '" title="Drag to resize. Double-click for the default width."></span></th>';
        }).join('');
        if (actions) cells += '<th class="dc-th sqlite-th--actions"><span class="sqlite-th">Actions</span></th>';
        return '<colgroup>' + cols + '</colgroup><thead class="dc-thead"><tr>' + cells + '</tr></thead>';
    }

    // One row of the grid. ctx: { actions, lock } where lock says why the row
    // can't be deleted, or is null.
    function rowHtml(row, index, columns, ctx) {
        var cells = columns.map(function(column, col) {
            if (column.rowid) {
                return '<td class="dc-cell dc-cell--muted" data-col="' + col + '">' + (row.rowid != null ? esc(String(row.rowid)) : '') + '</td>';
            }
            var view = cellView(row.cells ? row.cells[column.name] : null);
            return '<td class="dc-cell' + (view.cls ? ' ' + view.cls : '') + '" data-col="' + col + '">' + view.html + '</td>';
        }).join('');
        if (ctx && ctx.actions) {
            cells += '<td class="dc-cell sqlite-cell--actions"><button type="button" class="dc-btn dc-btn--sm dc-btn--danger" data-action="deleteRow" data-row="' + index + '"'
                + (ctx.lock ? ' disabled title="' + esc(ctx.lock) + '"' : ' title="Delete this row. The page asks first."') + '>Delete</button></td>';
        }
        return '<tr class="dc-row sqlite-row" data-row="' + index + '">' + cells + '</tr>';
    }

    // ── Databases and tables ────────────────────────────────────

    var SOURCE_LABELS = { support: 'SupportSQLite', framework: 'SQLiteDatabase', file: 'file' };
    var SOURCE_TITLES = {
        support: 'The app\'s SupportSQLiteDatabase, such as Room\'s: the tab reads through the app\'s handle.',
        framework: 'The app\'s SQLiteDatabase: the tab reads through the app\'s handle.',
        file: 'A file that the tab opens read-only for each request.'
    };

    // Why the page can't write to the database, or null when it can. The routes
    // refuse a write to it anyway; the page only shows it.
    function dbLockReason(db) {
        if (!db) return 'No database is open.';
        if (db.writable) return null;
        if (!db.registered) return 'The tab found this database on disk. A found database is never writable.';
        if (db.source === 'file') return 'The app registers this database as a file, which the tab opens read-only. A file is never writable: register the app\'s handle with writable = true to change it.';
        return 'The app registers this database without writable = true, so the tab only reads it.';
    }

    function rowLockReason(db, schema, row) {
        var lock = dbLockReason(db);
        if (lock) return lock;
        if (schema && schema.type === 'view') return 'A view\'s rows don\'t change: change the rows of its tables.';
        if (row && !rowTarget(row, schema)) {
            return row.rowid == null && !primaryKey(schema).length
                ? 'The table has no rowid and no primary key, so the tab can\'t name one row.'
                : 'The row\'s primary key holds a blob or a long text, which the tab can\'t send back to name the row.';
        }
        return null;
    }

    function dbMeta(db) {
        if (db.error) return db.error;
        var parts = [db.registered ? SOURCE_LABELS[db.source] || db.source : 'found on disk'];
        parts.push(db.path == null ? 'in memory' : bytesLabel(db.sizeBytes));
        return parts.join(' · ');
    }

    function dbTitle(db) {
        var lines = [db.name];
        if (db.path) lines.push(db.path);
        lines.push(SOURCE_TITLES[db.source] || db.source);
        if (!db.registered) lines.push('The tab found it in the app\'s databases directory.');
        if (db.schemaVersion != null) lines.push('PRAGMA user_version: ' + db.schemaVersion + '. Room and SQLiteOpenHelper set it to the schema\'s version.');
        lines.push(dbLockReason(db) || 'Writable: the tab may change its rows.');
        if (db.error) lines.push('It does not open: ' + db.error);
        return lines.join('\n');
    }

    function dbItemHtml(db, index, active) {
        var tags = db.error
            ? tag('Error', db.error, '--danger')
            : (db.writable ? tag('Writable', 'The tab may change its rows.', '--live') : tag('Read-only', dbLockReason(db), '--lvl-w'))
                + (db.schemaVersion != null ? tag('v' + db.schemaVersion, 'PRAGMA user_version: ' + db.schemaVersion + '.') : '');
        return '<button type="button" class="dc-listitem sqlite-db' + (active ? ' dc-listitem--active' : '') + (db.error ? ' sqlite-db--error' : '') + '"'
            + ' role="option" aria-selected="' + (active ? 'true' : 'false') + '"' + (db.error ? ' aria-disabled="true"' : '')
            + ' data-action="selectDb" data-db="' + index + '" title="' + esc(dbTitle(db)) + '">'
            + '<span class="sqlite-db__line"><span class="dc-listitem__name">' + esc(db.name) + '</span>' + tags + '</span>'
            + '<span class="sqlite-db__meta">' + esc(dbMeta(db)) + '</span></button>';
    }

    // The tables that the sidebar lists: the pinned ones first, then the rest,
    // each in the routes' order, which is by name.
    function shownTables(tables, filter, showInternal, pinned) {
        var q = String(filter || '').toLowerCase();
        var list = tables.filter(function(table) {
            return (showInternal || !table.internal) && (!q || table.name.toLowerCase().indexOf(q) >= 0);
        });
        var pins = pinned || [];
        return list.filter(function(table) { return pins.indexOf(table.name) >= 0; })
            .concat(list.filter(function(table) { return pins.indexOf(table.name) < 0; }));
    }

    function tableTitle(table) {
        var lines = [table.name];
        if (table.entity || table.purpose) lines.push([table.entity, table.purpose].filter(Boolean).join(': '));
        if (table.type === 'view') lines.push('A view: the tab does not count its rows, which runs its query.');
        else if (table.rowCount != null) lines.push(plural(table.rowCount, 'row', 'rows'));
        if (table.hasRowId === false && table.type !== 'view') lines.push('WITHOUT ROWID: the tab names a row by its primary key.');
        if (table.internal) lines.push('SQLite\'s or Room\'s own table.');
        if (table.error) lines.push('It does not read: ' + table.error);
        return lines.join('\n');
    }

    function tableItemHtml(table, index, active, pinned) {
        var end = table.error
            ? tag('Error', table.error, '--danger')
            : table.type === 'view'
                ? tag('View', 'A view', '--cat-config')
                : '<span class="dc-listitem__count' + (table.rowCount ? '' : ' dc-listitem__count--zero') + '">' + esc(count(table.rowCount || 0)) + '</span>';
        return '<div class="dc-listitem sqlite-table' + (active ? ' dc-listitem--active' : '') + (table.internal ? ' sqlite-table--internal' : '') + '"'
            + ' role="option" tabindex="0" aria-selected="' + (active ? 'true' : 'false') + '" data-action="selectTable" data-table="' + index + '"'
            + ' title="' + esc(tableTitle(table)) + '">'
            + '<button type="button" class="dc-star sqlite-star' + (pinned ? ' dc-star--on' : '') + '" data-action="togglePin" data-table="' + index + '"'
            + ' aria-pressed="' + (pinned ? 'true' : 'false') + '" aria-label="Pin ' + esc(table.name) + '"'
            + ' title="' + (pinned ? 'Unpin: the table goes back to its place.' : 'Pin to the top of this database\'s tables.') + '">★</button>'
            + '<span class="dc-listitem__name">' + esc(table.name) + '</span>' + end + '</div>';
    }

    // ── The console ─────────────────────────────────────────────

    // Where a statement goes: POST query for a read, which SQLite runs on a
    // read-only connection for every database, and POST exec for a write, only
    // to a writable database. { error } when it goes nowhere.
    function consoleRequest(mode, db, sql) {
        if (!db || db.error) return { error: 'Open a database first.' };
        if (!sql || !String(sql).trim()) return { error: 'Type a statement first.' };
        var body = { db: db.name, sql: String(sql) };
        if (mode !== 'write') return { route: 'query', body: body };
        var lock = dbLockReason(db);
        return lock ? { error: lock } : { route: 'exec', body: body };
    }

    function pushRecent(list, sql) {
        var text = String(sql || '').trim();
        var rest = (list || []).filter(function(item) { return item !== text; });
        return (text ? [text].concat(rest) : rest).slice(0, MAX_RECENT);
    }

    // ── The row editor ──────────────────────────────────────────

    // A value as POST edit binds it. The column's affinity converts a text, but
    // a column with none keeps one, so a number that was a number goes back as a
    // number: an integer past 2^53 as its digits, which a JS number would change.
    function editValue(text, original) {
        if (original && original.type === 'integer' && WHOLE.test(text)) {
            var whole = Number(text);
            return Number.isSafeInteger(whole) ? whole : text;
        }
        if (original && original.type === 'real' && DECIMAL.test(text) && isFinite(Number(text))) return Number(text);
        return text;
    }

    // The values that an edit changes. Each field: { column, original, whole,
    // initial, text, isNull, editable }, where whole is the whole text of a cut
    // cell, and initial what the field showed when it opened: a text field
    // turns a line break CR LF into LF, so an untouched text compares with that.
    function editChanges(fields) {
        var values = {};
        fields.forEach(function(field) {
            if (!field.editable) return;
            var wasNull = !field.original || field.original.type === 'null';
            if (field.isNull) {
                if (!wasNull) values[field.column] = null;
                return;
            }
            var before = field.initial != null ? field.initial : field.whole != null ? field.whole : cellText(field.original);
            if (wasNull || field.text !== before) values[field.column] = editValue(field.text, field.original);
        });
        return values;
    }

    // Why one field can't change, or say all of its value, or null when it can.
    function fieldLock(field, readOnly) {
        if (field.info && field.info.generated) return 'SQLite computes this column (GENERATED ALWAYS AS).';
        if (field.original && field.original.type === 'blob') {
            var size = 'A blob of ' + plural(field.original.bytes, 'byte', 'bytes');
            return readOnly ? size + ': the tab reads only its size.' : size + '. Write a blob with Run as write and a blob literal, such as x\'00ff\'.';
        }
        if (field.original && field.original.truncated && field.whole == null) {
            return field.error
                ? 'The page holds the first ' + count(String(field.original.value).length) + ' of ' + count(field.original.length) + ' characters, and the whole value does not read: ' + field.error
                : 'Reading the whole value, ' + plural(field.original.length, 'character', 'characters') + '…';
        }
        return null;
    }

    function fieldHtml(field, index, readOnly) {
        var original = field.original || { type: 'null' };
        var info = field.info || {};
        var lock = fieldLock(field, readOnly);
        var editable = !readOnly && !lock;
        var isNull = field.isNull != null ? field.isNull : original.type === 'null';
        var text = field.text != null ? field.text : field.whole != null ? field.whole : cellText(original);
        if (text == null) text = '';
        var multiline = text.indexOf('\n') >= 0 || text.length > 80;
        var id = 'sqlite-field-' + index;
        var attrs = ' id="' + id + '" data-field-input="' + index + '" spellcheck="false" autocomplete="off"'
            + (isNull ? ' placeholder="NULL"' : '') + (editable ? (isNull ? ' disabled' : '') : ' readonly');
        var input = multiline
            ? '<textarea class="dc-textarea sqlite-field__input" rows="' + Math.min(10, Math.max(3, text.split('\n').length)) + '"' + attrs + '>' + esc(isNull ? '' : text) + '</textarea>'
            : '<input type="text" class="dc-input--block sqlite-field__input"' + attrs + ' value="' + esc(isNull ? '' : text) + '">';
        var badges = (info.type ? '<span class="sqlite-field__type">' + esc(info.type) + '</span>' : '')
            + (info.primaryKey > 0 ? tag('PK', 'Column ' + info.primaryKey + ' of the primary key.', '--accent-2') : '')
            + (info.notNull ? tag('Not null', 'The column takes no NULL.') : '')
            + (info.generated ? tag('Generated', 'SQLite computes the column.') : '');
        var nullToggle = !readOnly && !(info.generated || original.type === 'blob')
            ? '<button type="button" class="sqlite-check sqlite-field__null" data-action="toggleNull" data-field="' + index + '" role="checkbox"'
                + ' aria-checked="' + (isNull ? 'true' : 'false') + '"' + (lock ? ' disabled' : '') + ' title="Save NULL in this column.">'
                + '<span class="dc-check' + (isNull ? ' dc-check--on' : '') + '">' + (isNull ? '✓' : '') + '</span>NULL</button>'
            : '';
        var canCopy = !isNull && original.type !== 'blob' && !(original.truncated && field.whole == null);
        var copy = canCopy ? '<button type="button" class="dc-btn dc-btn--icon" data-action="copyField" data-field="' + index + '" aria-label="Copy the value of ' + esc(field.column) + '" title="Copy the value.">⧉</button>' : '';
        return '<div class="sqlite-field" data-field="' + index + '">'
            + '<div class="sqlite-field__head"><label class="sqlite-field__name" for="' + id + '">' + esc(field.column) + '</label>' + badges
            + '<span class="sqlite-field__tools">' + nullToggle + copy + '</span></div>'
            + (original.type === 'blob' ? '<div class="sqlite-field__blob">' + tag('BLOB · ' + bytesLabel(original.bytes), null, null, 'sqlite-tag--blob') + '</div>' : input)
            + (lock ? '<div class="dc-comment sqlite-field__note">// ' + esc(lock) + '</div>' : '')
            + '</div>';
    }

    // ── Settings ────────────────────────────────────────────────

    function readJson(storage, key) {
        try {
            var raw = storage.getItem(key);
            return raw ? JSON.parse(raw) : null;
        } catch(e) {
            return null;
        }
    }

    function readSettings(storage) {
        var saved = readJson(storage, STORAGE_KEY) || {};
        var pins = {};
        if (saved.pins && typeof saved.pins === 'object') {
            Object.keys(saved.pins).forEach(function(db) {
                if (Array.isArray(saved.pins[db])) pins[db] = saved.pins[db].filter(function(name) { return typeof name === 'string'; });
            });
        }
        var lastTable = {};
        if (saved.lastTable && typeof saved.lastTable === 'object') {
            Object.keys(saved.lastTable).forEach(function(db) {
                if (typeof saved.lastTable[db] === 'string') lastTable[db] = saved.lastTable[db];
            });
        }
        return {
            db: typeof saved.db === 'string' ? saved.db : null,
            lastTable: lastTable,
            view: saved.view === 'sql' ? 'sql' : 'rows',
            showInternal: saved.showInternal === true,
            autoRefresh: saved.autoRefresh === true,
            foundOpen: saved.foundOpen === true,
            pins: pins,
            recent: Array.isArray(saved.recent) ? saved.recent.filter(function(sql) { return typeof sql === 'string'; }).slice(0, MAX_RECENT) : []
        };
    }

    // The message of a failed request: the error envelope's, or what went wrong.
    function errorText(failure) {
        if (!failure || !failure.status) return 'The app did not answer. Is it in the foreground? Try again.';
        var envelope = failure.envelope;
        return envelope && envelope.message ? envelope.message : 'HTTP ' + failure.status;
    }

    // The error envelope of a statement that a route refused, as the console shows it.
    function errorHtml(failure, hint) {
        var envelope = failure && failure.envelope;
        var head = failure && failure.status
            ? failure.status + (envelope && envelope.error ? ' ' + envelope.error : '') + (envelope && envelope.code ? ' · ' + envelope.code : '')
            : 'No answer';
        var html = '<div class="sqlite-error__head">' + esc(head) + '</div><div class="sqlite-error__message">' + esc(errorText(failure)) + '</div>';
        if (envelope && envelope.hint) html += '<div class="dc-comment">// ' + esc(envelope.hint) + '</div>';
        if (hint) html += '<div class="dc-comment">// ' + esc(hint) + '</div>';
        return html;
    }

    window.sqliteQuote = quote;
    window.sqliteLiteral = literal;
    window.sqliteRowidAlias = rowidAlias;
    window.sqliteGridColumns = gridColumns;
    window.sqliteRowTarget = rowTarget;
    window.sqliteWhereSql = whereSql;
    window.sqliteWholeValueSql = wholeValueSql;
    window.sqliteTargetLabel = targetLabel;
    window.sqliteNewPaging = newPaging;
    window.sqlitePageForward = pageForward;
    window.sqlitePageBack = pageBack;
    window.sqlitePageReset = pageReset;
    window.sqlitePageRange = pageRange;
    window.sqliteRangeLabel = rangeLabel;
    window.sqliteNextSort = nextSort;
    window.sqliteRowsPath = rowsPath;
    window.sqliteColumnsKey = columnsKey;
    window.sqliteColumnState = columnState;
    window.sqlitePutColumnState = putColumnState;
    window.sqliteVisibleColumns = visibleColumns;
    window.sqliteCanHide = canHide;
    window.sqliteSetHidden = setHidden;
    window.sqliteColumnWidth = columnWidth;
    window.sqliteCellText = cellText;
    window.sqliteCellView = cellView;
    window.sqliteHeadHtml = headHtml;
    window.sqliteRowHtml = rowHtml;
    window.sqliteDbLockReason = dbLockReason;
    window.sqliteRowLockReason = rowLockReason;
    window.sqliteDbItemHtml = dbItemHtml;
    window.sqliteShownTables = shownTables;
    window.sqliteTableItemHtml = tableItemHtml;
    window.sqliteConsoleRequest = consoleRequest;
    window.sqlitePushRecent = pushRecent;
    window.sqliteEditValue = editValue;
    window.sqliteEditChanges = editChanges;
    window.sqliteFieldHtml = fieldHtml;
    window.sqliteReadSettings = readSettings;
    window.sqliteErrorText = errorText;
    window.sqliteErrorHtml = errorHtml;

    // ── The page ────────────────────────────────────────────────

    var els = {};
    var settings = null;
    var allColumns = {};
    var databases = [];
    var tables = [];
    var shown = [];
    var loaded = false;
    var activeDb = null;
    var activeTable = null;
    var schema = null;
    var columns = [];
    var shownColumns = [];
    var colState = { hidden: [], widths: {} };
    var rows = [];
    var rowsSignature = null;
    var rowsError = null;
    var paging = newPaging();
    var filter = '';
    var sort = null;
    var tableFilter = '';
    // A change of the database starts a generation, and a change of the table
    // another: a response to a request of an earlier one is late and changes nothing.
    var dbGen = 0;
    var tableGen = 0;
    var rowsGen = 0;
    var rowsInFlight = false;
    var filterTimer = null;
    var resizing = false;
    var gridPending = false;
    var editModal = null;
    var valueModal = null;
    var confirmModal = null;
    // The dialog's row: { db, table, row, schema, target, readOnly, fields, saving }.
    var edit = null;
    var valueText = null;
    var valueGen = 0;
    var confirmAction = null;
    var running = false;
    var result = null;
    // A change of the database starts a generation of results: an answer to a statement before it shows nothing.
    var resultGen = 0;

    function storage() {
        try { return window.localStorage; } catch(e) { return null; }
    }

    function store(key, value) {
        try { window.localStorage.setItem(key, JSON.stringify(value)); } catch(e) {}
    }

    function storeSettings() {
        store(STORAGE_KEY, settings);
    }

    function failure(e) {
        if (e && e.response) {
            return e.response.json().then(function(envelope) {
                return { status: e.status, envelope: envelope || null };
            }, function() {
                return { status: e.status, envelope: null };
            });
        }
        return Promise.resolve({ status: 0, envelope: null });
    }

    // debugFetch shows a response that isn't ok as a lost connection, but a
    // refused statement or a read-only database is an answer of the app.
    function request(path, options) {
        return window.debugFetch(window.lustroApiUrl(path), options).then(function(r) { return r.json(); }, function(e) {
            return failure(e).then(function(f) {
                if (f.status) window.debugSetStatus('connected');
                throw f;
            });
        });
    }

    function post(path, body) {
        return request(path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
    }

    function activeDatabase() {
        return findDb(activeDb);
    }

    function findDb(name) {
        if (name == null) return null;
        for (var i = 0; i < databases.length; i++) if (databases[i].name === name) return databases[i];
        return null;
    }

    function findTable(name) {
        if (name == null) return null;
        for (var i = 0; i < tables.length; i++) if (tables[i].name === name) return tables[i];
        return null;
    }

    function anyDialogOpen() {
        return editModal.isVisible() || valueModal.isVisible() || confirmModal.isVisible();
    }

    // ── Databases ───────────────────────────────────────────────

    function loadDatabases(reload) {
        return request('databases').then(function(data) {
            databases = data && Array.isArray(data.items) ? data.items : [];
            var first = !loaded;
            loaded = true;
            renderNone();
            var current = activeDatabase();
            if (current && !current.error) {
                renderDatabases();
                renderConsoleControls();
                return loadTables(reload);
            }
            var stored = first ? findDb(settings.db) : null;
            var next = stored && !stored.error ? stored : databases.filter(function(db) { return !db.error; })[0];
            if (activeDb && !first) window.debugToast('The database ' + activeDb + ' is no longer in the app' + (next ? ', so the tab shows ' + next.name : ''), 'info');
            return selectDb(next ? next.name : null);
        }, function(f) {
            if (!loaded) {
                els.none.textContent = '// ' + errorText(f);
                els.none.hidden = false;
            }
            window.debugToast(errorText(f), 'error');
        });
    }

    function selectDb(name) {
        if (name !== activeDb) {
            activeDb = name;
            dbGen++;
            tables = [];
            clearTable();
            clearResult();
        }
        if (name != null) {
            settings.db = name;
            storeSettings();
        }
        renderDatabases();
        renderTables();
        renderConsoleControls();
        return loadTables(false);
    }

    function renderNone() {
        var none = loaded && !databases.length;
        els.none.hidden = !none;
        if (none) els.none.textContent = '// the app registers no database, and the tab found none on disk. Register one with SqliteDebugTab.create(databases = …).';
        els.main.classList.toggle('sqlite-main--none', none);
    }

    function renderDatabases() {
        var html = '';
        var registered = [];
        var found = [];
        databases.forEach(function(db, index) { (db.registered ? registered : found).push({ db: db, index: index }); });
        registered.forEach(function(item) { html += dbItemHtml(item.db, item.index, item.db.name === activeDb); });
        if (found.length) {
            var open = settings.foundOpen || found.some(function(item) { return item.db.name === activeDb; });
            html += '<button type="button" class="sqlite-group" data-action="toggleFound" aria-expanded="' + (open ? 'true' : 'false') + '"'
                + ' title="The other database files in the app\'s databases directory, such as those of its libraries. The tab opens them read-only.">'
                + '<span class="sqlite-group__caret" aria-hidden="true">' + (open ? '▾' : '▸') + '</span>Found on disk (' + found.length + ')</button>';
            if (open) {
                html += '<div class="sqlite-group__items">';
                found.forEach(function(item) { html += dbItemHtml(item.db, item.index, item.db.name === activeDb); });
                html += '</div>';
            }
        }
        if (!databases.length) html = '<div class="sqlite-side__empty">// no databases</div>';
        els.dbs.innerHTML = html;
    }

    // ── Tables ──────────────────────────────────────────────────

    // reload: read the open table again, as Refresh does.
    function loadTables(reload) {
        if (!activeDb) return Promise.resolve();
        var gen = dbGen;
        var name = activeDb;
        return request('tables?db=' + encodeURIComponent(name)).then(function(data) {
            if (gen !== dbGen) return;
            tables = data && Array.isArray(data.items) ? data.items : [];
            renderTables();
            var current = findTable(activeTable);
            if (current) {
                renderHead();
                if (reload) loadTable(false);
                return;
            }
            if (activeTable) window.debugToast('The table ' + activeTable + ' is no longer in ' + name, 'info');
            var remembered = findTable(settings.lastTable[name]);
            var next = remembered || shownTables(tables, '', settings.showInternal, settings.pins[name])[0];
            if (next) selectTable(next.name);
            else clearTable();
        }, function(f) {
            if (gen !== dbGen) return;
            tables = [];
            renderTables();
            showBanner(errorText(f));
        });
    }

    function renderTables() {
        var internal = tables.filter(function(table) { return table.internal; }).length;
        els.internalLabel.textContent = 'Show internal (' + internal + ')';
        els.internal.setAttribute('aria-checked', settings.showInternal ? 'true' : 'false');
        var check = els.internal.querySelector('.dc-check');
        check.classList.toggle('dc-check--on', settings.showInternal);
        check.textContent = settings.showInternal ? '✓' : '';
        var pins = settings.pins[activeDb] || [];
        shown = shownTables(tables, tableFilter, settings.showInternal, pins);
        var html;
        if (!activeDb) html = '<div class="sqlite-side__empty">// open a database</div>';
        else if (!shown.length) html = '<div class="sqlite-side__empty">// ' + (tables.length ? 'no table matches' : 'no tables') + '</div>';
        else html = shown.map(function(table, index) { return tableItemHtml(table, index, table.name === activeTable, pins.indexOf(table.name) >= 0); }).join('');
        els.tables.innerHTML = html;
    }

    function togglePin(table) {
        if (!activeDb || !table) return;
        var list = (settings.pins[activeDb] || []).slice();
        var at = list.indexOf(table.name);
        if (at >= 0) list.splice(at, 1);
        else list.push(table.name);
        if (list.length) settings.pins[activeDb] = list;
        else delete settings.pins[activeDb];
        storeSettings();
        renderTables();
    }

    // ── The open table ──────────────────────────────────────────

    function clearTable() {
        activeTable = null;
        tableGen++;
        schema = null;
        columns = [];
        rows = [];
        rowsSignature = null;
        rowsError = null;
        pageReset(paging);
        filter = '';
        sort = null;
        els.filter.value = '';
        els.filterClear.hidden = true;
        closeMenus();
        hideBanner();
        renderHead();
        renderGrid();
        renderPaging();
    }

    function selectTable(name) {
        if (name === activeTable) return;
        clearTable();
        activeTable = name;
        settings.lastTable[activeDb] = name;
        storeSettings();
        colState = columnState(allColumns, activeDb, name);
        renderTables();
        renderHead();
        loadTable(false);
    }

    function loadTable(quiet) {
        return Promise.all([loadSchema(), loadRows(quiet)]);
    }

    function loadSchema() {
        if (!activeTable) return Promise.resolve();
        var gen = tableGen;
        return request('schema?db=' + encodeURIComponent(activeDb) + '&table=' + encodeURIComponent(activeTable)).then(function(data) {
            if (gen !== tableGen) return;
            var before = JSON.stringify(columns);
            schema = data;
            columns = gridColumns(schema, rows[0]);
            renderHead();
            if (JSON.stringify(columns) !== before) renderGrid();
            renderPaging();
        }, function(f) {
            if (gen !== tableGen) return;
            if (f.status === 404) loadTables(false);
        });
    }

    function loadRows(quiet) {
        if (!activeTable) return Promise.resolve();
        var gen = tableGen;
        var mine = ++rowsGen;
        rowsInFlight = true;
        var path = rowsPath({ db: activeDb, table: activeTable, cursor: paging.cursor, filter: filter, sort: sort, limit: PAGE_LIMIT });
        return request(path).then(function(data) {
            if (gen !== tableGen || mine !== rowsGen) return;
            var items = data && Array.isArray(data.items) ? data.items : [];
            // The app removed the rows of this page: show the page before.
            if (!items.length && pageBack(paging)) return loadRows(quiet);
            paging.next = data.nextCursor || null;
            rowsError = null;
            hideBanner();
            var signature = JSON.stringify(items);
            if (signature !== rowsSignature) {
                rowsSignature = signature;
                rows = items;
                if (!columns.length) columns = gridColumns(schema, rows[0]);
                renderGrid();
            }
            renderPaging();
        }, function(f) {
            if (gen !== tableGen || mine !== rowsGen) return;
            var field = f.envelope && f.envelope.field;
            if (f.status === 400 && field === 'cursor') {
                pageReset(paging);
                return loadRows(quiet);
            }
            if (f.status === 400 && field === 'sort') {
                sort = null;
                pageReset(paging);
                return loadRows(quiet);
            }
            if (f.status === 404 && field === 'table') return loadTables(false);
            // An auto refresh that the app missed tries again at the next tick.
            if (quiet && !f.status) return;
            rowsError = errorText(f);
            rows = [];
            rowsSignature = null;
            paging.next = null;
            showBanner(rowsError);
            renderGrid();
            renderPaging();
        }).then(function() {
            if (mine === rowsGen) rowsInFlight = false;
        });
    }

    // After a write: the rows, the count in the header, and the counts in the sidebar.
    function reloadAfterWrite() {
        loadTables(false);
        loadTable(false);
    }

    function renderHead() {
        var db = activeDatabase();
        els.headDb.textContent = activeDb || '';
        var table = findTable(activeTable);
        if (!activeDb) {
            els.headTable.textContent = loaded && databases.length ? 'Open a database' : '';
            els.headFacts.innerHTML = '';
            return;
        }
        if (!activeTable) {
            els.headTable.textContent = 'Select a table';
            els.headFacts.innerHTML = '';
            return;
        }
        els.headTable.textContent = activeTable;
        var view = (schema && schema.type === 'view') || (table && table.type === 'view');
        var total = schema && schema.table === activeTable ? schema.rowCount : table ? table.rowCount : null;
        var html = '';
        if (!view && total != null) html += '<span class="sqlite-head__count">' + esc(plural(total, 'row', 'rows')) + '</span>';
        if (view) html += tag('View', 'A view: its rows don\'t change, and the tab does not count them.', '--cat-config');
        if (schema && !schema.hasRowId && !view) html += tag('Without rowid', 'A table WITHOUT ROWID: the tab names a row by its primary key.');
        var lock = dbLockReason(db);
        if (lock) html += tag('Read-only', lock, '--lvl-w');
        else if (!view) html += tag('Writable', 'The tab may change its rows.', '--live');
        if (table && (table.entity || table.purpose)) html += '<span class="sqlite-head__hint" title="' + esc([table.entity, table.purpose].filter(Boolean).join(': ')) + '">// ' + esc([table.entity, table.purpose].filter(Boolean).join(': ')) + '</span>';
        els.headFacts.innerHTML = html;
    }

    function canDelete() {
        return !dbLockReason(activeDatabase()) && !(schema && schema.type === 'view') && !!schema;
    }

    function renderGrid() {
        // A drag holds the grid's columns: the rows that came meanwhile show when it ends.
        if (resizing) {
            gridPending = true;
            return;
        }
        if (!activeTable || !columns.length) {
            els.grid.innerHTML = '';
            els.grid.style.width = '';
            var message = !activeDb ? '' : !activeTable ? '// select a table' : rowsError ? '' : '// reading the rows…';
            els.empty.textContent = message;
            els.empty.hidden = !message;
            renderColumnsButton();
            return;
        }
        shownColumns = visibleColumns(columns, colState);
        var actions = canDelete();
        var db = activeDatabase();
        var html = headHtml(shownColumns, colState, sort, actions) + '<tbody>'
            + rows.map(function(row, index) {
                return rowHtml(row, index, shownColumns, { actions: actions, lock: actions ? rowLockReason(db, schema, row) : null });
            }).join('') + '</tbody>';
        els.grid.innerHTML = html;
        applyTableWidth(actions);
        var empty = !rows.length && !rowsError;
        els.empty.textContent = empty ? (filter ? '// no row has a cell that contains "' + filter + '"' : '// the table is empty') : '';
        els.empty.hidden = !empty;
        renderColumnsButton();
    }

    function applyTableWidth(actions) {
        var width = shownColumns.reduce(function(sum, column) { return sum + columnWidth(colState, column); }, 0);
        els.grid.style.width = (width + (actions ? ACTIONS_WIDTH : 0)) + 'px';
    }

    function renderPaging() {
        var table = findTable(activeTable);
        var total = schema && schema.table === activeTable ? schema.rowCount : table ? table.rowCount : null;
        els.range.textContent = activeTable && !rowsError ? rangeLabel(paging, rows.length, PAGE_LIMIT, total, !!filter) : '';
        els.prev.disabled = !paging.stack.length;
        els.next.disabled = paging.next == null;
    }

    function showBanner(text) {
        els.banner.textContent = text;
        els.banner.hidden = false;
    }

    function hideBanner() {
        els.banner.hidden = true;
    }

    function onFilterInput() {
        els.filterClear.hidden = !els.filter.value;
        if (filterTimer) clearTimeout(filterTimer);
        filterTimer = setTimeout(applyFilter, FILTER_DEBOUNCE_MS);
    }

    function applyFilter() {
        if (filterTimer) { clearTimeout(filterTimer); filterTimer = null; }
        var value = els.filter.value;
        if (value === filter) return;
        filter = value;
        pageReset(paging);
        loadRows(false);
    }

    function sortBy(column) {
        sort = nextSort(sort, column.name);
        pageReset(paging);
        renderGrid();
        loadRows(false);
    }

    // ── Columns ─────────────────────────────────────────────────

    function saveColumns() {
        if (!activeDb || !activeTable) return;
        putColumnState(allColumns, activeDb, activeTable, colState);
        store(COLUMNS_KEY, allColumns);
    }

    function hideColumn(column, hidden) {
        if (!setHidden(columns, colState, column.name, hidden)) return;
        saveColumns();
        renderGrid();
        if (!els.columnsMenu.hidden) renderColumnsMenu();
    }

    function showAllColumns() {
        colState.hidden = [];
        saveColumns();
        renderGrid();
        renderColumnsMenu();
    }

    function renderColumnsButton() {
        var hidden = columns.length ? columns.length - visibleColumns(columns, colState).length : 0;
        els.columns.textContent = hidden ? 'Columns (' + hidden + ' hidden) ▾' : 'Columns ▾';
        els.columns.classList.toggle('dc-btn--active', hidden > 0);
        els.columns.disabled = !columns.length;
    }

    function renderColumnsMenu() {
        var visible = visibleColumns(columns, colState);
        var html = '<button type="button" class="dc-menu__head" data-action="showAllColumns" role="menuitem">Show all</button>';
        html += columns.map(function(column, index) {
            var on = visible.indexOf(column) >= 0;
            var locked = on && visible.length === 1;
            return '<button type="button" class="dc-menu__item' + (locked ? ' sqlite-menu__locked' : '') + '" role="menuitemcheckbox" aria-checked="' + (on ? 'true' : 'false') + '"'
                + (locked ? ' aria-disabled="true" title="The last column that shows stays."' : '') + ' data-action="toggleColumn" data-col="' + index + '">'
                + '<span class="dc-check' + (locked ? ' dc-check--locked' : on ? ' dc-check--on' : '') + '">' + (on ? '✓' : '') + '</span>'
                + '<span class="sqlite-menu__label">' + esc(column.name) + '</span></button>';
        }).join('');
        els.columnsMenu.innerHTML = html;
    }

    function toggleColumnsMenu() {
        var open = !els.columnsMenu.hidden;
        closeMenus();
        if (open || !columns.length) return;
        renderColumnsMenu();
        els.columnsMenu.hidden = false;
        els.menuScrim.hidden = false;
        els.columns.setAttribute('aria-expanded', 'true');
    }

    function closeMenus() {
        els.columnsMenu.hidden = true;
        els.menuScrim.hidden = true;
        els.columns.setAttribute('aria-expanded', 'false');
    }

    function startResize(handle, ev) {
        var column = shownColumns[Number(handle.dataset.colResize)];
        if (!column) return;
        var th = handle.parentNode;
        var col = els.grid.querySelectorAll('col')[Number(handle.dataset.colResize)];
        var startX = ev.clientX;
        var startWidth = th.getBoundingClientRect().width;
        var moved = false;
        ev.preventDefault();
        ev.stopPropagation();
        resizing = true;
        handle.classList.add('sqlite-resize--dragging');
        document.body.style.cursor = 'col-resize';
        function onMove(e) {
            if (Math.abs(e.clientX - startX) > 2) moved = true;
            colState.widths[column.name] = Math.max(MIN_WIDTH, Math.round(startWidth + e.clientX - startX));
            col.style.width = colState.widths[column.name] + 'px';
            applyTableWidth(canDelete());
        }
        function onUp() {
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('mouseup', onUp);
            handle.classList.remove('sqlite-resize--dragging');
            document.body.style.cursor = '';
            resizing = false;
            if (gridPending) {
                gridPending = false;
                renderGrid();
            }
            if (!moved) return;
            saveColumns();
            // The click that ends a drag would sort the column under the pointer.
            var swallow = function(e) {
                e.stopPropagation();
                window.removeEventListener('click', swallow, true);
            };
            window.addEventListener('click', swallow, true);
            setTimeout(function() { window.removeEventListener('click', swallow, true); }, 300);
        }
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
    }

    // ── The whole value of a cell ───────────────────────────────

    function showValue(title, meta, text) {
        valueGen++;
        valueText = text;
        els.valueTitle.textContent = title;
        els.valueMeta.textContent = meta;
        els.valueError.hidden = true;
        var tree = text != null && text.length <= JSON_TREE_MAX_CHARS && /^\s*[[{]/.test(text) ? window.debugJsonTree(text) : null;
        els.valueBody.innerHTML = tree || '<pre class="dc-code sqlite-value__text">' + esc(text == null ? '' : text) + '</pre>';
        els.valueCopy.disabled = text == null;
        valueModal.show();
    }

    function showValueError(text) {
        els.valueError.textContent = text;
        els.valueError.hidden = false;
    }

    // A cut text, read whole with POST query, which finds the row by its rowid or its key.
    function openWhole(row, column) {
        var cell = row.cells[column.name];
        var where = targetLabel(rowTarget(row, schema));
        var meta = activeTable + (where ? ' · ' + where : '') + ' · ' + plural(cell.length, 'character', 'characters');
        showValue(column.name, meta, null);
        els.valueBody.innerHTML = '<pre class="dc-code sqlite-value__text">' + esc(String(cell.value)) + '…</pre>';
        var sql = wholeValueSql(activeTable, [column.name], row, schema);
        if (!sql) {
            showValueError(schema && schema.type === 'view'
                ? 'A view\'s rows have no rowid, so the tab can\'t read one value again. Read it in the SQL view with substr(), in parts.'
                : 'The tab can\'t name this row again: it shows the first ' + count(String(cell.value).length) + ' characters.');
            return;
        }
        var db = activeDb;
        var mine = valueGen;
        var current = function() { return mine === valueGen && valueModal.isVisible(); };
        post('query', { db: db, sql: sql }).then(function(data) {
            if (!current()) return;
            var first = data.rows && data.rows[0] ? data.rows[0][0] : null;
            if (!first) return showValueError('The row is no longer in the table.');
            if (first.type !== 'text') return showValue(column.name, meta, cellText(first));
            showValue(column.name, meta, String(first.value));
        }, function(f) {
            if (current()) showValueError(errorText(f));
        });
    }

    // ── The row editor ──────────────────────────────────────────

    function openEditor(index) {
        var row = rows[index];
        if (!row || !schema) return;
        var db = activeDatabase();
        var lock = rowLockReason(db, schema, row);
        var fields = schema.columns.map(function(info) {
            return { column: info.name, info: info, original: row.cells[info.name] || { type: 'null' }, whole: null, error: null };
        });
        edit = { db: activeDb, table: activeTable, row: row, schema: schema, target: rowTarget(row, schema), readOnly: !!lock, fields: fields, saving: false, gen: tableGen };
        els.editTitle.textContent = lock ? 'Row (read-only)' : 'Edit row';
        els.editWhere.textContent = activeTable + (edit.target ? ' · ' + targetLabel(edit.target) : '');
        els.editLock.textContent = lock ? '// ' + lock : '';
        els.editLock.hidden = !lock;
        els.editFields.innerHTML = fields.map(function(field, i) { return fieldHtml(field, i, edit.readOnly); }).join('');
        fields.forEach(fillField);
        els.editError.hidden = true;
        els.editSave.hidden = edit.readOnly;
        els.editDelete.hidden = edit.readOnly;
        els.editCancel.textContent = edit.readOnly ? 'Close' : 'Cancel';
        editModal.show();
        loadWholeFields(edit);
    }

    // The cut texts of the row, read whole in one statement, so that they can be copied and edited.
    function loadWholeFields(state) {
        var cut = state.fields.filter(function(field) { return field.original.truncated; });
        if (!cut.length) return;
        var sql = wholeValueSql(state.table, cut.map(function(field) { return field.column; }), state.row, state.schema);
        if (!sql) {
            cut.forEach(function(field) { field.error = 'the tab can\'t name this row again.'; });
            redrawFields(state, cut);
            return;
        }
        post('query', { db: state.db, sql: sql }).then(function(data) {
            var values = data.rows && data.rows[0];
            cut.forEach(function(field, i) {
                var cell = values ? values[i] : null;
                if (!cell) field.error = 'the row is no longer in the table.';
                else if (cell.type !== 'text') field.error = 'the value is no longer a text.';
                else field.whole = String(cell.value);
            });
            redrawFields(state, cut);
        }, function(f) {
            cut.forEach(function(field) { field.error = errorText(f); });
            redrawFields(state, cut);
        });
    }

    function redrawFields(state, fields) {
        if (edit !== state || !editModal.isVisible()) return;
        fields.forEach(function(field) {
            var index = state.fields.indexOf(field);
            var el = els.editFields.querySelector('[data-field="' + index + '"]');
            if (el) el.outerHTML = fieldHtml(field, index, state.readOnly);
            fillField(field, index);
        });
    }

    // The HTML parser drops the first line break of a text area, so the field
    // gets its text from .value, and keeps what it then shows.
    function fillField(field, index) {
        var input = fieldInput(index);
        if (!input) return;
        var text = field.whole != null ? field.whole : cellText(field.original);
        input.value = text == null ? '' : text;
        field.initial = input.value;
    }

    function fieldInput(index) {
        return document.getElementById('sqlite-field-' + index);
    }

    function nullToggle(index) {
        return els.editFields.querySelector('[data-action="toggleNull"][data-field="' + index + '"]');
    }

    function toggleNull(index) {
        var button = nullToggle(index);
        var input = fieldInput(index);
        if (!button || !input || button.disabled) return;
        var on = button.getAttribute('aria-checked') !== 'true';
        button.setAttribute('aria-checked', on ? 'true' : 'false');
        var check = button.querySelector('.dc-check');
        check.classList.toggle('dc-check--on', on);
        check.textContent = on ? '✓' : '';
        input.disabled = on;
        input.placeholder = on ? 'NULL' : '';
        if (!on) input.focus();
    }

    function currentFields() {
        return edit.fields.map(function(field, index) {
            var input = fieldInput(index);
            var button = nullToggle(index);
            return {
                column: field.column,
                original: field.original,
                whole: field.whole,
                initial: field.initial,
                text: input ? input.value : '',
                isNull: !!button && button.getAttribute('aria-checked') === 'true',
                editable: !edit.readOnly && !fieldLock(field, false) && !!input
            };
        });
    }

    function setEditError(text) {
        els.editError.textContent = text;
        els.editError.hidden = !text;
    }

    function saveEdit() {
        if (!edit || edit.readOnly || edit.saving) return;
        var values = editChanges(currentFields());
        if (!Object.keys(values).length) return setEditError('Nothing changed.');
        var state = edit;
        state.saving = true;
        els.editSave.disabled = true;
        setEditError('');
        post('edit', Object.assign({ db: state.db, table: state.table, values: values }, state.target)).then(function() {
            if (edit === state) editModal.hide();
            window.debugToast('Saved the row in ' + state.table, 'success');
            if (state.gen === tableGen) reloadAfterWrite();
        }, function(f) {
            if (edit === state) setEditError(errorText(f));
        }).then(function() {
            state.saving = false;
            els.editSave.disabled = false;
        });
    }

    function showConfirm(title, message, ok) {
        els.confirmTitle.textContent = title;
        els.confirmMessage.textContent = message;
        confirmAction = ok;
        confirmModal.show();
        els.confirmOk.focus();
    }

    function confirmDelete(row, rowSchema, db, table) {
        var target = rowTarget(row, rowSchema);
        if (!target || rowLockReason(findDb(db), rowSchema, row)) return;
        var gen = tableGen;
        showConfirm('Delete row', 'Delete the row ' + targetLabel(target) + ' from ' + table + ' in ' + db + '? This can\'t be undone.', function() {
            post('delete', Object.assign({ db: db, table: table }, target)).then(function() {
                window.debugToast('Deleted the row from ' + table, 'success');
                if (gen === tableGen) reloadAfterWrite();
            }, function(f) {
                window.debugToast(errorText(f), 'error');
            });
        });
    }

    // ── The console ─────────────────────────────────────────────

    function renderConsoleControls() {
        var db = activeDatabase();
        var writable = !!db && !dbLockReason(db);
        els.runWrite.hidden = !writable;
        els.sqlHint.textContent = '// ⌘↵ or Ctrl+↵ runs it on a read-only connection'
            + (writable ? ' · Run as write changes the app\'s data' : '') + ' · one statement for each run';
    }

    function renderRecent() {
        els.recentBlock.hidden = !settings.recent.length;
        els.recent.innerHTML = settings.recent.map(function(sql, index) {
            return '<button type="button" class="sqlite-recent__item" data-action="useRecent" data-index="' + index + '" title="' + esc(sql) + '">'
                + '<span class="sqlite-recent__mark" aria-hidden="true">›</span><span class="sqlite-recent__sql">' + esc(sql) + '</span></button>';
        }).join('');
    }

    function setRunning(on) {
        running = on;
        els.run.disabled = on;
        els.runWrite.disabled = on;
    }

    function run(mode) {
        if (running) return;
        var db = activeDatabase();
        var req = consoleRequest(mode, db, els.sql.value);
        els.resultBlock.hidden = false;
        if (req.error) {
            showResultMessage(req.error);
            return;
        }
        setRunning(true);
        var mine = ++resultGen;
        els.resultStatus.textContent = 'Running…';
        els.resultStatus.className = 'sqlite-result__status';
        var started = Date.now();
        post(req.route, req.body).then(function(data) {
            var ms = Date.now() - started;
            settings.recent = pushRecent(settings.recent, req.body.sql);
            storeSettings();
            renderRecent();
            if (req.route === 'exec') {
                window.debugToast('Changed ' + plural(data.changes || 0, 'row', 'rows') + ' in ' + req.body.db, 'success');
                if (req.body.db === activeDb) reloadAfterWrite();
                if (mine === resultGen) showExecResult(data, ms);
            } else if (mine === resultGen) {
                showQueryResult(data, ms);
            }
        }, function(f) {
            if (mine !== resultGen) return;
            var code = f.envelope && f.envelope.code;
            var hint = mode !== 'write' && code === 'read_only' && db && !dbLockReason(db)
                ? 'The statement writes. Run as write sends it through the app\'s handle.'
                : null;
            showResultError(f, hint);
        }).then(function() {
            setRunning(false);
        });
    }

    function showResultError(f, hint) {
        els.resultStatus.textContent = f.status ? '✕ refused' : '✕ no answer';
        els.resultStatus.className = 'sqlite-result__status sqlite-result__status--error';
        els.sqlError.innerHTML = errorHtml(f, hint);
        els.sqlError.hidden = false;
        els.resultNote.hidden = true;
        els.resultScroll.hidden = true;
        result = null;
    }

    function clearResult() {
        resultGen++;
        result = null;
        els.resultBlock.hidden = true;
    }

    // A statement that the page did not send.
    function showResultMessage(text) {
        els.resultStatus.textContent = '';
        els.sqlError.hidden = true;
        els.resultScroll.hidden = true;
        els.resultNote.textContent = '// ' + text;
        els.resultNote.classList.add('sqlite-note--warn');
        els.resultNote.hidden = false;
        result = null;
    }

    function showExecResult(data, ms) {
        result = null;
        els.sqlError.hidden = true;
        els.resultScroll.hidden = true;
        els.resultStatus.textContent = '✓ ' + plural(data.changes || 0, 'row', 'rows') + ' changed · ' + ms + ' ms';
        els.resultStatus.className = 'sqlite-result__status sqlite-result__status--ok';
        els.resultNote.textContent = '// the rows that the statement inserted, updated, or deleted, without those of triggers and foreign key actions';
        els.resultNote.classList.remove('sqlite-note--warn');
        els.resultNote.hidden = false;
    }

    function showQueryResult(data, ms) {
        result = data;
        var cols = Array.isArray(data.columns) ? data.columns : [];
        var resultRows = Array.isArray(data.rows) ? data.rows : [];
        els.sqlError.hidden = true;
        els.resultStatus.textContent = '✓ ' + plural(resultRows.length, 'row', 'rows') + (data.truncated ? ' shown' : '') + ' · ' + ms + ' ms';
        els.resultStatus.className = 'sqlite-result__status sqlite-result__status--ok';
        var note = data.truncated
            ? '// the result has more rows: query returns at most 1000, and fewer when their texts pass 4 Mi characters. Add a LIMIT or a WHERE to see the others.'
            : !resultRows.length ? '// no rows, ' + plural(cols.length, 'column', 'columns') : '';
        els.resultNote.textContent = note;
        els.resultNote.hidden = !note;
        els.resultNote.classList.toggle('sqlite-note--warn', !!data.truncated);
        if (!cols.length || !resultRows.length) {
            els.resultScroll.hidden = true;
            return;
        }
        var head = '<thead class="dc-thead"><tr>' + cols.map(function(name) {
            return '<th class="dc-th"><span class="sqlite-th"><span class="sqlite-th__name">' + esc(name) + '</span></span></th>';
        }).join('') + '</tr></thead>';
        var body = '<tbody>' + resultRows.map(function(row, r) {
            return '<tr class="dc-row">' + row.map(function(cell, c) {
                var view = cellView(cell, PREVIEW_CHARS);
                var text = cellText(cell);
                return '<td class="dc-cell' + (view.cls ? ' ' + view.cls : '') + '"' + (text != null ? ' data-action="openResultCell" data-r="' + r + '" data-c="' + c + '" title="Click to open the whole value."' : '') + '>' + view.html + '</td>';
            }).join('') + '</tr>';
        }).join('') + '</tbody>';
        els.result.innerHTML = head + body;
        els.resultScroll.hidden = false;
    }

    function prefillSql() {
        if (els.sql.value.trim() || !activeTable) return;
        els.sql.value = 'SELECT * FROM ' + quote(activeTable) + ' LIMIT 50';
    }

    // ── Views and auto refresh ──────────────────────────────────

    function showView(view) {
        settings.view = view === 'sql' ? 'sql' : 'rows';
        storeSettings();
        renderView();
        if (settings.view === 'sql') {
            closeMenus();
            prefillSql();
            els.sql.focus();
        }
    }

    function renderView() {
        var sql = settings.view === 'sql';
        els.rowsView.hidden = sql;
        els.sqlView.hidden = !sql;
        [els.showRows, els.showSql].forEach(function(button) {
            var on = button.dataset.view === settings.view;
            button.classList.toggle('dc-seg__item--active', on);
            button.setAttribute('aria-selected', on ? 'true' : 'false');
        });
    }

    // Reads the page again unless the user is busy with it: a dialog, the SQL
    // view, a drag, or a request that has not come back.
    function autoRefreshTick() {
        if (!settings.autoRefresh || settings.view !== 'rows' || !activeTable || rowsInFlight || resizing) return;
        if (anyDialogOpen() || document.hidden) return;
        loadTable(true);
    }

    function refresh() {
        loadDatabases(true);
    }

    // ── Events ──────────────────────────────────────────────────

    var CLICK_ACTIONS = {
        selectDb: function(el) {
            var db = databases[Number(el.dataset.db)];
            if (db && !db.error) selectDb(db.name);
        },
        toggleFound: function() {
            var open = els.dbs.querySelector('.sqlite-group').getAttribute('aria-expanded') === 'true';
            settings.foundOpen = !open;
            storeSettings();
            renderDatabases();
        },
        selectTable: function(el) {
            var table = shown[Number(el.dataset.table)];
            if (table) selectTable(table.name);
        },
        togglePin: function(el) { togglePin(shown[Number(el.dataset.table)]); },
        toggleInternal: function() {
            settings.showInternal = !settings.showInternal;
            storeSettings();
            renderTables();
        },
        showView: function(el) { showView(el.dataset.view); },
        clearFilter: function() {
            els.filter.value = '';
            els.filterClear.hidden = true;
            applyFilter();
            els.filter.focus();
        },
        toggleColumns: toggleColumnsMenu,
        closeMenus: closeMenus,
        showAllColumns: showAllColumns,
        toggleColumn: function(el) {
            var column = columns[Number(el.dataset.col)];
            if (!column) return;
            hideColumn(column, visibleColumns(columns, colState).indexOf(column) >= 0);
        },
        refresh: refresh,
        prevPage: function() {
            if (pageBack(paging)) loadRows(false);
        },
        nextPage: function() {
            if (pageForward(paging)) {
                renderPaging();
                loadRows(false);
            }
        },
        deleteRow: function(el) {
            var row = rows[Number(el.dataset.row)];
            if (row) confirmDelete(row, schema, activeDb, activeTable);
        },
        runRead: function() { run('read'); },
        runWrite: function() { run('write'); },
        useRecent: function(el) {
            var sql = settings.recent[Number(el.dataset.index)];
            if (sql == null) return;
            els.sql.value = sql;
            els.sql.focus();
        },
        openResultCell: function(el) {
            if (!result) return;
            var cell = result.rows[Number(el.dataset.r)][Number(el.dataset.c)];
            var text = cellText(cell);
            if (text != null) showValue(result.columns[Number(el.dataset.c)], plural(text.length, 'character', 'characters'), text);
        },
        toggleNull: function(el) { toggleNull(Number(el.dataset.field)); },
        copyField: function(el) {
            var index = Number(el.dataset.field);
            var input = fieldInput(index);
            var field = edit && edit.fields[index];
            if (!input || !field) return;
            // The value as stored, unless the user changed it: a field turns CR LF into LF.
            var stored = field.whole != null ? field.whole : cellText(field.original);
            window.debugCopyToClipboard(input.value === field.initial && stored != null ? stored : input.value);
        },
        saveEdit: saveEdit,
        closeEdit: function() { editModal.hide(); },
        deleteFromEdit: function() {
            if (!edit || edit.readOnly) return;
            var state = edit;
            editModal.hide();
            confirmDelete(state.row, state.schema, state.db, state.table);
        },
        closeValue: function() { valueModal.hide(); },
        copyValue: function() {
            if (valueText != null) window.debugCopyToClipboard(valueText);
        },
        confirmOk: function() {
            var action = confirmAction;
            confirmAction = null;
            confirmModal.hide();
            if (action) action();
        },
        cancelConfirm: function() {
            confirmAction = null;
            confirmModal.hide();
        }
    };

    function onGridClick(ev) {
        var th = ev.target.closest('th[data-col]');
        if (th) {
            if (ev.target.closest('.sqlite-resize')) return;
            var column = shownColumns[Number(th.dataset.col)];
            if (column) sortBy(column);
            return;
        }
        if (ev.target.closest('[data-action]')) return;
        var tr = ev.target.closest('tr[data-row]');
        if (!tr) return;
        var row = rows[Number(tr.dataset.row)];
        var td = ev.target.closest('td[data-col]');
        var cellColumn = td ? shownColumns[Number(td.dataset.col)] : null;
        var cell = row && cellColumn && !cellColumn.rowid ? row.cells[cellColumn.name] : null;
        if (cell && cell.truncated) openWhole(row, cellColumn);
        else openEditor(Number(tr.dataset.row));
    }

    function setupEvents() {
        var root = els.root;
        root.addEventListener('click', function(ev) {
            var el = ev.target.closest('[data-action]');
            if (!el || !root.contains(el) || el.disabled || el.getAttribute('aria-disabled') === 'true') return;
            var fn = CLICK_ACTIONS[el.dataset.action];
            if (fn) fn(el, ev);
        });
        els.grid.addEventListener('click', onGridClick);
        els.grid.addEventListener('contextmenu', function(ev) {
            var th = ev.target.closest('th[data-col]');
            if (!th) return;
            ev.preventDefault();
            var column = shownColumns[Number(th.dataset.col)];
            if (column) hideColumn(column, true);
        });
        els.grid.addEventListener('mousedown', function(ev) {
            var handle = ev.target.closest('.sqlite-resize');
            if (handle && ev.button === 0) startResize(handle, ev);
        });
        els.grid.addEventListener('dblclick', function(ev) {
            var handle = ev.target.closest('.sqlite-resize');
            if (!handle) return;
            var column = shownColumns[Number(handle.dataset.colResize)];
            if (!column) return;
            delete colState.widths[column.name];
            saveColumns();
            renderGrid();
        });
        els.tables.addEventListener('keydown', function(ev) {
            if ((ev.key !== 'Enter' && ev.key !== ' ') || !ev.target.classList.contains('sqlite-table')) return;
            ev.preventDefault();
            CLICK_ACTIONS.selectTable(ev.target);
        });
        els.tableFilter.addEventListener('input', function() {
            tableFilter = els.tableFilter.value;
            renderTables();
        });
        els.filter.addEventListener('input', onFilterInput);
        els.filter.addEventListener('keydown', function(ev) {
            if (ev.key === 'Enter') applyFilter();
        });
        els.auto.addEventListener('change', function() {
            settings.autoRefresh = els.auto.checked;
            storeSettings();
            if (settings.autoRefresh) autoRefreshTick();
        });
        els.sql.addEventListener('keydown', function(ev) {
            if (ev.key === 'Enter' && (ev.metaKey || ev.ctrlKey) && !ev.isComposing) {
                ev.preventDefault();
                run('read');
            }
        });
        els.editDialog.addEventListener('keydown', function(ev) {
            if (ev.key !== 'Enter' || ev.isComposing) return;
            var target = ev.target;
            var oneLine = target.tagName === 'INPUT' && target.type === 'text';
            if (oneLine || (target.tagName === 'TEXTAREA' && (ev.ctrlKey || ev.metaKey))) {
                ev.preventDefault();
                saveEdit();
            }
        });
        document.addEventListener('keydown', function(ev) {
            if (ev.key === 'Escape' && !ev.defaultPrevented && !els.columnsMenu.hidden) closeMenus();
        });
    }

    function init() {
        var root = document.getElementById('sqlite-root');
        if (!root) return;
        var byId = function(id) { return document.getElementById(id); };
        els = {
            root: root,
            main: root.querySelector('.sqlite-main'),
            dbs: byId('sqlite-dbs'),
            tableFilter: byId('sqlite-table-filter'),
            internal: byId('sqlite-internal'),
            internalLabel: byId('sqlite-internal-label'),
            tables: byId('sqlite-tables'),
            headDb: byId('sqlite-head-db'),
            headTable: byId('sqlite-head-table'),
            headFacts: byId('sqlite-head-facts'),
            showRows: byId('sqlite-show-rows'),
            showSql: byId('sqlite-show-sql'),
            rowsView: byId('sqlite-rows-view'),
            filter: byId('sqlite-filter'),
            filterClear: byId('sqlite-filter-clear'),
            columns: byId('sqlite-columns'),
            columnsMenu: byId('sqlite-columns-menu'),
            menuScrim: byId('sqlite-menu-scrim'),
            auto: byId('sqlite-auto'),
            prev: byId('sqlite-prev'),
            next: byId('sqlite-next'),
            range: byId('sqlite-range'),
            banner: byId('sqlite-banner'),
            grid: byId('sqlite-grid'),
            empty: byId('sqlite-empty'),
            sqlView: byId('sqlite-sql-view'),
            sql: byId('sqlite-sql'),
            run: byId('sqlite-run'),
            runWrite: byId('sqlite-run-write'),
            sqlHint: byId('sqlite-sql-hint'),
            recentBlock: byId('sqlite-recent-block'),
            recent: byId('sqlite-recent'),
            resultBlock: byId('sqlite-result-block'),
            resultStatus: byId('sqlite-result-status'),
            sqlError: byId('sqlite-sql-error'),
            resultNote: byId('sqlite-result-note'),
            resultScroll: byId('sqlite-result-scroll'),
            result: byId('sqlite-result'),
            none: byId('sqlite-none'),
            editDialog: byId('sqlite-edit-dialog'),
            editTitle: byId('sqlite-edit-title'),
            editWhere: byId('sqlite-edit-where'),
            editLock: byId('sqlite-edit-lock'),
            editFields: byId('sqlite-edit-fields'),
            editError: byId('sqlite-edit-error'),
            editSave: byId('sqlite-edit-save'),
            editDelete: byId('sqlite-edit-delete'),
            editCancel: byId('sqlite-edit-cancel'),
            valueTitle: byId('sqlite-value-title'),
            valueMeta: byId('sqlite-value-meta'),
            valueBody: byId('sqlite-value-body'),
            valueError: byId('sqlite-value-error'),
            valueCopy: byId('sqlite-value-copy'),
            confirmTitle: byId('sqlite-confirm-title'),
            confirmMessage: byId('sqlite-confirm-message'),
            confirmOk: byId('sqlite-confirm-ok')
        };
        var local = storage();
        var empty = { getItem: function() { return null; } };
        settings = readSettings(local || empty);
        allColumns = readJson(local || empty, COLUMNS_KEY) || {};
        editModal = window.debugModal('sqlite-edit-modal');
        valueModal = window.debugModal('sqlite-value-modal');
        confirmModal = window.debugModal('sqlite-confirm-modal');
        window.debugInitResizers();
        setupEvents();
        els.auto.checked = settings.autoRefresh;
        renderView();
        renderRecent();
        renderConsoleControls();
        renderHead();
        renderGrid();
        renderPaging();
        setInterval(autoRefreshTick, AUTO_REFRESH_MS);
        loadDatabases(false);
    }

    if (typeof window.lustroOnContentReady === 'function') {
        window.lustroOnContentReady(init);
    } else {
        init();
    }
})();
