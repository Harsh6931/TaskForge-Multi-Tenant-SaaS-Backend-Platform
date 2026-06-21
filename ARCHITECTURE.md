# Architecture Decision

TaskForge uses a shared database and shared schema multi-tenant architecture.
So(Multi-tenancy Strategy: Shared Database + Shared Schema + Row-Level Security (RLS)) **[search to understand]

Every tenant-scoped table contains a tenant_id column. PostgreSQL Row-Level Security (RLS) is used to enforce isolation at the database layer.
This approach was chosen because it offers strong tenant isolation while keeping operational complexity low compared to schema-per-tenant or database-per-tenant models.
Even if an application query accidentally omits a tenant filter, PostgreSQL RLS prevents cross-tenant data leakage.
This architecture is commonly used by modern SaaS products because it balances scalability, cost, and security.