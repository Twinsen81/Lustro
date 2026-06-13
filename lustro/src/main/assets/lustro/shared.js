// Shared runtime for tab URL helpers, auth bootstrap, theme, and utilities.
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

// The tab page starts as unauthenticated chrome. After fragment-token auth sets
// the cookie, this loads the authenticated _view fragment and then the tab's
// CSS/JS. Per-tab scripts register DOM init with lustroOnContentReady().
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

    var authInFlight = false;

    function authenticateWithToken(token, reloadContent) {
        if (authInFlight) return;
        authInFlight = true;
        // POST the fragment token once, strip it, then load content.
        fetch('/api/v1/_auth', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token: token })
        }).then(function() {
            stripHash();
            if (reloadContent !== false) loadTabContent();
        }).catch(function() {
            stripHash();
            if (reloadContent !== false) loadTabContent();
        }).then(function() {
            authInFlight = false;
        });
    }

    function bootstrap() {
        var token = tokenFromHash();
        if (token) {
            authenticateWithToken(token, true);
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

    window.addEventListener('hashchange', function() {
        var token = tokenFromHash();
        if (!token) return;
        // A hash-only navigation does not reload the document, so the initial
        // bootstrap will not run again after the unauthenticated shell is shown.
        var needsContent = !contentReady || !!document.querySelector('.lustro-auth-needed');
        authenticateWithToken(token, needsContent);
    });
})();

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
            // U+FE0E forces text (not emoji) presentation for the sun, so all three
            // glyphs render as monochrome text on platforms that default ☀ to emoji.
            var icon = pref === 'auto' ? '◑ Auto' : (pref === 'light' ? '☀︎ Light' : '◐ Dark');
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

// Compatibility hook for older tab scripts; restart detection uses fetch failures.
window.debugCheckSession = function() { /* no-op */ };

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
    // Both divider flavors are supported: the legacy .pane-divider (explicit
    // data-left/data-right element-id contract) and the .dc-divider component
    // (infers its two flanking panes from the DOM: pane | divider | pane).
    document.querySelectorAll('.pane-divider, .dc-divider').forEach(function(divider) {
        var isDragging = false;
        var startX = 0;
        var leftPane = null;
        var rightPane = null;
        var leftStartWidth = 0;
        var total = 0;

        function resolvePanes() {
            var left = divider.dataset.left
                ? document.getElementById(divider.dataset.left)
                : divider.previousElementSibling;
            var right = divider.dataset.right
                ? document.getElementById(divider.dataset.right)
                : divider.nextElementSibling;
            return { left: left, right: right };
        }

        // Read each pane's CSS min-width so the drag honors it. Clamping to a
        // hardcoded floor instead would let JS and CSS disagree: JS would write a
        // width below the CSS min-width, the pane would render at its min-width,
        // and the panes + divider would overflow the container (the divider then
        // detaching from the cursor).
        function minWidthOf(pane) {
            if (!pane) return 0;
            var v = parseFloat(window.getComputedStyle(pane).minWidth);
            return isNaN(v) ? 0 : v;
        }

        divider.addEventListener('mousedown', function(e) {
            var panes = resolvePanes();
            leftPane = panes.left;
            rightPane = panes.right;
            if (!leftPane || !rightPane) return;
            e.preventDefault();
            isDragging = true;
            startX = e.clientX;
            leftStartWidth = leftPane.offsetWidth;
            // The pair shares a fixed total: their combined width stays constant as
            // the divider moves, so resizing is a single split point between them.
            total = leftStartWidth + rightPane.offsetWidth;
            divider.classList.add('dragging');
            document.body.style.cursor = 'col-resize';
        });

        document.addEventListener('mousemove', function(e) {
            if (!isDragging) return;
            e.preventDefault();
            var leftMin = minWidthOf(leftPane);
            var rightMin = minWidthOf(rightPane);
            // Clamp the split so BOTH panes keep their CSS min-width while the pair
            // still sums to the original total — no overflow, divider tracks the
            // cursor until a pane hits its min.
            var newLeft = leftStartWidth + (e.clientX - startX);
            newLeft = Math.max(leftMin, Math.min(newLeft, total - rightMin));
            leftPane.style.width = newLeft + 'px';
            leftPane.style.flex = 'none';
            rightPane.style.width = (total - newLeft) + 'px';
            rightPane.style.flex = 'none';
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
