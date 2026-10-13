import re, html, urllib.parse, urllib.request, sys
UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
BASE = "http://mobile.fatsecret.ru"; P = "/" + urllib.parse.quote("калории-питание")
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ru"})
    with urllib.request.urlopen(req, timeout=20) as r: return r.read().decode("utf-8", "ignore")
def text(s): return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", s))).strip()
ROW = re.compile(r'<td colspan="3" onclick=[^>]*>(.*?)</td>', re.S)
for q in sys.argv[1:]:
    s = get(f"{BASE}{P}/search?q={urllib.parse.quote(q)}")
    rows = [text(m.group(1))[:140] for m in ROW.finditer(s)]
    summ = re.search(r'class="page-summary">([^<]*)<', s)
    print(f"\n=== [{q}] {summ.group(1) if summ else '-'}")
    for r in rows[:6]: print("   ", r)
