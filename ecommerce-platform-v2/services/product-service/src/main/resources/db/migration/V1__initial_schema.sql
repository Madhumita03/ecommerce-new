CREATE TABLE IF NOT EXISTS categories (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150) NOT NULL UNIQUE,
    description VARCHAR(500),
    parent_id   BIGINT REFERENCES categories(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS products (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(255) NOT NULL,
    description    TEXT,
    sku            VARCHAR(100) NOT NULL UNIQUE,
    price          NUMERIC(12,2) NOT NULL CHECK (price > 0),
    sale_price     NUMERIC(12,2),
    stock_quantity INTEGER NOT NULL DEFAULT 0 CHECK (stock_quantity >= 0),
    category_id    BIGINT NOT NULL REFERENCES categories(id),
    image_url      VARCHAR(500),
    status         VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    vendor_name    VARCHAR(200),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    version        BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_product_category ON products(category_id);
CREATE INDEX IF NOT EXISTS idx_product_sku      ON products(sku);
CREATE INDEX IF NOT EXISTS idx_product_status   ON products(status);
CREATE INDEX IF NOT EXISTS idx_product_name_gin ON products USING GIN(to_tsvector('english', name));

INSERT INTO categories (name, description) VALUES
    ('Electronics','Electronic devices'),('Clothing','Apparel'),
    ('Home & Garden','Home improvement'),('Books','Physical & digital books'),
    ('Sports','Sports equipment'),('Toys','Games and toys'),
    ('Health & Beauty','Wellness products'),('Automotive','Car accessories')
ON CONFLICT (name) DO NOTHING;
