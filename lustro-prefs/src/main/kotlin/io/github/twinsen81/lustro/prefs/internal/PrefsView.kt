package io.github.twinsen81.lustro.prefs.internal

/**
 * The HTML of the Preferences tab's view. It is a static shell: `prefs.js` fills
 * the file tabs and the rows and wires the controls, with no inline handlers,
 * and `prefs.css` styles it on the console's `.dc-*` components.
 */
internal object PrefsView {
    val HTML: String =
        """
        <div class="prefs" id="prefs-root">
            <div class="prefs-bar">
                <button type="button" class="dc-btn dc-btn--soft" data-action="refresh" title="Read the list of files and the open file again.">↻ Refresh</button>
                <div class="prefs-auto">
                    <label class="dc-switch" title="Auto-refresh: ask the app for changes at the interval on the right. A poll gets the entries of the open file again only when the file changed. Your browser remembers the choice.">
                        <input type="checkbox" id="prefs-auto" aria-label="Auto-refresh">
                        <span class="dc-switch__track"></span>
                    </label>
                    <label class="prefs-auto__label" for="prefs-auto">Auto-refresh</label>
                </div>
                <div class="dc-field prefs-interval" title="How often auto-refresh asks the app for changes, in milliseconds: at least 1000.">
                    <input type="text" id="prefs-interval" class="dc-input" inputmode="numeric" autocomplete="off" spellcheck="false" aria-label="Auto-refresh interval in milliseconds">
                    <span class="prefs-interval__unit" aria-hidden="true">ms</span>
                </div>
            </div>
            <div class="dc-tabs prefs-files" id="prefs-files" role="tablist" aria-label="Files"></div>
            <div class="dc-tabpanel prefs-panel" id="prefs-panel">
                <div class="prefs-head">
                    <div class="prefs-head__title">
                        <h3 class="prefs-name" id="prefs-name"></h3>
                        <div class="prefs-facts" id="prefs-facts"></div>
                    </div>
                    <div class="prefs-head__actions">
                        <div class="dc-field prefs-search">
                            <label class="prefs-search__label" for="prefs-q"><svg class="prefs-search__icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4-4"/></svg><input type="text" id="prefs-q" name="q" class="dc-input" placeholder="search keys…" autocomplete="off" spellcheck="false" aria-label="Search keys" title="Show the keys that contain this text, ignoring case. With Values on, the values too."></label>
                            <button type="button" class="dc-btn dc-btn--icon prefs-search__clear" id="prefs-q-clear" data-action="clearSearch" hidden aria-label="Clear the search" title="Clear the search.">×</button>
                            <button type="button" class="dc-btn dc-btn--sm prefs-search__values" id="prefs-values" data-action="toggleValues" aria-pressed="false" title="Values: search the values too, and each string of a set. A value that the file's policy leaves out is not searched, and of a value longer than 4096 characters, the first 4096. Your browser remembers the choice.">Values</button>
                        </div>
                        <div class="prefs-head__buttons">
                            <button type="button" class="dc-btn dc-btn--primary" id="prefs-add" data-action="openAdd">+ Add key</button>
                            <button type="button" class="dc-btn dc-btn--danger" id="prefs-clear" data-action="confirmClear">Clear file</button>
                        </div>
                    </div>
                </div>
                <div class="prefs-banner" id="prefs-banner" role="alert" hidden></div>
                <div class="prefs-scroll">
                    <table class="dc-table prefs-table" id="prefs-table">
                        <colgroup>
                            <col data-col="key">
                            <col data-col="type">
                            <col data-col="value">
                            <col data-col="actions">
                        </colgroup>
                        <thead class="dc-thead">
                            <tr>
                                <th class="dc-th" data-col="key">Key<span class="prefs-resize" data-col-resize="key" title="Drag to resize. Double-click for the default width."></span></th>
                                <th class="dc-th" data-col="type">Type<span class="prefs-resize" data-col-resize="type" title="Drag to resize. Double-click for the default width."></span></th>
                                <th class="dc-th" data-col="value" title="Click a switch or a field to change the value, or a value to open it.">Value</th>
                                <th class="dc-th prefs-th--actions" data-col="actions">Actions</th>
                            </tr>
                        </thead>
                        <tbody id="prefs-rows"></tbody>
                    </table>
                    <div class="prefs-empty" id="prefs-empty" hidden></div>
                </div>
            </div>
            <div class="prefs-none" id="prefs-none" hidden>// the app has no SharedPreferences files</div>
            <div id="prefs-edit-modal" class="dc-modal-scrim" hidden>
                <div class="dc-modal dc-modal--wide" id="prefs-edit-dialog" role="dialog" aria-modal="true" aria-labelledby="prefs-edit-title">
                    <div class="dc-modal__head">
                        <span class="dc-modal__title" id="prefs-edit-title">Edit key</span>
                        <button type="button" class="dc-modal__close" data-action="closeEdit" aria-label="Close" title="Close without saving.">×</button>
                    </div>
                    <div class="dc-modal__body">
                        <div class="prefs-form__row">
                            <div class="dc-label" id="prefs-edit-key-label">Key</div>
                            <div class="prefs-form__key" id="prefs-edit-key"></div>
                        </div>
                        <div class="prefs-form__row">
                            <div class="dc-label">Type</div>
                            <div class="prefs-form__type" id="prefs-edit-type"></div>
                        </div>
                        <div class="prefs-form__row" id="prefs-edit-preview-row" hidden>
                            <div class="dc-label">Formatted preview</div>
                            <pre class="dc-code dc-json prefs-preview" id="prefs-edit-preview"></pre>
                        </div>
                        <div class="prefs-form__row">
                            <div class="dc-label" id="prefs-edit-value-label">Value</div>
                            <div class="prefs-form__value" id="prefs-edit-value"></div>
                            <div class="dc-comment prefs-form__hint" id="prefs-edit-hint" hidden></div>
                        </div>
                        <div class="prefs-note" id="prefs-edit-note" role="status" hidden></div>
                        <div class="prefs-error" id="prefs-edit-error" role="alert" hidden></div>
                    </div>
                    <div class="dc-modal__foot">
                        <button type="button" class="dc-btn dc-btn--primary" id="prefs-edit-save" data-action="saveEdit">Save</button>
                        <button type="button" class="dc-btn dc-btn--ghost-danger" id="prefs-edit-delete" data-action="deleteFromEdit">Delete</button>
                        <button type="button" class="dc-btn" data-action="closeEdit" title="Close without saving.">Cancel</button>
                    </div>
                </div>
            </div>
            <div id="prefs-confirm-modal" class="dc-modal-scrim" hidden>
                <div class="dc-modal" role="alertdialog" aria-modal="true" aria-labelledby="prefs-confirm-title" aria-describedby="prefs-confirm-message">
                    <div class="dc-modal__head">
                        <span class="dc-modal__title" id="prefs-confirm-title">Delete key</span>
                        <button type="button" class="dc-modal__close" data-action="cancelConfirm" aria-label="Close" title="Close. Nothing changes.">×</button>
                    </div>
                    <div class="dc-modal__body">
                        <p class="prefs-confirm__message" id="prefs-confirm-message"></p>
                    </div>
                    <div class="dc-modal__foot">
                        <button type="button" class="dc-btn dc-btn--ghost-danger" id="prefs-confirm-ok" data-action="confirmOk">Delete</button>
                        <button type="button" class="dc-btn" data-action="cancelConfirm" title="Close. Nothing changes.">Cancel</button>
                    </div>
                </div>
            </div>
        </div>
        """.trimIndent()
}
