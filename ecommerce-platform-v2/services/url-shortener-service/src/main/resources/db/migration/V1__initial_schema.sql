CREATE TABLE IF NOT EXISTS short_urls (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(12) NOT NULL UNIQUE,
    original_url VARCHAR(2048) NOT NULL,
    click_count  BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMP,
    created_by   VARCHAR(100)
);
CREATE INDEX IF NOT EXISTS idx_short_url_code ON short_urls(code);
