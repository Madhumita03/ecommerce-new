CREATE TABLE IF NOT EXISTS payment_records (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    saga_id        UUID NOT NULL UNIQUE,
    order_id       UUID NOT NULL,
    user_id        UUID NOT NULL,
    amount         NUMERIC(12,2) NOT NULL,
    success        BOOLEAN NOT NULL,
    transaction_id VARCHAR(200),
    failure_reason VARCHAR(500),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_payment_order ON payment_records(order_id);
CREATE INDEX IF NOT EXISTS idx_payment_saga  ON payment_records(saga_id);
