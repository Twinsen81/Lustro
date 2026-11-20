@file:OptIn(ExperimentalDebugRoutes::class)

package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.DebugRoutes.Companion.ref
import io.github.twinsen81.lustro.ExperimentalDebugRoutes

/**
 * The shared schemas of the Network tab's OpenAPI document, by name, in the order of
 * `components/schemas`. The responses and the other schemas refer to them with [ref].
 */
internal val NETWORK_SCHEMAS: List<Pair<String, String>> =
    listOf(
        "TransactionCursorEnvelope" to """
            {
              "type": "object",
              "required": ["cursor", "status"],
              "properties": {
                "cursor": {
                  "type": "string",
                  "description": "Opaque cursor token."
                },
                "status": {
                  "type": "string",
                  "enum": ["delta", "unchanged", "reset"]
                },
                "items": {
                  "type": "array",
                  "items": ${ref("Transaction")}
                },
                "state": {
                  "type": "object",
                  "description": "Capture controls, sent with every response, unchanged ones included.",
                  "properties": {
                    "paused": {
                      "type": "boolean"
                    },
                    "overwriteMode": {
                      "type": "boolean"
                    },
                    "throttleDelayMs": {
                      "type": "integer"
                    },
                    "mockRules": {
                      "type": "object",
                      "description": "The mock rules, in short. Since protocol 1.9.",
                      "required": ["enabled", "revision"],
                      "properties": {
                        "enabled": {
                          "type": "integer",
                          "minimum": 0,
                          "description": "How many rules are enabled."
                        },
                        "revision": {
                          "type": "integer",
                          "description": "Changes when a rule is added, edited, deleted, enabled, or disabled, and on a sync; a hit leaves it as it is. When it differs from the last one you saw, GET rules again."
                        }
                      },
                      "additionalProperties": false
                    },
                    "captureFilter": {
                      "type": ["object", "null"],
                      "description": "The capture filter the app set in its code, or null when it set none. The counts start again on clear.",
                      "required": ["description", "skipped", "failed"],
                      "properties": {
                        "description": {
                          "type": "string",
                          "description": "What the filter leaves out, as the app describes it."
                        },
                        "skipped": {
                          "type": "integer",
                          "minimum": 0,
                          "description": "Requests the filter left out of the list."
                        },
                        "failed": {
                          "type": "integer",
                          "minimum": 0,
                          "description": "Requests the filter threw on. They were captured."
                        }
                      },
                      "additionalProperties": false
                    }
                  }
                }
              }
            }
        """,
        "Transaction" to """
            {
              "type": "object",
              "required": ["id", "method", "url"],
              "properties": {
                "id": {
                  "type": "string"
                },
                "timestamp": {
                  "type": "string",
                  "description": "When the request started, as HH:mm:ss.SSS in the device's time zone. For display; sort and correlate by startedAt."
                },
                "startedAt": {
                  "type": "integer",
                  "description": "When the request started, in milliseconds since the Unix epoch."
                },
                "completedAt": {
                  "type": ["integer", "null"],
                  "description": "When the capture recorded the response as complete, or the request as failed, in milliseconds since the Unix epoch; null while in flight. A response stays in flight while the app reads a body that capture did not read whole, such as a download, a body past the capture cap, or a stream, until the app reads to its end or closes it. It can differ from startedAt plus durationMs by the time the capture took to record it."
                },
                "method": {
                  "type": "string"
                },
                "url": {
                  "type": "string",
                  "description": "The URL the app requested. Redacted at capture time."
                },
                "finalUrl": {
                  "type": ["string", "null"],
                  "description": "The URL the response came from, when it isn't url: after the client followed a redirect, or after an OkHttp interceptor added after Lustro's rewrote the URL. null otherwise, and while in flight. Redacted at capture time. Since protocol 1.5."
                },
                "protocol": {
                  "type": ["string", "null"],
                  "description": "The protocol the response came over, as the HTTP client names it, e.g. http/1.1, h2, or h3. null when unknown: before a response, and for a mocked response. Platform HttpURLConnection capture reads it from X-Android-Selected-Protocol, a header the platform adds to the response, since protocol 1.6, and leaves out the platform's X-Android-* headers from responseHeaders."
                },
                "statusCode": {
                  "type": ["integer", "null"]
                },
                "durationMs": {
                  "type": ["integer", "null"],
                  "description": "How long the request took from when it was sent, without the time the global throttle held it before that (see throttledMs). For a body the app reads after capture, such as a download, it runs until the app read to the end of the body or closed it, and while the body arrives it is the time to the headers. null before a response."
                },
                "throttledMs": {
                  "type": ["integer", "null"],
                  "description": "How long the global throttle held the request before it was sent, in milliseconds, or null when it wasn't throttled. The request is listed, with startedAt, from when the app made the call. Since protocol 1.4."
                },
                "categories": {
                  "type": "array",
                  "items": {
                    "type": "string"
                  },
                  "description": "Labels from the configured NetworkClassifier (may be empty)."
                },
                "isMocked": {
                  "type": "boolean"
                },
                "mockRuleId": {
                  "type": ["string", "null"],
                  "description": "The id of the mock rule that produced the response, as recorded when the request was answered: the rule can have changed or been deleted since. null when the response isn't mocked, and when an app's adapter reported it with mocked(true) instead of mockedBy(rule). Since protocol 1.9. Since protocol 1.12, also the rule that failed the request: a failure rule, or a rule that the interceptor could not serve. Such a transaction has isMocked true, error set, and statusCode null."
                },
                "activeMockRuleId": {
                  "type": ["string", "null"],
                  "description": "The id of the mock rule that answers the next request with this method and URL: the first enabled rule that matches, as the interceptor tries them. It is worked out when the transaction is listed, so it follows the rules as they change, also for a request captured before the rule existed, and a change to the rules advances the list's cursor. The rules are matched against the URL as captured, which is redacted, so a pattern that names a redacted value doesn't match here. null when no enabled rule matches, and for a platform transaction, which no rule answers; for an app transaction, the rule answers only if the app's adapter asks for it. Since protocol 1.9."
                },
                "replacedCount": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "How many earlier copies of this request overwrite mode removed when the app sent it: the completed transactions with the same method and URL path that started before it, each counted with the copies it had replaced in turn. 0 when it replaced none, as when overwrite mode is off. Since protocol 1.10."
                },
                "source": {
                  "type": "string",
                  "enum": ["okhttp", "platform", "app"],
                  "description": "The adapter that captured the request. okhttp: Lustro's OkHttp interceptor, where mock rules and the throttle apply. platform: platform HttpURLConnection capture, which only records, so no mock rule answers the request and the throttle doesn't hold it. app: the app's own adapter, through Lustro.networkCaptureSink(), where a mock rule applies only if the adapter asks for it, and the throttle doesn't. Since protocol 1.6; treat a value you don't know as one that no mock rule or throttle reaches."
                },
                "requestContentType": {
                  "type": ["string", "null"],
                  "description": "The request's media type as captured, e.g. application/json; charset=utf-8. null when the request declares none."
                },
                "responseContentType": {
                  "type": ["string", "null"],
                  "description": "The response's Content-Type as captured, e.g. application/json; charset=utf-8. null before a response, and when the header is missing or isn't a media type."
                },
                "requestBodyBytes": {
                  "type": ["integer", "null"],
                  "description": "Size of the request body on the wire, before a gzip or deflate Content-Encoding is inflated; null when not known."
                },
                "responseBodyBytes": {
                  "type": ["integer", "null"],
                  "description": "Size of the response body on the wire, before a gzip or deflate Content-Encoding is inflated; null when not known."
                },
                "responseComplete": {
                  "type": "boolean",
                  "description": "false while the request is in flight, which includes a response whose body the app is still reading. A body that fails while the app reads it sets error and keeps statusCode."
                },
                "webSocketId": {
                  "type": ["string", "null"],
                  "description": "Set on the handshake of a WebSocket that is listed under websockets: the id of that connection. null for every other transaction, and once the connection is cleared or evicted."
                },
                "error": {
                  "type": ["string", "null"]
                },
                "requestHeaders": {
                  "type": "object",
                  "additionalProperties": {
                    "type": "string"
                  }
                },
                "requestBody": {
                  "type": ["string", "null"],
                  "description": "The body as redacted text. A gzip or deflate body is stored inflated; one in another Content-Encoding, such as br, is null. A multipart body is stored as multipart text: each part's headers and the value of a text part, and for any other part, such as a file, the line [Lustro did not store this part: <type>, <n> bytes] in place of its content."
                },
                "requestBodyTruncated": {
                  "type": "boolean"
                },
                "requestBodyBinary": {
                  "type": "boolean",
                  "description": "Detail only. true when the request body was kept as bytes, as it arrived, because the Redactor can't read it: an image. requestBody is then null; fetch the bytes from GET transactions/{id}/body/request."
                },
                "responseHeaders": {
                  "type": ["object", "null"],
                  "additionalProperties": {
                    "type": "string"
                  }
                },
                "responseBody": {
                  "type": ["string", "null"],
                  "description": "The body as redacted text. A gzip or deflate body is stored inflated; one in another Content-Encoding, such as br, is null."
                },
                "responseBodyTruncated": {
                  "type": "boolean"
                },
                "responseBodyBinary": {
                  "type": "boolean",
                  "description": "Detail only. true when the response body was kept as bytes, as it arrived, because the Redactor can't read it: an image. responseBody is then null; fetch the bytes from GET transactions/{id}/body/response."
                },
                "matchingMockRuleIds": {
                  "type": "array",
                  "items": {
                    "type": "string"
                  },
                  "description": "Detail only. The ids of the mock rules whose urlPattern and method match this request, enabled or not, in the order they are tried; the first enabled one is activeMockRuleId. Matched as activeMockRuleId is, and empty for a platform transaction. Since protocol 1.9."
                },
                "priorResponses": {
                  "type": "array",
                  "items": ${ref("PriorResponse")},
                  "description": "Detail only. The responses the client answered with another request before the final one, oldest first: the redirects OkHttp followed and the authentication challenges it answered. The first one's url is url, and the response came from finalUrl. Empty when there were none, and for platform HttpURLConnection capture, which reports only finalUrl. Since protocol 1.5."
                }
              }
            }
        """,
        "PriorResponse" to """
            {
              "type": "object",
              "required": ["url", "statusCode"],
              "properties": {
                "url": {
                  "type": "string",
                  "description": "The URL of the request this response answered. Redacted at capture time."
                },
                "statusCode": {
                  "type": "integer",
                  "description": "Such as 301 for a redirect, or 401 for an authentication challenge."
                }
              },
              "additionalProperties": false
            }
        """,
        "WebSocketCursorEnvelope" to """
            {
              "type": "object",
              "required": ["cursor", "status"],
              "properties": {
                "cursor": {
                  "type": "string",
                  "description": "Opaque cursor token."
                },
                "status": {
                  "type": "string",
                  "enum": ["delta", "unchanged", "reset"]
                },
                "items": {
                  "type": "array",
                  "items": ${ref("WebSocketConnection")},
                  "description": "Newest first."
                },
                "state": {"${'$'}ref": "#/components/schemas/TransactionCursorEnvelope/properties/state"}
              }
            }
        """,
        "WebSocketConnection" to """
            {
              "type": "object",
              "required": ["id", "url", "state"],
              "properties": {
                "id": {
                  "type": "string"
                },
                "url": {
                  "type": "string",
                  "description": "The URL the app asked for, with the ws or wss scheme. Redacted at capture time. The handshake transaction has the same URL with http or https, as OkHttp holds it."
                },
                "state": {
                  "type": "string",
                  "enum": ["connecting", "open", "closing", "closed", "failed"],
                  "description": "connecting until the handshake response arrives; closing once either side sent a close frame; closed after both did; failed after an error, a refused handshake, or cancel()."
                },
                "timestamp": {
                  "type": "string",
                  "description": "startedAt as HH:mm:ss.SSS in the device's time zone. For display; sort and correlate by startedAt."
                },
                "startedAt": {
                  "type": "integer",
                  "description": "When the app created the socket, in milliseconds since the Unix epoch."
                },
                "openedAt": {
                  "type": ["integer", "null"],
                  "description": "When the handshake response arrived; null before that, and for a socket that never opened."
                },
                "closedAt": {
                  "type": ["integer", "null"],
                  "description": "When the socket closed or failed; null before that."
                },
                "statusCode": {
                  "type": ["integer", "null"],
                  "description": "The status of the handshake response: 101, or the status of the response that refused the upgrade. null before a response, and when the socket failed without one."
                },
                "closeCode": {
                  "type": ["integer", "null"],
                  "description": "The close code the server sent, or null when it sent none."
                },
                "closeReason": {
                  "type": ["string", "null"]
                },
                "closedBy": {
                  "type": ["string", "null"],
                  "enum": ["app", "server", null],
                  "description": "Whose close frame came first; null when neither side sent one."
                },
                "canceled": {
                  "type": "boolean",
                  "description": "true when the app called cancel() before the socket ended. OkHttp then fails the socket, so state is failed."
                },
                "error": {
                  "type": ["string", "null"],
                  "description": "For a failed socket: the exception it ended with, as class and message. Stored as it is, not redacted."
                },
                "transactionId": {
                  "type": ["string", "null"],
                  "description": "The handshake's transaction, when the interceptor on the same client captured it; else null."
                },
                "categories": {
                  "type": "array",
                  "items": {
                    "type": "string"
                  },
                  "description": "Labels from the configured NetworkClassifier (may be empty)."
                },
                "sentCount": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "Messages the app sent since the connection was listed. A message that send() refused is not counted."
                },
                "sentBytes": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "The payload bytes of those messages."
                },
                "receivedCount": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "Messages the app received since the connection was listed."
                },
                "receivedBytes": {
                  "type": "integer",
                  "minimum": 0
                },
                "storedEvents": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "Events in the log now."
                },
                "evictedEvents": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "Events the log dropped at its limits, oldest first. They are still in the counts."
                },
                "droppedEvents": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "Messages that were never stored, because capture was behind. They are in no count."
                },
                "protocol": {
                  "type": ["string", "null"],
                  "description": "Detail only. The protocol of the handshake response, http/1.1."
                },
                "requestHeaders": {
                  "type": "object",
                  "additionalProperties": {
                    "type": "string"
                  },
                  "description": "Detail only. The headers the app set on the request, redacted. The handshake transaction also has the headers OkHttp adds."
                },
                "responseHeaders": {
                  "type": ["object", "null"],
                  "additionalProperties": {
                    "type": "string"
                  },
                  "description": "Detail only. The headers of the handshake response, redacted; null before a response."
                }
              }
            }
        """,
        "WebSocketEventStreamEnvelope" to """
            {
              "type": "object",
              "required": ["cursor", "status"],
              "properties": {
                "cursor": {
                  "type": "string",
                  "description": "Opaque cursor token."
                },
                "status": {
                  "type": "string",
                  "enum": ["delta", "unchanged", "reset"]
                },
                "items": {
                  "type": "array",
                  "items": ${ref("WebSocketEvent")},
                  "description": "Oldest first. For reset, the last events of the log; for delta, the events after the cursor."
                },
                "dropped": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "For delta: events the log evicted before this client got them."
                }
              }
            }
        """,
        "WebSocketEvent" to """
            {
              "type": "object",
              "description": "One entry of a connection's log: a message, or a lifecycle event. An event has only the keys of its kind.",
              "required": ["seq", "at", "kind"],
              "properties": {
                "seq": {
                  "type": "integer",
                  "minimum": 1,
                  "description": "The event's number in its connection, in the order the events happened. A number is never used again, and one can be missing, such as that of a message that was not captured."
                },
                "at": {
                  "type": "integer",
                  "description": "When the event happened, in milliseconds since the Unix epoch. For a sent message, when the app called send()."
                },
                "timestamp": {
                  "type": "string",
                  "description": "at as HH:mm:ss.SSS in the device's time zone. For display."
                },
                "kind": {
                  "type": "string",
                  "enum": ["open", "message", "close", "closed", "cancel", "failure"],
                  "description": "open = the handshake response arrived. message = a text or binary message. close = a close frame: with direction sent, the app called close(); with direction received, the server's frame arrived. closed = both sides closed. cancel = the app called cancel(). failure = the socket failed."
                },
                "direction": {
                  "type": "string",
                  "enum": ["sent", "received"],
                  "description": "For message and close."
                },
                "type": {
                  "type": "string",
                  "enum": ["text", "binary"],
                  "description": "For message."
                },
                "payloadBytes": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "For message: the size of the whole payload, in UTF-8 for text, also when only its first part is stored."
                },
                "stored": {
                  "type": "boolean",
                  "description": "For message: false when no payload is stored, because the app's Redactor left it out or failed on it."
                },
                "truncated": {
                  "type": "boolean",
                  "description": "For message: true when the payload passed the capture cap, so only its first part is stored."
                },
                "preview": {
                  "type": "string",
                  "description": "For a text message with a stored payload: its first 512 characters, redacted."
                },
                "previewComplete": {
                  "type": "boolean",
                  "description": "With preview: true when it is the whole stored text. When false, GET websockets/{id}/events/{seq}/payload has all of it."
                },
                "hexPreview": {
                  "type": "string",
                  "description": "For a binary message with a stored payload: its first 32 bytes in hexadecimal. The Redactor's default stores a binary payload as it arrived."
                },
                "enqueued": {
                  "type": "boolean",
                  "description": "For a sent message and a sent close: what send() or close() returned. true means that OkHttp queued it, which does not show that the server received it. false means that OkHttp refused it, so it was not sent."
                },
                "code": {
                  "type": ["integer", "null"],
                  "description": "For close and closed: the close code."
                },
                "reason": {
                  "type": ["string", "null"],
                  "description": "For close and closed: the close reason."
                },
                "statusCode": {
                  "type": ["integer", "null"],
                  "description": "For open: the status of the handshake response. For failure: the status of a response that refused the upgrade, or null."
                },
                "error": {
                  "type": ["string", "null"],
                  "description": "For failure: the exception, as class and message."
                }
              }
            }
        """,
        "HarDocument" to """
            {
              "type": "object",
              "description": "A HAR 1.2 document. Only the parts the export writes are described; the HAR 1.2 specification describes the rest.",
              "required": ["log"],
              "properties": {
                "log": {
                  "type": "object",
                  "required": ["version", "creator", "entries"],
                  "properties": {
                    "version": {
                      "const": "1.2"
                    },
                    "creator": {
                      "type": "object",
                      "required": ["name", "version"],
                      "properties": {
                        "name": {
                          "const": "Lustro"
                        },
                        "version": {
                          "type": "string",
                          "description": "The library version."
                        }
                      }
                    },
                    "entries": {
                      "type": "array",
                      "items": ${ref("HarEntry")},
                      "description": "Oldest first."
                    }
                  }
                }
              }
            }
        """,
        "HarEntry" to """
            {
              "type": "object",
              "required": ["startedDateTime", "time", "request", "response", "cache", "timings", "_resourceType", "_lustro"],
              "properties": {
                "startedDateTime": {
                  "type": "string",
                  "description": "startedAt in ISO 8601, in UTC with milliseconds, e.g. 2026-09-28T14:22:09.003Z."
                },
                "time": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "durationMs plus throttledMs; 0 while the request is in flight, apart from a throttle it has already waited."
                },
                "request": {
                  "type": "object",
                  "required": ["method", "url", "httpVersion", "cookies", "headers", "queryString", "headersSize", "bodySize"],
                  "properties": {
                    "method": {
                      "type": "string"
                    },
                    "url": {
                      "type": "string",
                      "description": "Redacted at capture time."
                    },
                    "httpVersion": {
                      "type": "string",
                      "description": "protocol, e.g. h2; empty when it is null."
                    },
                    "cookies": {
                      "type": "array",
                      "maxItems": 0,
                      "description": "Always empty; the Cookie header is in headers."
                    },
                    "headers": {
                      "type": "array",
                      "items": ${ref("HarNameValue")}
                    },
                    "queryString": {
                      "type": "array",
                      "items": ${ref("HarNameValue")},
                      "description": "The URL's query parameters, decoded."
                    },
                    "postData": {
                      "type": "object",
                      "description": "Present when the request body was kept, as text or as bytes.",
                      "required": ["mimeType", "text"],
                      "properties": {
                        "mimeType": {
                          "type": "string",
                          "description": "requestContentType; empty when it is null."
                        },
                        "text": {
                          "type": "string",
                          "description": "The redacted text, or the bytes in base64 when _encoding is base64."
                        },
                        "_encoding": {
                          "const": "base64",
                          "description": "Present when the body was kept as bytes."
                        }
                      }
                    },
                    "headersSize": {
                      "const": -1
                    },
                    "bodySize": {
                      "type": "integer",
                      "description": "requestBodyBytes; -1 when it is null."
                    }
                  }
                },
                "response": {
                  "type": "object",
                  "required": ["status", "statusText", "httpVersion", "cookies", "headers", "content", "redirectURL", "headersSize", "bodySize"],
                  "properties": {
                    "status": {
                      "type": "integer",
                      "description": "statusCode; 0 before a response and for a failed request."
                    },
                    "statusText": {
                      "const": "",
                      "description": "The capture keeps no reason phrase."
                    },
                    "httpVersion": {
                      "type": "string"
                    },
                    "cookies": {
                      "type": "array",
                      "maxItems": 0,
                      "description": "Always empty; the Set-Cookie header is in headers."
                    },
                    "headers": {
                      "type": "array",
                      "items": ${ref("HarNameValue")}
                    },
                    "content": {
                      "type": "object",
                      "required": ["size", "mimeType"],
                      "properties": {
                        "size": {
                          "type": "integer",
                          "minimum": 0,
                          "description": "The larger of responseBodyBytes and the size of the body kept, which is more for a body kept inflated."
                        },
                        "mimeType": {
                          "type": "string",
                          "description": "responseContentType, or x-unknown when it is null."
                        },
                        "text": {
                          "type": "string",
                          "description": "The redacted text, or the bytes in base64 when encoding is base64. Absent when no body was kept."
                        },
                        "encoding": {
                          "const": "base64"
                        }
                      }
                    },
                    "redirectURL": {
                      "type": "string",
                      "description": "The Location header, or empty."
                    },
                    "headersSize": {
                      "const": -1
                    },
                    "bodySize": {
                      "type": "integer",
                      "description": "responseBodyBytes; -1 when it is null."
                    },
                    "_error": {
                      "type": "string",
                      "description": "error, for a failed request, where Chrome's own export puts it."
                    }
                  }
                },
                "cache": {
                  "type": "object",
                  "maxProperties": 0
                },
                "_resourceType": {
                  "type": "string",
                  "enum": ["fetch", "image", "websocket"],
                  "description": "How Chrome DevTools files the request on import: websocket for the handshake of a listed WebSocket, image for an image/* response, else fetch."
                },
                "_webSocketMessages": {
                  "type": "array",
                  "description": "On the handshake of a listed WebSocket: the messages in its log, oldest first, in the form Chrome DevTools writes and reads. A message that send() refused is left out, because it was not sent. The connection's state and counts are in _lustro.webSocket.",
                  "items": {
                    "type": "object",
                    "required": ["type", "time", "opcode", "data"],
                    "properties": {
                      "type": {
                        "type": "string",
                        "enum": ["send", "receive"]
                      },
                      "time": {
                        "type": "number",
                        "description": "When the message was sent or received, in seconds since the Unix epoch."
                      },
                      "opcode": {
                        "type": "integer",
                        "enum": [1, 2],
                        "description": "1 for a text message, 2 for a binary one."
                      },
                      "data": {
                        "type": "string",
                        "description": "The redacted text, or the bytes in base64 when opcode is 2. Empty when no payload is stored."
                      },
                      "_payloadBytes": {
                        "type": "integer",
                        "minimum": 0,
                        "description": "The size of the whole payload."
                      },
                      "_truncated": {
                        "const": true,
                        "description": "Present when data has only the first part of the payload."
                      },
                      "_stored": {
                        "const": false,
                        "description": "Present when no payload is stored."
                      }
                    }
                  }
                },
                "timings": {
                  "type": "object",
                  "required": ["send", "wait", "receive"],
                  "properties": {
                    "blocked": {
                      "type": "integer",
                      "minimum": -1,
                      "description": "throttledMs, or -1 when the request wasn't throttled."
                    },
                    "dns": {
                      "const": -1
                    },
                    "connect": {
                      "const": -1
                    },
                    "ssl": {
                      "const": -1
                    },
                    "send": {
                      "const": 0
                    },
                    "wait": {
                      "type": "integer",
                      "minimum": 0,
                      "description": "durationMs; 0 while the request is in flight."
                    },
                    "receive": {
                      "const": 0
                    }
                  }
                },
                "_lustro": {
                  "type": "object",
                  "description": "What HAR has no field for. HAR reserves names that start with an underscore for a tool's own fields.",
                  "required": ["id", "isMocked", "source", "categories", "requestBodyTruncated", "responseBodyTruncated", "responseComplete", "finalUrl", "priorResponses", "error"],
                  "properties": {
                    "id": {
                      "type": "string",
                      "description": "The transaction id."
                    },
                    "isMocked": {
                      "type": "boolean"
                    },
                    "source": {
                      "type": "string",
                      "enum": ["okhttp", "platform", "app"],
                      "description": "As the transaction's source. Since protocol 1.6."
                    },
                    "categories": {
                      "type": "array",
                      "items": {
                        "type": "string"
                      }
                    },
                    "requestBodyTruncated": {
                      "type": "boolean",
                      "description": "true when postData has only the first part of the body."
                    },
                    "responseBodyTruncated": {
                      "type": "boolean",
                      "description": "true when content has only the first part of the body."
                    },
                    "responseComplete": {
                      "type": "boolean",
                      "description": "false while the request is in flight, including a streaming response."
                    },
                    "finalUrl": {
                      "type": ["string", "null"],
                      "description": "As the transaction's finalUrl. response.redirectURL is the Location of a redirect the entry itself is, and a redirect the client followed has no entry of its own. Since protocol 1.5."
                    },
                    "priorResponses": {
                      "type": "array",
                      "items": ${ref("PriorResponse")},
                      "description": "As the transaction's priorResponses. Since protocol 1.5."
                    },
                    "error": {
                      "type": ["string", "null"]
                    },
                    "webSocket": {
                      "type": "object",
                      "description": "On the handshake of a listed WebSocket: the connection, as GET websockets/{id} describes its fields.",
                      "required": ["id", "url", "state"],
                      "properties": {
                        "id": {
                          "type": "string"
                        },
                        "url": {
                          "type": "string"
                        },
                        "state": {
                          "type": "string",
                          "enum": ["connecting", "open", "closing", "closed", "failed"]
                        },
                        "closeCode": {
                          "type": ["integer", "null"]
                        },
                        "closeReason": {
                          "type": ["string", "null"]
                        },
                        "closedBy": {
                          "type": ["string", "null"]
                        },
                        "canceled": {
                          "type": "boolean"
                        },
                        "error": {
                          "type": ["string", "null"]
                        },
                        "sentCount": {
                          "type": "integer"
                        },
                        "receivedCount": {
                          "type": "integer"
                        },
                        "evictedEvents": {
                          "type": "integer",
                          "description": "Events the log no longer has, so _webSocketMessages can miss the socket's first messages."
                        },
                        "droppedEvents": {
                          "type": "integer"
                        }
                      }
                    }
                  }
                }
              }
            }
        """,
        "HarNameValue" to """
            {
              "type": "object",
              "required": ["name", "value"],
              "properties": {
                "name": {
                  "type": "string"
                },
                "value": {
                  "type": "string"
                }
              }
            }
        """,
        "MockRule" to """
            {
              "type": "object",
              "required": ["id", "urlPattern", "statusCode", "enabled"],
              "properties": {
                "id": {
                  "type": "string"
                },
                "name": {
                  "type": "string"
                },
                "urlPattern": {
                  "type": "string",
                  "description": "Looked for anywhere in the URL: a substring, or a regular expression when prefixed with regex: (anchor it with ^ and ${'$'} to match the whole URL)."
                },
                "method": {
                  "type": ["string", "null"],
                  "description": "null matches any method."
                },
                "statusCode": {
                  "type": "integer"
                },
                "responseHeaders": {
                  "type": "object",
                  "additionalProperties": {
                    "type": "string"
                  },
                  "description": "Headers applied to the mocked response (e.g. Content-Type)."
                },
                "responseBody": {
                  "type": "string"
                },
                "failure": {
                  "type": ["string", "null"],
                  "description": "The connection error the rule fails a matching request with, in place of a response: timeout, unknownHost, connectionRefused, connectionReset, or tls. null for a rule that answers with statusCode, responseHeaders, and responseBody, which a failure rule ignores. Since protocol 1.12; a later protocol can add names."
                },
                "enabled": {
                  "type": "boolean"
                },
                "hitCount": {
                  "type": "integer",
                  "description": "Runtime-only; not persisted."
                }
              }
            }
        """,
        "MockRuleInput" to """
            {
              "type": "object",
              "required": ["urlPattern"],
              "properties": {
                "id": {
                  "type": "string",
                  "description": "Optional; generated when omitted. Supplying a stable id makes the write idempotent."
                },
                "name": {
                  "type": "string"
                },
                "urlPattern": {
                  "type": "string",
                  "description": "Looked for anywhere in the URL: a substring, or a regular expression when prefixed with regex: (anchor it with ^ and ${'$'} to match the whole URL). Rejected with 400 when blank, or when a regex: pattern is empty or does not compile."
                },
                "method": {
                  "type": ["string", "null"]
                },
                "statusCode": {
                  "type": "integer",
                  "default": 200,
                  "minimum": 100,
                  "maximum": 599,
                  "description": "Rejected with 400 outside 100-599."
                },
                "responseHeaders": {
                  "type": "object",
                  "additionalProperties": {
                    "type": "string"
                  },
                  "description": "Headers applied to the mocked response (e.g. Content-Type). Names and values must be ones OkHttp accepts and a Content-Type must parse as a media type; otherwise 400. Replaced wholesale, so send them again when editing a rule by id."
                },
                "responseBody": {
                  "type": "string"
                },
                "failure": {
                  "type": ["string", "null"],
                  "enum": ["timeout", "unknownHost", "connectionRefused", "connectionReset", "tls", null],
                  "default": null,
                  "description": "Fails a matching request with a connection error instead of answering it: Lustro's OkHttp interceptor throws the exception OkHttp throws for that error, SocketTimeoutException, UnknownHostException, ConnectException, SocketException (Connection reset), or SSLHandshakeException, and records the transaction as failed, with error, isMocked true, and the rule's mockRuleId. statusCode, responseHeaders, and responseBody are ignored then. The request fails at once; set a throttle to fail it after a wait. Omit it, or send null, for a rule that answers. Any other value is a 400 with field failure. Replaced wholesale, as enabled is. Since protocol 1.12."
                },
                "enabled": {
                  "type": "boolean",
                  "default": true,
                  "description": "Replaced wholesale, so send it again when editing a rule by id or the rule comes back enabled."
                }
              }
            }
        """,
    )
