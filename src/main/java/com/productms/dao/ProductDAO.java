package com.productms.dao;

import com.productms.db.Database;
import com.productms.db.Jdbc;
import com.productms.model.Batch;
import com.productms.model.BundleItem;
import com.productms.model.Product;
import com.productms.model.Variant;
import com.productms.service.InventoryService;
import com.productms.util.AppException;
import com.productms.util.BarcodeUtil;
import com.productms.util.Money;
import com.productms.util.Session;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Products, variants, bundles, batches and serial numbers. */
public class ProductDAO {

    /** Sellable quantity of a non-bundle variant aliased "x" (expired batches excluded). */
    private static String availOf(String v, String p) {
        return "(CASE WHEN " + p + ".track_batch = 1 THEN (SELECT COALESCE(SUM(b.qty_remaining),0) FROM product_batches b "
                + "WHERE b.variant_id = " + v + ".variant_id AND (b.expiry_date IS NULL OR b.expiry_date >= CURDATE())) "
                + "ELSE " + v + ".stock_qty END)";
    }

    private static final String AVAILABLE =
            "(CASE WHEN p.product_type = 'BUNDLE' THEN COALESCE((SELECT MIN(FLOOR(" + availOf("c", "cp") + " / bi.quantity)) "
            + "FROM bundle_items bi JOIN product_variants c ON c.variant_id = bi.component_variant_id "
            + "JOIN products cp ON cp.product_id = c.product_id WHERE bi.bundle_variant_id = v.variant_id), 0) "
            + "ELSE " + availOf("v", "p") + " END)";

    private static final String SELECT_VARIANT =
            "SELECT v.*, p.name AS product_name, p.description, p.product_type, p.track_batch, p.track_serial, "
            + "p.active AS product_active, p.vat_percent, p.discount_type, p.discount_value, p.category_id, p.brand_id, "
            + "p.image_path, cat.name AS cat_name, par.name AS parent_cat, b.name AS brand_name, u.short_name, u.allow_decimal, "
            + AVAILABLE + " AS available "
            + "FROM product_variants v JOIN products p ON p.product_id = v.product_id "
            + "JOIN units u ON u.unit_id = p.unit_id "
            + "LEFT JOIN categories cat ON cat.category_id = p.category_id "
            + "LEFT JOIN categories par ON par.category_id = cat.parent_id "
            + "LEFT JOIN brands b ON b.brand_id = p.brand_id ";

    static Variant mapVariant(ResultSet rs) throws SQLException {
        Variant v = new Variant();
        v.variantId = rs.getInt("variant_id");
        v.productId = rs.getInt("product_id");
        v.size = rs.getString("size");
        v.colour = rs.getString("colour");
        v.weight = rs.getString("weight");
        v.sku = rs.getString("sku");
        v.barcode = rs.getString("barcode");
        v.purchasePrice = rs.getBigDecimal("purchase_price");
        v.retailPrice = rs.getBigDecimal("retail_price");
        v.wholesalePrice = rs.getBigDecimal("wholesale_price");
        v.wholesaleMinQty = rs.getBigDecimal("wholesale_min_qty");
        v.stockQty = rs.getBigDecimal("stock_qty");
        v.reorderLevel = rs.getBigDecimal("reorder_level");
        v.active = rs.getBoolean("active");
        v.productName = rs.getString("product_name");
        v.description = rs.getString("description");
        v.productType = rs.getString("product_type");
        v.trackBatch = rs.getBoolean("track_batch");
        v.trackSerial = rs.getBoolean("track_serial");
        v.productActive = rs.getBoolean("product_active");
        v.vatPercent = rs.getBigDecimal("vat_percent");
        v.discountType = rs.getString("discount_type");
        v.discountValue = rs.getBigDecimal("discount_value");
        v.categoryId = Jdbc.intOrNull(rs, "category_id");
        String cat = rs.getString("cat_name");
        String parent = rs.getString("parent_cat");
        v.categoryName = cat == null ? null : (parent == null ? cat : parent + " › " + cat);
        v.brandId = Jdbc.intOrNull(rs, "brand_id");
        v.brandName = rs.getString("brand_name");
        v.unitShort = rs.getString("short_name");
        v.allowDecimal = rs.getBoolean("allow_decimal");
        v.imagePath = rs.getString("image_path");
        BigDecimal a = rs.getBigDecimal("available");
        v.available = a == null ? BigDecimal.ZERO : a;
        return v;
    }

