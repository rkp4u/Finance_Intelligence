const API = '/api/v1/knowledge-bases';
let activeKbId = null;
let pollingIntervals = {};

// --- Knowledge Base Operations ---

async function loadKnowledgeBases() {
    const res = await fetch(API);
    const kbs = await res.json();
    renderKnowledgeBases(kbs);
}

async function createKnowledgeBase() {
    const input = document.getElementById('kb-name');
    const name = input.value.trim();
    if (!name) return;

    const res = await fetch(API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, description: '' })
    });

    if (res.ok) {
        input.value = '';
        await loadKnowledgeBases();
    } else {
        const err = await res.json();
        alert(err.message || 'Failed to create knowledge base');
    }
}

async function deleteKnowledgeBase(id, event) {
    event.stopPropagation();
    if (!confirm('Delete this knowledge base and all its documents?')) return;

    await fetch(`${API}/${id}`, { method: 'DELETE' });
    if (activeKbId === id) {
        activeKbId = null;
        showEmptyState();
    }
    await loadKnowledgeBases();
}

function selectKnowledgeBase(id) {
    activeKbId = id;
    document.getElementById('empty-state').style.display = 'none';
    document.getElementById('kb-view').style.display = 'flex';

    document.querySelectorAll('.kb-item').forEach(el => {
        el.classList.toggle('active', el.dataset.id === id);
    });

    loadDocuments();
    clearMessages();
}

function showEmptyState() {
    document.getElementById('empty-state').style.display = 'flex';
    document.getElementById('kb-view').style.display = 'none';
}

function renderKnowledgeBases(kbs) {
    const list = document.getElementById('kb-list');
    list.innerHTML = kbs.map(kb => `
        <div class="kb-item ${kb.id === activeKbId ? 'active' : ''}"
             data-id="${kb.id}" onclick="selectKnowledgeBase('${kb.id}')">
            <div class="kb-info">
                <div class="kb-name">${escapeHtml(kb.name)}</div>
                <div class="kb-docs">${kb.documentCount} document${kb.documentCount !== 1 ? 's' : ''}</div>
            </div>
            <button class="kb-delete" onclick="deleteKnowledgeBase('${kb.id}', event)">x</button>
        </div>
    `).join('');
}

// --- Document Operations ---

async function loadDocuments() {
    if (!activeKbId) return;

    const res = await fetch(`${API}/${activeKbId}/documents`);
    const docs = await res.json();
    renderDocuments(docs);

    // Start polling for any processing documents
    docs.filter(d => d.status === 'PROCESSING' || d.status === 'PENDING').forEach(d => {
        startPolling(d.id);
    });
}

async function uploadDocument(file) {
    if (!activeKbId) return;

    const formData = new FormData();
    formData.append('file', file);

    const res = await fetch(`${API}/${activeKbId}/documents`, {
        method: 'POST',
        body: formData
    });

    if (res.ok) {
        const doc = await res.json();
        await loadDocuments();
        startPolling(doc.id);
    } else {
        const err = await res.json();
        alert(err.message || 'Upload failed');
    }
}

function startPolling(docId) {
    if (pollingIntervals[docId]) return;

    pollingIntervals[docId] = setInterval(async () => {
        const res = await fetch(`${API}/${activeKbId}/documents/${docId}`);
        if (!res.ok) { stopPolling(docId); return; }

        const doc = await res.json();
        if (doc.status === 'READY' || doc.status === 'FAILED') {
            stopPolling(docId);
            await loadDocuments();
            await loadKnowledgeBases();
        } else {
            updateDocStatus(doc);
        }
    }, 2000);
}

function stopPolling(docId) {
    clearInterval(pollingIntervals[docId]);
    delete pollingIntervals[docId];
}

function renderDocuments(docs) {
    const list = document.getElementById('docs-list');
    if (docs.length === 0) {
        list.innerHTML = '<div style="color: var(--text-secondary); font-size: 0.85rem;">No documents yet</div>';
        return;
    }
    list.innerHTML = docs.map(doc => `
        <div class="doc-item" id="doc-${doc.id}">
            <span class="doc-name">${escapeHtml(doc.filename || 'Unknown')}</span>
            <span class="doc-status ${doc.status}">${formatStatus(doc)}</span>
        </div>
    `).join('');
}

