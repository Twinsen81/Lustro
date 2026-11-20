package io.github.twinsen81.lustro.prefs.internal

import io.github.twinsen81.lustro.DebugRoutes.Companion.ref
import io.github.twinsen81.lustro.ExperimentalDebugRoutes

/**
 * The text of the Preferences tab's OpenAPI document that the route declarations in
 * [PrefsRoutes] do not make: the descriptions, the response schemas, and the shared schemas.
 */
@OptIn(ExperimentalDebugRoutes::class)
internal object PrefsDocument {
    const val TITLE: String = "Lustro Preferences debug tab"

    const val DESCRIPTION: String =
        "The app's SharedPreferences files and Preferences DataStores: the ones that the app registers, each with a " +
        "value policy and a read-only flag, and, with discovery on, the other files in its shared_prefs directory, " +
        "which are read-only by default, and the other Preferences DataStore files in its files/datastore directory, " +
        "which are always read-only. A file's store says which kind it is: shared_prefs or preferences_datastore. The " +
        "tab reads and writes a registered DataStore through the app's own instance; it reads a discovered one from its " +
        ".preferences_pb file, because the app's DataStore would not see an edit to the file. A value policy says what " +
        "the tab shows of the values: visible shows every value; mask_sensitive leaves out the value of a key that " +
        "looks like it holds a secret, such as auth_token or password, as the Network tab's redaction finds them, " +
        "except a boolean, float, or double value, which an app does not use for a secret, and shows a string value " +
        "that is a JSON object or array with the values of its sensitive fields replaced by [REDACTED], at any depth, " +
        "as the Network tab's redaction does in a JSON body; keys_only leaves out every value. A registered file can " +
        "list keys whose values every policy leaves out, and, under mask_sensitive, keys whose values show whole " +
        "although they look sensitive. A file that has the keysets of EncryptedSharedPreferences, read by a plain " +
        "instance, shows its keys only and is read-only. Each file has a cursor that moves when a value changes, " +
        "through the tab or through the app. The tab sees the app's changes to a registered file while Lustro is " +
        "started; before API 30, a clear of a SharedPreferences file by the app shows only with the next change to the " +
        "file. A discovered DataStore file's cursor moves when its bytes change. All routes are token-authenticated " +
        "(Bearer header or HttpOnly cookie) and live under /api/v1/prefs. Errors use the shared error envelope."

    const val FILES_DESCRIPTION: String =
        "The registered SharedPreferences files and then the registered DataStores, in the order the app registered " +
        "them, then the discovered SharedPreferences files and then the discovered DataStore files, each by name. Each " +
        "one reads its file, to count the keys and to find an encrypted one. A file that can't be read, such as an " +
        "EncryptedSharedPreferences whose key is gone, or a .preferences_pb file that is not a Preferences DataStore " +
        "file, is listed with keyCount 0 and the reason in error."

    const val ENTRIES_DESCRIPTION: String =
        "A cursor envelope. The first poll (no cursor, or a cursor of another file or from before an app restart) gets " +
        "status=reset with every entry of the file, by key. A poll with the returned cursor gets status=unchanged with " +
        "no items while the file stays the same, and status=delta with every entry again after a change. A value longer " +
        "than 4096 characters (for a string set, all its strings together; for bytes, their base64) is cut, with " +
        "truncated; GET entry returns it whole. 500 when the file can't be read."

    const val ENTRY_DESCRIPTION: String =
        "The same item as in entries, after the value policy, but never cut. 404 with field key when the file has no " +
        "such key."

    const val EDIT_DESCRIPTION: String =
        "Puts the value with the type's setter and waits until the file on disk has it, so the response shows what the " +
        "file holds: a SharedPreferences file commits, and a DataStore updates its data through the app's instance. It " +
        "adds the key, or replaces its value, also with a value of another type: then the app's getter of the old type " +
        "throws a ClassCastException. The cursor moves when the value changes. A value that is masked or redacted for " +
        "reading can still be written. 403 with code read_only and field file when the file is read-only. 400 with " +
        "field key when the key is empty, and with field key, type, or value when one is missing or the value is not of " +
        "the type, and with field type for a type that the file's store can't write. 500 when the write fails: for a " +
        "SharedPreferences file, the instance's memory holds the value, and the app sees it until the process ends; a " +
        "DataStore keeps what it had."

    const val DELETE_DESCRIPTION: String =
        "Removes the key, and waits until the file on disk has the change. The cursor moves when the key was there. 403 " +
        "with code read_only and field file when the file is read-only."

