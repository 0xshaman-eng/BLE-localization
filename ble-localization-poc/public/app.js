import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import {
    getDatabase,
    ref,
    onValue,
    push,
    set,
    update,
    remove
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-database.js";

const firebaseConfig = {
    apiKey: window.__FIREBASE_API_KEY__,
    authDomain: window.__FIREBASE_AUTH_DOMAIN__,
    databaseURL: window.__FIREBASE_DATABASE_URL__,
    projectId: window.__FIREBASE_PROJECT_ID__,
    storageBucket: window.__FIREBASE_STORAGE_BUCKET__,
    messagingSenderId: window.__FIREBASE_MESSAGING_SENDER_ID__,
    appId: window.__FIREBASE_APP_ID__,
    measurementId: window.__FIREBASE_MEASUREMENT_ID__
};

if (!firebaseConfig.apiKey) {
    console.warn('FIREBASE API key not set. Create public/env.js with window.__FIREBASE_API_KEY__ set, or set the key at build time. See README.md for details.');
}

const app = initializeApp(firebaseConfig);
const db = getDatabase(app);

// --- DOM ---
const toolsList = document.getElementById("toolsList");
const toolCount = document.getElementById("toolCount");
const addToolForm = document.getElementById("addToolForm");
const newToolName = document.getElementById("newToolName");
const tagTypeInput = document.getElementById("tagTypeInput");
const tagInputShell = document.getElementById("tagInputShell");
const addFeedback = document.getElementById("addFeedback");
const statusDot = document.getElementById("statusDot");
const statusText = document.getElementById("statusText");

// Filter elements
const showAllBtn = document.getElementById("showAllBtn");
const searchNameInput = document.getElementById("searchNameInput");
const searchUuidInput = document.getElementById("searchUuidInput");
const lastSeenStart = document.getElementById("lastSeenStart");
const lastSeenEnd = document.getElementById("lastSeenEnd");
const mapViewToggle = document.getElementById("mapViewToggle");
const includeMissingToggle = document.getElementById("includeMissingToggle");

// Reset buttons
const resetTextBtn = document.getElementById("resetTextBtn");
const resetDateBtn = document.getElementById("resetDateBtn");
const resetGeoBtn = document.getElementById("resetGeoBtn");

// Tab elements
const tabs = document.querySelectorAll(".tab");
const tabContents = document.querySelectorAll(".tab-content");

// --- Leaflet Map ---
const map = L.map("map").setView([0, 0], 3);
L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    attribution: '&copy; OpenStreetMap contributors',
    maxZoom: 22
}).addTo(map);

const markers = {};
let tools = [];
let filteredTools = [];
let mapBoundsDebounceTimer = null;
let sightings = [];
let highlightedToolId = null;

// Winner selection configuration
const STRONGEST_WINDOW_MS = 1000; // 1 second window for RSSI comparison
const DEFAULT_MARKER_ICON_URL = 'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/images/marker-icon.png';
const TAGGED_MARKER_ICON_URL = 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-orange.png';
const HIGHLIGHTED_MARKER_ICON_URL = 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-red.png';
const TAG_INPUT_CONFIG = {
    hilti: {
        totalSegments: 9,
        prefixSegments: 0,
        inputMode: 'numeric',
        allowedPattern: /[0-9]/,
        placeholder: ''
    },
    ela: {
        totalSegments: 9,
        prefixSegments: 3,
        inputMode: 'text',
        allowedPattern: /[0-9A-F]/,
        placeholder: ''
    },
    em: {
        totalSegments: 15,
        prefixSegments: 0,
        inputMode: 'numeric',
        allowedPattern: /[0-9]/,
        placeholder: ''
    },
    milwaukee: {
        totalSegments: 10,
        prefixSegments: 0,
        inputMode: 'text',
        allowedPattern: /[0-9A-F]/,
        placeholder: ''
    }
};

let tagSegmentInputs = [];
let currentTagType = '';

// --- Real-time listeners ---
const toolsRef = ref(db, "ble-localization-poc/tools");
const sightingsRef = ref(db, "ble-localization-poc/sightings");

