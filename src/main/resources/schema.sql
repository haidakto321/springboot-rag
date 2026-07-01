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
