CREATE TABLE IF NOT EXISTS parents (
    parent_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT NOT NULL UNIQUE,
    mobile TEXT,
    password_hash TEXT NOT NULL,
    password_salt TEXT NOT NULL,
    token TEXT UNIQUE,
    activated_at INTEGER,
    trial_ends_at INTEGER,
    subscription_status TEXT NOT NULL DEFAULT 'PENDING_ACTIVATION',
    subscription_ends_at INTEGER,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS children (
    child_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    code TEXT NOT NULL UNIQUE,
    token TEXT NOT NULL UNIQUE,
    parent_id TEXT,
    last_seen_at INTEGER,
    locked INTEGER NOT NULL DEFAULT 0,
    command TEXT NOT NULL DEFAULT 'UNLOCK',
    created_at INTEGER NOT NULL,
    FOREIGN KEY (parent_id) REFERENCES parents(parent_id)
);

CREATE TABLE IF NOT EXISTS payments (
    payment_id TEXT PRIMARY KEY,
    parent_id TEXT NOT NULL,
    payment_type TEXT NOT NULL,
    amount INTEGER NOT NULL,
    registration_fee INTEGER NOT NULL DEFAULT 0,
    annual_fee INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'PENDING',
    created_at INTEGER NOT NULL,
    approved_at INTEGER,
    FOREIGN KEY (parent_id) REFERENCES parents(parent_id)
);

CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

INSERT OR IGNORE INTO settings (key, value) VALUES
    ('trial_days', '2'),
    ('initial_payment', '400'),
    ('registration_fee', '100'),
    ('annual_fee', '300'),
    ('renewal_fee', '300'),
    ('payment_qr', '/payment-qr.png');

CREATE INDEX IF NOT EXISTS idx_parents_email
    ON parents(email);

CREATE INDEX IF NOT EXISTS idx_children_code
    ON children(code);

CREATE INDEX IF NOT EXISTS idx_children_parent
    ON children(parent_id);

CREATE INDEX IF NOT EXISTS idx_payments_parent
    ON payments(parent_id);
