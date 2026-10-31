#!/usr/bin/env python3
"""Compile and run the real Profile parser/encoder on the host, without an Android device.

Requires a C++17 GCC/Clang toolchain. --sanitize enables ASan/UBSan where supported.
Build products remain in build/profile-tests, outside the tracked source tree.
"""
from __future__ import annotations

import argparse
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
    raise SystemExit("Install a host C++17 compiler or pass --cxx / set CXX. No device toolchain is used.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default=os.environ.get("CXX"), help="Path to a host g++ or clang++ compiler")
    parser.add_argument("--sanitize", action="store_true", help="Compile with AddressSanitizer and UndefinedBehaviorSanitizer")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output_dir = root / "build/profile-tests"
    output_dir.mkdir(parents=True, exist_ok=True)
    executable = output_dir / ("hyperos4_profile_test.exe" if os.name == "nt" else "hyperos4_profile_test")
    cxx = compiler_path(args.cxx)
    env = os.environ.copy()
    env["PATH"] = str(cxx.parent) + os.pathsep + env.get("PATH", "")
    command = [str(cxx), "-std=c++17", "-O2", "-Wall", "-Wextra", "-Wpedantic", "-Werror", "-fno-exceptions", "-fno-rtti"]
    if args.sanitize:
        command += ["-g", "-fno-omit-frame-pointer", "-fsanitize=address,undefined"]
    command += [
        "-I", str(root / "app/src/main/cpp"),
        str(root / "app/src/main/cpp/hyperos4_profile.cpp"),
        str(root / "app/src/test/cpp/hyperos4_profile_test.cpp"),
        "-o", str(executable),
    ]
    print(f"Building Profile host tests with {cxx}", flush=True)
    subprocess.run(command, cwd=root, env=env, check=True)
    subprocess.run([str(executable), str(root / "app/src/main/assets/hyperos4/profiles/hyperos4-6144.json")], cwd=root, env=env, check=True)


if __name__ == "__main__":
    main()
