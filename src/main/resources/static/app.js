// Minimal client for the knowledge-base API. No framework - three fetch-backed zones.

const $ = (sel) => document.querySelector(sel);

// ---------- Import ----------

async function refreshDocs() {
    const res = await fetch('/documents');
    const docs = await res.json();
    const tbody = $('#doc-table tbody');
    tbody.innerHTML = '';
    for (const d of docs) {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td>${esc(d.docId)}</td><td>${esc(d.sourceFile ?? '-')}</td><td>${d.chunkCount}</td>`;
        const td = document.createElement('td');
        const btn = document.createElement('button');
        btn.textContent = 'delete';
        btn.onclick = async () => {
            await fetch(`/documents/${encodeURIComponent(d.docId)}`, { method: 'DELETE' });
            refreshDocs();
        };
        td.appendChild(btn);
        tr.appendChild(td);
        tbody.appendChild(tr);
    }
}

async function uploadFile(file) {
    const status = $('#import-status');
    status.textContent = `uploading ${file.name}...`;
    const form = new FormData();
    form.append('file', file);
    const res = await fetch('/documents', { method: 'POST', body: form });
    if (res.ok) {
        const body = await res.json();
        status.textContent = `imported ${file.name}: ${body.chunksStored ?? JSON.stringify(body)} chunks`;
        refreshDocs();
    } else {
        status.textContent = `error: ${(await res.json()).detail ?? res.status}`;
    }
}

$('#file-input').addEventListener('change', (e) => {
    if (e.target.files.length) uploadFile(e.target.files[0]);
});

const dropArea = $('#drop-area');
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
    const res = await fetch(`/search?q=${encodeURIComponent(q)}&type=${type}&topK=10`);
    const container = $('#search-results');
    if (!res.ok) {
        container.textContent = `error: ${(await res.json()).detail ?? res.status}`;
        return;
    }
    const hits = await res.json();
    container.innerHTML = hits.length ? '' : '<p>no results</p>';
    for (const h of hits) {
        const div = document.createElement('div');
        div.className = 'hit';
        div.innerHTML = `
            <div class="hit-meta">${esc(h.docId)}${h.headingPath ? ' - ' + esc(h.headingPath) : ''}
                <span class="score">${h.score.toFixed(4)}</span></div>
            <pre>${esc(h.content)}</pre>`;
        container.appendChild(div);
    }
});

// ---------- Ask ----------

$('#ask-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const q = $('#ask-q').value;
    const status = $('#ask-status');
    const answerEl = $('#ask-answer');
    const sourcesEl = $('#ask-sources');
    status.textContent = 'thinking...';
    answerEl.textContent = '';
    sourcesEl.innerHTML = '';
    const res = await fetch(`/ask?q=${encodeURIComponent(q)}`);
    if (!res.ok) {
        status.textContent = `error: ${(await res.json()).detail ?? res.status}`;
        return;
    }
    const body = await res.json();
    status.textContent = '';
    answerEl.textContent = body.answer;
    for (const s of body.sources) {
        const details = document.createElement('details');
        details.innerHTML = `
            <summary>[${s.index}] ${esc(s.docId)}${s.headingPath ? ' - ' + esc(s.headingPath) : ''}</summary>
            <pre>${esc(s.content)}</pre>`;
        sourcesEl.appendChild(details);
    }
});

function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

refreshDocs();
