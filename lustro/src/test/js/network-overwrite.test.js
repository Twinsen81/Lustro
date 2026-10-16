'use strict';

// Overwrite mode removes the earlier copies of a request when the app sends it
// again. The row that was selected moves to the copy that replaced it, and that
// row says how many copies it replaced.

const test = require('node:test');
const assert = require('node:assert');
const { loadNetworkJs } = require('./harness.js');

const network = loadNetworkJs();

function tx(id, fields) {
    return Object.assign({ id, method: 'GET', url: 'https://a.test/v1/featured.json', startedAt: 1000, replacedCount: 0 }, fields);
}

const ids = (...list) => Object.fromEntries(list.map((id) => [id, true]));

test('The selection moves to the copy that replaced it', async (t) => {
    const gone = tx('old', { startedAt: 1000 });

    await t.test('a new row with the same method and path that replaced copies', () => {
        const list = [tx('new', { startedAt: 2000, replacedCount: 1, url: 'https://a.test/v1/featured.json?page=2' }), tx('other', { url: 'https://a.test/v1/lists.json' })];
        assert.strictEqual(network.netReplacementFor(gone, list, ids('old', 'other')).id, 'new');
    });

    await t.test('the newest, when copies replaced each other between two polls', () => {
        // The list is newest first. The copy in between was never listed: the newest one replaced it.
        const list = [tx('newest', { startedAt: 3000, replacedCount: 2 }), tx('in-flight', { startedAt: 2500 })];
        assert.strictEqual(network.netReplacementFor(gone, list, ids('old')).id, 'newest');
    });

    await t.test('one listed a poll after the copy left the list', () => {
        const list = [tx('new', { startedAt: 2000, replacedCount: 1 })];
        assert.strictEqual(network.netReplacementFor(gone, list, {}).id, 'new');
    });

    await t.test('not a row that was listed before, which replaced other copies', () => {
        const list = [tx('listed', { startedAt: 2000, replacedCount: 1 })];
        assert.strictEqual(network.netReplacementFor(gone, list, ids('old', 'listed')), null);
    });

    await t.test('not a row that replaced nothing, as when overwrite mode is off', () => {
        const list = [tx('new', { startedAt: 2000 })];
        assert.strictEqual(network.netReplacementFor(gone, list, ids('old')), null);
    });

    await t.test('not another method or path', () => {
        const list = [
            tx('post', { method: 'POST', startedAt: 2000, replacedCount: 1 }),
            tx('path', { url: 'https://a.test/v1/featured.json/2', startedAt: 2000, replacedCount: 1 }),
        ];
        assert.strictEqual(network.netReplacementFor(gone, list, ids('old')), null);
    });

    await t.test('not a request that started earlier', () => {
        const list = [tx('earlier', { startedAt: 500, replacedCount: 1 })];
        assert.strictEqual(network.netReplacementFor(gone, list, ids('old')), null);
    });

    await t.test('the method is compared as overwrite mode compares it, ignoring case', () => {
        const list = [tx('new', { method: 'get', startedAt: 2000, replacedCount: 1 })];
        assert.strictEqual(network.netReplacementFor(gone, list, ids('old')).id, 'new');
    });
});

test('A row that replaced copies of its request says how many', async (t) => {
    await t.test('with the method and path in the tooltip', () => {
        const badge = network.netReplacedBadge(tx('a', { replacedCount: 3, url: 'https://a.test/v1/featured.json?page=2' }));
        assert.match(badge, />↻ 3</);
        assert.match(badge, /title="Replaced 3 earlier GET \/v1\/featured\.json requests \(overwrite mode\)"/);
    });

    await t.test('one copy is one request', () => {
        assert.match(network.netReplacedBadge(tx('a', { replacedCount: 1 })), /Replaced 1 earlier GET \/v1\/featured\.json request \(/);
    });

    await t.test('none, or a server from before protocol 1.10, shows no badge', () => {
        assert.strictEqual(network.netReplacedBadge(tx('a', { replacedCount: 0 })), '');
        assert.strictEqual(network.netReplacedBadge({ id: 'a', method: 'GET', url: 'https://a.test/' }), '');
    });

    await t.test('the path is text', () => {
        const badge = network.netReplacedBadge(tx('a', { replacedCount: 2, url: 'https://a.test/"><img src=x>' }));
        assert.ok(!badge.includes('<img'), badge);
    });
});
