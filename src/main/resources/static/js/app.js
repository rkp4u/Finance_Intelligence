// ============================================
// Section 1: API Client
// ============================================
const API = '/api/v1/knowledge-bases';

const api = {
    async listKBs() { return (await fetch(API)).json(); },
    async createKB(name) {
        return (await fetch(API, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name, description: '' }) })).json();
    },
    async deleteKB(id) { return fetch(`${API}/${id}`, { method: 'DELETE' }); },

    async listDocs(kbId) { return (await fetch(`${API}/${kbId}/documents`)).json(); },
    async getDoc(kbId, docId) { return (await fetch(`${API}/${kbId}/documents/${docId}`)).json(); },
    async uploadDoc(kbId, file) {
        const fd = new FormData(); fd.append('file', file);
        return (await fetch(`${API}/${kbId}/documents`, { method: 'POST', body: fd })).json();
    },
    async deleteDoc(kbId, docId) { return fetch(`${API}/${kbId}/documents/${docId}`, { method: 'DELETE' }); },

    async getFinancialData(kbId, docId) {
        const res = await fetch(`${API}/${kbId}/documents/${docId}/financial-data`);
        return res.ok ? res.json() : null;
    },
    async listFinancialData(kbId) {
        const res = await fetch(`${API}/${kbId}/financial-data`);
        return res.ok ? res.json() : [];
    },
    async compareFinancialData(kbId, docIds) {
        const params = docIds.map(id => `documentIds=${id}`).join('&');
        const res = await fetch(`${API}/${kbId}/financial-data/compare?${params}`);
        return res.ok ? res.json() : { documents: [], documentCount: 0 };
    },
    async reExtract(kbId, docId) {
        const res = await fetch(`${API}/${kbId}/documents/${docId}/financial-data/re-extract`, { method: 'POST' });
        return res.ok ? res.json() : null;
    },
    async reValidate(kbId, docId) {
        return (await fetch(`${API}/${kbId}/documents/${docId}/financial-data/validate`, { method: 'POST' })).json();
    },
    async query(kbId, question, topK = 5) {
        return (await fetch(`${API}/${kbId}/query`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ question, topK })
        })).json();
    },

    async getHealth(kbId) {
        const res = await fetch(`${API}/${kbId}/health`);
        return res.ok ? res.json() : null;
    },
    async getAnomalies(kbId) {
        const res = await fetch(`${API}/${kbId}/health/anomalies`);
        return res.ok ? res.json() : [];
    },
    async getDocumentSummaries(kbId, docId) {
        const res = await fetch(`${API}/${kbId}/documents/${docId}/summaries`);
        return res.ok ? res.json() : [];
    },
    exportCsv(kbId) {
        window.open(`${API}/${kbId}/financial-data/export.csv`, '_blank');
    }
};

// ============================================
// Section 2: State
// ============================================
const state = {
    activeKbId: null,
    activeDocId: null,
    activeTab: 'dashboard',
    documents: [],
    financialDataMap: {},  // docId -> FinancialDataResponse
    pollingIntervals: {},
    compareSelected: new Set(),
    extractionPollId: null
};

// ============================================
// Section 3: Sidebar — Knowledge Bases
// ============================================
async function loadKnowledgeBases() {
    const kbs = await api.listKBs();
    renderKnowledgeBases(kbs);
}

async function createKnowledgeBase() {
    const input = document.getElementById('kb-name');
    const name = input.value.trim();
    if (!name) {
        input.classList.add('shake');
        input.placeholder = 'Please enter a name...';
        setTimeout(() => input.classList.remove('shake'), 400);
        return;
    }
    const res = await api.createKB(name);
    if (res.id) { input.value = ''; await loadKnowledgeBases(); }
    else alert(res.message || 'Failed to create');
}

async function deleteKnowledgeBase(id, event) {
    event.stopPropagation();
    if (!confirm('Delete this knowledge base and all documents?')) return;
    await api.deleteKB(id);
    if (state.activeKbId === id) { state.activeKbId = null; state.activeDocId = null; showEmptyState(); }
    await loadKnowledgeBases();
}

function selectKnowledgeBase(id) {
    // Clear all stale polling from previous KB
    clearAllPolling();
    state.activeKbId = id;
    state.activeDocId = null;
    document.getElementById('empty-state').style.display = 'none';
    document.getElementById('kb-view').style.display = 'flex';
    document.getElementById('docs-panel').style.display = 'flex';
    document.querySelectorAll('.kb-item').forEach(el => el.classList.toggle('active', el.dataset.id === id));
    loadDocuments();
    clearMessages();
    hideDashboardContent();
}

