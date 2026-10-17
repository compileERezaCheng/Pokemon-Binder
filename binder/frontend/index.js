// ==================== STATE MANAGEMENT ====================
let appConfig = {
    rows: 3,
    cols: 3,
    mode: "dex",
    gsheet_enabled: false,
    gsheet_name: "",
    firebase_enabled: false,
    firebase_db_url: "",
    firebase_auth_method: "secret",
    firebase_secret: "",
    firebase_user_id: "",
    firebase_api_key: "",
    firebase_email: "",
    firebase_password: "",
    firebase_remember_password: true,
    cover_title: "Pokémon Card Binder",
    cover_subtitle: "My Personal Collection Album",
    cover_owner: "Trainer",
    cover_color: "#ef4444",
    cover_featured_dex: 25,
    cover_source: "pokemon",
    cover_image_url: "",
    username: "Trainer",
    profile_picture_source: "pokemon",
    profile_featured_dex: 25,
    profile_image_url: ""
};

let collection = [];
let pokemonLookupList = []; // Array of {id, name}
let activeTab = "binder";
let currentPage = 0;
let lastRenderedPage = null;
let pageTurnTimeout = null;
let selectedPokemon = null; // Currently selected in form
let selectedCatalogCard = null;
let previewRarityClass = "Normal";
let catalogSearchRevision = 0;
let catalogSelectionRevision = 0;
let catalogSetNames = null;
let catalogSetDetails = null;
const catalogImageFallbackCache = new Map();
let modalSelectedCatalogCard = null;
let modalSavedCatalogCard = null;
let modalCatalogRevision = 0;
let modalSelectionRevision = 0;
let modalArtworkRevision = 0;
let modalArtworkUrl = "";
let modalArtworkIsCard = false;
let modalNotesRevision = 0;
const TCGDEX_API = "https://api.tcgdex.net/v2/en";

// Autocomplete navigation state
let autocompleteSelectedIndex = -1;

// ==================== INITIALIZATION ====================
document.addEventListener("DOMContentLoaded", async () => {
    showToast("Loading app database...", "info");
    
    // Load config first
    await loadSettings();
    
    // Load collection
    await loadCollection();
    
    // Load Pokemon species DB for autocomplete
    await loadPokemonDb();
    
    // Set up default view
    switchTab("binder");
    
    // Update setup toggles visually
    toggleGsheetNameInput();
    
    // Close modal on Escape key press
    document.addEventListener("keydown", (e) => {
        if (e.key === "Escape") {
            closeCardModal();
        }
    });

    // Auto-sync on refresh (with 5-minute cooldown)
    const lastSync = localStorage.getItem("last_auto_sync");
    const now = new Date().getTime();
    const cooldown = 5 * 60 * 1000; // 5 minutes

    if (!lastSync || (now - parseInt(lastSync) > cooldown)) {
        if (appConfig.firebase_enabled || appConfig.gsheet_enabled) {
            console.log("Auto-syncing collection...");
            triggerManualSync().then(() => {
                localStorage.setItem("last_auto_sync", new Date().getTime().toString());
            });
        }
    }
});

// ==================== API FETCH CALLS ====================
async function loadSettings() {
    try {
        const res = await fetch("/api/settings");
        if (res.ok) {
            appConfig = await res.json();
            
            // Set CSS custom grid parameters
            const root = document.documentElement;
            root.style.setProperty("--rows", appConfig.rows);
            root.style.setProperty("--cols", appConfig.cols);
            
            // Populate form values
            document.getElementById("settings-rows").value = appConfig.rows;
            document.getElementById("settings-cols").value = appConfig.cols;
            document.getElementById("settings-mode").value = appConfig.mode;
            document.getElementById("settings-gsheet-toggle").checked = appConfig.gsheet_enabled;
            document.getElementById("settings-gsheet-name").value = appConfig.gsheet_name || "";
            
            // Populate trainer profile settings
            document.getElementById("settings-username").value = appConfig.username || "Trainer";
            document.getElementById("settings-profile-source").value = appConfig.profile_picture_source || "pokemon";
            document.getElementById("settings-profile-dex").value = appConfig.profile_featured_dex || 25;
            document.getElementById("settings-profile-url").value = appConfig.profile_image_url || "";
            
            // Render profile widget in sidebar
            renderTrainerProfile();
            
            // Update profile settings fields visibility
            toggleProfileSourceInputs();

            // Update Firebase Sync UI
            updateFirebaseUI(appConfig);
            
            // Update labels
            document.getElementById("grid-layout-label").textContent = `Grid Layout: ${appConfig.rows}x${appConfig.cols}`;
        }
    } catch (e) {
        showToast("Failed to load configuration", "error");
    }
}

async function loadCollection() {
    try {
        const res = await fetch("/api/collection");
        if (res.ok) {
            collection = await res.json();
            updateDashboardStats();
            renderBinderGrid();
            renderCollectionTable();
        }
    } catch (e) {
        showToast("Error loading collection cards", "error");
    }
}

async function loadPokemonDb() {
    try {
        const res = await fetch("/api/pokemon-db");
        if (res.ok) {
            const cacheData = await res.json();
            
            // Handle both legacy numeric IDs and entries shaped like { id, type }.
            const temp = [];
            for (const key in cacheData) {
                if (isNaN(key)) {
                    const value = cacheData[key];
                    const id = Number(typeof value === "object" && value !== null ? value.id : value);
                    if (!Number.isInteger(id) || id < 1) continue;
                    temp.push({
                        name: key,
                        id
                    });
                }
            }
            // Sort alphabetically for clean UI listing
            pokemonLookupList = temp.sort((a, b) => a.name.localeCompare(b.name));
        }
    } catch (e) {
        showToast("Offline mode: limited autocomplete matching", "info");
    }
}

async function checkSlotOccupation() {
    const pageVal = parseInt(document.getElementById("add-page").value);
    const slotVal = parseInt(document.getElementById("add-slot").value);
    
    const warningBanner = document.getElementById("slot-warning-banner");
    const warningText = document.getElementById("slot-occupied-info");
    
    // Update card preview details
    document.getElementById("preview-location-text").textContent = `Page ${pageVal || 1}, Slot ${slotVal || 1}`;
    
    if (isNaN(pageVal) || isNaN(slotVal) || pageVal < 1 || slotVal < 1) {
        warningBanner.classList.add("hidden");
        return;
    }
    
    // Check if slot exceeds configuration limits
    const maxSlots = appConfig.rows * appConfig.cols;
    if (slotVal > maxSlots) {
        warningText.innerHTML = `Slot exceeds limit! Page ${pageVal} has only <strong>${maxSlots} pockets</strong>.`;
        warningBanner.classList.remove("hidden");
        return;
    }
    
    // Scan collection
    const occupied = collection.filter(c => c.Page === pageVal && c.Slot === slotVal);
    if (occupied.length > 0) {
        const cardNames = occupied.map(c => `${capitalize(c.Name)} (#${c["Dex Number"] || "Custom"})`).join(", ");
        warningText.innerHTML = `Occupied by <strong>${cardNames}</strong>`;
        warningBanner.classList.remove("hidden");
    } else {
        warningBanner.classList.add("hidden");
    }
}

// ==================== INTERACTIVE UI FLOWS ====================
function switchTab(tabId) {
    activeTab = tabId;
    
    // Update button states
    document.querySelectorAll(".nav-item").forEach(btn => btn.classList.remove("active"));
    const navBtn = document.getElementById(`nav-btn-${tabId}`);
    if (navBtn) navBtn.classList.add("active");
    
    // Update trainer profile card active state
    const profileCard = document.querySelector(".trainer-profile-card");
    if (profileCard) {
        if (tabId === "profile") {
            profileCard.classList.add("active");
        } else {
            profileCard.classList.remove("active");
        }
    }
    
    // Update view sections visibility
    document.querySelectorAll(".view-section").forEach(sec => sec.classList.remove("active"));
    document.getElementById(`view-${tabId}`).classList.add("active");
    
    // Update header details dynamically
    const headerTitle = document.getElementById("page-title");
    const headerSubtitle = document.getElementById("page-subtitle");
    
    if (tabId === "binder") {
        headerTitle.textContent = "Binder Grid View";
        headerSubtitle.textContent = "Visual pocket view of your card collection";
        renderBinderGrid();
    } else if (tabId === "list") {
        headerTitle.textContent = "Collection Spreadsheet";
        headerSubtitle.textContent = "Search, filter, and sort your saved cards";
        renderCollectionTable();
    } else if (tabId === "add") {
        headerTitle.textContent = "Add Pokémon Card";
        headerSubtitle.textContent = "Search Pokédex species and catalog binder placement";
        resetAddCardForm();
    } else if (tabId === "settings") {
        headerTitle.textContent = "System Configuration";
        headerSubtitle.textContent = "Grid layout sheets, sorting order, and sync setups";
    } else if (tabId === "profile") {
        headerTitle.textContent = "Trainer Profile Settings";
        headerSubtitle.textContent = "Customize your trainer username and avatar image";
    }
}

function updateDashboardStats() {
    // Total cards
    document.getElementById("stat-total-cards").textContent = collection.length;
    
    // Completion Percentage (relative to National Pokédex Gen 1 - Gen 9 = 1025 species)
    const uniqueSpecies = new Set(collection.filter(c => c["Dex Number"] > 0).map(c => c["Dex Number"]));
    const completionPct = Math.min(100, Math.round((uniqueSpecies.size / 1025) * 100));
    document.getElementById("stat-completion").textContent = `${completionPct}%`;
    document.getElementById("stat-progress-fill").style.width = `${completionPct}%`;
    
    // Google Sheets & Firebase integration status badge
    const syncBadge = document.getElementById("stat-sync-mode");
    const gsheetActive = appConfig.gsheet_enabled;
    const firebaseActive = appConfig.firebase_enabled;
    
    if (gsheetActive && firebaseActive) {
        syncBadge.textContent = "Sheets & Firebase Active";
        syncBadge.className = "badge synced";
    } else if (gsheetActive) {
        syncBadge.textContent = `Sheets Active (${appConfig.gsheet_name || "unset"})`;
        syncBadge.className = "badge synced";
    } else if (firebaseActive) {
        syncBadge.textContent = "Firebase Active";
        syncBadge.className = "badge synced";
    } else {
        syncBadge.textContent = "Local Only";
        syncBadge.className = "badge disabled";
    }
}

// ==================== BINDER GRID RENDERING ====================
function pageNavigationState(page, pageCount) {
    return { previous: page > 0, next: page + 2 < pageCount };
}

