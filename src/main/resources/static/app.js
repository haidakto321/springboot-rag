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

// ---------- Projects ----------

let activeProjectId = null;

// Helper for Task 11: prefix any path with /projects/{activeProjectId}.
function projectFetch(path, opts) {
    return fetch(`/projects/${activeProjectId}${path}`, opts);
}

async function loadProjects() {
    const res = await fetch('/projects');
    if (!res.ok) return;
    const projects = await res.json();

    const sel = $('#project-select');
    sel.innerHTML = '';

    // Group projects by groupName
    const grouped = {};
    const ungrouped = [];
    for (const p of projects) {
        if (p.groupName) {
            if (!grouped[p.groupName]) grouped[p.groupName] = [];
            grouped[p.groupName].push(p);
        } else {
            ungrouped.push(p);
        }
    }

    for (const [grp, list] of Object.entries(grouped)) {
        const og = document.createElement('optgroup');
        og.label = grp;
        for (const p of list) {
            const o = document.createElement('option');
            o.value = p.id;
            o.textContent = p.name;
            og.appendChild(o);
        }
        sel.appendChild(og);
    }

    if (ungrouped.length) {
        const og = document.createElement('optgroup');
        og.label = 'No group';
        for (const p of ungrouped) {
            const o = document.createElement('option');
            o.value = p.id;
            o.textContent = p.name;
            og.appendChild(o);
        }
        sel.appendChild(og);
    }

    // Restore active project: localStorage -> "Default" -> first
    const saved = localStorage.getItem('kb-project');
    let target = projects.find((p) => String(p.id) === String(saved));
    if (!target) target = projects.find((p) => p.name === 'Default');
    if (!target && projects.length) target = projects[0];

    if (target) {
        sel.value = String(target.id);
        activeProjectId = String(target.id);
        localStorage.setItem('kb-project', activeProjectId);
    }

    refreshDocs();
}

async function loadGroups() {
    try {
        const res = await fetch('/groups');
        if (!res.ok) return;
        const groups = await res.json();
        const dl = $('#group-list');
        dl.innerHTML = '';
        for (const g of groups) {
            const o = document.createElement('option');
            o.value = g;
            dl.appendChild(o);
        }
    } catch (_) {}
}

async function renderModalProjectList() {
    const res = await fetch('/projects');
    if (!res.ok) return;
    const projects = await res.json();
    const list = $('#modal-project-list');
    list.innerHTML = '';

    if (!projects.length) {
        list.innerHTML = '<div class="empty-line">No projects yet.</div>';
        return;
    }

    for (const p of projects) {
        const row = document.createElement('div');
        row.className = 'pm-row';

        const nameIn = document.createElement('input');
        nameIn.type = 'text';
        nameIn.className = 'pm-input pm-name';
        nameIn.value = p.name;
        nameIn.autocomplete = 'off';

        const groupIn = document.createElement('input');
        groupIn.type = 'text';
        groupIn.className = 'pm-input pm-group';
        groupIn.setAttribute('list', 'group-list');
        groupIn.value = p.groupName || '';
        groupIn.placeholder = 'No group';
        groupIn.autocomplete = 'off';

        const saveBtn = document.createElement('button');
        saveBtn.type = 'button';
        saveBtn.className = 'btn-pm-save';
        saveBtn.textContent = 'Save';
        saveBtn.onclick = async () => {
            const newName = nameIn.value.trim();
            const newGroup = groupIn.value.trim() || null;
            if (!newName) return;
            const patch = {};
            if (newName !== p.name) patch.name = newName;
            const oldGroup = p.groupName || null;
            if (newGroup !== oldGroup) patch.groupName = newGroup;
            if (!Object.keys(patch).length) return;
            const r = await fetch(`/projects/${p.id}`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(patch),
            });
            if (r.ok) {
                toast('Project updated');
                await loadProjects();
                await loadGroups();
                await renderModalProjectList();
            } else {
                toast('Update failed', 'error');
            }
        };

        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'btn-pm-delete';
        delBtn.textContent = 'Delete';
        delBtn.onclick = async () => {
            if (!confirm(`Delete "${p.name}"?`)) return;
            const r = await fetch(`/projects/${p.id}`, { method: 'DELETE' });
            if (r.ok) {
                toast('Project deleted');
                await loadProjects();
                await renderModalProjectList();
            } else {
                toast('Delete failed', 'error');
            }
        };

        row.appendChild(nameIn);
        row.appendChild(groupIn);
        row.appendChild(saveBtn);
        row.appendChild(delBtn);
        list.appendChild(row);
    }
}

