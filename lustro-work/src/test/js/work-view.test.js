// The WorkManager tab's view: how the polls of the list and of the timeline
// change what the page holds, what the rows, the details, and the events say,
// the banners that say why there are no events or no work, and the bodies that
// Cancel and Prune send.
//
// These are where the page can go wrong without an error: a delta that the
// timeline appends twice shows a run twice, a cancel body that names the wrong
// key cancels other work, and a value that is not escaped runs as markup.
'use strict';

// The times in the rows are local times: one zone keeps the tests the same everywhere.
process.env.TZ = 'UTC';

const test = require('node:test');
const assert = require('node:assert');
const { loadTabJs, golden } = require('../../../../lustro/src/test/js/harness.js');

const work = loadTabJs('lustro-work', 'work');

// The test's own values: the script's come from another context, which deepStrictEqual tells apart.
const plain = (value) => JSON.parse(JSON.stringify(value));

const items = () => golden('work-items.json').items;
const byClass = (simple) => items().find((item) => item.workerClass.endsWith('.' + simple));
const events = () => golden('work-events.json').items;
const NOW = 1792419740000;

// ── The list ────────────────────────────────────────────────────

test('the items path has only the filters that are set, encoded, after the cursor', () => {
    assert.strictEqual(work.workItemsPath({ tag: '', workerClass: '', name: '' }, null), 'items');
    assert.strictEqual(
        work.workItemsPath({ tag: 'a b', workerClass: 'SyncWorker', name: 'sync&more' }, 'c1=='),
        'items?cursor=c1%3D%3D&tag=a%20b&class=SyncWorker&name=sync%26more',
    );
});

test('a reset fills the list, unchanged keeps it, and a delta replaces all of it', () => {
    const list = work.workNewList();
    const reset = golden('work-items.json');

    assert.strictEqual(work.workApplyItems(list, reset), true);
    assert.strictEqual(list.items.length, 4);
    assert.strictEqual(list.byId.get('5e7f9a1b-3c5d-4e7f-a1b3-c5d7e9f1a3b5').state, 'FAILED');
    assert.strictEqual(list.cursor, reset.cursor);
    assert.strictEqual(list.state.listeners, 'installed');

    assert.strictEqual(work.workApplyItems(list, { cursor: reset.cursor, status: 'unchanged', state: { listeners: 'missing' } }), false);
    assert.strictEqual(list.items.length, 4);
    assert.strictEqual(list.state.listeners, 'missing');

    const delta = { cursor: 'next', status: 'delta', items: [byClass('ThrowingWorker')], state: reset.state };
    assert.strictEqual(work.workApplyItems(list, delta), true);
    assert.deepStrictEqual(list.items.map((item) => item.state), ['FAILED']);
    assert.strictEqual(list.byId.size, 1);
    assert.strictEqual(list.cursor, 'next');
});

test('the chips count each state, and filter the list by any of the chosen states', () => {
    const all = items();
    assert.deepStrictEqual(plain(work.workStateCounts(all)), {
        ENQUEUED: 2, RUNNING: 0, SUCCEEDED: 1, FAILED: 1, BLOCKED: 0, CANCELLED: 0,
    });
    assert.strictEqual(work.workVisibleItems(all, new Set()).length, 4);
    assert.deepStrictEqual(
        work.workVisibleItems(all, new Set(['FAILED', 'SUCCEEDED'])).map((item) => item.state),
        ['SUCCEEDED', 'FAILED'],
    );
    assert.strictEqual(work.workCountLabel(4, 4), '4 work requests');
    assert.strictEqual(work.workCountLabel(1, 4), '1 of 4 work requests');
    assert.strictEqual(work.workCountLabel(1, 1), '1 work request');
});

