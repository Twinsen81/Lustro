// The Network tab's exports: a transaction as Markdown, and the HAR batches the
// console joins into one file.
//
// The Markdown goes into bug reports, pull requests, and chats, where a body
// that closes its own code block or a URL read as markup would break the rest
// of the document. So each test pins the text exactly.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { loadNetworkJs } = require('./harness.js');

const network = loadNetworkJs();
const markdown = (...txs) => network.netTransactionsMarkdown(txs);

const GOLDEN_DIR = path.join(__dirname, '..', '..', '..', '..', 'wire-protocol', 'v1', 'golden');
const golden = (name) => JSON.parse(fs.readFileSync(path.join(GOLDEN_DIR, name), 'utf8'));

function detail(fields) {
    return Object.assign({
        id: 'tx_1',
        timestamp: '14:22:09.003',
        startedAt: 1790605329003,
        method: 'GET',
        url: 'https://api.example.com/v1/orders',
        protocol: 'h2',
        statusCode: 200,
        durationMs: 12,
        categories: [],
        isMocked: false,
        requestContentType: null,
        responseContentType: 'application/json',
        responseComplete: true,
        error: null,
        requestHeaders: {},
        requestBody: null,
        requestBodyTruncated: false,
        requestBodyBinary: false,
        responseHeaders: {},
        responseBody: null,
        responseBodyTruncated: false,
        responseBodyBinary: false,
    }, fields);
}

test('a transaction renders as a heading, its meta, and fenced headers and bodies', () => {
    assert.strictEqual(markdown(golden('transaction.json')), [
        '## `GET https://api.example.com/v1/orders/77e2`',
        '',
        '**500** · 41 ms · held 1000 ms by the throttle · 2026-09-28T14:22:11.781Z · mocked by Lustro · `api`',
        '',
        '### Request headers',
        '',
        '```http',
        'Accept: application/json',
        'Authorization: <redacted>',
        '```',
        '',
        '### Response headers',
        '',
        '```http',
        'Content-Type: application/json',
        '```',
        '',
        '### Response body',
        '',
        '```json',
        '{',
        '  "error": "boom"',
        '}',
        '```',
        '',
    ].join('\n'));
});

test('the meta names an adapter other than OkHttp', () => {
    const meta = (tx) => markdown(detail(tx)).split('\n')[2];
    assert.strictEqual(meta({ source: 'okhttp' }), '**200** · 12 ms · 2026-09-28T14:22:09.003Z · `h2`');
    assert.strictEqual(meta({ source: 'platform', protocol: 'http/1.1' }),
        '**200** · 12 ms · 2026-09-28T14:22:09.003Z · `http/1.1` · captured by platform HttpURLConnection');
    assert.strictEqual(meta({ source: 'app', protocol: null }), "**200** · 12 ms · 2026-09-28T14:22:09.003Z · reported by the app's own adapter");
});

test('a JSON body is indented without changing a value', () => {
    const out = markdown(detail({ responseBody: '{"big":12345678901234567890,"n":1.10,"s":"\\u00e9"}' }));
    assert.ok(out.includes('```json\n{\n  "big": 12345678901234567890,\n  "n": 1.10,\n  "s": "\\u00e9"\n}\n```'), out);
});

test('a body cut at the capture cap stays as it is, and says it was cut', () => {
    const out = markdown(detail({ responseBody: '{"items":[1,2', responseBodyTruncated: true }));
    assert.ok(out.includes('### Response body\n\n_Truncated: Lustro kept only the first part of this body._\n\n```json\n{"items":[1,2\n```'), out);
});

test('a truncated request body is marked too', () => {
    const out = markdown(detail({ method: 'POST', requestBody: 'abc', requestContentType: 'text/plain', requestBodyTruncated: true }));
    assert.ok(out.includes('### Request body\n\n_Truncated: Lustro kept only the first part of this body._\n\n```text\nabc\n```'), out);
});

test('a body that holds backticks gets a longer fence', () => {
    const body = 'see:\n```\ncode\n````\n';
    const out = markdown(detail({ responseBody: body, responseContentType: 'text/markdown' }));
    assert.ok(out.includes('`````markdown\n' + body + '`````'), out);
});

test('a URL with backticks or markup stays one code span', () => {
    const out = markdown(detail({ url: 'https://x.test/a`b?q=<i>*x*</i>#h' }));
    assert.ok(out.startsWith('## ``GET https://x.test/a`b?q=<i>*x*</i>#h``\n'), out);
    // A span that ends with a backtick is padded, and Markdown drops one space from each side.
    const edge = markdown(detail({ method: 'GET', url: '`' }));
    assert.ok(edge.startsWith('## `` GET ` ``\n'), edge);
});

