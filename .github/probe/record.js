const { chromium } = require('playwright');
const fs = require('fs');
const dir = process.argv[2];
const LIB = fs.readFileSync('forma/core/src/main/resources/shops/edostavka.js', 'utf8').trim();
(async () => {
  // «Соседи»: карточки, где не нашлось КБЖУ
  for (const q of ['печенье лотте чокопай банан', 'квас лидский']) {
    const s = await fetch('https://dev.bazar-store.by/v2/products/search?query=' + encodeURIComponent(q)).then(r => r.json());
    for (const x of (s.data || []).slice(0, 2)) {
      const c = await fetch('https://dev.bazar-store.by/products/' + x.id + '/10').then(r => r.json()).catch(e => ({ err: String(e) }));
      console.log('SOSEDI CARD', x.id, x.name, '| cal', JSON.stringify(c.calorie), 'p', JSON.stringify(c.protein), 'f', JSON.stringify(c.fat), 'c', JSON.stringify(c.carbohydrate),
        '| desc', String(c.description || '').slice(0, 300), '| comp', String(c.composition || '').slice(-400));
    }
  }
  const qs = JSON.parse(fs.readFileSync(dir + '/variants.json', 'utf8'));
  const b = await chromium.launch();
  const p = await b.newPage({ locale: 'ru-RU' });
  await p.goto('https://edostavka.by/', { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(e => console.log('goto', e.message));
  await p.waitForFunction(() => !!window.__NEXT_DATA__, null, { timeout: 30000 }).catch(e => console.log('no next data', e.message));
  const run = (expr) => p.evaluate(`(async()=>{let r;try{r=await (${expr});}catch(e){r={error:String((e&&e.message)||e)}};return JSON.stringify(r)})()`).catch(e => JSON.stringify({ error: String(e) }));
  // как выглядит customPropertyGroup в выдаче поиска
  const sample = await p.evaluate(async () => {
    const b = window.__NEXT_DATA__.buildId;
    const j = await fetch('/_next/data/' + b + '/search.json?query=' + encodeURIComponent('квас лидский'), { headers: { 'x-nextjs-data': '1' } }).then(r => r.json());
    const x = j.pageProps.listing.products[0];
    return JSON.stringify({ cpg: x.customPropertyGroup, prev: x.previewProperties, add: x.additionalProperties }).slice(0, 1500);
  }).catch(e => String(e));
  console.log('LISTING SAMPLE', sample);
  const out = {};
  for (const q of qs) {
    const r = await run(`(${LIB}).search(${JSON.stringify(q)})`);
    out['search:' + q] = r;
    const j = JSON.parse(r);
    for (const it of (j.items || []).slice(0, 4)) {
      if (out['product:' + it.id]) continue;
      out['product:' + it.id] = await run(`(${LIB}).product(${JSON.stringify(it.id)})`);
    }
    console.log('REC', q, (j.items || []).length, j.error || '');
  }
  fs.writeFileSync(dir + '/edo.json', JSON.stringify(out));
  await b.close();
})();
