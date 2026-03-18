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

async function deleteDocument(docId, event) {
    event.stopPropagation();
    if (!confirm('Delete this document and all its embeddings?')) return;

    try {
        const res = await fetch(`${API}/${activeKbId}/documents/${docId}`, {
            method: 'DELETE'
        });

        if (res.ok || res.status === 204) {
            stopPolling(docId);
            await loadDocuments();
            await loadKnowledgeBases();
        } else {
            const err = await res.json();
            alert(err.message || 'Failed to delete document');
        }
    } catch (e) {
        alert('Failed to connect to server');
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
            <div class="doc-actions">
                <span class="doc-status ${doc.status}">${formatStatus(doc)}</span>
                <button class="doc-delete" onclick="deleteDocument('${doc.id}', event)" title="Delete document">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <polyline points="3 6 5 6 21 6"></polyline>
                        <path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"></path>
                    </svg>
                </button>
            </div>
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
            body: JSON.stringify({ question, topK: 5 })
        });

        removeLastMessage();

        if (res.ok) {
            const data = await res.json();

            // Build source cards
            const sourcesHtml = data.sources.length > 0
                ? `<div class="sources-section">
                    <div class="sources-header">Sources (${data.sources.length})</div>
                    <div class="source-cards">
                        ${data.sources.map(s => {
                            const score = s.similarityScore != null
                                ? Math.round(s.similarityScore * 100) : null;
                            const scoreClass = score >= 70 ? 'high' : score >= 40 ? 'med' : 'low';
                            return `<div class="source-card">
                                <div class="source-card-header">
                                    <span class="source-section">${escapeHtml(s.sectionName || 'Unknown Section')}</span>
                                    ${score != null ? `<span class="source-score ${scoreClass}">${score}%</span>` : ''}
                                </div>
                                <div class="source-page">Page ${s.pageNumber || '?'}</div>
                                ${s.content ? `<div class="source-preview">${escapeHtml(s.content.substring(0, 150))}${s.content.length > 150 ? '...' : ''}</div>` : ''}
                            </div>`;
                        }).join('')}
                    </div>
                </div>`
                : '';

            // Build metadata line
            const metaHtml = `<div class="meta">
                <span>${data.metadata.chunksRetrieved} chunks retrieved in ${data.metadata.retrievalTimeMs}ms</span>
                <span>Generated in ${(data.metadata.generationTimeMs / 1000).toFixed(1)}s</span>
            </div>`;

            addMessage(renderMarkdown(data.answer) + sourcesHtml + metaHtml, 'assistant', true);
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

/**
 * Simple markdown renderer — converts common markdown to HTML.
 * XSS-safe: escapes HTML first, then applies controlled regex transforms.
 */
function renderMarkdown(text) {
    if (!text) return '';

    // Step 1: Escape HTML entities (XSS prevention)
    let html = text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');

    // Step 2: Headers (### before ## before #)
    html = html.replace(/^### (.+)$/gm, '<h4 class="md-h4">$1</h4>');
    html = html.replace(/^## (.+)$/gm, '<h3 class="md-h3">$1</h3>');
    html = html.replace(/^# (.+)$/gm, '<h2 class="md-h2">$1</h2>');

    // Step 3: Bold and italic (bold first to avoid ** caught by single *)
    html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');

    // Step 4: Inline code
    html = html.replace(/`([^`]+)`/g, '<code class="md-code">$1</code>');

    // Step 5: Unordered lists
    html = html.replace(/^(?:[*\-] .+\n?)+/gm, function(match) {
        const items = match.trim().split('\n').map(line =>
            '<li>' + line.replace(/^[*\-] /, '') + '</li>'
        ).join('');
        return '<ul class="md-list">' + items + '</ul>';
    });

    // Step 6: Ordered lists
    html = html.replace(/^(?:\d+\. .+\n?)+/gm, function(match) {
        const items = match.trim().split('\n').map(line =>
            '<li>' + line.replace(/^\d+\. /, '') + '</li>'
        ).join('');
        return '<ol class="md-list">' + items + '</ol>';
    });

    // Step 7: Paragraphs (double newlines)
    html = html.replace(/\n\n+/g, '</p><p class="md-p">');

    // Step 8: Single newlines become <br>
    html = html.replace(/\n/g, '<br>');

    // Step 9: Wrap in paragraph
    html = '<p class="md-p">' + html + '</p>';

    // Step 10: Clean up empty/nested paragraphs around block elements
    html = html.replace(/<p class="md-p"><\/p>/g, '');
    html = html.replace(/<p class="md-p">(<[huo])/g, '$1');
    html = html.replace(/(<\/[huo]l>|<\/h[234]>)<\/p>/g, '$1');
    html = html.replace(/<p class="md-p">(<br>)+/g, '<p class="md-p">');

    return html;
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
