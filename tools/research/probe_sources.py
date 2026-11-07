#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量探测苹果CMS v10 采集站：可达性、内容量、分类覆盖、是否直出 m3u8。

只做只读 GET，用来选源。用法：
    python probe_sources.py sources.json
sources.json: [{"id":"xx","name":"xx","host":"https://...","path":"/api.php/provide/vod/"}, ...]
"""
import json
import ssl
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0 Safari/537.36"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def get(url, timeout=15):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        body = r.read()
        return body, time.time() - t0, r.status


def probe(s):
    host = s["host"].rstrip("/")
    path = s.get("path") or "/api.php/provide/vod/"
    base = host + path
    out = {"id": s["id"], "name": s["name"], "host": host, "path": path}

    # 1) 分类 + 首页
    try:
        body, dt, code = get(base + "?ac=list")
        j = json.loads(body.decode("utf-8", "ignore"))
        classes = j.get("class") or []
        out["ok"] = True
        out["list_code"] = code
        out["ms"] = int(dt * 1000)
        out["classes"] = len(classes)
        out["class_names"] = [c.get("type_name") for c in classes][:40]
        out["total"] = j.get("total")
        out["sample"] = len(j.get("list") or [])
        # 是否有 m3u8（直出）
        txt = body.decode("utf-8", "ignore")
        out["m3u8"] = ".m3u8" in txt
    except Exception as e:
        out["ok"] = False
        out["err"] = "%s: %s" % (type(e).__name__, str(e)[:80])
        return out

    # 2) 某个分类第一页（看有没有真数据、是否 m3u8）
    try:
        # 必须挑二级分类（type_pid != 0）：一级分类下面往往只有十几条汇总数据
        cid = None
        for c in (j.get("class") or []):
            if c.get("type_pid") not in (0, None):
                cid = c.get("type_id")
                break
        if cid is None:
            for c in (j.get("class") or []):
                if c.get("type_id") is not None:
                    cid = c.get("type_id")
                    break
        if cid is not None:
            body, dt, code = get("%s?ac=detail&pg=1&t=%s" % (base, cid))
            jj = json.loads(body.decode("utf-8", "ignore"))
            items = jj.get("list") or []
            out["cat_items"] = len(items)
            out["cat_total"] = jj.get("total")
            if items:
                pl = items[0].get("vod_play_url") or ""
                out["cat_m3u8"] = ".m3u8" in pl
                out["cat_title"] = items[0].get("vod_name")
    except Exception as e:
        out["cat_err"] = "%s: %s" % (type(e).__name__, str(e)[:60])

    # 3) 搜索能力（新片）
    try:
        kw = urllib.parse.quote("庆余年")
        body, dt, code = get("%s?ac=detail&wd=%s" % (base, kw))
        jj = json.loads(body.decode("utf-8", "ignore"))
        out["search_hits"] = len(jj.get("list") or [])
    except Exception as e:
        out["search_err"] = "%s: %s" % (type(e).__name__, str(e)[:60])

    return out


def main():
    src = json.load(open(sys.argv[1], encoding="utf-8"))
    with ThreadPoolExecutor(max_workers=8) as ex:
        rows = list(ex.map(probe, src))
    print(json.dumps(rows, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
