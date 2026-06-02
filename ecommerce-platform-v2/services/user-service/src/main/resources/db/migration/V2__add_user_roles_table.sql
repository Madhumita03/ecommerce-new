-- Adds the user_roles join table that backs User.roles (@ElementCollection).
-- Required by Hibernate schema validation (ddl-auto: validate).

CREATE TABLE IF NOT EXISTS user_roles (
    user_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role    VARCHAR(100) NOT NULL,
    PRIMARY KEY (user_id, role)
);

CREATE INDEX IF NOT EXISTS idx_user_roles_user_id ON user_roles(user_id);
