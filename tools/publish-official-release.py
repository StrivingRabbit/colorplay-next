#!/usr/bin/env python3
"""验证现有正式草稿并按数值 ID 公开；不创建 Release 或上传附件。"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(os.environ.get('OFFICIAL_REPO_DIR', Path(__file__).resolve().parents[1])).resolve()
REPO = 'StrivingRabbit/colorplay-next'
WORKFLOW = '.github/workflows/official-upstream-release.yml'
REQUIRED_STEPS = (
    '准备正式候选或初始化游标',
    'Rust 全量测试',
    '编译 Rust 核心层',
    '写入正式签名配置',
    '构建 APK、Android 单测与 Release Lint',
    '强制验证正式证书并准备发布附件',
)


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True).strip()


def api(path, payload=None, paginate=False):
    command = ['gh', 'api', path]
    if paginate:
        command += ['--paginate', '--slurp']
    if payload is not None:
        command += ['--method', 'PATCH', '--input', '-']
    return json.loads(subprocess.check_output(
        command, input=json.dumps(payload) if payload is not None else None, text=True, cwd=ROOT))


def validate_git_context(tag, candidate, recovery=False):
    if not re.fullmatch(r'[0-9a-f]{40}', candidate):
        raise ValueError('候选必须是精确的完整 SHA')
    if not re.fullmatch(r'v\d+\.\d+\.\d+', tag):
        raise ValueError('必须指定正式版本 tag')
    if git('rev-parse', f'refs/tags/{tag}^{{commit}}') != candidate:
        raise RuntimeError('tag 未指向指定候选，禁止发布')
    if recovery:
        git('merge-base', '--is-ancestor', candidate, 'HEAD')
        changed = git('diff', '--name-only', '--no-renames', candidate + '..HEAD').splitlines()
        if any(not (p.startswith('.github/') or p.startswith('tools/') or p in ('README.md', 'AGENTS.md')) for p in changed):
            raise RuntimeError('恢复期间 App 源码或版本等非允许路径发生变化')
    elif git('rev-parse', 'HEAD') != candidate:
        raise RuntimeError('普通发布必须在候选 HEAD 上执行')
    record = json.loads(git('show', candidate + ':.github/official-release.json'))
    spec = importlib.util.spec_from_file_location('official_prepare', ROOT / 'tools/prepare-official-release.py')
    prepare = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(prepare)
    version = prepare.properties(git('show', candidate + ':version.properties'))
    if tag != 'v' + record['version'] or version['versionName'] != record['version'] or int(version['versionCode']) != int(record['code']):
        raise RuntimeError('候选记录、tag 与版本身份不一致')
    return record, prepare


def validate_remote_tag(tag, candidate):
    ref = 'refs/tags/' + tag
    lines = git('ls-remote', 'origin', ref, ref + '^{}').splitlines()
    refs = dict(line.split()[::-1] for line in lines)
    if ref not in refs or refs.get(ref + '^{}', refs.get(ref)) != candidate:
        raise RuntimeError('远端正式 tag 缺失或已移动，禁止发布')


def validate_alpha_version(record, prepare):
    if not git('ls-remote', 'origin', 'refs/heads/alpha'):
        return
    git('fetch', '--no-tags', 'origin', '+refs/heads/alpha:refs/remotes/origin/alpha')
    alpha = prepare.properties(git('show', 'origin/alpha:version.properties'))
    if int(alpha['versionCode']) >= int(record['code']):
        raise RuntimeError('Alpha 已占用相同或更高安装编号，禁止恢复旧正式草稿')


def validate_prior_run(run_id, candidate):
    if not re.fullmatch(r'[1-9][0-9]*', str(run_id)):
        raise ValueError('验证 run ID 必须为正整数')
    run = api(f'repos/{REPO}/actions/runs/{run_id}')
    if (run.get('id') != int(run_id) or run.get('path') != WORKFLOW
            or run.get('head_sha') != git('rev-parse', candidate + '^1')
            or run.get('event') not in ('schedule', 'push', 'workflow_dispatch')
            or run.get('repository', {}).get('full_name') != REPO
            or run.get('head_repository', {}).get('full_name') != REPO
            or run.get('head_branch') != 'main' or run.get('status') != 'completed'):
        raise RuntimeError('历史运行来源、工作流、候选基线或状态不匹配')
    pages = api(f'repos/{REPO}/actions/runs/{run_id}/jobs?per_page=100', paginate=True)
    jobs = [job for page in pages for job in page['jobs']]
    official = [job for job in jobs if job.get('name') == 'official']
    if len(official) != 1 or official[0].get('status') != 'completed':
        raise RuntimeError('历史运行缺少唯一且完成的 official job')
    steps = official[0].get('steps', [])
    for name in REQUIRED_STEPS:
        matches = [step for step in steps if step.get('name') == name]
        if len(matches) != 1 or matches[0].get('conclusion') != 'success':
            raise RuntimeError('历史运行必需门禁未通过：' + name)


def validate_release(release, tag, candidate, record, prepare):
    if type(release.get('id')) is not int or release['id'] <= 0 or release.get('tag_name') != tag:
        raise RuntimeError('Release 数值 ID 或 tag 身份不匹配')
    if type(release.get('draft')) is not bool or release.get('prerelease') is not False:
        raise RuntimeError('Release 不是正式发布或正式草稿')
    body = release.get('body', '')
    required = (
        f"### ColorPlay Next {record['version']}（正式版）",
        f"- versionCode：{record['code']}",
        f"- 上游官方 Release 精确提交：fw867/hypercar @ {record['upstream']}",
        '- 本仓库候选提交：' + candidate,
        '- 正式签名证书 SHA-256：e367837a3305e5fceff996028355d3b2c3fff26260af5572d287d732d1472d66',
    )
    lines = body.splitlines()
    if any(lines.count(line) != 1 for line in required):
        raise RuntimeError('Release notes 的版本、候选或签名来源不符')
    for prefix in ('- versionCode：', '- 本仓库候选提交：', '- 上游官方 Release 精确提交：', '- APK SHA-256：', '- 正式签名证书 SHA-256：'):
        if sum(line.startswith(prefix) for line in lines) != 1:
            raise RuntimeError('Release notes 身份字段缺失或重复')
    for name in (f"colorplay-next-{record['version']}-release.apk", f"colorplay-next-{record['version']}-release.apk.sha256"):
        if sum(asset.get('name') == name for asset in release.get('assets', [])) != 1:
            raise RuntimeError('Release 附件缺失或同名重复')
    prepare.validate_published(dict(release, draft=False), record['version'])


def publish(tag, candidate, validated_run=None):
    record, prepare = validate_git_context(tag, candidate, validated_run is not None)
    if validated_run is not None:
        validate_prior_run(validated_run, candidate)
    validate_remote_tag(tag, candidate)
    pages = api(f'repos/{REPO}/releases?per_page=100', paginate=True)
    matches = [release for page in pages for release in page if release.get('tag_name') == tag]
    if len(matches) != 1:
        raise RuntimeError('必须存在唯一精确 tag 的 Release，缺失时禁止创建或猜测草稿')
    release = matches[0]
    validate_release(release, tag, candidate, record, prepare)
    if release['draft']:
        if validated_run is not None:
            validate_alpha_version(record, prepare)
        validate_remote_tag(tag, candidate)
        result = api(f"repos/{REPO}/releases/{release['id']}", {
            'draft': False, 'prerelease': False, 'make_latest': 'true'})
        if result.get('id') != release['id'] or result.get('draft') is not False:
            raise RuntimeError('公开结果的 ID 或 draft 状态不匹配')
        validate_release(result, tag, candidate, record, prepare)
    print('已确认正式发布：' + tag)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--candidate', required=True)
    parser.add_argument('--validated-run')
    args = parser.parse_args()
    publish(args.tag, args.candidate, args.validated_run)


if __name__ == '__main__':
    main()
