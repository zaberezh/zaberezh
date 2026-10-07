B=https://dev.bazar-store.by
curl -s -m 20 "$B/products/1318/10" | python3 -c "import json,sys; d=json.load(sys.stdin); print('BYID', d.get('name'), d.get('calorie'), d.get('protein'), d.get('fat'), d.get('carbohydrate'))"
for q in "%D1%81%D1%8B%D1%80%20%D0%BC%D0%BE%D1%86%D0%B0%D1%80%D0%B5%D0%BB%D0%BB%D0%B0" "%D1%82%D0%B2%D0%BE%D1%80%D0%BE%D0%B3" "%D0%BC%D0%B0%D0%BA%D0%B0%D1%80%D0%BE%D0%BD%D1%8B"; do
  curl -s -m 20 "$B/v2/products/search?query=$q" | python3 -c "
import json,sys,subprocess
d=json.load(sys.stdin)['data'][:3]
for x in d:
  c=json.loads(subprocess.run(['curl','-s','-m','20','$B/products/%s/10'%x['id']],capture_output=True,text=True).stdout or '{}')
  print(x['name'][:60],'|',c.get('calorie'),c.get('protein'),c.get('fat'),c.get('carbohydrate'))"
done
