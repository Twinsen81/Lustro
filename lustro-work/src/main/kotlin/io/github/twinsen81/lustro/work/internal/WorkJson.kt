package io.github.twinsen81.lustro.work.internal

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import io.github.twinsen81.lustro.escapeForJson

/** Writes WorkManager's types as the JSON of the routes. */
internal object WorkJson {
    /** The longest stack trace that an event keeps, in characters. */
    const val MAX_STACK_TRACE_CHARS: Int = 16 * 1024

    // A JavaScript number holds an integer exactly up to 2^53 - 1.
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    /** Appends [info] as a work item. [uniqueName] is the registered unique name that the work belongs to, if any. */
    fun appendItem(out: StringBuilder, info: WorkInfo, uniqueName: String?) {
        out.append("{\"id\":\"").append(info.id.toString()).append('"')
        out.append(",\"workerClass\":")
        appendNullable(out, info.workerClassName)
        out.append(",\"state\":\"").append(info.state.name).append('"')
        out.append(",\"tags\":")
        appendStrings(out, info.tags.sorted())
        if (uniqueName != null) out.append(",\"uniqueName\":\"").append(uniqueName.escapeForJson()).append('"')
        out.append(",\"runAttempts\":").append(info.runAttemptCount)
        out.append(",\"generation\":").append(info.generation)
        out.append(",\"stopReason\":")
        appendStopReason(out, info.stopReason)
        out.append(",\"output\":")
        appendData(out, info.outputData)
        out.append(",\"progress\":")
        appendData(out, info.progress)
        out.append(",\"constraints\":")
        appendConstraints(out, info.constraints)
        out.append(",\"initialDelayMillis\":").append(info.initialDelayMillis)
        info.periodicityInfo?.let {
            out.append(",\"periodicity\":{\"repeatIntervalMillis\":").append(it.repeatIntervalMillis)
            out.append(",\"flexIntervalMillis\":").append(it.flexIntervalMillis).append('}')
        }
        // Long.MAX_VALUE when WorkManager has no next run for the work, as for finished work.
        if (info.nextScheduleTimeMillis != Long.MAX_VALUE) out.append(",\"nextScheduleTimeMillis\":").append(info.nextScheduleTimeMillis)
        out.append('}')
    }

