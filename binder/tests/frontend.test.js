const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../frontend/index.js'), 'utf8');
const html = fs.readFileSync(path.join(__dirname, '../frontend/index.html'), 'utf8');
const styles = fs.readFileSync(path.join(__dirname, '../frontend/index.css'), 'utf8');
const server = fs.readFileSync(path.join(__dirname, '../backend/pokemon_server.py'), 'utf8');
const binder = fs.readFileSync(path.join(__dirname, '../backend/pokemon_binder.py'), 'utf8');

function loadApp() {
    const context = {
        document: {
            addEventListener() {},
            getElementById() { return { addEventListener() {} }; }
        },
        window: {},
        setInterval() {}
    };
    vm.runInNewContext(`${source}\nglobalThis.api = { filterCollectionCards, changePage, jumpToPage, page: () => currentPage, pageNavigationState, getCardImageSource, calculateRank, chooseUniqueCatalogCard, stubRender: () => { renderBinderGrid = () => {}; } };`, context);
    return context.api;
}

function fakeElement(tagName = 'div') {
    const listeners = {};
    const attributes = {};
    const element = {
        tagName: tagName.toUpperCase(),
        children: [],
        dataset: {},
        style: {},
        disabled: false,
        src: '',
        value: '',
        _innerHTML: '',
        get innerHTML() { return this._innerHTML; },
        set innerHTML(value) { this._innerHTML = String(value || ''); if (!this._innerHTML) this.children = []; },
        textContent: '',
        _className: '',
        get className() { return this._className; },
        set className(value) { this._className = String(value || ''); },
        get options() { return this.children.filter(child => child.tagName === 'OPTION'); },
        getAttribute(name) { return attributes[name] ?? null; },
        setAttribute(name, value) { attributes[name] = String(value); },
        addEventListener(name, callback) { (listeners[name] ||= []).push(callback); },
        appendChild(child) { this.children.push(child); return child; },
        append(...children) { children.forEach(child => this.appendChild(child)); },
        replaceChildren(...children) { this.children = []; this.textContent = ''; children.forEach(child => this.appendChild(child)); },
        click() {
            let result;
            for (const callback of listeners.click || []) result = callback({ target: this, preventDefault() {} });
            return result;
        }
    };
    Object.defineProperty(element, 'value', {
        get() { return this._value || ''; },
        set(value) {
            const next = String(value ?? '');
            this._value = this.tagName === 'SELECT' && this.options.length && !this.options.some(option => option.value === next) ? '' : next;
        }
    });
    element.classList = {
        add(...names) { element.className = [...new Set([...element.className.split(/\s+/).filter(Boolean), ...names])].join(' '); },
        remove(...names) { element.className = element.className.split(/\s+/).filter(name => name && !names.includes(name)).join(' '); },
        contains(name) { return element.className.split(/\s+/).includes(name); }
    };
    return element;
}

