@file:OptIn(ExperimentalDebugRoutes::class)

package io.github.twinsen81.lustro.sqlite.internal

import io.github.twinsen81.lustro.DebugRoutes.Companion.ref
import io.github.twinsen81.lustro.ExperimentalDebugRoutes

/**
 * The shared schemas of the SQLite tab's OpenAPI document, by name, in the order of
 * `components/schemas`. The responses and the other schemas refer to them with [ref].
 */
internal val SQLITE_SCHEMAS: List<Pair<String, String>> =
    listOf(
        "SqliteSource" to """
            {
              "type": "string",
              "enum": ["framework", "support", "file"],
              "description": "Where the tab reads the database: framework, the app's open SQLiteDatabase; support, the app's open SupportSQLiteDatabase, such as Room's; file, a connection that the tab opens read-only for each request."
            }
        """,
        "SqliteValuePolicy" to """
            {
              "type": "string",
              "enum": ["visible", "mask_sensitive"],
              "description": "What a client sees of the database's values. visible: every value. mask_sensitive: every value but those of the columns whose names look like they hold a secret, such as access_token, clientSecret, or password, by the same name check as the Network tab's redaction (SensitiveKeys.isSensitive). It goes by the name only. In GET rows it goes by the table's column, and in POST query by the result's column name, as the statement names it: SQLite names an expression without an alias by its text, such as length(access_token), which is masked too, but an alias shows the value. The policy keeps secrets off the screen; it does not stop a client that has the token."
            }
        """,
        "SqliteDatabase" to """
            {
              "type": "object",
              "required": ["name", "registered", "source", "writable", "policy", "path", "sizeBytes", "walBytes", "schemaVersion"],
              "properties": {
                "name": {
                  "type": "string",
                  "description": "The database's name: the name that the app registered, or the file's name for a discovered one. The other routes take it as db."
                },
                "registered": {
                  "type": "boolean",
                  "description": "true for a database that the app registered, false for a file that discovery found."
                },
                "source": ${ref("SqliteSource")},
                "writable": {
                  "type": "boolean",
                  "description": "Whether the app lets a client change the database through the tab: POST exec, edit, and delete answer 403 for a database that is not writable. Always false for a file."
                },
                "policy": ${ref("SqliteValuePolicy")},
                "path": {
                  "type": ["string", "null"],
                  "description": "The database file, or null for a database in memory, and for a live handle that does not open."
                },
                "sizeBytes": {
                  "type": ["integer", "null"],
                  "minimum": 0,
                  "description": "The size of the database file, without its -wal file, or null when there is no file."
                },
                "walBytes": {
                  "type": ["integer", "null"],
                  "minimum": 0,
                  "description": "The size of the database's -wal file, or null when it has none: a database out of WAL mode, one in memory, or one whose WAL SQLite has removed. In WAL mode, SQLite writes each change to this file and copies it into the database file at a checkpoint, so until then the -wal file can hold most of the data, and the database file can be a few pages."
                },
                "schemaVersion": {
                  "type": ["integer", "null"],
                  "description": "PRAGMA user_version, which Room and SQLiteOpenHelper set to the schema's version; null when the database does not open."
                },
                "error": {
                  "type": "string",
                  "description": "Only for a database that does not open: why."
                }
              },
              "additionalProperties": false
            }
        """,
        "SqliteTableType" to """
            {
              "type": "string",
              "enum": ["table", "view"]
            }
        """,
        "SqliteTable" to """
            {
              "type": "object",
              "required": ["name", "type", "internal"],
              "properties": {
                "name": {
                  "type": "string"
                },
                "type": ${ref("SqliteTableType")},
                "internal": {
                  "type": "boolean",
                  "description": "Whether SQLite, Android, or Room keeps the table for itself (sqlite_*, android_metadata, room_master_table), or it is a shadow table of a full-text or R*Tree index."
                },
                "hasRowId": {
                  "type": "boolean",
                  "description": "Whether each row has a rowid, which GET rows returns. false for a view and for a table WITHOUT ROWID. Absent when the table can't be read."
                },
                "rowCount": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "How many rows the table has. Absent for a view, and when the table can't be read."
                },
                "entity": {
                  "type": "string",
                  "description": "From the app's hint: the class that maps the table."
                },
                "purpose": {
                  "type": "string",
                  "description": "From the app's hint: what a row is."
                },
                "error": {
                  "type": "string",
                  "description": "Only for a table that can't be read, such as a virtual table whose module this SQLite does not have: why."
                }
              },
              "additionalProperties": false
            }
        """,
        "SqliteColumn" to """
            {
              "type": "object",
              "required": ["name", "type", "notNull", "defaultValue", "primaryKey", "generated", "masked"],
              "properties": {
                "name": {
                  "type": "string"
                },
                "type": {
                  "type": "string",
                  "description": "The declared type, as written in the table's statement; empty when it has none. SQLite stores a value of any type in any column, so a cell's own type is in GET rows."
                },
                "notNull": {
                  "type": "boolean"
                },
                "defaultValue": {
                  "type": ["string", "null"],
                  "description": "The default, as SQL text, such as 0 or 'none'; null when there is none."
                },
                "primaryKey": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "The column's place in the primary key, from 1, or 0 when it is not in it."
                },
                "generated": {
                  "type": "boolean",
                  "description": "Whether SQLite computes the column from the others: GENERATED ALWAYS AS, stored or virtual."
                },
                "masked": {
                  "type": "boolean",
                  "description": "Whether the database's value policy leaves out the column's values: in GET rows, a cell of this column that is not NULL is {type, masked: true}. The console does not edit such a column."
                }
              },
              "additionalProperties": false
            }
        """,
        "SqliteForeignKey" to """
            {
              "type": "object",
              "required": ["table", "from", "to", "onUpdate", "onDelete"],
              "properties": {
                "table": {
                  "type": "string",
                  "description": "The table that the key refers to."
                },
                "from": {
                  "type": "array",
                  "items": {
                    "type": "string"
                  },
                  "description": "The columns of this table, in order."
                },
                "to": {
                  "type": "array",
                  "items": {
                    "type": ["string", "null"]
                  },
                  "description": "The columns of the other table, in the same order. null stands for the other table's primary key column."
                },
                "onUpdate": {
                  "type": "string",
                  "description": "Such as NO ACTION or CASCADE."
                },
                "onDelete": {
                  "type": "string"
                }
              },
              "additionalProperties": false
            }
        """,
        "SqliteSchema" to """
            {
              "type": "object",
              "required": ["database", "table", "type", "internal", "hasRowId", "rowCount", "sql", "columns", "foreignKeys"],
              "properties": {
                "database": {
                  "type": "string"
                },
                "table": {
                  "type": "string",
                  "description": "The name as the database has it, which can differ in case from the one asked for."
                },
                "type": ${ref("SqliteTableType")},
                "internal": {
                  "type": "boolean",
                  "description": "As in GET tables."
                },
                "hasRowId": {
                  "type": "boolean",
                  "description": "As in GET tables."
                },
                "rowCount": {
                  "type": ["integer", "null"],
                  "minimum": 0,
                  "description": "How many rows the table has; null for a view."
                },
                "sql": {
                  "type": ["string", "null"],
                  "description": "The CREATE statement from sqlite_master."
                },
                "entity": {
                  "type": "string",
                  "description": "From the app's hint: the class that maps the table."
                },
                "purpose": {
                  "type": "string",
                  "description": "From the app's hint: what a row is."
                },
                "columns": {
                  "type": "array",
                  "items": ${ref("SqliteColumn")}
                },
                "foreignKeys": {
                  "type": "array",
                  "items": ${ref("SqliteForeignKey")}
                }
              },
              "additionalProperties": false
            }
        """,
        "SqliteCell" to """
            {
              "description": "A cell, by its own storage class, which typeof() gives: any column can hold any type, so the declared type does not say. A cell of a column that the value policy masks has only its storage class and masked: true, and a NULL is still a NULL.",
              "oneOf": [
                {
                  "type": "object",
                  "required": ["type"],
                  "properties": {
                    "type": {
                      "const": "null"
                    }
                  },
                  "additionalProperties": false
                },
                {
                  "type": "object",
                  "required": ["type", "value"],
                  "properties": {
                    "type": {
                      "const": "integer"
                    },
                    "value": {
                      "type": ["integer", "string"],
                      "description": "A number; a string of its digits when it is beyond 2^53 - 1 either way, which a JavaScript number can't hold exactly."
                    }
                  },
                  "additionalProperties": false
                },
                {
                  "type": "object",
                  "required": ["type", "value"],
                  "properties": {
                    "type": {
                      "const": "real"
                    },
                    "value": {
                      "type": ["number", "string"],
                      "description": "A number; the string Infinity or -Infinity for an infinity, which JSON has no number for."
                    }
                  },
                  "additionalProperties": false
                },
                {
                  "type": "object",
                  "required": ["type", "value"],
                  "properties": {
                    "type": {
                      "const": "text"
                    },
                    "value": {
                      "type": "string",
                      "description": "The text, or its first 4096 characters when truncated is true."
                    },
                    "truncated": {
                      "const": true,
                      "description": "Present when the text is longer than 4096 characters."
                    },
                    "length": {
                      "type": "integer",
                      "minimum": 0,
                      "description": "With truncated: the whole text's length, in characters."
                    }
                  },
                  "additionalProperties": false
                },
                {
                  "type": "object",
                  "required": ["type", "bytes"],
                  "properties": {
                    "type": {
                      "const": "blob"
                    },
                    "bytes": {
                      "type": "integer",
                      "minimum": 0,
                      "description": "The blob's size. Its bytes are not read."
                    }
                  },
                  "additionalProperties": false
                },
                {
                  "type": "object",
                  "required": ["type", "masked"],
                  "properties": {
                    "type": {
                      "enum": ["integer", "real", "text", "blob"]
                    },
                    "masked": {
                      "const": true,
                      "description": "The value policy leaves the value out: the tab never sends it. GET rows does not read it from the database; POST query reads it into the cursor and drops it. A text has no length here, and a blob no size."
                    }
                  },
                  "additionalProperties": false
                }
              ]
            }
        """,
        "SqliteRow" to """
            {
              "type": "object",
              "required": ["cells"],
              "properties": {
                "rowid": {
                  "type": "string",
                  "pattern": "^-?[0-9]+${'$'}",
                  "description": "The row's rowid, as a string, so that a JavaScript client keeps every digit. Absent for a view and for a table WITHOUT ROWID."
                },
                "cells": {
                  "type": "object",
                  "additionalProperties": ${ref("SqliteCell")},
                  "description": "Each column's cell, by the column's name, in the order of GET schema."
                }
              },
              "additionalProperties": false
            }
        """,
        "SqliteRowsPage" to """
            {
              "description": "The pagination envelope, with rows as its items.",
              "type": "object",
              "required": ["items", "nextCursor"],
              "properties": {
                "items": {
                  "type": "array",
                  "items": ${ref("SqliteRow")}
                },
                "nextCursor": {
                  "type": ["string", "null"],
                  "description": "The cursor of the next page, or null when this is the last."
                }
              },
              "additionalProperties": false
            }
        """,
        "SqliteValue" to """
            {
              "type": ["string", "number", "null"],
              "description": "A value to bind. A string binds as a text, which the column's type can turn into a number, such as \"42\" in an INTEGER column; a whole number as an integer; another number as a real; null as NULL. A blob is written with POST exec, as a blob literal such as x'00ff'."
            }
        """,
        "SqliteQueryResult" to """
            {
              "type": "object",
              "required": ["database", "columns", "rows", "truncated"],
              "properties": {
                "database": {
                  "type": "string"
                },
                "columns": {
                  "type": "array",
                  "items": {
                    "type": "string"
                  },
                  "description": "The result's columns, in order. Two can have the same name."
                },
                "rows": {
                  "type": "array",
                  "maxItems": 1000,
                  "items": {
                    "type": "array",
                    "items": ${ref("SqliteCell")},
                    "description": "A cell for each column, in the order of columns."
                  }
                },
                "truncated": {
                  "type": "boolean",
                  "description": "Whether the result had more rows than rows holds."
                }
              },
              "additionalProperties": false
            }
        """,
        "SqliteExecResult" to """
            {
              "type": "object",
              "required": ["status", "changes"],
              "properties": {
                "status": {
                  "const": "ok"
                },
                "changes": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "How many rows the statement inserted, updated, or deleted, without the rows of triggers and of foreign key actions; 0 for a statement that changes no row, such as CREATE TABLE."
                }
              },
              "additionalProperties": false
            }
        """,
        "SqliteWriteOk" to """
            {
              "type": "object",
              "required": ["status"],
              "properties": {
                "status": {
                  "const": "ok"
                }
              },
              "additionalProperties": false
            }
        """,
    )
