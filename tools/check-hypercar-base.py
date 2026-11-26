#!/usr/bin/env python3
"""Audit a HyperCar base update without merging or changing tracked files."""
import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_REMOTE = "https://github.com/fw867/hypercar.git"


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fetch", action="store_true", help="Fetch upstream/main before comparing")
    parser.add_argument("--target", default="refs/remotes/upstream/main")
    args = parser.parse_args()
    metadata = (ROOT / "app/src/main/java/io/github/hypercar/UpstreamBase.kt").read_text()
    base = re.search(r'const val COMMIT = "([0-9a-f]{40})"', metadata).group(1)
    version = re.search(r'const val VERSION = "([^"]+)"', metadata).group(1)
    code = re.search(r'const val CODE = (\d+)', metadata).group(1)
    actual = dict(line.split("=", 1) for line in git("show", f"{base}:version.properties").splitlines()
                  if "=" in line and not line.lstrip().startswith("#"))
    if (actual.get("versionName"), actual.get("versionCode")) != (version, code):
        raise RuntimeError("UpstreamBase.kt does not match the pinned commit's version.properties")
    subprocess.run(["git", "-C", str(ROOT), "merge-base", "--is-ancestor", base, "HEAD"], check=True)
    if args.fetch:
        remote = git("remote", "get-url", "upstream")
        if remote != EXPECTED_REMOTE:
            raise RuntimeError(f"Unexpected upstream remote: {remote}")
        subprocess.run(["git", "-C", str(ROOT), "fetch", "upstream", "main"], check=True)
    target = git("rev-parse", "--verify", args.target + "^{commit}")
    subprocess.run(["git", "-C", str(ROOT), "merge-base", "--is-ancestor", base, target], check=True)
    print(f"Imported HyperCar: {version}/{code} @ {base}")
    print(f"Candidate: {target}")
    print("Candidate version:\n" + git("show", f"{target}:version.properties"))
    print("Upstream changes:\n" + (git("log", "--oneline", f"{base}..{target}") or "(none)"))
    print("Fork delta:\n" + git("diff", "--stat", f"{base}..HEAD"))
    print("Working tree:\n" + (git("status", "--short") or "clean"))
    print("No merge, base-metadata update, installation or publication was performed.")
    print("Upgrade on a separate branch; resolve the small session/UI hooks, run regression tests,")
    print("then update UpstreamBase.kt and publish an APK with the original signing key.")


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, AttributeError) as error:
        print(f"Base audit stopped: {error}", file=sys.stderr)
        sys.exit(1)
