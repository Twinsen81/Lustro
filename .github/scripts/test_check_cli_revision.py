"""Tests of check_cli_revision.py, in a new git repository for each test.

CI runs them in the job of the CLI's tests: python -m pytest -q .github/scripts
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from typing import Dict, Optional

import pytest

SCRIPT = Path(__file__).resolve().parent / "check_cli_revision.py"
INIT = "lustro-cli/src/lustro_cli/__init__.py"


def _init_text(revision: Optional[int]) -> str:
    text = '__version__ = "0.1.0"\nPROTOCOL_VERSION = "1.17"\n'
    if revision is not None:
        text += "REVISION = {}\n".format(revision)
    return text


class Repo:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.git("init", "-q", "-b", "main")

    def git(self, *args: str) -> str:
        result = subprocess.run(
            ["git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false", *args],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=True,
        )
        return result.stdout.strip()

    def write(self, path: str, text: str) -> None:
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)

    def commit(self, files: Dict[str, str]) -> str:
        for path, text in files.items():
            self.write(path, text)
        self.git("add", "-A")
        self.git("commit", "-q", "--allow-empty", "-m", "change")
        return self.git("rev-parse", "HEAD")

    def check(self, base: str, head: str, cwd: Optional[Path] = None) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(SCRIPT), base, head],
            cwd=cwd or self.root,
            capture_output=True,
            text=True,
        )


def _files(revision: Optional[int], cli: str = "print('a')\n") -> Dict[str, str]:
    return {INIT: _init_text(revision), "lustro-cli/src/lustro_cli/cli.py": cli}


@pytest.fixture
def repo(tmp_path: Path) -> Repo:
    return Repo(tmp_path)


def test_a_change_under_src_without_an_increase_fails(repo):
    base = repo.commit(_files(1))
    head = repo.commit(_files(1, cli="print('b')\n"))
    result = repo.check(base, head)
    assert result.returncode == 1
    assert "lustro-cli/src/lustro_cli/cli.py" in result.stderr
    assert "Set REVISION = 2" in result.stderr


def test_a_change_under_src_with_an_increase_by_one_passes(repo):
    base = repo.commit(_files(1))
    head = repo.commit(_files(2, cli="print('b')\n"))
    result = repo.check(base, head)
    assert result.returncode == 0, result.stderr
    assert "REVISION goes from 1 to 2" in result.stdout


@pytest.mark.parametrize("revision", [0, 3])
def test_a_revision_that_is_not_the_next_number_fails(repo, revision):
    base = repo.commit(_files(1))
    head = repo.commit(_files(revision, cli="print('b')\n"))
    result = repo.check(base, head)
    assert result.returncode == 1
    assert "Set REVISION = 2" in result.stderr


def test_a_change_outside_src_needs_no_increase(repo):
    base = repo.commit(_files(1))
    head = repo.commit({"lustro-cli/README.md": "docs\n", "lustro-cli/tests/test_x.py": "pass\n"})
    result = repo.check(base, head)
    assert result.returncode == 0, result.stderr
    assert "the revision can stay" in result.stdout


def test_a_base_without_a_revision_takes_1(repo):
    base = repo.commit(_files(None))
    head = repo.commit(_files(1))
    assert repo.check(base, head).returncode == 0


def test_a_head_without_a_revision_fails(repo):
    base = repo.commit(_files(1))
    head = repo.commit(_files(None, cli="print('b')\n"))
    result = repo.check(base, head)
    assert result.returncode == 1
    assert "must assign REVISION once" in result.stderr


def test_after_a_rebase_on_a_base_with_the_same_increase_the_next_number_passes(repo):
    first = repo.commit(_files(1))
    other = repo.commit(_files(2, cli="print('other')\n"))
    rebased = repo.commit(_files(2, cli="print('mine')\n"))
    assert repo.check(other, rebased).returncode == 1
    repo.git("reset", "-q", "--hard", other)
    rebased = repo.commit(_files(3, cli="print('mine')\n"))
    assert repo.check(other, rebased).returncode == 0
    assert repo.check(first, other).returncode == 0


def test_it_runs_from_a_subdirectory(repo):
    base = repo.commit(_files(1))
    head = repo.commit(_files(1, cli="print('b')\n"))
    assert repo.check(base, head, cwd=repo.root / "lustro-cli").returncode == 1