    const val CLEAR_DESCRIPTION: String =
        "Removes every key of the file, and waits until the file on disk has the change. The cursor moves when the file " +
        "had a key, on every API level. 403 with code read_only and field file when the file is read-only. 400 with " +
        "field confirm unless confirm is true."

    const val STORE_PARAM: String =
        "The file's store, as GET files lists it. Without it, shared_prefs. 400 with field store when it is not one of " +
        "the stores."

    const val FILE: String =
        "The file's name, as GET files lists it. 400 with field file when it is missing or contains / or \\; 404 with " +
        "field file when the store has no file of this name."

    const val CURSOR: String = "Opaque cursor from the previous response for this file. Omit on the first poll."

    const val ENTRY_KEY: String = "The key. It can be empty. 400 with field key when it is missing."

    const val EDIT_KEY: String =
        "The key. It can't be empty: an empty key is most often a client's mistake, such as a shell variable that did " +
        "not expand. POST delete takes an empty key, to remove one that is already there."

    const val DELETE_KEY: String = "The key. It can be empty."

    const val VALUE: String =
        "For string, a JSON string. For int and long, a whole number, or a string of one, in the type's range. For " +
        "float and double, a number, or a string of one, in the type's range, or \"NaN\", \"Infinity\", or " +
        "\"-Infinity\". For boolean, true or false, or a string of one. For string_set, a JSON array of strings; a " +
        "repeated string is kept once. For bytes, a padded base64 string (RFC 4648); white space in it is dropped. null " +
        "is not a value: POST delete removes a key."

    const val VALUE_SCHEMA: String = """
        {
          "type": ["string", "number", "boolean", "array"],
          "items": {
            "type": "string"
          }
        }
    """

    const val CONFIRM: String = "Must be true: clear removes every key of the file."

    const val CONFIRM_SCHEMA: String = """
        {
          "const": true
        }
    """

    const val FILES_RESPONSE: String = "The files."

