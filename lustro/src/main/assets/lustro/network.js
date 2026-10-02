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
    var searchText = '';
    var searchTimer = null;
    var searchGen = 0;
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

    window.onSearchInput = function(val) {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(function() {
            searchText = val;
            // Search is part of the poll query; a changed query invalidates the
            // cursor so the server returns a fresh (reset) list for the new query.
            lastCursor = null;
            searchGen++;
            resetDisplayLimit();
            fetchTransactions();
            if (currentDetailTx && trafficView === 'http') renderDetail(currentDetailTx);
        }, 300);
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
            var dur = tx.durationMs != null ? tx.durationMs + 'ms' + (streaming ? '…' : '') : '…';
            var pathOnly = extractPath(tx.url).split('?')[0];
            var shortUrl = pathOnly.length > 100 ? pathOnly.substring(0, 100) + '…' : pathOnly;
            var sel = tx.id === selectedTxId ? ' dc-row--selected' : '';
            var mockedBadge = tx.isMocked ? ' <span class="dc-badge" style="--c: var(--ai)">Mocked</span>' : '';
            var streamingBadge = streaming ? ' <span class="dc-badge">Streaming</span>' : '';
            var socketBadge = tx.webSocketId
                ? ' <span class="dc-badge net-ws-link" style="--c: var(--accent-2)" data-action="openWebSocket" data-ws-id="' + debugEscapeHtml(tx.webSocketId)
                    + '" title="The handshake of a WebSocket. Click to see its messages.">WS</span>'
                : '';
            var check = selectMode
                ? '<td class="dc-cell net-cell-select" data-action="toggleTxSelection" data-tx-id="' + debugEscapeHtml(tx.id) + '">'
                    + '<input type="checkbox" class="net-check" aria-label="Select ' + debugEscapeHtml((tx.method || '') + ' ' + pathOnly) + '"'
                    + (selectedIds[tx.id] ? ' checked' : '') + '></td>'
                : '';
            return '<tr class="dc-row' + sel + '" data-action="selectTransaction" data-tx-id="' + debugEscapeHtml(tx.id) + '">'
                + check
                + '<td class="dc-cell net-cell-method ' + methodClass(tx) + '">' + debugEscapeHtml(tx.method || '') + '</td>'
                + '<td class="dc-cell net-cell-url" title="' + debugEscapeHtml(pathOnly) + '">' + debugEscapeHtml(shortUrl) + '</td>'
                + '<td class="dc-cell net-cell-status ' + sc + '">' + statusText + mockedBadge + streamingBadge + socketBadge + '</td>'
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
            || previous.responseBodyBytes !== next.responseBodyBytes
            || previous.responseComplete !== next.responseComplete
            || previous.error !== next.error
            || previous.isMocked !== next.isMocked;
    }

    window.selectTransaction = function(id) {
        selectedTxId = id;
        renderList();
        switchRightTab('detail');
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
            + '  |  ' + (tx.timestamp || ''));
        if (tx.categories && tx.categories.length) lines.push('Categories: ' + tx.categories.join(', '));

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

    function renderDetail(tx) {
        // A search or a refresh renders the bodies again; keep what was folded in
        // each one that is the same, such as a request's while its response streams.
        var previous = currentDetailTx;
        var folded = previous && previous.id === tx.id ? foldedNodes() : {};
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
        html += '<div class="net-detail-method-url"><span class="net-detail-method ' + methodClass(tx) + '">' + debugEscapeHtml(tx.method || '') + '</span> ' + debugEscapeHtml(tx.url) + ' ' + copyBtn(tx.url || '') + '</div>';
        html += '<div class="net-detail-meta">';
        html += '<span class="' + sc + '">' + statusLabel + '</span>';
        html += '<span>' + (tx.durationMs != null ? tx.durationMs + 'ms' + (streaming ? ' streaming' : '') : '—') + '</span>';
        html += '<span>' + debugEscapeHtml(tx.timestamp || '') + '</span>';
        if (tx.protocol) html += '<span title="Protocol the response came over">' + debugEscapeHtml(tx.protocol) + '</span>';
        html += bodyMeta('↑', 'Request', tx.requestBodyBytes, tx.requestContentType);
        html += bodyMeta('↓', 'Response', tx.responseBodyBytes, tx.responseContentType);
        (tx.categories || []).forEach(function(c) { html += '<span class="dc-tag" data-cat="' + debugEscapeHtml(c) + '">' + debugEscapeHtml(c) + '</span>'; });
        if (tx.isMocked) html += '<span class="dc-badge" style="--c: var(--ai)">Mocked</span>';
        html += '</div>';
        if (tx.error) html += '<div class="net-error-line">' + debugEscapeHtml(tx.error) + '</div>';
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

        html += '<div style="margin-top:16px">';
        html += '<button class="dc-btn" data-action="mockThis" title="Switch to Mock Rules and pre-fill a new rule that intercepts this request. Edit the status/body before saving to control the response on the next match.">Mock This Request</button>';
        if (tx.webSocketId) {
            html += ' <button class="dc-btn" data-action="openWebSocket" data-ws-id="' + debugEscapeHtml(tx.webSocketId)
                + '" title="This request is the handshake of a WebSocket. Show the socket and its messages.">WebSocket messages</button>';
        }
        html += '</div>';

        el.innerHTML = html;
        refold(folded);
        loadHexDumps();
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
        xml: ['pretty', 'raw'],
        html: ['pretty', 'raw'],
        image: ['preview', 'raw'],
        binary: ['raw'],
        text: ['raw'],
    };
    var BODY_VIEW_LABELS = { tree: 'Tree', table: 'Table', pretty: 'Pretty', preview: 'Preview', raw: 'Raw' };
    var BODY_VIEW_HELP = {
        tree: 'Show the JSON as a tree. Click a key or bracket to fold it, and Alt-click to fold or unfold everything inside it.',
        table: 'Show the form fields as a table, with names and values decoded.',
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
            return open + missingBody(tx[dir + 'Headers'], text, dir === 'response' ? 'No response body' : 'No request body') + '</div>';
        }
        var contentType = tx[dir + 'ContentType'];
        var truncated = !!tx[dir + 'BodyTruncated'];
        var kind = debugBodyKind(contentType, text, binary);
        var view = bodyViewFor(contentType, kind);
        var options = { searchText: searchText };
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
            } else if (view === 'pretty') {
                content = debugHighlightMarkup(text, { searchText: searchText, html: kind === 'html' });
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
    function missingBody(headers, body, label) {
        var coding = body == null ? undecodedCoding(headers) : null;
        var text = coding ? 'Body not captured: Lustro does not decode the ' + coding + ' encoding' : label;
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

    function formatHeaders(headers) {
        var keys = Object.keys(headers);
        var rows = keys.map(function(k) {
            return '<tr class="net-header-row"><td class="net-header-key">' + debugEscapeHtml(k)
                + '</td><td class="net-header-value">' + copyBtn(headers[k], 'net-copy-hover') + debugEscapeHtml(headers[k]) + '</td></tr>';
        }).join('');
        return '<details class="net-headers-details"' + (headersOpen() ? ' open' : '') + '>'
            + '<summary title="Expand or collapse the headers. The choice is remembered in your browser.">'
            + keys.length + ' header' + (keys.length === 1 ? '' : 's') + '</summary>'
            + '<table class="net-headers-table">' + rows + '</table>'
            + '</details>';
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

    function buildCurlCommand(tx) {
        var parts = ['curl', '-X', tx.method || 'GET'];
        var headers = tx.requestHeaders || {};
        var hasBody = tx.requestBody && tx.method !== 'GET' && tx.method !== 'HEAD';
        Object.keys(headers).forEach(function(k) {
            // The captured body is stored inflated and redacted, so it goes out
            // without its coding, and curl works out its length.
            var name = k.toLowerCase();
            if (hasBody && (name === 'content-encoding' || name === 'content-length')) return;
            parts.push('-H');
            parts.push(shellQuote(k + ': ' + headers[k]));
        });
        if (hasBody) {
            parts.push('--data-raw');
            parts.push(shellQuote(tx.requestBody));
        }
        parts.push(shellQuote(tx.url || ''));
        return parts.join(' ');
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

    function showDetailActions(show) {
        ['copy-curl-btn', 'copy-md-btn', 'copy-all-btn'].forEach(function(id) {
            var btn = document.getElementById(id);
            if (btn) btn.style.display = show ? '' : 'none';
        });
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
        if (tx.startedAt != null) meta.push(new Date(tx.startedAt).toISOString());
        else if (tx.timestamp) meta.push(tx.timestamp);
        if (tx.protocol) meta.push(markdownCode(tx.protocol));
        if (tx.isMocked) meta.push('mocked by Lustro');
        (tx.categories || []).forEach(function(c) { meta.push(markdownCode(c)); });
        out.push(meta.join(' · '));
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

    window.switchRightTab = function(tab) {
        document.getElementById('detail-content').classList.toggle('active', tab === 'detail');
        document.getElementById('rules-content').classList.toggle('active', tab === 'rules');
        document.getElementById('send-content').classList.toggle('active', tab === 'send');
        document.getElementById('tab-btn-detail').classList.toggle('dc-tab--active', tab === 'detail');
        document.getElementById('tab-btn-rules').classList.toggle('dc-tab--active', tab === 'rules');
        document.getElementById('tab-btn-send').classList.toggle('dc-tab--active', tab === 'send');
        showDetailActions(tab === 'detail' && !!currentDetailTx);
        if (tab === 'rules') loadRules();
        if (tab === 'send') ensureSendForm();
    };

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

    function loadRules() {
        debugFetch(netUrl('rules'))
            .then(function(r) { return r.json(); })
            .then(function(data) { renderRules(rulesFromResponse(data)); })
            .catch(function(e) { debugToast('Failed to load rules: ' + e.message, 'error'); });
    }

    var loadedRules = [];
    var editingRuleId = null;

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
        if (loadedRules.length === 0) {
            listEl.innerHTML = '<div class="net-rules-empty">No mock rules defined.</div>';
        } else {
            listEl.innerHTML = loadedRules.map(function(r) {
                var disabled = r.enabled ? '' : ' disabled';
                var checked = r.enabled ? ' checked' : '';
                var editing = r.id === editingRuleId ? ' editing' : '';
                var bodyPreview = '';
                if (r.responseBody) {
                    bodyPreview = '<pre class="net-rule-body dc-code dc-json">' + window.debugSyntaxHighlightJson(r.responseBody) + '</pre>';
                }
                return '<div class="net-rule-card' + disabled + editing + '">'
                    + '<div class="net-rule-header">'
                    + '<label class="net-toggle" title="Enable or disable this rule. Disabled rules stay in the list but do not match traffic."><input id="rule-toggle-' + debugEscapeHtml(r.id) + '" name="ruleEnabled" type="checkbox" aria-label="Enable mock rule ' + debugEscapeHtml(r.name || r.urlPattern) + '"' + checked + ' data-action="toggleRule" data-rule-id="' + debugEscapeHtml(r.id) + '"><span class="net-toggle-slider"></span></label>'
                    + '<span class="net-rule-name" title="' + debugEscapeHtml(r.name || r.urlPattern) + '">' + debugEscapeHtml(r.name || r.urlPattern) + '</span>'
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

    function renderRuleForm(prefill) {
        prefill = prefill || {};
        var formEl = document.getElementById('rule-form-container');
        if (!formEl) return;
        var isEdit = !!prefill.id;
        var heading = isEdit ? 'Edit Mock Rule' : 'Add Mock Rule';
        var submitText = isEdit ? 'Update Rule' : 'Add Rule';
        var submitTooltip = isEdit
            ? 'Save changes to this rule. Future matching requests use the updated response.'
            : 'Save this rule. Future matching requests get the synthetic response. Rules live in the app, and survive a restart when it gives Lustro a rule storage.';
        formEl.innerHTML = '<div class="net-rule-form">'
            + '<h4>' + heading + (isEdit ? ' <button class="dc-btn dc-btn--icon" data-action="cancelEditRule" title="Cancel editing and return to the empty Add form.">✕</button>' : '') + '</h4>'
            + '<input type="hidden" id="rf-id" name="id" value="' + debugEscapeHtml(prefill.id || '') + '">'
            + '<div class="net-form-row"><label class="dc-label" for="rf-name" title="Optional human label shown in the rule list. Defaults to the URL pattern.">Name</label><input class="dc-input--block" id="rf-name" name="name" placeholder="Optional label" value="' + debugEscapeHtml(prefill.name || '') + '" title="Optional human label. Doesn\'t affect matching."></div>'
            + '<div class="net-form-row"><label class="dc-label" for="rf-pattern" title="What URLs this rule intercepts.">URL Pattern</label><input class="dc-input--block" id="rf-pattern" name="urlPattern" placeholder="Substring or regex:..." value="' + debugEscapeHtml(prefill.urlPattern || '') + '" title="Substring match by default (e.g. /api/sync). Prefix with regex: for a regular expression (e.g. regex:^.+/api/v\\d+/entries$)."></div>'
            + '<div class="net-form-row"><label class="dc-label" for="rf-method" title="HTTP method to match.">Method</label>'
            + '<select class="dc-input--block" id="rf-method" name="method" title="HTTP method this rule applies to. Choose Any to match every method.">'
            + '<option value="">Any</option>'
            + ['GET','POST','PUT','PATCH','DELETE'].map(function(m) {
                var sel = prefill.method === m ? ' selected' : '';
                return '<option value="' + m + '"' + sel + '>' + m + '</option>';
            }).join('')
            + '</select></div>'
            + '<div class="net-form-row"><label class="dc-label" for="rf-status" title="HTTP status code returned to the app.">Status</label><input class="dc-input--block" id="rf-status" name="statusCode" type="number" value="' + (prefill.statusCode || 200) + '" style="width:80px;flex:none" title="Status code returned to the app (e.g. 200, 404, 503)."></div>'
            + '<div class="net-form-row"><label class="dc-label" for="rf-body" title="Body returned to the app when this rule matches.">Body <button type="button" class="net-format-btn" data-action="formatRuleBody" title="Pretty-print the body as JSON (no-op if not valid JSON).">Format</button></label><textarea class="dc-textarea" id="rf-body" name="responseBody" placeholder="Response body (JSON, text, etc.)" title="Response body returned to the app. Can be any string; JSON is auto-formatted in the rule preview.">' + debugEscapeHtml(prefill.responseBody || '') + '</textarea></div>'
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

    var sendHeaders = [
        { key: 'Content-Type', value: 'application/json' },
    ];

    function ensureSendForm() {
        var container = document.getElementById('send-form-container');
        if (container && !container.innerHTML) renderSendForm();
    }

    function renderSendForm() {
        var container = document.getElementById('send-form-container');
        if (!container) return;
        var methodOptions = ['GET','POST','PUT','PATCH','DELETE','HEAD']
            .map(function(m) { return '<option value="' + m + '">' + m + '</option>'; }).join('');
        container.innerHTML = '<div class="net-rule-form">'
            + '<h4>Send a request through the app\'s OkHttp client</h4>'
            + '<div class="net-form-row"><label class="dc-label" for="sf-method" title="HTTP method for the dispatched request.">Method</label>'
            + '<select class="dc-input--block" id="sf-method" name="method" style="max-width:120px;flex:0 0 120px" title="HTTP method.">' + methodOptions + '</select></div>'
            + '<div class="net-form-row"><label class="dc-label" for="sf-url" title="Where to send the request.">URL</label>'
            + '<input class="dc-input--block" id="sf-url" name="url" placeholder="/api/v1/entries or https://example.com/path" title="Absolute URL or a relative path. Relative paths are resolved against the app\'s configured server base URL."></div>'
            + '<div class="net-form-row"><label class="dc-label" id="sf-headers-label" title="Custom request headers (the app\'s OkHttp interceptors still add Auth/UA/etc.).">Headers</label><div id="sf-headers" role="group" aria-labelledby="sf-headers-label" style="flex:1"></div></div>'
            + '<div class="net-form-row"><label class="dc-label" for="sf-body" title="Body sent with the request.">Body <button type="button" class="net-format-btn" data-action="formatSendBody" title="Pretty-print the body as JSON (no-op if not valid JSON).">Format</button></label>'
            + '<textarea class="dc-textarea" id="sf-body" name="body" placeholder="Request body (omit for GET/HEAD)" title="Body sent with the request. Omit for GET/HEAD. Content-Type defaults to application/json unless overridden via Headers."></textarea></div>'
            + '<div class="net-form-actions">'
            + '<button class="dc-btn dc-btn--primary" id="sf-send-btn" data-action="submitSendRequest" title="Dispatch through the app\'s authorized OkHttpClient. Goes through every real interceptor (auth, logging, debug capture). Self-requests to the debug server are rejected. The request is sent synchronously and the result is shown below.">Send</button>'
            + '</div>'
            + '<div id="sf-result"></div>'
            + '</div>';
        renderSendHeaders();
    }

    function renderSendHeaders() {
        var container = document.getElementById('sf-headers');
        if (!container) return;
        container.innerHTML = sendHeaders.map(function(h, i) {
            var keyId = 'sf-header-key-' + i;
            var valueId = 'sf-header-value-' + i;
            return '<div class="net-send-header-row">'
                + '<input class="dc-input--block" id="' + keyId + '" name="headerKey" type="text" aria-label="Header name" placeholder="Header" value="' + debugEscapeHtml(h.key) + '" data-action="updateSendHeader" data-index="' + i + '" data-field="key" title="Header name (e.g. Content-Type, Accept).">'
                + '<input class="dc-input--block" id="' + valueId + '" name="headerValue" type="text" aria-label="Header value" placeholder="Value" value="' + debugEscapeHtml(h.value) + '" data-action="updateSendHeader" data-index="' + i + '" data-field="value" title="Header value.">'
                + '<button type="button" class="dc-btn dc-btn--icon dc-btn--danger" data-action="removeSendHeader" data-index="' + i + '" aria-label="Remove header row" title="Remove this header row.">✕</button>'
                + '</div>';
        }).join('')
            + '<button type="button" class="net-format-btn" data-action="addSendHeader" style="margin-top:6px" title="Add another header row.">+ Add header</button>';
    }

    window.updateSendHeader = function(i, field, value) {
        if (sendHeaders[i]) sendHeaders[i][field] = value;
    };
    window.addSendHeader = function() {
        sendHeaders.push({ key: '', value: '' });
        renderSendHeaders();
    };
    window.removeSendHeader = function(i) {
        sendHeaders.splice(i, 1);
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
    };

    function renderSendResult(data, networkError) {
        var el = document.getElementById('sf-result');
        if (!el) return;
        if (networkError) {
            el.className = 'net-send-result error';
            el.innerHTML = '<div class="net-send-result-status">Failed to send</div>'
                + '<div class="net-send-result-line">' + debugEscapeHtml(networkError) + '</div>';
            return;
        }
        var ok = !!(data && data.ok);
        var statusCode = data && data.statusCode != null ? data.statusCode : null;
        var lines = [];
        if (statusCode != null) lines.push('Status code: ' + statusCode);
        if (data && data.transactionId) {
            lines.push('Captured as transaction ' + data.transactionId + ' — open it in the traffic list.');
        }
        if (data && data.error) lines.push('Error: ' + data.error);
        el.className = 'net-send-result ' + (ok ? 'ok' : 'error');
        var headline = ok
            ? ('OK' + (statusCode != null ? ' (' + statusCode + ')' : ''))
            : (statusCode != null ? 'Failed (' + statusCode + ')' : 'Failed');
        el.innerHTML = '<div class="net-send-result-status">' + debugEscapeHtml(headline) + '</div>'
            + lines.map(function(l) { return '<div class="net-send-result-line">' + debugEscapeHtml(l) + '</div>'; }).join('');
    }

    window.submitSendRequest = function() {
        var urlEl = document.getElementById('sf-url');
        var url = urlEl ? urlEl.value.trim() : '';
        if (!url) { debugToast('URL is required', 'warning'); return; }
        var method = document.getElementById('sf-method').value;
        var bodyText = document.getElementById('sf-body').value;
        var headers = {};
        sendHeaders.forEach(function(h) {
            if (h.key.trim()) headers[h.key.trim()] = h.value;
        });
        var btn = document.getElementById('sf-send-btn');
        if (btn) btn.disabled = true;
        var resultEl = document.getElementById('sf-result');
        if (resultEl) { resultEl.className = ''; resultEl.innerHTML = '<div class="net-rule-hits">Sending…</div>'; }
        // Send is now SYNCHRONOUS: the server blocks for the NetworkSender result
        // and replies { transactionId, statusCode, ok, error? }. Show that result
        // directly in the panel instead of relying on the traffic list alone.
        debugFetch(netUrl('send'), {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                method: method,
                url: url,
                headers: headers,
                body: bodyText,
            }),
        }).then(function(r) { return r.json(); })
          .then(function(data) {
              renderSendResult(data, null);
              if (data && data.ok) {
                  debugToast('Request sent' + (data.statusCode != null ? ' (' + data.statusCode + ')' : ''), 'success');
              } else {
                  debugToast((data && data.error) || 'Send failed', 'error');
              }
          })
          .catch(function(e) {
              renderSendResult(null, e.message);
              debugToast('Failed to send: ' + e.message, 'error');
          })
          .then(function() {
              if (btn) btn.disabled = false;
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
        }
        debugFetch(netUrl('rules'), {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(rule),
        }).then(function() {
            debugToast(wasEdit ? 'Rule updated' : 'Rule added', 'success');
            editingRuleId = null;
            renderRuleForm();
            loadRules();
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
        }).then(function() { loadRules(); })
          .catch(function(e) { debugToast('Failed to toggle rule: ' + e.message, 'error'); });
    };

    window.deleteRule = function(id) {
        debugFetch(netUrl('rules/delete'), {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({id: id}),
        }).then(function() {
            debugToast('Rule deleted', 'info');
            loadRules();
        }).catch(function(e) { debugToast('Failed to delete rule: ' + e.message, 'error'); });
    };

    window.mockThis = function() {
        if (!selectedTxId) return;
        var tx = allTransactions.find(function(t) { return t.id === selectedTxId; });
        if (!tx) return;
        debugFetch(netUrl('transactions/' + encodeURIComponent(selectedTxId)))
            .then(function(r) { return r.json(); })
            .then(function(detail) {
                detail = detail || {};
                switchRightTab('rules');
                var url = tx.url || '';
                renderRuleForm({
                    name: (tx.method || '') + ' ' + (url.length > 60 ? url.substring(0, 60) + '…' : url),
                    urlPattern: extractPath(url),
                    method: tx.method,
                    statusCode: detail.statusCode || 200,
                    responseBody: detail.responseBody || '',
                });
            });
    };

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
        if (searchText) params.push('search=' + encodeURIComponent(searchText));
        if (params.length) url += '?' + params.join('&');
        return url;
    }

    function applyTransactionList(items) {
        var list = items || [];
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

    function fetchTransactions() {
        var gen = searchGen;
        debugFetch(pollUrl())
            .then(function(r) { return r.json(); })
            .then(function(data) {
                if (gen === searchGen) handlePollData(data);
            })
            .catch(function() { /* next poll will retry */ });
    }

    var pollSearchGen = searchGen;
    function startPolling() {
        debugPoll(function() {
            pollSearchGen = searchGen;
            return pollUrl();
        }, 1500, function(data) {
            if (pollSearchGen === searchGen) handlePollData(data);
        });
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
    var wsSearch = '';
    var wsSearchTimer = null;
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
        switchRightTab('detail');
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
        renderWsList();
        switchRightTab('detail');
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
            + '<label class="dc-field net-ws-search" for="ws-search"><span class="dc-field__prefix" aria-hidden="true">&gt;</span>'
            + '<input type="text" id="ws-search" name="wsSearch" aria-label="Search the messages" class="dc-input" placeholder="filter messages…" data-action="onWsSearchInput"'
            + ' value="' + debugEscapeHtml(wsSearch) + '"'
            + ' title="Show only the text messages that contain this text (server-side, 300ms debounce)."></label>'
            + '<button class="dc-btn dc-btn--sm" data-action="copyWsMarkdown" title="Copy the connection and its last ' + WS_MARKDOWN_EVENTS
            + ' events as Markdown, for a bug report, a pull request, or a chat.">Markdown</button>'
            + '<button class="dc-btn dc-btn--sm" id="ws-har-btn" data-action="exportWsHar" title="Save the handshake request and the messages as a HAR file, which Chrome DevTools imports with the messages.">Export HAR</button>'
            + '</div>'
            + '<div id="ws-log-note" class="dc-comment net-ws-log-note"></div>'
            + '<div id="ws-gone-note" class="dc-comment net-ws-log-note" hidden>// The app no longer has this connection: it was cleared, or it passed the connection limit. These are the events this page got.</div>'
            + '<div class="net-ws-log" id="ws-log"><table class="dc-table"><tbody id="ws-events"></tbody></table></div>'
            + '<div class="net-ws-payload" id="ws-payload"><div class="net-empty-body">Select a message to see its payload</div></div>'
            + '</div>';
        wsHeadShown = null;
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
        var url = netUrl('websockets/' + encodeURIComponent(id) + '/events?limit=' + WS_MAX_ROWS)
            + (wsEventsCursor != null ? '&cursor=' + encodeURIComponent(wsEventsCursor) : '')
            + (wsSearch ? '&search=' + encodeURIComponent(wsSearch) : '');
        // Not debugFetch: a 404 here means that the app cleared or evicted the
        // connection, which must not show the console as disconnected. The poll
        // goes on, because a socket that is still open is listed again with its
        // next message.
        fetch(url)
            .then(function(r) {
                if (request !== wsEventsRequest || id !== wsSelectedId) return null;
                setWsGone(r.status === 404);
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
            + (wsSearch ? 'No text message contains this text' : 'No events yet') + '</td></tr>';
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
        clearTimeout(wsSearchTimer);
        wsSearchTimer = setTimeout(function() {
            wsSearch = val;
            // The search is part of the poll query, so the log is read again from its end.
            wsEvents = [];
            wsEventsCursor = null;
            wsMissed = 0;
            // The payload on screen marks the matches of the search before this one.
            wsSelectedSeq = null;
            var payload = document.getElementById('ws-payload');
            if (payload) replacePart(payload, '<div class="net-empty-body">Select a message to see its payload</div>');
            pollWsEvents();
        }, 300);
    };

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
        var options = { searchText: wsSearch };
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
        if (e.key === 'Escape' && !e.defaultPrevented && !document.querySelector('.dc-modal-scrim:not([hidden])')) {
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
        switchRightTab: function(el) { window.switchRightTab(el.dataset.tab); },
        mockThis: function() { window.mockThis(); },
        editRule: function(el) { window.editRule(el.dataset.ruleId); },
        deleteRule: function(el) { window.deleteRule(el.dataset.ruleId); },
        cancelEditRule: function() { window.cancelEditRule(); },
        submitRule: function() { window.submitRule(); },
        formatRuleBody: function() { window.formatRuleBody(); },
        formatSendBody: function() { window.formatSendBody(); },
        submitSendRequest: function() { window.submitSendRequest(); },
        addSendHeader: function() { window.addSendHeader(); },
        removeSendHeader: function(el) { window.removeSendHeader(parseInt(el.dataset.index, 10)); },
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
        buildCategoryFilters();
        buildStatusFilters();
        buildMethodFilters();
        debugInitResizers();
        startPolling();
        startWsPolling();
    }
    if (typeof window.lustroOnContentReady === 'function') {
        window.lustroOnContentReady(init);
    } else {
        init();
    }
})();
