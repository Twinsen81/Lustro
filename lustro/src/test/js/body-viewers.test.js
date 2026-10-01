// The body viewers: which one a body gets, and the HTML each one builds.
//
// Every viewer turns a captured body, which is untrusted, into innerHTML. Two
// properties are pinned for each one. Only the viewer's own tags come out,
// whatever the body and the search query hold. And the text between those tags
// is the body's: decoded for a form, and otherwise with whitespace added and
// nothing else.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { loadSharedJs } = require('./harness.js');
const { createGenerator } = require('./json-corpus.js');
const { PAYLOADS } = require('./payloads.js');

const shared = loadSharedJs();
const {
    debugBodyKind, debugJsonTree, debugHighlightMarkup, debugScanMarkup,
    debugLineNumbered, debugFormTable, debugHexDump, debugFormatJson,
} = shared;

// Every tag the viewers emit. Anything else in the output came from the body or
// the query, which would mean escaping failed. A '<' from the body that is not
// escaped joins up with the next '>', so it shows up here too.
const VIEWER_TAGS = [
    /^<\/(span|mark|pre|table|tr|th|td)>$/,
    /^<mark>$/,
    /^<span class="[ksnbp]">$/,
    /^<span class="[gtasc]">$/,
    /^<span class="dc-fold" data-count="\d+ (key|item)s?">$/,
    /^<span class="dc-fold__body">$/,
    /^<span class="dc-lines__line">$/,
    /^<span class="dc-kv__raw" title="Not valid percent-encoding, shown as sent">$/,
    /^<pre class="dc-code dc-json dc-tree">$/,
    /^<pre class="dc-code dc-markup">$/,
    /^<pre class="dc-code dc-lines" style="--dc-lines-digits: \d+">$/,
    /^<table class="dc-kv">$/,
    /^<tr>$/,
    /^<th class="dc-kv__key">$/,
    /^<td class="dc-kv__value">$/,
];

function unexpectedTags(html) {
    return (html.match(/<[^>]*>/g) || []).filter((tag) => !VIEWER_TAGS.some((re) => re.test(tag)));
}

// The text a browser shows for the HTML, as the viewers only escape &, < and >.
function textOf(html) {
    return html.replace(/<[^>]*>/g, '').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
}

// What a printer may not change: every character but XML's whitespace.
const ink = (text) => text.replace(/[ \t\n\r\f]/g, '');

// The queries most likely to land inside a viewer's own markup if matching ran
// over it instead of over each piece of text.
const MARKUP_QUERIES = ['span', 'class', 'mark', 'dc-fold', 'data-count', 'keys', 'items', 'dc-lines', 'style',
    'dc-kv', 'title', 'pre', 'table', 'g', 't', 'a', 's', 'c', '"', '<', '&lt;', '&amp;'];

function assertSafe(html, what) {
    assert.deepStrictEqual(unexpectedTags(html), [], `${what} produced markup`);
}

