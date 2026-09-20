const state = {
    stops: [],
    drivers: [],
    routes: [],
    templates: [],
};

const ITEM_LABELS = [
    ["qtyNormalLunchbox", "Normal madpakke"],
    ["qtyFitnessLunchbox", "Fitness madpakke"],
    ["qtyMusliBar", "Müslibar"],
    ["qtyFruit", "Frugt"],
    ["qtyRisengroed", "Risengrød"],
    ["qtySandwich", "Sandwich"],
    ["qtyCake", "Kage"],
];

async function api(path, options) {
    const res = await fetch(path, {
        headers: { "Content-Type": "application/json" },
        ...options,
    });
    if (!res.ok) {
        let message = `Fejl (${res.status})`;
        try {
            const body = await res.json();
            if (body.error) message = body.error;
        } catch (e) { /* ignore */ }
        throw new Error(message);
    }
    const contentType = res.headers.get("content-type") || "";
    if (contentType.includes("application/json")) {
        return res.json();
    }
    if (res.status === 204) return null;
    return res.text();
}

/**
 * Two-tap delete confirmation instead of the browser's native confirm() dialog.
 * Some mobile browsers silently auto-block repeated confirm() popups on a page after
 * a few appearances — the dialog just never shows and confirm() returns false, so the
 * button looks completely dead with no feedback. This can't be silently suppressed.
 */
function confirmThenRun(btn, action) {
    if (btn.dataset.confirming === "1") {
        clearTimeout(Number(btn.dataset.confirmTimer));
        action();
        return;
    }
    const original = btn.textContent;
    btn.dataset.confirming = "1";
    btn.textContent = "Sikker? Tryk igen";
    const timer = setTimeout(() => {
        btn.textContent = original;
        delete btn.dataset.confirming;
    }, 3000);
    btn.dataset.confirmTimer = String(timer);
}

/**
 * Wires an address <input> to a live suggestions dropdown fed by /api/geocode/search,
 * so the user picks a real, already-geocoded address instead of typing one freehand
 * that might not match (typos, wrong house number, etc.).
 *
 * onSelect(candidate|null) fires with the chosen candidate, or null when the user
 * edits the text after picking one (the previous coordinates are no longer valid).
 */
function attachAddressAutocomplete(inputEl, listEl, onSelect) {
    let debounceTimer = null;
    let requestSeq = 0;

    function hide() {
        listEl.hidden = true;
        listEl.innerHTML = "";
    }

    async function search(query) {
        const seq = ++requestSeq;
        let candidates = [];
        try {
            candidates = await api(`/api/geocode/search?q=${encodeURIComponent(query)}`);
        } catch (e) {
            return;
        }
        if (seq !== requestSeq) return; // a newer keystroke already superseded this request
        if (candidates.length === 0) {
            hide();
            return;
        }
        listEl.innerHTML = candidates.map((c, i) => `<button type="button" data-i="${i}">${esc(c.label)}</button>`).join("");
        listEl.hidden = false;
        listEl.querySelectorAll("button").forEach((btn, i) => {
            btn.addEventListener("mousedown", e => {
                e.preventDefault(); // keep focus so the subsequent blur doesn't race the click
                const candidate = candidates[i];
                hide();
                if (candidate.partial) {
                    // Only a street name so far: complete the text and let the user type the number.
                    inputEl.value = candidate.label.trim() + " ";
                    inputEl.focus();
                    return;
                }
                const typedText = inputEl.value;
                inputEl.value = candidate.label;
                onSelect(candidate, typedText);
            });
        });
    }

    inputEl.addEventListener("input", () => {
        onSelect(null);
        clearTimeout(debounceTimer);
        const query = inputEl.value.trim();
        if (query.length < 3) {
            hide();
            return;
        }
        debounceTimer = setTimeout(() => search(query), 400);
    });

    inputEl.addEventListener("blur", () => {
        setTimeout(hide, 150);
    });
}

function showToast(message) {
    const toast = document.getElementById("toast");
    toast.textContent = message;
    toast.classList.add("show");
    clearTimeout(showToast._t);
    showToast._t = setTimeout(() => toast.classList.remove("show"), 2600);
}

function esc(str) {
    const div = document.createElement("div");
    div.textContent = str ?? "";
    return div.innerHTML;
}

function formatMinutes(minutes) {
    const total = Math.round(minutes);
    const h = Math.floor(total / 60);
    const m = total % 60;
    return h > 0 ? `${h}t ${m} min` : `${m} min`;
}

// ---------- Tabs ----------

