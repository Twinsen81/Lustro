// The search syntax as the console parses it to mark matches. The device runs
// the search with SearchQuery.kt; both parse the cases in search-queries.json,
// so a query marks what it found.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { loadSharedJs } = require('./harness.js');

const win = loadSharedJs();
const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'resources', 'search-queries.json'), 'utf8'));

// The one-line form SearchQuery.describe() gives.
function describe(query) {
    return query.groups.map(function(group) {
        const terms = group.map(function(t) {
            const value = t.kind === 'regex'
                ? '/' + t.value + '/'
                : '"' + t.value.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
            return (t.negated ? '-' : '') + (t.field ? t.field + ':' : '') + value;
        }).join(' OR ');
        return group.length > 1 ? '(' + terms + ')' : terms;
    }).join(' AND ');
}

test('each fixture query parses as the fixture says', () => {
    for (const c of fixture.parse) {
        const query = win.debugParseSearch(c.query, { fields: c.fields !== false });
        assert.strictEqual(describe(query), c.terms, c.query);
    }
});

test('each fixture error fails to parse', () => {
    for (const q of fixture.errors) {
        assert.throws(() => win.debugParseSearch(q), /status: takes a code/, q);
    }
});

const mark = (text, search, field) => win.debugHighlightPlain(text, search, field);

test('every term of an or is marked, and a negated term is not', () => {
    assert.strictEqual(mark('featured and popular', 'featured | popular'), '<mark>featured</mark> and <mark>popular</mark>');
    assert.strictEqual(mark('lists of json', 'lists json'), '<mark>lists</mark> of <mark>json</mark>');
    assert.strictEqual(mark('pocketcasts analytics', 'pocketcasts -analytics'), '<mark>pocketcasts</mark> analytics');
    assert.strictEqual(mark('analytics', 'NOT analytics'), 'analytics');
});

test('a regex marks what it finds, ignoring case', () => {
    assert.strictEqual(mark('get Featured-12.json now', '/featured-\\d+\\.json/'), 'get <mark>Featured-12.json</mark> now');
    assert.strictEqual(mark('order paid', '/^order\\spaid/'), '<mark>order paid</mark>');
    assert.strictEqual(mark('order paid', 'regex:pa+id'), 'order <mark>paid</mark>');
});

test('a phrase is marked whole, and plain text that looks like JSON is a word', () => {
    assert.strictEqual(mark('the podcast list', '"podcast list"'), 'the <mark>podcast list</mark>');
    assert.strictEqual(mark('{"id":42}', '"id":42'), '{<mark>"id":42</mark>}');
    assert.strictEqual(mark('GET /api/v1/users', '/api/v1/'), 'GET <mark>/api/v1/</mark>users');
});

test('a term marks only the field it names', () => {
    assert.strictEqual(mark('feed', 'url:feed'), 'feed');
    assert.strictEqual(mark('feed', 'url:feed', 'url'), '<mark>feed</mark>');
    assert.strictEqual(mark('feed', 'body:feed'), '<mark>feed</mark>');
    assert.strictEqual(mark('Cache-Control: no-cache', 'header:no-cache', 'header'), 'Cache-Control: <mark>no-cache</mark>');
    assert.strictEqual(mark('404', 'status:404'), '404');
    assert.strictEqual(mark('get', 'method:get'), 'get');
});

test('a query parsed without fields marks a prefix as text, as for a WebSocket message', () => {
    const query = win.debugParseSearch('url:x', { fields: false });
    assert.strictEqual(mark('a url:x b', query), 'a <mark>url:x</mark> b');
});

test('overlapping or touching matches make one mark, and the text around them stays escaped', () => {
    assert.strictEqual(mark('abc', 'ab | bc'), '<mark>abc</mark>');
    assert.strictEqual(mark('<b>&</b>', 'b> | &'), '&lt;<mark>b&gt;&amp;</mark>&lt;/<mark>b&gt;</mark>');
});

test('a regex that matches nothing, or that this browser cannot compile, marks nothing and does not throw', () => {
    assert.strictEqual(mark('aaa b', '/x*/'), 'aaa b');
    assert.strictEqual(mark('aaa b', '/a*/'), '<mark>aaa</mark> b');
    assert.strictEqual(mark('a(b', '/(?<x/ | b'), 'a(<mark>b</mark>');
    assert.strictEqual(mark('status:ok', 'status:ok'), 'status:ok');
});

test('ranges are reported in order for a caller that marks across pieces', () => {
    // The arrays come from the script's own context, so compare them as JSON.
    assert.strictEqual(JSON.stringify(win.debugMatchRanges('xabxcd', 'cd | ab')), '[[1,3],[4,6]]');
    assert.strictEqual(win.debugMarkRanges('cdef', [[1, 3], [4, 6]], 2), '<mark>c</mark>d<mark>ef</mark>');
});
