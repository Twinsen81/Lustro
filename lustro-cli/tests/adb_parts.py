"""One ``adb shell`` call that runs more than one command, as discovery makes it. A fake
adb answers each command from its table, as if adb ran each one alone, and records it."""

from __future__ import annotations

from lustro_cli import discovery

_SEPARATOR = "; echo; echo {}; ".format(discovery._PART)
_END = _SEPARATOR + "true"


def split(cmd):
    """The commands that ``cmd`` runs, each as an adb call of its own, or ``None`` for a
    call that runs one command."""
    if len(cmd) < 2 or cmd[-2] != "shell" or not cmd[-1].endswith(_END):
        return None
    return [cmd[:-1] + [command] for command in cmd[-1][: -len(_END)].split(_SEPARATOR)]


def join(outputs):
    """The output of the call: the output of each command, which ``None`` leaves empty, and
    the separator after it."""
    return "".join("{}\n{}\n".format(out or "", discovery._PART) for out in outputs)


TCP_HEADER = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"


def listening(*sockets):
    """/proc/net/tcp and tcp6 with a socket that listens on 127.0.0.1:``port`` for each
    ``(port, uid)`` in ``sockets``, and a connection on the first port, which does not
    listen and has another uid."""
    lines = [
        "   {}: 0000000000000000FFFF00000100007F:{:04X} 00000000000000000000000000000000:0000 0A "
        "00000000:00000000 00:00000000 00000000 {}        0 98765 1 0000000000000000 100 0 0 10 0\n".format(
            n, port, uid
        )
        for n, (port, uid) in enumerate(sockets)
    ]
    if sockets:
        lines.append(
            "   9: 0000000000000000FFFF00000100007F:{:04X} 0000000000000000FFFF00000100007F:9C40 01 "
            "00000000:00000000 00:00000000 00000000 99999        0 98766 1 0000000000000000 20 4 30 10 -1\n".format(
                sockets[0][0]
            )
        )
    return TCP_HEADER + TCP_HEADER + "".join(lines)