function renderBinderGrid() {
    const leftGrid = document.getElementById("left-binder-grid");
    const rightGrid = document.getElementById("right-binder-grid");
    
    leftGrid.innerHTML = "";
    rightGrid.innerHTML = "";
    
    // Ensure currentPage is always even (left page is N, right page is N+1)
    if (currentPage % 2 !== 0) {
        currentPage = currentPage - 1;
    }
    if (currentPage < 0) currentPage = 0;

    const binderBook = document.querySelector(".binder-book");
    if (binderBook && lastRenderedPage !== null && currentPage !== lastRenderedPage) {
        const direction = currentPage > lastRenderedPage ? "forward" : "backward";
        const animationClass = `page-turn-${direction}`;
        binderBook.classList.remove("page-turn-forward", "page-turn-backward");
        void binderBook.offsetWidth;
        binderBook.classList.add(animationClass);
        window.clearTimeout(pageTurnTimeout);
        pageTurnTimeout = window.setTimeout(() => binderBook.classList.remove(animationClass), 500);
    }
    lastRenderedPage = currentPage;
    
    const leftPageNum = currentPage;
    const rightPageNum = currentPage + 1;
    
    // Display Page 0 as "Cover" in the title headers
    document.getElementById("left-page-num").textContent = leftPageNum === 0 ? "Cover" : leftPageNum;
    document.getElementById("right-page-num").textContent = rightPageNum;
    
    // Page jump input shows 1 for Cover Spread, or the page number itself
    document.getElementById("binder-page-input").value = leftPageNum === 0 ? 1 : leftPageNum;
    
    // Range display text
    const rangeText = leftPageNum === 0 ? "Cover & Page 1" : `${leftPageNum} - ${rightPageNum}`;
    document.getElementById("binder-page-range").textContent = rangeText;
    
    // Find highest page in collection to calibrate max navigation boundary
    let highestPage = 1;
    collection.forEach(card => {
        if (card.Page > highestPage) highestPage = card.Page;
    });
    let pageCount = highestPage + 1; // Include the cover and complete the final spread.
    if (pageCount % 2 !== 0) pageCount += 1;
    document.getElementById("binder-max-page").textContent = pageCount;
    const navigation = pageNavigationState(leftPageNum, pageCount);
    document.getElementById("page-turn-previous").hidden = !navigation.previous;
    document.getElementById("page-turn-next").hidden = !navigation.next;
    
    // Filter cards for left and right pages
    const leftCards = collection.filter(c => c.Page === leftPageNum);
    const rightCards = collection.filter(c => c.Page === rightPageNum);
    
    // Helper to render a page grid
    function renderPageGrid(gridEl, pageNum, cardsList) {
        const slotsPerPage = appConfig.rows * appConfig.cols;
        
        // Group cards by slot
        const slotMap = {};
        cardsList.forEach(card => {
            if (!slotMap[card.Slot]) slotMap[card.Slot] = [];
            slotMap[card.Slot].push(card);
        });
        
        for (let slot = 1; slot <= slotsPerPage; slot++) {
            const pocket = document.createElement("div");
            pocket.className = "binder-pocket";
            
            const label = document.createElement("span");
            label.className = "pocket-label";
            label.textContent = `Slot ${slot}`;
            pocket.appendChild(label);
            
            const stackedCards = (slotMap[slot] || []).sort((a, b) => {
                const RARITY_ORDER = [
                    "Mega Hyper Rare", "Hyper Rare", "Mega Attack Rare",
                    "Special Illustration Rare", "Illustration Rare", "Ace Spec Rare",
                    "Secret Rare", "Ultra Rare", "Double Rare", "Shiny Rare",
                    "Reverse Holo", "Holofoil Rare", "Rare", "Normal"
                ];
                const ra = RARITY_ORDER.indexOf(a.Type || "Normal");
                const rb = RARITY_ORDER.indexOf(b.Type || "Normal");
                return (ra === -1 ? RARITY_ORDER.length : ra) - (rb === -1 ? RARITY_ORDER.length : rb);
            });
            
            if (stackedCards.length > 0) {
                // Occupied slot: render card top (most rare card first)
                const topCard = stackedCards[0];
                
                const cardContainer = document.createElement("div");
                cardContainer.className = "pokemon-card-container";
                const containerRarityClass = getRarityClass(getCardRarity(topCard));
                if (containerRarityClass !== "Normal") {
                    cardContainer.classList.add(containerRarityClass);
                }
                cardContainer.onclick = () => openCardModal(pageNum, slot);
                
                const cardPreview = document.createElement("div");
                cardPreview.className = "pokemon-card-preview";
                
                const cardInner = document.createElement("div");
                cardInner.className = "card-inner";
                
                const cardFront = document.createElement("div");
                cardFront.className = "card-front";
                
                // Holographic frame & Artwork image
                const frame = document.createElement("div");
                frame.className = "card-image-frame";
                
                const imageUrl = getCardImageSource(topCard);
                if (imageUrl) {
                    const img = document.createElement("img");
                    img.src = imageUrl;
                    img.alt = topCard.Name;
                    img.loading = "lazy";
                    frame.appendChild(img);
                    setCardImageFallback(img, topCard);
                } else if (topCard["Catalog Card ID"]) {
                    const placeholder = document.createElement("span");
                    placeholder.className = "image-unavailable";
                    placeholder.textContent = topCard["Image Source"] === "Scrydex" ? "Imagem indisponível" : "A carregar imagem…";
                    frame.appendChild(placeholder);
                    resolveCardArtwork(frame, topCard);
                } else {
                    // Custom card placeholder
                    const placeholder = document.createElement("div");
                    placeholder.className = "card-placeholder-pokeball";
                    placeholder.innerHTML = '<div class="pokeball-circle"></div>';
                    frame.appendChild(placeholder);
                }
                cardFront.appendChild(frame);
                
                // Header: Name & ID
                const header = document.createElement("div");
                header.className = "card-header";
                
                const nameEl = document.createElement("span");
                nameEl.className = "card-name";
                nameEl.textContent = topCard.Name;
                
                const dexEl = document.createElement("span");
                dexEl.className = "card-dex-number";
                dexEl.textContent = topCard["Dex Number"] > 0 ? `#${topCard["Dex Number"]}` : "Custom";
                
                header.appendChild(nameEl);
                header.appendChild(dexEl);
                cardFront.appendChild(header);
                
                // Body stats
                const body = document.createElement("div");
                body.className = "card-body";
                
                const spec = document.createElement("div");
                spec.className = "card-spec";
                const rarityCls = getRarityClass(topCard.Type || "Normal");
                const rarityAbbrev = getRarityAbbrev(topCard.Type || "Normal");
                spec.innerHTML = `<span>Rarity:</span><span class="badge ${rarityCls}">${rarityAbbrev}</span>`;
                body.appendChild(spec);
                const cardRarity = getCardRarity(topCard);
                const rarityBadge = spec.querySelector(".badge");
                rarityBadge.textContent = getRarityAbbrev(cardRarity);
                rarityBadge.className = "badge " + getRarityClass(cardRarity);
                const rank = calculateRank(topCard.Grade, topCard["Catalog Rarity"]);
                if (rank !== "No Rank") {
                    const rankBadge = document.createElement("span");
                    rankBadge.className = "rank-badge rank-" + rank.toLowerCase();
                    rankBadge.textContent = rank;
                    body.appendChild(rankBadge);
                }
                
                if (topCard.Notes) {
                    const notesPreview = document.createElement("div");
                    notesPreview.className = "card-notes-preview";
                    let notesText = topCard.Notes;
                    const maxLen = 30;
                    if (notesText.length > maxLen) {
                        notesText = notesText.substring(0, maxLen) + "...";
                    }
                    notesPreview.textContent = notesText;
                    body.appendChild(notesPreview);
                }
                
                cardFront.appendChild(body);
                cardInner.appendChild(cardFront);
                cardPreview.appendChild(cardInner);
                cardContainer.appendChild(cardPreview);
                
                // Stack indicator badge if multiple cards occupy this pocket
                if (stackedCards.length > 1) {
                    const stackBadge = document.createElement("div");
                    stackBadge.className = "card-stack-badge";
                    stackBadge.textContent = `x${stackedCards.length}`;
                    cardContainer.appendChild(stackBadge);
                }
                
                pocket.appendChild(cardContainer);
            } else {
                // Empty slot: render add button
                const addBtn = document.createElement("button");
                addBtn.className = "empty-slot-btn";
                addBtn.onclick = () => quickAddCard(pageNum, slot);
                addBtn.innerHTML = `
                    <svg viewBox="0 0 24 24" width="24" height="24">
                        <path fill="currentColor" d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/>
                    </svg>
                    <span>Add Card</span>
                `;
                pocket.appendChild(addBtn);
            }
            
            gridEl.appendChild(pocket);
        }
    }
    
    // Render Left Page (Cover or Grid)
    if (leftPageNum === 0) {
        leftGrid.style.display = "block"; // Reset to block layout for cover sheet
        
        const coverSource = appConfig.cover_source || "pokemon";
        const coverColor = appConfig.cover_color || "#ef4444";
        document.documentElement.style.setProperty("--cover-accent", coverColor);
        document.documentElement.style.setProperty("--cover-accent-glow", hexToRgbA(coverColor, 0.25));
        
        let coverImgSrc = "";
        if (coverSource === "pokemon") {
            const featuredDex = appConfig.cover_featured_dex || 25;
            coverImgSrc = featuredDex > 0 
                ? `https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/${featuredDex}.png`
                : "";
        } else if (coverSource === "url") {
            coverImgSrc = appConfig.cover_image_url || "";
        } else if (coverSource === "upload") {
            coverImgSrc = `/cover_image.png?t=${new Date().getTime()}`;
        }
            
        let coverContent = "";
        const isFullBleed = coverSource !== "pokemon";
        
        if (isFullBleed) {
            coverContent = `
                ${coverImgSrc ? `<img class="cover-background-image" src="${coverImgSrc}" alt="Cover Background" onerror="this.style.display='none'">` : ''}
                <div class="cover-content-overlay">
                    <h1 class="cover-title">${escapeHtml(appConfig.cover_title || "Pokémon Card Binder")}</h1>
                    <p class="cover-subtitle">${escapeHtml(appConfig.cover_subtitle || "My Personal Collection Album")}</p>
                    <div class="cover-owner-badge">
                        <span style="color:var(--cover-accent);font-size:12px;display:flex;align-items:center;">🔴</span>
                        <span>Trainer: <strong>${escapeHtml(appConfig.cover_owner || "Trainer")}</strong></span>
                    </div>
                    <button class="cover-edit-btn" onclick="toggleCoverEditor(true)">Customize Cover</button>
                </div>
            `;
        } else {
            coverContent = `
                <div class="cover-artwork-container">
                    <div class="cover-card-inner">
                        ${coverImgSrc ? `<img src="${coverImgSrc}" alt="Cover Photo" onerror="this.onerror=null; this.src=''; this.parentElement.innerHTML='<div class=&quot;pokeball-circle&quot;></div>'">` : '<div class="pokeball-circle"></div>'}
                    </div>
                </div>
                <h1 class="cover-title">${escapeHtml(appConfig.cover_title || "Pokémon Card Binder")}</h1>
                <p class="cover-subtitle">${escapeHtml(appConfig.cover_subtitle || "My Personal Collection Album")}</p>
                <div class="cover-owner-badge">
                    <span style="color:var(--cover-accent);font-size:12px;display:flex;align-items:center;">🔴</span>
                    <span>Trainer: <strong>${escapeHtml(appConfig.cover_owner || "Trainer")}</strong></span>
                </div>
                <button class="cover-edit-btn" onclick="toggleCoverEditor(true)">Customize Cover</button>
            `;
        }
        
        leftGrid.innerHTML = `
            <div class="binder-cover ${isFullBleed ? 'full-bleed' : ''}">
                ${coverContent}
                
                <div id="cover-editor-panel" class="cover-edit-overlay hidden">
                    <h3>Customize Binder Cover</h3>
                    <div class="form-group">
                        <label>Cover Title</label>
                        <input type="text" id="cover-edit-title" value="${escapeHtml(appConfig.cover_title || "Pokémon Card Binder")}">
                    </div>
                    <div class="form-group">
                        <label>Subtitle</label>
                        <input type="text" id="cover-edit-subtitle" value="${escapeHtml(appConfig.cover_subtitle || "My Personal Collection Album")}">
                    </div>
                    <div class="form-group">
                        <label>Trainer Owner Name</label>
                        <input type="text" id="cover-edit-owner" value="${escapeHtml(appConfig.cover_owner || "Trainer")}">
                    </div>
                    <div class="form-row-2">
                        <div class="form-group">
                            <label>Illustration Source</label>
                            <select id="cover-edit-source" onchange="toggleCoverSourceInputs()">
                                <option value="pokemon" ${coverSource === 'pokemon' ? 'selected' : ''}>Pokémon Dex Sprite</option>
                                <option value="url" ${coverSource === 'url' ? 'selected' : ''}>Online Image URL</option>
                                <option value="upload" ${coverSource === 'upload' ? 'selected' : ''}>Upload Local Photo</option>
                            </select>
                        </div>
                        <div class="form-group">
                            <label>Theme Color Accent</label>
                            <select id="cover-edit-color">
                                <option value="#ef4444" ${coverColor === '#ef4444' ? 'selected' : ''}>Pokéball Red</option>
                                <option value="#10b981" ${coverColor === '#10b981' ? 'selected' : ''}>Leaf Green</option>
                                <option value="#3b82f6" ${coverColor === '#3b82f6' ? 'selected' : ''}>Water Blue</option>
                                <option value="#f59e0b" ${coverColor === '#f59e0b' ? 'selected' : ''}>Luxury Gold</option>
                                <option value="#a855f7" ${coverColor === '#a855f7' ? 'selected' : ''}>Psychic Purple</option>
                                <option value="#ec4899" ${coverColor === '#ec4899' ? 'selected' : ''}>Fairy Pink</option>
                                <option value="#4b5563" ${coverColor === '#4b5563' ? 'selected' : ''}>Luxury Gray</option>
                            </select>
                        </div>
                    </div>
                    
                    <div class="form-group source-input-group" id="cover-group-pokemon">
                        <label>Featured Pokemon Dex ID</label>
                        <input type="number" id="cover-edit-dex" value="${appConfig.cover_featured_dex || 25}">
                    </div>
                    
                    <div class="form-group source-input-group hidden" id="cover-group-url">
                        <label>Online Image URL</label>
                        <input type="url" id="cover-edit-url" value="${escapeHtml(appConfig.cover_image_url || "")}" placeholder="https://example.com/photo.jpg">
                    </div>
                    
                    <div class="form-group source-input-group hidden" id="cover-group-upload">
                        <label>Upload Image File</label>
                        <input type="file" id="cover-edit-file" accept="image/*">
                    </div>
                    
                    <div class="form-actions" style="margin-top:auto;">
                        <button class="btn-primary" onclick="saveCoverConfig()">Save Changes</button>
                        <button class="btn-secondary" onclick="toggleCoverEditor(false)">Cancel</button>
                    </div>
                </div>
            </div>
        `;
    } else {
        leftGrid.style.display = "grid"; // Restore grid rules for standard left sheet
        renderPageGrid(leftGrid, leftPageNum, leftCards);
    }
    
    // Render Right Page (Grid)
    renderPageGrid(rightGrid, rightPageNum, rightCards);
}

function changePage(delta) {
    currentPage += delta;
    if (currentPage < 0) currentPage = 0;
    renderBinderGrid();
}

function jumpToPage(val) {
    const parsed = parseInt(val);
    if (!isNaN(parsed) && parsed >= 1) {
        // Calculate the even left page starting this spread
        currentPage = Math.floor(parsed / 2) * 2;
        renderBinderGrid();
    }
}

function quickAddCard(page, slot) {
    switchTab("add");
    document.getElementById("add-page").value = page;
    document.getElementById("add-slot").value = slot;
    checkSlotOccupation();
}

// ==================== COLLECTION TABLE VIEW ====================
function getCardImageSource(card) {
    const imageUrl = catalogImageUrl(card["Image URL"], "high");
    if (imageUrl) return imageUrl;
    if (card["Catalog Card ID"]) return "";
    return card["Dex Number"] > 0
        ? `https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/${card["Dex Number"]}.png`
        : "";
}

async function persistCardImageMatch(card, printing, match) {
    if (!card || !card["Copy ID"] || !printing || !match) return;
    try {
        const response = await fetch("/api/update-card", {
            method: "POST", headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                copy_id: card["Copy ID"], catalog_card_id: printing.id, set_id: printing.setId,
                set_name: printing.setName, card_number: printing.number, catalog_rarity: printing.rarity,
                image_url: match.imageUrl, image_source: match.imageSource, image_match_id: match.imageMatchId
            })
        });
        const data = await response.json();
        if (!data.success) return;
        Object.assign(card, {
            "Catalog Card ID": printing.id, "Set ID": printing.setId, "Set Name": printing.setName,
            "Card Number": printing.number, "Catalog Rarity": printing.rarity,
            "Image URL": match.imageUrl, "Image Source": match.imageSource, "Image Match ID": match.imageMatchId
        });
        const savedCopy = collection.find(item => item["Copy ID"] === card["Copy ID"]);
        if (savedCopy && savedCopy !== card) Object.assign(savedCopy, card);
    } catch (_) {}
}

