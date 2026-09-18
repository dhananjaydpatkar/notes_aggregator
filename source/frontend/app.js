/**
 * Clinical Notes Aggregator — Frontend SPA
 * Pure vanilla JS. No frameworks. No build step required.
 *
 * Settings:
 *   API_BASE_URL and TENANT_ID are stored in sessionStorage.
 *   They reset when the browser tab closes.
 *   To change: click the ⚙ gear icon in the top-right header.
 *
 * API Configuration:
 *   Default API URL: http://localhost:3000 (SAM local)
 *   To point to a deployed endpoint:
 *     1. Click ⚙ in the header
 *     2. Update "API Base URL" to your endpoint (e.g. https://abc123.execute-api.ap-south-1.amazonaws.com)
 *     3. Update "Default Tenant ID" if needed
 *     4. Click "Save Settings"
 *   Settings persist for the browser tab session only.
 */

const DEFAULT_API_URL = 'https://l64zo3f94g.execute-api.ap-south-1.amazonaws.com';

function getInitialApiUrl() {
  const stored = sessionStorage.getItem('notes_api_url');
  if (stored && stored !== 'http://localhost:3000' && !stored.includes('localhost:3000')) {
    return stored;
  }
  sessionStorage.setItem('notes_api_url', DEFAULT_API_URL);
  return DEFAULT_API_URL;
}

// ── ABDM Patient & Tenant Consent Directory ──────────────────────────────
const ABDM_PATIENT_DIRECTORY = {
  'PAT-ABDM-001': {
    name: 'Anita Deshmukh',
    age: 52,
    gender: 'F',
    diagnosis: 'Stage II Invasive Ductal Carcinoma Breast',
    abhaId: '14-8765-4321-9876',
    homeTenant: 'TMH-MUMBAI',
    tenants: {
      'TMH-MUMBAI': {
        name: 'Tata Memorial Hospital, Mumbai',
        isHome: true,
        consentId: 'LOCAL-EHR-TMH',
        status: 'LIVE',
        validity: 'Permanent (Home Facility Record)',
        purpose: 'PRIMARY_CARE'
      },
      'APOLLO-BLR': {
        name: 'Apollo Hospitals, Bengaluru',
        isHome: false,
        consentId: 'CONSENT-ABDM-9901-APOLLO',
        status: 'LIVE',
        validity: 'Valid till 31 Dec 2026',
        purpose: 'CARETREAT (Care & Treatment)'
      },
      'AIIMS-DEL': {
        name: 'AIIMS, New Delhi',
        isHome: false,
        consentId: 'CONSENT-ABDM-8802-AIIMS',
        status: 'EXPIRED',
        validity: 'Expired on 31 Aug 2026',
        expiredAt: '2026-08-31',
        purpose: 'CARETREAT (Care & Treatment)'
      }
    }
  },
  'PAT-ABDM-002': {
    name: 'Vikram Malhotra',
    age: 64,
    gender: 'M',
    diagnosis: 'Stage IB Non-Small Cell Lung Carcinoma',
    abhaId: '14-1234-5678-9012',
    homeTenant: 'MAX-DELHI',
    tenants: {
      'MAX-DELHI': {
        name: 'Max Super Speciality Hospital, Delhi',
        isHome: true,
        consentId: 'LOCAL-EHR-MAX',
        status: 'LIVE',
        validity: 'Permanent (Home Facility Record)',
        purpose: 'PRIMARY_CARE'
      },
      'FORTIS-NCR': {
        name: 'Fortis Memorial Research Institute, Gurugram',
        isHome: false,
        consentId: 'CONSENT-ABDM-9902-FORTIS',
        status: 'LIVE',
        validity: 'Valid till 31 Dec 2026',
        purpose: 'CARETREAT (Care & Treatment)'
      }
    }
  }
};

// ── Facility Configuration ───────────────────────────────────────────────
const DEFAULT_FACILITY = 'TMH-MUMBAI';

const FACILITY_NAMES = {
  'TMH-MUMBAI': 'Tata Memorial Hospital, Mumbai',
  'APOLLO-BLR': 'Apollo Hospitals, Bengaluru',
  'AIIMS-DEL': 'AIIMS, New Delhi',
  'MAX-DELHI': 'Max Super Speciality Hospital, Delhi',
  'FORTIS-NCR': 'Fortis Memorial Research Institute, Gurugram'
};

function getInitialFacility() {
  const stored = sessionStorage.getItem('notes_tenant');
  if (stored && stored !== 'HOSP-WEST' && stored !== 'DEFAULT_TENANT') {
    return stored;
  }
  return DEFAULT_FACILITY;
}

// ── State ─────────────────────────────────────────────────────────────────
const initialFacility = getInitialFacility();

const state = {
  apiBaseUrl: getInitialApiUrl(),
  currentFacility: initialFacility, // Active login hospital
  selectedTenant: initialFacility,  // Currently viewed tenant in dropdown
  activeQueryTenant: initialFacility,
  activeConsentId: null,
  currentPatientId: null,
  currentPatientData: null,
  tenantId: initialFacility,
  recentPatients: [],        // in-memory only: [{ patientId, tenantId, ts }], max 10
  currentPatient: null,
  allNotes: [],             // all fetched notes (current page)
  displayedNotes: [],        // after client-side filter
  selectedNote: null,
  fhirViewOpen: false,
  loading: false,
  servedFrom: null,
  responseMs: 0,
  pagination: { page: 1, limit: 20, total: 0 },
  filters: { type: 'ALL', text: '' }
};

