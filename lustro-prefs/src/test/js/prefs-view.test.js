// The Preferences tab's view: how a poll's cursor envelope changes the file the
// page holds, how the typed edit forms read what they hold, and how a row shows
// a file's policy and read-only flag.
//
// The merge and the forms are where the page can go wrong without an error: a
// merge that keeps a removed key shows a value the file no longer has, and a form
// that parses a long as a JS number writes another number back to the app.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { loadTabJs, golden } = require('../../../../lustro/src/test/js/harness.js');

const prefs = loadTabJs('lustro-prefs', 'prefs');

const keys = (view) => view.entries.map((entry) => entry.key);
// The test's own values: the script's come from another context, which deepStrictEqual tells apart.
const plain = (value) => JSON.parse(JSON.stringify(value));

function viewOf(...items) {
    const view = prefs.prefsNewView();
    prefs.prefsApplyEnvelope(view, { cursor: 'r', status: 'reset', items });
    return view;
}

function file(fields) {
    return Object.assign({
        name: 'settings', registered: true, policy: 'visible', readOnly: false,
        encrypted: false, keyCount: 7, cursor: 'c0',
    }, fields);
}

const ctx = (fields) => Object.assign({ file: file(), lock: null, changed: new Set(), saved: new Map(), now: 0 }, fields);

// A file of the golden GET files response, by its store and name.
const listed = (name, store = 'shared_prefs') =>
    golden('prefs-files.json').items.find((item) => item.name === name && item.store === store);

// ── The cursor envelope ─────────────────────────────────────────

test('a reset replaces the entries and keeps the cursor', () => {
    const view = viewOf({ key: 'stale', type: 'int', value: 1, bytes: 4 });
    const reset = golden('prefs-reset.json');

    const changes = prefs.prefsApplyEnvelope(view, reset);

    assert.deepStrictEqual(plain(changes), { reset: true, changed: [], removed: [] });
    assert.deepStrictEqual(keys(view), reset.items.map((item) => item.key));
    assert.strictEqual(view.cursor, reset.cursor);
    assert.strictEqual(view.byKey.get('launch_count').value, 3);
});

test('a delta carries the whole file: it replaces the entries and says which keys changed', () => {
    const view = prefs.prefsNewView();
    prefs.prefsApplyEnvelope(view, golden('prefs-reset.json'));
    const delta = golden('prefs-delta.json');

    const changes = prefs.prefsApplyEnvelope(view, delta);

    // launch_count goes from 3 to 4; the other six keys hold what they held.
    assert.deepStrictEqual(plain(changes), { reset: false, changed: ['launch_count'], removed: [] });
    assert.strictEqual(view.byKey.get('launch_count').value, 4);
    assert.strictEqual(view.cursor, delta.cursor);
});

test('a delta without a key removes it, and a new key counts as changed', () => {
    const view = viewOf({ key: 'a', type: 'int', value: 1, bytes: 4 }, { key: 'b', type: 'int', value: 2, bytes: 4 });

    const changes = prefs.prefsApplyEnvelope(view, {
        cursor: 'c2',
        status: 'delta',
        items: [{ key: 'b', type: 'int', value: 2, bytes: 4 }, { key: 'c', type: 'string', value: 'x', bytes: 1 }],
    });

    assert.deepStrictEqual(plain(changes), { reset: false, changed: ['c'], removed: ['a'] });
    assert.deepStrictEqual(keys(view), ['b', 'c']);
    assert.strictEqual(view.byKey.has('a'), false);
});

test('unchanged keeps the entries and the cursor it echoes', () => {
    const view = viewOf({ key: 'a', type: 'int', value: 1, bytes: 4 });
    const unchanged = golden('prefs-unchanged.json');

    assert.strictEqual(prefs.prefsApplyEnvelope(view, unchanged), null);
    assert.deepStrictEqual(keys(view), ['a']);
    assert.strictEqual(view.cursor, unchanged.cursor);
});

test('a status the page does not know is a reset', () => {
    const view = viewOf({ key: 'a', type: 'int', value: 1, bytes: 4 });

    const changes = prefs.prefsApplyEnvelope(view, { cursor: 'n', status: 'resync', items: [{ key: 'z', type: 'boolean', value: true, bytes: 1 }] });

    assert.strictEqual(changes.reset, true);
    assert.deepStrictEqual(keys(view), ['z']);
});

