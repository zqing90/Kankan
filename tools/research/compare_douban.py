# -*- coding: utf-8 -*-
"""豆瓣两个榜单接口的正面对比探测（只调研，不改 App 代码）。

A = rexxar subject_collection（「看看」现用）
B = movie.douban.com/j/new_search_subjects（影视仓首页「豆瓣热播」用）
"""
import io, json, time, urllib.request, sys

UA_M = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 Mobile Safari/537.36"
UA_D = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

def get(url, referer=None, ua=UA_M, timeout=15):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", ua)
    if referer:
        req.add_header("Referer", referer)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode("utf-8", "replace")
            return {"status": r.status, "ms": int((time.time()-t0)*1000), "body": body}
    except urllib.error.HTTPError as e:
        return {"status": e.code, "ms": int((time.time()-t0)*1000), "body": e.read().decode("utf-8","replace")[:200]}
    except Exception as e:
        return {"status": -1, "ms": int((time.time()-t0)*1000), "body": str(e)[:200]}

def jload(r):
    try:
        return json.loads(r["body"])
    except Exception:
        return None

results = []

def rec(name, r, note=""):
    results.append({"case": name, "status": r["status"], "ms": r["ms"], "note": note})
    print("%-46s http=%-4s %5dms  %s" % (name, r["status"], r["ms"], note))

print("== A. rexxar subject_collection（现用） ==")
u = "https://m.douban.com/rexxar/api/v2/subject_collection/movie_hot_gaia/items?start=0&count=60"
r = get(u, referer="https://m.douban.com/")
j = jload(r)
n = len((j or {}).get("subject_collection_items") or [])
first = ((j or {}).get("subject_collection_items") or [{}])[0]
rec("A1 正常请求 count=60", r, "items=%d 首条字段=%s" % (n, ",".join(sorted(first.keys()))[:90]))

r2 = get(u.replace("count=60", "count=100"), referer="https://m.douban.com/")
j2 = jload(r2)
rec("A2 count=100（看有没有上限）", r2, "items=%d" % len((j2 or {}).get("subject_collection_items") or []))

r3 = get("https://m.douban.com/rexxar/api/v2/subject_collection/movie_hot_gaia/items?start=60&count=30", referer="https://m.douban.com/")
j3 = jload(r3)
t3 = [x.get("title") for x in (j3 or {}).get("subject_collection_items") or []][:3]
rec("A3 start=60 深翻页", r3, "items=%s 首3=%s" % (len((j3 or {}).get("subject_collection_items") or []), t3))

r4 = get(u, referer=None)
rec("A4 不带 Referer", r4, "items=%s" % (len((jload(r4) or {}).get("subject_collection_items") or [])))

# 封面字段形态
cov = (first.get("cover") or {})
if isinstance(cov, dict):
    rec("A5 封面字段", r, "cover.url=%s" % str(cov.get("url"))[:60])
else:
    rec("A5 封面字段", r, "cover=%s" % str(cov)[:60])
rate = (first.get("rating") or {})
rec("A5b 评分字段", r, "rating.value=%s" % (rate.get("value") if isinstance(rate, dict) else rate))

print()
print("== B. new_search_subjects（影视仓用） ==")
B = "https://movie.douban.com/j/new_search_subjects"
r5 = get(B + "?sort=U&range=0,10&tags=&playable=1&start=0&year_range=2026,2026", ua=UA_D)
j5 = jload(r5)
d5 = (j5 or {}).get("data") or []
rec("B1 默认(当年)", r5, "items=%d 字段=%s" % (len(d5), ",".join(sorted(d5[0].keys()))[:90] if d5 else "-"))

for st in (20, 40, 60):
    r6 = get(B + "?sort=U&range=0,10&tags=&playable=1&start=%d&year_range=2026,2026" % st, ua=UA_D)
    j6 = jload(r6)
    rec("B2 start=%d" % st, r6, "items=%d" % len((j6 or {}).get("data") or []))

r7 = get(B + "?sort=U&range=0,10&tags=&playable=1&start=0", ua=UA_D)
j7 = jload(r7)
rec("B3 不限年份", r7, "items=%d" % len((j7 or {}).get("data") or []))

r8 = get(B + "?sort=U&range=0,10&tags=电视剧&playable=1&start=0&year_range=2026,2026", ua=UA_D)
rec("B4 tags=电视剧", r8, "items=%d" % len((jload(r8) or {}).get("data") or []))

r9 = get(B + "?sort=T&range=0,10&tags=&playable=1&start=0&year_range=2026,2026", ua=UA_D)
j9 = jload(r9)
t9 = [x.get("title") for x in (j9 or {}).get("data") or []][:3]
rec("B5 sort=T（近期热度）", r9, "items=%d 首3=%s" % (len((j9 or {}).get("data") or []), t9))

r10 = get(B + "?sort=U&range=0,10&tags=&playable=1&start=0&year_range=2026,2026", ua=None)
rec("B6 无 UA", r10, "items=%s" % (len((jload(r10) or {}).get("data") or [])))

r11 = get(B + "?sort=S&range=0,10&tags=&playable=1&start=0&year_range=2026,2026", ua=UA_D)
j11 = jload(r11)
t11 = [(x.get("title"), x.get("rate")) for x in (j11 or {}).get("data") or []][:4]
rec("B7 sort=S（高分优先）", r11, "items=%d %s" % (len((j11 or {}).get("data") or []), t11))

io.open("tools/research/compare_douban_out.json", "w", encoding="utf-8").write(
    json.dumps(results, ensure_ascii=False, indent=1))
print("\nsaved tools/research/compare_douban_out.json")