document.querySelectorAll(".tab-btn").forEach(btn => {
    btn.addEventListener("click", () => {
        document.querySelectorAll(".tab-btn").forEach(b => b.classList.remove("active"));
        document.querySelectorAll(".tab").forEach(t => t.classList.remove("active"));
        btn.classList.add("active");
        document.getElementById(btn.dataset.tab).classList.add("active");
        if (btn.dataset.tab === "tab-stops") loadStops();
        if (btn.dataset.tab === "tab-routes") loadRoutes();
        if (btn.dataset.tab === "tab-drivers") loadDrivers();
        if (btn.dataset.tab === "tab-menu") loadMenu();
        if (btn.dataset.tab === "tab-settings") loadSettings();
    });
});

// ---------- Stops ----------

async function loadStops() {
    const [stops, drivers, templates] = await Promise.all([api("/api/stops"), api("/api/drivers"), api("/api/templates")]);
    state.stops = stops;
    state.drivers = drivers;
    state.templates = templates;
    renderStops();
    renderTemplates();
}

function renderStops() {
    const container = document.getElementById("stop-list");
    const total = state.stops.length;
    const activeCount = state.stops.filter(s => s.active).length;
    document.getElementById("stop-summary").textContent = total === 0
        ? ""
        : `${activeCount} af ${total} stops er valgt til dagens ruter.`;
    document.getElementById("stop-controls").hidden = total === 0;
    if (total === 0) {
        container.innerHTML = `<p class="empty">Ingen stops endnu. Tryk "+ Tilføj stop" for at komme i gang.</p>`;
        return;
    }
    const query = document.getElementById("stop-search").value.trim().toLowerCase();
    const visible = query
        ? state.stops.filter(s => (s.customerName + " " + s.address).toLowerCase().includes(query))
        : state.stops;
    if (visible.length === 0) {
        container.innerHTML = `<p class="empty">Ingen stops matcher "${esc(query)}".</p>`;
        return;
    }
    container.innerHTML = visible.map(stop => {
        const items = ITEM_LABELS
            .map(([key, label]) => stop[key] > 0 ? `${stop[key]}x ${label}` : null)
            .filter(Boolean)
            .join(", ");
        const typeBadge = stop.stopType === "GYM"
            ? `<span class="badge gym">Gym</span>`
            : `<span class="badge private">Privat</span>`;
        const deadline = stop.deadline ? ` · Deadline ${stop.deadline.substring(0, 5)}` : "";
        const preferredDriver = state.drivers.find(d => d.id === stop.preferredDriverId);
        return `
        <div class="card stop-list-item ${stop.active ? "" : "is-inactive"}">
            <label class="active-toggle">
                <input type="checkbox" ${stop.active ? "checked" : ""} onchange="toggleActive(${stop.id}, this.checked)">
                <span>${stop.active ? "Med på dagens ruter" : "Ikke med i dag"}</span>
            </label>
            <div class="row">
                <div>
                    <h3>${esc(stop.customerName)}</h3>
                    <div class="muted">${esc(stop.address)}${stop.floorDoor ? ` · Etage/dør: ${esc(stop.floorDoor)}` : ""}${deadline}</div>
                    ${!stop.geocoded ? `<div class="address-status pending">⚠ Adresse ikke fundet endnu — redigér og vælg fra listen</div>` : ""}
                    ${preferredDriver ? `<div class="muted">🚐 Foretrukken chauffør: ${esc(preferredDriver.name)}</div>` : ""}
                </div>
                <div>${typeBadge}</div>
            </div>
            ${items ? `<div class="items">${esc(items)}</div>` : ""}
            ${stop.specialOrder ? `<div class="muted items">Special: ${esc(stop.specialOrder)}</div>` : ""}
            <div class="actions">
                <button class="btn small secondary" onclick="editStop(${stop.id})">Redigér</button>
                <button class="btn small danger" onclick="confirmThenRun(this, () => deleteStop(${stop.id}))">Slet</button>
            </div>
        </div>`;
    }).join("");
}

function findStop(id) {
    return state.stops.find(s => s.id === id);
}

// ---------- Templates ("faste stops") ----------

function renderTemplates() {
    const container = document.getElementById("template-list");
    if (state.templates.length === 0) {
        container.innerHTML = `<p class="empty">Ingen skabeloner endnu. Gem jeres faste liste én gang, så I ikke skal taste den ind hver gang.</p>`;
        return;
    }
    container.innerHTML = state.templates.map(t => `
        <div class="card">
            <div class="row">
                <div>
                    <h3>${esc(t.name)}</h3>
                    <div class="muted">${t.itemCount} stop</div>
                </div>
            </div>
            <div class="actions">
                <button class="btn small" onclick="applyTemplate(${t.id})">Brug denne skabelon</button>
                <button class="btn small danger" onclick="confirmThenRun(this, () => deleteTemplate(${t.id}))">Slet</button>
            </div>
        </div>
    `).join("");
}

