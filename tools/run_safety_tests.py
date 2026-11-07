#!/usr/bin/env python3
"""Build and run the production launcher safety policy tests on the host."""
from __future__ import annotations

import os
from pathlib import Path
import subprocess

from run_profile_tests import compiler_path


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    output = root / "build/safety-tests"
    output.mkdir(parents=True, exist_ok=True)
    executable = output / ("launcher_safety_test.exe" if os.name == "nt" else "launcher_safety_test")
    compiler = compiler_path(os.environ.get("CXX"))
    env = os.environ.copy()
    env["PATH"] = str(compiler.parent) + os.pathsep + env.get("PATH", "")
    subprocess.run([
        str(compiler), "-std=c++17", "-O2", "-Wall", "-Wextra", "-Wpedantic", "-Werror",
        "-fno-exceptions", "-fno-rtti", "-I", str(root / "app/src/main/cpp"),
        str(root / "app/src/test/cpp/launcher_safety_test.cpp"), "-o", str(executable),
    ], cwd=root, env=env, check=True)
    subprocess.run([str(executable)], cwd=root, env=env, check=True)


if __name__ == "__main__":
    main()
