const { chromium } = require('playwright');
const QS = ['Печенье Lotte Choco Pie с ароматом банана', 'choco pie', 'чоко пай', 'кефир детский депи', 'кефир депи'];
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ locale: 'ru-RU' });
  await p.goto('https://edostavka.by/', { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(e => console.log('goto', e.message));
  await p.waitForFunction(() => !!window.__NEXT_DATA__, null, { timeout: 30000 }).catch(e => console.log('no next data', e.message));
  const JS = async (q) => {
    const bid = window.__NEXT_DATA__.buildId;
    const r = await fetch(`/_next/data/${bid}/search.json?query=` + encodeURIComponent(q), { credentials: 'include', headers: { 'x-nextjs-data': '1' } });
    const txt = await r.text();
    let j; try { j = JSON.parse(txt); } catch (e) { return { status: r.status, err: 'not json', head: txt.slice(0, 200) }; }
    const found = [];
    const walk = (o, path) => {
      if (!o || typeof o !== 'object') return;
      if (o.productId && o.productName) { found.push({ path, id: o.productId, name: o.productName }); return; }
      for (const k of Object.keys(o)) walk(o[k], path + '.' + k);
    };
    walk(j, '');
    const keys = Object.keys((j.pageProps) || {});
    return { status: r.status, len: txt.length, pagePropsKeys: keys, n: found.length, paths: [...new Set(found.map(f => f.path.replace(/\.\d+$/, '')))].slice(0, 5), items: found.slice(0, 12).map(f => f.id + ' ' + f.name) };
  };
  for (const q of QS) {
    const out = await p.evaluate(JS, q).catch(e => ({ err: String(e) }));
    console.log('SEARCHJSON', q, JSON.stringify(out));
  }
  // структура данных товара
  const prod = await p.evaluate(async () => {
    const bid = window.__NEXT_DATA__.buildId;
    const d = await fetch(`/_next/data/${bid}/product/1922907.json`, { credentials: 'include', headers: { 'x-nextjs-data': '1' } }).then(r => r.json());
    const hits = [];
    const walk = (o, path) => {
      if (!o || typeof o !== 'object') return;
      for (const k of Object.keys(o)) {
        const v = o[k];
        if (/(prot|fat|carb|kcal|calor|energ|nutri|белк|жир|углев)/i.test(k) || (typeof v === 'string' && /Белки|ккал/.test(v) && v.length < 300)) hits.push(path + '.' + k + ' = ' + JSON.stringify(v).slice(0, 200));
        walk(v, path + '.' + k);
      }
    };
    walk(d.pageProps || d, '');
    return hits.slice(0, 40);
  }).catch(e => ['ERR ' + e]);
  console.log('PRODUCTJSON', JSON.stringify(prod, null, 1));
  await b.close();
})();
