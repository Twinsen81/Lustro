'use strict';

// The rows' checkboxes select requests for Copy Markdown and Export HAR. A
// filter change keeps only the selected rows it still shows, and the bar says
// how many it deselected. Each view's button counts its own traffic.

const test = require('node:test');
const assert = require('node:assert');
const { loadNetworkJs } = require('./harness.js');

const network = loadNetworkJs();

const rows = (...ids) => ids.map((id) => ({ id }));
const ids = (...list) => Object.fromEntries(list.map((id) => [id, true]));
// The sandbox's objects come from another realm, so compare them as plain data.
const plain = (value) => JSON.parse(JSON.stringify(value));

test('A filter change keeps only the selected rows it still shows', async (t) => {
    await t.test('a row the filters hide is deselected and counted', () => {
        const kept = network.netKeptSelection(ids('a', 'b', 'c'), rows('a', 'c'), rows('a', 'b', 'c'), false);
        assert.deepStrictEqual(plain(kept), { ids: ids('a', 'c'), hidden: 1 });
    });

    await t.test('a row that left the list, as Clear removes it, is not counted', () => {
        const kept = network.netKeptSelection(ids('a', 'gone'), rows('a'), rows('a'), false);
        assert.deepStrictEqual(plain(kept), { ids: ids('a'), hidden: 0 });
    });

    await t.test('a row that a new search does not find is counted', () => {
        const kept = network.netKeptSelection(ids('a', 'b'), rows('a'), rows('a'), true);
        assert.deepStrictEqual(plain(kept), { ids: ids('a'), hidden: 1 });
    });

    await t.test('nothing selected, nothing hidden', () => {
        assert.deepStrictEqual(plain(network.netKeptSelection({}, rows('a'), rows('a'), true)), { ids: {}, hidden: 0 });
    });

    await t.test('the note says how many', () => {
        assert.strictEqual(network.netSelectionNote(1), 'Deselected 1 request that the filters hide.');
        assert.strictEqual(network.netSelectionNote(3), 'Deselected 3 requests that the filters hide.');
    });
});

test('A shift-click selects the rows from the last one clicked', async (t) => {
    const list = rows('a', 'b', 'c', 'd', 'e');

    await t.test('down the list', () => {
        assert.deepStrictEqual(plain(network.netSelectionRange(list, 'b', 'd')), ['b', 'c', 'd']);
    });

    await t.test('up the list', () => {
        assert.deepStrictEqual(plain(network.netSelectionRange(list, 'e', 'c')), ['c', 'd', 'e']);
    });

    await t.test('only the row, with no anchor yet', () => {
        assert.deepStrictEqual(plain(network.netSelectionRange(list, null, 'c')), ['c']);
    });

    await t.test('only the row, when the filters hide the anchor', () => {
        assert.deepStrictEqual(plain(network.netSelectionRange(list, 'hidden', 'c')), ['c']);
    });

    await t.test('the row itself, clicked twice', () => {
        assert.deepStrictEqual(plain(network.netSelectionRange(list, 'c', 'c')), ['c']);
    });
});

test('Each view\'s button has its own count', async (t) => {
    await t.test('HTTP shows the total, or what the filters show of it', () => {
        assert.strictEqual(network.netHttpCountLabel(11, 11), ' (11)');
        assert.strictEqual(network.netHttpCountLabel(5, 11), ' (5/11)');
        assert.strictEqual(network.netHttpCountLabel(0, 0), ' (0)');
    });

    await t.test('the HTTP tooltip says what the numbers count', () => {
        assert.strictEqual(network.netHttpCountTitle(11, 11, 11, false), '11 requests captured.');
        assert.strictEqual(network.netHttpCountTitle(1, 1, 1, false), '1 request captured.');
        assert.strictEqual(network.netHttpCountTitle(5, 11, 5, false), '11 requests captured. The filters show 5 of them.');
        assert.strictEqual(network.netHttpCountTitle(1, 1, 1, true), '1 request matches the search.');
        assert.strictEqual(network.netHttpCountTitle(3, 4, 3, true), '4 requests match the search. The filters show 3 of them.');
    });

    await t.test('the HTTP tooltip says when the list draws only the first rows', () => {
        assert.strictEqual(network.netHttpCountTitle(300, 300, 200, false),
            '300 requests captured. The list draws the first 200, and "Load more" at its end draws more.');
    });

    await t.test('the WebSockets tooltip says how many are open', () => {
        const sockets = [{ state: 'open' }, { state: 'closed' }, { state: 'open' }];
        assert.strictEqual(network.netWsCountTitle(sockets), '3 connections, 2 open.');
        assert.strictEqual(network.netWsCountTitle([{ state: 'failed' }]), '1 connection, 0 open.');
        assert.strictEqual(network.netWsCountTitle([]), '0 connections, 0 open.');
    });
});
