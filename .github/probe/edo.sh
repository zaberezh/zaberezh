U='Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36'
for q in "кефир детский депи" "кефир депи" "депи" "сыр"; do
  e=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$q")
  echo "### $q"
  curl -s -m 20 -X POST -A "$U" -H 'accept: application/json' -H 'content-type: application/json' -H 'apitoken: iEfhbZxLdbS4n8Umbg1l3cGERx2g7kwo' -H 'web-user-agent: SiteEdostavka/1.0.0' -H 'referer: https://edostavka.by/' \
    --data '{}' "https://edostavka.by/api/v2?path=search/preview/?query=$e" -w "\nHTTP %{http_code}\n" | python3 -c "
import sys,json
raw=sys.stdin.read(); body,code=raw.rsplit('HTTP',1); print('code',code.strip())
try:
  d=json.loads(body); [print(p['productId'],p['productName']) for p in d.get('products',[])[:12]]
except Exception as ex: print('ERR',body[:300])"
done
echo "### no token"
curl -s -m 20 -X POST -A "$U" -H 'content-type: application/json' --data '{}' "https://edostavka.by/api/v2?path=search/preview/?query=kefir" -o /dev/null -w "HTTP %{http_code}\n"
echo "### GET instead of POST"
curl -s -m 20 -A "$U" -H 'apitoken: iEfhbZxLdbS4n8Umbg1l3cGERx2g7kwo' -H 'web-user-agent: SiteEdostavka/1.0.0' "https://edostavka.by/api/v2?path=search/preview/?query=kefir" -o /dev/null -w "HTTP %{http_code}\n"
echo "### product page plain"
curl -s -m 20 -A "$U" -H 'Accept-Language: ru-RU' "https://edostavka.by/product/1922907" -o p.html -w "HTTP %{http_code} %{size_download}\n"; python3 -c "
import re;t=re.sub(r'<[^>]+>',' ',open('p.html',encoding='utf-8',errors='ignore').read());t=re.sub(r'\s+',' ',t);i=t.find('На 100 грамм');print(t[i:i+200])"
echo "### product api"
curl -s -m 20 -A "$U" -H 'apitoken: iEfhbZxLdbS4n8Umbg1l3cGERx2g7kwo' -H 'web-user-agent: SiteEdostavka/1.0.0' "https://edostavka.by/api/v2?path=product/1922907" | head -c 1500; echo
