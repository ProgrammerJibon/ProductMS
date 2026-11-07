-- =====================================================================
-- Product Management System + POS - Seed / demo data
-- Run after schema.sql
--   Admin   : admin   / admin123
--   Cashier : cashier / cashier123
-- =====================================================================
USE ProductMSEasirArafat;

-- ---------------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------------
INSERT INTO users (user_id, username, password_hash, full_name, role) VALUES
 (1, 'admin',   SHA2('admin123', 256),   'System Administrator', 'ADMIN'),
 (2, 'cashier', SHA2('cashier123', 256), 'Front Desk Cashier',   'CASHIER');

-- ---------------------------------------------------------------------
-- Shop settings
-- ---------------------------------------------------------------------
INSERT INTO settings (setting_key, setting_value) VALUES
 ('shop_name',          'Easir Arafat Super Shop'),
 ('shop_address',       'House 12, Road 5, Dhanmondi, Dhaka 1205'),
 ('shop_phone',         '01700-000000'),
 ('shop_vat_reg',       'BIN 000123456-0101'),
 ('currency',           'Tk'),
 ('price_includes_vat', '0'),
 ('receipt_size',       '80'),
 ('receipt_footer',     'Thank you for shopping with us! Returns accepted within 7 days with receipt.'),
 ('expiry_alert_days',  '30'),
 ('return_days',        '7'),
 ('require_shift',      '1'),
 ('max_cashier_discount','10'),
 ('theme',              'light');

-- ---------------------------------------------------------------------
-- Units, categories, brands, suppliers, customers
-- ---------------------------------------------------------------------
INSERT INTO units (unit_id, name, short_name, allow_decimal) VALUES
 (1, 'Piece',    'PCS', 0),
 (2, 'Kilogram', 'KG',  1),
 (3, 'Litre',    'L',   1),
 (4, 'Box',      'BOX', 0),
 (5, 'Pack',     'PACK',0);

INSERT INTO categories (category_id, name, parent_id, description) VALUES
 (1,  'Electronics', NULL, 'Electronic goods'),
 (2,  'Grocery',     NULL, 'Daily grocery items'),
 (3,  'Clothing',    NULL, 'Apparel'),
 (4,  'Stationery',  NULL, 'Office and school supplies'),
 (5,  'Computer Accessories', 1, NULL),
 (6,  'Mobile Phones',        1, NULL),
 (7,  'Storage Devices',      1, NULL),
 (8,  'Rice & Grains',        2, NULL),
 (9,  'Dairy',                2, NULL),
 (10, 'Beverages',            2, NULL),
 (11, 'Men',                  3, NULL),
 (12, 'Pens & Writing',       4, NULL),
 (13, 'Notebooks',            4, NULL);

INSERT INTO brands (brand_id, name) VALUES
 (1, 'Logitech'), (2, 'Samsung'), (3, 'SanDisk'), (4, 'Chashi'),
 (5, 'Milk Vita'), (6, 'Mojo'), (7, 'Easy Fashion'), (8, 'Matador'), (9, 'Fresh');

INSERT INTO suppliers (supplier_id, name, phone, address) VALUES
 (1, 'Global Tech Distribution', '01811-111111', 'IDB Bhaban, Agargaon, Dhaka'),
 (2, 'Fresh Foods Ltd.',         '01911-222222', 'Kawran Bazar, Dhaka'),
 (3, 'City Garments',            '01611-333333', 'Gausia Market, Dhaka');

INSERT INTO customers (customer_id, name, phone, address, customer_type, is_walk_in) VALUES
 (1, 'Walk-in Customer', NULL,          NULL,                  'RETAIL',    1),
 (2, 'Rahim Traders',    '01711000001', 'Mirpur 10, Dhaka',    'WHOLESALE', 0),
 (3, 'Karim Uddin',      '01811000002', 'Mohammadpur, Dhaka',  'RETAIL',    0);

