#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把构建好的 APK 发到 GitHub Release（tag + Release + 附件）。

为什么要脚本：手动发一版要「读版本号 → 找包 → 过污染闸门 → 打 tag → 推 tag →
建 Release → 传 3MB 附件 → 核下载链路」，七八步里任何一步漏了都会发出脏包或错版本。
人眼盯不住，所以固化。

用法：
  python tools/release.py                     # 按 app/build.gradle 的版本号发版
  python tools/release.py --dry-run           # 只打印将要做的事，不动远程
  python tools/release.py --notes NOTES.md    # 在变更列表后插入自定义说明
  python tools/release.py --version 1.5.0     # 手动指定版本号（默认读 gradle）
  python tools/release.py --apk dist/xxx.apk  # 手动指定包（默认按版本号找）
  python tools/release.py --draft             # 发成草稿 Release

它会做的事（按顺序，任一步失败即中止）：
  1. 确认当前分支结构：main 必须已包含 dev 的全部提交（除非 --allow-unmerged）
  2. 定位 APK，并核对包内 versionName 与目标版本一致（有 aapt2 时）
  3. 跑 check_apk 的污染判定（PITFALLS P-01），脏包直接拒发
  4. 在 main 的 HEAD 上打 annotated tag 并推送到 origin
  5. 创建/更新 Release，正文 = 上一版 tag 到本版的提交标题
  6. 上传 APK 附件（同名资产先删后传），最后校验下载链路

凭证：从 git 的 credential helper 现取（Git Credential Manager 里那把 gho_ token），
不落盘、不打印。只需要 repo 写权限，不需要装 gh CLI。

