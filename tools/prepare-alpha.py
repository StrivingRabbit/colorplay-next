#!/usr/bin/env python3
"""Prepare a local Alpha candidate. Never push, install or publish anything."""
import argparse
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(os.environ.get("ALPHA_REPO_DIR", Path(__file__).resolve().parents[1])).resolve()
META = "app/src/main/java/io/github/hypercar/UpstreamBase.kt"
PROTECTED = [".github", "tools", "version.properties", META, "AGENTS.md"]


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()


def properties(text):
    return dict(line.strip().split("=", 1) for line in text.splitlines()
                if "=" in line and not line.lstrip().startswith("#"))


def next_version(alpha, main):
    stable = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", main["versionName"])
    if not stable:
        raise ValueError("origin/main must have a stable X.Y.Z version")
    major, minor, patch = map(int, stable.groups())
    core = f"{major}.{minor}.{patch + 1}"
    previous = re.fullmatch(r"(\d+\.\d+\.\d+)-alpha\.(\d+)", alpha["versionName"])
    if previous and tuple(map(int, previous[1].split("."))) >= (major, minor, patch + 1):
        core, sequence = previous[1], int(previous[2]) + 1
    else:
        sequence = 1
    code = max(int(alpha["versionCode"]), int(main["versionCode"])) + 1
    if code > 2_100_000_000:
        raise ValueError("Android versionCode exhausted")
    return f"{core}-alpha.{sequence}", code


def prepare():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", default="refs/remotes/upstream/main")
    args = parser.parse_args()
    if git("branch", "--show-current") != "alpha":
        raise RuntimeError("Only the alpha branch may prepare candidates")
    if git("status", "--porcelain"):
        raise RuntimeError("Working tree must be clean")
    if git("remote", "get-url", "upstream") != "https://github.com/fw867/hypercar.git":
        raise RuntimeError("Unexpected upstream remote")
    target = git("rev-parse", "--verify", args.upstream + "^{commit}")
    original = git("rev-parse", "HEAD")
    metadata = (ROOT / META).read_text()
    base = re.search(r'const val COMMIT = "([0-9a-f]{40})"', metadata)[1]
    git("merge-base", "--is-ancestor", base, target)
    git("merge-base", "--is-ancestor", base, original)
    old = properties((ROOT / "version.properties").read_text())
    main = properties(git("show", "origin/main:version.properties"))
    bootstrap = "-alpha." not in old["versionName"]
    changed = target != base or bootstrap
    output = Path(os.environ["GITHUB_OUTPUT"]) if os.environ.get("GITHUB_OUTPUT") else None
    if not changed:
        retry = os.environ.get("ALPHA_RETRY_UNPUBLISHED") == "true"
        if output:
            with output.open("a") as f:
                if retry:
                    f.write(f"changed=true\nversion={old['versionName']}\ncode={old['versionCode']}\nupstream={base}\n")
                else:
                    f.write("changed=false\n")
        print("Retry existing unpublished candidate; version unchanged." if retry else "No upstream changes. No candidate created.")
        return
    saved = {p: git("rev-parse", f"HEAD:{p}") for p in PROTECTED}
    try:
        result = subprocess.run(["git", "-C", str(ROOT), "merge", "--no-ff", "--no-commit", target])
        if result.returncode:
            conflicts = git("diff", "--name-only", "--diff-filter=U").splitlines()
            unresolved = [p for p in conflicts if not any(p == owned or p.startswith(owned + "/") for owned in PROTECTED)]
            if unresolved or not conflicts:
                raise RuntimeError("Merge conflict; manual resolution required:\n" + "\n".join(unresolved or ["merge failed"]))
            # Only the explicitly owned automation/version paths are deterministically preserved.
            # Protocol, UI and other source conflicts always require human review.
        git("restore", "--source=" + original, "--staged", "--worktree", "--", *PROTECTED)
        candidate_tree = git("write-tree")
        for path, blob in saved.items():
            if git("rev-parse", candidate_tree + ":" + path) != blob:
                raise RuntimeError("Protected path changed: " + path)
        upstream = properties(git("show", f"{target}:version.properties"))
        metadata = re.sub(r'const val VERSION = "[^"]+"', 'const val VERSION = "' + upstream["versionName"] + '"', metadata)
        metadata = re.sub(r'const val CODE = \d+', 'const val CODE = ' + upstream["versionCode"], metadata)
        metadata = re.sub(r'const val COMMIT = "[0-9a-f]+"', 'const val COMMIT = "' + target + '"', metadata)
        (ROOT / META).write_text(metadata)
        version, code = next_version(old, main)
        (ROOT / "version.properties").write_text(f"# Automatically prepared Alpha candidate; never reuse a published code.\nversionName={version}\nversionCode={code}\n")
        git("add", META, "version.properties")
        git("diff", "--cached", "--check")
        git("commit", "-m", f"chore: prepare {version} with HyperCar {upstream['versionName']} ({target[:12]})")
    except Exception:
        # Abort only the merge created by this invocation; never discard user work.
        if (ROOT / ".git/MERGE_HEAD").exists():
            subprocess.run(["git", "-C", str(ROOT), "merge", "--abort"], check=True)
        raise
    if output:
        with output.open("a") as f:
            f.write(f"changed=true\nversion={version}\ncode={code}\nupstream={target}\n")
    print(f"Prepared {version}/{code}; original={original}; upstream={target}")
    print("Local candidate only. Tests and signing must succeed before push or prerelease.")


if __name__ == "__main__":
    prepare()
