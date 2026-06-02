CREATE TABLE IF NOT EXISTS orders (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL,
    status           VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    total_amount     NUMERIC(12,2) NOT NULL,
    shipping_address VARCHAR(500),
    saga_id          UUID UNIQUE,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    version          BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS order_items (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id   UUID NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    sku          VARCHAR(100) NOT NULL,
    quantity     INTEGER NOT NULL CHECK (quantity > 0),
    unit_price   NUMERIC(12,2) NOT NULL,
    line_total   NUMERIC(12,2) NOT NULL
);
CREATE TABLE IF NOT EXISTS outbox_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic         VARCHAR(200) NOT NULL,
    partition_key VARCHAR(200),
    event_type    VARCHAR(100) NOT NULL,
    payload       TEXT NOT NULL,
    published     BOOLEAN NOT NULL DEFAULT FALSE,
    retry_count   INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_order_user   ON orders(user_id);
CREATE INDEX IF NOT EXISTS idx_order_saga   ON orders(saga_id);
CREATE INDEX IF NOT EXISTS idx_outbox_unpub ON outbox_events(published, created_at) WHERE published=false;
