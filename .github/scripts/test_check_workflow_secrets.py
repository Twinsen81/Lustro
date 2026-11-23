"""Tests of check_workflow_secrets.py, on workflows that each test writes, and on this repository's.

CI runs them in the job of the CLI's tests: python -m pytest -q .github/scripts
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_workflow_secrets as cws  # noqa: E402

WORKFLOWS = Path(__file__).resolve().parents[1] / "workflows"

BUILD = """\
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - name: Set up JDK + Gradle
        uses: ./.github/actions/setup
      - run: ./gradlew publishAllPublicationsToStagingRepository
"""

PUBLISH = """\
  publish:
    needs: build
    runs-on: ubuntu-latest
    environment: release
    steps:
      - name: Sign
        env:
          SIGNING_KEY: ${{ secrets.SIGNING_IN_MEMORY_KEY }}
        run: python3 .github/scripts/central_publish.py prepare --properties gradle.properties
"""


def problems(tmp_path: Path, text: str):
    path = tmp_path / "workflow.yml"
    path.write_text(text)
    return cws.check(path)


def test_a_secret_in_a_job_without_gradle_passes(tmp_path):
    assert problems(tmp_path, "on: push\njobs:\n" + BUILD + "\n" + PUBLISH) == []


@pytest.mark.parametrize(
    "secret",
    [
        "${{ secrets.MAVEN_CENTRAL_PASSWORD }}",
        "${{ secrets['MAVEN_CENTRAL_PASSWORD'] }}",
        "${{ toJSON(secrets) }}",
    ],
)
def test_a_secret_in_a_job_that_runs_gradle_fails(tmp_path, secret):
    job = BUILD + f"        env:\n          TOKEN: {secret}\n"
    found = problems(tmp_path, "on: push\njobs:\n" + job + PUBLISH)
    assert found == [f"{tmp_path / 'workflow.yml'}:3: job 'build' runs Gradle and references a secret"]


@pytest.mark.parametrize(
    "gradle",
    [
        "      - run: ./gradlew build",
        "      - run: cd consumer-check && ../gradlew :app:assemble",
        "      - run: gradle build",
        "      - uses: gradle/actions/setup-gradle@v6",
        "      - uses: './.github/actions/setup'",
        "      - uses: reactivecircus/android-emulator-runner@v2\n        with:\n          script: ./gradlew connectedCheck",
    ],
)
def test_each_way_to_run_gradle_counts(tmp_path, gradle):
    job = "  job:\n    runs-on: ubuntu-latest\n    steps:\n" + gradle + "\n      - run: echo ${{ secrets.X }}\n"
    assert len(problems(tmp_path, "jobs:\n" + job)) == 1


def test_gradle_properties_and_the_word_gradle_in_prose_do_not_count(tmp_path):
    job = (
        "  publish:\n    runs-on: ubuntu-latest\n    steps:\n"
        "      # Runs no Gradle: it reads gradle.properties only.\n"
        "      - run: grep GROUP gradle.properties\n"
        "        env:\n          KEY: ${{ secrets.KEY }}\n"
    )
    assert problems(tmp_path, "jobs:\n" + job) == []


def test_a_secret_in_a_comment_of_a_gradle_job_fails(tmp_path):
    # GitHub replaces the expression in a comment of a run script too.
    job = BUILD + "      - run: |\n          # ${{ secrets.KEY }}\n          echo done\n"
    assert len(problems(tmp_path, "jobs:\n" + job)) == 1


def test_secrets_inherit_in_a_gradle_job_fails(tmp_path):
    job = BUILD.replace("    runs-on: ubuntu-latest\n", "    runs-on: ubuntu-latest\n    secrets: inherit\n")
    assert len(problems(tmp_path, "jobs:\n" + job)) == 1


def test_prose_about_secrets_passes(tmp_path):
    job = BUILD.replace("    steps:\n", "    # This job gets no secrets. Only the publish job has secrets.\n    steps:\n")
    assert problems(tmp_path, "jobs:\n" + job) == []


def test_a_secret_in_the_workflow_env_reaches_the_gradle_jobs(tmp_path):
    text = "on: push\nenv:\n  KEY: ${{ secrets.KEY }}\njobs:\n" + BUILD + PUBLISH
    assert problems(tmp_path, text) == [
        f"{tmp_path / 'workflow.yml'}: a secret outside `jobs:` reaches the jobs that run Gradle: 'build'"
    ]


def test_the_secrets_of_a_reusable_workflow_trigger_are_no_reference(tmp_path):
    text = "on:\n  workflow_call:\n    secrets:\n      KEY:\n        required: true\njobs:\n" + BUILD
    assert problems(tmp_path, text) == []


def test_an_alias_in_a_gradle_job_fails(tmp_path):
    text = "jobs:\n" + PUBLISH.replace("        env:\n", "        env: &keys\n") + BUILD + "        env: *keys\n"
    assert problems(tmp_path, text) == [
        f"{tmp_path / 'workflow.yml'}:11: job 'build' runs Gradle and uses a YAML alias"
    ]


def test_jobs_with_another_indentation_and_a_key_after_jobs(tmp_path):
    text = (
        "jobs:\n"
        "    build:\n        runs-on: ubuntu-latest\n        steps:\n          - run: ./gradlew build\n"
        "    other:\n        runs-on: ubuntu-latest\n        steps:\n          - run: echo ${{ secrets.X }}\n"
        "env:\n  K: ${{ secrets.Y }}\n"
    )
    assert problems(tmp_path, text) == [
        f"{tmp_path / 'workflow.yml'}: a secret outside `jobs:` reaches the jobs that run Gradle: 'build'"
    ]


def test_the_workflows_of_this_repository_pass(capsys):
    assert cws.main(["check_workflow_secrets.py", str(WORKFLOWS)]) == 0
    assert "No job that runs Gradle references a secret" in capsys.readouterr().out


def test_the_check_finds_the_gradle_jobs_of_this_repository():
    # The check would pass for nothing if it missed the jobs that run Gradle.
    for name, jobs in {"ci.yml": {"gradle", "consumer", "emulator"}, "release.yml": {"build"},
                       "snapshot.yml": {"build"}}.items():
        _, found = cws.split_jobs((WORKFLOWS / name).read_text())
        assert {job.name for job in found if cws.GRADLE.search(job.text)} == jobs, name
