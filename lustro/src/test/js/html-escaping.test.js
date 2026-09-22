// Escaping on the captured-body path.
//
// A captured body is attacker-controlled text that ends up in innerHTML, and the
// search box is attacker-adjacent text that is compiled into a RegExp and then
// spliced into that same HTML. These functions decide whether a response body
// containing <img src=x onerror=...> renders as text or as markup.
//
// highlightMatches is not exported; it is reached the way the UI reaches it,
// through the searchText option of debugHighlightPlain and
// debugSyntaxHighlightJson.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { loadSharedJs } = require('./harness.js');

const shared = loadSharedJs();
const { debugEscapeHtml, debugHighlightPlain, debugSyntaxHighlightJson } = shared;

const PAYLOADS = [
    '<img src=x onerror=alert(1)>',
    '<script>alert(1)</script>',
    '</span><script>alert(1)</script>',
    '</mark><img src=x onerror=1><mark>',
    '"><img src=x onerror=1>',
    "'><img src=x onerror=1>",
    '<svg/onload=alert(1)>',
    '&lt;script&gt;',
    '&amp;lt;script&amp;gt;',
    '<!--<script>-->',
];

// Every tag the two highlighters are allowed to emit. Anything else in the
// output came from the body or the query, which would mean escaping failed.
const ALLOWED_TAGS = new Set([
    '<mark>', '</mark>', '</span>',
    '<span class="json-key">', '<span class="json-string">', '<span class="json-number">',
    '<span class="json-boolean">', '<span class="json-null">', '<span class="json-bracket">',
]);

function unexpectedTags(html) {
    return (html.match(/<[^>]*>/g) || []).filter((tag) => !ALLOWED_TAGS.has(tag));
}

test('debugEscapeHtml neutralises markup', async (t) => {
    await t.test('escapes every character that can break out', () => {
        assert.strictEqual(debugEscapeHtml('&'), '&amp;');
        assert.strictEqual(debugEscapeHtml('<'), '&lt;');
        assert.strictEqual(debugEscapeHtml('>'), '&gt;');
        // Quotes matter because the result is also interpolated into attribute
        // values, where a bare quote would end the attribute.
        assert.strictEqual(debugEscapeHtml('"'), '&quot;');
        assert.strictEqual(debugEscapeHtml("'"), '&#39;');
    });

    await t.test('escapes the ampersand first, so nothing is double-escaped', () => {
        assert.strictEqual(debugEscapeHtml('<'), '&lt;', 'must not be &amp;lt;');
        assert.strictEqual(debugEscapeHtml('&lt;'), '&amp;lt;', 'a literal entity stays literal');
    });

    await t.test('no payload survives as markup', () => {
        for (const payload of PAYLOADS) {
            const escaped = debugEscapeHtml(payload);
            assert.deepStrictEqual(unexpectedTags(escaped), [], `payload produced markup: ${payload}`);
            assert.ok(!escaped.includes('"'), `payload left a bare quote: ${payload}`);
            assert.ok(!escaped.includes("'"), `payload left a bare apostrophe: ${payload}`);
        }
    });

    await t.test('an absent body is the empty string, not "null"', () => {
        assert.strictEqual(debugEscapeHtml(null), '');
        assert.strictEqual(debugEscapeHtml(undefined), '');
        assert.strictEqual(debugEscapeHtml(0), '0');
        assert.strictEqual(debugEscapeHtml(''), '');
    });
});

test('debugHighlightPlain neutralises markup in a non-JSON body', async (t) => {
    await t.test('escapes the characters that can form a tag', () => {
        assert.strictEqual(debugHighlightPlain('<b>hi</b>'), '&lt;b&gt;hi&lt;/b&gt;');
        assert.strictEqual(debugHighlightPlain('a & b'), 'a &amp; b');
    });

    await t.test('no payload survives as markup', () => {
        for (const payload of PAYLOADS) {
            assert.deepStrictEqual(
                unexpectedTags(debugHighlightPlain(payload)),
                [],
                `payload produced markup: ${payload}`,
            );
        }
    });

    await t.test('an absent body is the empty string', () => {
        assert.strictEqual(debugHighlightPlain(null), '');
        assert.strictEqual(debugHighlightPlain(undefined), '');
    });
});

