#!/bin/bash
UA="Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
enc() { python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
P=$(enc "калории-питание")
for host in www.fatsecret.ru mobile.fatsecret.ru; do
 for q in "пирог с мясом и сыром" "творог 5%" "гречка с курицей" "борщ"; do
  url="https://$host/$P/search?q=$(enc "$q")"
  code=$(curl -sS -L -A "$UA" -H "Accept-Language: ru" -o /tmp/s.html -w "%{http_code}" "$url")
  echo "=== $host [$q] $code $(wc -c </tmp/s.html)"; grep -o '<title>[^<]*' /tmp/s.html
  python3 - <<'PY'
import re,html
s=open('/tmp/s.html',encoding='utf-8',errors='ignore').read()
links=re.findall(r'href="(/[^"]*?калории-питание/[^"]+|/%D0%BA[^"]+)"[^>]*class="prominent"[^>]*>([^<]+)',s)
print('links',len(links)); [print(' ',l) for l in links[:5]]
txt=html.unescape(re.sub(r'<[^>]+>',' ',s)); txt=re.sub(r'\s+',' ',txt)
for m in list(re.finditer(r'Калори',txt))[:4]: print('  ..',txt[max(0,m.start()-120):m.start()+160])
PY
 done
done
# one food page
url="https://www.fatsecret.ru/$P/$(enc общий)/$(enc пирог-с-мясом)"
code=$(curl -sS -L -A "$UA" -o /tmp/f.html -w "%{http_code}" "$url"); echo "=== page $code $(wc -c </tmp/f.html)"
python3 -c "
import re,html;s=open('/tmp/f.html',encoding='utf-8',errors='ignore').read();t=re.sub(r'\s+',' ',html.unescape(re.sub(r'<[^>]+>',' ',s)))
i=t.find('Пищевая ценность');print(t[i:i+900] if i>=0 else t[:600])"
