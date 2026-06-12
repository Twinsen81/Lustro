// Lustro shared debug UI runtime.
//
// Served as an EXTERNAL script (CSP-ready): there are no inline <script>-injected
// globals or %PLACEHOLDER% substitutions. The active tab id is read from
// `document.body[data-lustro-tab]`, and the versioned API base is derived from it.

// ═══ API base ═══
// The page sets <body data-lustro-tab="<id>">. All tab API calls go to
// `/api/v1/<id>/...`. lustroApiBase() returns that prefix (no trailing slash);
// lustroApiUrl(path) joins a sub-path onto it. These are exposed on window so
// per-tab scripts (e.g. network.js) reuse the exact same base.
window.lustroTabId = function() {
    return (document.body && document.body.dataset && document.body.dataset.lustroTab) || '';
};
window.lustroApiBase = function() {
    return '/api/v1/' + window.lustroTabId();
};
window.lustroApiUrl = function(path) {
    var base = window.lustroApiBase();
    if (path == null || path === '') return base;
    return base + (path.charAt(0) === '/' ? '' : '/') + path;
};

window.debugSetStatus = function(state) {
    const el = document.getElementById('status');
    if (!el) return;
    if (state === 'connected') {
        el.textContent = 'Live';
        el.className = 'status connected';
    } else if (state === 'error') {
        el.textContent = 'Error';
        el.className = 'status error';
    } else {
        el.textContent = 'Disconnected';
        el.className = 'status disconnected';
    }
};

// ═══ Auth bootstrap + tab-content loading ═══
//
// The server now serves only the framework CHROME for /tab/<id> (tab bar +
// connecting/authorizing shell, NO tab content or data). Tab content is loaded
// here, AFTER authentication, from the authenticated /api/v1/<id>/_view endpoint.
//
// A browser becomes authenticated by setting the `lustro_token` cookie via
// POST /api/v1/_auth. The token arrives in the URL fragment (#lustro_token=<t>
// or #token=<t>) when opened via `lustro open`; we POST it once, strip the
// fragment from the address bar, then load the content. Programmatic clients use
// `Authorization: Bearer <token>` instead and never hit this path.
//
// Per-tab scripts (e.g. network.js) register their DOM-dependent init via
// window.lustroOnContentReady(fn); those callbacks fire only once the _view HTML
// has been injected into #lustro-tab-content. If the API returns 401, we show a
// short instruction instead of erroring out.
(function() {
    var contentReadyCallbacks = [];
    var contentReady = false;

    // Per-tab scripts register here. If content is already injected (late
    // registration), the callback fires immediately.
    window.lustroOnContentReady = function(fn) {
        if (typeof fn !== 'function') return;
        if (contentReady) { try { fn(); } catch(e) {} return; }
        contentReadyCallbacks.push(fn);
    };

    function fireContentReady() {
        if (contentReady) return;
        contentReady = true;
        contentReadyCallbacks.forEach(function(fn) {
            try { fn(); } catch(e) {}
        });
        contentReadyCallbacks.length = 0;
    }

    function contentRoot() {
        return document.getElementById('lustro-tab-content');
    }

    function showAuthNeeded() {
        var root = contentRoot();
        window.debugSetStatus('error');
        if (!root) return;
        root.innerHTML =
            '<div class="lustro-auth-needed">' +
            '<p><strong>Not authorized.</strong></p>' +
            '<p>Open this console via <code>lustro open</code>, or append ' +
            '<code>#lustro_token=&lt;token&gt;</code> to the URL.</p>' +
            '</div>';
    }

    // Pull a token out of the URL fragment (#lustro_token=<t> or #token=<t>).
    function tokenFromHash() {
        var hash = (window.location.hash || '').replace(/^#/, '');
        if (!hash) return null;
        var pairs = hash.split('&');
        for (var i = 0; i < pairs.length; i++) {
            var kv = pairs[i].split('=');
            var key = decodeURIComponent(kv[0] || '');
            if (key === 'lustro_token' || key === 'token') {
                var val = decodeURIComponent(kv.slice(1).join('=') || '');
                if (val) return val;
            }
        }
        return null;
    }

    function stripHash() {
        try {
            var url = window.location.pathname + window.location.search;
            window.history.replaceState(null, '', url);
        } catch(e) {
            // Best-effort: clearing the hash directly still removes it from view.
            try { window.location.hash = ''; } catch(e2) {}
        }
    }

    // Inject the tab's own (auth-gated) CSS + JS AFTER the auth cookie is set, so
    // the requests carry the cookie. The page chrome deliberately does NOT load
    // these as static tags — those would fire during pre-auth HTML parse and 401.
    // The tab script registers its DOM init via lustroOnContentReady; since the
    // content is already injected by the time the script loads, that init fires
    // immediately via the late-registration path.
    function injectTabAssets(tabId) {
        if (!document.getElementById('lustro-view-css')) {
            var link = document.createElement('link');
            link.id = 'lustro-view-css';
            link.rel = 'stylesheet';
            link.href = '/api/v1/' + tabId + '/_view.css';
            document.head.appendChild(link);
        }
        if (!document.getElementById('lustro-view-js')) {
            var script = document.createElement('script');
            script.id = 'lustro-view-js';
            script.src = '/api/v1/' + tabId + '/_view.js';
            document.body.appendChild(script);
        }
    }

    // Fetch the authenticated tab content and inject it, then fire init hooks.
    function loadTabContent() {
        var root = contentRoot();
        if (!root) { fireContentReady(); return; }
        var tabId = window.lustroTabId();
        if (!tabId) { fireContentReady(); return; }
        fetch('/api/v1/' + tabId + '/_view', { credentials: 'same-origin' })
            .then(function(resp) {
                if (resp.status === 401) { showAuthNeeded(); return null; }
                if (!resp.ok) throw new Error('HTTP ' + resp.status);
                return resp.text();
            })
            .then(function(html) {
                if (html == null) return; // 401 already handled
                root.innerHTML = html;
                injectTabAssets(tabId);
                window.debugSetStatus('connected');
                fireContentReady();
            })
            .catch(function() {
                window.debugSetStatus('error');
            });
    }

    function bootstrap() {
        var token = tokenFromHash();
        if (token) {
            // POST the fragment token once, strip it, then load content.
            fetch('/api/v1/_auth', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: token })
            }).then(function() {
                stripHash();
                loadTabContent();
            }).catch(function() {
                stripHash();
                loadTabContent();
            });
        } else {
            // No fragment: rely on an existing cookie. The _view call 401s (and
            // shows the instruction) when no valid cookie is present.
            loadTabContent();
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', bootstrap);
    } else {
        bootstrap();
    }
})();