test('the search query is escaped as text and as a pattern', async (t) => {
    await t.test('regex metacharacters match literally', () => {
        // Each query would match far more than itself if it reached the RegExp
        // unescaped, so an over-wide <mark> is the symptom of a missing escape.
        assert.strictEqual(debugHighlightPlain('a.b axb', '.'), 'a<mark>.</mark>b axb');
        assert.strictEqual(debugHighlightPlain('anything', '.*'), 'anything');
        assert.strictEqual(debugHighlightPlain('aaa', 'a+'), 'aaa');
        assert.strictEqual(debugHighlightPlain('a+b ab', 'a+'), '<mark>a+</mark>b ab');
        assert.strictEqual(debugHighlightPlain('(x) y', '(x)'), '<mark>(x)</mark> y');
        assert.strictEqual(debugHighlightPlain('[a] b', '[a]'), '<mark>[a]</mark> b');
        assert.strictEqual(debugHighlightPlain('a^b', '^'), 'a<mark>^</mark>b');
        assert.strictEqual(debugHighlightPlain('a$b', '$'), 'a<mark>$</mark>b');
        assert.strictEqual(debugHighlightPlain('a|b', '|'), 'a<mark>|</mark>b');
        assert.strictEqual(debugHighlightPlain('a?b', '?'), 'a<mark>?</mark>b');
        assert.strictEqual(debugHighlightPlain('a{2}b', '{2}'), 'a<mark>{2}</mark>b');
    });

    await t.test('an unbalanced or backslash query neither throws nor matches wildly', () => {
        for (const query of ['\\', '(', ')', '[', ']', '\\\\', '*', '+', '?', '{', '(?<', '\\u']) {
            const html = debugHighlightPlain('a plain body', query);
            assert.strictEqual(html, 'a plain body', `query ${JSON.stringify(query)} matched something`);
        }
        assert.strictEqual(debugHighlightPlain('a\\b', '\\'), 'a<mark>\\</mark>b');
    });

    await t.test('matching is case-insensitive', () => {
        assert.strictEqual(debugHighlightPlain('ABC abc', 'abc'), '<mark>ABC</mark> <mark>abc</mark>');
    });

    await t.test('a query carrying markup is escaped before it is spliced in', () => {
        assert.strictEqual(debugHighlightPlain('safe <script>', '<script>'), 'safe <mark>&lt;script&gt;</mark>');
        for (const payload of PAYLOADS) {
            assert.deepStrictEqual(
                unexpectedTags(debugHighlightPlain(payload, payload)),
                [],
                `query produced markup: ${payload}`,
            );
        }
    });

    await t.test('an empty query leaves the body untouched', () => {
        assert.strictEqual(debugHighlightPlain('abc'), 'abc');
        assert.strictEqual(debugHighlightPlain('abc', ''), 'abc');
    });
});

test('search cannot corrupt the highlighter\'s own markup', async (t) => {
    await t.test('a query matching the generated tags does not mark them', () => {
        // Matching runs on each piece before its span is wrapped around it, so a
        // query of "span" or "class" can never land inside a generated tag.
        for (const query of ['span', 'class', 'json', 'mark', '<span', 'json-key']) {
            const html = debugSyntaxHighlightJson('{"a":1}', { searchText: query });
            assert.deepStrictEqual(unexpectedTags(html), [], `query ${query} corrupted the markup`);
            assert.ok(!html.includes('<mark>span'), `query ${query} marked a generated tag`);
        }
    });

    await t.test('a body of payloads highlights as JSON strings, not markup', () => {
        for (const payload of PAYLOADS) {
            const body = JSON.stringify({ hostile: payload });
            const html = debugSyntaxHighlightJson(body, { searchText: 'img' });
            assert.deepStrictEqual(unexpectedTags(html), [], `payload produced markup: ${payload}`);
        }
    });

    await t.test('marks land inside the value spans', () => {
        assert.strictEqual(
            debugSyntaxHighlightJson('{"a":"hello"}', { searchText: 'ell' }),
            '<span class="json-bracket">{</span>\n'
                + '  <span class="json-key">"a"</span>: '
                + '<span class="json-string">"h<mark>ell</mark>o"</span>\n'
                + '<span class="json-bracket">}</span>',
        );
    });
});
