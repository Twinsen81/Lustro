@file:OptIn(ExperimentalDebugRoutes::class)

package io.github.twinsen81.lustro.work.internal

import io.github.twinsen81.lustro.DebugRoutes.Companion.ref
import io.github.twinsen81.lustro.ExperimentalDebugRoutes

/**
 * The text of the WorkManager tab's OpenAPI document that the route declarations in
 * [WorkRoutes] do not make: the descriptions and the response schemas. [WORK_SCHEMAS] has the
 * shared schemas.
 */
internal object WorkDocument {
    const val TITLE: String = "Lustro WorkManager debug tab"

    const val DESCRIPTION: String =
        "The app's WorkManager work: every work request that WorkManager knows, with its state, tags, attempts, " +
        "output, progress, constraints, and schedule; the events of WorkManager's execution and schedule listeners " +
        "(WorkManager 2.12.0 and newer), with the result, the output, the stop reason, and the exception of each run; " +
        "the history of work-analytics when the app has it; and cancel and prune. The events need the app's " +
        "WorkManager configuration to go through WorkDebugTab.configure(), so the app initializes WorkManager on " +
        "demand; the routes' state says whether it does. The tab records the events from Lustro.start() to " +
        "Lustro.stop() and keeps them on the app's heap, so they end with the process. The tab never initializes " +
        "WorkManager. Without WorkManager 2.12.0 or newer, every route answers 503 with code work_unsupported; the " +
        "message names the app's WorkManager version when the APK has its version file " +
        "(META-INF/androidx.work_work-runtime.version), which a build can leave out. All routes are " +
        "token-authenticated (Bearer header or HttpOnly cookie) and live under /api/v1/work. Errors use the shared " +
        "error envelope."

    const val CURSOR: String = "Opaque cursor from the previous response. Omit on the first poll."

    const val ITEMS_SUMMARY: String =
        "Poll the app's work: every work request that WorkManager knows, with its state now."

    const val ITEMS_DESCRIPTION: String =
        "A cursor envelope. The list is the snapshot of one moment, from WorkManager.getWorkInfos, so the cursor " +
        "stands for its content: a poll with the returned cursor and the same filters gets status=unchanged while " +
        "every item stays the same, and status=delta with the whole list after any change. WorkManager keeps finished " +
        "work until it prunes it: at the next start of the app's process after a day, or on POST prune. 503 with code " +
        "work_not_initialized while the app has not initialized WorkManager."

    const val ITEMS_STATE: String =
        "One or more WorkState names, comma-separated, in any case: FAILED,CANCELLED. 400 with field state for any " +
        "other value."

    const val ITEMS_TAG: String = "Only work with this tag."

    const val ITEMS_CLASS: String =
        "Only work of this worker class: its full name, or its simple name (the part after the last . or \$)."

    const val ITEMS_NAME: String =
        "Only the work of this unique work name, registered or not. The items then carry the name."

    const val ITEMS_RESPONSE: String = "Cursor envelope."

    const val ITEM_SUMMARY: String = "One work request, with its events and, with work-analytics, its history."

    const val ITEM_DESCRIPTION: String =
        "item is null when WorkManager no longer has the work, such as after a prune, while the tab still has events " +
        "of it. 404 with field id when neither has it. 503 with code work_not_initialized while the app has not " +
        "initialized WorkManager."

    const val ITEM_ID: String = "The work's id. 400 with field id when it is not a UUID."

    const val ITEM_RESPONSE: String = "The work."

