// The Logs tab's view: how a poll's stream envelope changes the lines the page
// holds, how a line renders, and the text that Copy writes.
//
// The merge is where the page can go wrong without an error: a repeated line
// comes again with its seq, so a merge that only appends shows it twice, and
// one that drops the cap or the dropped count misleads about what the app logged.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { loadTabJs, golden } = require('../../../../lustro/src/test/js/harness.js');

const logs = loadTabJs('lustro-logs', 'logs');
const MAX = 5000;

function line(seq, fields) {
    return Object.assign({
        seq,
        at: 1792419740000 + seq,
        timestamp: '10-19 14:22:20.' + String(seq).padStart(3, '0'),
        pid: 12873,
        tid: 12901,
        level: 'I',
        tag: 'Sync',
        message: 'line ' + seq,
    }, fields);
}

function delta(items, fields) {
    return Object.assign({ cursor: 'c' + items.length, status: 'delta', items, dropped: 0 }, fields);
}

const seqs = (view) => view.entries.map((entry) => entry.seq);
// The test's own arrays: the script's come from another context, which deepStrictEqual tells apart.
const plain = (value) => JSON.parse(JSON.stringify(value));

function viewOf(...entries) {
    const view = logs.logsNewView();
    logs.logsApplyEnvelope(view, { cursor: 'r', status: 'reset', items: entries }, MAX);
    return view;
}

test('a reset replaces the lines and keeps the cursor', () => {
    const view = viewOf(line(90), line(91));
    view.missed = 4;
    const reset = golden('logs-reset.json');

    const changes = logs.logsApplyEnvelope(view, reset, MAX);

    assert.deepStrictEqual(plain(changes), { reset: true });
    assert.deepStrictEqual(seqs(view), [0, 1, 2, 3, 4, 5]);
    assert.strictEqual(view.cursor, reset.cursor);
    assert.strictEqual(view.missed, 0);
});

test('a delta appends, and a repeated line replaces the row it repeats', () => {
    const view = logs.logsNewView();
    logs.logsApplyEnvelope(view, golden('logs-reset.json'), MAX);
    const next = golden('logs-delta.json');

    const changes = logs.logsApplyEnvelope(view, next, MAX);

    // seq 5 is the heartbeat, at count 2 in the reset and 4 in the delta.
    assert.deepStrictEqual(seqs(view), [0, 1, 2, 3, 4, 5, 7, 8]);
    assert.strictEqual(view.entries[5].count, 4);
    assert.deepStrictEqual(plain(changes), {
        reset: false,
        removed: 0,
        replaced: [5],
        appended: plain(next.items.slice(1)),
    });
    assert.strictEqual(view.cursor, next.cursor);
});

test('unchanged changes no line, and keeps the cursor it echoes', () => {
    const view = viewOf(line(1), line(2));
    const unchanged = golden('logs-unchanged.json');

    assert.strictEqual(logs.logsApplyEnvelope(view, unchanged, MAX), null);
    assert.deepStrictEqual(seqs(view), [1, 2]);
    assert.strictEqual(view.cursor, unchanged.cursor);
});

test('dropped adds up over deltas, and a reset starts it again', () => {
    const view = viewOf(line(1));

    logs.logsApplyEnvelope(view, delta([line(5)], { dropped: 3 }), MAX);
    logs.logsApplyEnvelope(view, delta([], { dropped: 2 }), MAX);
    assert.strictEqual(view.missed, 5);

    logs.logsApplyEnvelope(view, { cursor: 'r2', status: 'reset', items: [line(9)] }, MAX);
    assert.strictEqual(view.missed, 0);
});

test('a status the page does not know is a reset', () => {
    const view = viewOf(line(1), line(2));

    const changes = logs.logsApplyEnvelope(view, { cursor: 'x', status: 'resync', items: [line(7)] }, MAX);

    assert.deepStrictEqual(plain(changes), { reset: true });
    assert.deepStrictEqual(seqs(view), [7]);
});

