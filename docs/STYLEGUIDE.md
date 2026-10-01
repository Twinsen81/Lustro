# Styleguide — Lustro Debug Console

The console is a terminal-flavored developer tool: dark-first, one blue accent,
monospace everywhere, dense and high-contrast. Everything below ships in
[`shared.css`](../lustro/src/main/assets/lustro/shared.css), which the chrome
loads on every tab page — **built-in tabs and third-party tabs get the same
design system with zero setup**. This document is the contract for anyone
writing a tab.

## 1. Principles

- **Mono everywhere.** The console face is `--font-mono`. No webfonts are
  loaded (CSP is `'self'`-only); the stack prefers locally installed terminal
  faces (`Geist Mono`, `SF Mono`, Menlo…) and degrades to the system monospace.
- **Uppercase, spaced labels.** Section labels and controls:
  `font-size: 10.5–13px; font-weight: 600; letter-spacing: .08–.1em;
  text-transform: uppercase;`. Use **spaces, not underscores**, in UI labels
  ("SEND REQUEST", not "SEND_REQUEST"). Real identifiers (table names, header
  keys) keep their underscores.
- **One accent.** `--accent` (blue) for primary actions, active states, focus,
  links. `--ai` (purple) is a secondary accent reserved for mock/AI/paused.
  Green (`--live`) = live/success, red (`--danger`) = danger.
- **Flat by default.** Shadows appear only on floating layers (menus, modals,
  toasts). Everything else is separated by 1px borders and surface shifts.
- **Never hardcode colors.** Every surface, border, text shade, and semantic
  color (HTTP method, status class, log level, value type, category) has a
  token. If you need a soft tinted fill, use
  `color-mix(in srgb, var(--c) 15%, transparent)`.
- **Comments as helper text.** Secondary hints render like
  `// sent through the app's live OkHttp client` in `--t4`.

## 2. Tokens

Defined on `:root` (dark default) with light overrides under
`[data-theme="light"]`. Components never change between themes — only tokens do.

- **Surfaces** (darkest → raised): `--bg` → `--header`/`--panel` → `--field`
  (inputs, wells, cards) → `--raise` (active/elevated, e.g. the pane behind an
  active tab). `--tabbar` is the recessed strip behind connected tabs; `--menu`
  is the dropdown/toast surface.
- **Lines**: `--border` (primary), `--border-soft` (faint), `--grid` (table
  column dividers), `--row-border`, `--chip-border` (inputs/chips),
  `--btn-border`, `--tag-border`, `--hover-border`.
- **Text ramp**: `--t1` headings · `--t2` body/cells · `--t3` muted labels ·
  `--t4` faint meta/placeholders/comments · `--t5` disabled. Keep `--t3`/`--t4`
  for *secondary* text only, never primary content.
- **Accents**: `--accent`, `--accent-2` (lighter, for text), `--accent-hover`,
  `--accent-soft` (tinted bg), `--accent-border`; same shape for `--ai-*`,
  `--live-*`, `--danger-*`.
- **Semantic sets** (each themed for dark and light): `--method-get/post/put/
  patch/delete/head`, `--status-2xx/3xx/4xx/5xx`, `--lvl-v/d/i/w/e`,
  `--type-string/boolean/int/long/float`, `--cat-sync/ai/config/media/auth/
  other/wiretap`, `--syn-key/str/num/bool/punc` (JSON highlighting).
- **Row states**: `--row-selected`, `--row-zebra`, `--row-hover`, `--row-warn`,
  `--row-error`.
- **Radii**: `--radius-sm` 3px · `--radius` 4px (default controls) ·
  `--radius-md` 6px (menus, tab tops, segmented tracks) · `--radius-lg` 8px
  (modals) · `--radius-pill`.
- **Misc**: `--shadow-menu`, `--shadow-modal`, `--scrim`, `--scrim-strong`,
  `--search-highlight`, `--star`.

## 3. Typography & spacing

| Use | Size | Weight | Tracking |
|---|---|---|---|
| Section label (UPPER) | 10.5–11px | 600 | .1em |
| Control / button text | 11–11.5px | 500 | .04em |
| Table cell / body | 12–12.5px | 400 | normal |
| Title (e.g. NETWORK TRAFFIC) | 13px | 600 | .06em |
| Nav item | 12px | 500/600 | .1em |

Rhythm: control padding ~`8px 13px`; table cells `9px 14px`; toolbar gaps
`8–12px`; form field gaps `12–17px`; pane edge padding `22px`.

The chrome's content area (`.dc-app__content`) is **full-bleed** (no padding)
so split-pane tabs can fill the viewport. Tabs own their edge padding (use
`22px`, see the sample flags tab).

## 4. Components

Every tab gets the `.dc-*` component library, and the chrome is built from it
too. Semantic color is passed via the `--c` custom property where noted:

- `.dc-btn` (+ `--primary`, `--soft`, `--active`, `--danger`, `--ghost-danger`,
  `--sm`, `--icon`): transparent 1px-border buttons. Primary is solid accent,
  `--ghost-danger` is red at rest (e.g. Clear), `--danger` turns red on hover
  only, and `--icon` is a bare glyph button (✎ ✕). A disabled `.dc-btn` greys
  out, and a `<select class="dc-btn">` is a dropdown with a drawn ▾.
