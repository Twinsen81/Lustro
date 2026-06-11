package io.github.twinsen81.lustro.network

/**
 * Classifies a request URL into zero or more category labels.
 *
 * A host-supplied SPI for tagging captured traffic with category labels, so
 * apps can classify requests (e.g. `"sync"`, `"media"`) however they like. Use
 * [NoOpNetworkClassifier] for the default behaviour of no categories.
 */
public fun interface NetworkClassifier {
    /** Returns the category labels for [url]; may be empty. */
    public fun classify(url: String): List<String>
}

/**
 * The default [NetworkClassifier] that assigns no categories to any URL.
 *
 * Provided as a top-level value because a `fun interface` cannot also declare a
 * companion-object default while remaining usable as a SAM conversion target;
 * a named top-level constant is the cleaner, discoverable option.
 */
public val NoOpNetworkClassifier: NetworkClassifier = NetworkClassifier { emptyList() }
