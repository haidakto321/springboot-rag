// Client for the knowledge-base API. No framework. Two screens: Documents and Search & Ask.

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

// ---------- Screen switching ----------

const SCREENS = {
    docs: {
        nav: 'nav-docs', el: 'screen-docs',
        title: 'Documents & Import',
        sub: 'Import markdown files and manage your indexed documents.',
    },
    query: {
        nav: 'nav-query', el: 'screen-query',
        title: 'Search & Ask',
        sub: 'Search chunks and ask questions grounded in your knowledge base.',
    },
    compare: {
        nav: 'nav-compare', el: 'screen-compare',
        title: 'Compare backends',
        sub: 'Run one query through every search backend and compare results and latency.',
    },
};

function showScreen(name) {
    for (const [key, s] of Object.entries(SCREENS)) {
        $('#' + s.el).hidden = key !== name;
        $('#' + s.nav).classList.toggle('active', key === name);
    }
    $('#screen-title').textContent = SCREENS[name].title;
    $('#screen-sub').textContent = SCREENS[name].sub;
}

$('#nav-docs').addEventListener('click', () => { showChunkList(); showScreen('docs'); });
$('#nav-query').addEventListener('click', () => showScreen('query'));
$('#nav-compare').addEventListener('click', () => showScreen('compare'));

// ---------- Theme ----------

function applyTheme(theme) {
    document.body.classList.toggle('dark', theme === 'dark');
    $('#theme-label').textContent = theme === 'dark' ? 'Dark' : 'Light';
}

let theme = localStorage.getItem('kb-theme') || 'light';
applyTheme(theme);

$('#theme-toggle').addEventListener('click', () => {
    theme = theme === 'dark' ? 'light' : 'dark';
    localStorage.setItem('kb-theme', theme);
    applyTheme(theme);
});

// ---------- Documents + stats ----------

async function refreshDocs() {
    const res = await fetch('/documents');
    const docs = await res.json();
    const list = $('#doc-list');
    list.innerHTML = '';

    let totalChunks = 0;
    for (const d of docs) totalChunks += d.chunkCount;

    if (!docs.length) {
        list.innerHTML = '<div class="empty-line">No documents indexed yet. Import a .md file above.</div>';
    }
    for (const d of docs) {
        const row = document.createElement('div');
        row.className = 'doc-row';
        row.innerHTML = `
            <div class="doc-id"><span class="doc-id-dot"></span><span class="doc-id-name">${esc(d.docId)}</span></div>
            <div class="doc-src mono">${d.sourceFile ? esc(d.sourceFile) : '—'}</div>
            <div class="doc-chunks">${d.chunkCount}</div>`;
        const actions = document.createElement('div');
        actions.className = 'doc-actions';

        const viewBtn = document.createElement('button');
        viewBtn.className = 'btn-view';
        viewBtn.textContent = 'View';
        viewBtn.onclick = () => showChunkView(d.docId);

        const delBtn = document.createElement('button');
        delBtn.className = 'btn-delete';
        delBtn.textContent = 'Delete';
        delBtn.onclick = async () => {
            if (!confirm(`Delete document "${d.docId}"?`)) return;
            await fetch(`/documents/${encodeURIComponent(d.docId)}`, { method: 'DELETE' });
            refreshDocs();
        };

        actions.appendChild(viewBtn);
        actions.appendChild(delBtn);
        row.appendChild(actions);
        list.appendChild(row);
    }

    // Stats + nav count
    $('#stat-docs').textContent = docs.length;
    $('#stat-chunks').textContent = totalChunks;
    $('#nav-count').textContent = docs.length;
    $('#doc-meta').textContent = `${docs.length} document${docs.length === 1 ? '' : 's'} · ${totalChunks} chunk${totalChunks === 1 ? '' : 's'}`;
    const last = localStorage.getItem('kb-last-import');
    $('#stat-last').textContent = last || '—';
}

// ---------- Chunk sub-view ----------

function showChunkList() {
    $('#docs-list-view').hidden = false;
    $('#docs-chunk-view').hidden = true;
}

