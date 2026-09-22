// The JSON viewer: debugScanJsonSource and the two functions on top of it.
//
// Every captured body the Network tab shows, copies, or dumps goes through this
// path, and the point of it is that the text comes out as it arrived. Two
// properties carry that:
//
//   1. The grammar accepted matches JSON.parse's exactly, in both directions.
//      Too strict and a body silently falls back to unhighlighted plain text;
//      too lax is worse, because the scanner would re-emit tokens it mis-parsed.
//   2. Every key, string, number, and literal comes back as the exact slice of
//      the input. Only whitespace is added.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { loadSharedJs } = require('./harness.js');
const { createGenerator } = require('./json-corpus.js');

const shared = loadSharedJs();
const { debugScanJsonSource, debugFormatJson, debugSyntaxHighlightJson } = shared;

// Spelled out by code point: these are invisible or easily mangled in source.
const BOM = String.fromCharCode(0xfeff);
const NBSP = String.fromCharCode(0xa0);
const VERTICAL_TAB = String.fromCharCode(0x0b);
const START_OF_HEADING = String.fromCharCode(0x01);
const DELETE = String.fromCharCode(0x7f);

const parses = (text) => {
    try {
        JSON.parse(text);
        return true;
    } catch (e) {
        return false;
    }
};

// Strips exactly the whitespace a pretty-printer is allowed to add: everything
// outside string literals. Deliberately far simpler than the scanner under test
// so it can serve as an independent oracle for "only whitespace was added".
function stripInsignificantWhitespace(json) {
    let out = '';
    let inString = false;
    for (let i = 0; i < json.length; i++) {
        const char = json[i];
        if (inString) {
            out += char;
            if (char === '\\') out += json[++i];
            else if (char === '"') inString = false;
            continue;
        }
        if (char === '"') inString = true;
        else if (char === ' ' || char === '\t' || char === '\n' || char === '\r') continue;
        out += char;
    }
    return out;
}

test('the accepted grammar is JSON.parse\'s', async (t) => {
    // Documents JSON.parse rejects, grouped by the rule they break. The scanner
    // must reject every one: accepting any would mean re-emitting tokens from a
    // document it had mis-parsed.
    const rejected = [
        '01', '1.', '.1', '+1', '1e', '1e+', '-', '--1', '0x10', '1_0',
        'Infinity', 'NaN', '-Infinity',
        "'a'", '`a`', '{a:1}', '{"a":1,}', '[1,]', '[,1]', '{"a"1}', '{"a":}',
        '{,}', '[]]', '{}}', '[1 2]', '{"a":1 "b":2}', '"a"b', '{"a":1}{"b":2}',
        'tru', 'truee', 'nulll', 'TRUE', 'True',
        '"\\x41"', '"\\u12"', '"\\uZZZZ"', '"\\a"',
        '"unterminated', '{"a":1', '[1',
        '', '   ', '\n',
        // Characters JSON.parse does not accept as whitespace, and a control
        // character raw inside a string. Built from char codes rather than
        // pasted, so what is under test is visible in the source.
        BOM + '{}', NBSP + ' 1', '[' + VERTICAL_TAB + '1]', '1' + VERTICAL_TAB,
        '"' + START_OF_HEADING + '"',
    ];

    const accepted = [
        '0', '-0', '1', '1.10', '1e2', '1E2', '1e-2', '0.0e-0', '1e309',
        '12345678901234567890', 'true', 'false', 'null',
        '""', '"a"', '"/"', '"\\/"', '"\\u00e9"', '"\\uD83D\\uDE00"',
        // DEL and NBSP sit above the control range JSON forbids in a string.
        '"' + DELETE + '"', '"' + NBSP + '"',
        '[]', '{}', '[ ]', '{ }', '{"":1}', '{"a" : 1}', '  1  ',
        '[[[[[1]]]]]', '[null,true,false]', '{"__proto__":1}', '{"a":1,"a":2}',
    ];

    await t.test('rejects what JSON.parse rejects', () => {
        for (const document of rejected) {
            assert.ok(!parses(document), `fixture ${JSON.stringify(document)} should not be valid JSON`);
            assert.strictEqual(
                debugScanJsonSource(document, 2),
                null,
                `scanner accepted ${JSON.stringify(document)}, which JSON.parse rejects`,
            );
        }
    });

    await t.test('accepts what JSON.parse accepts', () => {
        for (const document of accepted) {
            assert.ok(parses(document), `fixture ${JSON.stringify(document)} should be valid JSON`);
            assert.notStrictEqual(
                debugScanJsonSource(document, 2),
                null,
                `scanner rejected ${JSON.stringify(document)}, which JSON.parse accepts`,
            );
        }
    });

    await t.test('agrees with JSON.parse on generated and mutated documents', () => {
        const generate = createGenerator(0x9e3779b9);
        for (let n = 0; n < 20000; n++) {
            const document = generate.next();
            const accepts = debugScanJsonSource(document, 2) !== null;
            assert.strictEqual(
                accepts,
                parses(document),
                `scanner ${accepts ? 'accepted' : 'rejected'} ${JSON.stringify(document)}, ` +
                    `JSON.parse ${parses(document) ? 'accepts' : 'rejects'} it`,
            );
        }
    });
});

