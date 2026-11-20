package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.DebugRoutes.Companion.ref
import io.github.twinsen81.lustro.ExperimentalDebugRoutes

/**
 * The text of the Network tab's OpenAPI document that the route declarations in
 * [io.github.twinsen81.lustro.network.NetworkDebugTab], [MockRuleRoutes], and [WebSocketRoutes]
 * do not make: the descriptions and the response schemas. [NETWORK_SCHEMAS] has the shared schemas.
 */
@OptIn(ExperimentalDebugRoutes::class)
internal object NetworkDocument {
    const val TITLE: String = "Lustro Network debug tab"

    const val DESCRIPTION: String =
        "Capture, inspect, mock, throttle, and replay HTTP traffic, and inspect the messages of WebSocket connections. " +
        "All routes are token-authenticated (Bearer header or HttpOnly cookie) and live under /api/v1/network. Errors use " +
        "the shared error envelope. A method that a path below does not take gets a 405 with an Allow header that lists " +
        "the methods it takes."

    const val CURSOR: String = "Opaque cursor from the previous response. Omit on first poll."

    const val REGEX: String =
        "true makes each word of search, up to a space, a Java regular expression found ignoring case: every word must be " +
        "found, each in any field, and a word that starts with - must be found in none. A space in a regex is \\s. 400 " +
        "with field search when a word doesn't compile, and with field regex for a value other than true or false."

    const val TRANSACTIONS: String =
        "Short-polling. Send the last cursor back each call. status=reset returns the full current list; status=delta " +
        "returns the current list after a change; status=unchanged omits items. Treat unknown statuses as reset. The " +
        "cursor advances when the transaction list changes: a capture, a response or streaming progress, a failure, an " +
        "eviction, or clear. A change to the mock rules advances it too, from protocol 1.9, because each transaction has " +
        "activeMockRuleId; a hit on a rule doesn't. Pause, overwrite mode, throttle, and the capture filter's counts " +
        "don't advance it; state reports them on every response, unchanged ones included. A cursor from before an app " +
        "restart gets a reset. A request the app's capture filter skips is never listed; mock rules and the throttle " +
        "still apply to it, and state.captureFilter counts it."

    const val TRANSACTIONS_SEARCH: String =
        "Only the transactions in which this text is found, ignoring case: in the URL, the final URL, the method, a " +
        "request or response header as the line Name: value, or a text body. Headers and bodies are searched as stored, " +
        "after redaction. 400 with field search when the search takes longer than 5 s."

    const val TRANSACTIONS_SINCE: String =
        "Only the transactions with startedAt at or after this time, in milliseconds since the Unix epoch, such as the " +
        "requests that started after a step of a test. The cursor is the one of the whole list, as with search: a change " +
        "to a transaction that since leaves out also advances it, and the delta then has the same items. Send no cursor " +
        "after a change to since. 400 with field since for a value that is not an integer of 0 or more. Since protocol " +
        "1.14."

    const val TRANSACTION_BODY: String =
        "Returns the bytes of a binary body (requestBodyBinary or responseBodyBinary in the detail) as they arrived, or " +
        "the redacted text of a text body encoded as UTF-8, so it is also how to download a large text body. A body cut " +
        "at the capture cap comes back as the part that was kept; the detail's requestBodyTruncated and " +
        "responseBodyTruncated say when. Content-Type is the captured media type without its parameters, plus " +
        "charset=utf-8 for text; application/octet-stream or text/plain; charset=utf-8 when none was captured. image/png, " +
        "image/jpeg, image/gif, and image/webp are served inline, so the console can show them in an img element. Every " +
        "other type, SVG and HTML included, carries Content-Disposition: attachment and the Content-Security-Policy " +
        "default-src 'none'; sandbox, enforced alongside the server's own policy, so a captured page can't run script " +
        "under the console's origin. 404 when the transaction is unknown or no body was kept for that direction: none was " +
        "sent, it was not captured, or its type is one capture drops."

    const val TRANSACTION_BODY_DISPOSITION: String =
        "attachment, for every type but image/png, image/jpeg, image/gif, and image/webp; absent for those."

    const val TRANSACTION_BODY_CSP: String =
        "For an attachment, the server's policy and default-src 'none'; sandbox, in one header; for an inline image, the " +
        "server's policy alone."