const saveTemplateForm = document.getElementById("save-template-form");

document.getElementById("btn-save-template").addEventListener("click", () => {
    saveTemplateForm.hidden = !saveTemplateForm.hidden;
    if (!saveTemplateForm.hidden) {
        document.getElementById("template-name-input").focus();
    }
});

document.getElementById("btn-save-template-cancel").addEventListener("click", () => {
    saveTemplateForm.hidden = true;
});

document.getElementById("save-template-form").addEventListener("submit", async e => {
    e.preventDefault();
    const nameInput = document.getElementById("template-name-input");
    const name = nameInput.value.trim();
    if (!name) return;
    const activeIds = state.stops.filter(s => s.active).map(s => s.id);
    if (activeIds.length === 0) {
        alert("Der er ingen aktive stops at gemme lige nu.");
        return;
    }
    await api("/api/templates", { method: "POST", body: JSON.stringify({ name, stopIds: activeIds }) });
    nameInput.value = "";
    saveTemplateForm.hidden = true;
    showToast(`Skabelon "${name}" gemt (${activeIds.length} stop)`);
    loadStops();
});

window.applyTemplate = async function (id) {
    const created = await api(`/api/templates/${id}/apply`, { method: "POST" });
    showToast(`${created.length} stop tilføjet fra skabelonen`);
    loadStops();
};

window.deleteTemplate = async function (id) {
    await api(`/api/templates/${id}`, { method: "DELETE" });
    loadStops();
};

window.editStop = function (id) {
    openStopModal(findStop(id));
};

window.toggleActive = async function (id, active) {
    const stop = findStop(id);
    stop.active = active;
    renderStops();
    try {
        await api(`/api/stops/${id}/active`, { method: "PUT", body: JSON.stringify({ active }) });
    } catch (e) {
        showToast(e.message);
        loadStops();
    }
};

async function setAllStopsActive(active) {
    state.stops.forEach(s => { s.active = active; });
    renderStops();
    try {
        await api("/api/stops/active", { method: "PUT", body: JSON.stringify({ active }) });
    } catch (e) {
        showToast(e.message);
        loadStops();
    }
}

document.getElementById("btn-stops-all").addEventListener("click", () => setAllStopsActive(true));
document.getElementById("btn-stops-none").addEventListener("click", () => setAllStopsActive(false));
document.getElementById("stop-search").addEventListener("input", renderStops);

window.deleteStop = async function (id) {
    await api(`/api/stops/${id}`, { method: "DELETE" });
    showToast("Stop slettet");
    loadStops();
};

// ---------- Stop form modal ----------

const stopModal = document.getElementById("stop-modal");
const stopForm = document.getElementById("stop-form");
const stopAddressInput = document.getElementById("stop-address");
const stopAddressStatus = document.getElementById("stop-address-status");

let selectedStopCoords = null;

function setStopAddressStatus(text, ok) {
    stopAddressStatus.textContent = text;
    stopAddressStatus.className = "address-status" + (ok ? " ok" : (text ? " pending" : ""));
}

// Trailing floor/door in what was typed ("21, 3 th", "21 2. tv", "5 st") — mirrors the server's rule.
const TRAILING_FLOOR_DOOR = /(?:[,\s]+(?:st|kl|\d{1,2})\.?[,\s]*(?:th|tv|mf)\.?|[,\s]+(?:th|tv|mf)\.?|,\s*(?:st|kl|\d{1,2})\.?|\s+(?:st|kl)\.?)$/i;

function extractFloorDoor(text) {
    const match = (text || "").trim().match(TRAILING_FLOOR_DOOR);
    return match ? match[0].replace(/^[,\s]+/, "").trim() : "";
}

attachAddressAutocomplete(stopAddressInput, document.getElementById("stop-address-suggestions"), (candidate, typedText) => {
    if (candidate) {
        // Picking a suggestion replaces the text, so keep any floor/door the user typed.
        const floorInput = document.getElementById("stop-floor");
        const typedFloor = extractFloorDoor(typedText);
        if (typedFloor && !floorInput.value.trim()) {
            floorInput.value = typedFloor;
        }
        selectedStopCoords = { lat: candidate.lat, lon: candidate.lon };
        setStopAddressStatus("✓ Adresse fundet", true);
    } else {
        selectedStopCoords = null;
        setStopAddressStatus(stopAddressInput.value.trim() ? "Vælg adressen fra listen, så den er sikker at finde" : "", false);
    }
});

