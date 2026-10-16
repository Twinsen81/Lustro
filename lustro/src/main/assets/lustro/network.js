(function() {
    function netUrl(path) {
        return window.lustroApiUrl(path);
    }

    // Category filters are discovered from classifier-supplied strings at runtime.
    var KNOWN_CATEGORY_ORDER = ['Sync', 'Auth', 'Media', 'Config', 'AI', 'Wiretap', 'Other'];
    var CATEGORY_HELP = {
        'Sync': 'Sync data endpoints.',
        'Auth': 'Authentication: sign-in, tokens, /auth endpoints.',
        'Media': 'Media transfers: photos, videos, media assets.',
        'Config': 'Account and config endpoints.',
        'AI': 'AI endpoints.',
        'Wiretap': 'Non-OkHttp traffic captured below the app stack via the HttpURLConnection hook (3rd-party SDKs).',
        'Other': 'Uncategorized: requests that match none of the other categories.'
    };
    var STATUS_FILTERS = ['2xx', '3xx', '4xx', '5xx', 'Error', 'Mocked'];
    var METHOD_FILTERS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'Other'];
    var FILTER_STORAGE_KEY = 'debug-network-filters';

    var enabledCategories = {};   // category string -> boolean (discovered dynamically)
    var knownCategories = [];     // ordered list of categories seen so far
    var enabledStatuses = {};
    var enabledMethods = {};
    STATUS_FILTERS.forEach(function(s) { enabledStatuses[s] = true; });
    METHOD_FILTERS.forEach(function(m) { enabledMethods[m] = true; });
    loadFilters();

    var allTransactions = [];
    var selectedTxId = null;
    var selectMode = false;
    var selectedIds = {};         // transaction id -> true; only ids the filters show
    var lastCursor = null;        // opaque cursor token; omitted on the first poll
    var searchText = '';          // the search the list shows: the last one the app accepted
    var searchTextRegex = false;  // whether searchText is a regex
    var searchTimer = null;
    var searchRetryTimer = null;
    var searchGen = 0;
    var searchRequest = 0;
    var isPaused = false;
    var isOverwriteMode = false;
    var throttleDelayMs = 0;
    var captureFilter = null;     // the app's capture filter from the poll state; null when it set none
    var captureFilterModal = null;
    var DISPLAY_LIMIT_INITIAL = 200;
    var DISPLAY_LIMIT_INCREMENT = 200;
    var displayLimit = DISPLAY_LIMIT_INITIAL;
    var DETAIL_REFRESH_MIN_INTERVAL_MS = 750;
    var lastDetailRefreshAt = 0;
    var detailRefreshTimer = null;
    var detailRequestSeq = 0;
    var listSearchGen = null;     // the searchGen of the list in allTransactions; null before the first list
    // When each row that replaced earlier copies of its request (overwrite mode)
    // came into the list, by id, while its highlight lasts. As long as the
    // net-replaced-flash animation in network.css.
    var replacedAt = {};
    var REPLACED_FLASH_MS = 1600;
    // The request the selection moved to from the one the detail shows, when
    // overwrite mode replaced it. Its detail continues the one on screen.
    var detailContinuesTo = null;

    function loadFilters() {
        try {
            var raw = localStorage.getItem(FILTER_STORAGE_KEY);
            if (!raw) return;
            var saved = JSON.parse(raw);
            // Category enabled-state is keyed by classifier string. Restore any
            // saved booleans; categories appear in knownCategories as traffic
            // arrives (see ensureCategoryKnown).
            if (saved.categories) {
                Object.keys(saved.categories).forEach(function(c) {
                    if (typeof saved.categories[c] === 'boolean') {
                        enabledCategories[c] = saved.categories[c];
                        ensureCategoryKnown(c);
                    }
                });
            }
            if (saved.statuses) {
                STATUS_FILTERS.forEach(function(s) {
                    if (typeof saved.statuses[s] === 'boolean') enabledStatuses[s] = saved.statuses[s];
                });
            }
            if (saved.methods) {
                METHOD_FILTERS.forEach(function(m) {
                    if (typeof saved.methods[m] === 'boolean') enabledMethods[m] = saved.methods[m];
                });
            }
        } catch(e) {}
    }
    function saveFilters() {
        try {
            localStorage.setItem(FILTER_STORAGE_KEY, JSON.stringify({
                categories: enabledCategories,
                statuses: enabledStatuses,
                methods: enabledMethods,
            }));
        } catch(e) {}
    }

    // Track a category the first time it is seen. New categories default to
    // enabled (so traffic is never silently hidden by an unknown classifier
    // label). Ordering puts the classic labels first, then discovery order.
    function ensureCategoryKnown(cat) {
        if (cat == null || cat === '') return false;
        if (knownCategories.indexOf(cat) >= 0) return false;
        knownCategories.push(cat);
        knownCategories.sort(function(a, b) {
            var ia = KNOWN_CATEGORY_ORDER.indexOf(a);
            var ib = KNOWN_CATEGORY_ORDER.indexOf(b);
            if (ia < 0) ia = KNOWN_CATEGORY_ORDER.length + knownCategories.indexOf(a);
            if (ib < 0) ib = KNOWN_CATEGORY_ORDER.length + knownCategories.indexOf(b);
            return ia - ib;
        });
        if (typeof enabledCategories[cat] !== 'boolean') enabledCategories[cat] = true;
        return true;
    }

    function discoverCategories(list) {
        var changed = false;
        (list || []).forEach(function(tx) {
            ((tx && tx.categories) || []).forEach(function(c) {
                if (ensureCategoryKnown(c)) changed = true;
            });
        });
        return changed;
    }

    var copyStore = {};
    var copyIdSeq = 0;

    window.copyToClip = function(id, ev) {
        var text = copyStore[id];
        if (text == null) return;
        copyNetworkText(text, ev);
    };

    function copyNetworkText(text, ev) {
        window.debugWriteToClipboard(text).then(function() {
            showCopyPopup(ev);
        }).catch(function() {
            window.debugToast('Failed to copy', 'error');
        });
    }

    // Copies text that is still being fetched. Safari allows a copy only in the
    // click itself, so the clipboard item is made there and the text follows.
    function copyNetworkTextLater(textPromise, ev) {
        var write = null;
        try {
            if (window.isSecureContext && navigator.clipboard && navigator.clipboard.write && typeof ClipboardItem === 'function') {
                write = navigator.clipboard.write([new ClipboardItem({
                    'text/plain': textPromise.then(function(text) { return new Blob([text], { type: 'text/plain' }); }),
                })]);
            }
        } catch(e) {
            write = null;
        }
        var copied = write
            ? write.catch(function() { return textPromise.then(window.debugWriteToClipboard); })
            : textPromise.then(window.debugWriteToClipboard);
        copied.then(function() {
            showCopyPopup(ev);
        }).catch(function(e) {
            debugToast('Failed to copy' + (e && e.message ? ': ' + e.message : ''), 'error');
        });
    }

    function showCopyPopup(ev) {
        var popup = document.createElement('div');
        popup.className = 'net-copy-popup';
        popup.textContent = 'Copied!';
        // Clamp toward the viewport so the (nowrap, x-centered) pill never
        // hangs off the edge when a copy button sits near it.
        var x = ev ? ev.clientX : window.innerWidth / 2;
        x = Math.max(70, Math.min(x, window.innerWidth - 70));
        popup.style.left = x + 'px';
        popup.style.top = (ev ? ev.clientY : window.innerHeight / 2) + 'px';
        document.body.appendChild(popup);
        setTimeout(function() { popup.remove(); }, 1500);
    }

    var COPY_ICON = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5">'
        + '<rect x="5" y="5" width="9" height="9" rx="1.5"/><path d="M11 5V3.5A1.5 1.5 0 009.5 2h-6A1.5 1.5 0 002 3.5v6A1.5 1.5 0 003.5 11H5"/></svg>';

    // Puts new content in a part of the detail pane, and forgets the text that
    // the copy buttons of the old content held.
    function replacePart(el, html) {
        el.querySelectorAll('[data-copy-id]').forEach(function(b) { delete copyStore[b.dataset.copyId]; });
        el.innerHTML = html;
    }

    function copyBtn(text, cls) {
        var id = '_cp' + (copyIdSeq++);
        copyStore[id] = text;
        // CSP-safe: the click is handled by the delegated listener, which calls
        // event.stopPropagation() for data-action="copyToClip" so a copy button
        // inside a clickable row doesn't also select the row.
        return '<span class="net-copy-btn' + (cls ? ' ' + cls : '') + '" data-action="copyToClip" data-copy-id="' + debugEscapeHtml(id) + '" title="Copy">' + COPY_ICON + '</span>';
    }

    // ✓ = every chip on, – = mixed, empty = every chip off. Clicking applies
    // the row's toggle-all action: any off → enable all; all on → disable all.
    function masterStateClass(keys, enabledMap) {
        var on = 0;
        keys.forEach(function(k) { if (enabledMap[k]) on++; });
        if (keys.length > 0 && on === keys.length) return 'on';
        if (on === 0) return 'off';
        return 'mixed';
    }
    function masterToggleHtml(action, label, keys, enabledMap) {
        return '<button class="net-master-toggle ' + masterStateClass(keys, enabledMap) + '"'
            + ' data-action="' + action + '"'
            + ' title="Toggle every ' + label + ' at once: if any are off, enable all; if all are on, disable all."></button>';
    }
    function updateMasterToggle(action, keys, enabledMap) {
        var btn = document.querySelector('.net-master-toggle[data-action="' + action + '"]');
        if (btn) btn.className = 'net-master-toggle ' + masterStateClass(keys, enabledMap);
    }

    function buildCategoryFilters() {
        var container = document.getElementById('category-filters');
        if (!container) return;
        var pills = knownCategories.map(function(cat) {
            var cls = 'net-cat-pill' + (enabledCategories[cat] ? '' : ' off');
            return '<span class="' + cls + '" data-action="toggleCategory" data-cat="' + debugEscapeHtml(cat) + '"'
                + ' title="' + debugEscapeHtml(CATEGORY_HELP[cat] || cat) + ' Toggle off to hide these rows (saved in your browser).">'
                + debugEscapeHtml(cat) + '</span>';
        }).join('');
        // Only show the master toggle once at least one category is known.
        // Otherwise the empty category bar renders a lone unchecked checkbox that
        // implies "everything off" and does nothing when clicked.
        container.innerHTML = pills
            + (knownCategories.length
                ? masterToggleHtml('toggleAllCategories', 'category', knownCategories, enabledCategories)
                : '');
    }

    function updateCategoryPills() {
        var pills = document.querySelectorAll('.net-cat-pill');
        pills.forEach(function(pill) {
            var cat = pill.dataset.cat;
            pill.className = 'net-cat-pill' + (enabledCategories[cat] ? '' : ' off');
        });
        updateMasterToggle('toggleAllCategories', knownCategories, enabledCategories);
    }

    window.toggleCategory = function(cat) {
        enabledCategories[cat] = !enabledCategories[cat];
        updateCategoryPills();
        saveFilters();
        resetDisplayLimit();
        renderList();
    };

    window.toggleAllCategories = function() {
        var anyOff = knownCategories.some(function(c) { return !enabledCategories[c]; });
        knownCategories.forEach(function(c) { enabledCategories[c] = anyOff; });
        updateCategoryPills();
        saveFilters();
        resetDisplayLimit();
        renderList();
    };

    function buildStatusFilters() {
        var container = document.getElementById('status-filters');
        if (!container) return;
        container.innerHTML = STATUS_FILTERS.map(function(s) {
            var cls = 'net-filter-pill status-' + s.toLowerCase() + (enabledStatuses[s] ? '' : ' off');
            return '<span class="' + cls + '" data-action="toggleStatusFilter" data-status="' + s + '"'
                + ' title="Toggle ' + s + ' responses. Off = hide. Saved in browser localStorage.">'
                + s + '</span>';
        }).join('')
            + masterToggleHtml('toggleAllStatuses', 'status filter', STATUS_FILTERS, enabledStatuses);
    }
    function buildMethodFilters() {
        var container = document.getElementById('method-filters');
        if (!container) return;
        container.innerHTML = METHOD_FILTERS.map(function(m) {
            var cls = 'net-filter-pill method-' + m.toLowerCase() + (enabledMethods[m] ? '' : ' off');
            return '<span class="' + cls + '" data-action="toggleMethodFilter" data-method="' + m + '"'
                + ' title="Toggle ' + m + ' requests. Off = hide. Saved in browser localStorage.">'
                + m + '</span>';
        }).join('')
            + masterToggleHtml('toggleAllMethods', 'method filter', METHOD_FILTERS, enabledMethods);
    }
    function refreshStatusPills() {
        document.querySelectorAll('#status-filters .net-filter-pill').forEach(function(p) {
            var s = p.dataset.status;
            p.classList.toggle('off', !enabledStatuses[s]);
        });
        updateMasterToggle('toggleAllStatuses', STATUS_FILTERS, enabledStatuses);
    }
    function refreshMethodPills() {
        document.querySelectorAll('#method-filters .net-filter-pill').forEach(function(p) {
            var m = p.dataset.method;
            p.classList.toggle('off', !enabledMethods[m]);
        });
        updateMasterToggle('toggleAllMethods', METHOD_FILTERS, enabledMethods);
    }
    window.toggleStatusFilter = function(s) {
        enabledStatuses[s] = !enabledStatuses[s];
        refreshStatusPills();
        saveFilters();
        resetDisplayLimit();
        renderList();
    };
    window.toggleMethodFilter = function(m) {
        enabledMethods[m] = !enabledMethods[m];
        refreshMethodPills();
        saveFilters();
        resetDisplayLimit();
        renderList();
    };
    window.toggleAllStatuses = function() {
        var anyOff = STATUS_FILTERS.some(function(s) { return !enabledStatuses[s]; });
        STATUS_FILTERS.forEach(function(s) { enabledStatuses[s] = anyOff; });
        refreshStatusPills();
        saveFilters();
        resetDisplayLimit();
        renderList();
    };
    window.toggleAllMethods = function() {
        var anyOff = METHOD_FILTERS.some(function(m) { return !enabledMethods[m]; });
        METHOD_FILTERS.forEach(function(m) { enabledMethods[m] = anyOff; });
        refreshMethodPills();
        saveFilters();
        resetDisplayLimit();
        renderList();
    };

    function statusKeyForTx(tx) {
        if (tx.isMocked) return 'Mocked';
        if (tx.error) return 'Error';
        var s = tx.statusCode;
        if (s == null) return null;
        if (s >= 500) return '5xx';
        if (s >= 400) return '4xx';
        if (s >= 300) return '3xx';
        if (s >= 200) return '2xx';
        return null;
    }
    function methodKeyForTx(tx) {
        var m = (tx.method || '').toUpperCase();
        return METHOD_FILTERS.indexOf(m) >= 0 ? m : 'Other';
    }
    function matchesStatusFilter(tx) {
        var key = statusKeyForTx(tx);
        if (key == null) return true;
        return !!enabledStatuses[key];
    }
    function matchesMethodFilter(tx) {
        return !!enabledMethods[methodKeyForTx(tx)];
    }

    // The REGEX button of each search field: on, the text is a regular
    // expression; off, it is found as typed. Remembered in the browser.
    var SEARCH_REGEX_KEYS = { 'search-input': 'debug-network-search-regex', 'ws-search': 'debug-network-ws-search-regex' };
    var SEARCH_PLACEHOLDERS = {
        'search-input': ['filter url, method, headers, body…', 'featured|popular -analytics'],
        'ws-search': ['filter messages…', 'ping|pong -heartbeat'],
    };
    function searchRegexOn(inputId) {
        try { return localStorage.getItem(SEARCH_REGEX_KEYS[inputId]) === '1'; } catch(e) { return false; }
    }
    function showSearchRegex(inputId) {
        var on = searchRegexOn(inputId);
        var button = document.querySelector('[data-action="toggleSearchRegex"][data-target="' + inputId + '"]');
        if (button) {
            button.classList.toggle('dc-btn--active', on);
            button.setAttribute('aria-pressed', on ? 'true' : 'false');
        }
        var input = document.getElementById(inputId);
        if (input) input.placeholder = SEARCH_PLACEHOLDERS[inputId][on ? 1 : 0];
    }
    window.toggleSearchRegex = function(inputId) {
        try { localStorage.setItem(SEARCH_REGEX_KEYS[inputId], searchRegexOn(inputId) ? '0' : '1'); } catch(e) {}
        showSearchRegex(inputId);
        var input = document.getElementById(inputId);
        if (!input) return;
        // The text now means something else: search with it at once.
        if (inputId === 'ws-search') applyWsSearch(input.value);
        else applySearch(input.value);
        input.focus();
    };

    // What a viewer marks for a search: the text, or the regex of each word that
    // doesn't start with a minus, split as the app splits them. The app runs Java
    // regexes, and the u flag makes this browser reject the Java-only escapes,
    // such as \A or \Q, that it would otherwise read as letters. A regex it can't
    // compile marks nothing; the app decides whether a regex is valid.
    var lastSearchMark = { text: null, res: null };
    function searchMark(text, regex) {
        if (!text || !regex) return text;
        if (lastSearchMark.text !== text) {
            var res = [];
            // Java's \s, which the app splits on: no Unicode spaces.
            text.split(/[ \t\n\x0B\f\r]+/).forEach(function(word) {
                if (!word || (word.length > 1 && word[0] === '-')) return;
                try { res.push(new RegExp(word, 'giu')); } catch(e) {}
            });
            lastSearchMark = { text: text, res: res };
        }
        return lastSearchMark.res;
    }

    var searchHelpModal = null;
    window.showSearchHelp = function() {
        if (!searchHelpModal) searchHelpModal = window.debugModal('search-help-modal');
        searchHelpModal.show();
    };
    window.closeSearchHelp = function() {
        if (searchHelpModal) searchHelpModal.hide();
    };

    window.onSearchInput = function(val) {
        updateSearchClear('search-input');
        // A query that is still on its way, or waiting to try again, is out of date.
        searchRequest++;
        clearTimeout(searchRetryTimer);
        clearTimeout(searchTimer);
        searchTimer = setTimeout(function() { applySearch(val); }, 300);
    };

    // The search runs on the device, which says what is wrong with a query that
    // can't run. So a new query takes effect only once the app accepts it, and
    // until then, or after an error, the list stays as the last query left it.
    function applySearch(val) {
        clearTimeout(searchTimer);
        clearTimeout(searchRetryTimer);
        var request = ++searchRequest;
        var regex = searchRegexOn('search-input');
        if (val === searchText && (!val || regex === searchTextRegex)) {
            showSearchError('search-input', null);
            return;
        }
        fetch(netUrl('transactions') + searchParams(val, regex, '?'))
            .then(readSearchAnswer)
            .then(function(answer) {
                if (request !== searchRequest) return;
                showSearchError('search-input', answer.error);
                if (answer.error) return;
                searchText = val;
                searchTextRegex = regex;
                // The answer is a reset list with its own cursor; a poll that
                // left with the old query is ignored when it comes back.
                lastCursor = null;
                searchGen++;
                resetDisplayLimit();
                handlePollData(answer.data);
                if (currentDetailTx && trafficView === 'http') renderDetail(currentDetailTx);
            })
            .catch(function() {
                // The app didn't answer, as when it is in the background: try again.
                if (request === searchRequest) searchRetryTimer = setTimeout(function() { applySearch(val); }, 1500);
            });
    }

    function searchParams(text, regex, first) {
        if (!text) return '';
        return first + 'search=' + encodeURIComponent(text) + (regex ? '&regex=true' : '');
    }

    // A search's response as { data }, or as { error } when the app says that the
    // query can't run. Any other failure rejects, so the caller tries again.
    function readSearchAnswer(r) {
        return r.json().catch(function() { return null; }).then(function(data) {
            if (r.ok) return { data: data };
            if (data && data.field === 'search') return { error: data.message || 'The search can\'t run.' };
            throw new Error('HTTP ' + r.status);
        });
    }

    // The error goes under the field; the list keeps what the last valid query found.
    function showSearchError(inputId, message) {
        var el = document.getElementById(inputId + '-error');
        var input = document.getElementById(inputId);
        if (el) {
            el.textContent = message || '';
            el.hidden = !message;
        }
        var field = input && input.closest('.dc-field');
        if (field) field.classList.toggle('net-search--invalid', !!message);
    }

    function updateSearchClear(inputId) {
        var input = document.getElementById(inputId);
        var button = document.querySelector('[data-action="clearSearch"][data-target="' + inputId + '"]');
        if (input && button) button.hidden = !input.value;
    }

    window.clearSearch = function(inputId) {
        var input = document.getElementById(inputId);
        if (!input) return;
        input.value = '';
        updateSearchClear(inputId);
        if (inputId === 'ws-search') applyWsSearch('');
        else applySearch('');
        input.focus();
    };


    function filterTransactions() {
        return allTransactions.filter(function(tx) {
            var cats = (tx && tx.categories) || [];
            // Categories may be empty (classifier returned nothing) — such rows
            // have no category membership and are always shown. Otherwise the row
            // is shown if at least one of its categories is enabled.
            var catOk = cats.length === 0 || cats.some(function(c) { return enabledCategories[c]; });
            return catOk
                && matchesStatusFilter(tx)
                && matchesMethodFilter(tx);
        });
    }

    function renderList() {
        var filtered = filterTransactions();
        var tbody = document.getElementById('tx-list');
        var countEl = document.getElementById('tx-count');
        if (!tbody) return;
        // The selection follows the filters: what they hide is no longer selected,
        // so an export has exactly the selected rows on screen.
        var kept = {};
        filtered.forEach(function(tx) { if (selectedIds[tx.id]) kept[tx.id] = true; });
        selectedIds = kept;
        updateSelectionControls(filtered);
        var visible = filtered.slice(0, displayLimit);
        var label = filtered.length + (filtered.length !== allTransactions.length
            ? '/' + allTransactions.length : '') + ' requests';
        if (visible.length < filtered.length) {
            label += ' (showing ' + visible.length + ')';
        }
        if (countEl && trafficView === 'http') countEl.textContent = label;

        tbody.innerHTML = visible.map(function(tx) {
            var sc = statusClass(tx);
            var streaming = isStreaming(tx);
            var statusText = tx.error ? 'ERR' : (tx.statusCode ? tx.statusCode + (streaming ? '…' : '') : '…');
            var dur = (tx.durationMs != null ? tx.durationMs + 'ms' + (streaming ? '…' : '') : '…') + throttleBadge(tx);
            var pathOnly = extractPath(tx.url).split('?')[0];
            var shortUrl = pathOnly.length > 100 ? pathOnly.substring(0, 100) + '…' : pathOnly;
            var sel = tx.id === selectedTxId ? ' dc-row--selected' : '';
            var flashAge = replacedFlashAge(tx);
            // The list is drawn again on every change, so the highlight goes on from where it was.
            var rowAttrs = flashAge == null
                ? 'class="dc-row' + sel + '"'
                : 'class="dc-row' + sel + ' net-row--replaced" style="animation-delay:-' + flashAge + 'ms"';
            var urlText = tx.replacedCount > 0
                ? '<span class="net-url-line"><span class="net-url-text">' + debugEscapeHtml(shortUrl) + '</span>' + replacedBadge(tx) + '</span>'
                : debugEscapeHtml(shortUrl);
            var mockedBadge = tx.isMocked ? ' <span class="dc-badge" style="--c: var(--ai)">Mocked</span>' : armedMockTag(tx);
            var streamingBadge = streaming ? ' <span class="dc-badge">Streaming</span>' : '';
            var redirectBadge = tx.finalUrl
                ? ' <span class="dc-badge" title="' + debugEscapeHtml('The response came from ' + tx.finalUrl) + '">Redirected</span>'
                : '';
            var socketBadge = tx.webSocketId
                ? ' <span class="dc-badge net-ws-link" style="--c: var(--accent-2)" data-action="openWebSocket" data-ws-id="' + debugEscapeHtml(tx.webSocketId)
                    + '" title="The handshake of a WebSocket. Click to see its messages.">WS</span>'
                : '';
            var check = selectMode
                ? '<td class="dc-cell net-cell-select" data-action="toggleTxSelection" data-tx-id="' + debugEscapeHtml(tx.id) + '">'
                    + '<input type="checkbox" class="net-check" aria-label="Select ' + debugEscapeHtml((tx.method || '') + ' ' + pathOnly) + '"'
                    + (selectedIds[tx.id] ? ' checked' : '') + '></td>'
                : '';
            return '<tr ' + rowAttrs + ' data-action="selectTransaction" data-tx-id="' + debugEscapeHtml(tx.id) + '">'
                + check
                + '<td class="dc-cell net-cell-method ' + methodClass(tx) + '">' + debugEscapeHtml(tx.method || '') + '</td>'
                + '<td class="dc-cell net-cell-url" title="' + debugEscapeHtml(pathOnly) + '">' + urlText + '</td>'
                + '<td class="dc-cell net-cell-status ' + sc + '">' + statusText + mockedBadge + redirectBadge + streamingBadge + socketBadge + '</td>'
                + '<td class="dc-cell net-cell-time">' + dur + '</td>'
                + '<td class="dc-cell net-cell-cat">' + ((tx.categories || []).map(function(c) {
                    return '<span class="dc-tag" data-cat="' + debugEscapeHtml(c) + '">' + debugEscapeHtml(c) + '</span>';
                }).join(' ')) + '</td>'
                + '</tr>';
        }).join('');

        var loadMoreEl = document.getElementById('tx-load-more');
        if (visible.length < filtered.length) {
            if (!loadMoreEl) {
                loadMoreEl = document.createElement('button');
                loadMoreEl.id = 'tx-load-more';
                loadMoreEl.className = 'dc-btn';
                loadMoreEl.style.margin = '8px auto';
                loadMoreEl.style.display = 'block';
                loadMoreEl.onclick = function() {
                    displayLimit += DISPLAY_LIMIT_INCREMENT;
                    renderList();
                };
                loadMoreEl.title = 'Reveal the next ' + DISPLAY_LIMIT_INCREMENT + ' transactions. The list is capped at ' + DISPLAY_LIMIT_INITIAL + ' rows initially to keep rendering fast; this resets when you change a filter or search.';
                tbody.parentElement.parentElement.appendChild(loadMoreEl);
            }
            loadMoreEl.textContent = 'Load ' + Math.min(DISPLAY_LIMIT_INCREMENT, filtered.length - visible.length) + ' more';
        } else if (loadMoreEl) {
            loadMoreEl.remove();
        }
    }

    function resetDisplayLimit() {
        displayLimit = DISPLAY_LIMIT_INITIAL;
    }

    // How many earlier copies of the request overwrite mode removed for this one,
    // with the ones those had replaced.
    window.netReplacedBadge = replacedBadge;
    function replacedBadge(tx) {
        var count = tx.replacedCount || 0;
        if (count <= 0) return '';
        var title = 'Replaced ' + count + ' earlier ' + (tx.method || '') + ' ' + extractPath(tx.url || '').split('?')[0]
            + ' request' + (count === 1 ? '' : 's') + ' (overwrite mode)';
        return '<span class="dc-badge net-replaced-badge" title="' + debugEscapeHtml(title) + '" aria-label="' + debugEscapeHtml(title) + '">↻ ' + count + '</span>';
    }

    function replacedFlashAge(tx) {
        var at = replacedAt[tx.id];
        if (at == null) return null;
        var age = Date.now() - at;
        return age < REPLACED_FLASH_MS ? age : null;
    }

    function updateSelectionControls(filtered) {
        var count = Object.keys(selectedIds).length;
        var countEl = document.getElementById('select-count');
        if (countEl) countEl.textContent = count + ' of ' + filtered.length + ' selected';
        ['copy-selection-md-btn', 'export-har-btn'].forEach(function(id) {
            var btn = document.getElementById(id);
            if (btn) btn.disabled = count === 0;
        });
        var all = document.getElementById('select-all');
        if (all) {
            all.checked = count > 0 && count === filtered.length;
            all.indeterminate = count > 0 && count < filtered.length;
        }
    }

    window.toggleSelectMode = function() {
        selectMode = !selectMode;
        selectedIds = {};
        var btn = document.getElementById('select-btn');
        if (btn) btn.classList.toggle('dc-btn--active', selectMode);
        var bar = document.getElementById('select-bar');
        if (bar) bar.hidden = !selectMode;
        var col = document.getElementById('select-col');
        if (col) col.style.display = selectMode ? '' : 'none';
        renderList();
    };

    window.toggleTxSelection = function(id) {
        if (selectedIds[id]) delete selectedIds[id];
        else selectedIds[id] = true;
        renderList();
    };

    // Every row the filters show, past the ones rendered so far too; or none
    // when every one is selected already.
    window.toggleSelectAll = function() {
        var filtered = filterTransactions();
        var all = filtered.length > 0 && filtered.every(function(tx) { return selectedIds[tx.id]; });
        selectedIds = {};
        if (!all) filtered.forEach(function(tx) { selectedIds[tx.id] = true; });
        renderList();
    };

    // An export reads oldest first. The list is newest first by when each capture
    // was stored, which is not always when its request started, so sort by that.
    function selectedOldestFirst() {
        return filterTransactions().filter(function(tx) { return selectedIds[tx.id]; }).reverse()
            .sort(function(a, b) { return (a.startedAt || 0) - (b.startedAt || 0); });
    }

    // A request line and its headers must stay under 8 KB, and an id is 36 characters.
    var EXPORT_BATCH = 50;

    window.exportSelectionHar = function() {
        var ids = selectedOldestFirst().map(function(tx) { return tx.id; });
        if (!ids.length) return;
        var batches = [];
        for (var i = 0; i < ids.length; i += EXPORT_BATCH) batches.push(ids.slice(i, i + EXPORT_BATCH));
        var parts = [];
        var chain = Promise.resolve();
        batches.forEach(function(batch) {
            chain = chain.then(function() {
                var query = 'format=har&ids=' + batch.map(encodeURIComponent).join(',');
                return debugFetch(netUrl('transactions/_/export?' + query))
                    .then(function(r) { return r.json(); })
                    .then(function(part) { parts.push(part); });
            });
        });
        chain.then(function() {
            var har = window.netMergeHar(parts);
            saveText(JSON.stringify(har, null, 2) + '\n', 'lustro-' + fileTimestamp(new Date()) + '.har', 'application/json');
            var exported = har.log.entries.length;
            var gone = ids.length - exported;
            debugToast('Exported ' + exported + ' request' + (exported === 1 ? '' : 's')
                + (gone > 0 ? '; ' + gone + ' no longer captured' : ''), gone > 0 ? 'warning' : 'success');
        }).catch(function(e) {
            debugToast('Failed to export: ' + e.message, 'error');
        });
    };

    // The export batches as one HAR document, oldest first. Each batch is in order
    // already, and the times are ISO 8601 in UTC, so they sort as text; the sort
    // is stable, so a tie keeps the order the batches had.
    window.netMergeHar = function(parts) {
        var har = parts[0] || { log: { version: '1.2', creator: { name: 'Lustro', version: '' }, entries: [] } };
        for (var i = 1; i < parts.length; i++) har.log.entries = har.log.entries.concat(parts[i].log.entries);
        har.log.entries.sort(function(a, b) {
            return a.startedDateTime < b.startedDateTime ? -1 : (a.startedDateTime > b.startedDateTime ? 1 : 0);
        });
        return har;
    };

    function saveText(text, name, type) {
        var url = URL.createObjectURL(new Blob([text], { type: type }));
        var link = document.createElement('a');
        link.href = url;
        link.download = name;
        document.body.appendChild(link);
        link.click();
        link.remove();
        // Some browsers start the download only after the click returns.
        setTimeout(function() { URL.revokeObjectURL(url); }, 10000);
    }

    function fileTimestamp(date) {
        var pad = function(n) { return (n < 10 ? '0' : '') + n; };
        return date.getFullYear() + pad(date.getMonth() + 1) + pad(date.getDate())
            + '-' + pad(date.getHours()) + pad(date.getMinutes()) + pad(date.getSeconds());
    }

    window.copySelectionMarkdown = function(ev) {
        var txs = selectedOldestFirst();
        if (!txs.length) return;
        copyNetworkTextLater(fetchDetails(txs).then(function(details) {
            if (!details.length) throw new Error('the requests are no longer captured');
            return window.netTransactionsMarkdown(details);
        }), ev);
    };

    // The details of txs, in their order, four requests at a time. One the app no
    // longer has is left out.
    function fetchDetails(txs) {
        var details = [];
        var next = 0;
        function worker() {
            if (next >= txs.length) return Promise.resolve();
            var index = next++;
            return debugFetch(netUrl('transactions/' + encodeURIComponent(txs[index].id)))
                .then(function(r) { return r.json(); })
                .then(function(tx) { details[index] = tx; }, function(e) { if (!e || e.status !== 404) throw e; })
                .then(worker);
        }
        var workers = [];
        for (var i = 0; i < Math.min(4, txs.length); i++) workers.push(worker());
        return Promise.all(workers).then(function() { return details.filter(Boolean); });
    }

    function methodClass(tx) {
        return 'net-m-' + methodKeyForTx(tx).toLowerCase();
    }

    function statusClass(tx) {
        if (tx.isMocked) return 'net-status-mocked';
        if (tx.error) return 'net-status-err';
        if (isStreaming(tx)) return 'net-status-pending';
        if (!tx.statusCode) return 'net-status-pending';
        if (tx.statusCode >= 500) return 'net-status-5xx';
        if (tx.statusCode >= 400) return 'net-status-4xx';
        if (tx.statusCode >= 300) return 'net-status-3xx';
        return 'net-status-2xx';
    }

    function isStreaming(tx) {
        return !!(tx && tx.statusCode && tx.responseComplete === false && !tx.error);
    }

    function findTransactionById(list, id) {
        for (var i = 0; i < list.length; i++) {
            if (list[i].id === id) return list[i];
        }
        return null;
    }

    function selectedBriefChanged(previous, next) {
        if (!next) return false;
        if (!previous) return true;
        return previous.statusCode !== next.statusCode
            || previous.durationMs !== next.durationMs
            || previous.throttledMs !== next.throttledMs
            || previous.responseBodyBytes !== next.responseBodyBytes
            || previous.responseComplete !== next.responseComplete
            || previous.error !== next.error
            || previous.isMocked !== next.isMocked
            || previous.mockRuleId !== next.mockRuleId
            || previous.activeMockRuleId !== next.activeMockRuleId;
    }

    window.selectTransaction = function(id) {
        selectedTxId = id;
        detailContinuesTo = null;
        renderList();
        showRightPane('detail');
        loadTransactionDetail(id);
    };

    function loadTransactionDetail(id) {
        var requestSeq = ++detailRequestSeq;
        lastDetailRefreshAt = Date.now();
        debugFetch(netUrl('transactions/' + encodeURIComponent(id)))
            .then(function(r) { return r.json(); })
            .then(function(tx) {
                if (selectedTxId === id && requestSeq === detailRequestSeq) renderDetail(tx);
            })
            .catch(function() {
                if (selectedTxId === id && requestSeq === detailRequestSeq) {
                    document.getElementById('detail-content').innerHTML =
                        '<div class="net-empty-state"><p>Failed to load detail</p></div>';
                }
            });
    }

    function scheduleSelectedDetailRefresh() {
        // The WebSockets view has the detail pane; the request's detail is
        // loaded again when the HTTP view comes back.
        if (!selectedTxId || trafficView !== 'http') return;
        var now = Date.now();
        var waitMs = DETAIL_REFRESH_MIN_INTERVAL_MS - (now - lastDetailRefreshAt);
        if (waitMs <= 0) {
            loadTransactionDetail(selectedTxId);
            return;
        }
        if (detailRefreshTimer) return;
        detailRefreshTimer = setTimeout(function() {
            detailRefreshTimer = null;
            if (selectedTxId) loadTransactionDetail(selectedTxId);
        }, waitMs);
    }

    var currentDetailTx = null;

    window.copyAllDetail = function(ev) {
        if (!currentDetailTx) return;
        var tx = currentDetailTx;
        var streaming = isStreaming(tx);
        var lines = [];
        lines.push((tx.method || '') + ' ' + (tx.url || ''));
        lines.push('Status: ' + (tx.error ? 'Error' : (tx.statusCode || 'Pending'))
            + (streaming ? ' (streaming)' : '')
            + (tx.durationMs != null ? '  |  ' + tx.durationMs + 'ms' + (streaming ? ' streaming' : '') : '')
            + (tx.throttledMs ? '  |  held ' + tx.throttledMs + 'ms by the throttle' : '')
            + '  |  ' + (tx.timestamp || ''));
        if (tx.categories && tx.categories.length) lines.push('Categories: ' + tx.categories.join(', '));
        var source = captureSource(tx);
        if (source) lines.push(source.note.charAt(0).toUpperCase() + source.note.slice(1));
        redirectSteps(tx).forEach(function(step) { lines.push(step.label + ' ' + step.url); });

        lines.push('');
        lines.push('══════════════════ REQUEST ══════════════════');
        lines.push('');
        if (tx.requestHeaders && Object.keys(tx.requestHeaders).length > 0) {
            lines.push('── Headers ──');
            Object.keys(tx.requestHeaders).forEach(function(k) {
                lines.push(k + ': ' + tx.requestHeaders[k]);
            });
        }
        if (tx.requestBody) {
            lines.push('');
            lines.push('── Body' + (tx.requestBodyTruncated ? ' (truncated)' : '') + ' ──');
            lines.push(debugFormatJson(tx.requestBody));
        }

        lines.push('');
        lines.push('══════════════════ RESPONSE ══════════════════');
        lines.push('');
        if (tx.responseHeaders && Object.keys(tx.responseHeaders).length > 0) {
            lines.push('── Headers ──');
            Object.keys(tx.responseHeaders).forEach(function(k) {
                lines.push(k + ': ' + tx.responseHeaders[k]);
            });
        }
        if (tx.responseBody) {
            lines.push('');
            lines.push('── Body' + (tx.responseBodyTruncated ? ' (truncated)' : '') + ' ──');
            lines.push(debugFormatJson(tx.responseBody));
        }

        var text = lines.join('\n');
        copyNetworkText(text, ev);
    };

    var PLATFORM_MOCK_NOTE = 'Mock rules answer OkHttp requests only, and this request came through platform HttpURLConnection.';
    var APP_MOCK_NOTE = " The app's own adapter reported this request, so a rule answers it only if the adapter asks for it.";

    // No mock rule can answer a platform request, so a rule made from one would
    // never match it, and no rule matches one.
    window.netMockButtonsState = mockButtonsState;
    function mockButtonsState(tx) {
        if (tx.source === 'platform') {
            return {
                disabled: true,
                mockThisTitle: PLATFORM_MOCK_NOTE,
                rulesTitle: PLATFORM_MOCK_NOTE,
                ruleCount: 0,
            };
        }
        var adapter = tx.source === 'app' ? APP_MOCK_NOTE : '';
        return {
            disabled: false,
            mockThisTitle: 'Open the mock rules with a new rule filled in from this request: its URL, method, status, and body. Edit the status or the body before you save to set the response of the next request like this one.' + adapter,
            rulesTitle: 'Show the mock rules whose URL pattern and method match this request, enabled or not. The first enabled one answers the next request like this one.' + adapter,
            ruleCount: (tx.matchingMockRuleIds || []).length,
        };
    }

    function findRule(id) {
        if (!id) return null;
        for (var i = 0; i < loadedRules.length; i++) if (loadedRules[i].id === id) return loadedRules[i];
        return null;
    }

    function ruleLabel(rule) {
        return rule.name || ((rule.method ? rule.method + ' ' : '') + rule.urlPattern);
    }

    // A dashed MOCK on a row that a rule would answer if the app made it again,
    // such as one captured before the rule existed.
    function armedMockTag(tx) {
        if (!tx.activeMockRuleId) return '';
        var rule = findRule(tx.activeMockRuleId);
        var title = 'Mock rule active' + (rule ? ': ' + ruleLabel(rule) : '') + '. The next request like this one gets the mocked response.'
            + (tx.source === 'app' ? APP_MOCK_NOTE : '');
        return ' <span class="dc-tag dc-tag--mock net-mock-armed" title="' + debugEscapeHtml(title) + '">Mock</span>';
    }

    // What the detail says about the rules for this request: which rule answered
    // it, and which one answers the next request like it. The app matches the
    // rules, so this says what the interceptor does.
    window.netMockRuleLine = mockRuleLine;
    function mockRuleLine(tx, rules) {
        var byId = function(id) { return (rules || []).find(function(r) { return r.id === id; }) || null; };
        var link = function(rule) {
            return '<button type="button" class="net-mock-rule-link" data-action="openRequestRules" title="Show the mock rules for this request.">'
                + debugEscapeHtml(ruleLabel(rule)) + '</button>';
        };
        var active = tx.activeMockRuleId ? byId(tx.activeMockRuleId) : null;
        var activeKnown = !!tx.activeMockRuleId;
        var adapter = tx.source === 'app' ? ", if the app's adapter asks for mock rules" : '';
        var parts = [];
        if (tx.isMocked) {
            var answered = tx.mockRuleId ? byId(tx.mockRuleId) : null;
            if (answered) parts.push('Mocked by rule ' + link(answered) + '.');
            // rules is null until they are loaded, and then an unknown id is not a deleted rule yet.
            else if (tx.mockRuleId && rules) parts.push('Mocked by a rule that was deleted.');
            else parts.push('Mocked by a rule.');
            if (activeKnown && tx.activeMockRuleId === tx.mockRuleId) {
                // The rule can have been edited since, so it answers with what it says now.
                parts.push('The rule is still active, so it also answers the next request like this one' + adapter + '.');
            } else if (activeKnown) {
                parts.push('Now rule ' + (active ? link(active) : 'another rule') + ' answers the next request like this one' + adapter + '.');
            } else if (tx.activeMockRuleId === null) {
                parts.push('No enabled rule matches it now, so the next request like this one goes to the server.');
            }
        } else if (activeKnown) {
            parts.push('Mock rule active: ' + (active ? link(active) : 'a rule') + '. The next request like this one gets the mocked response' + adapter + '.');
        }
        if (!parts.length) return '';
        return '<div class="net-mock-line' + (activeKnown ? ' net-mock-line--active' : '') + '">' + parts.join(' ') + '</div>';
    }

    function renderDetail(tx) {
        // A search or a refresh renders the bodies again; keep what was folded in
        // each one that is the same, such as a request's while its response streams.
        var previous = currentDetailTx;
        // The copy that replaced the request on screen continues its detail: it
        // keeps the folds and the scroll position, as a refresh of the same one does.
        var continued = !!previous && (previous.id === tx.id || detailContinuesTo === tx.id);
        if (detailContinuesTo === tx.id) detailContinuesTo = null;
        var folded = continued ? foldedNodes() : {};
        ['request', 'response'].forEach(function(dir) {
            if (previous && previous[dir + 'Body'] !== tx[dir + 'Body']) delete folded[dir];
        });
        if (!previous || previous.id !== tx.id) hexDumps = {};
        currentDetailTx = tx;
        showDetailActions(true);
        copyStore = {};
        copyIdSeq = 0;
        var el = document.getElementById('detail-content');
        if (!el) return;
        var sc = statusClass(tx);
        var streaming = isStreaming(tx);
        var statusLabel = tx.error ? 'Error' : (tx.statusCode || 'Pending');
        if (streaming) statusLabel += ' streaming';
        var html = '<div class="net-detail-header">';
        html += '<div class="net-detail-method-url"><span class="net-detail-method ' + methodClass(tx) + '">' + debugEscapeHtml(tx.method || '') + '</span> ' + debugHighlightPlain(tx.url, searchMark(searchText, searchTextRegex)) + ' ' + copyBtn(tx.url || '') + '</div>';
        html += '<div class="net-detail-meta">';
        html += '<span class="' + sc + '">' + statusLabel + '</span>';
        html += '<span>' + (tx.durationMs != null ? tx.durationMs + 'ms' + (streaming ? ' streaming' : '') : '—') + '</span>';
        if (tx.throttledMs) html += '<span title="' + THROTTLE_TITLE + '">+' + formatThrottle(tx.throttledMs) + ' throttle</span>';
        html += '<span>' + debugEscapeHtml(tx.timestamp || '') + '</span>';
        if (tx.protocol) html += '<span title="Protocol the response came over">' + debugEscapeHtml(tx.protocol) + '</span>';
        var source = captureSource(tx);
        if (source) html += '<span title="' + debugEscapeHtml(source.title) + '">' + source.label + '</span>';
        html += bodyMeta('↑', 'Request', tx.requestBodyBytes, tx.requestContentType);
        html += bodyMeta('↓', 'Response', tx.responseBodyBytes, tx.responseContentType);
        (tx.categories || []).forEach(function(c) { html += '<span class="dc-tag" data-cat="' + debugEscapeHtml(c) + '">' + debugEscapeHtml(c) + '</span>'; });
        if (tx.isMocked) html += '<span class="dc-badge" style="--c: var(--ai)">Mocked</span>';
        html += replacedBadge(tx);
        html += '</div>';
        html += '<div id="detail-mock-line">' + mockRuleLine(tx, rulesLoaded ? loadedRules : null) + '</div>';
        if (tx.error) html += '<div class="net-error-line">' + debugEscapeHtml(tx.error) + '</div>';
        html += redirectsHtml(tx);
        html += '</div>';

        html += '<div class="dc-seg net-dir-tabs">';
        html += '<button class="dc-seg__item' + (activeDir === 'response' ? ' dc-seg__item--active' : '') + '" data-action="switchDir" data-dir="response" title="Show what the server sent back (status, headers, body).">Response</button>';
        html += '<button class="dc-seg__item' + (activeDir === 'request' ? ' dc-seg__item--active' : '') + '" data-action="switchDir" data-dir="request" title="Show what the app sent (method, URL, headers, body). Redacted values are removed.">Request</button>';
        html += '</div>';

        // One panel per direction: headers (collapsed <details>) above the
        // body. The collapsed/expanded choice is global, persisted in the
        // browser (see headersOpen), and shared by both directions.
        var vis = function(dir) { return dir === activeDir ? '' : ' style="display:none"'; };

        html += '<div class="net-dir-content" data-dir="response"' + vis('response') + '>';
        if (tx.responseHeaders && Object.keys(tx.responseHeaders).length > 0) {
            html += formatHeaders(tx.responseHeaders);
        }
        html += bodySection(tx, 'response');
        html += '</div>';

        html += '<div class="net-dir-content" data-dir="request"' + vis('request') + '>';
        if (tx.requestHeaders && Object.keys(tx.requestHeaders).length > 0) {
            html += formatHeaders(tx.requestHeaders);
        }
        html += bodySection(tx, 'request');
        html += '</div>';

        if (tx.webSocketId) {
            html += '<div style="margin-top:16px"><button class="dc-btn" data-action="openWebSocket" data-ws-id="' + debugEscapeHtml(tx.webSocketId)
                + '" title="This request is the handshake of a WebSocket. Show the socket and its messages.">WebSocket messages</button></div>';
        }

        var scrollTop = el.scrollTop;
        el.innerHTML = html;
        refold(folded);
        if (continued) el.scrollTop = scrollTop;
        loadHexDumps();
        // The rules filtered to this request follow what it matches now.
        if (rightPane === 'rules' && rulesFilterTxId === tx.id && (!previous || previous.id !== tx.id
            || String(previous.matchingMockRuleIds) !== String(tx.matchingMockRuleIds)
            || previous.activeMockRuleId !== tx.activeMockRuleId)) {
            renderRules(loadedRules);
        }
    }

    // After the rules change, the names in the detail and on the rows.
    function renderRuleNames() {
        renderList();
        var line = document.getElementById('detail-mock-line');
        if (line && currentDetailTx) line.innerHTML = mockRuleLine(currentDetailTx, loadedRules);
    }

    // The way from the requested URL to the one the response came from: each
    // response the client answered with another request, then the final URL.
    // Platform capture knows only the final URL.
    function redirectSteps(tx) {
        if (!tx.finalUrl && !(tx.priorResponses && tx.priorResponses.length)) return [];
        var steps = (tx.priorResponses || []).map(function(prior) {
            return { label: String(prior.statusCode), url: prior.url, statusCode: prior.statusCode };
        });
        if (!steps.length) steps.push({ label: 'from', url: tx.url });
        steps.push({ label: '→', url: tx.finalUrl || tx.url, final: true });
        return steps;
    }

    function redirectsHtml(tx) {
        var steps = redirectSteps(tx);
        if (!steps.length) return '';
        var html = '<div class="net-redirects" title="Redirects and authentication challenges the client followed, oldest first, then where the response came from">';
        steps.forEach(function(step) {
            var cls = step.statusCode ? statusClass({ statusCode: step.statusCode, responseComplete: true }) : '';
            html += '<div class="net-redirect-step"><span class="net-redirect-code ' + cls + '">' + debugEscapeHtml(step.label) + '</span>'
                + '<span class="net-redirect-url">' + debugHighlightPlain(step.url || '', searchMark(searchText, searchTextRegex)) + '</span>'
                + (step.final ? ' ' + copyBtn(step.url || '') : '') + '</div>';
        });
        return html + '</div>';
    }

    var activeDir = 'response';

    function updateDetailPanels() {
        var panels = document.querySelectorAll('.net-dir-content');
        panels.forEach(function(p) {
            p.style.display = (p.dataset.dir === activeDir) ? '' : 'none';
        });
    }

    window.switchDir = function(dir) {
        activeDir = dir;
        document.querySelectorAll('.net-dir-tabs .dc-seg__item').forEach(function(b) {
            b.classList.toggle('dc-seg__item--active', b.dataset.dir === dir);
        });
        updateDetailPanels();
    };

    // Headers <details> expanded state: ONE browser-persisted flag shared by
    // every transaction and both directions (not per URL).
    var HEADERS_OPEN_KEY = 'debug-network-headers-open';
    function headersOpen() {
        try { return localStorage.getItem(HEADERS_OPEN_KEY) === '1'; } catch(e) { return false; }
    }
    function setHeadersOpen(open) {
        try { localStorage.setItem(HEADERS_OPEN_KEY, open ? '1' : '0'); } catch(e) {}
    }
    // <details> toggle events don't bubble; the delegated listener in
    // setupDelegation uses capture. Mirrors the state onto the other
    // direction's details so switching Response/Request stays consistent.
    function onHeadersToggle(ev) {
        var el = ev.target;
        if (!el.classList || !el.classList.contains('net-headers-details')) return;
        setHeadersOpen(el.open);
        document.querySelectorAll('.net-headers-details').forEach(function(d) {
            if (d !== el && d.open !== el.open) d.open = el.open;
        });
    }

    // The views each kind of body has, the default first. Raw is the body as
    // captured: its text with line numbers, or the bytes of a binary body.
    var BODY_VIEWS = {
        json: ['tree', 'raw'],
        form: ['table', 'raw'],
        multipart: ['parts', 'raw'],
        xml: ['pretty', 'raw'],
        html: ['pretty', 'raw'],
        image: ['preview', 'raw'],
        binary: ['raw'],
        text: ['raw'],
    };
    var BODY_VIEW_LABELS = { tree: 'Tree', table: 'Table', parts: 'Parts', pretty: 'Pretty', preview: 'Preview', raw: 'Raw' };
    var BODY_VIEW_HELP = {
        tree: 'Show the JSON as a tree. Click a key or bracket to fold it, and Alt-click to fold or unfold everything inside it.',
        table: 'Show the form fields as a table, with names and values decoded.',
        parts: 'Show the parts as a table: each field and its value. Lustro keeps text parts, and only the type and size of a file.',
        pretty: 'Show the markup indented. Only whitespace is added.',
        preview: 'Show the image.',
        raw: 'Show the body as captured, with line numbers.',
    };

    // The view chosen for each media type, remembered in the browser like the
    // headers toggle.
    var BODY_VIEWS_KEY = 'debug-network-body-views';
    function savedBodyViews() {
        try { return JSON.parse(localStorage.getItem(BODY_VIEWS_KEY)) || {}; } catch(e) { return {}; }
    }
    function bodyViewFor(contentType, kind) {
        var saved = savedBodyViews()[mediaEssence(contentType)];
        return BODY_VIEWS[kind].indexOf(saved) >= 0 ? saved : BODY_VIEWS[kind][0];
    }
    function saveBodyView(contentType, view) {
        var views = savedBodyViews();
        views[mediaEssence(contentType)] = view;
        try { localStorage.setItem(BODY_VIEWS_KEY, JSON.stringify(views)); } catch(e) {}
    }
    function mediaEssence(contentType) {
        return String(contentType || '').split(';')[0].trim().toLowerCase();
    }

    // One direction's body: a bar with its views, then the view. Copy copies the
    // body as captured in every view, because an inspector is opened to see the
    // bytes, not a rendering of them.
    function bodySection(tx, dir) {
        var text = tx[dir + 'Body'];
        var binary = !!tx[dir + 'BodyBinary'];
        var open = '<div class="net-body" data-dir="' + dir + '">';
        if (!binary && !text) {
            return open + missingBody(tx[dir + 'Headers'], text, dir === 'response' ? 'No response body' : 'No request body',
                tx[dir + 'BodyBytes'], tx[dir + 'ContentType']) + '</div>';
        }
        var contentType = tx[dir + 'ContentType'];
        var truncated = !!tx[dir + 'BodyTruncated'];
        var kind = debugBodyKind(contentType, text, binary);
        var view = bodyViewFor(contentType, kind);
        var options = { searchText: searchMark(searchText, searchTextRegex) };
        var content = null;
        var notJson = false;
        if (kind === 'json') {
            content = view === 'tree' ? debugJsonTree(text, options) : null;
            // A JSON type on text that is not JSON, such as a body cut at the
            // capture cap.
            if (!content && (view === 'tree' || !debugScanJsonSource(text, 0))) {
                notJson = debugBodyKind(contentType, '', false) === 'json';
                kind = 'text';
                view = 'raw';
            }
        }

        // A hex dump's text arrives later, and loadHexDumps gives it to the button.
        var copy = binary && view !== 'raw' ? '' : copyBtn(binary ? null : text);
        if (content == null) {
            if (binary && view === 'raw') {
                content = '<pre class="dc-code net-hexdump" data-hex-src="' + debugEscapeHtml(bodyUrl(tx, dir)) + '">Loading the bytes…</pre>';
            } else if (view === 'preview') {
                // The browser sends the session cookie with a same-origin image.
                content = '<div class="net-body-image"><img class="net-body-img" src="'
                    + debugEscapeHtml(bodyUrl(tx, dir)) + '" alt="' + dir + ' body"></div>';
            } else if (view === 'table') {
                content = debugFormTable(text, options);
            } else if (view === 'parts') {
                content = debugMultipartTable(text, contentType, options) || debugLineNumbered(text, options);
            } else if (view === 'pretty') {
                content = debugHighlightMarkup(text, { searchText: options.searchText, html: kind === 'html' });
            } else {
                content = debugLineNumbered(text, options);
            }
        }

        var bar = '<div class="net-body-bar">';
        var views = BODY_VIEWS[kind];
        if (views.length > 1) {
            bar += '<div class="dc-seg dc-seg--sm" role="group" aria-label="' + (dir === 'response' ? 'Response' : 'Request') + ' body view">'
                + views.map(function(v) {
                    var help = v === 'raw' && binary ? 'Show the bytes as captured, as a hex dump.' : BODY_VIEW_HELP[v];
                    return '<button class="dc-seg__item' + (v === view ? ' dc-seg__item--active' : '') + '" data-action="switchBodyView"'
                        + ' data-dir="' + dir + '" data-view="' + v + '"'
                        + ' title="' + debugEscapeHtml(help + ' Remembered for this content type.') + '">'
                        + BODY_VIEW_LABELS[v] + '</button>';
                }).join('')
                + '</div>';
        }
        if (truncated) bar += '<span class="net-truncated-label" title="The body passed the capture cap, so only its first part was kept.">Truncated</span>';
        if (notJson && !truncated) bar += '<span class="dc-comment">// not valid JSON</span>';
        if (view === 'preview') {
            var size = formatBytes(tx[dir + 'BodyBytes']);
            bar += '<span class="net-body-meta" data-size="' + debugEscapeHtml(size) + '">' + debugEscapeHtml(size) + '</span>';
        } else if (kind === 'binary') {
            bar += '<span class="dc-comment">// ' + debugEscapeHtml(mediaEssence(contentType) || 'binary') + ' has no preview</span>';
        }
        bar += '<a class="dc-btn dc-btn--sm net-body-download" href="' + debugEscapeHtml(bodyUrl(tx, dir)) + '"'
            + ' download="' + debugEscapeHtml(bodyFileName(tx, dir, contentType)) + '"'
            + ' title="Save the body as captured to a file.">Download</a>';
        bar += '</div>';

        return open + bar + '<div class="net-body-wrap">' + copy + content + '</div></div>';
    }

    function bodyUrl(tx, dir) {
        return netUrl('transactions/' + encodeURIComponent(tx.id) + '/body/' + dir);
    }

    // No prototype: the media type comes from the captured headers.
    var BODY_FILE_EXTENSIONS = Object.assign(Object.create(null), {
        'application/x-www-form-urlencoded': 'txt', 'text/plain': 'txt', 'text/event-stream': 'txt',
        'application/javascript': 'js', 'text/javascript': 'js',
        'image/jpeg': 'jpg', 'image/svg+xml': 'svg', 'image/x-icon': 'ico', 'image/vnd.microsoft.icon': 'ico',
        'application/vnd.apple.mpegurl': 'm3u8', 'application/x-mpegurl': 'm3u8', 'audio/mpegurl': 'm3u8',
        'audio/x-mpegurl': 'm3u8',
    });
    function bodyFileName(tx, dir, contentType) {
        var essence = mediaEssence(contentType);
        var subtype = essence.slice(essence.indexOf('/') + 1);
        var ext = BODY_FILE_EXTENSIONS[essence]
            || subtype.slice(subtype.lastIndexOf('+') + 1).replace(/^(x-|vnd\.)/, '').replace(/[^a-z0-9]/g, '').slice(0, 8)
            || (tx[dir + 'BodyBinary'] ? 'bin' : 'txt');
        return 'lustro-' + String(tx.id).slice(0, 8) + '-' + dir + '.' + ext;
    }

    window.switchBodyView = function(dir, view) {
        var tx = currentDetailTx;
        if (!tx) return;
        saveBodyView(tx[dir + 'ContentType'], view);
        // Both directions, since they can share the content type.
        var folded = foldedNodes();
        document.querySelectorAll('.net-body').forEach(function(section) {
            section.outerHTML = bodySection(tx, section.dataset.dir);
        });
        refold(folded);
        loadHexDumps();
    };

    // The folded nodes of each tree on screen, by position.
    function foldedNodes() {
        var folded = {};
        document.querySelectorAll('.net-body').forEach(function(section) {
            var indexes = [];
            section.querySelectorAll('.dc-fold').forEach(function(fold, i) {
                if (fold.classList.contains('dc-fold--closed')) indexes.push(i);
            });
            folded[section.dataset.dir] = indexes;
        });
        return folded;
    }
    function refold(folded) {
        document.querySelectorAll('.net-body').forEach(function(section) {
            var indexes = folded[section.dataset.dir];
            if (!indexes || !indexes.length) return;
            var folds = section.querySelectorAll('.dc-fold');
            indexes.forEach(function(i) { if (folds[i]) folds[i].classList.add('dc-fold--closed'); });
        });
    }

    // A hex dump of a binary body, fetched from the body route. The dump is
    // text in a <pre>, so it goes in through textContent and needs no escaping.
    // Past the limit a dump is too long to read, and the download has it all.
    var HEX_DUMP_LIMIT = 256 * 1024;
    var hexDumps = {};
    function loadHexDumps() {
        document.querySelectorAll('.net-hexdump[data-hex-src]').forEach(function(pre) {
            var src = pre.dataset.hexSrc;
            pre.removeAttribute('data-hex-src');
            var show = function(dump) {
                // The detail may have been rendered again meanwhile, and the copy
                // id given to another button.
                if (!pre.isConnected) return;
                pre.textContent = dump;
                var copy = pre.parentNode.querySelector('.net-copy-btn');
                if (copy) copyStore[copy.dataset.copyId] = dump;
            };
            if (hexDumps[src] != null) { show(hexDumps[src]); return; }
            debugFetch(src)
                .then(function(r) { return r.arrayBuffer(); })
                .then(function(buffer) {
                    var bytes = new Uint8Array(buffer);
                    var dump = debugHexDump(bytes.subarray(0, HEX_DUMP_LIMIT));
                    if (bytes.length > HEX_DUMP_LIMIT) {
                        dump += '\n… ' + (bytes.length - HEX_DUMP_LIMIT) + ' more bytes. Download the body to see them all.';
                    }
                    hexDumps[src] = dump;
                    show(dump);
                })
                .catch(function() {
                    if (pre.isConnected) pre.textContent = 'Failed to load the body.';
                });
        });
    }

    // An image's pixel size is known once it loads; until then the bar shows its
    // size in bytes. load and error don't bubble, so this listens in capture.
    function onBodyImage(ev) {
        var img = ev.target;
        if (!img || !img.classList || !img.classList.contains('net-body-img')) return;
        if (ev.type === 'error') {
            img.parentNode.innerHTML = '<div class="net-empty-body">The browser could not show this image. Raw shows its bytes.</div>';
            return;
        }
        var section = img.closest('.net-body');
        var meta = section && section.querySelector('.net-body-meta');
        if (meta) {
            meta.textContent = (meta.dataset.size ? meta.dataset.size + ' · ' : '')
                + img.naturalWidth + ' × ' + img.naturalHeight + ' px';
        }
    }

    // Capture inflates a gzip or deflate body and keeps no body in another
    // content coding, such as br. An empty body is "", so null is one not kept.
    // Why a body has nothing to show. A body with a size was sent or received,
    // and Lustro did not keep it, which is not the same as an empty one.
    function missingBody(headers, body, label, bytes, contentType) {
        var coding = body == null ? undecodedCoding(headers) : null;
        var text = label;
        if (coding) {
            text = 'Body not captured: Lustro does not decode the ' + coding + ' encoding';
        } else if (body == null && bytes > 0) {
            text = 'Body not stored: ' + formatBytes(bytes) + (contentType ? ' of ' + mediaEssence(contentType) : '')
                + '. Lustro keeps text and image bodies, and not a body the app can send only once.';
        }
        return '<div class="net-empty-body">' + debugEscapeHtml(text) + '</div>';
    }

    function undecodedCoding(headers) {
        var codings = [];
        Object.keys(headers || {}).forEach(function(k) {
            if (k.toLowerCase() !== 'content-encoding') return;
            String(headers[k]).split(',').forEach(function(c) {
                c = c.trim().toLowerCase();
                if (c && c !== 'identity') codings.push(c);
            });
        });
        if (!codings.length) return null;
        if (codings.length === 1 && ['gzip', 'x-gzip', 'deflate'].indexOf(codings[0]) >= 0) return null;
        return codings.join(', ');
    }

    // Search sees a header as the line "Name: value", so a match is found in that
    // line and marked in the name and the value.
    function formatHeaders(headers) {
        var keys = Object.keys(headers);
        var matched = 0;
        var rows = keys.map(function(k) {
            var value = String(headers[k]);
            var ranges = searchText ? debugMatchRanges(k + ': ' + value, searchMark(searchText, searchTextRegex)) : [];
            if (ranges.length) matched++;
            return '<tr class="net-header-row"><td class="net-header-key">' + debugMarkRanges(k, ranges, 0)
                + '</td><td class="net-header-value">' + copyBtn(headers[k], 'net-copy-hover') + debugMarkRanges(value, ranges, k.length + 2) + '</td></tr>';
        }).join('');
        return '<details class="net-headers-details"' + (headersOpen() ? ' open' : '') + '>'
            + '<summary title="Expand or collapse the headers. The choice is remembered in your browser.">'
            + keys.length + ' header' + (keys.length === 1 ? '' : 's')
            + (matched ? ', <mark>' + matched + ' match' + (matched === 1 ? 'es' : '') + ' the search</mark>' : '') + '</summary>'
            + '<table class="net-headers-table">' + rows + '</table>'
            + '</details>';
    }

    var THROTTLE_TITLE = 'The global throttle held the request this long before it was sent. The duration leaves it out.';

    // Mock rules and the throttle act on OkHttp requests, so a request that another
    // adapter captured says which one. Unknown before protocol 1.6, read as OkHttp.
    var SOURCES = {
        platform: {
            label: 'HttpURLConnection',
            note: 'captured by platform HttpURLConnection',
            title: 'Captured by platform HttpURLConnection capture, which only records: no mock rule answers this request, and the throttle does not hold it.',
        },
        app: {
            label: 'App adapter',
            note: "reported by the app's own adapter",
            title: "Reported by the app's own adapter through Lustro.networkCaptureSink(). A mock rule answers this request only if the adapter asks for it, and the throttle does not hold it.",
        },
    };

    function captureSource(tx) {
        return (tx && SOURCES[tx.source]) || null;
    }

    function formatThrottle(ms) {
        return ms >= 1000 && ms % 1000 === 0 ? (ms / 1000) + 's' : ms + 'ms';
    }

    function throttleBadge(tx) {
        if (!tx.throttledMs) return '';
        return ' <span class="net-throttle" title="' + THROTTLE_TITLE + '">+' + formatThrottle(tx.throttledMs) + '</span>';
    }

    function formatBytes(n) {
        if (n == null) return '';
        if (n < 1024) return n + ' B';
        if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
        return (n / (1024 * 1024)).toFixed(2) + ' MB';
    }

    // "↓ 1.2 KB application/json": a body's size and media type, whichever
    // are known. The header shows the type without its parameters; the
    // tooltip has it as captured, charset and all.
    function bodyMeta(arrow, label, bytes, contentType) {
        var shown = [];
        var full = [];
        if (bytes != null) {
            shown.push(formatBytes(bytes));
            full.push(formatBytes(bytes));
        }
        if (contentType) {
            shown.push(String(contentType).split(';')[0].trim());
            full.push(contentType);
        }
        if (!shown.length) return '';
        return '<span title="' + debugEscapeHtml(label + ' body: ' + full.join(', ')) + '">'
            + arrow + ' ' + debugEscapeHtml(shown.join(' ')) + '</span>';
    }

    window.netCurlCommand = buildCurlCommand;
    function buildCurlCommand(tx) {
        var parts = ['curl', '-X', tx.method || 'GET'];
        var headers = tx.requestHeaders || {};
        var hasBody = tx.requestBody && tx.method !== 'GET' && tx.method !== 'HEAD';
        // curl builds a multipart body from its parts, with a boundary of its own.
        var formParts = hasBody ? debugMultipartParts(tx.requestBody, tx.requestContentType) : null;
        Object.keys(headers).forEach(function(k) {
            // The captured body is stored inflated and redacted, so it goes out
            // without its coding, and curl works out its length.
            var name = k.toLowerCase();
            if (hasBody && (name === 'content-encoding' || name === 'content-length')) return;
            if (formParts && name === 'content-type') return;
            parts.push('-H');
            parts.push(shellQuote(k + ': ' + headers[k]));
        });
        if (formParts) {
            formParts.forEach(function(part) {
                var name = part.name == null ? '' : part.name;
                if (part.value != null && part.filename == null && part.contentType) {
                    // A typed text part, such as JSON next to a file. curl takes a value in
                    // double quotes as it is, and the type after it.
                    parts.push('-F');
                    parts.push(shellQuote(name + '=' + curlQuote(part.value) + ';type=' + part.contentType));
                } else if (part.value != null && part.filename == null) {
                    // --form-string, because -F reads a value that starts with @ or < as a file.
                    parts.push('--form-string');
                    parts.push(shellQuote(name + '=' + part.value));
                } else {
                    // Lustro did not keep a file's bytes: the command names the file to send.
                    // In quotes, because curl reads , and ; in a bare file name as separators.
                    parts.push('-F');
                    parts.push(shellQuote(name + '=@' + curlQuote(part.filename || 'FILE') + (part.contentType ? ';type=' + part.contentType : '')));
                }
            });
        } else if (hasBody) {
            parts.push('--data-raw');
            parts.push(shellQuote(tx.requestBody));
        }
        parts.push(shellQuote(tx.url || ''));
        return parts.join(' ');
    }
    // A -F value or file name in double quotes, which curl takes as it is.
    function curlQuote(s) {
        return '"' + String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
    }
    function shellQuote(s) {
        return "'" + String(s == null ? '' : s).replace(/'/g, "'\\''") + "'";
    }
    window.copyCurl = function(ev) {
        if (!currentDetailTx) return;
        copyNetworkText(buildCurlCommand(currentDetailTx), ev);
    };

    window.copyMarkdown = function(ev) {
        if (!currentDetailTx) return;
        copyNetworkText(window.netTransactionsMarkdown([currentDetailTx]), ev);
    };

    // The bar's actions act on the selected request, so the bar shows only with
    // its detail: a refresh of the detail behind the rules or the Send form
    // leaves it hidden.
    function showDetailActions(show) {
        var visible = show && !!currentDetailTx && rightPane === 'detail' && trafficView === 'http';
        var bar = document.getElementById('detail-bar');
        if (bar) bar.hidden = !visible;
        if (!visible) return;
        var resend = document.getElementById('resend-btn');
        if (resend) resend.hidden = !senders.length;
        var state = mockButtonsState(currentDetailTx);
        var mockBtn = document.getElementById('mock-this-btn');
        if (mockBtn) {
            mockBtn.disabled = state.disabled;
            mockBtn.title = state.mockThisTitle;
        }
        var rulesBtn = document.getElementById('request-rules-btn');
        if (rulesBtn) {
            rulesBtn.disabled = state.disabled;
            rulesBtn.title = state.rulesTitle;
        }
        var count = document.getElementById('request-rules-count');
        if (count) count.textContent = state.ruleCount ? ' (' + state.ruleCount + ')' : '';
    }

    // Transaction details as one Markdown document, oldest first as given: for
    // each, the method and URL as a heading, then the headers in http blocks and
    // the bodies in blocks tagged with a language for their content type. A
    // body the capture cut short says so.
    window.netTransactionsMarkdown = function(txs) {
        return txs.map(transactionMarkdown).join('\n\n---\n\n') + '\n';
    };

    function transactionMarkdown(tx) {
        var out = ['## ' + markdownCode((tx.method || '') + ' ' + (tx.url || ''))];
        var meta = [];
        var status = tx.error ? 'Failed' : (tx.statusCode != null ? String(tx.statusCode) : 'Pending');
        if (isStreaming(tx)) status += ', streaming';
        meta.push('**' + status + '**');
        if (tx.durationMs != null) meta.push(tx.durationMs + ' ms');
        if (tx.throttledMs) meta.push('held ' + tx.throttledMs + ' ms by the throttle');
        if (tx.startedAt != null) meta.push(new Date(tx.startedAt).toISOString());
        else if (tx.timestamp) meta.push(tx.timestamp);
        if (tx.protocol) meta.push(markdownCode(tx.protocol));
        if (tx.isMocked) meta.push('mocked by Lustro');
        var source = captureSource(tx);
        if (source) meta.push(source.note);
        (tx.categories || []).forEach(function(c) { meta.push(markdownCode(c)); });
        out.push(meta.join(' · '));
        var steps = redirectSteps(tx);
        if (steps.length) {
            out.push('**Redirects:**\n\n' + steps.map(function(step) {
                return '- ' + (step.final ? 'Response from' : step.label) + ' ' + markdownCode(step.url || '');
            }).join('\n'));
        }
        if (tx.error) out.push('**Error:** ' + markdownCode(tx.error));
        ['request', 'response'].forEach(function(dir) {
            var label = dir === 'request' ? 'Request' : 'Response';
            var headers = tx[dir + 'Headers'] || {};
            var names = Object.keys(headers);
            if (names.length) {
                out.push('### ' + label + ' headers');
                out.push(markdownFence(names.map(function(k) { return k + ': ' + headers[k]; }).join('\n'), 'http'));
            }
            var body = markdownBody(tx, dir);
            if (body) out.push('### ' + label + ' body', body);
        });
        return out.join('\n\n');
    }

    function markdownBody(tx, dir) {
        var text = tx[dir + 'Body'];
        var contentType = tx[dir + 'ContentType'];
        if (tx[dir + 'BodyBinary']) {
            var size = formatBytes(tx[dir + 'BodyBytes']);
            return '_' + (mediaEssence(contentType) || 'Binary') + ' body' + (size ? ', ' + size : '')
                + ': kept as bytes, so it is not included.'
                + (tx[dir + 'BodyTruncated'] ? ' Lustro kept only its first part.' : '') + '_';
        }
        if (text == null) {
            var coding = undecodedCoding(tx[dir + 'Headers']);
            return coding ? '_Not captured: Lustro does not decode the ' + coding + ' encoding._' : '';
        }
        if (text === '') return '';
        var kind = debugBodyKind(contentType, text, false);
        var shown = text;
        if (kind === 'json') {
            // Indents without changing a value; a body cut short stays as it is.
            var pieces = debugScanJsonSource(text, 2);
            if (pieces) shown = pieces.map(function(piece) { return piece.text; }).join('');
        }
        var block = markdownFence(shown, markdownLanguage(kind, contentType));
        return tx[dir + 'BodyTruncated']
            ? '_Truncated: Lustro kept only the first part of this body._\n\n' + block
            : block;
    }

    // No prototype: the media type comes from the captured headers.
    var MARKDOWN_LANGUAGES = Object.assign(Object.create(null), {
        'text/css': 'css', 'text/csv': 'csv', 'text/markdown': 'markdown',
        'application/javascript': 'javascript', 'text/javascript': 'javascript',
        'application/graphql': 'graphql', 'application/yaml': 'yaml', 'application/x-yaml': 'yaml', 'text/yaml': 'yaml',
    });
    function markdownLanguage(kind, contentType) {
        if (kind === 'json' || kind === 'xml' || kind === 'html') return kind;
        return MARKDOWN_LANGUAGES[mediaEssence(contentType)] || 'text';
    }

    // A fenced code block. The fence is longer than any run of backticks in the
    // text, so nothing in it can close the block.
    function markdownFence(text, language) {
        var ticks = backticks(Math.max(3, longestBacktickRun(text) + 1));
        return ticks + language + '\n' + text + (/\n$/.test(text) ? '' : '\n') + ticks;
    }

    // An inline code span on one line, delimited as the fence is.
    function markdownCode(text) {
        text = String(text).replace(/[\r\n]+/g, ' ');
        var ticks = backticks(longestBacktickRun(text) + 1);
        var pad = /^`|`$/.test(text) || /^ .* $/.test(text) ? ' ' : '';
        return ticks + pad + text + pad + ticks;
    }

    function longestBacktickRun(text) {
        return (String(text).match(/`+/g) || []).reduce(function(longest, run) { return Math.max(longest, run.length); }, 0);
    }

    function backticks(n) {
        return new Array(n + 1).join('`');
    }

    // What the right pane shows: 'detail', the selected request; 'rules', the
    // mock rules; or 'send', the Send form. The rules and the form open in place
    // of the detail, each with its own close button, and a click on a row shows
    // the detail again.
    var rightPane = 'detail';
    // The row that "+ New request" or "Mocks" deselected, because what they open
    // is not about it; closing the pane selects it again.
    var selectionBeforePane = null;

    function showRightPane(pane) {
        rightPane = pane;
        ['detail', 'rules', 'send'].forEach(function(p) {
            var el = document.getElementById(p + '-content');
            if (el) el.classList.toggle('active', p === pane);
        });
        // The detail again: the row to go back to no longer applies.
        if (pane === 'detail') selectionBeforePane = null;
        showDetailActions(!!currentDetailTx);
    }

    // Deselects the row for a pane that is about all traffic, and remembers it.
    function deselectForPane() {
        if (!selectedTxId) return;
        selectionBeforePane = selectedTxId;
        selectedTxId = null;
        renderList();
    }

    window.closeRightPane = function() {
        var restore = selectionBeforePane;
        selectionBeforePane = null;
        if (restore && findTransactionById(allTransactions, restore)) {
            window.selectTransaction(restore);
        } else {
            showRightPane('detail');
            if (selectedTxId && currentDetailTx && currentDetailTx.id === selectedTxId) renderDetail(currentDetailTx); else showEmptyDetail();
        }
    };

    // ── Mock rules ──
    // "Mocks", next to the search field, opens all of them; "Matching rules", in
    // the detail's bar, only the ones that match the selected request, as the app
    // matched them (matchingMockRuleIds). "Mock this request" opens those with a
    // new rule filled in from the request.
    // The request the list is filtered to, or null for all rules.
    var rulesFilterTxId = null;

    function openRulesPane(filterTxId) {
        rulesFilterTxId = filterTxId;
        showRightPane('rules');
        renderRules(loadedRules);
        loadRules();
    }

    window.openMockRules = function() {
        deselectForPane();
        openRulesPane(null);
    };

    window.openRequestRules = function() {
        if (!currentDetailTx || currentDetailTx.id !== selectedTxId || currentDetailTx.source === 'platform') return;
        openRulesPane(currentDetailTx.id);
    };

    window.showAllRules = function() {
        rulesFilterTxId = null;
        renderRules(loadedRules);
    };

    // The rule ids the list is filtered to, or null for all of them.
    function rulesFilterIds() {
        if (!rulesFilterTxId) return null;
        var tx = currentDetailTx && currentDetailTx.id === rulesFilterTxId ? currentDetailTx : null;
        return tx ? (tx.matchingMockRuleIds || []) : null;
    }

    function renderRulesScope(shownCount) {
        var el = document.getElementById('rules-scope');
        if (!el) return;
        var tx = rulesFilterIds() ? currentDetailTx : null;
        if (!tx) {
            el.innerHTML = '<span class="dc-comment">// for all OkHttp traffic</span>';
            return;
        }
        var what = (tx.method || '') + ' ' + extractPath(tx.url || '').split('?')[0];
        el.innerHTML = '<span class="net-pane-sub-what" title="' + debugEscapeHtml((tx.method || '') + ' ' + (tx.url || '')) + '">for ' + debugEscapeHtml(what) + '</span>'
            + ' <span class="dc-comment">· ' + shownCount + ' of ' + loadedRules.length + '</span>'
            + ' <button type="button" class="net-format-btn" data-action="showAllRules" title="Show every mock rule, not only the ones that match this request.">Show all</button>';
    }

    // The app owns the rules: the list route is the only source, and the app's
    // MockRuleStorage is what makes them outlive a restart. The browser keeps no
    // copy — localStorage is per origin, so every app reached through the same
    // host:port would share one, and a rule deleted in the app or over the API
    // would come back on the next page load.
    function rulesFromResponse(data) {
        return (data && Array.isArray(data.items)) ? data.items : [];
    }

    // Drop the copy earlier versions kept here. It can hold response bodies, and
    // nothing reads it any more.
    try { localStorage.removeItem('debug-network-mock-rules'); } catch(e) {}

    // Loaded when the pane opens, after each change made here, and when the poll
    // says that the rules changed, so the detail and the rows can name them.
    var loadedRules = [];
    var rulesLoaded = false;
    var rulesLoadSeq = 0;
    var editingRuleId = null;
    // state.mockRules.revision of the last poll.
    var rulesRevision = null;

    function loadRules() {
        var seq = ++rulesLoadSeq;
        return debugFetch(netUrl('rules'))
            .then(function(r) { return r.json(); })
            .then(function(data) {
                if (seq !== rulesLoadSeq) return;
                rulesLoaded = true;
                renderRules(rulesFromResponse(data));
                renderRuleNames();
            })
            .catch(function(e) {
                if (rightPane === 'rules') debugToast('Failed to load rules: ' + e.message, 'error');
            });
    }

    // After a change made here: the rules, and what the selected request matches now.
    function reloadAfterRuleChange() {
        loadRules();
        if (selectedTxId && trafficView === 'http') loadTransactionDetail(selectedTxId);
    }

    function updateMocksButton(mockRules) {
        var count = document.getElementById('mocks-count');
        if (!count || !mockRules) return;
        count.textContent = mockRules.enabled ? ' ' + mockRules.enabled : '';
        var btn = document.getElementById('mocks-btn');
        if (btn) btn.classList.toggle('net-mocks-btn--on', !!mockRules.enabled);
        if (mockRules.revision !== rulesRevision) {
            var first = rulesRevision === null;
            rulesRevision = mockRules.revision;
            loadRules();
            // What the selected request matches is in its detail. The list's own
            // fields come with the next list, as the rules are part of its cursor.
            if (!first && selectedTxId && trafficView === 'http') scheduleSelectedDetailRefresh();
        }
    }

    // Tint a mock rule's status code badge: green below 300, amber 3xx, red from 400.
    function ruleStatusStyle(code) {
        var c = parseInt(code, 10);
        if (!c) return '';
        if (c < 300) return ' style="--c: var(--live)"';
        if (c < 400) return ' style="--c: var(--lvl-w)"';
        return ' style="--c: var(--danger)"';
    }

    function renderRules(rules) {
        loadedRules = rules || [];
        var listEl = document.getElementById('rules-list');
        if (!listEl) return;
        var filterIds = rulesFilterIds();
        var shown = filterIds ? loadedRules.filter(function(r) { return filterIds.indexOf(r.id) >= 0; }) : loadedRules;
        var answeringId = filterIds ? currentDetailTx.activeMockRuleId : null;
        renderRulesScope(shown.length);
        if (shown.length === 0) {
            listEl.innerHTML = filterIds
                ? '<div class="net-rules-empty">No rule matches this request.</div>'
                : '<div class="net-rules-empty">No mock rules defined.</div>';
        } else {
            // The list route returns the rules in the order they are tried.
            var orderNote = shown.length > 1
                ? '<div class="dc-comment net-rules-order" title="The first rule whose pattern and method match a request answers it. A new rule goes on top; editing one keeps its place.">// Tried from the top: the first match answers</div>'
                : '';
            listEl.innerHTML = orderNote + shown.map(function(r) {
                var disabled = r.enabled ? '' : ' disabled';
                var checked = r.enabled ? ' checked' : '';
                var editing = r.id === editingRuleId ? ' editing' : '';
                var answers = answeringId && r.id === answeringId
                    ? '<span class="dc-badge" style="--c: var(--ai)" title="The first enabled rule that matches this request, so the next request like it gets this response.">Answers it</span>'
                    : '';
                var bodyPreview = '';
                if (r.responseBody) {
                    bodyPreview = '<pre class="net-rule-body dc-code dc-json">' + window.debugSyntaxHighlightJson(r.responseBody) + '</pre>';
                }
                return '<div class="net-rule-card' + disabled + editing + '">'
                    + '<div class="net-rule-header">'
                    + '<label class="net-toggle" title="Enable or disable this rule. Disabled rules stay in the list but do not match traffic."><input id="rule-toggle-' + debugEscapeHtml(r.id) + '" name="ruleEnabled" type="checkbox" aria-label="Enable mock rule ' + debugEscapeHtml(r.name || r.urlPattern) + '"' + checked + ' data-action="toggleRule" data-rule-id="' + debugEscapeHtml(r.id) + '"><span class="net-toggle-slider"></span></label>'
                    + '<span class="net-rule-name" title="' + debugEscapeHtml(r.name || r.urlPattern) + '">' + debugEscapeHtml(r.name || r.urlPattern) + '</span>'
                    + answers
                    + '<span class="dc-badge"' + ruleStatusStyle(r.statusCode) + ' title="HTTP status returned to the app when this rule matches.">' + debugEscapeHtml(r.statusCode) + '</span>'
                    + '<button class="dc-btn dc-btn--icon" data-action="editRule" data-rule-id="' + debugEscapeHtml(r.id) + '" title="Edit this rule (loads it into the form below).">✎</button>'
                    + '<button class="dc-btn dc-btn--icon dc-btn--danger" data-action="deleteRule" data-rule-id="' + debugEscapeHtml(r.id) + '" title="Delete this rule permanently.">✕</button>'
                    + '</div>'
                    + '<div class="net-rule-detail">'
                    + (r.method ? debugEscapeHtml(r.method) + ' ' : '') + debugEscapeHtml(r.urlPattern)
                    + '</div>'
                    + bodyPreview
                    + '<div class="net-rule-hits">' + (r.hitCount || 0) + ' hits</div>'
                    + '</div>';
            }).join('');
        }

        var formEl = document.getElementById('rule-form-container');
        if (formEl && !formEl.innerHTML) renderRuleForm();
    }

    // Response headers a new rule gets from the form without showing a field
    // for them: the Content-Type of the response Mock this request copies.
    var pendingRuleHeaders = null;

    function renderRuleForm(prefill) {
        prefill = prefill || {};
        var formEl = document.getElementById('rule-form-container');
        if (!formEl) return;
        var isEdit = !!prefill.id;
        pendingRuleHeaders = isEdit ? null : (prefill.responseHeaders || null);
        var headerNames = pendingRuleHeaders ? Object.keys(pendingRuleHeaders) : [];
        var headersRow = headerNames.length
            ? '<div class="net-form-row"><span class="dc-label" title="Headers of the mocked response, copied from the captured one.">Headers</span>'
                + '<span class="net-form-fixed">' + debugEscapeHtml(headerNames.map(function(h) { return h + ': ' + pendingRuleHeaders[h]; }).join('\n')) + '</span></div>'
            : '';
        var redactedNote = !isEdit && String(prefill.responseBody || '').indexOf('[REDACTED]') >= 0
            ? '<div class="net-form-warning">The captured body has values that Lustro redacted. Replace each [REDACTED] before you save, or the app gets that text in their place.</div>'
            : '';
        var heading = isEdit ? 'Edit Mock Rule' : 'Add Mock Rule';
        var submitText = isEdit ? 'Update Rule' : 'Add Rule';
        var submitTooltip = isEdit
            ? 'Save changes to this rule. Future matching requests use the updated response.'
            : 'Save this rule. Future matching requests get the synthetic response. Rules live in the app, and survive a restart when it gives Lustro a rule storage.';
        formEl.innerHTML = '<div class="net-rule-form">'
            + '<h4>' + heading + (isEdit ? ' <button class="dc-btn dc-btn--icon" data-action="cancelEditRule" title="Cancel editing and return to the empty Add form.">✕</button>' : '') + '</h4>'
            + '<input type="hidden" id="rf-id" name="id" value="' + debugEscapeHtml(prefill.id || '') + '">'
            + '<div class="net-form-row"><label class="dc-label" for="rf-name" title="Optional human label shown in the rule list. Defaults to the URL pattern.">Name</label><input class="dc-input--block" id="rf-name" name="name" placeholder="Optional label" value="' + debugEscapeHtml(prefill.name || '') + '" title="Optional human label. Doesn\'t affect matching."></div>'
            + '<div class="net-form-row"><label class="dc-label" for="rf-pattern" title="What URLs this rule intercepts.">URL Pattern</label><input class="dc-input--block" id="rf-pattern" name="urlPattern" placeholder="Substring or regex:..." value="' + debugEscapeHtml(prefill.urlPattern || '') + '" title="Looked for anywhere in the URL: a substring by default (e.g. /api/sync), or a regular expression with the regex: prefix (e.g. regex:/api/v\\d+/entries$). Anchor a regex with ^ to match from the start of the URL."></div>'
            + '<div class="net-form-row"><label class="dc-label" for="rf-method" title="HTTP method to match.">Method</label>'
            + '<select class="dc-input--block" id="rf-method" name="method" title="HTTP method this rule applies to. Choose Any to match every method.">'
            + '<option value="">Any</option>'
            + ['GET','POST','PUT','PATCH','DELETE'].map(function(m) {
                var sel = prefill.method === m ? ' selected' : '';
                return '<option value="' + m + '"' + sel + '>' + m + '</option>';
            }).join('')
            + '</select></div>'
            + '<div class="net-form-row"><label class="dc-label" for="rf-status" title="HTTP status code returned to the app.">Status</label><input class="dc-input--block" id="rf-status" name="statusCode" type="number" value="' + (prefill.statusCode || 200) + '" style="width:80px;flex:none" title="Status code returned to the app (e.g. 200, 404, 503)."></div>'
            + headersRow
            + '<div class="net-form-row"><label class="dc-label" for="rf-body" title="Body returned to the app when this rule matches.">Body <button type="button" class="net-format-btn" data-action="formatRuleBody" title="Pretty-print the body as JSON (no-op if not valid JSON).">Format</button></label><textarea class="dc-textarea" id="rf-body" name="responseBody" placeholder="Response body (JSON, text, etc.)" title="Response body returned to the app. Can be any string; JSON is auto-formatted in the rule preview.">' + debugEscapeHtml(prefill.responseBody || '') + '</textarea></div>'
            + redactedNote
            + '<div class="net-form-actions">'
            + '<button class="dc-btn dc-btn--primary" data-action="submitRule" title="' + submitTooltip + '">' + submitText + '</button>'
            + (isEdit ? '<button class="dc-btn" data-action="cancelEditRule" title="Discard changes and return to the empty Add form.">Cancel</button>' : '')
            + '</div>'
            + '</div>';
    }

    window.editRule = function(id) {
        var rule = loadedRules.find(function(r) { return r.id === id; });
        if (!rule) return;
        editingRuleId = id;
        renderRuleForm({
            id: rule.id,
            name: rule.name,
            urlPattern: rule.urlPattern,
            method: rule.method || '',
            statusCode: rule.statusCode,
            responseBody: rule.responseBody,
        });
        renderRules(loadedRules);
        var formEl = document.getElementById('rule-form-container');
        if (formEl && formEl.scrollIntoView) formEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    };

    window.cancelEditRule = function() {
        editingRuleId = null;
        renderRuleForm();
        renderRules(loadedRules);
    };

    // ── Send form ──
    // It opens in the right pane, in place of the detail: "+ New request" opens an
    // empty one, "Edit and resend" one filled from the selected request. The app
    // registers the senders (GET senders); without one there is no Send at all.
    var senders = [];
    var SEND_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'];
    // OkHttp or the connection sets these, not the app. Sent again by hand they
    // fight OkHttp: an explicit Accept-Encoding turns off its gzip decoding.
    var TRANSPORT_HEADERS = ['host', 'content-length', 'connection', 'accept-encoding', 'transfer-encoding', 'keep-alive', 'te', 'upgrade', 'proxy-connection'];
    var STATUS_TEXT = {
        200: 'OK', 201: 'Created', 202: 'Accepted', 204: 'No Content', 301: 'Moved Permanently', 302: 'Found',
        304: 'Not Modified', 307: 'Temporary Redirect', 308: 'Permanent Redirect', 400: 'Bad Request',
        401: 'Unauthorized', 403: 'Forbidden', 404: 'Not Found', 405: 'Method Not Allowed', 409: 'Conflict',
        410: 'Gone', 415: 'Unsupported Media Type', 422: 'Unprocessable Content', 429: 'Too Many Requests',
        500: 'Internal Server Error', 502: 'Bad Gateway', 503: 'Service Unavailable', 504: 'Gateway Timeout',
    };

    // The form's values, kept while the form is closed, so a row looked at in
    // between doesn't cost what was typed. "Edit and resend" replaces them.
    var sendDraft = null;

    function loadSenders() {
        debugFetch(netUrl('senders'))
            .then(function(r) { return r.json(); })
            .then(function(data) { setSenders(data && Array.isArray(data.items) ? data.items : []); })
            .catch(function() { setSenders([]); });
    }

    function setSenders(list) {
        senders = list;
        var btn = document.getElementById('new-request-btn');
        if (btn) btn.hidden = senders.length === 0;
        if (!senders.length && rightPane === 'send') window.closeRightPane();
        showDetailActions(!!currentDetailTx);
    }

    function senderByName(name) {
        return senders.find(function(s) { return s.name === name; }) || senders[0];
    }

    function trimBase(base) {
        return base.replace(/\/+$/, '');
    }

    // The same rule as the server: an absolute URL is used as entered, and a
    // relative one is appended to the sender's base URL, path and all.
    function resolveSendUrl(url, sender) {
        if (!url) return { error: false, text: 'Enter a path such as /items, or an absolute URL.' };
        if (/^https?:\/\//i.test(url)) return { url: url, absolute: true };
        if (!sender || !sender.baseUrl) {
            return { error: true, text: 'The sender "' + (sender ? sender.name : '') + '" has no base URL. Enter an absolute URL, or choose a sender with a base URL.' };
        }
        return { url: trimBase(sender.baseUrl) + '/' + url.replace(/^\/+/, '') };
    }

    // The part of url after base, or null when url is not under base: the next
    // character must start a path or a query, so https://api.example.com is not
    // the base of https://api.example.com.evil.
    function pathUnderBase(url, base) {
        var b = trimBase(base);
        var next = url.charAt(b.length);
        if (url.indexOf(b) !== 0 || !(next === '' || next === '/' || next === '?')) return null;
        return url.substring(b.length) || '/';
    }

    // The sender whose base URL the captured URL is under, the longest one first;
    // otherwise one that takes absolute URLs, or the first.
    function senderForUrl(url) {
        var best = null;
        senders.forEach(function(s) {
            if (s.baseUrl && pathUnderBase(url, s.baseUrl) !== null && (!best || s.baseUrl.length > best.baseUrl.length)) best = s;
        });
        return best || senders.find(function(s) { return !s.baseUrl; }) || senders[0];
    }

    function blankDraft() {
        return { sender: senders[0].name, method: 'GET', url: '', headers: [], body: '', from: null, notes: [] };
    }

    function draftFromTransaction(tx) {
        var url = tx.url || '';
        var sender = senderForUrl(url);
        var relative = sender.baseUrl ? pathUnderBase(url, sender.baseUrl) : null;
        var notes = [];
        var headers = [];
        var reqHeaders = tx.requestHeaders || {};
        Object.keys(reqHeaders).forEach(function(k) {
            if (TRANSPORT_HEADERS.indexOf(k.toLowerCase()) < 0) headers.push({ key: k, value: String(reqHeaders[k]) });
        });
        var body = '';
        if (tx.requestBodyBinary) {
            notes.push({ warn: true, text: 'The captured body is binary, and the form has no place for it: the request goes without a body.' });
        } else if (tx.requestBody) {
            body = tx.requestBody;
            if (tx.requestBodyTruncated) notes.push({ warn: true, text: 'The capture cut the body short. The form has only the captured part.' });
        }
        if (body && tx.requestContentType && !headers.some(function(h) { return h.key.toLowerCase() === 'content-type'; })) {
            headers.unshift({ key: 'Content-Type', value: tx.requestContentType });
        }
        var redacted = [url, body].concat(headers.map(function(h) { return h.value; }))
            .some(function(v) { return String(v).indexOf('[REDACTED]') >= 0; });
        if (redacted) notes.push({ warn: true, text: 'Lustro redacted values of the captured request. Replace each [REDACTED] before you send, or the server gets that text.' });
        var method = (tx.method || 'GET').toUpperCase();
        return {
            sender: sender.name,
            method: method,
            url: relative !== null ? relative : url,
            headers: headers,
            body: body,
            from: method + ' ' + url + (tx.statusCode ? ' · ' + tx.statusCode : '') + (tx.timestamp ? ' · ' + tx.timestamp : ''),
            notes: notes,
        };
    }

    function openComposer(draft) {
        sendDraft = draft;
        showRightPane('send');
        renderSendForm();
        var url = document.getElementById('sf-url');
        if (url && !draft.from) url.focus();
    }

    window.openNewRequest = function() {
        if (!senders.length) return;
        // A new request is not about any captured row, so none stays selected.
        deselectForPane();
        openComposer(sendDraft && !sendDraft.from ? sendDraft : blankDraft());
    };

    window.editAndResend = function() {
        if (!currentDetailTx || !senders.length) return;
        selectionBeforePane = null;
        openComposer(draftFromTransaction(currentDetailTx));
    };

    window.showSentTransaction = function(id) {
        selectionBeforePane = null;
        window.selectTransaction(id);
    };

    function renderSendForm() {
        var container = document.getElementById('send-form-container');
        if (!container || !sendDraft) return;
        var d = sendDraft;
        var methods = SEND_METHODS.indexOf(d.method) >= 0 ? SEND_METHODS : SEND_METHODS.concat([d.method]);
        var senderOptions = senders.map(function(s) {
            return '<option value="' + debugEscapeHtml(s.name) + '"' + (s.name === d.sender ? ' selected' : '') + '>'
                + debugEscapeHtml(s.name) + ' · ' + debugEscapeHtml(s.baseUrl || 'absolute URLs only') + '</option>';
        }).join('');
        var methodOptions = methods.map(function(m) {
            return '<option value="' + debugEscapeHtml(m) + '"' + (m === d.method ? ' selected' : '') + '>' + debugEscapeHtml(m) + '</option>';
        }).join('');
        var notes = (d.from ? [{ warn: false, text: '// Copied from the captured request. Sending it adds a new row, and the captured one stays.' }] : [])
            .concat(d.notes || [])
            .map(function(n) { return '<div class="net-send-note' + (n.warn ? ' net-form-warning' : '') + '">' + debugEscapeHtml(n.text) + '</div>'; }).join('');
        container.innerHTML = '<div class="net-composer">'
            + '<div class="net-composer-head"><h4>' + (d.from ? 'Edit and resend' : 'New request') + '</h4>'
            + (d.from ? '<span class="net-composer-from" title="' + debugEscapeHtml(d.from) + '">' + debugEscapeHtml(d.from) + '</span>' : '')
            + '<button class="dc-btn dc-btn--icon" data-action="closeRightPane" aria-label="Close the Send form" title="Close the form and show the detail again. What you entered stays for the next New request.">✕</button></div>'
            + notes
            + '<div class="net-form-row"><label class="dc-label" for="sf-sender" title="The OkHttp client to send through, under the name the app registered it with.">Sender</label>'
            + '<div class="net-form-field"><select class="dc-input--block" id="sf-sender" name="sender" data-action="setSendSender" title="Which interceptors run (auth, logging, capture) depends on how the app built the client of this sender.">' + senderOptions + '</select>'
            + '<span class="dc-comment net-send-hint">// the app\'s client: how the app built it decides auth and capture</span></div></div>'
            + '<div class="net-form-row"><label class="dc-label" for="sf-url" title="Method, and an absolute URL or a path that the sender\'s base URL takes.">Request</label>'
            + '<div class="net-form-field"><div class="net-send-line">'
            + '<select class="dc-input--block net-send-method ' + methodClass({ method: d.method }) + '" id="sf-method" name="method" aria-label="Method" data-action="setSendMethod" title="HTTP method.">' + methodOptions + '</select>'
            + '<input class="dc-input--block" id="sf-url" name="url" placeholder="/items or https://example.com/items" value="' + debugEscapeHtml(d.url) + '" data-action="updateSendField" data-field="url" title="An absolute URL is sent as entered. A path is appended to the base URL of the sender."></div>'
            + '<span class="net-send-resolved" id="sf-resolved" aria-live="polite"></span></div></div>'
            + '<div class="net-form-row"><span class="dc-label" id="sf-headers-label" title="Headers the form sends. The interceptors of the sender\'s client can add more, or change these.">Headers</span><div id="sf-headers" class="net-form-field" role="group" aria-labelledby="sf-headers-label"></div></div>'
            + '<div class="net-form-row"><label class="dc-label" for="sf-body" title="Body sent with the request.">Body <button type="button" class="net-format-btn" data-action="formatSendBody" title="Pretty-print the body as JSON (no-op if not valid JSON).">Format</button></label>'
            + '<textarea class="dc-textarea" id="sf-body" name="body" placeholder="Request body (sent for POST, PUT, PATCH, and DELETE)" data-action="updateSendField" data-field="body" title="Sent for POST, PUT, PATCH, and DELETE. Content-Type is application/json unless a header sets it.">' + debugEscapeHtml(d.body) + '</textarea></div>'
            + '<div class="net-form-actions">'
            + '<button class="dc-btn dc-btn--primary" id="sf-send-btn" data-action="submitSendRequest" title="Send through the OkHttp client of the chosen sender. Which interceptors run (auth, logging, capture) depends on how the app built that client. Requests to this debug server are rejected.">Send</button>'
            + '<span class="dc-comment net-send-hint">// Ctrl/Cmd+Enter</span>'
            + '</div>'
            + '<div id="sf-result"></div>'
            + '</div>';
        renderSendHeaders();
        renderResolvedUrl();
    }

    function renderResolvedUrl() {
        var el = document.getElementById('sf-resolved');
        if (!el || !sendDraft) return;
        var r = resolveSendUrl(sendDraft.url.trim(), senderByName(sendDraft.sender));
        el.classList.toggle('net-send-resolved--error', !!r.error);
        el.innerHTML = r.url
            ? '→ will send to <span class="net-send-resolved-url">' + debugEscapeHtml(r.url) + '</span>'
                + (r.absolute ? ' <span class="dc-comment">(absolute URL: the base URL is not used)</span>' : '')
            : debugEscapeHtml(r.text);
    }

    function renderSendHeaders() {
        var container = document.getElementById('sf-headers');
        if (!container || !sendDraft) return;
        container.innerHTML = sendDraft.headers.map(function(h, i) {
            var keyId = 'sf-header-key-' + i;
            var valueId = 'sf-header-value-' + i;
            return '<div class="net-send-header-row">'
                + '<input class="dc-input--block" id="' + keyId + '" name="headerKey" type="text" aria-label="Header name" placeholder="Header" value="' + debugEscapeHtml(h.key) + '" data-action="updateSendHeader" data-index="' + i + '" data-field="key" title="Header name (e.g. Content-Type, Accept).">'
                + '<input class="dc-input--block" id="' + valueId + '" name="headerValue" type="text" aria-label="Header value" placeholder="Value" value="' + debugEscapeHtml(h.value) + '" data-action="updateSendHeader" data-index="' + i + '" data-field="value" title="Header value.">'
                + '<button type="button" class="dc-btn dc-btn--icon dc-btn--danger" data-action="removeSendHeader" data-index="' + i + '" aria-label="Remove header row" title="Remove this header row.">✕</button>'
                + '</div>';
        }).join('')
            + '<button type="button" class="net-format-btn net-add-header" data-action="addSendHeader" title="Add another header row.">+ Add header</button>';
    }

    window.updateSendHeader = function(i, field, value) {
        if (sendDraft && sendDraft.headers[i]) sendDraft.headers[i][field] = value;
    };
    window.updateSendField = function(field, value) {
        if (!sendDraft) return;
        sendDraft[field] = value;
        if (field === 'url') renderResolvedUrl();
    };
    window.setSendSender = function(name) {
        if (!sendDraft) return;
        sendDraft.sender = name;
        renderResolvedUrl();
    };
    window.setSendMethod = function(el) {
        if (!sendDraft) return;
        sendDraft.method = el.value;
        el.className = 'dc-input--block net-send-method ' + methodClass({ method: el.value });
    };
    window.addSendHeader = function() {
        if (!sendDraft) return;
        sendDraft.headers.push({ key: '', value: '' });
        renderSendHeaders();
    };
    window.removeSendHeader = function(i) {
        if (!sendDraft) return;
        sendDraft.headers.splice(i, 1);
        renderSendHeaders();
    };

    window.formatSendBody = function() {
        var ta = document.getElementById('sf-body');
        if (!ta || !ta.value.trim()) return;
        // Indents the body without rewriting a value of it — a round-trip through
        // JSON.parse would edit the request before it was ever sent.
        var pieces = window.debugScanJsonSource(ta.value, 2);
        if (!pieces) {
            debugToast('Body is not valid JSON', 'warning');
            return;
        }
        ta.value = pieces.map(function(piece) { return piece.text; }).join('');
        if (sendDraft) sendDraft.body = ta.value;
    };

    function renderSendResult(data, failure, headline) {
        var el = document.getElementById('sf-result');
        if (!el) return;
        var line = function(text) { return '<div class="net-send-result-line">' + text + '</div>'; };
        if (failure) {
            el.className = 'net-send-result error';
            el.innerHTML = '<div class="net-send-result-status">' + debugEscapeHtml(headline || 'Not sent') + '</div>' + line(debugEscapeHtml(failure));
            return;
        }
        var where = data.url ? line('Sent to ' + debugEscapeHtml(data.url) + (data.sender ? ' through "' + debugEscapeHtml(data.sender) + '"' : '')) : '';
        if (!data.ok) {
            // The call itself failed: no response, so no status.
            el.className = 'net-send-result error';
            el.innerHTML = '<div class="net-send-result-status">No response</div>' + line(debugEscapeHtml(data.error || 'The send failed')) + where;
            return;
        }
        var code = data.statusCode;
        el.className = 'net-send-result ' + (code < 300 ? 'ok' : (code < 400 ? 'redirect' : 'error'));
        var captured = data.transactionId
            ? line('<button type="button" class="net-format-btn" data-action="showSentTransaction" data-tx-id="' + debugEscapeHtml(data.transactionId) + '" title="Close the form and show the captured request and its response.">Show the response →</button>')
            : line('Not in the list: the client of this sender has no Lustro interceptor, or capture is paused or filtered.');
        el.innerHTML = '<div class="net-send-result-status">HTTP ' + debugEscapeHtml(String(code)) + (STATUS_TEXT[code] ? ' ' + STATUS_TEXT[code] : '') + '</div>'
            + where + captured;
    }

    window.submitSendRequest = function() {
        if (!sendDraft) return;
        var d = sendDraft;
        var url = d.url.trim();
        var resolved = resolveSendUrl(url, senderByName(d.sender));
        if (!resolved.url) { renderSendResult(null, resolved.text); return; }
        var headers = {};
        d.headers.forEach(function(h) {
            if (h.key.trim()) headers[h.key.trim()] = h.value;
        });
        var btn = document.getElementById('sf-send-btn');
        if (btn) btn.disabled = true;
        var resultEl = document.getElementById('sf-result');
        if (resultEl) { resultEl.className = ''; resultEl.innerHTML = '<div class="net-rule-hits">Sending…</div>'; }
        // SYNCHRONOUS: the server blocks for the sender's result and replies
        // { transactionId, sender, url, statusCode, ok, error? }.
        debugFetch(netUrl('send'), {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ sender: d.sender, method: d.method, url: url, headers: headers, body: d.body }),
        }).then(function(r) { return r.json(); })
          .then(function(data) { renderSendResult(data || {}, null); })
          .catch(function(e) {
              // A rejected send (an unknown sender, a timeout) answers with the error
              // envelope. A 504 went out: the app cancels its call after the timeout.
              var headline = e && e.status === 504 ? 'Timed out' : 'Not sent';
              var show = function(body) { renderSendResult(null, (body && body.message) || e.message, headline); };
              if (e && e.response) e.response.json().then(show, function() { show(null); }); else show(null);
          })
          .then(function() {
              var b = document.getElementById('sf-send-btn');
              if (b) b.disabled = false;
          });
    };

    window.formatRuleBody = function() {
        var ta = document.getElementById('rf-body');
        if (!ta) return;
        var raw = ta.value;
        if (!raw.trim()) return;
        // As in formatSendBody: indent only, so the rule still serves the body as
        // it was written.
        var pieces = window.debugScanJsonSource(raw, 2);
        if (!pieces) {
            debugToast('Body is not valid JSON', 'warning');
            return;
        }
        ta.value = pieces.map(function(piece) { return piece.text; }).join('');
    };

    window.submitRule = function() {
        var idEl = document.getElementById('rf-id');
        var rule = {
            id: idEl ? idEl.value : '',
            name: document.getElementById('rf-name').value,
            urlPattern: document.getElementById('rf-pattern').value,
            method: document.getElementById('rf-method').value || null,
            statusCode: parseInt(document.getElementById('rf-status').value) || 200,
            responseBody: document.getElementById('rf-body').value,
        };
        if (!rule.urlPattern) { debugToast('URL pattern is required', 'warning'); return; }
        var wasEdit = !!rule.id;
        // An add replaces the whole rule, and the form holds only part of it.
        // Carry the fields it doesn't show over from the rule being edited, or a
        // disabled rule would come back enabled and lose its response headers.
        var edited = wasEdit ? loadedRules.find(function(r) { return r.id === rule.id; }) : null;
        if (edited) {
            rule.enabled = edited.enabled !== false;
            rule.responseHeaders = edited.responseHeaders || {};
        } else if (pendingRuleHeaders) {
            rule.responseHeaders = pendingRuleHeaders;
        }
        debugFetch(netUrl('rules'), {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(rule),
        }).then(function() {
            debugToast(wasEdit ? 'Rule updated' : 'Rule added', 'success');
            editingRuleId = null;
            renderRuleForm();
            reloadAfterRuleChange();
        }).catch(reportRuleSaveFailure);
    };

    // A rule the app won't serve — a status out of range, say — comes back as the
    // error envelope. Show what it says; "HTTP 400" alone leaves nothing to fix.
    function reportRuleSaveFailure(e) {
        var fallback = function() { debugToast('Failed to save rule: ' + e.message, 'error'); };
        if (!e || !e.response) { fallback(); return; }
        e.response.json()
            .then(function(body) {
                if (body && body.message) debugToast('Failed to save rule: ' + body.message, 'error');
                else fallback();
            })
            .catch(fallback);
    }

    window.toggleRule = function(id) {
        debugFetch(netUrl('rules/toggle'), {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({id: id}),
        }).then(reloadAfterRuleChange)
          .catch(function(e) { debugToast('Failed to toggle rule: ' + e.message, 'error'); });
    };

    window.deleteRule = function(id) {
        debugFetch(netUrl('rules/delete'), {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({id: id}),
        }).then(function() {
            debugToast('Rule deleted', 'info');
            reloadAfterRuleChange();
        }).catch(function(e) { debugToast('Failed to delete rule: ' + e.message, 'error'); });
    };

    // The rules for the selected request, with a new one filled in from it. The
    // row stays selected.
    window.mockThis = function() {
        var tx = currentDetailTx;
        if (!tx || tx.id !== selectedTxId || tx.source === 'platform') return;
        var url = tx.url || '';
        var contentType = headerValue(tx.responseHeaders, 'content-type');
        editingRuleId = null;
        openRulesPane(tx.id);
        renderRuleForm({
            name: (tx.method || '') + ' ' + (url.length > 60 ? url.substring(0, 60) + '…' : url),
            urlPattern: exactUrlPattern(url),
            method: tx.method,
            statusCode: tx.statusCode || 200,
            responseHeaders: contentType ? { 'Content-Type': contentType } : null,
            responseBody: tx.responseBody || '',
        });
        var formEl = document.getElementById('rule-form-container');
        if (formEl && formEl.scrollIntoView) formEl.scrollIntoView({ block: 'nearest' });
    };

    // A pattern for this URL and no other: a sub-resource such as /items/1/comments
    // contains /items/1 and would match a substring. A redacted query value
    // matches any value, because the app sends the real one.
    window.netExactUrlPattern = exactUrlPattern;
    function exactUrlPattern(url) {
        var queryStart = url.indexOf('?');
        if (queryStart < 0) return 'regex:^' + escapeRegex(url) + '$';
        var pairs = url.slice(queryStart + 1).split('&').map(function(pair) {
            var eq = pair.indexOf('=');
            return eq >= 0 && isRedactedValue(pair.slice(eq + 1))
                ? escapeRegex(pair.slice(0, eq + 1)) + '[^&#]*'
                : escapeRegex(pair);
        });
        return 'regex:^' + escapeRegex(url.slice(0, queryStart)) + '\\?' + pairs.join('&') + '$';
    }
    function isRedactedValue(value) {
        try {
            return decodeURIComponent(value.replace(/\+/g, ' ')) === '[REDACTED]';
        } catch(e) {
            return false;
        }
    }
    function escapeRegex(s) {
        return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }
    function headerValue(headers, name) {
        var key = Object.keys(headers || {}).find(function(k) { return k.toLowerCase() === name; });
        return key ? headers[key] : null;
    }

    function extractPath(url) {
        try {
            var u = new URL(url);
            return u.pathname + u.search;
        } catch(e) {
            return url;
        }
    }

    function updatePauseButton() {
        var btn = document.getElementById('pause-btn');
        if (!btn) return;
        btn.textContent = isPaused ? '▶ Resume' : '⏸ Pause';
        btn.classList.toggle('dc-btn--active', isPaused);
    }

    window.togglePause = function() {
        debugFetch(netUrl('pause'), { method: 'POST' })
            .then(function(r) { return r.json(); })
            .then(function(data) {
                // Read the new state here; the next poll's envelope state reports
                // it too, even when the list is unchanged.
                if (data && data.paused !== undefined) isPaused = !!data.paused;
                else isPaused = !isPaused;
                updatePauseButton();
            })
            .catch(function(e) { debugToast('Failed to toggle pause: ' + e.message, 'error'); });
    };

    function updateOverwriteButton() {
        var btn = document.getElementById('overwrite-btn');
        if (!btn) return;
        btn.textContent = 'Overwrite: ' + (isOverwriteMode ? 'on' : 'off');
        btn.classList.toggle('dc-btn--active', isOverwriteMode);
    }

    window.toggleOverwriteMode = function() {
        debugFetch(netUrl('overwrite-mode'), { method: 'POST' })
            .then(function(r) { return r.json(); })
            .then(function(data) {
                isOverwriteMode = !!(data && data.overwriteMode);
                updateOverwriteButton();
            })
            .catch(function(e) { debugToast('Failed to toggle overwrite mode: ' + e.message, 'error'); });
    };

    function updateThrottleSelect() {
        var sel = document.getElementById('throttle-select');
        if (sel && sel.value !== String(throttleDelayMs)) sel.value = String(throttleDelayMs);
    }

    window.setThrottle = function(value) {
        var delayMs = parseInt(value, 10) || 0;
        debugFetch(netUrl('throttle'), {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({delayMs: delayMs}),
        }).then(function(r) { return r.json(); })
          .then(function(data) {
              throttleDelayMs = (data && data.delayMs) || 0;
              updateThrottleSelect();
              if (throttleDelayMs > 0) debugToast('Throttle: ' + throttleDelayMs + 'ms', 'info');
              else debugToast('Throttle off', 'info');
          })
          .catch(function(e) { debugToast('Failed to set throttle: ' + e.message, 'error'); });
    };

    // The flag shows only once the filter has left a request out of the list, so
    // a teammate who misses a request sees why; its details come from the poll state.
    function updateCaptureFilter(filter) {
        captureFilter = filter || null;
        var skipped = captureFilter ? (captureFilter.skipped || 0) : 0;
        var btn = document.getElementById('capture-filter-btn');
        if (btn) {
            var label = 'Skipped by the app filters: ' + skipped;
            btn.hidden = skipped <= 0;
            btn.title = label;
            btn.setAttribute('aria-label', label);
        }
        if (captureFilterModal && captureFilterModal.isVisible()) renderCaptureFilterDetails();
    }

    function renderCaptureFilterDetails() {
        var f = captureFilter || { description: '', skipped: 0, failed: 0 };
        var set = function(id, text) {
            var el = document.getElementById(id);
            if (el) el.textContent = text;
        };
        set('capture-filter-description', f.description || '');
        set('capture-filter-skipped', String(f.skipped || 0));
        set('capture-filter-failed', String(f.failed || 0));
    }

    window.showCaptureFilter = function() {
        if (!captureFilterModal) captureFilterModal = window.debugModal('capture-filter-modal');
        renderCaptureFilterDetails();
        captureFilterModal.show();
    };

    window.closeCaptureFilter = function() {
        if (captureFilterModal) captureFilterModal.hide();
    };

    window.clearTraffic = function() {
        debugFetch(netUrl('clear'), {method: 'POST'}).then(function() {
            allTransactions = [];
            selectedTxId = null;
            replacedAt = {};
            lastCursor = null;
            // Clearing restarts the filter's counts on the server as well.
            if (captureFilter) updateCaptureFilter(Object.assign({}, captureFilter, { skipped: 0, failed: 0 }));
            renderList();
            currentDetailTx = null;
            clearWebSockets();
            showEmptyDetail();
        });
    };

    // GET transactions?cursor=<opaque>&search=<q> →
    //   { cursor, status: "delta"|"unchanged"|"reset", items?,
    //     state: {paused, overwriteMode, throttleDelayMs, captureFilter} }
    // The cursor is opaque: we echo back the last one we received (omitted on the
    // first poll). `unchanged` carries no items and is a no-op; `reset` replaces
    // the whole list; `delta` carries the authoritative current list. Any UNKNOWN
    // status is treated as `reset`. The cursor advances only when the list changes;
    // `state` comes with every response, `unchanged` included.
    function pollUrl() {
        var url = netUrl('transactions');
        var params = [];
        if (lastCursor != null) params.push('cursor=' + encodeURIComponent(lastCursor));
        if (searchText) params.push(searchParams(searchText, searchTextRegex, ''));
        if (params.length) url += '?' + params.join('&');
        return url;
    }

    function applyTransactionList(items) {
        var list = items || [];
        // A list for another search has other rows, not new ones.
        if (listSearchGen === searchGen) noteReplacements(list);
        listSearchGen = searchGen;
        var previousSelected = selectedTxId ? findTransactionById(allTransactions, selectedTxId) : null;
        var nextSelected = selectedTxId ? findTransactionById(list, selectedTxId) : null;
        var refreshSelected = selectedBriefChanged(previousSelected, nextSelected) || isStreaming(nextSelected);
        allTransactions = list;
        if (discoverCategories(allTransactions)) {
            buildCategoryFilters();
            updateCategoryPills();
        }
        renderList();
        if (refreshSelected) scheduleSelectedDetailRefresh();
    }

    // Overwrite mode removes the earlier copies of a request when the app sends
    // it again, so the selected row, the row a pane put aside, and the request
    // the rules are filtered to can leave the list. Each moves to the copy that
    // replaced it, and a row that replaced copies is highlighted for a moment.
    function noteReplacements(list) {
        var previousIds = idSet(allTransactions);
        var ids = idSet(list);
        var follow = function(id) {
            if (!id || ids[id]) return id;
            var next = replacementFor(lastKnownTransaction(id), list, previousIds);
            return next ? next.id : id;
        };
        var selected = follow(selectedTxId);
        if (selected !== selectedTxId) {
            selectedTxId = selected;
            detailContinuesTo = selected;
        }
        selectionBeforePane = follow(selectionBeforePane);
        rulesFilterTxId = follow(rulesFilterTxId);

        var now = Date.now();
        Object.keys(replacedAt).forEach(function(id) {
            if (now - replacedAt[id] >= REPLACED_FLASH_MS) delete replacedAt[id];
        });
        list.forEach(function(tx) {
            if (tx.replacedCount > 0 && !previousIds[tx.id]) replacedAt[tx.id] = now;
        });
    }

    // The request that took the place of [gone]: a new row with the same method
    // and path, compared as overwrite mode compares them, that replaced earlier
    // copies and started no earlier. The list is newest first, so the newest.
    // A row is new when it isn't in previousIds. A poll can see a copy removed
    // before the one that replaced it is listed, so this also finds it a poll later.
    window.netReplacementFor = replacementFor;
    function replacementFor(gone, list, previousIds) {
        if (!gone) return null;
        var key = overwriteKey(gone);
        for (var i = 0; i < list.length; i++) {
            var tx = list[i];
            if (tx.replacedCount > 0 && !previousIds[tx.id] && overwriteKey(tx) === key
                && !(tx.startedAt < gone.startedAt)) return tx;
        }
        return null;
    }

    function overwriteKey(tx) {
        return String(tx.method || '').toUpperCase() + ' ' + extractPath(tx.url || '').split('?')[0];
    }

    // A request that overwrite mode removed is no longer in the list, but the detail can still show it.
    function lastKnownTransaction(id) {
        return findTransactionById(allTransactions, id)
            || (currentDetailTx && currentDetailTx.id === id ? currentDetailTx : null);
    }

    function idSet(list) {
        var ids = {};
        list.forEach(function(tx) { ids[tx.id] = true; });
        return ids;
    }

    function handlePollData(data) {
        if (!data) return;
        var status = data.status;

        // The control state moved from top-level fields into `state`.
        var state = data.state || {};
        if (state.paused !== undefined && !!state.paused !== isPaused) {
            isPaused = !!state.paused;
            updatePauseButton();
        }
        if (state.overwriteMode !== undefined && !!state.overwriteMode !== isOverwriteMode) {
            isOverwriteMode = !!state.overwriteMode;
            updateOverwriteButton();
        }
        if (state.throttleDelayMs !== undefined && state.throttleDelayMs !== throttleDelayMs) {
            throttleDelayMs = state.throttleDelayMs || 0;
            updateThrottleSelect();
        }
        updateCaptureFilter(state.captureFilter);
        updateMocksButton(state.mockRules);

        if (status === 'unchanged') {
            // No items; nothing to render. (Defensive: if items happen to be
            // present, ignore them — unchanged means the list did not change.)
        } else if (status === 'delta') {
            // The server returns the authoritative current list on change. If
            // items are absent, treat as unchanged (no list mutation).
            if (data.items !== undefined && data.items !== null) {
                applyTransactionList(data.items);
            }
        } else {
            // 'reset' OR any unknown/missing status → replace the whole list.
            applyTransactionList(data.items || []);
        }

        // Persist the returned cursor for the next poll (only if present).
        if (data.cursor !== undefined && data.cursor !== null) {
            lastCursor = data.cursor;
        }
    }

    var pollSearchGen = searchGen;
    function startPolling() {
        debugPoll(function() {
            pollSearchGen = searchGen;
            return pollUrl();
        }, 1500, function(data) {
            if (pollSearchGen === searchGen) handlePollData(data);
        }, function(status, envelope) {
            if (status !== 400 || !envelope || envelope.field !== 'search') return false;
            if (pollSearchGen === searchGen) dropSearch(envelope.message);
            return true;
        });
    }

    // A search the app accepted can stop being one it can run, as when a large
    // body arrives for a slow regex. Each poll would run it again, so the list
    // goes back to every request, and the error under the field says why.
    function dropSearch(message) {
        searchText = '';
        searchTextRegex = false;
        lastCursor = null;
        searchGen++;
        resetDisplayLimit();
        showSearchError('search-input', (message || 'The search stopped.') + ' The list shows every request until you change the search.');
        if (currentDetailTx && trafficView === 'http') renderDetail(currentDetailTx);
    }

    // ── WebSockets ──
    // A second list in the left pane. A connection's detail is its summary, its
    // log (messages and lifecycle events, in order), and the payload of the
    // message selected in the log.
    //
    // GET websockets?cursor= is a cursor envelope, like transactions.
    // GET websockets/{id}/events?cursor= is a stream envelope: `reset` carries the
    // end of the log, `delta` only the events after the cursor, and `dropped`
    // counts the events the log evicted before this page got them. Any UNKNOWN
    // status is treated as `reset`.
    var trafficView = 'http';
    var wsConnections = [];
    var wsCursor = null;
    var wsSelectedId = null;
    var wsDetail = null;          // the selected connection, with its headers
    var wsEvents = [];            // the selected connection's log, oldest first
    var wsEventsCursor = null;
    var wsMissed = 0;             // events evicted before this page got them
    var wsDirection = '';         // '', 'sent', or 'received'
    var wsSearch = '';            // the search the log shows: the last one the app accepted
    var wsSearchRegex = false;    // whether wsSearch is a regex
    var wsSearchTimer = null;
    var wsSearchRetryTimer = null;
    var wsSearchRequest = 0;
    var wsSelectedSeq = null;
    var wsJsonView = 'tree';
    var wsEventsTimer = null;
    var wsEventsRequest = 0;
    var wsHeadShown = null;       // the connection the summary shows, as JSON
    var WS_EVENTS_POLL_MS = 1000;
    // The page keeps this many events of a log; the app's own limit is 1000 by default.
    var WS_MAX_ROWS = 5000;
    var WS_MARKDOWN_EVENTS = 100;

    function showEmptyDetail() {
        var dc = document.getElementById('detail-content');
        if (dc) {
            dc.innerHTML = '<div class="net-empty-state"><div class="net-empty-icon">🔍</div><p>'
                + (trafficView === 'ws' ? 'Select a connection to inspect' : 'Select a request to inspect') + '</p></div>';
        }
        showDetailActions(false);
    }

    function updateTrafficCount() {
        var badge = document.getElementById('ws-count');
        if (badge) badge.textContent = wsConnections.length ? ' ' + wsConnections.length : '';
        if (trafficView !== 'ws') return;
        var countEl = document.getElementById('tx-count');
        var open = wsConnections.filter(function(c) { return c.state === 'open'; }).length;
        if (countEl) countEl.textContent = wsConnections.length + ' connection' + (wsConnections.length === 1 ? '' : 's') + ', ' + open + ' open';
    }

    window.switchTrafficView = function(view) {
        trafficView = view === 'ws' ? 'ws' : 'http';
        // A request's detail that is still on its way must not land in the pane of the other view.
        detailRequestSeq++;
        clearTimeout(detailRefreshTimer);
        detailRefreshTimer = null;
        var root = document.getElementById('net-root');
        if (root) root.dataset.view = trafficView;
        document.querySelectorAll('.net-view-seg .dc-seg__item').forEach(function(b) {
            b.classList.toggle('dc-seg__item--active', b.dataset.view === trafficView);
        });
        showRightPane('detail');
        if (trafficView === 'ws') {
            updateTrafficCount();
            renderWsList();
            if (wsSelectedId) { renderWsFrame(); pollWsEvents(); } else showEmptyDetail();
        } else {
            clearTimeout(wsEventsTimer);
            renderList();
            if (selectedTxId && currentDetailTx && currentDetailTx.id === selectedTxId) renderDetail(currentDetailTx); else showEmptyDetail();
            // The request may have changed while the other view was shown.
            if (selectedTxId && findTransactionById(allTransactions, selectedTxId)) loadTransactionDetail(selectedTxId);
        }
    };

    function deselectWebSocket() {
        wsSelectedId = null;
        wsDetail = null;
        wsEvents = [];
        wsEventsCursor = null;
        wsSelectedSeq = null;
        clearTimeout(wsEventsTimer);
    }

    function clearWebSockets() {
        wsConnections = [];
        wsCursor = null;
        deselectWebSocket();
        renderWsList();
        updateTrafficCount();
    }

    function wsStateLabel(c) {
        if (c.state === 'closed' && c.closeCode != null) return 'closed ' + c.closeCode;
        if (c.state === 'failed' && c.canceled) return 'canceled';
        return c.state || '';
    }

    function wsStateClass(c) {
        return 'net-ws-state net-ws-state--' + debugEscapeHtml(c.state || '');
    }

    function findWebSocket(id) {
        for (var i = 0; i < wsConnections.length; i++) if (wsConnections[i].id === id) return wsConnections[i];
        return null;
    }

    function renderWsList() {
        var tbody = document.getElementById('ws-list');
        if (!tbody) return;
        if (!wsConnections.length) {
            tbody.innerHTML = '<tr><td class="dc-cell net-ws-empty" colspan="4">No WebSocket yet. A socket is listed when the app creates it with '
                + 'the factory from Lustro.webSocketFactory(okHttpClient).</td></tr>';
            return;
        }
        tbody.innerHTML = wsConnections.map(function(c) {
            var sel = c.id === wsSelectedId ? ' dc-row--selected' : '';
            return '<tr class="dc-row' + sel + '" data-action="selectWebSocket" data-ws-id="' + debugEscapeHtml(c.id) + '">'
                + '<td class="dc-cell ' + wsStateClass(c) + '">' + debugEscapeHtml(wsStateLabel(c)) + '</td>'
                + '<td class="dc-cell net-cell-url" title="' + debugEscapeHtml(c.url || '') + '">' + debugEscapeHtml(c.url || '') + '</td>'
                + '<td class="dc-cell net-ws-counts">↑ ' + c.sentCount + '   ↓ ' + c.receivedCount + '</td>'
                + '<td class="dc-cell net-cell-time">' + debugEscapeHtml(c.timestamp || '') + '</td>'
                + '</tr>';
        }).join('');
    }

    function applyWebSockets(items) {
        var previous = wsSelectedId ? findWebSocket(wsSelectedId) : null;
        wsConnections = items || [];
        renderWsList();
        updateTrafficCount();
        if (trafficView !== 'ws' || !wsSelectedId) return;
        var next = findWebSocket(wsSelectedId);
        if (!next) return;
        renderWsHead(next);
        // The headers of the response arrive with the open event.
        if (!previous || previous.state !== next.state) loadWsDetail();
    }

    function startWsPolling() {
        debugPoll(function() {
            return netUrl('websockets') + (wsCursor != null ? '?cursor=' + encodeURIComponent(wsCursor) : '');
        }, 1500, function(data) {
            if (!data) return;
            if (data.status === 'unchanged') {
                // No items; nothing to render.
            } else if (data.status === 'delta') {
                if (data.items !== undefined && data.items !== null) applyWebSockets(data.items);
            } else {
                applyWebSockets(data.items || []);
            }
            if (data.cursor !== undefined && data.cursor !== null) wsCursor = data.cursor;
        });
    }

    window.selectWebSocket = function(id) {
        deselectWebSocket();
        wsSelectedId = id;
        wsMissed = 0;
        wsSearch = '';
        wsSearchRegex = false;
        clearTimeout(wsSearchTimer);
        clearTimeout(wsSearchRetryTimer);
        wsSearchRequest++;
        renderWsList();
        showRightPane('detail');
        renderWsFrame();
        loadWsDetail();
        pollWsEvents();
    };

    window.openWebSocket = function(id) {
        window.switchTrafficView('ws');
        window.selectWebSocket(id);
    };

    window.showWsHandshake = function() {
        var c = findWebSocket(wsSelectedId);
        if (!c || !c.transactionId) return;
        if (!findTransactionById(allTransactions, c.transactionId)) {
            debugToast('The handshake request is no longer captured', 'warning');
            return;
        }
        window.switchTrafficView('http');
        window.selectTransaction(c.transactionId);
    };

    // The parts of the detail that stay while the log grows: each part is
    // updated on its own, so a new message doesn't reset the scroll position
    // or the payload on screen.
    function renderWsFrame() {
        var el = document.getElementById('detail-content');
        if (!el) return;
        showDetailActions(false);
        // The whole pane is new, so nothing that was copied or dumped for the old one is needed.
        copyStore = {};
        hexDumps = {};
        var dirs = [['', 'All'], ['sent', 'Sent'], ['received', 'Received']];
        el.innerHTML = '<div class="net-ws-detail">'
            + '<div class="net-detail-header" id="ws-head"></div>'
            + '<div id="ws-headers"></div>'
            + '<div class="net-ws-bar">'
            + '<div class="dc-seg dc-seg--sm" role="group" aria-label="Direction of the messages">'
            + dirs.map(function(d) {
                return '<button class="dc-seg__item' + (d[0] === wsDirection ? ' dc-seg__item--active' : '') + '" data-action="setWsDirection" data-dir="' + d[0] + '">' + d[1] + '</button>';
            }).join('')
            + '</div>'
            + '<div class="dc-field net-ws-search"><label class="net-search-label" for="ws-search"><span class="dc-field__prefix" aria-hidden="true">&gt;</span>'
            + '<input type="text" id="ws-search" name="wsSearch" aria-label="Search the messages" aria-describedby="ws-search-error" class="dc-input" placeholder=""'
            + ' autocomplete="off" spellcheck="false" data-action="onWsSearchInput" value="' + debugEscapeHtml(wsSearch) + '"'
            + ' title="Show only the text messages that contain this text, ignoring case, on the device, 300 ms after you stop typing. With REGEX on, each word is a regular expression: all must match, and -word leaves out. Escape clears the field."></label>'
            + '<button type="button" class="dc-btn dc-btn--icon net-search-clear" data-action="clearSearch" data-target="ws-search"' + (wsSearch ? '' : ' hidden')
            + ' aria-label="Clear the search" title="Clear the search. Escape in the field does the same.">×</button>'
            + '<button type="button" class="dc-btn dc-btn--sm net-search-regex" data-action="toggleSearchRegex" data-target="ws-search" aria-pressed="false"'
            + ' title="Regular expressions. On: each word is a Java regular expression that ignores case, all words must match, and -word leaves out, such as ping|pong -heartbeat. Off: the text is found as typed. Remembered in your browser.">Regex</button>'
            + '<button type="button" class="dc-btn dc-btn--icon net-search-help" data-action="showSearchHelp" aria-label="Regex examples"'
            + ' title="Common regular expressions, with examples.">?</button></div>'
            + '<button class="dc-btn dc-btn--sm" data-action="copyWsMarkdown" title="Copy the connection and its last ' + WS_MARKDOWN_EVENTS
            + ' events as Markdown, for a bug report, a pull request, or a chat.">Markdown</button>'
            + '<button class="dc-btn dc-btn--sm" id="ws-har-btn" data-action="exportWsHar" title="Save the handshake request and the messages as a HAR file, which Chrome DevTools imports with the messages.">Export HAR</button>'
            + '</div>'
            + '<div class="net-search-error" id="ws-search-error" role="alert" hidden></div>'
            + '<div id="ws-log-note" class="dc-comment net-ws-log-note"></div>'
            + '<div id="ws-gone-note" class="dc-comment net-ws-log-note" hidden>// The app no longer has this connection: it was cleared, or it passed the connection limit. These are the events this page got.</div>'
            + '<div class="net-ws-log" id="ws-log"><table class="dc-table"><tbody id="ws-events"></tbody></table></div>'
            + '<div class="net-ws-payload" id="ws-payload"><div class="net-empty-body">Select a message to see its payload</div></div>'
            + '</div>';
        wsHeadShown = null;
        showSearchRegex('ws-search');
        var c = findWebSocket(wsSelectedId);
        if (c) renderWsHead(c);
        renderWsHeaders();
        renderWsEvents();
        // The view comes back with the message that was selected in it.
        if (wsSelectedSeq != null) window.selectWsEvent(wsSelectedSeq);
    }

    function renderWsHead(c) {
        var el = document.getElementById('ws-head');
        if (!el) return;
        // Every poll with a new message comes here. Only a change is rendered, so
        // a text selection in the summary stays, and no copy button is made again.
        var shown = JSON.stringify(c);
        if (shown === wsHeadShown) return;
        wsHeadShown = shown;
        var html = '<div class="net-detail-method-url"><span class="net-detail-method net-m-ws">WS</span> '
            + debugEscapeHtml(c.url || '') + ' ' + copyBtn(c.url || '') + '</div>';
        html += '<div class="net-detail-meta">';
        html += '<span class="' + wsStateClass(c) + '">' + debugEscapeHtml(wsStateLabel(c)) + '</span>';
        if (c.statusCode != null) html += '<span title="The status of the handshake response">HTTP ' + c.statusCode + '</span>';
        html += '<span title="When the app created the socket">' + debugEscapeHtml(c.timestamp || '') + '</span>';
        if (c.openedAt != null && c.closedAt != null) {
            html += '<span title="How long the socket was open">' + formatDuration(c.closedAt - c.openedAt) + '</span>';
        }
        html += '<span title="Messages the app sent, and their size. OkHttp queued them, which does not show that the server received them.">↑ ' + c.sentCount + ' · ' + formatBytes(c.sentBytes) + '</span>';
        html += '<span title="Messages the app received, and their size">↓ ' + c.receivedCount + ' · ' + formatBytes(c.receivedBytes) + '</span>';
        (c.categories || []).forEach(function(cat) { html += '<span class="dc-tag" data-cat="' + debugEscapeHtml(cat) + '">' + debugEscapeHtml(cat) + '</span>'; });
        if (c.transactionId) {
            html += '<button class="dc-btn dc-btn--sm" data-action="showWsHandshake" title="Show the handshake request in the HTTP list.">Handshake request</button>';
        }
        html += '</div>';
        var close = wsCloseLine(c);
        if (close) html += '<div class="net-ws-close">' + debugEscapeHtml(close) + '</div>';
        if (c.error) html += '<div class="net-error-line">' + debugEscapeHtml(c.error) + '</div>';
        var har = document.getElementById('ws-har-btn');
        if (har) har.disabled = !c.transactionId;
        var note = document.getElementById('ws-log-note');
        keepWsLogEnd(function() {
            replacePart(el, html);
            if (note) {
                var parts = [];
                if (c.evictedEvents) parts.push(c.evictedEvents + ' older events are past the log limit');
                if (c.droppedEvents) parts.push(c.droppedEvents + ' messages were not captured, because capture was behind');
                note.textContent = parts.length ? '// ' + parts.join('; ') : '';
            }
        });
    }

    function wsCloseLine(c) {
        if (c.closeCode == null) return '';
        return 'Close ' + c.closeCode
            + (c.closedBy ? ', started by the ' + (c.closedBy === 'app' ? 'app' : 'server') : '')
            + (c.closeReason ? ': ' + c.closeReason : '');
    }

    function formatDuration(ms) {
        if (ms < 1000) return ms + 'ms';
        if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
        return Math.floor(ms / 60000) + 'm ' + Math.floor(ms % 60000 / 1000) + 's';
    }

    function loadWsDetail() {
        var id = wsSelectedId;
        if (!id) return;
        debugFetch(netUrl('websockets/' + encodeURIComponent(id)))
            .then(function(r) { return r.json(); })
            .then(function(detail) {
                if (id !== wsSelectedId) return;
                wsDetail = detail;
                renderWsHeaders();
            })
            .catch(function() { /* the next state change loads it again */ });
    }

    function renderWsHeaders() {
        var el = document.getElementById('ws-headers');
        if (!el) return;
        var html = '';
        [['Response', wsDetail && wsDetail.responseHeaders], ['Request', wsDetail && wsDetail.requestHeaders]].forEach(function(part) {
            var headers = part[1];
            var keys = headers ? Object.keys(headers) : [];
            if (!keys.length) return;
            html += '<details class="net-headers-details"' + (headersOpen() ? ' open' : '') + '>'
                + '<summary title="The headers of the handshake. Expand or collapse them; the choice is remembered in your browser.">'
                + part[0] + ': ' + keys.length + ' header' + (keys.length === 1 ? '' : 's') + '</summary>'
                + '<table class="net-headers-table">' + keys.map(function(k) {
                    return '<tr class="net-header-row"><td class="net-header-key">' + debugEscapeHtml(k)
                        + '</td><td class="net-header-value">' + debugEscapeHtml(headers[k]) + '</td></tr>';
                }).join('') + '</table></details>';
        });
        keepWsLogEnd(function() { el.innerHTML = html; });
    }

    function pollWsEvents() {
        clearTimeout(wsEventsTimer);
        var id = wsSelectedId;
        if (!id || trafficView !== 'ws') return;
        var request = ++wsEventsRequest;
        var url = wsEventsUrl(id, wsEventsCursor, wsSearch, wsSearchRegex);
        // Not debugFetch: a 404 here means that the app cleared or evicted the
        // connection, which must not show the console as disconnected. The poll
        // goes on, because a socket that is still open is listed again with its
        // next message.
        fetch(url)
            .then(function(r) {
                if (request !== wsEventsRequest || id !== wsSelectedId) return null;
                setWsGone(r.status === 404);
                if (r.status === 400 && wsSearch) {
                    return r.json().catch(function() { return null; }).then(function(envelope) {
                        if (envelope && envelope.field === 'search' && request === wsEventsRequest && id === wsSelectedId) {
                            dropWsSearch(envelope.message);
                        }
                        return null;
                    });
                }
                return r.ok ? r.json() : null;
            })
            .then(function(data) {
                if (data && request === wsEventsRequest && id === wsSelectedId) applyWsEvents(data);
            })
            .catch(function() { /* the app is in the background; the transactions poll shows that */ })
            .then(function() {
                if (request === wsEventsRequest) wsEventsTimer = setTimeout(pollWsEvents, WS_EVENTS_POLL_MS);
            });
    }

    function wsEventsUrl(id, cursor, search, regex) {
        return netUrl('websockets/' + encodeURIComponent(id) + '/events?limit=' + WS_MAX_ROWS)
            + (cursor != null ? '&cursor=' + encodeURIComponent(cursor) : '')
            + searchParams(search, regex, '&');
    }

    // As dropSearch: the log goes back to every event, from its end.
    function dropWsSearch(message) {
        wsSearch = '';
        wsSearchRegex = false;
        wsEvents = [];
        wsEventsCursor = null;
        wsMissed = 0;
        showSearchError('ws-search', (message || 'The search stopped.') + ' The log shows every event until you change the search.');
        renderWsEvents();
    }

    function setWsGone(gone) {
        var note = document.getElementById('ws-gone-note');
        if (note && note.hidden === gone) keepWsLogEnd(function() { note.hidden = !gone; });
    }

    function applyWsEvents(data) {
        if (!data || data.status === 'unchanged') return;
        if (data.cursor !== undefined && data.cursor !== null) wsEventsCursor = data.cursor;
        if (data.status !== 'delta') {
            // 'reset' or any unknown status: this is the log now.
            wsEvents = (data.items || []).slice(-WS_MAX_ROWS);
            wsMissed = 0;
            renderWsEvents();
            return;
        }
        var added = data.items || [];
        if (data.dropped) wsMissed += data.dropped;
        wsEvents = wsEvents.concat(added);
        // The page keeps the end of a long log.
        var removed = wsEvents.splice(0, Math.max(0, wsEvents.length - WS_MAX_ROWS));
        appendWsEvents(added, removed);
    }

    function oneLine(text) {
        return String(text).replace(/\s+/g, ' ');
    }

    function wsEventText(e) {
        var codeAndReason = (e.code != null ? e.code : '') + (e.reason ? ' ' + e.reason : '');
        switch (e.kind) {
            case 'open': return 'Open' + (e.statusCode != null ? ' · HTTP ' + e.statusCode : '');
            case 'close':
                return e.direction === 'sent'
                    ? 'The app called close(' + codeAndReason.trim() + ')' + (e.enqueued === false ? ', which returned false' : '')
                    : 'Close frame from the server · ' + codeAndReason;
            case 'closed': return 'Closed · ' + codeAndReason;
            case 'cancel': return 'The app called cancel()';
            case 'failure': return 'Failure' + (e.statusCode != null ? ' · HTTP ' + e.statusCode : '') + ' · ' + (e.error || '');
            default: return e.kind || '';
        }
    }

    function spacedHex(hex) {
        return String(hex || '').replace(/(..)/g, '$1 ').trim();
    }

    // One row of the log, as HTML. A payload is content from the app's server,
    // so every value from an event is escaped.
    function wsEventRow(e, selectedSeq) {
        var time = '<td class="dc-cell net-cell-time net-ws-time">' + debugEscapeHtml(e.timestamp || '') + '</td>';
        if (e.kind !== 'message') {
            return '<tr class="dc-row net-ws-note-row' + (e.kind === 'failure' ? ' net-ws-note-row--failure' : '') + '">' + time
                + '<td class="dc-cell" colspan="3">' + debugEscapeHtml(wsEventText(e)) + '</td></tr>';
        }
        var sent = e.direction === 'sent';
        var binary = e.type === 'binary';
        var preview = binary ? spacedHex(e.hexPreview) : oneLine(e.preview || '');
        var badges = '';
        if (binary) badges += '<span class="dc-tag">binary</span> ';
        if (e.enqueued === false) badges += '<span class="dc-badge" style="--c: var(--danger)" title="send() returned false: OkHttp did not queue this message, so it was not sent.">not sent</span> ';
        if (e.truncated) badges += '<span class="net-truncated-label" title="The message passed the capture cap, so only its first part was kept.">Truncated</span> ';
        if (!e.stored) badges += '<span class="dc-comment">// payload not stored</span>';
        var dirTitle = sent
            ? 'Sent by the app. send() returned ' + (e.enqueued === false ? 'false' : 'true: OkHttp queued the message, which does not show that the server received it') + '.'
            : 'Received by the app.';
        return '<tr class="dc-row' + (e.seq === selectedSeq ? ' dc-row--selected' : '') + '" data-action="selectWsEvent" data-seq="' + debugEscapeHtml(String(e.seq)) + '">' + time
            + '<td class="dc-cell net-ws-dir net-ws-dir--' + (sent ? 'sent' : 'received') + '" title="' + debugEscapeHtml(dirTitle) + '">' + (sent ? '↑' : '↓') + '</td>'
            + '<td class="dc-cell net-ws-size">' + debugEscapeHtml(formatBytes(e.payloadBytes)) + '</td>'
            + '<td class="dc-cell net-ws-preview">' + badges + debugEscapeHtml(preview) + '</td></tr>';
    }
    window.netWebSocketEventRow = wsEventRow;

    function wsShown(e) {
        return !wsDirection || e.direction === wsDirection;
    }

    // A row of the log that is not an event. A delta takes these out and puts
    // them back, so the other rows are the events the page keeps, in order.
    function wsMissedRow() {
        return '<tr class="dc-row net-ws-note-row net-ws-info-row"><td class="dc-cell" colspan="4">' + wsMissed
            + ' events were evicted before this page got them</td></tr>';
    }

    function wsEmptyRow() {
        return '<tr class="net-ws-info-row"><td class="dc-cell net-ws-empty" colspan="4">'
            + (wsSearch ? 'No text message matches the search' : 'No events yet') + '</td></tr>';
    }

    function wsEventRows(events) {
        return events.filter(wsShown).map(function(e) { return wsEventRow(e, wsSelectedSeq); }).join('');
    }

    // Renders a log that is new to the reader, after a reset or a change of
    // the direction filter, and shows its end.
    function renderWsEvents() {
        var tbody = document.getElementById('ws-events');
        var log = document.getElementById('ws-log');
        if (!tbody || !log) return;
        var rows = wsEventRows(wsEvents);
        tbody.innerHTML = (wsMissed > 0 ? wsMissedRow() : '') + rows || wsEmptyRow();
        log.scrollTop = log.scrollHeight;
    }

    function wsLogAtEnd(log) {
        return log.scrollHeight - log.scrollTop - log.clientHeight < 40;
    }

    // Makes a change to the parts above the log, which can take height from
    // the log. The end of the log stays in view if it was in view.
    function keepWsLogEnd(change) {
        var log = document.getElementById('ws-log');
        var atEnd = log && wsLogAtEnd(log);
        change();
        if (atEnd) log.scrollTop = log.scrollHeight;
    }

    // Applies a delta: the rows of `added` go in at the end, and the rows of
    // `removed`, the oldest events, which the page no longer keeps, go out at
    // the start. The newest row stays in view while the reader is at the end
    // of the log. Once they scroll up to read, the rows on screen stay where
    // they are.
    function appendWsEvents(added, removed) {
        var tbody = document.getElementById('ws-events');
        var log = document.getElementById('ws-log');
        if (!tbody || !log) return;
        var atEnd = wsLogAtEnd(log);
        // A row that stays shows how far the changes above it move the rows.
        var anchor = atEnd ? null : tbody.lastElementChild;
        var anchorTop = anchor ? anchor.getBoundingClientRect().top : 0;
        tbody.querySelectorAll('.net-ws-info-row').forEach(function(row) { row.remove(); });
        var rows = wsEventRows(added);
        if (rows) tbody.insertAdjacentHTML('beforeend', rows);
        for (var gone = removed.filter(wsShown).length; gone > 0 && tbody.firstElementChild; gone--) tbody.firstElementChild.remove();
        if (wsMissed > 0) tbody.insertAdjacentHTML('afterbegin', wsMissedRow());
        else if (!tbody.firstElementChild) tbody.innerHTML = wsEmptyRow();
        if (atEnd) log.scrollTop = log.scrollHeight;
        else if (anchor && anchor.isConnected) log.scrollTop += anchor.getBoundingClientRect().top - anchorTop;
    }

    window.setWsDirection = function(dir) {
        wsDirection = dir || '';
        document.querySelectorAll('.net-ws-bar .dc-seg__item').forEach(function(b) {
            b.classList.toggle('dc-seg__item--active', (b.dataset.dir || '') === wsDirection);
        });
        renderWsEvents();
    };

    window.onWsSearchInput = function(val) {
        updateSearchClear('ws-search');
        wsSearchRequest++;
        clearTimeout(wsSearchRetryTimer);
        clearTimeout(wsSearchTimer);
        wsSearchTimer = setTimeout(function() { applyWsSearch(val); }, 300);
    };

    // As applySearch: the log changes only once the app accepts the query.
    function applyWsSearch(val) {
        clearTimeout(wsSearchTimer);
        clearTimeout(wsSearchRetryTimer);
        var id = wsSelectedId;
        var request = ++wsSearchRequest;
        var regex = searchRegexOn('ws-search');
        if (!id || (val === wsSearch && (!val || regex === wsSearchRegex))) {
            showSearchError('ws-search', null);
            return;
        }
        fetch(wsEventsUrl(id, null, val, regex))
            .then(readSearchAnswer)
            .then(function(answer) {
                if (request !== wsSearchRequest || id !== wsSelectedId) return;
                showSearchError('ws-search', answer.error);
                if (answer.error) return;
                wsSearch = val;
                wsSearchRegex = regex;
                // The answer is the end of the log for the new query. A poll that
                // left with the old one is ignored, and the next one goes on from here.
                clearTimeout(wsEventsTimer);
                wsEventsRequest++;
                wsEvents = [];
                wsEventsCursor = null;
                wsMissed = 0;
                // The payload on screen marks the matches of the search before this one.
                wsSelectedSeq = null;
                var payload = document.getElementById('ws-payload');
                if (payload) replacePart(payload, '<div class="net-empty-body">Select a message to see its payload</div>');
                applyWsEvents(answer.data);
                if (!answer.data || answer.data.status === 'unchanged') renderWsEvents();
                wsEventsTimer = setTimeout(pollWsEvents, WS_EVENTS_POLL_MS);
            })
            .catch(function() {
                // The app didn't answer, or no longer has the connection: try again.
                if (request === wsSearchRequest && id === wsSelectedId) {
                    wsSearchRetryTimer = setTimeout(function() { applyWsSearch(val); }, WS_EVENTS_POLL_MS);
                }
            });
    }

    window.selectWsEvent = function(seq) {
        wsSelectedSeq = seq;
        document.querySelectorAll('#ws-events .dc-row').forEach(function(row) {
            row.classList.toggle('dc-row--selected', row.dataset.seq === String(seq));
        });
        var event = null;
        for (var i = 0; i < wsEvents.length; i++) if (wsEvents[i].seq === seq) event = wsEvents[i];
        if (event) renderWsPayload(event);
    };

    window.switchWsJsonView = function(view) {
        wsJsonView = view === 'raw' ? 'raw' : 'tree';
        if (wsSelectedSeq != null) window.selectWsEvent(wsSelectedSeq);
    };

    function wsPayloadUrl(e) {
        return netUrl('websockets/' + encodeURIComponent(wsSelectedId) + '/events/' + encodeURIComponent(e.seq) + '/payload');
    }

    function renderWsPayload(e) {
        var el = document.getElementById('ws-payload');
        if (!el) return;
        if (!e.stored) {
            replacePart(el, '<div class="net-empty-body">The payload was not stored: the app\'s redactor left it out, or failed on it</div>');
            return;
        }
        if (e.type === 'binary') {
            showWsPayload(e, null);
            return;
        }
        if (e.previewComplete) {
            showWsPayload(e, e.preview);
            return;
        }
        replacePart(el, '<div class="net-empty-body">Loading the payload…</div>');
        var id = wsSelectedId;
        debugFetch(wsPayloadUrl(e))
            .then(function(r) { return r.text(); })
            .then(function(text) {
                if (id === wsSelectedId && wsSelectedSeq === e.seq) showWsPayload(e, text);
            })
            .catch(function() {
                if (id === wsSelectedId && wsSelectedSeq === e.seq) replacePart(el, '<div class="net-empty-body">The message is no longer in the log</div>');
            });
    }

    // A text payload goes through the viewers that bodies use; a binary one is a hex dump.
    function showWsPayload(e, text) {
        var el = document.getElementById('ws-payload');
        if (!el) return;
        var binary = e.type === 'binary';
        var url = wsPayloadUrl(e);
        var options = { searchText: searchMark(wsSearch, wsSearchRegex) };
        var tree = !binary && wsJsonView === 'tree' ? debugJsonTree(text, options) : null;
        var isJson = !binary && debugBodyKind(null, text, false) === 'json';
        var bar = '<div class="net-body-bar">';
        bar += '<span class="net-body-meta">' + (e.direction === 'sent' ? '↑ Sent' : '↓ Received') + ' · ' + (binary ? 'binary' : 'text')
            + ' · ' + debugEscapeHtml(formatBytes(e.payloadBytes)) + ' · ' + debugEscapeHtml(e.timestamp || '') + '</span>';
        if (isJson) {
            bar += '<div class="dc-seg dc-seg--sm" role="group" aria-label="Payload view">'
                + ['tree', 'raw'].map(function(v) {
                    return '<button class="dc-seg__item' + (v === wsJsonView ? ' dc-seg__item--active' : '') + '" data-action="switchWsJsonView" data-view="' + v + '"'
                        + ' title="' + debugEscapeHtml(BODY_VIEW_HELP[v]) + '">' + BODY_VIEW_LABELS[v] + '</button>';
                }).join('') + '</div>';
        }
        if (e.truncated) bar += '<span class="net-truncated-label" title="The message passed the capture cap, so only its first part was kept.">Truncated</span>';
        bar += '<a class="dc-btn dc-btn--sm net-body-download" href="' + debugEscapeHtml(url) + '" download="lustro-ws-' + debugEscapeHtml(String(e.seq)) + (binary ? '.bin' : '.txt')
            + '" title="Save the payload as stored to a file.">Download</a></div>';
        var content = binary
            ? '<pre class="dc-code net-hexdump" data-hex-src="' + debugEscapeHtml(url) + '">Loading the bytes…</pre>'
            : (tree || debugLineNumbered(text, options));
        // Only the dump of the payload on screen is kept.
        var dump = hexDumps[url];
        hexDumps = {};
        if (dump != null) hexDumps[url] = dump;
        replacePart(el, bar + '<div class="net-body-wrap">' + copyBtn(binary ? null : text) + content + '</div>');
        loadHexDumps();
    }

    // A connection and the end of its log as one Markdown document: the URL as
    // a heading, a summary line, then each event, with a text payload in a
    // code block. It is built from what the page has, so a long payload is its
    // first part, and says so.
    window.netWebSocketMarkdown = function(connection, events) {
        var c = connection;
        var out = ['## ' + markdownCode('WS ' + (c.url || ''))];
        var meta = ['**' + wsStateLabel(c) + '**'];
        if (c.statusCode != null) meta.push('HTTP ' + c.statusCode);
        if (c.startedAt != null) meta.push(new Date(c.startedAt).toISOString());
        meta.push('sent ' + c.sentCount + ' (' + formatBytes(c.sentBytes) + ')');
        meta.push('received ' + c.receivedCount + ' (' + formatBytes(c.receivedBytes) + ')');
        out.push(meta.join(' · '));
        var close = wsCloseLine(c);
        if (close) out.push(close);
        if (c.error) out.push('Error: ' + markdownCode(c.error));
        var shown = events.slice(-WS_MARKDOWN_EVENTS);
        out.push('### Events' + (shown.length < events.length ? ' (the last ' + shown.length + ' of ' + events.length + ')' : ''));
        shown.forEach(function(e) {
            var line = markdownCode(e.timestamp || '') + ' ';
            if (e.kind !== 'message') {
                out.push(line + wsEventText(e));
                return;
            }
            line += (e.direction === 'sent' ? 'Sent' : 'Received') + ' ' + e.type + ', ' + formatBytes(e.payloadBytes);
            if (e.enqueued === false) line += ', not sent: send() returned false';
            if (!e.stored) {
                out.push(line + ', payload not stored');
            } else if (e.type === 'binary') {
                out.push(line + ': ' + markdownCode(spacedHex(e.hexPreview)) + (e.payloadBytes * 2 > String(e.hexPreview || '').length ? ' (first bytes)' : ''));
            } else {
                if (!e.previewComplete) line += ', first part only';
                out.push(line);
                out.push(markdownFence(e.preview || '', debugBodyKind(null, e.preview || '', false) === 'json' ? 'json' : ''));
            }
        });
        return out.join('\n\n') + '\n';
    };

    window.copyWsMarkdown = function(ev) {
        var c = findWebSocket(wsSelectedId);
        if (c) copyNetworkText(window.netWebSocketMarkdown(c, wsEvents), ev);
    };

    // The messages go out with the handshake's entry, so the export is of that transaction.
    window.exportWsHar = function() {
        var c = findWebSocket(wsSelectedId);
        if (!c || !c.transactionId) return;
        debugFetch(netUrl('transactions/_/export?format=har&ids=' + encodeURIComponent(c.transactionId)))
            .then(function(r) { return r.json(); })
            .then(function(har) {
                if (!har.log.entries.length) {
                    debugToast('The handshake request is no longer captured', 'warning');
                    return;
                }
                saveText(JSON.stringify(har, null, 2) + '\n', 'lustro-ws-' + fileTimestamp(new Date()) + '.har', 'application/json');
            })
            .catch(function(e) { debugToast('Failed to export: ' + e.message, 'error'); });
    };

    document.addEventListener('keydown', function(e) {
        var t = document.activeElement;
        var inInput = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT');

        if ((e.metaKey || e.ctrlKey) && (e.key === 'k' || e.key === 'K')) {
            e.preventDefault();
            var search = document.getElementById('search-input');
            if (search) { search.focus(); search.select(); }
            return;
        }
        // Escape in a search field clears it first; the next one closes the detail.
        if (e.key === 'Escape' && !e.defaultPrevented && t && (t.id === 'search-input' || t.id === 'ws-search') && t.value) {
            e.preventDefault();
            window.clearSearch(t.id);
            return;
        }
        if (rightPane === 'send' && e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            window.submitSendRequest();
            return;
        }
        if (e.key === 'Escape' && !e.defaultPrevented && !document.querySelector('.dc-modal-scrim:not([hidden])')) {
            if (rightPane !== 'detail') {
                window.closeRightPane();
                return;
            }
            if (trafficView === 'ws') {
                if (wsSelectedId) {
                    deselectWebSocket();
                    renderWsList();
                    showEmptyDetail();
                }
                return;
            }
            if (selectedTxId) {
                selectedTxId = null;
                currentDetailTx = null;
                renderList();
                var dc = document.getElementById('detail-content');
                if (dc) dc.innerHTML =
                    '<div class="net-empty-state"><div class="net-empty-icon">🔍</div><p>Select a request to inspect</p></div>';
                showDetailActions(false);
            }
            return;
        }
        if (!inInput && (e.key === 'c' || e.key === 'C') && !e.metaKey && !e.ctrlKey && !e.altKey) {
            window.clearTraffic();
        }
    });

    // The served HTML uses NO inline on*= handlers (the page's CSP forbids them:
    // script-src 'self', no 'unsafe-inline'). Instead, every interactive element
    // carries data-action="<name>" plus data-* payload, and a SMALL number of
    // delegated listeners on the tab container dispatch on data-action. Delegation
    // survives the dynamic re-rendering of the list/detail/rules/send panes.

    var CLICK_ACTIONS = {
        copyToClip: function(el, ev) {
            // Copy buttons live inside clickable rows — don't also select the row.
            ev.stopPropagation();
            window.copyToClip(el.dataset.copyId, ev);
        },
        toggleCategory: function(el) { window.toggleCategory(el.dataset.cat); },
        toggleAllCategories: function() { window.toggleAllCategories(); },
        toggleAllStatuses: function() { window.toggleAllStatuses(); },
        toggleAllMethods: function() { window.toggleAllMethods(); },
        toggleStatusFilter: function(el) { window.toggleStatusFilter(el.dataset.status); },
        toggleMethodFilter: function(el) { window.toggleMethodFilter(el.dataset.method); },
        selectTransaction: function(el) { window.selectTransaction(el.dataset.txId); },
        switchDir: function(el) { window.switchDir(el.dataset.dir); },
        switchBodyView: function(el) { window.switchBodyView(el.dataset.dir, el.dataset.view); },
        mockThis: function() { window.mockThis(); },
        openMockRules: function() { window.openMockRules(); },
        openRequestRules: function() { window.openRequestRules(); },
        showAllRules: function() { window.showAllRules(); },
        editRule: function(el) { window.editRule(el.dataset.ruleId); },
        deleteRule: function(el) { window.deleteRule(el.dataset.ruleId); },
        cancelEditRule: function() { window.cancelEditRule(); },
        submitRule: function() { window.submitRule(); },
        formatRuleBody: function() { window.formatRuleBody(); },
        formatSendBody: function() { window.formatSendBody(); },
        submitSendRequest: function() { window.submitSendRequest(); },
        addSendHeader: function() { window.addSendHeader(); },
        removeSendHeader: function(el) { window.removeSendHeader(parseInt(el.dataset.index, 10)); },
        openNewRequest: function() { window.openNewRequest(); },
        editAndResend: function() { window.editAndResend(); },
        closeRightPane: function() { window.closeRightPane(); },
        showSentTransaction: function(el) { window.showSentTransaction(el.dataset.txId); },
        togglePause: function() { window.togglePause(); },
        toggleOverwriteMode: function() { window.toggleOverwriteMode(); },
        clearTraffic: function() { window.clearTraffic(); },
        showCaptureFilter: function() { window.showCaptureFilter(); },
        closeCaptureFilter: function() { window.closeCaptureFilter(); },
        copyCurl: function(el, ev) { window.copyCurl(ev); },
        copyMarkdown: function(el, ev) { window.copyMarkdown(ev); },
        toggleSelectMode: function() { window.toggleSelectMode(); },
        toggleTxSelection: function(el) { window.toggleTxSelection(el.dataset.txId); },
        toggleSelectAll: function() { window.toggleSelectAll(); },
        copySelectionMarkdown: function(el, ev) { window.copySelectionMarkdown(ev); },
        exportSelectionHar: function() { window.exportSelectionHar(); },
        copyAllDetail: function(el, ev) { window.copyAllDetail(ev); },
        switchTrafficView: function(el) { window.switchTrafficView(el.dataset.view); },
        selectWebSocket: function(el) { window.selectWebSocket(el.dataset.wsId); },
        openWebSocket: function(el, ev) {
            // The badge sits inside a clickable row: don't also select the row.
            ev.stopPropagation();
            window.openWebSocket(el.dataset.wsId);
        },
        showWsHandshake: function() { window.showWsHandshake(); },
        setWsDirection: function(el) { window.setWsDirection(el.dataset.dir); },
        selectWsEvent: function(el) { window.selectWsEvent(parseInt(el.dataset.seq, 10)); },
        switchWsJsonView: function(el) { window.switchWsJsonView(el.dataset.view); },
        copyWsMarkdown: function(el, ev) { window.copyWsMarkdown(ev); },
        exportWsHar: function() { window.exportWsHar(); },
        clearSearch: function(el) { window.clearSearch(el.dataset.target); },
        toggleSearchRegex: function(el) { window.toggleSearchRegex(el.dataset.target); },
        showSearchHelp: function() { window.showSearchHelp(); },
        closeSearchHelp: function() { window.closeSearchHelp(); },
    };

    var delegationRoot = null;

    function onDelegatedClick(ev) {
        var el = ev.target.closest('[data-action]');
        if (!el || !delegationRoot.contains(el)) return;
        var fn = CLICK_ACTIONS[el.dataset.action];
        // Only dispatch click for actions registered as clicks; input/change-only
        // actions (search, throttle, rule toggle, send-header inputs) are ignored
        // here and handled by the input/change listeners below.
        if (fn) fn(el, ev);
    }

    function onDelegatedInput(ev) {
        var el = ev.target.closest('[data-action]');
        if (!el || !delegationRoot.contains(el)) return;
        var action = el.dataset.action;
        if (action === 'onSearchInput') {
            window.onSearchInput(el.value);
        } else if (action === 'onWsSearchInput') {
            window.onWsSearchInput(el.value);
        } else if (action === 'updateSendHeader') {
            window.updateSendHeader(parseInt(el.dataset.index, 10), el.dataset.field, el.value);
        } else if (action === 'updateSendField') {
            window.updateSendField(el.dataset.field, el.value);
        }
    }

    function onDelegatedChange(ev) {
        var el = ev.target.closest('[data-action]');
        if (!el || !delegationRoot.contains(el)) return;
        var action = el.dataset.action;
        if (action === 'setThrottle') {
            window.setThrottle(el.value);
        } else if (action === 'toggleRule') {
            window.toggleRule(el.dataset.ruleId);
        } else if (action === 'setSendSender') {
            window.setSendSender(el.value);
        } else if (action === 'setSendMethod') {
            window.setSendMethod(el);
        }
    }

    function setupDelegation() {
        // Delegate on the tab content root (shared.js injects the _view there) so a
        // single set of listeners covers every dynamically-rendered descendant.
        delegationRoot = document.getElementById('lustro-tab-content') || document.body;
        delegationRoot.addEventListener('click', onDelegatedClick);
        delegationRoot.addEventListener('input', onDelegatedInput);
        delegationRoot.addEventListener('change', onDelegatedChange);
        // capture: <details> toggle events and image load and error events do
        // not bubble.
        delegationRoot.addEventListener('toggle', onHeadersToggle, true);
        delegationRoot.addEventListener('load', onBodyImage, true);
        delegationRoot.addEventListener('error', onBodyImage, true);
    }

    function init() {
        setupDelegation();
        showSearchRegex('search-input');
        buildCategoryFilters();
        buildStatusFilters();
        buildMethodFilters();
        debugInitResizers();
        loadSenders();
        startPolling();
        startWsPolling();
    }
    if (typeof window.lustroOnContentReady === 'function') {
        window.lustroOnContentReady(init);
    } else {
        init();
    }
})();