-- ---------------------------------------------------------------------
-- Products
-- ---------------------------------------------------------------------
INSERT INTO products (product_id, name, description, category_id, brand_id, unit_id, product_type,
                      track_batch, track_serial, vat_percent, discount_type, discount_value) VALUES
 (1,  'Wireless Mouse M185',   'Compact wireless mouse with USB nano receiver, 12 month battery life.', 5, 1, 1, 'STANDARD', 0, 0, 5.00,  'NONE',    0),
 (2,  'Keyboard K120',         'Full-size USB keyboard, spill resistant.',                            5, 1, 1, 'STANDARD', 0, 0, 5.00,  'NONE',    0),
 (3,  'USB Flash Drive Ultra', 'USB 3.0 flash drive.',                                                7, 3, 1, 'STANDARD', 0, 0, 5.00,  'NONE',    0),
 (4,  'Cotton Crew T-Shirt',   '100% cotton round neck T-shirt.',                                     11, 7, 1, 'STANDARD', 0, 0, 7.50, 'PERCENT', 10),
 (5,  'Miniket Rice',          'Premium Miniket rice, sold loose by the kilogram.',                   8, 4, 2, 'STANDARD', 0, 0, 0.00,  'NONE',    0),
 (6,  'Pasteurized Milk 1L',   'Fresh pasteurized liquid milk.',                                      9, 5, 1, 'STANDARD', 1, 0, 0.00,  'NONE',    0),
 (7,  'Mojo Soft Drink 250ml', 'Carbonated soft drink can.',                                          10, 6, 1, 'STANDARD', 1, 0, 15.00, 'NONE',   0),
 (8,  'Galaxy A15 Smartphone', '6.5" display, 50MP camera, 5000mAh battery. 1 year warranty by IMEI.', 6, 2, 1, 'STANDARD', 0, 1, 5.00,  'NONE',    0),
 (9,  'Notebook A5 200 Pages', 'Ruled notebook, 200 pages.',                                          13, 9, 1, 'STANDARD', 0, 0, 0.00,  'NONE',    0),
 (10, 'Mouse + Keyboard Combo','Combo offer: Wireless Mouse M185 (Black) + Keyboard K120.',           5, 1, 1, 'BUNDLE',   0, 0, 5.00,  'NONE',    0),
 (11, 'Ballpoint Pen (Box of 12)', 'Blue ink ballpoint pens, 12 per box.',                            12, 8, 4, 'STANDARD', 0, 0, 0.00,  'FIXED',   5);

-- variant_id, product_id, size, colour, weight, sku, barcode, purchase, retail, wholesale, wholesale_min_qty, stock, reorder
INSERT INTO product_variants (variant_id, product_id, size, colour, weight, sku, barcode,
        purchase_price, retail_price, wholesale_price, wholesale_min_qty, stock_qty, reorder_level) VALUES
 (1,  1,  NULL,    'Black', NULL,    'LOG-M185-BLK', '2000000000015',  900.00, 1250.00, 1100.00, 10,  40, 5),
 (2,  1,  NULL,    'Grey',  NULL,    'LOG-M185-GRY', '2000000000022',  900.00, 1250.00, 1100.00, 10,  20, 5),
 (3,  2,  NULL,    'Black', NULL,    'LOG-K120-BLK', '2000000000039',  650.00,  950.00,  850.00, 10,  25, 5),
 (4,  3,  '32GB',  NULL,    NULL,    'SD-ULT-32',    '2000000000046',  420.00,  590.00,  520.00, 20,  60, 10),
 (5,  3,  '64GB',  NULL,    NULL,    'SD-ULT-64',    '2000000000053',  650.00,  890.00,  800.00, 20,  35, 10),
 (6,  4,  'M',     'Black', NULL,    'TS-CRW-M-BLK', '2000000000060',  220.00,  450.00,  350.00, 12,  18, 5),
 (7,  4,  'L',     'Black', NULL,    'TS-CRW-L-BLK', '2000000000077',  220.00,  450.00,  350.00, 12,  22, 5),
 (8,  4,  'XL',    'Black', NULL,    'TS-CRW-XL-BLK','2000000000084',  230.00,  480.00,  370.00, 12,   4, 5),
 (9,  4,  'L',     'White', NULL,    'TS-CRW-L-WHT', '2000000000091',  220.00,  450.00,  350.00, 12,  15, 5),
 (10, 5,  NULL,    NULL,    '1 KG',  'RICE-MINI-KG', '2000000000107',   68.00,   82.00,   76.00, 25, 120, 20),
 (11, 6,  NULL,    NULL,    '1 L',   'MV-MILK-1L',   '2000000000114',   78.00,   95.00,   88.00, 24,  39, 12),
 (12, 7,  NULL,    NULL,    '250 ml','MOJO-250',     '2000000000121',   22.00,   30.00,   25.00, 24,  48, 24),
 (13, 8,  '128GB', 'Blue',  NULL,    'SAM-A15-128-BLU','2000000000138',17500.00,19999.00,19200.00, 5,   3, 2),
 (14, 8,  '128GB', 'Black', NULL,    'SAM-A15-128-BLK','2000000000145',17500.00,19999.00,19200.00, 5,   1, 2),
 (15, 9,  NULL,    NULL,    NULL,    'NB-A5-200',    '2000000000152',   55.00,   80.00,   65.00, 24,   0, 10),
 (16, 10, NULL,    NULL,    NULL,    'COMBO-MK-01',  '2000000000169',    0.00, 2000.00, 1850.00, 10,   0, 0),
 (17, 11, NULL,    'Blue',  NULL,    'MAT-PEN-BOX12','2000000000176',   90.00,  120.00,  105.00, 10,   3, 5);

