// The status pill in the top bar shows whether the console reaches the app: an
// error answer leaves it "Live", and only a request without an answer, a 401, or
// three failed polls in a row set "Error".
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { loadSharedJs } = require('./harness.js');

function settle() {
    return new Promise((resolve) => setImmediate(resolve));
}

function response(status, body) {
    const resp = {
        ok: status >= 200 && status < 300,
        status,
        json: () => Promise.resolve(body === undefined ? {} : body),
        clone: () => resp,
    };
    return resp;
}

// shared.js with a fetch that answers from `answers` in order, timers that run
// only when the test calls next(), and the states that set the pill in `statuses`.
function consoleShared(answers) {
    const shared = loadSharedJs();
    const statuses = [];
    shared.debugSetStatus = (state) => statuses.push(state);
    const timers = [];
    shared.setTimeout = (fn) => timers.push(fn);
    shared.fetch = () => {
        const answer = answers.shift();
        assert.ok(answer, 'the test gives an answer for each request');
        return answer instanceof Error ? Promise.reject(answer) : Promise.resolve(answer);
    };
    return {
        shared,
        statuses,
        async next() {
            const fn = timers.shift();
            assert.ok(fn, 'a next poll is scheduled');
            fn();
            await settle();
        },
    };
}

test('an error answer to debugFetch sets "Live", and debugFetch still rejects with the status and the response', async () => {
    for (const status of [400, 404, 409, 500]) {
        const answer = response(status, { error: 'x', message: 'Refused' });
        const { shared, statuses } = consoleShared([answer]);

        const err = await shared.debugFetch('/api/v1/x/do').then(() => assert.fail('rejects'), (e) => e);

        assert.strictEqual(err.status, status);
        assert.strictEqual(err.response, answer);
        assert.strictEqual(await shared.debugErrorMessage(err), 'Refused');
        assert.deepStrictEqual(statuses, ['connected'], 'after a ' + status);
    }
});

test('a 2xx answer to debugFetch sets "Live"', async () => {
    const { shared, statuses } = consoleShared([response(200, { n: 1 })]);

    const resp = await shared.debugFetch('/api/v1/x/list');

    assert.deepStrictEqual(await resp.json(), { n: 1 });
    assert.deepStrictEqual(statuses, ['connected']);
});

test('a 401 to debugFetch keeps the signed-out path, which sets "Error"', async () => {
    const { shared, statuses } = consoleShared([response(401)]);
    const signedOut = [];
    shared.lustroSignedOut = (value) => {
        signedOut.push(value);
        shared.debugSetStatus('error');
    };

    const err = await shared.debugFetch('/api/v1/x/list').then(() => assert.fail('rejects'), (e) => e);

    assert.strictEqual(err.status, 401);
    assert.deepStrictEqual(signedOut, [true]);
    assert.deepStrictEqual(statuses, ['error'], 'no "Live" after the notice');
});

test('debugFetch sets "Error" when fetch gets no answer, and rejects with the error of fetch', async () => {
    const failure = new TypeError('Failed to fetch');
    const { shared, statuses } = consoleShared([failure]);

    const err = await shared.debugFetch('/api/v1/x/list').then(() => assert.fail('rejects'), (e) => e);

    assert.strictEqual(err, failure);
    assert.deepStrictEqual(statuses, ['error']);
});

test('a running debugPoll sets "Live" at its next answer after another request set "Error"', async () => {
    const { shared, statuses, next } = consoleShared([
        response(200, { n: 1 }),
        new TypeError('Failed to fetch'),
        response(200, { n: 2 }),
    ]);
    const changes = [];

    shared.debugPoll('/api/v1/x/poll', 1000, () => {}, { onConnectionChange: (c) => changes.push(c) });
    await settle();
    assert.deepStrictEqual(statuses, ['connected']);

    await shared.debugFetch('/api/v1/x/do').catch(() => {});
    assert.deepStrictEqual(statuses, ['connected', 'error']);

    await next();
    assert.deepStrictEqual(statuses, ['connected', 'error', 'connected']);
    assert.deepStrictEqual(changes, [true], 'the poll stayed connected, so it reports no change');
});

test('a handled rejection of debugPoll sets "Live", and a handled 401 does not', async () => {
    const { shared, statuses, next } = consoleShared([response(409), response(401)]);

    shared.debugPoll('/api/v1/x/poll', 1000, () => assert.fail('a rejected poll has no data'), () => true);
    await settle();
    assert.deepStrictEqual(statuses, ['connected']);

    await next();
    assert.deepStrictEqual(statuses, ['connected', 'error'], 'the 401 sets "Error" through the signed-out notice');
});

test('three failed polls in a row still set "Error", and two do not', async () => {
    const down = () => new TypeError('Failed to fetch');
    const { shared, statuses, next } = consoleShared([
        response(200, { n: 1 }), down(), down(), response(200, { n: 2 }), down(), response(500), down(),
    ]);
    const data = [];

    shared.debugPoll('/api/v1/x/poll', 1000, (d) => data.push(d.n));
    await settle();
    await next();
    await next();
    assert.deepStrictEqual(statuses, ['connected'], 'two failures keep the pill');
    await next();
    assert.deepStrictEqual(statuses, ['connected', 'connected']);
    await next();
    await next();
    assert.deepStrictEqual(statuses, ['connected', 'connected'], 'a 500 that the poll does not handle is a failure');
    await next();
    assert.deepStrictEqual(statuses, ['connected', 'connected', 'error']);
    assert.deepStrictEqual(data, [1, 2]);
});
