// The WorkManager tab: the app's work in a list, polled from GET items in the
// cursor envelope with the tag, class, and name filters, and the selected work
// in a drawer: its details, the timeline of its events, polled from GET events
// in the stream envelope, and its history, which GET items/{id} carries when the
// app passed work-analytics. work.openapi.json describes the routes.
(function() {
    var ITEMS_POLL_MS = 1000;
    var EVENTS_POLL_MS = 1000;
    // A reset of the timeline gets the last events of the work, up to the route's cap.
    var EVENTS_LIMIT = 5000;
    var FILTER_DEBOUNCE_MS = 300;
    var STICK_TO_END_PX = 40;
    var PANEL_KEY = 'debug-work-panel';
    var PANELS = ['details', 'timeline', 'history'];

    var STATES = ['ENQUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELLED'];
    var FINISHED = { SUCCEEDED: true, FAILED: true, CANCELLED: true };
    var EVENT_LABELS = {
        enqueued: 'Enqueued',
        unblocked: 'Unblocked',
        updated: 'Updated',
        cancelled: 'Cancelled',
        prerequisiteFailed: 'Prerequisite failed',
        started: 'Started',
        stopped: 'Stopped',
        finished: 'Finished',
        exception: 'Exception'
    };
    var RESULT_COLORS = { success: 'succeeded', failure: 'failed', retry: 'blocked' };
    // work-analytics' WorkMetricsInfo.State, by the WorkInfo.State whose color it takes.
    var PERIOD_COLORS = {
        ENQUEUED_BLOCKED: 'blocked',
        ENQUEUED_PENDING: 'enqueued',
        RUNNING: 'running',
        SUCCEEDED: 'succeeded',
        FAILED: 'failed',
        CANCELLED: 'cancelled',
        OBSOLETE_UPDATED: 'cancelled'
    };

    function esc(text) {
        return window.debugEscapeHtml(text);
    }

    // ── The list ────────────────────────────────────────────────

    function newList() {
        return { items: [], byId: new Map(), cursor: null, state: null };
    }

    function itemsPath(filters, cursor) {
        var params = [];
        if (cursor) params.push('cursor=' + encodeURIComponent(cursor));
        if (filters.tag) params.push('tag=' + encodeURIComponent(filters.tag));
        if (filters.workerClass) params.push('class=' + encodeURIComponent(filters.workerClass));
        if (filters.name) params.push('name=' + encodeURIComponent(filters.name));
        return params.length ? 'items?' + params.join('&') : 'items';
    }

    // Applies one response of GET items, and returns whether the list changed. The
    // cursor envelope always carries the whole list, so a delta replaces it too.
    function applyItems(list, data) {
        if (!data) return false;
        if (data.state) list.state = data.state;
        if (data.cursor != null) list.cursor = data.cursor;
        if (data.status === 'unchanged') return false;
        list.items = data.items || [];
        list.byId = new Map();
        list.items.forEach(function(item) { list.byId.set(item.id, item); });
        return true;
    }

    function stateCounts(items) {
        var counts = {};
        STATES.forEach(function(state) { counts[state] = 0; });
        items.forEach(function(item) {
            if (counts[item.state] != null) counts[item.state]++;
        });
        return counts;
    }

    // The items of the chosen states, or all of them when none is chosen.
    function visibleItems(items, states) {
        if (!states || states.size === 0) return items;
        return items.filter(function(item) { return states.has(item.state); });
    }

    function isFinished(state) {
        return !!FINISHED[state];
    }

    function simpleClass(name) {
        if (!name) return '';
        var text = String(name);
        return text.slice(Math.max(text.lastIndexOf('.'), text.lastIndexOf('$')) + 1);
    }

    // WorkManager tags each work with its worker class; the list shows the app's own tags.
    function appTags(item) {
        return (item.tags || []).filter(function(tag) { return tag !== item.workerClass; });
    }

    function stateLabel(state) {
        var text = String(state || '').toLowerCase().replace(/_/g, ' ');
        return text.charAt(0).toUpperCase() + text.slice(1);
    }

    function stateColor(state) {
        return STATES.indexOf(state) >= 0 ? 'var(--work-' + state.toLowerCase() + ')' : 'var(--t3)';
    }

    function stateBadge(state) {
        return '<span class="dc-badge" style="--c: ' + stateColor(state) + '">' + esc(stateLabel(state)) + '</span>';
    }

    // ── Times ───────────────────────────────────────────────────

    function pad(number, width) {
        return String(number).padStart(width || 2, '0');
    }

    function durationText(millis) {
        var ms = Math.max(0, Math.round(Number(millis) || 0));
        if (ms < 1000) return ms + ' ms';
        if (ms < 60000) {
            if (ms % 1000 === 0 || ms >= 10000) return Math.round(ms / 1000) + ' s';
            return (ms / 1000).toFixed(1) + ' s';
        }
        var s = Math.round(ms / 1000);
        var units = [[86400, 'd'], [3600, 'h'], [60, 'min'], [1, 's']];
        for (var i = 0; i < units.length - 1; i++) {
            if (s >= units[i][0]) {
                var big = Math.floor(s / units[i][0]);
                var small = Math.floor((s % units[i][0]) / units[i + 1][0]);
                return big + ' ' + units[i][1] + (small ? ' ' + small + ' ' + units[i + 1][1] : '');
            }
        }
        return s + ' s';
    }

    // "in 4 min 12 s" or "3 s ago", from the device's time [at] and the page's [now].
    function relativeText(at, now) {
        var diff = Math.round((Number(at) - now) / 1000) * 1000;
        if (Math.abs(diff) < 1000) return 'now';
        return diff > 0 ? 'in ' + durationText(diff) : durationText(-diff) + ' ago';
    }

    function clockText(at) {
        var date = new Date(Number(at));
        return pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds()) + '.' + pad(date.getMilliseconds(), 3);
    }

    function dateTimeText(at) {
        var date = new Date(Number(at));
        return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' ' + clockText(at);
    }

    // ── What the details say ────────────────────────────────────

    function constraintsText(constraints) {
        if (!constraints) return 'none';
        var parts = [];
        if (constraints.network && constraints.network !== 'NOT_REQUIRED') {
            parts.push('network ' + constraints.network.toLowerCase().replace(/_/g, ' '));
        }
        if (constraints.requiresCharging) parts.push('charging');
        if (constraints.requiresDeviceIdle) parts.push('device idle');
        if (constraints.requiresBatteryNotLow) parts.push('battery not low');
        if (constraints.requiresStorageNotLow) parts.push('storage not low');
        var triggers = constraints.contentUriTriggers || [];
        if (triggers.length) parts.push(triggers.length === 1 ? '1 content URI trigger' : triggers.length + ' content URI triggers');
        return parts.length ? parts.join(', ') : 'none';
    }

    // The flex shows only when it is shorter than the interval: by default they are the same.
    function periodicityText(periodicity) {
        if (!periodicity) return 'one-time';
        var text = 'every ' + durationText(periodicity.repeatIntervalMillis);
        if (periodicity.flexIntervalMillis < periodicity.repeatIntervalMillis) {
            text += ', in the last ' + durationText(periodicity.flexIntervalMillis);
        }
        return text;
    }

    function stopReasonText(reason) {
        if (!reason || reason.name === 'NOT_STOPPED') return 'NOT_STOPPED';
        return reason.name + ' (' + reason.code + ')';
    }

    function hasData(data) {
        return !!data && typeof data === 'object' && Object.keys(data).length > 0;
    }

    function jsonHtml(data, cls) {
        return '<pre class="dc-code dc-json ' + (cls || '') + '">'
            + window.debugSyntaxHighlightJson(JSON.stringify(data), { indent: 2 }) + '</pre>';
    }

    // ── Rendering ───────────────────────────────────────────────

    function nextRunHtml(item, now) {
        if (item.state === 'RUNNING') return '<span class="work-next work-next--running">running</span>';
        if (item.nextScheduleTimeMillis == null || isFinished(item.state)) return '<span class="dc-comment">-</span>';
        var at = item.nextScheduleTimeMillis;
        return '<span class="work-next" data-at="' + esc(String(at)) + '" title="' + esc(dateTimeText(at)) + '">'
            + esc(relativeText(at, now)) + '</span>';
    }

    // One row of the list. Every value from the app is escaped: a tag, a name, or
    // a class can hold any text.
    function rowHtml(item, selected, now) {
        var cls = 'dc-row work-row';
        if (item.state === 'FAILED') cls += ' dc-row--error';
        if (selected) cls += ' dc-row--selected';
        var worker = simpleClass(item.workerClass) || '?';
        var tags = appTags(item);
        var tagsHtml = tags.map(function(tag) { return '<span class="dc-tag work-tag">' + esc(tag) + '</span>'; }).join('');
        var periodic = item.periodicity ? '<span class="work-periodic" title="Periodic: ' + esc(periodicityText(item.periodicity)) + '.">↻</span>' : '';
        return '<tr class="' + cls + '" data-id="' + esc(item.id) + '" aria-selected="' + (selected ? 'true' : 'false') + '">'
            + '<td class="dc-cell work-cell--state">' + stateBadge(item.state) + '</td>'
            + '<td class="dc-cell work-cell--worker" title="' + esc(item.workerClass || '') + '">' + periodic + esc(worker) + '</td>'
            + '<td class="dc-cell work-cell--name"' + (item.uniqueName ? ' title="' + esc(item.uniqueName) + '">' + esc(item.uniqueName) : '><span class="dc-comment">-</span>') + '</td>'
            + '<td class="dc-cell work-cell--tags" title="' + esc(tags.join(', ')) + '">' + tagsHtml + '</td>'
            + '<td class="dc-cell dc-cell--num work-cell--attempts">' + esc(String(item.runAttempts == null ? '' : item.runAttempts)) + '</td>'
            + '<td class="dc-cell dc-cell--muted work-cell--next">' + nextRunHtml(item, now) + '</td>'
            + '</tr>';
    }

    function countLabel(shown, total) {
        var noun = total === 1 ? 'work request' : 'work requests';
        return shown === total ? total + ' ' + noun : shown + ' of ' + total + ' ' + noun;
    }

    function kvRow(key, valueHtml, title) {
        return '<tr><th class="dc-kv__key"' + (title ? ' title="' + esc(title) + '"' : '') + '>' + esc(key) + '</th>'
            + '<td class="dc-kv__value">' + valueHtml + '</td></tr>';
    }

    function details(item, now) {
        var tags = (item.tags || []).map(function(tag) {
            var own = tag === item.workerClass;
            return '<span class="dc-tag work-tag' + (own ? ' work-tag--class' : '') + '"'
                + (own ? ' title="WorkManager adds the worker class as a tag."' : '') + '>' + esc(tag) + '</span>';
        }).join('');
        var next = item.nextScheduleTimeMillis == null || isFinished(item.state)
            ? '<span class="dc-comment">// none</span>'
            : esc(dateTimeText(item.nextScheduleTimeMillis)) + ' <span class="dc-comment" data-at="'
                + esc(String(item.nextScheduleTimeMillis)) + '">' + esc(relativeText(item.nextScheduleTimeMillis, now)) + '</span>';
        var name = item.uniqueName
            ? esc(item.uniqueName)
            : '<span class="dc-comment">// none that the app registers with the tab</span>';
        var stopped = !item.stopReason || item.stopReason.name === 'NOT_STOPPED';
        var rows = [
            kvRow('Id', '<span class="work-mono">' + esc(item.id) + '</span>'),
            kvRow('Worker class', esc(item.workerClass || '?')),
            kvRow('State', stateBadge(item.state)),
            kvRow('Tags', tags || '<span class="dc-comment">// none</span>'),
            kvRow('Unique name', name, 'WorkInfo has no unique name: the tab knows the names that the app passes in uniqueNames, and the name of a name filter.'),
            kvRow('Constraints', esc(constraintsText(item.constraints))),
            kvRow('Periodicity', esc(periodicityText(item.periodicity))),
            kvRow('Initial delay', item.initialDelayMillis ? esc(durationText(item.initialDelayMillis)) : '<span class="dc-comment">// none</span>'),
            kvRow('Next run', next, 'When WorkManager runs the work next, on the device\'s clock.'),
            kvRow('Run attempts', esc(String(item.runAttempts))),
            kvRow('Generation', esc(String(item.generation)), 'How many times the app updated the work with WorkManager.updateWork().'),
            kvRow('Last stop', stopped ? '<span class="dc-comment">NOT_STOPPED</span>' : esc(stopReasonText(item.stopReason)), 'The reason of the last stop of a run: the system stopped the worker before it finished.'),
            kvRow('Output', hasData(item.output) ? jsonHtml(item.output, 'work-json') : '<span class="dc-comment">// empty</span>'),
            kvRow('Progress', hasData(item.progress) ? jsonHtml(item.progress, 'work-json') : '<span class="dc-comment">// empty</span>')
        ];
        return '<table class="dc-kv work-kv">' + rows.join('') + '</table>';
    }

    // ── The timeline ────────────────────────────────────────────

    function newTimeline() {
        return { events: [], seqs: new Set(), cursor: null, read: false };
    }

    function eventsPath(id, cursor) {
        return 'events?id=' + encodeURIComponent(id) + (cursor ? '&cursor=' + encodeURIComponent(cursor) : '') + '&limit=' + EVENTS_LIMIT;
    }

    // Applies one response of GET events: { reset: true } when it replaced the
    // events, the events it appended, or null when nothing changed.
    function applyEvents(timeline, data) {
        if (!data) return null;
        if (data.cursor != null) timeline.cursor = data.cursor;
        if (data.status === 'unchanged') return null;
        var items = data.items || [];
        if (data.status !== 'delta') {
            timeline.events = items.slice();
            timeline.seqs = new Set(items.map(function(event) { return event.seq; }));
            return { reset: true, appended: [] };
        }
        var appended = items.filter(function(event) { return !timeline.seqs.has(event.seq); });
        appended.forEach(function(event) {
            timeline.events.push(event);
            timeline.seqs.add(event.seq);
        });
        return appended.length ? { reset: false, appended: appended } : null;
    }

    function eventColor(event) {
        return stateColor(event.state);
    }

    function eventDetailsHtml(event) {
        var parts = [];
        if (event.type === 'finished' && event.result) {
            var color = RESULT_COLORS[event.result] ? 'var(--work-' + RESULT_COLORS[event.result] + ')' : 'var(--t3)';
            parts.push('<span class="dc-tag work-result" style="--c: ' + color + '">' + esc(event.result) + '</span>');
        }
        if (event.type === 'stopped' && event.stopReason) {
            parts.push('<span class="work-event__reason">' + esc(stopReasonText(event.stopReason)) + '</span>');
        }
        if (event.type === 'updated' && event.previousGeneration != null) {
            parts.push('<span class="work-event__note">generation ' + esc(String(event.previousGeneration)) + ' → ' + esc(String(event.generation)) + '</span>');
        }
        if (event.exception) {
            var ex = event.exception;
            parts.push('<span class="work-event__exception">' + esc(simpleClass(ex.type)) + (ex.message != null ? ': ' + esc(ex.message) : '') + '</span>');
        }
        var html = parts.length ? '<div class="work-event__details">' + parts.join('') + '</div>' : '';
        if (event.type === 'finished' && hasData(event.output)) html += jsonHtml(event.output, 'work-json work-event__json');
        if (event.exception) {
            var trace = event.exception.stackTrace || '';
            html += '<details class="work-trace"><summary class="work-trace__summary">Stack trace'
                + (event.exception.truncated ? ' <span class="dc-comment">// cut by the tab</span>' : '')
                + '</summary><pre class="dc-code work-trace__text">' + esc(trace) + '</pre>'
                + '<button type="button" class="dc-btn dc-btn--sm" data-action="copyTrace" data-seq="' + esc(String(event.seq)) + '" title="Copy the stack trace.">⧉ Copy</button></details>';
        }
        return html;
    }

    function eventHtml(event) {
        var label = EVENT_LABELS[event.type] || stateLabel(event.type);
        return '<li class="work-event" data-seq="' + esc(String(event.seq)) + '" style="--c: ' + eventColor(event) + '">'
            + '<div class="work-event__line">'
            + '<span class="work-event__time" title="' + esc(dateTimeText(event.at)) + '">' + esc(clockText(event.at)) + '</span>'
            + '<span class="dc-badge work-event__type" style="--c: ' + eventColor(event) + '">' + esc(label) + '</span>'
            + '<span class="work-event__run" title="The run attempts when the event came.">run ' + esc(String(event.runAttempts)) + '</span>'
            + (event.generation ? '<span class="work-event__run" title="The generation of the work: how many times the app updated it.">gen ' + esc(String(event.generation)) + '</span>' : '')
            + '<span class="work-event__state">→ ' + esc(stateLabel(event.state)) + '</span>'
            + '</div>'
            + eventDetailsHtml(event)
            + '</li>';
    }

    // ── The history of work-analytics ───────────────────────────

    function periodHtml(period) {
        var color = PERIOD_COLORS[period.state] ? 'var(--work-' + PERIOD_COLORS[period.state] + ')' : 'var(--t3)';
        var start = period.enqueueTimeMillis;
        function since(at) {
            if (at == null) return '<span class="dc-comment">// not yet</span>';
            var text = esc(dateTimeText(at));
            if (start != null && at >= start) text += ' <span class="dc-comment">+' + esc(durationText(at - start)) + '</span>';
            return text;
        }
        var reasons = (period.stopReasons || []).map(function(reason) {
            return esc(reason.name + ' (' + reason.code + ')') + ' ×' + esc(String(reason.count));
        }).join(', ');
        var rows = [
            kvRow('Enqueued', start == null ? '<span class="dc-comment">// not yet</span>' : esc(dateTimeText(start))),
            kvRow('Unblocked', since(period.unblockTimeMillis)),
            kvRow('First start', since(period.firstStartTimeMillis)),
            kvRow('Finished', since(period.finishTimeMillis)),
            kvRow('Worker time', period.workerDurationMillis == null ? '<span class="dc-comment">// not yet</span>' : esc(durationText(period.workerDurationMillis)), 'How long the worker ran, over all its runs.'),
            kvRow('Total time', period.totalRuntimeMillis == null ? '<span class="dc-comment">// not yet</span>' : esc(durationText(period.totalRuntimeMillis)), 'From the first start to the finish, with the waits between the runs.'),
            kvRow('Run attempts', esc(String(period.runAttempts))),
            kvRow('Retries', esc(String(period.explicitRetries)), 'How many times the worker asked for a retry with Result.retry().'),
            kvRow('Stop reasons', reasons || '<span class="dc-comment">// none</span>')
        ];
        return '<div class="work-period">'
            + '<div class="work-period__head"><span class="dc-badge" style="--c: ' + color + '">' + esc(stateLabel(period.state)) + '</span>'
            + '<span class="work-period__gen">generation ' + esc(String(period.generation)) + '</span></div>'
            + '<table class="dc-kv work-kv">' + rows.join('') + '</table>'
            + '</div>';
    }

    // ── What the view says when there are no events ─────────────

    // The banner for the envelope's state: null when the listeners are installed.
    function listenersBanner(state) {
        if (!state || !state.listeners || state.listeners === 'installed') return null;
        var fix = state.listeners === 'missing'
            ? 'Initialize WorkManager on demand: implement Configuration.Provider, pass its Configuration.Builder '
                + 'through WorkDebugTab.configure(builder) last, right before build(), and remove '
                + 'WorkManagerInitializer from the manifest.'
            : 'The tab needs WorkManager 2.12.0 or newer, and a Lustro version that forwards each method of '
                + 'its listeners. Update Lustro when the reason names methods that it does not forward.';
        return {
            kind: 'warn',
            title: 'No events: the tab\'s listeners are ' + (state.listeners === 'missing' ? 'not installed' : 'not supported') + '. The list shows the work without the events of its runs.',
            fix: fix,
            reason: state.reason || ''
        };
    }

    // The banner for a refused GET items, or null for a refusal that is not about
    // WorkManager, which the poll then counts as a failed request.
    function rejectionBanner(status, envelope) {
        var code = envelope && envelope.code;
        if (code === 'work_not_initialized') {
            return { kind: 'info', title: 'WorkManager has not started in the app yet. The tab does not start it.', fix: envelope.hint || '', reason: envelope.message || '' };
        }
        if (code === 'work_unsupported') {
            return { kind: 'error', title: 'The tab can\'t read this app\'s WorkManager.', fix: envelope.hint || '', reason: envelope.message || '' };
        }
        if (code === 'work_error') {
            return { kind: 'error', title: 'WorkManager did not answer.', fix: '', reason: envelope.message || '' };
        }
        return null;
    }

    function emptyText(rejected, total, filtered) {
        if (rejected === 'work_not_initialized') return '// WorkManager has not started in the app yet';
        if (rejected) return '// the tab can\'t read this app\'s WorkManager';
        if (total === 0 && !filtered) return '// the app has no work in WorkManager';
        return '// no work matches the filters';
    }

    function timelineEmptyText(state) {
        if (state && state.listeners && state.listeners !== 'installed') return '// no events: the tab\'s listeners are not installed (see the banner)';
        return '// no events of this work in the tab\'s buffer: it records them from Lustro.start(), in this process';
    }

    // What a confirm dialog asks, and the body of POST cancel or prune.
    function confirmFor(kind, item) {
        var worker = simpleClass(item && item.workerClass) || 'the work';
        if (kind === 'cancel') {
            return {
                title: 'Cancel work',
                message: 'Cancel ' + worker + ' (' + item.id + ')? WorkManager stops it if it runs, and also cancels the work that waits for it in a chain.'
                    + (item.periodicity ? ' A periodic work does not run again until the app enqueues it again.' : '')
                    + ' This can\'t be undone.',
                ok: 'Cancel work',
                path: 'cancel',
                body: { id: item.id }
            };
        }
        if (kind === 'cancelName') {
            return {
                title: 'Cancel unique work',
                message: 'Cancel all unfinished work of the unique name "' + item.uniqueName + '"? WorkManager stops the work that runs. This can\'t be undone.',
                ok: 'Cancel unique work',
                path: 'cancel',
                body: { name: item.uniqueName }
            };
        }
        return {
            title: 'Prune finished work',
            message: 'Delete all finished work (succeeded, failed, and cancelled) that no unfinished work depends on from WorkManager\'s database, as WorkManager.pruneWork() does. '
                + 'Its state, output, and tags are gone after that; the events that the tab recorded stay. This can\'t be undone.',
            ok: 'Prune',
            path: 'prune',
            body: null
        };
    }

    function resultToast(path, data) {
        if (path === 'prune') {
            var pruned = data && data.pruned || 0;
            return pruned ? { text: 'Pruned ' + pruned + (pruned === 1 ? ' work request' : ' work requests'), type: 'success' } : { text: 'Nothing to prune', type: 'info' };
        }
        var cancelled = data && data.cancelled || 0;
        return cancelled
            ? { text: 'Cancelled ' + cancelled + (cancelled === 1 ? ' work request' : ' work requests'), type: 'success' }
            : { text: 'Nothing to cancel: the work had finished', type: 'info' };
    }

    window.workNewList = newList;
    window.workItemsPath = itemsPath;
    window.workApplyItems = applyItems;
    window.workStateCounts = stateCounts;
    window.workVisibleItems = visibleItems;
    window.workSimpleClass = simpleClass;
    window.workAppTags = appTags;
    window.workDurationText = durationText;
    window.workRelativeText = relativeText;
    window.workConstraintsText = constraintsText;
    window.workPeriodicityText = periodicityText;
    window.workStopReasonText = stopReasonText;
    window.workRowHtml = rowHtml;
    window.workCountLabel = countLabel;
    window.workDetailsHtml = details;
    window.workNewTimeline = newTimeline;
    window.workEventsPath = eventsPath;
    window.workApplyEvents = applyEvents;
    window.workEventHtml = eventHtml;
    window.workPeriodHtml = periodHtml;
    window.workListenersBanner = listenersBanner;
    window.workRejectionBanner = rejectionBanner;
    window.workEmptyText = emptyText;
    window.workConfirmFor = confirmFor;
    window.workResultToast = resultToast;

    // ── The page ────────────────────────────────────────────────

    var list = newList();
    var filters = { tag: '', workerClass: '', name: '' };
    var states = new Set();
    // The code of the refusal of the last GET items, or null after an answer.
    var rejected = null;
    // Every change of the filters starts a generation: an answer to a request of
    // an earlier one is late and changes nothing.
    var generation = 0;
    var itemsPoll = null;
    var filterTimer = null;
    var suggestions = { tags: new Set(), classes: new Set(), names: new Set() };
    // The key of each drawn row: its work as JSON, and whether it is selected.
    var rowKeys = new Map();

    // The selected work, its timeline, and what GET items/{id} said of it.
    var selectedId = null;
    var selection = 0;
    var timeline = newTimeline();
    var eventsPoll = null;
    var detail = null;
    // The work as the drawer showed it last, for after WorkManager deleted it.
    var lastItem = null;
    var detailBusy = false;
    var detailAgain = false;
    var shownItemJson = null;
    var panel = 'details';
    var stuckToEnd = true;

    var confirmModal = null;
    var pending = null;
    var els = {};

    function store(key, value) {
        try { window.localStorage.setItem(key, value); } catch(e) {}
    }

    function stored(key) {
        try { return window.localStorage.getItem(key); } catch(e) { return null; }
    }

    // ── Polling the list ────────────────────────────────────────

    function startItemsPoll() {
        if (itemsPoll) itemsPoll.stop();
        var requestGeneration = -1;
        itemsPoll = window.debugPoll(function() {
            requestGeneration = generation;
            return window.lustroApiUrl(itemsPath(filters, list.cursor));
        }, ITEMS_POLL_MS, function(data) {
            if (requestGeneration === generation) onItems(data);
        }, function(status, envelope) {
            if (requestGeneration !== generation) return true;
            var banner = rejectionBanner(status, envelope);
            if (!banner) return false;
            onRejected(envelope.code, banner);
            return true;
        });
    }

    function onItems(data) {
        rejected = null;
        var changed = applyItems(list, data);
        if (changed) {
            collectSuggestions();
            renderRows();
            syncSelected();
        }
        renderBanner(listenersBanner(list.state));
        renderTimes();
        renderCounts();
    }

    function onRejected(code, banner) {
        rejected = code;
        list = newList();
        renderBanner(banner);
        renderRows();
        renderCounts();
    }

    function filtered() {
        return !!(filters.tag || filters.workerClass || filters.name);
    }

    // Replaces only the rows whose work changed, so that a click on a row is not
    // lost to a poll that draws the list again between its press and its release.
    function renderRows() {
        var now = Date.now();
        var shown = visibleItems(list.items, states);
        var keys = shown.map(function(item) { return JSON.stringify(item) + (item.id === selectedId ? '|selected' : ''); });
        var rows = els.rows.children;
        var sameRows = rows.length === shown.length && shown.every(function(item, i) { return rows[i].dataset.id === item.id; });
        if (!sameRows) {
            els.rows.innerHTML = shown.map(function(item) { return rowHtml(item, item.id === selectedId, now); }).join('');
        } else {
            shown.forEach(function(item, i) {
                if (rowKeys.get(item.id) === keys[i]) return;
                var body = document.createElement('tbody');
                body.innerHTML = rowHtml(item, item.id === selectedId, now);
                els.rows.replaceChild(body.firstElementChild, rows[i]);
            });
        }
        rowKeys = new Map(shown.map(function(item, i) { return [item.id, keys[i]]; }));
        els.empty.hidden = shown.length > 0;
        els.empty.textContent = list.cursor || rejected
            ? emptyText(rejected, list.items.length, filtered() || states.size > 0)
            : '// reading the work…';
    }

    function renderCounts() {
        var counts = stateCounts(list.items);
        els.chips.querySelectorAll('[data-count]').forEach(function(span) {
            var count = counts[span.dataset.count];
            span.textContent = count ? ' ' + count : '';
        });
        var shown = visibleItems(list.items, states).length;
        els.count.textContent = rejected ? '' : countLabel(shown, list.items.length);
    }

    // The relative times count on between the polls that change nothing.
    function renderTimes() {
        var now = Date.now();
        els.root.querySelectorAll('[data-at]').forEach(function(el) {
            el.textContent = relativeText(Number(el.dataset.at), now);
        });
    }

    function renderBanner(banner) {
        els.banner.hidden = !banner;
        if (!banner) return;
        els.banner.className = 'work-banner work-banner--' + banner.kind;
        els.bannerTitle.textContent = banner.title;
        els.bannerFix.textContent = banner.fix;
        els.bannerFix.hidden = !banner.fix;
        els.bannerReason.textContent = banner.reason ? '// ' + banner.reason : '';
        els.bannerReason.hidden = !banner.reason;
    }

    function collectSuggestions() {
        var grew = false;
        function add(set, value) {
            if (value && !set.has(value)) { set.add(value); grew = true; }
        }
        list.items.forEach(function(item) {
            appTags(item).forEach(function(tag) { add(suggestions.tags, tag); });
            add(suggestions.classes, simpleClass(item.workerClass));
            add(suggestions.names, item.uniqueName);
        });
        ((list.state && list.state.uniqueNames) || []).forEach(function(name) { add(suggestions.names, name); });
        if (!grew) return;
        fillList(els.tagList, suggestions.tags);
        fillList(els.classList, suggestions.classes);
        fillList(els.nameList, suggestions.names);
    }

    function fillList(datalist, values) {
        datalist.innerHTML = Array.from(values).sort().map(function(value) {
            return '<option value="' + esc(value) + '"></option>';
        }).join('');
    }

    // ── Filters ─────────────────────────────────────────────────

    function toggleState(state) {
        if (states.has(state)) states.delete(state);
        else states.add(state);
        els.chips.querySelectorAll('[data-state]').forEach(function(chip) {
            var on = states.has(chip.dataset.state);
            chip.classList.toggle('dc-chip--active', on);
            chip.setAttribute('aria-pressed', on ? 'true' : 'false');
        });
        renderRows();
        renderCounts();
    }

    function onFilterInput(input) {
        showClear(input);
        if (filterTimer) clearTimeout(filterTimer);
        filterTimer = setTimeout(applyInputs, FILTER_DEBOUNCE_MS);
    }

    function applyInputs() {
        if (filterTimer) { clearTimeout(filterTimer); filterTimer = null; }
        var next = { tag: els.tag.value.trim(), workerClass: els.workerClass.value.trim(), name: els.name.value.trim() };
        if (next.tag === filters.tag && next.workerClass === filters.workerClass && next.name === filters.name) return;
        filters = next;
        generation++;
        list = newList();
        renderRows();
        renderCounts();
        startItemsPoll();
    }

    function showClear(input) {
        var clear = els.root.querySelector('[data-action="clearField"][data-target="' + input.id + '"]');
        if (clear) clear.hidden = !input.value;
    }

    // ── The selected work ───────────────────────────────────────

    function select(id) {
        if (!id || id === selectedId) return;
        selectedId = id;
        selection++;
        timeline = newTimeline();
        detail = null;
        lastItem = null;
        shownItemJson = null;
        stuckToEnd = true;
        renderRows();
        els.none.hidden = true;
        els.drawer.hidden = false;
        els.events.innerHTML = '';
        els.periods.innerHTML = '';
        els.timelineCount.textContent = '';
        els.historyCount.textContent = '';
        els.timelineNote.hidden = true;
        els.eventsEmpty.hidden = true;
        els.periodsEmpty.hidden = true;
        els.historyTab.hidden = !(list.state && list.state.metrics);
        if (panel === 'history' && els.historyTab.hidden) showPanel('details');
        renderDrawer();
        startEventsPoll();
        loadDetail();
    }

    function closeDetail() {
        selectedId = null;
        selection++;
        if (eventsPoll) { eventsPoll.stop(); eventsPoll = null; }
        detail = null;
        els.drawer.hidden = true;
        els.none.hidden = false;
        renderRows();
    }

    // The list's copy of the work is the newest; GET items/{id} has the work that
    // the list does not show, such as filtered-out work. Pruned work keeps the
    // state that the drawer showed last.
    function currentItem() {
        var item = list.byId.get(selectedId) || (detail && detail.item) || null;
        if (item) lastItem = item;
        return item || lastItem;
    }

    // After a change of the list: the drawer follows the work's new state, and
    // reads its history again.
    function syncSelected() {
        if (!selectedId) return;
        var item = list.byId.get(selectedId);
        var json = item ? JSON.stringify(item) : null;
        if (json === shownItemJson) return;
        renderDrawer();
        loadDetail();
    }

    function renderDrawer() {
        var item = currentItem();
        var json = item ? JSON.stringify(item) : null;
        var gone = !!detail && !detail.item && !list.byId.has(selectedId);
        if (json !== shownItemJson || !item) {
            shownItemJson = json;
            els.detailsPanel.innerHTML = item ? details(item, Date.now()) : '<div class="work-empty">// reading the work…</div>';
        }
        var state = item ? item.state : null;
        els.headState.hidden = !item;
        els.headState.textContent = item ? stateLabel(state) : '';
        els.headState.style.setProperty('--c', stateColor(state));
        els.headClass.textContent = item ? simpleClass(item.workerClass) || '?' : '';
        els.headClass.title = item ? item.workerClass || '' : '';
        els.headId.textContent = selectedId;
        var finished = !item || gone || isFinished(state);
        els.cancel.disabled = finished;
        els.cancel.title = finished
            ? 'The work has finished: there is nothing to cancel.'
            : 'Cancel this work, as WorkManager.cancelWorkById() does. It asks first.';
        var name = item && item.uniqueName;
        els.cancelName.hidden = !name || gone;
        if (name) {
            els.cancelName.textContent = 'Cancel unique work';
            els.cancelName.title = 'Cancel all unfinished work of the unique name "' + name + '", as WorkManager.cancelUniqueWork() does. It asks first.';
        }
        els.gone.hidden = !gone;
        if (gone) {
            els.gone.textContent = lastItem
                ? 'WorkManager no longer has this work: it was pruned, or the app replaced it. The details are as the tab saw them last, and the tab keeps its events.'
                : 'WorkManager no longer has this work: it was pruned, or the app replaced it. The tab keeps its events.';
        }
    }

    function loadDetail() {
        if (!selectedId) return;
        if (detailBusy) { detailAgain = true; return; }
        detailBusy = true;
        detailAgain = false;
        var id = selectedId;
        var requestSelection = selection;
        window.debugFetch(window.lustroApiUrl('items/' + encodeURIComponent(id)))
            .then(function(r) { return r.json(); })
            .then(function(data) {
                if (requestSelection !== selection) return;
                detail = data;
                renderDrawer();
                renderHistory();
            })
            .catch(function(e) {
                if (requestSelection !== selection) return;
                if (e && e.status === 404) {
                    detail = { item: null, events: [] };
                    renderDrawer();
                    renderHistory();
                }
            })
            .then(function() {
                detailBusy = false;
                if (detailAgain && selectedId) loadDetail();
            });
    }

    function renderHistory() {
        var periods = detail && detail.metrics;
        var on = !!(list.state && list.state.metrics) || Array.isArray(periods);
        els.historyTab.hidden = !on;
        if (!on) {
            if (panel === 'history') showPanel('details');
            return;
        }
        periods = periods || [];
        els.historyCount.textContent = periods.length ? ' ' + periods.length : '';
        // Newest first: the last run is the one to read.
        els.periods.innerHTML = periods.slice().reverse().map(periodHtml).join('');
        els.periodsEmpty.hidden = periods.length > 0;
        els.periodsEmpty.textContent = '// work-analytics has no period of this work';
    }

    // ── The timeline ────────────────────────────────────────────

    function startEventsPoll() {
        if (eventsPoll) eventsPoll.stop();
        var requestSelection = -1;
        eventsPoll = window.debugPoll(function() {
            requestSelection = selection;
            return window.lustroApiUrl(eventsPath(selectedId, timeline.cursor));
        }, EVENTS_POLL_MS, function(data) {
            if (requestSelection === selection) onEvents(data);
        }, function(status, envelope) {
            // A WorkManager that the tab can't read: GET items says why.
            return requestSelection !== selection || !!(envelope && envelope.code === 'work_unsupported');
        });
    }

    function onEvents(data) {
        var follow = stuckToEnd;
        var changes = applyEvents(timeline, data);
        if (changes) {
            if (changes.reset) els.events.innerHTML = timeline.events.map(eventHtml).join('');
            else els.events.insertAdjacentHTML('beforeend', changes.appended.map(eventHtml).join(''));
            // A new event means a new state, and a new period of its history. The
            // first answer comes with the selection, which reads them already.
            if (timeline.read) loadDetail();
            if (follow) scrollTimelineToEnd();
        }
        timeline.read = true;
        var count = timeline.events.length;
        els.timelineCount.textContent = count ? ' ' + count : '';
        els.eventsEmpty.hidden = count > 0;
        els.eventsEmpty.textContent = timelineEmptyText(data && data.state);
        var banner = data && listenersBanner(data.state);
        els.timelineNote.hidden = !banner || count === 0;
        if (banner) els.timelineNote.textContent = '// the tab\'s listeners are not installed now, so later events are missing';
    }

    function scrollTimelineToEnd() {
        var el = els.timelinePanel;
        el.scrollTop = el.scrollHeight;
    }

    function showPanel(name) {
        if (PANELS.indexOf(name) < 0) return;
        panel = name;
        store(PANEL_KEY, name);
        els.root.querySelectorAll('[data-action="showPanel"]').forEach(function(tab) {
            var on = tab.dataset.panel === name;
            tab.classList.toggle('dc-tab--active', on);
            tab.setAttribute('aria-selected', on ? 'true' : 'false');
        });
        els.detailsPanel.hidden = name !== 'details';
        els.timelinePanel.hidden = name !== 'timeline';
        els.historyPanel.hidden = name !== 'history';
        if (name === 'timeline' && stuckToEnd) scrollTimelineToEnd();
    }

    // ── Actions ─────────────────────────────────────────────────

    function ask(kind) {
        var item = currentItem();
        if (kind !== 'prune' && !item) return;
        pending = confirmFor(kind, item);
        els.confirmTitle.textContent = pending.title;
        els.confirmMessage.textContent = pending.message;
        els.confirmOk.textContent = pending.ok;
        confirmModal.show();
        els.confirmOk.focus();
    }

    function runPending() {
        var action = pending;
        pending = null;
        confirmModal.hide();
        if (!action) return;
        var options = { method: 'POST' };
        if (action.body) {
            options.headers = { 'Content-Type': 'application/json' };
            options.body = JSON.stringify(action.body);
        }
        window.debugFetch(window.lustroApiUrl(action.path), options)
            .then(function(r) { return r.json(); })
            .then(function(data) {
                var toast = resultToast(action.path, data);
                window.debugToast(toast.text, toast.type);
                loadDetail();
            })
            .catch(function(e) {
                var fallback = action.path === 'prune' ? 'The app did not prune the work. Try again.' : 'The app did not cancel the work. Try again.';
                if (!e || !e.response) {
                    window.debugToast(fallback, 'error');
                    return;
                }
                // A refusal is an answer of the app: it does not mean a lost connection.
                window.debugSetStatus('connected');
                e.response.json().then(function(envelope) {
                    window.debugToast(envelope && envelope.message ? envelope.message : fallback, 'error');
                }, function() {
                    window.debugToast(fallback, 'error');
                });
            });
    }

    function copyTrace(seq) {
        var event = timeline.events.find(function(e) { return String(e.seq) === String(seq); });
        if (event && event.exception) window.debugCopyToClipboard(event.exception.stackTrace || '');
    }

    function moveSelection(step) {
        var rows = Array.from(els.rows.querySelectorAll('tr[data-id]'));
        if (!rows.length) return;
        var index = rows.findIndex(function(row) { return row.dataset.id === selectedId; });
        var next = rows[index < 0 ? (step > 0 ? 0 : rows.length - 1) : Math.max(0, Math.min(rows.length - 1, index + step))];
        select(next.dataset.id);
        next.scrollIntoView({ block: 'nearest' });
    }

    var CLICK_ACTIONS = {
        toggleState: function(el) { toggleState(el.dataset.state); },
        clearField: function(el) {
            var input = document.getElementById(el.dataset.target);
            if (!input) return;
            input.value = '';
            showClear(input);
            applyInputs();
            input.focus();
        },
        closeDetail: closeDetail,
        showPanel: function(el) { showPanel(el.dataset.panel); },
        askCancelId: function() { ask('cancel'); },
        askCancelName: function() { ask('cancelName'); },
        askPrune: function() { ask('prune'); },
        confirmOk: runPending,
        closeConfirm: function() { pending = null; confirmModal.hide(); },
        copyId: function() { if (selectedId) window.debugCopyToClipboard(selectedId); },
        copyTrace: function(el) { copyTrace(el.dataset.seq); }
    };

    function typing(target) {
        return !!target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable);
    }

    function setupEvents() {
        var root = els.root;
        root.addEventListener('click', function(ev) {
            var el = ev.target.closest('[data-action]');
            if (el && root.contains(el)) {
                var fn = CLICK_ACTIONS[el.dataset.action];
                if (fn) fn(el, ev);
                return;
            }
            var row = ev.target.closest('tr[data-id]');
            if (row && els.rows.contains(row)) select(row.dataset.id);
        });
        root.addEventListener('input', function(ev) {
            if (ev.target.dataset && ev.target.dataset.action === 'filterInput') onFilterInput(ev.target);
        });
        els.timelinePanel.addEventListener('scroll', function() {
            var el = els.timelinePanel;
            stuckToEnd = el.scrollHeight - el.scrollTop - el.clientHeight < STICK_TO_END_PX;
        });
        document.addEventListener('keydown', function(ev) {
            if (ev.defaultPrevented || typing(ev.target) || !els.confirm.hidden) return;
            if (ev.key === 'Escape' && selectedId) {
                closeDetail();
            } else if ((ev.key === 'ArrowDown' || ev.key === 'ArrowUp') && (ev.target === document.body || els.list.contains(ev.target))) {
                // In the drawer, the arrows scroll it as usual.
                ev.preventDefault();
                moveSelection(ev.key === 'ArrowDown' ? 1 : -1);
            }
        });
    }

    function init() {
        var root = document.getElementById('work-root');
        if (!root) return;
        els = {
            root: root,
            banner: document.getElementById('work-banner'),
            bannerTitle: document.getElementById('work-banner-title'),
            bannerFix: document.getElementById('work-banner-fix'),
            bannerReason: document.getElementById('work-banner-reason'),
            list: root.querySelector('.work-list'),
            count: document.getElementById('work-count'),
            chips: document.getElementById('work-chips'),
            tag: document.getElementById('work-tag'),
            workerClass: document.getElementById('work-class'),
            name: document.getElementById('work-name'),
            tagList: document.getElementById('work-tag-list'),
            classList: document.getElementById('work-class-list'),
            nameList: document.getElementById('work-name-list'),
            rows: document.getElementById('work-rows'),
            empty: document.getElementById('work-empty'),
            none: document.getElementById('work-none'),
            drawer: document.getElementById('work-drawer'),
            headState: document.getElementById('work-head-state'),
            headClass: document.getElementById('work-head-class'),
            headId: document.getElementById('work-head-id'),
            cancel: document.getElementById('work-cancel'),
            cancelName: document.getElementById('work-cancel-name'),
            gone: document.getElementById('work-gone'),
            timelineCount: document.getElementById('work-timeline-count'),
            historyTab: document.getElementById('work-history-tab'),
            historyCount: document.getElementById('work-history-count'),
            detailsPanel: document.getElementById('work-panel-details'),
            timelinePanel: document.getElementById('work-panel-timeline'),
            historyPanel: document.getElementById('work-panel-history'),
            timelineNote: document.getElementById('work-timeline-note'),
            events: document.getElementById('work-events'),
            eventsEmpty: document.getElementById('work-events-empty'),
            periods: document.getElementById('work-periods'),
            periodsEmpty: document.getElementById('work-periods-empty'),
            confirm: document.getElementById('work-confirm-modal'),
            confirmTitle: document.getElementById('work-confirm-title'),
            confirmMessage: document.getElementById('work-confirm-message'),
            confirmOk: document.getElementById('work-confirm-ok')
        };
        confirmModal = window.debugModal('work-confirm-modal');
        var savedPanel = stored(PANEL_KEY);
        if (PANELS.indexOf(savedPanel) >= 0) showPanel(savedPanel);
        setupEvents();
        window.debugInitResizers();
        renderRows();
        renderCounts();
        startItemsPoll();
    }

    if (typeof window.lustroOnContentReady === 'function') {
        window.lustroOnContentReady(init);
    } else {
        init();
    }
})();
