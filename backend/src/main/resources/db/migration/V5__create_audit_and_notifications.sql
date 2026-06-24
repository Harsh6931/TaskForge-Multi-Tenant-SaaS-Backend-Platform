CREATE TABLE audit_logs (      /*No project_id as log need to survive after project deleted also; many logs like API keys etc dont belong to projects*/
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id     UUID NOT NULL REFERENCES tenants(id),   /*better as all log belong to the tenant user & that tenant user is assigned a project*/
    user_id       UUID NOT NULL REFERENCES users(id),
    action        VARCHAR(255) NOT NULL,
    resource_type VARCHAR(255) NOT NULL,
    resource_id   UUID NOT NULL,
    metadata      JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE notifications (  /*X assign task to Y; Y should be notified*/
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id  UUID NOT NULL REFERENCES tenants(id),   /*which workspace*/
    user_id    UUID NOT NULL REFERENCES users(id),    /*who receive it ; Y*/
    type       VARCHAR(255) NOT NULL,
    payload    JSONB NOT NULL,  /*contain notification detail; as it contain different detail for each static table make it very null; better JSONB make it flexible*/
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_tenant_created ON audit_logs (tenant_id, created_at);
CREATE INDEX idx_audit_logs_user_id ON audit_logs (user_id);
CREATE INDEX idx_notifications_tenant_user ON notifications (tenant_id, user_id);

