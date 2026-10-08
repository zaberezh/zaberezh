const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ locale: 'ru-RU' });
  p.on('response', async r => {
    const u = r.url(); const t = r.request().resourceType();
    if (!['xhr', 'fetch'].includes(t) || /google|yandex|facebook|mindbox|analytics|sentry/.test(u)) return;
    let body = ''; try { body = (await r.text()).slice(0, 1500); } catch (e) {}
    console.log('=== ' + r.request().method() + ' ' + r.status() + ' ' + u.slice(0, 250) + '\n' + (r.request().postData() || '').slice(0, 300) + '\n--- ' + body.replace(/\s+/g, ' '));
  });
  await p.goto('https://edostavka.by/search?query=' + encodeURIComponent('кефир детский депи'), { waitUntil: 'networkidle', timeout: 60000 }).catch(e => console.log('goto', e.message));
  await p.waitForTimeout(4000);
  const links = await p.$$eval('a', els => [...new Set(els.map(e => e.href).filter(h => /\/product\//.test(h)))].slice(0, 10));
  console.log('LINKS', links);
  console.log('TEXT', (await p.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ').slice(0, 1500));
  if (links[0]) {
    await p.goto(links[0], { waitUntil: 'networkidle', timeout: 60000 }).catch(() => {});
    await p.waitForTimeout(3000);
    const t = (await p.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ');
    const i = t.search(/100 г|Белк/);
    console.log('PRODUCT', await p.title(), '|', t.slice(Math.max(0, i - 200), i + 500));
  }
  await b.close();
})();
