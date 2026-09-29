// Escape and the shared dialogs.
//
// shared.js closes an open dialog on Escape before a tab's own Escape handler
// runs, so the tab can no longer see that a dialog was open. It marks the key
// as used instead, and a tab checks defaultPrevented before it acts on Escape,
// as the Network tab does before it clears the selected request.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { loadSharedJs } = require('./harness.js');

function pressEscape(shared, openDialogs) {
    shared.document.querySelectorAll = () => openDialogs;
    const event = {
        key: 'Escape',
        defaultPrevented: false,
        preventDefault() { this.defaultPrevented = true; },
    };
    shared.documentListeners.keydown.forEach((listener) => listener(event));
    return event;
}

test('Escape closes an open dialog and marks the key as used', () => {
    const dialog = { hidden: false };

    const event = pressEscape(loadSharedJs(), [dialog]);

    assert.strictEqual(dialog.hidden, true);
    assert.strictEqual(event.defaultPrevented, true);
});

test('Escape with no dialog open is left to the tab', () => {
    const event = pressEscape(loadSharedJs(), []);

    assert.strictEqual(event.defaultPrevented, false);
});