// Listen to sightings for winner computation
onValue(sightingsRef, (snapshot) => {
    const data = snapshot.val();
    sightings = data ? Object.keys(data).map(key => ({ id: key, ...data[key] })) : [];
    console.log(`Loaded ${sightings.length} sightings`);

    // Recompute winners when sightings change
    if (tools.length > 0) {
        computeWinners();
        applyFilters();
    }
});

onValue(toolsRef, (snapshot) => {
    console.log("%cFIREBASE LISTENER TRIGGERED", 'color: #00ff00; font-weight: bold;');
    const data = snapshot.val();
    const toolsArray = data ? Object.keys(data).map(key => ({ id: key, ...data[key] })) : [];
    console.log("Number of tools:", toolsArray.length);

    statusDot.classList.add("connected");
    statusText.textContent = `Connected - ${toolsArray.length} tool(s)`;
    tools = toolsArray;

    // Compute winners from sightings
    computeWinners();
    applyFilters();
}, (error) => {
    console.error("Database listener error:", error);
    statusDot.classList.remove("connected");
    statusText.textContent = "Error: " + error.message;
});

// --- Winner Selection Algorithm ---
function computeWinners() {
    if (!tools || tools.length === 0 || !sightings || sightings.length === 0) {
        return;
    }

    console.log(`Computing winners for ${tools.length} tools from ${sightings.length} sightings`);

    tools.forEach(tool => {
        if (!tool.tagUuid) {
            // No tag mapped, skip
            tool.computed = null;
            return;
        }

        // Get all sightings for this tool's tag
        const tagSightings = sightings.filter(s => s.tagUuid === tool.tagUuid);

        if (tagSightings.length === 0) {
            tool.computed = null;
            return;
        }

        // Sort by timestamp descending (most recent first)
        tagSightings.sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));

        // Get the most recent sighting
        const mostRecent = tagSightings[0];
        const mostRecentTime = mostRecent.timestamp || 0;

        // Find all sightings within the time window
        const withinWindow = tagSightings.filter(s => {
            const timeDiff = mostRecentTime - (s.timestamp || 0);
            return timeDiff >= 0 && timeDiff <= STRONGEST_WINDOW_MS;
        });

        // Among those within the window, pick the strongest RSSI (highest value, e.g., -45 > -60)
        let winner = withinWindow[0];
        withinWindow.forEach(s => {
            if ((s.rssi || -100) > (winner.rssi || -100)) {
                winner = s;
            }
        });

        // Store computed winner on the tool object
        tool.computed = {
            lat: winner.anchorLat || 0,
            lng: winner.anchorLng || 0,
            timestamp: winner.timestamp || 0,
            rssi: winner.rssi || -100,
            anchorId: winner.anchorId || 'unknown',
            anchorName: winner.anchorName || 'Unknown Anchor',
            windowSize: withinWindow.length
        };

        console.log(`Tool ${tool.name}: Winner is ${winner.anchorName} with RSSI=${winner.rssi} (${withinWindow.length} candidates in window)`);
    });
}

