# -*- coding: utf-8 -*-
"""
APK 污染核对：确认 zip 里没有「中央目录不引用的孤儿本地条目」。

为什么需要它：本项目在这台机器上反复踩到 zipflinger 的增量打包坑（PITFALLS P-01）——
增量 assembleDebug 出的包能装能跑，但本地条目区混进几 MB 孤儿数据，
体积从 3.2MB 翻到 6.07MB，肉眼看不出来，装到盒子上白占空间。
人眼分辨不了，所以做成机械检查。

判据：zip 文件里 `PK\\x03\\x04`（本地条目头）个数 与 `PK\\x01\\x02`（中央目录条目）个数
之差。正常包恒差 1（多出的一个是 dex 内数据的假阳性，历史所有正常包都如此），
污染时差值可达几十（实测 196 vs 149）。

用法：
  python tools/check_apk.py                       # 查 app/build/outputs/apk/debug 下全部 apk
  python tools/check_apk.py <apk> [apk ...]       # 查指定包
  python tools/check_apk.py --all                 # 连带查 dist/ 与 E:/share 的留档包

退出码 0 = 全部干净；1 = 有污染（此时**不许分发**，先 clean assembleDebug 重打）。
"""
import glob
import io
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.join(HERE, '..')
DEFAULT_DIR = os.path.join(BASE, 'app', 'build', 'outputs', 'apk', 'debug')
ARCHIVE_DIRS = [
    os.path.join(BASE, 'dist'),
    'E:/share',
]

LOCAL_SIG = b'PK\x03\x04'
CENTRAL_SIG = b'PK\x01\x02'
# 正常包本地头 - 中央条目 == 1（dex 内数据假阳性），超过即视为污染
TOLERANCE = 1


def check(path):
    """返回 (ok, local, central, size, note)"""
    with open(path, 'rb') as fp:
        data = fp.read()
    local = data.count(LOCAL_SIG)
    central = data.count(CENTRAL_SIG)
    size = len(data)

    try:
        names = zipfile.ZipFile(io.BytesIO(data)).namelist()
    except Exception as e:  # noqa: BLE001 —— 坏包本身就是问题，报告即可
        return False, local, central, size, 'zip 结构读不了：%s' % e

    dup = len(names) - len(set(names))
    if dup > 0:
        return False, local, central, size, '中央目录有 %d 个重名条目' % dup
    if local - central > TOLERANCE:
        return False, local, central, size, '孤儿本地条目 %d 个' % (local - central)
    return True, local, central, size, ''


def gather(argv):
    explicit = [p for p in argv if not p.startswith('--')]
    if explicit:
        return explicit
    files = sorted(glob.glob(os.path.join(DEFAULT_DIR, '*.apk')))
    if '--all' in argv:
        for d in ARCHIVE_DIRS:
            files += sorted(glob.glob(os.path.join(d, '*.apk')))
    return files


def main():
    files = gather(sys.argv[1:])
    if not files:
        print('没有找到任何 apk')
        return 1

    bad = []
    for p in files:
        ok, local, central, size, note = check(p)
        tag = 'OK  ' if ok else '脏包'
        print('%s %-52s %10s B  本地头=%-4d 中央=%-4d %s'
              % (tag, os.path.basename(p), '{:,}'.format(size), local, central, note))
        if not ok:
            bad.append(p)

    print('')
    if bad:
        print('发现 %d 个污染包（孤儿本地条目），**不要分发**：' % len(bad))
        for p in bad:
            print('  -', p)
        print('解法：cd vod-tv && ./gradlew clean assembleDebug 重打，再跑本脚本确认。')
        return 1
    print('全部干净（%d 个包）。' % len(files))
    return 0


if __name__ == '__main__':
    sys.exit(main())
