#!/usr/bin/env python3
"""Fail when a workflow job that runs Gradle can read a secret.

Usage: check_workflow_secrets.py [WORKFLOW_DIR]

A Gradle build loads every build plugin, and a plugin can read the environment, the
files of the runner, and the scripts of the job's steps, into which GitHub writes the
value of each expression. So a job that runs Gradle must not reference a secret:
release.yml and snapshot.yml give the signing key and the Central credentials only to a
publish job that runs no Gradle (see SECURITY.md, "Release Publishing").

A job runs Gradle when its text has `gradlew`, a `gradle` command, the
gradle/actions actions, or the setup action of this repository, which sets up Gradle.
A job references a secret when its text has `secrets.NAME`, `secrets[...]`,
`toJSON(secrets)`, or `secrets: inherit`, in any case, and in a comment too: in a `run` script, GitHub
replaces an expression in a shell comment as well. A secret in the workflow's own `env`
reaches every job. The check also fails on a job that runs Gradle and uses a YAML
alias, because it reads text and can't follow the alias. WORKFLOW_DIR is
.github/workflows when not given.

Python standard library only. It reads the workflows as text, line by line, with no
YAML parser: a job is the lines under its key in `jobs:`.
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Tuple

GRADLE = re.compile(
    r"gradlew"
    r"|(?:^|[\s;&|(])gradle\s"
    # GitHub matches the owner and the repository of an action in any case.
    r"|uses:\s*['\"]?(?i:gradle/actions)/"
    r"|uses:\s*['\"]?\./\.github/actions/setup\b"
)
# GitHub resolves the names in an expression in any case: SECRETS.X is secrets.X.
SECRET = re.compile(
    r"\bsecrets\.[A-Za-z_]|\bsecrets\s*\[|\(\s*secrets\s*\)|\bsecrets\s*:\s*inherit\b", re.IGNORECASE
)
EXPRESSION_SECRET = re.compile(r"\$\{\{[^}]*(?:" + SECRET.pattern + ")", re.IGNORECASE)
ALIAS = re.compile(r"(?:^|[\s\[{,:-])\*[A-Za-z0-9_][A-Za-z0-9_.-]*\s*(?:#.*)?$")
KEY = re.compile(r"^(\s*)([A-Za-z0-9_-]+|'[^']*'|\"[^\"]*\")\s*:(\s|$)")


@dataclass
class Job:
    name: str
    line: int  # 1-based line of the job's key
    text: str


def indentation(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def is_content(line: str) -> bool:
    stripped = line.strip()
    return bool(stripped) and not stripped.startswith("#")


def split_jobs(text: str) -> Tuple[str, List[Job]]:
    """The text outside `jobs:`, and each job with its lines."""
    lines = text.splitlines()
    start = next((i for i, l in enumerate(lines) if re.match(r"^jobs\s*:", l)), None)
    if start is None:
        return text, []
    end = next((i for i in range(start + 1, len(lines)) if is_content(lines[i]) and indentation(lines[i]) == 0), len(lines))
    outside = "\n".join(lines[:start] + lines[end:])
    jobs: List[Job] = []
    job_indent: Optional[int] = None
    for i in range(start + 1, end):
        line = lines[i]
        if not is_content(line):
            # A comment at the indentation of the job keys is between two jobs, in no job.
            if jobs and not (line.strip() and job_indent is not None and indentation(line) <= job_indent):
                jobs[-1].text += line + "\n"
            continue
        if job_indent is None:
            job_indent = indentation(line)
        match = KEY.match(line)
        if indentation(line) == job_indent and match:
            jobs.append(Job(match.group(2).strip("'\""), i + 1, line + "\n"))
        elif jobs:
            jobs[-1].text += line + "\n"
    return outside, jobs


def check(path: Path) -> List[str]:
    text = path.read_text(encoding="utf-8")
    outside, jobs = split_jobs(text)
    gradle_jobs = [job for job in jobs if GRADLE.search(job.text)]
    problems = []
    for job in gradle_jobs:
        if SECRET.search(job.text):
            problems.append(f"{path}:{job.line}: job '{job.name}' runs Gradle and references a secret")
        if any(ALIAS.search(line) for line in job.text.splitlines() if not line.lstrip().startswith("#")):
            problems.append(f"{path}:{job.line}: job '{job.name}' runs Gradle and uses a YAML alias")
    if gradle_jobs and EXPRESSION_SECRET.search(outside):
        names = ", ".join(f"'{job.name}'" for job in gradle_jobs)
        problems.append(f"{path}: a secret outside `jobs:` reaches the jobs that run Gradle: {names}")
    return problems


def main(argv: List[str]) -> int:
    directory = Path(argv[1]) if len(argv) > 1 else Path(".github/workflows")
    workflows = sorted([*directory.glob("*.yml"), *directory.glob("*.yaml")])
    if not workflows:
        print(f"error: no workflows in {directory}", file=sys.stderr)
        return 2
    problems = [p for workflow in workflows for p in check(workflow)]
    for problem in problems:
        print(f"::error::{problem}")
    if problems:
        print(
            "A Gradle build loads every build plugin, which can read what its job gets. Give a "
            "secret only to a job that runs no Gradle (see SECURITY.md, \"Release Publishing\")."
        )
        return 1
    print(f"No job that runs Gradle references a secret ({len(workflows)} workflows).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