async function resolveCardArtwork(container, card) {
    if (!card || !card["Catalog Card ID"] || card["Image Source"] === "Scrydex") return;
    const match = await resolveCatalogImageFallback({ id: card["Catalog Card ID"] });
    if (!match) {
        const placeholder = container.querySelector(".image-unavailable") || container.firstChild;
        if (placeholder) placeholder.textContent = "Imagem indisponível";
        return;
    }
    Object.assign(card, { "Image URL": match.imageUrl, "Image Source": match.imageSource, "Image Match ID": match.imageMatchId });
    const image = document.createElement("img");
    image.src = match.imageUrl;
    image.alt = card.Name || "Imagem da carta";
    image.loading = "lazy";
    setCardImageFallback(image, card);
    container.replaceChildren(image);
    await persistCardImageMatch(card, {
        id: card["Catalog Card ID"], setId: card["Set ID"], setName: card["Set Name"],
        number: card["Card Number"], rarity: card["Catalog Rarity"]
    }, match);
}

function calculateRank(gradeValue, rarity) {
    if (gradeValue == null || String(gradeValue).trim() === "") return "No Rank";
    const grade = Number(gradeValue);
    const rarityPoints = {
        "common": 0, "uncommon": 25, "rare": 50, "rare holo": 50,
        "holo rare": 50, "holo rare v": 50, "holo rare vmax": 50,
        "holo rare vstar": 50, "rare holo ex": 50, "rare holo gx": 50,
        "rare holo lv.x": 50, "rare holo v": 50, "rare holo vmax": 50,
        "rare holo vstar": 50, "ultra rare": 75, "double rare": 75,
        "illustration rare": 75, "secret rare": 100, "rare secret": 100,
        "special illustration rare": 100, "hyper rare": 100
    };
    const key = String(rarity || "").trim().toLowerCase();
    if (!Number.isFinite(grade) || grade < 1 || grade > 10 || !Object.prototype.hasOwnProperty.call(rarityPoints, key)) return "No Rank";
    const score = 0.8 * (grade * 10) + 0.2 * rarityPoints[key];
    if (score < 50) return "Bronze";
    if (score < 65) return "Silver";
    if (score < 80) return "Gold";
    if (score < 90) return "Platinum";
    return "Diamond";
}