async function showChunkView(docId) {
    const list = $('#chunk-list');
    $('#chunk-doc-title').textContent = docId;
    $('#chunk-meta').textContent = 'Loading…';
    list.innerHTML = '';
    $('#docs-list-view').hidden = true;
    $('#docs-chunk-view').hidden = false;

    const res = await fetch(`/documents/${encodeURIComponent(docId)}/chunks`);
    if (!res.ok) { $('#chunk-meta').textContent = `Error: ${res.status}`; return; }
    const chunks = await res.json();
    $('#chunk-meta').textContent = `${chunks.length} chunk${chunks.length === 1 ? '' : 's'}`;

    if (!chunks.length) { list.innerHTML = '<div class="empty-line">No chunks.</div>'; return; }
    for (const c of chunks) {
        const row = document.createElement('div');
        row.className = 'result-row';
        row.innerHTML = `
            <span class="chunk-index-badge">${c.index}</span>
            <div class="chunk-body">
                ${c.headingPath ? `<div class="chunk-heading">${esc(c.headingPath)}</div>` : ''}
                <pre class="chunk-content">${esc(c.content)}</pre>
            </div>`;
        list.appendChild(row);
    }
}

$('#chunk-back').addEventListener('click', showChunkList);

// ---------- Upload (with progress via XHR) ----------

let currentXhr = null;

// Toast notification, auto-dismisses. kind = 'success' | 'error'.
function toast(msg, kind = 'success') {
    const el = document.createElement('div');
    el.className = 'toast' + (kind === 'error' ? ' error' : '');
    el.innerHTML = `<span class="toast-dot"></span><span>${esc(msg)}</span>`;
    $('#toast-host').appendChild(el);
    setTimeout(() => {
        el.classList.add('out');
        el.addEventListener('animationend', () => el.remove(), { once: true });
    }, kind === 'error' ? 4000 : 2800);
}

// The progress row is shown ONLY while an upload is in flight.
function setUpload(name, stage, pct) {
    $('#upload-row').hidden = false;
    $('#upload-name').textContent = name;
    $('#upload-stage').textContent = stage;
    $('#upload-fill').style.width = pct + '%';
}
function hideUpload() {
    $('#upload-row').hidden = true;
    $('#upload-fill').style.width = '0%';
    currentXhr = null;
}

function uploadFile(file) {
    if (!file.name.endsWith('.md')) {
        toast('Only .md files are accepted', 'error');
        return;
    }
    const form = new FormData();
    form.append('file', file);

    const xhr = new XMLHttpRequest();
    currentXhr = xhr;
    xhr.open('POST', '/documents');

    xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
            const pct = Math.round((e.loaded / e.total) * 90); // reserve last 10% for embedding
            setUpload(file.name, `Uploading · ${pct}%`, pct);
        }
    };
    xhr.upload.onload = () => setUpload(file.name, 'Embedding chunks…', 95);

    xhr.onload = () => {
        hideUpload();
        if (xhr.status >= 200 && xhr.status < 300) {
            const body = JSON.parse(xhr.responseText);
            toast(`Imported ${file.name} · ${body.chunksStored ?? '?'} chunks`);
            localStorage.setItem('kb-last-import', 'Today');
            refreshDocs();
        } else {
            let detail = xhr.status;
            try { detail = JSON.parse(xhr.responseText).detail ?? detail; } catch (_) {}
            toast(`Import failed: ${detail}`, 'error');
        }
    };
    xhr.onerror = () => { hideUpload(); toast('Network error during import', 'error'); };
    xhr.send(form);
}

$('#browse-btn').addEventListener('click', () => $('#file-input').click());
$('#file-input').addEventListener('change', (e) => {
    if (e.target.files.length) uploadFile(e.target.files[0]);
    e.target.value = '';
});
$('#upload-cancel').addEventListener('click', () => {
    if (currentXhr) currentXhr.abort();
    hideUpload();
});

const dropArea = $('#drop-area');
dropArea.addEventListener('click', (e) => { if (e.target === dropArea || e.target.classList.contains('dropzone-text') || e.target.classList.contains('dropzone-plus')) $('#file-input').click(); });
dropArea.addEventListener('dragover', (e) => { e.preventDefault(); dropArea.classList.add('drag'); });
dropArea.addEventListener('dragleave', () => dropArea.classList.remove('drag'));
dropArea.addEventListener('drop', (e) => {
    e.preventDefault();
    dropArea.classList.remove('drag');
    if (e.dataTransfer.files.length) uploadFile(e.dataTransfer.files[0]);
});

// ---------- Search ----------

let lastHits = [];
let lastQuery = '';

// Highlight state, persisted like theme.
const hlToggle = $('#hl-toggle');
hlToggle.checked = localStorage.getItem('kb-highlight') !== 'off';
hlToggle.addEventListener('change', () => {
    localStorage.setItem('kb-highlight', hlToggle.checked ? 'on' : 'off');
    if (lastHits.length) renderHits();
});

