const { chromium } = require('playwright');
const fs = require('fs');
const LIB = fs.readFileSync(__dirname + '/edostavka.js', 'utf8');
const QS = ['печенье lotte choco pie банана', 'lotte choco pie банана', 'lotte choco pie', 'choco pie', 'чокопай', 'кефир детский депи', 'кефир депи', 'депи', 'кефир детский', 'сыр гауда'];
(async () => {
  for (const q of ['кефир депи', 'депи кефир', 'lotte choco pie']) {
    const r = await fetch('https://dev.bazar-store.by/v2/products/search?query=' + encodeURIComponent(q)).then(r => r.json()).catch(e => ({ err: String(e) }));
    console.log('SOSEDI', q, '→', (r.data || []).length, JSON.stringify((r.data || []).slice(0, 6).map(x => x.name)));
  }
  const b = await chromium.launch();
  const p = await b.newPage({ locale: 'ru-RU' });
  const t0 = Date.now();
  await p.goto('https://edostavka.by/', { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(e => console.log('goto', e.message));
  await p.waitForFunction(() => !!window.__NEXT_DATA__, null, { timeout: 30000 }).catch(e => console.log('no next data', e.message));
  console.log('READY', Date.now() - t0, 'ms');
  for (const q of QS) {
    const s = Date.now();
    const out = await p.evaluate(`(${LIB}).search(${JSON.stringify(q)})`).catch(e => ({ err: String(e) }));
    console.log('EDO', q, (Date.now() - s) + 'ms', (out.items || []).length, JSON.stringify((out.items || []).slice(0, 6)), out.err || '');
  }
  for (const id of ['1922907', '2287615']) {
    const out = await p.evaluate(`(${LIB}).product(${JSON.stringify(id)})`).catch(e => ({ err: String(e) }));
    console.log('PRODUCT', JSON.stringify(out));
  }
  // старый buildId — скрипт должен сам взять новый
  const stale = await p.evaluate(`window.__grindBuild='old-build-x'; (${LIB}).search('сыр гауда')`).catch(e => ({ err: String(e) }));
  console.log('STALE BUILD', (stale.items || []).length, stale.err || '');
  // listing: какие поля есть у товара
  const keys = await p.evaluate(async () => {
    const b = window.__NEXT_DATA__.buildId;
    const j = await fetch('/_next/data/' + b + '/search.json?query=' + encodeURIComponent('сыр гауда'), { headers: { 'x-nextjs-data': '1' } }).then(r => r.json());
    const x = j.pageProps.listing.products[0];
    return { keys: Object.keys(x), listingKeys: Object.keys(j.pageProps.listing), sample: JSON.stringify(x).slice(0, 600) };
  }).catch(e => ({ err: String(e) }));
  console.log('LISTING', JSON.stringify(keys));
  await b.close();
})();
