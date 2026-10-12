#!/bin/bash
UA="Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
enc() { python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
P=$(enc "калории-питание")
for q in "пирог с мясом и сыром" "Lotte Choco Pie" "борщ"; do
  curl -sS -A "$UA" -o /tmp/s.html "http://mobile.fatsecret.ru/$P/search?q=$(enc "$q")"
  echo "=== [$q]"
  python3 - <<'PY'
s=open('/tmp/s.html',encoding='utf-8',errors='ignore').read()
i=s.find('Калории:'); print(s[max(0,i-1400):i+1600] if i>=0 else s[:500])
PY
done
# a food page link from last search
python3 - <<'PY' > /tmp/link
import re
s=open('/tmp/s.html',encoding='utf-8',errors='ignore').read()
m=re.search(r'href="(/[^"]+)"[^>]*>\s*Борщ',s); print(m.group(1) if m else '')
PY
l=$(cat /tmp/link); echo "LINK $l"
[ -n "$l" ] && curl -sS -A "$UA" -o /tmp/f.html "http://mobile.fatsecret.ru$l" && python3 -c "
import re,html;s=open('/tmp/f.html',encoding='utf-8',errors='ignore').read();t=re.sub(r'\s+',' ',html.unescape(re.sub(r'<[^>]+>',' ',s)));i=t.find('Пищевая');print(t[i:i+1200])"
