(function() {
    // Lustro network tab UI. Served as an EXTERNAL script. The tab id is already
    // "network" (from <body data-lustro-tab>), so lustroApiUrl(path) yields
    // `/api/v1/network/<path>`. netUrl() is a thin alias for clarity.
    function netUrl(path) {
        return window.lustroApiUrl(path);
    }

    // Categories are now classifier-supplied strings, NOT a fixed enum. We don't
    // know the universe up front, so the category filter set is discovered from
    // incoming traffic and every newly-seen category defaults to enabled. The
    // classic category labels are kept only as preset tooltips/order hints.
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
    var lastCursor = null;        // opaque cursor token; omitted on the first poll
    var searchText = '';
    var searchTimer = null;
    var searchGen = 0;
    var isPaused = false;
    var isOverwriteMode = false;
    var throttleDelayMs = 0;
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

    // Scan a list of transactions for categories not yet known. Returns true if
    // the known set changed (so the filter bar can be rebuilt).
    function discoverCategories(list) {
        var changed = false;
        (list || []).forEach(function(tx) {
            ((tx && tx.categories) || []).forEach(function(c) {
                if (ensureCategoryKnown(c)) changed = true;
            });
        });
        return changed;
    }

    // ── Copy to clipboard ──
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

    function copyBtn(text, cls) {
        var id = '_cp' + (copyIdSeq++);
        copyStore[id] = text;
        // CSP-safe: the click is handled by the delegated listener, which calls
        // event.stopPropagation() for data-action="copyToClip" so a copy button
        // inside a clickable row doesn't also select the row.
        return '<span class="net-copy-btn' + (cls ? ' ' + cls : '') + '" data-action="copyToClip" data-copy-id="' + debugEscapeHtml(id) + '" title="Copy">' + COPY_ICON + '</span>';
    }

    // ── Per-row master toggles (tri-state "select all") ──
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

    // ── Category filter bar ──
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

    // ── Status & method filter bars ──
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

    // ── Search (server-side to match against bodies) ──
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
            if (currentDetailTx) renderDetail(currentDetailTx);
        }, 300);
    };

    // ── Filtering (category/status/method are client-side, search is server-side) ──
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

    // ── Left pane: transaction list ──
    function renderList() {
        var filtered = filterTransactions();
        var tbody = document.getElementById('tx-list');
        var countEl = document.getElementById('tx-count');
        if (!tbody) return;
        var visible = filtered.slice(0, displayLimit);
        var label = filtered.length + (filtered.length !== allTransactions.length
            ? '/' + allTransactions.length : '') + ' requests';
        if (visible.length < filtered.length) {
            label += ' (showing ' + visible.length + ')';
        }
        if (countEl) countEl.textContent = label;

        tbody.innerHTML = visible.map(function(tx) {
            var sc = statusClass(tx);
            var streaming = isStreaming(tx);
            var statusText = tx.error ? 'ERR' : (tx.statusCode ? tx.statusCode + (streaming ? '…' : '') : '…');
            var dur = tx.durationMs != null ? tx.durationMs + 'ms' + (streaming ? '…' : '') : '…';
            var pathOnly = extractPath(tx.url).split('?')[0];
            var shortUrl = pathOnly.length > 100 ? pathOnly.substring(0, 100) + '…' : pathOnly;
            var sel = tx.id === selectedTxId ? ' selected' : '';
            var mockedBadge = tx.isMocked ? ' <span class="status-pill mocked">Mocked</span>' : '';
            var streamingBadge = streaming ? ' <span class="status-pill">Streaming</span>' : '';
            return '<tr class="' + sel + '" data-action="selectTransaction" data-tx-id="' + debugEscapeHtml(tx.id) + '">'
                + '<td class="net-cell-method ' + methodClass(tx) + '">' + debugEscapeHtml(tx.method || '') + '</td>'
                + '<td class="net-cell-url" title="' + debugEscapeHtml(pathOnly) + '">' + debugEscapeHtml(shortUrl) + '</td>'
                + '<td class="net-cell-status ' + sc + '">' + statusText + mockedBadge + streamingBadge + '</td>'
                + '<td class="net-cell-time">' + dur + '</td>'
                + '<td class="net-cell-cat">' + ((tx.categories || []).map(function(c) {
                    return '<span class="status-pill cat-pill" data-cat="' + debugEscapeHtml(c) + '">' + debugEscapeHtml(c) + '</span>';
                }).join(' ')) + '</td>'
                + '</tr>';
        }).join('');

        var loadMoreEl = document.getElementById('tx-load-more');
        if (visible.length < filtered.length) {
            if (!loadMoreEl) {
                loadMoreEl = document.createElement('button');
                loadMoreEl.id = 'tx-load-more';
                loadMoreEl.className = 'debug-btn';
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

    // ── Transaction selection ──
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
        if (!selectedTxId) return;
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

    // ── Right pane: detail ──
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
        currentDetailTx = tx;
        var copyAllEl = document.getElementById('copy-all-btn');
        if (copyAllEl) copyAllEl.style.display = '';
        var copyCurlEl = document.getElementById('copy-curl-btn');
        if (copyCurlEl) copyCurlEl.style.display = '';
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
        if (tx.requestBodyBytes != null) html += '<span title="Request body size">↑ ' + formatBytes(tx.requestBodyBytes) + '</span>';
        if (tx.responseBodyBytes != null) html += '<span title="Response body size">↓ ' + formatBytes(tx.responseBodyBytes) + '</span>';
        (tx.categories || []).forEach(function(c) { html += '<span class="status-pill cat-pill" data-cat="' + debugEscapeHtml(c) + '">' + debugEscapeHtml(c) + '</span>'; });
        if (tx.isMocked) html += '<span class="status-pill mocked">Mocked</span>';
        html += '</div>';
        if (tx.error) html += '<div class="net-error-line">' + debugEscapeHtml(tx.error) + '</div>';
        html += '</div>';

        // Direction tabs: Response | Request
        html += '<div class="net-dir-tabs">';
        html += '<button class="net-dir-btn' + (activeDir === 'response' ? ' active' : '') + '" data-action="switchDir" data-dir="response" title="Show what the server sent back (status, headers, body).">Response</button>';
        html += '<button class="net-dir-btn' + (activeDir === 'request' ? ' active' : '') + '" data-action="switchDir" data-dir="request" title="Show what the app sent (method, URL, headers, body). Redacted values are removed.">Request</button>';
        html += '</div>';

        // One panel per direction: headers (collapsed <details>) above the
        // body. The collapsed/expanded choice is global, persisted in the
        // browser (see headersOpen), and shared by both directions.
        var vis = function(dir) { return dir === activeDir ? '' : ' style="display:none"'; };

        html += '<div class="net-dir-content" data-dir="response"' + vis('response') + '>';
        if (tx.responseHeaders && Object.keys(tx.responseHeaders).length > 0) {
            html += formatHeaders(tx.responseHeaders);
        }
        if (tx.responseBody) {
            var respFmt = debugFormatJson(tx.responseBody);
            var respHtml = renderBody(tx.responseBody);
            if (tx.responseBodyTruncated) html += '<div class="net-truncated-label">Truncated</div>';
            html += '<div class="net-body-wrap">' + copyBtn(respFmt) + '<pre class="debug-code-block debug-json">' + respHtml + '</pre></div>';
        } else {
            html += '<div class="net-empty-body">No response body</div>';
        }
        html += '</div>';

        html += '<div class="net-dir-content" data-dir="request"' + vis('request') + '>';
        if (tx.requestHeaders && Object.keys(tx.requestHeaders).length > 0) {
            html += formatHeaders(tx.requestHeaders);
        }
        if (tx.requestBody) {
            var reqFmt = debugFormatJson(tx.requestBody);
            var reqHtml = renderBody(tx.requestBody);
            if (tx.requestBodyTruncated) html += '<div class="net-truncated-label">Truncated</div>';
            html += '<div class="net-body-wrap">' + copyBtn(reqFmt) + '<pre class="debug-code-block debug-json">' + reqHtml + '</pre></div>';
        } else {
            html += '<div class="net-empty-body">No request body</div>';
        }
        html += '</div>';

        // Mock This button
        html += '<div style="margin-top:16px">';
        html += '<button class="debug-btn" data-action="mockThis" title="Switch to Mock Rules and pre-fill a new rule that intercepts this request. Edit the status/body before saving to control the response on the next match.">Mock This Request</button>';
        html += '</div>';

        el.innerHTML = html;
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
        document.querySelectorAll('.net-dir-btn').forEach(function(b) {
            b.classList.toggle('active', b.dataset.dir === dir);
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

    // Render a request/response body. JSON gets syntax-highlighted; non-JSON
    // is HTML-escaped. When the search field has text, matches are wrapped in
    // <mark> inside both code paths.
    function renderBody(rawBody) {
        return window.debugSyntaxHighlightJson(rawBody, { searchText: searchText });
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
            + '<table class="debug-table net-headers-table">' + rows + '</table>'
            + '</details>';
    }

    function formatBytes(n) {
        if (n == null) return '';
        if (n < 1024) return n + ' B';
        if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
        return (n / (1024 * 1024)).toFixed(2) + ' MB';
    }

    // Build a curl(1) command from the captured transaction.
    function buildCurlCommand(tx) {
        var parts = ['curl', '-X', tx.method || 'GET'];
        var headers = tx.requestHeaders || {};
        Object.keys(headers).forEach(function(k) {
            parts.push('-H');
            parts.push(shellQuote(k + ': ' + headers[k]));
        });
        var hasBody = tx.requestBody && tx.method !== 'GET' && tx.method !== 'HEAD';
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

    // ── Right pane: tab switcher ──
    window.switchRightTab = function(tab) {
        document.getElementById('detail-content').classList.toggle('active', tab === 'detail');
        document.getElementById('rules-content').classList.toggle('active', tab === 'rules');
        document.getElementById('send-content').classList.toggle('active', tab === 'send');
        document.getElementById('tab-btn-detail').classList.toggle('active', tab === 'detail');
        document.getElementById('tab-btn-rules').classList.toggle('active', tab === 'rules');
        document.getElementById('tab-btn-send').classList.toggle('active', tab === 'send');
        var detailHasTx = !!currentDetailTx;
        var copyAll = document.getElementById('copy-all-btn');
        var copyCurl = document.getElementById('copy-curl-btn');
        if (copyAll) copyAll.style.display = (tab === 'detail' && detailHasTx) ? '' : 'none';
        if (copyCurl) copyCurl.style.display = (tab === 'detail' && detailHasTx) ? '' : 'none';
        if (tab === 'rules') loadRules();
        if (tab === 'send') ensureSendForm();
    };

    // ── Mock Rules ──
    var RULES_STORAGE_KEY = 'debug-network-mock-rules';

    function readLocalRules() {
        try {
            var raw = localStorage.getItem(RULES_STORAGE_KEY);
            return raw ? JSON.parse(raw) : [];
        } catch(e) {
            return [];
        }
    }
    function writeLocalRules(rules) {
        try { localStorage.setItem(RULES_STORAGE_KEY, JSON.stringify(rules || [])); } catch(e) {}
    }

    // The rules endpoint may return either a bare array or a pagination envelope
    // ({ items: [...] }). Normalize to an array defensively.
    function rulesFromResponse(data) {
        if (Array.isArray(data)) return data;
        if (data && Array.isArray(data.items)) return data.items;
        return [];
    }

    var rulesInitialLoadDone = false;

    function loadRules() {
        debugFetch(netUrl('rules'))
            .then(function(r) { return r.json(); })
            .then(function(data) {
                var rules = rulesFromResponse(data);
                // Only auto-restore from localStorage on the *first* load — i.e. the
                // user just opened the page and the server-side storage was wiped (app
                // reinstall, Clear Data). After that, an empty server state is the
                // legitimate result of the user deleting their last rule, and
                // restoring from cache would resurrect it on the very next refresh.
                if (!rulesInitialLoadDone &&
                    rules.length === 0 &&
                    readLocalRules().length > 0) {
                    rulesInitialLoadDone = true;
                    syncLocalRulesToServer();
                } else {
                    rulesInitialLoadDone = true;
                    renderRules(rules);
                    writeLocalRules(rules);
                }
            })
            .catch(function(e) { debugToast('Failed to load rules: ' + e.message, 'error'); });
    }

    function syncLocalRulesToServer() {
        var local = readLocalRules();
        // Versioned atomic-replace route is rules/_/sync.
        debugFetch(netUrl('rules/_/sync'), {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(local),
        }).then(function() {
            debugToast('Restored ' + local.length + ' rule(s) from browser storage', 'info');
            return debugFetch(netUrl('rules')).then(function(r) { return r.json(); });
        }).then(function(data) {
            var rules = rulesFromResponse(data);
            renderRules(rules);
            writeLocalRules(rules);
        }).catch(function(e) { debugToast('Failed to sync rules: ' + e.message, 'error'); });
    }

    var loadedRules = [];
    var editingRuleId = null;

    // Map a mock rule's status code onto the shared .status-pill variants.
    function ruleStatusVariant(code) {
        var c = parseInt(code, 10);
        if (!c) return '';
        if (c < 300) return ' success';
        if (c < 400) return ' warning';
        return ' error';
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
                    bodyPreview = '<pre class="net-rule-body debug-code-block debug-json">' + window.debugSyntaxHighlightJson(r.responseBody) + '</pre>';
                }
                return '<div class="net-rule-card' + disabled + editing + '">'
                    + '<div class="net-rule-header">'
                    + '<label class="net-toggle" title="Enable or disable this rule. Disabled rules stay in the list but do not match traffic."><input id="rule-toggle-' + debugEscapeHtml(r.id) + '" name="ruleEnabled" type="checkbox" aria-label="Enable mock rule ' + debugEscapeHtml(r.name || r.urlPattern) + '"' + checked + ' data-action="toggleRule" data-rule-id="' + debugEscapeHtml(r.id) + '"><span class="net-toggle-slider"></span></label>'
                    + '<span class="net-rule-name" title="' + debugEscapeHtml(r.name || r.urlPattern) + '">' + debugEscapeHtml(r.name || r.urlPattern) + '</span>'
                    + '<span class="status-pill' + ruleStatusVariant(r.statusCode) + '" title="HTTP status returned to the app when this rule matches.">' + debugEscapeHtml(r.statusCode) + '</span>'
                    + '<button class="debug-btn-icon" data-action="editRule" data-rule-id="' + debugEscapeHtml(r.id) + '" title="Edit this rule (loads it into the form below).">✎</button>'
                    + '<button class="debug-btn-icon danger" data-action="deleteRule" data-rule-id="' + debugEscapeHtml(r.id) + '" title="Delete this rule permanently.">✕</button>'
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
            : 'Save this rule. Future matching requests get the synthetic response. Rules persist across app restarts.';
        formEl.innerHTML = '<div class="net-rule-form">'
            + '<h4>' + heading + (isEdit ? ' <button class="debug-btn-icon" data-action="cancelEditRule" title="Cancel editing and return to the empty Add form.">✕</button>' : '') + '</h4>'
            + '<input type="hidden" id="rf-id" name="id" value="' + debugEscapeHtml(prefill.id || '') + '">'
            + '<div class="net-form-row"><label for="rf-name" title="Optional human label shown in the rule list. Defaults to the URL pattern.">Name</label><input id="rf-name" name="name" placeholder="Optional label" value="' + debugEscapeHtml(prefill.name || '') + '" title="Optional human label. Doesn\'t affect matching."></div>'
            + '<div class="net-form-row"><label for="rf-pattern" title="What URLs this rule intercepts.">URL Pattern</label><input id="rf-pattern" name="urlPattern" placeholder="Substring or regex:..." value="' + debugEscapeHtml(prefill.urlPattern || '') + '" title="Substring match by default (e.g. /api/sync). Prefix with regex: for a regular expression (e.g. regex:^.+/api/v\\d+/entries$)."></div>'
            + '<div class="net-form-row"><label for="rf-method" title="HTTP method to match.">Method</label>'
            + '<select id="rf-method" name="method" title="HTTP method this rule applies to. Choose Any to match every method.">'
            + '<option value="">Any</option>'
            + ['GET','POST','PUT','PATCH','DELETE'].map(function(m) {
                var sel = prefill.method === m ? ' selected' : '';
                return '<option value="' + m + '"' + sel + '>' + m + '</option>';
            }).join('')
            + '</select></div>'
            + '<div class="net-form-row"><label for="rf-status" title="HTTP status code returned to the app.">Status</label><input id="rf-status" name="statusCode" type="number" value="' + (prefill.statusCode || 200) + '" style="width:80px;flex:none" title="Status code returned to the app (e.g. 200, 404, 503)."></div>'
            + '<div class="net-form-row"><label for="rf-body" title="Body returned to the app when this rule matches.">Body <button type="button" class="net-format-btn" data-action="formatRuleBody" title="Pretty-print the body as JSON (no-op if not valid JSON).">Format</button></label><textarea id="rf-body" name="responseBody" placeholder="Response body (JSON, text, etc.)" title="Response body returned to the app. Can be any string; JSON is auto-formatted in the rule preview.">' + debugEscapeHtml(prefill.responseBody || '') + '</textarea></div>'
            + '<div class="net-form-actions">'
            + '<button class="debug-btn debug-btn-primary" data-action="submitRule" title="' + submitTooltip + '">' + submitText + '</button>'
            + (isEdit ? '<button class="debug-btn" data-action="cancelEditRule" title="Discard changes and return to the empty Add form.">Cancel</button>' : '')
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

    // ── Send Request panel ──
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
            + '<div class="net-form-row"><label for="sf-method" title="HTTP method for the dispatched request.">Method</label>'
            + '<select id="sf-method" name="method" style="max-width:120px;flex:0 0 120px" title="HTTP method.">' + methodOptions + '</select></div>'
            + '<div class="net-form-row"><label for="sf-url" title="Where to send the request.">URL</label>'
            + '<input id="sf-url" name="url" placeholder="/api/v1/entries or https://example.com/path" title="Absolute URL or a relative path. Relative paths are resolved against the app\'s configured server base URL."></div>'
            + '<div class="net-form-row"><label id="sf-headers-label" title="Custom request headers (the app\'s OkHttp interceptors still add Auth/UA/etc.).">Headers</label><div id="sf-headers" role="group" aria-labelledby="sf-headers-label" style="flex:1"></div></div>'
            + '<div class="net-form-row"><label for="sf-body" title="Body sent with the request.">Body <button type="button" class="net-format-btn" data-action="formatSendBody" title="Pretty-print the body as JSON (no-op if not valid JSON).">Format</button></label>'
            + '<textarea id="sf-body" name="body" placeholder="Request body (omit for GET/HEAD)" title="Body sent with the request. Omit for GET/HEAD. Content-Type defaults to application/json unless overridden via Headers."></textarea></div>'
            + '<div class="net-form-actions">'
            + '<button class="debug-btn debug-btn-primary" id="sf-send-btn" data-action="submitSendRequest" title="Dispatch through the app\'s authorized OkHttpClient. Goes through every real interceptor (auth, logging, debug capture). Self-requests to the debug server are rejected. The request is sent synchronously and the result is shown below.">Send</button>'
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
                + '<input id="' + keyId + '" name="headerKey" type="text" aria-label="Header name" placeholder="Header" value="' + debugEscapeHtml(h.key) + '" data-action="updateSendHeader" data-index="' + i + '" data-field="key" title="Header name (e.g. Content-Type, Accept).">'
                + '<input id="' + valueId + '" name="headerValue" type="text" aria-label="Header value" placeholder="Value" value="' + debugEscapeHtml(h.value) + '" data-action="updateSendHeader" data-index="' + i + '" data-field="value" title="Header value.">'
                + '<button type="button" class="debug-btn-icon danger" data-action="removeSendHeader" data-index="' + i + '" aria-label="Remove header row" title="Remove this header row.">✕</button>'
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
        try {
            ta.value = JSON.stringify(JSON.parse(ta.value), null, 2);
        } catch (e) {
            debugToast('Body is not valid JSON', 'warning');
        }
    };

    // Render the synchronous send result (or error) into the Send panel.
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
        try {
            ta.value = JSON.stringify(JSON.parse(raw), null, 2);
        } catch (e) {
            debugToast('Body is not valid JSON', 'warning');
        }
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
        debugFetch(netUrl('rules'), {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(rule),
        }).then(function() {
            debugToast(wasEdit ? 'Rule updated' : 'Rule added', 'success');
            editingRuleId = null;
            renderRuleForm();
            loadRules();
        }).catch(function(e) { debugToast('Failed to save rule: ' + e.message, 'error'); });
    };

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

    // ── Pause/Resume ──
    function updatePauseButton() {
        var btn = document.getElementById('pause-btn');
        if (!btn) return;
        btn.textContent = isPaused ? '▶ Resume' : '⏸ Pause';
        btn.classList.toggle('active', isPaused);
    }

    window.togglePause = function() {
        debugFetch(netUrl('pause'), { method: 'POST' })
            .then(function(r) { return r.json(); })
            .then(function(data) {
                // Mutations advance the cursor; read the new state. Pause may also
                // be reported via the cursor envelope's state on the next poll.
                if (data && data.paused !== undefined) isPaused = !!data.paused;
                else isPaused = !isPaused;
                updatePauseButton();
            })
            .catch(function(e) { debugToast('Failed to toggle pause: ' + e.message, 'error'); });
    };

    // ── Overwrite mode ──
    function updateOverwriteButton() {
        var btn = document.getElementById('overwrite-btn');
        if (!btn) return;
        btn.textContent = 'Overwrite: ' + (isOverwriteMode ? 'on' : 'off');
        btn.classList.toggle('active', isOverwriteMode);
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

    // ── Throttle ──
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

    // ── Clear ──
    window.clearTraffic = function() {
        debugFetch(netUrl('clear'), {method: 'POST'}).then(function() {
            allTransactions = [];
            selectedTxId = null;
            lastCursor = null;
            renderList();
            var dc = document.getElementById('detail-content');
            if (dc) dc.innerHTML =
                '<div class="net-empty-state"><div class="net-empty-icon">🔍</div><p>Select a request to inspect</p></div>';
        });
    };

    // ── Polling (opaque cursor envelope) ──
    // GET transactions?cursor=<opaque>&search=<q> →
    //   { cursor, status: "delta"|"unchanged"|"reset", items?, state: {paused, overwriteMode, throttleDelayMs} }
    // The cursor is opaque: we echo back the last one we received (omitted on the
    // first poll). `unchanged` carries no items and is a no-op; `reset` replaces
    // the whole list; `delta` carries the authoritative current list. Any UNKNOWN
    // status is treated as `reset`. Every mutation server-side advances the cursor.
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

    // ── Keyboard shortcuts ──
    document.addEventListener('keydown', function(e) {
        var t = document.activeElement;
        var inInput = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT');

        // Cmd/Ctrl+K → focus search
        if ((e.metaKey || e.ctrlKey) && (e.key === 'k' || e.key === 'K')) {
            e.preventDefault();
            var search = document.getElementById('search-input');
            if (search) { search.focus(); search.select(); }
            return;
        }
        // Esc → deselect transaction (only if not handled by modal)
        if (e.key === 'Escape' && !document.querySelector('.debug-modal.visible')) {
            if (selectedTxId) {
                selectedTxId = null;
                currentDetailTx = null;
                renderList();
                var dc = document.getElementById('detail-content');
                if (dc) dc.innerHTML =
                    '<div class="net-empty-state"><div class="net-empty-icon">🔍</div><p>Select a request to inspect</p></div>';
                var copyAll = document.getElementById('copy-all-btn');
                var copyCurl = document.getElementById('copy-curl-btn');
                if (copyAll) copyAll.style.display = 'none';
                if (copyCurl) copyCurl.style.display = 'none';
            }
            return;
        }
        // C → clear (when not in input)
        if (!inInput && (e.key === 'c' || e.key === 'C') && !e.metaKey && !e.ctrlKey && !e.altKey) {
            window.clearTraffic();
        }
    });

    // ── CSP-safe event delegation ──
    // The served HTML uses NO inline on*= handlers (the page's CSP forbids them:
    // script-src 'self', no 'unsafe-inline'). Instead, every interactive element
    // carries data-action="<name>" plus data-* payload, and a SMALL number of
    // delegated listeners on the tab container dispatch on data-action. Delegation
    // survives the dynamic re-rendering of the list/detail/rules/send panes.

    // Click actions keyed by data-action. Each receives (el, event); el is the
    // matched [data-action] element. stopPropagation is applied per-action below.
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
        copyCurl: function(el, ev) { window.copyCurl(ev); },
        copyAllDetail: function(el, ev) { window.copyAllDetail(ev); },
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
        // capture: <details> toggle events do not bubble.
        delegationRoot.addEventListener('toggle', onHeadersToggle, true);
    }

    // ── Init ──
    // The chrome serves an empty content shell; shared.js injects the
    // authenticated /_view HTML, then fires lustroOnContentReady. All
    // DOM-dependent setup (filters, resizers) and polling start there. When
    // shared.js is unavailable (older host), fall back to running immediately.
    function init() {
        setupDelegation();
        buildCategoryFilters();
        buildStatusFilters();
        buildMethodFilters();
        debugInitResizers();
        startPolling();
    }
    if (typeof window.lustroOnContentReady === 'function') {
        window.lustroOnContentReady(init);
    } else {
        init();
    }
})();
