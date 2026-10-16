'use strict';

// The two commands that turn a captured request into something to run again:
// the mock rule pattern Mock this request fills in, and Copy as cURL; and what
// the detail says about the mock rules for a request.

const test = require('node:test');
const assert = require('node:assert');
const { loadNetworkJs } = require('./harness.js');

const network = loadNetworkJs();

// The rule pattern without its prefix, as the app's regex engine reads it.
const regexOf = (pattern) => {
    assert.ok(pattern.startsWith('regex:'), pattern);
    return new RegExp(pattern.slice('regex:'.length));
};

test('Mock this request fills in a pattern for that URL and no other', async (t) => {
    await t.test('a sub-resource of the URL does not match', () => {
        const re = regexOf(network.netExactUrlPattern('https://mastodon.social/api/v1/statuses/1173887'));
        assert.ok(re.test('https://mastodon.social/api/v1/statuses/1173887'));
        assert.ok(!re.test('https://mastodon.social/api/v1/statuses/1173887/context'));
        assert.ok(!re.test('https://mastodon.social/api/v1/statuses/11738870'));
        assert.ok(!re.test('https://evil.test/?u=https://mastodon.social/api/v1/statuses/1173887'));
    });

    await t.test('the query must match, and its regex characters are literal', () => {
        const re = regexOf(network.netExactUrlPattern('https://a.test/search?q=a.b+(c)&limit=30'));
        assert.ok(re.test('https://a.test/search?q=a.b+(c)&limit=30'));
        assert.ok(!re.test('https://a.test/search?q=aXb+(c)&limit=30'));
        assert.ok(!re.test('https://a.test/search?q=a.b+(c)&limit=300'));
    });

    await t.test('a redacted query value matches the value the app really sends', () => {
        const re = regexOf(network.netExactUrlPattern('https://a.test/feed?access_token=%5BREDACTED%5D&page=2'));
        assert.ok(re.test('https://a.test/feed?access_token=s3cr3t&page=2'));
        assert.ok(!re.test('https://a.test/feed?access_token=s3cr3t&page=3'));
    });
});

test('Mock this request and Matching rules are disabled for a request that no rule can answer', async (t) => {
    await t.test('an OkHttp request, or one from before the source was sent, can be mocked', () => {
        for (const source of ['okhttp', undefined]) {
            const state = network.netMockButtonsState({ source, matchingMockRuleIds: ['r1', 'r2'] });
            assert.strictEqual(state.disabled, false);
            assert.strictEqual(state.ruleCount, 2);
        }
    });

    await t.test('a platform HttpURLConnection request cannot', () => {
        const state = network.netMockButtonsState({ source: 'platform', matchingMockRuleIds: [] });
        assert.strictEqual(state.disabled, true);
        assert.match(state.mockThisTitle, /Mock rules answer OkHttp requests only/);
        assert.match(state.rulesTitle, /Mock rules answer OkHttp requests only/);
    });

    await t.test("the app's own adapter can, if it asks for the rule", () => {
        const state = network.netMockButtonsState({ source: 'app' });
        assert.strictEqual(state.disabled, false);
        assert.match(state.mockThisTitle, /only if the adapter asks for it/);
        assert.strictEqual(state.ruleCount, 0);
    });
});