function clearAllPolling() {
    Object.keys(state.pollingIntervals).forEach(id => stopPolling(id));
    if (state.extractionPollId) { clearInterval(state.extractionPollId); state.extractionPollId = null; }
}

function showEmptyState() {
    document.getElementById('empty-state').style.display = 'flex';
    document.getElementById('kb-view').style.display = 'none';
    document.getElementById('docs-panel').style.display = 'none';
}

function renderKnowledgeBases(kbs) {
    document.getElementById('kb-list').innerHTML = kbs.map(kb => `
        <div class="kb-item ${kb.id === state.activeKbId ? 'active' : ''}" data-id="${kb.id}" onclick="selectKnowledgeBase('${kb.id}')">
            <div class="kb-info">
                <div class="kb-name">${esc(kb.name)}</div>
                <div class="kb-docs">${kb.documentCount} document${kb.documentCount !== 1 ? 's' : ''}</div>
            </div>
            <button class="kb-delete" onclick="deleteKnowledgeBase('${kb.id}', event)">x</button>
        </div>
    `).join('');
}

// ============================================
// Section 4: Sidebar — Documents
// ============================================
async function loadDocuments() {
    if (!state.activeKbId) return;
    const [docs, finList] = await Promise.all([
        api.listDocs(state.activeKbId),
        api.listFinancialData(state.activeKbId)
    ]);
    state.documents = docs;
    state.financialDataMap = {};
    finList.forEach(fd => { state.financialDataMap[fd.documentId] = fd; });
    renderDocuments(docs);
    renderCompareSelector();
    docs.filter(d => d.status === 'PROCESSING').forEach(d => startPolling(d.id));
}

function renderDocuments(docs) {
    const list = document.getElementById('docs-list');
    if (docs.length === 0) {
        list.innerHTML = '<div style="color: var(--text-muted); font-size: 0.78rem; padding: 8px;">No documents uploaded</div>';
        return;
    }
    list.innerHTML = docs.map(doc => {
        const fd = state.financialDataMap[doc.id];
        const extractionStatus = fd ? fd.extractionStatus : '';
        return `
        <div class="doc-item ${doc.id === state.activeDocId ? 'active' : ''}" onclick="selectDocument('${doc.id}')">
            <span class="extraction-dot ${extractionStatus}"></span>
            <span class="doc-name">${esc(doc.filename || 'Unknown')}</span>
            <span class="doc-status ${doc.status}">${formatDocStatus(doc)}</span>
            <button class="doc-delete" onclick="deleteDocument('${doc.id}', event)">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
        </div>`;
    }).join('');
}

async function selectDocument(docId) {
    state.activeDocId = docId;
    document.querySelectorAll('.doc-item').forEach(el => el.classList.remove('active'));
    const clicked = document.querySelector(`.doc-item[onclick*="${docId}"]`);
    if (clicked) clicked.classList.add('active');

    switchTab('dashboard');
    await loadDashboard(docId);
}

async function deleteDocument(docId, event) {
    event.stopPropagation();
    if (!confirm('Delete this document?')) return;
    await api.deleteDoc(state.activeKbId, docId);
    stopPolling(docId);
    if (state.activeDocId === docId) { state.activeDocId = null; hideDashboardContent(); }
    await loadDocuments();
    await loadKnowledgeBases();
}

function uploadDocument(file) {
    if (!state.activeKbId) return;
    api.uploadDoc(state.activeKbId, file).then(doc => {
        if (doc.id) { loadDocuments(); startPolling(doc.id); }
    });
}

function startPolling(docId) {
    if (state.pollingIntervals[docId]) return;
    const kbId = state.activeKbId; // capture KB at poll start
    state.pollingIntervals[docId] = setInterval(async () => {
        if (state.activeKbId !== kbId) { stopPolling(docId); return; } // stale KB guard
        const doc = await api.getDoc(kbId, docId);
        if (doc.status === 'READY' || doc.status === 'FAILED') {
            stopPolling(docId);
            await loadDocuments();
            await loadKnowledgeBases();
        }
    }, 3000);
}

function stopPolling(docId) {
    clearInterval(state.pollingIntervals[docId]);
    delete state.pollingIntervals[docId];
}

function formatDocStatus(doc) {
    if (doc.status === 'PROCESSING' && doc.totalPages) return `${doc.pagesProcessed || 0}/${doc.totalPages}`;
    if (doc.status === 'READY') return 'Ready';
    return doc.status;
}

// ============================================
// Section 5: Tab Switching
// ============================================
function switchTab(tabName) {
    state.activeTab = tabName;
    document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === tabName));
    document.querySelectorAll('.tab-content').forEach(tc => tc.classList.remove('active'));
    document.getElementById(`tab-${tabName}`).classList.add('active');
    if (tabName === 'health' && state.activeKbId) loadHealth();
}

