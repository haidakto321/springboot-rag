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
