# Mock rules

A mock rule answers the requests that it matches with a response that the rule gives: the
interceptor answers from the rule, and the request does not leave the device. In the Network tab, **Mocks**, next to the
search field, opens the rules and the rule form in the right pane, and shows how many rules are
enabled. The rules apply to all traffic, not to the selected request. Rules answer the calls of
Lustro's OkHttp interceptor. An [adapter for another HTTP client](other-clients.md) applies a rule
only when it asks for one with `NetworkCaptureSink.findMockRule`.
[Platform `HttpURLConnection` capture](httpurlconnection.md) only records traffic, and a rule never
matches it.
`urlPattern` is looked for anywhere in the request URL: as a substring, or as a regular expression
when prefixed with `regex:`. So `regex:/api/v1/statuses$` matches
`https://example.com/api/v1/statuses` and not `.../statuses/1/favourite`; anchor a pattern with `^`
when it must match from the start of the URL. A `regex:` pattern that does not compile is rejected
when you save the rule, because a rule that never matches lets the real request through.

**The first match answers.** Rules are tried from the top of the list: the first enabled rule
whose pattern and method match a request answers it. A new rule goes on top, so the rule you added
last wins when two match; editing a rule keeps its place, and `POST rules/_/sync` sets the list in
the order posted.

**A rule can fail the request instead of answering it.** Choose a **Failure** in the rule form,
set `failure` in the API, or pass `--failure` to `lustro mock add`, and the rule fails each request
that it matches with a connection error, as a bad network does. The interceptor throws the
exception that OkHttp throws for that error, so the app takes the path that it takes when the
network fails, such as its offline state or a retry, and not the path for an HTTP error response:

| `failure` | The app's call throws |
| --- | --- |
| `timeout` | `SocketTimeoutException` |
| `unknownHost` | `UnknownHostException` |
| `connectionRefused` | `ConnectException` |
| `connectionReset` | `SocketException` (`Connection reset`) |
| `tls` | `SSLHandshakeException` |

A failure rule ignores its status, headers, and body. The exception's message ends with
`(mocked by Lustro rule '<id>')`, so that a rule left enabled is easy to find in the app's logs.
The call fails at once: to fail it after a wait, as a timeout does on a real network, set the
throttle too. The Network tab lists the request as failed, with the error, and its detail says
"Mocked by rule" with that rule. A failure rule for the handshake of a WebSocket makes the socket
fail: the app's `WebSocketListener.onFailure` gets the exception and no response.

**Mock this request**, in the bar above a transaction's detail, opens the rules with a new rule
filled in for that request: a `regex:` pattern that matches its URL and no other, so a rule for `/items/1` doesn't also answer
`/items/1/comments`, its method, its status, its `Content-Type`, and its body. A query value that
Lustro redacted matches any value, because the app sends the real one. The body is the stored one,
so a redacted value in it is `[REDACTED]`: the form says when that is so, and you replace those
values before you save. For a request that a failure rule failed, the new rule has the failure
that the rule has now. The button is disabled for a request that
[platform `HttpURLConnection` capture](httpurlconnection.md) recorded,
because no rule answers one.

**The detail says which rule answers the request.** When an enabled rule matches the selected
request, the detail says "Mock rule active" with the rule's name, also for a request captured
before the rule existed, and its row in the list has a dashed **MOCK** tag. A request that a rule
answered says "Mocked by rule" with that rule, and whether it still answers the next request like
it. **Matching rules**, next to **Mock this request**, shows only the rules whose pattern and method
match that request, enabled or not, and marks the one that answers it. For these indicators, the
app matches the rules as the interceptor does, but against the URL as captured, which is redacted.
The interceptor itself matches the real URL. The console updates
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
it on every change, in the order the rules are tried. A `MockRuleStorage` of your own must keep
every property of a rule, `failure` too: a failure rule that it saves without `failure` loads as a
rule that answers with its status and body. `SharedPreferencesMockRuleStorage` keeps them all.

**A rule must be one that the interceptor can serve.** Its `statusCode` must be from 100 to 599,
its `responseHeaders` must be header names and values that OkHttp accepts, a `Content-Type`
among them must parse as a media type, and its `failure`, when it has one, must be a name from the
table above. The API refuses a rule that is not valid with a `400` in
the error envelope, whose `field` names the value. When the app loads a stored rule that is not
valid, it drops the rule. Such a rule would throw inside your own HTTP call, for every request
that it matched.