// ============================================
// Section 6: Dashboard
// ============================================
async function loadDashboard(docId) {
    document.getElementById('dashboard-empty').style.display = 'none';
    const content = document.getElementById('dashboard-content');
    content.style.display = 'block';
    content.innerHTML = '<div class="tab-empty"><span class="loading"></span> Loading financial data...</div>';

    const fd = await api.getFinancialData(state.activeKbId, docId);
    if (!fd || fd.status === 404) {
        content.innerHTML = '<div class="tab-empty">No financial data extracted for this document yet.<br>It will appear automatically when extraction completes.</div>';
        // Poll until extraction appears
        startExtractionPolling(docId);
        return;
    }
    state.financialDataMap[docId] = fd;
    renderDashboard(fd);

    // If still extracting, poll for completion
    if (fd.extractionStatus === 'EXTRACTING' || fd.extractionStatus === 'PENDING') {
        startExtractionPolling(docId);
    }
}

function startExtractionPolling(docId) {
    if (state.extractionPollId) clearInterval(state.extractionPollId);
    state.extractionPollId = setInterval(async () => {
        if (state.activeDocId !== docId) { clearInterval(state.extractionPollId); return; }
        const fd = await api.getFinancialData(state.activeKbId, docId);
        if (fd && (fd.extractionStatus === 'COMPLETED' || fd.extractionStatus === 'FAILED')) {
            clearInterval(state.extractionPollId);
            state.financialDataMap[docId] = fd;
            renderDashboard(fd);
            // Also refresh doc list to update extraction dots
            loadDocuments();
        }
    }, 3000);
}

function hideDashboardContent() {
    document.getElementById('dashboard-empty').style.display = 'flex';
    document.getElementById('dashboard-content').style.display = 'none';
}