test('a worker shows by its simple name, and the tags without the one of its class', () => {
    assert.strictEqual(work.workSimpleClass('io.github.app.SyncWorker'), 'SyncWorker');
    assert.strictEqual(work.workSimpleClass('io.github.app.Outer$InnerWorker'), 'InnerWorker');
    assert.strictEqual(work.workSimpleClass(null), '');
    assert.deepStrictEqual(plain(work.workAppTags(byClass('StepWorker'))), ['demo']);
    assert.deepStrictEqual(plain(work.workAppTags(byClass('RetryWorker'))), []);
});

// ── Times and the details ───────────────────────────────────────

test('a duration is in the largest unit that fits, with the next one', () => {
    assert.strictEqual(work.workDurationText(0), '0 ms');
    assert.strictEqual(work.workDurationText(850), '850 ms');
    assert.strictEqual(work.workDurationText(1500), '1.5 s');
    assert.strictEqual(work.workDurationText(2000), '2 s');
    assert.strictEqual(work.workDurationText(12400), '12 s');
    assert.strictEqual(work.workDurationText(900000), '15 min');
    assert.strictEqual(work.workDurationText(252000), '4 min 12 s');
    assert.strictEqual(work.workDurationText(7500000), '2 h 5 min');
    assert.strictEqual(work.workDurationText(3 * 86400000 + 4 * 3600000), '3 d 4 h');
});

test('a time on the device is relative to the page clock, to the second', () => {
    assert.strictEqual(work.workRelativeText(NOW + 400, NOW), 'now');
    assert.strictEqual(work.workRelativeText(NOW + 252000, NOW), 'in 4 min 12 s');
    assert.strictEqual(work.workRelativeText(NOW - 3000, NOW), '3 s ago');
});

test('the constraints name only what the work waits for', () => {
    assert.strictEqual(work.workConstraintsText(byClass('RetryWorker').constraints), 'none');
    const periodic = items().find((item) => item.periodicity);
    assert.strictEqual(work.workConstraintsText(periodic.constraints), 'network unmetered, battery not low');
    assert.strictEqual(
        work.workConstraintsText({ network: 'NOT_ROAMING', requiresCharging: true, requiresDeviceIdle: true, requiresStorageNotLow: true, contentUriTriggers: [{}, {}] }),
        'network not roaming, charging, device idle, storage not low, 2 content URI triggers',
    );
});

test('the periodicity shows the flex only when it is shorter than the interval', () => {
    assert.strictEqual(work.workPeriodicityText(null), 'one-time');
    assert.strictEqual(work.workPeriodicityText({ repeatIntervalMillis: 900000, flexIntervalMillis: 900000 }), 'every 15 min');
    assert.strictEqual(work.workPeriodicityText({ repeatIntervalMillis: 3600000, flexIntervalMillis: 300000 }), 'every 1 h, in the last 5 min');
});

test('a stop reason shows its constant and its code', () => {
    assert.strictEqual(work.workStopReasonText({ code: -256, name: 'NOT_STOPPED' }), 'NOT_STOPPED');
    assert.strictEqual(work.workStopReasonText({ code: 6, name: 'CONSTRAINT_CHARGING' }), 'CONSTRAINT_CHARGING (6)');
});