    /** Filter for {@link #search(Filter)}. */
    public static class Filter {
        public String text;
        public Integer categoryId;
        public Integer brandId;
        /** ALL, ACTIVE, INACTIVE */
        public String status = "ALL";
        /** ALL, IN, LOW, OUT */
        public String stock = "ALL";
        public boolean sellableOnly;
        public boolean excludeBundles;
        public int limit = 1000;
    }

    public List<Variant> search(Filter f) throws SQLException {
        StringBuilder sql = new StringBuilder(SELECT_VARIANT).append("WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (f.text != null && !f.text.isBlank()) {
            sql.append("AND (p.name LIKE ? OR v.sku LIKE ? OR v.barcode = ? OR b.name LIKE ? "
                    + "OR CONCAT_WS(' ', v.size, v.colour, v.weight) LIKE ?) ");
            String like = "%" + f.text.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(f.text.trim());
            params.add(like);
            params.add(like);
        }
        if (f.categoryId != null) {
            sql.append("AND (p.category_id = ? OR cat.parent_id = ?) ");
            params.add(f.categoryId);
            params.add(f.categoryId);
        }
        if (f.brandId != null) {
            sql.append("AND p.brand_id = ? ");
            params.add(f.brandId);
        }
        if (f.sellableOnly || "ACTIVE".equals(f.status)) {
            sql.append("AND v.active = 1 AND p.active = 1 ");
        } else if ("INACTIVE".equals(f.status)) {
            sql.append("AND (v.active = 0 OR p.active = 0) ");
        }
        if (f.excludeBundles) {
            sql.append("AND p.product_type <> 'BUNDLE' ");
        }
        switch (f.stock) {
            case "OUT":
                sql.append("AND ").append(AVAILABLE).append(" <= 0 ");
                break;
            case "LOW":
                sql.append("AND p.product_type <> 'BUNDLE' AND v.stock_qty > 0 AND v.stock_qty <= v.reorder_level ");
                break;
            case "IN":
                sql.append("AND ").append(AVAILABLE).append(" > 0 ");
                break;
            default:
                break;
        }
        sql.append("ORDER BY p.name, v.variant_id LIMIT ").append(f.limit);
        return Jdbc.list(sql.toString(), ProductDAO::mapVariant, params.toArray());
    }

    public Variant findVariant(int variantId) throws SQLException {
        return Jdbc.one(SELECT_VARIANT + "WHERE v.variant_id = ?", ProductDAO::mapVariant, variantId);
    }

    /** Exact barcode or SKU match (what a barcode scanner sends). */
    public Variant findByCode(String code) throws SQLException {
        return Jdbc.one(SELECT_VARIANT + "WHERE (v.barcode = ? OR v.sku = ?) ORDER BY v.barcode = ? DESC LIMIT 1",
                ProductDAO::mapVariant, code.trim(), code.trim(), code.trim());
    }

    /** Finds the variant owning a serial number that is in stock. */
    public Variant findBySerial(String serial) throws SQLException {
        return Jdbc.one(SELECT_VARIANT + "WHERE v.variant_id = (SELECT s.variant_id FROM product_serials s "
                + "WHERE s.serial_no = ? AND s.status = 'IN_STOCK' LIMIT 1)", ProductDAO::mapVariant, serial.trim());
    }

    public List<Variant> variantsOf(int productId) throws SQLException {
        return Jdbc.list(SELECT_VARIANT + "WHERE v.product_id = ? ORDER BY v.variant_id", ProductDAO::mapVariant, productId);
    }

