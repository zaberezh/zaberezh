const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ locale: 'ru-RU' });
  p.on('response', async r => {
    const u = r.url(); const t = r.request().resourceType();
    if (!['xhr', 'fetch'].includes(t) || !u.includes('bazar-store')) return;
    let body = ''; try { body = (await r.text()).slice(0, 2500); } catch (e) {}
    console.log('=== ' + r.status() + ' ' + u.slice(0, 300) + '\n--- ' + body.replace(/\s+/g, ' '));
  });
  await p.goto('https://sosedi-dostavka.by/search?query=' + encodeURIComponent('кефир депи'), { waitUntil: 'networkidle', timeout: 60000 }).catch(e => console.log('goto', e.message));
  await p.waitForTimeout(5000);
  console.log('BODY', (await p.evaluate(() => document.body.innerText)).slice(0, 1500));
  console.log('#### CLICK');
  const el = await p.$('img[src*="images/"]');
  if (el) { await el.click().catch(e => console.log('click', e.message)); await p.waitForTimeout(6000); }
  console.log('URL NOW', p.url());
  console.log('PRODUCT', (await p.evaluate(() => document.body.innerText)).slice(0, 2500));
  await b.close();
})();