- `.dc-chip` + `.dc-chip__dot`: pill filters with a colored leading dot;
  `--active` = accent-tinted.
- `.dc-seg` / `.dc-seg__item(--active)`: segmented control on a recessed track;
  the selected segment is solid accent. `.dc-seg--sm` is the compact one, for a
  mode switch inside a panel.
- `.dc-tabs` / `.dc-tab(--active)` / `.dc-tabpanel`: connected panel tabs. The
  active tab lifts onto `--raise`, takes a top accent bar, and visually joins
  its panel.
- `.dc-toolbar`: the title row above a list or grid. Put the title in a
  `.dc-mono-label` (an `<h3>` is fine) and push the actions right with
  `margin-left: auto`.
- `.dc-field` (+ `.dc-field__prefix`, `.dc-input`), `.dc-input--block`,
  `.dc-textarea`, `.dc-label`, `.dc-check(--on/--locked)`: inputs and labels.
- `.dc-table` family (`.dc-thead`, `.dc-th(--sortable/--sorted)`, `.dc-row
  (--selected/--warn/--error)`, `.dc-cell(--num/--null/--muted)`): data grids,
  as a `<table>` or as div rows. Header cells stick to the top of the scroll
  area; rows are zebra-striped and highlight on hover.
- `.dc-badge` (soft tinted fill) and `.dc-tag` (outlined), colored via
  `style="--c: var(--method-get)"`; `.dc-tag--mock` is the dashed purple MOCK.
- `.dc-pill` (+ `--live`, `--danger`, `.dc-pill__dot`): the theme toggle (a
  `<button>`) and the LIVE / ERROR status pill in the top bar.
- `.dc-menu` family: dropdowns with single-select `✓` or multi-select
  checkboxes; close via a `.dc-menu-scrim` layer.
- `.dc-modal` family: keep the `.dc-modal-scrim` in your markup with the
  `hidden` attribute and show or hide it with `debugModal(id)`; Escape and a
  click on the scrim close it. Actions go in `.dc-modal__foot`, primary first.
- `.dc-toast`: shown by `debugToast(message, type)`, where `type` is `success`,
  `error`, `info` or `warning`. Toasts stack bottom-right and go away after 3 s.
- `.dc-split` / `.dc-pane` / `.dc-divider`: resizable panes, in the order pane,
  divider, pane. Call `debugInitResizers()` once your markup is in the page; a
  drag stops at each pane's CSS `min-width`.
- `.dc-json` (`.k/.s/.n/.b/.p` spans, as `debugSyntaxHighlightJson()` emits
  them) in a `.dc-code` well: `<pre class="dc-code dc-json">`.
- Body viewers. Each function takes a body's text, escapes all of it, and
  returns one element's HTML; `{ searchText }` marks the matches.
  `debugBodyKind(contentType, text, binary)` says which one suits a body.
  - `.dc-tree`: `debugJsonTree(text)` returns a
    `<pre class="dc-code dc-json dc-tree">`, or `null` when the text is not
    JSON. Each object or array with members is a `.dc-fold` (its first line)
    followed by a `.dc-fold__body`. `shared.js` folds a node on click
    (`.dc-fold--closed`, which shows a count of what it hides), and Alt-click
    folds or unfolds everything inside it.
  - `.dc-markup` (`.g/.t/.a/.s/.c` spans: a tag, its name, an attribute name,
    an attribute value, a comment or declaration): `debugHighlightMarkup(text,
    { html })` indents XML, or HTML with `html: true`, and adds whitespace and
    nothing else.
  - `.dc-lines` (`.dc-lines__line` rows): `debugLineNumbered(text)` numbers the
    lines with CSS counters, which are never selected or copied.
  - `.dc-kv` (`.dc-kv__key`, `.dc-kv__value`, and `.dc-kv__raw` for a part that
    is not valid percent-encoding): a key and value table.
    `debugFormTable(text)` builds one from an
    `application/x-www-form-urlencoded` body.
  - `debugHexDump(bytes)` returns text in the `hexdump -C` layout. Put it in a
    `.dc-code` well with `textContent`.
- `.dc-listitem` (+ `.dc-star`), `.dc-comment`, `.dc-mono-label`, and the chrome
  shell: `.dc-app`, `.dc-topbar`, `.dc-nav`.

## 5. Theming

`shared.js` owns the theme: it cycles auto → light → dark, persists to
`localStorage['debug-theme']`, and sets `data-theme` on `<html>`. Tabs need to
do **nothing** — if every color in your CSS is a token (or a `color-mix` of
one), both themes work automatically. Semantic colors intentionally darken in
light mode for contrast; never collapse the two sets.

## 6. Tab-author rules

- Reference tokens, never hex values; pick the token by *meaning*.
- Build on the `.dc-*` components before writing custom CSS; when you do write
  custom rules, follow the type table above.
- Own your edge padding (`22px`); the chrome content area is full-bleed.
- CSP holds: external JS/CSS only, no inline `on*=` handlers (use
  `data-action` delegation), no inline `<script>`.
- Don't rely on color alone — pair semantic colors with a text label or letter.
- Hit targets ≥ 30px tall for buttons/inputs; ≥ 24px for icon affordances.

The sample tab (`sample/src/debug/assets/lustro/flags.css`) is the working
reference for a third-party tab on this system.
