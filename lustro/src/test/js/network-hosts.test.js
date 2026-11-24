'use strict';

// A row of the list on one of the app's hosts shows only its path; a row on
// any other host starts with a tag of its host. The app's hosts come from the
// senders' base URLs, or from the hosts the user checked.

const test = require('node:test');
const assert = require('node:assert');
const { loadNetworkJs } = require('./harness.js');

const network = loadNetworkJs();

function tx(url, fields) {
    return Object.assign({ id: 'a', method: 'POST', url, replacedCount: 0 }, fields);
}

test('The host of a URL', async (t) => {
    await t.test('is its host name', () => {
        assert.strictEqual(network.netHostOf('https://events.sdk.test/api/v1/events?x=1'), 'events.sdk.test');
    });

    await t.test('has a port that is not the default one of the scheme', () => {
        assert.strictEqual(network.netHostOf('http://10.0.2.2:8080/v1/items'), '10.0.2.2:8080');
        assert.strictEqual(network.netHostOf('https://api.example.com:443/v1/items'), 'api.example.com');
    });

    await t.test('is in lower case', () => {
        assert.strictEqual(network.netHostOf('https://API.Example.com/v1'), 'api.example.com');
    });

    await t.test('is empty for a URL that is not absolute', () => {
        assert.strictEqual(network.netHostOf('/v1/items'), '');
        assert.strictEqual(network.netHostOf(''), '');
    });
});

test("The app's hosts", async (t) => {
    const senders = [
        { name: 'API', baseUrl: 'https://api.example.com/v2/' },
        { name: 'CDN', baseUrl: 'https://cdn.example.com' },
        { name: 'Again', baseUrl: 'https://api.example.com/other' },
        { name: 'Any host', baseUrl: null },
    ];

    await t.test("are the hosts of the senders' base URLs, each once", () => {
        assert.deepStrictEqual([...network.netAppHosts(null, senders, true)], ['api.example.com', 'cdn.example.com']);
    });

    await t.test('are none when no sender has a base URL', () => {
        assert.deepStrictEqual([...network.netAppHosts(null, [{ name: 'Any host', baseUrl: null }], true)], []);
    });

    await t.test('are unknown until the senders are loaded, so no row gets a tag yet', () => {
        assert.strictEqual(network.netAppHosts(null, [], false), null);
    });

    await t.test('are the checked hosts once the user checked any, also none', () => {
        assert.deepStrictEqual([...network.netAppHosts(['b.test'], senders, true)], ['b.test']);
        assert.deepStrictEqual([...network.netAppHosts([], senders, false)], []);
    });
});

test('The URL cell', async (t) => {
    const appHosts = ['api.example.com'];

    await t.test("shows only the path for a row on one of the app's hosts", () => {
        const html = network.netUrlCell(tx('https://api.example.com/api/v1/events?since=1'), appHosts);
        assert.ok(!html.includes('net-host-tag'), html);
        assert.match(html, />\/api\/v1\/events<\/td>$/);
    });

    await t.test('starts with a tag of the host for a row on another host', () => {
        const html = network.netUrlCell(tx('https://events.sdk.test/api/v1/events'), appHosts);
        assert.match(html, /<span class="net-host-tag" style="--c: var\(--host-[1-6]\)"><bdi>events\.sdk\.test<\/bdi><\/span><span class="net-url-text">\/api\/v1\/events<\/span>/);
    });

    await t.test('tags every row when the app has no host', () => {
        assert.ok(network.netUrlCell(tx('https://api.example.com/v1'), []).includes('net-host-tag'));
    });

    await t.test('tags no row while the app hosts are unknown', () => {
        assert.ok(!network.netUrlCell(tx('https://events.sdk.test/v1'), null).includes('net-host-tag'));
    });

    await t.test('has the whole URL in its tooltip, with the host and the query', () => {
        const html = network.netUrlCell(tx('https://events.sdk.test/api/v1/events?a=1&b=2'), appHosts);
        assert.ok(html.startsWith('<td class="dc-cell net-cell-url" title="https://events.sdk.test/api/v1/events?a=1&amp;b=2">'), html);
    });

    await t.test('shows a URL that is not absolute as it is, without a tag', () => {
        const html = network.netUrlCell(tx('not a url'), appHosts);
        assert.ok(!html.includes('net-host-tag'), html);
        assert.ok(html.includes('>not a url</td>'), html);
    });

    await t.test('keeps the badge of the copies that overwrite mode replaced, after the path', () => {
        const html = network.netUrlCell(tx('https://events.sdk.test/api/v1/events', { replacedCount: 2 }), appHosts);
        assert.match(html, /net-host-tag.*net-url-text.*net-replaced-badge/);
    });
});

test('A host keeps its color', () => {
    const color = network.netHostColor('events.sdk.test');
    assert.ok(color >= 1 && color <= 6, String(color));
    assert.strictEqual(network.netHostColor('events.sdk.test'), color);
    const colors = new Set(['a.test', 'b.test', 'c.test', 'd.test', 'e.test', 'f.test', 'g.test', 'h.test'].map(network.netHostColor));
    assert.ok(colors.size > 2, 'the hosts should not all share a color');
});

test("The hosts button names the app's hosts", () => {
    assert.strictEqual(network.netHostsButtonLabel(['api.example.com']), 'api.example.com');
    assert.strictEqual(network.netHostsButtonLabel(['api.example.com', 'cdn.example.com', 'b.test']), 'api.example.com +2');
    assert.strictEqual(network.netHostsButtonLabel([]), 'no app host');
});

test('The checked hosts are kept for each app', () => {
    assert.strictEqual(network.netAppHostsStorageKey('com.example.app'), 'debug-network-app-hosts:com.example.app');
    assert.strictEqual(network.netAppHostsStorageKey('com.example.other'), 'debug-network-app-hosts:com.example.other');
    assert.strictEqual(network.netAppHostsStorageKey(undefined), 'debug-network-app-hosts');
});

test('The hosts menu counts a host named like a member of Object.prototype', () => {
    const txs = [tx('http://constructor/v1'), tx('http://constructor/v2'), tx('http://__proto__/v1'), tx('http://tostring/')];
    assert.deepStrictEqual(
        [...network.netHostCounts(txs, []).map((row) => row.host + ' ' + row.count)],
        ['constructor 2', '__proto__ 1', 'tostring 1'],
    );
});

test('The hosts menu lists each host, most rows first, and the app hosts without rows', () => {
    const txs = [
        tx('https://maps.googleapis.com/vt/1'),
        tx('https://events.sdk.test/api/v1/events'),
        tx('https://maps.googleapis.com/vt/2'),
        tx('/relative'),
    ];
    assert.deepStrictEqual(
        [...network.netHostCounts(txs, ['api.example.com']).map((row) => row.host + ' ' + row.count)],
        ['maps.googleapis.com 2', 'events.sdk.test 1', 'api.example.com 0'],
    );
});