document.getElementById("btn-add-stop").addEventListener("click", () => openStopModal(null));
document.getElementById("btn-close-modal").addEventListener("click", closeStopModal);
stopModal.addEventListener("click", e => {
    if (e.target === stopModal) closeStopModal();
});

async function openStopModal(stop) {
    document.getElementById("stop-modal-title").textContent = stop ? "Redigér stop" : "Nyt stop";
    document.getElementById("stop-id").value = stop ? stop.id : "";
    document.getElementById("stop-customer").value = stop ? stop.customerName : "";
    document.getElementById("stop-address").value = stop ? stop.address : "";
    document.getElementById("stop-floor").value = stop ? (stop.floorDoor || "") : "";
    document.querySelector(`input[name="stop-type"][value="${stop ? stop.stopType : "PRIVATE"}"]`).checked = true;
    document.getElementById("stop-deadline").value = stop && stop.deadline ? stop.deadline.substring(0, 5) : "";

    state.drivers = await api("/api/drivers");
    const driverSelect = document.getElementById("stop-driver");
    const preferredId = stop ? stop.preferredDriverId : null;
    driverSelect.innerHTML = `<option value="">Automatisk (fordeles ved generering)</option>`
        + state.drivers.filter(d => d.active || d.id === preferredId)
            .map(d => `<option value="${d.id}" ${d.id === preferredId ? "selected" : ""}>${esc(d.name)}${d.active ? "" : " (ikke med i dag)"}</option>`).join("");

    document.getElementById("qty-normal").value = stop ? stop.qtyNormalLunchbox : 0;
    document.getElementById("qty-fitness").value = stop ? stop.qtyFitnessLunchbox : 0;
    document.getElementById("qty-musli").value = stop ? stop.qtyMusliBar : 0;
    document.getElementById("qty-fruit").value = stop ? stop.qtyFruit : 0;
    document.getElementById("qty-risengroed").value = stop ? stop.qtyRisengroed : 0;
    document.getElementById("qty-sandwich").value = stop ? stop.qtySandwich : 0;
    document.getElementById("qty-cake").value = stop ? stop.qtyCake : 0;
    document.getElementById("stop-special").value = stop ? (stop.specialOrder || "") : "";

    if (stop && stop.geocoded) {
        selectedStopCoords = null; // unchanged address + no new coords = server keeps the existing ones
        setStopAddressStatus("✓ Adresse fundet", true);
    } else if (stop) {
        selectedStopCoords = null;
        setStopAddressStatus("Denne adresse er ikke fundet endnu — skriv den igen og vælg fra listen", false);
    } else {
        selectedStopCoords = null;
        setStopAddressStatus("", false);
    }

    const activeRow = document.getElementById("active-row");
    if (stop) {
        activeRow.style.display = "block";
        document.getElementById("stop-active").checked = stop.active;
    } else {
        activeRow.style.display = "none";
    }

    stopModal.hidden = false;
}

function closeStopModal() {
    stopModal.hidden = true;
}

stopForm.addEventListener("submit", async e => {
    e.preventDefault();
    const id = document.getElementById("stop-id").value;
    const deadlineVal = document.getElementById("stop-deadline").value;

    const payload = {
        customerName: document.getElementById("stop-customer").value.trim(),
        address: document.getElementById("stop-address").value.trim(),
        stopType: document.querySelector('input[name="stop-type"]:checked').value,
        deadline: deadlineVal ? deadlineVal : null,
        preferredDriverId: document.getElementById("stop-driver").value ? Number(document.getElementById("stop-driver").value) : null,
        qtyNormalLunchbox: Number(document.getElementById("qty-normal").value) || 0,
        qtyFitnessLunchbox: Number(document.getElementById("qty-fitness").value) || 0,
        qtyMusliBar: Number(document.getElementById("qty-musli").value) || 0,
        qtyFruit: Number(document.getElementById("qty-fruit").value) || 0,
        qtyRisengroed: Number(document.getElementById("qty-risengroed").value) || 0,
        qtySandwich: Number(document.getElementById("qty-sandwich").value) || 0,
        qtyCake: Number(document.getElementById("qty-cake").value) || 0,
        floorDoor: document.getElementById("stop-floor").value.trim(),
        specialOrder: document.getElementById("stop-special").value.trim(),
        active: id ? document.getElementById("stop-active").checked : true,
        lat: selectedStopCoords ? selectedStopCoords.lat : null,
        lon: selectedStopCoords ? selectedStopCoords.lon : null,
    };

    if (id) {
        await api(`/api/stops/${id}`, { method: "PUT", body: JSON.stringify(payload) });
    } else {
        await api("/api/stops", { method: "POST", body: JSON.stringify(payload) });
    }
    showToast("Stop gemt");
    closeStopModal();
    loadStops();
});

