#!/usr/bin/env python3
"""安全发布 helper 的离线回归测试；所有 API 修改均为 mock。"""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


publisher = load('publisher', 'publish-official-release.py')
prepare = load('prepare', 'prepare-official-release.py')
SHA = 'a' * 40
RECORD = {'version': '0.2.13', 'code': 214, 'upstream': 'b' * 40}
TAG = 'v0.2.13'


def release(draft=True):
    return {
        'id': 123, 'tag_name': TAG, 'draft': draft, 'prerelease': False,
        'body': '\n'.join((
            '### ColorPlay Next 0.2.13（正式版）', '- versionCode：214',
            '- 上游官方 Release 精确提交：fw867/hypercar @ ' + RECORD['upstream'],
            '- 本仓库候选提交：' + SHA, '- APK SHA-256：' + 'c' * 64,
            '- 正式签名证书 SHA-256：e367837a3305e5fceff996028355d3b2c3fff26260af5572d287d732d1472d66')),
        'assets': [
            {'name': 'colorplay-next-0.2.13-release.apk', 'state': 'uploaded', 'size': 99, 'digest': 'sha256:' + 'c' * 64},
            {'name': 'colorplay-next-0.2.13-release.apk.sha256', 'state': 'uploaded', 'size': 99}],
    }


class PublishTests(unittest.TestCase):
    def setUp(self):
        remote = patch.object(publisher, 'validate_remote_tag')
        remote.start()
        self.addCleanup(remote.stop)

    def publish_with(self, value):
        def api(path, payload=None, paginate=False):
            self.assertNotIn('/releases/tags/', path)
            if payload is None:
                self.assertTrue(paginate)
                return [[], [value]]
            self.assertEqual(path, f'repos/{publisher.REPO}/releases/123')
            self.assertEqual(payload, {'draft': False, 'prerelease': False, 'make_latest': 'true'})
            return dict(value, draft=False)
        with patch.object(publisher, 'validate_git_context', return_value=(RECORD, prepare)), patch.object(publisher, 'api', side_effect=api) as mock:
            publisher.publish(TAG, SHA)
            return mock.call_count

    def test_draft_is_found_by_paginated_list_and_patched_by_id(self):
        self.assertEqual(self.publish_with(release()), 2)

    def test_published_is_idempotent(self):
        self.assertEqual(self.publish_with(release(False)), 1)

    def test_invalid_release_never_patches(self):
        variants = []
        for change in ('missing', 'digest', 'tag', 'candidate', 'empty', 'prerelease', 'duplicate'):
            value = release()
            if change == 'missing':
                value['assets'].pop()
            elif change == 'digest':
                value['assets'][0]['digest'] = 'sha256:' + 'd' * 64
            elif change == 'tag':
                value['tag_name'] = 'v0.2.12'
            elif change == 'candidate':
                value['body'] = value['body'].replace(SHA, 'd' * 40)
            elif change == 'empty':
                value['assets'][0]['size'] = 0
            elif change == 'prerelease':
                value['prerelease'] = True
            else:
                value['assets'].append(copy.deepcopy(value['assets'][0]))
            variants.append(value)
        for value in variants:
            with self.subTest(value=value), patch.object(publisher, 'validate_git_context', return_value=(RECORD, prepare)), patch.object(publisher, 'api', return_value=[[value]]) as api:
                with self.assertRaises(RuntimeError):
                    publisher.publish(TAG, SHA)
                self.assertEqual(api.call_count, 1)

    def test_missing_and_duplicate_tag_rejected(self):
        for values in ([], [release(), release()]):
            with self.subTest(values=values), patch.object(publisher, 'validate_git_context', return_value=(RECORD, prepare)), patch.object(publisher, 'api', return_value=[values]):
                with self.assertRaises(RuntimeError):
                    publisher.publish(TAG, SHA)

    def test_patch_response_identity_rechecked(self):
        for altered in (dict(release(False), id=999), dict(release(False), tag_name='v0.2.12'), release()):
            with self.subTest(altered=altered), patch.object(publisher, 'validate_git_context', return_value=(RECORD, prepare)), patch.object(publisher, 'api', side_effect=[[[release()]], altered]):
                with self.assertRaises(RuntimeError):
                    publisher.publish(TAG, SHA)

    def test_valid_normal_and_recovery_contexts(self):
        for recovery in (False, True):
            outputs = [SHA, '', '.github/workflows/fix.yml\ntools/fix.py\nREADME.md\nAGENTS.md'] if recovery else [SHA, SHA]
            outputs += [json.dumps(RECORD), 'versionName=0.2.13\nversionCode=214\n']
            with self.subTest(recovery=recovery), patch.object(publisher, 'git', side_effect=outputs):
                record, module = publisher.validate_git_context(TAG, SHA, recovery)
                self.assertEqual(record, RECORD)
                self.assertTrue(callable(module.validate_published))

    def test_version_code_mismatch_rejected(self):
        with patch.object(publisher, 'git', side_effect=[SHA, SHA, json.dumps(RECORD), 'versionName=0.2.13\nversionCode=215\n']):
            with self.assertRaisesRegex(RuntimeError, '版本身份'):
                publisher.validate_git_context(TAG, SHA)

    def test_normal_head_mismatch_rejected(self):
        with patch.object(publisher, 'git', side_effect=[SHA, 'b' * 40]):
            with self.assertRaisesRegex(RuntimeError, 'HEAD'):
                publisher.validate_git_context(TAG, SHA)

    def test_tag_mismatch_rejected(self):
        with patch.object(publisher, 'git', return_value='b' * 40):
            with self.assertRaisesRegex(RuntimeError, 'tag'):
                publisher.validate_git_context(TAG, SHA)

    def test_recovery_source_and_version_changes_rejected(self):
        for path in ('app/src/main/Test.kt', 'version.properties', 'rust-core/src/lib.rs'):
            with self.subTest(path=path), patch.object(publisher, 'git', side_effect=[SHA, '', path]):
                with self.assertRaisesRegex(RuntimeError, '非允许路径'):
                    publisher.validate_git_context(TAG, SHA, True)

    def test_recovery_validates_run_before_release_lookup(self):
        with patch.object(publisher, 'validate_git_context', return_value=(RECORD, prepare)), patch.object(publisher, 'validate_prior_run', side_effect=RuntimeError('门禁失败')) as check, patch.object(publisher, 'api') as api:
            with self.assertRaises(RuntimeError):
                publisher.publish(TAG, SHA, '123')
            check.assert_called_once_with('123', SHA)
            api.assert_not_called()


