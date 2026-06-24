"SET LOCAL app.current_tenant_id = ?"

is conceptually correct for learning, but PostgreSQL often doesn't allow parameter placeholders with SET LOCAL the way normal queries do.

In real projects you'll commonly see:

SELECT set_config(
    'app.current_tenant_id',
    ?,
    true
);

Your current implementation is fine for understanding the architecture, but if you later get SQL syntax/parameter errors, this is the first place to check.