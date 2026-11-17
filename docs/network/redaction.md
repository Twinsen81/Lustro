# Redaction and categories

Two parameters of `NetworkDebugTab.create` change what the Network tab stores for each request:

- **`redactor`**, a `Redactor`, masks the sensitive values before the tab stores the request. The
    default is `DefaultRedactor`.
- **`classifier`**, a `NetworkClassifier`, gives each request category labels, such as `Auth` or
    `Media`. The default gives none.

Both apply to every capture: OkHttp, platform `HttpURLConnection`, WebSockets, and an
[adapter of your own](other-clients.md).

## What the default redactor masks

`DefaultRedactor` decides by **name**. It replaces a value with `[REDACTED]` when:

- **A header** is `Authorization`, `Proxy-Authorization`, `Cookie`, or `Set-Cookie`, or its name
    has a sensitive word, such as `X-Api-Key` or `X-Auth-Token`. `WWW-Authenticate`,
    `Proxy-Authenticate`, and `Access-Control-Allow-Credentials` stay as they are, because they
    hold no secret.
- **A URL query parameter, a JSON field, or a form field** has a sensitive word in its name:
    `token`, `key`, `secret`, `password`, `passwd`, `pwd`, `auth`, `access_token`,
    `refresh_token`, `api_key`, `apikey`, `client_secret`, `session`, `credential`, `bearer`,
    `signature`, or `sig`. The word must be a word of the name, at a separator or a camelCase
    boundary: `accessToken` and `presigned_url` are masked, but `author_name`, `keywords`, and
    `assignments` are not. Names such as `Idempotency-Key`, `public_key`, `max_tokens`,
    `primary_key`, and `is_password_protected` hold no secret, and stay as they are.
- **A multipart part** has a sensitive field name. The redactor masks the whole part, and redacts
    each other part as a body of its own type.
- **Any other text body**, such as an event stream, XML, or plain text, has a value under a
    sensitive key. The redactor finds the keys in the text, and masks the whole value of each.

A JSON body that has no sensitive field is stored as it arrived, byte for byte.
`SensitiveKeys.isSensitive(name)`, in `lustro-api`, is the same name check, so your code can use
it too.

A rule by name cannot find a secret that has no sensitive name. These stay as they arrived: a
token in a URL path or fragment, a presigned URL in a field named `url`, and a credential in a
`{"name": "token", "value": "..."}` pair. The full list of gaps is in
[SECURITY.md](https://github.com/Twinsen81/Lustro/blob/main/SECURITY.md#threat-model).

## Write a redactor

A `Redactor` has three methods for requests and responses, and two for WebSocket messages, which
have a default:

| Method | It gets | It returns |
| --- | --- | --- |
| `redactUrl(url)` | The full URL. | The URL to store. |
| `redactHeaderValue(name, value)` | One header of a request or a response. | The value to store. |
| `redactBody(body, contentType)` | A text body, and its content type or `null`. | The body to store. |
| `redactWebSocketText(text, message)` | A text message, after `redactBody`. | The text to store, or `null` to store only its size. |
| `redactWebSocketBinary(bytes, message)` | A binary message. | The bytes to store, or `null` to store only its size. |

The easiest redactor keeps the default rules and adds yours. This one also masks a device id
header and the token in a password reset path:

```kotlin
object AppRedactor : Redactor by DefaultRedactor {
    private val resetPath = Regex("(/password-reset/)[^/?#]+")

    override fun redactHeaderValue(name: String, value: String): String =
        if (name.equals("X-Device-Id", ignoreCase = true)) "[REDACTED]"
        else DefaultRedactor.redactHeaderValue(name, value)

    override fun redactUrl(url: String): String =
        DefaultRedactor.redactUrl(url).replace(resetPath, "$1[REDACTED]")
}

NetworkDebugTab.create(senderClient = client, redactor = AppRedactor)
```

Keep these rules in mind:

- **It must be thread-safe.** The tab calls it on a background capture thread. During a burst of
    large bodies, the threads of your HTTP calls call it too.
- **A body can end in the middle of a value.** A body larger than the capture limit is cut, and
    the tab redacts an event stream again each time more of it arrives. Mask a sensitive value
    also when its end is missing.
- **The mock rules match the stored URL.** A rule whose pattern has a value that the redactor
    masks does not match. See [Mock rules](mocks.md).
- **Some data never goes through it.** The error text of a failed request, an image body, and the
    close reason of a WebSocket are stored as they arrived. To keep such a request out of the
    capture, use a [capture filter](okhttp.md).

## Label requests with categories

A `NetworkClassifier` gets the URL of each request, after the redactor, and returns its labels.
The labels show in the **Cat** column, as filter buttons above the list, and in the
`categories` of each transaction in the API:

```kotlin
val classifier = NetworkClassifier { url ->
    buildList {
        if ("/auth/" in url || "/oauth/" in url) add("Auth")
        if ("/sync/" in url) add("Sync")
        if (url.endsWith(".jpg") || url.endsWith(".png")) add("Media")
    }
}

NetworkDebugTab.create(senderClient = client, classifier = classifier)
```

Six labels have a color of their own: `Sync`, `Auth`, `Media`, `Config`, `AI`, and `Wiretap`. The
match is case-sensitive, so `sync` is not `Sync`. Every other label gets the same gray color.

The classifier runs on the same threads as the redactor, so keep it fast and thread-safe. When it
throws, the request gets no labels, and the capture goes on.
