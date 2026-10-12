#!/bin/bash
UA="Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
enc() { python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
P=$(enc "калории-питание")
for h in www.fatsecret.ru mobile.fatsecret.ru; do
  url="http://$h/$P/search?q=$(enc "пирог с мясом и сыром")"
  curl -sS -A "$UA" -o /tmp/h.html -D /tmp/h.hdr -w "HTTP $h %{http_code} %{size_download} redirect=%{redirect_url}\n" "$url"; head -3 /tmp/h.hdr
done
curl -sS -L -A "$UA" -o /tmp/h.html -w "follow %{http_code} %{url_effective} %{size_download}\n" "http://www.fatsecret.ru/$P/search?q=$(enc "пирог с мясом и сыром")" 2>&1 | tail -1
for q in "пирог с мясом и сыром" "Lotte Choco Pie" "кефир 2.5" "суп куриный домашний"; do
 for h in www.fatsecret.ru mobile.fatsecret.ru; do
  curl -sS -k -A "$UA" -o /tmp/s_$h.html "https://$h/$P/search?q=$(enc "$q")"
 done
 echo "=== [$q]"
 python3 - "$q" <<'PY'
import re,sys
for h in ['www.fatsecret.ru','mobile.fatsecret.ru']:
  s=open(f'/tmp/s_{h}.html',encoding='utf-8',errors='ignore').read()
  i=s.find('Калории:'); print('--',h,len(s)); print(s[max(0,i-1500):i+700] if i>=0 else s[:300])
  break
PY
done