function loadInteractiveApp(fetchOverride) {
    const elements = new Map();
    const requests = [];
    const previewContainer = fakeElement();
    const document = {
        addEventListener() {},
        getElementById(id) {
            if (!elements.has(id)) elements.set(id, fakeElement(id === 'add-type' ? 'select' : 'div'));
            return elements.get(id);
        },
        createElement: tagName => fakeElement(tagName),
        createTextNode: text => ({ textContent: String(text) }),
        querySelector(selector) { return selector === '.card-preview-area .pokemon-card-container' ? previewContainer : null; },
        querySelectorAll() { return []; }
    };
    const addType = document.getElementById('add-type');
    const addTypeHtml = html.match(/<select id="add-type"[^>]*>([\s\S]*?)<\/select>/)[1];
    for (const [, value, attrs, label] of addTypeHtml.matchAll(/<option value="([^"]+)"([^>]*)>([\s\S]*?)<\/option>/g)) {
        const option = fakeElement('option');
        option.value = value;
        option.textContent = label;
        addType.appendChild(option);
        if (/\bselected\b/.test(attrs)) addType.value = value;
    }
    document.getElementById('add-clear-set').disabled = true;
    document.getElementById('modal-save-set').disabled = true;

    const jsonResponse = data => ({ ok: true, json: async () => data });
    const cardDetails = {
        'base1-25': { id: 'base1-25', name: 'Pikachu', set: { id: 'base1', name: 'Base' }, localId: '25', rarity: 'Rare', image: 'https://assets.tcgdex.net/en/base/base1/25' },
        'neo3-12': { id: 'neo3-12', name: 'Pikachu', set: { id: 'neo3', name: 'Neo Revelation' }, localId: '12', rarity: 'Uncommon', image: 'https://assets.tcgdex.net/en/neo/neo3/12' },
        'common-card': { id: 'common-card', name: 'Pikachu', set: { id: 'promo', name: 'Promo' }, localId: '1', rarity: 'Common', image: '' }
    };
    const defaultFetch = async (url, options = {}) => {
        if (url === '/api/pokemon-db') return jsonResponse({ pecharunt: { id: 1025 }, '1025': { name: 'pecharunt' } });
        if (String(url).startsWith('/api/suggest-position')) return jsonResponse({ page: 2, slot: 3, position_type: 'Dex Order' });
        if (String(url).includes('/cards?name=')) {
            const name = decodeURIComponent(String(url).split('name=')[1]);
            return jsonResponse(name === 'pikachu' ? [
                { id: 'base1-25', name: 'Pikachu', localId: '25', image: 'https://assets.tcgdex.net/en/base/base1/25' },
                { id: 'neo3-12', name: 'Pikachu', localId: '12', image: 'https://assets.tcgdex.net/en/neo/neo3/12' }
            ] : []);
        }
        if (String(url).endsWith('/sets')) return jsonResponse([{ id: 'base1', name: 'Base' }, { id: 'neo3', name: 'Neo Revelation' }]);
        const cardId = String(url).split('/cards/')[1];
        if (cardDetails[cardId]) return jsonResponse(cardDetails[cardId]);
        if (url === '/api/add') return jsonResponse({ success: false, error: 'captured by test' });
        return jsonResponse({});
    };
    const context = {
        document,
        window: {},
        console,
        setInterval() {},
        fetch(url, options = {}) {
            requests.push({ url, options });
            return fetchOverride ? fetchOverride(url, options, defaultFetch) : defaultFetch(url, options);
        }
    };
    vm.runInNewContext(`${source}
showToast = () => {};
globalThis.api = {
    searchCatalogCards, chooseCatalogCard, clearAddCatalogCard, resetAddCardForm, submitAddCard,
    selectPokemonCustom, updatePreviewRarity, loadPokemonDb, handleAutocomplete,
    setPokemon: pokemon => { selectedPokemon = pokemon; },
    state: () => ({ selectedPokemon, selectedCatalogCard, modalSelectedCatalogCard }),
    pokemonLookup: () => pokemonLookupList,
    revisions: () => ({ add: catalogSearchRevision, modal: modalCatalogRevision })
};`, context);
    return { api: context.api, document, elements, requests, previewContainer, jsonResponse };
}

async function preparePikachu(app, listId = 'add-set-options') {
    app.api.setPokemon({ name: 'pikachu', id: 25 });
    const revision = listId === 'modal-set-options' ? app.api.revisions().modal : app.api.revisions().add;
    await app.api.searchCatalogCards('pikachu', listId, revision);
}

function deferred() {
    let resolve;
    const promise = new Promise(done => { resolve = done; });
    return { promise, resolve };
}

const cards = [
    { Name: 'Pikachu', 'Dex Number': 25, Page: 2, Slot: 1, Type: 'Normal', Condition: 'NM', Notes: '', 'Date Added': '2026-01-01' },
    { Name: 'Pikachu', 'Dex Number': 25, Page: 2, Slot: 1, Type: 'Holofoil Rare', Condition: 'LP', Notes: 'first edition', 'Date Added': '2026-01-02' },
    { Name: 'Bulbasaur', 'Dex Number': 1, Page: 1, Slot: 2, Type: 'Normal', Condition: 'NM', Notes: '', 'Date Added': '2026-01-03' },
    { Name: 'Custom', 'Dex Number': 0, Page: 3, Slot: 1, Type: 'Normal', Condition: 'MP', Notes: 'trainer card', 'Date Added': '2026-01-04' }
];

