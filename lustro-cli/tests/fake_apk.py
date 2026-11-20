"""A fake APK, and the answer of ``adb exec-out "tail -c N <path>"`` for it, for the
tests of the check of the Lustro runtime."""

from __future__ import annotations

import io
import re
import shlex
import struct
import zipfile


# Some entries of a debug APK with the Lustro runtime, and of any APK.
RUNTIME_ENTRIES = ["assets/lustro/_schema.json", "assets/lustro/network.js", "assets/lustro/shared.js"]
APP_ENTRIES = ["AndroidManifest.xml", "classes.dex", "resources.arsc", "res/layout/main.xml", "META-INF/MANIFEST.MF"]


def apk_bytes(names, comment=b""):
    """An APK with an entry for each of ``names``, laid out as the build tools do: the
    entries, then the APK signing block, the central directory, and the end of central
    directory record (EOCD). Some entries have an extra field and a comment."""
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as apk:
        for index, name in enumerate(names):
            info = zipfile.ZipInfo(name)
            if index % 3 == 1:
                info.extra = struct.pack("<HH", 0xD935, 4) + b"\0" * 4
            if index % 4 == 2:
                info.comment = b"entry comment"
            apk.writestr(info, b"\0PK\x05\x06 content of " + name.encode())
        apk.comment = comment
    data = out.getvalue()
    eocd = len(data) - 22 - len(comment)
    directory_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    block = b"\0" * 4096 + b"APK Sig Block 42"
    eocd_record = bytearray(data[eocd:])
    struct.pack_into("<I", eocd_record, 16, directory_offset + len(block))
    return data[:directory_offset] + block + data[directory_offset:eocd] + bytes(eocd_record)


_TAIL = re.compile(r"tail -c (?P<size>\d+) (?P<path>.+)")


def tail_of(files, cmd):
    """What ``adb exec-out "tail -c N <path>"`` writes for a file in ``files``, which maps
    each path to its bytes, or ``None`` for another command or another path."""
    match = _TAIL.fullmatch(cmd[-1]) if len(cmd) > 1 and cmd[-2] == "exec-out" else None
    data = files.get(shlex.split(match.group("path"))[0]) if match else None
    return None if not isinstance(data, bytes) else data[-int(match.group("size")) :]
