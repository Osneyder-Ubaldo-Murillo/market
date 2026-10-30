-- ############################################################################
-- # HISTÓRICO - NO EJECUTAR. Copia de respaldo de db/migration/V1__schema.sql.
-- # El esquema vigente está en market/src/main/resources/db/schema (Flyway).
-- # Este archivo no se empaqueta en el jar ni en la imagen Docker.
-- ############################################################################

-- ============================================================================
-- NexusMarket - Migración V1: Esquema inicial (MySQL 8)
-- ============================================================================

-- -----------------------------------------------------
-- Tabla: users
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id CHAR(36) NOT NULL COMMENT 'UUID del usuario',
    full_name VARCHAR(200) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    document_id VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role_code VARCHAR(50) NOT NULL COMMENT 'Código del rol (BUYER, SELLER, ADMIN, ...)',
    status_code VARCHAR(50) NOT NULL COMMENT 'Estado del usuario (ACTIVE, INACTIVE, BLOCKED, ...)',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Tabla: buyers
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS buyers (
    id CHAR(36) NOT NULL COMMENT 'UUID del comprador',
    user_id CHAR(36) NOT NULL UNIQUE,
    commercial_status_code VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_buyers_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Tabla: sellers
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS sellers (
    id CHAR(36) NOT NULL COMMENT 'UUID del vendedor',
    user_id CHAR(36) NOT NULL UNIQUE,
    business_name VARCHAR(200) NOT NULL,
    tax_id VARCHAR(50) NOT NULL UNIQUE,
    status_code VARCHAR(50) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_sellers_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Tabla: buyer_addresses
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS buyer_addresses (
    id CHAR(36) NOT NULL,
    buyer_id CHAR(36) NOT NULL,
    street VARCHAR(200) NOT NULL,
    number VARCHAR(20) NOT NULL,
    complement VARCHAR(200),
    neighborhood VARCHAR(100) NOT NULL,
    city VARCHAR(100) NOT NULL,
    state VARCHAR(100) NOT NULL,
    postal_code VARCHAR(20) NOT NULL,
    country CHAR(2) NOT NULL,
    is_main BOOLEAN NOT NULL DEFAULT FALSE,

-- -----------------------------------------------------
-- Tabla: warehouses
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS warehouses (
    id CHAR(36) NOT NULL,
    name VARCHAR(200) NOT NULL,
    type_code VARCHAR(50) NOT NULL COMMENT 'MARKETPLACE o SELLER',
    seller_id CHAR(36) NULL,
    status_code VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_warehouses_seller FOREIGN KEY (seller_id) REFERENCES sellers(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Tabla: warehouse_addresses
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS warehouse_addresses (
    warehouse_id CHAR(36) NOT NULL,
    street VARCHAR(200) NOT NULL,
    number VARCHAR(20) NOT NULL,
    complement VARCHAR(200),
    neighborhood VARCHAR(100) NOT NULL,
    city VARCHAR(100) NOT NULL,
    state VARCHAR(100) NOT NULL,
    postal_code VARCHAR(20) NOT NULL,
    country CHAR(2) NOT NULL,
    PRIMARY KEY (warehouse_id),
    CONSTRAINT fk_warehouse_addresses_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Tabla: products
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS products (
    id CHAR(36) NOT NULL,
    seller_id CHAR(36) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    type_code VARCHAR(20) NOT NULL,
    status_code VARCHAR(50) NOT NULL DEFAULT 'INACTIVE',
    amount DECIMAL(19,4) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'COP',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_products_seller FOREIGN KEY (seller_id) REFERENCES sellers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Tabla: product_specifications
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS product_specifications (
    product_id CHAR(36) NOT NULL,
    spec_key VARCHAR(100) NOT NULL,

-- -----------------------------------------------------
-- Tabla: inventory
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS inventory (
    id CHAR(36) NOT NULL,
    product_id CHAR(36) NOT NULL,
    warehouse_id CHAR(36) NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    reserved_quantity INT NOT NULL DEFAULT 0,
    status_code VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_product_warehouse (product_id, warehouse_id),
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT fk_inventory_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Tabla: carts
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS carts (
    id CHAR(36) NOT NULL,
    buyer_id CHAR(36) NOT NULL UNIQUE,
    currency CHAR(3) NOT NULL DEFAULT 'COP',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_carts_buyer FOREIGN KEY (buyer_id) REFERENCES buyers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Tabla: cart_items
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS cart_items (
    id CHAR(36) NOT NULL,
    cart_id CHAR(36) NOT NULL,
    product_id CHAR(36) NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cart_items_cart_product (cart_id, product_id),

-- -----------------------------------------------------
-- Tabla: orders
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS orders (
    id CHAR(36) NOT NULL,
    buyer_id CHAR(36) NOT NULL,
    cart_id CHAR(36) NOT NULL,
    status_code VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    total_amount DECIMAL(19,4) NOT NULL,
    total_currency CHAR(3) NOT NULL DEFAULT 'COP',
    shipping_address_street VARCHAR(200),
    shipping_address_number VARCHAR(20),
    shipping_address_complement VARCHAR(200),
    shipping_address_neighborhood VARCHAR(100),
    shipping_address_city VARCHAR(100),
    shipping_address_state VARCHAR(100),
    shipping_address_postal_code VARCHAR(20),
    shipping_address_country CHAR(2),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_orders_buyer FOREIGN KEY (buyer_id) REFERENCES buyers(id),
    CONSTRAINT fk_orders_cart FOREIGN KEY (cart_id) REFERENCES carts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Tabla: order_items
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS order_items (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    product_id CHAR(36) NOT NULL,
    product_name_snapshot VARCHAR(200) NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    unit_price_amount DECIMAL(19,4) NOT NULL,
    unit_price_currency CHAR(3) NOT NULL DEFAULT 'COP',
    PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Tabla: payments
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS payments (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL UNIQUE,
    transaction_id VARCHAR(255) NOT NULL UNIQUE,
    method VARCHAR(50) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'COP',
    status_code VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Tabla: shipping
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS shipping (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL UNIQUE,
    warehouse_id CHAR(36) NOT NULL,
    tracking_number VARCHAR(100),
    carrier VARCHAR(100),
    status_code VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

-- -----------------------------------------------------
-- Tabla: invoices
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS invoices (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL UNIQUE,
    invoice_number VARCHAR(100) NOT NULL UNIQUE,
    status_code VARCHAR(50) NOT NULL DEFAULT 'ISSUED',
    total_amount DECIMAL(19,4) NOT NULL,
    total_currency CHAR(3) NOT NULL DEFAULT 'COP',
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_invoices_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Tabla: returns
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS returns (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    status_code VARCHAR(50) NOT NULL DEFAULT 'REQUESTED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_returns_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Tabla: return_lines
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS return_lines (
    id CHAR(36) NOT NULL,
    return_id CHAR(36) NOT NULL,
    product_id CHAR(36) NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    reason_code VARCHAR(50) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_return_lines_return FOREIGN KEY (return_id) REFERENCES returns(id) ON DELETE CASCADE,
    CONSTRAINT fk_return_lines_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------
-- Tabla: refunds
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS refunds (
    id CHAR(36) NOT NULL,
    return_id CHAR(36) NOT NULL UNIQUE,
    transaction_id VARCHAR(255) NOT NULL UNIQUE,
    amount DECIMAL(19,4) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'COP',
    status_code VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_refunds_return FOREIGN KEY (return_id) REFERENCES returns(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- Índices de uso frecuente
-- ============================================================================
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role ON users(role_code);
CREATE INDEX idx_products_seller ON products(seller_id);
CREATE INDEX idx_products_status ON products(status_code);
CREATE INDEX idx_inventory_product ON inventory(product_id);
CREATE INDEX idx_orders_buyer ON orders(buyer_id);
CREATE INDEX idx_orders_status ON orders(status_code);
CREATE INDEX idx_shipping_order ON shipping(order_id);
CREATE INDEX idx_invoices_order ON invoices(order_id);
CREATE INDEX idx_returns_order ON returns(order_id);

-- ============================================================================
-- Fin de V1__schema.sql
-- ============================================================================
    PRIMARY KEY (id),
    CONSTRAINT fk_shipping_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_shipping_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES carts(id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_items_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    spec_value JSON NOT NULL,
    PRIMARY KEY (product_id, spec_key),
    CONSTRAINT fk_product_specs_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_buyer_addresses_buyer FOREIGN KEY (buyer_id) REFERENCES buyers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;