// ---------- Drivers ----------

async function loadDrivers() {
    state.drivers = await api("/api/drivers");
    renderDrivers();
}

function renderDrivers() {
    const container = document.getElementById("driver-list");
    if (state.drivers.length === 0) {
        container.innerHTML = `<p class="empty">Ingen chauffører tilføjet endnu.</p>`;
        return;
    }
    const activeCount = state.drivers.filter(d => d.active).length;
    container.innerHTML = `<p class="muted" style="margin:0 2px 8px;">${activeCount} af ${state.drivers.length} chauffører kører i dag.</p>`
        + state.drivers.map(d => `
        <div class="card ${d.active ? "" : "is-inactive"}">
            <div class="row">
                <label class="active-toggle" style="margin:0;">
                    <input type="checkbox" ${d.active ? "checked" : ""} onchange="toggleDriverActive(${d.id}, this.checked)">
                    <span><strong>${esc(d.name)}</strong> · ${d.active ? "Kører i dag" : "Ikke med i dag"}</span>
                </label>
                <button class="btn small danger" onclick="confirmThenRun(this, () => deleteDriver(${d.id}))">Slet</button>
            </div>
            <div class="address-field" style="margin-top:10px;">
                <label class="muted" for="end-addr-${d.id}">Slutadresse (valgfri) — hvor chaufføren afslutter ruten</label>
                <input type="text" id="end-addr-${d.id}" value="${esc(d.endAddress || "")}" placeholder="Begynd at skrive adressen..." autocomplete="off">
                <div class="address-suggestions" id="end-suggestions-${d.id}" hidden></div>
                <div class="address-status ${d.endAddress ? (d.endGeocoded ? "ok" : "pending") : ""}" id="end-status-${d.id}">${d.endAddress ? (d.endGeocoded ? "✓ Adresse fundet" : "⚠ Adressen kunne ikke findes — vælg den fra listen") : ""}</div>
                <div class="actions">
                    <button class="btn small" onclick="saveDriverEnd(${d.id})">Gem slutadresse</button>
                    ${d.endAddress ? `<button class="btn small ghost" onclick="clearDriverEnd(${d.id})">Fjern</button>` : ""}
                </div>
            </div>
        </div>
    `).join("");
    state.drivers.forEach(d => {
        const input = document.getElementById(`end-addr-${d.id}`);
        attachAddressAutocomplete(input, document.getElementById(`end-suggestions-${d.id}`), candidate => {
            driverEndCoords[d.id] = candidate ? { lat: candidate.lat, lon: candidate.lon } : null;
            const status = document.getElementById(`end-status-${d.id}`);
            status.className = "address-status" + (candidate ? " ok" : "");
            status.textContent = candidate ? "✓ Adresse fundet — tryk Gem" : "";
        });
    });
}

// Coordinates of a suggestion picked for a driver's end address, until it is saved.
const driverEndCoords = {};

async function updateDriverEnd(id, address) {
    const text = address !== undefined ? address : document.getElementById(`end-addr-${id}`).value.trim();
    const coords = text ? driverEndCoords[id] : null;
    const saved = await api(`/api/drivers/${id}/end`, {
        method: "PUT",
        body: JSON.stringify({ address: text, lat: coords ? coords.lat : null, lon: coords ? coords.lon : null }),
    });
    delete driverEndCoords[id];
    const driver = state.drivers.find(d => d.id === id);
    Object.assign(driver, saved);
    renderDrivers();
    if (!text) {
        showToast("Slutadresse fjernet");
    } else if (saved.endGeocoded) {
        showToast("Slutadresse gemt — gælder næste gang du genererer ruter");
    } else {
        showToast("Adressen kunne ikke findes — vælg den fra listen");
    }
}

window.saveDriverEnd = id => updateDriverEnd(id);
window.clearDriverEnd = id => updateDriverEnd(id, "");

window.toggleDriverActive = async function (id, active) {
    const driver = state.drivers.find(d => d.id === id);
    driver.active = active;
    renderDrivers();
    try {
        await api(`/api/drivers/${id}/active`, { method: "PUT", body: JSON.stringify({ active }) });
    } catch (e) {
        showToast(e.message);
        loadDrivers();
    }
};

