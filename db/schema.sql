-- =====================================================================
-- Product Management System + Point of Sale - Database Schema (v3)
-- Database : ProductMSJibon   (MySQL 5.7+/8.x or MariaDB 10.4+)
--
-- NON-DESTRUCTIVE: this script never drops anything. It only creates what
-- is missing, so it is safe to run on an existing database. The application
-- runs it on every start and then adds any columns that older databases lack
-- (see com.productms.db.Migrations).
--
-- Fresh install:  run this file, then seed.sql (demo data).
-- Full reset:     DROP DATABASE ProductMSJibon;  then run both files again.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS ProductMSJibon CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ProductMSJibon;

-- ---------------------------------------------------------------------
-- Users, settings, audit
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    user_id              INT AUTO_INCREMENT PRIMARY KEY,
    username             VARCHAR(50)   NOT NULL UNIQUE,
    password_hash        VARCHAR(100)  NOT NULL,              -- bcrypt ($2a$...); legacy SHA-256 upgraded on login
    full_name            VARCHAR(100)  NOT NULL,
    role                 ENUM('ADMIN','MANAGER','CASHIER') NOT NULL DEFAULT 'CASHIER',
    active               TINYINT(1)    NOT NULL DEFAULT 1,
    must_change_password TINYINT(1)    NOT NULL DEFAULT 0,
    failed_attempts      INT           NOT NULL DEFAULT 0,
    locked_until         DATETIME      NULL,
    last_login           DATETIME      NULL,
    password_changed_at  DATETIME      NULL,
    can_override_price   TINYINT(1)    NOT NULL DEFAULT 0,    -- cashier may change selling prices
    can_sell_on_credit   TINYINT(1)    NOT NULL DEFAULT 1,    -- cashier may record due sales
    max_discount_percent DECIMAL(5,2)  NULL,                  -- NULL = use the shop-wide limit
    max_sale_amount      DECIMAL(14,2) NULL,                  -- NULL = no limit; larger invoices need approval
    created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS settings (
    setting_key   VARCHAR(60)  PRIMARY KEY,
    setting_value VARCHAR(1000) NOT NULL
) ENGINE=InnoDB;

-- Who changed what (prices, stock, discounts, invoices, settings, logins...)
CREATE TABLE IF NOT EXISTS audit_log (
    audit_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     INT          NULL,
    username    VARCHAR(50)  NULL,
    action      VARCHAR(40)  NOT NULL,
    entity      VARCHAR(40)  NOT NULL,
    entity_id   VARCHAR(40)  NULL,
    details     TEXT         NULL,
    approved_by VARCHAR(50)  NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_date (created_at),
    INDEX idx_audit_entity (entity, entity_id)
) ENGINE=InnoDB;

-- Gap-free document numbering (invoices, returns, counts...) per period
CREATE TABLE IF NOT EXISTS doc_sequences (
    seq_name   VARCHAR(30) NOT NULL,
    period_key VARCHAR(10) NOT NULL,
    last_no    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (seq_name, period_key)
) ENGINE=InnoDB;

