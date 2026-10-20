package io.github.twinsen81.lustro.logs.internal

/**
 * The HTML of the Logs tab's view. It is a static shell: `logs.js` fills the
 * rows and wires the controls, with no inline handlers, and `logs.css` styles it
 * on the console's `.dc-*` components.
 */
internal object LogsView {
    val HTML: String =
        """
        <div class="logs" id="logs-root">
            <div class="logs-bar">
                <div class="logs-bar__row">
                    <div class="dc-menu-anchor">
                        <button type="button" class="logs-level" id="logs-level" data-action="toggleLevelMenu" aria-haspopup="listbox" aria-expanded="false" aria-controls="logs-level-menu" title="The lowest level to show: Warning shows warnings, errors, and fatal lines. The list asks the app again, so it also shows the lines that a higher level left out.">
                            <span class="logs-level__value"><span class="dc-menu__dot" id="logs-level-dot"></span><span id="logs-level-label">Verbose</span></span>
                            <span class="logs-level__caret" aria-hidden="true">▾</span>
                        </button>
                        <div class="dc-menu logs-menu logs-level-menu" id="logs-level-menu" role="listbox" aria-label="Lowest level" hidden>
                            <button type="button" class="dc-menu__item" role="option" data-action="setLevel" data-level="V"><span class="dc-menu__dot" style="--c: var(--lvl-v)"></span><span class="logs-menu__label">Verbose</span><span class="dc-menu__check"></span></button>
                            <button type="button" class="dc-menu__item" role="option" data-action="setLevel" data-level="D"><span class="dc-menu__dot" style="--c: var(--lvl-d)"></span><span class="logs-menu__label">Debug</span><span class="dc-menu__check"></span></button>
                            <button type="button" class="dc-menu__item" role="option" data-action="setLevel" data-level="I"><span class="dc-menu__dot" style="--c: var(--lvl-i)"></span><span class="logs-menu__label">Info</span><span class="dc-menu__check"></span></button>
                            <button type="button" class="dc-menu__item" role="option" data-action="setLevel" data-level="W"><span class="dc-menu__dot" style="--c: var(--lvl-w)"></span><span class="logs-menu__label">Warning</span><span class="dc-menu__check"></span></button>
                            <button type="button" class="dc-menu__item" role="option" data-action="setLevel" data-level="E"><span class="dc-menu__dot" style="--c: var(--lvl-e)"></span><span class="logs-menu__label">Error</span><span class="dc-menu__check"></span></button>
                            <button type="button" class="dc-menu__item" role="option" data-action="setLevel" data-level="F"><span class="dc-menu__dot" style="--c: var(--lvl-f)"></span><span class="logs-menu__label">Fatal</span><span class="dc-menu__check"></span></button>
                        </div>
                    </div>
                    <div class="logs-tag">
                        <div class="dc-field logs-field logs-field--tag" id="logs-tag-field">
                            <label class="logs-field__label" for="logs-tag"><span class="dc-field__prefix" aria-hidden="true">/</span><input type="text" id="logs-tag" name="tag" class="dc-input" placeholder="tag regex   ^OkHttp" autocomplete="off" spellcheck="false" data-action="filterInput" aria-label="Tag regex" aria-describedby="logs-tag-tip"></label>
                            <button type="button" class="dc-btn dc-btn--icon logs-field__clear" data-action="clearField" data-target="logs-tag" hidden aria-label="Clear the tag regex" title="Clear the tag regex.">×</button>
                        </div>
                        <div class="logs-tip" id="logs-tag-tip" role="tooltip">
                            <div class="logs-tip__title">Tag regex examples</div>
                            <table class="logs-tip__table">
                                <tr><td><code>OkHttp</code></td><td>contains "OkHttp"</td></tr>
                                <tr><td><code>^OkHttp${'$'}</code></td><td>exactly "OkHttp"</td></tr>
                                <tr><td><code>^Sync</code></td><td>starts with "Sync"</td></tr>
                                <tr><td><code>(OkHttp|Retrofit)</code></td><td>either tag</td></tr>
                                <tr><td><code>(?i)sync</code></td><td>ignoring case</td></tr>
                                <tr><td><code>^(?!System).*</code></td><td>not "System…"</td></tr>
                                <tr><td><code>^OkHttp(?!Sync)</code></td><td>"OkHttp", but not "OkHttpSync"</td></tr>
                            </table>
                            <div class="logs-tip__note">// a Java regular expression, found in the tag</div>
                        </div>
                    </div>
                    <div class="dc-field logs-field logs-field--search">
                        <label class="logs-field__label" for="logs-q"><svg class="logs-field__icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4-4"/></svg><input type="text" id="logs-q" name="q" class="dc-input" placeholder="search messages…" autocomplete="off" spellcheck="false" data-action="filterInput" aria-label="Search messages" title="Show the lines whose message contains this text, ignoring case."></label>
                        <button type="button" class="dc-btn dc-btn--icon logs-field__clear" data-action="clearField" data-target="logs-q" hidden aria-label="Clear the search" title="Clear the search.">×</button>
                    </div>
                    <div class="logs-actions">
                        <button type="button" class="dc-btn" id="logs-pause" data-action="togglePause" aria-pressed="false" title="Pause: the tab drops the lines that come while it is paused. The list keeps the lines it has.">⏸ Pause</button>
                        <button type="button" class="dc-btn dc-btn--active" id="logs-compact" data-action="toggleCompact" aria-pressed="true" title="Compact: a line that repeats the line before folds into it, with a ×N count. It applies to the lines that come next.">☑ Compact</button>
                        <button type="button" class="dc-btn dc-btn--danger" data-action="clearLog" title="Clear: empty the tab's buffer in the app, for every client. The list then shows only the lines that come after.">⌫ Clear</button>
                        <button type="button" class="dc-btn" data-action="copyRows" title="Copy the lines in the list as text, with the columns that it shows.">⧉ Copy</button>
                        <div class="dc-menu-anchor">
                            <button type="button" class="dc-btn" id="logs-columns" data-action="toggleColumnsMenu" aria-haspopup="true" aria-expanded="false" aria-controls="logs-columns-menu" title="Show or hide columns. Your browser remembers the choice.">Columns ▾</button>
                            <div class="dc-menu dc-menu--right logs-menu" id="logs-columns-menu" role="menu" aria-label="Columns" hidden>
                                <button type="button" class="dc-menu__item" role="menuitemcheckbox" data-action="toggleColumn" data-column="time"><span class="dc-check"></span>Time</button>
                                <button type="button" class="dc-menu__item" role="menuitemcheckbox" data-action="toggleColumn" data-column="lvl"><span class="dc-check"></span>Level</button>
                                <button type="button" class="dc-menu__item" role="menuitemcheckbox" data-action="toggleColumn" data-column="tag"><span class="dc-check"></span>Tag</button>
                                <button type="button" class="dc-menu__item" role="menuitemcheckbox" data-action="toggleColumn" data-column="pid"><span class="dc-check"></span>PID / TID</button>
                                <button type="button" class="dc-menu__item logs-menu__locked" role="menuitemcheckbox" aria-checked="true" aria-disabled="true" title="The message is always shown."><span class="dc-check dc-check--locked">✓</span>Message</button>
                            </div>
                        </div>
                    </div>
                    <div class="dc-menu-scrim" id="logs-menu-scrim" data-action="closeMenus" hidden></div>
                </div>
                <div class="logs-bar__row">
                    <button type="button" class="dc-chip" id="logs-fatal" data-action="toggleFatal" aria-pressed="false" title="Show only fatal lines, such as a crash. Click again for the level before."><span class="dc-chip__dot" style="--c: var(--lvl-f)"></span>Fatal</button>
                    <span class="logs-paused" id="logs-paused" hidden>Stream paused</span>
                    <span class="logs-count" id="logs-count">0 lines</span>
                </div>
                <div class="logs-filter-error" id="logs-filter-error" role="alert" hidden></div>
            </div>
            <div class="logs-banner" id="logs-banner" role="status" hidden></div>
            <div class="logs-body">
                <div class="logs-scroll" id="logs-scroll">
                    <table class="dc-table logs-table" id="logs-table">
                        <colgroup>
                            <col data-col="lvl">
                            <col data-col="time">
                            <col data-col="tag">
                            <col data-col="pid">
                            <col data-col="msg">
                        </colgroup>
                        <thead class="dc-thead">
                            <tr>
                                <th class="dc-th logs-th--lvl" data-col="lvl" title="Level">Lvl<span class="logs-resize" data-col-resize="lvl" title="Drag to resize. Double-click for the default width."></span></th>
                                <th class="dc-th" data-col="time" title="The time on the device. Hover a time for its date.">Time<span class="logs-resize" data-col-resize="time" title="Drag to resize. Double-click for the default width."></span></th>
                                <th class="dc-th" data-col="tag">Tag<span class="logs-resize" data-col-resize="tag" title="Drag to resize. Double-click for the default width."></span></th>
                                <th class="dc-th" data-col="pid">PID / TID<span class="logs-resize" data-col-resize="pid" title="Drag to resize. Double-click for the default width."></span></th>
                                <th class="dc-th" data-col="msg">Message</th>
                            </tr>
                        </thead>
                        <tbody id="logs-rows"></tbody>
                    </table>
                    <div class="logs-empty" id="logs-empty">// no log lines yet</div>
                </div>
                <button type="button" class="dc-btn dc-btn--sm logs-jump" id="logs-jump" data-action="jumpToLatest" hidden title="Scroll to the newest line. The list then follows the new lines again.">▼ Latest</button>
            </div>
            <div class="logs-tools" id="logs-tools" role="toolbar" aria-label="Actions for this line" hidden>
                <button type="button" class="dc-btn dc-btn--sm" id="logs-tool-json" data-action="formatJson" title="Show the JSON in this message, indented.">{ } Format JSON</button>
                <button type="button" class="dc-btn dc-btn--sm" data-action="copyMessage" title="Copy the message.">⧉ Copy</button>
                <button type="button" class="dc-btn dc-btn--sm" data-action="filterTag" title="Show only the lines with this tag.">Filter by this tag</button>
            </div>
            <div id="logs-json-modal" class="dc-modal-scrim" hidden>
                <div class="dc-modal dc-modal--wide" role="dialog" aria-labelledby="logs-json-title">
                    <div class="dc-modal__head">
                        <span class="dc-modal__title" id="logs-json-title">Formatted JSON</span>
                        <button type="button" class="dc-modal__close" data-action="closeJson" aria-label="Close" title="Close.">×</button>
                    </div>
                    <div class="dc-modal__body">
                        <pre class="dc-code dc-json logs-json" id="logs-json-pre"></pre>
                    </div>
                    <div class="dc-modal__foot">
                        <button type="button" class="dc-btn dc-btn--primary" data-action="copyJson" title="Copy the indented JSON.">Copy</button>
                        <button type="button" class="dc-btn" data-action="closeJson" title="Close.">Close</button>
                    </div>
                </div>
            </div>
        </div>
        """.trimIndent()
}
