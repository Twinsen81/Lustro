// The notice that the console shows when the app answers 401 to a tab that has
// its content: the app has a new token, as after a restart with LAN exposure.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { loadSharedJs } = require('./harness.js');

function settle() {
    return new Promise((resolve) => setImmediate(resolve));
}

function response(status, body) {
    return {
        ok: status >= 200 && status < 300,
        status,
        json: () => Promise.resolve(body === undefined ? {} : body),
        text: () => Promise.resolve(typeof body === 'string' ? body : ''),
    };
}

// shared.js with the content area of a tab page, and a fetch that answers from
// `answers` by URL and records each URL. `notices` has the notices in the page.
function consoleShared(answers) {
    const shared = loadSharedJs();
    const notices = [];
    const parent = {
        insertBefore(el, before) {
            assert.strictEqual(before, root, 'the notice goes above the content');
            el.parentNode = parent;
            notices.push(el);
        },
        removeChild(el) {
            notices.splice(notices.indexOf(el), 1);
        },
    };
    const root = { parentNode: parent, innerHTML: '' };
    const requests = [];
    shared.document.body.dataset.lustroTab = 'flags';
    shared.document.getElementById = (id) =>
        (id === 'lustro-tab-content' ? root : notices.find((el) => el.id === id) || null);
    shared.location = { hash: '', pathname: '/tab/flags', search: '' };
    shared.history = { replaceState() {} };
    shared.fetch = (url) => {
        requests.push(url);
        const answer = answers[url].shift();
        return answer instanceof Error ? Promise.reject(answer) : Promise.resolve(answer);
    };
    return { shared, notices, requests, root };
}

test('a 401 of debugFetch shows one notice above the content, and an ok answer removes it', async () => {
    const { shared, notices, root } = consoleShared({
        '/api/v1/flags/list': [response(401), response(401), response(500), response(200)],
    });
    root.innerHTML = '<table>the rows from before</table>';
    const call = () => shared.debugFetch('/api/v1/flags/list').catch((e) => e);

    await call();
    assert.strictEqual(notices.length, 1);
    assert.strictEqual(notices[0].id, 'lustro-signed-out');
    assert.match(notices[0].innerHTML, /#lustro_token=/);
    assert.strictEqual(root.innerHTML, '<table>the rows from before</table>', 'the content stays');

    await call();
    assert.strictEqual(notices.length, 1, 'a second 401 adds no second notice');
    await call();
    assert.strictEqual(notices.length, 1, 'a 500 says nothing about the token');
    await call();
    assert.strictEqual(notices.length, 0);
});

test('a status other than 401, and a failed connection, show no notice', async () => {
    const { shared, notices } = consoleShared({
        '/api/v1/flags/list': [response(403), response(503), new Error('Failed to fetch')],
    });

    for (let i = 0; i < 3; i++) await shared.debugFetch('/api/v1/flags/list').catch((e) => e);

    assert.strictEqual(notices.length, 0);
});

test('a 401 of debugPoll shows the notice at once, and the next ok poll removes it', async () => {
    const { shared, notices } = consoleShared({
        '/api/v1/flags/poll': [response(200, { n: 1 }), response(401), response(200, { n: 2 })],
    });
    const timers = [];
    shared.setTimeout = (fn) => timers.push(fn);
    const data = [];
    const next = async () => {
        timers.shift()();
        await settle();
    };

    shared.debugPoll('/api/v1/flags/poll', 1000, (d) => data.push(d.n));
    await settle();
    assert.strictEqual(notices.length, 0);

    await next();
    assert.strictEqual(notices.length, 1, 'at the first 401, before the poll counts the connection as lost');

    await next();
    assert.strictEqual(notices.length, 0);
    assert.deepStrictEqual(data, [1, 2]);
});

test('a sign-in from the URL fragment removes the notice and does not load the content again', async () => {
    const { shared, notices, requests, root } = consoleShared({
        '/api/v1/flags/_view': [response(200, '<table>the rows from before</table>')],
        '/api/v1/flags/list': [response(401)],
        '/api/v1/_auth': [response(401), response(200)],
    });
    shared.documentListeners.DOMContentLoaded.forEach((listener) => listener());
    await settle();
    assert.strictEqual(root.innerHTML, '<table>the rows from before</table>');
    await shared.debugFetch('/api/v1/flags/list').catch((e) => e);
    assert.strictEqual(notices.length, 1);

    const signIn = async (token) => {
        shared.location.hash = '#lustro_token=' + token;
        shared.windowListeners.hashchange.forEach((listener) => listener());
        await settle();
    };

    await signIn('not-the-token');
    assert.strictEqual(notices.length, 1, 'a token that the app refuses keeps the notice');

    await signIn('the-new-token');
    assert.strictEqual(notices.length, 0);
    assert.deepStrictEqual(
        requests,
        ['/api/v1/flags/_view', '/api/v1/flags/list', '/api/v1/_auth', '/api/v1/_auth'],
        'the content is loaded once: a second load would not run the tab script again',
    );
});