test('binder prefers catalog card images and keeps the species fallback', () => {
    const api = loadApp();
    assert.equal(api.getCardImageSource({ "Image URL": "https://assets.tcgdex.net/en/base/base1/4/high.png", "Dex Number": 6 }), "https://assets.tcgdex.net/en/base/base1/4/high.png");
    assert.equal(api.getCardImageSource({ "Image URL": "javascript:alert(1)", "Dex Number": 6 }), "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/6.png");
});

test('search, filters, sorting, and repeated-card toggle preserve binder list behavior', () => {
    const api = loadApp();
    const filtered = api.filterCollectionCards(cards, {
        searchVal: 'pikachu', condVal: '', typeFilterVal: '', sortBy: 'dex', showRepeated: false
    });
    assert.deepEqual(Array.from(filtered, card => card.Type), ['Holofoil Rare']);

    const repeated = api.filterCollectionCards(cards, {
        searchVal: 'pikachu', condVal: '', typeFilterVal: '', sortBy: 'dex', showRepeated: true
    });
    assert.equal(repeated.length, 2);

    const pageAndCondition = api.filterCollectionCards(cards, {
        searchVal: 'page 3', condVal: 'MP', typeFilterVal: 'Normal', sortBy: 'name', showRepeated: true
    });
    assert.deepEqual(Array.from(pageAndCondition, card => card.Name), ['Custom']);

    const byRarity = api.filterCollectionCards(cards, {
        searchVal: 'edition', condVal: 'LP', typeFilterVal: 'Holofoil Rare', sortBy: 'rarity', showRepeated: true
    });
    assert.deepEqual(Array.from(byRarity, card => card.Name), ['Pikachu']);
});

test('binder navigation clamps the first spread and maps entered pages to their spread', () => {
    const api = loadApp();
    api.stubRender();
    api.changePage(-1);
    assert.equal(api.page(), 0);
    api.jumpToPage('1');
    assert.equal(api.page(), 0);
    api.jumpToPage('3');
    assert.equal(api.page(), 2);
    api.changePage(1);
    assert.equal(api.page(), 3);
    api.jumpToPage('invalid');
    assert.equal(api.page(), 3);
});

test('binder arrows are hidden at the first and last spread', () => {
    const api = loadApp();
    assert.deepEqual({ ...api.pageNavigationState(0, 2) }, { previous: false, next: false });
    assert.deepEqual({ ...api.pageNavigationState(0, 4) }, { previous: false, next: true });
    assert.deepEqual({ ...api.pageNavigationState(2, 4) }, { previous: true, next: false });
    assert.deepEqual({ ...api.pageNavigationState(2, 6) }, { previous: true, next: true });
    assert.deepEqual({ ...api.pageNavigationState(4, 6) }, { previous: true, next: false });
});

test('spread arrows flank the binder and page numbers sit at the outer page corners', () => {
    const container = html.indexOf('<div class="binder-book-container">');
    const leftArrow = html.indexOf('class="page-turn-button page-turn-previous"', container);
    const book = html.indexOf('<div class="binder-book">', container);
    const rightArrow = html.indexOf('class="page-turn-button page-turn-next"', book);

    assert.ok(container >= 0 && leftArrow < book && book < rightArrow);
    assert.match(html, /page-turn-previous"[^>]*onclick="changePage\(-2\)"[^>]*aria-label="Previous binder spread"/);
    assert.match(html, /page-turn-next"[^>]*onclick="changePage\(2\)"[^>]*aria-label="Next binder spread"/);
    assert.match(html, /id="page-turn-previous"[^>]*hidden/);
    assert.match(html, /id="page-turn-next"[^>]*hidden/);
    assert.match(styles, /\.page-turn-button\[hidden\]\s*\{\s*display:\s*none;/);
    assert.match(styles, /\.left-page\s+\.page-number-banner\s*\{[^}]*align-self:\s*flex-start/s);
    assert.match(styles, /\.right-page\s+\.page-number-banner\s*\{[^}]*align-self:\s*flex-end/s);
});

test('trainer defaults are generic across the web app', () => {
    assert.doesNotMatch(source, /Dr4g0n/);
    assert.doesNotMatch(html, /Dr4g0n/);
    assert.doesNotMatch(server, /Dr4g0n/);
    assert.doesNotMatch(binder, /Dr4g0n/);
    assert.match(binder, /    "username": "Trainer",/);
});

test('binder spread changes apply a directional page-turn animation', () => {
    assert.match(source, /page-turn-\$\{direction\}/);
    assert.match(styles, /\.binder-book\.page-turn-forward\s*\{[^}]*animation:/s);
    assert.match(styles, /\.binder-book\.page-turn-backward\s*\{[^}]*animation:/s);
});

test('file pickers and selects use binder styling and the add-card Pokéball fills its frame', () => {
    assert.match(styles, /input\[type="file"\]\s*\{[^}]*background-color:\s*var\(--bg-input\)/s);
    assert.match(styles, /input\[type="file"\]::file-selector-button\s*\{/);
    assert.match(styles, /(^|\n)select\s*\{[^}]*appearance:\s*none/s);
    assert.match(styles, /select option\s*\{[^}]*background-color:/s);
    assert.match(styles, /\.card-placeholder-pokeball\s*\{[^}]*position:\s*absolute;[^}]*inset:\s*0;[^}]*display:\s*grid;[^}]*place-items:\s*center;/s);
    assert.match(styles, /\.catalog-options\s*\{[^}]*max-height:\s*360px/s);
    assert.match(styles, /\.catalog-option img\s*\{[^}]*width:\s*80px;[^}]*height:\s*112px;/s);
});