    const val EXPORT: String =
        "Returns the transactions that ids names, or every one when ids is left out, as a HAR 1.2 document that browser " +
        "devtools and HTTP tools import, oldest first. The entry of a WebSocket's handshake carries the socket's messages " +
        "in _webSocketMessages, the field Chrome DevTools writes and reads, and its _resourceType is websocket. It is " +
        "built from the store, so headers, the URL, and text bodies are redacted as in the detail. An id the store no " +
        "longer has is left out, and an ids parameter that names none returns no entries. Each entry spends its whole " +
        "durationMs in timings.wait, and the time the global throttle held it in timings.blocked, with send and receive " +
        "at 0 and the other phases at -1; time is their sum. A body kept as bytes goes out in base64: content.encoding is " +
        "base64 for a response, and postData._encoding for a request, which HAR gives no encoding field. _lustro on each " +
        "entry carries the transaction id and what HAR has no field for: isMocked, source, categories, the truncation " +
        "flags, responseComplete, finalUrl, priorResponses, and error. A request still in flight has status 0 and time 0. " +
        "The request line and headers must stay under 8 KB, so a client that exports many ids sends them in batches."

    const val EXPORT_IDS: String =
        "Transaction ids separated by commas; the parameter can also repeat. Left out, the export has every transaction."

    const val WEBSOCKETS: String =
        "Short-polling, as for transactions: status=reset and status=delta carry the full current list, newest first, and " +
        "status=unchanged omits items. The cursor advances when a connection is listed, changes state, or gets an event, " +
        "and on clear. A connection is listed when the app creates its socket with the factory from " +
        "Lustro.webSocketFactory; the interceptor alone shows only the handshake, as a transaction. A socket the app's " +
        "capture filter skips is never listed. While capture is paused, no socket is listed and no message is recorded; a " +
        "close or a failure of a listed connection still is. A connection that was cleared or evicted is listed again " +
        "with its next message, with counts that start again."

    const val EVENTS: String =
        "A stream envelope. The first poll (no cursor, or one from another log) gets status=reset with the last events of " +
        "the log, at most limit of them. A poll with the returned cursor gets status=delta with only the events after it, " +
        "oldest first, or status=unchanged. When more than limit events are new, the delta has the oldest limit of them, " +
        "and the next poll goes on from there. dropped counts the events the log evicted before this client got them. " +
        "direction and search leave events out of items; the cursor still moves past them. Clear starts a new log, so a " +
        "cursor from before it gets a reset."

    const val EVENTS_LIMIT: String =
        "The most events in one response. A limit above the size of the log returns the whole log. 400 when it is not a " +
        "whole number of 1 or more."

    const val EVENTS_DIRECTION: String =
        "Only the events with this direction: messages, and close frames. 400 for any other value."

    const val EVENTS_SEARCH: String =
        "Only the text messages whose stored text contains this, ignoring case. 400 with field search when the search " +
        "takes longer than 5 s."

    const val PAYLOAD: String =
        "Returns the bytes of a binary message as application/octet-stream, or the redacted text of a text message as " +
        "text/plain; charset=utf-8. A payload cut at the capture cap comes back as the part that was kept; the event's " +
        "truncated says when. Every payload carries Content-Disposition: attachment and the Content-Security-Policy " +
        "default-src 'none'; sandbox, enforced alongside the server's own policy, so a captured payload can't run script " +
        "under the console's origin. 404 when the connection or the event is unknown, when the event is not a message, or " +
        "when no payload is stored for it."

    const val PAYLOAD_CSP: String = "The server's policy and default-src 'none'; sandbox, in one header."

    const val CLEAR_SUMMARY: String =
        "Clear the captured transactions and WebSocket connections, and start the capture filter's counts again. Mock " +
        "rules and settings are preserved."

    const val OK: String = """
        {
          "type": "object",
          "required": ["status"],
          "properties": {
            "status": {
              "const": "ok"
            }
          }
        }
    """

    const val RULES_RESPONSE: String =
        "Mock rules, in the order they are tried: the first enabled rule that matches a request answers it."

