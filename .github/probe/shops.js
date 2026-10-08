const { chromium } = require('playwright');
const QS = ['Печенье Lotte Choco Pie с ароматом банана', 'lotte choco pie банан', 'чоко пай', 'кефир детский депи', 'сыр гауда'];
(async () => {
  // соседи — обычные запросы
  for (const q of QS) {
    const r = await fetch('https://dev.bazar-store.by/v2/products/search?query=' + encodeURIComponent(q)).then(r => r.json()).catch(e => ({ err: String(e) }));
    console.log('SOSEDI', q, '→', JSON.stringify((r.data || []).slice(0, 8).map(x => x.id + ' ' + x.name)));
  }
  const b = await chromium.launch();
  const p = await b.newPage({ locale: 'ru-RU' });
  const t0 = Date.now();
  await p.goto('https://edostavka.by/', { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(e => console.log('goto', e.message));
  await p.waitForFunction(() => !!window.__NEXT_DATA__, null, { timeout: 30000 }).catch(e => console.log('no next data', e.message));
  console.log('READY ms', Date.now() - t0, 'title', await p.title());
  const nd = await p.evaluate(() => JSON.stringify(window.__NEXT_DATA__ || {}));
  console.log('BUILD', await p.evaluate(() => window.__NEXT_DATA__ && window.__NEXT_DATA__.buildId), 'token in NEXT_DATA', nd.includes('iEfhbZ'));
  const html = await p.content();
  console.log('token in html', html.includes('iEfhbZ'));
  // тот же JS, что пойдёт в WebView
  const JS = async (q) => {
    const T = 'iEfhbZxLdbS4n8Umbg1l3cGERx2g7kwo';
    const r = await fetch('/api/v2?path=search/preview/?query=' + encodeURIComponent(q), { method: 'POST', credentials: 'include',
      headers: { 'accept': 'application/json', 'content-type': 'application/json', 'apitoken': T, 'web-user-agent': 'SiteEdostavka/1.0.0' }, body: '{}' });
    const txt = await r.text();
    let j; try { j = JSON.parse(txt); } catch (e) { return { status: r.status, err: 'not json', head: txt.slice(0, 200) }; }
    const items = (j.products || []).slice(0, 8).map(x => ({ id: x.productId, name: x.productName }));
    const pages = [];
    for (const it of items.slice(0, 2)) {
      const h = await fetch('/product/' + it.id, { credentials: 'include' }).then(r => r.text());
      const text = h.replace(/<script[\s\S]*?<\/script>/g, ' ').replace(/<style[\s\S]*?<\/style>/g, ' ').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');
      const i = text.indexOf('На 100 грамм');
      pages.push({ id: it.id, len: h.length, block: i >= 0 ? text.slice(i, i + 160) : 'NO BLOCK ' + text.slice(0, 120) });
    }
    let data = null;
    try {
      const bid = window.__NEXT_DATA__.buildId;
      const d = await fetch(`/_next/data/${bid}/product/${items[0].id}.json`, { credentials: 'include' }).then(r => r.text());
      const k = d.search(/protein|белк|calor|energ/i);
      data = { len: d.length, around: k >= 0 ? d.slice(Math.max(0, k - 300), k + 500) : d.slice(0, 300) };
    } catch (e) { data = String(e); }
    return { status: r.status, items, pages, data };
  };
  for (const q of QS) {
    const s = Date.now();
    const out = await p.evaluate(JS, q).catch(e => ({ err: String(e) }));
    console.log('EDO', q, (Date.now() - s) + 'ms', JSON.stringify(out));
  }
  await b.close();
})();
