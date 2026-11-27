import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('official', Path(__file__).with_name('prepare-official-release.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def release(rid, tag=None, **values):
    return dict(id=rid, tag_name=tag or f'v1.0.{rid}', draft=False, prerelease=False,
                published_at=f'2026-11-{rid:02}T00:00:00Z', **values)


class SelectionTest(unittest.TestCase):
    def test_baseline_and_completed_are_not_republished(self):
        state = {'ignored': [1], 'completed': [2]}
        self.assertIsNone(module.choose([release(1), release(2)], state))

    def test_oldest_new_formal_release_first(self):
        draft = release(3)
        draft['draft'] = True
        alpha = release(4)
        alpha['prerelease'] = True
        self.assertEqual(2, module.choose([release(5), release(2), draft, alpha], {'ignored': [], 'completed': []})['id'])

    def test_manual_backfill_requires_explicit_permission(self):
        state = {'ignored': [1], 'completed': []}
        with self.assertRaises(ValueError):
            module.choose([release(1)], state, 'v1.0.1')
        self.assertEqual(1, module.choose([release(1)], state, 'v1.0.1', True)['id'])
        state['completed'] = [1]
        self.assertIsNone(module.choose([release(1)], state, 'v1.0.1', True))

    def test_missing_or_prerelease_tag_is_rejected(self):
        alpha = release(1)
        alpha['prerelease'] = True
        with self.assertRaises(ValueError):
            module.choose([alpha], {'ignored': [], 'completed': []}, alpha['tag_name'], True)

    def test_version_exceeds_every_stable_and_alpha(self):
        version, code = module.next_version({'versionName': '0.2.12', 'versionCode': '212'}, [
            {'versionName': '0.2.9', 'versionCode': '209'},
            {'versionName': '0.4.0-alpha.7', 'versionCode': '999'},
            {'versionName': '0.3.8', 'versionCode': '600'}])
        self.assertEqual(('0.4.0', 1000), (version, code))

    def test_release_pagination_does_not_drop_second_page(self):
        with patch.object(module, 'api', return_value=[[release(1)], [release(2)]]):
            self.assertEqual([1, 2], [r['id'] for r in module.releases(module.UPSTREAM)])

    def test_annotated_tag_uses_peeled_commit_and_detects_retag(self):
        state = {'observed': {}}
        tag_object = '1' * 40
        commit = '2' * 40
        refs = f'{tag_object}\trefs/tags/v1.0.1\n{commit}\trefs/tags/v1.0.1^{{}}'
        with patch.object(module, 'git', return_value=refs):
            module.observe_tags([release(1)], state)
        self.assertEqual(commit, state['observed']['1']['commit'])
        with patch.object(module, 'git', return_value=f'{tag_object}\trefs/tags/v1.0.1'):
            with self.assertRaisesRegex(RuntimeError, '移动或删除'):
                module.observe_tags([release(1)], state)

    def test_legacy_four_part_version_keeps_install_number_floor(self):
        self.assertEqual(('0.2.13', 10733), module.next_version(
            {'versionName': '0.2.12', 'versionCode': '212'},
            [{'versionName': '0.1.0.636', 'versionCode': '10732'}]))

    def test_pending_wins_over_ignored_backfill_and_new_earlier_release(self):
        state = {'ignored': [1, 2], 'completed': []}
        record = {'release_id': 2, 'tag': 'v1.0.2'}
        earlier = release(3)
        earlier['published_at'] = '2020-01-01T00:00:00Z'
        self.assertEqual(2, module.select_pending([release(1), release(2), earlier], state, record)['id'])
        with self.assertRaisesRegex(RuntimeError, '先恢复'):
            module.select_pending([release(1), release(2), earlier], state, record, 'v1.0.3', True)

    def test_overflow_and_malformed_version_fail_closed(self):
        with self.assertRaises(ValueError):
            module.next_version({'versionName': '0.2.12', 'versionCode': '2100000000'}, [])
        with self.assertRaises(ValueError):
            module.next_version({'versionName': '0.2.12-alpha.1', 'versionCode': '1'}, [])


class RepositoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'repo'
        self.origin = Path(self.temp.name) / 'origin.git'
        self.root.mkdir()
        subprocess.run(['git', 'init', '--bare', str(self.origin)], check=True, capture_output=True)
        self.git('init', '-b', 'main')
        self.git('config', 'user.name', 'Official Test')
        self.git('config', 'user.email', 'official@example.test')
        for folder in ('.github', 'tools', str(Path(module.META).parent)):
            (self.root / folder).mkdir(parents=True, exist_ok=True)
        for file in ('.github/trusted', 'tools/trusted', 'AGENTS.md'):
            (self.root / file).write_text('fork owned')
        (self.root / 'business.txt').write_text('base\n')
        (self.root / 'version.properties').write_text('versionName=0.1.0\nversionCode=100\n')
        (self.root / module.META).write_text('const val VERSION = "0.1.0"\nconst val CODE = 100\nconst val COMMIT = "' + '0' * 40 + '"\n')
        self.commit('base')
        self.base = self.git('rev-parse', 'HEAD')
        self.git('checkout', '-b', 'upstream-source')
        (self.root / 'business.txt').write_text('official release\n')
        (self.root / '.github/trusted').write_text('untrusted upstream automation')
        (self.root / 'version.properties').write_text('versionName=0.1.1\nversionCode=101\n')
        self.commit('upstream release')
        self.target = self.git('rev-parse', 'HEAD')
        self.git('tag', 'v1.0.2')
        self.git('checkout', 'main')
        path = self.root / module.META
        path.write_text(path.read_text().replace('0' * 40, self.base))
        (self.root / 'version.properties').write_text('versionName=0.2.12\nversionCode=212\n')
        self.commit('fork metadata')
        self.original = self.git('rev-parse', 'HEAD')
        self.git('remote', 'add', 'origin', str(self.origin))
        self.git('remote', 'add', 'upstream', 'https://github.com/fw867/hypercar.git')
        self.git('push', 'origin', 'main', 'main:alpha')
        self.git('update-ref', 'refs/remotes/origin/alpha', 'HEAD')
        self.real_git = module.git
        self.patch_root = patch.object(module, 'ROOT', self.root)
        self.patch_root.start()
        self.addCleanup(self.patch_root.stop)
        self.output = self.root / '.git/output'
        self.env = patch.dict(os.environ, {'GITHUB_OUTPUT': str(self.output)})
        self.env.start()
        self.addCleanup(self.env.stop)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], text=True, stderr=subprocess.PIPE).strip()

    def commit(self, subject):
        self.git('add', '.')
        self.git('commit', '-m', subject)

    def invoke(self, upstream, own=None, args=None):
        def wrapped_git(*values, **kwargs):
            if values == ('ls-remote', 'upstream', 'refs/tags/*'):
                return '\n'.join(f'{self.base if r["id"] == 1 else self.target}\trefs/tags/{r["tag_name"]}' for r in upstream)
            if values[:3] == ('fetch', '--no-tags', 'upstream'):
                return self.git('update-ref', 'refs/upstream-official/selected', self.target)
            return self.real_git(*values, **kwargs)
        with patch.object(module, 'releases', side_effect=lambda repo: upstream if repo == module.UPSTREAM else (own or [])), \
                patch.object(module, 'git', side_effect=wrapped_git), patch.object(sys, 'argv', ['prepare', *(args or [])]):
            module.main()

    def initialize(self):
        self.invoke([release(1)])
        self.assertEqual(self.original, self.git('rev-parse', 'HEAD'))
        self.assertIn('changed=false', self.output.read_text())
        self.output.write_text('')

    def test_first_run_only_initializes_and_next_release_merges_exact_commit(self):
        self.initialize()
        self.invoke([release(1), release(2)])
        self.assertIn('changed=true', self.output.read_text())
        self.assertEqual('official release\n', (self.root / 'business.txt').read_text())
        self.assertEqual('fork owned', (self.root / '.github/trusted').read_text())
        self.assertIn(self.target, (self.root / module.META).read_text())
        self.assertEqual({'versionName': '0.2.13', 'versionCode': '213'}, module.properties((self.root / 'version.properties').read_text()))
        self.git('merge-base', '--is-ancestor', self.target, 'HEAD')
        self.assertEqual(self.original, self.git('ls-remote', 'origin', 'refs/heads/main').split()[0])
        self.assertEqual(self.original, self.git('ls-remote', 'origin', 'refs/heads/alpha').split()[0])
        self.assertEqual('', self.git('status', '--porcelain'))

    def test_business_conflicts_abort_without_touching_main(self):
        self.initialize()
        (self.root / 'business.txt').write_text('fork conflicting edit\n')
        self.commit('fork business change')
        original = self.git('rev-parse', 'HEAD')
        self.git('update-ref', 'refs/remotes/origin/main', original)
        with self.assertRaisesRegex(RuntimeError, '源码冲突'):
            self.invoke([release(1), release(2)])
        self.assertEqual(original, self.git('rev-parse', 'HEAD'))
        self.assertEqual('', self.git('status', '--porcelain'))

    def test_unpublished_retry_reuses_same_commit_and_version(self):
        self.initialize()
        self.invoke([release(1), release(2)])
        candidate = self.git('rev-parse', 'HEAD')
        self.git('push', 'origin', 'main')
        self.output.write_text('')
        self.invoke([release(1), release(2)])
        self.assertEqual(candidate, self.git('rev-parse', 'HEAD'))
        self.assertIn('version=0.2.13', self.output.read_text())
        (self.root / 'later.txt').write_text('later main')
        self.commit('later main')
        self.git('push', 'origin', 'main')
        with self.assertRaisesRegex(RuntimeError, 'main 已前进'):
            self.invoke([release(1), release(2)])

    def test_published_retry_only_advances_cursor_and_is_idempotent(self):
        self.initialize()
        self.invoke([release(1), release(2)])
        self.git('push', 'origin', 'main')
        self.git('tag', 'v0.2.13')
        own = [release(10, 'v0.2.13')]
        with self.assertRaisesRegex(RuntimeError, '缺失附件'):
            self.invoke([release(1), release(2)], own)
        own[0]['body'] = '- APK SHA-256：' + 'a' * 64
        own[0]['assets'] = [{'name': 'colorplay-next-0.2.13-release.apk', 'size': 123, 'state': 'uploaded', 'digest': 'sha256:' + 'a' * 64},
                            {'name': 'colorplay-next-0.2.13-release.apk.sha256', 'size': 123, 'state': 'uploaded'}]
        self.output.write_text('')
        self.invoke([release(1), release(2)], own)
        self.assertIn('changed=false', self.output.read_text())
        self.invoke([release(1), release(2)], own)
        state_sha = self.git('ls-remote', 'origin', module.STATE_REF).split()[0]
        state = json.loads(self.git('show', state_sha + ':state.json'))
        self.assertEqual([2], state['completed'])

    def test_manual_backfill_pending_survives_scheduled_retry_with_earlier_new_event(self):
        self.invoke([release(1), release(2)])
        self.invoke([release(1), release(2)], args=['--tag', 'v1.0.2', '--allow-backfill'])
        candidate = self.git('rev-parse', 'HEAD')
        self.git('push', 'origin', 'main')
        earlier = release(3)
        earlier['published_at'] = '2020-01-01T00:00:00Z'
        self.invoke([release(1), release(2), earlier])
        self.assertEqual(candidate, self.git('rev-parse', 'HEAD'))
        record = json.loads((self.root / module.PROVENANCE).read_text())
        self.assertEqual(2, record['release_id'])

    def test_observed_tag_move_fails_before_any_candidate(self):
        self.invoke([release(1), release(2)])
        self.target = self.base
        with self.assertRaisesRegex(RuntimeError, '移动或删除'):
            self.invoke([release(1), release(2)])
        self.assertEqual(self.original, self.git('rev-parse', 'HEAD'))

    def test_state_compare_and_swap_rejects_stale_writer(self):
        self.initialize()
        old = self.git('ls-remote', 'origin', module.STATE_REF).split()[0]
        state = {'ignored': [1], 'completed': [2]}
        module.save_state(state, old)
        with self.assertRaises(subprocess.CalledProcessError):
            module.save_state({'ignored': [1], 'completed': [3]}, old)


if __name__ == '__main__':
    unittest.main()