    val RULES_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["items"],
          "properties": {
            "items": {
              "type": "array",
              "items": ${ref("MockRule")},
              "description": "In the order the rules are tried."
            }
          }
        }
    """

    const val ADD_RULE: String =
        "A new rule goes first, so the rule added last wins when two match a request. A rule with the id of an existing " +
        "one replaces it in place, whole: send enabled, responseHeaders, and failure when editing one, or they fall back " +
        "to true, empty, and null."

    const val ADD_RULE_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["status", "id"],
          "properties": {
            "status": {
              "const": "ok"
            },
            "id": {
              "type": "string"
            }
          }
        }
    """

    const val SYNC_RULES: String =
        "Idempotent: the resulting rule list equals the posted array, in that order, so the first rule posted is tried " +
        "first. A rule without an id gets a new one. Two posted rules with the same id are a 400 with field id, and the " +
        "message has the indexes of both (since protocol 1.11). A 400 leaves the rules as they were. The interceptor " +
        "never observes an empty window during the swap."

    val SYNC_RULES_BODY_SCHEMA: String = """
        {
          "type": "array",
          "items": ${ref("MockRuleInput")}
        }
    """

    const val SYNC_RULES_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["status", "count"],
          "properties": {
            "status": {
              "const": "ok"
            },
            "count": {
              "type": "integer",
              "description": "The number of rules set."
            }
          }
        }
    """

    const val NO_SUCH_RULE: String = "404 with field id when no rule has the id, and then nothing changes."

    const val PAUSE_SUMMARY: String =
        "Toggle capture pause (capture-only; mocks and throttle still apply while paused). It pauses WebSocket messages " +
        "too."

    const val PAUSE_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["status", "paused"],
          "properties": {
            "status": {
              "const": "ok"
            },
            "paused": {
              "type": "boolean"
            }
          }
        }
    """

    const val OVERWRITE_MODE_SUMMARY: String =
        "Toggle overwrite mode (new request evicts earlier completed transactions with the same method + URL path)."

    const val OVERWRITE_MODE_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["status", "overwriteMode"],
          "properties": {
            "status": {
              "const": "ok"
            },
            "overwriteMode": {
              "type": "boolean"
            }
          }
        }
    """

    const val THROTTLE: String =
        "Holds each OkHttp request of the app for delayMs before it is sent; 0 turns the throttle off. A missing delayMs, " +
        "or one that is not a whole number from 0 to 600000 (10 minutes), gets a 400 with field delayMs, and the throttle " +
        "stays as it was."

    const val THROTTLE_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["status", "delayMs"],
          "properties": {
            "status": {
              "const": "ok"
            },
            "delayMs": {
              "type": "integer"
            }
          }
        }
    """

    const val SENDERS_SUMMARY: String =
        "List the senders that POST send can use, in order; the first one is the default."

    const val SENDERS: String =
        "Each sender is an OkHttp client that the app registered under a name. An empty list means that the app " +
        "registered none, and POST send is not available."

    const val SENDERS_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["items"],
          "properties": {
            "items": {
              "type": "array",
              "items": {
                "type": "object",
                "required": ["name", "baseUrl"],
                "properties": {
                  "name": {
                    "type": "string",
                    "description": "The name to pass as sender to POST send."
                  },
                  "baseUrl": {
                    "type": ["string", "null"],
                    "description": "The URL a relative url is appended to; null when the sender takes absolute URLs only. An app can give a sender's base URL as a function that the app calls for each list and each send, so the value can change between two calls; null also when that function failed."
                  }
                }
              }
            }
          }
        }
    """

    const val SEND: String =
        "Blocks until the sender returns, within the per-request timeout. An absolute url is sent as given; a relative " +
        "one is appended to the base URL of the sender, as the app gives it at the time of the send. 400 with field url " +
        "for a relative url when the sender has no base URL, and, since protocol 1.14, with field baseUrl when the app's " +
        "base URL function fails or returns a URL that is not an absolute http or https URL. Self-requests to the debug " +
        "server's own bind host:port are rejected. Only available when the app registered a sender (see GET senders): " +
        "without one, the tab's document has no send route, and POST send gets 404."

    const val SEND_URL: String = "Absolute URL, or a path appended to the base URL of the sender."

    const val SEND_SENDER: String = "The name of a sender from GET senders. Left out or null: the first one."

    const val SEND_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["ok"],
          "properties": {
            "transactionId": {
              "type": ["string", "null"],
              "description": "The transaction that the Lustro interceptor recorded for this request; null when the sender's client has no Lustro interceptor, or the request was not captured."
            },
            "sender": {
              "type": "string",
              "description": "The name of the sender that sent the request. Since 1.7."
            },
            "url": {
              "type": "string",
              "description": "The URL the request went to, after a relative url was resolved. Since 1.7."
            },
            "statusCode": {
              "type": ["integer", "null"],
              "description": "The response status; 0 when ok is false."
            },
            "ok": {
              "type": "boolean",
              "description": "true when a response came back, whatever its status; false when the call failed, and error says why."
            },
            "error": {
              "type": "string"
            }
          }
        }
    """
}