    val ITEM_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["item", "events", "state"],
          "properties": {
            "item": {
              "oneOf": [
                ${ref("WorkItem")},
                {
                  "type": "null"
                }
              ]
            },
            "events": {
              "type": "array",
              "items": ${ref("WorkEvent")},
              "description": "The events of the work that the buffer still holds, oldest first."
            },
            "metrics": {
              "type": "array",
              "items": ${ref("WorkPeriod")},
              "description": "The periods of the work in work-analytics, oldest first. Only when the app passed a repository."
            },
            "state": ${ref("WorkTabState")}
          },
          "additionalProperties": false
        }
    """

    const val EVENTS_SUMMARY: String =
        "Poll the events of WorkManager's listeners: the events after the cursor, filtered."

    const val EVENTS_DESCRIPTION: String =
        "A stream envelope. The first poll gets status=reset with the last events that pass the filters, at most " +
        "limit of them. A poll with the returned cursor gets status=delta with only the events after it, oldest " +
        "first, or status=unchanged. When more than limit events are new, the delta has the oldest limit of them, and " +
        "the next poll goes on from there. The filters leave events out of items; the cursor still moves past them. " +
        "The tab records the events from Lustro.start() to Lustro.stop(), also in the background, and keeps them on " +
        "the heap, so the events of a process that the system started in the background and stopped are lost; " +
        "work-analytics keeps a history on disk. state.listeners says whether there are events at all."

    const val EVENTS_ID: String = "Only the events of the work with this id. 400 with field id when it is not a UUID."

    const val EVENTS_CLASS: String = "Only events of this worker class: its full name, or its simple name."

    const val EVENTS_TYPE: String =
        "One or more event types, comma-separated: finished,exception. 400 with field type for any other value."

    const val EVENTS_LIMIT: String =
        "The most events in one response. 400 when it is not a whole number from 1 to 5000."

    const val EVENTS_RESPONSE: String = "Stream envelope."

    const val METRICS_SUMMARY: String =
        "The history that work-analytics keeps on disk, also of the runs of earlier processes."

    const val METRICS_DESCRIPTION: String =
        "Only when the app passed its WorkMetricsInfoRepository to WorkDebugTab.createWithMetrics: otherwise 404 with " +
        "code metrics_off. work-analytics keeps the periods for 7 days by default. Oldest first, by enqueue time."

    const val METRICS_ID: String = "Only the periods of the work with this id. 400 with field id when it is not a UUID."

    const val METRICS_TAG: String = "Only periods of work with this tag."

    const val METRICS_CLASS: String = "Only periods of this worker class: its full name, or its simple name."

    const val METRICS_STATE: String =
        "One or more WorkMetricsInfo.State names, comma-separated, in any case. 400 with field state for any other " +
        "value."

    const val METRICS_FROM: String =
        "Only periods enqueued at or after this time, in milliseconds since the epoch. 400 with field from when it is " +
        "not a whole number."

    const val METRICS_TO: String =
        "Only periods enqueued at or before this time, in milliseconds since the epoch. 400 with field to when it is " +
        "not a whole number, or is before from."

    const val METRICS_RESPONSE: String = "The periods."

    val METRICS_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["items"],
          "properties": {
            "items": {
              "type": "array",
              "items": ${ref("WorkPeriod")}
            }
          },
          "additionalProperties": false
        }
    """

    const val CANCEL_SUMMARY: String = "Cancel work by its id, its unique work name, or a tag."

    const val CANCEL_DESCRIPTION: String =
        "The body names exactly one of id, name, and tag, as WorkManager's cancelWorkById, cancelUniqueWork, and " +
        "cancelAllWorkByTag take them. WorkManager stops the work that runs, leaves finished work as it is, and " +
        "cancels the work that depends on the cancelled work. In an app that sets a work-analytics 2.12.0 repository " +
        "as its listeners, cancelling work that runs ends the app's process, through a bug of work-analytics, unless " +
        "the app catches it, as the sample does. 400 with the field for any other key. 404 with the field when no " +
        "work matches. 503 with code work_not_initialized while the app has not initialized WorkManager."

    const val CANCEL_BODY_SCHEMA: String = """
        {
          "type": "object",
          "properties": {
            "id": {
              "type": "string",
              "format": "uuid"
            },
            "name": {
              "type": "string",
              "minLength": 1
            },
            "tag": {
              "type": "string",
              "minLength": 1
            }
          },
          "minProperties": 1,
          "maxProperties": 1,
          "additionalProperties": false
        }
    """

    const val CANCEL_RESPONSE: String = "WorkManager cancelled the work."

    const val CANCEL_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["status", "cancelled"],
          "properties": {
            "status": {
              "const": "ok"
            },
            "cancelled": {
              "type": "integer",
              "minimum": 0,
              "description": "How many of the matched work requests were not finished, which WorkManager cancelled; the dependent work is not counted."
            }
          },
          "additionalProperties": false
        }
    """

    const val PRUNE_SUMMARY: String = "Delete finished work, as WorkManager.pruneWork() does."

    const val PRUNE_DESCRIPTION: String =
        "WorkManager deletes the succeeded, failed, and cancelled work that no unfinished work depends on. The events " +
        "in the tab's buffer stay. 503 with code work_not_initialized while the app has not initialized WorkManager."

    const val PRUNE_RESPONSE: String = "WorkManager pruned the work."

    const val PRUNE_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["status", "pruned"],
          "properties": {
            "status": {
              "const": "ok"
            },
            "pruned": {
              "type": "integer",
              "minimum": 0,
              "description": "How many finished work requests are gone."
            }
          },
          "additionalProperties": false
        }
    """
}