test('formatting adds whitespace and nothing else', async (t) => {
    // The shapes that motivated scanning the source instead of round-tripping
    // through JSON.parse. Each left-hand side is rewritten by JSON.stringify.
    const preserved = [
        ['{"id":12345678901234567890}', 'an integer past 2^53 keeps its digits'],
        ['{"n":9007199254740993}', 'an integer one past 2^53 keeps its last digit'],
        ['{"n":1.10}', 'a trailing zero survives'],
        ['{"n":1e2}', 'an exponent is not expanded'],
        ['{"n":1E+2}', 'an exponent keeps its case and sign'],
        ['{"n":0.1000}', 'trailing fraction zeroes survive'],
        ['{"a":1,"a":2}', 'a repeated key keeps both entries'],
        ['{"e":"\\u00e9"}', 'a \\uXXXX escape is not decoded'],
        ['{"e":"\\uD83D\\uDE00"}', 'a surrogate pair escape is not decoded'],
        ['{"s":"a\\/b"}', 'an escaped solidus is not unescaped'],
    ];

    await t.test('golden cases survive a format', () => {
        for (const [source, why] of preserved) {
            assert.strictEqual(
                stripInsignificantWhitespace(debugFormatJson(source, 2)),
                stripInsignificantWhitespace(source),
                why,
            );
        }
    });

    await t.test('a JSON.parse round-trip would have rewritten them', () => {
        // The guard behind this whole path: if debugFormatJson is ever reverted
        // to JSON.parse + JSON.stringify, the cases above stop holding. This
        // asserts they are cases where that actually differs, so a green suite
        // above means something.
        for (const [source, why] of preserved) {
            const roundTripped = JSON.stringify(JSON.parse(source), null, 2);
            assert.notStrictEqual(
                stripInsignificantWhitespace(roundTripped),
                stripInsignificantWhitespace(source),
                `${why} — but a JSON.parse round-trip preserves it too, so this case has no teeth`,
            );
        }
    });

    await t.test('generated documents survive a format', () => {
        const generate = createGenerator(0x85ebca6b);
        for (let n = 0; n < 5000; n++) {
            const source = generate.validWithWhitespace();
            assert.ok(parses(source), `generator emitted invalid JSON: ${JSON.stringify(source)}`);
            assert.strictEqual(
                stripInsignificantWhitespace(debugFormatJson(source, 2)),
                stripInsignificantWhitespace(source),
                `formatting rewrote ${JSON.stringify(source)}`,
            );
        }
    });

    await t.test('every scanned piece is an exact slice of the input', () => {
        const generate = createGenerator(0xc2b2ae35);
        for (let n = 0; n < 5000; n++) {
            const source = generate.validWithWhitespace();
            const pieces = debugScanJsonSource(source, 2);
            assert.notStrictEqual(pieces, null, `scanner rejected valid ${JSON.stringify(source)}`);
            // Each classified piece must be found in the input, in order, at
            // non-overlapping positions — so none of them was synthesised.
            let cursor = 0;
            for (const piece of pieces) {
                if (!piece.cls) continue;
                // An empty object or array is emitted as one '{}' / '[]' piece;
                // its braces may be separated by whitespace in the input.
                const tokens = piece.text === '{}' || piece.text === '[]'
                    ? piece.text.split('')
                    : [piece.text];
                for (const token of tokens) {
                    const at = source.indexOf(token, cursor);
                    assert.ok(
                        at >= 0,
                        `piece ${JSON.stringify(token)} is not a slice of ${JSON.stringify(source)}`,
                    );
                    cursor = at + token.length;
                }
            }
        }
    });

    await t.test('indentation is the caller\'s', () => {
        assert.strictEqual(debugFormatJson('{"a":1}', 2), '{\n  "a": 1\n}');
        assert.strictEqual(debugFormatJson('{"a":1}', 4), '{\n    "a": 1\n}');
        assert.strictEqual(debugFormatJson('{"a":1}'), '{\n  "a": 1\n}', 'defaults to 2');
    });
});