function escapeRegex(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

// Escape content first (XSS-safe), then wrap literal query terms in <mark>.
// Purely lexical - for semantic modes the query words may not appear, which is expected.
function highlightSnippet(text) {
    const safe = esc(text);
    if (!hlToggle.checked) return safe;
    const terms = [...new Set(lastQuery.toLowerCase().split(/\s+/).filter((t) => t.length >= 2))];
    if (!terms.length) return safe;
    const rx = new RegExp('(' + terms.map(escapeRegex).join('|') + ')', 'gi');
    return safe.replace(rx, '<mark>$1</mark>');
}

function renderHits() {
    const hitsEl = $('#search-hits');
    hitsEl.innerHTML = '';
    if (!lastHits.length) { hitsEl.innerHTML = '<div class="empty-line">No results.</div>'; return; }

    const maxScore = Math.max(...lastHits.map((h) => h.score), 0.0001);
    for (const h of lastHits) {
        const pct = Math.round((h.score / maxScore) * 100);
        const row = document.createElement('div');
        row.className = 'result-row';
        row.innerHTML = `
            <div class="result-main">
                <div class="result-title-line">
                    <span class="result-doc">${esc(h.docId)}</span>
                    ${h.headingPath ? `<span class="result-heading">${esc(h.headingPath)}</span>` : ''}
                </div>
                <div class="result-snippet">${highlightSnippet(h.content)}</div>
            </div>
            <div class="result-score">
                <span class="result-score-val">${h.score.toFixed(3)}</span>
                <div class="score-track"><div class="score-fill" style="width:${pct}%"></div></div>
            </div>`;
        hitsEl.appendChild(row);
    }
}

$('#search-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const q = $('#search-q').value;
    const type = $('#search-type').value;
    const area = $('#search-results');
    const meta = $('#search-meta');

    area.hidden = false;
    meta.textContent = 'Searching…';
    $('#search-hits').innerHTML = '';

    const t0 = performance.now();
    const res = await fetch(`/search?q=${encodeURIComponent(q)}&type=${type}&topK=10`);
    const ms = Math.round(performance.now() - t0);

    if (!res.ok) {
        let detail = res.status;
        try { detail = (await res.json()).detail ?? detail; } catch (_) {}
        meta.textContent = `Error: ${detail}`;
        return;
    }
    lastHits = await res.json();
    lastQuery = q;
    meta.textContent = `${lastHits.length} result${lastHits.length === 1 ? '' : 's'} · ${ms} ms`;
    renderHits();
});

// ---------- Chat (streaming, multi-turn) ----------

const MAX_SENT = 10;               // trim history sent to the server, matches backend guard
const chatMessages = [];           // client-held memory: {role, content, sources?, streaming?, error?}
const threadEl = $('#chat-thread');

function scrollThreadBottom() { threadEl.scrollTop = threadEl.scrollHeight; }

function renderThread() {
    threadEl.innerHTML = '';
    threadEl.hidden = chatMessages.length === 0;
    $('#chat-new').hidden = chatMessages.length === 0;

    for (const m of chatMessages) {
        const wrap = document.createElement('div');
        wrap.className = 'chat-msg ' + m.role;
        const bubble = document.createElement('div');
        bubble.className = 'bubble';

        const text = document.createElement('div');
        text.className = 'bubble-text' + (m.error ? ' error' : '') + (m.streaming ? ' streaming' : '');
        text.textContent = m.content || '';
        bubble.appendChild(text);

        if (m.sources && m.sources.length) {
            const chips = document.createElement('div');
            chips.className = 'chips';
            for (const s of m.sources) {
                const label = s.headingPath ? `${s.docId} · ${s.headingPath}` : `${s.docId} · [${s.index}]`;
                const chip = document.createElement('button');
                chip.className = 'chip';
                chip.type = 'button';
                chip.textContent = label;
                chip.title = 'Click to view source chunk';
                chip.onclick = () => toggleSource(chip, s);
                chips.appendChild(chip);
            }
            bubble.appendChild(chips);
        }
        wrap.appendChild(bubble);
        threadEl.appendChild(wrap);
    }
    scrollThreadBottom();
}

$('#chat-new').addEventListener('click', () => {
    chatMessages.length = 0;
    renderThread();
    $('#chat-q').focus();
});