// ── Type → category mapping ────────────────────────────────────────────────
const NOTE_CATEGORIES = {
  MEDICAL_ONCOLOGY_PROGRESS: 'ONCOLOGY',
  RADIATION_ONCOLOGY_NOTE: 'ONCOLOGY',
  TREATMENT_PLAN: 'TREATMENT',
  PATHOLOGY_REPORT: 'LAB',
  LAB_REPORT: 'LAB',
  RADIOLOGY_REPORT: 'RADIOLOGY',
  DISCHARGE_SUMMARY: 'DISCHARGE',
  OPERATIVE_NOTE: 'SURGERY',
  SURGICAL_NOTE: 'SURGERY',
};

function categoryOf(noteType) {
  if (!noteType) return 'generic';
  return (NOTE_CATEGORIES[noteType] || 'generic').toLowerCase();
}

function typeLabel(noteType) {
  if (!noteType) return 'Note';
  return noteType.replace(/_/g, ' ');
}

function typeShortLabel(noteType) {
  const map = {
    MEDICAL_ONCOLOGY_PROGRESS: 'MED ONC',
    RADIATION_ONCOLOGY_NOTE: 'RAD ONC',
    TREATMENT_PLAN: 'PLAN',
    PATHOLOGY_REPORT: 'PATH',
    LAB_REPORT: 'LAB',
    RADIOLOGY_REPORT: 'RADIOLOGY',
    DISCHARGE_SUMMARY: 'DISCHARGE',
    OPERATIVE_NOTE: 'OT',
    SURGICAL_NOTE: 'SURGERY',
  };
  return map[noteType] || (noteType || 'NOTE').substring(0, 8);
}

// ── API ───────────────────────────────────────────────────────────────────
async function apiFetch(path, options = {}) {
  const url = state.apiBaseUrl.replace(/\/$/, '') + path;
  const tenantVal = state.activeQueryTenant || state.currentFacility || state.tenantId || 'TMH-MUMBAI';
  const headers = {
    'X-Tenant-Id': tenantVal,
    ...(options.headers || {})
  };
  if (options.body && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json';
  }
  const t0 = Date.now();
  const res = await fetch(url, { ...options, headers });
  state.responseMs = Date.now() - t0;
  if (!res.ok) {
    const err = await res.text().catch(() => res.statusText);
    throw new Error(`API error ${res.status}: ${err}`);
  }
  return res.json();
}

async function fetchNotes(patientId, page = 1) {
  const limit = state.pagination.limit;
  const tenantVal = state.activeQueryTenant || state.currentFacility || state.tenantId || 'TMH-MUMBAI';
  const tenantParam = `&tenantId=${encodeURIComponent(tenantVal)}`;
  const consentParam = state.activeConsentId ? `&consentId=${encodeURIComponent(state.activeConsentId)}` : '';
  const data = await apiFetch(
    `/api/v1/patients/${encodeURIComponent(patientId)}/notes?page=${page}&limit=${limit}${tenantParam}${consentParam}`
  );
  return data;
}

async function evictPatient(patientId) {
  return apiFetch(`/api/v1/patients/${encodeURIComponent(patientId)}/index`, {
    method: 'DELETE'
  });
}

function updateFacilityDisplay(facilityId) {
  const eff = facilityId || DEFAULT_FACILITY;
  state.currentFacility = eff;
  state.tenantId = eff;
  const hospDisplay = el('current-hospital-display');
  if (hospDisplay) hospDisplay.textContent = eff;
  const descDisplay = document.querySelector('.facility-desc');
  if (descDisplay) descDisplay.textContent = FACILITY_NAMES[eff] || `${eff} Hospital`;
  document.querySelectorAll('.curr-tenant-text').forEach(node => {
    node.textContent = eff;
  });
}

// ── Settings ─────────────────────────────────────────────────────────────
function loadSettings() {
  const url = sessionStorage.getItem('notes_api_url');
  const tenant = sessionStorage.getItem('notes_tenant');
  if (url && (url.startsWith('http://') || url.startsWith('https://')) && !url.slice(8).includes('http') && !url.includes('localhost:3000')) {
    state.apiBaseUrl = url;
  } else {
    state.apiBaseUrl = DEFAULT_API_URL;
    sessionStorage.setItem('notes_api_url', state.apiBaseUrl);
  }
  const effectiveTenant = (tenant && tenant !== 'HOSP-WEST' && tenant !== 'DEFAULT_TENANT')
    ? tenant
    : DEFAULT_FACILITY;
  sessionStorage.setItem('notes_tenant', effectiveTenant);
  updateFacilityDisplay(effectiveTenant);
  el('settings-url').value = state.apiBaseUrl;
  el('settings-tenant').value = state.tenantId;
}

function saveSettings() {
  const url = el('settings-url').value.trim();
  const tenant = el('settings-tenant').value.trim();
  if (!url) { toast('API URL cannot be empty', 'error'); return; }
  try {
    const parsed = new URL(url);
    if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error('Must start with http:// or https://');
  } catch (e) {
    toast('Invalid URL format: ' + e.message, 'error');
    return;
  }
  state.apiBaseUrl = url.replace(/\/$/, '');
  const effectiveTenant = tenant || state.currentFacility || DEFAULT_FACILITY;
  sessionStorage.setItem('notes_api_url', state.apiBaseUrl);
  sessionStorage.setItem('notes_tenant', effectiveTenant);
  updateFacilityDisplay(effectiveTenant);
  closeSettings();
  toast(`Facility configured: ${effectiveTenant} ✓`, 'success');
}