    public Product getProduct(int productId) throws SQLException {
        Product p = Jdbc.one("SELECT * FROM products WHERE product_id = ?", rs -> {
            Product x = new Product();
            x.productId = rs.getInt("product_id");
            x.name = rs.getString("name");
            x.description = rs.getString("description");
            x.categoryId = Jdbc.intOrNull(rs, "category_id");
            x.brandId = Jdbc.intOrNull(rs, "brand_id");
            x.unitId = rs.getInt("unit_id");
            x.productType = rs.getString("product_type");
            x.trackBatch = rs.getBoolean("track_batch");
            x.trackSerial = rs.getBoolean("track_serial");
            x.vatPercent = rs.getBigDecimal("vat_percent");
            x.discountType = rs.getString("discount_type");
            x.discountValue = rs.getBigDecimal("discount_value");
            x.imagePath = rs.getString("image_path");
            x.active = rs.getBoolean("active");
            return x;
        }, productId);
        if (p == null) {
            return null;
        }
        p.variants = variantsOf(productId);
        if (p.isBundle() && !p.variants.isEmpty()) {
            p.bundleItems = bundleItems(p.variants.get(0).variantId);
        }
        return p;
    }

    public List<BundleItem> bundleItems(int bundleVariantId) throws SQLException {
        return Jdbc.list("SELECT bi.*, v.sku, p.name, CONCAT_WS(' / ', v.size, v.colour, v.weight) AS lbl "
                + "FROM bundle_items bi JOIN product_variants v ON v.variant_id = bi.component_variant_id "
                + "JOIN products p ON p.product_id = v.product_id WHERE bi.bundle_variant_id = ? ORDER BY bi.bundle_item_id", rs -> {
                    BundleItem b = new BundleItem();
                    b.componentVariantId = rs.getInt("component_variant_id");
                    String lbl = rs.getString("lbl");
                    b.componentName = rs.getString("name") + (lbl == null || lbl.isBlank() ? "" : " (" + lbl + ")");
                    b.componentSku = rs.getString("sku");
                    b.quantity = rs.getBigDecimal("quantity");
                    return b;
                }, bundleVariantId);
    }

    // ------------------------------------------------------------------ save

