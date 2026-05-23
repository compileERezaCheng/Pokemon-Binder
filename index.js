// ==================== STATE MANAGEMENT ====================
let appConfig = {
    rows: 3,
    cols: 3,
    mode: "dex",
    gsheet_enabled: false,
    gsheet_name: ""
};

let collection = [];
let pokemonLookupList = []; // Array of {id, name}
let activeTab = "binder";
let currentPage = 0;
let selectedPokemon = null; // Currently selected in form

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
    
    // Update setup toggle visually
    toggleGsheetNameInput();
    
    // Close modal on Escape key press
    document.addEventListener("keydown", (e) => {
        if (e.key === "Escape") {
            closeCardModal();
        }
    });
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
            
            // Cache format is { "name": ID, "ID": "name" }
            // Filter names and map to an array
            const temp = [];
            for (const key in cacheData) {
                if (isNaN(key)) {
                    temp.push({
                        name: key,
                        id: cacheData[key]
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
    document.getElementById(`nav-btn-${tabId}`).classList.add("active");
    
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
    
    // Google Sheets integration status badge
    const syncBadge = document.getElementById("stat-sync-mode");
    if (appConfig.gsheet_enabled) {
        syncBadge.textContent = `Sync Active (${appConfig.gsheet_name || "unset"})`;
        syncBadge.className = "badge synced";
    } else {
        syncBadge.textContent = "Local Only";
        syncBadge.className = "badge disabled";
    }
}

// ==================== BINDER GRID RENDERING ====================
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
    // Max page spread boundary should match the next spread
    let maxPage = Math.max(highestPage, rightPageNum);
    if (maxPage % 2 !== 0) maxPage += 1;
    document.getElementById("binder-max-page").textContent = maxPage;
    
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
            
            const stackedCards = slotMap[slot] || [];
            
            if (stackedCards.length > 0) {
                // Occupied slot: render card top
                const topCard = stackedCards[0];
                
                const cardContainer = document.createElement("div");
                cardContainer.className = "pokemon-card-container";
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
                
                if (topCard["Dex Number"] > 0) {
                    const img = document.createElement("img");
                    img.src = `https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/${topCard["Dex Number"]}.png`;
                    img.alt = topCard.Name;
                    img.loading = "lazy";
                    frame.appendChild(img);
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
                spec.innerHTML = `<span>Condition:</span><span class="badge ${topCard.Condition}">${topCard.Condition}</span>`;
                body.appendChild(spec);
                
                if (topCard.Notes) {
                    const notesPreview = document.createElement("div");
                    notesPreview.className = "card-notes-preview";
                    notesPreview.textContent = topCard.Notes;
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
                        <span>Trainer: <strong>${escapeHtml(appConfig.cover_owner || "Dr4g0n")}</strong></span>
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
                    <span>Trainer: <strong>${escapeHtml(appConfig.cover_owner || "Dr4g0n")}</strong></span>
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
                        <input type="text" id="cover-edit-owner" value="${escapeHtml(appConfig.cover_owner || "Dr4g0n")}">
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
function renderCollectionTable() {
    const tableBody = document.getElementById("collection-table-body");
    const noCards = document.getElementById("no-cards-found");
    tableBody.innerHTML = "";
    
    const searchVal = document.getElementById("list-search").value.trim().toLowerCase();
    const condVal = document.getElementById("filter-condition").value;
    const sortBy = document.getElementById("sort-by").value;
    
    // Filter collection array
    let filtered = collection.filter(card => {
        const matchesSearch = !searchVal || 
            card.Name.toLowerCase().includes(searchVal) || 
            card["Dex Number"].toString() === searchVal || 
            (card.Notes && card.Notes.toLowerCase().includes(searchVal)) ||
            `page ${card.Page}`.includes(searchVal);
            
        const matchesCond = !condVal || card.Condition === condVal;
        
        return matchesSearch && matchesCond;
    });
    
    // Sort array
    filtered.sort((a, b) => {
        if (sortBy === "location") {
            if (a.Page !== b.Page) return a.Page - b.Page;
            return a.Slot - b.Slot;
        } else if (sortBy === "dex") {
            const dexA = parseInt(a["Dex Number"]) || 9999;
            const dexB = parseInt(b["Dex Number"]) || 9999;
            return dexA - dexB;
        } else if (sortBy === "name") {
            return a.Name.localeCompare(b.Name);
        } else if (sortBy === "date") {
            const dateA = new Date(a["Date Added"] || 0);
            const dateB = new Date(b["Date Added"] || 0);
            return dateB - dateA; // Descending
        }
        return 0;
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
        row.onclick = () => openCardModal(card.Page, card.Slot, card.Name);
        
        // Sprite frame thumbnail
        const spriteUrl = card["Dex Number"] > 0 
            ? `https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/${card["Dex Number"]}.png`
            : "";
        const spriteHtml = spriteUrl 
            ? `<div class="table-sprite-frame"><img src="${spriteUrl}" alt="${card.Name}"></div>` 
            : `<div class="table-sprite-frame"><div class="pokeball-circle" style="width:16px;height:16px;border-width:2px;margin:auto;"><div style="position:absolute;height:2px;left:-2px;right:-2px;top:5px;background-color:currentColor;"></div></div></div>`;
            
        row.innerHTML = `
            <td>
                <div class="table-pokemon-cell">
                    ${spriteHtml}
                    <span class="table-poke-name">${card.Name}</span>
                </div>
            </td>
            <td>${card["Dex Number"] > 0 ? `#${card["Dex Number"]}` : "-"}</td>
            <td>Page ${card.Page}, Slot ${card.Slot}</td>
            <td><span class="badge ${card.Condition}">${card.Condition}</span></td>
            <td><span class="text-secondary">${card.Notes || "-"}</span></td>
            <td class="text-secondary" style="font-size:12px;">${card["Date Added"] || "-"}</td>
            <td onclick="event.stopPropagation()">
                <button class="btn-icon-danger" title="Remove Card" onclick="deleteCardDirectly(${card.Page}, ${card.Slot}, '${card.Name}')">
                    <svg viewBox="0 0 24 24" width="16" height="16">
                        <path fill="currentColor" d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/>
                    </svg>
                </button>
            </td>
        `;
        tableBody.appendChild(row);
    });
}

function filterCollection() {
    renderCollectionTable();
}

async function deleteCardDirectly(page, slot, name) {
    if (confirm(`Are you sure you want to remove ${capitalize(name)} from Page ${page}, Slot ${slot}?`)) {
        showToast("Removing card...", "info");
        try {
            const res = await fetch("/api/remove", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ page, slot, name })
            });
            const data = await res.json();
            if (data.success) {
                showToast(`Removed ${capitalize(name)} successfully!`, "success");
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
    document.getElementById("add-search-input").value = capitalize(match.name);
    document.getElementById("autocomplete-suggestions").classList.add("hidden");
    
    // Update card front preview
    document.getElementById("preview-name").textContent = capitalize(match.name);
    document.getElementById("preview-dex-id").textContent = `#${match.id}`;
    
    const previewImg = document.getElementById("preview-image");
    const previewPlaceholder = document.getElementById("preview-image-placeholder");
    
    previewImg.src = `https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/${match.id}.png`;
    previewImg.classList.remove("hidden");
    previewPlaceholder.classList.add("hidden");
    
    // Request suggested placement positions
    await fetchSuggestedPosition(match.id, match.name);
}

function selectPokemonCustom(query) {
    selectedPokemon = { name: query.trim().toLowerCase(), id: 0 };
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
    document.getElementById("add-search-input").value = "";
    document.getElementById("add-condition").value = "NM";
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
                condition: condVal,
                notes: notesVal
            })
        });
        
        const data = await res.json();
        if (data.success) {
            let msg = `Added ${capitalize(selectedPokemon.name)} to Page ${pageVal}, Slot ${slotVal}!`;
            if (data.gsheet_synced) {
                if (data.gsheet_success) {
                    msg += " (Google Sheet Synced!)";
                    showToast(msg, "success");
                } else {
                    msg += " (CSV Saved. Sheets sync failed, check settings.)";
                    showToast(msg, "warning");
                }
            } else {
                showToast(msg, "success");
            }
            
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

function openCardModal(page, slot, targetName = null) {
    // Filter items in this slot
    const slotCards = collection.filter(c => c.Page === page && c.Slot === slot);
    if (slotCards.length === 0) return;
    
    // If a targetName was specified (from list view), highlight that card in the slot list
    let activeCard = slotCards[0];
    if (targetName) {
        const found = slotCards.find(c => c.Name.toLowerCase() === targetName.toLowerCase());
        if (found) activeCard = found;
    }
    
    modalActiveCard = activeCard;
    
    const modal = document.getElementById("card-modal");
    
    // Fill text properties
    document.getElementById("modal-pokemon-name").textContent = capitalize(activeCard.Name);
    document.getElementById("modal-details-title").textContent = `${capitalize(activeCard.Name)} Detail Specs`;
    document.getElementById("modal-pokemon-dex").textContent = activeCard["Dex Number"] > 0 ? `#${activeCard["Dex Number"]}` : "Custom Card";
    document.getElementById("modal-pokemon-location").textContent = `Page ${activeCard.Page}, Slot ${activeCard.Slot}`;
    document.getElementById("modal-pokemon-date").textContent = activeCard["Date Added"] || "Unknown";
    document.getElementById("modal-pokemon-notes").textContent = activeCard.Notes || "No notes available for this card.";
    
    // Set condition badge
    const badge = document.getElementById("modal-pokemon-condition");
    badge.textContent = activeCard.Condition;
    badge.className = `badge ${activeCard.Condition}`;
    
    // Render artwork or placeholder
    const artworkImg = document.getElementById("modal-pokemon-image");
    if (activeCard["Dex Number"] > 0) {
        artworkImg.src = `https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/${activeCard["Dex Number"]}.png`;
        artworkImg.style.display = "block";
    } else {
        artworkImg.style.display = "none";
    }
    
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
                item.style.borderColor = "var(--color-primary)";
                item.style.backgroundColor = "rgba(255, 255, 255, 0.05)";
            }
            
            item.innerHTML = `
                <div class="stacked-info">
                    <span class="badge ${c.Condition}" style="padding:2px 4px;font-size:9px;">${c.Condition}</span>
                    <span class="stacked-notes">${c.Notes || "No Notes"}</span>
                </div>
                <span style="font-size:10px;color:var(--text-muted);">${c["Date Added"].split(" ")[0]}</span>
            `;
            
            // Switch active view card in modal on stacked item click
            item.onclick = () => {
                openCardModal(page, slot, c.Name);
            };
            
            stackedList.appendChild(item);
        });
    } else {
        stackedSection.classList.add("hidden");
    }
    
    modal.classList.remove("hidden");
}

function closeCardModal(e) {
    document.getElementById("card-modal").classList.add("hidden");
    modalActiveCard = null;
}

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
                    name: card.Name
                })
            });
            const data = await res.json();
            if (data.success) {
                showToast(`Successfully removed ${capitalize(card.Name)}!`, "success");
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
    txt.textContent = "Syncing Google Sheets...";
    showToast("Synchronizing collection to Google Sheets...", "info");
    
    try {
        const res = await fetch("/api/sync", { method: "POST" });
        const data = await res.json();
        
        if (data.success) {
            showToast("Google Sheets sync completed successfully!", "success");
        } else {
            showToast(data.error || "Google Sheets sync failed", "error");
        }
    } catch (e) {
        showToast("Sync error: check credentials.json", "error");
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