function toggleSettings() {
  const panel = el('settings-panel');
  panel.classList.toggle('open');
  el('settings-btn').classList.toggle('active');
}
function closeSettings() {
  el('settings-panel').classList.remove('open');
  el('settings-btn').classList.remove('active');
}

// ── ABDM Patient & Consent Management ─────────────────────────────────────

function handlePatientIdSearch() {
  const patientInput = el('sidebar-patient-input');
  if (!patientInput) return;
  const patientId = patientInput.value.trim();
  if (!patientId) {
    toast('Enter a Patient ID', 'warn');
    return;
  }
  performPatientSearch(patientId, 'BY_PATIENT_ID');
}

function handleAbhaSearch() {
  const abhaInput = el('sidebar-abha-input');
  if (!abhaInput) return;
  const abhaId = abhaInput.value.trim();
  if (!abhaId) {
    toast('Enter an ABHA number (e.g. 14-8765-4321-9876)', 'warn');
    return;
  }
  performPatientSearch(abhaId, 'BY_ABHA');
}

function performPatientSearch(searchKey, mode = 'BY_PATIENT_ID') {
  let matchedPatientId = null;
  let pData = null;

  if (mode === 'BY_PATIENT_ID') {
    matchedPatientId = searchKey;
    pData = ABDM_PATIENT_DIRECTORY[searchKey];
    if (!pData) {
      const byAbha = Object.entries(ABDM_PATIENT_DIRECTORY).find(([_, d]) => d.abhaId === searchKey);
      if (byAbha) {
        matchedPatientId = byAbha[0];
        pData = byAbha[1];
      }
    }
  } else {
    // Search by ABHA
    const byAbha = Object.entries(ABDM_PATIENT_DIRECTORY).find(([_, d]) => d.abhaId === searchKey || d.abhaId.replace(/-/g, '') === searchKey.replace(/-/g, ''));
    if (byAbha) {
      matchedPatientId = byAbha[0];
      pData = byAbha[1];
    } else if (ABDM_PATIENT_DIRECTORY[searchKey]) {
      matchedPatientId = searchKey;
      pData = ABDM_PATIENT_DIRECTORY[searchKey];
    }
  }

  // Fallback for unlisted patient
  if (!pData) {
    matchedPatientId = searchKey;
    const isAbha = searchKey.startsWith('14-') || searchKey.length === 17;
    const randomAbha = '14-' + Math.floor(1000 + Math.random() * 9000) + '-' + Math.floor(1000 + Math.random() * 9000) + '-' + Math.floor(1000 + Math.random() * 9000);
    pData = {
      name: 'Patient ' + searchKey,
      age: 50,
      gender: '—',
      diagnosis: 'Clinical Evaluation',
      abhaId: isAbha ? searchKey : randomAbha,
      homeTenant: state.currentFacility,
      tenants: {
        [state.currentFacility]: {
          name: state.currentFacility,
          isHome: true,
          consentId: 'LOCAL-EHR-' + state.currentFacility,
          status: 'LIVE',
          validity: 'Permanent (Home Facility Record)',
          purpose: 'PRIMARY_CARE'
        }
      }
    };
  }

  // Synchronize inputs
  el('sidebar-patient-input').value = matchedPatientId;
  el('sidebar-abha-input').value = pData.abhaId;
  el('header-search').value = matchedPatientId;

  state.currentPatientId = matchedPatientId;
  state.currentPatientData = pData;

  // Populate tenant dropdown
  populateTenantDropdown(pData);

  // Per requirement: If search is done by patient ID, it is ALWAYS for the current tenant
  let targetTenant = state.currentFacility;
  if (!pData.tenants[targetTenant]) {
    targetTenant = pData.homeTenant || Object.keys(pData.tenants)[0];
  }

  const sel = el('tenant-select');
  if (sel) sel.value = targetTenant;
  handleTenantChange(targetTenant);

  toast(`ABDM Profile: ${pData.name} (ABHA: ${pData.abhaId})`, 'success');
}

function populateTenantDropdown(pData) {
  const sel = el('tenant-select');
  if (!sel) return;
  sel.innerHTML = '';

  const tenantEntries = Object.entries(pData.tenants || {});

  // Add individual facility options
  tenantEntries.forEach(([tId, tInfo]) => {
    const opt = document.createElement('option');
    opt.value = tId;
    let badge = 'Live Consent';
    if (tInfo.isHome) badge = 'Home EHR';
    else if (tInfo.status === 'EXPIRED') badge = 'Expired Consent';
    opt.textContent = `${tId} — ${tInfo.name} (${badge})`;
    sel.appendChild(opt);
  });

  // Add ALL option if more than 1 facility exists
  if (tenantEntries.length > 1) {
    const optAll = document.createElement('option');
    optAll.value = 'ALL';
    optAll.textContent = 'ALL — All ABDM Facilities (Federated View)';
    sel.appendChild(optAll);
  }
}

