// The Network tab's WebSocket view: a row of a connection's log, and a
// connection as Markdown.
//
// A message's payload is content from the app's server, so a row must show it
// as text whatever it holds. The Markdown goes into bug reports and chats, so
// each test pins the text exactly.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { loadNetworkJs } = require('./harness.js');

const network = loadNetworkJs();
const row = (event, selectedSeq) => network.netWebSocketEventRow(event, selectedSeq == null ? null : selectedSeq);

const GOLDEN_DIR = path.join(__dirname, '..', '..', '..', '..', 'wire-protocol', 'v1', 'golden');
const golden = (name) => JSON.parse(fs.readFileSync(path.join(GOLDEN_DIR, name), 'utf8'));

function message(fields) {
    return Object.assign({
        seq: 3,
        at: 1790605337391,
        timestamp: '14:22:17.391',
        kind: 'message',
        direction: 'received',
        type: 'text',
        payloadBytes: 16,
        stored: true,
        truncated: false,
        preview: '{"type":"ready"}',
        previewComplete: true,
    }, fields);
}

test('a payload that holds markup shows as text, on one line', () => {
    const html = row(message({ preview: '<img src=x onerror=alert(1)>\n"q"' }));
    assert.ok(html.includes('&lt;img src=x onerror=alert(1)&gt; &quot;q&quot;</td>'), html);
    assert.ok(!html.includes('<img'), html);
});

test('a lifecycle event that holds markup shows as text', () => {
    const html = row({ seq: 9, at: 1, timestamp: '14:22:21.018', kind: 'failure', statusCode: 403, error: 'java.net.ProtocolException: <b>' });
    assert.ok(html.includes('Failure · HTTP 403 · java.net.ProtocolException: &lt;b&gt;'), html);
    assert.ok(html.includes('net-ws-note-row--failure'), html);
    // A lifecycle row has no payload to select.
    assert.ok(!html.includes('data-action'), html);
});

test('a sent message says that OkHttp queued it, not that the server received it', () => {
    const html = row(message({ direction: 'sent', enqueued: true }));
    assert.ok(html.includes('send() returned true: OkHttp queued the message, which does not show that the server received it.'), html);
    assert.ok(!html.includes('not sent'), html);
});

test('a message that send() refused is marked as not sent', () => {
    const html = row(message({ direction: 'sent', enqueued: false }));
    assert.ok(html.includes('>not sent</span>'), html);
    assert.ok(html.includes('send() returned false.'), html);
});

test('a binary message shows its first bytes in hex, and a cut one says so', () => {
    const html = row(message({ type: 'binary', preview: undefined, hexPreview: 'deadbeef', truncated: true }));
    assert.ok(html.includes('<span class="dc-tag">binary</span>'), html);
    assert.ok(html.includes('>Truncated</span> de ad be ef</td>'), html);
});

test('a message with no stored payload says so', () => {
    const html = row(message({ stored: false, preview: undefined }));
    assert.ok(html.includes('// payload not stored'), html);
});

test('the selected message is marked, and only that one', () => {
    assert.ok(row(message({ seq: 3 }), 3).includes('dc-row--selected'));
    assert.ok(!row(message({ seq: 4 }), 3).includes('dc-row--selected'));
});

test('a connection renders as a heading, a summary, and its events with fenced text payloads', () => {
    const connection = Object.assign({}, golden('websocket.json'), {
        state: 'closed', closeCode: 1000, closeReason: 'bye', closedBy: 'app', closedAt: 1790605341020,
    });
    const events = golden('stream-reset.json').items.concat(golden('stream-delta.json').items);
    assert.strictEqual(network.netWebSocketMarkdown(connection, events), [
        '## `WS wss://chat.example.com/socket?token=%5BREDACTED%5D`',
        '**closed 1000** · HTTP 101 · 2026-09-28T14:22:17.098Z · sent 2 (40 B) · received 2 (293.0 KB)',
        'Close 1000, started by the app: bye',
        '### Events',
        '`14:22:17.243` Open · HTTP 101',
        '`14:22:17.250` Sent text, 36 B',
        '```json\n{"type":"auth","token":"[REDACTED]"}\n```',
        '`14:22:17.391` Received text, 16 B',
        '```json\n{"type":"ready"}\n```',
        '`14:22:18.004` Sent binary, 4 B: `de ad be ef`',
        '`14:22:19.112` Received text, 293.0 KB, first part only',
        '```\n{"type":"snapshot","items":[{"id":1,"na\n```',
        '`14:22:20.010` The app called close(1000 bye)',
        '`14:22:20.019` Sent text, 8 B, not sent: send() returned false',
        '```\ntoo late\n```',
        '`14:22:21.018` Close frame from the server · 1000 bye',
        '`14:22:21.020` Closed · 1000 bye',
    ].join('\n\n') + '\n');
});

test('a failed connection says why, and a long log keeps its last events', () => {
    const failed = golden('websockets-reset.json').items[2];
    const events = [];
    for (let seq = 1; seq <= 120; seq++) events.push(message({ seq, preview: 'm' + seq, payloadBytes: 4 }));
    const out = network.netWebSocketMarkdown(failed, events);
    assert.ok(out.includes("**failed** · HTTP 403"), out);
    assert.ok(out.includes("Error: `java.net.ProtocolException: Expected HTTP 101 response but was '403 Forbidden'`"), out);
    assert.ok(out.includes('### Events (the last 100 of 120)'), out);
    assert.ok(!out.includes('\nm20\n') && out.includes('\nm21\n') && out.includes('\nm120\n'), out);
});

test('a payload that holds backticks gets a longer fence', () => {
    const out = network.netWebSocketMarkdown(golden('websocket.json'), [message({ preview: 'a ``` b' })]);
    assert.ok(out.includes('````\na ``` b\n````'), out);
});
