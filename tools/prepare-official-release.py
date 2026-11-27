#!/usr/bin/env python3
"""正式 Release 跟随通道；只在显式 finalize 后保存发布游标。"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(os.environ.get('OFFICIAL_REPO_DIR', Path(__file__).resolve().parents[1])).resolve()
META = 'app/src/main/java/io/github/hypercar/UpstreamBase.kt'
PROVENANCE = '.github/official-release.json'
PROTECTED = ['.github', 'tools', 'version.properties', META, 'AGENTS.md']
STATE_REF = 'refs/heads/official-release-state'
REPO = 'StrivingRabbit/colorplay-next'
UPSTREAM = 'fw867/hypercar'


def git(*args, input=None):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], input=input, text=True).strip()


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', '--paginate', '--slurp', path], text=True))


def releases(repo):
    return [release for page in api(f'repos/{repo}/releases?per_page=100') for release in page]


def properties(text):
    return dict(line.strip().split('=', 1) for line in text.splitlines()
                if '=' in line and not line.lstrip().startswith('#'))


def next_version(main, published):
    stable = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)', main['versionName'])
    if not stable:
        raise ValueError('main 必须使用正式 X.Y.Z 版本')
    core = tuple(map(int, stable.groups()))
    proposed = (core[0], core[1], core[2] + 1)
    codes = [int(main['versionCode'])]
    for version in published:
        match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)(?:\.\d+|-(?:alpha|beta)\.\d+)?', version['versionName'])
        if not match:
            raise ValueError('无法解析已发布版本：' + version['versionName'])
        published_core = tuple(map(int, match.groups()))
        if '-' in version['versionName']:
            proposed = max(proposed, published_core)
        else:
            proposed = max(proposed, (published_core[0], published_core[1], published_core[2] + 1))
        codes.append(int(version['versionCode']))
    code = max(codes) + 1
    if code > 2_100_000_000:
        raise ValueError('Android versionCode 已耗尽')
    return '.'.join(map(str, proposed)), code


def choose(upstream, state, tag='', backfill=False):
    formal = [r for r in upstream if not r['draft'] and not r['prerelease']]
    if tag:
        if not backfill:
            raise ValueError('指定 tag 必须明确允许回补')
        matches = [r for r in formal if r['tag_name'] == tag]
        if len(matches) != 1:
            raise ValueError('必须指定存在且唯一的官方正式 Release tag')
        return None if matches[0]['id'] in state['completed'] else matches[0]
    unseen = [r for r in formal if r['id'] not in state['ignored'] + state['completed']]
    return min(unseen, key=lambda r: (r['published_at'], r['id'])) if unseen else None


def select_pending(upstream, state, record, tag='', backfill=False):
    if record and record['release_id'] not in state['completed']:
        matches = [r for r in upstream if r['id'] == record['release_id'] and not r['draft'] and not r['prerelease']]
        if len(matches) != 1 or matches[0]['tag_name'] != record['tag']:
            raise RuntimeError('未完成的上游 Release 已删除、改名或不再正式，必须人工恢复')
        if tag and tag != record['tag']:
            raise RuntimeError('必须先恢复未完成的正式候选，禁止回补其他 Release')
        return matches[0]
    return choose(upstream, state, tag, backfill)


def observe_tags(upstream, state):
    refs = {}
    for line in git('ls-remote', 'upstream', 'refs/tags/*').splitlines():
        commit, ref = line.split()
        refs[ref] = commit
    observed = state.setdefault('observed', {})
    for value in observed.values():
        ref = 'refs/tags/' + value['tag']
        if refs.get(ref + '^{}', refs.get(ref)) != value['commit']:
            raise RuntimeError('已观测官方 tag 被移动或删除，禁止继续：' + value['tag'])
    for release in upstream:
        if release['draft'] or release['prerelease']:
            continue
        tag = release['tag_name']
        ref = 'refs/tags/' + tag
        commit = refs.get(ref + '^{}', refs.get(ref))
        if not commit or not re.fullmatch(r'[0-9a-f]{40}', commit):
            raise RuntimeError('无法解析官方 Release tag 的精确提交：' + tag)
        value = {'tag': tag, 'commit': commit}
        key = str(release['id'])
        if key in observed and observed[key] != value:
            raise RuntimeError('已观测官方 Release 被改名或 retag，禁止继续：' + tag)
        observed[key] = value


def save_state(state, old):
    blob = git('hash-object', '-w', '--stdin', input=json.dumps(state, sort_keys=True) + '\n')
    tree = git('mktree', input=f'100644 blob {blob}\tstate.json\n')
    parent = ['-p', old] if old else []
    commit = git('commit-tree', tree, *parent, '-m', 'chore: record official Release cursor')
    git('push', f'--force-with-lease={STATE_REF}:{old}', 'origin', f'{commit}:{STATE_REF}')


def validate_published(release, version):
    expected = {f'colorplay-next-{version}-release.apk', f'colorplay-next-{version}-release.apk.sha256'}
    actual = {asset['name'] for asset in release.get('assets', []) if asset.get('size', 0) > 0 and asset.get('state') == 'uploaded'}
    if release['draft'] or release['prerelease'] or not expected.issubset(actual):
        raise RuntimeError('同名 Release 尚未完整正式发布；禁止把缺失附件或草稿当成成功，需人工恢复')
    recorded = re.search(r'^- APK SHA-256：([0-9a-f]{64})$', release.get('body', ''), re.M)
    apk = next(a for a in release['assets'] if a['name'] == f'colorplay-next-{version}-release.apk')
    if not recorded or apk.get('digest') != 'sha256:' + recorded[1]:
        raise RuntimeError('正式 APK 缺少可核对的已发布摘要或摘要不符，禁止推进游标，需人工恢复')


def emit(**values):
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            for key, value in values.items():
                output.write(f'{key}={value}\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', default='')
    parser.add_argument('--allow-backfill', action='store_true')
    parser.add_argument('--finalize', action='store_true')
    args = parser.parse_args()
    if args.tag and (not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._/-]*', args.tag) or '..' in args.tag):
        raise ValueError('非法上游 tag')
    state_line = git('ls-remote', 'origin', STATE_REF)
    old_state = state_line.split()[0] if state_line else ''
    if old_state:
        git('fetch', 'origin', STATE_REF)
        state = json.loads(git('show', old_state + ':state.json'))
    else:
        state = None
    if args.tag and not args.allow_backfill:
        raise ValueError('指定 tag 必须明确允许回补')
    if git('remote', 'get-url', 'upstream') != 'https://github.com/fw867/hypercar.git':
        raise RuntimeError('上游仓库身份不匹配')
    upstream = releases(UPSTREAM)
    if state is None:
        state = {'ignored': [r['id'] for r in upstream if not r['draft'] and not r['prerelease']], 'completed': [], 'observed': {}}
        observe_tags(upstream, state)
        save_state(state, '')
        if not (args.tag and args.allow_backfill):
            emit(changed='false')
            print('首次仅记录现存正式 Release 基线；不会发布历史版本。')
            return
        old_state = git('ls-remote', 'origin', STATE_REF).split()[0]
    previous_state = json.dumps(state, sort_keys=True)
    observe_tags(upstream, state)
    if json.dumps(state, sort_keys=True) != previous_state:
        save_state(state, old_state)
        old_state = git('ls-remote', 'origin', STATE_REF).split()[0]
    provenance = ROOT / PROVENANCE
    record = json.loads(provenance.read_text()) if provenance.exists() else None
    selected = select_pending(upstream, state, record, args.tag, args.allow_backfill)
    if selected is None:
        emit(changed='false')
        return
    rid = selected['id']
    tag = selected['tag_name']
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._/-]*', tag) or '..' in tag:
        raise ValueError('非法上游 tag')
    if args.finalize:
        # 发布完成后才调用；重新验证候选与正式 Release，不盲目推进游标。
        record = json.loads((ROOT / PROVENANCE).read_text())
        if record['release_id'] != rid or record['upstream'] != state['observed'][str(rid)]['commit']:
            raise RuntimeError('发布记录不对应当前上游 Release 精确提交')
        own = [r for r in releases(REPO) if r['tag_name'] == 'v' + record['version'] and not r['draft'] and not r['prerelease']]
        if len(own) != 1:
            raise RuntimeError('正式发布尚未完成')
        validate_published(own[0], record['version'])
        if git('rev-parse', f"refs/tags/v{record['version']}^{{commit}}") != git('rev-parse', 'HEAD'):
            raise RuntimeError('正式 tag 与已验证候选不一致')
        state['completed'].append(rid)
        save_state(state, old_state)
        return
    if git('status', '--porcelain') or git('rev-parse', 'HEAD') != git('rev-parse', 'origin/main'):
        raise RuntimeError('必须从干净的 origin/main 准备正式候选')
    if git('remote', 'get-url', 'upstream') != 'https://github.com/fw867/hypercar.git':
        raise RuntimeError('上游仓库身份不匹配')
    git('fetch', '--no-tags', 'upstream', f'+refs/tags/{tag}:refs/upstream-official/selected')
    target = git('rev-parse', 'refs/upstream-official/selected^{commit}')
    if state['observed'][str(rid)]['commit'] != target:
        raise RuntimeError('官方 tag 在观测后发生移动，禁止继续')
    original = git('rev-parse', 'HEAD')
    existing = releases(REPO)
    provenance = ROOT / PROVENANCE
    if provenance.exists():
        record = json.loads(provenance.read_text())
        if record['release_id'] == rid:
            if record['upstream'] != target:
                raise RuntimeError('上游 tag 已移动，禁止复用已记录 Release')
            # 候选以已推 tag 指向的提交为准；HEAD 只允许在其上叠加元数据（CI/文档）提交，
            # 源码或版本一旦变化即禁止复用旧候选。
            version = record['version']
            code = record['code']
            candidate_commit = git('rev-parse', f'refs/tags/v{version}^{{commit}}')
            changed = git('diff', '--name-only', '--no-renames', candidate_commit + '..HEAD').splitlines()
            if any(not (p.startswith('.github/') or p.startswith('tools/') or p in ('README.md', 'AGENTS.md')) for p in changed):
                raise RuntimeError('候选之后 main 出现了源码或版本变化，需人工恢复未完成的正式发布')
            if any(r['tag_name'] == 'v' + version for r in existing):
                own = next(r for r in existing if r['tag_name'] == 'v' + version)
                validate_published(own, version)
                if git('rev-parse', f'refs/tags/v{version}^{{commit}}') != candidate_commit:
                    raise RuntimeError('已有 tag 与 main 候选不一致')
                state['completed'].append(rid)
                save_state(state, old_state)
                emit(changed='false')
                return
            other_versions = [properties(git('show', f"refs/tags/{r['tag_name']}:version.properties"))
                              for r in existing if not r['draft']]
            if git('ls-remote', 'origin', 'refs/heads/alpha'):
                other_versions.append(properties(git('show', 'origin/alpha:version.properties')))
            if any(int(v['versionCode']) >= int(code) for v in other_versions):
                raise RuntimeError('其他通道已占用更高安装编号，禁止发布旧候选；需人工恢复')
            emit(changed='true', version=version, code=code, upstream=target, base=original)
            return
    published = []
    for release in existing:
        if not release['draft']:
            published.append(properties(git('show', f"refs/tags/{release['tag_name']}:version.properties")))
    if git('ls-remote', 'origin', 'refs/heads/alpha'):
        published.append(properties(git('show', 'origin/alpha:version.properties')))
    current = properties((ROOT / 'version.properties').read_text())
    version, code = next_version(current, published)
    if git('ls-remote', 'origin', 'refs/tags/v' + version):
        raise RuntimeError('候选 tag 已存在，禁止覆盖')
    metadata = (ROOT / META).read_text()
    base = re.search(r'const val COMMIT = "([0-9a-f]{40})"', metadata)[1]
    git('merge-base', '--is-ancestor', base, target)
    git('merge-base', '--is-ancestor', base, original)
    saved = {p: git('rev-parse', f'HEAD:{p}') for p in PROTECTED}
    try:
        result = subprocess.run(['git', '-C', str(ROOT), 'merge', '--no-ff', '--no-commit', target])
        if result.returncode:
            conflicts = git('diff', '--name-only', '--diff-filter=U').splitlines()
            unresolved = [p for p in conflicts if not any(p == owned or p.startswith(owned + '/') for owned in PROTECTED)]
            if unresolved or not conflicts:
                raise RuntimeError(f'业务源码冲突，必须人工处理（上游 {tag} @ {target}）：' + repr(unresolved))
        git('restore', '--source=' + original, '--staged', '--worktree', '--', *PROTECTED)
        tree = git('write-tree')
        if any(git('rev-parse', tree + ':' + p) != blob for p, blob in saved.items()):
            raise RuntimeError('保护路径被修改')
        upstream_version = properties(git('show', target + ':version.properties'))
        for key, value in [('VERSION', upstream_version['versionName']), ('CODE', upstream_version['versionCode']), ('COMMIT', target)]:
            pattern = r'const val ' + key + (r' = \d+' if key == 'CODE' else r' = "[^"]+"')
            replacement = 'const val ' + key + ' = ' + (value if key == 'CODE' else '"' + value + '"')
            metadata, count = re.subn(pattern, lambda _: replacement, metadata)
            if count != 1:
                raise RuntimeError('底座元数据格式异常')
        (ROOT / META).write_text(metadata)
        (ROOT / 'version.properties').write_text(f'versionName={version}\nversionCode={code}\n')
        subject = f'chore: prepare official {version} from HyperCar release {rid}'
        provenance.write_text(json.dumps({'release_id': rid, 'tag': tag, 'upstream': target, 'version': version, 'code': code, 'subject': subject}, indent=2) + '\n')
        git('add', META, 'version.properties', PROVENANCE)
        git('diff', '--cached', '--check')
        git('commit', '-m', subject)
    except Exception:
        if git('rev-parse', '--git-path', 'MERGE_HEAD') and (ROOT / git('rev-parse', '--git-path', 'MERGE_HEAD')).exists():
            git('merge', '--abort')
        raise
    emit(changed='true', version=version, code=code, upstream=target, base=original)


if __name__ == '__main__':
    main()