test('each body gets a language for its content type', () => {
    const cases = [
        ['application/problem+json', '{"a":1}', 'json'],
        ['text/html; charset=utf-8', '<p>hi</p>', 'html'],
        ['application/xml', '<a/>', 'xml'],
        ['application/atom+xml', '<feed/>', 'xml'],
        ['text/css', 'a{}', 'css'],
        ['application/javascript', 'x()', 'javascript'],
        ['application/x-www-form-urlencoded', 'a=1&b=2', 'text'],
        ['text/plain', 'hello', 'text'],
        [null, 'hello', 'text'],
        // JSON sent as text is still JSON.
        ['text/plain', '[1]', 'json'],
    ];
    for (const [type, body, language] of cases) {
        const out = markdown(detail({ responseContentType: type, responseBody: body }));
        assert.ok(out.includes('```' + language + '\n'), type + ': ' + out);
    }
});

test('a body kept as bytes is named, not included', () => {
    const out = markdown(golden('transaction-image.json'));
    assert.ok(out.includes('### Response body\n\n_image/png body, 5.1 KB: kept as bytes, so it is not included._'), out);
});

test('a body kept as bytes and cut at the capture cap says both', () => {
    const out = markdown(detail({ responseBody: null, responseBodyBinary: true, responseBodyTruncated: true,
        responseContentType: 'image/jpeg', responseBodyBytes: 300000 }));
    assert.ok(out.includes('_image/jpeg body, 293.0 KB: kept as bytes, so it is not included. Lustro kept only its first part._'), out);
});

test('a body in an encoding Lustro does not decode says so', () => {
    const out = markdown(detail({ responseHeaders: { 'Content-Encoding': 'br' }, responseBody: null }));
    assert.ok(out.includes('### Response body\n\n_Not captured: Lustro does not decode the br encoding._'), out);
});

test('a request with no body and no headers has no sections for them', () => {
    const out = markdown(detail({ responseBody: '' }));
    assert.strictEqual(out, '## `GET https://api.example.com/v1/orders`\n\n**200** · 12 ms · 2026-09-28T14:22:09.003Z · `h2`\n');
});

test('a request in flight, a stream, and a failure say so in the meta line', () => {
    const pending = markdown(detail({ statusCode: null, durationMs: null, protocol: null, responseComplete: false, responseHeaders: null }));
    assert.ok(pending.includes('\n\n**Pending** · 2026-09-28T14:22:09.003Z\n'), pending);
    const streaming = markdown(detail({ responseComplete: false }));
    assert.ok(streaming.includes('**200, streaming**'), streaming);
    const failed = markdown(detail({ statusCode: null, error: 'java.net.SocketTimeoutException: timeout', protocol: null }));
    assert.ok(failed.includes('**Failed** · 12 ms · 2026-09-28T14:22:09.003Z\n\n**Error:** `java.net.SocketTimeoutException: timeout`'), failed);
});

test('a redirected request lists each hop and where the response came from', () => {
    const out = markdown(detail({
        url: 'http://feeds.example.com/rss',
        finalUrl: 'https://cdn.example.com/rss',
        priorResponses: [
            { url: 'http://feeds.example.com/rss', statusCode: 301 },
            { url: 'https://feeds.example.com/rss', statusCode: 302 },
        ],
    }));
    assert.ok(out.includes([
        '**Redirects:**',
        '',
        '- 301 `http://feeds.example.com/rss`',
        '- 302 `https://feeds.example.com/rss`',
        '- Response from `https://cdn.example.com/rss`',
    ].join('\n')), out);
});

test('platform capture names only the final URL', () => {
    const out = markdown(detail({ url: 'http://a.test/x', finalUrl: 'https://b.test/x', priorResponses: [] }));
    assert.ok(out.includes('- from `http://a.test/x`\n- Response from `https://b.test/x`'), out);
});

test('a request that was not redirected has no redirect lines', () => {
    assert.ok(!markdown(detail({ finalUrl: null, priorResponses: [] })).includes('Redirects'));
});

test('a selection is one document, with a rule between transactions', () => {
    const out = markdown(detail({ id: 'a', url: 'https://x.test/a' }), detail({ id: 'b', url: 'https://x.test/b' }));
    const parts = out.split('\n\n---\n\n');
    assert.strictEqual(parts.length, 2);
    assert.ok(parts[0].startsWith('## `GET https://x.test/a`'));
    assert.ok(parts[1].startsWith('## `GET https://x.test/b`'));
    assert.ok(out.endsWith('\n') && !out.endsWith('\n\n'));
});

test('HAR batches join into one log, oldest first', () => {
    const entry = (id, started) => ({ startedDateTime: started, _lustro: { id } });
    const har = (...entries) => ({ log: { version: '1.2', creator: { name: 'Lustro', version: '0.1.0' }, entries } });
    const merged = network.netMergeHar([
        har(entry('b', '2026-09-28T14:22:09.003Z'), entry('d', '2026-09-28T14:22:11.000Z')),
        har(entry('a', '2026-09-28T14:22:07.512Z'), entry('c', '2026-09-28T14:22:09.003Z')),
    ]);
    assert.deepStrictEqual(merged.log.entries.map((e) => e._lustro.id), ['a', 'b', 'c', 'd']);
    assert.strictEqual(merged.log.creator.name, 'Lustro');
});

test('the golden HAR merges unchanged', () => {
    const merged = network.netMergeHar([golden('export-har.json')]);
    assert.deepStrictEqual(merged, golden('export-har.json'));
});
