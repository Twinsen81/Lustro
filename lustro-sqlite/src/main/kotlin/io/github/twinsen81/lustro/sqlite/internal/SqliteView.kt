package io.github.twinsen81.lustro.sqlite.internal

/**
 * The HTML of the SQLite tab's view. It is a static shell: `sqlite.js` fills
 * the databases, the tables, and the rows and wires the controls, with no inline
 * handlers, and `sqlite.css` styles it on the console's `.dc-*` components.
 */
internal object SqliteView {
    val HTML: String =
        """
        <div class="sqlite" id="sqlite-root">
            <div class="dc-split sqlite-split">
                <aside class="dc-pane sqlite-side">
                    <div class="sqlite-side__head">
                        <h3 class="dc-mono-label">Databases</h3>
                    </div>
                    <div class="sqlite-dbs" id="sqlite-dbs" role="listbox" aria-label="Databases"></div>
                    <div class="sqlite-side__tools">
                        <label class="dc-field sqlite-search">
                            <svg class="sqlite-search__icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4-4"/></svg>
                            <input type="text" id="sqlite-table-filter" class="dc-input" placeholder="filter tables…" autocomplete="off" spellcheck="false" aria-label="Filter tables" title="Show the tables whose name contains this text, ignoring case.">
                        </label>
                        <button type="button" class="sqlite-check" id="sqlite-internal" data-action="toggleInternal" role="checkbox" aria-checked="false" title="Show SQLite's and Room's own tables too: sqlite_*, android_metadata, room_master_table, and the shadow tables of a full-text or R*Tree index. Your browser remembers the choice."><span class="dc-check"></span><span id="sqlite-internal-label">Show internal (0)</span></button>
                    </div>
                    <div class="sqlite-tables" id="sqlite-tables" role="listbox" aria-label="Tables"></div>
                </aside>
                <div class="dc-divider" aria-hidden="true"></div>
                <section class="dc-pane sqlite-main">
                    <div class="sqlite-head">
                        <div class="sqlite-head__title">
                            <span class="sqlite-head__db" id="sqlite-head-db"></span>
                            <h3 class="sqlite-head__table" id="sqlite-head-table"></h3>
                            <span class="sqlite-head__facts" id="sqlite-head-facts"></span>
                        </div>
                        <div class="dc-seg" role="tablist" aria-label="View">
                            <button type="button" class="dc-seg__item dc-seg__item--active" id="sqlite-show-rows" data-action="showView" data-view="rows" role="tab" aria-selected="true" title="Browse the rows of the table.">Rows</button>
                            <button type="button" class="dc-seg__item" id="sqlite-show-sql" data-action="showView" data-view="sql" role="tab" aria-selected="false" title="Run your own statement on the database.">SQL</button>
                        </div>
                    </div>
                    <div class="sqlite-rows" id="sqlite-rows-view">
                        <div class="sqlite-bar">
                            <div class="dc-field sqlite-search sqlite-search--rows">
                                <label class="sqlite-search__label" for="sqlite-filter"><svg class="sqlite-search__icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4-4"/></svg></label>
                                <input type="text" id="sqlite-filter" class="dc-input" placeholder="filter rows… (a cell contains it)" autocomplete="off" spellcheck="false" aria-label="Filter rows" title="Show the rows in which a cell, as text, contains this text. Case does not matter for ASCII letters, and a blob never matches.">
                                <button type="button" class="dc-btn dc-btn--icon sqlite-search__clear" id="sqlite-filter-clear" data-action="clearFilter" hidden aria-label="Clear the filter" title="Clear the filter.">×</button>
                            </div>
                            <div class="dc-menu-anchor">
                                <button type="button" class="dc-btn" id="sqlite-columns" data-action="toggleColumns" aria-haspopup="true" aria-expanded="false" aria-controls="sqlite-columns-menu" title="Show or hide columns. Right-click a column's header to hide it. Your browser remembers the choice for each table.">Columns ▾</button>
                                <div class="dc-menu dc-menu--right sqlite-menu" id="sqlite-columns-menu" role="menu" aria-label="Columns" hidden></div>
                            </div>
                            <button type="button" class="dc-btn dc-btn--soft" data-action="refresh" title="Read the databases, the tables, and this page of rows again.">↻ Refresh</button>
                            <div class="sqlite-auto">
                                <label class="dc-switch" title="Auto refresh: read this page of rows again every 2 s. It waits while a dialog is open or the SQL view shows. Your browser remembers the choice.">
                                    <input type="checkbox" id="sqlite-auto" aria-label="Auto refresh">
                                    <span class="dc-switch__track"></span>
                                </label>
                                <label class="sqlite-auto__label" for="sqlite-auto">Auto refresh</label>
                            </div>
                            <div class="dc-menu-scrim" id="sqlite-menu-scrim" data-action="closeMenus" hidden></div>
                        </div>
                        <div class="sqlite-paging">
                            <button type="button" class="dc-btn dc-btn--sm" id="sqlite-prev" data-action="prevPage" disabled title="The page before.">‹ Prev</button>
                            <span class="sqlite-range" id="sqlite-range"></span>
                            <button type="button" class="dc-btn dc-btn--sm" id="sqlite-next" data-action="nextPage" disabled title="The next page.">Next ›</button>
                        </div>
                        <div class="sqlite-banner" id="sqlite-banner" role="alert" hidden></div>
                        <div class="sqlite-scroll" id="sqlite-scroll">
                            <table class="dc-table sqlite-grid" id="sqlite-grid"></table>
                            <div class="sqlite-empty" id="sqlite-empty" hidden></div>
                        </div>
                    </div>
                    <div class="sqlite-sql" id="sqlite-sql-view" hidden>
                        <div class="sqlite-sql__block">
                            <label class="dc-label" for="sqlite-sql">SQL console</label>
                            <textarea id="sqlite-sql" class="dc-textarea sqlite-sql__input" rows="5" spellcheck="false" autocomplete="off" placeholder="SELECT * FROM … LIMIT 50"></textarea>
                            <div class="sqlite-sql__actions">
                                <button type="button" class="dc-btn dc-btn--primary" id="sqlite-run" data-action="runRead" title="Run the statement on a connection that SQLite opens read-only: SQLite refuses every write there.">▸ Run</button>
                                <button type="button" class="dc-btn dc-btn--ghost-danger" id="sqlite-run-write" data-action="runWrite" hidden title="Run the statement through the app's handle, in a transaction of its own. It can change the app's data.">Run as write</button>
                                <span class="dc-comment sqlite-sql__hint" id="sqlite-sql-hint"></span>
                            </div>
                        </div>
                        <div class="sqlite-sql__block" id="sqlite-recent-block" hidden>
                            <div class="dc-label">Recent statements</div>
                            <div class="sqlite-recent" id="sqlite-recent"></div>
                        </div>
                        <div class="sqlite-sql__block sqlite-result" id="sqlite-result-block" hidden>
                            <div class="sqlite-result__head">
                                <span class="dc-label">Results</span>
                                <span class="sqlite-result__status" id="sqlite-result-status"></span>
                            </div>
                            <div class="sqlite-error" id="sqlite-sql-error" role="alert" hidden></div>
                            <div class="sqlite-note" id="sqlite-result-note" hidden></div>
                            <div class="sqlite-result__scroll" id="sqlite-result-scroll" hidden>
                                <table class="dc-table sqlite-grid sqlite-grid--result" id="sqlite-result"></table>
                            </div>
                        </div>
                    </div>
                    <div class="sqlite-none" id="sqlite-none" hidden></div>
                </section>
            </div>
            <div id="sqlite-edit-modal" class="dc-modal-scrim" hidden>
                <div class="dc-modal dc-modal--wide" id="sqlite-edit-dialog" role="dialog" aria-modal="true" aria-labelledby="sqlite-edit-title">
                    <div class="dc-modal__head">
                        <span class="dc-modal__title" id="sqlite-edit-title">Edit row</span>
                        <button type="button" class="dc-modal__close" data-action="closeEdit" aria-label="Close" title="Close without saving.">×</button>
                    </div>
                    <div class="dc-modal__body">
                        <div class="sqlite-edit__where" id="sqlite-edit-where"></div>
                        <div class="dc-comment sqlite-edit__lock" id="sqlite-edit-lock" hidden></div>
                        <div class="sqlite-fields" id="sqlite-edit-fields"></div>
                        <div class="sqlite-error" id="sqlite-edit-error" role="alert" hidden></div>
                    </div>
                    <div class="dc-modal__foot">
                        <button type="button" class="dc-btn dc-btn--primary" id="sqlite-edit-save" data-action="saveEdit">Save</button>
                        <button type="button" class="dc-btn dc-btn--ghost-danger" id="sqlite-edit-delete" data-action="deleteFromEdit">Delete</button>
                        <button type="button" class="dc-btn" id="sqlite-edit-cancel" data-action="closeEdit" title="Close without saving.">Cancel</button>
                    </div>
                </div>
            </div>
            <div id="sqlite-value-modal" class="dc-modal-scrim" hidden>
                <div class="dc-modal dc-modal--wide" role="dialog" aria-modal="true" aria-labelledby="sqlite-value-title">
                    <div class="dc-modal__head">
                        <span class="dc-modal__title" id="sqlite-value-title">Value</span>
                        <button type="button" class="dc-modal__close" data-action="closeValue" aria-label="Close" title="Close.">×</button>
                    </div>
                    <div class="dc-modal__body">
                        <div class="dc-comment" id="sqlite-value-meta"></div>
                        <div class="sqlite-value" id="sqlite-value-body"></div>
                        <div class="sqlite-error" id="sqlite-value-error" role="alert" hidden></div>
                    </div>
                    <div class="dc-modal__foot">
                        <button type="button" class="dc-btn" id="sqlite-value-copy" data-action="copyValue" title="Copy the whole value.">⧉ Copy</button>
                        <button type="button" class="dc-btn" data-action="closeValue">Close</button>
                    </div>
                </div>
            </div>
            <div id="sqlite-confirm-modal" class="dc-modal-scrim" hidden>
                <div class="dc-modal" role="alertdialog" aria-modal="true" aria-labelledby="sqlite-confirm-title" aria-describedby="sqlite-confirm-message">
                    <div class="dc-modal__head">
                        <span class="dc-modal__title" id="sqlite-confirm-title">Delete row</span>
                        <button type="button" class="dc-modal__close" data-action="cancelConfirm" aria-label="Close" title="Close. Nothing changes.">×</button>
                    </div>
                    <div class="dc-modal__body">
                        <p class="sqlite-confirm__message" id="sqlite-confirm-message"></p>
                    </div>
                    <div class="dc-modal__foot">
                        <button type="button" class="dc-btn dc-btn--ghost-danger" id="sqlite-confirm-ok" data-action="confirmOk">Delete</button>
                        <button type="button" class="dc-btn" data-action="cancelConfirm" title="Close. Nothing changes.">Cancel</button>
                    </div>
                </div>
            </div>
        </div>
        """.trimIndent()
}
