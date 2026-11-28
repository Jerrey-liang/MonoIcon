#!/usr/bin/env python3
"""Build and run the production launcher safety policy tests on the host."""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess


def compiler_path(requested: str | None) -> Path:
    if requested:
        found = shutil.which(requested) or (requested if Path(requested).is_file() else None)
        if found:
            return Path(found).resolve()
        raise SystemExit(f"C++ compiler not found: {requested}")
    for name in ("g++", "clang++"):
        found = shutil.which(name)
        if found:
            return Path(found).resolve()
    if os.name == "nt":
        codeblocks = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "CodeBlocks/MinGW/bin/g++.exe"
        if codeblocks.is_file():
            return codeblocks
    raise SystemExit("Install a host C++17 compiler or set CXX.")


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