function renderDashboard(fd) {
    const content = document.getElementById('dashboard-content');
    content.innerHTML = '';

    // Build trust strip summary
    const checks = fd.validationChecks || [];
    const passed = checks.filter(c => c.passed).length;
    const failed = checks.filter(c => !c.passed).length;
    const xbrlChecks = checks.filter(c => c.checkName && c.checkName.includes('XBRL'));
    const xbrlPassed = xbrlChecks.filter(c => c.passed).length;

    // Header card with trust strip
    const header = document.createElement('div');
    header.className = 'dash-header';
    header.innerHTML = `
        <div class="dash-header-top">
            <div>
                <div class="company-name">${esc(fd.companyName || 'Unknown Company')}</div>
                <div class="meta-pills">
                    <span class="meta-pill">FY ${esc(fd.fiscalYear || '?')}</span>
                    <span class="meta-pill">${esc(fd.currencyCode || '?')}</span>
                    <span class="meta-pill">${esc(fd.accountingStandard || '?')}</span>
                    <span class="meta-pill">${esc(fd.amountsInUnit || '?')}</span>
                </div>
                ${fd.extractionStatus === 'COMPLETED' ? `
                <div class="header-actions">
                    <button class="btn-action" onclick="handleReExtract('${fd.documentId}', this)">Re-extract</button>
                    <button class="btn-action" onclick="handleReValidate('${fd.documentId}', this)">Re-validate</button>
                    <button class="btn-export" onclick="api.exportCsv('${state.activeKbId}')">↓ Export CSV</button>
                </div>
                ` : fd.extractionStatus === 'EXTRACTING' ? `
                <div class="header-actions">
                    <span style="font-size:12px;color:var(--amber)"><span class="loading"></span> Extraction in progress...</span>
                </div>
                ` : fd.extractionStatus === 'FAILED' ? `
                <div class="header-actions">
                    <button class="btn-action" onclick="handleReExtract('${fd.documentId}', this)">Retry extraction</button>
                </div>
                ` : ''}
            </div>
            <div class="header-right">
                <div style="display:flex;gap:6px;">
                    <span class="badge ${fd.extractionStatus}">${fd.extractionStatus}</span>
                    ${fd.validationStatus ? `<span class="badge ${fd.validationStatus}">${fd.validationStatus}</span>` : ''}
                </div>
                ${fd.extractionConfidence ? `
                    <div class="confidence">
                        <div class="conf-track"><div class="conf-fill" style="width:${Math.round(fd.extractionConfidence * 100)}%"></div></div>
                        <span>${Math.round(fd.extractionConfidence * 100)}% confidence</span>
                    </div>
                ` : ''}
            </div>
        </div>
        ${checks.length > 0 ? `
            <div class="trust-strip">
                <div class="trust-item">
                    <span class="trust-dot ${failed === 0 ? 'pass' : 'fail'}"></span>
                    ${passed}/${checks.length} checks passed
                </div>
                ${xbrlChecks.length > 0 ? `
                    <div class="trust-item">
                        <span class="trust-dot ${xbrlPassed === xbrlChecks.length ? 'pass' : 'warn'}"></span>
                        ${xbrlPassed}/${xbrlChecks.length} XBRL verified
                    </div>
                ` : ''}
                <div class="trust-item">
                    <span class="trust-dot pass"></span>
                    A = L + E balanced
                </div>
            </div>
        ` : ''}
    `;
    content.appendChild(header);

    // Financial cards grid
    const grid = document.createElement('div');
    grid.className = 'fin-grid';
    grid.innerHTML = `
        ${renderFinCard('Balance Sheet', [
            ['Total Assets', fd.totalAssets],
            ['Current Assets', fd.currentAssets],
            ['Total Liabilities', fd.totalLiabilities],
            ['Current Liabilities', fd.currentLiabilities],
            ['Total Equity', fd.totalEquity],
            ['Cash & Equivalents', fd.cashAndEquivalents],
            ['Trade Receivables', fd.tradeReceivables],
            ['Trade Payables', fd.tradePayables],
            ['Retained Earnings', fd.accumulatedProfit]
        ], fd.currencyCode, fd.amountsInUnit)}
        ${renderFinCard('Income Statement', [
            ['Revenue', fd.revenue],
            ['Cost of Sales', fd.costOfSales],
            ['Gross Profit', fd.grossProfit],
            ['Net Income', fd.netIncome]
        ], fd.currencyCode, fd.amountsInUnit)}
        ${renderRatiosCard(fd)}
    `;
    content.appendChild(grid);

    // Validation checks
    if (checks.length > 0) {
        const valSection = document.createElement('div');
        valSection.className = 'validation-section';
        valSection.innerHTML = `<h3>Validation Checks (${passed}/${checks.length} passed)</h3>
            <div class="validation-checks">
                ${checks.map(c => {
                    const isXbrl = c.checkName && c.checkName.includes('XBRL');
                    const source = isXbrl ? 'XBRL' : 'Rule';
                    const statusText = c.passed ? 'Passed' : 'Mismatch';
                    const statusClass = c.passed ? 'pass' : 'fail';
                    return `
                    <div class="val-check">
                        <span class="val-icon ${statusClass}">${c.passed ? '✓' : '✗'}</span>
                        <span class="val-name">${esc(c.checkName)}</span>
                        ${!c.passed ? `<span class="val-detail">${esc(c.actual)} vs ${esc(c.expected)}</span>` : ''}
                        <span class="sev-badge ${statusClass}">${statusText}</span>
                    </div>`;
                }).join('')}
            </div>`;
        content.appendChild(valSection);
    }

    // Compiled summaries (async, loads after main render)
    loadAndRenderSummaries(state.activeKbId, fd.documentId, content);
}

function renderFinCard(title, rows, currency, unit) {
    const symbol = getCurrencySymbol(currency);
    return `<div class="fin-card">
        <div class="fin-card-title">${title}</div>
        ${rows.map(([label, value]) => `
            <div class="fin-row">
                <span class="fin-label">${label}</span>
                <span class="fin-value ${value == null ? 'null' : ''} ${value < 0 ? 'negative' : ''}">${
                    value != null ? `${symbol}${formatNum(value)}` : '—'
                }</span>
            </div>
        `).join('')}
    </div>`;
}

function renderRatiosCard(fd) {
    const ratios = [];
    if (fd.currentAssets && fd.currentLiabilities && fd.currentLiabilities !== 0)
        ratios.push(['Current Ratio', (fd.currentAssets / fd.currentLiabilities).toFixed(2) + 'x']);
    if (fd.totalLiabilities && fd.totalEquity && fd.totalEquity !== 0)
        ratios.push(['Debt / Equity', (fd.totalLiabilities / fd.totalEquity).toFixed(2) + 'x']);
    if (fd.grossProfit != null && fd.revenue && fd.revenue !== 0)
        ratios.push(['Gross Margin', (fd.grossProfit / fd.revenue * 100).toFixed(1) + '%']);
    if (fd.netIncome != null && fd.revenue && fd.revenue !== 0)
        ratios.push(['Net Margin', (fd.netIncome / fd.revenue * 100).toFixed(1) + '%']);

    if (ratios.length === 0) return '';
    return `<div class="fin-card">
        <div class="fin-card-title">Key Ratios</div>
        ${ratios.map(([label, value]) => `
            <div class="fin-row">
                <span class="fin-label">${label}</span>
                <span class="fin-value">${value}</span>
            </div>
        `).join('')}
    </div>`;
}