    /** Inserts or updates a product with all its variants (and bundle components). */
    public void save(Product p) throws SQLException {
        validate(p);
        Database.inTransaction(con -> {
            if (p.productId == 0) {
                p.productId = Jdbc.insert(con, "INSERT INTO products (name, description, category_id, brand_id, unit_id, "
                        + "product_type, track_batch, track_serial, vat_percent, discount_type, discount_value, image_path, active) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        p.name.trim(), p.description, p.categoryId, p.brandId, p.unitId, p.productType, p.trackBatch,
                        p.trackSerial, p.vatPercent, p.discountType, p.discountValue, p.imagePath, p.active);
            } else {
                Jdbc.update(con, "UPDATE products SET name=?, description=?, category_id=?, brand_id=?, unit_id=?, "
                        + "product_type=?, track_batch=?, track_serial=?, vat_percent=?, discount_type=?, discount_value=?, "
                        + "image_path=?, active=? WHERE product_id=?",
                        p.name.trim(), p.description, p.categoryId, p.brandId, p.unitId, p.productType, p.trackBatch,
                        p.trackSerial, p.vatPercent, p.discountType, p.discountValue, p.imagePath, p.active, p.productId);
            }

            // Variants removed in the editor: delete if never used, otherwise deactivate.
            Set<Integer> keep = new HashSet<>();
            for (Variant v : p.variants) {
                if (v.variantId != 0) {
                    keep.add(v.variantId);
                }
            }
            List<Integer> existing = Jdbc.list(con, "SELECT variant_id FROM product_variants WHERE product_id=?",
                    rs -> rs.getInt(1), p.productId);
            for (Integer id : existing) {
                if (!keep.contains(id)) {
                    removeVariant(con, id);
                }
            }

            for (Variant v : p.variants) {
                String sku = v.sku == null || v.sku.isBlank() ? null : v.sku.trim().toUpperCase();
                String barcode = v.barcode == null || v.barcode.isBlank() ? null : v.barcode.trim();
                checkUnique(con, "sku", sku, v.variantId);
                checkUnique(con, "barcode", barcode, v.variantId);
                if (v.variantId == 0) {
                    v.variantId = Jdbc.insert(con, "INSERT INTO product_variants (product_id, size, colour, weight, sku, barcode, "
                            + "purchase_price, retail_price, wholesale_price, wholesale_min_qty, reorder_level, active) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                            p.productId, blank(v.size), blank(v.colour), blank(v.weight),
                            sku == null ? "TMP-" + System.nanoTime() : sku, barcode, v.purchasePrice, v.retailPrice,
                            v.wholesalePrice, v.wholesaleMinQty, v.reorderLevel, v.active);
                    if (sku == null) {
                        sku = generateSku(p.name, v.variantId);
                        Jdbc.update(con, "UPDATE product_variants SET sku=? WHERE variant_id=?", sku, v.variantId);
                    }
                    if (barcode == null) {
                        barcode = BarcodeUtil.internalEan13(v.variantId);
                        if (Jdbc.one(con, "SELECT 1 FROM product_variants WHERE barcode=?", rs -> 1, barcode) == null) {
                            Jdbc.update(con, "UPDATE product_variants SET barcode=? WHERE variant_id=?", barcode, v.variantId);
                        }
                    }
                    if (!p.isBundle() && Money.pos(v.openingStock)) {
                        if (p.trackBatch || p.trackSerial) {
                            throw new AppException("Opening stock for batch/serial tracked products must be entered "
                                    + "through Stock → Stock In (batch numbers / serials are required).");
                        }
                        InventoryService.applyChange(con, v.variantId, null, v.openingStock, "OPENING",
                                v.purchasePrice, "OPENING", null, "Opening stock", Session.userId());
                    }
                } else {
                    Jdbc.update(con, "UPDATE product_variants SET size=?, colour=?, weight=?, sku=?, barcode=?, "
                            + "purchase_price=?, retail_price=?, wholesale_price=?, wholesale_min_qty=?, reorder_level=?, active=? "
                            + "WHERE variant_id=?",
                            blank(v.size), blank(v.colour), blank(v.weight), sku == null ? generateSku(p.name, v.variantId) : sku,
                            barcode, v.purchasePrice, v.retailPrice, v.wholesalePrice, v.wholesaleMinQty, v.reorderLevel,
                            v.active, v.variantId);
                }
            }

            if (p.isBundle()) {
                int bundleVariant = p.variants.get(0).variantId;
                Jdbc.update(con, "DELETE FROM bundle_items WHERE bundle_variant_id=?", bundleVariant);
                for (BundleItem bi : p.bundleItems) {
                    if (bi.componentVariantId == bundleVariant) {
                        throw new AppException("A bundle cannot contain itself.");
                    }
                    Jdbc.insert(con, "INSERT INTO bundle_items (bundle_variant_id, component_variant_id, quantity) VALUES (?,?,?)",
                            bundleVariant, bi.componentVariantId, bi.quantity);
                }
            }
            return null;
        });
    }

