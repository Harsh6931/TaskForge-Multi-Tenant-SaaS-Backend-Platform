-- V8__add_task_fts.sql
-- Adds PostgreSQL full-text search capability to the tasks table.
--
-- Strategy: GENERATED ALWAYS AS ... STORED
--   A generated column that Postgres automatically keeps in sync whenever
--   `title` or `description` changes. STORED means the value is physically
--   written to disk (vs. computed on read), which allows a GIN index to be
--   built over it.
--
-- Weight rationale:
--   setweight('A') → title matches rank higher (more relevant)
--   setweight('B') → description matches rank lower
--
-- coalesce(col, '') handles NULL description so to_tsvector never returns NULL.

ALTER TABLE tasks
    ADD COLUMN IF NOT EXISTS search_vector tsvector
        GENERATED ALWAYS AS (
            setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
            setweight(to_tsvector('english', coalesce(description, '')), 'B')
        ) STORED;

-- GIN index for fast full-text search on the generated column.
-- GIN (Generalised Inverted Index) is the correct index type for tsvector —
-- it maps each lexeme to the rows that contain it, enabling O(log n) lookups
-- instead of the full-table sequential scan that LIKE '%query%' requires.
CREATE INDEX IF NOT EXISTS idx_tasks_search_vector ON tasks USING GIN (search_vector);
