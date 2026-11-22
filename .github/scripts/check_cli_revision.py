#!/usr/bin/env python3
"""Fail when a change to the CLI does not increase its revision by one.

Usage: check_cli_revision.py BASE [HEAD]

BASE and HEAD are commits; HEAD is HEAD when not given. When a file under
lustro-cli/src/ differs between them, REVISION in lustro-cli/src/lustro_cli/__init__.py
at HEAD must be the one at BASE plus one. A BASE without REVISION counts as 0.

In CI, BASE is the base branch of the pull request and HEAD is the merge commit that
GitHub makes, so the diff holds only the pull request's changes. Two pull requests
that both increase the revision change the same line, so the second one gets a
conflict. After its rebase, the check compares with the new base and asks for the
next number.

Python standard library only.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from typing import List, Optional

SOURCE = "lustro-cli/src/"
INIT = "lustro-cli/src/lustro_cli/__init__.py"
ASSIGNMENT = re.compile(r"^REVISION\s*=\s*(\S+)\s*$", re.MULTILINE)


class CheckError(Exception):
    pass


def _git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], capture_output=True, text=True)


def _changed_files(base: str, head: str) -> List[str]:
    result = _git("diff", "--name-only", base, head, "--", ":(top)" + SOURCE)
    if result.returncode != 0:
        raise CheckError("git diff {} {} failed: {}".format(base, head, result.stderr.strip()))
    return [line for line in result.stdout.splitlines() if line]


def _revision(commit: str, required: bool) -> Optional[int]:
    """REVISION at ``commit``, or None when the commit has no REVISION and it is not required."""
    result = _git("show", "{}:{}".format(commit, INIT))
    if result.returncode != 0:
        if required:
            raise CheckError("{} has no {}: {}".format(commit, INIT, result.stderr.strip()))
        return None
    found = ASSIGNMENT.findall(result.stdout)
    if not found and not required:
        return None
    if len(found) != 1:
        raise CheckError("{} at {} must assign REVISION once, found {}".format(INIT, commit, len(found)))
    if not re.fullmatch(r"[0-9]+", found[0]):
        raise CheckError("REVISION in {} at {} must be a whole number, found {}".format(INIT, commit, found[0]))
    return int(found[0])


def check(base: str, head: str) -> str:
    """The result as a line of text. Raises CheckError when the check fails."""
    changed = _changed_files(base, head)
    if not changed:
        return "no file under {} changed: the revision can stay".format(SOURCE)
    old = _revision(base, required=False) or 0
    new = _revision(head, required=True)
    if new != old + 1:
        raise CheckError(
            "{} file(s) under {} changed, such as {}, and REVISION in {} is {}, with {} on the base branch. "
            "Set REVISION = {}: each pull request that changes the CLI increases it by one.".format(
                len(changed), SOURCE, changed[0], INIT, new, old, old + 1
            )
        )
    return "{} file(s) under {} changed, and REVISION goes from {} to {}".format(len(changed), SOURCE, old, new)


def main(argv: List[str]) -> int:
    if len(argv) not in (1, 2):
        print("usage: check_cli_revision.py BASE [HEAD]", file=sys.stderr)
        return 2
    base = argv[0]
    head = argv[1] if len(argv) == 2 else "HEAD"
    try:
        print("check_cli_revision: OK: {}.".format(check(base, head)))
        return 0
    except CheckError as exc:
        prefix = "::error::" if os.environ.get("GITHUB_ACTIONS") == "true" else ""
        print("{}check_cli_revision FAILED: {}".format(prefix, exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
