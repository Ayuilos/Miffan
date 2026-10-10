#!/usr/bin/env python3
"""Copy only source/header/license files; never mutate the offline dependencies."""
from pathlib import Path
import shutil
import subprocess
import sys

deps, dest, patches = map(Path, sys.argv[1:])
if dest.exists():
    shutil.rmtree(dest)
for project, source in [("common-c", deps / "common-c"),
                        ("libgamestream", deps / "embedded/libgamestream")]:
    roots = [source / "src", source / "enet", source / "nanors"] if project == "common-c" else [source]
    for root in roots:
        if not root.is_dir():
            raise SystemExit(f"Missing offline dependency: {root}")
        for path in root.rglob("*"):
            if path.is_file() and not path.is_symlink() and (path.suffix in (".c", ".h") or path.name.startswith("LICENSE")):
                target = dest / project / path.relative_to(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
for patch in sorted(patches.glob("*.patch")):
    project = "common-c" if "split-transport" in patch.name else "libgamestream"
    subprocess.run(["git", "apply", "--check", str(patch.resolve())], cwd=dest / project, check=True)
    subprocess.run(["git", "apply", str(patch.resolve())], cwd=dest / project, check=True)