// --- Render tool cards ---
function renderTools(tools) {
    toolCount.textContent = `(${tools.length})`;
    if (tools.length === 0) {
        toolsList.innerHTML = `<div class="empty-state">No tools yet. Add one above.</div>`;
        return;
    }

    toolsList.innerHTML = tools.map((tool) => {
        const tagHtml = tool.tagUuid
            ? `<div class="tool-tag">${escapeHtml(formatTagForDisplay(tool.tagUuid, tool.tagType))}</div>`
            : `<div class="tool-no-tag">No tag mapped</div>`;

        let locationHtml = "";
        let rssiHtml = "";
        let anchorHtml = "";

        // Use computed winner data if available, otherwise fall back to raw tool data
        const lat = tool.computed ? tool.computed.lat : (tool.lastLat || 0);
        const lng = tool.computed ? tool.computed.lng : (tool.lastLng || 0);
        const timestamp = tool.computed ? tool.computed.timestamp : tool.lastSeenAt;
        const rssi = tool.computed ? tool.computed.rssi : tool.lastRssi;
        const anchorName = tool.computed ? tool.computed.anchorName : tool.lastAnchorName;

        if (lat !== 0 || lng !== 0) {
            let timeStr = "";
            if (timestamp) {
                const ts = timestamp.toDate ? timestamp.toDate() : new Date(timestamp);
                timeStr = ` — ${ts.toLocaleString()}`;
            }
            locationHtml = `<div class="tool-location">📍 ${lat.toFixed(6)}, ${lng.toFixed(6)}${timeStr}</div>`;
        }

        // Display RSSI if available
        if (rssi !== undefined && rssi !== null) {
            const rssiColor = rssi > -60 ? 'green' : rssi > -80 ? 'orange' : 'red';
            rssiHtml = `<div class="tool-rssi" style="color: ${rssiColor};">📶 RSSI: ${rssi} dBm</div>`;
        }

        // Display anchor name if available
        if (anchorName) {
            anchorHtml = `<div class="tool-anchor">🎯 Anchor: ${escapeHtml(anchorName)}</div>`;
        }

        return `<div class="tool-card" onclick="handleToolCardClick(event, '${tool.id}')">
            <div class="tool-header">
                <span class="tool-name">${escapeHtml(tool.name)}</span>
                <button class="btn-menu" onclick="toggleMenu(event, '${tool.id}')">⋮</button>
                <div class="dropdown-menu" id="menu-${tool.id}">
                    ${tool.tagUuid ? `<div class="dropdown-item" onclick="unmapTag('${tool.id}')">Unmap Tag</div>` : ''}
                    <div class="dropdown-item danger" onclick="deleteTool('${tool.id}', '${escapeHtml(tool.name)}')">Delete</div>
                </div>
            </div>
            ${tagHtml}
            ${locationHtml}
            ${rssiHtml}
            ${anchorHtml}
        </div>`;
    }).join("");
}

// --- Update Leaflet map markers ---
function updateMap(tools) {
    console.log(`%cUPDATE MAP CALLED with ${tools.length} tools`, 'color: #ff00ff; font-weight: bold; font-size: 14px;');
    if (!map) return;
    // Remove old markers
    Object.keys(markers).forEach((id) => {
        if (!tools.find((t) => t.id === id)) {
            map.removeLayer(markers[id]);
            delete markers[id];
        }
    });

    const bounds = [];

    tools.forEach((tool) => {
        // Use computed winner location if available, otherwise fall back to raw tool data
        const lat = tool.computed ? tool.computed.lat : (tool.lastLat || 0);
        const lng = tool.computed ? tool.computed.lng : (tool.lastLng || 0);

        // Always show markers for tools with tags, even if not currently updating
        // Only skip tools without tags AND without location data
        if (lat === 0 && lng === 0 && !tool.hasOwnProperty('tagUuid')) {
            if (markers[tool.id]) {
                map.removeLayer(markers[tool.id]);
                delete markers[tool.id];
            }
            return;
        }

        // For tools with tags, show them even if lat/lng is 0 (they might have old location data)
        if (lat === 0 && lng === 0 && tool.hasOwnProperty('tagUuid')) {
            // Don't show marker, but don't remove it either - keep existing marker if it exists
            if (!markers[tool.id]) {
                return; // Skip creating new marker
            }
            // Keep existing marker with old position
        }

        const timestamp = tool.computed ? tool.computed.timestamp : tool.lastSeenAt;
        const rssi = tool.computed ? tool.computed.rssi : tool.lastRssi;
        const anchorName = tool.computed ? tool.computed.anchorName : tool.lastAnchorName;

        let timeStr = "Never";
        if (timestamp) {
            const ts = timestamp.toDate ? timestamp.toDate() : new Date(timestamp);
            timeStr = ts.toLocaleString();
        }

        // Create custom icon based on tag status
        const hasTag = tool.hasOwnProperty('tagUuid') && tool.tagUuid && tool.tagUuid.trim() !== '';

        // Build popup with RSSI and anchor info
        let popupContent = `<strong>${escapeHtml(tool.name)}</strong><br>`;
        if (hasTag) {
            popupContent += `Tag: <code>${escapeHtml(formatTagForDisplay(tool.tagUuid, tool.tagType))}</code><br>`;
        } else {
            popupContent += `<em>No tag mapped</em><br>`;
        }
        popupContent += `Last seen: ${timeStr}<br>`;
        if (rssi !== undefined && rssi !== null) {
            popupContent += `RSSI: ${rssi} dBm<br>`;
        }
        if (anchorName) {
            popupContent += `Anchor: ${escapeHtml(anchorName)}`;
        }

        const popup = popupContent;

        // Use different marker icons based on tag status
        const iconUrl = tool.id === highlightedToolId
            ? HIGHLIGHTED_MARKER_ICON_URL
            : hasTag
                ? TAGGED_MARKER_ICON_URL
                : DEFAULT_MARKER_ICON_URL;

        const customIcon = L.icon({
            iconUrl: iconUrl,
            shadowUrl: 'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/images/marker-shadow.png',
            iconSize: [25, 41],
            iconAnchor: [12, 41],
            popupAnchor: [1, -34],
            shadowSize: [41, 41]
        });

        // Always recreate marker to ensure color update
        if (markers[tool.id]) {
            map.removeLayer(markers[tool.id]);
            delete markers[tool.id];
        }
        markers[tool.id] = L.marker([lat, lng], {
            icon: customIcon,
            zIndexOffset: tool.id === highlightedToolId ? 10000 : 0
        }).addTo(map).bindPopup(popup);

        // Log computed winner info for debugging
        if (tool.computed) {
            console.log(`${tool.name}: Using computed location from ${tool.computed.anchorName} (RSSI=${tool.computed.rssi})`);
        }

        bounds.push([lat, lng]);
    });

    // Removed auto-fit bounds to prevent map from zooming out on every update
    // if (bounds.length > 0) {
    //     map.fitBounds(bounds, { padding: [40, 40], maxZoom: 16 });
    // }
}