test('combined rank uses the same rarity scale and threshold boundaries', () => {
    const api = loadApp();
    assert.equal(typeof api.calculateRank, 'function');
    assert.equal(api.calculateRank(4.375, 'Ultra Rare'), 'Silver');
    assert.equal(api.calculateRank(4.37, 'Ultra Rare'), 'Bronze');
    assert.equal(api.calculateRank(6.25, 'Ultra Rare'), 'Gold');
    assert.equal(api.calculateRank(6.24, 'Ultra Rare'), 'Silver');
    assert.equal(api.calculateRank(8.125, 'Ultra Rare'), 'Platinum');
    assert.equal(api.calculateRank(8.12, 'Ultra Rare'), 'Gold');
    assert.equal(api.calculateRank(9.375, 'Ultra Rare'), 'Diamond');
    assert.equal(api.calculateRank(9.37, 'Ultra Rare'), 'Platinum');
    assert.equal(api.calculateRank(null, 'Ultra Rare'), 'No Rank');
    assert.equal(api.calculateRank(8.5, 'Normal'), 'No Rank');
    assert.equal(api.calculateRank(11, 'Ultra Rare'), 'No Rank');
});

test('catalog matching refuses ambiguous editions and distinguishes set numbers', () => {
    const api = loadApp();
    assert.equal(typeof api.chooseUniqueCatalogCard, 'function');
    const candidates = [
        { id: 'base1-25', name: 'Pikachu', setName: 'Base', number: '25' },
        { id: 'base1-58', name: 'Pikachu', setName: 'Base', number: '58' },
        { id: 'neo3-12', name: 'Pikachu', setName: 'Neo Revelation', number: '12' }
    ];
    assert.equal(api.chooseUniqueCatalogCard(candidates, { name: 'Pikachu' }), null);
    assert.equal(api.chooseUniqueCatalogCard(candidates, {
        name: 'Pikachu', setName: 'Base', cardNumber: '58/102'
    }).id, 'base1-58');
});

test('desktop set picker and grading details have accessible closed defaults', () => {
    assert.match(html, /id="add-set-options"[^>]*role="listbox"/);
    assert.match(html, /id="modal-set-options"[^>]*role="listbox"/);
    assert.match(html, /<details[^>]*id="modal-grade-details"/);
    assert.doesNotMatch(html.match(/<details[^>]*id="modal-grade-details"[^>]*>/)?.[0] || '', /\bopen\b/);
});