    private void validate(Product p) {
        if (p.name == null || p.name.isBlank()) {
            throw new AppException("Product name is required.");
        }
        if (p.variants.isEmpty()) {
            throw new AppException("A product needs at least one variant (SKU).");
        }
        if (p.isBundle()) {
            if (p.variants.size() != 1) {
                throw new AppException("A bundle/combo has exactly one variant.");
            }
            if (p.bundleItems.size() < 2) {
                throw new AppException("A bundle/combo needs at least 2 component items.");
            }
            if (p.trackBatch || p.trackSerial) {
                throw new AppException("Bundles cannot track batches or serials themselves; their components do.");
            }
        }
        if (p.trackBatch && p.trackSerial) {
            throw new AppException("Choose either batch tracking or serial tracking, not both.");
        }
        if (p.vatPercent.signum() < 0 || p.vatPercent.compareTo(Money.HUNDRED) > 0) {
            throw new AppException("VAT must be between 0 and 100%.");
        }
        if (p.discountValue.signum() < 0 || ("PERCENT".equals(p.discountType) && p.discountValue.compareTo(Money.HUNDRED) > 0)) {
            throw new AppException("Discount is invalid.");
        }
        Set<String> skus = new HashSet<>();
        Set<String> barcodes = new HashSet<>();
        for (Variant v : p.variants) {
            if (v.retailPrice.signum() <= 0) {
                throw new AppException("Retail price must be greater than 0 for every variant.");
            }
            if (v.purchasePrice.signum() < 0 || v.wholesalePrice.signum() < 0 || v.reorderLevel.signum() < 0
                    || v.wholesaleMinQty.signum() < 0 || v.openingStock.signum() < 0) {
                throw new AppException("Prices and quantities cannot be negative.");
            }
            if (v.sku != null && !v.sku.isBlank() && !skus.add(v.sku.trim().toUpperCase())) {
                throw new AppException("SKU " + v.sku + " is used twice.");
            }
            if (v.barcode != null && !v.barcode.isBlank() && !barcodes.add(v.barcode.trim())) {
                throw new AppException("Barcode " + v.barcode + " is used twice.");
            }
        }
        for (BundleItem bi : p.bundleItems) {
            if (!Money.pos(bi.quantity)) {
                throw new AppException("Bundle component quantities must be greater than 0.");
            }
        }
    }

    private void checkUnique(Connection con, String column, String value, int variantId) throws SQLException {
        if (value == null) {
            return;
        }
        String other = Jdbc.one(con, "SELECT p.name FROM product_variants v JOIN products p ON p.product_id=v.product_id "
                + "WHERE v." + column + " = ? AND v.variant_id <> ?", rs -> rs.getString(1), value, variantId);
        if (other != null) {
            throw new AppException(column.toUpperCase() + " \"" + value + "\" is already used by \"" + other + "\".");
        }
    }

    private void removeVariant(Connection con, int variantId) throws SQLException {
        boolean used = Jdbc.one(con, "SELECT 1 FROM sale_items WHERE variant_id=? LIMIT 1", rs -> 1, variantId) != null
                || Jdbc.one(con, "SELECT 1 FROM bundle_items WHERE component_variant_id=? LIMIT 1", rs -> 1, variantId) != null
                || Jdbc.decimal(con, "SELECT stock_qty FROM product_variants WHERE variant_id=?", variantId).signum() > 0;
        if (used) {
            Jdbc.update(con, "UPDATE product_variants SET active=0 WHERE variant_id=?", variantId);
        } else {
            Jdbc.update(con, "DELETE FROM product_variants WHERE variant_id=?", variantId);
        }
    }