function chooseUniqueCatalogCard(candidates, hints = {}) {
    const clean = value => String(value || "").trim().toLowerCase();
    let matches = (candidates || []).filter(card => !hints.name || clean(card.name) === clean(hints.name));
    if (hints.setName) matches = matches.filter(card => clean(card.setName || (card.set && card.set.name)) === clean(hints.setName));
    if (hints.cardNumber) {
        const number = clean(hints.cardNumber).split("/")[0].replace(/^#/, "");
        matches = matches.filter(card => clean(card.number || card.localId).split("/")[0].replace(/^#/, "") === number);
    }
    return matches.length === 1 ? matches[0] : null;
}

function getCardRarity(card) {
    return card["Catalog Rarity"] || card.Type || "Normal";
}

function isPokemonCardNameMatch(cardName, pokemonName) {
    const normalize = value => String(value || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "")
        .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim().replace(/\s+/g, " ");
    let candidate = normalize(cardName).split(" ");
    const target = normalize(pokemonName);
    if (!target || !candidate.length) return false;
    const isMega = ["m", "mega"].includes(candidate[0]);
    if (isMega) candidate.shift();
    if (["ex", "gx", "v", "vmax", "vstar", "break"].includes(candidate.at(-1))) candidate.pop();
    if (isMega && ["x", "y"].includes(candidate.at(-1))) candidate.pop();
    const name = candidate.join(" ");
    return name === target;
}

async function resolveCatalogImageFallback(card) {
    const id = String(card && card.id || "");
    if (!id) return null;
    if (catalogImageFallbackCache.has(id)) return await catalogImageFallbackCache.get(id);
    const request = (async () => {
        const response = await fetch("/api/catalog-image-fallback", {
            method: "POST", headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ tcgdex_card_id: id })
        });
        if (!response.ok) throw new Error("Fallback image service unavailable");
        const data = await response.json();
        if (!data.success) throw new Error("Fallback image lookup failed");
        return data.match && data.match.image_url && data.match.card_id ? {
            imageUrl: data.match.image_url, imageSource: "Scrydex", imageMatchId: data.match.card_id
        } : null;
    })();
    catalogImageFallbackCache.set(id, request);
    try {
        const match = await request;
        catalogImageFallbackCache.set(id, match);
        return match;
    } catch (_) {
        catalogImageFallbackCache.delete(id);
        return null;
    }
}

const catalogImageSetAliases = {
    "swsh4.5sv": "swsh4.5", "swsh12.5gg": "swsh12.5",
    "swsh9tg": "swsh9", "swsh9.5tg": "swsh9",
    "swsh10tg": "swsh10", "swsh10.5tg": "swsh10",
    "swsh11tg": "swsh11", "swsh11.5tg": "swsh11",
    "swsh12tg": "swsh12", "swsh12.5tg": "swsh12.5"
};
const catalogSetSeriesOverrides = {
    mep: "me", svp: "sv", "P-A": "sv",
    "swsh4.5sv": "swsh", "swsh12.5gg": "swsh",
    swsh9tg: "swsh", "swsh9.5tg": "swsh",
    swsh10tg: "swsh", "swsh10.5tg": "swsh",
    swsh11tg: "swsh", "swsh11.5tg": "swsh",
    swsh12tg: "swsh", "swsh12.5tg": "swsh"
};

function catalogImageUrl(value, quality = "high") {
    const url = String(value || "").trim();
    if (/^https:\/\/images\.scrydex\.com\/pokemon\/[^/?#]+\/(?:small|medium|large)$/i.test(url)) return url;
    const match = url.match(/^https:\/\/assets\.tcgdex\.net\/([a-z]{2}(?:-[a-z]{2})?)\/([^/]+)\/([^/]+)\/([^/]+)(?:\/(?:high|low)\.(?:png|webp|jpe?g))?$/i);
    if (!match || /^(?:logo|symbol)(?:\.[a-z]+)?$/i.test(match[4])) return "";
    const size = quality === "low" ? "low.webp" : "high.png";
    return `https://assets.tcgdex.net/${match[1]}/${match[2]}/${match[3]}/${match[4]}/${size}`;
}

function getCatalogSetId(card) {
    if (card.set && card.set.id) return String(card.set.id);
    const id = String(card.id || "");
    const localId = String(card.localId || "");
    return localId && id.endsWith("-" + localId) ? id.slice(0, -localId.length - 1) : "";
}

function resolveCatalogImageUrl(card, setDetails = catalogSetDetails) {
    const source = catalogImageUrl(card.image, "low");
    if (source) return source;
    const setId = getCatalogSetId(card);
    const localId = String(card.localId || "").trim();
    const seriesId = (card.set && card.set.serie && card.set.serie.id) ||
        (setDetails && setDetails[setId] && setDetails[setId].seriesId) || catalogSetSeriesOverrides[setId];
    if (!setId || !localId || !seriesId) return "";
    const assetSetId = catalogImageSetAliases[setId] || setId;
    return catalogImageUrl(`https://assets.tcgdex.net/en/${seriesId}/${assetSetId}/${localId}`, "low");
}

function setCardImageFallback(img, card, onResolved = () => {}) {
    const dex = Number(card["Dex Number"]);
    const fallback = dex > 0 ? "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/" + dex + ".png" : "";
    img.onerror = async () => {
        if (card["Catalog Card ID"]) {
            if (card["Image Source"] !== "Scrydex") {
                const match = await resolveCatalogImageFallback({ id: card["Catalog Card ID"] });
                if (match) {
                    img.src = match.imageUrl;
                    onResolved(match);
                    await persistCardImageMatch(card, {
                        id: card["Catalog Card ID"], setId: card["Set ID"], setName: card["Set Name"],
                        number: card["Card Number"], rarity: card["Catalog Rarity"]
                    }, match);
                    return;
                }
            }
            const placeholder = document.createElement("span");
            placeholder.className = "image-unavailable";
            placeholder.textContent = "Imagem indisponível";
            img.replaceWith(placeholder);
        } else if (fallback && img.src !== fallback) img.src = fallback;
        else {
            const placeholder = document.createElement("span");
            placeholder.className = "image-unavailable";
            placeholder.textContent = "Imagem indisponível";
            img.replaceWith(placeholder);
        }
    };
}

async function loadCatalogSetNames() {
    if (catalogSetNames) return catalogSetNames;
    const response = await fetch(TCGDEX_API + "/sets");
    if (!response.ok) throw new Error("TCGdex sets unavailable");
    const sets = await response.json();
    catalogSetNames = Object.fromEntries(sets.map(set => [set.id, set.name]));
    catalogSetDetails = Object.fromEntries(sets.map(set => [set.id, { seriesId: set.serie && set.serie.id }]));
    return catalogSetNames;
}

function renderCatalogOptions(cards, listId, revision) {
    const list = document.getElementById(listId);
    list.replaceChildren();
    document.getElementById(listId.replace("-options", "-search")).hidden = false;
    document.getElementById(listId.replace("-options", "-search-empty")).classList.add("hidden");
    const isModal = listId === "modal-set-options";
    const selected = isModal ? modalSelectedCatalogCard : selectedCatalogCard;
    cards.sort((a, b) => a.setName.localeCompare(b.setName, undefined, { sensitivity: "base" }));
    cards.forEach(card => {
        const option = document.createElement("button");
        option.type = "button";
        option.className = "catalog-option";
        option.setAttribute("role", "option");
        option.dataset.catalogCardId = card.id;
        option.dataset.setName = card.setName;
        option.setAttribute("aria-selected", selected && selected.id === card.id ? "true" : "false");
        const art = document.createElement("span");
        art.className = "catalog-option-art";
        const placeholder = document.createElement("span");
        placeholder.className = "artwork-placeholder";
        placeholder.textContent = "A carregar imagem…";
        art.appendChild(placeholder);
        const image = document.createElement("img");
        image.alt = "";
        image.loading = "eager";
        image.classList.add("hidden");
        image.addEventListener("load", () => {
            image.classList.remove("hidden");
            placeholder.classList.add("hidden");
        });
        image.addEventListener("error", async () => {
            image.classList.add("hidden");
            if (card.imageSource !== "Scrydex" && card.id && revision === (isModal ? modalCatalogRevision : catalogSearchRevision)) {
                const fallback = await resolveCatalogImageFallback(card);
                if (fallback) {
                    Object.assign(card, fallback);
                    image.src = fallback.imageUrl;
                    return;
                }
            }
            placeholder.textContent = "Imagem indisponível";
            placeholder.classList.remove("hidden");
        });
        art.appendChild(image);
        option.appendChild(art);
        const copy = document.createElement("span");
        copy.className = "catalog-option-copy";
        const name = document.createElement("strong");
        name.textContent = card.setName;
        const number = document.createElement("span");
        number.className = "muted-text";
        number.textContent = "#" + (card.number || "?");
        copy.append(name, number);
        option.appendChild(copy);
        option.addEventListener("click", () => chooseCatalogCard(card, listId, revision));
        list.appendChild(option);
        if (card.imageUrl) image.src = card.imageUrl;
        else resolveCatalogImageFallback(card).then(fallback => {
            if (fallback && revision === (isModal ? modalCatalogRevision : catalogSearchRevision)) {
                Object.assign(card, fallback);
                image.src = fallback.imageUrl;
            } else if (!fallback) {
                placeholder.textContent = "Imagem indisponível";
            }
        });
    });
}

function filterCatalogOptions(listId, query) {
    const normalize = value => String(value || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().trim();
    const term = normalize(query);
    let matches = 0;
    Array.from(document.getElementById(listId).children).forEach(option => {
        option.hidden = Boolean(term && !normalize(option.dataset.setName).includes(term));
        if (!option.hidden) matches++;
    });
    const empty = document.getElementById(listId.replace("-options", "-search-empty"));
    if (term && !matches) empty.classList.remove("hidden");
    else empty.classList.add("hidden");
}

async function searchCatalogCards(name, listId, revision) {
    const isModal = listId === "modal-set-options";
    const current = () => isModal ? revision === modalCatalogRevision : revision === catalogSearchRevision;
    const status = document.getElementById(isModal ? "modal-set-status" : "add-set-status");
    const list = document.getElementById(listId);
    if (!current()) return;
    const search = document.getElementById(listId.replace("-options", "-search"));
    search.value = "";
    search.hidden = true;
    document.getElementById(listId.replace("-options", "-search-empty")).classList.add("hidden");
    status.textContent = isModal ? "A carregar edições…" : "Searching TCGdex printings for " + name + "...";
    list.replaceChildren();
    try {
        const response = await fetch(TCGDEX_API + "/cards?name=" + encodeURIComponent(name));
        if (!response.ok) throw new Error("TCGdex search failed");
        const records = await response.json();
        const matching = records.filter(card => isPokemonCardNameMatch(card.name, name));
        let sets = {};
        try { sets = await loadCatalogSetNames(); } catch (_) {}
        if (!current()) return;
        const cards = matching.map(card => {
            const id = String(card.id || "");
            const splitAt = id.lastIndexOf("-");
            const setId = getCatalogSetId(card) || (splitAt > 0 ? id.slice(0, splitAt) : id);
            return {
                id, name: card.name, setId, setName: sets[setId] || setId,
                number: card.localId || "", rarity: "", imageSource: "TCGdex", imageMatchId: "",
                imageUrl: resolveCatalogImageUrl(card, catalogSetDetails)
            };
        });
        if (!cards.length) {
            status.textContent = "No printings found. You can still add this Pokémon without a TCGdex set.";
            return;
        }
        status.textContent = "Choose the matching set and card number. Leaving this blank adds only the Pokémon.";
        renderCatalogOptions(cards, listId, revision);
        if (isModal) {
            status.textContent = "";
            if (modalSavedCatalogCard) {
                const savedMatch = cards.find(card => card.id === modalSavedCatalogCard.id);
                if (savedMatch) {
                    modalSavedCatalogCard = { ...modalSavedCatalogCard, imageUrl: savedMatch.imageUrl || modalSavedCatalogCard.imageUrl };
                    modalSelectedCatalogCard = modalSavedCatalogCard;
                    renderSavedModalPrinting();
                    if (modalSavedCatalogCard.id) renderModalArtwork(modalSavedCatalogCard.imageUrl, true, modalSavedCatalogCard.imageSource, modalSavedCatalogCard.imageMatchId);
                }
            }
        }
        const unique = chooseUniqueCatalogCard(cards, { name });
        if (unique && !isModal) chooseCatalogCard(unique, listId, revision);
    } catch (error) {
        if (!current()) return;
        status.replaceChildren(document.createTextNode("Could not load TCGdex printings. "));
        const button = document.createElement("button");
        button.type = "button";
        button.className = "btn-secondary";
        button.textContent = "Retry";
        button.addEventListener("click", () => searchCatalogCards(name, listId, revision));
        status.appendChild(button);
    }
}

async function chooseCatalogCard(candidate, listId, revision) {
    const isModal = listId === "modal-set-options";
    const current = () => isModal ? revision === modalCatalogRevision : revision === catalogSearchRevision;
    const selectionRevision = isModal ? ++modalSelectionRevision : ++catalogSelectionRevision;
    const selectionIsCurrent = () => current() && (isModal ? selectionRevision === modalSelectionRevision : selectionRevision === catalogSelectionRevision);
    const status = document.getElementById(isModal ? "modal-set-status" : "add-set-status");
    status.textContent = "Loading the selected TCGdex card...";
    try {
        const response = await fetch(TCGDEX_API + "/cards/" + encodeURIComponent(candidate.id));
        if (!response.ok) throw new Error("TCGdex card unavailable");
        const details = await response.json();
        if (!selectionIsCurrent()) return;
        const setId = (details.set && details.set.id) || candidate.setId;
        const selected = {
            id: details.id || candidate.id,
            name: details.name || candidate.name,
            setId,
            setName: (details.set && details.set.name) || candidate.setName,
            number: details.localId || candidate.number,
            rarity: details.rarity || "",
            imageUrl: candidate.imageSource === "Scrydex" ? candidate.imageUrl : catalogImageUrl(details.image, "high") || catalogImageUrl(candidate.imageUrl, "high"),
            imageSource: candidate.imageSource === "Scrydex" ? "Scrydex" : (catalogImageUrl(details.image, "high") || catalogImageUrl(candidate.imageUrl, "high") ? "TCGdex" : ""),
            imageMatchId: candidate.imageSource === "Scrydex" ? candidate.imageMatchId : ""
        };
        if (isModal) {
            modalSelectedCatalogCard = selected;
            updateModalPrintingUi();
            const fallback = modalActiveCard && getCardImageSource({ ...modalActiveCard, "Image URL": "" });
            renderModalArtwork(selected.imageUrl || fallback, Boolean(selected.id), selected.imageSource, selected.imageMatchId);
        } else {
            selectedCatalogCard = selected;
            document.getElementById("add-clear-set").disabled = false;
            const raritySelect = document.getElementById("add-type");
            if (selected.rarity) {
                if (![...raritySelect.options].some(option => option.value === selected.rarity)) {
                    const option = document.createElement("option");
                    option.value = selected.rarity;
                    option.textContent = selected.rarity;
                    raritySelect.appendChild(option);
                }
                raritySelect.value = selected.rarity;
            }
            updatePreviewRarity(raritySelect.value);
            const image = document.getElementById("preview-image");
            const placeholder = document.getElementById("preview-image-placeholder");
            if (selected.imageUrl) {
                image.src = selected.imageUrl;
                setCardImageFallback(image, {
                    "Dex Number": selectedPokemon ? selectedPokemon.id : 0, "Catalog Card ID": selected.id,
                    "Set ID": selected.setId, "Set Name": selected.setName, "Card Number": selected.number,
                    "Catalog Rarity": selected.rarity, "Image Source": selected.imageSource
                }, match => Object.assign(selectedCatalogCard, match));
                image.classList.remove("hidden");
                placeholder.classList.add("hidden");
            } else {
                image.classList.add("hidden");
                placeholder.classList.remove("hidden");
                if (selected.id) {
                    placeholder.textContent = "A carregar imagem…";
                    resolveCatalogImageFallback(selected).then(match => {
                        if (match && selectedCatalogCard && selectedCatalogCard.id === selected.id) {
                            Object.assign(selected, match);
                            image.src = match.imageUrl;
                            setCardImageFallback(image, {
                                "Dex Number": selectedPokemon ? selectedPokemon.id : 0, "Catalog Card ID": selected.id,
                                "Set ID": selected.setId, "Set Name": selected.setName, "Card Number": selected.number,
                                "Catalog Rarity": selected.rarity, "Image Source": selected.imageSource
                            }, resolved => Object.assign(selectedCatalogCard, resolved));
                            image.classList.remove("hidden");
                            placeholder.classList.add("hidden");
                        } else placeholder.textContent = "Imagem indisponível";
                    });
                }
            }
            status.textContent = "Selected " + selected.setName + " - #" + selected.number + ".";
        }
        const selectedCard = isModal ? modalSelectedCatalogCard : selectedCatalogCard;
        Array.from(document.getElementById(listId).children).forEach(option => {
            option.setAttribute("aria-selected", selectedCard && option.dataset.catalogCardId === selectedCard.id ? "true" : "false");
        });
    } catch (error) {
        if (selectionIsCurrent()) status.textContent = "Could not load that card. Choose another printing.";
    }
}

function clearAddCatalogCard() {
    catalogSelectionRevision++;
    selectedCatalogCard = null;
    document.getElementById("add-clear-set").disabled = true;
    document.getElementById("add-set-status").textContent = "No printing selected; the card will be saved without a TCGdex set.";
    const rarity = document.getElementById("add-type").value;
    updatePreviewRarity(rarity);
    Array.from(document.getElementById("add-set-options").children).forEach(option => option.setAttribute("aria-selected", "false"));
    const image = document.getElementById("preview-image");
    const dex = Number(selectedPokemon && selectedPokemon.id);
    if (dex > 0) {
        image.src = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/" + dex + ".png";
        image.classList.remove("hidden");
        document.getElementById("preview-image-placeholder").classList.add("hidden");
    } else {
        image.classList.add("hidden");
        document.getElementById("preview-image-placeholder").classList.remove("hidden");
    }
}

function getSavedModalCatalogCard(card) {
    const id = String(card["Catalog Card ID"] || "");
    const setId = String(card["Set ID"] || "");
    const setName = String(card["Set Name"] || "");
    const number = String(card["Card Number"] || "");
    if (!id && !setId && !setName && !number) return null;
    return {
        id, name: card.Name, setId, setName: setName || setId, number,
        rarity: card["Catalog Rarity"] || "",
        imageUrl: catalogImageUrl(card["Image URL"], "high"),
        imageSource: card["Image Source"] || "",
        imageMatchId: card["Image Match ID"] || ""
    };
}

function isValidCatalogPrinting(card) {
    return Boolean(card && card.id && card.setId && card.setName && card.number);
}

function modalPrintingNeedsSave() {
    if (!isValidCatalogPrinting(modalSelectedCatalogCard)) return false;
    const saved = modalSavedCatalogCard;
    return !saved || modalSelectedCatalogCard.id !== saved.id ||
        modalSelectedCatalogCard.imageSource !== saved.imageSource ||
        modalSelectedCatalogCard.imageMatchId !== saved.imageMatchId ||
        modalSelectedCatalogCard.imageUrl !== saved.imageUrl;
}

function renderSavedModalPrinting() {
    const saved = modalSavedCatalogCard;
    document.getElementById("modal-current-label").textContent = saved && saved.id ? "Edição guardada" : "Edição";
    document.getElementById("modal-current-set").textContent = saved ? (saved.setName || saved.setId) : "Sem edição guardada";
    document.getElementById("modal-current-number").textContent = saved && saved.number ? "#" + saved.number : "";
    const image = document.getElementById("modal-current-set-image");
    const placeholder = document.getElementById("modal-current-set-image-placeholder");
    const imageUrl = saved && catalogImageUrl(saved.imageUrl, "low");
    image.classList.add("hidden");
    placeholder.textContent = imageUrl ? "A carregar imagem…" : "Imagem indisponível";
    placeholder.classList.remove("hidden");
    image.onload = () => {
        if (!imageUrl || image.src !== imageUrl) return;
        image.classList.remove("hidden");
        placeholder.classList.add("hidden");
    };
    image.onerror = () => {
        if (!imageUrl || image.src !== imageUrl) return;
        image.classList.add("hidden");
        if (saved && saved.id && saved.imageSource !== "Scrydex") {
            resolveCatalogImageFallback(saved).then(match => {
                if (match && modalSavedCatalogCard === saved) {
                    Object.assign(saved, match);
                    renderSavedModalPrinting();
                    persistCardImageMatch(modalActiveCard, saved, match);
                } else {
                    placeholder.textContent = "Imagem indisponível";
                    placeholder.classList.remove("hidden");
                }
            });
        } else {
            placeholder.textContent = "Imagem indisponível";
            placeholder.classList.remove("hidden");
        }
    };
    if (imageUrl) image.src = imageUrl;
}

function renderModalArtwork(url, isCardImage, imageSource = "TCGdex", imageMatchId = "") {
    const image = document.getElementById("modal-card-artwork-image");
    const placeholder = document.getElementById("modal-card-artwork-placeholder");
    const button = document.getElementById("modal-card-artwork-button");
    const revision = ++modalArtworkRevision;
    const source = isCardImage ? catalogImageUrl(url, "high") : String(url || "");
    const printing = modalSelectedCatalogCard || modalSavedCatalogCard;
    modalArtworkUrl = "";
    modalArtworkIsCard = false;
    image.classList.add("hidden");
    button.disabled = true;
    placeholder.textContent = source || (isCardImage && printing && printing.id) ? "A carregar imagem…" : "Imagem indisponível";
    placeholder.classList.remove("hidden");
    image.alt = modalActiveCard ? "Imagem de " + modalActiveCard.Name : "Imagem da carta";
    image.onload = () => {
        if (revision !== modalArtworkRevision || (source && image.src !== source)) return;
        image.classList.remove("hidden");
        placeholder.classList.add("hidden");
        modalArtworkUrl = source;
        modalArtworkIsCard = Boolean(isCardImage);
        button.disabled = !modalArtworkIsCard;
        button.setAttribute("aria-label", modalArtworkIsCard ? "Ampliar imagem da carta" : "Imagem do Pokémon");
    };
    image.onerror = () => {
        if (revision !== modalArtworkRevision || !source || image.src !== source) return;
        if (isCardImage && printing && printing.id && imageSource !== "Scrydex") {
            resolveCatalogImageFallback(printing).then(match => {
                if (revision !== modalArtworkRevision) return;
                if (match) {
                    Object.assign(printing, match);
                    const isSaved = printing === modalSavedCatalogCard && !modalPrintingNeedsSave();
                    if (modalSelectedCatalogCard && modalSelectedCatalogCard.id === printing.id) Object.assign(modalSelectedCatalogCard, match);
                    updateModalPrintingUi();
                    renderSavedModalPrinting();
                    renderModalArtwork(match.imageUrl, true, match.imageSource, match.imageMatchId);
                    if (isSaved) persistCardImageMatch(modalActiveCard, printing, match);
                    return;
                }
                image.classList.add("hidden");
                placeholder.textContent = "Imagem indisponível";
                placeholder.classList.remove("hidden");
            });
            return;
        }
        image.classList.add("hidden");
        placeholder.textContent = "Imagem indisponível";
        placeholder.classList.remove("hidden");
    };
    if (source) image.src = source;
    else if (isCardImage && printing && printing.id) image.onerror();
}

function openModalImageViewer() {
    if (!modalArtworkIsCard || !modalArtworkUrl) return;
    const viewer = document.getElementById("modal-image-viewer");
    const image = document.getElementById("modal-image-viewer-image");
    image.src = modalArtworkUrl;
    image.alt = modalActiveCard ? "Imagem ampliada da carta de " + modalActiveCard.Name : "Imagem ampliada da carta";
    viewer.classList.remove("hidden");
}

function closeModalImageViewer(event) {
    if (event) event.stopPropagation();
    document.getElementById("modal-image-viewer").classList.add("hidden");
    document.getElementById("modal-image-viewer-image").src = "";
}

function updateModalPrintingUi() {
    const dirty = modalPrintingNeedsSave();
    const save = document.getElementById("modal-save-set");
    save.disabled = !dirty;
    const pending = document.getElementById("modal-pending-set");
    pending.textContent = dirty
        ? (modalSavedCatalogCard && modalSelectedCatalogCard.id === modalSavedCatalogCard.id ? "Origem da imagem por guardar: " : "Nova edição por guardar: ") + modalSelectedCatalogCard.setName + " · #" + modalSelectedCatalogCard.number
        : "";
    if (dirty) pending.classList.remove("hidden");
    else pending.classList.add("hidden");
    document.getElementById("modal-set-status").textContent = "";
}

function openModalPrintingPicker() {
    const picker = document.getElementById("modal-set-picker");
    picker.classList.remove("hidden");
    if (!document.getElementById("modal-set-options").children.length && !document.getElementById("modal-set-status").textContent) {
        document.getElementById("modal-set-status").textContent = "A carregar edições…";
    }
    document.getElementById("modal-open-set-picker").setAttribute("aria-expanded", "true");
}

function cancelModalCatalogSelection() {
    modalSelectionRevision++;
    modalSelectedCatalogCard = modalSavedCatalogCard;
    document.getElementById("modal-set-picker").classList.add("hidden");
    document.getElementById("modal-open-set-picker").setAttribute("aria-expanded", "false");
    document.getElementById("modal-set-status").textContent = "";
    updateModalPrintingUi();
    renderSavedModalPrinting();
    const imageUrl = modalSavedCatalogCard && modalSavedCatalogCard.imageUrl;
    renderModalArtwork(imageUrl || (modalActiveCard && getCardImageSource({ ...modalActiveCard, "Image URL": "" })), Boolean(modalSavedCatalogCard && modalSavedCatalogCard.id), modalSavedCatalogCard && modalSavedCatalogCard.imageSource, modalSavedCatalogCard && modalSavedCatalogCard.imageMatchId);
    Array.from(document.getElementById("modal-set-options").children).forEach(option =>
        option.setAttribute("aria-selected", modalSelectedCatalogCard && option.dataset.catalogCardId === modalSelectedCatalogCard.id ? "true" : "false"));
}

function loadModalCatalogOptions(card) {
    modalCatalogRevision++;
    modalSavedCatalogCard = getSavedModalCatalogCard(card);
    modalSelectedCatalogCard = modalSavedCatalogCard;
    document.getElementById("modal-save-set").disabled = true;
    document.getElementById("modal-set-picker").classList.add("hidden");
    document.getElementById("modal-open-set-picker").setAttribute("aria-expanded", "false");
    document.getElementById("modal-set-options").replaceChildren();
    document.getElementById("modal-set-status").textContent = "";
    renderSavedModalPrinting();
    const imageUrl = modalSavedCatalogCard && modalSavedCatalogCard.imageUrl;
    renderModalArtwork(imageUrl || getCardImageSource({ ...card, "Image URL": "" }), Boolean(modalSavedCatalogCard && modalSavedCatalogCard.id), modalSavedCatalogCard && modalSavedCatalogCard.imageSource, modalSavedCatalogCard && modalSavedCatalogCard.imageMatchId);
    return searchCatalogCards(card.Name, "modal-set-options", modalCatalogRevision);
}

function renderModalGrade(card) {
    const summary = document.getElementById("modal-grade-summary");
    const criteriaBox = document.getElementById("modal-grade-criteria");
    const details = document.getElementById("modal-grade-details");
    const detailsText = document.getElementById("modal-grade-details-text");
    const overlay = document.getElementById("modal-card-rank");
    const overlayGrade = document.getElementById("modal-card-rank-grade");
    const overlayRank = document.getElementById("modal-card-rank-name");
    summary.replaceChildren();
    criteriaBox.replaceChildren();
    const grade = Number(card.Grade);
    const hasGrade = card.Grade != null && String(card.Grade).trim() !== "" && Number.isFinite(grade) && grade >= 1 && grade <= 10;
    if (!hasGrade) {
        const score = document.createElement("span");
        score.textContent = "Sem avaliação";
        summary.appendChild(score);
        overlay.classList.add("hidden");
        details.open = false;
        details.hidden = true;
        criteriaBox.hidden = true;
        detailsText.textContent = "";
        return;
    }
    criteriaBox.hidden = false;
    const rank = calculateRank(hasGrade ? grade : null, card["Catalog Rarity"]);
    const rankBadge = document.createElement("span");
    rankBadge.className = "rank-badge rank-" + rank.toLowerCase().replace(/\s+/g, "-");
    rankBadge.textContent = rank === "No Rank" ? "Sem rank" : rank;
    summary.appendChild(rankBadge);
    overlay.className = "card-rank-overlay rank-" + rank.toLowerCase().replace(/\s+/g, "-");
    overlay.classList.remove("hidden");
    overlayGrade.textContent = Number.isInteger(grade) ? String(grade) : String(grade);
    overlayRank.textContent = rank === "No Rank" ? "Sem rank" : rank;
    overlay.setAttribute("aria-label", `${rank === "No Rank" ? "Sem rank" : rank}; nota ${grade}/10`);
    const rawDetails = String(card["Grade Details"] || "");
    let parsed = null;
    try { parsed = rawDetails ? JSON.parse(rawDetails) : null; } catch (_) {}
    const criteria = parsed && parsed.criteria;
    if (criteria && typeof criteria === "object") {
        Object.entries(criteria).forEach(([name, result]) => {
            const value = result && typeof result === "object" ? result.score : result;
            const criterionScore = Number(value);
            if (value == null || String(value).trim() === "" || !Number.isFinite(criterionScore) || criterionScore < 1 || criterionScore > 10) return;
            const item = document.createElement("span");
            item.className = "badge";
            item.textContent = name + " " + criterionScore + "/10";
            criteriaBox.appendChild(item);
        });
    }
    detailsText.textContent = parsed ? JSON.stringify(parsed, null, 2) : rawDetails;
    details.open = false;
    details.hidden = !rawDetails;
}

function toggleModalEditDetails() {
    if (!modalActiveCard) return;
    const form = document.getElementById("modal-edit-form");
    const button = document.getElementById("modal-edit-details");
    const opening = form.classList.contains("hidden");
    if (opening) {
        const card = modalActiveCard;
        document.getElementById("modal-edit-name").value = card.Name || "";
        document.getElementById("modal-edit-dex").value = card["Dex Number"] || "";
        document.getElementById("modal-edit-page").value = card.Page || 1;
        document.getElementById("modal-edit-slot").value = card.Slot || 1;
        document.getElementById("modal-edit-condition").value = card.Condition || "NM";
        document.getElementById("modal-edit-type").value = card.Type || "Normal";
        document.getElementById("modal-edit-rarity").value = card["Catalog Rarity"] || "";
        document.getElementById("modal-edit-grade").value = card.Grade || "";
        document.getElementById("modal-edit-date").value = String(card["Date Added"] || "").replace(" ", "T");
        document.getElementById("modal-edit-status").textContent = "";
        form.classList.remove("hidden");
        document.getElementById("modal-edit-name").focus();
    } else form.classList.add("hidden");
    button.setAttribute("aria-expanded", opening ? "true" : "false");
}

function cancelModalEditDetails() {
    document.getElementById("modal-edit-form").classList.add("hidden");
    document.getElementById("modal-edit-details").setAttribute("aria-expanded", "false");
    document.getElementById("modal-edit-status").textContent = "";
}

async function saveModalCopyDetails() {
    if (!modalActiveCard) return;
    const card = modalActiveCard;
    const button = document.getElementById("modal-save-details");
    const status = document.getElementById("modal-edit-status");
    button.disabled = true;
    status.textContent = "A guardar…";
    try {
        const response = await fetch("/api/update-card", {
            method: "POST", headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                copy_id: card["Copy ID"] || "", page: document.getElementById("modal-edit-page").value,
                slot: document.getElementById("modal-edit-slot").value, name: document.getElementById("modal-edit-name").value,
                dex_id: document.getElementById("modal-edit-dex").value || 0,
                date_added: document.getElementById("modal-edit-date").value.replace("T", " "),
                condition: document.getElementById("modal-edit-condition").value,
                type: document.getElementById("modal-edit-type").value,
                catalog_rarity: document.getElementById("modal-edit-rarity").value,
                grade: document.getElementById("modal-edit-grade").value
            })
        });
        const data = await response.json();
        if (!data.success) throw new Error(data.error || "Não foi possível guardar as alterações.");
        await loadCollection();
        const updated = data.card || card;
        openCardModal(updated.Page, updated.Slot, updated["Copy ID"] || updated["Date Added"]);
        showToast("Detalhes atualizados.", "success");
    } catch (error) {
        status.textContent = error.message || "Não foi possível guardar as alterações.";
    } finally {
        button.disabled = false;
    }
}

async function saveModalCatalogCard() {
    if (!modalActiveCard || !modalPrintingNeedsSave()) return;
    const card = modalActiveCard;
    const selected = modalSelectedCatalogCard;
    const status = document.getElementById("modal-set-status");
    const payload = {
        copy_id: card["Copy ID"] || "",
        page: card.Page, slot: card.Slot, name: card.Name, date_added: card["Date Added"],
        catalog_card_id: selected.id, set_id: selected.setId, set_name: selected.setName,
        card_number: selected.number, catalog_rarity: selected.rarity, image_url: selected.imageUrl,
        image_source: selected.imageSource || "", image_match_id: selected.imageMatchId || ""
    };
    status.textContent = "A guardar a nova edição…";
    document.getElementById("modal-save-set").disabled = true;
    try {
        const response = await fetch("/api/update-card", {
            method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload)
        });
        const data = await response.json();
        if (!data.success) throw new Error(data.error || "Could not save printing");
        showToast("Edição TCGdex guardada.", "success");
        await loadCollection();
        const refreshed = collection.find(item => card["Copy ID"] && item["Copy ID"] === card["Copy ID"]) ||
            collection.find(item => item.Page === card.Page && item.Slot === card.Slot && item["Date Added"] === card["Date Added"]);
        if (refreshed) openCardModal(refreshed.Page, refreshed.Slot, refreshed["Copy ID"] || refreshed["Date Added"]);
    } catch (error) {
        status.textContent = error.message || "Could not save the selected printing.";
        document.getElementById("modal-save-set").disabled = false;
        showToast(status.textContent, "error");
    }
}

function filterCollectionCards(cards, { searchVal = "", condVal = "", typeFilterVal = "", sortBy = "dex", showRepeated = false }) {
    searchVal = searchVal.trim().toLowerCase();
    let filtered = cards.filter(card => {
        const matchesSearch = !searchVal || card.Name.toLowerCase().includes(searchVal) ||
            card["Dex Number"].toString() === searchVal ||
            (card.Notes && card.Notes.toLowerCase().includes(searchVal)) ||
            `page ${card.Page}`.includes(searchVal);
        return matchesSearch && (!condVal || card.Condition === condVal) &&
            (!typeFilterVal || (card.Type || "Normal") === typeFilterVal);
    });

    const RARITY_ORDER = [
        "Mega Hyper Rare", "Hyper Rare", "Mega Attack Rare",
        "Special Illustration Rare", "Illustration Rare", "Ace Spec Rare",
        "Secret Rare", "Ultra Rare", "Double Rare", "Shiny Rare",
        "Reverse Holo", "Holofoil Rare", "Rare", "Normal"
    ];
    const rarityRank = card => {
        const rank = RARITY_ORDER.indexOf(card.Type || "Normal");
        return rank === -1 ? RARITY_ORDER.length : rank;
    };

    filtered.sort((a, b) => {
        if (sortBy === "location") {
            return a.Page - b.Page || rarityRank(a) - rarityRank(b) || a.Slot - b.Slot;
        }
        if (sortBy === "dex") {
            return (parseInt(a["Dex Number"]) || 9999) - (parseInt(b["Dex Number"]) || 9999) || rarityRank(a) - rarityRank(b);
        }
        if (sortBy === "rarity") {
            const rank = card => {
                const value = RARITY_ORDER.indexOf(card.Type || "Normal");
                return value === -1 ? RARITY_ORDER.length - 1 : value;
            };
            return rank(a) - rank(b) || a.Name.localeCompare(b.Name);
        }
        if (sortBy === "name") return a.Name.localeCompare(b.Name);
        if (sortBy === "date") return new Date(b["Date Added"] || 0) - new Date(a["Date Added"] || 0);
        return 0;
    });

    if (!showRepeated) {
        const seen = new Set();
        filtered = filtered.filter(card => {
            const key = card["Dex Number"] > 0 ? String(card["Dex Number"]) : `custom:${card.Name.toLowerCase()}`;
            if (seen.has(key)) return false;
            seen.add(key);
            return true;
        });
    }
    return filtered;
}

function renderCollectionTable() {
    const tableBody = document.getElementById("collection-table-body");
    const noCards = document.getElementById("no-cards-found");
    tableBody.innerHTML = "";
    const filtered = filterCollectionCards(collection, {
        searchVal: document.getElementById("list-search").value,
        condVal: document.getElementById("filter-condition").value,
        typeFilterVal: document.getElementById("filter-type").value,
        sortBy: document.getElementById("sort-by").value,
        showRepeated
    });
    if (filtered.length === 0) {
        noCards.classList.remove("hidden");
        return;
    }
    noCards.classList.add("hidden");
    
    // Populate table rows
    filtered.forEach(card => {
        const row = document.createElement("tr");
        row.style.cursor = "pointer";
        row.onclick = () => openCardModal(card.Page, card.Slot, card["Date Added"]);
        
        // Sprite frame thumbnail
        const spriteUrl = getCardImageSource(card);
        const spriteHtml = spriteUrl
            ? '<div class="table-sprite-frame"><img src="' + spriteUrl + '" alt="' + card.Name + '"></div>'
            : card["Catalog Card ID"]
                ? '<div class="table-sprite-frame"><span class="image-unavailable">A carregar imagem…</span></div>'
                : '<div class="table-sprite-frame"><div class="pokeball-circle" style="width:16px;height:16px;border-width:2px;margin:auto;"><div style="position:absolute;height:2px;left:-2px;right:-2px;top:5px;background-color:currentColor;"></div></div></div>';
        const rarityCls = getRarityClass(getCardRarity(card));
        // Count of cards sharing the same page+slot (stack size)
        const slotCount = collection.filter(c => c.Page === card.Page && c.Slot === card.Slot).length;
        const countBadgeHtml = slotCount > 1
            ? `<span class="table-count-badge">×${slotCount}</span>`
            : `<span class="table-count-single">1</span>`;

        row.innerHTML = `
            <td>
                <div class="table-pokemon-cell">
                    ${spriteHtml}
                    <span class="table-poke-name">${card.Name}</span>
                </div>
            </td>
            <td>${card["Dex Number"] > 0 ? `#${card["Dex Number"]}` : "-"}</td>
            <td>Page ${card.Page}, Slot ${card.Slot}</td>
            <td><span class="badge ${rarityCls}">${card.Type || "Normal"}</span></td>
            <td><span class="badge ${card.Condition}">${card.Condition}</span></td>
            <td>${countBadgeHtml}</td>
            <td><span class="text-secondary">${card.Notes || "-"}</span></td>
            <td class="text-secondary" style="font-size:12px;">${card["Date Added"] || "-"}</td>
            <td onclick="event.stopPropagation()">
                <button class="btn-icon-danger" title="Remove Card" onclick="deleteCardDirectly(${card.Page}, ${card.Slot}, '${card.Name.replace(/'/g, "\\'")}', '${card["Date Added"]}')">
                    <svg viewBox="0 0 24 24" width="16" height="16">
                        <path fill="currentColor" d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/>
                    </svg>
                </button>
            </td>
        `;
        const rarityBadge = row.cells[3].querySelector(".badge");
        rarityBadge.textContent = getCardRarity(card);
        rarityBadge.className = "badge " + getRarityClass(getCardRarity(card));
        const rank = calculateRank(card.Grade, card["Catalog Rarity"]);
        if (rank !== "No Rank") {
            const rankBadge = document.createElement("span");
            rankBadge.className = "rank-badge rank-" + rank.toLowerCase();
            rankBadge.textContent = rank;
            row.cells[3].appendChild(rankBadge);
        }
        const spriteFrame = row.querySelector(".table-sprite-frame");
        const tableImage = spriteFrame && spriteFrame.querySelector("img");
        if (tableImage) setCardImageFallback(tableImage, card);
        const deleteButton = row.querySelector(".btn-icon-danger");
        deleteButton.onclick = event => {
            event.stopPropagation();
            deleteCardDirectly(card.Page, card.Slot, card.Name, card["Date Added"], card["Copy ID"]);
        };
        tableBody.appendChild(row);
        if (!tableImage && card["Catalog Card ID"]) resolveCardArtwork(spriteFrame, card);
    });
}

function filterCollection() {
    renderCollectionTable();
}

// ==================== SHOW REPEATED TOGGLE ====================
let showRepeated = false;

function toggleRepeated() {
    showRepeated = !showRepeated;
    const btn = document.getElementById("toggle-repeated");
    const pill = btn.querySelector(".toggle-repeated-pill");
    if (showRepeated) {
        pill.textContent = "ON";
        pill.className = "toggle-repeated-pill on";
        btn.classList.add("active");
    } else {
        pill.textContent = "OFF";
        pill.className = "toggle-repeated-pill off";
        btn.classList.remove("active");
    }
    renderCollectionTable();
}

async function deleteCardDirectly(page, slot, name, dateAdded = null, copyId = null) {
    if (confirm(`Are you sure you want to remove ${capitalize(name)} from Page ${page}, Slot ${slot}?`)) {
        showToast("Removing card...", "info");
        try {
            const payload = { page, slot, name };
            if (dateAdded) payload.date_added = dateAdded;
            if (copyId) payload.copy_id = copyId;
            const res = await fetch("/api/remove", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });
            const data = await res.json();
            if (data.success) {
                let msg = `Removed ${capitalize(name)} successfully!`;
                let syncMsg = [];
                let isWarning = false;
                
                if (data.gsheet_synced) {
                    if (data.gsheet_success) {
                        syncMsg.push("Sheets Synced");
                    } else {
                        syncMsg.push("Sheets Failed");
                        isWarning = true;
                    }
                }
                if (data.firebase_synced) {
                    if (data.firebase_success) {
                        syncMsg.push("Firebase Synced");
                    } else {
                        syncMsg.push("Firebase Failed");
                        isWarning = true;
                    }
                }
                if (syncMsg.length > 0) {
                    msg += ` (${syncMsg.join(", ")})`;
                }
                
                showToast(msg, isWarning ? "warning" : "success");
                await loadCollection();
            } else {
                showToast(data.error || "Failed to remove card", "error");
            }
        } catch (e) {
            showToast("Network error removing card", "error");
        }
    }
}

// ==================== AUTOCOMPLETE & ADD CARD FORM ====================
function handleAutocomplete(query) {
    if (selectedPokemon && query.trim().toLowerCase() !== selectedPokemon.name.toLowerCase()) {
        selectedPokemon = null;
        selectedCatalogCard = null;
        document.getElementById("add-clear-set").disabled = true;
        catalogSearchRevision++;
        document.getElementById("add-set-options").replaceChildren();
        document.getElementById("add-set-status").textContent = "Select a Pokémon to search its printings.";
    }
    const box = document.getElementById("autocomplete-suggestions");
    box.innerHTML = "";
    autocompleteSelectedIndex = -1;
    
    const val = query.trim().toLowerCase();
    if (!val) {
        box.classList.add("hidden");
        return;
    }
    
    // Filter species list
    const matches = pokemonLookupList.filter(item => 
        item.name.includes(val) || item.id.toString() === val
    ).slice(0, 6);
    
    if (matches.length === 0) {
        // Show "no species found - press enter to add as custom"
        const item = document.createElement("div");
        item.className = "suggestion-item";
        item.innerHTML = `
            <div class="suggestion-info">
                <span class="suggestion-name">Add "${capitalize(query)}" as custom card</span>
                <span class="suggestion-dex">No pokedex ID matches</span>
            </div>
        `;
        item.onclick = () => selectPokemonCustom(query);
        box.appendChild(item);
        box.classList.remove("hidden");
        return;
    }
    
    matches.forEach((match, index) => {
        const item = document.createElement("div");
        item.className = "suggestion-item";
        item.dataset.index = index;
        
        const spriteUrl = `https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/${match.id}.png`;
        
        item.innerHTML = `
            <div class="suggestion-sprite">
                <img src="${spriteUrl}" alt="${match.name}" onerror="this.src=''">
            </div>
            <div class="suggestion-info">
                <span class="suggestion-name">${match.name}</span>
                <span class="suggestion-dex">#${match.id}</span>
            </div>
        `;
        
        item.onclick = () => selectPokemon(match);
        box.appendChild(item);
    });
    
    box.classList.remove("hidden");
    
    // Add arrow key navigation support
    const input = document.getElementById("add-search-input");
    input.onkeydown = (e) => {
        const items = box.querySelectorAll(".suggestion-item");
        if (e.key === "ArrowDown") {
            e.preventDefault();
            autocompleteSelectedIndex = (autocompleteSelectedIndex + 1) % items.length;
            highlightSuggestion(items);
        } else if (e.key === "ArrowUp") {
            e.preventDefault();
            autocompleteSelectedIndex = (autocompleteSelectedIndex - 1 + items.length) % items.length;
            highlightSuggestion(items);
        } else if (e.key === "Enter") {
            e.preventDefault();
            if (autocompleteSelectedIndex >= 0 && autocompleteSelectedIndex < items.length) {
                items[autocompleteSelectedIndex].click();
            } else if (items.length > 0) {
                items[0].click(); // Select first item if nothing highlighted
            } else {
                selectPokemonCustom(input.value);
            }
        }
    };
}

function highlightSuggestion(items) {
    items.forEach((item, index) => {
        if (index === autocompleteSelectedIndex) {
            item.classList.add("active-selection");
            item.scrollIntoView({ block: "nearest" });
        } else {
            item.classList.remove("active-selection");
        }
    });
}

async function selectPokemon(match) {
    selectedPokemon = match;
    selectedCatalogCard = null;
    catalogSelectionRevision++;
    document.getElementById("add-clear-set").disabled = true;
    searchCatalogCards(match.name, "add-set-options", ++catalogSearchRevision);
    document.getElementById("add-search-input").value = capitalize(match.name);
    document.getElementById("autocomplete-suggestions").classList.add("hidden");
    
    // Update card front preview
    document.getElementById("preview-name").textContent = capitalize(match.name);
    document.getElementById("preview-dex-id").textContent = `#${match.id}`;
    
    const previewImg = document.getElementById("preview-image");
    const previewPlaceholder = document.getElementById("preview-image-placeholder");
    
    previewImg.src = `https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/${match.id}.png`;
    setCardImageFallback(previewImg, { "Dex Number": match.id });
    previewImg.classList.remove("hidden");
    previewPlaceholder.classList.add("hidden");
    
    // Request suggested placement positions
    await fetchSuggestedPosition(match.id, match.name);
}

function selectPokemonCustom(query) {
    selectedPokemon = { name: query.trim().toLowerCase(), id: 0 };
    selectedCatalogCard = null;
    catalogSelectionRevision++;
    document.getElementById("add-clear-set").disabled = true;
    searchCatalogCards(selectedPokemon.name, "add-set-options", ++catalogSearchRevision);
    document.getElementById("add-search-input").value = capitalize(query);
    document.getElementById("autocomplete-suggestions").classList.add("hidden");
    
    // Preview updates
    document.getElementById("preview-name").textContent = capitalize(query);
    document.getElementById("preview-dex-id").textContent = "Custom";
    
    document.getElementById("preview-image").classList.add("hidden");
    document.getElementById("preview-image-placeholder").classList.remove("hidden");
    
    fetchSuggestedPosition(0, query);
}

async function fetchSuggestedPosition(id, name) {
    try {
        const res = await fetch(`/api/suggest-position?id=${id}&name=${encodeURIComponent(name)}`);
        if (res.ok) {
            const data = await res.json();
            
            // Show recommended position banner
            const banner = document.getElementById("suggested-banner");
            const posText = document.getElementById("suggested-position-text");
            
            posText.textContent = `Page ${data.page}, Slot ${data.slot} (${data.position_type})`;
            banner.classList.remove("hidden");
            
            // Set form values
            document.getElementById("add-page").value = data.page;
            document.getElementById("add-slot").value = data.slot;
            
            // Update preview details
            document.getElementById("preview-location-text").textContent = `Page ${data.page}, Slot ${data.slot}`;
            
            // Update occupation banner warnings
            const warningBanner = document.getElementById("slot-warning-banner");
            if (data.occupied) {
                const cardNames = data.occupied_cards.map(c => `${capitalize(c.name)} (#${c.dex || "Custom"})`).join(", ");
                document.getElementById("slot-occupied-info").innerHTML = `Occupied by <strong>${cardNames}</strong>`;
                warningBanner.classList.remove("hidden");
            } else {
                warningBanner.classList.add("hidden");
            }
        }
    } catch (e) {
        showToast("Error requesting placement suggestions", "error");
    }
}

// Form preview live mirroring
document.getElementById("add-condition").addEventListener("change", (e) => {
    const previewBadge = document.getElementById("preview-badge-cond");
    previewBadge.textContent = e.target.value;
    previewBadge.className = `badge ${e.target.value}`;
});

document.getElementById("add-notes").addEventListener("input", (e) => {
    const previewNotes = document.getElementById("preview-notes-text");
    previewNotes.textContent = e.target.value.trim() || "No notes added.";
});

function resetAddCardForm() {
    selectedPokemon = null;
    selectedCatalogCard = null;
    catalogSelectionRevision++;
    document.getElementById("add-clear-set").disabled = true;
    catalogSearchRevision++;
    document.getElementById("add-set-options").replaceChildren();
    document.getElementById("add-set-status").textContent = "Select a Pokémon to search its printings.";
    document.getElementById("add-search-input").value = "";
    document.getElementById("add-condition").value = "NM";
    document.getElementById("add-type").value = "Normal";
    document.getElementById("add-notes").value = "";
    document.getElementById("add-page").value = 1;
    document.getElementById("add-slot").value = 1;
    
    // Hide panels
    document.getElementById("suggested-banner").classList.add("hidden");
    document.getElementById("slot-warning-banner").classList.add("hidden");
    document.getElementById("autocomplete-suggestions").classList.add("hidden");
    
    // Restore empty preview card backing state
    document.getElementById("preview-name").textContent = "Select Pokémon";
    document.getElementById("preview-dex-id").textContent = "#???";
    document.getElementById("preview-badge-cond").textContent = "NM";
    document.getElementById("preview-badge-cond").className = "badge NM";
    
    updatePreviewRarity("Normal");
    
    const container = document.querySelector(".card-preview-area .pokemon-card-container");
    if (container) {
        container.className = "pokemon-card-container";
    }
    
    document.getElementById("preview-location-text").textContent = "Page 1, Slot 1";
    document.getElementById("preview-notes-text").textContent = "No notes added.";
    
    document.getElementById("preview-image").classList.add("hidden");
    document.getElementById("preview-image-placeholder").classList.remove("hidden");
}

async function submitAddCard() {
    if (!selectedPokemon) {
        showToast("Please search and select a Pokémon species first!", "error");
        return;
    }
    
    const pageVal = parseInt(document.getElementById("add-page").value);
    const slotVal = parseInt(document.getElementById("add-slot").value);
    const typeVal = document.getElementById("add-type").value;
    const condVal = document.getElementById("add-condition").value;
    const notesVal = document.getElementById("add-notes").value.trim();
    
    if (isNaN(pageVal) || isNaN(slotVal) || pageVal < 1 || slotVal < 1) {
        showToast("Invalid Page or Slot coordinates specified", "error");
        return;
    }
    
    showToast("Saving card to binder...", "info");
    
    try {
        const res = await fetch("/api/add", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                name: selectedPokemon.name,
                dex_id: selectedPokemon.id,
                page: pageVal,
                slot: slotVal,
                type: typeVal,
                condition: condVal,
                notes: notesVal,
                catalog_card_id: selectedCatalogCard ? selectedCatalogCard.id : "",
                set_id: selectedCatalogCard ? selectedCatalogCard.setId : "",
                set_name: selectedCatalogCard ? selectedCatalogCard.setName : "",
                card_number: selectedCatalogCard ? selectedCatalogCard.number : "",
                catalog_rarity: selectedCatalogCard ? selectedCatalogCard.rarity : "",
                image_url: selectedCatalogCard ? selectedCatalogCard.imageUrl : "",
                image_source: selectedCatalogCard ? selectedCatalogCard.imageSource || "" : "",
                image_match_id: selectedCatalogCard ? selectedCatalogCard.imageMatchId || "" : ""
            })
        });
        
        const data = await res.json();
        if (data.success) {
            let msg = `Added ${capitalize(selectedPokemon.name)} to Page ${pageVal}, Slot ${slotVal}!`;
            let syncMsg = [];
            let isWarning = false;
            
            if (data.gsheet_synced) {
                if (data.gsheet_success) {
                    syncMsg.push("Sheets Synced");
                } else {
                    syncMsg.push("Sheets Failed");
                    isWarning = true;
                }
            }
            if (data.firebase_synced) {
                if (data.firebase_success) {
                    syncMsg.push("Firebase Synced");
                } else {
                    syncMsg.push("Firebase Failed");
                    isWarning = true;
                }
            }
            if (syncMsg.length > 0) {
                msg += ` (${syncMsg.join(", ")})`;
            }
            
            showToast(msg, isWarning ? "warning" : "success");
            
            // Reload database
            await loadCollection();
            
            // Navigate back to binder
            currentPage = pageVal;
            switchTab("binder");
        } else {
            showToast(data.error || "Failed to save card entry", "error");
        }
    } catch (e) {
        showToast("Network error submitting card entry", "error");
    }
}

