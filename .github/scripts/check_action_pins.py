#!/usr/bin/env python3
"""Fail when a workflow or a composite action uses an action by a tag or a branch.

Usage: check_action_pins.py [ROOT]

ROOT is the repository root; the default is the root of the repository that holds
this script. The check reads each workflow, .github/workflows/*.yml and *.yaml, and
each composite action, action.yml or action.yaml under .github/. Each `uses:` must
name one of these:

- an action or a reusable workflow at a full commit SHA, such as
  actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
- a local path, such as ./.github/actions/setup
- a Docker image by its digest, such as docker://alpine@sha256:<64 hex digits>

The owner of an action can move a tag or a branch to other code without a change in
this repository. A commit SHA always names the same code, and Dependabot updates
the SHA and the version in its comment.

The check reads the `uses:` lines as text, not as YAML, so that it needs only the
Python standard library. It fails on a `uses:` whose value it cannot read, such as
a value on the next line.

Python standard library only.
"""

from __future__ import annotations

import os
import re
import sys
from pathlib import Path
from typing import List, NamedTuple, Optional

# A `uses:` key at the start of a line, after the dashes of a list item, or in a
# flow mapping such as `- { uses: owner/repo@ref }`. The key can be quoted. The
# match ends at the colon, so that each key of a flow sequence such as
# `[{uses: a}, {uses: b}]` is found.
USES = re.compile(r"""(?:^\s*(?:-\s+)*|[{,]\s*)(?P<quote>["']?)uses(?P=quote)\s*:""")
VALUE = re.compile(r"""\s*(?:"(?P<double>[^"]*)"|'(?P<single>[^']*)'|(?P<plain>[^\s,}#]+))""")
COMMIT = re.compile(r"[^@\s]+@[0-9a-f]{40}")
DOCKER_DIGEST = re.compile(r"docker://[^@\s]+@sha256:[0-9a-f]{64}")


class Finding(NamedTuple):
    path: str
    line: int
    message: str


def _value(rest: str) -> Optional[str]:
    """The value after `uses:`, without quotes, or None when the line has none."""
    match = VALUE.match(rest)
    if not match:
        return None
    value = next(group for group in match.group("double", "single", "plain") if group is not None)
    if not value or value[0] in "|>&*!":
        return None
    return value


def _problem(value: Optional[str]) -> Optional[str]:
    """Why ``value`` is not pinned, or None when it is."""
    if value is None:
        return "cannot read the value of uses:. Write the action on the same line, as owner/repo@<commit SHA>"
    if value.startswith("./"):
        return None
    if value.startswith("docker://"):
        if DOCKER_DIGEST.fullmatch(value):
            return None
        return "uses {} by a tag. Name the image by its digest: docker://<image>@sha256:<digest>".format(value)
    if COMMIT.fullmatch(value):
        return None
    return "uses {} {}. Pin it to the full commit SHA of the release, with the version in a comment, such as {}".format(
        value,
        "by a tag or a branch" if "@" in value else "with no ref",
        "owner/repo@<40 hex digits> # v1.2.3",
    )


def check_file(path: Path, name: str) -> List[Finding]:
    findings = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if line.lstrip().startswith("#"):
            continue
        for match in USES.finditer(line):
            problem = _problem(_value(line[match.end() :]))
            if problem:
                findings.append(Finding(name, number, problem))
    return findings


def files(root: Path) -> List[Path]:
    github = root / ".github"
    found = [path for pattern in ("*.yml", "*.yaml") for path in (github / "workflows").glob(pattern)]
    found += [path for pattern in ("action.yml", "action.yaml") for path in github.rglob(pattern)]
    return sorted(set(found))


def main(argv: List[str]) -> int:
    if len(argv) > 1:
        print("usage: check_action_pins.py [ROOT]", file=sys.stderr)
        return 2
    root = Path(argv[0]) if argv else Path(__file__).resolve().parents[2]
    annotate = os.environ.get("GITHUB_ACTIONS") == "true"
    checked = []
    findings: List[Finding] = []
    for path in files(root):
        name = path.relative_to(root).as_posix()
        checked.append(name)
        findings += check_file(path, name)
    if not checked:
        print("check_action_pins FAILED: no workflow or composite action under {}".format(root / ".github"), file=sys.stderr)
        return 1
    for finding in findings:
        if annotate:
            print("::error file={},line={}::{}".format(finding.path, finding.line, finding.message), file=sys.stderr)
        else:
            print("{}:{}: {}".format(finding.path, finding.line, finding.message), file=sys.stderr)
    if findings:
        print("check_action_pins FAILED: {} `uses:` not pinned to a commit SHA.".format(len(findings)), file=sys.stderr)
        return 1
    print(
        "check_action_pins: OK: every `uses:` in {} file(s) is pinned, a local path, or an image digest.".format(
            len(checked)
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