// --- Add tool with tag ---
addToolForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const name = newToolName.value.trim();
    const tagType = tagTypeInput.value;
    const tagValue = getTagValueFromSegments();

    if (!name) return;
    if (!tagType) {
        addFeedback.textContent = "Choose a tag type first.";
        addFeedback.className = "feedback-error";
        return;
    }
    if (!tagValue) {
        addFeedback.textContent = "Complete the tag ID boxes first.";
        addFeedback.className = "feedback-error";
        return;
    }

    addFeedback.textContent = "";
    addFeedback.className = "";

    try {
        const tagUuid = formatTagForStorage(tagType, tagValue);

        // Check if tag is already mapped to another tool
        const existingTool = tools.find(t => t.tagUuid === tagUuid);
        if (existingTool) {
            addFeedback.textContent = `Tag already mapped to "${existingTool.name}"`;
            addFeedback.className = "feedback-error";
            return;
        }

        const newToolRef = push(ref(db, "ble-localization-poc/tools"));
        await set(newToolRef, {
            name: name,
            tagType: tagType,
            tagUuid: tagUuid,
            lastLat: 0,
            lastLng: 0,
            lastSeenAt: null
        });
        addFeedback.textContent = `"${name}" added with tag!`;
        addFeedback.className = "feedback-success";
        newToolName.value = "";
        tagTypeInput.value = "";
        currentTagType = "";
        renderTagInputShell();
    } catch (err) {
        addFeedback.textContent = "Error: " + err.message;
        addFeedback.className = "feedback-error";
    }
});

tagTypeInput.addEventListener('change', () => {
    currentTagType = tagTypeInput.value;
    renderTagInputShell();
});

renderTagInputShell();


// --- Tab switching ---
tabs.forEach(tab => {
    tab.addEventListener('click', () => {
        const targetTab = tab.dataset.tab;

        // Update active tab
        tabs.forEach(t => t.classList.remove('active'));
        tab.classList.add('active');

        // Update active content
        tabContents.forEach(content => {
            content.classList.remove('active');
        });

        if (targetTab === 'map') {
            document.getElementById('mapTab').classList.add('active');
        } else if (targetTab === 'add-tool') {
            document.getElementById('addToolTab').classList.add('active');
        }
    });
});

