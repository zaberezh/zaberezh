U='Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36'
curl -s -m 20 -A "$U" -D - "https://edostavka.by/search?query=kefir" | head -c 3000; echo
curl -s -m 20 -A "$U" -o /dev/null -w "home %{http_code}\n" https://edostavka.by/
curl -s -m 20 -A "$U" -o /dev/null -w "api %{http_code}\n" "https://api.edostavka.by/"
curl -s -m 20 "https://ipinfo.io/country"
