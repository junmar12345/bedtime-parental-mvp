-- Per-Child billing ledger migration for the approved BEDTIME release.
-- Release billing rules: first-Child trial is 24 hours; initial payment is 300.
ALTER TABLE parents ADD COLUMN recovery_code_hash TEXT;

ALTER TABLE payments ADD COLUMN billing_child_id TEXT;
ALTER TABLE payments ADD COLUMN billing_reason TEXT;
ALTER TABLE payments ADD COLUMN reference_number TEXT;
ALTER TABLE payments ADD COLUMN proof_name TEXT;

CREATE TABLE IF NOT EXISTS billing_children (
    billing_child_id TEXT PRIMARY KEY,
    parent_id TEXT NOT NULL,
    child_key TEXT NOT NULL,
    child_code TEXT NOT NULL,
    name TEXT NOT NULL,
    child_order INTEGER NOT NULL,
    billing_status TEXT NOT NULL DEFAULT 'PENDING_PAYMENT',
    paired_at INTEGER NOT NULL,
    trial_started_at INTEGER,
    trial_ends_at INTEGER,
    paid_until INTEGER,
    paid_at INTEGER,
    paid_amount INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (parent_id) REFERENCES parents(parent_id),
    UNIQUE (parent_id, child_code)
);

CREATE INDEX IF NOT EXISTS idx_billing_children_parent
    ON billing_children(parent_id, child_order);
CREATE INDEX IF NOT EXISTS idx_payments_billing_child
    ON payments(billing_child_id, status);

-- These values implement the approved billing rules for this release.
-- Apply only after the production release checks and backup review.
INSERT INTO settings (key, value) VALUES
    ('trial_days', '1'),
    ('initial_payment', '300'),
    ('registration_fee', '100'),
    ('annual_fee', '300'),
    ('renewal_fee', '300'),
    ('second_child_fee', '300'),
    ('additional_child_fee', '50')
ON CONFLICT(key) DO UPDATE SET value = excluded.value;
