"""直接执行正式工作流中的校验代码，避免测试替身偏离发布门禁。"""
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch
import zipfile

WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/android-ci.yml'
TEXT = WORKFLOW.read_text(encoding='utf-8-sig')
CERT = 'e367837a3305e5fceff996028355d3b2c3fff26260af5572d287d732d1472d66'


def heredoc(marker):
    start = TEXT.index("<<'" + marker + "'")
    block = TEXT[start:].split('\n', 1)[1].split('\n          ' + marker, 1)[0]
    return textwrap.dedent(block)


class ManualReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        old = Path.cwd()
        os.chdir(self.temp.name)
        self.addCleanup(os.chdir, old)
        env = patch.dict(os.environ, {'VERSION': '0.2.15', 'VERSION_CODE': '216',
                                     'EXPECTED_CERT': '', 'NAME': 'candidate.apk',
                                     'TAG': 'v0.2.15', 'REPO': 'owner/repo',
                                     'GITHUB_SHA': 'a' * 40}, clear=False)
        env.start()
        self.addCleanup(env.stop)

    def identity(self, cert=None, package='com.emxxme.colorplaynext', code='216',
                 version='0.2.15', core=True):
        Path('certs.txt').write_text(cert or 'Signer #1 certificate SHA-256 digest: ' + CERT + '\n')
        Path('badging.txt').write_text(f"package: name='{package}' versionCode='{code}' versionName='{version}'\n")
        apk = Path('app/build/outputs/apk/release/app-release.apk')
        apk.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(apk, 'w') as archive:
            if core:
                archive.writestr('lib/arm64-v8a/libhypercar_core.so', b'core')
        exec(compile(heredoc('PYIDENTITY'), '<identity>', 'exec'), {})

    def test_signer_number_and_v2(self):
        for prefix in ('Signer #1', 'V2 Signer:'):
            for ending in ('\n', '\r\n', ''):
                with self.subTest(prefix=prefix, ending=ending):
                    self.identity(prefix + ' certificate SHA-256 digest: ' + CERT.upper() + ending)
                    self.assertEqual(Path('verified-cert.txt').read_text(), CERT)

    def test_three_identity_mismatches(self):
        for kwargs in ({'package': 'evil.app'}, {'code': '215'}, {'version': '0.2.14'}):
            with self.subTest(kwargs=kwargs), self.assertRaises(SystemExit):
                self.identity(**kwargs)

    def test_missing_wrong_debug_and_multiple_certificates(self):
        for cert in ('no certificate\n', 'V2 Signer: certificate SHA-256 digest: ' + '0' * 64,
                     'Signer #1 certificate SHA-256 digest: ' + CERT + '\nSigner #2 certificate SHA-256 digest: ' + CERT,
                     'Signer #1 certificate DN: CN=Android Debug\nSigner #1 certificate SHA-256 digest: ' + CERT):
            with self.subTest(cert=cert), self.assertRaises(SystemExit):
                self.identity(cert)

    def test_extra_certificate_secret_and_missing_core(self):
        with patch.dict(os.environ, {'EXPECTED_CERT': '0' * 64}), self.assertRaises(SystemExit):
            self.identity()
        with self.assertRaises(KeyError):
            self.identity(core=False)

    def fixture_release(self):
        data = b'fixture apk'
        digest = hashlib.sha256(data).hexdigest()
        Path('candidate.apk').write_bytes(data)
        Path('candidate.apk.sha256').write_text(f'{digest}  candidate.apk\n')
        os.environ['SHA'] = digest
        self.release = {'tag_name': 'v0.2.15', 'draft': False, 'prerelease': False,
                        'published_at': '2026-11-28T00:00:00Z',
                        'target_commitish': 'a' * 40, 'assets': []}
        self.downloads = {}
        for index, name in enumerate(('candidate.apk', 'candidate.apk.sha256')):
            body = Path(name).read_bytes()
            self.release['assets'].append({'id': index, 'name': name, 'state': 'uploaded',
                                           'digest': 'sha256:' + hashlib.sha256(body).hexdigest()})
            self.downloads[str(index)] = body

    def verify_release(self, wrong_tag=False, wrong_download=False):
        Path('existing-release.json').write_text(json.dumps(self.release))
        def output(args, **kwargs):
            if args[:2] == ['git', 'ls-remote']:
                return ('b' * 40 if wrong_tag else 'a' * 40) + '\trefs/tags/v0.2.15\n'
            return b'corrupt' if wrong_download else self.downloads[args[-1].rsplit('/', 1)[1]]
        with patch.object(subprocess, 'check_output', side_effect=output):
            exec(compile(heredoc('PYRELEASE'), '<release>', 'exec'), {})

    def test_public_release_exact_match(self):
        self.fixture_release()
        self.verify_release()

    def test_digest_download_and_candidate_mismatches(self):
        for failure in ('digest', 'download', 'candidate', 'tag', 'draft', 'state', 'published', 'checksum', 'localsha'):
            with self.subTest(failure=failure):
                self.fixture_release()
                if failure == 'digest':
                    self.release['assets'][0]['digest'] = 'sha256:' + '0' * 64
                elif failure == 'candidate':
                    self.release['target_commitish'] = 'main'
                elif failure == 'draft':
                    self.release['draft'] = True
                elif failure == 'state':
                    self.release['assets'][0]['state'] = 'new'
                elif failure == 'published':
                    self.release['published_at'] = None
                elif failure == 'checksum':
                    Path('candidate.apk.sha256').write_text('wrong')
                elif failure == 'localsha':
                    os.environ['SHA'] = '0' * 64
                with self.assertRaises(SystemExit):
                    self.verify_release(wrong_tag=failure == 'tag', wrong_download=failure == 'download')

    def test_preflight_pagination_and_draft_stop(self):
        pages = [[{'tag_name': 'other'}] * 100, [{'tag_name': 'v0.2.15', 'draft': True}]]
        with patch.object(subprocess, 'check_output', side_effect=[json.dumps(p) for p in pages]) as api:
            with self.assertRaises(SystemExit):
                exec(compile(heredoc('PYPREFLIGHT'), '<preflight>', 'exec'), {})
            self.assertIn('page=2', api.call_args.args[0][-1])
        with patch.object(subprocess, 'check_output', return_value='[]'):
            exec(compile(heredoc('PYPREFLIGHT'), '<preflight>', 'exec'), {})
        self.assertIsNone(json.loads(Path('existing-release.json').read_text()))

    def test_preflight_public_matches_only_exact_tag(self):
        self.fixture_release()
        unrelated = dict(self.release, tag_name='v0.2.150')
        with patch.object(subprocess, 'check_output', return_value=json.dumps([unrelated, self.release])):
            exec(compile(heredoc('PYPREFLIGHT'), '<preflight>', 'exec'), {})
        self.assertEqual(json.loads(Path('existing-release.json').read_text()), self.release)
        self.verify_release()

    def test_upstream_notes_read_actual_source(self):
        source = Path('app/src/main/java/io/github/hypercar/UpstreamBase.kt')
        source.parent.mkdir(parents=True)
        source.write_text('const val VERSION = "0.1.0.639"\nconst val CODE = 10735\nconst val COMMIT = "' + 'd' * 40 + '"\n')
        result = subprocess.run(['python3', '-c', heredoc('PYBASE')], capture_output=True, text=True, check=True)
        self.assertEqual(result.stdout.strip(), '0.1.0.639 / code=10735 / ' + 'd' * 40)
        self.assertIn('${UPSTREAM_BASE}', TEXT)

    def test_shell_blocks_parse(self):
        blocks = re.findall(r'^        run: \|\n((?:          .*\n|\n)*)', TEXT, re.M)
        self.assertGreater(len(blocks), 5)
        for block in blocks:
            subprocess.run(['bash', '-n'], input=textwrap.dedent(block), text=True, check=True)

    def test_gate_order_and_no_overwrite_or_alpha_merge(self):
        markers = ['cargo test --lib', 'cargo ndk', ':app:lintRelease :app:testDebugUnitTest :app:assembleRelease',
                   "<<'PYIDENTITY'", 'name: 上传 APK', 'name: 防止正式版安装编号低于 Alpha',
                   "<<'PYPREFLIGHT'", 'gh release create', 'gh api "repos/$REPO/releases/tags/$TAG"']
        positions = [TEXT.index(marker) for marker in markers]
        self.assertEqual(positions, sorted(positions))
        manual = TEXT.split('      - name: 正式版发布成功后同步并启动 Alpha', 1)[0]
        for forbidden in ('--clobber', 'gh release edit', 'gh release upload', 'git merge', 'git push', 'gh release view', '--draft'):
            self.assertNotIn(forbidden, manual)
        historical = TEXT.split('      - name: 正式版发布成功后同步并启动 Alpha', 1)[1]
        self.assertIn("steps.version.outputs.name == '0.2.12'", historical)
        self.assertIn("github.ref == 'refs/heads/main'", historical)
        self.assertIn('"$NAME.sha256"', TEXT)
        self.assertIn('release_code <= alpha_code', TEXT)
        self.assertIn('existing" != "$GITHUB_SHA', TEXT)
        self.assertIn("os.environ.get(\"GITHUB_REF\") == \"refs/heads/alpha\"", TEXT)


if __name__ == '__main__':
    unittest.main()
