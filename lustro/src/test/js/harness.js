// Loads the debug console's browser scripts into this Node process.
//
// `shared.js` is a browser script, not a module: it assigns to `window` and runs
// DOM setup at load (theme, toast container, auth bootstrap). Rather than depend
// on a DOM implementation, this stubs the few globals that setup touches and
// runs the file in a fresh `vm` context, returning the `window` it published to.
//
// Two stub choices keep loading inert: `getElementById` returns null, so the
// auth bootstrap finds no content root and never calls `fetch`, and `readyState`
// is 'loading', so DOMContentLoaded work is queued and never fired.
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ASSET_DIR = path.join(__dirname, '..', '..', 'main', 'assets', 'lustro');

function stubElement() {
    return {
        style: {},
        dataset: {},
        title: '',
        textContent: '',
        innerHTML: '',
        classList: { add() {}, remove() {}, toggle() {}, contains: () => false },
        setAttribute() {},
        appendChild() {},
        addEventListener() {},
        remove() {},
    };
}

function stubLocalStorage() {
    const store = new Map();
    return {
        getItem: (key) => (store.has(key) ? store.get(key) : null),
        setItem: (key, value) => store.set(key, String(value)),
        removeItem: (key) => store.delete(key),
    };
}

function loadSharedJs() {
    const sandbox = {
        console,
        setTimeout,
        clearTimeout,
        localStorage: stubLocalStorage(),
        navigator: { clipboard: null },
        document: {
            readyState: 'loading',
            documentElement: stubElement(),
            body: stubElement(),
            head: stubElement(),
            getElementById: () => null,
            querySelector: () => null,
            querySelectorAll: () => [],
            createElement: stubElement,
            addEventListener() {},
            getSelection: () => null,
        },
    };
    sandbox.window = sandbox;
    sandbox.globalThis = sandbox;
    sandbox.isSecureContext = false;
    sandbox.matchMedia = () => ({ matches: false, addEventListener() {}, addListener() {} });
    sandbox.addEventListener = () => {};

    vm.createContext(sandbox);
    const source = fs.readFileSync(path.join(ASSET_DIR, 'shared.js'), 'utf8');
    vm.runInContext(source, sandbox, { filename: 'shared.js' });
    return sandbox;
}

module.exports = { loadSharedJs };