async function handleTenantChange(selectedTenant) {
  state.selectedTenant = selectedTenant;
  const pData = state.currentPatientData;
  const pId = state.currentPatientId || el('sidebar-patient-input').value.trim();

  if (!pData || !pId) return;

  if (selectedTenant !== 'ALL') {
    hideWarningBanner();
    const tInfo = pData.tenants[selectedTenant];
    if (!tInfo) return;

    updateConsentCard(tInfo);

    // If Consent is EXPIRED: throw warning and disallow user to view notes
    if (tInfo.status === 'EXPIRED') {
      showBlockedView(selectedTenant, tInfo);
      toast(`⚠️ Access Restricted: ABDM Consent for ${tInfo.name || selectedTenant} has expired!`, 'error');

      state.allNotes = [];
      state.displayedNotes = [];
      state.currentPatient = { patientId: pId, tenantId: selectedTenant };
      state.servedFrom = 'RESTRICTED (CONSENT EXPIRED)';
      state.pagination = { page: 1, limit: state.pagination.limit, total: 0 };
      updateFilterStats();
      renderSidebarPatientInfo();
      return;
    }

    // Consent is LIVE or HOME
    hideBlockedView();
    state.activeQueryTenant = selectedTenant;
    state.activeConsentId = tInfo.consentId;
    await executeNotesQuery(pId, selectedTenant, tInfo.consentId);

  } else {
    // User selected "ALL" (all hospitals)
    hideBlockedView();
    const tenants = Object.entries(pData.tenants);
    const expiredList = tenants.filter(([_, t]) => t.status === 'EXPIRED');
    const liveList = tenants.filter(([_, t]) => t.status === 'LIVE' || t.isHome);

    if (expiredList.length > 0) {
      const expiredNames = expiredList.map(([id, t]) => `${t.name} (${id})`).join(', ');
      const liveNames = liveList.map(([id]) => id).join(', ');

      showWarningBanner(
        `ABDM Notice: Records from ${expiredList.map(([id]) => id).join(', ')} Excluded (Consent Expired)`,
        `Clinical records from <strong>${esc(expiredNames)}</strong> cannot be displayed because patient consent has expired. Permitted notes from active facilities (<strong>${esc(liveNames)}</strong>) are displayed below in compliance with ABDM policies.`
      );
      toast(`⚠️ Notice: Notes from ${expiredList.map(([id]) => id).join(', ')} omitted due to expired consent`, 'warn');

      updateConsentCard({
        consentId: `Active: ${liveList.map(([_, t]) => t.consentId).join(', ')}`,
        status: 'PARTIAL',
        validity: `${liveList.length} Live / ${expiredList.length} Expired`,
        purpose: 'CARETREAT (Permitted Facilities Only)'
      });
    } else {
      hideWarningBanner();
      updateConsentCard({
        consentId: 'MULTI-FACILITY-ALL-ACTIVE',
        status: 'LIVE',
        validity: 'All Tenant Consents Valid',
        purpose: 'CARETREAT'
      });
    }

    // Query backend for ALL and filter out expired tenants client-side
    state.activeQueryTenant = 'ALL';
    const validConsent = liveList.find(([_, t]) => !t.isHome)?.[1]?.consentId || 'LOCAL-EHR';
    state.activeConsentId = validConsent;
    const expiredTenantIds = expiredList.map(([id]) => id);
    await executeNotesQuery(pId, 'ALL', validConsent, expiredTenantIds);
  }
}

function updateConsentCard(c) {
  const card = el('consent-status-card');
  if (!card) return;
  card.style.display = 'block';

  const badge = el('consent-badge');
  const idEl = el('consent-id-val');
  const valEl = el('consent-validity-val');
  const purEl = el('consent-purpose-val');

  if (idEl) idEl.textContent = c.consentId || '—';
  if (valEl) valEl.textContent = c.validity || '—';
  if (purEl) purEl.textContent = c.purpose || 'CARETREAT (Care & Treatment)';

  if (badge) {
    badge.className = '';
    if (c.status === 'EXPIRED') {
      badge.className = 'consent-badge-expired';
      badge.textContent = 'EXPIRED';
    } else if (c.status === 'PARTIAL') {
      badge.className = 'consent-badge-expired';
      badge.style.background = 'rgba(245, 158, 11, 0.15)';
      badge.style.color = '#d97706';
      badge.style.border = '1px solid rgba(245, 158, 11, 0.35)';
      badge.textContent = 'PARTIAL (1 EXPIRED)';
    } else if (c.isHome) {
      badge.className = 'consent-badge-home';
      badge.textContent = 'HOME RECORD';
    } else {
      badge.className = 'consent-badge-live';
      badge.textContent = 'LIVE';
    }
  }
}

function showBlockedView(tenantId, tInfo) {
  const blocked = el('expired-tenant-blocked-view');
  const timeline = el('notes-timeline');
  if (blocked) {
    blocked.classList.remove('hidden');
    el('blocked-title').textContent = `Access Restricted: ABDM Consent Expired for ${tenantId}`;
    el('blocked-desc').textContent = `Clinical notes from ${tInfo.name || tenantId} cannot be displayed. The patient consent artefact (${tInfo.consentId}) expired on ${tInfo.expiredAt || tInfo.validity}. Under ABDM HIU policy, viewing notes without active consent is strictly disallowed.`;
    el('blocked-hospital-name').textContent = `${tInfo.name || tenantId} (${tenantId})`;
    el('blocked-artefact-id').textContent = tInfo.consentId || '—';
    el('blocked-expiry-date').textContent = tInfo.validity || tInfo.expiredAt || 'Expired';
  }
  if (timeline) {
    timeline.style.display = 'none';
  }
}

function hideBlockedView() {
  const blocked = el('expired-tenant-blocked-view');
  const timeline = el('notes-timeline');
  if (blocked) blocked.classList.add('hidden');
  if (timeline) timeline.style.display = 'flex';
}

function showWarningBanner(title, html) {
  const banner = el('all-tenants-warning-banner');
  if (!banner) return;
  banner.classList.remove('hidden');
  el('warning-banner-title').textContent = title;
  el('all-tenants-warning-text').innerHTML = html;
}

