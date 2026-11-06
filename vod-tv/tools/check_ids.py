# -*- coding: utf-8 -*-
"""
id 类型核对：java 里的 findViewById 强转类型 必须与布局里的控件标签兼容。

为什么需要它：改布局结构（比如把空态的 TextView 换成 LinearLayout）时，
`(TextView) findViewById(R.id.empty)` 依然能编译通过 —— 强转在编译期不校验，
运行时才抛 ClassCastException。这类崩溃装到电视上才发现，代价很高。

用法：python tools/check_ids.py
退出码 0 = 全部兼容；1 = 有不兼容项。
"""
import glob
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.join(HERE, '..', 'app', 'src', 'main')

# 声明类型 -> 允许的实际标签。None 表示「任何都行」（View 是所有控件的父类）
OK = {
    'View': None,
    'TextView': {'TextView', 'EditText', 'Button'},
    'ImageView': {'ImageView', 'RoundImageView'},
    'LinearLayout': {'LinearLayout'},
    'FrameLayout': {'FrameLayout'},
    'RelativeLayout': {'RelativeLayout'},
    'GridView': {'GridView'},
    'ProgressBar': {'ProgressBar'},
    'HorizontalScrollView': {'HorizontalScrollView'},
    'ScrollView': {'ScrollView'},
}


def collect_ids():
    id2tag = {}
    for f in glob.glob(os.path.join(BASE, 'res', 'layout', '*.xml')):
        s = io.open(f, encoding='utf-8').read()
        for tag, attrs in re.findall(r'<([\w.]+)([^>]*)>', s):
            m = re.search(r'android:id="@\+id/(\w+)"', attrs)
            if m:
                # 同名 id 在多个布局里出现时以第一次为准（本项目都是同一种控件）
                id2tag.setdefault(m.group(1), tag.split('.')[-1])
    return id2tag


def main():
    id2tag = collect_ids()
    bad = []
    for f in glob.glob(os.path.join(BASE, 'java', '**', '*.java'), recursive=True):
        s = io.open(f, encoding='utf-8').read()
        for typ, rid in re.findall(r'\(([\w.]+)\)\s*findViewById\(R\.id\.(\w+)\)', s):
            t = typ.split('.')[-1]
            tag = id2tag.get(rid)
            if tag is None:
                continue
            allow = OK.get(t, {t})
            if allow is None:
                continue
            if tag not in allow:
                bad.append('%s: (%s) R.id.%s  实际是 <%s>'
                           % (os.path.basename(f), t, rid, tag))

    print('布局 id 总数: %d' % len(id2tag))
    if bad:
        print('!! 类型不兼容 %d 处:' % len(bad))
        for b in bad:
            print('  ' + b)
        return 1
    print('OK: 所有 findViewById 强转类型与布局一致')
    return 0


if __name__ == '__main__':
    sys.exit(main())