async function handleReExtract(docId, btn) {
    btn.disabled = true; btn.textContent = 'Extracting...';
    const fd = await api.reExtract(state.activeKbId, docId);
    btn.disabled = false; btn.textContent = 'Re-extract';
    if (fd) { state.financialDataMap[docId] = fd; renderDashboard(fd); }
    else alert('Re-extraction failed. Ensure PDF bytes are stored (re-upload if needed).');
}

async function handleReValidate(docId, btn) {
    btn.disabled = true; btn.textContent = 'Validating...';
    await api.reValidate(state.activeKbId, docId);
    btn.disabled = false; btn.textContent = 'Re-validate';
    await loadDashboard(docId);
}

// ============================================
// Section 7: Compare
// ============================================
function renderCompareSelector() {
    const selector = document.getElementById('compare-doc-selector');
    if (!selector) return;
    state.compareSelected.clear();

    const docsWithData = state.documents.filter(d => {
        const fd = state.financialDataMap[d.id];
        return fd && fd.extractionStatus === 'COMPLETED';
    });

    if (docsWithData.length === 0) {
        selector.innerHTML = '<div style="color: var(--text-muted); font-size: 0.82rem;">No documents with extracted financial data</div>';
        return;
    }

    selector.innerHTML = docsWithData.map(doc => {
        const fd = state.financialDataMap[doc.id];
        return `<label class="compare-chip" id="chip-${doc.id}">
            <input type="checkbox" onchange="toggleCompareDoc('${doc.id}')">
            <span>${esc(fd.companyName || doc.filename)}</span>
        </label>`;
    }).join('');
}

function toggleCompareDoc(docId) {
    if (state.compareSelected.has(docId)) state.compareSelected.delete(docId);
    else state.compareSelected.add(docId);
    document.getElementById(`chip-${docId}`).classList.toggle('selected');
    document.getElementById('compare-btn').disabled = state.compareSelected.size < 2;
}

async function runComparison() {
    const btn = document.getElementById('compare-btn');
    btn.disabled = true; btn.textContent = 'Comparing...';
    const result = await api.compareFinancialData(state.activeKbId, [...state.compareSelected]);
    btn.disabled = false; btn.textContent = 'Compare Selected';
    renderComparisonTable(result.documents);
}

function renderComparisonTable(docs) {
    const container = document.getElementById('compare-results');
    if (!docs || docs.length === 0) {
        container.innerHTML = '<div class="tab-empty">No comparison data available</div>';
        return;
    }

    const fields = [
        { section: 'Balance Sheet' },
        { key: 'totalAssets', label: 'Total Assets' },
        { key: 'currentAssets', label: 'Current Assets' },
        { key: 'totalLiabilities', label: 'Total Liabilities' },
        { key: 'currentLiabilities', label: 'Current Liabilities' },
        { key: 'totalEquity', label: 'Total Equity' },
        { key: 'cashAndEquivalents', label: 'Cash & Equivalents' },
        { key: 'tradeReceivables', label: 'Trade Receivables' },
        { key: 'tradePayables', label: 'Trade Payables' },
        { key: 'accumulatedProfit', label: 'Retained Earnings' },
        { section: 'Income Statement' },
        { key: 'revenue', label: 'Revenue' },
        { key: 'costOfSales', label: 'Cost of Sales' },
        { key: 'grossProfit', label: 'Gross Profit' },
        { key: 'netIncome', label: 'Net Income' },
        { section: 'Key Ratios' },
        { key: '_currentRatio', label: 'Current Ratio', computed: fd => fd.currentAssets && fd.currentLiabilities ? (fd.currentAssets / fd.currentLiabilities).toFixed(2) + 'x' : '—' },
        { key: '_debtEquity', label: 'Debt / Equity', computed: fd => fd.totalLiabilities && fd.totalEquity ? (fd.totalLiabilities / fd.totalEquity).toFixed(2) + 'x' : '—' },
        { key: '_grossMargin', label: 'Gross Margin', computed: fd => fd.grossProfit != null && fd.revenue ? (fd.grossProfit / fd.revenue * 100).toFixed(1) + '%' : '—' },
        { key: '_netMargin', label: 'Net Margin', computed: fd => fd.netIncome != null && fd.revenue ? (fd.netIncome / fd.revenue * 100).toFixed(1) + '%' : '—' }
    ];

    let html = '<table class="cmp-table"><thead><tr><th>Metric</th>';
    docs.forEach(fd => {
        html += `<th><span class="cmp-company">${esc(fd.companyName || '?')}</span><span class="cmp-meta">${fd.currencyCode || ''} · ${fd.amountsInUnit || ''}</span></th>`;
    });
    html += '</tr></thead><tbody>';

    fields.forEach(f => {
        if (f.section) {
            html += `<tr class="section-row"><td colspan="${docs.length + 1}">${f.section}</td></tr>`;
            return;
        }
        html += '<tr>';
        html += `<td>${f.label}</td>`;
        docs.forEach(fd => {
            if (f.computed) {
                html += `<td>${f.computed(fd)}</td>`;
            } else {
                const val = fd[f.key];
                const sym = getCurrencySymbol(fd.currencyCode);
                html += `<td>${val != null ? sym + formatNum(val) : '—'}</td>`;
            }
        });
        html += '</tr>';
    });

    html += '</tbody></table>';
    container.innerHTML = html;
}