test('the entries sort by key, ignoring case', () => {
    const view = viewOf(
        { key: 'b', type: 'int', value: 1, bytes: 4 },
        { key: 'ACCOUNT_TYPE', type: 'string', value: 'x', bytes: 1 },
        { key: 'account_id', type: 'long', value: 1, bytes: 8 },
    );

    assert.deepStrictEqual(keys(view), ['account_id', 'ACCOUNT_TYPE', 'b']);
});

test('a long past 2^53 keeps every digit, as a string', () => {
    const data = prefs.prefsParseJson('{"cursor":"c","status":"reset","items":[{"key":"max","type":"long","value":9223372036854775807,"bytes":8},'
        + '{"key":"min","type":"long","value":-9223372036854775808,"bytes":8},{"key":"small","type":"long","value":1792419740010,"bytes":8},'
        + '{"key":"note","type":"string","value":"\\"value\\":12345678901234567,","bytes":26},'
        + '{"key":"edge","type":"long","value":9007199254740993,"bytes":8}]}');

    assert.strictEqual(data.items[0].value, '9223372036854775807');
    assert.strictEqual(data.items[1].value, '-9223372036854775808');
    // A long that a JS number holds stays a number, and a string's text is left alone.
    assert.strictEqual(data.items[2].value, 1792419740010);
    assert.strictEqual(data.items[3].value, '"value":12345678901234567,');
    // 16 digits, one past 2^53, which a JS number rounds to ...992.
    assert.strictEqual(data.items[4].value, '9007199254740993');
});

test('a write from the page replaces its entry and keeps the cursor', () => {
    const view = viewOf({ key: 'a', type: 'int', value: 1, bytes: 4 });

    prefs.prefsPutEntry(view, { key: 'a', type: 'int', value: 5, bytes: 4 });

    assert.strictEqual(view.byKey.get('a').value, 5);
    assert.strictEqual(view.cursor, 'r');
});

// ── Files ───────────────────────────────────────────────────────

test('the file tabs sort by key count, the fullest first, and a poll keeps their order', () => {
    const files = golden('prefs-files.json').items;

    const order = prefs.prefsOrderFiles(files, null, false);
    assert.deepStrictEqual(plain(order), [
        'shared_prefs/settings', 'shared_prefs/sdk_cache', 'shared_prefs/secure_store',
        'shared_prefs/session', 'preferences_datastore/app_state', 'preferences_datastore/sdk_state',
    ]);

    const grown = files.map((f) => (f.name === 'session' ? Object.assign({}, f, { keyCount: 20 }) : f));
    assert.deepStrictEqual(plain(prefs.prefsOrderFiles(grown, order, false)), plain(order));
    assert.deepStrictEqual(plain(prefs.prefsOrderFiles(grown, order, true)).slice(0, 2), ['shared_prefs/session', 'shared_prefs/settings']);

    const gone = files.filter((f) => f.name !== 'settings');
    assert.deepStrictEqual(plain(prefs.prefsOrderFiles(gone, order, false)), plain(order).slice(1));
});

