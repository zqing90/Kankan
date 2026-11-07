#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""抓 TVBox / 影视仓 配置，收集里面「type=1 的苹果CMS 站点」的 api 地址。

我们自己的客户端只有 HTTP + JSON 解析能力，跑不了 type=3 的 spider jar，
所以只关心 api 形如 .../api.php/provide/vod/ 的站点。

用法：python harvest_configs.py urls.txt > out.json
"""
import json
import re
import ssl
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = "okhttp/3.15"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def fetch(url, timeout=15):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        raw = r.read()
    txt = raw.decode("utf-8", "ignore").strip()
    # 有的仓前面带 BOM 或注释
    txt = txt.lstrip("\ufeff")
    return txt


def parse_json(txt):
    """TVBox 配置合法 JSON 一般不宽松，但有人会写注释/尾逗号，失败就用正则兜底"""
    try:
        return json.loads(txt)
    except Exception:
        m = re.search(r"\{[\s\S]*\}", txt)
        if not m:
            return None
        try:
            return json.loads(m.group(0))
        except Exception:
            return None


def sites_of(url, depth=0):
    """返回 [(key,name,api,type)]；遇到多仓（urls）往下抓一层"""
    out = []
    try:
        txt = fetch(url)
    except Exception as e:
        return out, "fetch_fail: %s" % str(e)[:60]
    j = parse_json(txt)
    if j is None:
        return out, "not_json"
    if isinstance(j, dict) and isinstance(j.get("urls"), list) and "sites" not in j:
        # 多仓：抓前几个子仓
        subs = [u.get("url") for u in j["urls"] if isinstance(u, dict) and u.get("url")][:6]
        if depth == 0:
            for s in subs:
                sub_sites, _ = sites_of(s, depth + 1)
                out += sub_sites
        return out, "multi(%d)" % len(j["urls"])
    for s in (j.get("sites") or []):
        if not isinstance(s, dict):
            continue
        api = s.get("api") or ""
        out.append({
            "key": s.get("key"),
            "name": s.get("name"),
            "api": api,
            "type": s.get("type"),
            "searchable": s.get("searchable", 1),
            "src": url,
        })
    return out, "ok"


def main():
    urls = [u.strip() for u in open(sys.argv[1], encoding="utf-8") if u.strip() and not u.startswith("#")]
    all_sites = []
    with ThreadPoolExecutor(max_workers=6) as ex:
        for sites, status in ex.map(sites_of, urls):
            all_sites += sites
    # 只留 http 直连的 CMS
    cms = []
    seen = set()
    for s in all_sites:
        api = s["api"]
        if not api.startswith("http"):
            continue
        if "provide/vod" not in api:
            continue
        if api in seen:
            continue
        seen.add(api)
        cms.append(s)
    print(json.dumps({"total_sites": len(all_sites), "cms": cms}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