// ============================================
// Section 7b: Knowledge Summaries (B2)
// ============================================
async function loadAndRenderSummaries(kbId, docId, container) {
    try {
        const summaries = await api.getDocumentSummaries(kbId, docId);
        if (!summaries || summaries.length === 0) return;

        const section = document.createElement('div');
        section.className = 'health-section';
        section.innerHTML = `
            <div class="health-section-title">Compiled Summaries (${summaries.length})</div>
            <div class="summary-list">
                ${summaries.map(s => `
                    <div class="summary-card">
                        <div class="summary-card-header">
                            <span class="summary-type-badge">${esc(formatSummaryType(s.summaryType))}</span>
                            <span class="summary-section-name">${esc(s.sectionSource || '')}</span>
                            <span class="summary-meta-text">${s.wordCount} words · ${esc(s.modelUsed || '')}</span>
                        </div>
                        <div class="summary-content">${esc(s.content)}</div>
                    </div>
                `).join('')}
            </div>`;
        container.appendChild(section);
    } catch (e) {
        // Summaries are optional — silently ignore failures
    }
}

function formatSummaryType(type) {
    const labels = {
        BUSINESS_OVERVIEW: 'Business',
        RISK_PROFILE: 'Risks',
        MANAGEMENT_DISCUSSION: 'MD&A',
        FINANCIAL_HIGHLIGHTS: 'Financials',
        GENERAL: 'General'
    };
    return labels[type] || type;
}

// ============================================
// Section 7c: Health Dashboard (B3)
// ============================================
async function loadHealth() {
    const content = document.getElementById('health-content');
    content.className = '';
    content.innerHTML = '<div class="tab-empty"><span class="loading"></span> Loading health metrics...</div>';

    try {
        const [report, anomalies] = await Promise.all([
            api.getHealth(state.activeKbId),
            api.getAnomalies(state.activeKbId)
        ]);
        renderHealthReport(report, anomalies);
    } catch (e) {
        content.innerHTML = '<div class="tab-empty">Failed to load health metrics</div>';
    }
}

function scoreClass(pct) {
    if (pct >= 80) return 'score-high';
    if (pct >= 60) return 'score-mid';
    return 'score-low';
}

function coverageClass(pct) {
    if (pct >= 80) return 'high';
    if (pct >= 50) return 'mid';
    return 'low';
}

function formatFieldName(name) {
    return name
        .replace(/([A-Z])/g, ' $1')
        .replace(/^./, s => s.toUpperCase())
        .replace('And', '&')
        .trim();
}