// --- Filtering pipeline ---
function applyFilters() {
    let filtered = [...tools];

    // Filter by tool name
    const nameQuery = searchNameInput.value.trim().toLowerCase();
    if (nameQuery) {
        filtered = filtered.filter(tool =>
            tool.name.toLowerCase().includes(nameQuery)
        );
    }

    // Filter by tag UUID (decimal for Hilti, C ID for ELA)
    const uuidQuery = searchUuidInput.value.trim();
    if (uuidQuery) {
        const uuidQueryUpper = uuidQuery.toUpperCase();
        filtered = filtered.filter(tool => {
            if (!tool.tagUuid) return false;
            const displayUuid = formatTagForDisplay(tool.tagUuid, tool.tagType);
            // Match against display format and raw stored format (case-insensitive)
            return displayUuid.includes(uuidQuery) ||
                tool.tagUuid.toUpperCase().includes(uuidQueryUpper);
        });
    }

    // Filter by last seen range
    const startTime = lastSeenStart.value ? new Date(lastSeenStart.value).getTime() : null;
    const endTime = lastSeenEnd.value ? new Date(lastSeenEnd.value).getTime() : null;
    const includeMissing = includeMissingToggle.checked;

    if (startTime || endTime) {
        filtered = filtered.filter(tool => {
            if (!tool.lastSeenAt) {
                return includeMissing;
            }
            const toolTime = tool.lastSeenAt.toDate ? tool.lastSeenAt.toDate().getTime() : new Date(tool.lastSeenAt).getTime();

            if (startTime && toolTime < startTime) return false;
            if (endTime && toolTime > endTime) return false;
            return true;
        });
    } else if (!includeMissing) {
        // Exclude missing last seen by default when no time range is set
        filtered = filtered.filter(tool => tool.lastSeenAt);
    }

    // Filter by map bounds
    if (mapViewToggle.checked && map) {
        const bounds = map.getBounds();
        filtered = filtered.filter(tool => {
            const lat = tool.lastLat || 0;
            const lng = tool.lastLng || 0;
            if (lat === 0 && lng === 0) return false;
            return bounds.contains([lat, lng]);
        });
    }

    filteredTools = filtered;
    renderTools(filteredTools);
    updateMap(filteredTools);
}

// --- Filter event listeners ---
showAllBtn.addEventListener('click', () => {
    searchNameInput.value = '';
    searchUuidInput.value = '';
    lastSeenStart.value = '';
    lastSeenEnd.value = '';
    mapViewToggle.checked = false;
    includeMissingToggle.checked = false;
    applyFilters();
});

// Individual reset buttons
resetTextBtn.addEventListener('click', () => {
    searchNameInput.value = '';
    searchUuidInput.value = '';
    applyFilters();
});

resetDateBtn.addEventListener('click', () => {
    lastSeenStart.value = '';
    lastSeenEnd.value = '';
    includeMissingToggle.checked = false;
    applyFilters();
});

resetGeoBtn.addEventListener('click', () => {
    mapViewToggle.checked = false;
    applyFilters();
});

searchNameInput.addEventListener('input', applyFilters);
searchUuidInput.addEventListener('input', applyFilters);
lastSeenStart.addEventListener('change', applyFilters);
lastSeenEnd.addEventListener('change', applyFilters);
mapViewToggle.addEventListener('change', applyFilters);
includeMissingToggle.addEventListener('change', applyFilters);

// --- Map bounds filtering with debounce ---
map.on('moveend', () => {
    if (mapViewToggle.checked) {
        clearTimeout(mapBoundsDebounceTimer);
        mapBoundsDebounceTimer = setTimeout(() => {
            applyFilters();
        }, 300);
    }
});

map.on('zoomend', () => {
    if (mapViewToggle.checked) {
        clearTimeout(mapBoundsDebounceTimer);
        mapBoundsDebounceTimer = setTimeout(() => {
            applyFilters();
        }, 300);
    }
});

map.on('click', () => {
    if (!highlightedToolId) return;
    highlightedToolId = null;
    updateMap(filteredTools);
});

// --- 3-dot menu toggle ---
window.handleToolCardClick = function (event, toolId) {
    const target = event.target;
    if (target.closest('.btn-menu') || target.closest('.dropdown-menu')) {
        return;
    }
    window.locateTool(toolId);
};

window.toggleMenu = function (event, toolId) {
    event.stopPropagation();
    const menu = document.getElementById(`menu-${toolId}`);

    // Close all other menus
    document.querySelectorAll('.dropdown-menu').forEach(m => {
        if (m.id !== `menu-${toolId}`) {
            m.classList.remove('show');
        }
    });

    menu.classList.toggle('show');
};