document.getElementById("btn-add-driver").addEventListener("click", async () => {
    const input = document.getElementById("new-driver-name");
    const name = input.value.trim();
    if (!name) return;
    await api("/api/drivers", { method: "POST", body: JSON.stringify({ name }) });
    input.value = "";
    loadDrivers();
});

window.deleteDriver = async function (id) {
    await api(`/api/drivers/${id}`, { method: "DELETE" });
    loadDrivers();
};

// ---------- Routes ----------

async function loadRoutes() {
    const [drivers, routes] = await Promise.all([api("/api/drivers"), api("/api/routes/today")]);
    state.drivers = drivers;
    state.routes = routes;
    renderRoutes([]);
}

document.getElementById("btn-generate").addEventListener("click", async () => {
    const btn = document.getElementById("btn-generate");
    btn.disabled = true;
    btn.textContent = "Genererer…";
    try {
        const result = await api("/api/routes/generate", { method: "POST" });
        state.routes = result.routes;
        renderRoutes(result.skippedStops || []);
        showToast("Ruter genereret");
    } catch (err) {
        alert(err.message);
    } finally {
        btn.disabled = false;
        btn.textContent = "Generér ruter";
    }
});

function renderRoutes(skippedStops) {
    state.lastSkipped = skippedStops || [];
    const warnBox = document.getElementById("route-warnings");
    if (skippedStops && skippedStops.length > 0) {
        warnBox.innerHTML = `<div class="card" style="border-color:#b3423a;">
            <strong>Kunne ikke finde adresse for:</strong>
            <div class="muted">${skippedStops.map(esc).join("<br>")}</div>
            <div class="muted">Disse stops er ikke med i ruterne. Tjek adressen og prøv igen.</div>
        </div>`;
    } else {
        warnBox.innerHTML = "";
    }

    const container = document.getElementById("route-list");
    if (state.routes.length === 0) {
        container.innerHTML = `<p class="empty">Ingen ruter genereret endnu i dag.</p>`;
        return;
    }

    const driverOptions = (selectedId) => {
        const opts = [`<option value="">Ikke tildelt</option>`];
        for (const d of state.drivers.filter(d => d.active || d.id === selectedId)) {
            opts.push(`<option value="${d.id}" ${d.id === selectedId ? "selected" : ""}>${esc(d.name)}</option>`);
        }
        return opts.join("");
    };

    container.innerHTML = state.routes.map(route => {
        const totals = {};
        for (const stop of route.stops) {
            for (const [key] of ITEM_LABELS) {
                totals[key] = (totals[key] || 0) + (stop[key] || 0);
            }
        }
        const totalsText = ITEM_LABELS
            .map(([key, label]) => totals[key] > 0 ? `${totals[key]}x ${label}` : null)
            .filter(Boolean)
            .join(", ");

        const stopsHtml = route.stops.map((stop, i) => {
            const items = ITEM_LABELS
                .map(([key, label]) => stop[key] > 0 ? `${stop[key]}x ${label}` : null)
                .filter(Boolean)
                .join(", ");
            const typeBadge = stop.stopType === "GYM"
                ? `<span class="badge gym">Gym</span>`
                : `<span class="badge private">Privat</span>`;
            const deadline = stop.deadline ? ` · Deadline ${stop.deadline.substring(0, 5)}` : "";
            return `
                <div style="padding:8px 0; border-top:1px solid var(--border);">
                    <div class="row">
                        <strong>${i + 1}. ${esc(stop.customerName)}</strong>
                        ${typeBadge}
                    </div>
                    <div class="muted">${esc(stop.address)}${stop.floorDoor ? ` · <strong>Etage/dør: ${esc(stop.floorDoor)}</strong>` : ""}${deadline}</div>
                    ${items ? `<div class="items">${esc(items)}</div>` : ""}
                    ${stop.specialOrder ? `<div class="muted items">Special: ${esc(stop.specialOrder)}</div>` : ""}
                </div>`;
        }).join("");

        return `
        <div class="card" data-route-id="${route.id}">
            <div class="row">
                <div>
                    <h3>Rute ${route.sequenceIndex + 1}</h3>
                    <div class="muted">Estimeret tid: ${formatMinutes(route.estimatedMinutes)}</div>
                    ${route.endAddress ? `<div class="muted">🏁 Slutter: ${esc(route.endAddress)}</div>` : ""}
                </div>
                <select class="driver-select" onchange="assignDriver(${route.id}, this.value)">
                    ${driverOptions(route.driverId)}
                </select>
            </div>
            ${totalsText ? `<div class="pack-summary"><strong>📦 Total til pakning:</strong> ${esc(totalsText)}</div>` : ""}
            ${route.googleMapsExcludedStopCount > 0 ? `<div class="pack-summary" style="background:#fde8df; color:#a44d1e;">⚠ Google Maps kan kun tage 10 stop ad gangen${route.endAddress ? " (startpunkt og slutadresse tæller med)" : ""}. Linket dækker kun stop 1-${route.stops.length - route.googleMapsExcludedStopCount}. De sidste ${route.googleMapsExcludedStopCount} stop skal chaufføren navigere til manuelt.</div>` : ""}
            ${stopsHtml}
            <div class="actions">
                ${route.googleMapsUrl ? `<a class="btn small" href="${esc(route.googleMapsUrl)}" target="_blank" rel="noopener">🧭 Åbn i Google Maps</a>` : ""}
                <button class="btn small secondary" onclick="toggleRouteText(${route.id})">Vis/kopiér tekst</button>
            </div>
            <div id="route-text-${route.id}" hidden style="margin-top:8px;">
                <textarea class="route-text" id="route-text-area-${route.id}" readonly></textarea>
                <div class="actions">
                    <button class="btn small" onclick="copyRouteText(${route.id})">Kopiér til udklipsholder</button>
                </div>
            </div>
        </div>`;
    }).join("");
}

