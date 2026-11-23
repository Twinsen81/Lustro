#!/usr/bin/env python3
"""Fail when a module with instrumented tests ran none of them in connectedCheck.

Usage: check_connected_results.py [ROOT]

ROOT is the root of the Gradle build; the default is the root of the repository
that holds this script. Run it after ./gradlew connectedCheck.

A module has instrumented tests when its src/androidTest directory has a .kt or a
.java file. Each such module must have a JUnit XML result, a TEST-*.xml file under
build/outputs/androidTest-results/connected/, and each of its result files must
have at least one test. connectedCheck writes one result file for each device.

connectedCheck can pass with no test run: when the install of the test APK failed
with INSTALL_FAILED_ALREADY_EXISTS, AGP reported BUILD SUCCESSFUL and 0 tests.

The search for modules skips build directories, hidden directories, and each
directory that has its own settings.gradle or settings.gradle.kts, such as
build-logic/ and consumer-check/: that is another build, and connectedCheck of
ROOT does not run its tests. A skipped test counts as a test.

Python standard library only.
"""

from __future__ import annotations

import os
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import List, NamedTuple, Optional

TESTS = Path("src") / "androidTest"
RESULTS = Path("build") / "outputs" / "androidTest-results" / "connected"
SOURCE_SUFFIXES = (".kt", ".java")
SETTINGS = ("settings.gradle", "settings.gradle.kts")
SKIPPED_DIRECTORIES = {"build", "node_modules", "src"}


class CheckError(Exception):
    def __init__(self, message: str, details: Optional[List[str]] = None) -> None:
        super().__init__(message)
        self.details = details or []


class Result(NamedTuple):
    path: Path
    tests: Optional[int]
    error: Optional[str]


class Module(NamedTuple):
    name: str
    results: List[Result]

    def problem(self) -> Optional[str]:
        """Why the module fails the check, or None when it passes."""
        if not self.results:
            return "{}: no TEST-*.xml under {}".format(self.name, (Path(self.name) / RESULTS).as_posix())
        bad = []
        for result in self.results:
            if result.error is not None:
                bad.append("{} cannot be read: {}".format(result.path.name, result.error))
            elif result.tests == 0:
                bad.append("0 tests in {}".format(result.path.name))
        if not bad:
            return None
        return "{}: {}".format(self.name, "; ".join(bad))

    def summary(self) -> str:
        tests = sum(result.tests or 0 for result in self.results)
        return "{}: {} test(s) in {} result file(s)".format(self.name, tests, len(self.results))


def _has_source(directory: Path) -> bool:
    for _, _, files in os.walk(directory):
        if any(name.endswith(SOURCE_SUFFIXES) for name in files):
            return True
    return False


def _modules(root: Path) -> List[Path]:
    """The directories under ``root``, ``root`` too, whose src/androidTest has a source file."""
    found = []
    for current, directories, _ in os.walk(root):
        path = Path(current)
        if path != root and any((path / name).is_file() for name in SETTINGS):
            directories[:] = []
            continue
        if (path / TESTS).is_dir() and _has_source(path / TESTS):
            found.append(path)
        directories[:] = sorted(
            name for name in directories if name not in SKIPPED_DIRECTORIES and not name.startswith(".")
        )
    return found


def _read(path: Path) -> Result:
    try:
        tree = ElementTree.parse(str(path))
    except (ElementTree.ParseError, OSError) as exc:
        return Result(path, None, str(exc))
    return Result(path, sum(1 for _ in tree.getroot().iter("testcase")), None)


def _module(root: Path, directory: Path) -> Module:
    name = directory.relative_to(root).as_posix()
    if name == ".":
        name = root.name
    results = sorted((directory / RESULTS).rglob("TEST-*.xml"))
    return Module(name, [_read(path) for path in results if path.is_file()])


def check(root: Path) -> List[str]:
    """One line for each module, when the check passes. Raises CheckError when it fails."""
    directories = _modules(root)
    if not directories:
        raise CheckError(
            "no module under {} has a .kt or .java file in {}: check the ROOT argument".format(root, TESTS.as_posix())
        )
    modules = [_module(root, directory) for directory in directories]
    failed = [(module, module.problem()) for module in modules]
    failed = [(module, problem) for module, problem in failed if problem is not None]
    if failed:
        raise CheckError(
            "connectedCheck ran no instrumented test in {} of {} module(s) with {}: {}".format(
                len(failed), len(modules), TESTS.as_posix(), ", ".join(module.name for module, _ in failed)
            ),
            [problem for _, problem in failed],
        )
    return [module.summary() for module in modules]


def main(argv: List[str]) -> int:
    if len(argv) > 1:
        print("usage: check_connected_results.py [ROOT]", file=sys.stderr)
        return 2
    root = Path(argv[0]) if argv else Path(__file__).resolve().parent.parent.parent
    root = root.resolve()
    try:
        lines = check(root)
    except CheckError as exc:
        prefix = "::error::" if os.environ.get("GITHUB_ACTIONS") == "true" else ""
        print("{}check_connected_results FAILED: {}.".format(prefix, exc), file=sys.stderr)
        for detail in exc.details:
            print("  {}".format(detail), file=sys.stderr)
        return 1
    print("check_connected_results: OK: each module with instrumented tests ran them.")
    for line in lines:
        print("  {}".format(line))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