    /** Appends the values of [data] as a JSON object, each value as JSON has it. */
    fun appendData(out: StringBuilder, data: Data) {
        out.append('{')
        data.keyValueMap.entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
            if (index > 0) out.append(',')
            out.append('"').append(key.escapeForJson()).append("\":")
            appendValue(out, value)
        }
        out.append('}')
    }

    /** Appends [code], a `WorkInfo.STOP_REASON_*` value, with its name. */
    fun appendStopReason(out: StringBuilder, code: Int) {
        out.append("{\"code\":").append(code).append(",\"name\":\"").append(stopReasonName(code)).append("\"}")
    }

    /** The name of the `WorkInfo.STOP_REASON_*` constant with the value [code], without the prefix. */
    fun stopReasonName(code: Int): String =
        when (code) {
            WorkInfo.STOP_REASON_NOT_STOPPED -> "NOT_STOPPED"
            WorkInfo.STOP_REASON_UNKNOWN -> "UNKNOWN"
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "FOREGROUND_SERVICE_TIMEOUT"
            WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "CANCELLED_BY_APP"
            WorkInfo.STOP_REASON_PREEMPT -> "PREEMPT"
            WorkInfo.STOP_REASON_TIMEOUT -> "TIMEOUT"
            WorkInfo.STOP_REASON_DEVICE_STATE -> "DEVICE_STATE"
            WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "CONSTRAINT_BATTERY_NOT_LOW"
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "CONSTRAINT_CHARGING"
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "CONSTRAINT_CONNECTIVITY"
            WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "CONSTRAINT_DEVICE_IDLE"
            WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "CONSTRAINT_STORAGE_NOT_LOW"
            WorkInfo.STOP_REASON_QUOTA -> "QUOTA"
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "BACKGROUND_RESTRICTION"
            WorkInfo.STOP_REASON_APP_STANDBY -> "APP_STANDBY"
            WorkInfo.STOP_REASON_USER -> "USER"
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "SYSTEM_PROCESSING"
            WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "ESTIMATED_APP_LAUNCH_TIME_CHANGED"
            // A reason that a newer platform or WorkManager added.
            else -> "OTHER"
        }

    /**
     * The kind of [result]: `success`, `failure`, or `retry`. The subclasses of
     * `ListenableWorker.Result` are restricted API, so the kind comes from the
     * class's name, not from an `is` check.
     */
    fun resultKind(result: ListenableWorker.Result): String = result.javaClass.simpleName.replaceFirstChar { it.lowercaseChar() }

    /** Appends [throwable] with its class, message, and stack trace, cut at [MAX_STACK_TRACE_CHARS]. */
    fun appendException(out: StringBuilder, throwable: Throwable) {
        val trace = throwable.stackTraceToString()
        out.append("{\"type\":\"").append(throwable.javaClass.name.escapeForJson()).append('"')
        throwable.message?.let { out.append(",\"message\":\"").append(it.escapeForJson()).append('"') }
        out.append(",\"stackTrace\":\"").append(trace.take(MAX_STACK_TRACE_CHARS).escapeForJson()).append('"')
        if (trace.length > MAX_STACK_TRACE_CHARS) out.append(",\"truncated\":true")
        out.append('}')
    }

    /** Appends [value] as a JSON string, or `null`. */
    fun appendNullable(out: StringBuilder, value: String?) {
        if (value == null) out.append("null") else out.append('"').append(value.escapeForJson()).append('"')
    }

    fun appendStrings(out: StringBuilder, values: Iterable<String>) {
        out.append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) out.append(',')
            out.append('"').append(value.escapeForJson()).append('"')
        }
        out.append(']')
    }

    private fun appendConstraints(out: StringBuilder, constraints: Constraints) {
        out.append("{\"network\":\"").append(constraints.requiredNetworkType.name).append('"')
        out.append(",\"requiresCharging\":").append(constraints.requiresCharging())
        out.append(",\"requiresDeviceIdle\":").append(constraints.requiresDeviceIdle())
        out.append(",\"requiresBatteryNotLow\":").append(constraints.requiresBatteryNotLow())
        out.append(",\"requiresStorageNotLow\":").append(constraints.requiresStorageNotLow())
        val triggers = constraints.contentUriTriggers
        if (triggers.isNotEmpty()) {
            out.append(",\"contentUriTriggers\":[")
            triggers.forEachIndexed { index, trigger ->
                if (index > 0) out.append(',')
                out.append("{\"uri\":\"").append(trigger.uri.toString().escapeForJson()).append('"')
                out.append(",\"descendants\":").append(trigger.isTriggeredForDescendants).append('}')
            }
            out.append(']')
        }
        out.append('}')
    }

    // Data holds booleans, numbers, strings, and arrays of them. A long beyond what
    // JavaScript holds exactly is a string, as a NaN or an infinity is.
    private fun appendValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Long -> if (value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) out.append(value) else out.append('"').append(value).append('"')
            is Byte, is Int -> out.append(value)
            is Float -> appendDouble(out, value.toDouble())
            is Double -> appendDouble(out, value)
            is String -> out.append('"').append(value.escapeForJson()).append('"')
            is BooleanArray -> appendArray(out, value.size) { appendValue(out, value[it]) }
            is ByteArray -> appendArray(out, value.size) { appendValue(out, value[it]) }
            is IntArray -> appendArray(out, value.size) { appendValue(out, value[it]) }
            is LongArray -> appendArray(out, value.size) { appendValue(out, value[it]) }
            is FloatArray -> appendArray(out, value.size) { appendValue(out, value[it]) }
            is DoubleArray -> appendArray(out, value.size) { appendValue(out, value[it]) }
            is Array<*> -> appendArray(out, value.size) { appendValue(out, value[it]) }
            else -> out.append('"').append(value.toString().escapeForJson()).append('"')
        }
    }

    private fun appendDouble(out: StringBuilder, value: Double) {
        if (value.isNaN() || value.isInfinite()) out.append('"').append(value).append('"') else out.append(value)
    }

    private inline fun appendArray(out: StringBuilder, size: Int, appendAt: (Int) -> Unit) {
        out.append('[')
        for (index in 0 until size) {
            if (index > 0) out.append(',')
            appendAt(index)
        }
        out.append(']')
    }
}
