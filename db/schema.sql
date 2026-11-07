-- =====================================================================
-- Product Management System + Point of Sale - Database Schema
-- Database : ProductMSEasirArafat   (MySQL 5.7+/8.x or MariaDB 10.4+)
-- Run this first, then seed.sql
-- =====================================================================

DROP DATABASE IF EXISTS ProductMSEasirArafat;
CREATE DATABASE ProductMSEasirArafat CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ProductMSEasirArafat;

-- ---------------------------------------------------------------------
-- Users & settings
-- ---------------------------------------------------------------------
CREATE TABLE users (
    user_id       INT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash CHAR(64)     NOT NULL,              -- SHA-256 hex
    full_name     VARCHAR(100) NOT NULL,
    role          ENUM('ADMIN','MANAGER','CASHIER') NOT NULL DEFAULT 'CASHIER',
    active        TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE settings (
    setting_key   VARCHAR(60)  PRIMARY KEY,
    setting_value VARCHAR(500) NOT NULL
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Master data
-- ---------------------------------------------------------------------
CREATE TABLE categories (
    category_id INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(80)  NOT NULL,
    parent_id   INT          NULL,                    -- NULL = top level, otherwise subcategory
    description VARCHAR(255) NULL,
    active      TINYINT(1)   NOT NULL DEFAULT 1,
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES categories(category_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE brands (
    brand_id INT AUTO_INCREMENT PRIMARY KEY,
    name     VARCHAR(80) NOT NULL UNIQUE,
    active   TINYINT(1)  NOT NULL DEFAULT 1
) ENGINE=InnoDB;

CREATE TABLE units (
    unit_id       INT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(40) NOT NULL UNIQUE,
    short_name    VARCHAR(10) NOT NULL UNIQUE,
    allow_decimal TINYINT(1)  NOT NULL DEFAULT 0      -- KG / Litre can be sold in fractions
) ENGINE=InnoDB;

CREATE TABLE suppliers (
    supplier_id INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    phone       VARCHAR(30)  NULL,
    address     VARCHAR(255) NULL,
    active      TINYINT(1)   NOT NULL DEFAULT 1
) ENGINE=InnoDB;

CREATE TABLE customers (
    customer_id   INT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    phone         VARCHAR(30)  NULL UNIQUE,
    email         VARCHAR(100) NULL,
    address       VARCHAR(255) NULL,
    customer_type ENUM('RETAIL','WHOLESALE') NOT NULL DEFAULT 'RETAIL',
    is_walk_in    TINYINT(1)   NOT NULL DEFAULT 0,
    active        TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Products & variants
-- A product always has at least one variant. Stock, SKU, barcode and
-- prices live on the variant (size / colour / weight combination).
-- ---------------------------------------------------------------------
CREATE TABLE products (
    product_id     INT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(150) NOT NULL,
    description    TEXT         NULL,
    category_id    INT          NULL,
    brand_id       INT          NULL,
    unit_id        INT          NOT NULL,
    product_type   ENUM('STANDARD','BUNDLE') NOT NULL DEFAULT 'STANDARD',
    track_batch    TINYINT(1)   NOT NULL DEFAULT 0,   -- batch numbers + expiry dates
    track_serial   TINYINT(1)   NOT NULL DEFAULT 0,   -- one serial number per unit
    vat_percent    DECIMAL(5,2) NOT NULL DEFAULT 0,
    discount_type  ENUM('NONE','PERCENT','FIXED') NOT NULL DEFAULT 'NONE',
    discount_value DECIMAL(14,2) NOT NULL DEFAULT 0,
    image_path     VARCHAR(255) NULL,
    active         TINYINT(1)   NOT NULL DEFAULT 1,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES categories(category_id) ON DELETE SET NULL,
    CONSTRAINT fk_product_brand    FOREIGN KEY (brand_id)    REFERENCES brands(brand_id)       ON DELETE SET NULL,
    CONSTRAINT fk_product_unit     FOREIGN KEY (unit_id)     REFERENCES units(unit_id)
) ENGINE=InnoDB;

CREATE TABLE product_variants (
    variant_id        INT AUTO_INCREMENT PRIMARY KEY,
    product_id        INT           NOT NULL,
    size              VARCHAR(30)   NULL,
    colour            VARCHAR(30)   NULL,
    weight            VARCHAR(30)   NULL,
    sku               VARCHAR(50)   NOT NULL UNIQUE,
    barcode           VARCHAR(50)   NULL UNIQUE,
    purchase_price    DECIMAL(14,2) NOT NULL DEFAULT 0,   -- weighted average cost
    retail_price      DECIMAL(14,2) NOT NULL DEFAULT 0,
    wholesale_price   DECIMAL(14,2) NOT NULL DEFAULT 0,
    wholesale_min_qty DECIMAL(14,3) NOT NULL DEFAULT 0,   -- retail sale switches to wholesale price at this qty (0 = never)
    stock_qty         DECIMAL(14,3) NOT NULL DEFAULT 0,
    reorder_level     DECIMAL(14,3) NOT NULL DEFAULT 0,   -- low stock alert threshold
    active            TINYINT(1)    NOT NULL DEFAULT 1,
    CONSTRAINT fk_variant_product FOREIGN KEY (product_id) REFERENCES products(product_id) ON DELETE CASCADE,
    CONSTRAINT chk_variant_stock CHECK (stock_qty >= 0)
) ENGINE=InnoDB;

-- Components of a bundle / combo product (bundle has no stock of its own)
CREATE TABLE bundle_items (
    bundle_item_id       INT AUTO_INCREMENT PRIMARY KEY,
    bundle_variant_id    INT           NOT NULL,
    component_variant_id INT           NOT NULL,
    quantity             DECIMAL(14,3) NOT NULL,
    CONSTRAINT fk_bundle_parent    FOREIGN KEY (bundle_variant_id)    REFERENCES product_variants(variant_id) ON DELETE CASCADE,
    CONSTRAINT fk_bundle_component FOREIGN KEY (component_variant_id) REFERENCES product_variants(variant_id) ON DELETE RESTRICT,
    UNIQUE KEY uq_bundle_component (bundle_variant_id, component_variant_id)
) ENGINE=InnoDB;

CREATE TABLE product_batches (
    batch_id         INT AUTO_INCREMENT PRIMARY KEY,
    variant_id       INT           NOT NULL,
    batch_no         VARCHAR(50)   NOT NULL,
    manufacture_date DATE          NULL,
    expiry_date      DATE          NULL,
    qty_received     DECIMAL(14,3) NOT NULL DEFAULT 0,
    qty_remaining    DECIMAL(14,3) NOT NULL DEFAULT 0,
    unit_cost        DECIMAL(14,2) NOT NULL DEFAULT 0,
    received_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_batch_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id) ON DELETE CASCADE,
    CONSTRAINT chk_batch_remaining CHECK (qty_remaining >= 0),
    UNIQUE KEY uq_batch (variant_id, batch_no)
) ENGINE=InnoDB;

CREATE TABLE product_serials (
    serial_id    INT AUTO_INCREMENT PRIMARY KEY,
    variant_id   INT         NOT NULL,
    serial_no    VARCHAR(80) NOT NULL,
    status       ENUM('IN_STOCK','SOLD','DAMAGED') NOT NULL DEFAULT 'IN_STOCK',
    sale_item_id INT         NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_serial_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id) ON DELETE CASCADE,
    UNIQUE KEY uq_serial (variant_id, serial_no)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Stock movement ledger (every stock change is recorded here)
-- ---------------------------------------------------------------------
CREATE TABLE stock_movements (
    movement_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    variant_id    INT           NOT NULL,
    batch_id      INT           NULL,
    movement_type ENUM('OPENING','STOCK_IN','STOCK_OUT','SALE','SALE_CANCEL','RETURN',
                       'ADJUSTMENT_IN','ADJUSTMENT_OUT','DAMAGED','EXPIRED') NOT NULL,
    quantity      DECIMAL(14,3) NOT NULL,                 -- signed: + in, - out
    balance_after DECIMAL(14,3) NOT NULL,
    unit_cost     DECIMAL(14,2) NOT NULL DEFAULT 0,
    reference_no  VARCHAR(50)   NULL,                     -- invoice / return / GRN number
    supplier_id   INT           NULL,
    note          VARCHAR(255)  NULL,
    user_id       INT           NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_move_variant  FOREIGN KEY (variant_id)  REFERENCES product_variants(variant_id) ON DELETE CASCADE,
    CONSTRAINT fk_move_batch    FOREIGN KEY (batch_id)    REFERENCES product_batches(batch_id)    ON DELETE SET NULL,
    CONSTRAINT fk_move_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(supplier_id)       ON DELETE SET NULL,
    CONSTRAINT fk_move_user     FOREIGN KEY (user_id)     REFERENCES users(user_id)               ON DELETE SET NULL,
    INDEX idx_move_date (created_at),
    INDEX idx_move_type (movement_type)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- POS: shifts, sales, payments, returns, held carts
-- ---------------------------------------------------------------------
CREATE TABLE shifts (
    shift_id      INT AUTO_INCREMENT PRIMARY KEY,
    user_id       INT           NOT NULL,
    opened_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at     TIMESTAMP     NULL,
    opening_cash  DECIMAL(14,2) NOT NULL DEFAULT 0,
    expected_cash DECIMAL(14,2) NULL,
    counted_cash  DECIMAL(14,2) NULL,
    difference    DECIMAL(14,2) NULL,
    status        ENUM('OPEN','CLOSED') NOT NULL DEFAULT 'OPEN',
    note          VARCHAR(255)  NULL,
    CONSTRAINT fk_shift_user FOREIGN KEY (user_id) REFERENCES users(user_id)
) ENGINE=InnoDB;

CREATE TABLE sales (
    sale_id        INT AUTO_INCREMENT PRIMARY KEY,
    invoice_no     VARCHAR(30)   NOT NULL UNIQUE,
    customer_id    INT           NOT NULL,
    user_id        INT           NOT NULL,
    shift_id       INT           NULL,
    price_mode     ENUM('RETAIL','WHOLESALE') NOT NULL DEFAULT 'RETAIL',
    subtotal       DECIMAL(14,2) NOT NULL DEFAULT 0,      -- qty x unit price
    item_discount  DECIMAL(14,2) NOT NULL DEFAULT 0,
    cart_discount  DECIMAL(14,2) NOT NULL DEFAULT 0,
    vat_total      DECIMAL(14,2) NOT NULL DEFAULT 0,
    grand_total    DECIMAL(14,2) NOT NULL DEFAULT 0,
    paid_total     DECIMAL(14,2) NOT NULL DEFAULT 0,
    due_total      DECIMAL(14,2) NOT NULL DEFAULT 0,
    tendered_total DECIMAL(14,2) NOT NULL DEFAULT 0,      -- money handed over by customer
    change_amount  DECIMAL(14,2) NOT NULL DEFAULT 0,
    cost_total     DECIMAL(14,2) NOT NULL DEFAULT 0,
    payment_status ENUM('PAID','PARTIAL','DUE') NOT NULL DEFAULT 'PAID',
    status         ENUM('COMPLETED','CANCELLED') NOT NULL DEFAULT 'COMPLETED',
    note           VARCHAR(255)  NULL,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at   TIMESTAMP     NULL,
    cancelled_by   INT           NULL,
    cancel_reason  VARCHAR(255)  NULL,
    CONSTRAINT fk_sale_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id),
    CONSTRAINT fk_sale_user     FOREIGN KEY (user_id)     REFERENCES users(user_id),
    CONSTRAINT fk_sale_shift    FOREIGN KEY (shift_id)    REFERENCES shifts(shift_id),
    INDEX idx_sale_date (created_at)
) ENGINE=InnoDB;

CREATE TABLE sale_items (
    sale_item_id    INT AUTO_INCREMENT PRIMARY KEY,
    sale_id         INT           NOT NULL,
    variant_id      INT           NOT NULL,
    product_name    VARCHAR(150)  NOT NULL,               -- snapshot at time of sale
    variant_label   VARCHAR(100)  NULL,
    sku             VARCHAR(50)   NOT NULL,
    unit_name       VARCHAR(10)   NOT NULL,
    quantity        DECIMAL(14,3) NOT NULL,
    unit_price      DECIMAL(14,2) NOT NULL,
    discount_amount DECIMAL(14,2) NOT NULL DEFAULT 0,     -- product + item + share of cart discount
    vat_percent     DECIMAL(5,2)  NOT NULL DEFAULT 0,
    vat_amount      DECIMAL(14,2) NOT NULL DEFAULT 0,
    line_total      DECIMAL(14,2) NOT NULL,
    unit_cost       DECIMAL(14,2) NOT NULL DEFAULT 0,
    returned_qty    DECIMAL(14,3) NOT NULL DEFAULT 0,
    serial_numbers  VARCHAR(500)  NULL,
    CONSTRAINT fk_item_sale    FOREIGN KEY (sale_id)    REFERENCES sales(sale_id) ON DELETE CASCADE,
    CONSTRAINT fk_item_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id)
) ENGINE=InnoDB;

-- What was physically taken out of stock for each sale line
-- (bundle -> components, batch-tracked -> batches)
CREATE TABLE sale_item_allocations (
    allocation_id INT AUTO_INCREMENT PRIMARY KEY,
    sale_item_id  INT           NOT NULL,
    variant_id    INT           NOT NULL,
    batch_id      INT           NULL,
    quantity      DECIMAL(14,3) NOT NULL,
    returned_qty  DECIMAL(14,3) NOT NULL DEFAULT 0,
    unit_cost     DECIMAL(14,2) NOT NULL DEFAULT 0,
    CONSTRAINT fk_alloc_item    FOREIGN KEY (sale_item_id) REFERENCES sale_items(sale_item_id) ON DELETE CASCADE,
    CONSTRAINT fk_alloc_variant FOREIGN KEY (variant_id)   REFERENCES product_variants(variant_id),
    CONSTRAINT fk_alloc_batch   FOREIGN KEY (batch_id)     REFERENCES product_batches(batch_id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE payments (
    payment_id   INT AUTO_INCREMENT PRIMARY KEY,
    sale_id      INT           NOT NULL,
    shift_id     INT           NULL,
    user_id      INT           NOT NULL,
    method       ENUM('CASH','CARD','BKASH','NAGAD','ROCKET','STORE_CREDIT') NOT NULL,
    amount       DECIMAL(14,2) NOT NULL,                  -- amount applied to the invoice
    reference    VARCHAR(80)   NULL,                      -- trx id / card last 4 / return no
    payment_type ENUM('SALE','DUE_COLLECTION') NOT NULL DEFAULT 'SALE',
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pay_sale  FOREIGN KEY (sale_id)  REFERENCES sales(sale_id) ON DELETE CASCADE,
    CONSTRAINT fk_pay_shift FOREIGN KEY (shift_id) REFERENCES shifts(shift_id),
    CONSTRAINT fk_pay_user  FOREIGN KEY (user_id)  REFERENCES users(user_id)
) ENGINE=InnoDB;

CREATE TABLE sales_returns (
    return_id     INT AUTO_INCREMENT PRIMARY KEY,
    return_no     VARCHAR(30)   NOT NULL UNIQUE,
    sale_id       INT           NOT NULL,
    user_id       INT           NOT NULL,
    shift_id      INT           NULL,
    return_type   ENUM('REFUND','EXCHANGE','DUE_ADJUST') NOT NULL DEFAULT 'REFUND',
    refund_method ENUM('CASH','CARD','BKASH','NAGAD','ROCKET','STORE_CREDIT','NONE') NOT NULL DEFAULT 'CASH',
    total_amount  DECIMAL(14,2) NOT NULL,                 -- value of returned goods
    refund_amount DECIMAL(14,2) NOT NULL DEFAULT 0,       -- money actually paid back
    reason        VARCHAR(255)  NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_return_sale  FOREIGN KEY (sale_id)  REFERENCES sales(sale_id),
    CONSTRAINT fk_return_user  FOREIGN KEY (user_id)  REFERENCES users(user_id),
    CONSTRAINT fk_return_shift FOREIGN KEY (shift_id) REFERENCES shifts(shift_id)
) ENGINE=InnoDB;

CREATE TABLE sales_return_items (
    return_item_id INT AUTO_INCREMENT PRIMARY KEY,
    return_id      INT           NOT NULL,
    sale_item_id   INT           NOT NULL,
    quantity       DECIMAL(14,3) NOT NULL,
    amount         DECIMAL(14,2) NOT NULL,
    item_condition ENUM('GOOD','DAMAGED') NOT NULL DEFAULT 'GOOD',
    CONSTRAINT fk_ritem_return FOREIGN KEY (return_id)    REFERENCES sales_returns(return_id) ON DELETE CASCADE,
    CONSTRAINT fk_ritem_item   FOREIGN KEY (sale_item_id) REFERENCES sale_items(sale_item_id)
) ENGINE=InnoDB;

-- Carts parked with "Hold" so they survive an app restart
CREATE TABLE held_carts (
    held_id       INT AUTO_INCREMENT PRIMARY KEY,
    label         VARCHAR(80)   NOT NULL,
    customer_id   INT           NULL,
    price_mode    ENUM('RETAIL','WHOLESALE') NOT NULL DEFAULT 'RETAIL',
    cart_discount DECIMAL(14,2) NOT NULL DEFAULT 0,
    payload       TEXT          NOT NULL,                 -- one line per cart item
    user_id       INT           NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_held_user FOREIGN KEY (user_id) REFERENCES users(user_id)
) ENGINE=InnoDB;
