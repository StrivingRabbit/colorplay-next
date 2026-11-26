import importlib.util
from pathlib import Path
import unittest
import tempfile
import subprocess
import os

spec = importlib.util.spec_from_file_location("prepare_alpha", Path(__file__).with_name("prepare-alpha.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AlphaVersionTest(unittest.TestCase):
    def test_bootstrap_targets_next_stable_patch(self):
        stable = {"versionName": "0.2.8", "versionCode": "208"}
        self.assertEqual(("0.2.9-alpha.1", 209), module.next_version(stable, stable))

    def test_sequence_and_install_code_are_monotonic(self):
        self.assertEqual(("0.2.9-alpha.12", 230), module.next_version(
            {"versionName": "0.2.9-alpha.11", "versionCode": "229"},
            {"versionName": "0.2.8", "versionCode": "208"}))

    def test_new_stable_moves_alpha_to_next_patch(self):
        self.assertEqual(("0.3.1-alpha.1", 401), module.next_version(
            {"versionName": "0.2.9-alpha.2", "versionCode": "210"},
            {"versionName": "0.3.0", "versionCode": "400"}))

    def test_nonstable_main_is_rejected(self):
        with self.assertRaises(ValueError):
            module.next_version({"versionName": "0.2.9-alpha.2", "versionCode": "210"},
                                {"versionName": "0.3.0-beta.1", "versionCode": "400"})


class AlphaMergeTest(unittest.TestCase):
    def test_merge_preserves_fork_automation_and_records_actual_upstream(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(["git", "-C", directory, *args], text=True, stderr=subprocess.PIPE).strip()
            git("init", "-b", "main")
            git("config", "user.name", "Alpha Test")
            git("config", "user.email", "alpha@example.test")
            for folder in (".github", "tools", "app/src/main/java/io/github/hypercar"):
                (root / folder).mkdir(parents=True, exist_ok=True)
            (root / ".github/workflow").write_text("trusted")
            (root / "tools/helper").write_text("trusted")
            (root / "AGENTS.md").write_text("fork policy")
            (root / "version.properties").write_text("versionName=0.1.0\nversionCode=100\n")
            (root / module.META).write_text('const val VERSION = "0.1.0"\nconst val CODE = 100\nconst val COMMIT = "' + "0" * 40 + '"\n')
            git("add", ".")
            git("commit", "-m", "base")
            base = git("rev-parse", "HEAD")
            git("checkout", "-b", "upstream-test")
            (root / "protocol.txt").write_text("new protocol")
            (root / ".github/workflow").write_text("upstream workflow must not execute")
            (root / "version.properties").write_text("versionName=0.1.1\nversionCode=101\n")
            git("add", ".")
            git("commit", "-m", "upstream")
            target = git("rev-parse", "HEAD")
            git("checkout", "-b", "alpha", base)
            metadata = (root / module.META).read_text().replace("0" * 40, base)
            (root / module.META).write_text(metadata)
            (root / "version.properties").write_text("versionName=0.2.8\nversionCode=208\n")
            git("add", ".")
            git("commit", "-m", "fork metadata")
            git("update-ref", "refs/remotes/origin/main", "HEAD")
            git("update-ref", "refs/remotes/upstream/main", target)
            git("remote", "add", "upstream", "https://github.com/fw867/hypercar.git")
            result = subprocess.run(["python3", str(Path(module.__file__)), "--upstream", "refs/remotes/upstream/main"],
                env={**os.environ, "ALPHA_REPO_DIR": directory}, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual("trusted", (root / ".github/workflow").read_text())
            self.assertTrue((root / "protocol.txt").exists())
            self.assertIn(target, (root / module.META).read_text())
            self.assertIn("alpha.1", (root / "version.properties").read_text())
            self.assertEqual("", git("status", "--porcelain"))
            git("merge-base", "--is-ancestor", target, "HEAD")
            candidate = git("rev-parse", "HEAD")
            output = root / ".git/outputs"
            retry = subprocess.run(["python3", str(Path(module.__file__))],
                env={**os.environ, "ALPHA_REPO_DIR": directory, "ALPHA_RETRY_UNPUBLISHED": "true", "GITHUB_OUTPUT": str(output)},
                capture_output=True, text=True)
            self.assertEqual(0, retry.returncode, retry.stderr)
            self.assertIn("changed=true", output.read_text())
            self.assertEqual(candidate, git("rev-parse", "HEAD"))
            output.write_text("")
            no_changes = subprocess.run(["python3", str(Path(module.__file__))],
                env={**os.environ, "ALPHA_REPO_DIR": directory, "ALPHA_RETRY_UNPUBLISHED": "false", "GITHUB_OUTPUT": str(output)},
                capture_output=True, text=True)
            self.assertEqual(0, no_changes.returncode, no_changes.stderr)
            self.assertIn("changed=false", output.read_text())


if __name__ == "__main__":
    unittest.main()
