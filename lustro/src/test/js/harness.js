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

const REPO_ROOT = path.join(__dirname, '..', '..', '..', '..');

// The console assets of a module: lustro, or a plugin such as lustro-logs.
function assetPath(module, name) {
    return path.join(REPO_ROOT, module, 'src', 'main', 'assets', 'lustro', name);
}

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
    const documentListeners = {};
    const sandbox = {
        console,
        setTimeout,
        clearTimeout,
        URL,
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
            addEventListener(type, listener) {
                (documentListeners[type] = documentListeners[type] || []).push(listener);
            },
            getSelection: () => null,
        },
    };
    // The listeners shared.js puts on document, by event type, so a test can fire them.
    sandbox.documentListeners = documentListeners;
    sandbox.window = sandbox;
    sandbox.globalThis = sandbox;
    sandbox.isSecureContext = false;
    sandbox.matchMedia = () => ({ matches: false, addEventListener() {}, addListener() {} });
    sandbox.addEventListener = () => {};

    vm.createContext(sandbox);
    const source = fs.readFileSync(assetPath('lustro', 'shared.js'), 'utf8');
    vm.runInContext(source, sandbox, { filename: 'shared.js' });
    return sandbox;
}

// shared.js, then a tab's <id>.js from a module's assets in the same context,
// as the console loads them. The tab registers its DOM setup with
// lustroOnContentReady, which never fires here, so only its top-level functions
// run. A plugin's tests, in <plugin>/src/test/js, load its script with this.
function loadTabJs(module, id) {
    const sandbox = loadSharedJs();
    const source = fs.readFileSync(assetPath(module, id + '.js'), 'utf8');
    vm.runInContext(source, sandbox, { filename: id + '.js' });
    return sandbox;
}

function loadNetworkJs() {
    return loadTabJs('lustro', 'network');
}

// The golden fixtures of the wire protocol, which pin what the routes return.
function golden(name) {
    return JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'wire-protocol', 'v1', 'golden', name), 'utf8'));
}

module.exports = { loadSharedJs, loadTabJs, loadNetworkJs, golden };