// ═══ Theme (auto / light / dark) ═══
(function() {
    var STORAGE_KEY = 'debug-theme';
    var systemQuery = window.matchMedia('(prefers-color-scheme: light)');

    function getStored() {
        try { return localStorage.getItem(STORAGE_KEY) || 'auto'; }
        catch(e) { return 'auto'; }
    }
    function setStored(value) {
        try { localStorage.setItem(STORAGE_KEY, value); } catch(e) {}
    }
    function applyTheme(pref) {
        var effective = pref === 'auto' ? (systemQuery.matches ? 'light' : 'dark') : pref;
        document.documentElement.setAttribute('data-theme', effective);
        var btn = document.getElementById('theme-toggle');
        if (btn) {
            var icon = pref === 'auto' ? '◑ Auto' : (pref === 'light' ? '☀ Light' : '◐ Dark');
            btn.textContent = icon;
            var explain = pref === 'auto' ? 'follows your system preference' : pref;
            btn.title = 'Theme: ' + pref + ' (' + explain + '). Click to cycle: auto → light → dark.';
        }
    }
    function cycle() {
        var current = getStored();
        var next = current === 'auto' ? 'light' : (current === 'light' ? 'dark' : 'auto');
        setStored(next);
        applyTheme(next);
    }

    // Apply immediately so the page never flashes the wrong theme.
    applyTheme(getStored());

    // Re-apply when system pref changes (only matters in 'auto').
    if (systemQuery.addEventListener) {
        systemQuery.addEventListener('change', function() {
            if (getStored() === 'auto') applyTheme('auto');
        });
    } else if (systemQuery.addListener) {
        systemQuery.addListener(function() {
            if (getStored() === 'auto') applyTheme('auto');
        });
    }

    // Wire up toggle button when DOM is ready.
    function wireToggle() {
        var btn = document.getElementById('theme-toggle');
        if (btn) {
            applyTheme(getStored());
            btn.addEventListener('click', cycle);
        }
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', wireToggle);
    } else {
        wireToggle();
    }
})();

// ═══ Session / restart detection — DEFERRED ═══
// Earlier versions polled `/api/session` (with an inline-injected session id)
// to reload the page after an app restart. Session identity is not yet enforced;
// this is a no-op stub for now. The disconnect overlay below is instead driven
// purely by fetch failures (see debugPoll / .disconnect-overlay).
window.debugCheckSession = function() { /* no-op */ };

// ═══ Shared Debug Utilities ═══

// Toast container
(function() {
    var c = document.createElement('div');
    c.className = 'debug-toast-container';
    document.body.appendChild(c);
})();

