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
    compareSelected: new Set()
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
    if (!name) return;
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
    state.pollingIntervals[docId] = setInterval(async () => {
        const doc = await api.getDoc(state.activeKbId, docId);
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
        content.innerHTML = '<div class="tab-empty">No financial data extracted for this document</div>';
        return;
    }
    state.financialDataMap[docId] = fd;
    renderDashboard(fd);
}

function hideDashboardContent() {
    document.getElementById('dashboard-empty').style.display = 'flex';
    document.getElementById('dashboard-content').style.display = 'none';
}

function renderDashboard(fd) {
    const content = document.getElementById('dashboard-content');
    content.innerHTML = '';

    // Extraction header
    const header = document.createElement('div');
    header.className = 'extraction-header';
    header.innerHTML = `
        <div>
            <div class="company-info">
                <h2>${esc(fd.companyName || 'Unknown Company')}</h2>
                <div class="company-meta">
                    <span class="meta-tag">${esc(fd.fiscalYear || '?')}</span>
                    <span class="meta-tag">${esc(fd.currencyCode || '?')}</span>
                    <span class="meta-tag">${esc(fd.accountingStandard || '?')}</span>
                    <span class="meta-tag">${esc(fd.amountsInUnit || '?')}</span>
                </div>
            </div>
            <div class="header-actions">
                <button class="btn-sm" onclick="handleReExtract('${fd.documentId}')">Re-extract</button>
                <button class="btn-sm" onclick="handleReValidate('${fd.documentId}')">Re-validate</button>
            </div>
        </div>
        <div class="extraction-badges">
            ${fd.extractionConfidence ? `
                <div class="confidence-bar">
                    <div class="confidence-track"><div class="confidence-fill" style="width:${Math.round(fd.extractionConfidence * 100)}%"></div></div>
                    <span>${Math.round(fd.extractionConfidence * 100)}%</span>
                </div>
            ` : ''}
            <span class="status-badge ${fd.extractionStatus}">${fd.extractionStatus}</span>
            ${fd.validationStatus ? `<span class="status-badge ${fd.validationStatus}">${fd.validationStatus}</span>` : ''}
        </div>
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
    if (fd.validationChecks && fd.validationChecks.length > 0) {
        const valSection = document.createElement('div');
        valSection.className = 'validation-section';
        valSection.innerHTML = `<h3>Validation Checks (${fd.validationChecks.filter(c => c.passed).length}/${fd.validationChecks.length} passed)</h3>
            <div class="validation-checks">
                ${fd.validationChecks.map(c => `
                    <div class="validation-check">
                        <span class="check-icon ${c.passed ? 'pass' : 'fail'}">${c.passed ? '✓' : '✗'}</span>
                        <span class="check-name">${esc(c.checkName)}</span>
                        <span class="check-detail">${esc(c.passed ? '' : `${c.actual} vs ${c.expected}`)}</span>
                        <span class="severity-badge ${c.severity}">${c.severity}</span>
                    </div>
                `).join('')}
            </div>`;
        content.appendChild(valSection);
    }
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

async function handleReExtract(docId) {
    const btn = event.target;
    btn.disabled = true; btn.textContent = 'Extracting...';
    const fd = await api.reExtract(state.activeKbId, docId);
    btn.disabled = false; btn.textContent = 'Re-extract';
    if (fd) { state.financialDataMap[docId] = fd; renderDashboard(fd); }
    else alert('Re-extraction failed. Ensure PDF bytes are stored (re-upload if needed).');
}

async function handleReValidate(docId) {
    const btn = event.target;
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
        return `<label class="compare-doc-chip" id="chip-${doc.id}">
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

    let html = '<table class="compare-table"><thead><tr><th>Metric</th>';
    docs.forEach(fd => {
        html += `<th><div class="company-header">${esc(fd.companyName || '?')}</div><span class="currency-tag">${fd.currencyCode || ''} · ${fd.amountsInUnit || ''}</span></th>`;
    });
    html += '</tr></thead><tbody>';

    fields.forEach(f => {
        if (f.section) {
            html += `<tr class="section-divider"><td colspan="${docs.length + 1}">${f.section}</td></tr>`;
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
        const res = await fetch(`${API}/${state.activeKbId}/query`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ question, topK: 5 })
        });
        removeLastMessage();
        if (res.ok) {
            const data = await res.json();
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
            const err = await res.json();
            addMessage('Error: ' + esc(err.message || 'Query failed'), 'assistant', true);
        }
    } catch (e) {
        removeLastMessage();
        addMessage('Error: Failed to connect', 'assistant', true);
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
    html = html.replace(/^(?:[*\-] .+\n?)+/gm, m => '<ul class="md-list">' + m.trim().split('\n').map(l => '<li>' + l.replace(/^[*\-] /, '') + '</li>').join('') + '</ul>');
    html = html.replace(/^(?:\d+\. .+\n?)+/gm, m => '<ol class="md-list">' + m.trim().split('\n').map(l => '<li>' + l.replace(/^\d+\. /, '') + '</li>').join('') + '</ol>');
    html = html.replace(/\n\n+/g, '</p><p class="md-p">');
    html = html.replace(/\n/g, '<br>');
    html = '<p class="md-p">' + html + '</p>';
    html = html.replace(/<p class="md-p"><\/p>/g, '');
    html = html.replace(/<p class="md-p">(<[huo])/g, '$1');
    html = html.replace(/(<\/[huo]l>|<\/h[234]>)<\/p>/g, '$1');
    html = html.replace(/<p class="md-p">(<br>)+/g, '<p class="md-p">');
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
