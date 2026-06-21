-- Run once at DB initialisation to enable the pgvector extension.
-- This script is executed by the postgres Docker image via /docker-entrypoint-initdb.d/
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- for full-text search trigrams
