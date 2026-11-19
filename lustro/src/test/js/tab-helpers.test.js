// The helpers that shared.js gives a tab's script: the connection callback of
// debugPoll, the hide callback of debugModal, and debugErrorMessage.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { loadSharedJs } = require('./harness.js');

// Lets the awaits in shared.js finish: they resolve in the shared microtask queue.
function settle() {
    return new Promise((resolve) => setImmediate(resolve));
}

function response(status, body) {
    const text = body === undefined ? '' : JSON.stringify(body);
    function make() {
        let used = false;
        return {
            ok: status >= 200 && status < 300,
            status,
            json() {
                if (used) return Promise.reject(new TypeError('Body has already been consumed.'));
                used = true;
                return Promise.resolve().then(() => JSON.parse(text));
            },
            clone() {
                if (used) throw new TypeError('Response body is already used');
                return make();
            },
        };
    }
    return make();
}

// shared.js with a fetch that answers from `answers`, one per poll, and timers
// that run only when the test calls next().
function pollingShared(answers) {
    const shared = loadSharedJs();
    const timers = [];
    shared.setTimeout = (fn) => timers.push(fn);
    shared.fetch = () => {
        const answer = answers.shift();
        return answer instanceof Error ? Promise.reject(answer) : Promise.resolve(answer);
    };
    return {
        shared,
        async next() {
            const fn = timers.shift();
            assert.ok(fn, 'a next poll is scheduled');
            fn();
            await settle();
        },
    };
}

test('debugPoll reports the connection when it changes, and not on every poll', async () => {
    const down = () => new Error('Failed to fetch');
    const { shared, next } = pollingShared([
        down(), response(200, { n: 1 }), response(200, { n: 2 }),
        down(), down(), down(), down(), response(200, { n: 3 }),
    ]);
    const changes = [];
    const data = [];

    shared.debugPoll('/api/v1/x/poll', 1000, (d) => data.push(d.n), {
        onConnectionChange: (connected) => changes.push(connected),
    });
    await settle();
    assert.deepStrictEqual(changes, [], 'a failure before the first answer is no change');

    await next();
    assert.deepStrictEqual(changes, [true]);
    await next();
    await next();
    await next();
    assert.deepStrictEqual(changes, [true], 'two failures in a row keep the connection');
    await next();
    assert.deepStrictEqual(changes, [true, false]);
    await next();
    assert.deepStrictEqual(changes, [true, false], 'a failure while disconnected is no change');
    await next();
    assert.deepStrictEqual(changes, [true, false, true]);
    assert.deepStrictEqual(data, [1, 2, 3]);
});

test('debugPoll still takes onRejected as the fourth argument, or in the options', async () => {
    for (const make of [(fn) => fn, (fn) => ({ onRejected: fn })]) {
        const { shared } = pollingShared([response(409, { error: 'conflict', message: 'Busy' })]);
        const rejected = [];
        const changes = [];
        const options = make((status, envelope) => {
            rejected.push([status, envelope.message]);
            return true;
        });
        if (typeof options === 'object') options.onConnectionChange = (c) => changes.push(c);

        shared.debugPoll('/api/v1/x/poll', 1000, () => assert.fail('a rejected poll has no data'), options);
        await settle();

        assert.deepStrictEqual(rejected, [[409, 'Busy']]);
        if (typeof options === 'object') assert.deepStrictEqual(changes, [true], 'a handled rejection is an answer');
    }
});

test('debugPoll keeps polling when onConnectionChange throws', async () => {
    const { shared, next } = pollingShared([response(200, { n: 1 }), response(200, { n: 2 })]);
    const data = [];
    const consoleError = shared.console;
    shared.console = { ...console, error() {} };

    shared.debugPoll('/p', 1000, (d) => data.push(d.n), {
        onConnectionChange() { throw new Error('tab bug'); },
    });
    await settle();
    await next();

    shared.console = consoleError;
    assert.deepStrictEqual(data, [1, 2]);
});

// A .dc-modal-scrim in the tab markup, with the listeners shared.js puts on it.
function modalShared() {
    const shared = loadSharedJs();
    const listeners = {};
    const el = {
        hidden: true,
        addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
    };
    shared.document.getElementById = (id) => (id === 'edit-modal' ? el : null);
    shared.document.querySelectorAll = () => (el.hidden ? [] : [el]);
    return {
        shared,
        el,
        clickScrim() { listeners.mousedown.forEach((fn) => fn({ target: el })); },
        clickInside() { listeners.mousedown.forEach((fn) => fn({ target: {} })); },
        pressEscape() {
            const event = { key: 'Escape', preventDefault() {} };
            shared.documentListeners.keydown.forEach((fn) => fn(event));
        },
    };
}

test('debugModal calls onHide for hide(), Escape, and a click on the scrim', () => {
    const { shared, el, clickScrim, clickInside, pressEscape } = modalShared();
    let hides = 0;
    const modal = shared.debugModal('edit-modal', { onHide: () => hides++ });

    modal.show();
    modal.hide();
    assert.strictEqual(hides, 1);

    modal.show();
    pressEscape();
    assert.strictEqual(el.hidden, true);
    assert.strictEqual(hides, 2);

    modal.show();
    clickInside();
    assert.strictEqual(el.hidden, false, 'a click inside the dialog keeps it open');
    clickScrim();
    assert.strictEqual(el.hidden, true);
    assert.strictEqual(hides, 3);

    modal.toggle();
    modal.toggle();
    assert.strictEqual(hides, 4);
});

test('debugModal does not call onHide for a dialog that is already hidden', () => {
    const { shared, pressEscape } = modalShared();
    let hides = 0;
    const modal = shared.debugModal('edit-modal', { onHide: () => hides++ });

    modal.hide();
    pressEscape();
    modal.show();
    modal.hide();
    modal.hide();

    assert.strictEqual(hides, 1);
});

test('debugModal without options works as before', () => {
    const { shared, el, pressEscape } = modalShared();
    const modal = shared.debugModal('edit-modal');

    modal.show();
    assert.strictEqual(modal.isVisible(), true);
    pressEscape();
    assert.strictEqual(el.hidden, true);
});

test('debugErrorMessage gives the message of the error envelope', async () => {
    const shared = loadSharedJs();
    shared.fetch = () => Promise.resolve(response(409, { error: 'conflict', message: 'The flag changed. Reload it.' }));
    shared.debugSetStatus = () => {};

    const err = await shared.debugFetch('/api/v1/flags/toggle').then(() => assert.fail('rejects'), (e) => e);

    assert.strictEqual(err.status, 409);
    assert.strictEqual(await shared.debugErrorMessage(err), 'The flag changed. Reload it.');
    assert.strictEqual((await err.response.json()).error, 'conflict', 'the tab can still read the response');
});

test('debugErrorMessage falls back to err.message', async () => {
    const shared = loadSharedJs();
    const withStatus = (resp) => Object.assign(new Error('HTTP ' + resp.status), { status: resp.status, response: resp });

    assert.strictEqual(await shared.debugErrorMessage(new TypeError('Failed to fetch')), 'Failed to fetch');
    assert.strictEqual(await shared.debugErrorMessage(withStatus(response(502))), 'HTTP 502', 'no JSON body');
    assert.strictEqual(await shared.debugErrorMessage(withStatus(response(500, { error: 'internal_error' }))), 'HTTP 500');
    const read = withStatus(response(422, { message: 'x' }));
    await read.response.json();
    assert.strictEqual(await shared.debugErrorMessage(read), 'HTTP 422', 'the tab already read the body');
    assert.strictEqual(await shared.debugErrorMessage(null), 'Request failed');
});