// Close menus when clicking outside
document.addEventListener('click', () => {
    document.querySelectorAll('.dropdown-menu').forEach(menu => {
        menu.classList.remove('show');
    });
});

// --- Global actions (called from onclick) ---
window.unmapTag = async function (toolId) {
    try {
        await update(ref(db, `ble-localization-poc/tools/${toolId}`), { tagUuid: null });
    } catch (err) {
        alert("Error unmapping tag: " + err.message);
    }
};

window.deleteTool = async function (toolId, name) {
    if (!confirm(`Delete "${name}"? This cannot be undone.`)) return;
    try {
        await remove(ref(db, `ble-localization-poc/tools/${toolId}`));
    } catch (err) {
        alert("Error deleting tool: " + err.message);
    }
};

window.locateTool = function (toolId) {
    const tool = tools.find((t) => t.id === toolId);
    if (!tool) return;

    const lat = tool.computed ? tool.computed.lat : (tool.lastLat || 0);
    const lng = tool.computed ? tool.computed.lng : (tool.lastLng || 0);
    if (lat === 0 && lng === 0) return;

    highlightedToolId = toolId;
    updateMap(filteredTools);
    map.setView([lat, lng], 18);
    if (markers[toolId]) {
        markers[toolId].openPopup();
    }
};

// --- Test function ---
window.testAddTool = async function () {
    try {
        const newToolRef = push(ref(db, "ble-localization-poc/tools"));
        await set(newToolRef, {
            name: "Test Tool " + Date.now(),
            tagUuid: null,
            lastLat: 0,
            lastLng: 0,
            lastSeenAt: null
        });
        console.log("Test tool added successfully");
    } catch (err) {
        console.error("Error adding test tool:", err);
    }
};

// --- Tag UUID Conversion Utilities ---
function hexToDecimal(hexUuid) {
    if (!hexUuid) return "";

    console.log("Converting hex to decimal:", hexUuid);

    try {
        // Remove any non-hex characters and convert to lowercase
        const cleanHex = hexUuid.replace(/[^0-9a-fA-F]/g, "").toLowerCase();

        // Check if it has the d322 prefix, if so, remove it for conversion
        const variablePart = cleanHex.startsWith("d322") ? cleanHex.substring(4) : cleanHex;

        console.log("Variable part for conversion:", variablePart);

        // Reverse bytes for little-endian: 18666c12 -> 126c6618
        let reversedHex = "";
        for (let i = variablePart.length - 2; i >= 0; i -= 2) {
            reversedHex += variablePart.substring(i, i + 2);
        }

        // Convert entire hex number to decimal
        const decimal = parseInt(reversedHex, 16);
        console.log("Decimal result:", decimal);

        return decimal.toString();
    } catch (e) {
        console.error("Conversion error:", e.message);
        return hexUuid; // Return original if conversion fails
    }
}

function decimalToHex(decimal) {
    if (!decimal) return "";

    console.log("Converting decimal to hex:", decimal);

    try {
        // Parse decimal to hex
        const decimalValue = parseInt(decimal);
        const hexString = decimalValue.toString(16);

        // Left-pad to exactly 8 characters (4 bytes)
        const paddedHex = hexString.padStart(8, '0');

        // Reverse bytes (little-endian swap)
        let reversedHex = "";
        for (let i = paddedHex.length - 2; i >= 0; i -= 2) {
            reversedHex += paddedHex.substring(i, i + 2);
        }

        // Prefix with d322
        const result = "d322" + reversedHex;
        console.log("Hex result:", result);

        return result;
    } catch (e) {
        console.error("Conversion error:", e.message);
        return "";
    }
}

// --- EM Tag Utilities ---
function emMacToDecimal(macHex) {
    if (!macHex) return "";
    
    try {
        // Remove any non-hex characters
        const cleanHex = macHex.replace(/[^0-9A-Fa-f]/g, "").toUpperCase();
        
        if (cleanHex.length !== 12) {
            console.warn("Invalid EM MAC hex length:", cleanHex.length);
            return macHex;
        }
        
        // Reverse bytes: 58C35608EEB0 -> B0EE0856C358
        let reversedHex = "";
        for (let i = cleanHex.length - 2; i >= 0; i -= 2) {
            reversedHex += cleanHex.substring(i, i + 2);
        }
        
        // Convert 48-bit hex to decimal using BigInt for large numbers
        const decimal = BigInt("0x" + reversedHex).toString();
        console.log("EM MAC to decimal:", macHex, "->", decimal);
        
        return decimal;
    } catch (e) {
        console.error("Error converting EM MAC to decimal:", e.message);
        return macHex;
    }
}