function renderHealthReport(report, anomalies) {
    const content = document.getElementById('health-content');
    if (!report) {
        content.innerHTML = '<div class="tab-empty">No health data available yet. Upload and process documents first.</div>';
        return;
    }

    const html = [];

    // ── Metric cards grid ──────────────────────────────────────────
    html.push('<div class="health-grid">');

    const metrics = [
        { label: 'Extraction Rate',    value: report.extractionSuccessRate,  suffix: '%', sub: `${report.completedDocuments}/${report.totalDocuments} documents` },
        { label: 'Validation Pass',    value: report.validationPassRate,      suffix: '%', sub: 'accounting checks' },
        { label: 'Avg Confidence',     value: report.avgConfidence,           suffix: '%', sub: 'LLM-scored' },
        { label: 'Field Completeness', value: report.avgFieldCompleteness,    suffix: '%', sub: '13 financial fields' },
        { label: 'Companies',          value: report.companyCount,            suffix: '',  sub: 'distinct entities', noColor: true },
        { label: 'Summaries',          value: report.summaryCount,            suffix: '',  sub: 'compiled sections', noColor: true }
    ];

    metrics.forEach(m => {
        const cls = m.noColor ? '' : scoreClass(m.value);
        html.push(`
            <div class="health-metric-card">
                <div class="health-metric-label">${m.label}</div>
                <div class="health-metric-score ${cls}">${typeof m.value === 'number' && !Number.isInteger(m.value) ? m.value.toFixed(1) : m.value}${m.suffix}</div>
                <div class="health-metric-sub">${m.sub}</div>
            </div>`);
    });

    html.push('</div>');

    // ── Field coverage ─────────────────────────────────────────────
    if (report.fieldCoverage && report.fieldCoverage.length > 0) {
        html.push('<div class="health-section">');
        html.push('<div class="health-section-title">Field Coverage</div>');
        html.push('<div class="coverage-list">');
        report.fieldCoverage.forEach(fc => {
            const cls = coverageClass(fc.coveragePercent);
            const pct = fc.coveragePercent.toFixed(1);
            html.push(`
                <div class="coverage-row">
                    <span class="coverage-field">${formatFieldName(fc.fieldName)}</span>
                    <div class="coverage-bar"><div class="coverage-fill ${cls}" style="width:${pct}%"></div></div>
                    <span class="coverage-pct">${pct}%</span>
                </div>`);
        });
        html.push('</div></div>');
    }

    // ── Anomalies ──────────────────────────────────────────────────
    html.push('<div class="health-section">');
    const anomalyCount = anomalies ? anomalies.length : 0;
    html.push(`<div class="health-section-title">Anomalies (${anomalyCount})</div>`);

    if (!anomalies || anomalies.length === 0) {
        html.push('<div class="anomaly-empty"><span>✓</span> No anomalies detected</div>');
    } else {
        html.push('<div class="anomaly-list">');
        anomalies.forEach(a => {
            const company = a.companyName ? `${esc(a.companyName)} · ${esc(a.fiscalYear || '?')}` : '';
            html.push(`
                <div class="anomaly-item">
                    <span class="sev-badge ${esc(a.severity)}">${esc(a.severity)}</span>
                    <span class="anomaly-desc">${esc(a.description)}</span>
                    ${company ? `<span class="anomaly-meta">${company}</span>` : ''}
                </div>`);
        });
        html.push('</div>');
    }

    html.push('</div>');

    content.innerHTML = html.join('');
}

// ============================================
// Section 8: Chat
// ============================================
async function sendQuery() {
    const input = document.getElementById('query-input');
    const question = input.value.trim();
    if (!question || !state.activeKbId) return;
    input.value = '';
    addMessage(question, 'user');
    addMessage('<span class="loading"></span> Thinking...', 'assistant', true);
    const btn = document.getElementById('send-btn');
    btn.disabled = true;

    try {
        const data = await api.query(state.activeKbId, question);
        removeLastMessage();
        if (data && data.answer) {
            const sourcesHtml = data.sources.length > 0 ? `<div class="sources-section">
                <div class="sources-header">Sources (${data.sources.length})</div>
                <div class="source-cards">${data.sources.map(s => {
                    const score = s.similarityScore != null ? Math.round(s.similarityScore * 100) : null;
                    const sc = score >= 70 ? 'high' : score >= 40 ? 'med' : 'low';
                    return `<div class="source-card"><div class="source-card-header">
                        <span class="source-section">${esc(s.sectionName || 'Unknown')}</span>
                        ${score != null ? `<span class="source-score ${sc}">${score}%</span>` : ''}
                    </div><div class="source-page">Page ${s.pageNumber || '?'}</div>
                    ${s.content ? `<div class="source-preview">${esc(s.content.substring(0, 150))}...</div>` : ''}</div>`;
                }).join('')}</div></div>` : '';
            const metaHtml = `<div class="meta"><span>${data.metadata.chunksRetrieved} chunks · ${data.metadata.retrievalTimeMs}ms retrieval</span><span>${(data.metadata.generationTimeMs / 1000).toFixed(1)}s generation</span></div>`;
            addMessage(renderMarkdown(data.answer) + sourcesHtml + metaHtml, 'assistant', true);
        } else {
            addMessage('Error: No response received', 'assistant', true);
        }
    } catch (e) {
        removeLastMessage();
        addMessage('Error: ' + esc(e.message || 'Failed to connect'), 'assistant', true);
    } finally { btn.disabled = false; }
}

function addMessage(content, role, isHtml = false) {
    const messages = document.getElementById('messages');
    const div = document.createElement('div');
    div.className = `message ${role}`;
    if (isHtml) div.innerHTML = content; else div.textContent = content;
    messages.appendChild(div);
    messages.scrollTop = messages.scrollHeight;
}

function removeLastMessage() {
    const m = document.getElementById('messages');
    if (m.lastChild) m.removeChild(m.lastChild);
}

function clearMessages() { document.getElementById('messages').innerHTML = ''; }

// ============================================
// Section 9: Utilities
// ============================================
function esc(str) {
    if (!str) return '';
    const d = document.createElement('div');
    d.textContent = str;
    return d.innerHTML;
}

