// Generates JSON documents — and near-miss mutations of them — for the scanner
// tests. The generator is seeded so a CI failure reproduces locally from the
// printed document; nothing here depends on the code under test.
//
// Documents are built as token lists rather than strings, because the tests need
// to insert whitespace at exactly the positions where JSON allows it: between
// tokens, never inside one.
'use strict';

// Atoms chosen so a JSON.parse + JSON.stringify round-trip would visibly rewrite
// them: precision past 2^53, trailing zeroes, exponents, and escapes that decode.
const ATOMS = [
    '0', '-0', '1', '1.10', '1e2', '1E+2', '1.5e-3', '0.1000',
    '12345678901234567890', '9007199254740993', '1e309',
    'true', 'false', 'null',
    '""', '"a"', '" "', '"\\u00e9"', '"\\n"', '"\\\\"', '"\\""', '"a\\/b"',
    '"\\uD83D\\uDE00"', '"\\u0000"', '"<img src=x>"',
];

const KEYS = ['"k"', '"a"', '"k"', '"z z"', '""', '"\\u00e9"'];
const WHITESPACE = ['', '', ' ', '\t', '\n', '\r', '  \n\t'];

// Characters that turn a valid document into a near-miss: JSON-adjacent syntax
// that JSON.parse rejects, plus the ones it accepts only in specific positions.
const NOISE = [
    '{', '}', '[', ']', ',', ':', '"', '\\', 'e', '+', '.', '-', '0', 'x',
    '/', "'", '\u0000', '﻿', ' ',
];

function createRandom(seed) {
    let state = seed >>> 0;
    return function random() {
        // Floating-point multiplication loses low bits and creates short cycles.
        state = (Math.imul(state, 1103515245) + 12345) & 0x7fffffff;
        return state / 0x80000000;
    };
}

function createGenerator(seed) {
    const random = createRandom(seed);
    const pick = (list) => list[Math.floor(random() * list.length)];
    const upTo = (n) => Math.floor(random() * n);

    function tokens(depth) {
        if (depth > 3 || random() < 0.45) return [pick(ATOMS)];
        const count = upTo(4);
        const isArray = random() < 0.5;
        const out = [isArray ? '[' : '{'];
        for (let n = 0; n < count; n++) {
            if (n > 0) out.push(',');
            if (!isArray) out.push(pick(KEYS), ':');
            out.push(...tokens(depth + 1));
        }
        out.push(isArray ? ']' : '}');
        return out;
    }

    // A compact valid document.
    const valid = () => tokens(0).join('');

    // The same, with insignificant whitespace between tokens — the only place
    // JSON allows it, and the only thing a pretty-printer is allowed to change.
    const validWithWhitespace = () => {
        const parts = tokens(0);
        let out = '';
        for (const token of parts) out += pick(WHITESPACE) + token;
        return out + pick(WHITESPACE);
    };

    function mutate(text) {
        const at = upTo(text.length);
        switch (upTo(6)) {
            case 0: return text.slice(0, at) + text.slice(at + 1);
            case 1: return text.slice(0, at) + pick(NOISE) + text.slice(at);
            case 2: return text.slice(0, at) + text.slice(at).toUpperCase();
            case 3: return text + pick([',', '}', ']', '1', ' x', '//c', '\u0000']);
            case 4: return pick(['', ' ', '﻿', '+', '.']) + text;
            default: return text.replace(/"/g, pick(["'", '`', '"']));
        }
    }

    // A document plus, often, whitespace and one to three mutations. The mix is
    // deliberately biased towards near-misses: those are the inputs where the
    // scanner's grammar could drift from JSON.parse's.
    function next() {
        const roll = random();
        let text = roll < 0.35 ? validWithWhitespace() : valid();
        if (roll > 0.5) {
            const rounds = 1 + upTo(3);
            for (let n = 0; n < rounds; n++) text = mutate(text);
        }
        return text;
    }

    return { next, valid, validWithWhitespace };
}

module.exports = { createGenerator };
