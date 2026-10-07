const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ locale: 'ru-RU' });
  const scripts = [];
  p.on('response', async r => {
    const u = r.url(); const t = r.request().resourceType();
    if (t === 'script' && u.includes('sosedi')) scripts.push(u);
    if (!['xhr', 'fetch'].includes(t) || !u.includes('bazar-store')) return;
    if (/paymentMethods|darkstores|delivery-cost|banners|v2\/main/.test(u)) return;
    let body = ''; try { body = (await r.text()).slice(0, 1800); } catch (e) {}
    console.log('=== ' + r.status() + ' ' + u.slice(0, 200) + '\n--- ' + body.replace(/\s+/g, ' '));
  });
  await p.goto('https://sosedi-dostavka.by/', { waitUntil: 'networkidle', timeout: 60000 }).catch(e => console.log('goto', e.message));
  // маршруты в бандле
  for (const s of scripts) {
    const js = await (await p.request.get(s, { timeout: 20000 })).text().catch(() => '');
    const hits = new Set((js.match(/["'`][^"'`\s]{0,40}(product|Product)[^"'`\s]{0,60}["'`]/g) || []));
    console.log('SCRIPT', s, [...hits].slice(0, 80).join(' | '));
  }
  for (const path of ['/product/kefir-15-savushkin', '/ru/product/kefir-15-savushkin', '/products/kefir-15-savushkin', '/catalog/product/kefir-15-savushkin']) {
    console.log('#### PAGE ' + path);
    await p.goto('https://sosedi-dostavka.by' + path, { waitUntil: 'networkidle', timeout: 60000 }).catch(e => console.log('goto', e.message));
    await p.waitForTimeout(3000);
    console.log('TEXT', (await p.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ').slice(0, 700));
  }
  await b.close();
})();
