import re, html, urllib.parse, urllib.request, sys
UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
BASE = "http://mobile.fatsecret.ru"
P = "/" + urllib.parse.quote("калории-питание")
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ru"})
    with urllib.request.urlopen(req, timeout=20) as r: return r.read().decode("utf-8", "ignore")
def text(s): return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", s))).strip()
ROW = re.compile(r'<td colspan="3" onclick=[^>]*>(.*?)</td>', re.S)
def search(q, pg=None):
    url = f"{BASE}{P}/search?q={urllib.parse.quote(q)}" + (f"&pg={pg}" if pg is not None else "")
    s = get(url)
    out = []
    for m in ROW.finditer(s):
        row = m.group(1)
        link = re.search(r'<a class="inner-link" href="([^"]+)">([^<]+)</a>', row)
        brand = re.search(r'<a class="brand"[^>]*>\(([^<]*)\)</a>', row)
        out.append((urllib.parse.unquote(link.group(1)) if link else None, link.group(2) if link else None,
                    brand.group(1) if brand else None, text(row)[:230]))
    nxt = re.findall(r'href="([^"]*search[^"]*pg=[^"]*)"', s)
    return out, nxt[:3]
qs = sys.argv[1:]
for q in qs:
    rows, nxt = search(q)
    print(f"\n=== [{q}] rows={len(rows)} next={[urllib.parse.unquote(n) for n in nxt]}")
    for r in rows: print("  ", r[1], "|", r[2], "|", r[0]); print("      ", r[3])
# page 2 for one query
rows, _ = search("пирог с грибами", 1)
print("\n=== page pg=1 пирог с грибами", len(rows))
for r in rows: print("  ", r[1], "|", r[2], "|", r[3][:150])
# food pages
for path in ["/калории-питание/общий/Пирог-с-Мясом", "/калории-питание/Провиант/Пирог-с-Мясом-и-Сыром/100г", "/калории-питание/общий/Драники"]:
    url = BASE + urllib.parse.quote(path)
    try:
        s = get(url)
    except Exception as e:
        print("ERR", path, e); continue
    print("\n=== PAGE", path, len(s))
    i = s.find("Размер Порции"); i = i if i >= 0 else s.find("порци")
    print(s[max(0, i-1500): i+2500])