window.debugToast = function(message, type) {
    type = type || 'info';
    var container = document.querySelector('.debug-toast-container');
    var toast = document.createElement('div');
    toast.className = 'debug-toast ' + type;
    toast.textContent = message;
    container.appendChild(toast);
    setTimeout(function() { toast.remove(); }, 3000);
};

window.debugEscapeHtml = function(text) {
    if (text == null) return '';
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
};

window.debugWriteToClipboard = function(text) {
    text = String(text == null ? '' : text);
    if (navigator.clipboard && window.isSecureContext) {
        return navigator.clipboard.writeText(text).catch(function() {
            return debugCopyToClipboardFallback(text);
        });
    }
    return debugCopyToClipboardFallback(text);
};

function debugCopyToClipboardFallback(text) {
    return new Promise(function(resolve, reject) {
        var textarea = document.createElement('textarea');
        var selection = document.getSelection();
        var selectedRange = selection && selection.rangeCount > 0 ? selection.getRangeAt(0) : null;
        textarea.value = text;
        textarea.setAttribute('readonly', '');
        textarea.style.position = 'fixed';
        textarea.style.left = '-9999px';
        textarea.style.top = '-9999px';
        document.body.appendChild(textarea);
        textarea.focus();
        textarea.select();
        textarea.setSelectionRange(0, textarea.value.length);

        var copied = false;
        try {
            copied = document.execCommand('copy');
        } catch(e) {
            copied = false;
        }

        document.body.removeChild(textarea);
        if (selection && selectedRange) {
            selection.removeAllRanges();
            selection.addRange(selectedRange);
        }

        if (copied) {
            resolve();
        } else {
            reject(new Error('Copy command failed'));
        }
    });
}

window.debugCopyToClipboard = function(text) {
    return window.debugWriteToClipboard(text).then(function() {
        window.debugToast('Copied to clipboard', 'success');
    }).catch(function() {
        window.debugToast('Failed to copy', 'error');
    });
};

window.debugFormatJson = function(obj, indent) {
    indent = indent || 2;
    try {
        if (typeof obj === 'string') obj = JSON.parse(obj);
        return JSON.stringify(obj, null, indent);
    } catch(e) {
        return String(obj);
    }
};

// JetBrains-style JSON syntax highlighter. Returns HTML safe to drop into
// innerHTML of a <pre class="debug-json">. Falls back to escaped plain text
// when the input isn't valid JSON. Optional options.searchText wraps matches
// in <mark> inside the generated spans.
window.debugSyntaxHighlightJson = function(jsonString, options) {
    options = options || {};
    var indent = options.indent || 2;
    var searchText = options.searchText || '';
    var parsed;
    try {
        parsed = typeof jsonString === 'string' ? JSON.parse(jsonString) : jsonString;
    } catch(e) {
        return debugHighlightPlain(String(jsonString == null ? '' : jsonString), searchText);
    }
    return walkValue(parsed, 0, indent, searchText);

    function walkValue(value, depth, indent, searchText) {
        if (value === null) return '<span class="json-null">null</span>';
        var t = typeof value;
        if (t === 'boolean') return '<span class="json-boolean">' + value + '</span>';
        if (t === 'number') return '<span class="json-number">' + escapeAndMark(String(value), searchText) + '</span>';
        if (t === 'string') return '<span class="json-string">"' + escapeStringInner(value, searchText) + '"</span>';
        if (Array.isArray(value)) return walkArray(value, depth, indent, searchText);
        if (t === 'object') return walkObject(value, depth, indent, searchText);
        return escapeAndMark(String(value), searchText);
    }

    function walkArray(arr, depth, indent, searchText) {
        if (arr.length === 0) return '<span class="json-bracket">[]</span>';
        var pad = repeat(' ', (depth + 1) * indent);
        var endPad = repeat(' ', depth * indent);
        var items = arr.map(function(v) {
            return pad + walkValue(v, depth + 1, indent, searchText);
        }).join(',\n');
        return '<span class="json-bracket">[</span>\n' + items + '\n' + endPad + '<span class="json-bracket">]</span>';
    }

    function walkObject(obj, depth, indent, searchText) {
        var keys = Object.keys(obj);
        if (keys.length === 0) return '<span class="json-bracket">{}</span>';
        var pad = repeat(' ', (depth + 1) * indent);
        var endPad = repeat(' ', depth * indent);
        var items = keys.map(function(k) {
            return pad
                + '<span class="json-key">"' + escapeStringInner(k, searchText) + '"</span>: '
                + walkValue(obj[k], depth + 1, indent, searchText);
        }).join(',\n');
        return '<span class="json-bracket">{</span>\n' + items + '\n' + endPad + '<span class="json-bracket">}</span>';
    }

    function escapeStringInner(s, searchText) {
        // Use JSON.stringify to handle \", \\, \n, control chars, then HTML-escape.
        var jsonEscaped = JSON.stringify(String(s)).slice(1, -1);
        return escapeAndMark(jsonEscaped, searchText);
    }

    function escapeAndMark(text, searchText) {
        var escaped = String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        if (searchText) escaped = highlightMatches(escaped, searchText);
        return escaped;
    }

    function repeat(s, n) {
        return n > 0 ? new Array(n + 1).join(s) : '';
    }
};

