package io.github.twinsen81.lustro.sample

import io.github.twinsen81.lustro.network.NetworkClassifier

/**
 * A tiny [NetworkClassifier] that tags captured traffic by URL substring.
 *
 * The library's default classifier ([io.github.twinsen81.lustro.network.NoOpNetworkClassifier])
 * returns no categories, so out of the box the Network tab's category bar is
 * empty. This demonstrates the classifier SPI: each captured URL is matched
 * against a few substrings and labelled, and those labels appear as filterable
 * category pills in the Network tab and in agent-facing transaction JSON.
 *
 * A URL may carry more than one label (e.g. an auth-related media upload).
 */
public class SampleNetworkClassifier : NetworkClassifier {
    override fun classify(url: String): List<String> {
        val lower = url.lowercase()
        val labels = mutableListOf<String>()
        if ("/post" in lower || "/put" in lower || "/patch" in lower || "/delete" in lower) {
            labels += "Write"
        }
        if ("/get" in lower || "/json" in lower) {
            labels += "Read"
        }
        if ("auth" in lower || "bearer" in lower || "/basic-auth" in lower) {
            labels += "Auth"
        }
        if ("/image" in lower || "/upload" in lower || "media" in lower) {
            labels += "Media"
        }
        if ("/sse" in lower) {
            labels += "Streaming"
        }
        if ("/anything/sync" in lower) {
            labels += "Sync"
        }
        if ("platform" in lower) {
            labels += "Platform"
        }
        if ("/status/4" in lower || "/status/5" in lower) {
            labels += "Errors"
        }
        if ("/delay/" in lower) {
            labels += "Slow"
        }
        if ("/anything/other" in lower) {
            labels += "Other"
        }
        return labels
    }
}
