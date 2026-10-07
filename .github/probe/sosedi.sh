set -x
H=(-H 'accept: application/json, text/plain, */*' -H 'referer: https://sosedi-dostavka.by/' -H 'user-agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/129 Mobile Safari/537.36')
B=https://dev.bazar-store.by
curl -s -m 20 "${H[@]}" "$B/v2/products/search?query=%D1%81%D1%8B%D1%80" | head -c 700; echo
curl -s -m 20 "$B/v2/products/search?query=%D1%81%D1%8B%D1%80" -o /dev/null -w "noheaders %{http_code}\n"
curl -s -m 20 "${H[@]}" "$B/api/productsList/10/%5B1318,300375575%5D" | head -c 900; echo
curl -s -m 20 "${H[@]}" "$B/api/productsList/10/[1318,300375575]" -o /dev/null -w "raw brackets %{http_code}\n"
curl -s -m 20 "${H[@]}" "$B/products/kefir-15-savushkin/10" | python3 -c "import json,sys; d=json.load(sys.stdin); print({k:v for k,v in d.items() if k not in ('description','descriptionTranslate','description_ru','composition','compositionTranslate')})"
curl -s -m 20 "${H[@]}" "$B/products/1318/10" -o /dev/null -w "by id %{http_code}\n"
curl -s -m 20 "$B/products/kefir-15-savushkin/10" -o /dev/null -w "detail noheaders %{http_code}\n"