// ==================== BINDER DETAIL MODALS ====================
let modalActiveCard = null; // Storing card index when detail is active

function openCardModal(page, slot, targetIdentifier = null) {
    // Filter items in this slot, sorted by rarity (most rare first)
    const RARITY_ORDER_MODAL = [
        "Mega Hyper Rare", "Hyper Rare", "Mega Attack Rare",
        "Special Illustration Rare", "Illustration Rare", "Ace Spec Rare",
        "Secret Rare", "Ultra Rare", "Double Rare", "Shiny Rare",
        "Reverse Holo", "Holofoil Rare", "Rare", "Normal"
    ];
    const slotCards = collection
        .filter(c => c.Page === page && c.Slot === slot)
        .sort((a, b) => {
            const ra = RARITY_ORDER_MODAL.indexOf(a.Type || "Normal");
            const rb = RARITY_ORDER_MODAL.indexOf(b.Type || "Normal");
            return (ra === -1 ? RARITY_ORDER_MODAL.length : ra) - (rb === -1 ? RARITY_ORDER_MODAL.length : rb);
        });
    if (slotCards.length === 0) return;
    
    // If a targetIdentifier was specified, find by Date Added first, fallback to Name
    let activeCard = slotCards[0];
    if (targetIdentifier) {
        let found = slotCards.find(c => c["Copy ID"] === targetIdentifier) || slotCards.find(c => c["Date Added"] === targetIdentifier);
        if (!found) {
            found = slotCards.find(c => c.Name.toLowerCase() === targetIdentifier.toLowerCase());
        }
        if (found) activeCard = found;
    }
    
    modalActiveCard = activeCard;
    modalNotesRevision++;
    loadModalCatalogOptions(activeCard);
    document.getElementById("modal-edit-form").classList.add("hidden");
    document.getElementById("modal-edit-details").setAttribute("aria-expanded", "false");
    
    const modal = document.getElementById("card-modal");
    
    // Fill text properties
    document.getElementById("modal-details-title").textContent = capitalize(activeCard.Name);
    document.getElementById("modal-pokemon-location").textContent = `Página ${activeCard.Page} · Espaço ${activeCard.Slot}`;
    document.getElementById("modal-pokemon-date").textContent = activeCard["Date Added"] ? `Adicionada em ${activeCard["Date Added"]}` : "Data não disponível";
    const notes = document.getElementById("modal-pokemon-notes");
    notes.value = String(activeCard.Notes || "");
    document.getElementById("modal-notes-status").textContent = "";
    renderModalGrade(activeCard);
    
    // Set type (rarity) badge
    const typeBadge = document.getElementById("modal-pokemon-type");
    if (typeBadge) {
        typeBadge.textContent = getCardRarity(activeCard);
        const rarityCls = getRarityClass(getCardRarity(activeCard));
        typeBadge.className = `modal-pokemon-type badge ${rarityCls} rarity-${rarityCls.toLowerCase()}`;
    }
    
    // Set condition badge
    const badge = document.getElementById("modal-pokemon-condition");
    badge.textContent = activeCard.Condition || "—";
    badge.className = activeCard.Condition ? `badge ${activeCard.Condition}` : "";
    
    // Manage stacked items UI list
    const stackedSection = document.getElementById("modal-stacked-section");
    const stackedList = document.getElementById("modal-stacked-list");
    stackedList.innerHTML = "";
    
    if (slotCards.length > 1) {
        stackedSection.classList.remove("hidden");
        slotCards.forEach(c => {
            const item = document.createElement("div");
            item.className = "stacked-card-item";
            if (c === activeCard) {
                item.classList.add("selected");
            }
            
            const rCls = getRarityClass(c.Type || "Normal");
            item.innerHTML = `
                <div class="stacked-info">
                    <span class="badge ${c.Condition}" style="padding:2px 4px;font-size:9px;margin-right:4px;">${c.Condition}</span>
                    <span class="badge ${rCls}" style="padding:2px 4px;font-size:9px;margin-right:8px;">${c.Type || "Normal"}</span>
                    <span class="stacked-notes">${c.Notes || ""}</span>
                </div>
                <span style="font-size:10px;color:var(--text-muted);">${c["Date Added"].split(" ")[0]}</span>
            `;
            
            // Switch active view card in modal on stacked item click
            item.onclick = () => {
                openCardModal(page, slot, c["Date Added"]);
            };
            
            stackedList.appendChild(item);
        });
    } else {
        stackedSection.classList.add("hidden");
    }
    
    modal.classList.remove("hidden");
}