    public static String generateSku(String productName, int variantId) {
        StringBuilder prefix = new StringBuilder();
        for (String w : productName.toUpperCase().replaceAll("[^A-Z0-9 ]", " ").trim().split("\\s+")) {
            if (!w.isEmpty() && prefix.length() < 6) {
                prefix.append(w, 0, Math.min(3, w.length()));
            }
        }
        if (prefix.length() == 0) {
            prefix.append("ITEM");
        }
        return prefix + "-" + String.format("%05d", variantId);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ------------------------------------------------------------------ status / delete

    public void setActive(int productId, boolean active) throws SQLException {
        Jdbc.update("UPDATE products SET active=? WHERE product_id=?", active, productId);
    }

    public void setVariantActive(int variantId, boolean active) throws SQLException {
        Jdbc.update("UPDATE product_variants SET active=? WHERE variant_id=?", active, variantId);
    }

    public void delete(int productId) throws SQLException {
        if (Jdbc.count("SELECT COUNT(*) FROM sale_items si JOIN product_variants v ON v.variant_id=si.variant_id "
                + "WHERE v.product_id=?", productId) > 0) {
            throw new AppException("This product has sales history and cannot be deleted.\nDeactivate it instead so it "
                    + "no longer appears in the POS.");
        }
        if (Jdbc.count("SELECT COUNT(*) FROM bundle_items bi JOIN product_variants v ON v.variant_id=bi.component_variant_id "
                + "WHERE v.product_id=?", productId) > 0) {
            throw new AppException("This product is a component of a bundle/combo. Remove it from the bundle first.");
        }
        Jdbc.update("DELETE FROM products WHERE product_id=?", productId);
    }

    public void updateImage(int productId, String path) throws SQLException {
        Jdbc.update("UPDATE products SET image_path=? WHERE product_id=?", path, productId);
    }

    // ------------------------------------------------------------------ batches & serials

    static Batch mapBatch(ResultSet rs) throws SQLException {
        Batch b = new Batch();
        b.batchId = rs.getInt("batch_id");
        b.variantId = rs.getInt("variant_id");
        b.batchNo = rs.getString("batch_no");
        b.manufactureDate = Jdbc.date(rs, "manufacture_date");
        b.expiryDate = Jdbc.date(rs, "expiry_date");
        b.qtyReceived = rs.getBigDecimal("qty_received");
        b.qtyRemaining = rs.getBigDecimal("qty_remaining");
        b.unitCost = rs.getBigDecimal("unit_cost");
        b.productName = rs.getString("product_name");
        b.variantLabel = rs.getString("lbl");
        b.sku = rs.getString("sku");
        return b;
    }

    private static final String SELECT_BATCH = "SELECT b.*, p.name AS product_name, v.sku, "
            + "CONCAT_WS(' / ', v.size, v.colour, v.weight) AS lbl FROM product_batches b "
            + "JOIN product_variants v ON v.variant_id = b.variant_id JOIN products p ON p.product_id = v.product_id ";

    public List<Batch> batches(int variantId, boolean withStockOnly) throws SQLException {
        return Jdbc.list(SELECT_BATCH + "WHERE b.variant_id=? " + (withStockOnly ? "AND b.qty_remaining > 0 " : "")
                + "ORDER BY b.expiry_date IS NULL, b.expiry_date, b.batch_id", ProductDAO::mapBatch, variantId);
    }

    /** All batches with stock; filter: ALL, EXPIRED, EXPIRING (within days). */
    public List<Batch> allBatches(String filter, int days, String text) throws SQLException {
        String where = "WHERE b.qty_remaining > 0 ";
        if ("EXPIRED".equals(filter)) {
            where += "AND b.expiry_date < CURDATE() ";
        } else if ("EXPIRING".equals(filter)) {
            where += "AND b.expiry_date >= CURDATE() AND b.expiry_date <= CURDATE() + INTERVAL " + days + " DAY ";
        }
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        return Jdbc.list(SELECT_BATCH + where + "AND (p.name LIKE ? OR v.sku LIKE ? OR b.batch_no LIKE ?) "
                + "ORDER BY b.expiry_date IS NULL, b.expiry_date", ProductDAO::mapBatch, like, like, like);
    }

    public List<String> serialsInStock(int variantId) throws SQLException {
        return Jdbc.list("SELECT serial_no FROM product_serials WHERE variant_id=? AND status='IN_STOCK' ORDER BY serial_no",
                rs -> rs.getString(1), variantId);
    }

    /** Serial lookup: serial, product, status, invoice. */
    public Jdbc.TableData serialLookup(String text) throws SQLException {
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        return Jdbc.table("SELECT s.serial_no AS `Serial / IMEI`, p.name AS `Product`, v.sku AS `SKU`, s.status AS `Status`, "
                + "sa.invoice_no AS `Invoice`, sa.created_at AS `Sold On`, c.name AS `Customer` "
                + "FROM product_serials s JOIN product_variants v ON v.variant_id=s.variant_id "
                + "JOIN products p ON p.product_id=v.product_id "
                + "LEFT JOIN sale_items si ON si.sale_item_id = s.sale_item_id "
                + "LEFT JOIN sales sa ON sa.sale_id = si.sale_id LEFT JOIN customers c ON c.customer_id = sa.customer_id "
                + "WHERE s.serial_no LIKE ? OR p.name LIKE ? ORDER BY p.name, s.serial_no LIMIT 500", like, like);
    }
}
