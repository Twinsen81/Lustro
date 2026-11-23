"""Tests of check_connected_results.py, in a new directory for each test.

CI runs them in the job of the CLI's tests: python -m pytest -q .github/scripts
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from typing import Dict, Optional

SCRIPT = Path(__file__).resolve().parent / "check_connected_results.py"
RESULTS = "build/outputs/androidTest-results/connected/debug"
TEST_SOURCE = "src/androidTest/kotlin/com/example/ExampleTest.kt"

# What connectedCheck of AGP 8 wrote on an API 35 emulator: a run of 2 tests, and a
# run in which the runner found no test (an annotation filter that no test has).
TWO_TESTS = """<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="2" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-11-23T15:31:04" hostname="localhost">
  <testsuite name="com.example.ExampleTest" tests="2" failures="0" errors="0" skipped="0" time="0.185" timestamp="2026-11-23T15:31:04" hostname="localhost">
    <properties>
      <property name="device" value="emulator-5554" />
    </properties>
    <testcase name="first" classname="com.example.ExampleTest" time="0.008" />
    <testcase name="second" classname="com.example.ExampleTest" time="0.006" />
  </testsuite>
</testsuites>
"""
NO_TESTS = """<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="0" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-11-23T15:31:21" hostname="localhost" />
"""
DEVICE_RESULT = RESULTS + "/TEST-test(AVD) - 15.xml"


def _run(root: Path, files: Dict[str, str], env: Optional[Dict[str, str]] = None) -> subprocess.CompletedProcess:
    for path, text in files.items():
        file = root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)
    return subprocess.run(
        [sys.executable, str(SCRIPT), str(root)],
        capture_output=True,
        text=True,
        env={"PATH": "/usr/bin:/bin", **(env or {})},
    )


def _module(name: str, result: Optional[str] = TWO_TESTS) -> Dict[str, str]:
    files = {"{}/build.gradle.kts".format(name): "", "{}/{}".format(name, TEST_SOURCE): "class ExampleTest\n"}
    if result is not None:
        files["{}/{}".format(name, DEVICE_RESULT)] = result
    return files


def test_a_module_with_a_result_with_tests_passes(tmp_path: Path) -> None:
    result = _run(tmp_path, _module("lustro"))
    assert result.returncode == 0, result.stderr
    assert "lustro: 2 test(s) in 1 result file(s)" in result.stdout


def test_a_module_without_a_result_file_fails_with_its_name(tmp_path: Path) -> None:
    result = _run(tmp_path, {**_module("lustro"), **_module("lustro-logs", result=None)})
    assert result.returncode == 1
    assert "in 1 of 2 module(s) with src/androidTest: lustro-logs." in result.stderr
    assert "lustro-logs: no TEST-*.xml under lustro-logs/build/outputs/androidTest-results/connected" in result.stderr
    assert "lustro:" not in result.stderr


def test_a_result_with_0_tests_fails_with_the_module_name(tmp_path: Path) -> None:
    result = _run(tmp_path, _module("lustro", result=NO_TESTS))
    assert result.returncode == 1
    assert "with src/androidTest: lustro." in result.stderr
    assert "lustro: 0 tests in TEST-test(AVD) - 15.xml" in result.stderr


def test_a_testsuite_root_with_testcases_counts(tmp_path: Path) -> None:
    suite = '<testsuite name="s" tests="1"><testcase name="a" classname="A" /></testsuite>\n'
    result = _run(tmp_path, {**_module("lustro", result=None), "lustro/build/outputs/androidTest-results/connected/TEST-x.xml": suite})
    assert result.returncode == 0, result.stderr
    assert "lustro: 1 test(s) in 1 result file(s)" in result.stdout


def test_a_skipped_test_counts_as_a_test(tmp_path: Path) -> None:
    suite = '<testsuites tests="1" skipped="1"><testsuite name="s"><testcase name="a" classname="A"><skipped /></testcase></testsuite></testsuites>\n'
    result = _run(tmp_path, _module("lustro", result=suite))
    assert result.returncode == 0, result.stderr


def test_each_device_result_needs_a_test(tmp_path: Path) -> None:
    files = {**_module("lustro"), "lustro/{}/TEST-test(AVD) - 7.0.xml".format(RESULTS): NO_TESTS}
    result = _run(tmp_path, files)
    assert result.returncode == 1
    assert "lustro: 0 tests in TEST-test(AVD) - 7.0.xml" in result.stderr


def test_a_result_that_is_not_xml_fails(tmp_path: Path) -> None:
    result = _run(tmp_path, _module("lustro", result="not xml"))
    assert result.returncode == 1
    assert "lustro: TEST-test(AVD) - 15.xml cannot be read" in result.stderr


def test_modules_without_instrumented_test_sources_are_not_checked(tmp_path: Path) -> None:
    files = {
        **_module("lustro"),
        "sample/build.gradle.kts": "",
        "sample/src/main/kotlin/Main.kt": "",
        "lustro-noop/src/androidTest/res/values/strings.xml": "<resources />\n",
    }
    result = _run(tmp_path, files)
    assert result.returncode == 0, result.stderr
    assert "sample" not in result.stdout
    assert "lustro-noop" not in result.stdout


def test_included_builds_hidden_and_build_directories_are_not_checked(tmp_path: Path) -> None:
    files = {
        **_module("lustro"),
        "consumer-check/settings.gradle.kts": "",
        **_module("consumer-check/app", result=None),
        **_module(".context/copy", result=None),
        **_module("lustro/build/generated", result=None),
    }
    result = _run(tmp_path, files)
    assert result.returncode == 0, result.stderr
    assert "lustro: 2 test(s)" in result.stdout


def test_a_nested_module_is_checked(tmp_path: Path) -> None:
    result = _run(tmp_path, {**_module("lustro"), **_module("plugins/lustro-x", result=NO_TESTS)})
    assert result.returncode == 1
    assert "with src/androidTest: plugins/lustro-x." in result.stderr


def test_a_root_without_instrumented_tests_fails(tmp_path: Path) -> None:
    result = _run(tmp_path, {"sample/build.gradle.kts": ""})
    assert result.returncode == 1
    assert "no module under" in result.stderr
    assert "check the ROOT argument" in result.stderr


def test_github_actions_gets_an_error_annotation(tmp_path: Path) -> None:
    result = _run(tmp_path, _module("lustro", result=None), env={"GITHUB_ACTIONS": "true"})
    assert result.returncode == 1
    assert result.stderr.startswith("::error::check_connected_results FAILED:")


def test_more_than_one_argument_is_a_usage_error(tmp_path: Path) -> None:
    result = subprocess.run([sys.executable, str(SCRIPT), "a", "b"], capture_output=True, text=True)
    assert result.returncode == 2
    assert "usage:" in result.stderr
