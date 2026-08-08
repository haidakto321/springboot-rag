CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS chunks (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_id       VARCHAR(255) NOT NULL,
    chunk_index  INT NOT NULL,
    content      TEXT NOT NULL,
    source_file  VARCHAR(512),
    heading_path TEXT,
    tsv          tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    embedding    vector(768) NOT NULL,
    created_at   TIMESTAMP DEFAULT now()
);

ALTER TABLE chunks ADD COLUMN IF NOT EXISTS source_file VARCHAR(512);
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS heading_path TEXT;

CREATE INDEX IF NOT EXISTS idx_chunks_tsv ON chunks USING gin (tsv);
CREATE INDEX IF NOT EXISTS idx_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON chunks (doc_id);

CREATE TABLE IF NOT EXISTS projects (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    group_name VARCHAR(255),
    created_at TIMESTAMP DEFAULT now()
);

-- Seed a Default project (only if the table is empty).
INSERT INTO projects (name)
SELECT 'Default' WHERE NOT EXISTS (SELECT 1 FROM projects);

ALTER TABLE chunks ADD COLUMN IF NOT EXISTS project_id BIGINT;

-- Backfill any chunk without a project to the Default project.
UPDATE chunks
SET project_id = (SELECT id FROM projects WHERE name = 'Default' ORDER BY id LIMIT 1)
WHERE project_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_chunks_project ON chunks (project_id);

-- FK: use single-quote DO body so Spring ScriptUtils does not split on the internal semicolons.
DO '
BEGIN
    ALTER TABLE chunks ADD CONSTRAINT fk_chunks_project
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL;
END
';

-- Trigger function using single-quote body to avoid dollar-quoting issues with Spring SQL parser.
-- (Spring ScriptUtils splits on bare semicolons; single-quoted strings are exempt.)
CREATE OR REPLACE FUNCTION fn_chunks_default_project()
RETURNS TRIGGER LANGUAGE plpgsql AS '
BEGIN
    IF NEW.project_id IS NULL THEN
        RAISE WARNING ''chunks INSERT missing project_id; defaulting to Default project'';
        SELECT id INTO NEW.project_id FROM projects WHERE name = ''Default'' ORDER BY id LIMIT 1;
        IF NEW.project_id IS NULL THEN
            RAISE EXCEPTION ''Default project missing from projects table'';
        END IF;
    END IF;
    RETURN NEW;
END;
';

CREATE OR REPLACE TRIGGER trg_chunks_default_project
    BEFORE INSERT ON chunks
    FOR EACH ROW EXECUTE FUNCTION fn_chunks_default_project();

-- ---- GraphRAG structural graph (Phase 1) ----

ALTER TABLE chunks ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS doc_edge (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT NOT NULL,
    src_doc    VARCHAR(255) NOT NULL,
    dst_doc    VARCHAR(255) NOT NULL,
    kind       VARCHAR(32)  NOT NULL,   -- 'link' | 'hierarchy'
    created_at TIMESTAMP DEFAULT now(),
    UNIQUE (project_id, src_doc, dst_doc, kind)
);

CREATE INDEX IF NOT EXISTS idx_doc_edge_dst ON doc_edge (project_id, dst_doc);

DO '
BEGIN
    ALTER TABLE doc_edge ADD CONSTRAINT fk_doc_edge_project
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL;
END
';

-- ---- GraphRAG semantic layer (Phase 2) ----

CREATE TABLE IF NOT EXISTS entity (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id    BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name_norm     TEXT NOT NULL,
    name_display  TEXT NOT NULL,
    type          VARCHAR(64),
    mention_count INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMP DEFAULT now(),
    UNIQUE (project_id, name_norm)
);

CREATE TABLE IF NOT EXISTS chunk_entity (
    chunk_id  BIGINT NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
    entity_id BIGINT NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
    PRIMARY KEY (chunk_id, entity_id)
);

CREATE TABLE IF NOT EXISTS entity_edge (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    src_entity BIGINT NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
    dst_entity BIGINT NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
    relation   VARCHAR(128) NOT NULL,
    weight     DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    UNIQUE (project_id, src_entity, dst_entity, relation)
);

CREATE INDEX IF NOT EXISTS idx_chunk_entity_entity ON chunk_entity (entity_id);
CREATE INDEX IF NOT EXISTS idx_entity_edge_src ON entity_edge (project_id, src_entity);
CREATE INDEX IF NOT EXISTS idx_entity_name ON entity (project_id, name_norm);

-- ---- Access labels (RAG-MASTERY section 1) ----
-- Stamped at ingest, filtered INSIDE every retrieval query. NULL means "written before access
-- control existed"; the one-time backfill below hands those to the 'public' group. After that,
-- a chunk with an empty array is readable by NOBODY - the array overlap operator (&&) is false
-- for both NULL and '{}', so the default is deny, not allow.
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS allowed_groups TEXT[];

UPDATE chunks SET allowed_groups = ARRAY['public'] WHERE allowed_groups IS NULL;

CREATE INDEX IF NOT EXISTS idx_chunks_allowed_groups ON chunks USING gin (allowed_groups);

