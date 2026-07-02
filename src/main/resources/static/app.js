// Client for the knowledge-base API. No framework. Two screens: Documents and Search & Ask.

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

// ---------- Screen switching ----------

const SCREENS = {
    docs: {
        title: 'Documents & Import',
        sub: 'Import markdown files and manage your indexed documents.',
    },
    query: {
        title: 'Search & Ask',
        sub: 'Search chunks and ask questions grounded in your knowledge base.',
    },
};

function showScreen(name) {
    $('#screen-docs').hidden = name !== 'docs';
    $('#screen-query').hidden = name !== 'query';
    $('#nav-docs').classList.toggle('active', name === 'docs');
    $('#nav-query').classList.toggle('active', name === 'query');
    $('#screen-title').textContent = SCREENS[name].title;
    $('#screen-sub').textContent = SCREENS[name].sub;
}

$('#nav-docs').addEventListener('click', () => showScreen('docs'));
$('#nav-query').addEventListener('click', () => showScreen('query'));

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
        const act = document.createElement('button');
        act.className = 'btn-delete';
        act.textContent = 'Delete';
        act.onclick = async () => {
            if (!confirm(`Delete document "${d.docId}"?`)) return;
            await fetch(`/documents/${encodeURIComponent(d.docId)}`, { method: 'DELETE' });
            refreshDocs();
        };
        row.appendChild(act);
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

// ---------- Upload (with progress via XHR) ----------

let currentXhr = null;

function setUpload(name, stage, pct) {
    $('#upload-row').hidden = false;
    $('#upload-name').textContent = name;
    $('#upload-stage').textContent = stage;
    $('#upload-fill').style.width = pct + '%';
}
function hideUpload() {
    $('#upload-row').hidden = true;
    currentXhr = null;
}

function uploadFile(file) {
    if (!file.name.endsWith('.md')) {
        setUpload(file.name, 'Only .md files are accepted', 0);
        setTimeout(hideUpload, 2500);
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
        if (xhr.status >= 200 && xhr.status < 300) {
            const body = JSON.parse(xhr.responseText);
            setUpload(file.name, `Imported · ${body.chunksStored ?? '?'} chunks`, 100);
            localStorage.setItem('kb-last-import', 'Today');
            setTimeout(hideUpload, 1500);
            refreshDocs();
        } else {
            let detail = xhr.status;
            try { detail = JSON.parse(xhr.responseText).detail ?? detail; } catch (_) {}
            setUpload(file.name, `Error: ${detail}`, 0);
            setTimeout(hideUpload, 3000);
        }
    };
    xhr.onerror = () => { setUpload(file.name, 'Network error', 0); setTimeout(hideUpload, 3000); };
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

$('#search-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const q = $('#search-q').value;
    const type = $('#search-type').value;
    const area = $('#search-results');
    const meta = $('#search-meta');
    const hitsEl = $('#search-hits');

    area.hidden = false;
    meta.textContent = 'Searching…';
    hitsEl.innerHTML = '';

    const t0 = performance.now();
    const res = await fetch(`/search?q=${encodeURIComponent(q)}&type=${type}&topK=10`);
    const ms = Math.round(performance.now() - t0);

    if (!res.ok) {
        let detail = res.status;
        try { detail = (await res.json()).detail ?? detail; } catch (_) {}
        meta.textContent = `Error: ${detail}`;
        return;
    }
    const hits = await res.json();
    meta.textContent = `${hits.length} result${hits.length === 1 ? '' : 's'} · ${ms} ms`;
    if (!hits.length) { hitsEl.innerHTML = '<div class="empty-line">No results.</div>'; return; }

    const maxScore = Math.max(...hits.map((h) => h.score), 0.0001);
    for (const h of hits) {
        const pct = Math.round((h.score / maxScore) * 100);
        const row = document.createElement('div');
        row.className = 'result-row';
        row.innerHTML = `
            <div class="result-main">
                <div class="result-title-line">
                    <span class="result-doc">${esc(h.docId)}</span>
                    ${h.headingPath ? `<span class="result-heading">${esc(h.headingPath)}</span>` : ''}
                </div>
                <div class="result-snippet">${esc(h.content)}</div>
            </div>
            <div class="result-score">
                <span class="result-score-val">${h.score.toFixed(3)}</span>
                <div class="score-track"><div class="score-fill" style="width:${pct}%"></div></div>
            </div>`;
        hitsEl.appendChild(row);
    }
});

// ---------- Ask ----------

$('#ask-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const q = $('#ask-q').value;
    const area = $('#ask-answer-area');
    const status = $('#ask-status');
    const answerEl = $('#ask-answer');
    const sourcesEl = $('#ask-sources');
    const btn = $('#ask-btn');

    area.hidden = false;
    btn.disabled = true;
    status.textContent = 'Thinking…';
    answerEl.textContent = '';
    sourcesEl.innerHTML = '';

    try {
        const res = await fetch(`/ask?q=${encodeURIComponent(q)}`);
        if (!res.ok) {
            let detail = res.status;
            try { detail = (await res.json()).detail ?? detail; } catch (_) {}
            status.textContent = `Error: ${detail}`;
            return;
        }
        const body = await res.json();
        const n = body.sources.length;
        status.textContent = `Answer · grounded in ${n} chunk${n === 1 ? '' : 's'}`;
        answerEl.textContent = body.answer;

        for (const s of body.sources) {
            const label = s.headingPath ? `${s.docId} · ${s.headingPath}` : `${s.docId} · [${s.index}]`;
            const chip = document.createElement('button');
            chip.className = 'chip';
            chip.type = 'button';
            chip.textContent = label;
            chip.title = 'Click to view source chunk';
            chip.onclick = () => toggleSource(chip, s);
            sourcesEl.appendChild(chip);
        }
    } finally {
        btn.disabled = false;
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

refreshDocs();