function formatNum(value) {
    if (value == null) return '—';
    return new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 }).format(value);
}

function getCurrencySymbol(code) {
    const map = { USD: '$', INR: '₹', SGD: 'S$', EUR: '€', GBP: '£', JPY: '¥', CNY: '¥', HKD: 'HK$', KRW: '₩' };
    return map[code] || (code ? code + ' ' : '');
}

function renderMarkdown(text) {
    if (!text) return '';
    let html = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    html = html.replace(/^### (.+)$/gm, '<h4 class="md-h4">$1</h4>');
    html = html.replace(/^## (.+)$/gm, '<h3 class="md-h3">$1</h3>');
    html = html.replace(/^# (.+)$/gm, '<h2 class="md-h2">$1</h2>');
    html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');
    html = html.replace(/`([^`]+)`/g, '<code class="md-code">$1</code>');
    // Markdown tables — detect contiguous | lines and convert to <table>
    html = html.replace(/((?:\|[^\n]+\|\n?)+)/gm, match => {
        const rows = match.trim().split('\n').map(r => r.trim()).filter(Boolean);
        if (rows.length < 2) return match;
        const isSepRow = r => /^\|[\s|:\-]+\|$/.test(r);
        const headerCells = rows[0].split('|').slice(1, -1).map(c => c.trim());
        const dataRows = rows.slice(1).filter(r => !isSepRow(r));
        if (dataRows.length === 0) return match;
        let t = '<div class="md-table-wrapper"><table class="md-table"><thead><tr>';
        t += headerCells.map(h => `<th>${h}</th>`).join('');
        t += '</tr></thead><tbody>';
        dataRows.forEach(row => {
            const cells = row.split('|').slice(1, -1).map(c => c.trim());
            t += '<tr>' + cells.map(c => `<td>${c}</td>`).join('') + '</tr>';
        });
        t += '</tbody></table></div>';
        return t;
    });
    html = html.replace(/^(?:[*\-] .+\n?)+/gm, m => '<ul class="md-list">' + m.trim().split('\n').map(l => '<li>' + l.replace(/^[*\-] /, '') + '</li>').join('') + '</ul>');
    html = html.replace(/^(?:\d+\. .+\n?)+/gm, m => '<ol class="md-list">' + m.trim().split('\n').map(l => '<li>' + l.replace(/^\d+\. /, '') + '</li>').join('') + '</ol>');
    html = html.replace(/\n\n+/g, '</p><p class="md-p">');
    html = html.replace(/\n/g, '<br>');
    html = '<p class="md-p">' + html + '</p>';
    html = html.replace(/<p class="md-p"><\/p>/g, '');
    html = html.replace(/<p class="md-p">(<[huo])/g, '$1');
    html = html.replace(/(<\/[huo]l>|<\/h[234]>)<\/p>/g, '$1');
    html = html.replace(/<p class="md-p">(<br>)+/g, '<p class="md-p">');
    // Unwrap tables from <p> tags
    html = html.replace(/<p class="md-p">(<div class="md-table-wrapper">)/g, '$1');
    html = html.replace(/(<\/table><\/div>)<\/p>/g, '$1');
    return html;
}

// ============================================
// Section 10: Event Listeners + Init
// ============================================
document.getElementById('kb-create-btn').addEventListener('click', createKnowledgeBase);
document.getElementById('kb-name').addEventListener('keydown', e => { if (e.key === 'Enter') createKnowledgeBase(); });
document.getElementById('send-btn').addEventListener('click', sendQuery);
document.getElementById('query-input').addEventListener('keydown', e => { if (e.key === 'Enter') sendQuery(); });

document.getElementById('file-input').addEventListener('change', e => {
    if (e.target.files.length > 0) { uploadDocument(e.target.files[0]); e.target.value = ''; }
});

// Drag and drop on sidebar
const sidebar = document.querySelector('.sidebar');
sidebar.addEventListener('dragover', e => {
    e.preventDefault();
    const zone = document.getElementById('upload-zone');
    zone.style.display = 'block';
    zone.classList.add('dragover');
});
sidebar.addEventListener('dragleave', e => {
    if (!sidebar.contains(e.relatedTarget)) {
        const zone = document.getElementById('upload-zone');
        zone.classList.remove('dragover');
        zone.style.display = 'none';
    }
});
sidebar.addEventListener('drop', e => {
    e.preventDefault();
    const zone = document.getElementById('upload-zone');
    zone.classList.remove('dragover');
    zone.style.display = 'none';
    if (e.dataTransfer.files.length > 0) uploadDocument(e.dataTransfer.files[0]);
});

// Init
loadKnowledgeBases();