    val FILES_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["items"],
          "properties": {
            "items": {
              "type": "array",
              "items": ${ref("PrefsFile")}
            }
          },
          "additionalProperties": false
        }
    """

    const val ENTRY_RESPONSE: String = "The entry."

    val ENTRY_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["item"],
          "properties": {
            "item": ${ref("PrefEntry")}
          },
          "additionalProperties": false
        }
    """

    const val EDIT_RESPONSE: String =
        "The entry as the file now holds it, after the value policy. A value longer than 4096 characters is cut, as in " +
        "entries: read it whole with GET entry."

    val EDIT_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["status", "item"],
          "properties": {
            "status": {
              "const": "ok"
            },
            "item": ${ref("PrefEntry")}
          },
          "additionalProperties": false
        }
    """

    const val DELETE_RESPONSE: String = "Removed, or not there."

    const val DELETE_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["status", "deleted"],
          "properties": {
            "status": {
              "const": "ok"
            },
            "deleted": {
              "type": "boolean",
              "description": "Whether the file had the key."
            }
          },
          "additionalProperties": false
        }
    """

    const val CLEAR_RESPONSE: String = "The file is empty."

    const val CLEAR_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["status", "removed"],
          "properties": {
            "status": {
              "const": "ok"
            },
            "removed": {
              "type": "integer",
              "minimum": 0,
              "description": "How many keys the file had."
            }
          },
          "additionalProperties": false
        }
    """

    const val ENTRIES_RESPONSE: String = "Cursor envelope."

    /** The schemas under `components/schemas`, except `ErrorEnvelope`, which every generated document has. */
    val COMPONENTS: List<Pair<String, String>> =
        listOf(
            "ValuePolicy" to """
                {
                  "type": "string",
                  "enum": ["visible", "mask_sensitive", "keys_only"],
                  "description": "What the tab shows of the values. visible: every value. mask_sensitive: every value but those of keys that look like they hold a secret; a boolean, float, or double value always shows. keys_only: no value."
                }
            """,
            "PrefsStore" to """
                {
                  "type": "string",
                  "enum": ["shared_prefs", "preferences_datastore"],
                  "description": "The kind of file. shared_prefs: a SharedPreferences file, shared_prefs/<name>.xml. preferences_datastore: a Preferences DataStore, files/datastore/<name>.preferences_pb for a discovered one."
                }
            """,
            "PrefsFile" to """
                {
                  "type": "object",
                  "required": ["name", "store", "registered", "policy", "readOnly", "encrypted", "keyCount", "cursor"],
                  "properties": {
                    "name": {
                      "type": "string",
                      "description": "The file's name, as the app passes it to getSharedPreferences or preferencesDataStore. With store, it picks the file in the other routes."
                    },
                    "store": ${ref("PrefsStore")},
                    "registered": {
                      "type": "boolean",
                      "description": "true for a file that the app registered, false for one that discovery found."
                    },
                    "policy": ${ref("ValuePolicy")},
                    "readOnly": {
                      "type": "boolean",
                      "description": "Whether edit, delete, and clear answer 403. Always true for a discovered DataStore file."
                    },
                    "encrypted": {
                      "type": "boolean",
                      "description": "Whether the file on disk is encrypted: the app registered an EncryptedSharedPreferences, or the file has its keysets. A file read without its keys is keys_only and read-only. Always false for a DataStore."
                    },
                    "keyCount": {
                      "type": "integer",
                      "minimum": 0
                    },
                    "cursor": {
                      "type": "string",
                      "description": "The file's cursor now. It is the cursor that entries returns, so a client can compare it with the one it polled with, to see that the file changed."
                    },
                    "error": {
                      "type": "string",
                      "description": "Only for a file that can't be read: why."
                    }
                  },
                  "additionalProperties": false
                }
            """,
            "PrefsCursorEnvelope" to """
                {
                  "type": "object",
                  "required": ["cursor", "status"],
                  "properties": {
                    "cursor": {
                      "type": "string",
                      "description": "Opaque cursor token. Echo it on the next poll of the same file."
                    },
                    "status": {
                      "type": "string",
                      "enum": ["delta", "unchanged", "reset"]
                    },
                    "items": {
                      "type": "array",
                      "items": ${ref("PrefEntry")},
                      "description": "Every entry of the file, by key. Omitted for unchanged."
                    }
                  },
                  "additionalProperties": false
                }
            """,
            "PrefType" to """
                {
                  "type": "string",
                  "enum": ["string", "int", "long", "float", "double", "boolean", "string_set", "bytes", "unknown"],
                  "description": "The type of a value: a SharedPreferences getter's, or for a Preferences DataStore also double and bytes (a byte array, DataStore 1.1 and newer). string_set is a Set<String>. unknown is a value that only a custom SharedPreferences implementation can hold, written as its text."
                }
            """,
            "PrefWriteType" to """
                {
                  "type": "string",
                  "enum": ["string", "int", "long", "float", "double", "boolean", "string_set", "bytes"],
                  "description": "The type to write the value with. A shared_prefs file takes string, int, long, float, boolean, and string_set, its setters' types; a preferences_datastore file takes double and bytes too. A DataStore before 1.1 can't write bytes: the edit is then a 500."
                }
            """,
            "PrefEntry" to """
                {
                  "type": "object",
                  "required": ["key", "type", "bytes"],
                  "properties": {
                    "key": {
                      "type": "string"
                    },
                    "type": ${ref("PrefType")},
                    "value": {
                      "description": "Absent when masked, and for an unknown value that is null. When redacted, the string with the values of its sensitive JSON fields replaced. A string, a number, a boolean, or for string_set an array of strings, sorted. A long beyond 2^53 - 1 either way, which a JavaScript number can't hold, is the string of its digits. A float or a double that is not finite is the string \"NaN\", \"Infinity\", or \"-Infinity\". Bytes are a padded base64 string.",
                      "type": ["string", "number", "boolean", "array"],
                      "items": {
                        "type": "string"
                      }
                    },
                    "bytes": {
                      "type": "integer",
                      "minimum": 0,
                      "description": "The size of the whole value: a string's UTF-8 bytes, the sum of those of a set's strings, a byte array's length, or 4 for int and float, 8 for long and double, and 1 for boolean."
                    },
                    "masked": {
                      "const": true,
                      "description": "Only when the value policy leaves the value out."
                    },
                    "redacted": {
                      "const": true,
                      "description": "Only under mask_sensitive, for a string value that is a JSON object or array with a sensitive field: value has the values of those fields, at any depth, replaced by [REDACTED], except a boolean or a null, and is rebuilt from the parse, so it is compact, and a number in it comes as the parser read it: 1.0 as 1, and an integer outside the range of a 64-bit long as a double, with fewer digits. bytes is the size of the value in the file. Never with masked. A client must not write the value back: it would replace the secrets with the placeholder."
                    },
                    "truncated": {
                      "const": true,
                      "description": "Only in entries and in the item of the POST edit response, when the value was cut at 4096 characters; bytes are cut at a whole base64 quantum, so the cut text still decodes. GET entry returns it whole."
                    }
                  },
                  "additionalProperties": false
                }
            """,
        )
}