window.assignDriver = async function (routeId, driverIdRaw) {
    const driverId = driverIdRaw ? Number(driverIdRaw) : null;
    await api(`/api/routes/${routeId}/driver`, { method: "PUT", body: JSON.stringify({ driverId }) });
    showToast("Chauffør opdateret");
    // The new driver may have another end address, which changes the map link and the total time.
    state.routes = await api("/api/routes/today");
    renderRoutes(state.lastSkipped);
};

window.toggleRouteText = async function (routeId) {
    const box = document.getElementById(`route-text-${routeId}`);
    const area = document.getElementById(`route-text-area-${routeId}`);
    if (!box.hidden) {
        box.hidden = true;
        return;
    }
    if (!area.value) {
        area.value = await api(`/api/routes/${routeId}/text`);
    }
    box.hidden = false;
};

window.copyRouteText = async function (routeId) {
    const area = document.getElementById(`route-text-area-${routeId}`);
    if (!area.value) {
        area.value = await api(`/api/routes/${routeId}/text`);
    }
    try {
        await navigator.clipboard.writeText(area.value);
        showToast("Kopieret!");
    } catch (e) {
        area.removeAttribute("readonly");
        area.focus();
        area.select();
        document.execCommand("copy");
        area.setAttribute("readonly", "true");
        showToast("Kopieret!");
    }
};

// ---------- Settings ----------

let selectedDepotCoords = null;

attachAddressAutocomplete(
    document.getElementById("depot-address"),
    document.getElementById("depot-address-suggestions"),
    candidate => {
        selectedDepotCoords = candidate ? { lat: candidate.lat, lon: candidate.lon } : null;
    }
);

async function loadSettings() {
    const settings = await api("/api/settings");
    document.getElementById("depot-address").value = settings.depotAddress || "";
    document.getElementById("depot-status").textContent = settings.depotGeocoded
        ? "Adressen er fundet og gemt."
        : (settings.depotAddress ? "Kunne ikke finde adressen endnu — skriv den igen og vælg fra listen." : "Ingen startadresse sat endnu.");
    document.getElementById("geocode-status").textContent = "Geokodning: "
        + (settings.usingGoogleGeocoding ? "Google Maps API" : "OpenStreetMap/Nominatim (gratis)");
    loadBackupStatus();
}

async function loadBackupStatus() {
    const el = document.getElementById("backup-status");
    try {
        const { backups } = await api("/api/backup");
        if (backups.length === 0) {
            el.textContent = "Ingen kopier på serveren endnu.";
            return;
        }
        const newest = new Date(backups[0].createdAt).toLocaleString("da-DK", { dateStyle: "medium", timeStyle: "short" });
        el.textContent = `Seneste kopi på serveren: ${newest} (${backups.length} gemt i alt).`;
    } catch (e) {
        el.textContent = "Kunne ikke læse status for sikkerhedskopier.";
    }
}

document.getElementById("btn-backup-now").addEventListener("click", async () => {
    try {
        await api("/api/backup/now", { method: "POST" });
        showToast("Sikkerhedskopi taget");
        loadBackupStatus();
    } catch (e) {
        showToast(e.message);
    }
});

