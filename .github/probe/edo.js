const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ locale: 'ru-RU', viewport: { width: 1280, height: 900 } });
  p.on('response', async r => {
    const u = r.url(); const t = r.request().resourceType();
    if (!['xhr', 'fetch', 'document'].includes(t) || !/edostavka/.test(u)) return;
    let body = ''; try { body = (await r.text()).slice(0, 1200); } catch (e) {}
    console.log('=== ' + r.request().method() + ' ' + r.status() + ' ' + u.slice(0, 250) + '\n' + JSON.stringify(r.request().headers()).slice(0, 500) + '\n' + (r.request().postData() || '').slice(0, 300) + '\n--- ' + body.replace(/\s+/g, ' '));
  });
  await p.goto('https://edostavka.by/', { waitUntil: 'networkidle', timeout: 60000 }).catch(e => console.log('goto', e.message));
  await p.waitForTimeout(3000);
  const inputs = await p.$$eval('input', els => els.map(e => e.outerHTML.slice(0, 200)));
  console.log('INPUTS', inputs);
  const inp = await p.$('input[type=search], input[placeholder*="оиск"], input[name*="search"], input[name*="query"]');
  console.log('#### TYPE', !!inp);
  if (inp) { await inp.click().catch(()=>{}); await inp.type('кефир детский депи', { delay: 80 }); await p.waitForTimeout(4000); await p.keyboard.press('Enter'); await p.waitForTimeout(6000); }
  console.log('URL NOW', p.url());
  const links = await p.$$eval('a', els => [...new Set(els.map(e => e.href).filter(h => /\/product\//.test(h)))].slice(0, 10));
  console.log('LINKS', links);
  if (links[0]) {
    await p.goto(links[0], { waitUntil: 'networkidle', timeout: 60000 }).catch(() => {});
    await p.waitForTimeout(3000);
    const t = (await p.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ');
    const i = t.search(/100 г|Белк/);
    console.log('PRODUCT', await p.title(), '|', t.slice(Math.max(0, i - 200), i + 500));
  }
  await b.close();
})();