async function saveModalNotes() {
    if (!modalActiveCard) return;
    const card = modalActiveCard;
    const notes = document.getElementById("modal-pokemon-notes").value.trim();
    if (notes === String(card.Notes || "").trim()) return;
    const revision = ++modalNotesRevision;
    const status = document.getElementById("modal-notes-status");
    status.textContent = "A guardar…";
    try {
        const response = await fetch("/api/update-card", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                copy_id: card["Copy ID"] || "", page: card.Page, slot: card.Slot,
                name: card.Name, date_added: card["Date Added"], notes
            })
        });
        const data = await response.json();
        if (!data.success) throw new Error(data.error || "Não foi possível guardar a nota.");
        card.Notes = notes;
        if (revision === modalNotesRevision) status.textContent = "Nota guardada.";
        await loadCollection();
    } catch (error) {
        if (revision === modalNotesRevision) status.textContent = error.message || "Não foi possível guardar a nota.";
    }
}

function closeCardModal(e) {
    document.getElementById("card-modal").classList.add("hidden");
    modalActiveCard = null;
    modalSavedCatalogCard = null;
    modalSelectedCatalogCard = null;
    modalCatalogRevision++;
    modalSelectionRevision++;
    modalArtworkRevision++;
    modalNotesRevision++;
    modalArtworkUrl = "";
    modalArtworkIsCard = false;
    document.getElementById("modal-image-viewer").classList.add("hidden");
}

