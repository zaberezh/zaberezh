U='Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36'
Q=%D0%BA%D0%B5%D1%84%D0%B8%D1%80%20%D0%B4%D0%B5%D1%82%D1%81%D0%BA%D0%B8%D0%B9%20%D0%B4%D0%B5%D0%BF%D0%B8
curl -s -m 20 -A "$U" -H 'Accept-Language: ru-RU' "https://edostavka.by/search?query=$Q" -o s.html -w "PLAIN search %{http_code} %{size_download}\n"
grep -o '/product/[0-9]\{4,\}' s.html | sort -u | head; grep -o '__NEXT_DATA__' s.html | head -1
grep -o '"productName":"[^"]*"' s.html | head -5
