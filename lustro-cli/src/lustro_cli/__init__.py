"""lustro — a CLI client for the Lustro Android debug server.

The CLI talks ONLY to the HTTP wire protocol under ``/api/v1/``, which is the
public, SemVer-governed contract (see ``wire-protocol/v1/``). It has no
dependency on a source checkout and uses the Python standard library only at
runtime.
"""

__all__ = ["__version__", "LustroError", "LustroClient", "CursorState", "main"]

# Tied to the Lustro library/wire-protocol version. Keep in lockstep with
# gradle.properties VERSION_NAME (the SemVer-governed wire protocol).
__version__ = "0.1.0"

from .client import CursorState, LustroClient, LustroError  # noqa: E402
from .cli import main  # noqa: E402