function hideWarningBanner() {
  const banner = el('all-tenants-warning-banner');
  if (banner) banner.classList.add('hidden');
}

async function executeNotesQuery(patientId, tenantId, consentId, excludeTenants = []) {
  setLoading(true);
  try {
    const data = await fetchNotes(patientId, 1);
    let rawNotes = data.notes || [];

    // CRITICAL: When viewing a specific hospital (e.g. TMH-MUMBAI), strictly return current tenant data ONLY!
    if (tenantId && tenantId !== 'ALL') {
      rawNotes = rawNotes.filter(n => n.tenantId === tenantId);
    }

    // Exclude notes from expired tenants when ALL is selected
    if (excludeTenants && excludeTenants.length > 0) {
      rawNotes = rawNotes.filter(n => !excludeTenants.includes(n.tenantId));
    }

    state.loading = false;
    state.currentPatient = { patientId, tenantId };
    state.allNotes = rawNotes;
    state.servedFrom = data.servedFrom;
    state.pagination = { page: 1, limit: state.pagination.limit, total: rawNotes.length };
    state.filters = { type: 'ALL', text: '' };
    state.selectedNote = null;
    state.fhirViewOpen = false;

    addRecentPatient(patientId, tenantId);
    applyFilters();
    renderSidebarPatientInfo();
    renderRecentList();
    closeDetailPanel();
    el('header-search').value = patientId;

    const count = rawNotes.length;
    toast(`Loaded ${count} notes ${tenantId === 'ALL' ? 'across permitted facilities' : `for ${tenantId}`} — ${data.servedFrom || ''}`, 'success');
  } catch (e) {
    state.loading = false;
    toast('Error: ' + e.message, 'error');
    renderEmptyState('error', e.message);
  } finally {
    state.loading = false;
  }
}

function simulateConsentRenewal() {
  const selTenant = state.selectedTenant;
  if (!state.currentPatientData || !selTenant) return;
  const tInfo = state.currentPatientData.tenants[selTenant];
  if (!tInfo) return;

  toast(`Initiating ABDM OTP consent renewal for ${tInfo.name}...`, 'info');
  setTimeout(() => {
    tInfo.status = 'LIVE';
    tInfo.consentId = tInfo.consentId + '-RENEWED';
    tInfo.validity = 'Valid till 31 Dec 2026 (Renewed via ABDM OTP)';
    toast(`✓ ABDM Consent Granted for ${tInfo.name}! Validity renewed.`, 'success');
    populateTenantDropdown(state.currentPatientData);
    el('tenant-select').value = selTenant;
    handleTenantChange(selTenant);
  }, 1000);
}

function loadDemoPatient(patientId) {
  el('sidebar-patient-input').value = patientId;
  handlePatientIdSearch();
}

async function searchPatient(patientId, tenantId) {
  if (tenantId) {
    state.tenantId = tenantId;
    state.currentFacility = tenantId;
    const hospDisp = el('current-hospital-display');
    if (hospDisp) hospDisp.textContent = tenantId;
  }
  el('sidebar-patient-input').value = patientId;
  handlePatientIdSearch();
}

function renderEmptyState(type = 'default', message = '') {
  const container = el('notes-timeline');
  if (type === 'error') {
    container.innerHTML = `<div class="state-placeholder">
      <div class="state-icon">⚠️</div>
      <div class="state-title">Error Loading Notes</div>
      <div class="state-sub">${esc(message || 'Unable to connect to API server. Verify API Base URL in settings (⚙) and ensure SAM local / backend is running.')}</div>
    </div>`;
  } else {
    renderNoteCards();
  }
}

function addRecentPatient(patientId, tenantId) {
  state.recentPatients = state.recentPatients.filter(r => r.patientId !== patientId);
  state.recentPatients.unshift({ patientId, tenantId, ts: Date.now() });
  if (state.recentPatients.length > 10) state.recentPatients.pop();
}

// ── Filters ───────────────────────────────────────────────────────────────
function applyFilters() {
  let notes = [...state.allNotes];
  const typeFilter = state.filters.type;
  const textFilter = state.filters.text.toLowerCase().trim();

  if (typeFilter && typeFilter !== 'ALL') {
    notes = notes.filter(n => {
      const cat = NOTE_CATEGORIES[n.noteType] || 'GENERIC';
      return cat === typeFilter;
    });
  }
  if (textFilter) {
    notes = notes.filter(n => {
      const haystack = [n.noteType, n.author?.name, n.author?.department,
      n.summary, n.sourceSystem, n.facilityId].join(' ').toLowerCase();
      return haystack.includes(textFilter);
    });
  }
  state.displayedNotes = notes;
  renderNoteCards();
  updateFilterStats();
}