document.addEventListener("keydown", event => {
    if (event.key !== "Escape") return;
    const viewer = document.getElementById("modal-image-viewer");
    if (viewer && !viewer.classList.contains("hidden")) closeModalImageViewer();
    else if (!document.getElementById("card-modal").classList.contains("hidden")) closeCardModal();
});

async function deleteCardFromModal() {
    if (!modalActiveCard) return;
    
    const card = modalActiveCard;
    if (confirm(`Remove ${capitalize(card.Name)} from Page ${card.Page}, Slot ${card.Slot}?`)) {
        closeCardModal();
        showToast("Removing card from collection...", "info");
        
        try {
            const res = await fetch("/api/remove", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    page: card.Page,
                    slot: card.Slot,
                    name: card.Name,
                    date_added: card["Date Added"],
                    copy_id: card["Copy ID"] || ""
                })
            });
            const data = await res.json();
            if (data.success) {
                let msg = `Successfully removed ${capitalize(card.Name)}!`;
                let syncMsg = [];
                let isWarning = false;
                
                if (data.gsheet_synced) {
                    if (data.gsheet_success) {
                        syncMsg.push("Sheets Synced");
                    } else {
                        syncMsg.push("Sheets Failed");
                        isWarning = true;
                    }
                }
                if (data.firebase_synced) {
                    if (data.firebase_success) {
                        syncMsg.push("Firebase Synced");
                    } else {
                        syncMsg.push("Firebase Failed");
                        isWarning = true;
                    }
                }
                if (syncMsg.length > 0) {
                    msg += ` (${syncMsg.join(", ")})`;
                }
                
                showToast(msg, isWarning ? "warning" : "success");
                await loadCollection();
            } else {
                showToast(data.error || "Failed to remove card", "error");
            }
        } catch (e) {
            showToast("Network error removing card from database", "error");
        }
    }
}

// ==================== SETTINGS ACTIONS ====================
async function saveSettings() {
    const rows = parseInt(document.getElementById("settings-rows").value);
    const cols = parseInt(document.getElementById("settings-cols").value);
    const mode = document.getElementById("settings-mode").value;
    const gsheet_enabled = document.getElementById("settings-gsheet-toggle").checked;
    const gsheet_name = document.getElementById("settings-gsheet-name").value.trim();
    
    if (isNaN(rows) || isNaN(cols) || rows < 1 || cols < 1) {
        showToast("Rows and columns must be at least 1!", "error");
        return;
    }
    
    showToast("Saving configurations...", "info");
    
    try {
        const res = await fetch("/api/settings", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                rows,
                cols,
                mode,
                gsheet_enabled,
                gsheet_name
            })
        });
        
        const data = await res.json();
        if (data.success) {
            showToast("Configuration saved successfully!", "success");
            await loadSettings();
            await loadCollection(); // Grid recalculations might adapt collections layout
        } else {
            showToast(data.error || "Failed to save configurations", "error");
        }
    } catch (e) {
        showToast("Network error saving configurations", "error");
    }
}

function toggleGsheetNameInput() {
    const isChecked = document.getElementById("settings-gsheet-toggle").checked;
    const group = document.getElementById("gsheet-name-group");
    
    if (isChecked) {
        group.style.opacity = "1";
        group.style.pointerEvents = "all";
    } else {
        group.style.opacity = "0.5";
        group.style.pointerEvents = "none";
    }
}

async function triggerManualSync() {
    const sidebarBtn = document.getElementById("sync-now-sidebar-btn");
    const settingsBtn = document.getElementById("sync-now-settings-btn");
    const dot = document.querySelector(".status-dot");
    const txt = document.querySelector(".status-text");
    
    if (sidebarBtn) sidebarBtn.disabled = true;
    if (settingsBtn) settingsBtn.disabled = true;
    
    // Set status to syncing
    dot.className = "status-dot loading";
    txt.textContent = "Syncing...";
    showToast("Synchronizing collection...", "info");
    
    try {
        const res = await fetch("/api/sync", { method: "POST" });
        const data = await res.json();
        
        if (data.success) {
            showToast(data.message || "Sync completed successfully!", "success");
        } else {
            showToast(data.error || "Sync failed", "error");
        }
    } catch (e) {
        showToast("Sync error occurred", "error");
    } finally {
        if (sidebarBtn) sidebarBtn.disabled = false;
        if (settingsBtn) settingsBtn.disabled = false;
        dot.className = "status-dot online";
        txt.textContent = "Connected locally";
    }
}

function toggleGuide() {
    const btn = document.querySelector(".guide-toggle");
    const content = document.getElementById("guide-content");
    
    btn.classList.toggle("open");
    content.classList.toggle("hidden");
}

// ==================== HELPER FUNCTIONS ====================
function capitalize(str) {
    if (!str) return "";
    return str.charAt(0).toUpperCase() + str.slice(1);
}

function showToast(msg, type = "info") {
    const toast = document.getElementById("notification-toast");
    const msgEl = toast.querySelector(".toast-message");
    
    // Clear type classes
    toast.className = "notification-toast";
    
    // Set text
    msgEl.textContent = msg;
    
    // Add type class
    toast.classList.add(type);
    
    // Show toast
    toast.classList.remove("hidden");
    
    // Clear current timeout if exists to prevent early hides
    if (window.toastTimeout) {
        clearTimeout(window.toastTimeout);
    }
    
    // Set automatic hide
    window.toastTimeout = setTimeout(() => {
        toast.classList.add("hidden");
    }, 4000);
}

// ==================== BINDER COVER HELPER FUNCTIONS ====================
function toggleCoverEditor(show) {
    const panel = document.getElementById("cover-editor-panel");
    if (show) {
        panel.classList.remove("hidden");
        toggleCoverSourceInputs(); // Check inputs visibility on open
    } else {
        panel.classList.add("hidden");
    }
}

function toggleCoverSourceInputs() {
    const source = document.getElementById("cover-edit-source").value;
    
    // Hide all
    document.getElementById("cover-group-pokemon").classList.add("hidden");
    document.getElementById("cover-group-url").classList.add("hidden");
    document.getElementById("cover-group-upload").classList.add("hidden");
    
    // Show selected
    if (source === "pokemon") {
        document.getElementById("cover-group-pokemon").classList.remove("hidden");
    } else if (source === "url") {
        document.getElementById("cover-group-url").classList.remove("hidden");
    } else if (source === "upload") {
        document.getElementById("cover-group-upload").classList.remove("hidden");
    }
}

