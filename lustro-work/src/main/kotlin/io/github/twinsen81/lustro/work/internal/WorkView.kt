package io.github.twinsen81.lustro.work.internal

/**
 * The HTML of the WorkManager tab's view. It is a static shell: `work.js` fills
 * the list, the drawer, and the timeline and wires the controls, with no inline
 * handlers, and `work.css` styles it on the console's `.dc-*` components.
 */
internal object WorkView {
    val HTML: String =
        """
        <div class="work" id="work-root">
            <div class="work-banner" id="work-banner" role="status" hidden>
                <div class="work-banner__title" id="work-banner-title"></div>
                <div class="work-banner__fix" id="work-banner-fix"></div>
                <div class="work-banner__reason" id="work-banner-reason"></div>
            </div>
            <div class="dc-split work-split">
                <section class="dc-pane work-list">
                    <div class="dc-toolbar work-toolbar">
                        <h3 class="dc-mono-label">Work</h3>
                        <span class="work-count" id="work-count"></span>
                        <button type="button" class="dc-btn dc-btn--ghost-danger work-toolbar__prune" id="work-prune" data-action="askPrune" title="Prune: delete the finished work (succeeded, failed, and cancelled) that no unfinished work depends on, as WorkManager.pruneWork() does. It asks first.">Prune</button>
                    </div>
                    <div class="work-filters">
                        <div class="work-chips" id="work-chips" role="group" aria-label="States">
                            <button type="button" class="dc-chip" data-action="toggleState" data-state="ENQUEUED" aria-pressed="false" title="Show the enqueued work: waiting for its time, its constraints, or a free worker. Click more states to show them too."><span class="dc-chip__dot" style="--c: var(--work-enqueued)"></span>Enqueued<span class="work-chip__count" data-count="ENQUEUED"></span></button>
                            <button type="button" class="dc-chip" data-action="toggleState" data-state="RUNNING" aria-pressed="false" title="Show the running work."><span class="dc-chip__dot" style="--c: var(--work-running)"></span>Running<span class="work-chip__count" data-count="RUNNING"></span></button>
                            <button type="button" class="dc-chip" data-action="toggleState" data-state="SUCCEEDED" aria-pressed="false" title="Show the work that succeeded."><span class="dc-chip__dot" style="--c: var(--work-succeeded)"></span>Succeeded<span class="work-chip__count" data-count="SUCCEEDED"></span></button>
                            <button type="button" class="dc-chip" data-action="toggleState" data-state="FAILED" aria-pressed="false" title="Show the work that failed, also because a work before it in a chain failed."><span class="dc-chip__dot" style="--c: var(--work-failed)"></span>Failed<span class="work-chip__count" data-count="FAILED"></span></button>
                            <button type="button" class="dc-chip" data-action="toggleState" data-state="BLOCKED" aria-pressed="false" title="Show the blocked work: it waits for the work before it in a chain."><span class="dc-chip__dot" style="--c: var(--work-blocked)"></span>Blocked<span class="work-chip__count" data-count="BLOCKED"></span></button>
                            <button type="button" class="dc-chip" data-action="toggleState" data-state="CANCELLED" aria-pressed="false" title="Show the cancelled work."><span class="dc-chip__dot" style="--c: var(--work-cancelled)"></span>Cancelled<span class="work-chip__count" data-count="CANCELLED"></span></button>
                        </div>
                        <div class="work-fields">
                            <div class="dc-field work-field">
                                <label class="work-field__label" for="work-tag"><span class="dc-field__prefix" aria-hidden="true">#</span><input type="text" id="work-tag" name="tag" class="dc-input" list="work-tag-list" placeholder="tag" autocomplete="off" spellcheck="false" data-action="filterInput" aria-label="Tag" title="Show the work with exactly this tag. The list suggests the tags of the work that it showed."></label>
                                <button type="button" class="dc-btn dc-btn--icon work-field__clear" data-action="clearField" data-target="work-tag" hidden aria-label="Clear the tag" title="Clear the tag.">×</button>
                            </div>
                            <div class="dc-field work-field">
                                <label class="work-field__label" for="work-class"><span class="dc-field__prefix" aria-hidden="true">&gt;</span><input type="text" id="work-class" name="class" class="dc-input" list="work-class-list" placeholder="worker class" autocomplete="off" spellcheck="false" data-action="filterInput" aria-label="Worker class" title="Show the work of this worker class, by its full or its simple name, such as SyncWorker."></label>
                                <button type="button" class="dc-btn dc-btn--icon work-field__clear" data-action="clearField" data-target="work-class" hidden aria-label="Clear the worker class" title="Clear the worker class.">×</button>
                            </div>
                            <div class="dc-field work-field">
                                <label class="work-field__label" for="work-name"><span class="dc-field__prefix" aria-hidden="true">@</span><input type="text" id="work-name" name="name" class="dc-input" list="work-name-list" placeholder="unique name" autocomplete="off" spellcheck="false" data-action="filterInput" aria-label="Unique name" title="Show the work of this unique name, any name that the app enqueued work under. The list suggests the names that the app registers with the tab."></label>
                                <button type="button" class="dc-btn dc-btn--icon work-field__clear" data-action="clearField" data-target="work-name" hidden aria-label="Clear the unique name" title="Clear the unique name.">×</button>
                            </div>
                            <datalist id="work-tag-list"></datalist>
                            <datalist id="work-class-list"></datalist>
                            <datalist id="work-name-list"></datalist>
                        </div>
                    </div>
                    <div class="work-scroll">
                        <table class="dc-table work-table" id="work-table">
                            <thead class="dc-thead">
                                <tr>
                                    <th class="dc-th work-th--state">State</th>
                                    <th class="dc-th">Worker</th>
                                    <th class="dc-th work-th--name">Unique name</th>
                                    <th class="dc-th work-th--tags" title="The tags that the app added. WorkManager also tags each work with its worker class; the details show that tag too.">Tags</th>
                                    <th class="dc-th work-th--attempts" title="How many times the work ran: run attempts.">Runs</th>
                                    <th class="dc-th work-th--next" title="When WorkManager runs the work next, on the device's clock. Hover a time for the date.">Next run</th>
                                </tr>
                            </thead>
                            <tbody id="work-rows"></tbody>
                        </table>
                        <div class="work-empty" id="work-empty">// reading the work…</div>
                    </div>
                </section>
                <div class="dc-divider" aria-hidden="true"></div>
                <section class="dc-pane work-detail">
                    <div class="work-none" id="work-none">
                        <p>// select work to see its details and the events of its runs</p>
                    </div>
                    <div class="work-drawer" id="work-drawer" hidden>
                        <div class="work-head">
                            <div class="work-head__line">
                                <span class="dc-badge work-head__state" id="work-head-state"></span>
                                <h3 class="work-head__class" id="work-head-class"></h3>
                                <button type="button" class="dc-btn dc-btn--icon work-head__close" data-action="closeDetail" aria-label="Close the details" title="Close the details. Escape does the same.">✕</button>
                            </div>
                            <div class="work-head__id" id="work-head-id"></div>
                            <div class="work-head__actions">
                                <button type="button" class="dc-btn dc-btn--sm dc-btn--ghost-danger" id="work-cancel" data-action="askCancelId">Cancel</button>
                                <button type="button" class="dc-btn dc-btn--sm dc-btn--ghost-danger" id="work-cancel-name" data-action="askCancelName" hidden></button>
                                <button type="button" class="dc-btn dc-btn--sm" data-action="copyId" title="Copy the id of the work.">⧉ Copy id</button>
                            </div>
                            <div class="work-gone" id="work-gone" role="status" hidden></div>
                        </div>
                        <div class="dc-tabs work-tabs" role="tablist" aria-label="Details of the work">
                            <button type="button" class="dc-tab dc-tab--active" data-action="showPanel" data-panel="details" role="tab" aria-selected="true">Details</button>
                            <button type="button" class="dc-tab" data-action="showPanel" data-panel="timeline" role="tab" aria-selected="false" title="The events of the work's runs that the tab recorded, oldest first.">Timeline<span class="work-tab__count" id="work-timeline-count"></span></button>
                            <button type="button" class="dc-tab" id="work-history-tab" data-action="showPanel" data-panel="history" role="tab" aria-selected="false" hidden title="The periods of the work that work-analytics keeps on disk, also of the runs of earlier processes.">History<span class="work-tab__count" id="work-history-count"></span></button>
                        </div>
                        <div class="dc-tabpanel work-panel" id="work-panel-details" role="tabpanel"></div>
                        <div class="dc-tabpanel work-panel" id="work-panel-timeline" role="tabpanel" hidden>
                            <div class="work-note" id="work-timeline-note" hidden></div>
                            <ol class="work-events" id="work-events"></ol>
                            <div class="work-empty" id="work-events-empty" hidden></div>
                        </div>
                        <div class="dc-tabpanel work-panel" id="work-panel-history" role="tabpanel" hidden>
                            <div class="work-periods" id="work-periods"></div>
                            <div class="work-empty" id="work-periods-empty" hidden></div>
                        </div>
                    </div>
                </section>
            </div>
            <div id="work-confirm-modal" class="dc-modal-scrim" hidden>
                <div class="dc-modal" role="alertdialog" aria-modal="true" aria-labelledby="work-confirm-title" aria-describedby="work-confirm-message">
                    <div class="dc-modal__head">
                        <span class="dc-modal__title" id="work-confirm-title"></span>
                        <button type="button" class="dc-modal__close" data-action="closeConfirm" aria-label="Close" title="Close. Nothing changes.">×</button>
                    </div>
                    <div class="dc-modal__body">
                        <p class="work-confirm__message" id="work-confirm-message"></p>
                    </div>
                    <div class="dc-modal__foot">
                        <button type="button" class="dc-btn dc-btn--ghost-danger" id="work-confirm-ok" data-action="confirmOk"></button>
                        <button type="button" class="dc-btn" data-action="closeConfirm" title="Close. Nothing changes.">Keep</button>
                    </div>
                </div>
            </div>
        </div>
        """.trimIndent()
}