-- Payment methods are data, so shops can add their own (e.g. Upay, bank transfer)
CREATE TABLE IF NOT EXISTS payment_methods (
    code               VARCHAR(20)  PRIMARY KEY,
    name               VARCHAR(40)  NOT NULL,
    kind               ENUM('CASH','CARD','MOBILE','CREDIT','OTHER') NOT NULL DEFAULT 'OTHER',
    requires_reference TINYINT(1)   NOT NULL DEFAULT 1,
    reference_regex    VARCHAR(120) NULL,                     -- validation pattern for the reference
    reference_hint     VARCHAR(80)  NULL,
    color              VARCHAR(7)   NULL,
    sort_order         INT          NOT NULL DEFAULT 0,
    active             TINYINT(1)   NOT NULL DEFAULT 1
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Master data
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS categories (
    category_id INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(80)  NOT NULL,
    parent_id   INT          NULL,
    description VARCHAR(255) NULL,
    active      TINYINT(1)   NOT NULL DEFAULT 1,
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES categories(category_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS brands (
    brand_id INT AUTO_INCREMENT PRIMARY KEY,
    name     VARCHAR(80) NOT NULL UNIQUE,
    active   TINYINT(1)  NOT NULL DEFAULT 1
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS units (
    unit_id       INT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(40) NOT NULL UNIQUE,
    short_name    VARCHAR(10) NOT NULL UNIQUE,
    allow_decimal TINYINT(1)  NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS suppliers (
    supplier_id INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    phone       VARCHAR(30)  NULL,
    address     VARCHAR(255) NULL,
    active      TINYINT(1)   NOT NULL DEFAULT 1
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS customers (
    customer_id        INT AUTO_INCREMENT PRIMARY KEY,
    name               VARCHAR(100)  NOT NULL,
    phone              VARCHAR(30)   NULL UNIQUE,
    email              VARCHAR(100)  NULL,
    address            VARCHAR(255)  NULL,
    customer_type      ENUM('RETAIL','WHOLESALE') NOT NULL DEFAULT 'RETAIL',
    is_walk_in         TINYINT(1)    NOT NULL DEFAULT 0,
    active             TINYINT(1)    NOT NULL DEFAULT 1,
    credit_limit       DECIMAL(14,2) NULL,                    -- NULL = no limit, 0 = no credit allowed
    payment_terms_days INT           NOT NULL DEFAULT 30,     -- dues older than this are overdue
    store_credit       DECIMAL(14,2) NOT NULL DEFAULT 0,      -- balance from exchanges
    notes              TEXT          NULL,                    -- shown to the cashier at the POS
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS customer_credit_txns (
    txn_id      INT AUTO_INCREMENT PRIMARY KEY,
    customer_id INT           NOT NULL,
    amount      DECIMAL(14,2) NOT NULL,                       -- + credit given, - credit used
    reference   VARCHAR(50)   NULL,
    note        VARCHAR(255)  NULL,
    user_id     INT           NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cct_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Products & variants
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS products (
    product_id              INT AUTO_INCREMENT PRIMARY KEY,
    name                    VARCHAR(150)  NOT NULL,
    description             TEXT          NULL,
    category_id             INT           NULL,
    brand_id                INT           NULL,
    unit_id                 INT           NOT NULL,
    product_type            ENUM('STANDARD','BUNDLE') NOT NULL DEFAULT 'STANDARD',
    track_batch             TINYINT(1)    NOT NULL DEFAULT 0,
    track_serial            TINYINT(1)    NOT NULL DEFAULT 0,
    vat_percent             DECIMAL(5,2)  NOT NULL DEFAULT 0,
    discount_type           ENUM('NONE','PERCENT','FIXED') NOT NULL DEFAULT 'NONE',
    discount_value          DECIMAL(14,2) NOT NULL DEFAULT 0,
    discount_start          DATE          NULL,               -- scheduled discount window (NULL = open)
    discount_end            DATE          NULL,
    image_path              VARCHAR(255)  NULL,               -- primary image (first of product_images)
    tags                    VARCHAR(255)  NULL,               -- comma separated
    attributes              TEXT          NULL,               -- custom "key: value" lines (material, origin...)
    warranty_months         INT           NOT NULL DEFAULT 0,
    bundle_pricing          ENUM('FIXED','SUM_DISCOUNT') NOT NULL DEFAULT 'FIXED',
    bundle_discount_percent DECIMAL(5,2)  NOT NULL DEFAULT 0,
    active                  TINYINT(1)    NOT NULL DEFAULT 1,
    deleted                 TINYINT(1)    NOT NULL DEFAULT 0, -- soft delete (history kept)
    deleted_at              DATETIME      NULL,
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES categories(category_id) ON DELETE SET NULL,
    CONSTRAINT fk_product_brand    FOREIGN KEY (brand_id)    REFERENCES brands(brand_id)       ON DELETE SET NULL,
    CONSTRAINT fk_product_unit     FOREIGN KEY (unit_id)     REFERENCES units(unit_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS product_images (
    image_id   INT AUTO_INCREMENT PRIMARY KEY,
    product_id INT          NOT NULL,
    path       VARCHAR(255) NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_image_product FOREIGN KEY (product_id) REFERENCES products(product_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS product_variants (
    variant_id        INT AUTO_INCREMENT PRIMARY KEY,
    product_id        INT           NOT NULL,
    size              VARCHAR(30)   NULL,
    colour            VARCHAR(30)   NULL,
    weight            VARCHAR(30)   NULL,
    extra_attributes  VARCHAR(255)  NULL,                     -- custom variant attributes "Material=Cotton; Fit=Slim"
    sku               VARCHAR(50)   NOT NULL UNIQUE,
    barcode           VARCHAR(50)   NULL UNIQUE,
    plu               VARCHAR(10)   NULL UNIQUE,              -- scale (weighed barcode) item code
    purchase_price    DECIMAL(14,2) NOT NULL DEFAULT 0,       -- weighted average cost
    retail_price      DECIMAL(14,2) NOT NULL DEFAULT 0,
    wholesale_price   DECIMAL(14,2) NOT NULL DEFAULT 0,
    wholesale_min_qty DECIMAL(14,3) NOT NULL DEFAULT 0,
    mrp               DECIMAL(14,2) NOT NULL DEFAULT 0,       -- maximum retail price (0 = none)
    min_price         DECIMAL(14,2) NOT NULL DEFAULT 0,       -- never sell below without approval (0 = none)
    stock_qty         DECIMAL(14,3) NOT NULL DEFAULT 0,       -- sellable on-hand stock
    damaged_qty       DECIMAL(14,3) NOT NULL DEFAULT 0,       -- quarantined damaged units still on premises
    reorder_level     DECIMAL(14,3) NOT NULL DEFAULT 0,
    reorder_qty       DECIMAL(14,3) NOT NULL DEFAULT 0,       -- usual order quantity
    location          VARCHAR(50)   NULL,                     -- shelf / rack
    is_favourite      TINYINT(1)    NOT NULL DEFAULT 0,       -- POS quick button
    pos_order         INT           NOT NULL DEFAULT 0,       -- custom POS sort (lower first)
    active            TINYINT(1)    NOT NULL DEFAULT 1,
    CONSTRAINT fk_variant_product FOREIGN KEY (product_id) REFERENCES products(product_id) ON DELETE CASCADE,
    CONSTRAINT chk_variant_stock CHECK (stock_qty >= 0)
) ENGINE=InnoDB;

-- Extra barcodes (old packaging, supplier codes) and pack barcodes
CREATE TABLE IF NOT EXISTS variant_barcodes (
    barcode    VARCHAR(50) PRIMARY KEY,
    variant_id INT         NOT NULL,
    note       VARCHAR(80) NULL,
    CONSTRAINT fk_vb_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Packaging units / unit conversion (e.g. 1 BOX = 12 PCS)
CREATE TABLE IF NOT EXISTS variant_packs (
    pack_id    INT AUTO_INCREMENT PRIMARY KEY,
    variant_id INT           NOT NULL,
    name       VARCHAR(30)   NOT NULL,
    quantity   DECIMAL(14,3) NOT NULL,                        -- base units in one pack
    barcode    VARCHAR(50)   NULL UNIQUE,
    price      DECIMAL(14,2) NULL,                            -- pack selling price (NULL = quantity x unit price)
    CONSTRAINT fk_pack_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id) ON DELETE CASCADE,
    UNIQUE KEY uq_pack (variant_id, name)
) ENGINE=InnoDB;

-- Quantity-based price tiers (e.g. 10+ = 1,100; 50+ = 1,050)
CREATE TABLE IF NOT EXISTS price_tiers (
    tier_id    INT AUTO_INCREMENT PRIMARY KEY,
    variant_id INT           NOT NULL,
    min_qty    DECIMAL(14,3) NOT NULL,
    price      DECIMAL(14,2) NOT NULL,
    CONSTRAINT fk_tier_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id) ON DELETE CASCADE,
    UNIQUE KEY uq_tier (variant_id, min_qty)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS price_history (
    history_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    variant_id   INT           NOT NULL,
    field        VARCHAR(20)   NOT NULL,                      -- RETAIL, WHOLESALE, COST, MRP, MIN_PRICE
    old_value    DECIMAL(14,2) NULL,
    new_value    DECIMAL(14,2) NULL,
    effective_at DATETIME      NOT NULL,
    reason       VARCHAR(255)  NULL,
    user_id      INT           NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ph_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id) ON DELETE CASCADE,
    INDEX idx_ph_variant (variant_id, effective_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS scheduled_prices (
    schedule_id   INT AUTO_INCREMENT PRIMARY KEY,
    variant_id    INT           NOT NULL,
    new_retail    DECIMAL(14,2) NULL,
    new_wholesale DECIMAL(14,2) NULL,
    effective_at  DATETIME      NOT NULL,
    status        ENUM('PENDING','APPLIED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    note          VARCHAR(255)  NULL,
    created_by    INT           NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at    DATETIME      NULL,
    CONSTRAINT fk_sp_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS cost_history (
    history_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    variant_id INT           NOT NULL,
    old_cost   DECIMAL(14,2) NOT NULL,
    new_cost   DECIMAL(14,2) NOT NULL,
    reason     VARCHAR(40)   NOT NULL,                        -- STOCK_IN, MANUAL, IMPORT
    reference  VARCHAR(50)   NULL,
    user_id    INT           NULL,
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ch_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS bundle_items (
    bundle_item_id       INT AUTO_INCREMENT PRIMARY KEY,
    bundle_variant_id    INT           NOT NULL,
    component_variant_id INT           NOT NULL,
    quantity             DECIMAL(14,3) NOT NULL,
    optional             TINYINT(1)    NOT NULL DEFAULT 0,    -- customer may leave it out
    CONSTRAINT fk_bundle_parent    FOREIGN KEY (bundle_variant_id)    REFERENCES product_variants(variant_id) ON DELETE CASCADE,
    CONSTRAINT fk_bundle_component FOREIGN KEY (component_variant_id) REFERENCES product_variants(variant_id) ON DELETE RESTRICT,
    UNIQUE KEY uq_bundle_component (bundle_variant_id, component_variant_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS product_batches (
    batch_id         INT AUTO_INCREMENT PRIMARY KEY,
    variant_id       INT           NOT NULL,
    batch_no         VARCHAR(50)   NOT NULL,
    manufacture_date DATE          NULL,
    expiry_date      DATE          NULL,
    qty_received     DECIMAL(14,3) NOT NULL DEFAULT 0,
    qty_remaining    DECIMAL(14,3) NOT NULL DEFAULT 0,
    unit_cost        DECIMAL(14,2) NOT NULL DEFAULT 0,        -- batch-specific cost (used as sale cost)
    received_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_batch_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id) ON DELETE CASCADE,
    CONSTRAINT chk_batch_remaining CHECK (qty_remaining >= 0),
    UNIQUE KEY uq_batch (variant_id, batch_no)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS product_serials (
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
-- Stock ledger, damaged stock, reservations, counts, approvals
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_movements (
    movement_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    variant_id    INT           NOT NULL,
    batch_id      INT           NULL,
    movement_type ENUM('OPENING','STOCK_IN','STOCK_OUT','SALE','SALE_CANCEL','RETURN',
                       'ADJUSTMENT_IN','ADJUSTMENT_OUT','DAMAGED','EXPIRED') NOT NULL,
    quantity      DECIMAL(14,3) NOT NULL,
    balance_after DECIMAL(14,3) NOT NULL,
    unit_cost     DECIMAL(14,2) NOT NULL DEFAULT 0,
    reference_no  VARCHAR(50)   NULL,
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

-- Damaged units kept aside (not sellable) until disposed or restored
CREATE TABLE IF NOT EXISTS damaged_stock_log (
    log_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
    variant_id INT           NOT NULL,
    batch_id   INT           NULL,
    quantity   DECIMAL(14,3) NOT NULL,                        -- + into damaged, - out of damaged
    action     ENUM('DAMAGED','DISPOSED','RESTORED') NOT NULL,
    unit_cost  DECIMAL(14,2) NOT NULL DEFAULT 0,
    reference  VARCHAR(50)   NULL,
    note       VARCHAR(255)  NULL,
    user_id    INT           NULL,
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dsl_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS stock_counts (
    count_id     INT AUTO_INCREMENT PRIMARY KEY,
    count_no     VARCHAR(30)  NOT NULL UNIQUE,
    name         VARCHAR(100) NOT NULL,
    category_id  INT          NULL,
    status       ENUM('OPEN','SUBMITTED','APPROVED','CANCELLED') NOT NULL DEFAULT 'OPEN',
    note         VARCHAR(255) NULL,
    created_by   INT          NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at DATETIME     NULL,
    approved_by  INT          NULL,
    approved_at  DATETIME     NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS stock_count_items (
    item_id     INT AUTO_INCREMENT PRIMARY KEY,
    count_id    INT           NOT NULL,
    variant_id  INT           NOT NULL,
    system_qty  DECIMAL(14,3) NOT NULL,
    counted_qty DECIMAL(14,3) NULL,
    unit_cost   DECIMAL(14,2) NOT NULL DEFAULT 0,
    CONSTRAINT fk_sci_count   FOREIGN KEY (count_id)   REFERENCES stock_counts(count_id) ON DELETE CASCADE,
    CONSTRAINT fk_sci_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id) ON DELETE CASCADE,
    UNIQUE KEY uq_count_item (count_id, variant_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS adjustment_requests (
    request_id    INT AUTO_INCREMENT PRIMARY KEY,
    variant_id    INT           NOT NULL,
    adj_type      VARCHAR(20)   NOT NULL,
    quantity      DECIMAL(14,3) NOT NULL,
    batch_id      INT           NULL,
    serials       TEXT          NULL,
    note          VARCHAR(255)  NOT NULL,
    value         DECIMAL(14,2) NOT NULL DEFAULT 0,
    status        ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
    requested_by  INT           NOT NULL,
    requested_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by    INT           NULL,
    decided_at    DATETIME      NULL,
    decision_note VARCHAR(255)  NULL,
    CONSTRAINT fk_ar_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS label_templates (
    template_id INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(60)  NOT NULL UNIQUE,
    width_mm    DECIMAL(6,2) NOT NULL,
    height_mm   DECIMAL(6,2) NOT NULL,
    cols        INT          NOT NULL DEFAULT 1,
    rows_count  INT          NOT NULL DEFAULT 1,
    roll        TINYINT(1)   NOT NULL DEFAULT 0,
    show_shop   TINYINT(1)   NOT NULL DEFAULT 1,
    show_name   TINYINT(1)   NOT NULL DEFAULT 1,
    show_price  TINYINT(1)   NOT NULL DEFAULT 1,
    show_mrp    TINYINT(1)   NOT NULL DEFAULT 0,
    show_sku    TINYINT(1)   NOT NULL DEFAULT 0,
    show_qr     TINYINT(1)   NOT NULL DEFAULT 0,
    font_scale  DECIMAL(4,2) NOT NULL DEFAULT 1.00
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- POS: shifts, cash, sales, payments, refunds, held carts
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS shifts (
    shift_id      INT AUTO_INCREMENT PRIMARY KEY,
    user_id       INT           NOT NULL,
    opened_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at     TIMESTAMP     NULL,
    opening_cash  DECIMAL(14,2) NOT NULL DEFAULT 0,
    expected_cash DECIMAL(14,2) NULL,
    counted_cash  DECIMAL(14,2) NULL,
    difference    DECIMAL(14,2) NULL,
    denominations VARCHAR(500)  NULL,                         -- "1000x3;500x2;..." from the closing count
    status        ENUM('OPEN','CLOSED') NOT NULL DEFAULT 'OPEN',
    note          VARCHAR(255)  NULL,
    CONSTRAINT fk_shift_user FOREIGN KEY (user_id) REFERENCES users(user_id)
) ENGINE=InnoDB;

-- Cash put into / taken out of the drawer, and safe drops
CREATE TABLE IF NOT EXISTS cash_movements (
    movement_id INT AUTO_INCREMENT PRIMARY KEY,
    shift_id    INT           NOT NULL,
    user_id     INT           NOT NULL,
    type        ENUM('CASH_IN','CASH_OUT','SAFE_DROP') NOT NULL,
    amount      DECIMAL(14,2) NOT NULL,
    reason      VARCHAR(255)  NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cm_shift FOREIGN KEY (shift_id) REFERENCES shifts(shift_id)
) ENGINE=InnoDB;

-- Per payment method: what the system expected vs what the cashier declared at close
CREATE TABLE IF NOT EXISTS shift_reconciliation (
    rec_id     INT AUTO_INCREMENT PRIMARY KEY,
    shift_id   INT           NOT NULL,
    method     VARCHAR(20)   NOT NULL,
    expected   DECIMAL(14,2) NOT NULL,
    declared   DECIMAL(14,2) NOT NULL,
    difference DECIMAL(14,2) NOT NULL,
    CONSTRAINT fk_rec_shift FOREIGN KEY (shift_id) REFERENCES shifts(shift_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sales (
    sale_id        INT AUTO_INCREMENT PRIMARY KEY,
    invoice_no     VARCHAR(40)   NOT NULL UNIQUE,
    client_txn_id  VARCHAR(40)   NULL UNIQUE,                 -- idempotency key: one checkout = one sale
    offline_ref    VARCHAR(40)   NULL,                        -- provisional number if sold while the DB was down
    customer_id    INT           NOT NULL,
    user_id        INT           NOT NULL,
    shift_id       INT           NULL,
    price_mode     ENUM('RETAIL','WHOLESALE') NOT NULL DEFAULT 'RETAIL',
    subtotal       DECIMAL(14,2) NOT NULL DEFAULT 0,
    item_discount  DECIMAL(14,2) NOT NULL DEFAULT 0,
    cart_discount  DECIMAL(14,2) NOT NULL DEFAULT 0,
    vat_total      DECIMAL(14,2) NOT NULL DEFAULT 0,
    grand_total    DECIMAL(14,2) NOT NULL DEFAULT 0,
    paid_total     DECIMAL(14,2) NOT NULL DEFAULT 0,
    due_total      DECIMAL(14,2) NOT NULL DEFAULT 0,
    tendered_total DECIMAL(14,2) NOT NULL DEFAULT 0,
    change_amount  DECIMAL(14,2) NOT NULL DEFAULT 0,
    cost_total     DECIMAL(14,2) NOT NULL DEFAULT 0,
    payment_status ENUM('PAID','PARTIAL','DUE') NOT NULL DEFAULT 'PAID',
    status         ENUM('COMPLETED','CANCELLED') NOT NULL DEFAULT 'COMPLETED',
    note           VARCHAR(255)  NULL,
    approved_by    VARCHAR(50)   NULL,                        -- manager who approved limits on this sale
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at   TIMESTAMP     NULL,
    cancelled_by   INT           NULL,
    cancel_reason  VARCHAR(255)  NULL,
    CONSTRAINT fk_sale_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id),
    CONSTRAINT fk_sale_user     FOREIGN KEY (user_id)     REFERENCES users(user_id),
    CONSTRAINT fk_sale_shift    FOREIGN KEY (shift_id)    REFERENCES shifts(shift_id),
    INDEX idx_sale_date (created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sale_items (
    sale_item_id        INT AUTO_INCREMENT PRIMARY KEY,
    sale_id             INT           NOT NULL,
    variant_id          INT           NOT NULL,
    product_name        VARCHAR(150)  NOT NULL,
    variant_label       VARCHAR(150)  NULL,
    sku                 VARCHAR(50)   NOT NULL,
    unit_name           VARCHAR(10)   NOT NULL,
    quantity            DECIMAL(14,3) NOT NULL,
    unit_price          DECIMAL(14,2) NOT NULL,
    original_price      DECIMAL(14,2) NULL,                   -- list price when the price was overridden
    discount_amount     DECIMAL(14,2) NOT NULL DEFAULT 0,
    vat_percent         DECIMAL(5,2)  NOT NULL DEFAULT 0,
    vat_amount          DECIMAL(14,2) NOT NULL DEFAULT 0,
    line_total          DECIMAL(14,2) NOT NULL,
    unit_cost           DECIMAL(14,2) NOT NULL DEFAULT 0,     -- cost snapshot at time of sale
    returned_qty        DECIMAL(14,3) NOT NULL DEFAULT 0,
    serial_numbers      VARCHAR(500)  NULL,
    warranty_months     INT           NOT NULL DEFAULT 0,
    excluded_components VARCHAR(255)  NULL,                   -- optional combo parts left out
    note                VARCHAR(255)  NULL,
    CONSTRAINT fk_item_sale    FOREIGN KEY (sale_id)    REFERENCES sales(sale_id) ON DELETE CASCADE,
    CONSTRAINT fk_item_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sale_item_allocations (
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

CREATE TABLE IF NOT EXISTS payments (
    payment_id   INT AUTO_INCREMENT PRIMARY KEY,
    sale_id      INT           NOT NULL,
    shift_id     INT           NULL,
    user_id      INT           NOT NULL,
    method       VARCHAR(20)   NOT NULL,
    amount       DECIMAL(14,2) NOT NULL,
    reference    VARCHAR(80)   NULL,
    payment_type ENUM('SALE','DUE_COLLECTION') NOT NULL DEFAULT 'SALE',
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pay_sale  FOREIGN KEY (sale_id)  REFERENCES sales(sale_id) ON DELETE CASCADE,
    CONSTRAINT fk_pay_shift FOREIGN KEY (shift_id) REFERENCES shifts(shift_id),
    CONSTRAINT fk_pay_user  FOREIGN KEY (user_id)  REFERENCES users(user_id),
    INDEX idx_pay_ref (method, reference)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sales_returns (
    return_id     INT AUTO_INCREMENT PRIMARY KEY,
    return_no     VARCHAR(40)   NOT NULL UNIQUE,
    sale_id       INT           NOT NULL,
    user_id       INT           NOT NULL,
    shift_id      INT           NULL,
    return_type   ENUM('REFUND','EXCHANGE','DUE_ADJUST','CANCEL') NOT NULL DEFAULT 'REFUND',
    refund_method VARCHAR(20)   NOT NULL DEFAULT 'CASH',      -- main method; detail in refund_payments
    total_amount  DECIMAL(14,2) NOT NULL,                     -- value of returned goods incl. VAT
    vat_amount    DECIMAL(14,2) NOT NULL DEFAULT 0,           -- VAT part of total_amount
    cost_amount   DECIMAL(14,2) NOT NULL DEFAULT 0,           -- cost of the goods returned
    refund_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    reason        VARCHAR(255)  NULL,
    approved_by   VARCHAR(50)   NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_return_sale  FOREIGN KEY (sale_id)  REFERENCES sales(sale_id),
    CONSTRAINT fk_return_user  FOREIGN KEY (user_id)  REFERENCES users(user_id),
    CONSTRAINT fk_return_shift FOREIGN KEY (shift_id) REFERENCES shifts(shift_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sales_return_items (
    return_item_id INT AUTO_INCREMENT PRIMARY KEY,
    return_id      INT           NOT NULL,
    sale_item_id   INT           NOT NULL,
    quantity       DECIMAL(14,3) NOT NULL,
    amount         DECIMAL(14,2) NOT NULL,
    vat_amount     DECIMAL(14,2) NOT NULL DEFAULT 0,
    cost_amount    DECIMAL(14,2) NOT NULL DEFAULT 0,
    item_condition ENUM('GOOD','DAMAGED') NOT NULL DEFAULT 'GOOD',
    CONSTRAINT fk_ritem_return FOREIGN KEY (return_id)    REFERENCES sales_returns(return_id) ON DELETE CASCADE,
    CONSTRAINT fk_ritem_item   FOREIGN KEY (sale_item_id) REFERENCES sale_items(sale_item_id)
) ENGINE=InnoDB;

-- Money paid back, linked to the original payment it reverses
CREATE TABLE IF NOT EXISTS refund_payments (
    refund_id           INT AUTO_INCREMENT PRIMARY KEY,
    return_id           INT           NOT NULL,
    original_payment_id INT           NULL,
    method              VARCHAR(20)   NOT NULL,
    amount              DECIMAL(14,2) NOT NULL,
    reference           VARCHAR(80)   NULL,
    shift_id            INT           NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rp_return  FOREIGN KEY (return_id)           REFERENCES sales_returns(return_id) ON DELETE CASCADE,
    CONSTRAINT fk_rp_payment FOREIGN KEY (original_payment_id) REFERENCES payments(payment_id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS held_carts (
    held_id       INT AUTO_INCREMENT PRIMARY KEY,
    label         VARCHAR(80)   NOT NULL,
    customer_id   INT           NULL,
    price_mode    ENUM('RETAIL','WHOLESALE') NOT NULL DEFAULT 'RETAIL',
    cart_discount DECIMAL(14,2) NOT NULL DEFAULT 0,
    payload       TEXT          NOT NULL,
    user_id       INT           NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at    DATETIME      NULL,                         -- held carts are discarded after this
    CONSTRAINT fk_held_user FOREIGN KEY (user_id) REFERENCES users(user_id)
) ENGINE=InnoDB;

-- Stock set aside for held carts until they are resumed or expire
CREATE TABLE IF NOT EXISTS stock_reservations (
    reservation_id INT AUTO_INCREMENT PRIMARY KEY,
    held_id        INT           NOT NULL,
    variant_id     INT           NOT NULL,
    quantity       DECIMAL(14,3) NOT NULL,
    expires_at     DATETIME      NOT NULL,
    CONSTRAINT fk_res_held    FOREIGN KEY (held_id)    REFERENCES held_carts(held_id) ON DELETE CASCADE,
    CONSTRAINT fk_res_variant FOREIGN KEY (variant_id) REFERENCES product_variants(variant_id) ON DELETE CASCADE,
    INDEX idx_res_variant (variant_id, expires_at)
) ENGINE=InnoDB;
