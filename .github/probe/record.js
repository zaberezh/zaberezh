const { chromium } = require('playwright');
const fs = require('fs');
const dir = process.argv[2];
const LIB = fs.readFileSync('forma/core/src/main/resources/shops/edostavka.js', 'utf8').trim();
(async () => {
  const qs = JSON.parse(fs.readFileSync(dir + '/variants.json', 'utf8'));
  const b = await chromium.launch();
  const p = await b.newPage({ locale: 'ru-RU' });
  await p.goto('https://edostavka.by/', { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(e => console.log('goto', e.message));
  await p.waitForFunction(() => !!window.__NEXT_DATA__, null, { timeout: 30000 }).catch(e => console.log('no next data', e.message));
  const out = {};
  for (const q of qs) {
    const r = await p.evaluate(`(async()=>{let r;try{r=await ((${LIB}).search(${JSON.stringify(q)}));}catch(e){r={error:String((e&&e.message)||e)}};return JSON.stringify(r)})()`).catch(e => JSON.stringify({ error: String(e) }));
    out[q] = r;
    const j = JSON.parse(r);
    console.log('REC', q, (j.items || []).length, j.error || '', JSON.stringify((j.items || []).slice(0, 2)));
  }
  fs.writeFileSync(dir + '/edo.json', JSON.stringify(out));
  await b.close();
})();