test('the page keeps the newest lines, and a replaced row moves with the removal', () => {
    const view = logs.logsNewView();
    logs.logsApplyEnvelope(view, { cursor: 'r', status: 'reset', items: [1, 2, 3, 4, 5].map((seq) => line(seq)) }, 4);
    assert.deepStrictEqual(seqs(view), [2, 3, 4, 5]);

    const changes = logs.logsApplyEnvelope(view, delta([line(5, { count: 2 }), line(6), line(7)]), 4);

    assert.deepStrictEqual(seqs(view), [4, 5, 6, 7]);
    // Two rows go from the start, so the row of seq 5 is at index 1 after it.
    assert.strictEqual(changes.removed, 2);
    assert.deepStrictEqual(plain(changes.replaced), [1]);
    assert.deepStrictEqual(changes.appended.map((entry) => entry.seq), [6, 7]);
});

test('a delta larger than the page replaces every row', () => {
    const view = viewOf(line(1), line(2));

    const changes = logs.logsApplyEnvelope(view, delta([3, 4, 5, 6, 7].map((seq) => line(seq))), 3);

    assert.deepStrictEqual(seqs(view), [5, 6, 7]);
    assert.strictEqual(changes.removed, 2);
    assert.deepStrictEqual(plain(changes.replaced), []);
    assert.deepStrictEqual(changes.appended.map((entry) => entry.seq), [5, 6, 7]);
});

test('a repeat of a line that left the page comes back as a new row', () => {
    const view = logs.logsNewView();
    logs.logsApplyEnvelope(view, { cursor: 'r', status: 'reset', items: [1, 2, 3].map((seq) => line(seq)) }, 2);

    const changes = logs.logsApplyEnvelope(view, delta([line(1, { count: 9 })]), 2);

    assert.deepStrictEqual(seqs(view), [3, 1]);
    assert.deepStrictEqual(plain(changes.replaced), []);
    assert.deepStrictEqual(changes.appended.map((entry) => entry.count), [9]);
});

test('the poll path has the filters and the cursor, escaped', () => {
    assert.strictEqual(
        logs.logsEntriesPath({ level: 'V', tag: '', q: '' }, null, 1000),
        'entries?limit=1000');
    assert.strictEqual(
        logs.logsEntriesPath({ level: 'W', tag: '^(OkHttp|Sync)$', q: 'a b&c' }, 'Q1w=', 5000),
        'entries?cursor=Q1w%3D&level=W&tag=%5E(OkHttp%7CSync)%24&q=a%20b%26c&limit=5000');
});

test('a line that holds markup shows as text', () => {
    const html = logs.logsRowHtml(line(1, { tag: '<b>', message: '<img src=x onerror=alert(1)> "q"' }), allColumns(), false);
    assert.ok(html.includes('&lt;img src=x onerror=alert(1)&gt; &quot;q&quot;'), html);
    assert.ok(html.includes('title="&lt;b&gt;">&lt;b&gt;</td>'), html);
    assert.ok(!html.includes('<img') && !html.includes('<b>'), html);
});

test('warnings and errors get the row states, and Fatal its own', () => {
    const cls = (level) => /<tr class="([^"]*)"/.exec(logs.logsRowHtml(line(1, { level }), allColumns(), false))[1];
    assert.strictEqual(cls('I'), 'dc-row logs-row');
    assert.strictEqual(cls('W'), 'dc-row logs-row dc-row--warn');
    assert.strictEqual(cls('E'), 'dc-row logs-row dc-row--error');
    assert.strictEqual(cls('F'), 'dc-row logs-row dc-row--error logs-row--fatal');
});

test('a hidden column renders its cells hidden', () => {
    const html = logs.logsRowHtml(line(1), { lvl: true, time: false, tag: true, pid: false, msg: true }, false);
    assert.ok(html.includes('data-col="time" title="10-19 14:22:20.001" hidden>14:22:20.001</td>'), html);
    assert.ok(html.includes('data-col="pid" hidden>12873/12901</td>'), html);
    assert.ok(html.includes('data-col="tag" title="Sync">Sync</td>'), html);
});

test('a long message, or one with line breaks, folds, and a repeated one shows its count', () => {
    const long = logs.logsRowHtml(line(1, { message: 'x'.repeat(241) }), allColumns(), false);
    assert.ok(long.includes('class="logs-msg logs-msg--long" data-action="toggleMessage"'), long);
    const open = logs.logsRowHtml(line(1, { message: 'a\nb' }), allColumns(), true);
    assert.ok(open.includes('class="logs-msg logs-msg--long logs-msg--open"'), open);
    const short = logs.logsRowHtml(line(1, { message: 'x'.repeat(240) }), allColumns(), false);
    assert.ok(short.includes('class="logs-msg">'), short);

    const repeated = logs.logsRowHtml(line(1, { count: 4, at: 1000, lastAt: 4000 }), allColumns(), false);
    assert.ok(repeated.includes('title="Repeated 4 times in a row, over 3.0 s.">×4</span>'), repeated);
    assert.ok(!short.includes('logs-repeat'), short);
});

