"""Tests of check_release_commit.py, in a new git repository for each test, with a fake gh.

CI runs them in the job of the CLI's tests: python -m pytest -q .github/scripts
"""

from __future__ import annotations

import json
import os
import stat
import subprocess
import sys
from pathlib import Path
from typing import List, Optional

import pytest

SCRIPT = Path(__file__).resolve().parent / "check_release_commit.py"

FAKE_GH = """#!/bin/sh
printf '%s\\n' "$@" > "$FAKE_GH_ARGS"
if [ -n "$FAKE_GH_ERROR" ]; then
  echo "$FAKE_GH_ERROR" >&2
  exit 1
fi
cat "$FAKE_GH_OUTPUT"
"""


class Repo:
    def __init__(self, root: Path) -> None:
        self.root = root / "repo"
        self.root.mkdir()
        self.bin = root / "bin"
        self.bin.mkdir()
        gh = self.bin / "gh"
        gh.write_text(FAKE_GH)
        gh.chmod(gh.stat().st_mode | stat.S_IEXEC)
        self.gh_args = root / "gh-args.txt"
        self.gh_output = root / "gh-output.json"
        self.gh_output.write_text("[]")
        self.gh_error = ""
        self.git("init", "-q", "-b", "main")

    def git(self, *args: str) -> str:
        result = subprocess.run(
            ["git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false",
             "-c", "tag.gpgsign=false", *args],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=True,
        )
        return result.stdout.strip()

    def commit(self, message: str = "change") -> str:
        self.git("commit", "-q", "--allow-empty", "-m", message)
        return self.git("rev-parse", "HEAD")

    def runs(self, *runs: dict) -> None:
        self.gh_output.write_text(json.dumps(list(runs)))

    def gh_called_with(self) -> Optional[List[str]]:
        return self.gh_args.read_text().splitlines() if self.gh_args.exists() else None

    def check(self, commit: str, *extra: str) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env["PATH"] = "{}{}{}".format(self.bin, os.pathsep, env.get("PATH", ""))
        env["FAKE_GH_ARGS"] = str(self.gh_args)
        env["FAKE_GH_OUTPUT"] = str(self.gh_output)
        env["FAKE_GH_ERROR"] = self.gh_error
        env.pop("GITHUB_ACTIONS", None)
        return subprocess.run(
            [sys.executable, str(SCRIPT), commit, "--main-ref", "main", *extra],
            cwd=self.root,
            capture_output=True,
            text=True,
            env=env,
        )


def _run(sha: str, event: str = "push", branch: str = "main", status: str = "completed",
         conclusion: Optional[str] = "success", run_id: int = 1) -> dict:
    return {
        "databaseId": run_id,
        "status": status,
        "conclusion": conclusion if status == "completed" else "",
        "event": event,
        "headBranch": branch,
        "headSha": sha,
        "url": "https://github.com/o/r/actions/runs/{}".format(run_id),
    }


@pytest.fixture
def repo(tmp_path: Path) -> Repo:
    return Repo(tmp_path)


def test_a_commit_on_main_with_a_passed_ci_run_passes(repo):
    repo.commit()
    sha = repo.commit()
    repo.runs(_run(sha))

    result = repo.check(sha)

    assert result.returncode == 0, result.stderr
    assert "is on main" in result.stdout
    assert repo.gh_called_with() == [
        "run", "list", "--workflow", "ci.yml", "--commit", sha, "--limit", "100",
        "--json", "databaseId,status,conclusion,event,headBranch,headSha,url",
    ]


def test_a_tag_name_resolves_to_its_commit(repo):
    sha = repo.commit()
    repo.git("tag", "-a", "v0.1.0", "-m", "release")
    repo.runs(_run(sha))

    result = repo.check("v0.1.0")

    assert result.returncode == 0, result.stderr
    assert sha in result.stdout


def test_an_older_commit_of_main_passes(repo):
    sha = repo.commit()
    repo.commit()
    repo.runs(_run(sha))

    assert repo.check(sha).returncode == 0


def test_a_commit_that_is_not_on_main_fails_before_gh_runs(repo):
    repo.commit()
    repo.git("checkout", "-q", "-b", "feature")
    sha = repo.commit("not merged")
    repo.git("checkout", "-q", "main")
    repo.runs(_run(sha))

    result = repo.check(sha)

    assert result.returncode == 1
    assert "{} is not on main".format(sha) in result.stderr
    assert repo.gh_called_with() is None


def test_a_commit_without_a_ci_run_fails(repo):
    sha = repo.commit()

    result = repo.check(sha)

    assert result.returncode == 1
    assert "has no run of ci.yml on a push to main" in result.stderr


def test_a_passed_run_of_a_pull_request_does_not_count(repo):
    sha = repo.commit()
    repo.runs(_run(sha, event="pull_request", branch="feature"))

    result = repo.check(sha)

    assert result.returncode == 1
    assert "has no run of ci.yml on a push to main (only: pull_request on feature)" in result.stderr


def test_a_failed_ci_run_fails(repo):
    sha = repo.commit()
    repo.runs(_run(sha, conclusion="failure"))

    result = repo.check(sha)

    assert result.returncode == 1
    assert "did not pass" in result.stderr
    assert "(failure)" in result.stderr


def test_a_ci_run_that_has_not_finished_fails(repo):
    sha = repo.commit()
    repo.runs(_run(sha, status="in_progress"))

    result = repo.check(sha)

    assert result.returncode == 1
    assert "has not finished" in result.stderr
    assert "Run this workflow again" in result.stderr


def test_one_passed_run_is_enough(repo):
    sha = repo.commit()
    repo.runs(_run(sha, conclusion="cancelled", run_id=1), _run(sha, run_id=2))

    result = repo.check(sha)

    assert result.returncode == 0, result.stderr
    assert "actions/runs/2" in result.stdout


def test_a_gh_failure_fails_the_check(repo):
    sha = repo.commit()
    repo.gh_error = "HTTP 403: Resource not accessible by integration"

    result = repo.check(sha)

    assert result.returncode == 1
    assert "gh run list failed: HTTP 403" in result.stderr


def test_an_unknown_commit_fails(repo):
    repo.commit()

    result = repo.check("0" * 40)

    assert result.returncode == 1
    assert "is not a commit in this checkout" in result.stderr


def test_a_main_ref_that_is_not_fetched_fails(repo):
    sha = repo.commit()
    repo.runs(_run(sha))

    result = repo.check(sha, "--main-ref", "origin/main")

    assert result.returncode == 1
    assert "origin/main is not in this checkout" in result.stderr


def test_another_workflow_and_branch(repo):
    sha = repo.commit()
    repo.runs(_run(sha, branch="trunk"))

    result = repo.check(sha, "--branch", "trunk", "--workflow", "build.yml")

    assert result.returncode == 0, result.stderr
    assert repo.gh_called_with()[3] == "build.yml"
