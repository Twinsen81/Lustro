# Mock rules

A mock rule short-circuits matching requests with a synthetic response: the interceptor answers
from the rule and the request never leaves the device. In the Network tab, **Mocks**, next to the
search field, opens the rules and the rule form in the right pane, and shows how many rules are
enabled. The rules apply to all traffic, not to the selected request. Rules answer OkHttp calls
only: [platform `HttpURLConnection` capture](httpurlconnection.md)
records traffic, and a rule never matches it.
`urlPattern` is looked for anywhere in the request URL: as a substring, or as a regular expression
when prefixed with `regex:`. So `regex:/api/v1/statuses$` matches
`https://example.com/api/v1/statuses` and not `.../statuses/1/favourite`; anchor a pattern with `^`
when it must match from the start of the URL. A `regex:` pattern that does not compile is rejected
when you save the rule, because a rule that never matches lets the real request through.

**The first match answers.** Rules are tried from the top of the list: the first enabled rule
whose pattern and method match a request answers it. A new rule goes on top, so the rule you added
last wins when two match; editing a rule keeps its place, and `POST rules/_/sync` sets the list in
the order posted.

**Mock this request**, in the bar above a transaction's detail, opens the rules with a new rule
filled in for that request: a `regex:` pattern that matches its URL and no other, so a rule for `/items/1` doesn't also answer
`/items/1/comments`, its method, its status, its `Content-Type`, and its body. A query value that
Lustro redacted matches any value, because the app sends the real one. The body is the stored one,
so a redacted value in it is `[REDACTED]`: the form says when that is so, and you replace those
values before you save. The button is disabled for a request that
[platform `HttpURLConnection` capture](httpurlconnection.md) recorded,
because no rule answers one.

**The detail says which rule answers the request.** When an enabled rule matches the selected
request, the detail says "Mock rule active" with the rule's name, also for a request captured
before the rule existed, and its row in the list has a dashed **MOCK** tag. A request that a rule
answered says "Mocked by rule" with that rule, and whether it still answers the next request like
it. **Matching rules**, next to **Mock this request**, shows only the rules whose pattern and method
match that request, enabled or not, and marks the one that answers it. The app matches the rules
as the interceptor does, against the URL as captured, which is redacted, and the console updates
when a rule is added, changed, enabled, or disabled. Agents read the same from each transaction's
`activeMockRuleId`, `mockRuleId`, and `matchingMockRuleIds`.

**Rules live in the app, not in the browser.** They are kept in memory unless you pass a
`MockRuleStorage` to `NetworkDebugTab.create(...)`, so without one they are gone when the process
dies:

```kotlin
NetworkDebugTab.create(
    senderClient = client,
    mockRuleStorage = SharedPreferencesMockRuleStorage(
        context.getSharedPreferences("my_app_mocks", Context.MODE_PRIVATE),
    ),
)
```

Lustro reads the storage once, when the rules are first needed, off the main thread, and writes
it on every change, in the order the rules are tried.

**A rule must be one the interceptor can serve.** Its `statusCode` has to be within 100–599, its
`responseHeaders` have to be header names and values OkHttp accepts, and a `Content-Type` among
them has to parse as a media type. A rule that isn't is rejected with an enveloped `400` naming
the offending `field`, and one already stored is dropped when it is loaded — an unservable rule
would otherwise throw inside your own HTTP call, on every request it matched.
