CREATE TABLE IF NOT EXISTS idempotency_keys (
    principal       TEXT    NOT NULL,
    idem_key        TEXT    NOT NULL,
    fingerprint     TEXT    NOT NULL,
    status          TEXT    NOT NULL CHECK (status IN ('in_progress', 'completed')),
    response_status INTEGER,
    response_body   TEXT,
    response_etag   TEXT,
    created_at      INTEGER NOT NULL,
    PRIMARY KEY (principal, idem_key)
);
CREATE INDEX IF NOT EXISTS idx_idem_created ON idempotency_keys (created_at);

CREATE TABLE IF NOT EXISTS audit_log (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    ts             INTEGER NOT NULL,
    correlation_id TEXT,
    actor          TEXT    NOT NULL,
    agent          TEXT,
    action         TEXT    NOT NULL,
    resource_type  TEXT    NOT NULL,
    resource_id    TEXT,
    outcome        TEXT    NOT NULL,
    detail         TEXT
);
CREATE INDEX IF NOT EXISTS idx_audit_resource ON audit_log (resource_type, resource_id, ts);
CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_log (actor, ts);

CREATE TABLE IF NOT EXISTS webhook_subscriptions (
    id         TEXT    PRIMARY KEY,
    url        TEXT    NOT NULL,
    secret     TEXT    NOT NULL,
    events     TEXT    NOT NULL,
    active     INTEGER NOT NULL DEFAULT 1,
    created_by TEXT    NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS webhook_outbox (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id        TEXT    NOT NULL,
    subscription_id TEXT    NOT NULL REFERENCES webhook_subscriptions (id) ON DELETE CASCADE,
    event_type      TEXT    NOT NULL,
    payload         TEXT    NOT NULL,
    status          TEXT    NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'delivered', 'dead')),
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at INTEGER NOT NULL,
    last_error      TEXT,
    created_at      INTEGER NOT NULL,
    delivered_at    INTEGER,
    UNIQUE (event_id, subscription_id)
);
CREATE INDEX IF NOT EXISTS idx_outbox_due ON webhook_outbox (status, next_attempt_at);
