package io.github.twinsen81.lustro.internal

import android.content.Context
import java.util.Collections

/**
 * Loads CSS/JS/JSON/image asset files for the debug web server. Text assets use
 * a bounded LRU cache to avoid unbounded memory growth.
 *
 * Asset files live under `assets/lustro/` and follow a naming convention:
 * - `shared.css` / `shared.js` — shared styles and utilities loaded by the server.
 * - `lustro-icon-transparent.png` — browser favicon / app icon.
 * - `<tab.id>.css` / `<tab.id>.js` — per-tab assets, matched by the tab id.
 *
 * For dynamic values, use `%UPPER_SNAKE%` placeholders in asset files and pass a
 * replacements map at load time. Templates are cached; replacement results are
 * computed per-call since dynamic values change between requests.
 */
internal class DebugAssetLoader(
    private val context: Context,
    maxCacheEntries: Int = DEFAULT_MAX_CACHE_ENTRIES,
) {
    // Access-ordered LinkedHashMap with eldest-entry eviction == a simple LRU.
    // Wrapped in synchronizedMap because access-order mutation is not thread-safe.
    private val cache: MutableMap<String, String> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                    size > maxCacheEntries
            },
        )

    /**
     * Loads the asset at `assets/lustro/<name>`, returning cached content on
     * subsequent calls. Returns `null` when the asset is missing.
     */
    fun load(name: String): String? {
        cache[name]?.let { return it }
        return try {
            val content =
                context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
            cache[name] = content
            content
        } catch (_: Exception) {
            null
        }
    }

    fun loadBytes(name: String): ByteArray? =
        try {
            context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
        } catch (_: Exception) {
            null
        }

    /**
     * Loads the asset template and replaces `%PLACEHOLDER%` tokens with the given
     * values. Returns `null` when the asset is missing.
     */
    fun load(name: String, replacements: Map<String, String>): String? {
        val template = load(name) ?: return null
        return replacements.entries.fold(template) { acc, (key, value) ->
            acc.replace(key, value)
        }
    }

    private companion object {
        private const val ASSET_DIR = "lustro"
        private const val DEFAULT_MAX_CACHE_ENTRIES = 64
    }
}
