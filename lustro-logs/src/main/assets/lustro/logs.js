// The Logs tab: a live tail of the app's own logcat. It polls GET entries in the
// stream envelope, with the filters as query parameters, so the app filters the
// whole buffer and the page holds only the lines that pass. logs.openapi.json
// describes the routes.
(function() {
    var POLL_MS = 500;
    // The first poll after a reset gets the last lines that pass the filters; a
    // later poll gets up to the server's cap, so a burst comes in one response.
    var RESET_LIMIT = 1000;
    var DELTA_LIMIT = 5000;
    var MAX_ROWS = 5000;
    var COLLAPSE_AT = 240;
    var FILTER_DEBOUNCE_MS = 300;
    var TOOLS_DELAY_MS = 1000;
    var TOOLS_HIDE_DELAY_MS = 200;
    var STICK_TO_END_PX = 50;
    var MIN_COLUMN_WIDTH = 30;
    var MIN_MESSAGE_WIDTH = 280;
    var COLUMNS_KEY = 'debug-logs-columns';
    var WIDTHS_KEY = 'debug-logs-column-widths';

    var COLUMNS = ['lvl', 'time', 'tag', 'pid', 'msg'];
    var DEFAULT_COLUMNS = { lvl: true, time: true, tag: true, pid: false, msg: true };
    var DEFAULT_WIDTHS = { lvl: 46, time: 130, tag: 186, pid: 118 };
    var LEVELS = { V: 'Verbose', D: 'Debug', I: 'Info', W: 'Warning', E: 'Error', F: 'Fatal' };

    // ── The page's copy of the log ──────────────────────────────

    function newView() {
        return { entries: [], bySeq: new Map(), cursor: null, missed: 0 };
    }

    // Applies one response of GET entries to the lines the page holds, and
    // returns what changed for the rows, or null when nothing did:
    // { reset: true } when the response replaced the lines, or the rows to remove
    // from the start, the indexes (after the removal) of the rows to render again,
    // and the entries to append.
    //
    // A delta appends, but a line that repeats the line before comes again with
    // its seq and a larger count: it replaces the entry it repeats. The page keeps
    // the newest maxRows lines.
    function applyEnvelope(view, data, maxRows) {
        if (!data) return null;
        if (data.cursor != null) view.cursor = data.cursor;
        if (data.status === 'unchanged') return null;
        var items = data.items || [];
        if (data.status !== 'delta') {
            // 'reset', or a status this page doesn't know: these are the lines now.
            view.entries = items.slice(Math.max(0, items.length - maxRows));
            view.bySeq = new Map();
            view.entries.forEach(function(entry) { view.bySeq.set(entry.seq, entry); });
            view.missed = 0;
            return { reset: true };
        }
        if (data.dropped) view.missed += data.dropped;
        var entries = view.entries;
        var before = entries.length;
        var replaced = [];
        items.forEach(function(item) {
            var held = view.bySeq.get(item.seq);
            if (held) {
                // The repeated line is the newest one, so the search from the end is short.
                var index = entries.lastIndexOf(held);
                entries[index] = item;
                if (index < before) replaced.push(index);
            } else {
                entries.push(item);
            }
            view.bySeq.set(item.seq, item);
        });
        var removed = Math.max(0, entries.length - maxRows);
        var appended = entries.slice(Math.max(before, removed));
        entries.splice(0, removed).forEach(function(entry) { view.bySeq.delete(entry.seq); });
        var removedRows = Math.min(removed, before);
        return {
            reset: false,
            removed: removedRows,
            replaced: replaced
                .filter(function(index) { return index >= removedRows; })
                .map(function(index) { return index - removedRows; }),
            appended: appended
        };
    }

    function entriesPath(filters, cursor, limit) {
        var params = [];
        if (cursor) params.push('cursor=' + encodeURIComponent(cursor));
        if (filters.level && filters.level !== 'V') params.push('level=' + encodeURIComponent(filters.level));
        if (filters.tag) params.push('tag=' + encodeURIComponent(filters.tag));
        if (filters.q) params.push('q=' + encodeURIComponent(filters.q));
        params.push('limit=' + limit);
        return 'entries?' + params.join('&');
    }

    // ── Rendering ───────────────────────────────────────────────

    function levelOf(entry) {
        return LEVELS[entry.level] ? entry.level : 'V';
    }

    function rowClass(level) {
        if (level === 'W') return 'dc-row logs-row dc-row--warn';
        if (level === 'E') return 'dc-row logs-row dc-row--error';
        if (level === 'F') return 'dc-row logs-row dc-row--error logs-row--fatal';
        return 'dc-row logs-row';
    }

    // The time of day of logcat's "MM-dd HH:mm:ss.SSS"; the title has the date.
    function timeOfDay(timestamp) {
        var text = String(timestamp || '');
        var space = text.indexOf(' ');
        return space >= 0 ? text.slice(space + 1) : text;
    }

    // A message has no line breaks: logcat prints each line of a multi-line
    // message, such as a stack trace, as a line of its own.
    function isLongMessage(message) {
        return message.length > COLLAPSE_AT;
    }

    function repeatTitle(entry) {
        var title = 'Repeated ' + entry.count + ' times in a row';
        if (entry.lastAt != null && entry.at != null && entry.lastAt > entry.at) {
            title += ', over ' + ((entry.lastAt - entry.at) / 1000).toFixed(1) + ' s';
        }
        return title + '.';
    }

    // One row of the table. Every value from the app is escaped: a log line can
    // hold any text, markup too.
    function rowHtml(entry, columns, open) {
        var esc = window.debugEscapeHtml;
        var level = levelOf(entry);
        var message = String(entry.message == null ? '' : entry.message);
        var tag = String(entry.tag == null ? '' : entry.tag);
        function cell(column, cls, attrs, html) {
            return '<td class="dc-cell ' + cls + '" data-col="' + column + '"' + attrs + (columns[column] ? '' : ' hidden') + '>' + html + '</td>';
        }
        var msgClass = 'logs-msg';
        var msgAttrs = '';
        if (isLongMessage(message)) {
            msgClass += ' logs-msg--long' + (open ? ' logs-msg--open' : '');
            msgAttrs = ' data-action="toggleMessage" title="' + (open ? 'Click to fold the message.' : 'Click to show the whole message.') + '"';
        }
        var pill = entry.count > 1
            ? '<span class="logs-repeat" title="' + esc(repeatTitle(entry)) + '">×' + esc(String(entry.count)) + '</span>'
            : '';
        return '<tr class="' + rowClass(level) + '" data-seq="' + esc(String(entry.seq)) + '">'
            + cell('lvl', 'logs-cell--lvl logs-lvl--' + level, '', esc(String(entry.level || '')))
            + cell('time', 'dc-cell--muted', ' title="' + esc(String(entry.timestamp || '')) + '"', esc(timeOfDay(entry.timestamp)))
            + cell('tag', 'logs-cell--tag', ' title="' + esc(tag) + '"', esc(tag))
            + cell('pid', 'dc-cell--muted', '', esc(entry.pid + '/' + entry.tid))
            + cell('msg', 'logs-cell--msg', '', '<div class="logs-msg-wrap"><span class="' + msgClass + '"' + msgAttrs + '>' + esc(message) + '</span>' + pill + '</div>')
            + '</tr>';
    }

    // The lines as text, one a line, with the columns that the table shows, in
    // its order, two spaces apart. A repeated line ends with [×N], as in GET text.
    function rowsText(entries, columns) {
        return entries.map(function(entry) {
            var parts = [];
            if (columns.lvl) parts.push(entry.level);
            if (columns.time) parts.push(entry.timestamp);
            if (columns.tag) parts.push(entry.tag);
            if (columns.pid) parts.push(entry.pid + '/' + entry.tid);
            parts.push(entry.message + (entry.count > 1 ? ' [×' + entry.count + ']' : ''));
            return parts.join('  ') + '\n';
        }).join('');
    }

    function countLabel(lines, missed) {
        var label = lines === 1 ? '1 line' : lines + ' lines';
        if (missed > 0) label += ' · ' + missed + ' missed';
        return label;
    }

    function readerBanner(state) {
        if (!state) return null;
        if (state.reader === 'failed') {
            return {
                kind: 'error',
                text: 'The tab can\'t read logcat' + (state.readerError ? ': ' + state.readerError : '')
                    + '. The list keeps the lines it read before.'
            };
        }
        if (state.reader === 'restarting') {
            return { kind: 'warn', text: 'Logcat ended. The tab starts it again in a second.' };
        }
        // No client can poll 'stopped': the reader stops only when the server does.
        return null;
    }

    // The JSON in a message: all of it, or else the longer of the text from its
    // first { to its last } and from its first [ to its last ] that parses, or
    // null. Each bracket is tried alone because a logger often puts a bracketed
    // note before or after the body: "[req 42] response: {...}", "{...} [128ms]".
    function extractJson(text) {
        if (!text || !/[{[]/.test(text)) return null;
        try { JSON.parse(text); return text; } catch(e) {}
        var found = null;
        [['{', '}'], ['[', ']']].forEach(function(pair) {
            var start = text.indexOf(pair[0]);
            var end = text.lastIndexOf(pair[1]);
            if (start < 0 || end <= start) return;
            var slice = text.slice(start, end + 1);
            if (found !== null && found.length >= slice.length) return;
            try { JSON.parse(slice); found = slice; } catch(e) {}
        });
        return found;
    }

    // A regex that finds exactly this tag. Java's regex syntax escapes the same
    // characters as JavaScript's.
    function exactTagRegex(tag) {
        return '^' + String(tag).replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$';
    }

    function readColumns(storage) {
        var columns = Object.assign({}, DEFAULT_COLUMNS);
        try {
            var stored = JSON.parse(storage.getItem(COLUMNS_KEY) || 'null');
            if (stored && typeof stored === 'object') {
                COLUMNS.forEach(function(column) {
                    if (typeof stored[column] === 'boolean') columns[column] = stored[column];
                });
            }
        } catch(e) {}
        columns.msg = true;
        return columns;
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

    window.logsNewView = newView;
    window.logsApplyEnvelope = applyEnvelope;
    window.logsEntriesPath = entriesPath;
    window.logsRowHtml = rowHtml;
    window.logsRowsText = rowsText;
    window.logsCountLabel = countLabel;
    window.logsReaderBanner = readerBanner;
    window.logsExtractJson = extractJson;
    window.logsExactTagRegex = exactTagRegex;
    window.logsReadColumns = readColumns;
    window.logsReadWidths = readWidths;

    // ── The page ────────────────────────────────────────────────

    var view = newView();
    var filters = { level: 'V', tag: '', q: '' };
    var levelBeforeFatal = 'V';
    // A tag regex the app rejected: the list leaves it out until it changes.
    var rejectedTag = null;
    var columns = DEFAULT_COLUMNS;
    var widths = {};
    var openSeqs = new Set();
    var state = { paused: false, compact: true };
    // Every change of filters, and every clear, starts a generation: a response
    // to a request of an earlier one is late and changes nothing.
    var generation = 0;
    var poll = null;
    var filterTimer = null;
    var jsonModal = null;
    var jsonText = '';
    var els = {};
    // Set while the list scrolls to a new line by itself: that scroll doesn't
    // cancel a hover that waits for the row toolbar, as one by the user does.
    var followScroll = false;
    // The list follows the tail, but waits for the row toolbar to close.
    var followPending = false;
    // Whether the list was at its end at its last scroll. A change of its height,
    // such as a smaller window or the banner, scrolls no list, so it keeps this.
    var stuckToEnd = true;

    function storage() {
        try { return window.localStorage; } catch(e) { return null; }
    }

    function store(key, value) {
        try { window.localStorage.setItem(key, JSON.stringify(value)); } catch(e) {}
    }

    function activeTag() {
        return filters.tag && filters.tag !== rejectedTag ? filters.tag : '';
    }

    function startPolling() {
        if (poll) poll.stop();
        var requestGeneration = -1;
        var requestTag = '';
        poll = window.debugPoll(function() {
            requestGeneration = generation;
            requestTag = activeTag();
            var limit = view.cursor ? DELTA_LIMIT : RESET_LIMIT;
            var query = { level: filters.level, tag: requestTag, q: filters.q };
            return window.lustroApiUrl(entriesPath(query, view.cursor, limit));
        }, POLL_MS, function(data) {
            if (requestGeneration === generation) onEntries(data);
        }, function(status, envelope) {
            if (requestGeneration !== generation) return true;
            if (status !== 400 || !envelope || envelope.field !== 'tag' || !requestTag) return false;
            rejectTag(requestTag, envelope.message);
            return true;
        });
    }

    function onEntries(data) {
        if (!data) return;
        updateState(data.state);
        var follow = followPending || stuckToEnd;
        var changes = applyEnvelope(view, data, MAX_ROWS);
        if (changes) {
            if (changes.reset) {
                openSeqs.clear();
                els.rows.innerHTML = view.entries.map(renderRow).join('');
                follow = true;
            } else {
                applyChanges(changes);
            }
            if (follow) followEnd();
            else if (changes.reset || changes.appended.length) els.jump.hidden = false;
        }
        renderCount();
        renderEmpty();
    }

    function applyChanges(changes) {
        var rows = els.rows;
        for (var i = 0; i < changes.removed && rows.firstElementChild; i++) rows.removeChild(rows.firstElementChild);
        changes.replaced.forEach(function(index) {
            var old = rows.children[index];
            if (old) rows.replaceChild(rowElement(view.entries[index]), old);
        });
        if (changes.appended.length) rows.insertAdjacentHTML('beforeend', changes.appended.map(renderRow).join(''));
    }

    function renderRow(entry) {
        return rowHtml(entry, columns, openSeqs.has(entry.seq));
    }

    function rowElement(entry) {
        var body = document.createElement('tbody');
        body.innerHTML = renderRow(entry);
        return body.firstElementChild;
    }

    function renderCount() {
        els.count.textContent = countLabel(view.entries.length, view.missed);
        els.count.title = view.missed > 0
            ? view.missed + ' lines left the app\'s buffer before this page read them.'
            : '';
    }

    function renderEmpty() {
        els.empty.hidden = view.entries.length > 0;
        var filtered = filters.level !== 'V' || !!activeTag() || !!filters.q;
        els.empty.textContent = filtered ? '// no log lines match the filters' : '// no log lines yet';
    }

    function updateState(next) {
        if (!next) return;
        state = next;
        els.pause.textContent = state.paused ? '▶ Resume' : '⏸ Pause';
        els.pause.classList.toggle('dc-btn--active', !!state.paused);
        els.pause.setAttribute('aria-pressed', state.paused ? 'true' : 'false');
        els.paused.hidden = !state.paused;
        els.compact.textContent = (state.compact ? '☑' : '☐') + ' Compact';
        els.compact.classList.toggle('dc-btn--active', !!state.compact);
        els.compact.setAttribute('aria-pressed', state.compact ? 'true' : 'false');
        var banner = readerBanner(state);
        els.banner.hidden = !banner;
        if (banner) {
            els.banner.className = 'logs-banner logs-banner--' + banner.kind;
            els.banner.textContent = banner.text;
        }
    }

    function isAtEnd() {
        var el = els.scroll;
        return el.scrollHeight - el.scrollTop - el.clientHeight < STICK_TO_END_PX;
    }

    // While the row toolbar shows, the rows stay where they are, so that it
    // keeps pointing at its row; the list goes to the end when it closes.
    function followEnd() {
        if (els.tools.hidden) scrollToEnd();
        else followPending = true;
    }

    function scrollToEnd() {
        var el = els.scroll;
        if (el.scrollTop < el.scrollHeight - el.clientHeight - 1) {
            followScroll = true;
            el.scrollTop = el.scrollHeight;
        }
        stuckToEnd = true;
        els.jump.hidden = true;
    }

    // ── Filters ─────────────────────────────────────────────────

    function filtersChanged() {
        generation++;
        view.cursor = null;
        els.jump.hidden = true;
        hideTools();
        renderEmpty();
        startPolling();
    }

    function setLevel(level) {
        if (!LEVELS[level]) return;
        if (level === 'F' && filters.level !== 'F') levelBeforeFatal = filters.level;
        closeMenus();
        if (level === filters.level) return;
        filters.level = level;
        renderLevel();
        filtersChanged();
    }

    function renderLevel() {
        els.levelDot.style.setProperty('--c', 'var(--lvl-' + filters.level.toLowerCase() + ')');
        els.levelLabel.textContent = LEVELS[filters.level];
        els.levelMenu.querySelectorAll('[data-level]').forEach(function(item) {
            var on = item.dataset.level === filters.level;
            item.classList.toggle('dc-menu__item--active', on);
            item.setAttribute('aria-selected', on ? 'true' : 'false');
            item.querySelector('.dc-menu__check').textContent = on ? '✓' : '';
        });
        var fatal = filters.level === 'F';
        els.fatal.classList.toggle('dc-chip--active', fatal);
        els.fatal.setAttribute('aria-pressed', fatal ? 'true' : 'false');
    }

    function onFilterInput(input) {
        showClear(input);
        if (filterTimer) clearTimeout(filterTimer);
        filterTimer = setTimeout(applyInputs, FILTER_DEBOUNCE_MS);
    }

    function setInput(input, value) {
        input.value = value;
        showClear(input);
        applyInputs();
    }

    // Both fields at once: a pending change in one is not lost to an edit in the other.
    function applyInputs() {
        if (filterTimer) { clearTimeout(filterTimer); filterTimer = null; }
        var tag = els.tag.value;
        var q = els.q.value;
        if (filters.tag === tag && filters.q === q) return;
        if (filters.tag !== tag) clearTagError();
        filters.tag = tag;
        filters.q = q;
        filtersChanged();
    }

    function showClear(input) {
        var clear = els.root.querySelector('[data-action="clearField"][data-target="' + input.id + '"]');
        if (clear) clear.hidden = !input.value;
    }

    function rejectTag(tag, message) {
        rejectedTag = tag;
        els.tagField.classList.add('logs-field--invalid');
        var reason = message || 'The app did not accept the tag regex';
        els.filterError.textContent = reason + (/[.!?]$/.test(reason) ? ' ' : '. ')
            + 'The list shows every tag until you change it.';
        els.filterError.hidden = false;
        filtersChanged();
    }

    function clearTagError() {
        rejectedTag = null;
        els.tagField.classList.remove('logs-field--invalid');
        els.filterError.hidden = true;
    }

    // ── Menus ───────────────────────────────────────────────────

    function openMenu(menu, button) {
        var open = !menu.hidden;
        closeMenus();
        if (open) return;
        menu.hidden = false;
        els.menuScrim.hidden = false;
        button.setAttribute('aria-expanded', 'true');
    }

    function closeMenus() {
        els.levelMenu.hidden = true;
        els.columnsMenu.hidden = true;
        els.menuScrim.hidden = true;
        els.level.setAttribute('aria-expanded', 'false');
        els.columnsButton.setAttribute('aria-expanded', 'false');
    }

    // ── Columns ─────────────────────────────────────────────────

    function applyColumns() {
        COLUMNS.forEach(function(column) {
            var hidden = !columns[column];
            els.table.querySelectorAll('[data-col="' + column + '"]').forEach(function(el) { el.hidden = hidden; });
        });
        els.columnsMenu.querySelectorAll('[data-column]').forEach(function(item) {
            var on = !!columns[item.dataset.column];
            var check = item.querySelector('.dc-check');
            check.classList.toggle('dc-check--on', on);
            check.textContent = on ? '✓' : '';
            item.setAttribute('aria-checked', on ? 'true' : 'false');
        });
        var changed = COLUMNS.some(function(column) { return columns[column] !== DEFAULT_COLUMNS[column]; });
        els.columnsButton.classList.toggle('dc-btn--active', changed);
        applyTableWidth();
    }

    function toggleColumn(column) {
        if (!column || column === 'msg') return;
        columns = Object.assign({}, columns);
        columns[column] = !columns[column];
        store(COLUMNS_KEY, columns);
        applyColumns();
    }

    function columnWidth(column) {
        return widths[column] || DEFAULT_WIDTHS[column];
    }

    function applyWidths() {
        Object.keys(DEFAULT_WIDTHS).forEach(function(column) {
            els.table.querySelector('col[data-col="' + column + '"]').style.width = columnWidth(column) + 'px';
        });
        applyTableWidth();
    }

    // The message takes what the other columns leave; below its minimum, the table scrolls sideways.
    function applyTableWidth() {
        var fixed = 0;
        Object.keys(DEFAULT_WIDTHS).forEach(function(column) {
            if (columns[column]) fixed += columnWidth(column);
        });
        els.table.style.minWidth = (fixed + MIN_MESSAGE_WIDTH) + 'px';
    }

    function startResize(handle, ev) {
        var column = handle.dataset.colResize;
        var th = handle.parentNode;
        var startX = ev.clientX;
        var startWidth = th.getBoundingClientRect().width;
        ev.preventDefault();
        hideTools();
        handle.classList.add('logs-resize--dragging');
        document.body.style.cursor = 'col-resize';
        function onMove(e) {
            widths[column] = Math.max(MIN_COLUMN_WIDTH, Math.round(startWidth + e.clientX - startX));
            applyWidths();
        }
        function onUp() {
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('mouseup', onUp);
            handle.classList.remove('logs-resize--dragging');
            document.body.style.cursor = '';
            store(WIDTHS_KEY, widths);
        }
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
    }

    function resetWidth(handle) {
        delete widths[handle.dataset.colResize];
        store(WIDTHS_KEY, widths);
        applyWidths();
    }

    // ── The row toolbar: on a row after 1 s of hover ────────────

    var toolsSeq = null;
    // The row is known by its seq: a repeat of its line replaces its element.
    var hoveredSeq = null;
    var showTimer = null;
    var hideTimer = null;
    var pointerX = 0;

    function entryOfRow(row) {
        return row ? view.bySeq.get(Number(row.dataset.seq)) : null;
    }

    function showTools(row) {
        var entry = entryOfRow(row);
        if (!entry) return;
        toolsSeq = entry.seq;
        els.toolJson.hidden = extractJson(entry.message) === null;
        var tools = els.tools;
        var rowRect = row.getBoundingClientRect();
        tools.hidden = false;
        tools.style.left = pointerX + 'px';
        tools.style.top = (rowRect.bottom + 4) + 'px';
        var rect = tools.getBoundingClientRect();
        if (rect.right > window.innerWidth - 4) tools.style.left = Math.max(4, window.innerWidth - rect.width - 4) + 'px';
        if (rect.bottom > window.innerHeight - 4) tools.style.top = (rowRect.top - rect.height - 4) + 'px';
    }

    function hideTools() {
        if (showTimer) { clearTimeout(showTimer); showTimer = null; }
        if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
        if (els.tools) els.tools.hidden = true;
        hoveredSeq = null;
        toolsSeq = null;
        if (followPending) {
            followPending = false;
            scrollToEnd();
        }
    }

    function scheduleHide() {
        if (hideTimer) clearTimeout(hideTimer);
        hideTimer = setTimeout(hideTools, TOOLS_HIDE_DELAY_MS);
    }

    function rowOfSeq(seq) {
        return els.rows.querySelector('tr[data-seq="' + seq + '"]');
    }

    function onRowOver(e) {
        var row = e.target.closest('tr');
        // While the toolbar shows, the pointer crosses other rows on its way to it.
        if (!row || !els.tools.hidden || row.dataset.seq === hoveredSeq) return;
        if (showTimer) clearTimeout(showTimer);
        var seq = row.dataset.seq;
        hoveredSeq = seq;
        showTimer = setTimeout(function() {
            showTimer = null;
            var current = hoveredSeq === seq ? rowOfSeq(seq) : null;
            if (current) showTools(current);
        }, TOOLS_DELAY_MS);
    }

    function onRowOut(e) {
        var row = e.target.closest('tr');
        if (!row || row.dataset.seq !== hoveredSeq) return;
        var to = e.relatedTarget;
        if (to && (row.contains(to) || els.tools.contains(to))) return;
        if (!els.tools.hidden) {
            scheduleHide();
        } else {
            if (showTimer) { clearTimeout(showTimer); showTimer = null; }
            hoveredSeq = null;
        }
    }

    function toolsEntry() {
        return toolsSeq == null ? null : view.bySeq.get(toolsSeq);
    }

    function formatJson(entry) {
        var json = extractJson(entry.message);
        if (json === null) {
            window.debugToast('No JSON in this message', 'error');
            return;
        }
        jsonText = window.debugFormatJson(json, 2);
        els.jsonPre.innerHTML = window.debugSyntaxHighlightJson(json, { indent: 2 });
        jsonModal.show();
    }

    // ── Actions ─────────────────────────────────────────────────

    function postFlag(path, key, value) {
        var body = {};
        body[key] = value;
        return window.debugFetch(window.lustroApiUrl(path), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }).then(function(r) { return r.json(); }).then(function(data) {
            var next = Object.assign({}, state);
            next[key] = data && typeof data[key] === 'boolean' ? data[key] : value;
            updateState(next);
        }).catch(function() {
            window.debugToast('The app did not answer. Try again.', 'error');
        });
    }

    function clearLog() {
        window.debugFetch(window.lustroApiUrl('clear'), { method: 'POST' })
            .then(function(r) { return r.json(); })
            .then(function(data) {
                generation++;
                view = newView();
                // The cursor of the empty log: the next poll gets only the later lines.
                view.cursor = data && data.cursor ? data.cursor : null;
                openSeqs.clear();
                hideTools();
                els.rows.innerHTML = '';
                els.jump.hidden = true;
                renderCount();
                renderEmpty();
                startPolling();
            })
            .catch(function() {
                window.debugToast('The app did not clear the log. Try again.', 'error');
            });
    }

    function toggleMessage(span) {
        // A drag that selects text in a long message doesn't fold it.
        var selection = window.getSelection && window.getSelection();
        if (selection && String(selection).length > 0) return;
        var row = span.closest('tr');
        var entry = entryOfRow(row);
        if (!entry) return;
        if (openSeqs.has(entry.seq)) openSeqs.delete(entry.seq);
        else openSeqs.add(entry.seq);
        row.parentNode.replaceChild(rowElement(entry), row);
    }

    var CLICK_ACTIONS = {
        toggleLevelMenu: function() { openMenu(els.levelMenu, els.level); },
        toggleColumnsMenu: function() { openMenu(els.columnsMenu, els.columnsButton); },
        closeMenus: closeMenus,
        setLevel: function(el) { setLevel(el.dataset.level); },
        toggleFatal: function() { setLevel(filters.level === 'F' ? levelBeforeFatal : 'F'); },
        toggleColumn: function(el) { toggleColumn(el.dataset.column); },
        clearField: function(el) {
            var input = document.getElementById(el.dataset.target);
            if (!input) return;
            setInput(input, '');
            input.focus();
        },
        togglePause: function() { postFlag('pause', 'paused', !state.paused); },
        toggleCompact: function() { postFlag('compact', 'compact', !state.compact); },
        clearLog: clearLog,
        copyRows: function() {
            if (!view.entries.length) {
                window.debugToast('No lines to copy', 'info');
                return;
            }
            window.debugCopyToClipboard(rowsText(view.entries, columns));
        },
        jumpToLatest: scrollToEnd,
        toggleMessage: toggleMessage,
        formatJson: function() {
            var entry = toolsEntry();
            hideTools();
            if (entry) formatJson(entry);
        },
        copyMessage: function() {
            var entry = toolsEntry();
            hideTools();
            if (entry) window.debugCopyToClipboard(entry.message);
        },
        filterTag: function() {
            var entry = toolsEntry();
            hideTools();
            if (entry) setInput(els.tag, exactTagRegex(entry.tag));
        },
        copyJson: function() { window.debugCopyToClipboard(jsonText); },
        closeJson: function() { jsonModal.hide(); }
    };

    function setupEvents() {
        var root = els.root;
        root.addEventListener('click', function(ev) {
            var el = ev.target.closest('[data-action]');
            if (!el || !root.contains(el)) return;
            var fn = CLICK_ACTIONS[el.dataset.action];
            if (fn) fn(el, ev);
        });
        root.addEventListener('input', function(ev) {
            if (ev.target.dataset && ev.target.dataset.action === 'filterInput') onFilterInput(ev.target);
        });
        els.table.querySelector('thead').addEventListener('mousedown', function(ev) {
            var handle = ev.target.closest('.logs-resize');
            if (handle && ev.button === 0) startResize(handle, ev);
        });
        els.table.querySelector('thead').addEventListener('dblclick', function(ev) {
            var handle = ev.target.closest('.logs-resize');
            if (handle) resetWidth(handle);
        });
        els.rows.addEventListener('mouseover', onRowOver);
        els.rows.addEventListener('mouseout', onRowOut);
        els.rows.addEventListener('mousemove', function(ev) { pointerX = ev.clientX; });
        els.tools.addEventListener('mouseenter', function() {
            if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
        });
        els.tools.addEventListener('mouseleave', scheduleHide);
        els.scroll.addEventListener('scroll', function() {
            if (followScroll) {
                followScroll = false;
            } else {
                // The user scrolled: the list follows the tail again only at its end.
                followPending = false;
                hideTools();
            }
            stuckToEnd = isAtEnd();
            els.jump.hidden = stuckToEnd;
        });
        window.addEventListener('resize', hideTools);
        if (window.ResizeObserver) {
            new window.ResizeObserver(function() {
                if (stuckToEnd) followEnd();
            }).observe(els.scroll);
        }
        document.addEventListener('keydown', function(ev) {
            if (ev.key !== 'Escape' || ev.defaultPrevented) return;
            if (!els.menuScrim.hidden) {
                ev.preventDefault();
                closeMenus();
            } else if (!els.tools.hidden) {
                hideTools();
            }
        });
    }

    function init() {
        var root = document.getElementById('logs-root');
        if (!root) return;
        els = {
            root: root,
            level: document.getElementById('logs-level'),
            levelDot: document.getElementById('logs-level-dot'),
            levelLabel: document.getElementById('logs-level-label'),
            levelMenu: document.getElementById('logs-level-menu'),
            tag: document.getElementById('logs-tag'),
            q: document.getElementById('logs-q'),
            tagField: document.getElementById('logs-tag-field'),
            pause: document.getElementById('logs-pause'),
            compact: document.getElementById('logs-compact'),
            columnsButton: document.getElementById('logs-columns'),
            columnsMenu: document.getElementById('logs-columns-menu'),
            menuScrim: document.getElementById('logs-menu-scrim'),
            fatal: document.getElementById('logs-fatal'),
            paused: document.getElementById('logs-paused'),
            count: document.getElementById('logs-count'),
            filterError: document.getElementById('logs-filter-error'),
            banner: document.getElementById('logs-banner'),
            scroll: document.getElementById('logs-scroll'),
            table: document.getElementById('logs-table'),
            rows: document.getElementById('logs-rows'),
            empty: document.getElementById('logs-empty'),
            jump: document.getElementById('logs-jump'),
            tools: document.getElementById('logs-tools'),
            toolJson: document.getElementById('logs-tool-json'),
            jsonPre: document.getElementById('logs-json-pre')
        };
        var local = storage();
        if (local) {
            columns = readColumns(local);
            widths = readWidths(local);
        }
        jsonModal = window.debugModal('logs-json-modal');
        setupEvents();
        renderLevel();
        applyWidths();
        applyColumns();
        renderCount();
        renderEmpty();
        startPolling();
    }

    if (typeof window.lustroOnContentReady === 'function') {
        window.lustroOnContentReady(init);
    } else {
        init();
    }
})();
