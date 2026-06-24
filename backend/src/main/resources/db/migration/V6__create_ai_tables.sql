CREATE EXTENSION IF NOT EXISTS vector;


/*task embedding used for semantic search; similar words give result*/
CREATE TABLE task_embeddings (
    task_id    UUID PRIMARY KEY REFERENCES tasks(id),
    tenant_id  UUID NOT NULL REFERENCES tenants(id),       /*for tight privacy; dont show something of google t amazon*/
    embedding  VECTOR(1536) NOT NULL,                /*store all embedding for one task in verctor & with pg vector stored inside PostgreSQL only*/
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_task_embeddings_tenant_id ON task_embeddings (tenant_id);
