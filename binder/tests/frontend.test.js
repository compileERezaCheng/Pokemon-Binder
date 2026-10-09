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
    vm.runInNewContext(`${source}\nglobalThis.api = { filterCollectionCards, changePage, jumpToPage, page: () => currentPage, pageNavigationState, stubRender: () => { renderBinderGrid = () => {}; } };`, context);
    return context.api;
}

const cards = [
    { Name: 'Pikachu', 'Dex Number': 25, Page: 2, Slot: 1, Type: 'Normal', Condition: 'NM', Notes: '', 'Date Added': '2026-01-01' },
    { Name: 'Pikachu', 'Dex Number': 25, Page: 2, Slot: 1, Type: 'Holofoil Rare', Condition: 'LP', Notes: 'first edition', 'Date Added': '2026-01-02' },
    { Name: 'Bulbasaur', 'Dex Number': 1, Page: 1, Slot: 2, Type: 'Normal', Condition: 'NM', Notes: '', 'Date Added': '2026-01-03' },
    { Name: 'Custom', 'Dex Number': 0, Page: 3, Slot: 1, Type: 'Normal', Condition: 'MP', Notes: 'trainer card', 'Date Added': '2026-01-04' }
];

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
});
