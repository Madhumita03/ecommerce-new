CREATE TABLE IF NOT EXISTS users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_id VARCHAR(100) NOT NULL UNIQUE,
    email       VARCHAR(255) NOT NULL UNIQUE,
    first_name  VARCHAR(100),
    last_name   VARCHAR(100),
    status      VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_user_email      ON users(email);
CREATE INDEX IF NOT EXISTS idx_user_keycloak   ON users(keycloak_id);
