"""Tests of check_action_pins.py, in a new directory for each test.

CI runs them in the job of the CLI's tests: python -m pytest -q .github/scripts
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from typing import Dict

import pytest

SCRIPT = Path(__file__).resolve().parent / "check_action_pins.py"
SHA = "3d3c42e5aac5ba805825da76410c181273ba90b1"
DIGEST = "a" * 64


def _run(root: Path, files: Dict[str, str]) -> subprocess.CompletedProcess:
    for path, text in files.items():
        file = root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)
    env = {"PATH": "/usr/bin:/bin"}
    return subprocess.run([sys.executable, str(SCRIPT), str(root)], capture_output=True, text=True, env=env)


def _workflow(*uses: str) -> str:
    steps = "".join("      - uses: {}\n".format(value) for value in uses)
    return "name: CI\non: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n" + steps


def test_pinned_local_and_digest_pass(tmp_path: Path) -> None:
    result = _run(
        tmp_path,
        {
            ".github/workflows/ci.yml": _workflow(
                "actions/checkout@{} # v7.0.1".format(SHA),
                "./.github/actions/setup",
                "docker://alpine@sha256:{}".format(DIGEST),
                '"actions/setup-python@{}" # v7.1.0'.format(SHA),
                "'gradle/actions/setup-gradle@{}'".format(SHA),
            ),
            ".github/workflows/reuse.yaml": "jobs:\n  call:\n    uses: owner/repo/.github/workflows/build.yml@{}\n".format(SHA),
            ".github/actions/setup/action.yml": "runs:\n  using: composite\n  steps:\n    - name: Java\n      uses: actions/setup-java@{} # v6.3.0\n".format(SHA),
        },
    )
    assert result.returncode == 0, result.stderr
    assert "3 file(s)" in result.stdout


@pytest.mark.parametrize(
    "value",
    [
        "actions/checkout@v7",
        "actions/checkout@v7.0.1",
        "actions/checkout@main",
        "actions/checkout@3d3c42e",
        "actions/checkout",
        "actions/checkout@{}".format(SHA.upper()),
        "docker://alpine:3.20",
        "docker://alpine",
    ],
)
def test_tag_branch_or_short_sha_fails(tmp_path: Path, value: str) -> None:
    result = _run(tmp_path, {".github/workflows/ci.yml": _workflow("actions/checkout@{}".format(SHA), value)})
    assert result.returncode == 1
    assert ".github/workflows/ci.yml:8: uses {}".format(value) in result.stderr
    assert "1 `uses:` not pinned" in result.stderr


def test_composite_action_with_a_tag_fails(tmp_path: Path) -> None:
    action = "runs:\n  using: composite\n  steps:\n    - uses: gradle/actions/setup-gradle@v6\n"
    result = _run(
        tmp_path,
        {
            ".github/workflows/ci.yml": _workflow("./.github/actions/setup"),
            ".github/actions/setup/action.yml": action,
        },
    )
    assert result.returncode == 1
    assert ".github/actions/setup/action.yml:4: uses gradle/actions/setup-gradle@v6 by a tag" in result.stderr


def test_flow_mapping_and_quoted_key_are_read(tmp_path: Path) -> None:
    text = _workflow("./.github/actions/setup") + "      - { name: Check out, uses: actions/checkout@v7 }\n" + '      - "uses": actions/setup-node@v7\n'
    result = _run(tmp_path, {".github/workflows/ci.yml": text})
    assert result.returncode == 1
    assert "ci.yml:8: uses actions/checkout@v7" in result.stderr
    assert "ci.yml:9: uses actions/setup-node@v7" in result.stderr


def test_value_on_the_next_line_fails(tmp_path: Path) -> None:
    text = _workflow("./.github/actions/setup") + "      - uses: >-\n          actions/checkout@{}\n".format(SHA)
    result = _run(tmp_path, {".github/workflows/ci.yml": text})
    assert result.returncode == 1
    assert "ci.yml:8: cannot read the value of uses:" in result.stderr


def test_comments_and_run_text_are_not_read(tmp_path: Path) -> None:
    text = (
        _workflow("actions/checkout@{} # was actions/checkout@v7".format(SHA))
        + "      # - uses: actions/checkout@v6\n"
        + '      - run: echo "uses: actions/checkout@v7"\n'
    )
    result = _run(tmp_path, {".github/workflows/ci.yml": text})
    assert result.returncode == 0, result.stderr


def test_release_workflows_are_skipped(tmp_path: Path) -> None:
    result = _run(
        tmp_path,
        {
            ".github/workflows/ci.yml": _workflow("actions/checkout@{}".format(SHA)),
            ".github/workflows/release.yml": _workflow("actions/checkout@v7"),
            ".github/workflows/snapshot.yml": _workflow("actions/checkout@v7"),
        },
    )
    assert result.returncode == 0, result.stderr
    assert "1 file(s)" in result.stdout


def test_annotations_in_github_actions(tmp_path: Path) -> None:
    file = tmp_path / ".github/workflows/ci.yml"
    file.parent.mkdir(parents=True)
    file.write_text(_workflow("actions/checkout@v7"))
    env = {"PATH": "/usr/bin:/bin", "GITHUB_ACTIONS": "true"}
    result = subprocess.run([sys.executable, str(SCRIPT), str(tmp_path)], capture_output=True, text=True, env=env)
    assert result.returncode == 1
    assert "::error file=.github/workflows/ci.yml,line=7::uses actions/checkout@v7 by a tag" in result.stderr


def test_no_workflow_fails(tmp_path: Path) -> None:
    result = _run(tmp_path, {"README.md": "no workflows\n"})
    assert result.returncode == 1
    assert "no workflow or composite action" in result.stderr