test('a row marks failed and selected work, a periodic work, and its next run', () => {
    const failed = work.workRowHtml(byClass('ThrowingWorker'), true, NOW);
    assert.match(failed, /class="dc-row work-row dc-row--error dc-row--selected"/);
    assert.match(failed, /--c: var\(--work-failed\)">Failed</);

    const periodic = items().find((item) => item.periodicity);
    const row = work.workRowHtml(periodic, false, NOW);
    assert.match(row, /title="Periodic: every 15 min\.">↻/);
    assert.match(row, /data-at="1792419740000"[^>]*>now</);

    const done = work.workRowHtml(byClass('StepWorker'), false, NOW);
    assert.match(done, /work-cell--name" title="sync">sync</);
    assert.match(done, /<span class="dc-tag work-tag">demo<\/span>/);
});

test('a row escapes every value from the app', () => {
    const item = Object.assign(byClass('StepWorker'), {
        id: '"><b>',
        workerClass: 'x.<img src=x onerror=alert(1)>',
        uniqueName: '<script>alert(1)</script>',
        tags: ['<i>tag</i>'],
    });
    const row = work.workRowHtml(item, false, NOW);
    assert.doesNotMatch(row, /<img|<script|<i>|<b>/);
    assert.match(row, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
    assert.match(row, /data-id="&quot;&gt;&lt;b&gt;"/);

    const details = work.workDetailsHtml(item, NOW);
    assert.doesNotMatch(details, /<img|<script|<i>|<b>/);
});

test('the details show the output as JSON, and say when a value is empty', () => {
    const details = work.workDetailsHtml(byClass('StepWorker'), NOW);
    assert.match(details, /<th class="dc-kv__key">Output<\/th><td class="dc-kv__value"><pre class="dc-code dc-json work-json">/);
    // A long beyond 2^53 is a string in the JSON, so it shows with every digit.
    assert.match(details, /9223372036854775807/);
    assert.match(details, /Progress<\/th><td class="dc-kv__value"><span class="dc-comment">\/\/ empty/);
    assert.match(details, /Periodicity<\/th><td class="dc-kv__value">one-time/);
    assert.match(details, /work-tag work-tag--class/);

    const waiting = work.workDetailsHtml(byClass('RetryWorker'), NOW);
    assert.match(waiting, /Next run<\/th><td class="dc-kv__value">2026-10-19 14:22:50\.000 <span class="dc-comment" data-at="1792419770000">in 30 s</);
    assert.match(waiting, /Unique name<\/th><td class="dc-kv__value"><span class="dc-comment">/);

    // A run's own schedule time is in the past: running work says so instead.
    const running = work.workDetailsHtml(Object.assign(byClass('RetryWorker'), { state: 'RUNNING' }), NOW);
    assert.match(running, /Next run<\/th><td class="dc-kv__value"><span class="dc-comment">\/\/ running now</);
});

// ── The timeline ────────────────────────────────────────────────

test('the events path names the work, the cursor, and the largest page', () => {
    assert.strictEqual(work.workEventsPath('3f1c', null), 'events?id=3f1c&limit=5000');
    assert.strictEqual(work.workEventsPath('3f1c', 'a+b'), 'events?id=3f1c&cursor=a%2Bb&limit=5000');
});

test('a reset replaces the events, a delta appends only the new ones, and unchanged changes nothing', () => {
    const timeline = work.workNewTimeline();
    const reset = golden('work-events.json');

    assert.deepStrictEqual(plain(work.workApplyEvents(timeline, reset)), { reset: true, appended: [] });
    assert.strictEqual(timeline.events.length, 12);
    assert.strictEqual(timeline.cursor, reset.cursor);

    assert.strictEqual(work.workApplyEvents(timeline, { cursor: 'same', status: 'unchanged' }), null);
    assert.strictEqual(timeline.cursor, 'same');

    const next = Object.assign({}, events()[0], { seq: 14, type: 'stopped' });
    const changes = work.workApplyEvents(timeline, { cursor: 'c15', status: 'delta', items: [events()[11], next], dropped: 0 });
    assert.deepStrictEqual(changes.appended.map((event) => event.seq), [14]);
    assert.strictEqual(timeline.events.length, 13);
    assert.strictEqual(work.workApplyEvents(timeline, { cursor: 'c15', status: 'delta', items: [next], dropped: 0 }), null);

    work.workApplyEvents(timeline, { cursor: 'r', status: 'reset', items: [next] });
    assert.deepStrictEqual(timeline.events.map((event) => event.seq), [14]);
});

test('an event shows its time, its type in the color of the state it leads to, and its run', () => {
    const started = work.workEventHtml(events()[0]);
    assert.match(started, /<span class="work-event__time" title="2026-10-19 14:22:20\.000">14:22:20\.000<\/span>/);
    assert.match(started, /--c: var\(--work-running\)">Started</);
    assert.match(started, />run 1</);
    assert.doesNotMatch(started, /gen /);

    const finished = work.workEventHtml(events()[1]);
    assert.match(finished, /<span class="dc-tag work-result" style="--c: var\(--work-succeeded\)">success</);
    assert.match(finished, /<pre class="dc-code dc-json work-json work-event__json">/);

    const retry = work.workEventHtml(events().find((event) => event.result === 'retry'));
    assert.match(retry, /--c: var\(--work-blocked\)">retry</);
    // An empty output shows nothing.
    assert.doesNotMatch(retry, /work-event__json/);
});

test('an exception shows its class and message, and its stack trace escaped behind a toggle', () => {
    const exception = events().find((event) => event.type === 'exception');
    const html = work.workEventHtml(exception);
    assert.match(html, /<span class="work-event__exception">IllegalStateException: the journal store is corrupt<\/span>/);
    assert.match(html, /<details class="work-trace">/);
    assert.match(html, /at io\.github\.twinsen81\.lustro\.work\.internal\.ThrowingWorker\.doWork/);
    assert.match(html, /data-action="copyTrace" data-seq="13"/);
    assert.doesNotMatch(html, /cut by the tab/);

    const hostile = Object.assign({}, exception, {
        exception: { type: 'x.<b>Boom', message: '<img src=x onerror=alert(1)>', stackTrace: '</pre><script>alert(1)</script>', truncated: true },
    });
    const escaped = work.workEventHtml(hostile);
    assert.doesNotMatch(escaped, /<img|<script|<\/pre><script/);
    assert.match(escaped, /cut by the tab/);
});

test('a stopped event shows its reason, and an updated one its generations', () => {
    const base = events()[0];
    const stopped = work.workEventHtml(Object.assign({}, base, { type: 'stopped', state: 'ENQUEUED', stopReason: { code: 6, name: 'CONSTRAINT_CHARGING' } }));
    assert.match(stopped, /<span class="work-event__reason">CONSTRAINT_CHARGING \(6\)<\/span>/);
    assert.match(stopped, /--c: var\(--work-enqueued\)">Stopped</);

    const updated = work.workEventHtml(Object.assign({}, base, { type: 'updated', state: 'ENQUEUED', generation: 2, previousGeneration: 1 }));
    assert.match(updated, /generation 1 → 2/);
    assert.match(updated, />gen 2</);
});

// ── The history ─────────────────────────────────────────────────

test('a period of work-analytics shows each time after the enqueue, and says what it has not seen', () => {
    const [done, pending] = golden('work-metrics.json').items;
    const html = work.workPeriodHtml(done);
    assert.match(html, /--c: var\(--work-succeeded\)">Succeeded</);
    assert.match(html, /First start<\/th><td class="dc-kv__value">2026-10-28 14:37:18\.761 <span class="dc-comment">\+23 ms</);
    assert.match(html, /Stop reasons<\/th><td class="dc-kv__value"><span class="dc-comment">\/\/ none/);

    const waiting = work.workPeriodHtml(Object.assign({}, pending, { stopReasons: [{ code: 6, name: 'CONSTRAINT_CHARGING', count: 2 }] }));
    assert.match(waiting, /--c: var\(--work-enqueued\)">Enqueued pending</);
    assert.match(waiting, /First start<\/th><td class="dc-kv__value"><span class="dc-comment">\/\/ not yet/);
    assert.match(waiting, /CONSTRAINT_CHARGING \(6\) ×2/);
});

// ── Banners ─────────────────────────────────────────────────────

test('the listeners banner says how to fix each state, with the reason of the app', () => {
    assert.strictEqual(work.workListenersBanner({ listeners: 'installed' }), null);
    assert.strictEqual(work.workListenersBanner(null), null);

    const missing = work.workListenersBanner({ listeners: 'missing', reason: 'WorkManager runs with a Configuration that did not go through WorkDebugTab.configure()' });
    assert.strictEqual(missing.kind, 'warn');
    assert.match(missing.title, /not installed/);
    assert.match(missing.fix, /Configuration\.Provider/);
    assert.match(missing.fix, /WorkDebugTab\.configure\(builder\)/);
    assert.match(missing.fix, /WorkManagerInitializer/);
    assert.match(missing.reason, /did not go through/);

    const unsupported = work.workListenersBanner({ listeners: 'unsupported', reason: 'update Lustro' });
    assert.match(unsupported.title, /not supported/);
    assert.match(unsupported.fix, /WorkManager 2\.12\.0 or newer/);
    assert.strictEqual(unsupported.reason, 'update Lustro');
});

test('a refused list says why for the codes of WorkManager, and leaves the others to the poll', () => {
    const notStarted = work.workRejectionBanner(503, { error: 'service_unavailable', code: 'work_not_initialized', message: 'not yet', hint: 'Open the part of the app' });
    assert.deepStrictEqual(plain(notStarted), {
        kind: 'info',
        title: 'WorkManager has not started in the app yet. The tab does not start it.',
        fix: 'Open the part of the app',
        reason: 'not yet',
    });
    assert.strictEqual(work.workRejectionBanner(503, { code: 'work_unsupported', message: 'old', hint: 'Add androidx.work:work-runtime 2.12.0 or newer' }).kind, 'error');
    assert.strictEqual(work.workRejectionBanner(500, { code: 'work_error', message: 'boom' }).reason, 'boom');
    assert.strictEqual(work.workRejectionBanner(401, { error: 'unauthorized', message: 'no token' }), null);
    assert.strictEqual(work.workRejectionBanner(502, null), null);

    assert.strictEqual(work.workEmptyText('work_not_initialized', 0, false), '// WorkManager has not started in the app yet');
    assert.strictEqual(work.workEmptyText(null, 0, false), '// the app has no work in WorkManager');
    assert.strictEqual(work.workEmptyText(null, 3, true), '// no work matches the filters');
});

// ── Actions ─────────────────────────────────────────────────────

test('Cancel sends the id, Cancel unique work the name, and Prune no body', () => {
    const periodic = Object.assign(items().find((item) => item.periodicity), { uniqueName: 'demo-periodic' });

    const cancel = work.workConfirmFor('cancel', periodic);
    assert.strictEqual(cancel.path, 'cancel');
    assert.deepStrictEqual(plain(cancel.body), { id: periodic.id });
    assert.match(cancel.message, /StepWorker \(8a6b0c1d-2e3f-4a5b-9c7d-1e2f3a4b5c6d\)/);
    assert.match(cancel.message, /A periodic work does not run again/);

    const name = work.workConfirmFor('cancelName', periodic);
    assert.deepStrictEqual(plain(name.body), { name: 'demo-periodic' });
    assert.match(name.message, /"demo-periodic"/);

    const prune = work.workConfirmFor('prune', null);
    assert.strictEqual(prune.path, 'prune');
    assert.strictEqual(prune.body, null);
    assert.match(prune.message, /pruneWork\(\)/);
    assert.match(prune.message, /events that the tab recorded stay/);
});

test('the toast after an action says how much it changed', () => {
    assert.deepStrictEqual(plain(work.workResultToast('cancel', { status: 'ok', cancelled: 1 })), { text: 'Cancelled 1 work request', type: 'success' });
    assert.deepStrictEqual(plain(work.workResultToast('cancel', { status: 'ok', cancelled: 0 })), { text: 'Nothing to cancel: the work had finished', type: 'info' });
    assert.deepStrictEqual(plain(work.workResultToast('prune', { status: 'ok', pruned: 3 })), { text: 'Pruned 3 work requests', type: 'success' });
    assert.deepStrictEqual(plain(work.workResultToast('prune', { status: 'ok', pruned: 0 })), { text: 'Nothing to prune', type: 'info' });
});