/**
 * The shared schemas of the WorkManager tab's OpenAPI document, by name, in the order of
 * `components/schemas`. The responses and the other schemas refer to them with [ref].
 */
internal val WORK_SCHEMAS: List<Pair<String, String>> =
    listOf(
        "WorkState" to """
            {
              "type": "string",
              "enum": ["ENQUEUED", "RUNNING", "SUCCEEDED", "FAILED", "BLOCKED", "CANCELLED"],
              "description": "WorkInfo.State, by its name."
            }
        """,
        "WorkData" to """
            {
              "type": "object",
              "description": "The values of a WorkManager Data object, by key, sorted by key. A boolean, a number, or a string is that JSON value, and an array of them is an array. A long beyond 2^53 - 1 (in either direction), a NaN, and an infinity are strings.",
              "additionalProperties": {
                "type": ["boolean", "number", "string", "array", "null"]
              }
            }
        """,
        "StopReason" to """
            {
              "type": "object",
              "required": ["code", "name"],
              "properties": {
                "code": {
                  "type": "integer",
                  "description": "The value of a WorkInfo.STOP_REASON_* constant, as WorkManager reports it."
                },
                "name": {
                  "type": "string",
                  "enum": ["NOT_STOPPED", "UNKNOWN", "FOREGROUND_SERVICE_TIMEOUT", "CANCELLED_BY_APP", "PREEMPT", "TIMEOUT", "DEVICE_STATE", "CONSTRAINT_BATTERY_NOT_LOW", "CONSTRAINT_CHARGING", "CONSTRAINT_CONNECTIVITY", "CONSTRAINT_DEVICE_IDLE", "CONSTRAINT_STORAGE_NOT_LOW", "QUOTA", "BACKGROUND_RESTRICTION", "APP_STANDBY", "USER", "SYSTEM_PROCESSING", "ESTIMATED_APP_LAUNCH_TIME_CHANGED", "OTHER"],
                  "description": "The name of the constant without STOP_REASON_. NOT_STOPPED (-256) when the work was never stopped, UNKNOWN (-512) when WorkManager does not know why, as after the process died, and OTHER for a code that this version of the tab does not know."
                }
              },
              "additionalProperties": false
            }
        """,
        "WorkConstraints" to """
            {
              "type": "object",
              "required": ["network", "requiresCharging", "requiresDeviceIdle", "requiresBatteryNotLow", "requiresStorageNotLow"],
              "properties": {
                "network": {
                  "type": "string",
                  "description": "The NetworkType name, such as NOT_REQUIRED, CONNECTED, or UNMETERED. NOT_REQUIRED when the work needs a NetworkRequest instead: see networkRequest."
                },
                "networkRequest": {
                  "type": "object",
                  "description": "Only when the work needs a NetworkRequest, which Constraints.Builder.setRequiredNetworkRequest sets from API 28 in place of a NetworkType. The names of the NetworkCapabilities constants that the request has, without their TRANSPORT_ and NET_CAPABILITY_ prefixes, in the order of their values; a value that this version of Lustro does not know is its number as a string.",
                  "required": ["transports", "capabilities"],
                  "properties": {
                    "transports": {
                      "type": "array",
                      "description": "Such as WIFI, CELLULAR, ETHERNET, or VPN. Empty when the request takes any transport.",
                      "items": {
                        "type": "string"
                      }
                    },
                    "capabilities": {
                      "type": "array",
                      "description": "Such as INTERNET, NOT_METERED, or VALIDATED.",
                      "items": {
                        "type": "string"
                      }
                    }
                  },
                  "additionalProperties": false
                },
                "requiresCharging": {
                  "type": "boolean"
                },
                "requiresDeviceIdle": {
                  "type": "boolean"
                },
                "requiresBatteryNotLow": {
                  "type": "boolean"
                },
                "requiresStorageNotLow": {
                  "type": "boolean"
                },
                "contentUriTriggers": {
                  "type": "array",
                  "description": "Only when the work has content URI triggers.",
                  "items": {
                    "type": "object",
                    "required": ["uri", "descendants"],
                    "properties": {
                      "uri": {
                        "type": "string"
                      },
                      "descendants": {
                        "type": "boolean"
                      }
                    },
                    "additionalProperties": false
                  }
                }
              },
              "additionalProperties": false
            }
        """,
        "WorkItem" to """
            {
              "type": "object",
              "description": "One work request as WorkManager's WorkInfo has it now.",
              "required": ["id", "workerClass", "state", "tags", "runAttempts", "generation", "stopReason", "output", "progress", "constraints", "initialDelayMillis"],
              "properties": {
                "id": {
                  "type": "string",
                  "format": "uuid"
                },
                "workerClass": {
                  "type": ["string", "null"],
                  "description": "The worker's class name, as WorkInfo.workerClassName has it. null when WorkManager does not know it."
                },
                "state": ${ref("WorkState")},
                "tags": {
                  "type": "array",
                  "items": {
                    "type": "string"
                  },
                  "description": "Sorted. WorkManager adds the worker's class name as a tag of its own."
                },
                "uniqueName": {
                  "type": "string",
                  "description": "The unique work name that the app enqueued the work with. WorkInfo has none, so the tab reads it from WorkManager's own database; when the app's WorkManager does not have the call that reads it, only for a name that the app registered with uniqueNames, or the name that the request asked for with name."
                },
                "prerequisites": {
                  "type": "array",
                  "items": {
                    "type": "string",
                    "format": "uuid"
                  },
                  "minItems": 1,
                  "description": "The ids of the work that this work waits for: the work before it in a chain of beginWith() and then(), sorted. WorkManager runs blocked work when all of it has succeeded, and fails or cancels it when one fails or is cancelled. WorkInfo has none, so the tab reads them from WorkManager's own database, as it reads uniqueName. WorkManager records them when the app enqueues the work, and the tab reads them once for each work. Absent when the work has none, and when the app's WorkManager does not have the call that reads them."
                },
                "runAttempts": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "WorkInfo.runAttemptCount: how often the work started. A retry and a stop by the system each start it again. For periodic work, the attempts of the current period: WorkManager sets the count back to 0 when a period ends, also after a run that succeeded."
                },
                "generation": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "How often the app updated the work with updateWork."
                },
                "stopReason": {
                  "${'$'}ref": "#/components/schemas/StopReason",
                  "description": "Why the last run stopped."
                },
                "output": {
                  "${'$'}ref": "#/components/schemas/WorkData",
                  "description": "The output of finished work; empty before."
                },
                "progress": {
                  "${'$'}ref": "#/components/schemas/WorkData",
                  "description": "The progress that running work set; empty otherwise."
                },
                "constraints": ${ref("WorkConstraints")},
                "initialDelayMillis": {
                  "type": "integer",
                  "minimum": 0
                },
                "periodicity": {
                  "type": "object",
                  "required": ["repeatIntervalMillis", "flexIntervalMillis"],
                  "properties": {
                    "repeatIntervalMillis": {
                      "type": "integer"
                    },
                    "flexIntervalMillis": {
                      "type": "integer"
                    }
                  },
                  "additionalProperties": false,
                  "description": "Only for periodic work."
                },
                "nextScheduleTimeMillis": {
                  "type": "integer",
                  "description": "When WorkManager plans to run the work next, in milliseconds since the epoch. Left out when it has no next run, as for finished work."
                }
              },
              "additionalProperties": false
            }
        """,
        "WorkTabState" to """
            {
              "type": "object",
              "description": "The tab's view of the app's WorkManager, sent with every list and event response.",
              "required": ["listeners", "initialized", "metrics", "uniqueNames", "bufferBytes"],
              "properties": {
                "listeners": {
                  "type": "string",
                  "enum": ["installed", "missing", "unsupported"],
                  "description": "installed: WorkManager runs with the tab's listeners, or will when the app initializes it. missing: the app's WorkManager configuration did not go through configure(), or WorkManager runs with another configuration, so there are no events. unsupported: the app's WorkManager has listener methods or calls that this version of the tab does not know, so the tab set no listeners."
                },
                "reason": {
                  "type": "string",
                  "description": "Why the listeners are not installed, and what to change. Only when they are not."
                },
                "initialized": {
                  "type": "boolean",
                  "description": "Whether the app has initialized WorkManager. Before it does, items, cancel, and prune answer 503 with code work_not_initialized: the tab never initializes WorkManager."
                },
                "metrics": {
                  "type": "boolean",
                  "description": "Whether the app passed a work-analytics WorkMetricsInfoRepository, so that GET metrics answers."
                },
                "uniqueNames": {
                  "type": "array",
                  "items": {
                    "type": "string"
                  },
                  "description": "The unique work names that the app registered; items label their work with the name."
                },
                "bufferBytes": {
                  "type": "integer",
                  "description": "The size of the event buffer."
                }
              },
              "additionalProperties": false
            }
        """,
        "WorkCursorEnvelope" to """
            {
              "type": "object",
              "required": ["cursor", "status", "state"],
              "properties": {
                "cursor": {
                  "type": "string",
                  "description": "Opaque cursor token. Echo it on the next poll with the same filters."
                },
                "status": {
                  "type": "string",
                  "enum": ["delta", "unchanged", "reset"]
                },
                "items": {
                  "type": "array",
                  "items": ${ref("WorkItem")},
                  "description": "Every work item that passes the filters, in the order that WorkManager returns them. Omitted for unchanged."
                },
                "state": ${ref("WorkTabState")}
              },
              "additionalProperties": false
            }
        """,
        "WorkException" to """
            {
              "type": "object",
              "required": ["type", "stackTrace"],
              "properties": {
                "type": {
                  "type": "string",
                  "description": "The throwable's class name."
                },
                "message": {
                  "type": "string",
                  "description": "Only when the throwable has a message. At most 4096 characters."
                },
                "stackTrace": {
                  "type": "string",
                  "description": "As Throwable.printStackTrace writes it, causes included, at most 16384 characters."
                },
                "truncated": {
                  "const": true,
                  "description": "Only when the stack trace or the message was longer and is cut."
                }
              },
              "additionalProperties": false
            }
        """,
        "WorkEvent" to """
            {
              "type": "object",
              "description": "One event of WorkManager's execution or schedule listener, with the work as WorkInfo had it at the event.",
              "required": ["seq", "at", "type", "id", "workerClass", "state", "runAttempts", "generation"],
              "properties": {
                "seq": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "The event's place in the stream of this process, from 0."
                },
                "at": {
                  "type": "integer",
                  "description": "When the tab recorded the event, in milliseconds since the epoch."
                },
                "type": {
                  "type": "string",
                  "enum": ["enqueued", "unblocked", "updated", "cancelled", "prerequisiteFailed", "started", "stopped", "finished", "exception"],
                  "description": "enqueued, unblocked (the work waits for no work before it: WorkManager sends it at the time of enqueued for work that has no prerequisites, or whose prerequisites all succeeded, and else when the last one succeeds), updated (updateWork), cancelled, and prerequisiteFailed (a step before it in the chain failed or was cancelled) come from the schedule listener; started, stopped, finished, and exception from the execution listener. A worker that throws ends its run with exception, not finished. WorkManager also sends cancelled, with the state CANCELLED, for finished work that an enqueue with APPEND_OR_REPLACE deletes because work of its unique name failed or was cancelled."
                },
                "id": {
                  "type": "string",
                  "format": "uuid"
                },
                "workerClass": {
                  "type": ["string", "null"],
                  "description": "The worker's class name, as WorkInfo.workerClassName has it. null when WorkManager does not know it."
                },
                "state": ${ref("WorkState")},
                "runAttempts": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "WorkInfo.runAttemptCount at the event, as runAttempts of an item. The finished event of a run of periodic work has 0: WorkManager sets the count back to 0 before it sends the event. The started event of the run has the attempt."
                },
                "generation": {
                  "type": "integer",
                  "minimum": 0
                },
                "result": {
                  "type": "string",
                  "description": "For finished: success, failure, or retry, from the class name of the worker's ListenableWorker.Result."
                },
                "output": {
                  "${'$'}ref": "#/components/schemas/WorkData",
                  "description": "For finished: the output data of the Result."
                },
                "stopReason": {
                  "${'$'}ref": "#/components/schemas/StopReason",
                  "description": "For stopped: why WorkManager stopped the run."
                },
                "exception": {
                  "${'$'}ref": "#/components/schemas/WorkException",
                  "description": "For exception: what the worker threw."
                },
                "previousGeneration": {
                  "type": "integer",
                  "description": "For updated: the generation before the update."
                }
              },
              "additionalProperties": false
            }
        """,
        "WorkStreamEnvelope" to """
            {
              "type": "object",
              "required": ["cursor", "status", "state"],
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
                  "items": ${ref("WorkEvent")},
                  "description": "Oldest first. For reset, the last events that pass the filters; for delta, the events after the cursor."
                },
                "dropped": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "For delta: events that the buffer evicted before this client got them."
                },
                "state": ${ref("WorkTabState")}
              },
              "additionalProperties": false
            }
        """,
        "WorkPeriod" to """
            {
              "type": "object",
              "description": "One period of a work's history in work-analytics: one run of one-time work, or one interval of periodic work. A time that work-analytics has not seen yet is left out.",
              "required": ["id", "generation", "workerClass", "tags", "state", "runAttempts", "explicitRetries", "stopReasons"],
              "properties": {
                "id": {
                  "type": "string",
                  "format": "uuid"
                },
                "generation": {
                  "type": "integer",
                  "minimum": 0
                },
                "workerClass": {
                  "type": ["string", "null"],
                  "description": "The worker's class name, as WorkInfo.workerClassName has it. null when WorkManager does not know it."
                },
                "tags": {
                  "type": "array",
                  "items": {
                    "type": "string"
                  }
                },
                "state": {
                  "type": "string",
                  "enum": ["ENQUEUED_BLOCKED", "ENQUEUED_PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "OBSOLETE_UPDATED"],
                  "description": "WorkMetricsInfo.State, by its name."
                },
                "enqueueTimeMillis": {
                  "type": "integer",
                  "description": "Milliseconds since the epoch."
                },
                "unblockTimeMillis": {
                  "type": "integer",
                  "description": "Milliseconds since the epoch."
                },
                "firstStartTimeMillis": {
                  "type": "integer",
                  "description": "Milliseconds since the epoch."
                },
                "finishTimeMillis": {
                  "type": "integer",
                  "description": "Milliseconds since the epoch."
                },
                "workerDurationMillis": {
                  "type": "integer",
                  "description": "How long the worker ran in the run that finished the period."
                },
                "totalRuntimeMillis": {
                  "type": "integer",
                  "description": "How long the worker ran in all attempts of the period."
                },
                "runAttempts": {
                  "type": "integer",
                  "minimum": 0
                },
                "explicitRetries": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "How often the worker returned Result.retry()."
                },
                "stopReasons": {
                  "type": "array",
                  "description": "How often each stop reason stopped a run of the period, by code.",
                  "items": {
                    "type": "object",
                    "required": ["code", "name", "count"],
                    "properties": {
                      "code": {
                        "type": "integer"
                      },
                      "name": {
                        "type": "string",
                        "enum": ["NOT_STOPPED", "UNKNOWN", "FOREGROUND_SERVICE_TIMEOUT", "CANCELLED_BY_APP", "PREEMPT", "TIMEOUT", "DEVICE_STATE", "CONSTRAINT_BATTERY_NOT_LOW", "CONSTRAINT_CHARGING", "CONSTRAINT_CONNECTIVITY", "CONSTRAINT_DEVICE_IDLE", "CONSTRAINT_STORAGE_NOT_LOW", "QUOTA", "BACKGROUND_RESTRICTION", "APP_STANDBY", "USER", "SYSTEM_PROCESSING", "ESTIMATED_APP_LAUNCH_TIME_CHANGED", "OTHER"]
                      },
                      "count": {
                        "type": "integer",
                        "minimum": 1
                      }
                    },
                    "additionalProperties": false
                  }
                }
              },
              "additionalProperties": false
            }
        """,
    )