-- Combo = 1 x Mouse (Black) + 1 x Keyboard
INSERT INTO bundle_items (bundle_variant_id, component_variant_id, quantity) VALUES
 (16, 1, 1),
 (16, 3, 1);

-- Batches with expiry dates (one expired, one expiring soon, rest fine)
INSERT INTO product_batches (batch_id, variant_id, batch_no, manufacture_date, expiry_date, qty_received, qty_remaining, unit_cost) VALUES
 (1, 11, 'MLK-2401', CURDATE() - INTERVAL 12 DAY, CURDATE() - INTERVAL 2 DAY,  10,  3, 78.00),
 (2, 11, 'MLK-2402', CURDATE() - INTERVAL 3 DAY,  CURDATE() + INTERVAL 4 DAY,  12, 12, 78.00),
 (3, 11, 'MLK-2403', CURDATE(),                   CURDATE() + INTERVAL 20 DAY, 24, 24, 78.00),
 (4, 12, 'MJ-0925',  CURDATE() - INTERVAL 30 DAY, CURDATE() + INTERVAL 150 DAY,48, 48, 22.00);

-- Serial numbers (IMEI) for the smartphone
INSERT INTO product_serials (variant_id, serial_no) VALUES
 (13, '359120450000011'),
 (13, '359120450000029'),
 (13, '359120450000037'),
 (14, '359120450000045');

-- ---------------------------------------------------------------------
-- Opening stock ledger (matches stock_qty above)
-- ---------------------------------------------------------------------
INSERT INTO stock_movements (variant_id, batch_id, movement_type, quantity, balance_after, unit_cost, reference_no, note, user_id) VALUES
 (1,  NULL, 'OPENING', 40,  40,  900.00,  'OPENING', 'Opening stock', 1),
 (2,  NULL, 'OPENING', 20,  20,  900.00,  'OPENING', 'Opening stock', 1),
 (3,  NULL, 'OPENING', 25,  25,  650.00,  'OPENING', 'Opening stock', 1),
 (4,  NULL, 'OPENING', 60,  60,  420.00,  'OPENING', 'Opening stock', 1),
 (5,  NULL, 'OPENING', 35,  35,  650.00,  'OPENING', 'Opening stock', 1),
 (6,  NULL, 'OPENING', 18,  18,  220.00,  'OPENING', 'Opening stock', 1),
 (7,  NULL, 'OPENING', 22,  22,  220.00,  'OPENING', 'Opening stock', 1),
 (8,  NULL, 'OPENING', 4,   4,   230.00,  'OPENING', 'Opening stock', 1),
 (9,  NULL, 'OPENING', 15,  15,  220.00,  'OPENING', 'Opening stock', 1),
 (10, NULL, 'OPENING', 120, 120, 68.00,   'OPENING', 'Opening stock', 1),
 (11, 1,    'OPENING', 3,   3,   78.00,   'OPENING', 'Opening stock - batch MLK-2401', 1),
 (11, 2,    'OPENING', 12,  15,  78.00,   'OPENING', 'Opening stock - batch MLK-2402', 1),
 (11, 3,    'OPENING', 24,  39,  78.00,   'OPENING', 'Opening stock - batch MLK-2403', 1),
 (12, 4,    'OPENING', 48,  48,  22.00,   'OPENING', 'Opening stock - batch MJ-0925', 1),
 (13, NULL, 'OPENING', 3,   3,   17500.00,'OPENING', 'Opening stock (3 IMEI)', 1),
 (14, NULL, 'OPENING', 1,   1,   17500.00,'OPENING', 'Opening stock (1 IMEI)', 1),
 (17, NULL, 'OPENING', 3,   3,   90.00,   'OPENING', 'Opening stock', 1);
