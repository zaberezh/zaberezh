const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ locale: 'ru-RU' });
  const seen = [];
  p.on('response', async r => {
    const u = r.url(); const t = r.request().resourceType();
    if (!['xhr', 'fetch', 'document'].includes(t)) return;
    let body = ''; try { body = (await r.text()).slice(0, 1500); } catch (e) {}
    seen.push(u);
    console.log('=== ' + r.request().method() + ' ' + r.status() + ' ' + u + '\n' + JSON.stringify(r.request().headers()).slice(0, 600) + '\n' + (r.request().postData() || '').slice(0, 400) + '\n--- ' + body.replace(/\s+/g, ' '));
  });
  await p.goto('https://sosedi-dostavka.by/', { waitUntil: 'networkidle', timeout: 60000 }).catch(e => console.log('goto', e.message));
  await p.waitForTimeout(3000);
  const inputs = await p.$$eval('input', els => els.map(e => e.outerHTML.slice(0, 200)));
  console.log('INPUTS', inputs);
  const inp = await p.$('input[type=search], input[placeholder*="оиск"], input[placeholder*="айти"], input');
  if (inp) { await inp.click().catch(()=>{}); await inp.fill('кефир'); await p.keyboard.press('Enter'); }
  await p.waitForTimeout(8000);
  console.log('URL NOW', p.url());
  const links = await p.$$eval('a', els => els.map(e => e.href).filter(h => /product|tovar|item|catalog/i.test(h)).slice(0, 15));
  console.log('LINKS', links);
  if (links[0]) { await p.goto(links[0], { waitUntil: 'networkidle', timeout: 60000 }).catch(()=>{}); await p.waitForTimeout(4000);
    const txt = await p.evaluate(() => document.body.innerText); console.log('PRODUCT TEXT', txt.slice(0, 3000)); }
  await b.close();
})();