function emDecimalToMac(decimalSerial) {
    if (!decimalSerial) return "";
    
    try {
        // Parse decimal to hex using BigInt
        const decimalValue = BigInt(decimalSerial);
        let hexString = decimalValue.toString(16).toUpperCase();
        
        // Pad to exactly 12 characters (6 bytes)
        hexString = hexString.padStart(12, '0');
        
        // Reverse bytes: B0EE0856C358 -> 58C35608EEB0
        let reversedHex = "";
        for (let i = hexString.length - 2; i >= 0; i -= 2) {
            reversedHex += hexString.substring(i, i + 2);
        }
        
        console.log("EM decimal to MAC:", decimalSerial, "->", reversedHex);
        
        return reversedHex;
    } catch (e) {
        console.error("Error converting EM decimal to MAC:", e.message);
        return "";
    }
}

// --- ELA C ID Utilities ---
function normalizeElaTagId(input) {
    if (!input) return null;

    try {
        // Remove all whitespace and convert to uppercase
        const cleaned = input.replace(/\s+/g, "").toUpperCase();

        // Extract hex part based on different formats
        let hexPart = null;

        if (cleaned.startsWith("CID")) {
            // Format: "CID015E58" or "cid015e58"
            const hex = cleaned.substring(3);
            if (hex.length === 6 && /^[0-9A-F]{6}$/.test(hex)) {
                hexPart = hex;
            }
        } else if (/^[0-9A-F]{6}$/.test(cleaned)) {
            // Format: "015E58" or "015e58" (just hex)
            hexPart = cleaned;
        }

        if (!hexPart) {
            console.warn("Invalid C ID format:", input);
            return null;
        }

        // Return normalized format
        const normalized = "C ID " + hexPart;
        console.log("Normalized C ID:", input, "->", normalized);
        return normalized;

    } catch (e) {
        console.error("Error normalizing C ID:", input, e);
        return null;
    }
}

function isElaTagFormat(tagUuid) {
    return tagUuid && tagUuid.startsWith("C ID ");
}

function isEmTagFormat(tagUuid) {
    // EM tags are stored as 12 hex characters (MAC address without colons)
    return tagUuid && /^[0-9A-F]{12}$/i.test(tagUuid);
}

function extractHexFromCId(cId) {
    if (!cId || !cId.startsWith("C ID ")) return null;
    const hex = cId.substring(5);
    return /^[0-9A-F]{6}$/.test(hex) ? hex : null;
}

function inferTagType(tagUuid) {
    if (!tagUuid) return "";
    if (tagUuid.startsWith("C ID ")) return "ela";
    if (isEmTagFormat(tagUuid)) return "em";
    if (/^[0-9A-F]{10}$/i.test(tagUuid)) return "milwaukee";
    return "hilti";
}

function formatTagForDisplay(tagUuid, tagType = null) {
    const resolvedType = tagType || inferTagType(tagUuid);
    if (resolvedType === "ela") {
        return tagUuid;
    }
    if (resolvedType === "em") {
        return emMacToDecimal(tagUuid);
    }
    if (resolvedType === "milwaukee") {
        return tagUuid;
    }
    return hexToDecimal(tagUuid);
}

function formatTagForStorage(tagType, tagValue) {
    if (tagType === "ela") {
        return `C ID ${tagValue.toUpperCase()}`;
    }
    if (tagType === "em") {
        return emDecimalToMac(tagValue);
    }
    if (tagType === "milwaukee") {
        return tagValue.toUpperCase();
    }
    return decimalToHex(tagValue);
}

