-- STAGING ONLY: PayMongo Checkout V2 + private payment-proof storage metadata.
-- Do not apply to production until a separate staging D1 has passed integration tests.
ALTER TABLE payments ADD COLUMN payment_method TEXT NOT NULL DEFAULT 'MANUAL';
ALTER TABLE payments ADD COLUMN proof_object_key TEXT;
ALTER TABLE payments ADD COLUMN proof_content_type TEXT;
ALTER TABLE payments ADD COLUMN proof_size INTEGER;
ALTER TABLE payments ADD COLUMN proof_submitted_at INTEGER;
ALTER TABLE payments ADD COLUMN review_note TEXT;
ALTER TABLE payments ADD COLUMN rejected_at INTEGER;
ALTER TABLE payments ADD COLUMN paid_at INTEGER;
ALTER TABLE payments ADD COLUMN paymongo_checkout_session_id TEXT;
ALTER TABLE payments ADD COLUMN checkout_url TEXT;
ALTER TABLE payments ADD COLUMN paymongo_payment_id TEXT;
ALTER TABLE payments ADD COLUMN paymongo_payment_intent_id TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_payments_paymongo_session
  ON payments(paymongo_checkout_session_id)
  WHERE paymongo_checkout_session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_payments_reference_number
  ON payments(reference_number);
CREATE INDEX IF NOT EXISTS idx_payments_method_status_created
  ON payments(payment_method, status, created_at);

CREATE TABLE IF NOT EXISTS paymongo_webhook_events (
  event_key TEXT PRIMARY KEY,
  provider_event_id TEXT,
  event_type TEXT NOT NULL,
  checkout_session_id TEXT,
  payment_id TEXT,
  status TEXT NOT NULL DEFAULT 'RECEIVED',
  received_at INTEGER NOT NULL,
  processed_at INTEGER,
  error_code TEXT
);
CREATE INDEX IF NOT EXISTS idx_paymongo_events_status_received
  ON paymongo_webhook_events(status, received_at);