$('#chat-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const q = $('#chat-q').value.trim();
    if (!q) return;
    $('#chat-q').value = '';

    chatMessages.push({ role: 'user', content: q });
    const assistant = { role: 'assistant', content: '', sources: null, streaming: true };
    chatMessages.push(assistant);
    renderThread();

    // Live handle to the streaming bubble's text node (last message).
    const streamEl = threadEl.querySelector('.chat-msg:last-child .bubble-text');
    const send = $('#chat-send');
    send.disabled = true;

    try {
        const payload = chatMessages
            .filter((m) => !m.streaming)
            .map((m) => ({ role: m.role, content: m.content }))
            .slice(-MAX_SENT);

        const res = await fetch('/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ messages: payload }),
        });

        if (!res.ok || !res.body) {
            let detail = res.status;
            try { detail = (await res.json()).detail ?? detail; } catch (_) {}
            assistant.content = `Error: ${detail}`;
            assistant.error = true;
            streamEl.classList.add('error');
            streamEl.textContent = assistant.content;
            return;
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buf = '';
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buf += decoder.decode(value, { stream: true });
            let nl;
            while ((nl = buf.indexOf('\n')) >= 0) {
                const line = buf.slice(0, nl).trim();
                buf = buf.slice(nl + 1);
                if (!line) continue;
                let frame;
                try { frame = JSON.parse(line); } catch (_) { continue; }
                if (frame.type === 'token') {
                    assistant.content += frame.text;
                    streamEl.textContent = assistant.content;
                    scrollThreadBottom();
                } else if (frame.type === 'sources') {
                    assistant.sources = frame.sources;
                } else if (frame.type === 'error') {
                    assistant.content = (assistant.content ? assistant.content + '\n' : '') + `[error: ${frame.message}]`;
                    assistant.error = true;
                    streamEl.classList.add('error');
                    streamEl.textContent = assistant.content;
                }
            }
        }
    } catch (err) {
        assistant.content = `Error: ${err.message}`;
        assistant.error = true;
        streamEl.textContent = assistant.content;
    } finally {
        assistant.streaming = false;
        send.disabled = false;
        renderThread();       // finalize: drop caret, render citation chips
        $('#chat-q').focus();
    }
});

// Toggle an inline <pre> with the cited chunk content under the chips.
function toggleSource(chip, source) {
    const existing = chip._detail;
    if (existing) { existing.remove(); chip._detail = null; return; }
    const pre = document.createElement('pre');
    pre.className = 'result-snippet';
    pre.style.cssText = 'width:100%; margin:4px 0 0; padding:10px 12px; background:var(--bg); border:1px solid var(--border); border-radius:8px';
    pre.textContent = source.content;
    chip.insertAdjacentElement('afterend', pre);
    chip._detail = pre;
}

// ---------- Compare ----------

// Fixed backend order, matching the /compare response.
const BACKENDS = ['fts', 'pgvector', 'qdrant', 'hybrid', 'rerank'];

$('#compare-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const q = $('#compare-q').value;
    const topK = $('#compare-topk').value;
    const grid = $('#compare-grid');

    grid.hidden = false;
    grid.innerHTML = '<div class="backend-empty">Running all backends…</div>';

    const res = await fetch(`/compare?q=${encodeURIComponent(q)}&topK=${topK}`);
    if (!res.ok) {
        let detail = res.status;
        try { detail = (await res.json()).detail ?? detail; } catch (_) {}
        grid.innerHTML = `<div class="backend-empty">Error: ${esc(String(detail))}</div>`;
        return;
    }
    const data = await res.json();
    grid.innerHTML = '';

    for (const name of BACKENDS) {
        const result = data[name];
        if (!result) continue;
        const hits = result.hits || [];
        const card = document.createElement('div');
        card.className = 'backend-card';
        let rows = '';
        if (!hits.length) {
            rows = '<div class="backend-empty">No results.</div>';
        } else {
            hits.forEach((h, i) => {
                rows += `
                    <div class="crow">
                        <div class="crow-top">
                            <span class="crow-rank">#${i + 1}</span>
                            <span class="crow-doc">${esc(h.docId)}</span>
                            <span class="crow-score">${h.score.toFixed(3)}</span>
                        </div>
                        ${h.headingPath ? `<div class="crow-heading">${esc(h.headingPath)}</div>` : ''}
                        <div class="crow-snippet">${esc(h.content)}</div>
                    </div>`;
            });
        }
        card.innerHTML = `
            <div class="backend-head">
                <span class="backend-name">${name}</span>
                <span class="backend-ms">${result.elapsedMs} ms · ${hits.length}</span>
            </div>
            <div class="backend-body">${rows}</div>`;
        grid.appendChild(card);
    }
});

refreshDocs();
