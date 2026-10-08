U='Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36'
H=(-A "$U" -H 'accept: application/json' -H 'content-type: application/json' -H 'apitoken: iEfhbZxLdbS4n8Umbg1l3cGERx2g7kwo' -H 'web-user-agent: SiteEdostavka/1.0.0' -H 'referer: https://edostavka.by/')
api() { curl -s -m 20 -X POST "${H[@]}" ${CK:+-H "cookie: $CK"} --data '{}' "https://edostavka.by/api/v2?path=search/preview/?query=$1"; }
e=$(python3 -c "import urllib.parse; print(urllib.parse.quote('кефир детский депи'))")
first=$(api "$e")
CK=$(echo "$first" | grep -o 'hg-security=[^;"]*' | head -1); echo "COOKIE ${CK:0:40}"
for q in "кефир детский депи" "кефир депи" "депи кефир" "сыр"; do
  e=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$q"); echo "### $q"
  api "$e" | python3 -c "
import sys,json
b=sys.stdin.read()
try:
  d=json.loads(b); [print(p['productId'],p['productName']) for p in d.get('products',[])[:12]]
except Exception: print('ERR',b[:150])"
done
echo "### product page with cookie"
curl -s -m 20 -A "$U" -H "cookie: $CK" "https://edostavka.by/product/1922907" -o p.html -w "HTTP %{http_code} %{size_download}\n"; python3 -c "
import re;t=re.sub(r'<[^>]+>',' ',open('p.html',encoding='utf-8',errors='ignore').read());t=re.sub(r'\s+',' ',t);i=t.find('На 100 грамм');print(t[i:i+200])"
echo "### product page cookie from its own challenge"
c2=$(curl -s -m 20 -A "$U" "https://edostavka.by/product/1922907" | grep -o 'hg-security=[^;"]*' | head -1)
curl -s -m 20 -A "$U" -H "cookie: $c2" "https://edostavka.by/product/1922907" -o p2.html -w "HTTP %{http_code} %{size_download}\n"
