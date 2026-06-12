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

> **Legacy names.** The pre-redesign token names (`--bg-primary`,
> `--accent-info`, `--text-muted`, `--json-key`, …) are aliased onto the new
> tokens at the bottom of `shared.css`, so existing tab CSS keeps working and
> keeps theming. New code should use the new tokens directly.

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

The chrome's `.content` container is **full-bleed** (no padding) so split-pane
tabs can fill the viewport — tabs own their edge padding (use `22px`, see the
sample flags tab).

## 4. Components

Two component layers are available to every tab:

**`.dc-*` library (preferred for new UI).** The documented design-system
components. Semantic color is passed via the `--c` custom property where noted:

- `.dc-btn` (+ `--primary`, `--soft`, `--active`, `--danger`, `--ghost-danger`,
  `--sm`) — transparent 1px-border buttons; primary is solid accent.
- `.dc-chip` + `.dc-chip__dot` — pill filters with a colored leading dot;
  `--active` = accent-tinted.
- `.dc-seg` / `.dc-seg__item(--active)` — segmented control on a recessed track;
  the selected segment is solid accent.
- `.dc-tabs` / `.dc-tab(--active)` / `.dc-tabpanel` — connected panel tabs: the
  active tab lifts onto `--raise`, takes a top accent bar, and visually joins
  its panel.
- `.dc-field` (+ `.dc-field__prefix`, `.dc-input`), `.dc-input--block`,
  `.dc-textarea`, `.dc-label`, `.dc-check(--on/--locked)` — inputs and labels.
- `.dc-table` family (`.dc-thead`, `.dc-th(--sortable/--sorted)`, `.dc-row
  (--selected/--warn/--error)`, `.dc-cell(--num/--null/--muted)`) — data grids.
- `.dc-badge` (soft tinted fill) and `.dc-tag` (outlined), colored via
  `style="--c: var(--method-get)"`; `.dc-tag--mock` is the dashed purple MOCK.
- `.dc-menu` family — dropdowns with single-select `✓` or multi-select
  checkboxes; close via a `.dc-menu-scrim` layer.
- `.dc-modal` family, `.dc-toast`, `.dc-listitem` (+ `.dc-star`), `.dc-split` /
  `.dc-divider`, `.dc-json` (`.k/.s/.n/.b/.p` spans), `.dc-comment`,
  `.dc-mono-label`.

**Shared `.debug-*` components (legacy names, same look).** The pre-redesign
class names are restyled on the tokens, so existing tabs inherit the design
automatically: `.debug-btn(-primary/-danger/-icon)`, `.debug-table`,
`.status-pill(.success/.error/.warning/.info/.mocked)`, `.debug-search-bar`,
`.debug-filter-group .filter-chip`, `.debug-code-block` + `.debug-json`,
`.json-tree`, `.debug-modal` family, `.debug-toast` (via `debugToast()`),
`.pane`/`.pane-header`/`.pane-divider`/`.split-container`, and the disconnect
overlay. Both layers compose freely.

## 5. Theming

`shared.js` owns the theme: it cycles auto → light → dark, persists to
`localStorage['debug-theme']`, and sets `data-theme` on `<html>`. Tabs need to
do **nothing** — if every color in your CSS is a token (or a `color-mix` of
one), both themes work automatically. Semantic colors intentionally darken in
light mode for contrast; never collapse the two sets.

## 6. Tab-author rules

- Reference tokens, never hex values; pick the token by *meaning*.
- Build on `.dc-*` (or the shared `.debug-*`) components before writing custom
  CSS; when you do write custom rules, follow the type table above.
- Own your edge padding (`22px`); the chrome content area is full-bleed.
- CSP holds: external JS/CSS only, no inline `on*=` handlers (use
  `data-action` delegation), no inline `<script>`.
- Don't rely on color alone — pair semantic colors with a text label or letter.
- Hit targets ≥ 30px tall for buttons/inputs; ≥ 24px for icon affordances.

The sample tab (`sample/src/debug/assets/lustro/flags.css`) is the working
reference for a third-party tab on this system.
