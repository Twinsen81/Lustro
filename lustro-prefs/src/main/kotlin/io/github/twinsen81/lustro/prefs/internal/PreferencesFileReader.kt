package io.github.twinsen81.lustro.prefs.internal

/** The bytes are not a Preferences DataStore file. */
internal class MalformedFileException(message: String) : Exception(message)

/**
 * Reads the file of a Preferences DataStore, without DataStore: the protobuf
 * message `PreferenceMap { map<string, Value> preferences = 1; }`, where `Value`
 * is a oneof of bool (1), float (2), int32 (3), int64 (4), string (5),
 * `StringSet { repeated string strings = 1; }` (6), double (7), and bytes (8).
 * The values have the classes that `Preferences.asMap()` gives them.
 */
internal object PreferencesFileReader {
    private const val VARINT = 0
    private const val FIXED64 = 1
    private const val LENGTH = 2
    private const val FIXED32 = 5

    /** @throws MalformedFileException when [bytes] are not such a message, or a value has no type. */
    fun read(bytes: ByteArray): Map<String, Any> {
        val entries = LinkedHashMap<String, Any>()
        val reader = Reader(bytes, 0, bytes.size)
        while (reader.more()) {
            val (field, wire) = reader.tag()
            if (field == 1 && wire == LENGTH) {
                val entry = reader.message()
                var key = ""
                var value: Any? = null
                while (entry.more()) {
                    val (entryField, entryWire) = entry.tag()
                    when {
                        entryField == 1 && entryWire == LENGTH -> key = entry.string()
                        entryField == 2 && entryWire == LENGTH -> value = value(entry.message())
                        else -> entry.skip(entryWire)
                    }
                }
                // A map keeps the last entry of a key, as protobuf does.
                entries[key] = value ?: throw MalformedFileException("The key \"$key\" has no value")
            } else {
                reader.skip(wire)
            }
        }
        return entries
    }

    // The last field of the oneof wins, as protobuf has it; null when none is set.
    private fun value(reader: Reader): Any? {
        var value: Any? = null
        while (reader.more()) {
            val (field, wire) = reader.tag()
            value =
                when {
                    field == 1 && wire == VARINT -> reader.varint() != 0L
                    field == 2 && wire == FIXED32 -> Float.fromBits(reader.fixed32())
                    field == 3 && wire == VARINT -> reader.varint().toInt()
                    field == 4 && wire == VARINT -> reader.varint()
                    field == 5 && wire == LENGTH -> reader.string()
                    field == 6 && wire == LENGTH -> stringSet(reader.message())
                    field == 7 && wire == FIXED64 -> Double.fromBits(reader.fixed64())
                    field == 8 && wire == LENGTH -> reader.bytes()
                    else -> {
                        reader.skip(wire)
                        value
                    }
                }
        }
        return value
    }

    private fun stringSet(reader: Reader): Set<String> {
        val set = LinkedHashSet<String>()
        while (reader.more()) {
            val (field, wire) = reader.tag()
            if (field == 1 && wire == LENGTH) set += reader.string() else reader.skip(wire)
        }
        return set
    }

    private class Reader(private val bytes: ByteArray, private var position: Int, private val end: Int) {
        fun more(): Boolean = position < end

        /** The field number and the wire type of the next field. */
        fun tag(): Pair<Int, Int> {
            val tag = varint()
            val field = (tag ushr 3).toInt()
            if (field <= 0 || tag ushr 3 > Int.MAX_VALUE) throw MalformedFileException("A field number of $field at byte $position")
            return field to (tag and 7L).toInt()
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (shift < Long.SIZE_BITS) {
                val byte = next()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            throw MalformedFileException("A varint longer than 10 bytes at byte $position")
        }

        fun fixed32(): Int {
            var result = 0
            for (index in 0 until Int.SIZE_BYTES) result = result or (next() shl (index * Byte.SIZE_BITS))
            return result
        }

        fun fixed64(): Long {
            var result = 0L
            for (index in 0 until Long.SIZE_BYTES) result = result or (next().toLong() shl (index * Byte.SIZE_BITS))
            return result
        }

        fun string(): String = String(bytes(), Charsets.UTF_8)

        fun bytes(): ByteArray {
            val length = length()
            return bytes.copyOfRange(position, position + length).also { position += length }
        }

        /** The message that the next length-delimited field holds. */
        fun message(): Reader {
            val length = length()
            return Reader(bytes, position, position + length).also { position += length }
        }

        fun skip(wire: Int) {
            when (wire) {
                VARINT -> varint()
                FIXED64 -> advance(Long.SIZE_BYTES)
                LENGTH -> advance(length())
                FIXED32 -> advance(Int.SIZE_BYTES)
                else -> throw MalformedFileException("The wire type $wire at byte $position")
            }
        }

        private fun length(): Int {
            val length = varint()
            if (length < 0 || length > end - position) throw MalformedFileException("A length of $length at byte $position, past the end")
            return length.toInt()
        }

        private fun advance(count: Int) {
            if (count > end - position) throw MalformedFileException("A field past the end at byte $position")
            position += count
        }

        private fun next(): Int {
            if (position >= end) throw MalformedFileException("The data ends inside a field")
            return bytes[position++].toInt() and 0xFF
        }
    }
}