class RemoteSafetyTests(unittest.TestCase):
    def test_lightweight_and_annotated_remote_tags(self):
        for refs in (SHA + '\trefs/tags/' + TAG, 'd' * 40 + '\trefs/tags/' + TAG + '\n' + SHA + '\trefs/tags/' + TAG + '^{}'):
            with self.subTest(refs=refs), patch.object(publisher, 'git', return_value=refs):
                publisher.validate_remote_tag(TAG, SHA)

    def test_missing_or_moved_remote_tag(self):
        for refs in ('', 'd' * 40 + '\trefs/tags/' + TAG, 'd' * 40 + '\trefs/tags/' + TAG + '\n' + 'e' * 40 + '\trefs/tags/' + TAG + '^{}'):
            with self.subTest(refs=refs), patch.object(publisher, 'git', return_value=refs):
                with self.assertRaisesRegex(RuntimeError, '远端正式 tag'):
                    publisher.validate_remote_tag(TAG, SHA)

    def test_alpha_equal_or_higher_blocks_recovery(self):
        for code in (214, 215):
            with self.subTest(code=code), patch.object(publisher, 'git', side_effect=['alpha-ref', '', f'versionCode={code}']):
                with self.assertRaisesRegex(RuntimeError, 'Alpha'):
                    publisher.validate_alpha_version(RECORD, prepare)

    def test_lower_or_missing_alpha_allows_recovery(self):
        for outputs in ([''], ['alpha-ref', '', 'versionCode=213']):
            with self.subTest(outputs=outputs), patch.object(publisher, 'git', side_effect=outputs):
                publisher.validate_alpha_version(RECORD, prepare)

    def test_alpha_guard_only_for_draft_recovery(self):
        for draft in (False, True):
            with self.subTest(draft=draft), patch.object(publisher, 'validate_git_context', return_value=(RECORD, prepare)), patch.object(publisher, 'validate_prior_run'), patch.object(publisher, 'validate_remote_tag'), patch.object(publisher, 'validate_alpha_version', side_effect=RuntimeError('Alpha')) as guard, patch.object(publisher, 'api', return_value=[[release(draft)]]) as api:
                if draft:
                    with self.assertRaisesRegex(RuntimeError, 'Alpha'):
                        publisher.publish(TAG, SHA, '123')
                    guard.assert_called_once()
                else:
                    publisher.publish(TAG, SHA, '123')
                    guard.assert_not_called()
                self.assertEqual(api.call_count, 1)


class RunValidationTests(unittest.TestCase):
    def setUp(self):
        self.run = {'id': 123, 'path': publisher.WORKFLOW, 'head_sha': 'b' * 40,
                    'event': 'schedule', 'head_branch': 'main', 'status': 'completed',
                    'repository': {'full_name': publisher.REPO}, 'head_repository': {'full_name': publisher.REPO},
                    'conclusion': 'failure'}
        self.job = {'name': 'official', 'status': 'completed', 'conclusion': 'failure',
                    'steps': [{'name': name, 'conclusion': 'success'} for name in publisher.REQUIRED_STEPS] + [
                        {'name': '所有验证通过后 CAS 更新 main 与正式 tag 并发布', 'conclusion': 'failure'}]}

    def check(self):
        with patch.object(publisher, 'git', return_value='b' * 40), patch.object(publisher, 'api', side_effect=[self.run, [{'jobs': []}, {'jobs': [self.job]}]]):
            publisher.validate_prior_run('123', SHA)

    def test_failed_publish_with_all_gates_success_is_allowed(self):
        self.check()

    def test_run_identity_rejected(self):
        for key, value in (('path', 'other.yml'), ('head_sha', SHA), ('event', 'pull_request'), ('head_branch', 'alpha'), ('status', 'in_progress'), ('head_repository', {'full_name': 'other/repo'}), ('repository', {'full_name': 'other/repo'})):
            with self.subTest(key=key):
                original = self.run[key]
                self.run[key] = value
                with self.assertRaises(RuntimeError):
                    self.check()
                self.run[key] = original

    def test_each_required_gate_must_succeed(self):
        for step in self.job['steps'][:-1]:
            for state in ('failure', 'skipped', 'cancelled'):
                with self.subTest(step=step['name'], state=state):
                    step['conclusion'] = state
                    with self.assertRaises(RuntimeError):
                        self.check()
            step['conclusion'] = 'success'

    def test_missing_gate_rejected(self):
        self.job['steps'].pop(0)
        with self.assertRaises(RuntimeError):
            self.check()


if __name__ == '__main__':
    unittest.main()
