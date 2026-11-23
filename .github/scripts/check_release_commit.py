#!/usr/bin/env python3
"""Fail unless a release commit is on main and the CI workflow passed for it.

Usage: check_release_commit.py COMMIT [--branch BRANCH] [--main-ref REF] [--workflow FILE]

COMMIT is the commit of the release tag: a SHA or a tag name. The check passes when
COMMIT is an ancestor of REF (or REF itself), and when the workflow FILE has a run for
COMMIT on a push to BRANCH that completed with success. BRANCH is main, REF is
origin/BRANCH, and FILE is ci.yml when not given.

A pull request is merged with a squash, so the commit on main is a new commit, and only
the CI run of the push to main tested it. A run of the pull request does not count. The
script reads the runs with `gh run list`, which needs GH_TOKEN with `actions: read`.

release.yml runs it before it publishes anything: Maven Central keeps every release, so
a release from a commit that is not on main, or that CI did not pass, can't be taken back.

Python standard library only.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from typing import List, Optional


class CheckError(Exception):
    pass


def _run(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(list(args), capture_output=True, text=True)


def _resolve(commit: str) -> str:
    result = _run("git", "rev-parse", "--verify", "--quiet", "{}^{{commit}}".format(commit))
    if result.returncode != 0:
        raise CheckError("{} is not a commit in this checkout".format(commit))
    return result.stdout.strip()


def _check_on_branch(sha: str, main_ref: str) -> None:
    if _run("git", "rev-parse", "--verify", "--quiet", "{}^{{commit}}".format(main_ref)).returncode != 0:
        raise CheckError("{} is not in this checkout: fetch it first".format(main_ref))
    result = _run("git", "merge-base", "--is-ancestor", sha, main_ref)
    if result.returncode == 1:
        raise CheckError(
            "{} is not on {}. Tag a commit of {}: the merge commit of the release pull request.".format(
                sha, main_ref, main_ref
            )
        )
    if result.returncode != 0:
        raise CheckError("git merge-base --is-ancestor {} {} failed: {}".format(sha, main_ref, result.stderr.strip()))


def _runs(sha: str, workflow: str) -> List[dict]:
    result = _run(
        "gh", "run", "list",
        "--workflow", workflow,
        "--commit", sha,
        "--limit", "100",
        "--json", "databaseId,status,conclusion,event,headBranch,headSha,url",
    )
    if result.returncode != 0:
        raise CheckError("gh run list failed: {}".format(result.stderr.strip() or result.stdout.strip()))
    try:
        runs = json.loads(result.stdout or "[]")
    except ValueError as exc:
        raise CheckError("gh run list did not return JSON: {}".format(exc))
    return [run for run in runs if run.get("headSha", sha) == sha]


def _describe(run: dict) -> str:
    state = run.get("conclusion") or run.get("status") or "unknown"
    return "{} ({})".format(run.get("url") or run.get("databaseId"), state)


def _check_ci(sha: str, branch: str, workflow: str) -> str:
    runs = _runs(sha, workflow)
    pushes = [run for run in runs if run.get("event") == "push" and run.get("headBranch") == branch]
    passed = [run for run in pushes if run.get("status") == "completed" and run.get("conclusion") == "success"]
    if passed:
        return passed[0].get("url") or str(passed[0].get("databaseId"))
    if not pushes:
        others = ", ".join("{} on {}".format(run.get("event"), run.get("headBranch")) for run in runs)
        raise CheckError(
            "{} has no run of {} on a push to {}{}. Release a commit that CI tested on {}.".format(
                sha, workflow, branch, " (only: {})".format(others) if others else "", branch
            )
        )
    running = [run for run in pushes if run.get("status") != "completed"]
    if running:
        raise CheckError(
            "the run of {} for {} has not finished: {}. Run this workflow again when it has passed.".format(
                workflow, sha, ", ".join(_describe(run) for run in running)
            )
        )
    raise CheckError(
        "the run of {} for {} did not pass: {}. Fix main, and release a later commit.".format(
            workflow, sha, ", ".join(_describe(run) for run in pushes)
        )
    )


def check(commit: str, branch: str = "main", main_ref: Optional[str] = None, workflow: str = "ci.yml") -> str:
    """The result as a line of text. Raises CheckError when the check fails."""
    main_ref = main_ref or "origin/{}".format(branch)
    sha = _resolve(commit)
    _check_on_branch(sha, main_ref)
    run = _check_ci(sha, branch, workflow)
    return "{} is on {}, and {} passed for it: {}".format(sha, main_ref, workflow, run)


def main(argv: List[str]) -> int:
    parser = argparse.ArgumentParser(prog="check_release_commit.py", description=__doc__.splitlines()[0])
    parser.add_argument("commit")
    parser.add_argument("--branch", default="main")
    parser.add_argument("--main-ref", default=None)
    parser.add_argument("--workflow", default="ci.yml")
    args = parser.parse_args(argv)
    try:
        print("check_release_commit: OK: {}.".format(check(args.commit, args.branch, args.main_ref, args.workflow)))
        return 0
    except CheckError as exc:
        prefix = "::error::" if os.environ.get("GITHUB_ACTIONS") == "true" else ""
        print("{}check_release_commit FAILED: {}".format(prefix, exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