// ── Rendering ─────────────────────────────────────────────────────────────
function renderNoteCards() {
  const container = el('notes-timeline');
  if (state.loading) {
    container.innerHTML = `<div class="state-placeholder"><div class="loading-spinner"></div>
      <div class="state-title">Loading notes…</div></div>`;
    return;
  }
  if (!state.currentPatient) {
    container.innerHTML = `<div class="state-placeholder">
      <div class="state-icon">🏥</div>
      <div class="state-title">Search for a patient</div>
      <div class="state-sub">Enter a Patient ID or ABHA ID in the sidebar to view clinical notes. Press <span class="kbd">/</span> to focus search.</div>
    </div>`;
    return;
  }
  if (!state.displayedNotes.length) {
    container.innerHTML = `<div class="state-placeholder">
      <div class="state-icon">📋</div>
      <div class="state-title">No notes found</div>
      <div class="state-sub">No notes match the current filters. Try changing the department filter or clearing the text search.</div>
    </div>`;
    return;
  }

  container.innerHTML = state.displayedNotes.map((note, idx) => {
    const cat = categoryOf(note.noteType);
    const isActive = state.selectedNote && state.selectedNote.noteId === note.noteId;
    const dateStr = formatDate(note.authoredAt);
    const authorName = note.author?.name || '—';
    const dept = note.author?.department || note.sourceSystem || '';
    const summary = note.summary || 'No summary available.';
    const tenantBadge = note.tenantId ? `<span class="note-tenant-badge">${esc(note.tenantId)}</span>` : '';
    return `<div class="note-card ${isActive ? 'active' : ''}" data-idx="${idx}" onclick="selectNote(${idx})">
      <div class="note-card-stripe stripe-${cat}"></div>
      <div class="note-card-body">
        <div class="note-card-top">
          <span class="note-type-badge badge-${cat}">${typeShortLabel(note.noteType)}</span>
          ${tenantBadge}
          <span class="note-card-author">${esc(authorName)}</span>
          <span class="note-card-date">${dateStr}</span>
        </div>
        ${dept ? `<div class="note-card-dept">${esc(dept)}</div>` : ''}
        <div class="note-card-summary">${esc(summary)}</div>
      </div>
    </div>`;
  }).join('');

  renderPaginationBar();
}

function renderSidebarPatientInfo() {
  const card = el('patient-info-card');
  if (!state.currentPatient) { card.classList.remove('visible'); return; }
  const { patientId, tenantId } = state.currentPatient;
  const pData = state.currentPatientData;
  const sf = state.servedFrom || '';
  const noteCount = state.displayedNotes.length;

  card.innerHTML = `
    <div style="display:flex;align-items:baseline;justify-content:space-between;margin-bottom:2px;">
      <div class="patient-mrn">${esc(pData?.name || patientId)}</div>
      <span style="font-size:11px;font-weight:600;color:var(--text-secondary);">${pData ? `${pData.age}y / ${pData.gender}` : ''}</span>
    </div>
    ${pData?.diagnosis ? `<div style="font-size:11px;color:var(--text-muted);margin-bottom:6px;line-height:1.3;">${esc(pData.diagnosis)}</div>` : ''}
    <div class="patient-meta">
      MRN: <span style="font-family:var(--font-mono);font-weight:600;">${esc(patientId)}</span><br>
      ABHA: <span style="font-family:var(--font-mono);font-weight:600;">${esc(pData?.abhaId || '—')}</span><br>
      Facility View: <span>${esc(tenantId)}</span> · Notes: <span>${noteCount}</span>
      ${state.responseMs ? ` · <span>${state.responseMs}ms</span>` : ''}
    </div>
    <div class="served-from-badge ${sf.includes('S3') ? 's3' : (sf.includes('RESTRICTED') ? 'expired' : 'index')}">
      ${sf.includes('S3') ? '💾' : (sf.includes('RESTRICTED') ? '⛔' : '⚡')} ${sf || 'INDEX'}
    </div>`;
  card.classList.add('visible');
  el('evict-btn').disabled = false;
}

function renderRecentList() {
  const list = el('recent-list');
  if (!state.recentPatients.length) {
    list.innerHTML = `<div style="padding:12px;font-size:11px;color:var(--text-muted);text-align:center;">
      No recent patients this session</div>`;
    return;
  }
  list.innerHTML = state.recentPatients.map(r => {
    const isActive = state.currentPatient && state.currentPatient.patientId === r.patientId;
    return `<div class="recent-item ${isActive ? 'active' : ''}"
        onclick="searchPatient('${esc(r.patientId)}','${esc(r.tenantId)}')">
      <div class="recent-dot"></div>
      <div class="recent-patient-id">${esc(r.patientId)}</div>
      <div class="recent-tenant">${esc(r.tenantId)}</div>
    </div>`;
  }).join('');
}

function renderPaginationBar() {
  const { page, limit, total } = state.pagination;
  const totalPages = Math.max(1, Math.ceil(total / limit));
  const from = (page - 1) * limit + 1;
  const to = Math.min(page * limit, total);
  el('page-info').textContent = `${from}–${to} of ${total} notes`;
  el('prev-page-btn').disabled = page <= 1;
  el('next-page-btn').disabled = page >= totalPages;
}

function updateFilterStats() {
  const displayed = state.displayedNotes.length;
  const total = state.allNotes.length;
  el('filter-stats').textContent =
    displayed === total ? `${total} notes` : `${displayed} / ${total} notes`;
}

// ── Note Detail Panel ─────────────────────────────────────────────────────
function selectNote(idx) {
  const note = state.displayedNotes[idx];
  if (!note) return;
  state.selectedNote = note;
  state.fhirViewOpen = false;
  openDetailPanel(note);
  renderNoteCards(); // re-render to show active highlight
}

