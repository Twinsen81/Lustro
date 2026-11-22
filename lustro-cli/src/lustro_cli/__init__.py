"""lustro — a CLI client for the Lustro Android debug server.

The CLI talks ONLY to the HTTP wire protocol under ``/api/v1/``, which is the
public, SemVer-governed contract (see ``wire-protocol/v1/``). It has no
dependency on a source checkout and uses the Python standard library only at
runtime.
"""

__all__ = ["__version__", "PROTOCOL_VERSION", "REVISION", "LustroError", "LustroClient", "CursorState", "main"]

# The package's only version: pyproject.toml reads it from here. It is
# VERSION_NAME in gradle.properties without -SNAPSHOT, so a release publishes the
# CLI with the version of the library; `./gradlew checkCliVersion` fails when not.
__version__ = "0.1.0"

# The wire protocol version that this CLI implements, which `lustro --version`
# prints. It is the server's protocolVersion in lustro/build.gradle.kts, and
# `./gradlew checkCliVersion` fails when not: a change to the protocol updates it.
PROTOCOL_VERSION = "1.17"

# The revision of the CLI, which `lustro --version` prints. Each pull request that
# changes a file under lustro-cli/src/ increases it by one, and CI fails one that
# does not (.github/scripts/check_cli_revision.py). The two versions above change
# only at a release or with the protocol, so a wrapper script that needs a fix of
# the CLI requires the revision of that fix or a later one.
REVISION = 1

from .client import CursorState, LustroClient, LustroError  # noqa: E402
from .cli import main  # noqa: E402
