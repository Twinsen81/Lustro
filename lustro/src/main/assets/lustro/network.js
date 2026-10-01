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
            if (currentDetailTx) renderDetail(currentDetailTx);
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
            var sel = tx.id === selectedTxId ? ' dc-row--selected' : '';
            var mockedBadge = tx.isMocked ? ' <span class="dc-badge" style="--c: var(--ai)">Mocked</span>' : '';
            var streamingBadge = streaming ? ' <span class="dc-badge">Streaming</span>' : '';
            return '<tr class="dc-row' + sel + '" data-action="selectTransaction" data-tx-id="' + debugEscapeHtml(tx.id) + '">'
                + '<td class="dc-cell net-cell-method ' + methodClass(tx) + '">' + debugEscapeHtml(tx.method || '') + '</td>'
                + '<td class="dc-cell net-cell-url" title="' + debugEscapeHtml(pathOnly) + '">' + debugEscapeHtml(shortUrl) + '</td>'
                + '<td class="dc-cell net-cell-status ' + sc + '">' + statusText + mockedBadge + streamingBadge + '</td>'
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
        // A search or a refresh renders the same bodies again; keep what was folded.
        var previous = currentDetailTx;
        var folded = previous && previous.id === tx.id && previous.requestBody === tx.requestBody
            && previous.responseBody === tx.responseBody ? foldedNodes() : null;
        if (!previous || previous.id !== tx.id) hexDumps = {};
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
        html += '</div>';

        el.innerHTML = html;
        if (folded) refold(folded);
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
            // Cut at the capture cap, or text that only starts like JSON.
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

    window.switchRightTab = function(tab) {
        document.getElementById('detail-content').classList.toggle('active', tab === 'detail');
        document.getElementById('rules-content').classList.toggle('active', tab === 'rules');
        document.getElementById('send-content').classList.toggle('active', tab === 'send');
        document.getElementById('tab-btn-detail').classList.toggle('dc-tab--active', tab === 'detail');
        document.getElementById('tab-btn-rules').classList.toggle('dc-tab--active', tab === 'rules');
        document.getElementById('tab-btn-send').classList.toggle('dc-tab--active', tab === 'send');
        var detailHasTx = !!currentDetailTx;
        var copyAll = document.getElementById('copy-all-btn');
        var copyCurl = document.getElementById('copy-curl-btn');
        if (copyAll) copyAll.style.display = (tab === 'detail' && detailHasTx) ? '' : 'none';
        if (copyCurl) copyCurl.style.display = (tab === 'detail' && detailHasTx) ? '' : 'none';
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
            var dc = document.getElementById('detail-content');
            if (dc) dc.innerHTML =
                '<div class="net-empty-state"><div class="net-empty-icon">🔍</div><p>Select a request to inspect</p></div>';
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
    }
    if (typeof window.lustroOnContentReady === 'function') {
        window.lustroOnContentReady(init);
    } else {
        init();
    }
})();