async function openProjectModal() {
    await Promise.all([loadGroups(), renderModalProjectList()]);
    $('#project-modal').hidden = false;
}

function closeProjectModal() {
    $('#project-modal').hidden = true;
}

$('#ps-new').addEventListener('click', async () => {
    await openProjectModal();
    $('#pc-name').focus();
});

$('#ps-manage').addEventListener('click', openProjectModal);

$('#modal-close').addEventListener('click', closeProjectModal);

$('#project-modal').addEventListener('click', (e) => {
    if (e.target === $('#project-modal')) closeProjectModal();
});

$('#project-select').addEventListener('change', () => {
    activeProjectId = $('#project-select').value;
    localStorage.setItem('kb-project', activeProjectId);
    // Clear search results and chat thread
    $('#search-results').hidden = true;
    lastHits = [];
    lastQuery = '';
    chatMessages.length = 0;
    renderThread();
    refreshDocs();
});

$('#project-create-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const name = $('#pc-name').value.trim();
    const group = $('#pc-group').value.trim() || null;
    if (!name) return;
    const r = await fetch('/projects', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, groupName: group }),
    });
    if (r.ok) {
        toast('Project created');
        $('#pc-name').value = '';
        $('#pc-group').value = '';
        await loadProjects();
        await loadGroups();
        await renderModalProjectList();
    } else {
        toast('Create failed', 'error');
    }
});

// ---------- Document scope (filter) ----------

let allDocIds = [];
const selectedScope = new Set();   // selected docIds; empty OR all selected = no filter
let scopeInitialized = false;

// Keep the scope selection in sync with the current document list.
function syncScope(newDocIds) {
    if (!scopeInitialized) {
        newDocIds.forEach((id) => selectedScope.add(id));
        scopeInitialized = true;
    } else {
        for (const id of [...selectedScope]) if (!newDocIds.includes(id)) selectedScope.delete(id);
        for (const id of newDocIds) if (!allDocIds.includes(id)) selectedScope.add(id); // new doc -> selected
    }
    allDocIds = newDocIds;
    renderScopeChips();
}

// Empty result = "all documents" (all or none selected -> send no filter).
function scopeDocIds() {
    if (selectedScope.size === 0 || selectedScope.size === allDocIds.length) return [];
    return allDocIds.filter((id) => selectedScope.has(id));
}

function renderScopeChips() {
    const show = allDocIds.length > 1;
    for (const [barId, chipsId] of [['query-scope', 'query-scope-chips'], ['compare-scope', 'compare-scope-chips']]) {
        $('#' + barId).hidden = !show;
        const host = $('#' + chipsId);
        host.innerHTML = '';
        for (const id of allDocIds) {
            const b = document.createElement('button');
            b.type = 'button';
            b.className = 'scope-chip' + (selectedScope.has(id) ? ' on' : '');
            b.textContent = id;
            b.onclick = () => {
                if (selectedScope.has(id)) selectedScope.delete(id); else selectedScope.add(id);
                renderScopeChips();
            };
            host.appendChild(b);
        }
    }
}

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

    syncScope(docs.map((d) => d.docId));
}

// Append &docIds=... for each scoped document (nothing when unscoped).
function appendScope(url) {
    const ids = scopeDocIds();
    return ids.reduce((u, id) => u + `&docIds=${encodeURIComponent(id)}`, url);
}

// ---------- Chunk sub-view ----------

function showChunkList() {
    $('#docs-list-view').hidden = false;
    $('#docs-chunk-view').hidden = true;
}

async function showChunkView(docId, focusIndex) {
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
        row.dataset.index = c.index;
        row.innerHTML = `
            <span class="chunk-index-badge">${c.index}</span>
            <div class="chunk-body">
                ${c.headingPath ? `<div class="chunk-heading">${esc(c.headingPath)}</div>` : ''}
                <pre class="chunk-content">${esc(c.content)}</pre>
            </div>`;
        list.appendChild(row);
    }

    if (focusIndex !== undefined && focusIndex !== null) {
        const target = list.querySelector(`.result-row[data-index="${focusIndex}"]`);
        if (target) {
            target.scrollIntoView({ block: 'center', behavior: 'smooth' });
            target.classList.add('chunk-flash');
            target.addEventListener('animationend', () => target.classList.remove('chunk-flash'), { once: true });
        }
    }
}