本脚本只管 GitHub 侧的发布；本地留档、对外分发（U 盘 / 内网共享目录 / 网盘）属于你自己的
分发流程，不在这里做。
"""
import argparse
import glob
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
VODTV = os.path.dirname(HERE)
ROOT = os.path.dirname(VODTV)

sys.path.insert(0, HERE)
import check_apk  # noqa: E402  —— 复用污染判定，避免两套实现漂移

DEFAULT_BUILD_APK = os.path.join(VODTV, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
GRADLE = os.path.join(VODTV, 'app', 'build.gradle')
DIST = os.path.join(VODTV, 'dist')


# ---------------------------------------------------------------- 基础工具

def out(msg=''):
    print(msg)


def die(msg):
    print('')
    print('[失败] %s' % msg)
    sys.exit(1)


def git(*args, **kw):
    """跑 git，返回 stdout（去尾部空白）。失败抛异常。"""
    res = subprocess.run(
        ('git',) + args,
        cwd=ROOT,
        capture_output=True,
        encoding='utf-8',
        errors='replace',
        **kw
    )
    if res.returncode != 0:
        raise RuntimeError('git %s 失败：%s' % (' '.join(args), (res.stderr or res.stdout).strip()))
    return res.stdout.strip()


def git_ok(*args):
    """跑 git，只关心成不成功。"""
    res = subprocess.run(('git',) + args, cwd=ROOT, capture_output=True,
                         encoding='utf-8', errors='replace')
    return res.returncode == 0


def read_version():
    """从 app/build.gradle 读 versionName / versionCode。"""
    try:
        text = open(GRADLE, encoding='utf-8').read()
    except OSError as e:
        die('读不到 %s：%s' % (GRADLE, e))
    name = re.search(r'versionName\s+"([^"]+)"', text)
    code = re.search(r'versionCode\s+(\d+)', text)
    if not name:
        die('app/build.gradle 里没找到 versionName')
    return name.group(1), (code.group(1) if code else '?')


def find_aapt2():
    """尽量找到 aapt2，用于核对 APK 内版本号；找不到就返回 None（跳过该检查）。"""
    cands = []
    env = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if env:
        cands.append(env)
    local_props = os.path.join(VODTV, 'local.properties')
    if os.path.isfile(local_props):
        for line in open(local_props, encoding='utf-8'):
            if line.strip().startswith('sdk.dir'):
                cands.append(line.split('=', 1)[1].strip().replace('\\\\', '\\'))
    for sdk in cands:
        hits = sorted(glob.glob(os.path.join(sdk, 'build-tools', '*', 'aapt2.exe')))
        hits += sorted(glob.glob(os.path.join(sdk, 'build-tools', '*', 'aapt2')))
        if hits:
            return hits[-1]
    return None


def apk_version(apk):
    """用 aapt2 读 APK 的 versionName/versionCode；读不到返回 None。"""
    aapt2 = find_aapt2()
    if not aapt2:
        return None
    res = subprocess.run([aapt2, 'dump', 'badging', apk], capture_output=True,
                         encoding='utf-8', errors='replace')
    if res.returncode != 0:
        return None
    m = re.search(r"versionCode='(\d+)'\s+versionName='([^']*)'", res.stdout)
    return (m.group(2), m.group(1)) if m else None


def human(n):
    return '{:,}'.format(n)


# ---------------------------------------------------------------- 前置检查

def check_branch_state(allow_unmerged):
    """main 必须已包含 dev 的全部提交——main 只放验证过的稳定版本。"""
    try:
        cur = git('branch', '--show-current')
    except RuntimeError as e:
        die(str(e))
    behind = git('rev-list', '--count', 'main..dev')
    ahead = git('rev-list', '--count', 'dev..main')
    out('分支：当前 %s ｜ dev 领先 main %s 个提交 ｜ main 领先 dev %s 个' % (cur, behind, ahead))
    if behind != '0':
        if not allow_unmerged:
            die('dev 上还有 %s 个提交没合进 main。先验收合并（git checkout main && '
                'git merge --no-ff dev && git checkout dev），或加 --allow-unmerged 强制继续。' % behind)
        out('  [警告] dev 有 %s 个未合并提交，仍按 --allow-unmerged 继续，tag 打在 main 的 HEAD 上' % behind)
    try:
        remote = git('remote', 'get-url', 'origin')
    except RuntimeError:
        die('没有配置 origin 远程仓库')
    out('远程：%s' % remote)


def pick_apk(version, explicit):
    """按版本号找包：先看 dist 留档，再退回构建产物。"""
    if explicit:
        if not os.path.isfile(explicit):
            die('指定的 APK 不存在：%s' % explicit)
        return os.path.abspath(explicit)
    archived = os.path.join(DIST, 'Kankan-%s-debug.apk' % version)
    if os.path.isfile(archived):
        return archived
    if os.path.isfile(DEFAULT_BUILD_APK):
        out('  [提示] dist 里没有 Kankan-%s-debug.apk，改用构建产物 %s'
            % (version, os.path.relpath(DEFAULT_BUILD_APK, ROOT)))
        return DEFAULT_BUILD_APK
    die('找不到 APK：dist/Kankan-%s-debug.apk 与构建产物都不存在，先构建' % version)


def verify_apk(apk, version, code, skip_check):
    """包内版本号 + zip 污染双重核对。"""
    size = os.path.getsize(apk)
    out('包：%s（%s B）' % (apk, human(size)))

    info = apk_version(apk)
    if info is None:
        out('  [跳过] 没找到 aapt2，无法核对包内版本号（建议装好 Android SDK build-tools）')
    else:
        got_name, got_code = info
        out('  包内版本：versionName=%s versionCode=%s' % (got_name, got_code))
        if got_name != version:
            die('包内 versionName 是 %s，与目标版本 %s 不一致——八成拿错包了' % (got_name, version))
        if code != '?' and got_code != code:
            die('包内 versionCode 是 %s，但 gradle 里写的是 %s' % (got_code, code))

    if skip_check:
        out('  [警告] --skip-check 跳过了污染判定，出问题自己负责')
        return
    ok, local, central, psize, note = check_apk.check(apk)
    out('  污染判定：本地头=%d 中央目录=%d %s' % (local, central, note or '干净'))
    if not ok:
        die('这是污染包（PITFALLS P-01）：%s。先 cd vod-tv && ./gradlew clean assembleDebug 重打'
            % note)


# ---------------------------------------------------------------- GitHub API

def get_token():
    """从 git credential helper 现取 token，不落盘。"""
    payload = 'protocol=https\nhost=github.com\n\n'
    try:
        res = subprocess.run(['git', 'credential', 'fill'], cwd=ROOT, input=payload,
                             capture_output=True, encoding='utf-8', errors='replace')
    except OSError as e:
        die('调用 git credential 失败：%s' % e)
    for line in (res.stdout or '').splitlines():
        if line.startswith('password='):
            tok = line.split('=', 1)[1].strip()
            if tok:
                return tok
    die('git 里没有可用的 GitHub 凭证。先手工 push 一次让 Git Credential Manager 存好，'
        '或用 GITHUB_TOKEN 环境变量指定')


def make_api(token, proxy):
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({'http': proxy, 'https': proxy} if proxy else {})
    )

    def api(url, method='GET', payload=None, raw=None, ctype='application/json'):
        req = urllib.request.Request(url, method=method)
        req.add_header('Authorization', 'token ' + token)
        req.add_header('Accept', 'application/vnd.github+json')
        req.add_header('User-Agent', 'kankan-release')
        if payload is not None:
            req.data = json.dumps(payload).encode('utf-8')
            req.add_header('Content-Type', ctype)
        elif raw is not None:
            req.data = raw
            req.add_header('Content-Type', ctype)
        with opener.open(req, timeout=300) as resp:
            body = resp.read()
            return json.loads(body) if body else {}

    return api


def build_body(version, code, tag, notes_file, target):
    """正文 = 版本头 + 上一版到本版的提交标题 + 安装/说明尾巴。"""
    # 上一版：优先取当前 tag 之前最近的 tag（重发时也正确）
    prev = None
    if git_ok('rev-parse', '--verify', '--quiet', 'refs/tags/' + tag):
        try:
            prev = git('describe', '--tags', '--abbrev=0', tag + '^')
        except RuntimeError:
            prev = None
    if prev is None:
        others = [t for t in git('tag', '--list', 'v*', '--sort=-v:refname').splitlines()
                  if t != tag]
        prev = others[0] if others else None

    rng = '%s..%s' % (prev, target) if prev else target
    try:
        log = git('log', '--pretty=- %s', rng)
    except RuntimeError:
        log = '- （无法读取提交历史）'

    lines = [l for l in log.splitlines() if l.strip()]
    if not lines:
        lines = ['- 本版无新增提交']
    elif len(lines) > 15:  # 首个版本会带全历史，截断避免正文长得没法看
        lines = lines[:15] + ['- …… 以及更早的 %d 条提交' % (len(lines) - 15)]
    log = '\n'.join(lines)

    parts = [
        '**看看 Kankan %s**（versionCode %s）' % (version, code),
        '',
        'Android 4.4+ 可直接安装，debug 签名。',
        '',
        '### 这一版改了什么',
        '',
        log,
    ]
    if notes_file:
        if not os.path.isfile(notes_file):
            die('--notes 指定的文件不存在：%s' % notes_file)
        extra = open(notes_file, encoding='utf-8').read().strip()
        if extra:
            parts += ['', '### 补充说明', '', extra]

    parts += [
        '',
        '### 安装',
        '',
        '```bash',
        'adb install -r Kankan-%s-debug.apk' % version,
        '```',
        '',
        '也可以拷到 U 盘插电视盒子，用文件管理器安装。',
        '',
        '### 说明',
        '',
        '- minSdk 19（Android 4.4）/ targetSdk 28，ExoPlayer 2.19.1 + HLS',
        '- **debug 签名**，仅供个人设备安装验证，不是可上架的正式包',
        '- 不含任何影片文件，播放地址均在运行时从第三方采集站获取；详见仓库 README 的「合规」一节',
    ]
    return '\n'.join(parts), prev


def repo_slug():
    """从 origin 取出 owner/repo，兼容 https 与 ssh 两种写法。"""
    url = git('remote', 'get-url', 'origin')
    m = re.search(r'github\.com[:/]+([^/]+)/([^/]+?)(?:\.git)?$', url)
    if not m:
        die('看不懂 origin 地址：%s（本脚本只支持 github.com）' % url)
    return '%s/%s' % (m.group(1), m.group(2))


# ---------------------------------------------------------------- 主流程

def main():
    try:  # Windows 控制台默认 GBK，中文多的时候容易炸，能切就切
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:  # noqa: BLE001
        pass

    ap = argparse.ArgumentParser(
        description='把 APK 发到 GitHub Release（tag + 正文 + 附件），发版前自动过污染闸门',
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument('--version', help='版本号（默认读 app/build.gradle 的 versionName）')
    ap.add_argument('--apk', help='APK 路径（默认 dist/Kankan-<version>-debug.apk，缺失则用构建产物）')
    ap.add_argument('--tag', help='tag 名（默认 v<version>）')
    ap.add_argument('--notes', help='附加说明的 markdown 文件，内容插在变更列表之后')
    ap.add_argument('--draft', action='store_true', help='发成草稿，不对外可见')
    ap.add_argument('--prerelease', action='store_true', help='标记为预发布')
    ap.add_argument('--dry-run', action='store_true', help='只打印计划，不打 tag、不推、不发 Release')
    ap.add_argument('--allow-unmerged', action='store_true', help='dev 未合进 main 时也继续')
    ap.add_argument('--skip-check', action='store_true', help='跳过 zip 污染判定（不建议）')
    ap.add_argument('--skip-push', action='store_true', help='本地打 tag 但不推送到 origin')
    ap.add_argument('--proxy', help='HTTP(S) 代理，如 http://127.0.0.1:7890（默认直连）')
    args = ap.parse_args()

    gradle_ver, gradle_code = read_version()
    version = args.version or gradle_ver
    code = gradle_code if version == gradle_ver else '?'
    tag = args.tag or ('v%s' % version)

    out('=' * 62)
    out('发版：Kankan %s（versionCode %s）→ tag %s' % (version, code, tag))
    out('=' * 62)

    # 1. 分支结构
    check_branch_state(args.allow_unmerged)

    # 2. 找包 + 核对
    apk = pick_apk(version, args.apk)
    verify_apk(apk, version, code, args.skip_check)

    # 3. tag 目标与变更正文
    tag_exists = git_ok('rev-parse', '--verify', '--quiet', 'refs/tags/' + tag)
    target = git('rev-parse', 'main')
    out('tag 目标：main 的 HEAD %s%s' % (target[:7], '（tag 已存在，复用）' if tag_exists else ''))

    body, prev = build_body(version, code, tag, args.notes, target)
    out('变更范围：%s' % ('%s..%s' % (prev, target[:7]) if prev else '%s（首个版本）' % target[:7]))

    if args.dry_run:
        out('')
        out('--- dry-run：以下动作没有执行 ---')
        out('  打 tag  %s → %s' % (tag, target[:7]))
        out('  推送    %s' % ('跳过（--skip-push）' if args.skip_push else 'tag 到 origin'))
        out('  发版    %s' % ('草稿' if args.draft else '正式 Release'))
        out('  附件    %s（%s B）' % (os.path.basename(apk), human(os.path.getsize(apk))))
        out('')
        out('--- Release 正文预览 ---')
        out(body)
        return 0

    if not tag_exists:
        git('tag', '-a', tag, target, '-m', '看看 Kankan %s（versionCode %s）' % (version, code))
        out('  已建 tag %s' % tag)

    if args.skip_push:
        out('  按 --skip-push 未推送 tag')
    else:
        # push 必须检查返回值：网络抖动时静默失败会让 Release 指向一个远端不存在的 tag
        base = ['git']
        if args.proxy:
            base += ['-c', 'http.proxy=' + args.proxy, '-c', 'https.proxy=' + args.proxy]
        last_err = ''
        for attempt in (1, 2, 3):
            res = subprocess.run(base + ['push', 'origin', tag], cwd=ROOT,
                                 capture_output=True, encoding='utf-8', errors='replace')
            if res.returncode == 0:
                out('  已推 tag 到 origin')
                last_err = ''
                break
            last_err = (res.stderr or res.stdout or '').strip().splitlines()
            last_err = last_err[-1] if last_err else '未知错误'
            out('  第 %d 次推 tag 失败：%s' % (attempt, last_err))
            if attempt < 3:
                time.sleep(2)
        if last_err:
            die('推 tag 连续失败，已中止（未创建 Release）。\n'
                '  常见原因：github.com:443 不通（api.github.com 通不代表主站通）。\n'
                '  可加 --proxy http://127.0.0.1:端口 重试，或稍后网络恢复再跑。')

        # 二次确认远端真的有这个 tag，避免建出指向空 tag 的 Release
        try:
            remote_tags = git('ls-remote', '--tags', 'origin')
        except RuntimeError as e:
            die('无法读取远端 tag 列表：%s' % e)
        if ('refs/tags/%s' % tag) not in {l.split('\t')[-1].strip()
                                          for l in remote_tags.splitlines() if l.strip()}:
            die('远端没有 %s（推送报告成功但读不到），已中止，请复查 origin' % tag)
        out('  远端已确认存在 %s' % tag)

    # 5. Release
    token = get_token()
    api = make_api(token, args.proxy)
    repo = repo_slug()

    rel = None
    try:
        rel = api('https://api.github.com/repos/%s/releases/tags/%s' % (repo, tag))
        out('  Release 已存在，更新正文（id=%s）' % rel['id'])
        rel = api('https://api.github.com/repos/%s/releases/%s' % (repo, rel['id']),
                  method='PATCH',
                  payload={'name': '看看 Kankan %s' % tag, 'body': body,
                           'draft': bool(args.draft), 'prerelease': bool(args.prerelease)})
    except urllib.error.HTTPError as e:
        if e.code != 404:
            die('查询 Release 失败：%s' % e)
    if rel is None:
        rel = api('https://api.github.com/repos/%s/releases' % repo, method='POST',
                  payload={
                      'tag_name': tag,
                      'target_commitish': 'main',
                      'name': '看看 Kankan %s' % tag,
                      'body': body,
                      'draft': bool(args.draft),
                      'prerelease': bool(args.prerelease),
                  })
        out('  已创建 Release（id=%s）' % rel['id'])

    # 6. 附件
    name = os.path.basename(apk)
    for a in api('https://api.github.com/repos/%s/releases/%s/assets' % (repo, rel['id'])):
        if a['name'] == name:
            api('https://api.github.com/repos/%s/releases/assets/%s' % (repo, a['id']),
                method='DELETE')
            out('  删掉同名旧附件 %s' % name)

    asset = api(
        'https://uploads.github.com/repos/%s/releases/%s/assets?name=%s' % (repo, rel['id'], name),
        method='POST', raw=open(apk, 'rb').read(),
        ctype='application/vnd.android.package-archive',
    )
    out('  已上传附件 %s（%s B，状态 %s）' % (asset['name'], human(asset['size']), asset['state']))

    out('')
    out('完成：')
    out('  Release  %s' % rel['html_url'])
    out('  下载     %s' % asset['browser_download_url'])
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print('')
        sys.exit(130)
