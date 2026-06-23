CREATE TYPE task_status AS ENUM ('BACKLOG', 'IN_PROGRESS', 'DONE');   /*value constraint*/
CREATE TYPE task_priority AS ENUM ('LOW', 'MEDIUM', 'HIGH');

CREATE TABLE projects (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_by  UUID NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ
);

CREATE TABLE tasks (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    project_id  UUID NOT NULL REFERENCES projects(id),
    title       VARCHAR(255) NOT NULL,
    description TEXT,
    status      task_status NOT NULL,
    priority    task_priority NOT NULL,
    assignee_id UUID REFERENCES users(id),
    due_date    DATE,
    version     INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ
);

CREATE TABLE comments (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id  UUID NOT NULL REFERENCES tenants(id),
    task_id    UUID NOT NULL REFERENCES tasks(id),
    user_id    UUID NOT NULL REFERENCES users(id),
    body       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE labels (
    id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name      VARCHAR(255) NOT NULL,
    color     VARCHAR(50) NOT NULL
    /*No delete_at label to make simple ; completely delete label*/
);

CREATE TABLE task_labels (             /*Many-many rel; one task many label vice versa*/
    task_id  UUID NOT NULL REFERENCES tasks(id),
    label_id UUID NOT NULL REFERENCES labels(id),
    PRIMARY KEY (task_id, label_id)
);

CREATE INDEX idx_projects_tenant_id ON projects (tenant_id) WHERE deleted_at IS NULL;  /*Partial index to ignore deleted project*/
CREATE INDEX idx_projects_created_by ON projects (created_by) WHERE deleted_at IS NULL;
CREATE INDEX idx_tasks_tenant_project ON tasks (tenant_id, project_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_tasks_assignee_id ON tasks (assignee_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_comments_tenant_task ON comments (tenant_id, task_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_comments_user_id ON comments (user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_labels_tenant_id ON labels (tenant_id);
CREATE INDEX idx_task_labels_label_id ON task_labels (label_id);