function openDetailPanel(note) {
  const panel = el('detail-panel');
  panel.classList.add('open');

  const cat = categoryOf(note.noteType);
  el('detail-type-badge').className = `detail-type-badge badge-${cat}`;
  el('detail-type-badge').textContent = typeShortLabel(note.noteType);

  // Meta section
  el('detail-meta').innerHTML = `
    <div class="detail-meta-row"><span class="detail-meta-label">Note ID</span>
      <span class="detail-meta-value">${esc(note.noteId || '—')}</span></div>
    <div class="detail-meta-row"><span class="detail-meta-label">Author</span>
      <span class="detail-meta-value">${esc(note.author?.name || '—')}</span></div>
    <div class="detail-meta-row"><span class="detail-meta-label">Dept</span>
      <span class="detail-meta-value">${esc(note.author?.department || '—')}</span></div>
    <div class="detail-meta-row"><span class="detail-meta-label">Authored</span>
      <span class="detail-meta-value">${formatDateLong(note.authoredAt)}</span></div>
    <div class="detail-meta-row"><span class="detail-meta-label">Recorded</span>
      <span class="detail-meta-value">${formatDateLong(note.recordedAt)}</span></div>
    <div class="detail-meta-row"><span class="detail-meta-label">Source</span>
      <span class="detail-meta-value">${esc(note.sourceSystem || '—')}</span></div>
    <div class="detail-meta-row"><span class="detail-meta-label">Facility</span>
      <span class="detail-meta-value">${esc(note.facilityId || '—')}</span></div>`;

  // Body — summary + sections
  let bodyHtml = '';

  if (note.summary) {
    bodyHtml += `<p class="section-heading">Summary</p>
    <div class="detail-summary-box">${esc(note.summary)}</div>`;
  }

  // Coding
  if (note.coding && note.coding.length) {
    bodyHtml += `<p class="section-heading">Clinical Codes</p>
    <div class="coding-tags">` +
      note.coding.map(c =>
        `<div class="coding-tag"><span class="code-system">${esc(c.system || '')}</span> ${esc(c.code || '')} — ${esc(c.display || '')}</div>`
      ).join('') + `</div>`;
  }

  // FHIR view toggle (collapsed by default per user request)
  bodyHtml += `
    <div class="fhir-toggle-row">
      <button class="fhir-toggle-btn" id="fhir-toggle-btn" onclick="toggleFhirView()">
        <span>⚕</span> FHIR R4 View
      </button>
      <span class="fhir-dev-label">Developer / Integrator view</span>
    </div>
    <div class="fhir-json-view" id="fhir-json-view"></div>`;

  el('detail-body').innerHTML = bodyHtml;
}

function closeDetailPanel() {
  el('detail-panel').classList.remove('open');
  state.selectedNote = null;
  state.fhirViewOpen = false;
}

// ── FHIR View (optional dev view) ─────────────────────────────────────────
async function toggleFhirView() {
  state.fhirViewOpen = !state.fhirViewOpen;
  const btn = el('fhir-toggle-btn');
  const view = el('fhir-json-view');
  if (!btn || !view) return;

  btn.classList.toggle('active', state.fhirViewOpen);

  if (state.fhirViewOpen) {
    view.classList.add('visible');
    if (state.selectedNote && state.selectedNote.noteId) {
      view.textContent = 'Loading FHIR DocumentReference…';
      try {
        const res = await fetch(
          state.apiBaseUrl.replace(/\/$/, '') + '/fhir/r4/DocumentReference/' + state.selectedNote.noteId,
          { headers: { 'X-Tenant-Id': state.tenantId } }
        );
        if (res.ok) {
          const fhirDoc = await res.json();
          view.innerHTML = syntaxHighlightJson(JSON.stringify(fhirDoc, null, 2));
        } else {
          view.textContent = 'FHIR endpoint returned ' + res.status;
        }
      } catch (e) {
        view.textContent = 'Could not load FHIR view: ' + e.message;
      }
    } else {
      view.innerHTML = syntaxHighlightJson(JSON.stringify({
        resourceType: 'DocumentReference',
        id: state.selectedNote?.noteId,
        status: 'current',
        note: 'FHIR endpoint requires SAM restart with new notes-fhir-handler JAR'
      }, null, 2));
    }
  } else {
    view.classList.remove('visible');
  }
}

function syntaxHighlightJson(jsonStr) {
  return jsonStr
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+-]?\d+)?)/g,
      function (match) {
        let cls = 'json-number';
        if (/^"/.test(match)) { cls = /:$/.test(match) ? 'json-key' : 'json-string'; }
        else if (/true|false/.test(match)) { cls = 'json-bool'; }
        else if (/null/.test(match)) { cls = 'json-null'; }
        return `<span class="${cls}">${match}</span>`;
      });
}

// ── Eviction ──────────────────────────────────────────────────────────────
async function evictCurrentPatient() {
  if (!state.currentPatient) return;
  const { patientId } = state.currentPatient;
  if (!confirm(`Evict ${patientId} from the warm index? (Data remains in S3)`)) return;
  try {
    const res = await evictPatient(patientId);
    toast(`Evicted ${res.deletedFromIndex || 0} notes from index. S3 intact.`, 'success');
    // Reload notes (will trigger S3 rehydration on next GET)
    await searchPatient(patientId, state.tenantId);
  } catch (e) {
    toast('Eviction failed: ' + e.message, 'error');
  }
}

// ── Pagination ────────────────────────────────────────────────────────────
async function goToPage(delta) {
  if (!state.currentPatient) return;
  const newPage = state.pagination.page + delta;
  if (newPage < 1) return;
  setLoading(true);
  try {
    const data = await fetchNotes(state.currentPatient.patientId, newPage);
    state.loading = false;
    state.allNotes = data.notes || [];
    state.servedFrom = data.servedFrom;
    state.pagination = { ...state.pagination, page: newPage, total: data.totalNotes };
    applyFilters();
    renderSidebarPatientInfo();
  } catch (e) {
    state.loading = false;
    toast('Page load error: ' + e.message, 'error');
  } finally {
    state.loading = false;
  }
}