-- ---- Per-request RAG trace (RAG-MASTERY section 6) ----
-- One row per answered question. When an answer is wrong nothing throws, so the only way to debug
-- it is to record every decision that produced it: which query was really searched, which chunks
-- came back at what score, and where the time went.
-- The variable-shaped parts are JSONB: the retrieved list and the stage timings both change shape
-- as backends change, and neither is ever joined on.
CREATE TABLE IF NOT EXISTS rag_trace (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id        UUID NOT NULL UNIQUE,
    ts                TIMESTAMPTZ NOT NULL DEFAULT now(),
    principal         VARCHAR(255) NOT NULL,
    project_ids       BIGINT[],
    raw_query         TEXT NOT NULL,
    condensed_query   TEXT,
    backend           VARCHAR(32) NOT NULL,
    retrieved         JSONB NOT NULL DEFAULT '[]'::jsonb,   -- [{docId, chunkIndex, score}]
    stage_latency_ms  JSONB NOT NULL DEFAULT '{}'::jsonb,   -- {embed, retrieve, generate, total}
    prompt_tokens     INT,
    completion_tokens INT,
    answer            TEXT,
    guard_reason      VARCHAR(32)
);

CREATE INDEX IF NOT EXISTS idx_rag_trace_principal ON rag_trace (principal, ts DESC);

-- ---- Per-chunk relevance feedback (eval only - never feeds live ranking) ----
-- Keyed by (doc_id, chunk_index), NOT by chunks.id: re-ingesting a document deletes and
-- reinserts its rows, so a chunk id is not stable across imports but the pair is.
-- query_text is capped so the UNIQUE btree index can never exceed the ~2704-byte row limit.
CREATE TABLE IF NOT EXISTS chunk_feedback (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    query_text  TEXT NOT NULL CHECK (char_length(query_text) BETWEEN 1 AND 500),
    doc_id      VARCHAR(255) NOT NULL,
    chunk_index INT NOT NULL CHECK (chunk_index >= 0),
    rating      VARCHAR(8) NOT NULL CHECK (rating IN ('up', 'down')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, doc_id, chunk_index, query_text)
);

CREATE INDEX IF NOT EXISTS idx_chunk_feedback_project ON chunk_feedback (project_id, updated_at DESC);

-- ---- Extracted-record support (2026-08-06) ----
-- doc_type is the render-profile lookup key and a filter field. Deliberately free-form: the set
-- of document types an extraction pipeline emits is open, so a validated enum would reject the
-- interesting case (a type nobody configured yet).
-- metadata holds three nested trees - values (extracted data), prov (confidence/page/bbox),
-- conf (per-chunk min/avg). Nested rather than flat dotted keys because Qdrant parses '.' in a
-- filter key as a path separator, and both stores must agree on one shape.
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS doc_type VARCHAR(128);
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS idx_chunks_metadata ON chunks USING gin (metadata jsonb_path_ops);
CREATE INDEX IF NOT EXISTS idx_chunks_doc_type ON chunks (project_id, doc_type);

-- One OPTIONAL rendering configuration per (project, docType). Absent means generic rendering,
-- which is what makes an unconfigured document type searchable the moment it lands.
-- version is bumped on every write and participates in the freshness hash, so editing a profile
-- re-indexes exactly the documents of that type and nothing else.
CREATE TABLE IF NOT EXISTS render_profile (
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    doc_type   VARCHAR(128) NOT NULL,
    body       JSONB NOT NULL,
    version    INT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, doc_type)
);

-- One row per indexed document: what was indexed, from what, and under which settings.
-- content_hash covers the RENDERED text and drives re-embedding; raw_hash covers the raw record
-- and drives a cheap metadata refresh when only provenance (a confidence, a bbox) changed.
-- Splitting them is what stops a re-extraction that jitters a score from re-embedding a corpus
-- to produce byte-identical vectors.
CREATE TABLE IF NOT EXISTS document (
    project_id      BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    doc_id          VARCHAR(255) NOT NULL,
    doc_type        VARCHAR(128),
    origin          VARCHAR(32) NOT NULL DEFAULT 'record',   -- 'record' | 'upload'
    content_hash    CHAR(64) NOT NULL,
    raw_hash        CHAR(64) NOT NULL,
    embed_model     VARCHAR(128) NOT NULL,
    profile_version INT,
    allowed_groups  TEXT[] NOT NULL,
    chunk_count     INT NOT NULL,
    indexed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, doc_id)
);

-- ---- Query understanding (2026-08-06) ----
-- The filter that was actually applied and whether it had to be dropped. Without these, the one
-- question a surprised user asks - "why did it not find my document?" - has no answer.
ALTER TABLE rag_trace ADD COLUMN IF NOT EXISTS applied_filter JSONB;
ALTER TABLE rag_trace ADD COLUMN IF NOT EXISTS filter_widened BOOLEAN NOT NULL DEFAULT false;

-- ---- Query routing (2026-08-08) ----
-- Which path answered: chitchat, aggregate, or search. A column rather than a stage-map key
-- because rows get filtered by it ("every question that took the aggregate path"). Route LATENCY
-- stays inside stage_latency_ms, which is JSONB precisely so a new stage needs no migration.
ALTER TABLE rag_trace ADD COLUMN IF NOT EXISTS route VARCHAR(16);
