#!/bin/bash
UA="Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
date -u
for h in fatsecret.ru www.fatsecret.ru mobile.fatsecret.ru www.fatsecret.com foods.fatsecret.com platform.fatsecret.com www.fatsecret.kz www.fatsecret.com.ua; do
  echo "--- $h"; echo | timeout 10 openssl s_client -servername $h -connect $h:443 2>/dev/null | openssl x509 -noout -subject -issuer -dates 2>&1 | head -5
  curl -sS -o /dev/null -w "verify-ok http %{http_code}\n" -A "$UA" "https://$h/" 2>&1 | tail -1
done
enc() { python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
P=$(enc "калории-питание")
for q in "пирог с мясом и сыром" "творог 5%" "борщ"; do
  url="https://www.fatsecret.ru/$P/search?q=$(enc "$q")"
  code=$(curl -sS -k -L -A "$UA" -H "Accept-Language: ru" -o /tmp/s.html -w "%{http_code}" "$url"); echo "=== [$q] $code $(wc -c </tmp/s.html)"
  grep -o '<title>[^<]*' /tmp/s.html
  python3 - <<'PY'
import re,html
s=open('/tmp/s.html',encoding='utf-8',errors='ignore').read()
for m in list(re.finditer(r'class="prominent"',s))[:3]: print('  A',s[max(0,m.start()-200):m.start()+120].replace('\n',' '))
txt=re.sub(r'\s+',' ',html.unescape(re.sub(r'<[^>]+>',' ',s)))
for m in list(re.finditer(r'Калори',txt))[:5]: print('  ..',txt[max(0,m.start()-150):m.start()+130])
PY
done