test('Copy writes the columns the table shows, and the count of a repeated line', () => {
    const entries = [line(1, { level: 'W', message: 'retry' }), line(2, { message: 'tick', count: 3 })];
    assert.strictEqual(logs.logsRowsText(entries, allColumns()), [
        'W  10-19 14:22:20.001  Sync  12873/12901  retry',
        'I  10-19 14:22:20.002  Sync  12873/12901  tick [×3]',
        '',
    ].join('\n'));
    assert.strictEqual(logs.logsRowsText(entries, { lvl: false, time: true, tag: false, pid: false, msg: true }),
        '10-19 14:22:20.001  retry\n10-19 14:22:20.002  tick [×3]\n');
    assert.strictEqual(logs.logsRowsText([], allColumns()), '');
});

test('the count says how many lines the page holds, and how many it missed', () => {
    assert.strictEqual(logs.logsCountLabel(1, 0), '1 line');
    assert.strictEqual(logs.logsCountLabel(312, 0), '312 lines');
    assert.strictEqual(logs.logsCountLabel(5000, 40), '5000 lines · 40 missed');
});

test('the banner says when logcat failed or ended, and nothing while it runs', () => {
    assert.strictEqual(logs.logsReaderBanner({ reader: 'running' }), null);
    assert.deepStrictEqual(plain(logs.logsReaderBanner({ reader: 'failed', readerError: 'logcat: not found' })), {
        kind: 'error',
        text: 'The tab can\'t read logcat: logcat: not found. The list keeps the lines it read before.',
    });
    assert.strictEqual(logs.logsReaderBanner({ reader: 'restarting' }).kind, 'warn');
});

test('Format JSON finds the JSON in a message', () => {
    assert.strictEqual(logs.logsExtractJson('{"a":1}'), '{"a":1}');
    assert.strictEqual(logs.logsExtractJson('<-- 200 body: {"items":[1,2]} (12ms)'), '{"items":[1,2]}');
    assert.strictEqual(logs.logsExtractJson('ids [1, 2]'), '[1, 2]');
    // A bracketed note before or after the body.
    assert.strictEqual(logs.logsExtractJson('[requestId=42] response: {"ok":true}'), '{"ok":true}');
    assert.strictEqual(logs.logsExtractJson('response: {"ok":true} [128ms]'), '{"ok":true}');
    // Both brackets parse: the outer one.
    assert.strictEqual(logs.logsExtractJson('rows [{"a":1}] done'), '[{"a":1}]');
    assert.strictEqual(logs.logsExtractJson('Map{a=1}'), null);
    assert.strictEqual(logs.logsExtractJson('no json here'), null);
});

test('Filter by this tag finds exactly the tag', () => {
    assert.strictEqual(logs.logsExactTagRegex('okhttp.OkHttpClient'), '^okhttp\\.OkHttpClient$');
    assert.strictEqual(logs.logsExactTagRegex('a(b)$[c]'), '^a\\(b\\)\\$\\[c\\]$');
    assert.ok(new RegExp(logs.logsExactTagRegex('a+b')).test('a+b'));
});

test('the column choice reads back, with the message always shown', () => {
    const storage = (value) => ({ getItem: () => value });
    assert.deepStrictEqual(plain(logs.logsReadColumns(storage(null))), allColumns({ pid: false }));
    assert.deepStrictEqual(plain(logs.logsReadColumns(storage('{"pid":true,"lvl":false,"msg":false}'))),
        allColumns({ lvl: false }));
    assert.deepStrictEqual(plain(logs.logsReadColumns(storage('not json'))), allColumns({ pid: false }));
    assert.deepStrictEqual(plain(logs.logsReadWidths(storage('{"tag":240.4,"time":5,"msg":900}'))), { tag: 240 });
});

function allColumns(fields) {
    return Object.assign({ lvl: true, time: true, tag: true, pid: true, msg: true }, fields);
}