function renderTagInputShell() {
    const config = TAG_INPUT_CONFIG[currentTagType];
    tagSegmentInputs = [];

    if (!config) {
        tagInputShell.classList.add("disabled");
        tagInputShell.innerHTML = `<div class="tag-entry-help" id="tagInputPrompt" style="margin: 0;">Select a tag type to enter the tag ID.</div>`;
        return;
    }

    tagInputShell.classList.remove("disabled");
    const parts = [];
    const prefixChars = ["C", "I", "D"];

    for (let index = 0; index < config.totalSegments; index += 1) {
        if (currentTagType === "ela" && index < config.prefixSegments) {
            parts.push(`<span class="tag-segment prefix">${prefixChars[index]}</span>`);
            continue;
        }

        const inputIndex = tagSegmentInputs.length;
        parts.push(`<input class="tag-segment tag-segment-input" data-index="${inputIndex}" maxlength="1" inputmode="${config.inputMode}" autocomplete="off" spellcheck="false" placeholder="${config.placeholder}" aria-label="Tag segment ${inputIndex + 1}" />`);
        tagSegmentInputs.push(null);
    }

    tagInputShell.innerHTML = parts.join("");
    tagSegmentInputs = Array.from(tagInputShell.querySelectorAll(".tag-segment-input"));
    tagSegmentInputs.forEach((input) => {
        input.addEventListener("input", handleTagSegmentInput);
        input.addEventListener("keydown", handleTagSegmentKeydown);
        input.addEventListener("paste", handleTagSegmentPaste);
    });

    if (tagSegmentInputs[0]) {
        tagSegmentInputs[0].focus();
    }
}

function normalizeTagSegmentValue(value) {
    if (!value) return "";
    const config = TAG_INPUT_CONFIG[currentTagType];
    if (!config) return "";

    const match = value.toUpperCase().match(config.allowedPattern);
    return match ? match[0] : "";
}

function handleTagSegmentInput(event) {
    const input = event.target;
    const index = Number(input.dataset.index);
    const normalized = normalizeTagSegmentValue(input.value);
    input.value = normalized;

    if (normalized && tagSegmentInputs[index + 1]) {
        tagSegmentInputs[index + 1].focus();
        tagSegmentInputs[index + 1].select();
    }
}

function handleTagSegmentKeydown(event) {
    const input = event.target;
    const index = Number(input.dataset.index);

    if (event.key === "Backspace" && !input.value && tagSegmentInputs[index - 1]) {
        event.preventDefault();
        tagSegmentInputs[index - 1].focus();
        tagSegmentInputs[index - 1].value = "";
        return;
    }

    if (event.key === "ArrowLeft" && tagSegmentInputs[index - 1]) {
        event.preventDefault();
        tagSegmentInputs[index - 1].focus();
    }

    if (event.key === "ArrowRight" && tagSegmentInputs[index + 1]) {
        event.preventDefault();
        tagSegmentInputs[index + 1].focus();
    }
}

function handleTagSegmentPaste(event) {
    event.preventDefault();
    const input = event.target;
    const index = Number(input.dataset.index);
    const pasted = (event.clipboardData || window.clipboardData).getData("text").toUpperCase();
    const config = TAG_INPUT_CONFIG[currentTagType];
    const cleaned = pasted.replace(config.allowedPattern.source === "[0-9]" ? /[^0-9]/g : /[^0-9A-F]/g, "");

    let writeIndex = index;
    for (const char of cleaned) {
        if (!tagSegmentInputs[writeIndex]) break;
        tagSegmentInputs[writeIndex].value = char;
        writeIndex += 1;
    }

    if (tagSegmentInputs[Math.min(writeIndex, tagSegmentInputs.length - 1)]) {
        tagSegmentInputs[Math.min(writeIndex, tagSegmentInputs.length - 1)].focus();
    }
}

function getTagValueFromSegments() {
    if (!currentTagType || !tagSegmentInputs.length) return "";

    const value = tagSegmentInputs.map((input) => input.value.trim()).join("");
    if (currentTagType === "ela") {
        return value.length === 6 ? value.toUpperCase() : "";
    }
    if (currentTagType === "em") {
        return value.length === 15 ? value : "";
    }
    if (currentTagType === "milwaukee") {
        return value.length === 10 ? value.toUpperCase() : "";
    }
    return value.length === 9 ? value : "";
}

// --- Utility ---
function escapeHtml(str) {
    const div = document.createElement("div");
    div.textContent = str || "";
    return div.innerHTML;
}
