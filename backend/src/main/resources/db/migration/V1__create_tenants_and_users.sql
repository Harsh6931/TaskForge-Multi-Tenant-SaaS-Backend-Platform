CREATE EXTENSION IF NOT EXISTS "uuid-ossp";       /*Sql cant generate uuid, call the uuid generator*/ 
/*uuid_generate_v4()*/

CREATE TYPE tenant_user_role AS ENUM ('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER');   /*role constraint*/

CREATE TABLE tenants (   /*represent org & comapnies*/
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name       VARCHAR(255) NOT NULL,
    slug       VARCHAR(255) NOT NULL UNIQUE,
    plan_id    UUID,                 /*later point to plans table as FK*/
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE users (   /*represent actual people*/
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email         VARCHAR(320) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,                 /*store hashed password*/
    full_name     VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ
);

CREATE TABLE tenant_users (                  /*Many:Many relation between tenant & user; user can belong to multiple tenant org*/
    id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id   UUID NOT NULL REFERENCES users(id),
    role      tenant_user_role NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenant_users_tenant_user UNIQUE (tenant_id, user_id)  /*unique constraint; avoid(tenant,user) repetition*/
);

CREATE INDEX idx_tenant_users_tenant_id ON tenant_users (tenant_id);   /*Make query fast*/
CREATE INDEX idx_tenant_users_user_id ON tenant_users (user_id);  /*Without index, scan entire row; with it jump directly to matching row*/

