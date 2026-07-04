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

CREATE INDEX IF NOT EXISTS idx_doc_edge_src ON doc_edge (project_id, src_doc);
CREATE INDEX IF NOT EXISTS idx_doc_edge_dst ON doc_edge (project_id, dst_doc);

DO '
BEGIN
    ALTER TABLE doc_edge ADD CONSTRAINT fk_doc_edge_project
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL;
END
';