test('highlighting classifies the same slices', async (t) => {
    await t.test('spans carry the source text verbatim', () => {
        assert.strictEqual(
            debugSyntaxHighlightJson('{"id":12345678901234567890,"n":1.10}'),
            '<span class="json-bracket">{</span>\n'
                + '  <span class="json-key">"id"</span>: <span class="json-number">12345678901234567890</span>,\n'
                + '  <span class="json-key">"n"</span>: <span class="json-number">1.10</span>\n'
                + '<span class="json-bracket">}</span>',
        );
    });

    await t.test('every JSON token type gets a class', () => {
        const html = debugSyntaxHighlightJson('{"k":["s",1,true,null,{}]}');
        for (const cls of ['json-key', 'json-string', 'json-number', 'json-boolean', 'json-null', 'json-bracket']) {
            assert.ok(html.includes(`class="${cls}"`), `expected a ${cls} span in ${html}`);
        }
    });
});

test('non-JSON bodies fall back instead of being rewritten', async (t) => {
    // What the Network tab actually receives when a body is not one JSON value.
    const truncatedAtCaptureCap = '{"items":[{"id":1,"name":"first"},{"id":2,"na';
    const ndjson = '{"event":"a"}\n{"event":"b"}\n{"event":"c"}';
    const xml = '<?xml version="1.0"?><root><a>1</a></root>';

    await t.test('formatting returns the body unchanged', () => {
        for (const body of [truncatedAtCaptureCap, ndjson, xml]) {
            assert.strictEqual(debugScanJsonSource(body, 2), null);
            assert.strictEqual(debugFormatJson(body, 2), body);
        }
    });

    await t.test('highlighting escapes it as plain text', () => {
        assert.strictEqual(debugSyntaxHighlightJson(truncatedAtCaptureCap), truncatedAtCaptureCap);
        assert.strictEqual(debugSyntaxHighlightJson(ndjson), ndjson);
        assert.strictEqual(
            debugSyntaxHighlightJson(xml),
            '&lt;?xml version="1.0"?&gt;&lt;root&gt;&lt;a&gt;1&lt;/a&gt;&lt;/root&gt;',
        );
    });

    await t.test('an already-parsed object is walked as a tree', () => {
        // The one input that is not source text, so there is nothing to preserve.
        assert.strictEqual(debugFormatJson({ a: 1 }, 2), '{\n  "a": 1\n}');
        assert.strictEqual(
            debugSyntaxHighlightJson({ a: 1 }),
            '<span class="json-bracket">{</span>\n  <span class="json-key">"a"</span>: '
                + '<span class="json-number">1</span>\n<span class="json-bracket">}</span>',
        );
    });

    await t.test('a non-string, non-JSON input does not throw', () => {
        assert.strictEqual(debugScanJsonSource(undefined, 2), null);
        assert.strictEqual(debugScanJsonSource(42, 2), null);
        assert.strictEqual(debugSyntaxHighlightJson(undefined), 'undefined');
        // JSON.stringify(undefined) is undefined, and debugFormatJson returns it
        // as-is; the caller renders an empty body rather than the text.
        assert.strictEqual(debugFormatJson(undefined, 2), undefined);
    });
});
