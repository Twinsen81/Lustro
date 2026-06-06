package io.github.twinsen81.lustro.internal

import io.github.twinsen81.lustro.DebugTab

/**
 * Internal registry of [DebugTab]s. Mutable while building (before [start]),
 * read-only after.
 *
 * Notable behavior:
 * - Tab ids are validated against `[a-z][a-z0-9-]{0,30}` at [addTab] time
 *   (throws [IllegalArgumentException]).
 * - Duplicate ids are detected at [start] time.
 * - Sorting is by [DebugTab.order] with an alphabetical id tie-break.
 */
internal class DebugTabRegistry {
    private val tabs = ArrayList<DebugTab>()

    @Volatile
    private var started = false

    @Volatile
    private var sorted: List<DebugTab> = emptyList()

    /**
     * Adds a [tab], validating its id immediately. Throws
     * [IllegalArgumentException] when the id is invalid or when the registry has
     * already been started.
     */
    fun addTab(tab: DebugTab) {
        check(!started) { "Cannot add tabs after the registry has started" }
        require(ID_REGEX.matches(tab.id)) {
            "Invalid debug tab id '${tab.id}': must match ${ID_REGEX.pattern}"
        }
        tabs.add(tab)
    }

    /**
     * Freezes the registry, checking for duplicate ids and computing the sorted
     * order. Throws [IllegalArgumentException] on a duplicate id.
     */
    fun start() {
        if (started) return
        val seen = HashSet<String>()
        for (tab in tabs) {
            require(seen.add(tab.id)) { "Duplicate debug tab id '${tab.id}'" }
        }
        sorted = tabs.sortedWith(TAB_ORDER)
        started = true
    }

    /**
     * All registered tabs, sorted by order (ties broken alphabetically by id).
     *
     * Available BEFORE [start]: `Lustro.Builder.build()` resolves the
     * [NetworkDebugTab]/[NetworkCaptureProvider] and pushes [DebugConfig] from this
     * list while still building (the registry only freezes — and dedups — at
     * [start], which runs later inside `Lustro.start()`). Returning an empty list
     * until [start] would silently disable network capture and config injection.
     */
    val sortedTabs: List<DebugTab>
        get() = if (started) sorted else tabs.sortedWith(TAB_ORDER)

    /** Tabs that appear in the tab bar, in sorted order. */
    fun visibleTabs(): List<DebugTab> = sorted.filter { it.showInTabBar }

    /** Find a tab by its id, or `null`. */
    fun findTab(id: String): DebugTab? = sorted.firstOrNull { it.id == id }

    /** The default (first visible) tab, or `null` when there are none. */
    fun defaultTab(): DebugTab? = visibleTabs().firstOrNull()

    /** All registered tab ids, sorted alphabetically. */
    fun allIds(): List<String> = sorted.map { it.id }.sorted()

    private companion object {
        private val ID_REGEX = Regex("[a-z][a-z0-9-]{0,30}")
        private val TAB_ORDER = compareBy<DebugTab>({ it.order }, { it.id })
    }
}
