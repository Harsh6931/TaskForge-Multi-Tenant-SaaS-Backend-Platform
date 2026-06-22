-- Temporary migration used only to verify that Flyway is wired correctly.
--
-- Expected result after the backend starts:
--   1. flyway_schema_history contains a successful version 1 row.
--   2. flyway_smoke_test contains the seed row below.
--
-- Before Goal 2, reset the local PostgreSQL volume and replace this file with
-- the real V1 schema migration. Never rewrite an applied migration in a shared
-- or production database.

CREATE TABLE flyway_smoke_test (
    id         SERIAL PRIMARY KEY,
    message    TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO flyway_smoke_test (message)
VALUES ('Flyway is working! Migration V1 ran successfully.');