async function saveCoverConfig() {
    const title = document.getElementById("cover-edit-title").value.trim();
    const subtitle = document.getElementById("cover-edit-subtitle").value.trim();
    const owner = document.getElementById("cover-edit-owner").value.trim();
    const source = document.getElementById("cover-edit-source").value;
    const color = document.getElementById("cover-edit-color").value;
    
    const dex = parseInt(document.getElementById("cover-edit-dex").value) || 0;
    const url = document.getElementById("cover-edit-url").value.trim();
    const fileInput = document.getElementById("cover-edit-file");
    
    showToast("Saving cover settings...", "info");
    
    async function submitSettings(extraData = {}) {
        try {
            const res = await fetch("/api/settings", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    cover_title: title,
                    cover_subtitle: subtitle,
                    cover_owner: owner,
                    cover_source: source,
                    cover_color: color,
                    cover_featured_dex: dex,
                    cover_image_url: url,
                    ...extraData
                })
            });
            const data = await res.json();
            if (data.success) {
                showToast("Binder cover customized successfully!", "success");
                await loadSettings();
                renderBinderGrid();
            } else {
                showToast(data.error || "Failed to save cover changes", "error");
            }
        } catch (e) {
            showToast("Network error saving cover settings", "error");
        }
    }
    
    if (source === "upload" && fileInput.files.length > 0) {
        const file = fileInput.files[0];
        const reader = new FileReader();
        reader.onload = async function(e) {
            const base64Data = e.target.result;
            try {
                const uploadRes = await fetch("/api/upload-cover-image", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ image_data: base64Data })
                });
                
                if (!uploadRes.ok) {
                    if (uploadRes.status === 404) {
                        showToast("Restart required: Please close the server terminal and double-click 'run_server.bat'!", "error");
                    } else {
                        const text = await uploadRes.text();
                        showToast(`Upload failed (${uploadRes.status}): ${text.substring(0, 40)}`, "error");
                    }
                    return;
                }
                
                const uploadData = await uploadRes.json();
                if (uploadData.success) {
                    await submitSettings();
                } else {
                    showToast(uploadData.error || "Failed to upload image file", "error");
                }
            } catch (err) {
                showToast("Network error uploading cover image", "error");
            }
        };
        reader.readAsDataURL(file);
    } else {
        await submitSettings();
    }
}

function hexToRgbA(hex, alpha) {
    let c;
    if(/^#([A-Fa-f0-9]{3}){1,2}$/.test(hex)){
        c= hex.substring(1).split('');
        if(c.length === 3){
            c= [c[0], c[0], c[1], c[1], c[2], c[2]];
        }
        c= '0x'+c.join('');
        return 'rgba('+[(c>>16)&255, (c>>8)&255, c&255].join(',')+','+alpha+')';
    }
    return 'rgba(239, 68, 68, ' + alpha + ')'; // Fallback to red
}

function escapeHtml(unsafe) {
    if (!unsafe) return "";
    return unsafe
         .replace(/&/g, "&amp;")
         .replace(/</g, "&lt;")
         .replace(/>/g, "&gt;")
         .replace(/"/g, "&quot;")
         .replace(/'/g, "&#039;");
}

// Start heartbeat loop (every 3 seconds) to let backend know the app is active
setInterval(() => {
    fetch('/api/heartbeat', { method: 'POST' }).catch(() => {});
}, 3000);

// ==================== TRAINER PROFILE HELPER FUNCTIONS ====================
function renderTrainerProfile() {
    const nameEl = document.getElementById("sidebar-trainer-name");
    const avatarEl = document.getElementById("sidebar-trainer-avatar");
    
    if (!nameEl || !avatarEl) return;
    
    // Username
    const username = appConfig.username || "Trainer";
    nameEl.textContent = username;
    
    // Avatar
    const source = appConfig.profile_picture_source || "pokemon";
    if (source === "pokemon") {
        const dexId = appConfig.profile_featured_dex || 25;
        avatarEl.src = `https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/${dexId}.png`;
    } else if (source === "url") {
        avatarEl.src = appConfig.profile_image_url || "/profile_image.png";
    } else if (source === "upload") {
        avatarEl.src = `/profile_image.png?t=${new Date().getTime()}`;
    } else if (source === "base64" || source === "base64") {
        const base64Data = appConfig.profile_image_base64;
        if (base64Data) {
            avatarEl.src = `data:image/jpeg;base64,${base64Data}`;
        }
    }
}

function toggleProfileSourceInputs() {
    const source = document.getElementById("settings-profile-source").value;
    
    // Hide all
    document.getElementById("profile-group-pokemon").classList.add("hidden");
    document.getElementById("profile-group-url").classList.add("hidden");
    document.getElementById("profile-group-upload").classList.add("hidden");
    
    // Show selected
    if (source === "pokemon") {
        document.getElementById("profile-group-pokemon").classList.remove("hidden");
    } else if (source === "url") {
        document.getElementById("profile-group-url").classList.remove("hidden");
    } else if (source === "upload") {
        document.getElementById("profile-group-upload").classList.remove("hidden");
    }
}

async function saveProfileSettings() {
    const username = document.getElementById("settings-username").value.trim();
    const source = document.getElementById("settings-profile-source").value;
    const dex = parseInt(document.getElementById("settings-profile-dex").value) || 25;
    const url = document.getElementById("settings-profile-url").value.trim();
    const fileInput = document.getElementById("settings-profile-file");
    
    if (!username) {
        showToast("Trainer username cannot be empty!", "error");
        return;
    }
    
    showToast("Saving trainer profile...", "info");
    
    async function submitProfile(extraData = {}) {
        try {
            const res = await fetch("/api/settings", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    username: username,
                    profile_picture_source: source,
                    profile_featured_dex: dex,
                    profile_image_url: url,
                    ...extraData
                })
            });
            const data = await res.json();
            if (data.success) {
                showToast("Trainer profile updated successfully!", "success");
                await loadSettings();
            } else {
                showToast(data.error || "Failed to save profile changes", "error");
            }
        } catch (e) {
            showToast("Network error saving profile settings", "error");
        }
    }
    
    if (source === "upload" && fileInput.files.length > 0) {
        const file = fileInput.files[0];
        const reader = new FileReader();
        reader.onload = async function(e) {
            const base64Data = e.target.result;
            try {
                const uploadRes = await fetch("/api/upload-profile-image", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ image_data: base64Data })
                });
                
                if (!uploadRes.ok) {
                    const text = await uploadRes.text();
                    showToast(`Upload failed: ${text.substring(0, 40)}`, "error");
                    return;
                }
                
                const uploadData = await uploadRes.json();
                if (uploadData.success) {
                    await submitProfile();
                } else {
                    showToast(uploadData.error || "Failed to upload profile photo", "error");
                }
            } catch (err) {
                showToast("Network error uploading profile image", "error");
            }
        };
        reader.readAsDataURL(file);
    } else {
        await submitProfile();
    }
}

// Get general CSS styling class from TCG Rarity name
function getRarityClass(rarity) {
    if (!rarity) return "Normal";
    const r = rarity.trim().toLowerCase();
    if (r === "normal") return "Normal";
    if (r === "rare") return "Rare";
    if (r === "holo" || r === "holofoil rare" || r === "holofoil") return "Holo";
    if (r === "reverse" || r === "r holo" || r.startsWith("reverse ")) return "Reverse";
    if (r === "double rare" || r === "double") return "Double";
    if (r === "ultra rare" || r === "ultra") return "Ultra";
    if (r === "illustration rare" || r === "ilustration rare" || r === "illustration" || r === "ir") return "Illustration";
    if (r === "special illustration rare" || r === "special" || r === "sir") return "Special";
    if (r === "secret rare" || r === "secret" || r === "sr") return "Secret";
    if (r === "hyper rare" || r === "hyper" || r === "hr") return "Hyper";
    if (r === "shiny rare" || r === "shiny" || r === "sh") return "Shiny";
    if (r === "ace spec rare" || r === "ace spec" || r === "ace") return "Ace";
    if (r === "mega attack rare" || r === "mega attack" || r === "mar") return "MegaAttack";
    if (r === "mega hyper rare" || r === "mega hyper" || r === "mhr") return "MegaHyper";
    return rarity.split(" ")[0]; // Fallback
}

// Get abbreviated TCG Rarity name for small cards/badges
function getRarityAbbrev(rarity) {
    if (!rarity) return "Normal";
    const r = rarity.trim().toLowerCase();
    if (r === "normal") return "Normal";
    if (r === "rare") return "Rare";
    if (r === "holo" || r === "holofoil rare" || r === "holofoil") return "Holo";
    if (r === "reverse" || r === "r holo" || r.startsWith("reverse ")) return "R Holo";
    if (r === "double rare" || r === "double") return "DR";
    if (r === "ultra rare" || r === "ultra") return "UR";
    if (r === "secret rare" || r === "secret") return "SR";
    if (r === "ace spec rare" || r === "ace spec" || r === "ace") return "Ace";
    if (r === "illustration rare" || r === "illustration" || r === "ir") return "IR";
    if (r === "special illustration rare" || r === "special" || r === "sir") return "SIR";
    if (r === "mega attack rare" || r === "mega attack" || r === "mar") return "MAR";
    if (r === "hyper rare" || r === "hyper" || r === "hr") return "HR";
    if (r === "mega hyper rare" || r === "mega hyper" || r === "mhr") return "MHR";
    if (r === "shiny rare" || r === "shiny" || r === "sh") return "Shiny R";
    return rarity;
}

// Live rarity preview selection
function updatePreviewRarity(value) {
    const container = document.querySelector(".card-preview-area .pokemon-card-container");
    if (!container) return;
    
    // Remove existing rarity classes
    container.classList.remove("Normal", "Rare", "Holo", "Reverse", "Double", "Ultra", "Illustration", "Special", "Secret", "Hyper", "Shiny", "Ace", "MegaAttack", "MegaHyper", previewRarityClass);
    
    // Get class name using helper
    const cls = getRarityClass(value);
    if (cls !== "Normal") {
        container.classList.add(cls);
    }
    previewRarityClass = cls;
    
    // Also update the badge element text and class
    const badge = document.getElementById("preview-badge-type");
    if (badge) {
        badge.textContent = value;
        badge.className = `badge ${cls}`;
    }
}

// ==================== FIREBASE SYNC ACTIONS ====================
async function loginFirebasePC() {
    // Works both from overlay and from profile section
    const emailEl = document.getElementById('login-email') || document.getElementById('settings-firebase-email');
    const passwordEl = document.getElementById('login-password') || document.getElementById('settings-firebase-password');
    const email = emailEl ? emailEl.value.trim() : '';
    const password = passwordEl ? passwordEl.value : '';
    
    if (!email || !password) {
        showToast('Email and password are required', 'error');
        const errEl = document.getElementById('login-error');
        if (errEl) { errEl.textContent = 'Email and password are required.'; errEl.classList.remove('hidden'); }
        return;
    }
    
    // Show loading state on the overlay button
    const btnText = document.getElementById('login-btn-text');
    const btnSpinner = document.getElementById('login-btn-spinner');
    const submitBtn = document.getElementById('login-submit-btn');
    if (btnText) btnText.classList.add('hidden');
    if (btnSpinner) btnSpinner.classList.remove('hidden');
    if (submitBtn) submitBtn.disabled = true;
    
    showToast('Connecting to Firebase...', 'info');
    
    try {
        const res = await fetch('/api/firebase/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password })
        });
        
        const data = await res.json();
        if (data.success) {
            showToast(data.message || 'Connected to Firebase!', 'success');
            appConfig = data.config;
            updateFirebaseUI(appConfig);
            await loadCollection();
        } else {
            showToast(data.error || 'Login failed', 'error');
            const errEl = document.getElementById('login-error');
            if (errEl) { errEl.textContent = data.error || 'Login failed. Check your credentials.'; errEl.classList.remove('hidden'); }
        }
    } catch (e) {
        showToast('Network error during Firebase login', 'error');
        const errEl = document.getElementById('login-error');
        if (errEl) { errEl.textContent = 'Could not reach server. Is the app running?'; errEl.classList.remove('hidden'); }
    } finally {
        if (btnText) btnText.classList.remove('hidden');
        if (btnSpinner) btnSpinner.classList.add('hidden');
        if (submitBtn) submitBtn.disabled = false;
    }
}

async function logoutFirebasePC() {
    if (!confirm("Are you sure you want to disconnect from Firebase? This will stop cloud syncing.")) return;
    
    showToast("Disconnecting...", "info");
    
    try {
        const res = await fetch("/api/firebase/logout", { method: "POST" });
        const data = await res.json();
        
        if (data.success) {
            showToast(data.message || "Disconnected from Firebase", "success");
            await loadSettings(); // Reload settings to update UI
        } else {
            showToast(data.error || "Logout failed", "error");
        }
    } catch (e) {
        showToast("Network error during Firebase logout", "error");
    }
}

function updateFirebaseUI(config) {
    // Firebase sign-in is optional; local collection access is always available.
    const overlay = document.getElementById('login-overlay');
    if (overlay) overlay.classList.add('hidden');

    // --- Profile section Firebase controls ---
    const profileStatus = document.getElementById('profile-firebase-status');
    const profileConnect = document.getElementById('profile-firebase-connect');
    const profileEmail = document.getElementById('profile-linked-email');
    if (profileStatus) {
        if (config.firebase_enabled) {
            profileStatus.classList.remove('hidden');
            profileConnect?.classList.add('hidden');
            if (profileEmail) profileEmail.textContent = config.firebase_email || 'Active User';
        } else {
            profileStatus.classList.add('hidden');
            profileConnect?.classList.remove('hidden');
        }
    }

    // --- Dashboard sync badge ---
    updateDashboardStats();
}

function showFirebaseLogin() {
    document.getElementById('login-error')?.classList.add('hidden');
    document.getElementById('login-overlay')?.classList.remove('hidden');
}

function continueLocalOnly() {
    document.getElementById('login-overlay')?.classList.add('hidden');
}

// Login overlay submit (called by the overlay button)
async function submitLoginOverlay() {
    await loginFirebasePC();
}

// Allow pressing Enter in the login overlay password field to submit
document.addEventListener('DOMContentLoaded', () => {
    const pwField = document.getElementById('login-password');
    if (pwField) {
        pwField.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') submitLoginOverlay();
        });
    }
    const emailField = document.getElementById('login-email');
    if (emailField) {
        emailField.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') document.getElementById('login-password')?.focus();
        });
    }
});
