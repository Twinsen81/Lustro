package io.github.twinsen81.lustro.internal.network

/**
 * The dialog that the ? button of the Network tab's search fields opens: how a
 * regex search works, with common regular expressions as examples.
 */
internal val SEARCH_HELP_DIALOG: String =
    """
    <div id="search-help-modal" class="dc-modal-scrim" hidden>
        <div class="dc-modal dc-modal--wide" role="dialog" aria-labelledby="search-help-title">
            <div class="dc-modal__head">
                <span class="dc-modal__title" id="search-help-title">Regular expressions</span>
                <button class="dc-modal__close" data-action="closeSearchHelp" title="Close.">×</button>
            </div>
            <div class="dc-modal__body">
                <p class="net-filter-note">Turn on Regex next to the search field to search with these.</p>
                <table class="dc-kv net-search-examples">
                    <tr><th class="dc-kv__key">featured|popular</th><td class="dc-kv__value">Either word.</td></tr>
                    <tr><th class="dc-kv__key">lists json</th><td class="dc-kv__value">Both words, each in any field. Words are separated by spaces, and all must match.</td></tr>
                    <tr><th class="dc-kv__key">podcasts -analytics</th><td class="dc-kv__value">This, but not that: a word that starts with - must not be in any field.</td></tr>
                    <tr><th class="dc-kv__key">-analytics</th><td class="dc-kv__value">Not this.</td></tr>
                    <tr><th class="dc-kv__key">feat.*\.json</th><td class="dc-kv__value">Both parts, in this order, in one field.</td></tr>
                    <tr><th class="dc-kv__key">/v\d+/</th><td class="dc-kv__value">A version in a path, such as /v1/ or /v12/.</td></tr>
                    <tr><th class="dc-kv__key">\.(png|jpe?g|webp)</th><td class="dc-kv__value">An image file.</td></tr>
                    <tr><th class="dc-kv__key">^post$</th><td class="dc-kv__value">The whole method: ^ and $ are the start and the end of a field.</td></tr>
                    <tr><th class="dc-kv__key">^cache-control:</th><td class="dc-kv__value">A header by its name. A header is searched as the line Name: value.</td></tr>
                    <tr><th class="dc-kv__key">^content-type:\s.*json</th><td class="dc-kv__value">A header with a value. A space ends a word, so write it as \s.</td></tr>
                    <tr><th class="dc-kv__key">"id":\s*42</th><td class="dc-kv__value">A JSON key and value, with or without a space between them.</td></tr>
                    <tr><th class="dc-kv__key">[0-9a-f]{8}-[0-9a-f]{4}-</th><td class="dc-kv__value">The start of a UUID.</td></tr>
                </table>
                <p class="net-filter-note">Case is ignored. A regex is found anywhere in a field: the URL, the method, one header line, or one body. A . is any character, so write \. for a dot, and .* is any text. \d is a digit, \s is a space, and \ before a character such as ( or ? makes it plain text. To find a minus at the start of a word, write [-]. The search runs on the device, with Java's regular expressions.</p>
                <p class="net-filter-note">In the search of a WebSocket's messages, a field is the text of one message.</p>
            </div>
            <div class="dc-modal__foot">
                <button class="dc-btn dc-btn--primary" data-action="closeSearchHelp" title="Close.">Close</button>
            </div>
        </div>
    </div>
    """.trimIndent()