function debugHighlightPlain(text, searchText) {
    var escaped = String(text == null ? '' : text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
    if (searchText) escaped = highlightMatches(escaped, searchText);
    return escaped;
}
window.debugHighlightPlain = debugHighlightPlain;

function highlightMatches(html, searchText) {
    if (!searchText) return html;
    var escapedQuery = String(searchText)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    if (!escapedQuery) return html;
    try {
        var re = new RegExp(escapedQuery, 'gi');
        return html.replace(re, function(match) { return '<mark>' + match + '</mark>'; });
    } catch(e) {
        return html;
    }
}

window.debugModal = function(modalId) {
    var el = document.getElementById(modalId);
    if (!el) return { show: function(){}, hide: function(){}, toggle: function(){}, isVisible: function(){ return false; } };
    el.addEventListener('mousedown', function(e) {
        if (e.target === el) el.classList.remove('visible');
    });
    return {
        show: function() { el.classList.add('visible'); },
        hide: function() { el.classList.remove('visible'); },
        toggle: function() { el.classList.toggle('visible'); },
        isVisible: function() { return el.classList.contains('visible'); }
    };
};

document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape') {
        document.querySelectorAll('.debug-modal.visible').forEach(function(m) {
            m.classList.remove('visible');
        });
    }
});

window.debugFetch = async function(url, options) {
    try {
        var resp = await fetch(url, options || {});
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        window.debugSetStatus('connected');
        return resp;
    } catch(e) {
        window.debugSetStatus('error');
        throw e;
    }
};

window.debugPoll = function(endpointOrFn, intervalMs, callback) {
    var consecutiveFailures = 0;
    var MAX_FAILURES = 3;
    var SLOW_INTERVAL = 5000;
    var stopped = false;
    var isConnected = false;

    async function tick() {
        if (stopped) return;
        try {
            var url = typeof endpointOrFn === 'function' ? endpointOrFn() : endpointOrFn;
            var resp = await fetch(url);
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            var data = await resp.json();
            consecutiveFailures = 0;
            if (!isConnected) {
                isConnected = true;
                window.debugSetStatus('connected');
            }
            callback(data);
        } catch(e) {
            consecutiveFailures++;
            if (consecutiveFailures >= MAX_FAILURES && isConnected) {
                isConnected = false;
                window.debugSetStatus('error');
            }
        }
        if (!stopped) {
            setTimeout(tick, isConnected ? intervalMs : SLOW_INTERVAL);
        }
    }

    tick();
    return { stop: function() { stopped = true; } };
};

window.debugInitResizers = function() {
    document.querySelectorAll('.pane-divider').forEach(function(divider) {
        var isDragging = false;
        var startX = 0;
        var leftPane = null;
        var rightPane = null;
        var leftStartWidth = 0;
        var rightStartWidth = 0;

        divider.addEventListener('mousedown', function(e) {
            e.preventDefault();
            isDragging = true;
            startX = e.clientX;
            leftPane = document.getElementById(divider.dataset.left);
            rightPane = document.getElementById(divider.dataset.right);
            if (leftPane) leftStartWidth = leftPane.offsetWidth;
            if (rightPane) rightStartWidth = rightPane.offsetWidth;
            divider.classList.add('dragging');
            document.body.style.cursor = 'col-resize';
        });

        document.addEventListener('mousemove', function(e) {
            if (!isDragging) return;
            e.preventDefault();
            var delta = e.clientX - startX;
            var newLeftWidth = Math.max(200, leftStartWidth + delta);
            var newRightWidth = Math.max(200, rightStartWidth - delta);
            if (newLeftWidth >= 200 && newRightWidth >= 200) {
                if (leftPane) { leftPane.style.width = newLeftWidth + 'px'; leftPane.style.flex = 'none'; }
                if (rightPane) { rightPane.style.width = newRightWidth + 'px'; rightPane.style.flex = 'none'; }
            }
        });

        document.addEventListener('mouseup', function() {
            if (isDragging) {
                isDragging = false;
                divider.classList.remove('dragging');
                document.body.style.cursor = '';
            }
        });
    });
};
