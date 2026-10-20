-- Backfill existing linked Children into the per-Child billing ledger.
-- Migration policy: each Parent's oldest existing Child receives a fresh
-- 24-hour trial from migration time; additional Children require payment.
-- Existing payment records are not modified.

WITH migration_clock AS (
  SELECT CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) AS migration_at
),
ranked_children AS (
  SELECT
    c.child_id,
    c.name,
    c.code,
    c.parent_id,
    c.created_at,
    ROW_NUMBER() OVER (
      PARTITION BY c.parent_id
      ORDER BY c.created_at ASC, c.child_id ASC
    ) AS child_order
  FROM children c
  WHERE c.parent_id IS NOT NULL
)
INSERT INTO billing_children (
  billing_child_id,
  parent_id,
  child_key,
  child_code,
  name,
  child_order,
  billing_status,
  paired_at,
  trial_started_at,
  trial_ends_at,
  paid_until,
  paid_at,
  paid_amount,
  created_at,
  updated_at
)
SELECT
  'BC-MIG-' || r.child_id,
  r.parent_id,
  r.child_id,
  r.code,
  r.name,
  r.child_order,
  CASE
    WHEN r.child_order = 1 THEN 'TRIAL'
    ELSE 'PENDING_PAYMENT'
  END,
  r.created_at,
  CASE WHEN r.child_order = 1 THEN m.migration_at ELSE NULL END,
  CASE WHEN r.child_order = 1 THEN m.migration_at + 86400000 ELSE NULL END,
  NULL,
  NULL,
  0,
  m.migration_at,
  m.migration_at
FROM ranked_children r
CROSS JOIN migration_clock m
WHERE NOT EXISTS (
  SELECT 1
  FROM billing_children b
  WHERE b.parent_id = r.parent_id
    AND b.child_code = r.code
);