test('The detail says which rule answered the request and which one answers the next', async (t) => {
    const rules = [
        { id: 'r1', name: 'GET featured.json', urlPattern: '/featured.json', enabled: true },
        { id: 'r2', name: '', method: 'GET', urlPattern: 'regex:/feed$', enabled: true },
    ];
    const text = (html) => html.replace(/<[^>]+>/g, '');

    await t.test('a rule that matches a request captured before it existed', () => {
        const line = network.netMockRuleLine({ isMocked: false, mockRuleId: null, activeMockRuleId: 'r1' }, rules);
        assert.strictEqual(text(line), 'Mock rule active: GET featured.json. The next request like this one gets the mocked response.');
        assert.match(line, /data-action="openRequestRules"/);
    });

    await t.test('a rule without a name is named by its method and pattern', () => {
        const line = network.netMockRuleLine({ isMocked: false, activeMockRuleId: 'r2' }, rules);
        assert.match(text(line), /^Mock rule active: GET regex:\/feed\$\./);
    });

    await t.test('the rule that answered, while it still answers', () => {
        const line = network.netMockRuleLine({ isMocked: true, mockRuleId: 'r1', activeMockRuleId: 'r1' }, rules);
        assert.strictEqual(text(line),
            'Mocked by rule GET featured.json. The rule is still active, so the next request like this one gets the same response.');
    });

    await t.test('the rule that answered, after another one took its place', () => {
        const line = network.netMockRuleLine({ isMocked: true, mockRuleId: 'r1', activeMockRuleId: 'r2' }, rules);
        assert.strictEqual(text(line), 'Mocked by rule GET featured.json. Now rule GET regex:/feed$ answers the next request like this one.');
    });

    await t.test('the rule that answered, after it was disabled', () => {
        const line = network.netMockRuleLine({ isMocked: true, mockRuleId: 'r1', activeMockRuleId: null }, rules);
        assert.strictEqual(text(line),
            'Mocked by rule GET featured.json. No enabled rule matches it now, so the next request like this one goes to the server.');
    });

    await t.test('a rule that was deleted, once the rules are loaded', () => {
        const tx = { isMocked: true, mockRuleId: 'gone', activeMockRuleId: null };
        assert.match(text(network.netMockRuleLine(tx, rules)), /^Mocked by a rule that was deleted\./);
        assert.match(text(network.netMockRuleLine(tx, null)), /^Mocked by a rule\./);
    });

    await t.test("an app adapter's request gets the rule only if the adapter asks for it", () => {
        const line = network.netMockRuleLine({ isMocked: false, activeMockRuleId: 'r1', source: 'app' }, rules);
        assert.match(text(line), /gets the mocked response, if the app's adapter asks for mock rules\.$/);
    });

    await t.test('nothing when no rule answered it and none matches', () => {
        assert.strictEqual(network.netMockRuleLine({ isMocked: false, mockRuleId: null, activeMockRuleId: null }, rules), '');
    });

    await t.test("a rule's name is escaped", () => {
        const line = network.netMockRuleLine({ isMocked: false, activeMockRuleId: 'x' }, [{ id: 'x', name: '<img src=x>', urlPattern: 'a' }]);
        assert.doesNotMatch(line, /<img/);
        assert.match(line, /&lt;img src=x&gt;/);
    });
});

test('Copy as cURL keeps the type of a typed text part', () => {
    const command = network.netCurlCommand({
        method: 'POST',
        url: 'https://a.test/upload',
        requestContentType: 'multipart/form-data; boundary=b0',
        requestHeaders: {},
        requestBody: '--b0\r\nContent-Disposition: form-data; name="meta"\r\nContent-Type: application/json\r\n\r\n'
            + '{"a":"x;y","b":"back\\\\slash"}\r\n--b0--\r\n',
    });
    assert.strictEqual(command,
        "curl -X POST -F 'meta=\"{\\\"a\\\":\\\"x;y\\\",\\\"b\\\":\\\"back\\\\\\\\slash\\\"}\";type=application/json' 'https://a.test/upload'");
});

test('Copy as cURL builds a multipart body from its parts', () => {
    const command = network.netCurlCommand({
        method: 'POST',
        url: 'https://a.test/api/v2/media',
        requestContentType: 'multipart/form-data; boundary=b0',
        requestHeaders: { 'Content-Type': 'multipart/form-data; boundary=b0', Authorization: '[REDACTED]' },
        requestBody: '--b0\r\nContent-Disposition: form-data; name="description"\r\n\r\n@not a file\r\n'
            + '--b0\r\nContent-Disposition: form-data; name="file"; filename="cat.png"\r\nContent-Type: image/png\r\n'
            + 'Content-Length: 300\r\n\r\n[Lustro did not store this part: image/png, 300 bytes]\r\n--b0--\r\n',
    });
    assert.strictEqual(command,
        "curl -X POST -H 'Authorization: [REDACTED]' --form-string 'description=@not a file' "
            + "-F 'file=@\"cat.png\";type=image/png' 'https://a.test/api/v2/media'");
});

test('Copy as cURL quotes a file name that curl would split', () => {
    const command = network.netCurlCommand({
        method: 'POST',
        url: 'https://a.test/upload',
        requestContentType: 'multipart/form-data; boundary=b0',
        requestHeaders: {},
        requestBody: '--b0\r\nContent-Disposition: form-data; name="file"; filename="photo,edited;v2.png"\r\n\r\n'
            + '[Lustro did not store this part: image/png, 7 bytes]\r\n--b0--\r\n',
    });
    assert.strictEqual(command, "curl -X POST -F 'file=@\"photo,edited;v2.png\"' 'https://a.test/upload'");
});
