# Phase 1 - Entity Layer Interview Notes

## BaseEntity

### Purpose

Common parent class for all entities.

Provides:

* UUID id
* createdAt
* updatedAt
* deletedAt

### Why @MappedSuperclass?

* No table created.
* Child entities inherit columns.
* Avoids code duplication.

### Why UUID?

* Hard to guess.
* Safe for public APIs.
* Prevents ID enumeration attacks.

### Why Soft Delete?

* Preserve history.
* Recover deleted data.
* Maintain audit logs.

---

## JpaConfig

### Purpose

Enables Spring Data JPA features.

### @EnableJpaAuditing

Activates:

* @CreatedDate
* @LastModifiedDate

Without it:

* createdAt = null
* updatedAt = null

### @EnableJpaRepositories

Scans repository interfaces and registers them as Spring beans.

---

## Project Entity

### Purpose

Represents a project inside a tenant workspace.

### Why extend BaseEntity?

Gets:

* id
* timestamps
* soft delete

### Why @Where(deleted_at IS NULL)?

Automatically hides soft-deleted projects.

### Why FetchType.LAZY?

Avoid loading Tenant/User unless actually needed.

### Why both tenant and tenantId?

```java
private Tenant tenant;
private UUID tenantId;
```

tenant:

* manages relationship

tenantId:

* access FK without triggering lazy load

Benefit:

* fewer SQL queries

---

## Tenant Entity

### Purpose

Represents a company/workspace.

Examples:

* Acme Corp
* Google
* Microsoft

### Why no tenantId?

Tenant is the root entity.
It does not belong to another tenant.

### Slug

Unique URL-safe identifier.

Example:

```text
Acme Corp
↓
acme-corp
```

Used in URLs and workspace routing.

---

## User Entity

### Purpose

Represents a global user identity.

### Why no tenantId?

A user can belong to multiple tenants.

Relationship:

```text
User
 ↕
TenantUser
 ↕
Tenant
```

### Why passwordHash instead of password?

Never store plaintext passwords.

Store BCrypt hash only.

---

## TenantUser Entity

### Purpose

Membership table between User and Tenant.

Stores:

* role
* joinedAt

### Why not @ManyToMany?

Need extra columns:

```text
role
joined_at
```

So a dedicated join entity is required.

### Unique Constraint

```sql
(tenant_id, user_id)
```

Prevents duplicate memberships.

---

## TenantUserRole Enum

### Purpose

Defines valid workspace roles.

```java
ADMIN
MANAGER
MEMBER
VIEWER
```

### Why Enum?

Prevents invalid role values.

### Why EnumType.STRING?

Stores:

```text
ADMIN
MANAGER
```

instead of:

```text
0
1
```

Safe if enum order changes.

---

# Quick Interview Questions

### Why BaseEntity?

Centralized common fields and auditing.

### Why UUID?

Security and API safety.

### Why User has no tenantId?

User can belong to multiple tenants.

### Why TenantUser exists?

Many-to-many relationship with extra fields.

### Why tenant + tenantId in Project?

Avoid unnecessary lazy-load queries.

### Why EnumType.STRING?

Prevents enum-order corruption.

### Why LAZY loading?

Reduces unnecessary database queries.

### Why soft delete?

History, recovery, auditability.