test('printing candidates stay selectable, and clearing a print preserves the Pokémon form', async () => {
    const app = loadInteractiveApp();
    await preparePikachu(app);
    const list = app.elements.get('add-set-options');
    const page = app.document.getElementById('add-page');
    const slot = app.document.getElementById('add-slot');
    const condition = app.document.getElementById('add-condition');
    const notes = app.document.getElementById('add-notes');
    page.value = '4';
    slot.value = '7';
    condition.value = 'LP';
    notes.value = 'keep these notes';

    await list.children[0].click();
    assert.equal(list.children.length, 2);
    assert.equal(list.children[0].getAttribute('aria-selected'), 'true');
    assert.equal(list.children[1].getAttribute('aria-selected'), 'false');
    await list.children[1].click();
    assert.equal(app.api.state().selectedCatalogCard.id, 'neo3-12');

    app.api.clearAddCatalogCard();
    assert.equal(app.api.state().selectedPokemon.name, 'pikachu');
    assert.equal(app.api.state().selectedCatalogCard, null);
    assert.equal(app.elements.get('add-clear-set').disabled, true);
    assert.deepEqual([page.value, slot.value, condition.value, notes.value], ['4', '7', 'LP', 'keep these notes']);
    assert.equal(list.children.length, 2);
    assert.equal(list.children.every(option => option.getAttribute('aria-selected') === 'false'), true);
    assert.match(app.elements.get('preview-image').src, /official-artwork\/25\.png$/);

    await list.children[1].click();
    assert.equal(app.api.state().selectedCatalogCard.id, 'neo3-12');
    await app.api.submitAddCard();
    const saved = JSON.parse(app.requests.findLast(request => request.url === '/api/add').options.body);
    assert.deepEqual([saved.name, saved.page, saved.slot, saved.condition, saved.notes, saved.set_id], ["pikachu", 4, 7, "LP", "keep these notes", "neo3"]);
});

test('clear printing selection invalidates an in-flight card detail request', async () => {
    let releaseDetails;
    const waitingForDetails = new Promise(resolve => { releaseDetails = resolve; });
    const app = loadInteractiveApp((url, options, defaultFetch) =>
        String(url).endsWith('/cards/base1-25') ? waitingForDetails : defaultFetch(url, options));
    await preparePikachu(app);
    const list = app.elements.get('add-set-options');
    const pending = list.children[0].click();
    app.api.clearAddCatalogCard();
    releaseDetails(app.jsonResponse({
        id: 'base1-25', name: 'Pikachu', set: { id: 'base1', name: 'Base' }, localId: '25', rarity: 'Rare', image: ''
    }));
    await pending;

    assert.equal(app.api.state().selectedCatalogCard, null);
    assert.equal(list.children.length, 2);
    assert.equal(app.elements.get('add-clear-set').disabled, true);
});

test('modal printing choices also remain available after selecting one', async () => {
    const app = loadInteractiveApp();
    await preparePikachu(app, 'modal-set-options');
    const list = app.elements.get('modal-set-options');
    await list.children[0].click();
    assert.equal(list.children.length, 2);
    assert.equal(list.children[0].getAttribute('aria-selected'), 'true');
    await list.children[1].click();
    assert.equal(app.api.state().modalSelectedCatalogCard.id, 'neo3-12');
});

