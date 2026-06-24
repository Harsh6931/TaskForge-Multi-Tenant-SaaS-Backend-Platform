CREATE TABLE refresh_tokens (                     /*Refresh token used to regenerate acess token for JWT*/
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id    UUID NOT NULL REFERENCES users(id),
    token_hash TEXT NOT NULL,                      /*token id*/               
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ
);

CREATE TABLE api_keys (                  /*API key for bot accessing */
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id    UUID NOT NULL REFERENCES tenants(id),       /*API key belong to tenant*/
    key_hash     TEXT NOT NULL,
    name         VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ,                   /*to find compromised and unused keys*/
    revoked_at   TIMESTAMPTZ
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX idx_api_keys_tenant_id ON api_keys (tenant_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_api_keys_key_hash ON api_keys (key_hash) WHERE revoked_at IS NULL;

