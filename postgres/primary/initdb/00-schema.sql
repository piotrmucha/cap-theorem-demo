-- Runs once, on first startup of the primary, after the "shop" database
-- and its owner role already exist (created by the postgres image itself
-- from POSTGRES_DB / POSTGRES_USER). This table is what gets streamed to the
-- standby by WAL replication -- there is no separate schema step on the
-- standby side, it inherits everything from the base backup.

CREATE TABLE IF NOT EXISTS stock (
    product_id INT PRIMARY KEY,
    qty        INT NOT NULL CHECK (qty >= 0)
);

INSERT INTO stock (product_id, qty)
VALUES (1, 50)
ON CONFLICT (product_id) DO NOTHING;