document.getElementById("btn-save-depot").addEventListener("click", async () => {
    const address = document.getElementById("depot-address").value.trim();
    const payload = { depotAddress: address };
    if (selectedDepotCoords) {
        payload.lat = selectedDepotCoords.lat;
        payload.lon = selectedDepotCoords.lon;
    }
    await api("/api/settings", { method: "PUT", body: JSON.stringify(payload) });
    showToast("Startadresse gemt");
    loadSettings();
});

document.getElementById("btn-reset-all").addEventListener("click", function () {
    confirmThenRun(this, async () => {
        await api("/api/stops", { method: "DELETE" });
        await api("/api/routes", { method: "DELETE" });
        showToast("Alle stops og ruter er slettet");
        state.stops = [];
        state.routes = [];
        renderStops();
        renderRoutes([]);
    });
});

// ---------- Menu (weekly dish + printable location cards) ----------

async function loadMenu() {
    const [dish, locations] = await Promise.all([api("/api/menu/dish"), api("/api/menu/locations")]);
    document.getElementById("dish-name").value = dish.name || "";
    document.getElementById("dish-subtitle").value = dish.subtitle || "";
    document.getElementById("dish-description").value = dish.description || "";
    document.getElementById("dish-price").value = dish.price || "";
    document.getElementById("dish-protein").value = dish.proteinGrams || "";
    document.getElementById("dish-kcal").value = dish.kcal || "";
    document.getElementById("dish-allergens").value = dish.allergens || "";
    renderMenuLocations(locations);
}

function renderMenuLocations(locations) {
    const container = document.getElementById("menu-location-list");
    if (locations.length === 0) {
        container.innerHTML = `<p class="empty">Ingen lokationer endnu.</p>`;
        return;
    }
    container.innerHTML = locations.map(loc => `
        <div class="card">
            <div class="driver-row">
                <input type="text" value="${esc(loc.name)}" id="loc-name-${loc.id}">
                <input type="text" value="${esc(loc.mobilePayNumber)}" id="loc-mp-${loc.id}" style="max-width:120px;">
            </div>
            <div class="actions">
                <button class="btn small secondary" onclick="saveLocation(${loc.id})">Gem</button>
                <a class="btn small" href="/api/menu/pdf/${loc.id}" target="_blank" rel="noopener">📄 Åbn/Print PDF</a>
                <button class="btn small danger" onclick="confirmThenRun(this, () => deleteLocation(${loc.id}))">Slet</button>
            </div>
        </div>
    `).join("");
}

document.getElementById("dish-form").addEventListener("submit", async e => {
    e.preventDefault();
    const payload = {
        name: document.getElementById("dish-name").value.trim(),
        subtitle: document.getElementById("dish-subtitle").value.trim(),
        description: document.getElementById("dish-description").value.trim(),
        price: document.getElementById("dish-price").value.trim(),
        proteinGrams: document.getElementById("dish-protein").value.trim(),
        kcal: document.getElementById("dish-kcal").value.trim(),
        allergens: document.getElementById("dish-allergens").value.trim(),
    };
    await api("/api/menu/dish", { method: "PUT", body: JSON.stringify(payload) });
    showToast("Ugens ret er gemt — menukortene er opdateret");
});

window.saveLocation = async function (id) {
    const name = document.getElementById(`loc-name-${id}`).value.trim();
    const mobilePayNumber = document.getElementById(`loc-mp-${id}`).value.trim();
    if (!name || !mobilePayNumber) {
        alert("Navn og MobilePay-nr skal begge udfyldes");
        return;
    }
    await api(`/api/menu/locations/${id}`, { method: "PUT", body: JSON.stringify({ name, mobilePayNumber }) });
    showToast("Lokation gemt");
    loadMenu();
};

window.deleteLocation = async function (id) {
    await api(`/api/menu/locations/${id}`, { method: "DELETE" });
    loadMenu();
};

document.getElementById("btn-add-location").addEventListener("click", async () => {
    const nameInput = document.getElementById("new-location-name");
    const mpInput = document.getElementById("new-location-mobilepay");
    const name = nameInput.value.trim();
    const mobilePayNumber = mpInput.value.trim();
    if (!name || !mobilePayNumber) {
        alert("Navn og MobilePay-nr skal begge udfyldes");
        return;
    }
    await api("/api/menu/locations", { method: "POST", body: JSON.stringify({ name, mobilePayNumber }) });
    nameInput.value = "";
    mpInput.value = "";
    loadMenu();
});

// ---------- Init ----------

loadStops();
