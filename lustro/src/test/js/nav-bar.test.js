// The tabs of the top bar scroll inside it in a narrow window: the active tab is
// scrolled into view when the page opens, clear of the 24 px fade at an edge with
// tabs past it, and a vertical mouse wheel scrolls them.
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { loadSharedJs } = require('./harness.js');

function rect(left, right) {
    return { left, right };
}

// A .dc-nav from barLeft to barRight, scrolled to scrollLeft, with an active tab
// at activeLeft..activeRight in the window (null for a page without one).
function navBar({ barLeft = 0, barRight = 300, scrollLeft = 0, active, scrollWidth = 900, clientWidth = 300 }) {
    const listeners = {};
    const classes = new Set();
    const nav = {
        classes,
        classList: {
            toggle: (name, on) => (on ? classes.add(name) : classes.delete(name)),
        },
        // A browser keeps scrollLeft between 0 and scrollWidth - clientWidth.
        get scrollLeft() {
            return scrollLeft;
        },
        set scrollLeft(value) {
            scrollLeft = Math.max(0, Math.min(value, this.scrollWidth - this.clientWidth));
        },
        scrollWidth,
        clientWidth,
        getBoundingClientRect: () => rect(barLeft, barRight),
        querySelector: (selector) => {
            assert.strictEqual(selector, '.dc-nav__item--active');
            return active ? { getBoundingClientRect: () => rect(active[0], active[1]) } : null;
        },
        addEventListener(type, listener, options) {
            listeners[type] = { listener, options };
        },
    };
    return { nav, listeners };
}

// Runs the DOMContentLoaded work of the bar only: the auth bootstrap that also
// waits for it needs a location, which these tests do not stub.
function fireWireNav(shared) {
    const wireNav = shared.documentListeners.DOMContentLoaded.find((listener) => listener.name === 'wireNav');
    assert.ok(wireNav, 'shared.js wires the bar when the page has loaded');
    wireNav();
}

// shared.js with the bar in the page, after DOMContentLoaded.
function openPage(bar) {
    const shared = loadSharedJs();
    shared.document.querySelector = (selector) => (selector === '.dc-nav' ? bar.nav : null);
    fireWireNav(shared);
    return shared;
}

function wheel(nav, { deltaX = 0, deltaY = 0, deltaMode = 0, ctrlKey = false }) {
    const event = { currentTarget: nav, deltaX, deltaY, deltaMode, ctrlKey, prevented: false };
    event.preventDefault = () => { event.prevented = true; };
    return event;
}

test('an active tab past the right edge of the bar is scrolled into view, clear of the fade', () => {
    const bar = navBar({ active: [700, 820] });
    openPage(bar);
    assert.strictEqual(bar.nav.scrollLeft, 544);
});

test('an active tab past the left edge of the bar is scrolled into view, clear of the fade', () => {
    const bar = navBar({ barLeft: 22, barRight: 322, scrollLeft: 400, active: [-80, 40] });
    openPage(bar);
    assert.strictEqual(bar.nav.scrollLeft, 274);
});

test('an active tab in the fade at an edge is scrolled clear of it', () => {
    const bar = navBar({ active: [250, 290] });
    openPage(bar);
    assert.strictEqual(bar.nav.scrollLeft, 14);
});

test('an active tab wider than the space between the fades shows the start of its title', () => {
    const bar = navBar({ barLeft: 22, barRight: 175, active: [600, 800] });
    openPage(bar);
    assert.strictEqual(bar.nav.scrollLeft, 554);
});

test('an active tab that is already in view does not scroll the bar', () => {
    const bar = navBar({ scrollLeft: 10, active: [100, 200] });
    openPage(bar);
    assert.strictEqual(bar.nav.scrollLeft, 10);
});

test('a page without an active tab, or without a bar, does not fail', () => {
    const bar = navBar({ active: null });
    openPage(bar);
    assert.strictEqual(bar.nav.scrollLeft, 0);

    fireWireNav(loadSharedJs());
});

test('a vertical wheel scrolls the tabs sideways and does not scroll the page', () => {
    const bar = navBar({ active: [0, 100] });
    openPage(bar);
    const { listener, options } = bar.listeners.wheel;
    assert.strictEqual(options.passive, false, 'the listener can call preventDefault');

    const down = wheel(bar.nav, { deltaY: 120 });
    listener(down);
    assert.strictEqual(bar.nav.scrollLeft, 120);
    assert.strictEqual(down.prevented, true);

    listener(wheel(bar.nav, { deltaY: 3, deltaMode: 1 }));
    assert.strictEqual(bar.nav.scrollLeft, 168, 'a wheel in lines scrolls 16 px a line');
});

test('a pinch or a Ctrl-wheel over the tabs is left to the browser, which zooms', () => {
    const bar = navBar({ active: [0, 100] });
    openPage(bar);
    const zoom = wheel(bar.nav, { deltaY: 120, ctrlKey: true });
    bar.listeners.wheel.listener(zoom);
    assert.strictEqual(bar.nav.scrollLeft, 0);
    assert.strictEqual(zoom.prevented, false);
});

test('a sideways wheel, or a wheel on tabs that fit, is left to the browser', () => {
    const bar = navBar({ active: [0, 100] });
    openPage(bar);
    const { listener } = bar.listeners.wheel;

    const sideways = wheel(bar.nav, { deltaX: 50, deltaY: 10 });
    listener(sideways);
    assert.strictEqual(bar.nav.scrollLeft, 0);
    assert.strictEqual(sideways.prevented, false);

    bar.nav.scrollWidth = 300;
    const fits = wheel(bar.nav, { deltaY: 120 });
    listener(fits);
    assert.strictEqual(bar.nav.scrollLeft, 0);
    assert.strictEqual(fits.prevented, false);
});

test('an edge fades while there are tabs past it, and follows the scroll', () => {
    const bar = navBar({ active: [0, 100] });
    openPage(bar);
    const classes = () => [...bar.nav.classes].sort();
    assert.deepStrictEqual(classes(), ['dc-nav--more-end']);

    const scrolled = (left) => {
        bar.nav.scrollLeft = left;
        bar.listeners.scroll.listener();
        return classes();
    };
    assert.deepStrictEqual(scrolled(300), ['dc-nav--more-end', 'dc-nav--more-start']);
    assert.deepStrictEqual(scrolled(600), ['dc-nav--more-start']);
    assert.deepStrictEqual(scrolled(599.5), ['dc-nav--more-start'], 'a fractional scrollLeft at the end');
    assert.deepStrictEqual(scrolled(0.5), ['dc-nav--more-end'], 'a fractional scrollLeft at the start');
    assert.strictEqual(bar.listeners.scroll.options.passive, true);
});

test('tabs that fit have no fade, and a resize updates the fades', () => {
    const bar = navBar({ active: [0, 100], scrollWidth: 300 });
    const shared = openPage(bar);
    assert.deepStrictEqual([...bar.nav.classes], []);

    bar.nav.clientWidth = 200;
    shared.windowListeners.resize.forEach((listener) => listener());
    assert.deepStrictEqual([...bar.nav.classes], ['dc-nav--more-end']);
});
