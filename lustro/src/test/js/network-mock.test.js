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
            + "-F 'file=@cat.png;type=image/png' 'https://a.test/api/v2/media'");
});
