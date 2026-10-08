({
  async data(path) {
    const get = (b) => fetch('/_next/data/' + b + '/' + path, { credentials: 'include', headers: { 'x-nextjs-data': '1', 'accept': 'application/json' } });
    let b = window.__grindBuild || (window.__NEXT_DATA__ && window.__NEXT_DATA__.buildId);
    let r = b ? await get(b) : null;
    if (!r || r.status === 404) {
      const h = await (await fetch('/', { credentials: 'include' })).text();
      const m = h.match(/"buildId":"([^"]+)"/);
      if (!m) throw new Error('no buildId');
      window.__grindBuild = b = m[1];
      r = await get(b);
    }
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return r.json();
  },
  async search(q) {
    const j = await this.data('search.json?query=' + encodeURIComponent(q));
    const l = (j.pageProps && j.pageProps.listing && j.pageProps.listing.products) || [];
    return { items: l.slice(0, 40).map(p => ({ id: String(p.productId), name: String(p.productName || '') })) };
  },
  async product(id) {
    const j = await this.data('product/' + encodeURIComponent(id) + '.json');
    const p = (j.pageProps && j.pageProps.productData && j.pageProps.productData.product) || {};
    const props = {};
    for (const g of (p.customPropertyGroup || [])) {
      const n = String(g.propertyName || '');
      if (!/белк|жир|углев|энерг|калор|клетч/i.test(n)) continue;
      props[n] = Array.isArray(g.propertyValue) ? g.propertyValue.join(' ') : String(g.propertyValue == null ? '' : g.propertyValue);
    }
    return { id: String(id), name: String(p.productName || ''), props };
  }
})
