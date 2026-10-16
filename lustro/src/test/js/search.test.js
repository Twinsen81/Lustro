// How the viewers mark a search: a string is found as typed, ignoring case,
// as the plain search finds it on the device, and a RegExp as the regex search
// finds it.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { loadSharedJs } = require('./harness.js');

const win = loadSharedJs();
const mark = (text, search) => win.debugHighlightPlain(text, search);

test('a string is marked as typed, ignoring case, with no syntax', () => {
    assert.strictEqual(mark('Featured and featured', 'featured'), '<mark>Featured</mark> and <mark>featured</mark>');
    assert.strictEqual(mark('a|b and ab', 'a|b'), '<mark>a|b</mark> and ab');
    assert.strictEqual(mark('-1 and 1', '-1'), '<mark>-1</mark> and 1');
    assert.strictEqual(mark('GET /api/v1/users', '/api/'), 'GET <mark>/api/</mark>v1/users');
});

test('a RegExp is marked as it finds, ignoring case when it says so', () => {
    assert.strictEqual(mark('featured and Popular', /featured|popular/gi), '<mark>featured</mark> and <mark>Popular</mark>');
    assert.strictEqual(mark('get Featured-12.json now', /featured-\d+\.json/i), 'get <mark>Featured-12.json</mark> now');
    // Without the g flag it still marks every match.
    assert.strictEqual(mark('aXa', /a/), '<mark>a</mark>X<mark>a</mark>');
});

test('the text around a match stays escaped', () => {
    assert.strictEqual(mark('<b>&</b>', /b>|&/g), '&lt;<mark>b&gt;</mark><mark>&amp;</mark>&lt;/<mark>b&gt;</mark>');
});

test('a regex that matches nothing marks nothing and does not loop', () => {
    assert.strictEqual(mark('aaa b', /x*/g), 'aaa b');
    assert.strictEqual(mark('aaa b', /a*/g), '<mark>aaa</mark> b');
    assert.strictEqual(mark('anything', null), 'anything');
});

test('ranges are reported in order for a caller that marks across pieces', () => {
    // The arrays come from the script's own context, so compare them as JSON.
    assert.strictEqual(JSON.stringify(win.debugMatchRanges('xabxcd', /cd|ab/g)), '[[1,3],[4,6]]');
    assert.strictEqual(win.debugMarkRanges('cdef', [[1, 3], [4, 6]], 2), '<mark>c</mark>d<mark>ef</mark>');
});

test('the line-numbered view marks matches in the whole text, as the device finds them', () => {
    const lines = (text, search) => win.debugLineNumbered(text, { searchText: search })
        .match(/<span class="dc-lines__line">.*?<\/span>/gs);
    // ^ is the start of the text, not of each line.
    assert.deepStrictEqual(lines('foo\nfoo', /^foo/gi), [
        '<span class="dc-lines__line"><mark>foo</mark>\n</span>',
        '<span class="dc-lines__line">foo</span>',
    ]);
    // A match that goes on to the next line is marked on both.
    assert.deepStrictEqual(lines('a\r\nb c', /a\s+b/gi), [
        '<span class="dc-lines__line"><mark>a</mark>\n</span>',
        '<span class="dc-lines__line"><mark>b</mark> c</span>',
    ]);
});

test('the line-numbered view marks many matches in many lines in linear time', () => {
    const text = Array.from({ length: 20000 }, () => 'a b a').join('\n');
    const started = Date.now();
    const html = win.debugLineNumbered(text, { searchText: 'a' });
    assert.ok(Date.now() - started < 2000, 'took ' + (Date.now() - started) + ' ms');
    assert.strictEqual(html.split('<mark>a</mark>').length - 1, 40000);
    // A match that spans lines is marked on each of them.
    assert.ok(win.debugLineNumbered('x\nab\ncd\ny', { searchText: /b\s+c/gi }).includes('a<mark>b</mark>\n</span><span class="dc-lines__line"><mark>c</mark>d'));
});

test('a poll lets its caller handle a rejected response, as a search that stopped, without losing the connection', async () => {
    const statuses = [];
    const rejected = [];
    let answered = false;
    const realStatus = win.debugSetStatus;
    win.debugSetStatus = (state) => statuses.push(state);
    win.fetch = async () => ({ ok: false, status: 400, json: async () => ({ error: 'bad_request', field: 'search', message: 'slow' }) });
    try {
        const poll = win.debugPoll('/x', 1000, () => { answered = true; }, (status, envelope) => {
            rejected.push(status + ' ' + envelope.field);
            return true;
        });
        await new Promise((resolve) => setTimeout(resolve, 20));
        poll.stop();
    } finally {
        win.debugSetStatus = realStatus;
        delete win.fetch;
    }
    assert.deepStrictEqual(rejected, ['400 search']);
    assert.strictEqual(answered, false);
    assert.deepStrictEqual(statuses, ['connected']);
});
