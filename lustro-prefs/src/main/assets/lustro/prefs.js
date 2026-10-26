// The Preferences tab: the app's SharedPreferences files, to read and edit them.
// A poll asks GET files, which has the cursor of each file, and asks GET entries
// for the open file only when its cursor moved, so a poll of a file that did not
// change is one short request. prefs.openapi.json describes the routes.
(function() {
    var STORAGE_KEY = 'debug-prefs';
    var WIDTHS_KEY = 'debug-prefs-column-widths';
    var DEFAULT_INTERVAL_MS = 3000;
    var MIN_INTERVAL_MS = 1000;
    // A string shorter than this, on one line and not JSON, is edited in its row.
    var INLINE_MAX_CHARS = 50;
    // A longer string, or one with line breaks, opens in a text area.
    var TEXTAREA_FROM_CHARS = 80;
    var SAVED_FLASH_MS = 1600;
    var CHANGED_FLASH_MS = 1500;
    var PREVIEW_DEBOUNCE_MS = 150;
    var MIN_COLUMN_WIDTH = 60;
    var MIN_VALUE_WIDTH = 240;
    var ACTIONS_WIDTH = 96;
    var DEFAULT_WIDTHS = { key: 300, type: 110 };

    var TYPES = ['string', 'int', 'long', 'float', 'boolean', 'string_set'];
    var TYPE_LABELS = {
        string: 'String', int: 'Int', long: 'Long', float: 'Float', boolean: 'Boolean',
        string_set: 'String set', unknown: 'Unknown'
    };
    // A string set has the color of a string: the label tells the two apart.
    var TYPE_COLORS = {
        string: '--type-string', string_set: '--type-string', int: '--type-int',
        long: '--type-long', float: '--type-float', boolean: '--type-boolean'
    };
    var INT_RANGE = ['-2147483648', '2147483647'];
    var LONG_RANGE = ['-9223372036854775808', '9223372036854775807'];
    var NON_FINITE = ['NaN', 'Infinity', '-Infinity'];
    var POLICY_LABELS = { visible: 'Values visible', mask_sensitive: 'Sensitive values masked', keys_only: 'Keys only' };
    // Lustro's own file, which holds the token.
    var LUSTRO_FILE = 'lustro_debug';

    // ── Reading the routes' JSON ────────────────────────────────

    // A long can be past 2^53, where a JS number loses digits, and an edit would
    // then write another number back. The routes write a value right after
    // "value":, and only a long has 16 or more digits with no point or exponent,
    // so those become strings before the parse. No string holds the match: its
    // quotes are escaped.
    var LONG_VALUE = /"value":(-?\d{16,})(?=[,}])/g;

    function parseJson(text) {
        return JSON.parse(String(text).replace(LONG_VALUE, '"value":"$1"'));
    }

    // ── The page's copy of a file ───────────────────────────────

    function newView() {
        return { file: null, cursor: null, entries: [], byKey: new Map() };
    }

    // By key, ignoring case, so that ACCOUNT_TYPE sorts next to account_id.
    function sortEntries(items) {
        return items.slice().sort(function(a, b) {
            var x = String(a.key).toLowerCase();
            var y = String(b.key).toLowerCase();
            if (x !== y) return x < y ? -1 : 1;
            return a.key < b.key ? -1 : a.key > b.key ? 1 : 0;
        });
    }

    function sameEntry(a, b) {
        return !!a && !!b && a.type === b.type && !!a.masked === !!b.masked && a.bytes === b.bytes
            && !!a.truncated === !!b.truncated && JSON.stringify(a.value) === JSON.stringify(b.value);
    }

    // Applies one response of GET entries to the file the page holds. A reset and
    // a delta both carry every entry of the file, so either one replaces them, and
    // an unknown status counts as a reset. Returns null when nothing changed, or
    // { reset, changed, removed }: the keys a delta changed or removed, for the
    // rows to show it.
    function applyEnvelope(view, data) {
        if (!data) return null;
        if (data.cursor != null) view.cursor = data.cursor;
        if (data.status === 'unchanged') return null;
        var items = Array.isArray(data.items) ? data.items : [];
        var before = view.byKey;
        var byKey = new Map();
        items.forEach(function(item) { byKey.set(item.key, item); });
        view.entries = sortEntries(items);
        view.byKey = byKey;
        if (data.status !== 'delta') return { reset: true, changed: [], removed: [] };
        var changed = [];
        var removed = [];
        byKey.forEach(function(item, key) {
            if (!sameEntry(before.get(key), item)) changed.push(key);
        });
        before.forEach(function(item, key) {
            if (!byKey.has(key)) removed.push(key);
        });
        return { reset: false, changed: changed, removed: removed };
    }

    // An entry that the page wrote, from the response of POST edit. The cursor
    // stays, so the next poll still reads the file.
    function putEntry(view, item) {
        if (!item || item.key == null) return;
        view.byKey.set(item.key, item);
        view.entries = sortEntries(Array.from(view.byKey.values()));
    }

    // The names of the files in the order of their tabs: by key count, the fullest
    // first, and in the app's order for the same count (sort is stable). A poll
    // keeps the order the page has, so that a tab doesn't move under the pointer;
    // a new or a gone file, or Refresh, sorts them again.
    function orderFiles(files, order, resort) {
        var names = files.map(function(file) { return file.name; });
        if (!resort && order && order.length === names.length
                && order.every(function(name) { return names.indexOf(name) >= 0; })) {
            return order.slice();
        }
        return files.slice()
            .sort(function(a, b) { return (b.keyCount || 0) - (a.keyCount || 0); })
            .map(function(file) { return file.name; });
    }

    // Why the page can't change a file, or null when it can. The app decides it,
    // and the routes refuse a change to a read-only file; the page only shows it.
    function lockReason(file) {
        if (!file) return 'No file is open.';
        if (file.error) return 'The tab can\'t read this file: ' + file.error;
        if (!file.readOnly) return null;
        if (!file.registered && file.name === LUSTRO_FILE) {
            return 'This is Lustro\'s own file: it holds the console\'s token, so the tab never changes it.';
        }
        if (!file.registered && file.encrypted) {
            return 'The tab found this encrypted file on disk and reads it without its keys: an edit would write a plain value into it and break it for the app.';
        }
        if (!file.registered) {
            return 'The tab found this file on disk. A found file is read-only unless the app turns off discoveredReadOnly, because an edit to a library\'s file can break the library.';
        }
        if (file.encrypted && file.policy === 'keys_only') {
            return 'The app registered this file as read-only, or the file is encrypted and its instance can\'t decrypt it.';
        }
        return 'The app registered this file as read-only.';
    }

    function policyLabel(policy) {
        return POLICY_LABELS[policy] || String(policy || '');
    }

    function looksLikeJson(text) {
        var first = String(text).trim().charAt(0);
        return first === '{' || first === '[';
    }

    function isJsonContainer(text) {
        try {
            var parsed = JSON.parse(text);
            return parsed !== null && typeof parsed === 'object';
        } catch(e) {
            return false;
        }
    }

    // The editor in a row: a switch for a boolean, a field for an int or a short
    // string on one line that is not JSON, or null when the value opens in the dialog.
    function inlineKind(entry) {
        if (!entry || entry.masked || entry.truncated || !('value' in entry)) return null;
        if (entry.type === 'boolean' && typeof entry.value === 'boolean') return 'boolean';
        if (entry.type === 'int' && typeof entry.value === 'number') return 'int';
        if (entry.type === 'string' && typeof entry.value === 'string') {
            var text = entry.value;
            if (text.length < INLINE_MAX_CHARS && !/[\r\n]/.test(text) && !looksLikeJson(text)) return 'string';
        }
        return null;
    }

    function wholeNumber(text, range) {
        if (!/^[+-]?\d+$/.test(text)) return null;
        var number = BigInt(text);
        if (number < BigInt(range[0]) || number > BigInt(range[1])) return null;
        return number;
    }

    // Reads what a form holds as a value of type: { value } to send in POST edit,
    // or { error } to show. raw is the text of a field, or a switch's checked.
    function readValue(type, raw) {
        if (type === 'boolean') return { value: raw === true || raw === 'true' };
        var text = String(raw == null ? '' : raw);
        var trimmed = text.trim();
        if (type === 'string') return { value: text };
        if (type === 'int') {
            var int = wholeNumber(trimmed, INT_RANGE);
            if (int === null) return { error: 'An int is a whole number from ' + INT_RANGE[0] + ' to ' + INT_RANGE[1] + '.' };
            return { value: Number(int) };
        }
        if (type === 'long') {
            var long = wholeNumber(trimmed, LONG_RANGE);
            if (long === null) return { error: 'A long is a whole number from ' + LONG_RANGE[0] + ' to ' + LONG_RANGE[1] + '.' };
            // As a string: a JS number has no exact form for every long.
            return { value: long.toString() };
        }
        if (type === 'float') {
            if (NON_FINITE.indexOf(trimmed) >= 0) return { value: trimmed };
            if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(trimmed)) {
                return { error: 'A float is a number such as 1.5 or -2e3, or NaN, Infinity, or -Infinity.' };
            }
            var number = Number(trimmed);
            // Rounded to a float as the app's toFloat() rounds it, so that 3.4028235E38,
            // as the routes write Float.MAX_VALUE, is in range.
            if (!isFinite(Math.fround(number))) {
                return { error: 'The number is outside the range of a float, about ±3.4e38.' };
            }
            return { value: number };
        }
        if (type === 'string_set') {
            var error = { error: 'A string set is a JSON array of strings, such as ["work", "travel"].' };
            var parsed;
            try {
                parsed = JSON.parse(trimmed === '' ? '[]' : text);
            } catch(e) {
                return error;
            }
            if (!Array.isArray(parsed) || parsed.some(function(item) { return typeof item !== 'string'; })) return error;
            return { value: parsed };
        }
        return { error: 'The tab can\'t write a value of type ' + type + '.' };
    }

    // JSON text without the whitespace between its tokens. It keeps every number
    // and key as written, where JSON.stringify(JSON.parse(text)) would round a
    // long past 2^53 and drop a repeated key.
    function compactJson(text) {
        var out = [];
        var inString = false;
        for (var i = 0; i < text.length; i++) {
            var c = text.charAt(i);
            if (inString) {
                out.push(c);
                if (c === '\\') out.push(text.charAt(++i));
                else if (c === '"') inString = false;
            } else if (c === '"') {
                inString = true;
                out.push(c);
            } else if (c !== ' ' && c !== '\n' && c !== '\r' && c !== '\t') {
                out.push(c);
            }
        }
        return out.join('');
    }

    function defaultText(type) {
        if (type === 'string_set') return '[]';
        if (type === 'int' || type === 'long' || type === 'float') return '0';
        return '';
    }

    // The value field of the dialog for a value of type: a switch, an input, or a
    // text area, and the text it opens with. A JSON string opens indented, and a
    // set as an indented JSON array. A masked value opens empty.
    function editorFor(type, value, masked) {
        if (type === 'boolean') return { control: 'switch', checked: !masked && value === true };
        if (masked || value === undefined || value === null) {
            var text = defaultText(type);
            return { control: type === 'string_set' ? 'textarea' : 'input', text: masked && type !== 'string_set' ? '' : text };
        }
        if (type === 'string_set') {
            return { control: 'textarea', text: JSON.stringify(Array.isArray(value) ? value : [], null, 2) };
        }
        if (type === 'string') {
            var string = String(value);
            if (looksLikeJson(string) && isJsonContainer(string)) {
                return { control: 'textarea', text: window.debugFormatJson(string, 2), json: true, compact: compactJson(string) === string };
            }
            if (string.length > TEXTAREA_FROM_CHARS || /[\r\n]/.test(string)) return { control: 'textarea', text: string };
            return { control: 'input', text: string };
        }
        return { control: 'input', text: formatValue(type, value) };
    }

    // What Save sends. A JSON string opens indented: saved as it opened, it goes
    // back as the file held it, and edited, it goes back compact when it was.
    function valueToSave(type, raw, editor, original) {
        if (type === 'string' && editor && editor.json) {
            if (raw === editor.text) return { value: original };
            if (editor.compact && isJsonContainer(raw)) return { value: compactJson(raw) };
        }
        return readValue(type, raw);
    }

    // The highlighted JSON for the dialog's preview, or null when the value is not
    // JSON-shaped: a set, or a string that starts with { or [.
    function previewHtml(type, text) {
        if (type !== 'string_set' && !(type === 'string' && looksLikeJson(text))) return null;
        if (!isJsonContainer(text)) return '<span class="dc-comment">// not valid JSON yet</span>';
        return window.debugSyntaxHighlightJson(text, { indent: 2 });
    }

    function matches(entry, query, searchValues) {
        var q = String(query || '').toLowerCase();
        if (!q) return true;
        if (String(entry.key).toLowerCase().indexOf(q) >= 0) return true;
        if (!searchValues || entry.masked || entry.value == null) return false;
        var values = Array.isArray(entry.value) ? entry.value : [entry.value];
        return values.some(function(value) { return String(value).toLowerCase().indexOf(q) >= 0; });
    }

    function keysLabel(count) {
        return count === 1 ? '1 key' : count + ' keys';
    }

    function countLabel(shown, total) {
        return shown === total ? keysLabel(total) : shown + ' of ' + keysLabel(total);
    }

    function bytesLabel(bytes) {
        return bytes === 1 ? '1 byte' : (bytes || 0) + ' bytes';
    }

    // A whole float keeps its point, as the app's getFloat shows it: 1.0, not 1.
    function formatValue(type, value) {
        if (Array.isArray(value)) return JSON.stringify(value);
        if (type === 'float' && typeof value === 'number' && Number.isInteger(value) && Math.abs(value) < 1e21) return value.toFixed(1);
        return String(value);
    }

    function valueText(entry) {
        return formatValue(entry.type, entry.value);
    }

    // A value that the policy leaves out: the reason, in the words of the file's policy.
    function hiddenText(entry, file) {
        var size = bytesLabel(entry.bytes);
        if (file && file.encrypted && file.policy === 'keys_only') return '// encrypted · ' + size;
        if (file && file.policy === 'keys_only') return '// keys only · ' + size;
        return '// masked · ' + size;
    }

    function hiddenTitle(file) {
        if (file && file.encrypted && file.policy === 'keys_only') return 'The file is encrypted, and the tab shows its keys only.';
        if (file && file.policy === 'keys_only') return 'The file\'s policy shows its keys only.';
        return 'The key looks like it holds a secret, so the file\'s policy leaves its value out.';
    }

    function badgeHtml(type) {
        var color = TYPE_COLORS[type];
        return '<span class="dc-badge prefs-badge"' + (color ? ' style="--c: var(' + color + ')"' : '') + '>'
            + window.debugEscapeHtml(TYPE_LABELS[type] || type) + '</span>';
    }

    function fileTitle(file) {
        var parts = [file.name, keysLabel(file.keyCount || 0), file.registered ? 'registered' : 'found on disk',
            policyLabel(file.policy).toLowerCase(), file.readOnly ? 'read-only' : 'writable'];
        if (file.encrypted) parts.push('encrypted');
        return parts.join(' · ') + '.';
    }

    function fileTabHtml(file, active) {
        var esc = window.debugEscapeHtml;
        return '<button type="button" class="dc-tab prefs-file' + (active ? ' dc-tab--active' : '') + '" role="tab"'
            + ' aria-selected="' + (active ? 'true' : 'false') + '" data-action="selectFile" data-file="' + esc(file.name) + '"'
            + ' title="' + esc(fileTitle(file)) + '">'
            + (file.encrypted ? '<span class="prefs-file__lock" aria-hidden="true">🔒</span>' : '')
            + esc(file.name)
            + '<span class="prefs-file__count">(' + esc(String(file.keyCount || 0)) + ')</span>'
            + '</button>';
    }

    // The facts of the open file: how many keys show, where it comes from, its
    // policy, and whether the tab may change it.
    function factsHtml(file, shown, total) {
        var esc = window.debugEscapeHtml;
        function tag(text, title, color) {
            return '<span class="dc-tag"' + (color ? ' style="--c: var(' + color + ')"' : '') + ' title="' + esc(title) + '">' + esc(text) + '</span>';
        }
        var html = '<span class="prefs-count">' + esc(countLabel(shown, total)) + '</span>';
        html += file.registered
            ? tag('Registered', 'The app registered this file, with its policy and read-only flag.')
            : tag('Found on disk', 'The tab found this file in the app\'s shared_prefs directory, such as the file of a library.');
        html += tag(policyLabel(file.policy), {
            visible: 'The policy shows every value.',
            mask_sensitive: 'The policy leaves out the value of each key that looks like it holds a secret, such as a token or a password.',
            keys_only: 'The policy leaves out every value: the keys, the types, and the sizes show.'
        }[file.policy] || 'The file\'s value policy.');
        html += file.encrypted ? tag('Encrypted', 'The file on disk is encrypted.', '--accent-2') : '';
        var lock = lockReason(file);
        html += lock ? tag('Read-only', lock, '--lvl-w') : tag('Writable', 'The tab may add, edit, delete, and clear keys.', '--live');
        return html;
    }

    // Tells a value that opens the dialog from a field that edits in the row.
    var OPEN_MARK = '<span class="prefs-value__open" aria-hidden="true">⤢</span>';

    function valueHtml(entry, ctx) {
        var esc = window.debugEscapeHtml;
        var key = esc(entry.key);
        var lock = ctx.lock;
        if (entry.masked || !('value' in entry)) {
            var hidden = '<span class="dc-comment prefs-hidden">' + esc(hiddenText(entry, ctx.file)) + '</span>';
            if (lock || entry.type === 'unknown') return '<span class="prefs-hidden-wrap" title="' + esc(hiddenTitle(ctx.file)) + '">' + hidden + '</span>';
            return '<button type="button" class="prefs-value prefs-value--hidden" data-action="openEdit" data-key="' + key + '"'
                + ' title="' + esc(hiddenTitle(ctx.file) + ' Click to write a new value.') + '">' + hidden + OPEN_MARK + '</button>';
        }
        var kind = inlineKind(entry);
        if (kind === 'boolean') {
            var on = entry.value === true;
            return '<span class="prefs-bool"><label class="dc-switch" title="' + esc(lock ? 'Read-only. ' + lock : 'Flip to save the other value.') + '">'
                + '<input type="checkbox" data-inline="boolean" data-key="' + key + '" aria-label="' + key + '"' + (on ? ' checked' : '') + (lock ? ' disabled' : '') + '>'
                + '<span class="dc-switch__track"></span></label>'
                + '<span class="prefs-bool__label">' + (on ? 'true' : 'false') + '</span></span>';
        }
        if (kind && !lock) {
            return '<div class="dc-field prefs-inline"><input type="text" class="dc-input" data-inline="' + kind + '" data-key="' + key + '"'
                + ' value="' + esc(String(entry.value)) + '" aria-label="' + key + '" autocomplete="off" spellcheck="false"'
                + (kind === 'int' ? ' inputmode="numeric"' : '')
                + ' title="Edit, then press Enter or click elsewhere to save. Escape puts the value back."></div>';
        }
        var text = valueText(entry);
        var title = lock ? 'Read-only. ' + lock + ' Click to see the whole value.' : 'Click to edit.';
        if (entry.truncated) title = 'The value is longer than 4096 characters; the dialog reads it whole. ' + title;
        return '<button type="button" class="prefs-value" data-action="openEdit" data-key="' + key + '" title="' + esc(title) + '">'
            + (text === '' ? '<span class="dc-comment">// empty string</span>' : '<span class="prefs-value__text">' + esc(text) + '</span>')
            + (entry.truncated ? '<span class="dc-comment prefs-value__cut">… cut</span>' : '')
            + OPEN_MARK + '</button>';
    }

    // One row of the table. Every key and value is escaped: a file can hold any
    // text, markup too. ctx: { file, lock, changed (a Set of keys), saved (a Map
    // of key to the time of the page's own save), now }.
    function rowHtml(entry, ctx) {
        var esc = window.debugEscapeHtml;
        var key = esc(entry.key);
        var changed = ctx.changed && ctx.changed.has(entry.key);
        var savedAt = ctx.saved && ctx.saved.get(entry.key);
        var elapsed = savedAt != null ? (ctx.now || Date.now()) - savedAt : -1;
        // A row drawn again in the middle of the flash goes on from where it was.
        var saved = elapsed >= 0 && elapsed < SAVED_FLASH_MS
            ? '<span class="prefs-saved" style="animation-delay: -' + elapsed + 'ms">✓ Saved</span>'
            : '';
        var lockTitle = ctx.lock ? 'Read-only. ' + ctx.lock : 'Delete this key from the file.';
        return '<tr class="dc-row prefs-row' + (changed ? ' prefs-row--changed' : '') + '" data-key="' + key + '">'
            + '<td class="dc-cell prefs-cell--key">' + key + '</td>'
            + '<td class="dc-cell prefs-cell--type" title="' + esc(bytesLabel(entry.bytes)) + '">' + badgeHtml(entry.type) + '</td>'
            + '<td class="dc-cell prefs-cell--value"><div class="prefs-value-wrap">' + valueHtml(entry, ctx) + saved + '</div></td>'
            + '<td class="dc-cell prefs-cell--actions"><button type="button" class="dc-btn dc-btn--sm dc-btn--danger" data-action="confirmDelete"'
            + ' data-key="' + key + '" title="' + esc(lockTitle) + '"' + (ctx.lock ? ' disabled' : '') + '>Delete</button></td>'
            + '</tr>';
    }

    function readSettings(storage) {
        var settings = { activeFile: null, autoRefresh: true, searchValues: false, intervalMs: DEFAULT_INTERVAL_MS };
        try {
            var stored = JSON.parse(storage.getItem(STORAGE_KEY) || 'null');
            if (stored && typeof stored === 'object') {
                if (typeof stored.activeFile === 'string') settings.activeFile = stored.activeFile;
                if (typeof stored.autoRefresh === 'boolean') settings.autoRefresh = stored.autoRefresh;
                if (typeof stored.searchValues === 'boolean') settings.searchValues = stored.searchValues;
                if (typeof stored.intervalMs === 'number' && stored.intervalMs >= MIN_INTERVAL_MS) {
                    settings.intervalMs = Math.round(stored.intervalMs);
                }
            }
        } catch(e) {}
        return settings;
    }

    function readInterval(text) {
        var ms = parseInt(String(text).trim(), 10);
        return isNaN(ms) ? DEFAULT_INTERVAL_MS : Math.max(MIN_INTERVAL_MS, ms);
    }

    function readWidths(storage) {
        var widths = {};
        try {
            var stored = JSON.parse(storage.getItem(WIDTHS_KEY) || 'null') || {};
            Object.keys(DEFAULT_WIDTHS).forEach(function(column) {
                var width = stored[column];
                if (typeof width === 'number' && width >= MIN_COLUMN_WIDTH) widths[column] = Math.round(width);
            });
        } catch(e) {}
        return widths;
    }

    window.prefsParseJson = parseJson;
    window.prefsNewView = newView;
    window.prefsApplyEnvelope = applyEnvelope;
    window.prefsPutEntry = putEntry;
    window.prefsOrderFiles = orderFiles;
    window.prefsLockReason = lockReason;
    window.prefsInlineKind = inlineKind;
    window.prefsReadValue = readValue;
    window.prefsCompactJson = compactJson;
    window.prefsEditorFor = editorFor;
    window.prefsValueToSave = valueToSave;
    window.prefsPreviewHtml = previewHtml;
    window.prefsMatches = matches;
    window.prefsCountLabel = countLabel;
    window.prefsHiddenText = hiddenText;
    window.prefsFileTabHtml = fileTabHtml;
    window.prefsFactsHtml = factsHtml;
    window.prefsRowHtml = rowHtml;
    window.prefsReadSettings = readSettings;
    window.prefsReadInterval = readInterval;

    // ── The page ────────────────────────────────────────────────

    var els = {};
    var settings = null;
    var widths = {};
    var files = [];
    var byName = {};
    var order = null;
    var loaded = false;
    var active = null;
    var view = newView();
    // A change of the open file starts a generation: a response to a request of
    // an earlier one is late and changes nothing.
    var generation = 0;
    var entriesInFlight = false;
    var entriesAgain = false;
    var poll = null;
    var query = '';
    var saved = new Map();
    var changedKeys = new Set();
    var changedTimer = null;
    var savedTimer = null;
    // Set while a field in a row has the focus: a new poll draws the rows when it loses it.
    var pendingRender = false;
    var lastFilesHtml = '';
    var editModal = null;
    var confirmModal = null;
    // The dialog's state: { mode: 'add' | 'edit' | 'view', file, key, type, opened, editor, original, loading }.
    var edit = null;
    var confirmAction = null;
    var previewTimer = null;

    function storage() {
        try { return window.localStorage; } catch(e) { return null; }
    }

    function store(key, value) {
        try { window.localStorage.setItem(key, JSON.stringify(value)); } catch(e) {}
    }

    function storeSettings() {
        store(STORAGE_KEY, {
            activeFile: active,
            autoRefresh: settings.autoRefresh,
            searchValues: settings.searchValues,
            intervalMs: settings.intervalMs
        });
    }

    // The message of a failed request: the error envelope's, or a status.
    function errorMessage(e) {
        if (e && e.response) {
            return e.response.json().then(function(envelope) {
                return envelope && envelope.message ? envelope.message : 'HTTP ' + e.status;
            }, function() {
                return 'HTTP ' + e.status;
            });
        }
        return Promise.resolve('The app did not answer. Try again.');
    }

    function post(path, body) {
        return window.debugFetch(window.lustroApiUrl(path), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }).then(function(r) { return r.text(); }).then(parseJson, function(e) {
            return errorMessage(e).then(function(message) { throw new Error(message); });
        });
    }

    // ── Files ───────────────────────────────────────────────────

    function startPolling() {
        stopPolling();
        if (!settings.autoRefresh) return;
        poll = window.debugPoll(function() { return window.lustroApiUrl('files'); }, settings.intervalMs, function(data) {
            onFiles(data, false);
        });
    }

    function stopPolling() {
        if (poll) {
            poll.stop();
            poll = null;
        }
    }

    function refreshNow(resort) {
        return window.debugFetch(window.lustroApiUrl('files'))
            .then(function(r) { return r.json(); })
            .then(function(data) { onFiles(data, resort); })
            .catch(function() {
                window.debugToast('The app did not answer. Try again.', 'error');
            });
    }

    function onFiles(data, resort) {
        files = data && Array.isArray(data.items) ? data.items : [];
        byName = {};
        files.forEach(function(file) { byName[file.name] = file; });
        order = orderFiles(files, order, resort);
        var first = !loaded;
        loaded = true;
        if (!active || !byName[active]) {
            var next = settings.activeFile && byName[settings.activeFile] ? settings.activeFile : order[0] || null;
            if (next !== active) openFile(next);
        }
        renderFiles();
        if (first) scrollTabIntoView();
        renderHead();
        var file = active ? byName[active] : null;
        if (!file) {
            renderRows();
            return;
        }
        if (file.error) {
            // Read again once the file reads.
            view.cursor = null;
            renderRows();
            return;
        }
        if (view.file !== active || file.cursor !== view.cursor) {
            loadEntries();
        } else if (file.keyCount !== view.entries.length) {
            // The cursor didn't move, but the file holds another number of keys: a
            // change that called no listener, such as the app's clear() before API
            // 30. A read without the cursor gets the file as it is.
            view.cursor = null;
            loadEntries();
        }
    }

    function openFile(name) {
        active = name;
        generation++;
        view = newView();
        saved.clear();
        changedKeys.clear();
        // The banner is about the file that was open.
        showBanner('');
        if (name) {
            settings.activeFile = name;
            storeSettings();
        }
    }

    function selectFile(name) {
        if (!byName[name] || name === active) return;
        openFile(name);
        renderFiles();
        renderHead();
        renderRows();
        scrollTabIntoView();
        if (!byName[name].error) loadEntries();
    }

    function renderFiles() {
        var html = order ? order.map(function(name) { return fileTabHtml(byName[name], name === active); }).join('') : '';
        if (html !== lastFilesHtml) {
            els.files.innerHTML = html;
            lastFilesHtml = html;
        }
        els.files.hidden = !html;
    }

    function scrollTabIntoView() {
        var tab = els.files.querySelector('.dc-tab--active');
        if (tab && tab.scrollIntoView) tab.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    }

    function shownEntries() {
        return view.entries.filter(function(entry) { return matches(entry, query, settings.searchValues); });
    }

    function renderHead() {
        var file = active ? byName[active] : null;
        els.panel.hidden = !file;
        els.none.hidden = !!file || !loaded;
        if (!file) return;
        var total = view.file === active ? view.entries.length : file.keyCount || 0;
        var shown = view.file === active ? shownEntries().length : total;
        els.name.textContent = file.name;
        els.facts.innerHTML = factsHtml(file, shown, total);
        var lock = lockReason(file);
        els.add.disabled = !!lock;
        els.add.title = lock ? 'Read-only. ' + lock : 'Add a key to this file.';
        els.clear.disabled = !!lock || total === 0;
        els.clear.title = lock ? 'Read-only. ' + lock : total === 0 ? 'The file has no keys.' : 'Remove every key of this file.';
        if (file.error) showBanner('The tab can\'t read this file: ' + file.error);
    }

    function showBanner(text) {
        els.banner.textContent = text;
        els.banner.hidden = !text;
    }

    // ── Entries ─────────────────────────────────────────────────

    function loadEntries() {
        if (entriesInFlight) {
            entriesAgain = true;
            return;
        }
        if (!active) return;
        entriesInFlight = true;
        var requestGeneration = generation;
        var path = 'entries?file=' + encodeURIComponent(active) + (view.cursor ? '&cursor=' + encodeURIComponent(view.cursor) : '');
        window.debugFetch(window.lustroApiUrl(path))
            .then(function(r) { return r.text(); })
            .then(function(text) {
                if (requestGeneration !== generation) return;
                showBanner('');
                onEntries(parseJson(text));
            })
            .catch(function(e) {
                if (requestGeneration !== generation) return;
                return errorMessage(e).then(function(message) {
                    if (requestGeneration === generation) showBanner('The app did not send the file: ' + message);
                });
            })
            .then(function() {
                entriesInFlight = false;
                if (entriesAgain) {
                    entriesAgain = false;
                    loadEntries();
                }
            });
    }

    function onEntries(data) {
        var first = view.file !== active;
        view.file = active;
        var changes = applyEnvelope(view, data);
        if (!changes && !first) return;
        if (changes && !changes.reset) flashKeys(changes.changed);
        renderHead();
        renderRows();
        if (changes) checkEditedEntry();
    }

    function flashKeys(keys) {
        if (!keys.length) return;
        keys.forEach(function(key) { changedKeys.add(key); });
        if (changedTimer) clearTimeout(changedTimer);
        changedTimer = setTimeout(function() {
            changedTimer = null;
            changedKeys.clear();
        }, CHANGED_FLASH_MS);
    }

    function inlineFocused() {
        var el = document.activeElement;
        return !!el && els.rows.contains(el) && el.dataset.inline && el.dataset.inline !== 'boolean';
    }

    function renderRows() {
        if (inlineFocused()) {
            pendingRender = true;
            return;
        }
        pendingRender = false;
        var file = active ? byName[active] : null;
        if (!file || view.file !== active) {
            els.rows.innerHTML = '';
            els.empty.hidden = !file || !!file.error;
            els.empty.textContent = '// reading the file…';
            return;
        }
        var ctx = { file: file, lock: lockReason(file), changed: changedKeys, saved: saved, now: Date.now() };
        var shown = shownEntries();
        els.rows.innerHTML = shown.map(function(entry) { return rowHtml(entry, ctx); }).join('');
        els.empty.hidden = shown.length > 0;
        els.empty.textContent = view.entries.length ? '// no keys match the search' : '// the file has no keys';
    }

    // ── Editing in a row ────────────────────────────────────────

    function revertInline(input, entry) {
        if (!entry) return;
        if (input.dataset.inline === 'boolean') {
            input.checked = entry.value === true;
            var label = input.closest('.prefs-bool').querySelector('.prefs-bool__label');
            if (label) label.textContent = input.checked ? 'true' : 'false';
        } else {
            input.value = String(entry.value);
        }
    }

    function onInlineChange(input) {
        var key = input.dataset.key;
        var kind = input.dataset.inline;
        var entry = view.byKey.get(key);
        var file = active;
        var raw = kind === 'boolean' ? input.checked : input.value;
        if (kind !== 'boolean' && entry && String(entry.value) === raw) return;
        var read = readValue(kind, raw);
        if (read.error) {
            window.debugToast(read.error, 'error');
            revertInline(input, entry);
            return;
        }
        if (kind === 'boolean') {
            var label = input.closest('.prefs-bool').querySelector('.prefs-bool__label');
            if (label) label.textContent = input.checked ? 'true' : 'false';
        }
        // The rows show the new value at once, so that rows drawn before the
        // response, when the field loses the focus, don't show the old one.
        var written = entry ? Object.assign({}, entry, { value: read.value }) : null;
        if (written) putEntry(view, written);
        post('edit', { file: file, key: key, type: kind, value: read.value }).then(function(data) {
            if (file !== active) return;
            saved.set(key, Date.now());
            putEntry(view, data && data.item);
            renderRows();
            scheduleSavedCleanup();
            refreshNow(false);
        }).catch(function(e) {
            window.debugToast('Not saved: ' + e.message, 'error');
            if (file !== active || !written || view.byKey.get(key) !== written) return;
            putEntry(view, entry);
            renderRows();
        });
    }

    function scheduleSavedCleanup() {
        if (savedTimer) clearTimeout(savedTimer);
        savedTimer = setTimeout(function() {
            savedTimer = null;
            var now = Date.now();
            saved.forEach(function(at, key) {
                if (now - at >= SAVED_FLASH_MS) saved.delete(key);
            });
        }, SAVED_FLASH_MS);
    }

    // ── The dialog: add, edit, or view a key ────────────────────

    function control() {
        return document.getElementById('prefs-edit-input');
    }

    function renderTypeChips() {
        els.editType.innerHTML = '<div class="prefs-types" role="radiogroup" aria-label="Type">' + TYPES.map(function(type) {
            var on = edit.type === type;
            return '<button type="button" class="prefs-type' + (on ? ' prefs-type--on' : '') + '" role="radio" aria-checked="' + (on ? 'true' : 'false') + '"'
                + ' style="--c: var(' + TYPE_COLORS[type] + ')" data-action="pickType" data-type="' + type + '">'
                + window.debugEscapeHtml(TYPE_LABELS[type]) + '</button>';
        }).join('') + '</div>';
    }

    function renderEditor(editor, readOnly) {
        edit.editor = editor;
        var label = 'aria-labelledby="prefs-edit-value-label"';
        if (editor.control === 'switch') {
            els.editValue.innerHTML = '<span class="prefs-bool"><label class="dc-switch"><input type="checkbox" id="prefs-edit-input" ' + label
                + (editor.checked ? ' checked' : '') + (readOnly ? ' disabled' : '') + '><span class="dc-switch__track"></span></label>'
                + '<span class="prefs-bool__label" id="prefs-edit-bool">' + (editor.checked ? 'true' : 'false') + '</span></span>';
        } else if (editor.control === 'textarea') {
            els.editValue.innerHTML = '<textarea id="prefs-edit-input" class="dc-textarea prefs-textarea" rows="10" spellcheck="false" ' + label
                + (readOnly ? ' readonly' : '') + '></textarea>';
            control().value = editor.text;
        } else {
            var numeric = edit.type === 'int' || edit.type === 'long' ? 'numeric' : edit.type === 'float' ? 'decimal' : null;
            els.editValue.innerHTML = '<input type="text" id="prefs-edit-input" class="dc-input--block" autocomplete="off" spellcheck="false" ' + label
                + (numeric ? ' inputmode="' + numeric + '"' : '') + (readOnly ? ' readonly' : '') + '>';
            control().value = editor.text;
        }
        updatePreview();
    }

    function updatePreview() {
        if (previewTimer) {
            clearTimeout(previewTimer);
            previewTimer = null;
        }
        var el = control();
        var html = el && el.type !== 'checkbox' ? previewHtml(edit.type, el.value) : null;
        els.previewRow.hidden = html === null;
        els.preview.innerHTML = html || '';
    }

    function setEditMessage(el, text) {
        el.textContent = text || '';
        el.hidden = !text;
    }

    function setButton(button, enabled, title) {
        button.disabled = !enabled;
        button.title = title;
    }

    function openEditor(mode, file) {
        setEditMessage(els.editError, '');
        setEditMessage(els.editNote, '');
        setEditMessage(els.editHint, '');
        els.editDialog.classList.toggle('dc-modal--wide', mode !== 'add');
        els.editTitle.textContent = mode === 'add' ? 'Add key' : mode === 'view' ? 'View key' : 'Edit key';
        var lock = lockReason(file);
        els.editDelete.hidden = mode === 'add';
        setButton(els.editDelete, !lock, lock ? 'Read-only. ' + lock : 'Delete this key from the file.');
        if (mode === 'view') {
            setButton(els.editSave, false, lock ? 'Read-only. ' + lock : 'The tab can\'t write a value of this type.');
        } else {
            setButton(els.editSave, true, 'Save the value to the file. Enter in a one-line field, or Ctrl+Enter, does the same.');
        }
        editModal.show();
    }

    function openAdd() {
        var file = active ? byName[active] : null;
        if (!file || lockReason(file)) return;
        edit = { mode: 'add', file: active, type: 'string' };
        els.editKey.innerHTML = '<input type="text" id="prefs-edit-key-input" class="dc-input--block" placeholder="key name" autocomplete="off" spellcheck="false" aria-labelledby="prefs-edit-key-label">';
        renderTypeChips();
        renderEditor(editorFor('string', ''), false);
        openEditor('add', file);
        var keyInput = document.getElementById('prefs-edit-key-input');
        if (keyInput) keyInput.focus();
    }

    function pickType(type) {
        if (!edit || edit.mode !== 'add' || TYPES.indexOf(type) < 0 || type === edit.type) return;
        edit.type = type;
        renderTypeChips();
        renderEditor(editorFor(type, type === 'boolean' ? false : defaultText(type)), false);
    }

    function openEdit(key) {
        var entry = view.byKey.get(key);
        var file = active ? byName[active] : null;
        if (!entry || !file) return;
        var lock = lockReason(file);
        var mode = lock || entry.type === 'unknown' ? 'view' : 'edit';
        edit = { mode: mode, file: active, key: key, type: entry.type, opened: entry, original: entry.value };
        els.editKey.textContent = key;
        els.editType.innerHTML = badgeHtml(entry.type);
        openEditor(mode, file);
        if (entry.truncated) {
            loadWholeEntry(edit, mode === 'view');
        } else {
            fillEditor(entry, mode === 'view');
        }
    }

    function fillEditor(entry, readOnly) {
        var file = byName[edit.file];
        renderEditor(editorFor(entry.type, entry.value, entry.masked || !('value' in entry)), readOnly);
        if (entry.masked) {
            setEditMessage(els.editHint, '// ' + hiddenTitle(file) + (readOnly ? '' : ' What you save replaces the value.'));
        } else if (entry.type === 'unknown') {
            setEditMessage(els.editHint, '// a value that no SharedPreferences getter returns: the tab can show it, not write it');
        }
        var el = control();
        if (el && !readOnly && el.type !== 'checkbox') {
            el.focus();
            if (el.tagName === 'INPUT') el.select();
        }
    }

    // GET entries cuts a long value; the dialog reads it whole before it can be edited.
    function loadWholeEntry(state, readOnly) {
        state.loading = true;
        els.editSave.disabled = true;
        els.editValue.innerHTML = '<div class="dc-comment">// reading the whole value…</div>';
        els.previewRow.hidden = true;
        var path = 'entry?file=' + encodeURIComponent(state.file) + '&key=' + encodeURIComponent(state.key);
        window.debugFetch(window.lustroApiUrl(path))
            .then(function(r) { return r.text(); })
            .then(function(text) {
                if (edit !== state) return;
                var item = parseJson(text).item;
                state.loading = false;
                state.original = item.value;
                fillEditor(item, readOnly);
                if (!readOnly) els.editSave.disabled = false;
            })
            .catch(function(e) {
                if (edit !== state) return;
                errorMessage(e).then(function(message) {
                    els.editValue.innerHTML = '';
                    setEditMessage(els.editError, 'The app did not send the value: ' + message);
                });
            });
    }

    // While the dialog is open, a poll can bring a change of the same key from the app.
    function checkEditedEntry() {
        if (!edit || edit.mode === 'add' || edit.file !== active || !editModal.isVisible()) return;
        var now = view.byKey.get(edit.key);
        if (!now) {
            setEditMessage(els.editNote, 'The app removed this key after the dialog opened.' + (edit.mode === 'edit' ? ' Save writes it again.' : ''));
        } else if (!sameEntry(now, edit.opened)) {
            var shown = now.masked || !('value' in now) ? '' : ': it is now ' + clip(valueText(now));
            setEditMessage(els.editNote, 'The app changed this value after the dialog opened' + shown + '.' + (edit.mode === 'edit' ? ' Save writes yours over it.' : ''));
        }
    }

    function clip(text) {
        return text.length > 80 ? text.slice(0, 80) + '…' : text;
    }

    function saveEdit() {
        if (!edit || edit.mode === 'view' || edit.loading || els.editSave.disabled) return;
        var state = edit;
        var key = state.key;
        if (state.mode === 'add') {
            var keyInput = document.getElementById('prefs-edit-key-input');
            key = keyInput ? keyInput.value.trim() : '';
            if (!key) {
                setEditMessage(els.editError, 'Enter a key.');
                return;
            }
            if (view.byKey.has(key)) {
                setEditMessage(els.editError, 'The file already has the key "' + key + '". Open it in the list to change its value.');
                return;
            }
        }
        var el = control();
        if (!el) return;
        var raw = el.type === 'checkbox' ? el.checked : el.value;
        var read = valueToSave(state.type, raw, state.editor, state.original);
        if (read.error) {
            setEditMessage(els.editError, read.error);
            return;
        }
        setEditMessage(els.editError, '');
        els.editSave.disabled = true;
        post('edit', { file: state.file, key: key, type: state.type, value: read.value }).then(function(data) {
            if (edit === state) editModal.hide();
            window.debugToast(state.mode === 'add' ? 'Added ' + key : 'Saved ' + key, 'success');
            if (state.file !== active) return;
            saved.set(key, Date.now());
            putEntry(view, data && data.item);
            renderHead();
            renderRows();
            scheduleSavedCleanup();
            refreshNow(false);
        }).catch(function(e) {
            if (edit !== state) return;
            els.editSave.disabled = false;
            setEditMessage(els.editError, 'Not saved: ' + e.message);
        });
    }

    // ── Delete and clear ────────────────────────────────────────

    function showConfirm(title, message, ok, action) {
        els.confirmTitle.textContent = title;
        els.confirmMessage.textContent = message;
        els.confirmOk.textContent = ok;
        confirmAction = action;
        confirmModal.show();
        els.confirmOk.focus();
    }

    // file is the dialog's file, which a poll can have closed while the dialog was open.
    function confirmDelete(key, file) {
        file = file || active;
        if (!file || !byName[file] || lockReason(byName[file])) return;
        showConfirm('Delete key', 'Delete the key "' + key + '" from the file ' + file + '?', 'Delete', function() {
            post('delete', { file: file, key: key }).then(function(data) {
                window.debugToast(data && data.deleted === false ? 'The file had no key ' + key : 'Deleted ' + key, 'success');
                refreshNow(false);
            }).catch(function(e) {
                window.debugToast('Not deleted: ' + e.message, 'error');
            });
        });
    }

    function confirmClear() {
        var file = active;
        if (!file || lockReason(byName[file])) return;
        var count = view.file === file ? view.entries.length : byName[file].keyCount || 0;
        showConfirm('Clear file', 'Remove all ' + keysLabel(count) + ' from the file ' + file + '? The app can\'t get them back.', 'Clear file', function() {
            post('clear', { file: file, confirm: true }).then(function(data) {
                var removed = data && typeof data.removed === 'number' ? data.removed : count;
                window.debugToast('Removed ' + keysLabel(removed) + ' from ' + file, 'success');
                refreshNow(false);
            }).catch(function(e) {
                window.debugToast('Not cleared: ' + e.message, 'error');
            });
        });
    }

    // ── Controls ────────────────────────────────────────────────

    function renderControls() {
        els.auto.checked = settings.autoRefresh;
        els.interval.value = String(settings.intervalMs);
        els.values.classList.toggle('dc-btn--active', settings.searchValues);
        els.values.setAttribute('aria-pressed', settings.searchValues ? 'true' : 'false');
    }

    function columnWidth(column) {
        return widths[column] || DEFAULT_WIDTHS[column];
    }

    function applyWidths() {
        var fixed = ACTIONS_WIDTH;
        Object.keys(DEFAULT_WIDTHS).forEach(function(column) {
            els.table.querySelector('col[data-col="' + column + '"]').style.width = columnWidth(column) + 'px';
            fixed += columnWidth(column);
        });
        els.table.querySelector('col[data-col="actions"]').style.width = ACTIONS_WIDTH + 'px';
        // The value takes what the other columns leave; below its minimum, the table scrolls sideways.
        els.table.style.minWidth = (fixed + MIN_VALUE_WIDTH) + 'px';
    }

    function startResize(handle, ev) {
        var column = handle.dataset.colResize;
        var startX = ev.clientX;
        var startWidth = handle.parentNode.getBoundingClientRect().width;
        ev.preventDefault();
        handle.classList.add('prefs-resize--dragging');
        document.body.style.cursor = 'col-resize';
        function onMove(e) {
            widths[column] = Math.max(MIN_COLUMN_WIDTH, Math.round(startWidth + e.clientX - startX));
            applyWidths();
        }
        function onUp() {
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('mouseup', onUp);
            handle.classList.remove('prefs-resize--dragging');
            document.body.style.cursor = '';
            store(WIDTHS_KEY, widths);
        }
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
    }

    var CLICK_ACTIONS = {
        // Without the cursor, so that the file is read whole: it also shows a
        // change that moved no cursor.
        refresh: function() {
            view.cursor = null;
            refreshNow(true);
        },
        selectFile: function(el) { selectFile(el.dataset.file); },
        clearSearch: function() {
            els.q.value = '';
            onSearch();
            els.q.focus();
        },
        toggleValues: function() {
            settings.searchValues = !settings.searchValues;
            storeSettings();
            renderControls();
            renderHead();
            renderRows();
        },
        openAdd: openAdd,
        confirmClear: confirmClear,
        openEdit: function(el) { openEdit(el.dataset.key); },
        confirmDelete: function(el) { confirmDelete(el.dataset.key); },
        pickType: function(el) { pickType(el.dataset.type); },
        saveEdit: saveEdit,
        closeEdit: function() { editModal.hide(); },
        deleteFromEdit: function() {
            if (!edit || edit.mode === 'add') return;
            var key = edit.key;
            var file = edit.file;
            editModal.hide();
            confirmDelete(key, file);
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

    function onSearch() {
        query = els.q.value;
        els.qClear.hidden = !query;
        renderHead();
        renderRows();
    }

    function setupEvents() {
        var root = els.root;
        root.addEventListener('click', function(ev) {
            var el = ev.target.closest('[data-action]');
            if (!el || !root.contains(el) || el.disabled) return;
            var fn = CLICK_ACTIONS[el.dataset.action];
            if (fn) fn(el, ev);
        });
        root.addEventListener('input', function(ev) {
            var target = ev.target;
            if (target === els.q) {
                onSearch();
            } else if (target.id === 'prefs-edit-input' && target.type !== 'checkbox') {
                if (previewTimer) clearTimeout(previewTimer);
                previewTimer = setTimeout(updatePreview, PREVIEW_DEBOUNCE_MS);
            }
        });
        root.addEventListener('change', function(ev) {
            var target = ev.target;
            if (target === els.auto) {
                settings.autoRefresh = els.auto.checked;
                storeSettings();
                if (settings.autoRefresh) startPolling();
                else stopPolling();
            } else if (target === els.interval) {
                settings.intervalMs = readInterval(els.interval.value);
                storeSettings();
                renderControls();
                if (settings.autoRefresh) startPolling();
            } else if (target.dataset && target.dataset.inline) {
                onInlineChange(target);
            } else if (target.id === 'prefs-edit-input' && target.type === 'checkbox') {
                var label = document.getElementById('prefs-edit-bool');
                if (label) label.textContent = target.checked ? 'true' : 'false';
            }
        });
        els.rows.addEventListener('keydown', function(ev) {
            var input = ev.target;
            if (!input.dataset || !input.dataset.inline || input.dataset.inline === 'boolean') return;
            if (ev.key === 'Enter') {
                ev.preventDefault();
                input.blur();
            } else if (ev.key === 'Escape') {
                // Put the value back before the blur, so that no change fires.
                ev.preventDefault();
                revertInline(input, view.byKey.get(input.dataset.key));
                input.blur();
            }
        });
        els.rows.addEventListener('focusout', function() {
            // After the change that the blur fires, which may draw the rows itself.
            setTimeout(function() {
                if (pendingRender) renderRows();
            }, 0);
        });
        els.interval.addEventListener('keydown', function(ev) {
            if (ev.key === 'Enter') els.interval.blur();
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
        els.table.querySelector('thead').addEventListener('mousedown', function(ev) {
            var handle = ev.target.closest('.prefs-resize');
            if (handle && ev.button === 0) startResize(handle, ev);
        });
        els.table.querySelector('thead').addEventListener('dblclick', function(ev) {
            var handle = ev.target.closest('.prefs-resize');
            if (!handle) return;
            delete widths[handle.dataset.colResize];
            store(WIDTHS_KEY, widths);
            applyWidths();
        });
    }

    function init() {
        var root = document.getElementById('prefs-root');
        if (!root) return;
        els = {
            root: root,
            auto: document.getElementById('prefs-auto'),
            interval: document.getElementById('prefs-interval'),
            files: document.getElementById('prefs-files'),
            panel: document.getElementById('prefs-panel'),
            none: document.getElementById('prefs-none'),
            name: document.getElementById('prefs-name'),
            facts: document.getElementById('prefs-facts'),
            q: document.getElementById('prefs-q'),
            qClear: document.getElementById('prefs-q-clear'),
            values: document.getElementById('prefs-values'),
            add: document.getElementById('prefs-add'),
            clear: document.getElementById('prefs-clear'),
            banner: document.getElementById('prefs-banner'),
            table: document.getElementById('prefs-table'),
            rows: document.getElementById('prefs-rows'),
            empty: document.getElementById('prefs-empty'),
            editDialog: document.getElementById('prefs-edit-dialog'),
            editTitle: document.getElementById('prefs-edit-title'),
            editKey: document.getElementById('prefs-edit-key'),
            editType: document.getElementById('prefs-edit-type'),
            previewRow: document.getElementById('prefs-edit-preview-row'),
            preview: document.getElementById('prefs-edit-preview'),
            editValue: document.getElementById('prefs-edit-value'),
            editHint: document.getElementById('prefs-edit-hint'),
            editNote: document.getElementById('prefs-edit-note'),
            editError: document.getElementById('prefs-edit-error'),
            editSave: document.getElementById('prefs-edit-save'),
            editDelete: document.getElementById('prefs-edit-delete'),
            confirmTitle: document.getElementById('prefs-confirm-title'),
            confirmMessage: document.getElementById('prefs-confirm-message'),
            confirmOk: document.getElementById('prefs-confirm-ok')
        };
        var local = storage();
        settings = local ? readSettings(local) : readSettings({ getItem: function() { return null; } });
        widths = local ? readWidths(local) : {};
        editModal = window.debugModal('prefs-edit-modal');
        confirmModal = window.debugModal('prefs-confirm-modal');
        setupEvents();
        renderControls();
        applyWidths();
        els.panel.hidden = true;
        if (settings.autoRefresh) {
            startPolling();
        } else {
            refreshNow(true);
        }
    }

    if (typeof window.lustroOnContentReady === 'function') {
        window.lustroOnContentReady(init);
    } else {
        init();
    }
})();