function updateDocStatus(doc) {
    const el = document.getElementById(`doc-${doc.id}`);
    if (!el) return;
    const statusEl = el.querySelector('.doc-status');
    statusEl.className = `doc-status ${doc.status}`;
    statusEl.textContent = formatStatus(doc);
}

function formatStatus(doc) {
    if (doc.status === 'PROCESSING' && doc.totalPages) {
        return `Processing ${doc.pagesProcessed || 0}/${doc.totalPages}`;
    }
    if (doc.status === 'READY' && doc.chunkCount) {
        return `Ready (${doc.chunkCount} chunks)`;
    }
    return doc.status;
}

// --- Query Operations ---

async function sendQuery() {
    const input = document.getElementById('query-input');
    const question = input.value.trim();
    if (!question || !activeKbId) return;

    input.value = '';
    addMessage(question, 'user');
    addMessage('<span class="loading"></span> Thinking...', 'assistant', true);

    const sendBtn = document.getElementById('send-btn');
    sendBtn.disabled = true;

    try {
        const res = await fetch(`${API}/${activeKbId}/query`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ question, topK: 5, similarityThreshold: 0.7 })
        });

        removeLastMessage();

        if (res.ok) {
            const data = await res.json();
            const sourcesHtml = data.sources.length > 0
                ? `<div class="sources"><strong>Sources:</strong>${data.sources.map(s =>
                    `<div class="source-item">${escapeHtml(s.sectionName || 'Unknown')} (p.${s.pageNumber || '?'})</div>`
                  ).join('')}</div>`
                : '';
            const metaHtml = `<div class="meta">Retrieved ${data.metadata.chunksRetrieved} chunks in ${data.metadata.retrievalTimeMs}ms | Generated in ${data.metadata.generationTimeMs}ms</div>`;
            addMessage(escapeHtml(data.answer) + sourcesHtml + metaHtml, 'assistant', true);
        } else {
            const err = await res.json();
            addMessage('Error: ' + escapeHtml(err.message || 'Query failed'), 'assistant', true);
        }
    } catch (e) {
        removeLastMessage();
        addMessage('Error: Failed to connect to the server', 'assistant', true);
    } finally {
        sendBtn.disabled = false;
    }
}

function addMessage(content, role, isHtml = false) {
    const messages = document.getElementById('messages');
    const div = document.createElement('div');
    div.className = `message ${role}`;
    if (isHtml) {
        div.innerHTML = content;
    } else {
        div.textContent = content;
    }
    messages.appendChild(div);
    messages.scrollTop = messages.scrollHeight;
}

function removeLastMessage() {
    const messages = document.getElementById('messages');
    if (messages.lastChild) messages.removeChild(messages.lastChild);
}

function clearMessages() {
    document.getElementById('messages').innerHTML = '';
}

// --- Utilities ---

function escapeHtml(str) {
    if (!str) return '';
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

// --- Event Listeners ---

document.getElementById('kb-create-btn').addEventListener('click', createKnowledgeBase);
document.getElementById('kb-name').addEventListener('keydown', e => {
    if (e.key === 'Enter') createKnowledgeBase();
});

document.getElementById('send-btn').addEventListener('click', sendQuery);
document.getElementById('query-input').addEventListener('keydown', e => {
    if (e.key === 'Enter') sendQuery();
});

// File upload via input
document.getElementById('file-input').addEventListener('change', e => {
    if (e.target.files.length > 0) {
        uploadDocument(e.target.files[0]);
        e.target.value = '';
    }
});

// Drag and drop
const uploadZone = document.getElementById('upload-zone');
uploadZone.addEventListener('dragover', e => { e.preventDefault(); uploadZone.classList.add('dragover'); });
uploadZone.addEventListener('dragleave', () => uploadZone.classList.remove('dragover'));
uploadZone.addEventListener('drop', e => {
    e.preventDefault();
    uploadZone.classList.remove('dragover');
    if (e.dataTransfer.files.length > 0) uploadDocument(e.dataTransfer.files[0]);
});

// Initial load
loadKnowledgeBases();