// Jump from a search/compare hit to its chunk in the Documents chunk view.
function openInContext(docId, chunkIndex) {
    showScreen('docs');
    showChunkView(docId, chunkIndex);
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
let searchTopK = 10;
const SNIPPET_WINDOW = 260;
const expandedHits = new Set();   // hit ids whose snippet is expanded to full

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

// Best-matching passage window: center on the first query-term match, else the start.
function windowSnippet(content) {
    if (content.length <= SNIPPET_WINDOW) return { text: content, truncated: false };
    const terms = lastQuery.toLowerCase().split(/\s+/).filter((t) => t.length >= 2);
    const lc = content.toLowerCase();
    let at = -1;
    for (const t of terms) { const i = lc.indexOf(t); if (i >= 0 && (at < 0 || i < at)) at = i; }
    const start = at < 0 ? 0 : Math.max(0, at - 60);
    let text = content.slice(start, start + SNIPPET_WINDOW);
    if (start > 0) text = '…' + text;
    if (start + SNIPPET_WINDOW < content.length) text = text + '…';
    return { text, truncated: true };
}

function renderHits() {
    const hitsEl = $('#search-hits');
    hitsEl.innerHTML = '';
    if (!lastHits.length) {
        const type = $('#search-type').value;
        const tip = type === 'fts'
            ? ' Try a semantic mode (pgvector / qdrant / hybrid) for meaning-based matches.'
            : (type === 'pgvector' || type === 'qdrant')
                ? ' Try hybrid or keyword (FTS) for exact terms and codes.'
                : '';
        hitsEl.innerHTML = `<div class="empty-line">No results.${tip}</div>`;
        return;
    }
    const maxScore = Math.max(...lastHits.map((h) => h.score), 0.0001);
    for (const h of lastHits) {
        const pct = Math.round((h.score / maxScore) * 100);
        const expanded = expandedHits.has(h.id);
        const win = windowSnippet(h.content);
        const snippet = expanded ? highlightSnippet(h.content) : highlightSnippet(win.text);
        const toggle = win.truncated
            ? `<button class="snippet-toggle" data-id="${h.id}" type="button">${expanded ? 'Show less' : 'Show full'}</button>`
            : '';
        const row = document.createElement('div');
        row.className = 'result-row clickable';
        row.title = 'Open in document';
        row.onclick = (e) => { if (e.target.closest('.snippet-toggle')) return; openInContext(h.docId, h.chunkIndex); };
        row.innerHTML = `
            <div class="result-main">
                <div class="result-title-line">
                    <span class="result-doc">${esc(h.docId)}</span>
                    ${h.headingPath ? `<span class="result-heading">${esc(h.headingPath)}</span>` : ''}
                    <span class="open-cue">open ↗</span>
                </div>
                <div class="result-snippet">${snippet}</div>
                ${toggle}
            </div>
            <div class="result-score">
                <span class="result-score-val">${h.score.toFixed(3)}</span>
                <div class="score-track"><div class="score-fill" style="width:${pct}%"></div></div>
            </div>`;
        hitsEl.appendChild(row);
    }
}

// Delegated: expand/collapse a snippet without triggering the row's open-in-context.
$('#search-hits').addEventListener('click', (e) => {
    const t = e.target.closest('.snippet-toggle');
    if (!t) return;
    const id = Number(t.dataset.id);
    if (expandedHits.has(id)) expandedHits.delete(id); else expandedHits.add(id);
    renderHits();
});

// ---------- Recent searches (localStorage) ----------

function recentSearches() {
    try { return JSON.parse(localStorage.getItem('kb-recent') || '[]'); } catch (_) { return []; }
}
function recordRecent(q) {
    const list = recentSearches().filter((x) => x !== q);
    list.unshift(q);
    localStorage.setItem('kb-recent', JSON.stringify(list.slice(0, 8)));
    renderRecent();
}
function renderRecent() {
    const dl = $('#recent-list');
    dl.innerHTML = '';
    for (const q of recentSearches()) {
        const o = document.createElement('option');
        o.value = q;
        dl.appendChild(o);
    }
}
renderRecent();

// ---------- Search runner (pagination + debounce) ----------

async function runSearch(reset, record) {
    const q = $('#search-q').value;
    const type = $('#search-type').value;
    if (!q.trim()) return;
    if (reset) { searchTopK = 10; expandedHits.clear(); }

    const area = $('#search-results');
    const meta = $('#search-meta');
    area.hidden = false;
    meta.textContent = 'Searching…';

    const t0 = performance.now();
    const res = await fetch(appendScope(`/search?q=${encodeURIComponent(q)}&type=${type}&topK=${searchTopK}`));
    const ms = Math.round(performance.now() - t0);

    if (!res.ok) {
        let detail = res.status;
        try { detail = (await res.json()).detail ?? detail; } catch (_) {}
        meta.textContent = `Error: ${detail}`;
        $('#search-more').hidden = true;
        return;
    }
    lastHits = await res.json();
    lastQuery = q;
    meta.textContent = `${lastHits.length} result${lastHits.length === 1 ? '' : 's'} · ${ms} ms`;
    renderHits();
    // Heuristic: a full page back means there may be more.
    $('#search-more').hidden = !(lastHits.length > 0 && lastHits.length === searchTopK);
    if (record) recordRecent(q);
}

$('#search-form').addEventListener('submit', (e) => { e.preventDefault(); runSearch(true, true); });
$('#search-more').addEventListener('click', () => { searchTopK += 10; runSearch(false, false); });

let searchDebounce;
$('#search-q').addEventListener('input', () => {
    clearTimeout(searchDebounce);
    if ($('#search-q').value.trim().length < 2) return;
    searchDebounce = setTimeout(() => runSearch(true, false), 450);   // live search; recorded only on submit
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
                const chip = document.createElement('span');
                chip.className = 'chip';

                const peek = document.createElement('button');
                peek.type = 'button';
                peek.className = 'chip-label';
                peek.textContent = label;
                peek.title = 'Show source chunk inline';
                peek.onclick = () => toggleSource(chip, s);

                const open = document.createElement('button');
                open.type = 'button';
                open.className = 'chip-open';
                open.textContent = '↗';
                open.title = 'Open in document';
                open.onclick = () => openInContext(s.docId, s.chunkIndex);

                chip.appendChild(peek);
                chip.appendChild(open);
                chips.appendChild(chip);
            }
            bubble.appendChild(chips);
        }

        // Finalized assistant answers get copy + feedback actions.
        if (m.role === 'assistant' && !m.streaming && !m.error && m.content) {
            const actions = document.createElement('div');
            actions.className = 'msg-actions';

            const copy = document.createElement('button');
            copy.type = 'button';
            copy.className = 'msg-action';
            copy.textContent = 'Copy';
            copy.onclick = () => navigator.clipboard.writeText(m.content).then(() => toast('Answer copied'));

            const up = document.createElement('button');
            up.type = 'button';
            up.className = 'msg-action';
            up.textContent = '👍';
            up.title = 'Helpful';
            up.onclick = () => recordFeedback(m, 'up', up, down);

            const down = document.createElement('button');
            down.type = 'button';
            down.className = 'msg-action';
            down.textContent = '👎';
            down.title = 'Not helpful';
            down.onclick = () => recordFeedback(m, 'down', up, down);

            if (m.feedback === 'up') up.classList.add('on');
            if (m.feedback === 'down') down.classList.add('on');

            actions.appendChild(copy);
            actions.appendChild(up);
            actions.appendChild(down);
            bubble.appendChild(actions);
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
            body: JSON.stringify({ messages: payload, docIds: scopeDocIds() }),
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

// Local-only thumbs feedback (no backend); toggles and logs to localStorage.
function recordFeedback(m, rating, upBtn, downBtn) {
    m.feedback = m.feedback === rating ? null : rating;
    upBtn.classList.toggle('on', m.feedback === 'up');
    downBtn.classList.toggle('on', m.feedback === 'down');
    try {
        const log = JSON.parse(localStorage.getItem('kb-feedback') || '[]');
        log.push({ rating: m.feedback, answer: (m.content || '').slice(0, 80), at: Date.now() });
        localStorage.setItem('kb-feedback', JSON.stringify(log.slice(-100)));
    } catch (_) {}
    if (m.feedback) toast(m.feedback === 'up' ? 'Marked helpful' : 'Marked not helpful');
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

    const res = await fetch(appendScope(`/compare?q=${encodeURIComponent(q)}&topK=${topK}`));
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
                    <div class="crow clickable" data-doc="${esc(h.docId)}" data-index="${h.chunkIndex}" title="Open in document">
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

// Delegated: click a compare row to open that chunk in its document.
$('#compare-grid').addEventListener('click', (e) => {
    const row = e.target.closest('.crow');
    if (row && row.dataset.doc) openInContext(row.dataset.doc, Number(row.dataset.index));
});

// ---------- Keyboard shortcut: "/" focuses the search box ----------
document.addEventListener('keydown', (e) => {
    if (e.key !== '/' || e.metaKey || e.ctrlKey || e.altKey) return;
    const tag = document.activeElement && document.activeElement.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
    e.preventDefault();
    showScreen('query');
    $('#search-q').focus();
});

loadProjects();
