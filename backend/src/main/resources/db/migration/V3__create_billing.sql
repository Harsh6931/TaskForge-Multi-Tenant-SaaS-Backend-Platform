CREATE TYPE subscription_status AS ENUM ('ACTIVE', 'CANCELLED', 'PAST_DUE');
CREATE TYPE usage_metric AS ENUM ('API_CALLS', 'STORAGE_MB');

CREATE TABLE plans (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name                    VARCHAR(100) NOT NULL,
    max_seats               INTEGER NOT NULL,
    max_projects            INTEGER NOT NULL,
    max_storage_mb          INTEGER NOT NULL,
    max_api_calls_month     INTEGER NOT NULL,
    price_cents             INTEGER NOT NULL
);

ALTER TABLE tenants
    ADD CONSTRAINT fk_tenants_plan
    FOREIGN KEY (plan_id) REFERENCES plans(id);

CREATE TABLE subscriptions (
    id                     UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id              UUID NOT NULL REFERENCES tenants(id),
    plan_id                UUID NOT NULL REFERENCES plans(id),
    status                 subscription_status NOT NULL,
    stripe_subscription_id VARCHAR(255),
    current_period_start   TIMESTAMPTZ NOT NULL,
    current_period_end     TIMESTAMPTZ NOT NULL
);

CREATE TABLE usage_records (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id    UUID NOT NULL REFERENCES tenants(id),
    metric       usage_metric NOT NULL,
    value        INTEGER NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_tenants_plan_id ON tenants (plan_id) WHERE deleted_at IS NULL;  /*Seperate active tenants based on plan type for fast browsing eg .Search tenant where plan="pro"*/
CREATE INDEX idx_subscriptions_tenant_id ON subscriptions (tenant_id);
CREATE INDEX idx_subscriptions_plan_id ON subscriptions (plan_id);
CREATE INDEX idx_usage_records_tenant_period ON usage_records (tenant_id, period_start, period_end);  /*tenant_id first as all associated with it in shared schema; also composite is more efficient*/

/*Indexed as frequent query eg Find company Y plan status as of June*/