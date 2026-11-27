#!/usr/bin/env python3
"""Execute the actual workflow verifier against public certificate fixtures."""
import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = 'e367837a3305e5fceff996028355d3b2c3fff26260af5572d287d732d1472d66'


def verifier(workflow_name='alpha-upstream.yml'):
    workflow = (ROOT / '.github/workflows' / workflow_name).read_text()
    step = workflow.split('      - name: 强制验证正式证书并准备发布附件', 1)[1]
    source = step.split("          python3 - <<'PY'\n", 1)[1].split('\n          PY', 1)[0]
    return compile('\n'.join(line[10:] for line in source.splitlines()), 'alpha-workflow-verifier', 'exec')


class AlphaSigningTest(unittest.TestCase):
    workflow_name = 'alpha-upstream.yml'

    def check_certificate(self, text, core=True, badging="package: name='com.emxxme.colorplaynext' versionCode='214' versionName='0.2.13'"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'alpha-certs.txt').write_text(text)
            (root / 'official-badging.txt').write_text(badging)
            apk = root / 'app/build/outputs/apk/release/app-release.apk'
            apk.parent.mkdir(parents=True)
            with zipfile.ZipFile(apk, 'w') as archive:
                if core:
                    archive.writestr('lib/arm64-v8a/libhypercar_core.so', b'fixture')
            previous = Path.cwd()
            try:
                os.chdir(root)
                with patch.dict(os.environ, {'RUNNER_TEMP': str(root), 'VERSION': '0.2.13', 'CODE': '214'}), contextlib.redirect_stdout(io.StringIO()):
                    exec(verifier(self.workflow_name), {})
            finally:
                os.chdir(previous)

    def test_actual_v2_output_from_run_45953835678(self):
        self.check_certificate('V2 Signer: certificate DN: CN=colorPlay-next\n'
                               'V2 Signer: certificate SHA-256 digest: ' + EXPECTED + '\n')

    def test_numbered_signer_lf_crlf_and_uppercase(self):
        for ending in ('\n', '\r\n', ''):
            with self.subTest(ending=repr(ending)):
                self.check_certificate('Signer #1 certificate SHA-256 digest: ' + EXPECTED.upper() + ending)

    def test_wrong_certificate_is_rejected_in_both_formats(self):
        for prefix in ('V2 Signer:', 'Signer #1'):
            with self.subTest(prefix=prefix), self.assertRaisesRegex(SystemExit, '证书不匹配'):
                self.check_certificate(prefix + ' certificate SHA-256 digest: ' + '0' * 64)

    def test_debug_certificate_is_rejected_even_with_matching_fingerprint(self):
        with self.assertRaisesRegex(SystemExit, '证书不匹配'):
            self.check_certificate('V2 Signer: certificate DN: CN=Android Debug\n'
                                   'V2 Signer: certificate SHA-256 digest: ' + EXPECTED)

    def test_multiple_signers_are_rejected(self):
        with self.assertRaisesRegex(SystemExit, '证书不匹配'):
            self.check_certificate('Signer #1 certificate SHA-256 digest: ' + EXPECTED + '\n'
                                   'Signer #2 certificate SHA-256 digest: ' + EXPECTED)

    def test_unrecognized_or_truncated_fingerprint_is_rejected(self):
        for text in ('certificate SHA-256 digest: ' + EXPECTED,
                     'V2 Signer: certificate SHA-256 digest: ' + EXPECTED[:-1]):
            with self.subTest(text=text), self.assertRaisesRegex(SystemExit, '无法解析'):
                self.check_certificate(text)

    def test_missing_native_core_is_rejected(self):
        with self.assertRaisesRegex(SystemExit, '缺少 Rust'):
            self.check_certificate('V2 Signer: certificate SHA-256 digest: ' + EXPECTED, core=False)


class OfficialSigningTest(AlphaSigningTest):
    workflow_name = 'official-upstream-release.yml'

    def test_wrong_package_version_or_code_is_rejected(self):
        for text in ("package: name='io.github.hypercar' versionCode='214' versionName='0.2.13'",
                     "package: name='com.emxxme.colorplaynext' versionCode='213' versionName='0.2.13'",
                     "package: name='com.emxxme.colorplaynext' versionCode='214' versionName='0.2.12'",
                     'unrecognized badging'):
            with self.subTest(badging=text), self.assertRaisesRegex(SystemExit, '身份不匹配'):
                self.check_certificate('V2 Signer: certificate SHA-256 digest: ' + EXPECTED, badging=text)


if __name__ == '__main__':
    unittest.main()
