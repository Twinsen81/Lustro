'use strict';

// The Copy button of a body copies what the selected view shows: in the JSON
// Tree view, the JSON indented, and in the Raw view, the body as captured.

const test = require('node:test');
const assert = require('node:assert');
const { loadNetworkJs } = require('./harness.js');

const network = loadNetworkJs();

// What the body's Copy button puts on the clipboard.
async function copied(tx, dir) {
    const html = network.netBodySection(tx, dir);
    const id = html.match(/data-action="copyToClip" data-copy-id="([^"]+)"/)[1];
    let text = null;
    network.debugWriteToClipboard = (t) => { text = t; return Promise.resolve(); };
    network.copyToClip(id);
    await Promise.resolve();
    return text;
}

function json(body) {
    return { id: 'a', method: 'POST', url: 'https://a.test/v1/lists', responseBody: body, responseContentType: 'application/json' };
}

function chooseView(view) {
    network.localStorage.setItem('debug-network-body-views', JSON.stringify({ 'application/json': view }));
}

test('Copy copies what the body view shows', async (t) => {
    t.afterEach(() => network.localStorage.removeItem('debug-network-body-views'));

    await t.test('the Tree view, the JSON indented', async () => {
        assert.strictEqual(await copied(json('{"a":1,"b":[true,null]}'), 'response'), '{\n  "a": 1,\n  "b": [\n    true,\n    null\n  ]\n}');
    });

    await t.test('the Tree view, with each value as captured', async () => {
        // A JSON.parse round-trip would change the number and decode the escape.
        assert.strictEqual(await copied(json('{"id":12345678901234567890,"s":"\\u00e9"}'), 'response'),
            '{\n  "id": 12345678901234567890,\n  "s": "\\u00e9"\n}');
    });

    await t.test('the Raw view, the body as captured', async () => {
        chooseView('raw');
        assert.strictEqual(await copied(json('{"a":1}'), 'response'), '{"a":1}');
    });

    await t.test('a JSON type on text that is not JSON, as captured', async () => {
        assert.strictEqual(await copied(json('{"a":1,"b":'), 'response'), '{"a":1,"b":');
    });

    await t.test('the request body too', async () => {
        const tx = { id: 'a', method: 'POST', url: 'https://a.test/', requestBody: '{"hello":"world"}', requestContentType: 'application/json' };
        assert.strictEqual(await copied(tx, 'request'), '{\n  "hello": "world"\n}');
    });
});
