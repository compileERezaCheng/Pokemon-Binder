/* TCGdex data: Copyright (c) 2021 TCGdex, MIT; see THIRD_PARTY_NOTICES.md. */
function parsePrices(card, selected, consulted = new Date().toISOString()) {
    if (!selected?.id || card?.id !== selected.id ||
        (selected.setId && card.set?.id !== selected.setId) ||
        (selected.number && String(card.localId) !== String(selected.number))) return [];
    const rows = [];
    const detailed = Array.isArray(card.variants_detailed) ? card.variants_detailed : [];
    const scopes = detailed.length ? detailed.filter(v => v && typeof v === 'object').map(v => ({ pricing: v.pricing,
        label: [v.type, v.subtype, v.foil, v.size, ...(Array.isArray(v.stamp) ? v.stamp : [])].filter(Boolean).join(' / '), id: v.variantId, type: v.type })) : [{ pricing: card.pricing }];
    for (const scope of scopes) {
        const add = (provider, source, field, type, variant) => {
            const amount = provider?.[field];
            if (typeof amount !== 'number' || !Number.isFinite(amount) || amount <= 0 || !/^[A-Z]{3}$/.test(provider.unit || '')) return;
            const date = provider.updated;
            const updated = typeof date === 'number' && Number.isFinite(date) ? new Date(date).toISOString() : typeof date === 'string' && date.trim() ? date : '';
            rows.push({ amount, currency: provider.unit, source, type,
                variant: [scope.label, variant].filter(Boolean).join(' · '), variantId: scope.id || '',
                updated: updated || consulted, dateLabel: updated ? 'Source updated' : 'Consulted' });
        };
        const cm = scope.pricing?.cardmarket;
        for (const [suffix, finish] of [['', 'Non-foil'], ['-holo', 'Foil (finish unspecified)']]) {
            if (scope.type === 'normal' && suffix) continue;
            if (['holo', 'reverse'].includes(scope.type) && !suffix) continue;
            for (const [field, type] of [['avg', 'Average'], ['trend', 'Trend'], ['low', 'Low']]) add(cm, 'Cardmarket via TCGdex', field + suffix, type, finish);
        }
        const tp = scope.pricing?.tcgplayer;
        for (const [finish, value] of Object.entries(tp || {})) {
            if (!value || typeof value !== 'object') continue;
            if (detailed.length && finish !== { normal: 'normal', reverse: 'reverse-holofoil', holo: 'holofoil' }[scope.type]) continue;
            for (const [field, type] of [['marketPrice', 'Market price'], ['midPrice', 'Median'], ['lowPrice', 'Low']]) {
                add({ ...value, unit: tp.unit, updated: tp.updated }, 'TCGplayer via TCGdex', field, type, finish);
            }
        }
    }
    if (detailed.length) {
        const missing = {};
        for (const [provider, source] of [['cardmarket', 'Cardmarket via TCGdex'], ['tcgplayer', 'TCGplayer via TCGdex']]) {
            if (!rows.some(row => row.source === source)) missing[provider] = card.pricing?.[provider];
        }
        // Keep edition-level quotes separate; they do not establish a detailed variant's price.
        rows.push(...parsePrices({ ...card, variants_detailed: [], pricing: missing }, selected, consulted).map(row => ({
            ...row, referenceOnly: true, variantId: '',
            variant: `Edition reference · ${row.variant === 'Non-foil' ? 'Standard price group' : row.variant} (finish unconfirmed)`
        })));
    }
    return rows.sort((a, b) => (a.currency === 'EUR' ? 0 : 1) - (b.currency === 'EUR' ? 0 : 1));
}

function groupMarketPrices(prices) {
    const groups = new Map();
    const order = ['Market price', 'Trend', 'Average', 'Median', 'Low'];
    for (const price of prices) {
        const key = JSON.stringify([price.variantId || '', price.variant, price.currency, price.source, !!price.referenceOnly]);
        if (!groups.has(key)) groups.set(key, { key, variant: price.variant, currency: price.currency, source: price.source, referenceOnly: !!price.referenceOnly, prices: [] });
        groups.get(key).prices.push(price);
    }
    return [...groups.values()].map(group => ({ ...group,
        primary: [...group.prices].sort((a, b) => order.indexOf(a.type) - order.indexOf(b.type))[0]
    })).sort((a, b) => (a.currency === 'EUR' ? 0 : 1) - (b.currency === 'EUR' ? 0 : 1));
}

class LatestRequest {
    revision = 0;
    next() { return ++this.revision; }
    isCurrent(revision) { return revision === this.revision; }
}

class MarketClient {
    constructor(fetcher = (url, options) => globalThis.fetch(url, options), clock = Date.now) {
        this.fetcher = fetcher; this.clock = clock;
        this.cache = new Map(); this.pending = new Map(); this.retryAt = 0;
    }
    // Cache the complete response, including every finish, currency and condition supplied.
    async get(path, refresh = false) {
        const old = this.cache.get(path);
        if (!refresh && old && this.clock() - old.time < 3600000) return { data: old.data, consulted: old.consulted, stale: false };
        if (this.pending.has(path)) return this.pending.get(path);
        const request = (async () => {
            const controller = new AbortController();
            const timeout = setTimeout(() => controller.abort(), 10000);
            try {
                if (this.clock() < this.retryAt) throw new Error('API limit reached. Retry later.');
                const response = await this.fetcher('https://api.tcgdex.net/v2/en/' + path, { signal: controller.signal });
                if (response.status === 429) {
                    const seconds = Number(response.headers?.get('Retry-After'));
                    this.retryAt = this.clock() + (seconds > 0 ? seconds * 1000 : 60000);
                    throw new Error('API limit reached. Retry later.');
                }
                if (!response.ok) throw new Error('TCGdex HTTP ' + response.status);
                const data = await response.json();
                const consulted = new Date(this.clock()).toISOString();
                if (this.cache.size >= 128) this.cache.delete(this.cache.keys().next().value);
                this.cache.set(path, { data, time: this.clock(), consulted });
                return { data, consulted, stale: false };
            } catch (error) {
                if (old) return { data: old.data, consulted: old.consulted, stale: true, error: error.message };
                throw error;
            } finally { clearTimeout(timeout); }
        })();
        this.pending.set(path, request);
        try { return await request; } finally { this.pending.delete(path); }
    }
}

if (typeof module !== 'undefined') module.exports = { parsePrices, groupMarketPrices, MarketClient, LatestRequest };