// ── Keyboard Shortcuts ────────────────────────────────────────────────────
document.addEventListener('keydown', e => {
  const target = e.target;
  const tag = (target && target.tagName) ? target.tagName.toUpperCase() : '';
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (target && target.isContentEditable)) {
    if (e.key === 'Escape') {
      target.blur();
      closeSettings();
    }
    return;
  }

  if (e.key === '/') {
    e.preventDefault();
    el('sidebar-patient-input').focus();
    el('sidebar-patient-input').select();
  } else if (e.key === 'Escape') {
    closeDetailPanel();
    closeSettings();
  } else if (e.key === 'n' || e.key === 'ArrowDown') {
    navigateNotes(1);
  } else if (e.key === 'p' || e.key === 'ArrowUp') {
    navigateNotes(-1);
  } else if (e.key === 'f') {
    el('filter-text-search').focus();
  }
});

function navigateNotes(delta) {
  if (!state.displayedNotes.length) return;
  const currentIdx = state.selectedNote
    ? state.displayedNotes.findIndex(n => n.noteId === state.selectedNote.noteId)
    : -1;
  const nextIdx = Math.max(0, Math.min(state.displayedNotes.length - 1, currentIdx + delta));
  selectNote(nextIdx);
}

// ── Utilities ─────────────────────────────────────────────────────────────
function el(id) { return document.getElementById(id); }
function esc(str) {
  if (str == null) return '';
  return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function formatDate(iso) {
  if (!iso) return '—';
  try {
    const d = new Date(iso);
    return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: '2-digit' });
  } catch { return iso; }
}
function formatDateLong(iso) {
  if (!iso) return '—';
  try {
    const d = new Date(iso);
    return d.toLocaleString('en-IN', {
      day: '2-digit', month: 'short', year: 'numeric',
      hour: '2-digit', minute: '2-digit', hour12: false
    });
  } catch { return iso; }
}

function setLoading(val) {
  state.loading = val;
  if (val) {
    el('notes-timeline').innerHTML = `<div class="state-placeholder">
      <div class="loading-spinner"></div>
      <div class="state-title">Fetching notes…</div></div>`;
  }
}

// ── Filter chip events ────────────────────────────────────────────────────
function setTypeFilter(type) {
  state.filters.type = type;
  document.querySelectorAll('.chip[data-type]').forEach(c => {
    c.classList.toggle('active', c.dataset.type === type);
  });
  applyFilters();
}

function setTextFilter(text) {
  state.filters.text = text;
  applyFilters();
}

// ── Toast ─────────────────────────────────────────────────────────────────
function toast(msg, type = 'info') {
  const icons = { success: '✓', error: '✕', warn: '⚠', info: 'ℹ' };
  const container = el('toast-container');
  const t = document.createElement('div');
  t.className = `toast ${type}`;
  t.innerHTML = `<span>${icons[type] || '·'}</span> ${esc(msg)}`;
  container.appendChild(t);
  setTimeout(() => t.remove(), 3500);
}

// ── Init ──────────────────────────────────────────────────────────────────
function init() {
  loadSettings();
  renderNoteCards();    // shows empty state
  renderRecentList();

  // Wire filter chips
  document.querySelectorAll('.chip[data-type]').forEach(c => {
    c.addEventListener('click', () => setTypeFilter(c.dataset.type));
  });
  el('filter-text-search').addEventListener('input', e => setTextFilter(e.target.value));

  // Wire header search
  const headerSearch = el('header-search');
  if (headerSearch) {
    headerSearch.addEventListener('keydown', e => {
      if (e.key === 'Enter') {
        const val = headerSearch.value.trim();
        if (val) {
          if (val.startsWith('14-') || val.length === 17) {
            el('sidebar-abha-input').value = val;
            handleAbhaSearch();
          } else {
            el('sidebar-patient-input').value = val;
            handlePatientIdSearch();
          }
        }
      }
    });
  }

  // Wire Patient ID form
  const patientForm = el('patient-id-form');
  if (patientForm) {
    patientForm.addEventListener('submit', e => {
      e.preventDefault();
      handlePatientIdSearch();
    });
  }

  // Wire ABHA ID form
  const abhaForm = el('abha-id-form');
  if (abhaForm) {
    abhaForm.addEventListener('submit', e => {
      e.preventDefault();
      handleAbhaSearch();
    });
  }

  // Wire settings
  el('settings-btn').addEventListener('click', toggleSettings);
  el('settings-save-btn').addEventListener('click', saveSettings);

  // Update settings inputs from state
  el('settings-url').value = state.apiBaseUrl;
  el('settings-tenant').value = state.tenantId;

  // Wire pagination
  el('prev-page-btn').addEventListener('click', () => goToPage(-1));
  el('next-page-btn').addEventListener('click', () => goToPage(1));

  // Wire evict
  el('evict-btn').addEventListener('click', evictCurrentPatient);

  // Wire detail close
  el('detail-close-btn').addEventListener('click', closeDetailPanel);

  // Close settings on outside click
  document.addEventListener('click', e => {
    const panel = el('settings-panel');
    if (panel.classList.contains('open')
      && !panel.contains(e.target)
      && !el('settings-btn').contains(e.target)) {
      closeSettings();
    }
  });

  console.log('Clinical Notes Aggregator loaded. API:', state.apiBaseUrl);
}

document.addEventListener('DOMContentLoaded', init);
