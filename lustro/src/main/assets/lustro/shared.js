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
    var label = 'Disconnected';
    if (state === 'connected') label = 'Live';
    else if (state === 'error') label = 'Error';
    el.className = 'dc-pill ' + (state === 'connected' ? 'dc-pill--live' : 'dc-pill--danger');
    el.innerHTML = '<span class="dc-pill__dot"></span>' + label;
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
    c.className = 'dc-toasts';
    document.body.appendChild(c);
})();

window.debugToast = function(message, type) {
    type = type || 'info';
    var container = document.querySelector('.dc-toasts');
    var toast = document.createElement('div');
    toast.className = 'dc-toast dc-toast--' + type;
    toast.textContent = message;
    container.appendChild(toast);
    setTimeout(function() { toast.remove(); }, 3000);
};

window.debugEscapeHtml = function(text) {
    if (text == null) return '';
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
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

// Splits JSON source text into the pieces a pretty-printer emits, WITHOUT
// rewriting any of them: every key, string, number, and literal is the exact
// slice of the input, and only whitespace between them is the printer's own.
// A JSON.parse + JSON.stringify round-trip can't do that on a captured body —
// integers past 2^53 lose their last digits, `1.10` and `1e2` normalize, a
// `\uXXXX` escape decodes, and a repeated key keeps only its last value — and a
// body is shown and copied exactly to answer questions about those details.
//
// Returns an array of {cls, text}, where cls is the piece's .dc-json span class
// (k, s, n, b or p) or null for the printer's own punctuation and indentation,
// or null when the text is not a single valid JSON value, so callers can fall
// back. The grammar accepted is JSON.parse's.
function debugScanJsonSource(src, indent) {
    if (typeof src !== 'string') return null;
    var i = 0;
    var out = [];
    var NUMBER = /-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?/y;
    try {
        skipWs();
        value(0);
        skipWs();
        return i === src.length ? out : null;
    } catch(e) {
        return null;
    }

    function fail() { throw new Error('not json'); }

    function push(cls, text) { out.push({ cls: cls, text: text }); }

    function pad(depth) { return depth * indent > 0 ? new Array(depth * indent + 1).join(' ') : ''; }

    function skipWs() {
        while (i < src.length) {
            var c = src.charAt(i);
            if (c !== ' ' && c !== '\t' && c !== '\n' && c !== '\r') return;
            i++;
        }
    }

    function value(depth) {
        var c = src.charAt(i);
        if (c === '{') return object(depth);
        if (c === '[') return array(depth);
        if (c === '"') return push('s', string());
        if (c === '-' || (c >= '0' && c <= '9')) return push('n', number());
        if (src.substr(i, 4) === 'true') { i += 4; return push('b', 'true'); }
        if (src.substr(i, 5) === 'false') { i += 5; return push('b', 'false'); }
        if (src.substr(i, 4) === 'null') { i += 4; return push('b', 'null'); }
        fail();
    }

    function object(depth) {
        i++;
        skipWs();
        if (src.charAt(i) === '}') { i++; return push('p', '{}'); }
        push('p', '{');
        for (var n = 0; ; n++) {
            push(null, (n === 0 ? '\n' : ',\n') + pad(depth + 1));
            skipWs();
            if (src.charAt(i) !== '"') fail();
            push('k', string());
            skipWs();
            if (src.charAt(i) !== ':') fail();
            i++;
            push(null, ': ');
            skipWs();
            value(depth + 1);
            skipWs();
            if (src.charAt(i) === ',') { i++; continue; }
            if (src.charAt(i) === '}') { i++; break; }
            fail();
        }
        push(null, '\n' + pad(depth));
        push('p', '}');
    }

    function array(depth) {
        i++;
        skipWs();
        if (src.charAt(i) === ']') { i++; return push('p', '[]'); }
        push('p', '[');
        for (var n = 0; ; n++) {
            push(null, (n === 0 ? '\n' : ',\n') + pad(depth + 1));
            skipWs();
            value(depth + 1);
            skipWs();
            if (src.charAt(i) === ',') { i++; continue; }
            if (src.charAt(i) === ']') { i++; break; }
            fail();
        }
        push(null, '\n' + pad(depth));
        push('p', ']');
    }

    function string() {
        var start = i;
        i++;
        while (i < src.length) {
            var c = src.charAt(i);
            if (c === '"') { i++; return src.slice(start, i); }
            if (c === '\\') {
                var escape = src.charAt(i + 1);
                if ('"\\/bfnrt'.indexOf(escape) >= 0) { i += 2; continue; }
                if (escape === 'u' && /^[0-9a-fA-F]{4}$/.test(src.substr(i + 2, 4))) { i += 6; continue; }
                fail();
            }
            if (c < ' ') fail();
            i++;
        }
        fail();
    }

    function number() {
        NUMBER.lastIndex = i;
        var match = NUMBER.exec(src);
        if (!match) fail();
        i = NUMBER.lastIndex;
        return match[0];
    }
}
window.debugScanJsonSource = debugScanJsonSource;

// Pretty-prints JSON text as it arrived: indentation is the only thing added.
// Anything that isn't a single valid JSON value (and any non-string input) goes
// through JSON.parse/stringify as before, or comes back as-is.
window.debugFormatJson = function(obj, indent) {
    indent = indent || 2;
    var pieces = debugScanJsonSource(obj, indent);
    if (pieces) {
        return pieces.map(function(piece) { return piece.text; }).join('');
    }
    try {
        if (typeof obj === 'string') obj = JSON.parse(obj);
        return JSON.stringify(obj, null, indent);
    } catch(e) {
        return String(obj);
    }
};

// JetBrains-style JSON syntax highlighter. Returns HTML safe to drop into
// innerHTML of a <pre class="dc-json">. Falls back to escaped plain text
// when the input isn't valid JSON. Optional options.searchText wraps matches
// in <mark> inside the generated spans.
window.debugSyntaxHighlightJson = function(jsonString, options) {
    options = options || {};
    var indent = options.indent || 2;
    var searchText = options.searchText || '';
    // Highlight the body's own text, so what's on screen is what arrived (see
    // debugScanJsonSource). Only input that isn't JSON source — an already-parsed
    // object — is walked as a tree.
    var pieces = debugScanJsonSource(jsonString, indent);
    if (pieces) {
        return pieces.map(function(piece) {
            var text = escapeAndMark(piece.text, piece.cls ? searchText : '');
            return piece.cls ? '<span class="' + piece.cls + '">' + text + '</span>' : text;
        }).join('');
    }
    var parsed;
    try {
        parsed = typeof jsonString === 'string' ? JSON.parse(jsonString) : jsonString;
    } catch(e) {
        return debugHighlightPlain(String(jsonString == null ? '' : jsonString), searchText);
    }
    return walkValue(parsed, 0, indent, searchText);

    function walkValue(value, depth, indent, searchText) {
        if (value === null) return '<span class="b">null</span>';
        var t = typeof value;
        if (t === 'boolean') return '<span class="b">' + value + '</span>';
        if (t === 'number') return '<span class="n">' + escapeAndMark(String(value), searchText) + '</span>';
        if (t === 'string') return '<span class="s">"' + escapeStringInner(value, searchText) + '"</span>';
        if (Array.isArray(value)) return walkArray(value, depth, indent, searchText);
        if (t === 'object') return walkObject(value, depth, indent, searchText);
        return escapeAndMark(String(value), searchText);
    }

    function walkArray(arr, depth, indent, searchText) {
        if (arr.length === 0) return '<span class="p">[]</span>';
        var pad = repeat(' ', (depth + 1) * indent);
        var endPad = repeat(' ', depth * indent);
        var items = arr.map(function(v) {
            return pad + walkValue(v, depth + 1, indent, searchText);
        }).join(',\n');
        return '<span class="p">[</span>\n' + items + '\n' + endPad + '<span class="p">]</span>';
    }

    function walkObject(obj, depth, indent, searchText) {
        var keys = Object.keys(obj);
        if (keys.length === 0) return '<span class="p">{}</span>';
        var pad = repeat(' ', (depth + 1) * indent);
        var endPad = repeat(' ', depth * indent);
        var items = keys.map(function(k) {
            return pad
                + '<span class="k">"' + escapeStringInner(k, searchText) + '"</span>: '
                + walkValue(obj[k], depth + 1, indent, searchText);
        }).join(',\n');
        return '<span class="p">{</span>\n' + items + '\n' + endPad + '<span class="p">}</span>';
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

// The body viewers below each take a captured body's text and return the HTML
// of one element. They escape every character of the body, and only the
// viewer's own spans are markup; options.searchText wraps matches in <mark>.

// The raster types a browser draws in an <img>. The body route serves only
// these inline, so they are the only bytes the console can preview.
var DEBUG_PREVIEW_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp'];

// Which viewer suits a body: 'image' for a previewable image kept as bytes,
// 'binary' for any other body kept as bytes, then by the media type 'json',
// 'form', 'html', 'xml', or 'text'. A JSON type wins even over text that is
// not JSON, such as a body cut at the capture cap, and a caller shows that as
// text. An object or array that is valid JSON is 'json' whatever the type
// says, because servers send JSON as text/html, and apps send it as a form.
window.debugBodyKind = function(contentType, text, binary) {
    var essence = String(contentType == null ? '' : contentType).split(';')[0].trim().toLowerCase();
    if (binary) return DEBUG_PREVIEW_IMAGE_TYPES.indexOf(essence) >= 0 ? 'image' : 'binary';
    var subtype = essence.slice(essence.indexOf('/') + 1);
    if (subtype === 'json' || /\+json$/.test(subtype) || debugIsJsonContainer(text)) return 'json';
    if (essence === 'application/x-www-form-urlencoded') return 'form';
    if (essence === 'text/html') return 'html';
    if (subtype === 'xml' || /\+xml$/.test(subtype)) return 'xml';
    return 'text';
};

// JSON.parse accepts the grammar debugScanJsonSource does, and answers sooner.
function debugIsJsonContainer(text) {
    if (typeof text !== 'string' || !/^\s*[{[]/.test(text)) return false;
    try {
        JSON.parse(text);
        return true;
    } catch(e) {
        return false;
    }
}

// JSON text as a tree that folds by object and array. Expanded, it is the text
// debugSyntaxHighlightJson shows, built from the same debugScanJsonSource
// pieces. Each object or array with members has a .dc-fold, its first line up
// to the bracket, followed by a .dc-fold__body with the rest up to the closing
// bracket; folded, the first line counts what the body hides. Returns a
// <pre class="dc-code dc-json dc-tree">, or null when the text is not JSON.
// shared.js handles the clicks that fold and unfold it.
window.debugJsonTree = function(src, options) {
    options = options || {};
    var pieces = debugScanJsonSource(src, options.indent || 2);
    if (!pieces) return null;
    var searchText = options.searchText || '';
    var out = [];
    // One frame per open object or array: where its .dc-fold tag goes in out,
    // and how many members it has, which is only known when it closes.
    var stack = [];
    for (var i = 0; i < pieces.length; i++) {
        var piece = pieces[i];
        var top = stack[stack.length - 1];
        if (piece.cls === 'k') {
            top.count++;
            if (opens(pieces[i + 2])) {
                // A member whose value is an object or array folds from its key.
                openFold(pieces[i + 2]);
                out.push(token(piece), plain(pieces[i + 1].text), token(pieces[i + 2]), '</span><span class="dc-fold__body">');
                i += 2;
            } else {
                out.push(token(piece));
            }
        } else if (opens(piece)) {
            if (top) top.count++;
            openFold(piece);
            out.push(token(piece), '</span><span class="dc-fold__body">');
        } else if (piece.cls === 'p' && (piece.text === '}' || piece.text === ']')) {
            var frame = stack.pop();
            var noun = frame.object ? 'key' : 'item';
            out[frame.head] = '<span class="dc-fold" data-count="' + frame.count + ' ' + noun + (frame.count === 1 ? '' : 's') + '">';
            out.push('</span>', token(piece));
        } else {
            // In an object a value was counted at its key; in an array, here.
            if (piece.cls && top && !top.object) top.count++;
            out.push(piece.cls ? token(piece) : plain(piece.text));
        }
    }
    return '<pre class="dc-code dc-json dc-tree">' + out.join('') + '</pre>';

    function opens(p) {
        return !!p && p.cls === 'p' && (p.text === '{' || p.text === '[');
    }
    function openFold(bracket) {
        stack.push({ head: out.length, count: 0, object: bracket.text === '{' });
        out.push('');
    }
    function token(p) {
        return '<span class="' + p.cls + '">' + debugHighlightPlain(p.text, searchText) + '</span>';
    }
    function plain(text) {
        return debugHighlightPlain(text, '');
    }
};

// Folding for every .dc-tree on the page. A click that ends a text selection
// leaves the tree alone, so a key can still be selected and copied; Alt-click
// also folds or unfolds everything inside the node.
document.addEventListener('click', function(e) {
    var fold = e.target && e.target.closest ? e.target.closest('.dc-tree .dc-fold') : null;
    if (!fold) return;
    var selection = document.getSelection();
    if (selection && !selection.isCollapsed) return;
    var close = !fold.classList.contains('dc-fold--closed');
    if (e.altKey && fold.nextElementSibling) {
        fold.nextElementSibling.querySelectorAll('.dc-fold').forEach(function(inner) {
            inner.classList.toggle('dc-fold--closed', close);
        });
    }
    fold.classList.toggle('dc-fold--closed', close);
});

// XML or HTML text, indented. As in the JSON viewer, whitespace is the only
// thing added: every tag, comment, and run of text is the exact slice of the
// input. An element that holds text, even in part, is shown as it arrived,
// because its whitespace is part of the text. An element that holds only
// elements and the like gets one line for each, and the whitespace between
// them, the input's own layout, gives way to the printer's. options.html
// applies HTML's rules: elements such as <br> have no end tag, an open <li> or
// <p> ends at the next one, and the text of <script>, <style>, <pre>,
// <textarea>, and <title> is not markup. Returns a
// <pre class="dc-code dc-markup">.
window.debugHighlightMarkup = function(src, options) {
    options = options || {};
    var html = !!options.html;
    var searchText = options.searchText || '';
    var indent = options.indent || 2;
    var root = debugMarkupTree(debugScanMarkup(String(src == null ? '' : src), html), html);
    var out = [];
    root.children.forEach(function(node) { print(node, 0); });
    return '<pre class="dc-code dc-markup">' + out.join('') + '</pre>';

    function print(node, depth) {
        if (node.type === 'element' && holdsText(node)) {
            line(depth, verbatim(node));
        } else if (node.type === 'element') {
            line(depth, highlight(node.open));
            node.children.forEach(function(child) { print(child, depth + 1); });
            if (node.close) line(depth, highlight(node.close));
        } else if (node.type !== 'text' || node.cut) {
            line(depth, highlight(node));
        } else if (/[^ \t\n\r\f]/.test(node.text)) {
            // Text outside every element, which belongs to none of them.
            line(depth, highlight({ type: 'text', text: node.text.replace(/^[ \t\n\r\f]+|[ \t\n\r\f]+$/g, '') }));
        }
    }
    // Text in XML's sense, which is also HTML's: a no-break space is text, and
    // a CDATA section is. An element with nothing in it, or only whitespace,
    // holds text too, so it is shown as it arrived.
    function holdsText(element) {
        var markup = false;
        for (var i = 0; i < element.children.length; i++) {
            var child = element.children[i];
            if (child.type === 'text' && !child.cut) {
                if (child.raw || /[^ \t\n\r\f]/.test(child.text)) return true;
            } else if (child.type === 'other' && child.text.startsWith('<![CDATA[')) {
                return true;
            } else {
                markup = true;
            }
        }
        return !markup;
    }
    function verbatim(node) {
        if (node.type !== 'element') return highlight(node);
        return highlight(node.open) + node.children.map(verbatim).join('') + (node.close ? highlight(node.close) : '');
    }
    function line(depth, htmlText) {
        if (out.length) out.push('\n' + new Array(depth * indent + 1).join(' '));
        out.push(htmlText);
    }
    function highlight(tok) {
        if (tok.type === 'text') return debugHighlightPlain(tok.text, searchText);
        if (tok.type === 'other') return '<span class="c">' + debugHighlightPlain(tok.text, searchText) + '</span>';
        // A tag is searched as a whole, so a query such as type="all" or <title
        // is marked across the spans of its parts.
        var matches = debugMatchRanges(tok.pieces.map(function(p) { return p.text; }).join(''), searchText);
        var offset = 0;
        return '<span class="g">' + tok.pieces.map(function(p) {
            var marked = markSlice(p.text, offset, matches);
            offset += p.text.length;
            return p.cls ? '<span class="' + p.cls + '">' + marked + '</span>' : marked;
        }).join('') + '</span>';
    }
    // The part of the matches that falls in text, which starts at offset.
    function markSlice(text, offset, matches) {
        var out = '';
        var at = 0;
        matches.forEach(function(match) {
            var start = Math.max(match[0] - offset, at);
            var end = Math.min(match[1] - offset, text.length);
            if (end <= start) return;
            out += debugHighlightPlain(text.slice(at, start), '')
                + '<mark>' + debugHighlightPlain(text.slice(start, end), '') + '</mark>';
            at = end;
        });
        return out + debugHighlightPlain(text.slice(at), '');
    }
};

// Where searchText occurs in text, as [start, end) pairs, with the matching
// highlightMatches uses: literal and case-insensitive.
function debugMatchRanges(text, searchText) {
    var matches = [];
    if (!searchText) return matches;
    var re = new RegExp(String(searchText).replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi');
    for (var match = re.exec(text); match; match = re.exec(text)) {
        matches.push([match.index, match.index + match[0].length]);
    }
    return matches;
}

// Elements a body leaves open, such as an XML body's <br>s, nest deeper with
// each one. Past this depth an element takes no children, so what follows it
// stays at its depth: the lines stop moving right, and the output and the
// printer's recursion stay bounded.
var DEBUG_MARKUP_MAX_DEPTH = 32;

// The elements of debugScanMarkup's tokens, as the printer needs them: an
// element is { type: 'element', open, close, children }, and the children are
// elements and tokens. An end tag closes the innermost open element of its
// name, and the ones inside it; one with no open element of its name is a
// child like any token.
function debugMarkupTree(tokens, html) {
    var root = { type: 'element', children: [] };
    var stack = [root];
    tokens.forEach(function(tok) {
        var top = stack[stack.length - 1];
        if (tok.type === 'open') {
            if (html) {
                var ends = DEBUG_HTML_IMPLIED_END[tok.name];
                while (ends && stack.length > 1 && ends.indexOf(stack[stack.length - 1].open.name) >= 0) stack.pop();
                top = stack[stack.length - 1];
            }
            if (tok.selfClosing || (html && DEBUG_HTML_VOID[tok.name])) {
                top.children.push(tok);
                return;
            }
            var element = { type: 'element', open: tok, close: null, children: [] };
            top.children.push(element);
            if (stack.length <= DEBUG_MARKUP_MAX_DEPTH) stack.push(element);
        } else if (tok.type === 'close') {
            var at = stack.length - 1;
            while (at > 0 && stack[at].open.name !== tok.name) at--;
            if (at > 0) {
                stack[at].close = tok;
                stack.length = at;
            } else {
                top.children.push(tok);
            }
        } else {
            top.children.push(tok);
        }
    });
    return root;
}

// Looked up by tag names from the body, so they have no prototype: a tag
// named <constructor> must not find Object's.
function debugTagTable(entries) {
    return Object.assign(Object.create(null), entries);
}
var DEBUG_HTML_VOID = debugTagTable({
    area: 1, base: 1, br: 1, col: 1, embed: 1, hr: 1, img: 1, input: 1, link: 1, meta: 1,
    param: 1, source: 1, track: 1, wbr: 1, basefont: 1, bgsound: 1, frame: 1, keygen: 1,
});
// The elements whose text is not markup, or whose whitespace is part of the page.
var DEBUG_HTML_RAW_TEXT = debugTagTable({ script: 1, style: 1, textarea: 1, title: 1, pre: 1, xmp: 1, listing: 1 });
// For an opening tag, the open elements it ends: HTML leaves out these end tags.
var DEBUG_HTML_IMPLIED_END = debugTagTable({
    li: ['li'], p: ['p'], dt: ['dt', 'dd'], dd: ['dt', 'dd'], option: ['option'],
    td: ['td', 'th'], th: ['td', 'th'], tr: ['td', 'th', 'tr'],
    thead: ['td', 'th', 'tr', 'thead', 'tbody', 'tfoot'],
    tbody: ['td', 'th', 'tr', 'thead', 'tbody', 'tfoot'],
    tfoot: ['td', 'th', 'tr', 'thead', 'tbody', 'tfoot'],
});

// Splits XML or HTML into tags, comments and other <! ?> constructs, and the
// text between them, each the exact slice of the input. A '<' that starts none
// of those is text. A tag that never ends, as in a body cut at the capture
// cap, makes the rest of the input one text token marked cut, so the scan
// stays linear.
function debugScanMarkup(src, html) {
    var tokens = [];
    var textStart = 0;
    var i = 0;
    while (i < src.length) {
        var lt = src.indexOf('<', i);
        if (lt < 0) break;
        var tok = construct(lt);
        if (tok === undefined) { i = lt + 1; continue; }
        if (lt > textStart) tokens.push({ type: 'text', text: src.slice(textStart, lt) });
        if (tok === null) {
            tokens.push({ type: 'text', text: src.slice(lt), cut: true });
            return tokens;
        }
        tokens.push(tok);
        i = textStart = tok.end;
        if (html && tok.type === 'open' && !tok.selfClosing && DEBUG_HTML_RAW_TEXT[tok.name]) {
            var close = rawTextEnd(tok.end, tok.name);
            if (close > tok.end) tokens.push({ type: 'text', text: src.slice(tok.end, close), raw: true });
            i = textStart = close;
        }
    }
    if (textStart < src.length) tokens.push({ type: 'text', text: src.slice(textStart) });
    return tokens;

    // The construct at lt; undefined when the '<' is text, null when it starts
    // a tag that never ends.
    function construct(lt) {
        if (src.startsWith('<!--', lt)) return other(lt, src.indexOf('-->', lt + 4), 3);
        if (src.startsWith('<![CDATA[', lt)) return other(lt, src.indexOf(']]>', lt + 9), 3);
        if (src.startsWith('<?', lt)) {
            var pi = src.indexOf('?>', lt + 2);
            return pi >= 0 ? other(lt, pi, 2) : other(lt, src.indexOf('>', lt + 2), 1);
        }
        if (src.startsWith('<!', lt)) return other(lt, src.indexOf('>', lt + 2), 1);
        var nameAt = src.charAt(lt + 1) === '/' ? lt + 2 : lt + 1;
        if (!startsName(src.charAt(nameAt))) return undefined;
        return scanTag(lt);
    }

    function other(lt, end, endLength) {
        var stop = end < 0 ? src.length : end + endLength;
        return { type: 'other', text: src.slice(lt, stop), end: stop };
    }

    function startsName(c) {
        if (!c) return false;
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return true;
        return !html && (c === '_' || c === ':' || c.charCodeAt(0) > 127);
    }

    function isSpace(c) {
        return c === ' ' || c === '\n' || c === '\t' || c === '\r' || c === '\f';
    }

    // A tag's pieces partition its text: '<' or '</', the name, then attribute
    // names, '=', values and the whitespace between them, then '>' or '/>'.
    function scanTag(start) {
        var pieces = [];
        var i = start + 1;
        var closing = src.charAt(i) === '/';
        if (closing) i++;
        pieces.push({ cls: null, text: src.slice(start, i) });
        var from = i;
        while (i < src.length && !isSpace(src.charAt(i)) && src.charAt(i) !== '/' && src.charAt(i) !== '>') i++;
        var name = src.slice(from, i);
        pieces.push({ cls: 't', text: name });
        for (;;) {
            from = i;
            while (i < src.length && isSpace(src.charAt(i))) i++;
            if (i > from) pieces.push({ cls: null, text: src.slice(from, i) });
            if (i >= src.length) return null;
            var c = src.charAt(i);
            if (c === '>' || (c === '/' && src.charAt(i + 1) === '>')) {
                var selfClosing = c === '/';
                var end = i + (selfClosing ? 2 : 1);
                pieces.push({ cls: null, text: src.slice(i, end) });
                return {
                    type: closing ? 'close' : 'open',
                    name: html ? name.toLowerCase() : name,
                    selfClosing: selfClosing,
                    pieces: pieces,
                    end: end,
                };
            }
            if (c === '/') {
                pieces.push({ cls: null, text: '/' });
                i++;
                continue;
            }
            from = i;
            while (i < src.length && !isSpace(src.charAt(i)) && '/>='.indexOf(src.charAt(i)) < 0) i++;
            if (i > from) pieces.push({ cls: 'a', text: src.slice(from, i) });
            var beforeEquals = i;
            while (i < src.length && isSpace(src.charAt(i))) i++;
            if (src.charAt(i) !== '=') {
                i = beforeEquals;
                continue;
            }
            if (i > beforeEquals) pieces.push({ cls: null, text: src.slice(beforeEquals, i) });
            pieces.push({ cls: null, text: '=' });
            from = ++i;
            while (i < src.length && isSpace(src.charAt(i))) i++;
            if (i > from) pieces.push({ cls: null, text: src.slice(from, i) });
            if (i >= src.length) return null;
            var quote = src.charAt(i);
            from = i;
            if (quote === '"' || quote === "'") {
                var closeQuote = src.indexOf(quote, i + 1);
                if (closeQuote < 0) return null;
                i = closeQuote + 1;
            } else {
                while (i < src.length && !isSpace(src.charAt(i)) && src.charAt(i) !== '>') i++;
            }
            pieces.push({ cls: 's', text: src.slice(from, i) });
        }
    }

    // Where the raw text of an element ends: at its end tag, in any case, or at
    // the end of the input.
    function rawTextEnd(from, name) {
        var end = new RegExp('</' + name + '(?=[\\s/>])', 'ig');
        end.lastIndex = from;
        var match = end.exec(src);
        return match ? match.index : src.length;
    }
}
window.debugScanMarkup = debugScanMarkup;

// Text with a number before each line. The numbers are CSS counters, so they
// cost nothing and are never selected or copied with the text. Each row is a
// block that keeps its line break, because a selection copied from rows
// without one loses every empty line. A final line break ends the last line
// rather than starting an empty one. Returns a <pre class="dc-code dc-lines">.
window.debugLineNumbered = function(text, options) {
    var searchText = (options && options.searchText) || '';
    var lines = String(text == null ? '' : text).split(/\r\n|\r|\n/);
    var endsWithBreak = lines.length > 1 && lines[lines.length - 1] === '';
    if (endsWithBreak) lines.pop();
    return '<pre class="dc-code dc-lines" style="--dc-lines-digits: ' + String(lines.length).length + '">'
        + lines.map(function(lineText, i) {
            var lineBreak = i < lines.length - 1 || endsWithBreak ? '\n' : '';
            return '<span class="dc-lines__line">' + debugHighlightPlain(lineText, searchText) + lineBreak + '</span>';
        }).join('')
        + '</pre>';
};

// An application/x-www-form-urlencoded body as a table of its fields, in the
// order they were sent, names and values decoded. A part that is not valid
// percent-encoding is shown as it was sent. Returns a <table class="dc-kv">.
window.debugFormTable = function(text, options) {
    var searchText = (options && options.searchText) || '';
    var rows = String(text == null ? '' : text).split('&').filter(Boolean).map(function(pair) {
        var eq = pair.indexOf('=');
        var name = eq < 0 ? pair : pair.slice(0, eq);
        var value = eq < 0 ? '' : pair.slice(eq + 1);
        return '<tr><th class="dc-kv__key">' + cell(name) + '</th><td class="dc-kv__value">' + cell(value) + '</td></tr>';
    });
    return '<table class="dc-kv">' + rows.join('') + '</table>';

    function cell(part) {
        try {
            return debugHighlightPlain(decodeURIComponent(part.replace(/\+/g, ' ')), searchText);
        } catch(e) {
            return '<span class="dc-kv__raw" title="Not valid percent-encoding, shown as sent">'
                + debugHighlightPlain(part, searchText) + '</span>';
        }
    }
};

// Bytes as hexdump -C prints them: the offset, sixteen bytes in hex, and the
// same bytes as ASCII with a dot for each one that is not printable. Returns
// text, for textContent.
window.debugHexDump = function(bytes) {
    var lines = [];
    for (var offset = 0; offset < bytes.length; offset += 16) {
        var hex = '';
        var ascii = '';
        for (var j = 0; j < 16; j++) {
            if (j === 8) hex += ' ';
            if (offset + j < bytes.length) {
                var b = bytes[offset + j];
                hex += (b < 16 ? '0' : '') + b.toString(16) + ' ';
                ascii += b >= 0x20 && b < 0x7f ? String.fromCharCode(b) : '.';
            } else {
                hex += '   ';
            }
        }
        lines.push(('0000000' + offset.toString(16)).slice(-8) + '  ' + hex + ' |' + ascii + '|');
    }
    return lines.join('\n');
};

// Controls a .dc-modal-scrim kept in the tab markup with the hidden attribute.
window.debugModal = function(modalId) {
    var el = document.getElementById(modalId);
    if (!el) return { show: function(){}, hide: function(){}, toggle: function(){}, isVisible: function(){ return false; } };
    el.addEventListener('mousedown', function(e) {
        if (e.target === el) el.hidden = true;
    });
    return {
        show: function() { el.hidden = false; },
        hide: function() { el.hidden = true; },
        toggle: function() { el.hidden = !el.hidden; },
        isVisible: function() { return !el.hidden; }
    };
};

document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape') {
        var open = document.querySelectorAll('.dc-modal-scrim:not([hidden])');
        // A tab's own Escape handler runs after this one, when no dialog is open
        // any more, so mark the key as used: the tab then leaves its state alone.
        if (open.length) e.preventDefault();
        open.forEach(function(m) {
            m.hidden = true;
        });
    }
});

window.debugFetch = async function(url, options) {
    try {
        var resp = await fetch(url, options || {});
        if (!resp.ok) {
            // Carry the response along: a rejected request answers with the error
            // envelope, and a caller can show what it says instead of the status.
            var err = new Error('HTTP ' + resp.status);
            err.status = resp.status;
            err.response = resp;
            throw err;
        }
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
    // Each .dc-divider resizes the two panes on either side of it:
    // pane | divider | pane.
    document.querySelectorAll('.dc-divider').forEach(function(divider) {
        var isDragging = false;
        var startX = 0;
        var leftPane = null;
        var rightPane = null;
        var leftStartWidth = 0;
        var total = 0;

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
            leftPane = divider.previousElementSibling;
            rightPane = divider.nextElementSibling;
            if (!leftPane || !rightPane) return;
            e.preventDefault();
            isDragging = true;
            startX = e.clientX;
            leftStartWidth = leftPane.offsetWidth;
            // The pair shares a fixed total: their combined width stays constant as
            // the divider moves, so resizing is a single split point between them.
            total = leftStartWidth + rightPane.offsetWidth;
            divider.classList.add('dc-divider--dragging');
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
                divider.classList.remove('dc-divider--dragging');
                document.body.style.cursor = '';
            }
        });
    });
};