test('catalog rarity, editable form rarity, preview, and save payload stay in sync', async () => {
    const app = loadInteractiveApp();
    await preparePikachu(app);
    const raritySelect = app.elements.get('add-type');
    const list = app.elements.get('add-set-options');
    app.document.getElementById('add-page').value = '1';
    app.document.getElementById('add-slot').value = '1';
    app.document.getElementById('add-condition').value = 'NM';
    await list.children[0].click();

    assert.equal(raritySelect.value, 'Rare');
    assert.equal(app.elements.get('preview-badge-type').textContent, 'Rare');
    await app.api.submitAddCard();
    let saved = JSON.parse(app.requests.findLast(request => request.url === '/api/add').options.body);
    assert.equal(saved.type, 'Rare');
    assert.equal(saved.catalog_rarity, 'Rare');

    raritySelect.value = 'Ultra Rare';
    app.api.updatePreviewRarity(raritySelect.value);
    assert.equal(app.elements.get('preview-badge-type').textContent, 'Ultra Rare');
    await app.api.submitAddCard();
    saved = JSON.parse(app.requests.findLast(request => request.url === '/api/add').options.body);
    assert.equal(saved.type, 'Ultra Rare');
    assert.equal(saved.catalog_rarity, 'Rare');

    app.api.clearAddCatalogCard();
    assert.equal(raritySelect.value, 'Ultra Rare');
    assert.equal(app.elements.get('preview-badge-type').textContent, 'Ultra Rare');
    await app.api.chooseCatalogCard({ id: 'common-card', name: 'Pikachu', setId: 'promo', setName: 'Promo', number: '1' }, 'add-set-options', app.api.revisions().add);
    assert.equal(raritySelect.value, 'Common');
    assert.ok(raritySelect.options.some(option => option.value === 'Common'));
    assert.equal(app.elements.get('preview-badge-type').textContent, 'Common');
    await app.api.chooseCatalogCard({ id: 'neo3-12', name: 'Pikachu', setId: 'neo3', setName: 'Neo Revelation', number: '12' }, 'add-set-options', app.api.revisions().add);
    assert.equal(raritySelect.value, 'Uncommon');
    assert.ok(raritySelect.options.some(option => option.value === 'Uncommon'));
    assert.equal(app.previewContainer.classList.contains('Common'), false);
    assert.equal(app.previewContainer.classList.contains('Uncommon'), true);

    app.api.resetAddCardForm();
    assert.equal(raritySelect.value, 'Normal');
    assert.equal(app.elements.get('preview-badge-type').textContent, 'Normal');
});

test('a prior Pokémon response cannot overwrite a newly selected or cleared form', async () => {
    let waitingForDetails = null;
    const app = loadInteractiveApp((url, options, defaultFetch) =>
        waitingForDetails && String(url).endsWith('/cards/base1-25') ? waitingForDetails.promise : defaultFetch(url, options));
    await preparePikachu(app);
    waitingForDetails = deferred();
    const pending = app.elements.get('add-set-options').children[0].click();
    app.api.selectPokemonCustom('Charmander');
    assert.equal(app.api.state().selectedPokemon.name, 'charmander');
    waitingForDetails.resolve(app.jsonResponse({
        id: 'base1-25', name: 'Pikachu', set: { id: 'base1', name: 'Base' }, localId: '25', rarity: 'Rare', image: ''
    }));
    await pending;
    assert.equal(app.api.state().selectedPokemon.name, 'charmander');
    assert.equal(app.api.state().selectedCatalogCard, null);

    app.api.setPokemon({ name: 'pikachu', id: 25 });
    await app.api.searchCatalogCards('pikachu', 'add-set-options', app.api.revisions().add);
    waitingForDetails = deferred();
    const pendingBeforeReset = app.elements.get('add-set-options').children[0].click();
    app.api.resetAddCardForm();
    waitingForDetails.resolve(app.jsonResponse({
        id: 'base1-25', name: 'Pikachu', set: { id: 'base1', name: 'Base' }, localId: '25', rarity: 'Rare', image: ''
    }));
    await pendingBeforeReset;
    assert.equal(app.api.state().selectedPokemon, null);
    assert.equal(app.api.state().selectedCatalogCard, null);
});

test('autocomplete can find a National Dex species above 151 by name and number', async () => {
    const app = loadInteractiveApp();
    await app.api.loadPokemonDb();
    assert.deepEqual(Array.from(app.api.pokemonLookup(), item => ({ name: item.name, id: item.id })), [{ name: 'pecharunt', id: 1025 }]);

    const suggestions = app.document.getElementById('autocomplete-suggestions');
    app.api.handleAutocomplete('pecharunt');
    assert.match(suggestions.children[0].innerHTML, /pecharunt/);
    assert.match(suggestions.children[0].innerHTML, /#1025/);
    app.api.handleAutocomplete('1025');
    assert.match(suggestions.children[0].innerHTML, /pecharunt/);
    assert.match(suggestions.children[0].innerHTML, /#1025/);
});

test('PokéBinder branding uses Pikachu artwork in the page header and login view', () => {
    assert.equal((html.match(/class="pokemon-brand-icon"/g) || []).length, 2);
    assert.equal((html.match(/src="\/pokemon\.png\?v=1"/g) || []).length, 2);
    assert.doesNotMatch(html, /class="pokeball-svg"/);
    assert.match(server, /elif path == '\/pokemon\.png':/);
});