test('a body gets the viewer for its media type', async (t) => {
    await t.test('by type, with parameters and case ignored', () => {
        const cases = [
            ['application/json', 'json'],
            ['application/json; charset=utf-8', 'json'],
            ['Application/JSON; charset=UTF-8', 'json'],
            ['application/problem+json', 'json'],
            ['application/vnd.api+json', 'json'],
            ['application/x-www-form-urlencoded', 'form'],
            ['application/x-www-form-urlencoded; charset=utf-8', 'form'],
            ['text/html; charset=utf-8', 'html'],
            ['application/xml', 'xml'],
            ['text/xml', 'xml'],
            ['application/atom+xml', 'xml'],
            ['image/svg+xml', 'xml'],
            ['text/plain', 'text'],
            ['text/event-stream', 'text'],
            ['text/css', 'text'],
        ];
        for (const [type, kind] of cases) {
            assert.strictEqual(debugBodyKind(type, 'x', false), kind, type);
        }
    });

    await t.test('only the raster types the body route serves inline are previewed', () => {
        for (const type of ['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'IMAGE/PNG; q=1']) {
            assert.strictEqual(debugBodyKind(type, null, true), 'image', type);
        }
        // SVG and HTML are attachments, so they can't run script under the
        // console's origin; an <img> must never point at them either.
        for (const type of ['image/svg+xml', 'image/bmp', 'image/avif', 'text/html', 'application/octet-stream', null, '']) {
            assert.strictEqual(debugBodyKind(type, null, true), 'binary', String(type));
        }
    });

    await t.test('a body of no known type that starts like JSON gets the JSON viewer', () => {
        assert.strictEqual(debugBodyKind('text/plain', '{"a":1}', false), 'json');
        assert.strictEqual(debugBodyKind(null, ' \n[1,2]', false), 'json');
        assert.strictEqual(debugBodyKind(undefined, '{', false), 'json', 'the caller falls back when it does not scan');
        assert.strictEqual(debugBodyKind(null, 'plain', false), 'text');
        assert.strictEqual(debugBodyKind(null, null, false), 'text');
    });

    await t.test('a stated type wins over the look of the body', () => {
        assert.strictEqual(debugBodyKind('text/html', '{"a":1}', false), 'html');
        assert.strictEqual(debugBodyKind('application/x-www-form-urlencoded', '[1]', false), 'form');
        assert.strictEqual(debugBodyKind('application/xml', '{"a":1}', false), 'xml');
    });
});

test('the JSON tree', async (t) => {
    await t.test('is not built for text that is not JSON', () => {
        for (const body of ['', 'plain', '{"a":1', '{"a":1}\n{"b":2}', '<a/>', null, undefined]) {
            assert.strictEqual(debugJsonTree(body), null, JSON.stringify(body));
        }
    });

    await t.test('expanded, shows the text the formatter prints', () => {
        const generate = createGenerator(0x27d4eb2d);
        for (let n = 0; n < 3000; n++) {
            const source = generate.validWithWhitespace();
            const html = debugJsonTree(source);
            assert.notStrictEqual(html, null, `rejected valid ${JSON.stringify(source)}`);
            assert.strictEqual(textOf(html), debugFormatJson(source, 2), `tree of ${JSON.stringify(source)}`);
            assertSafe(html, JSON.stringify(source));
        }
    });

    await t.test('folds each object and array that has members, from its key', () => {
        const html = debugJsonTree('{"a":1,"b":[1,{"c":null},[]],"d":{},"e":[true]}');
        assert.deepStrictEqual(
            (html.match(/data-count="[^"]*"/g) || []),
            ['data-count="1 key"', 'data-count="3 items"', 'data-count="1 item"', 'data-count="4 keys"']
                .sort((a, b) => html.indexOf(a) - html.indexOf(b)),
        );
        assert.ok(
            html.includes('<span class="dc-fold" data-count="3 items"><span class="k">"b"</span>: <span class="p">[</span></span>'),
            'a member\'s fold starts at its key and ends at the bracket',
        );
        assert.ok(html.includes('<span class="p">{}</span>'), 'an empty object does not fold');
        assert.ok(html.includes('<span class="p">[]</span>'), 'an empty array does not fold');
        // The body runs from after the opening bracket to before the closing one.
        assert.ok(html.endsWith('</span><span class="p">}</span></pre>'));
    });

    await t.test('counts members of the folded node only', () => {
        const html = debugJsonTree('[[1,2,3],{"x":[4,5]}]');
        assert.ok(html.includes('data-count="2 items"'), 'the outer array');
        assert.ok(html.includes('data-count="3 items"'), 'the first inner array');
        assert.ok(html.includes('data-count="1 key"'), 'the object');
    });

    await t.test('a top-level value with nothing to fold is still a tree', () => {
        for (const body of ['42', '"text"', 'null', '{}', '[]']) {
            const html = debugJsonTree(body);
            assert.strictEqual(textOf(html), body);
            assert.ok(!html.includes('dc-fold'), body);
        }
    });

    await t.test('escapes payloads in keys and values', () => {
        for (const payload of PAYLOADS) {
            const body = JSON.stringify({ [payload]: [payload, { nested: payload }] });
            const html = debugJsonTree(body);
            assertSafe(html, `body with ${payload}`);
            assertSafe(debugJsonTree(body, { searchText: payload }), `query ${payload}`);
            assert.strictEqual(textOf(html), debugFormatJson(body, 2));
        }
    });

    await t.test('search marks land in the text, never in the viewer\'s markup', () => {
        const body = '{"items":[{"name":"keys and items"}],"span":"class"}';
        for (const query of MARKUP_QUERIES) {
            const html = debugJsonTree(body, { searchText: query });
            assertSafe(html, `query ${query}`);
            assert.strictEqual(textOf(html), debugFormatJson(body, 2), `query ${query} changed the text`);
        }
        assert.ok(debugJsonTree('{"a":"hello"}', { searchText: 'ell' }).includes('<span class="s">"h<mark>ell</mark>o"</span>'));
    });

    await t.test('the count of a folded node is not searched', () => {
        // It is an attribute shown by CSS, not text of the body.
        assert.ok(!debugJsonTree('{"a":{"b":1}}', { searchText: 'key' }).includes('<mark>'));
    });
});

test('the markup printer', async (t) => {
    const XML = [
        "<?xml version='1.0' encoding='us-ascii'?>",
        '<!--  A SAMPLE set of slides  -->',
        '<slideshow',
        '    title="Sample Slide Show"',
        '    >',
        '    <slide type="all">',
        '      <title>Wake up to WonderWidgets!</title>',
        '    </slide>',
        '    <slide type="all">',
        '        <item>Why <em>WonderWidgets</em> are great</item>',
        '        <item/>',
        '        <data><![CDATA[a < b && c > d]]></data>',
        '    </slide>',
        '</slideshow>',
    ].join('\n');

    const HTML = [
        '<!DOCTYPE html>',
        '<html><head><meta charset="utf-8"><title>a < b</title>',
        '<script>if (a<b && c>d) { document.write("</div>"); }</script>',
        '<style>p > a { color: red }</style></head>',
        '<body><ul><li>one<li>two</ul>',
        '<pre>  keep\n    this   layout </pre>',
        '<p>first<p>second<br><img src="x.png" alt="a > b"></p>',
        '<table><tr><td>1<td>2<tr><td>3</table>',
        '</body></html>',
    ].join('');

    await t.test('adds whitespace and nothing else', () => {
        for (const [source, html] of [[XML, false], [HTML, true], [XML, true], [HTML, false]]) {
            const out = debugHighlightMarkup(source, { html });
            assert.strictEqual(ink(textOf(out)), ink(source), `${html ? 'HTML' : 'XML'} rules`);
            assertSafe(out, 'sample');
        }
    });

    await t.test('indents elements and keeps an element holding only text on one line', () => {
        assert.strictEqual(
            textOf(debugHighlightMarkup('<a><b>text</b><c/><d><e>1</e></d><f></f></a>')),
            '<a>\n  <b>text</b>\n  <c/>\n  <d>\n    <e>1</e>\n  </d>\n  <f></f>\n</a>',
        );
    });

    await t.test('the input\'s own indentation gives way to the printer\'s', () => {
        assert.strictEqual(
            textOf(debugHighlightMarkup('<a>\n\t\t<b>x</b>\n      <c>  y  </c>\n</a>\n')),
            '<a>\n  <b>x</b>\n  <c>y</c>\n</a>',
        );
    });

    await t.test('a tag, comment, CDATA section, or declaration is kept as it was sent', () => {
        const out = textOf(debugHighlightMarkup(XML));
        for (const piece of [
            "<?xml version='1.0' encoding='us-ascii'?>",
            '<!--  A SAMPLE set of slides  -->',
            '<slideshow\n    title="Sample Slide Show"\n    >',
            '<![CDATA[a < b && c > d]]>',
            '<slide type="all">',
        ]) {
            assert.ok(out.includes(piece), piece);
        }
    });

    await t.test('HTML: void elements, implied end tags, and raw text', () => {
        const out = textOf(debugHighlightMarkup(HTML, { html: true }));
        assert.ok(out.includes('\n    <meta charset="utf-8">\n    <title>a < b</title>\n'), 'meta has no end tag');
        assert.ok(out.includes('<script>if (a<b && c>d) { document.write("</div>"); }</script>'), 'script text is not markup');
        assert.ok(out.includes('<style>p > a { color: red }</style>'), 'style text is not markup');
        assert.ok(out.includes('<pre>  keep\n    this   layout </pre>'), 'pre keeps its whitespace');
        assert.ok(out.includes('\n      <li>\n        one\n      <li>\n        two\n    </ul>'), 'an li ends the open li');
        assert.ok(out.includes('\n    <p>\n      first\n    <p>\n      second\n      <br>\n      <img'), 'a p ends the open p');
        assert.ok(out.endsWith('\n  </body>\n</html>'));
    });

    await t.test('HTML end tags match in any case, and XML ones only in the same case', () => {
        assert.strictEqual(
            textOf(debugHighlightMarkup('<DIV><SCRIPT>a</b></Script></div>', { html: true })),
            '<DIV>\n  <SCRIPT>a</b></Script>\n</div>',
        );
        assert.strictEqual(textOf(debugHighlightMarkup('<A><b/></a>')), '<A>\n  <b/>\n  </a>');
    });

    await t.test('tag names from the body are not looked up on Object.prototype', () => {
        for (const name of ['constructor', 'toString', 'hasOwnProperty', 'valueOf', '__proto__']) {
            const source = `<${name}><b>x</b></${name}>`;
            for (const html of [true, false]) {
                // An HTML tag name starts with a letter, so there <__proto__> is text.
                const expected = html && name === '__proto__'
                    ? `<${name}>\n<b>x</b>\n</${name}>`
                    : `<${name}>\n  <b>x</b>\n</${name}>`;
                assert.strictEqual(
                    textOf(debugHighlightMarkup(source, { html })),
                    expected,
                    `${name}, ${html ? 'HTML' : 'XML'} rules`,
                );
            }
        }
    });

    await t.test('a body cut inside a tag ends as text', () => {
        for (const cut of ['<a><b>x</b><c attr="unterminated', '<a><b', '<a></', '<a><!-- open comment', '<a><![CDATA[x']) {
            const out = debugHighlightMarkup(cut);
            assert.strictEqual(ink(textOf(out)), ink(cut), cut);
            assertSafe(out, cut);
        }
        assert.ok(textOf(debugHighlightMarkup('<a><b>x</b><c attr="cut')).endsWith('\n  <c attr="cut'));
    });

    await t.test('a < that starts no tag is text', () => {
        assert.strictEqual(textOf(debugHighlightMarkup('<a>1 < 2 and <3 and a<-b</a>')), '<a>1 < 2 and <3 and a<-b</a>');
    });

    await t.test('escapes payloads in text, attribute values, comments, and raw text', () => {
        for (const payload of PAYLOADS) {
            const quoted = payload.replace(/"/g, '&quot;');
            const sources = [
                `<a title="${quoted}">${payload}</a>`,
                `<a title='${payload.replace(/'/g, '')}'>x</a>`,
                `<!-- ${payload} -->`,
                `<x><![CDATA[${payload}]]></x>`,
                `<script>${payload}</script>`,
                `<pre>${payload}</pre>`,
                payload,
            ];
            for (const source of sources) {
                for (const html of [true, false]) {
                    assertSafe(debugHighlightMarkup(source, { html }), source);
                    assertSafe(debugHighlightMarkup(source, { html, searchText: payload }), `query ${payload}`);
                }
            }
        }
    });

    await t.test('search marks land in the text, never in the viewer\'s markup', () => {
        for (const query of MARKUP_QUERIES) {
            for (const html of [true, false]) {
                const out = debugHighlightMarkup(XML, { html, searchText: query });
                assertSafe(out, `query ${query}`);
                assert.strictEqual(ink(textOf(out)), ink(XML), `query ${query} changed the text`);
            }
        }
    });

    await t.test('the scan partitions the input into exact slices', () => {
        const random = seeded(0x165667b1);
        const words = ['<', '>', '/', '!', '-', '?', '[', ']', '"', "'", '=', ' ', '\n', 'a', 'b', 'li', 'p',
            'script', 'pre', '<!--', '-->', '<![CDATA[', ']]>', '<?', '?>', '<a ', '</a>', '<br>', 'x="1"', '&'];
        for (let n = 0; n < 3000; n++) {
            let source = '';
            const length = Math.floor(random() * 40);
            for (let i = 0; i < length; i++) source += words[Math.floor(random() * words.length)];
            for (const html of [true, false]) {
                const tokens = debugScanMarkup(source, html);
                const joined = tokens.map((tok) => (tok.pieces ? tok.pieces.map((p) => p.text).join('') : tok.text)).join('');
                assert.strictEqual(joined, source, `scan of ${JSON.stringify(source)}`);
                const out = debugHighlightMarkup(source, { html, searchText: words[n % words.length] });
                assert.strictEqual(ink(textOf(out)), ink(source), `print of ${JSON.stringify(source)}`);
                assertSafe(out, JSON.stringify(source));
            }
        }
    });

    await t.test('stays linear on input built to make it quadratic', () => {
        const hostile = [
            '<a b="'.repeat(20000),
            '<'.repeat(100000),
            '<a'.repeat(50000),
            '<a>'.repeat(30000),
            '<!--'.repeat(25000),
            '<a '.repeat(30000) + '>',
        ];
        for (const source of hostile) {
            const started = Date.now();
            const out = debugHighlightMarkup(source, { html: false });
            // Deliberately generous; quadratic behavior takes minutes here.
            assert.ok(Date.now() - started < 5000, `took ${Date.now() - started} ms on ${source.slice(0, 12)}…`);
            assert.ok(out.length < source.length * 50, `output of ${out.length} characters for ${source.length}`);
        }
    });
});

test('line-numbered text', async (t) => {
    const lineTexts = (html) => (html.match(/<span class="dc-lines__line">[\s\S]*?<\/span>/g) || [])
        .map((line) => textOf(line));

    await t.test('one row per line, whatever ends the lines', () => {
        assert.deepStrictEqual(lineTexts(debugLineNumbered('a\nb\r\nc\rd')), ['a', 'b', 'c', 'd']);
        assert.deepStrictEqual(lineTexts(debugLineNumbered('a\n\nb')), ['a', '', 'b'], 'an empty line keeps its row');
        assert.deepStrictEqual(lineTexts(debugLineNumbered('a\n')), ['a'], 'a final line break ends the last line');
        assert.deepStrictEqual(lineTexts(debugLineNumbered('a\n\n')), ['a', '']);
        assert.deepStrictEqual(lineTexts(debugLineNumbered('')), ['']);
        assert.deepStrictEqual(lineTexts(debugLineNumbered(null)), ['']);
    });

    await t.test('the gutter fits the last line number', () => {
        assert.ok(debugLineNumbered('x').includes('--dc-lines-digits: 1'));
        assert.ok(debugLineNumbered('x\n'.repeat(9)).includes('--dc-lines-digits: 1'));
        assert.ok(debugLineNumbered('x\n'.repeat(10)).includes('--dc-lines-digits: 2'));
        assert.ok(debugLineNumbered('x\n'.repeat(12345)).includes('--dc-lines-digits: 5'));
    });

    await t.test('escapes payloads and queries', () => {
        for (const payload of PAYLOADS) {
            const body = `first\n${payload}\nlast`;
            assertSafe(debugLineNumbered(body), payload);
            assertSafe(debugLineNumbered(body, { searchText: payload }), `query ${payload}`);
            assert.deepStrictEqual(lineTexts(debugLineNumbered(body)), ['first', payload, 'last']);
        }
        for (const query of MARKUP_QUERIES) {
            const html = debugLineNumbered('a span with class\nand style', { searchText: query });
            assertSafe(html, `query ${query}`);
            assert.deepStrictEqual(lineTexts(html), ['a span with class', 'and style'], `query ${query}`);
        }
    });
});

test('the form table', async (t) => {
    const rows = (html) => (html.match(/<tr>[\s\S]*?<\/tr>/g) || []).map((row) => {
        const cells = row.match(/<t[hd][^>]*>[\s\S]*?<\/t[hd]>/g);
        return cells.map((cell) => textOf(cell));
    });

    await t.test('decodes names and values, in the order they were sent', () => {
        assert.deepStrictEqual(
            rows(debugFormTable('name=Ada+Lovelace&email=ada%40example.com&note=caf%C3%A9%20%26%20cr%C3%A8me&tag=a&tag=b')),
            [['name', 'Ada Lovelace'], ['email', 'ada@example.com'], ['note', 'café & crème'], ['tag', 'a'], ['tag', 'b']],
        );
    });

    await t.test('reads the first = as the separator, and skips empty parts', () => {
        assert.deepStrictEqual(
            rows(debugFormTable('a=b=c&&flag&empty=&=nameless&')),
            [['a', 'b=c'], ['flag', ''], ['empty', ''], ['', 'nameless']],
        );
        assert.deepStrictEqual(rows(debugFormTable('')), []);
    });

    await t.test('shows the redaction placeholder decoded', () => {
        assert.deepStrictEqual(rows(debugFormTable('password=%5BREDACTED%5D')), [['password', '[REDACTED]']]);
    });

    await t.test('shows a part that is not valid percent-encoding as sent', () => {
        const html = debugFormTable('ok=1&bad=%E0%A4%A&worse%zz=2');
        assert.deepStrictEqual(rows(html), [['ok', '1'], ['bad', '%E0%A4%A'], ['worse%zz', '2']]);
        assert.strictEqual((html.match(/dc-kv__raw/g) || []).length, 2);
    });

    await t.test('escapes payloads, including the ones decoding produces', () => {
        for (const payload of PAYLOADS) {
            for (const body of [`${payload}=${payload}`, `${encodeURIComponent(payload)}=${encodeURIComponent(payload)}`,
                `x=${encodeURIComponent(payload)}%zz`]) {
                assertSafe(debugFormTable(body), body);
                assertSafe(debugFormTable(body, { searchText: payload }), `query ${payload}`);
            }
            assert.deepStrictEqual(rows(debugFormTable(`k=${encodeURIComponent(payload)}`)), [['k', payload]]);
        }
        for (const query of MARKUP_QUERIES) {
            assertSafe(debugFormTable('span=class&title=dc-kv', { searchText: query }), `query ${query}`);
        }
    });
});

test('the hex dump', async (t) => {
    await t.test('prints as hexdump -C does', () => {
        const bytes = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 0x49, 0x48, 0x44, 0x52, 0x3c, 0x26, 0x7f]);
        assert.strictEqual(
            debugHexDump(bytes),
            '00000000  89 50 4e 47 0d 0a 1a 0a  00 00 00 0d 49 48 44 52  |.PNG........IHDR|\n'
                + '00000010  3c 26 7f                                          |<&.|',
        );
    });

    await t.test('keeps its columns on a short last line', () => {
        for (let length = 1; length <= 33; length++) {
            const lines = debugHexDump(new Uint8Array(length)).split('\n');
            assert.strictEqual(lines.length, Math.ceil(length / 16));
            for (const line of lines) assert.strictEqual(line.indexOf('|'), 60, `length ${length}: ${line}`);
        }
    });

    await t.test('shows only printable ASCII as itself', () => {
        const every = Uint8Array.from({ length: 256 }, (_, i) => i);
        const ascii = debugHexDump(every).split('\n').map((line) => line.slice(61, -1)).join('');
        for (let b = 0; b < 256; b++) {
            const printable = b >= 0x20 && b < 0x7f;
            assert.strictEqual(ascii[b], printable ? String.fromCharCode(b) : '.', `byte ${b}`);
        }
    });

    await t.test('an empty body prints nothing', () => {
        assert.strictEqual(debugHexDump(new Uint8Array(0)), '');
    });
});

function seeded(seed) {
    let state = seed >>> 0;
    return () => {
        state = (Math.imul(state, 1103515245) + 12345) & 0x7fffffff;
        return state / 0x80000000;
    };
}
