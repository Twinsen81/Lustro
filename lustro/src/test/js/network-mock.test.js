'use strict';

// The two commands that turn a captured request into something to run again:
// the mock rule pattern Mock This Request fills in, and Copy as cURL.

const test = require('node:test');
const assert = require('node:assert');
const { loadNetworkJs } = require('./harness.js');

const network = loadNetworkJs();

// The rule pattern without its prefix, as the app's regex engine reads it.
const regexOf = (pattern) => {
    assert.ok(pattern.startsWith('regex:'), pattern);
    return new RegExp(pattern.slice('regex:'.length));
};

test('Mock This Request fills in a pattern for that URL and no other', async (t) => {
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

test('Mock This Request is disabled for a request that no rule can answer', async (t) => {
    await t.test('an OkHttp request, or one from before the source was sent, can be mocked', () => {
        for (const source of ['okhttp', undefined]) {
            const button = network.netMockThisButton({ source });
            assert.match(button, /data-action="mockThis"/);
            assert.doesNotMatch(button, /disabled/);
        }
    });

    await t.test('a platform HttpURLConnection request cannot', () => {
        const button = network.netMockThisButton({ source: 'platform' });
        assert.doesNotMatch(button, /data-action/);
        assert.match(button, /<button class="dc-btn" disabled /);
        assert.match(button, /Mock rules answer OkHttp requests only/);
    });

    await t.test("the app's own adapter can, if it asks for the rule", () => {
        const button = network.netMockThisButton({ source: 'app' });
        assert.match(button, /data-action="mockThis"/);
        assert.match(button, /only if the adapter asks for it/);
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