test('a SharedPreferences file and a DataStore of the same name are two files', () => {
    const shared = file({ name: 'app_state' });
    const store = file({ name: 'app_state', store: 'preferences_datastore' });

    assert.strictEqual(prefs.prefsFileId(shared), 'shared_prefs/app_state');
    assert.strictEqual(prefs.prefsFileId(store), 'preferences_datastore/app_state');
    // A file from before the stores is a SharedPreferences file.
    assert.strictEqual(prefs.prefsFileId({ name: 'app_state' }), 'shared_prefs/app_state');
    assert.match(prefs.prefsFileTabHtml(store, false), /data-file="preferences_datastore\/app_state"[^>]*title="app_state · Preferences DataStore · /);
    assert.match(prefs.prefsFileTabHtml(store, false), /<span class="prefs-file__store">DS<\/span>app_state/);
    assert.doesNotMatch(prefs.prefsFileTabHtml(shared, false), /prefs-file__store/);
});

test('a DataStore takes doubles and bytes too', () => {
    assert.deepStrictEqual(plain(prefs.prefsWriteTypes(file())), ['string', 'int', 'long', 'float', 'boolean', 'string_set']);
    assert.deepStrictEqual(
        plain(prefs.prefsWriteTypes(file({ store: 'preferences_datastore' }))),
        ['string', 'int', 'long', 'float', 'double', 'boolean', 'string_set', 'bytes'],
    );
});

test('a read-only file says why it is read-only, and a writable one is not locked', () => {
    assert.strictEqual(prefs.prefsLockReason(listed('settings')), null);
    assert.match(prefs.prefsLockReason(listed('session')), /registered this file as read-only/);
    assert.match(prefs.prefsLockReason(listed('sdk_cache')), /found this file on disk/);
    assert.match(prefs.prefsLockReason(listed('secure_store')), /encrypted file/);
    assert.match(prefs.prefsLockReason(file({ name: 'lustro_debug', registered: false, readOnly: true })), /token/);
    assert.match(prefs.prefsLockReason(file({ error: 'boom' })), /can't read this file: boom/);
    assert.strictEqual(prefs.prefsLockReason(listed('app_state', 'preferences_datastore')), null);
    assert.match(prefs.prefsLockReason(listed('sdk_state', 'preferences_datastore')), /keeps its values in memory.*PrefsDataStore/);
});

test('the facts show the store, the origin, the policy, and the read-only flag', () => {
    assert.match(prefs.prefsFactsHtml(listed('settings'), 7, 7), /7 keys.*SharedPreferences.*Registered.*Sensitive values masked.*Writable/);
    assert.match(prefs.prefsFactsHtml(listed('session'), 1, 2), /1 of 2 keys.*Registered.*Keys only.*Read-only/);
    assert.match(prefs.prefsFactsHtml(listed('secure_store'), 3, 3), /Found on disk.*Keys only.*Encrypted.*Read-only/);
    assert.match(prefs.prefsFactsHtml(listed('app_state', 'preferences_datastore'), 2, 2), /DataStore.*Registered.*Values visible.*Writable/);
    assert.match(prefs.prefsFactsHtml(listed('sdk_state', 'preferences_datastore'), 1, 1), /DataStore.*files\/datastore directory.*Found on disk.*Read-only/);
});

test('a file tab escapes the name and shows its count', () => {
    const html = prefs.prefsFileTabHtml(file({ name: '<b>x</b>', keyCount: 3, encrypted: true }), true);

    assert.match(html, /dc-tab--active/);
    assert.match(html, /&lt;b&gt;x&lt;\/b&gt;/);
    assert.match(html, /\(3\)/);
    assert.match(html, /🔒/);
    assert.doesNotMatch(html, /<b>/);
});

// ── Rows ────────────────────────────────────────────────────────

test('a boolean, an int, and a short string edit in the row; other values open the dialog', () => {
    assert.strictEqual(prefs.prefsInlineKind({ key: 'k', type: 'boolean', value: true }), 'boolean');
    assert.strictEqual(prefs.prefsInlineKind({ key: 'k', type: 'int', value: 3 }), 'int');
    assert.strictEqual(prefs.prefsInlineKind({ key: 'k', type: 'string', value: 'ann' }), 'string');
    assert.strictEqual(prefs.prefsInlineKind({ key: 'k', type: 'string', value: 'x'.repeat(50) }), null);
    assert.strictEqual(prefs.prefsInlineKind({ key: 'k', type: 'string', value: 'two\nlines' }), null);
    assert.strictEqual(prefs.prefsInlineKind({ key: 'k', type: 'string', value: ' {"a":1}' }), null);
    assert.strictEqual(prefs.prefsInlineKind({ key: 'k', type: 'long', value: 1 }), null);
    assert.strictEqual(prefs.prefsInlineKind({ key: 'k', type: 'float', value: 1.5 }), null);
    assert.strictEqual(prefs.prefsInlineKind({ key: 'k', type: 'string_set', value: ['a'] }), null);
    assert.strictEqual(prefs.prefsInlineKind({ key: 'k', type: 'int', bytes: 4, masked: true }), null);
});

test('a row escapes the key and the value', () => {
    const html = prefs.prefsRowHtml({ key: '<k>', type: 'string', value: '<img src=x onerror=alert(1)>'.repeat(3), bytes: 90 }, ctx());

    assert.doesNotMatch(html, /<img/);
    assert.doesNotMatch(html, /<k>/);
    assert.match(html, /&lt;k&gt;/);
});

test('a masked value shows as a comment with its size, never its value', () => {
    const entry = { key: 'auth_token', type: 'string', bytes: 29, masked: true };

    const visible = prefs.prefsRowHtml(entry, ctx({ file: file({ policy: 'mask_sensitive' }) }));
    assert.match(visible, /dc-comment prefs-hidden">\/\/ masked · 29 bytes/);
    // A writable file can still take a new value through the dialog.
    assert.match(visible, /data-action="openEdit"/);

    const keysOnly = file({ policy: 'keys_only', readOnly: true });
    const locked = prefs.prefsRowHtml(entry, ctx({ file: keysOnly, lock: prefs.prefsLockReason(keysOnly) }));
    assert.match(locked, /\/\/ keys only · 29 bytes/);
    assert.doesNotMatch(locked, /data-action="openEdit"/);

    assert.strictEqual(prefs.prefsHiddenText(entry, file({ policy: 'keys_only', encrypted: true })), '// encrypted · 29 bytes');
});

test('in a read-only file, the row editors and Delete are off, with the reason', () => {
    const session = listed('session');
    const lock = prefs.prefsLockReason(session);
    const locked = ctx({ file: session, lock });

    const bool = prefs.prefsRowHtml({ key: 'b', type: 'boolean', value: true, bytes: 1 }, locked);
    assert.match(bool, /type="checkbox"[^>]* checked disabled/);
    assert.match(bool, /title="Read-only\. The app registered this file as read-only\."/);
    assert.match(bool, /data-action="confirmDelete"[^>]* disabled>Delete/);

    const int = prefs.prefsRowHtml({ key: 'n', type: 'int', value: 3, bytes: 4 }, locked);
    assert.doesNotMatch(int, /data-inline="int"/);
    assert.match(int, /title="Read-only\. The app registered this file as read-only\. Click to see the whole value\."/);

    const writable = prefs.prefsRowHtml({ key: 'n', type: 'int', value: 3, bytes: 4 }, ctx());
    assert.match(writable, /data-inline="int"[^>]* value="3"/);
    assert.doesNotMatch(writable, / disabled>Delete/);
});

test('the type badge takes the color of its type, and a string set the string color', () => {
    const badge = (type) => prefs.prefsRowHtml({ key: 'k', type, value: type === 'string_set' ? [] : 1, bytes: 0 }, ctx());

    assert.match(badge('int'), /--c: var\(--type-int\)">Int</);
    assert.match(badge('long'), /--c: var\(--type-long\)">Long</);
    assert.match(badge('string_set'), /--c: var\(--type-string\)">String set</);
    assert.match(badge('double'), /--c: var\(--type-float\)">Double</);
    assert.match(badge('bytes'), /--c: var\(--type-string\)">Bytes</);
    assert.match(badge('unknown'), /class="dc-badge prefs-badge">Unknown</);
});

test('a masked value of a type that the file can\'t write does not open the editor', () => {
    const entry = { key: 'secret_ratio', type: 'double', bytes: 8, masked: true };

    assert.doesNotMatch(prefs.prefsRowHtml(entry, ctx()), /data-action="openEdit"/);
    assert.match(prefs.prefsRowHtml(entry, ctx({ file: file({ store: 'preferences_datastore' }) })), /data-action="openEdit"/);
});

test('a row saved a moment ago shows Saved, from where its flash was', () => {
    const entry = { key: 'k', type: 'int', value: 3, bytes: 4 };

    assert.match(prefs.prefsRowHtml(entry, ctx({ saved: new Map([['k', 1000]]), now: 1400 })), /animation-delay: -400ms">✓ Saved/);
    assert.doesNotMatch(prefs.prefsRowHtml(entry, ctx({ saved: new Map([['k', 1000]]), now: 3000 })), /Saved/);
    assert.match(prefs.prefsRowHtml(entry, ctx({ changed: new Set(['k']) })), /prefs-row--changed/);
});

test('the search finds keys, and values only with Values on', () => {
    const name = { key: 'username', type: 'string', value: 'Ann', bytes: 3 };
    const tags = { key: 'pinned', type: 'string_set', value: ['travel', 'work'], bytes: 10 };
    const token = { key: 'auth_token', type: 'string', bytes: 29, masked: true };

    assert.strictEqual(prefs.prefsMatches(name, 'USER', false), true);
    assert.strictEqual(prefs.prefsMatches(name, 'ann', false), false);
    assert.strictEqual(prefs.prefsMatches(name, 'ann', true), true);
    assert.strictEqual(prefs.prefsMatches(tags, 'trav', true), true);
    assert.strictEqual(prefs.prefsMatches(token, 'secret', true), false);
    assert.strictEqual(prefs.prefsMatches(token, '', false), true);
    assert.strictEqual(prefs.prefsCountLabel(3, 7), '3 of 7 keys');
    assert.strictEqual(prefs.prefsCountLabel(1, 1), '1 key');
});

// ── The typed edit forms ────────────────────────────────────────

test('an int takes a whole number in its range', () => {
    const read = (text) => plain(prefs.prefsReadValue('int', text));

    assert.deepStrictEqual(read(' 42 '), { value: 42 });
    assert.deepStrictEqual(read('-2147483648'), { value: -2147483648 });
    assert.match(read('2147483648').error, /from -2147483648 to 2147483647/);
    assert.ok(read('4.5').error);
    assert.ok(read('1e3').error);
    assert.ok(read('').error);
});

test('a long is sent as a string, so that it keeps every digit', () => {
    const read = (text) => plain(prefs.prefsReadValue('long', text));

    assert.deepStrictEqual(read('9223372036854775807'), { value: '9223372036854775807' });
    assert.deepStrictEqual(read('+0042'), { value: '42' });
    assert.ok(read('9223372036854775808').error);
    assert.ok(read('12.0').error);
});

test('a float takes a decimal number in its range, or NaN and the infinities', () => {
    const read = (text) => plain(prefs.prefsReadValue('float', text));

    assert.deepStrictEqual(read('1.25'), { value: 1.25 });
    assert.deepStrictEqual(read('-2e3'), { value: -2000 });
    assert.deepStrictEqual(read('.5'), { value: 0.5 });
    assert.deepStrictEqual(read('NaN'), { value: 'NaN' });
    assert.deepStrictEqual(read('-Infinity'), { value: '-Infinity' });
    assert.match(read('1e39').error, /range of a float/);
    // Float.MAX_VALUE as the routes write it, just past the exact maximum as a double.
    assert.deepStrictEqual(read('3.4028235E38'), { value: 3.4028235e38 });
    assert.deepStrictEqual(read('-3.4028235E38'), { value: -3.4028235e38 });
    assert.match(read('3.5e38').error, /range of a float/);
    // Java's parser takes these; the form does not.
    assert.ok(read('0x1p3').error);
    assert.ok(read('1f').error);
});

test('a double takes any finite number, or NaN and the infinities', () => {
    const read = (text) => plain(prefs.prefsReadValue('double', text));

    assert.deepStrictEqual(read('0.1'), { value: 0.1 });
    assert.deepStrictEqual(read('1e300'), { value: 1e300 });
    assert.deepStrictEqual(read('Infinity'), { value: 'Infinity' });
    assert.match(read('1e309').error, /range of a double/);
    assert.ok(read('0x1p3').error);
    assert.ok(read('').error);
});

test('bytes are padded base64, and white space in them is dropped', () => {
    const read = (text) => plain(prefs.prefsReadValue('bytes', text));

    assert.deepStrictEqual(read('AAEC/w=='), { value: 'AAEC/w==' });
    assert.deepStrictEqual(read(' AAEC\n/w== '), { value: 'AAEC/w==' });
    assert.deepStrictEqual(read(''), { value: '' });
    assert.match(read('AAEC/w').error, /padded base64/);
    assert.ok(read('AA*=').error);
});

test('a whole double keeps its point, and bytes open as their base64', () => {
    assert.deepStrictEqual(plain(prefs.prefsEditorFor('double', 2)), { control: 'input', text: '2.0' });
    assert.deepStrictEqual(plain(prefs.prefsEditorFor('bytes', 'AAEC/w==')), { control: 'input', text: 'AAEC/w==' });
    assert.strictEqual(prefs.prefsEditorFor('bytes', 'A'.repeat(120)).control, 'textarea');
    assert.deepStrictEqual(plain(prefs.prefsEditorFor('double', null)), { control: 'input', text: '0' });
});

test('a boolean, a string, and a string set', () => {
    assert.deepStrictEqual(plain(prefs.prefsReadValue('boolean', true)), { value: true });
    assert.deepStrictEqual(plain(prefs.prefsReadValue('boolean', false)), { value: false });
    assert.deepStrictEqual(plain(prefs.prefsReadValue('string', '  kept as typed ')), { value: '  kept as typed ' });
    assert.deepStrictEqual(plain(prefs.prefsReadValue('string_set', '[\n  "a",\n  "b"\n]')), { value: ['a', 'b'] });
    assert.deepStrictEqual(plain(prefs.prefsReadValue('string_set', '')), { value: [] });
    assert.match(prefs.prefsReadValue('string_set', '["a", 1]').error, /JSON array of strings/);
    assert.ok(prefs.prefsReadValue('string_set', '{"a":"b"}').error);
    assert.ok(prefs.prefsReadValue('unknown', 'x').error);
});

test('the dialog opens a JSON string indented and a set as a JSON array', () => {
    const json = prefs.prefsEditorFor('string', '{"user":{"id":12345678901234567890},"tags":["a"]}');
    assert.strictEqual(json.control, 'textarea');
    assert.strictEqual(json.json, true);
    assert.strictEqual(json.compact, true);
    assert.match(json.text, /\n {4}"id": 12345678901234567890\n/);

    const set = prefs.prefsEditorFor('string_set', ['travel', 'work']);
    assert.deepStrictEqual(plain(set), { control: 'textarea', text: '[\n  "travel",\n  "work"\n]' });

    assert.strictEqual(prefs.prefsEditorFor('string', 'ann').control, 'input');
    assert.strictEqual(prefs.prefsEditorFor('string', 'line\nbreak').control, 'textarea');
    assert.strictEqual(prefs.prefsEditorFor('string', 'x'.repeat(81)).control, 'textarea');
    assert.deepStrictEqual(plain(prefs.prefsEditorFor('boolean', true)), { control: 'switch', checked: true });
    assert.deepStrictEqual(plain(prefs.prefsEditorFor('long', '9223372036854775807')), { control: 'input', text: '9223372036854775807' });
    // A whole float keeps its point; a non-finite one comes as a string.
    assert.deepStrictEqual(plain(prefs.prefsEditorFor('float', 1)), { control: 'input', text: '1.0' });
    assert.deepStrictEqual(plain(prefs.prefsEditorFor('float', 'NaN')), { control: 'input', text: 'NaN' });
    // A masked value opens empty: the page never had it.
    assert.deepStrictEqual(plain(prefs.prefsEditorFor('string', undefined, true)), { control: 'input', text: '' });
});

test('a JSON string goes back as the file held it, or compact when it was, with every digit', () => {
    const original = '{"user":{"id":12345678901234567890,"name":"a b"},"tags":["x"]}';
    const editor = prefs.prefsEditorFor('string', original);

    assert.deepStrictEqual(plain(prefs.prefsValueToSave('string', editor.text, editor, original)), { value: original });

    const edited = editor.text.replace('"a b"', '"c d"');
    assert.deepStrictEqual(plain(prefs.prefsValueToSave('string', edited, editor, original)),
        { value: '{"user":{"id":12345678901234567890,"name":"c d"},"tags":["x"]}' });

    // JSON that was not compact goes back as typed.
    const spaced = '{ "a": 1 }';
    const spacedEditor = prefs.prefsEditorFor('string', spaced);
    assert.deepStrictEqual(plain(prefs.prefsValueToSave('string', '{\n  "a": 2\n}', spacedEditor, spaced)), { value: '{\n  "a": 2\n}' });
});

test('compact JSON keeps the whitespace inside strings and escaped quotes', () => {
    assert.strictEqual(prefs.prefsCompactJson('{\n  "a b": "c \\" d",\n  "n": [ 1, 2 ]\n}'), '{"a b":"c \\" d","n":[1,2]}');
});

test('the preview highlights a JSON value, and says when it does not parse', () => {
    assert.match(prefs.prefsPreviewHtml('string', '{"a":1}'), /<span class="k">"a"<\/span>/);
    assert.match(prefs.prefsPreviewHtml('string_set', '["x"'), /not valid JSON yet/);
    assert.strictEqual(prefs.prefsPreviewHtml('string', 'plain text'), null);
    assert.strictEqual(prefs.prefsPreviewHtml('int', '[1]'), null);
});

// ── Settings ────────────────────────────────────────────────────

test('auto-refresh is on every 3 s by default, and the interval is at least 1 s', () => {
    const storage = (value) => ({ getItem: () => (value === undefined ? null : JSON.stringify(value)) });

    assert.deepStrictEqual(plain(prefs.prefsReadSettings(storage())), { activeFile: null, autoRefresh: true, searchValues: false, intervalMs: 3000 });
    assert.deepStrictEqual(plain(prefs.prefsReadSettings(storage({ activeFile: 'x', autoRefresh: false, searchValues: true, intervalMs: 500 }))),
        { activeFile: 'x', autoRefresh: false, searchValues: true, intervalMs: 3000 });
    assert.strictEqual(prefs.prefsReadInterval('250'), 1000);
    assert.strictEqual(prefs.prefsReadInterval('5000'), 5000);
    assert.strictEqual(prefs.prefsReadInterval('soon'), 3000);
});
