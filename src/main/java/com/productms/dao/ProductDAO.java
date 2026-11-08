package com.productms.dao;

import com.productms.db.Database;
import com.productms.db.Jdbc;
import com.productms.model.Batch;
import com.productms.model.BundleItem;
import com.productms.model.Product;
import com.productms.model.Variant;
import com.productms.service.AuditService;
import com.productms.service.InventoryService;
import com.productms.util.AppException;
import com.productms.util.BarcodeUtil;
import com.productms.util.Money;
import com.productms.util.Session;
import com.productms.util.Settings;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Products, variants, bundles, price tiers, packs, extra barcodes, images, price / cost history,
 * scheduled prices, batches and serial numbers.
 */
public class ProductDAO {

    // ================================================================== availability SQL

    /** Sellable quantity of a non-bundle variant: unexpired batches (minus the near-expiry block) less reservations. */
    private static String availOf(String v, String p) {
        int block = Math.max(0, Settings.getInt("near_expiry_block_days", 0));
        return "(CASE WHEN " + p + ".track_batch = 1 THEN (SELECT COALESCE(SUM(b.qty_remaining),0) FROM product_batches b "
                + "WHERE b.variant_id = " + v + ".variant_id AND (b.expiry_date IS NULL OR b.expiry_date >= CURDATE() + INTERVAL "
                + block + " DAY)) ELSE " + v + ".stock_qty END - " + reservedOf(v) + ")";
    }

    private static String reservedOf(String v) {
        return "(SELECT COALESCE(SUM(r.quantity),0) FROM stock_reservations r WHERE r.variant_id = " + v
                + ".variant_id AND r.expires_at > NOW())";
    }

    private static String available() {
        return "(CASE WHEN p.product_type = 'BUNDLE' THEN COALESCE((SELECT MIN(FLOOR(" + availOf("c", "cp") + " / bi.quantity)) "
                + "FROM bundle_items bi JOIN product_variants c ON c.variant_id = bi.component_variant_id "
                + "JOIN products cp ON cp.product_id = c.product_id WHERE bi.bundle_variant_id = v.variant_id AND bi.optional = 0), 0) "
                + "ELSE " + availOf("v", "p") + " END)";
    }

    private static String selectVariant() {
        return "SELECT v.*, p.name AS product_name, p.description, p.product_type, p.track_batch, p.track_serial, "
                + "(p.active AND NOT p.deleted) AS product_active, p.deleted, p.vat_percent, p.discount_type, p.discount_value, "
                + "p.discount_start, p.discount_end, p.category_id, p.brand_id, p.image_path, p.tags, p.warranty_months, "
                + "p.bundle_pricing, p.bundle_discount_percent, cat.name AS cat_name, par.name AS parent_cat, "
                + "b.name AS brand_name, u.short_name, u.allow_decimal, "
                + available() + " AS available, " + reservedOf("v") + " AS reserved, "
                + "(SELECT COALESCE(SUM(si.quantity),0) FROM sale_items si JOIN sales s ON s.sale_id = si.sale_id "
                + " WHERE si.variant_id = v.variant_id AND s.status = 'COMPLETED' AND s.created_at >= NOW() - INTERVAL 30 DAY) AS sold30 "
                + "FROM product_variants v JOIN products p ON p.product_id = v.product_id "
                + "JOIN units u ON u.unit_id = p.unit_id "
                + "LEFT JOIN categories cat ON cat.category_id = p.category_id "
                + "LEFT JOIN categories par ON par.category_id = cat.parent_id "
                + "LEFT JOIN brands b ON b.brand_id = p.brand_id ";
    }

    static Variant mapVariant(ResultSet rs) throws SQLException {
        Variant v = new Variant();
        v.variantId = rs.getInt("variant_id");
        v.productId = rs.getInt("product_id");
        v.size = rs.getString("size");
        v.colour = rs.getString("colour");
        v.weight = rs.getString("weight");
        v.extraAttributes = rs.getString("extra_attributes");
        v.sku = rs.getString("sku");
        v.barcode = rs.getString("barcode");
        v.plu = rs.getString("plu");
        v.purchasePrice = rs.getBigDecimal("purchase_price");
        v.retailPrice = rs.getBigDecimal("retail_price");
        v.wholesalePrice = rs.getBigDecimal("wholesale_price");
        v.wholesaleMinQty = rs.getBigDecimal("wholesale_min_qty");
        v.mrp = rs.getBigDecimal("mrp");
        v.minPrice = rs.getBigDecimal("min_price");
        v.stockQty = rs.getBigDecimal("stock_qty");
        v.damagedQty = rs.getBigDecimal("damaged_qty");
        v.reorderLevel = rs.getBigDecimal("reorder_level");
        v.reorderQty = rs.getBigDecimal("reorder_qty");
        v.location = rs.getString("location");
        v.favourite = rs.getBoolean("is_favourite");
        v.posOrder = rs.getInt("pos_order");
        v.active = rs.getBoolean("active");
        v.productName = rs.getString("product_name");
        v.description = rs.getString("description");
        v.productType = rs.getString("product_type");
        v.trackBatch = rs.getBoolean("track_batch");
        v.trackSerial = rs.getBoolean("track_serial");
        v.productActive = rs.getBoolean("product_active");
        v.deleted = rs.getBoolean("deleted");
        v.vatPercent = rs.getBigDecimal("vat_percent");
        v.discountType = rs.getString("discount_type");
        v.discountValue = rs.getBigDecimal("discount_value");
        v.discountStart = Jdbc.date(rs, "discount_start");
        v.discountEnd = Jdbc.date(rs, "discount_end");
        v.categoryId = Jdbc.intOrNull(rs, "category_id");
        String cat = rs.getString("cat_name");
        String parent = rs.getString("parent_cat");
        v.categoryName = cat == null ? null : (parent == null ? cat : parent + " › " + cat);
        v.brandId = Jdbc.intOrNull(rs, "brand_id");
        v.brandName = rs.getString("brand_name");
        v.unitShort = rs.getString("short_name");
        v.allowDecimal = rs.getBoolean("allow_decimal");
        v.imagePath = rs.getString("image_path");
        v.tags = rs.getString("tags");
        v.warrantyMonths = rs.getInt("warranty_months");
        v.bundlePricing = rs.getString("bundle_pricing");
        v.bundleDiscountPercent = rs.getBigDecimal("bundle_discount_percent");
        BigDecimal a = rs.getBigDecimal("available");
        v.available = a == null ? BigDecimal.ZERO : Money.max(BigDecimal.ZERO, a);
        v.reservedQty = Money.nz(rs.getBigDecimal("reserved"));
        v.soldLast30 = Money.nz(rs.getBigDecimal("sold30"));
        return v;
    }

    /** Loads tiers (incl. pack prices), bundle components and editor text for the variants. */
    public void enrich(List<Variant> list) throws SQLException {
        if (list.isEmpty()) {
            return;
        }
        Map<Integer, Variant> byId = new HashMap<>();
        for (Variant v : list) {
            byId.put(v.variantId, v);
            v.tiers.clear();
            v.components.clear();
        }
        String ids = byId.keySet().stream().map(String::valueOf).collect(Collectors.joining(","));
        try (Connection con = Database.getConnection()) {
            Map<Integer, StringBuilder> tiersText = new HashMap<>();
            for (Object[] r : Jdbc.list(con, "SELECT variant_id, min_qty, price FROM price_tiers WHERE variant_id IN (" + ids
                    + ") ORDER BY min_qty", rs -> new Object[]{rs.getInt(1), rs.getBigDecimal(2), rs.getBigDecimal(3)})) {
                Variant v = byId.get((Integer) r[0]);
                v.tiers.add(new BigDecimal[]{(BigDecimal) r[1], (BigDecimal) r[2]});
                append(tiersText, v.variantId, Money.qty((BigDecimal) r[1]) + "=" + Money.qty((BigDecimal) r[2]));
            }
            Map<Integer, StringBuilder> packsText = new HashMap<>();
            for (Object[] r : Jdbc.list(con, "SELECT variant_id, name, quantity, barcode, price FROM variant_packs WHERE variant_id IN ("
                    + ids + ") ORDER BY quantity", rs -> new Object[]{rs.getInt(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getString(4), rs.getBigDecimal(5)})) {
                Variant v = byId.get((Integer) r[0]);
                BigDecimal q = (BigDecimal) r[2];
                if (r[4] != null && q.signum() > 0) {
                    v.tiers.add(new BigDecimal[]{q, ((BigDecimal) r[4]).divide(q, 2, java.math.RoundingMode.HALF_UP)});
                }
                append(packsText, v.variantId, r[1] + "=" + Money.qty(q) + (r[4] == null ? "" : "@" + Money.qty((BigDecimal) r[4]))
                        + (r[3] == null ? "" : "#" + r[3]));
            }
            Map<Integer, StringBuilder> codes = new HashMap<>();
            for (Object[] r : Jdbc.list(con, "SELECT variant_id, barcode FROM variant_barcodes WHERE variant_id IN (" + ids + ")",
                    rs -> new Object[]{rs.getInt(1), rs.getString(2)})) {
                append(codes, (Integer) r[0], (String) r[1]);
            }
            for (Variant v : list) {
                v.tiers.sort(Comparator.comparing(t -> t[0]));
                v.tiersText = text(tiersText, v.variantId);
                v.packsText = text(packsText, v.variantId);
                v.barcodesText = text(codes, v.variantId);
                if (v.isBundle()) {
                    v.components = bundleItems(con, v.variantId);
                }
            }
        }
    }

    private static void append(Map<Integer, StringBuilder> m, int id, String s) {
        StringBuilder sb = m.computeIfAbsent(id, k -> new StringBuilder());
        if (sb.length() > 0) {
            sb.append("; ");
        }
        sb.append(s);
    }

    private static String text(Map<Integer, StringBuilder> m, int id) {
        StringBuilder sb = m.get(id);
        return sb == null ? null : sb.toString();
    }

    // ================================================================== search

    /** Filter for {@link #search(Filter)}. */
    public static class Filter {
        public String text;
        public Integer categoryId;
        public Integer brandId;
        /** ALL, ACTIVE, INACTIVE, DELETED */
        public String status = "ALL";
        /** ALL, IN, LOW, OUT, DAMAGED */
        public String stock = "ALL";
        /** NAME, PRICE_ASC, PRICE_DESC, STOCK_ASC, STOCK_DESC, NEWEST, BEST_SELLING, LOCATION, CUSTOM */
        public String sort = "NAME";
        public boolean sellableOnly;
        public boolean excludeBundles;
        public boolean favouritesOnly;
        public int limit = 1000;
        public boolean enrich = true;
    }

    public List<Variant> search(Filter f) throws SQLException {
        List<Variant> result = query(f, true);
        if (result.isEmpty() && f.text != null && f.text.trim().length() >= 3) {
            // fuzzy fallback: tolerate typos ("keybord", "samsng")
            Filter loose = copy(f);
            loose.text = null;
            loose.limit = 5000;
            String[] tokens = tokens(f.text);
            result = query(loose, false).stream().filter(v -> fuzzyMatch(v, tokens)).limit(f.limit).collect(Collectors.toList());
        }
        if (f.enrich) {
            enrich(result);
        }
        return result;
    }

    private List<Variant> query(Filter f, boolean useText) throws SQLException {
        StringBuilder sql = new StringBuilder(selectVariant()).append("WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (useText && f.text != null && !f.text.isBlank()) {
            String exact = f.text.trim();
            sql.append("AND (v.barcode = ? OR v.sku = ? OR EXISTS (SELECT 1 FROM variant_barcodes x WHERE x.variant_id = v.variant_id AND x.barcode = ?) OR (1=1 ");
            params.add(exact);
            params.add(exact);
            params.add(exact);
            for (String token : tokens(exact)) {
                sql.append("AND CONCAT_WS(' ', p.name, v.sku, b.name, v.size, v.colour, v.weight, v.extra_attributes, p.tags, "
                        + "cat.name, par.name, v.location) LIKE ? ");
                params.add("%" + token + "%");
            }
            sql.append(")) ");
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
        if ("DELETED".equals(f.status)) {
            sql.append("AND p.deleted = 1 ");
        } else {
            sql.append("AND p.deleted = 0 ");
            if (f.sellableOnly || "ACTIVE".equals(f.status)) {
                sql.append("AND v.active = 1 AND p.active = 1 ");
            } else if ("INACTIVE".equals(f.status)) {
                sql.append("AND (v.active = 0 OR p.active = 0) ");
            }
        }
        if (f.excludeBundles) {
            sql.append("AND p.product_type <> 'BUNDLE' ");
        }
        if (f.favouritesOnly) {
            sql.append("AND v.is_favourite = 1 ");
        }
        switch (f.stock) {
            case "OUT":
                sql.append("AND ").append(available()).append(" <= 0 ");
                break;
            case "LOW":
                sql.append("AND p.product_type <> 'BUNDLE' AND v.stock_qty > 0 AND v.stock_qty <= v.reorder_level ");
                break;
            case "IN":
                sql.append("AND ").append(available()).append(" > 0 ");
                break;
            case "DAMAGED":
                sql.append("AND v.damaged_qty > 0 ");
                break;
            default:
                break;
        }
        sql.append("ORDER BY ").append(orderBy(f.sort)).append(" LIMIT ").append(f.limit);
        return Jdbc.list(sql.toString(), ProductDAO::mapVariant, params.toArray());
    }

    private static String orderBy(String sort) {
        switch (sort == null ? "NAME" : sort) {
            case "PRICE_ASC": return "v.retail_price, p.name";
            case "PRICE_DESC": return "v.retail_price DESC, p.name";
            case "STOCK_ASC": return "v.stock_qty, p.name";
            case "STOCK_DESC": return "v.stock_qty DESC, p.name";
            case "NEWEST": return "p.created_at DESC, v.variant_id DESC";
            case "BEST_SELLING": return "sold30 DESC, p.name";
            case "LOCATION": return "v.location IS NULL, v.location, p.name";
            case "CUSTOM": return "v.is_favourite DESC, v.pos_order = 0, v.pos_order, p.name";
            default: return "p.name, v.variant_id";
        }
    }

    private static Filter copy(Filter f) {
        Filter c = new Filter();
        c.text = f.text;
        c.categoryId = f.categoryId;
        c.brandId = f.brandId;
        c.status = f.status;
        c.stock = f.stock;
        c.sort = f.sort;
        c.sellableOnly = f.sellableOnly;
        c.excludeBundles = f.excludeBundles;
        c.favouritesOnly = f.favouritesOnly;
        c.limit = f.limit;
        c.enrich = false;
        return c;
    }

    private static String[] tokens(String text) {
        return text.trim().toLowerCase(Locale.ROOT).split("\\s+");
    }

    /** Every token must be close (edit distance 1-2) to some word of the product. */
    static boolean fuzzyMatch(Variant v, String[] tokens) {
        String hay = (v.productName + " " + v.sku + " " + nz(v.brandName) + " " + v.label() + " " + nz(v.tags))
                .toLowerCase(Locale.ROOT);
        String[] words = hay.split("[^a-z0-9]+");
        for (String t : tokens) {
            boolean found = false;
            int allowed = t.length() >= 7 ? 2 : (t.length() >= 4 ? 1 : 0);
            for (String w : words) {
                if (w.contains(t) || (allowed > 0 && Math.abs(w.length() - t.length()) <= allowed && distance(w, t) <= allowed)
                        || (allowed > 0 && w.length() > t.length() && distance(w.substring(0, t.length()), t) <= allowed)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ================================================================== lookups

    public Variant findVariant(int variantId) throws SQLException {
        Variant v = Jdbc.one(selectVariant() + "WHERE v.variant_id = ?", ProductDAO::mapVariant, variantId);
        if (v != null) {
            enrich(List.of(v));
        }
        return v;
    }

    /** What a scanned code resolved to: the variant and how many base units it stands for. */
    public static class CodeMatch {
        public Variant variant;
        public BigDecimal quantity = BigDecimal.ONE;
        public String packName;
    }

    /** Barcode, SKU, extra barcode or pack barcode (what a barcode scanner sends). */
    public CodeMatch findByCode(String code) throws SQLException {
        String c = code.trim();
        Integer id = Jdbc.one("SELECT variant_id FROM product_variants WHERE barcode = ? OR sku = ? ORDER BY barcode = ? DESC LIMIT 1",
                rs -> rs.getInt(1), c, c, c);
        CodeMatch m = new CodeMatch();
        if (id == null) {
            id = Jdbc.one("SELECT variant_id FROM variant_barcodes WHERE barcode = ?", rs -> rs.getInt(1), c);
        }
        if (id == null) {
            Object[] pack = Jdbc.one("SELECT variant_id, quantity, name FROM variant_packs WHERE barcode = ?",
                    rs -> new Object[]{rs.getInt(1), rs.getBigDecimal(2), rs.getString(3)}, c);
            if (pack != null) {
                id = (Integer) pack[0];
                m.quantity = (BigDecimal) pack[1];
                m.packName = (String) pack[2];
            }
        }
        if (id == null) {
            return null;
        }
        m.variant = findVariant(id);
        return m;
    }

    public Variant findByPlu(String plu) throws SQLException {
        Integer id = Jdbc.one("SELECT variant_id FROM product_variants WHERE plu = ?", rs -> rs.getInt(1), plu);
        return id == null ? null : findVariant(id);
    }

    public Variant findBySerial(String serial) throws SQLException {
        Integer id = Jdbc.one("SELECT variant_id FROM product_serials WHERE serial_no = ? AND status = 'IN_STOCK' LIMIT 1",
                rs -> rs.getInt(1), serial.trim());
        return id == null ? null : findVariant(id);
    }

    public List<Variant> variantsOf(int productId) throws SQLException {
        List<Variant> l = Jdbc.list(selectVariant() + "WHERE v.product_id = ? ORDER BY v.variant_id", ProductDAO::mapVariant, productId);
        enrich(l);
        return l;
    }

    /** Pack definitions of a variant: [name, quantity]. */
    public List<Object[]> packs(int variantId) throws SQLException {
        return Jdbc.list("SELECT name, quantity FROM variant_packs WHERE variant_id = ? ORDER BY quantity",
                rs -> new Object[]{rs.getString(1), rs.getBigDecimal(2)}, variantId);
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
            x.discountStart = Jdbc.date(rs, "discount_start");
            x.discountEnd = Jdbc.date(rs, "discount_end");
            x.imagePath = rs.getString("image_path");
            x.tags = rs.getString("tags");
            x.attributes = rs.getString("attributes");
            x.warrantyMonths = rs.getInt("warranty_months");
            x.bundlePricing = rs.getString("bundle_pricing");
            x.bundleDiscountPercent = rs.getBigDecimal("bundle_discount_percent");
            x.active = rs.getBoolean("active");
            x.deleted = rs.getBoolean("deleted");
            return x;
        }, productId);
        if (p == null) {
            return null;
        }
        p.variants = variantsOf(productId);
        p.images = images(productId);
        if (p.images.isEmpty() && p.imagePath != null) {
            p.images.add(p.imagePath);
        }
        if (p.isBundle() && !p.variants.isEmpty()) {
            p.bundleItems = bundleItems(p.variants.get(0).variantId);
        }
        return p;
    }

    public List<String> images(int productId) throws SQLException {
        return Jdbc.list("SELECT path FROM product_images WHERE product_id = ? ORDER BY sort_order, image_id",
                rs -> rs.getString(1), productId);
    }

    public List<BundleItem> bundleItems(int bundleVariantId) throws SQLException {
        try (Connection con = Database.getConnection()) {
            return bundleItems(con, bundleVariantId);
        }
    }

    private List<BundleItem> bundleItems(Connection con, int bundleVariantId) throws SQLException {
        return Jdbc.list(con, "SELECT bi.*, v.sku, v.retail_price, p.name, CONCAT_WS(' / ', v.size, v.colour, v.weight) AS lbl "
                + "FROM bundle_items bi JOIN product_variants v ON v.variant_id = bi.component_variant_id "
                + "JOIN products p ON p.product_id = v.product_id WHERE bi.bundle_variant_id = ? ORDER BY bi.bundle_item_id", rs -> {
                    BundleItem b = new BundleItem();
                    b.componentVariantId = rs.getInt("component_variant_id");
                    String lbl = rs.getString("lbl");
                    b.componentName = rs.getString("name") + (lbl == null || lbl.isBlank() ? "" : " (" + lbl + ")");
                    b.componentSku = rs.getString("sku");
                    b.quantity = rs.getBigDecimal("quantity");
                    b.optional = rs.getBoolean("optional");
                    b.unitPrice = rs.getBigDecimal("retail_price");
                    return b;
                }, bundleVariantId);
    }

    // ================================================================== duplicates

    /** Products that look like duplicates of the given name / codes (for a warning before saving). */
    public List<String> findSimilar(Product p) throws SQLException {
        List<String> out = new ArrayList<>();
        String norm = normalizeName(p.name);
        for (Object[] r : Jdbc.list("SELECT product_id, name FROM products WHERE deleted = 0 AND product_id <> ?",
                rs -> new Object[]{rs.getInt(1), rs.getString(2)}, p.productId)) {
            String other = normalizeName((String) r[1]);
            if (other.equals(norm) || (norm.length() >= 6 && distance(other, norm) <= 2)) {
                out.add("Similar name: \"" + r[1] + "\" (product #" + r[0] + ")");
            }
        }
        for (Variant v : p.variants) {
            for (String code : new String[]{v.sku, v.barcode}) {
                if (code == null || code.isBlank()) {
                    continue;
                }
                String owner = codeOwner(null, code.trim(), v.variantId);
                if (owner != null) {
                    out.add("Code " + code + " already belongs to " + owner);
                }
            }
        }
        return out;
    }

    private static String normalizeName(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    // ================================================================== save

    /** Inserts or updates a product with its variants, tiers, packs, extra barcodes, images and bundle parts. */
    public void save(Product p) throws SQLException {
        Database.inTransaction(con -> {
            save(con, p);
            return null;
        });
    }

    /** Same as {@link #save(Product)} inside the caller's transaction (used by the all-or-nothing import). */
    public void save(Connection con, Product p) throws SQLException {
        validate(p);
        Map<Integer, Variant> before = new HashMap<>();
        if (p.productId != 0) {
            for (Variant v : variantsOf(p.productId)) {
                before.put(v.variantId, v);
            }
        }
        boolean isNew = p.productId == 0;
        {
            String primaryImage = p.images.isEmpty() ? null : p.images.get(0);
            Object[] fields = {p.name.trim(), p.description, p.categoryId, p.brandId, p.unitId, p.productType, p.trackBatch,
                p.trackSerial, p.vatPercent, p.discountType, p.discountValue, p.discountStart, p.discountEnd, primaryImage,
                blank(p.tags), blank(p.attributes), p.warrantyMonths, p.bundlePricing, p.bundleDiscountPercent, p.active};
            if (isNew) {
                p.productId = Jdbc.insert(con, "INSERT INTO products (name, description, category_id, brand_id, unit_id, product_type, "
                        + "track_batch, track_serial, vat_percent, discount_type, discount_value, discount_start, discount_end, image_path, "
                        + "tags, attributes, warranty_months, bundle_pricing, bundle_discount_percent, active) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", fields);
            } else {
                Object[] withId = java.util.Arrays.copyOf(fields, fields.length + 1);
                withId[fields.length] = p.productId;
                Jdbc.update(con, "UPDATE products SET name=?, description=?, category_id=?, brand_id=?, unit_id=?, product_type=?, "
                        + "track_batch=?, track_serial=?, vat_percent=?, discount_type=?, discount_value=?, discount_start=?, discount_end=?, "
                        + "image_path=?, tags=?, attributes=?, warranty_months=?, bundle_pricing=?, bundle_discount_percent=?, active=? "
                        + "WHERE product_id=?", withId);
            }
            Jdbc.update(con, "DELETE FROM product_images WHERE product_id=?", p.productId);
            for (int i = 0; i < p.images.size(); i++) {
                Jdbc.insert(con, "INSERT INTO product_images (product_id, path, sort_order) VALUES (?,?,?)", p.productId, p.images.get(i), i);
            }

            Set<Integer> keep = new HashSet<>();
            for (Variant v : p.variants) {
                if (v.variantId != 0) {
                    keep.add(v.variantId);
                }
            }
            for (Integer id : before.keySet()) {
                if (!keep.contains(id)) {
                    removeVariant(con, id);
                }
            }

            String reason = p.priceChangeReason == null || p.priceChangeReason.isBlank() ? "Edited in product editor"
                    : p.priceChangeReason.trim();
            for (Variant v : p.variants) {
                String sku = v.sku == null || v.sku.isBlank() ? null : v.sku.trim().toUpperCase(Locale.ROOT);
                String barcode = v.barcode == null || v.barcode.isBlank() ? null : v.barcode.trim();
                String plu = v.plu == null || v.plu.isBlank() ? null : v.plu.trim();
                checkCodeFree(con, sku, v.variantId, "SKU");
                checkCodeFree(con, barcode, v.variantId, "Barcode");
                Object[] vf = {blank(v.size), blank(v.colour), blank(v.weight), blank(v.extraAttributes), barcode, plu,
                    v.purchasePrice, v.retailPrice, v.wholesalePrice, v.wholesaleMinQty, v.mrp, v.minPrice, v.reorderLevel,
                    v.reorderQty, blank(v.location), v.active};
                if (v.variantId == 0) {
                    Object[] ins = new Object[vf.length + 2];
                    ins[0] = p.productId;
                    ins[1] = sku == null ? "TMP-" + System.nanoTime() : sku;
                    System.arraycopy(vf, 0, ins, 2, vf.length);
                    v.variantId = Jdbc.insert(con, "INSERT INTO product_variants (product_id, sku, size, colour, weight, extra_attributes, "
                            + "barcode, plu, purchase_price, retail_price, wholesale_price, wholesale_min_qty, mrp, min_price, reorder_level, "
                            + "reorder_qty, location, active) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", ins);
                    if (sku == null) {
                        Jdbc.update(con, "UPDATE product_variants SET sku=? WHERE variant_id=?", generateSku(con, p, v), v.variantId);
                    }
                    if (barcode == null) {
                        String gen = "CODE128".equals(Settings.get("barcode_type", "EAN13"))
                                ? Jdbc.one(con, "SELECT sku FROM product_variants WHERE variant_id=?", rs -> rs.getString(1), v.variantId)
                                : BarcodeUtil.internalEan13(v.variantId);
                        if (codeOwner(con, gen, v.variantId) == null) {
                            Jdbc.update(con, "UPDATE product_variants SET barcode=? WHERE variant_id=?", gen, v.variantId);
                        }
                    }
                    history(con, v.variantId, "RETAIL", null, v.retailPrice, "New product");
                    if (!p.isBundle() && Money.pos(v.openingStock)) {
                        if (p.trackBatch || p.trackSerial) {
                            throw new AppException("Opening stock for batch/serial tracked products must be entered "
                                    + "through Stock → Stock In (batch numbers / serials are required).");
                        }
                        InventoryService.applyChange(con, v.variantId, null, v.openingStock, "OPENING",
                                v.purchasePrice, "OPENING", null, "Opening stock", Session.userId());
                    }
                } else {
                    Object[] upd = new Object[vf.length + 2];
                    upd[0] = sku == null ? generateSku(con, p, v) : sku;
                    System.arraycopy(vf, 0, upd, 1, vf.length);
                    upd[vf.length + 1] = v.variantId;
                    Jdbc.update(con, "UPDATE product_variants SET sku=?, size=?, colour=?, weight=?, extra_attributes=?, barcode=?, plu=?, "
                            + "purchase_price=?, retail_price=?, wholesale_price=?, wholesale_min_qty=?, mrp=?, min_price=?, reorder_level=?, "
                            + "reorder_qty=?, location=?, active=? WHERE variant_id=?", upd);
                    Variant old = before.get(v.variantId);
                    if (old != null) {
                        history(con, v.variantId, "RETAIL", old.retailPrice, v.retailPrice, reason);
                        history(con, v.variantId, "WHOLESALE", old.wholesalePrice, v.wholesalePrice, reason);
                        history(con, v.variantId, "MRP", old.mrp, v.mrp, reason);
                        history(con, v.variantId, "MIN_PRICE", old.minPrice, v.minPrice, reason);
                        if (old.purchasePrice.compareTo(v.purchasePrice) != 0) {
                            history(con, v.variantId, "COST", old.purchasePrice, v.purchasePrice, reason);
                            Jdbc.insert(con, "INSERT INTO cost_history (variant_id, old_cost, new_cost, reason, user_id) VALUES (?,?,?,?,?)",
                                    v.variantId, old.purchasePrice, v.purchasePrice, "MANUAL", Session.userId());
                        }
                    }
                }
                saveTiers(con, v);
                savePacks(con, v);
                saveExtraBarcodes(con, v);
            }

            if (p.isBundle()) {
                int bundleVariant = p.variants.get(0).variantId;
                Jdbc.update(con, "DELETE FROM bundle_items WHERE bundle_variant_id=?", bundleVariant);
                for (BundleItem bi : p.bundleItems) {
                    if (bi.componentVariantId == bundleVariant) {
                        throw new AppException("A bundle cannot contain itself.");
                    }
                    Jdbc.insert(con, "INSERT INTO bundle_items (bundle_variant_id, component_variant_id, quantity, optional) VALUES (?,?,?,?)",
                            bundleVariant, bi.componentVariantId, bi.quantity, bi.optional);
                }
            }
            AuditService.log(con, isNew ? "CREATE" : "UPDATE", "PRODUCT", p.productId, p.name + " (" + p.variants.size() + " variant(s))");
        }
    }

    private void history(Connection con, int variantId, String field, BigDecimal oldV, BigDecimal newV, String reason)
            throws SQLException {
        if (oldV != null && newV != null && oldV.compareTo(newV) == 0) {
            return;
        }
        Jdbc.insert(con, "INSERT INTO price_history (variant_id, field, old_value, new_value, effective_at, reason, user_id) "
                + "VALUES (?,?,?,?,?,?,?)", variantId, field, oldV, newV, LocalDateTime.now(), reason, Session.userId());
        if (oldV != null) {
            AuditService.log(con, "PRICE_CHANGE", "VARIANT", variantId, field + " " + Money.fmt(oldV) + " → " + Money.fmt(newV)
                    + " (" + reason + ")");
        }
    }

    private void saveTiers(Connection con, Variant v) throws SQLException {
        Jdbc.update(con, "DELETE FROM price_tiers WHERE variant_id=?", v.variantId);
        for (BigDecimal[] t : parseTiers(v.tiersText)) {
            if (t[1].compareTo(v.retailPrice) > 0) {
                throw new AppException("Tier price " + Money.fmt(t[1]) + " is higher than the retail price of " + v.sku + ".");
            }
            Jdbc.insert(con, "INSERT INTO price_tiers (variant_id, min_qty, price) VALUES (?,?,?)", v.variantId, t[0], t[1]);
        }
    }

    /** "25=76; 50=74" -> [[25,76],[50,74]] */
    public static List<BigDecimal[]> parseTiers(String text) {
        List<BigDecimal[]> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.split("[;,]")) {
            if (part.isBlank()) {
                continue;
            }
            String[] kv = part.split("=");
            if (kv.length != 2) {
                throw new AppException("Price tiers: write them as qty=price; e.g. 10=1100; 50=1050");
            }
            BigDecimal q = Money.parse(kv[0]);
            BigDecimal pr = Money.parse(kv[1]);
            if (!Money.pos(q) || !Money.pos(pr)) {
                throw new AppException("Price tiers: quantity and price must be greater than 0.");
            }
            out.add(new BigDecimal[]{q, pr});
        }
        return out;
    }

    private void savePacks(Connection con, Variant v) throws SQLException {
        Jdbc.update(con, "DELETE FROM variant_packs WHERE variant_id=?", v.variantId);
        if (v.packsText == null || v.packsText.isBlank()) {
            return;
        }
        for (String part : v.packsText.split(";")) {
            if (part.isBlank()) {
                continue;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^\\s*([A-Za-z][A-Za-z0-9 ]{0,29}?)\\s*=\\s*([0-9.]+)\\s*(?:@\\s*([0-9.,]+))?\\s*(?:#\\s*(\\S+))?\\s*$")
                    .matcher(part);
            if (!m.matches()) {
                throw new AppException("Packs: write them as NAME=qty@price#barcode, e.g. BOX=12@1100#2001234567890; CASE=24");
            }
            String code = m.group(4);
            checkCodeFree(con, code, -1, "Pack barcode");
            BigDecimal qty = new BigDecimal(m.group(2));
            if (qty.compareTo(BigDecimal.ONE) <= 0) {
                throw new AppException("A pack must contain more than 1 unit.");
            }
            Jdbc.insert(con, "INSERT INTO variant_packs (variant_id, name, quantity, barcode, price) VALUES (?,?,?,?,?)",
                    v.variantId, m.group(1).trim().toUpperCase(Locale.ROOT), qty, code, m.group(3) == null ? null : Money.parse(m.group(3)));
        }
    }

    private void saveExtraBarcodes(Connection con, Variant v) throws SQLException {
        Jdbc.update(con, "DELETE FROM variant_barcodes WHERE variant_id=?", v.variantId);
        if (v.barcodesText == null || v.barcodesText.isBlank()) {
            return;
        }
        for (String code : v.barcodesText.split("[;,\\s]+")) {
            if (code.isBlank()) {
                continue;
            }
            String err = BarcodeUtil.checkDigitError(code);
            if (err != null) {
                throw new AppException("Extra barcode " + code + ": " + err);
            }
            checkCodeFree(con, code, v.variantId, "Extra barcode");
            Jdbc.insert(con, "INSERT INTO variant_barcodes (barcode, variant_id) VALUES (?,?)", code, v.variantId);
        }
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
            if (p.bundleItems.stream().allMatch(b -> b.optional)) {
                throw new AppException("At least one combo component must be mandatory.");
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
        if (p.discountStart != null && p.discountEnd != null && p.discountEnd.isBefore(p.discountStart)) {
            throw new AppException("Discount end date is before its start date.");
        }
        if (p.warrantyMonths < 0 || p.warrantyMonths > 240) {
            throw new AppException("Warranty must be between 0 and 240 months.");
        }
        Set<String> skus = new HashSet<>();
        Set<String> barcodes = new HashSet<>();
        for (Variant v : p.variants) {
            String name = v.sku == null || v.sku.isBlank() ? (v.label().isEmpty() ? p.name : v.label()) : v.sku;
            if (v.retailPrice.signum() <= 0) {
                throw new AppException("Retail price must be greater than 0 (" + name + ").");
            }
            if (v.purchasePrice.signum() < 0 || v.wholesalePrice.signum() < 0 || v.reorderLevel.signum() < 0
                    || v.wholesaleMinQty.signum() < 0 || v.openingStock.signum() < 0 || v.mrp.signum() < 0
                    || v.minPrice.signum() < 0 || v.reorderQty.signum() < 0) {
                throw new AppException("Prices and quantities cannot be negative (" + name + ").");
            }
            if (v.mrp.signum() > 0 && v.retailPrice.compareTo(v.mrp) > 0) {
                throw new AppException("Retail price " + Money.fmt(v.retailPrice) + " is above the MRP " + Money.fmt(v.mrp)
                        + " (" + name + "). Selling above MRP is not allowed.");
            }
            if (v.minPrice.signum() > 0 && v.minPrice.compareTo(v.retailPrice) > 0) {
                throw new AppException("Minimum selling price is above the retail price (" + name + ").");
            }
            if (v.sku != null && !v.sku.isBlank() && !skus.add(v.sku.trim().toUpperCase(Locale.ROOT))) {
                throw new AppException("SKU " + v.sku + " is used twice.");
            }
            if (v.barcode != null && !v.barcode.isBlank()) {
                if (!barcodes.add(v.barcode.trim())) {
                    throw new AppException("Barcode " + v.barcode + " is used twice.");
                }
                String err = BarcodeUtil.checkDigitError(v.barcode.trim());
                if (err != null) {
                    throw new AppException("Barcode " + v.barcode + ": " + err);
                }
            }
            if (v.plu != null && !v.plu.isBlank() && !v.plu.trim().matches("\\d{5}")) {
                throw new AppException("Scale PLU must be exactly 5 digits (" + name + ").");
            }
            parseTiers(v.tiersText);
        }
        for (BundleItem bi : p.bundleItems) {
            if (!Money.pos(bi.quantity)) {
                throw new AppException("Bundle component quantities must be greater than 0.");
            }
        }
    }

    /** Returns who owns a code (SKU, barcode, extra barcode, pack barcode, PLU), ignoring the given variant. */
    private String codeOwner(Connection con, String code, int variantId) throws SQLException {
        String sql = "SELECT CONCAT(p.name, ' [', v.sku, ']') FROM product_variants v JOIN products p ON p.product_id = v.product_id "
                + "WHERE v.variant_id <> ? AND (v.sku = ? OR v.barcode = ? OR v.plu = ? "
                + "OR EXISTS (SELECT 1 FROM variant_barcodes x WHERE x.variant_id = v.variant_id AND x.barcode = ?) "
                + "OR EXISTS (SELECT 1 FROM variant_packs k WHERE k.variant_id = v.variant_id AND k.barcode = ?)) LIMIT 1";
        Object[] params = {variantId, code, code, code, code, code};
        return con == null ? Jdbc.one(sql, rs -> rs.getString(1), params) : Jdbc.one(con, sql, rs -> rs.getString(1), params);
    }

    private void checkCodeFree(Connection con, String code, int variantId, String what) throws SQLException {
        if (code == null) {
            return;
        }
        String owner = codeOwner(con, code, variantId);
        if (owner != null) {
            throw new AppException(what + " \"" + code + "\" is already used by " + owner + ".");
        }
    }

    private void removeVariant(Connection con, int variantId) throws SQLException {
        boolean used = Jdbc.one(con, "SELECT 1 FROM sale_items WHERE variant_id=? LIMIT 1", rs -> 1, variantId) != null
                || Jdbc.one(con, "SELECT 1 FROM bundle_items WHERE component_variant_id=? LIMIT 1", rs -> 1, variantId) != null
                || Jdbc.one(con, "SELECT 1 FROM stock_movements WHERE variant_id=? LIMIT 1", rs -> 1, variantId) != null;
        if (used) {
            Jdbc.update(con, "UPDATE product_variants SET active=0 WHERE variant_id=?", variantId);
        } else {
            Jdbc.update(con, "DELETE FROM product_variants WHERE variant_id=?", variantId);
        }
    }

    /**
     * SKU from the format in Settings. Tokens: {NAME} {CAT} {BRAND} {SIZE} {COLOUR} {YY} {SEQ:n}.
     * Example "{CAT}-{NAME}-{SEQ:4}" gives "CLO-COTCRE-0021".
     */
    String generateSku(Connection con, Product p, Variant v) throws SQLException {
        String fmt = Settings.get("sku_format", "{NAME}-{SEQ:5}");
        String cat = p.categoryId == null ? "GEN"
                : Jdbc.one(con, "SELECT name FROM categories WHERE category_id=?", rs -> rs.getString(1), p.categoryId);
        String brand = p.brandId == null ? "GEN" : Jdbc.one(con, "SELECT name FROM brands WHERE brand_id=?", rs -> rs.getString(1), p.brandId);
        String sku = fmt.replace("{NAME}", initials(p.name, 6)).replace("{CAT}", initials(cat, 3)).replace("{BRAND}", initials(brand, 3))
                .replace("{SIZE}", code(v.size)).replace("{COLOUR}", code(v.colour))
                .replace("{YY}", String.valueOf(java.time.Year.now().getValue() % 100));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{SEQ(?::(\\d))?\\}").matcher(sku);
        if (m.find()) {
            int width = m.group(1) == null ? 5 : Integer.parseInt(m.group(1));
            sku = m.replaceAll(String.format("%0" + width + "d", v.variantId));
        } else {
            sku = sku + "-" + v.variantId;
        }
        sku = sku.replaceAll("-{2,}", "-").replaceAll("^-|-$", "").toUpperCase(Locale.ROOT);
        String candidate = sku;
        int n = 2;
        while (codeOwner(con, candidate, v.variantId) != null) {
            candidate = sku + "-" + n++;
        }
        return candidate;
    }

    private static String initials(String text, int max) {
        StringBuilder prefix = new StringBuilder();
        for (String w : (text == null ? "" : text).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").trim().split("\\s+")) {
            if (!w.isEmpty() && prefix.length() < max) {
                prefix.append(w, 0, Math.min(3, w.length()));
            }
        }
        return prefix.length() == 0 ? "ITEM" : prefix.substring(0, Math.min(max, prefix.length()));
    }

    private static String code(String s) {
        return s == null || s.isBlank() ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    public static String generateSku(String productName, int variantId) {
        return initials(productName, 6) + "-" + String.format("%05d", variantId);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ================================================================== clone / status / delete

    /** Copies a product (variants without SKU, barcode or stock) and returns the new product id. */
    public int cloneProduct(int productId) throws SQLException {
        Product src = getProduct(productId);
        if (src == null) {
            throw new AppException("Product not found.");
        }
        Product c = new Product();
        c.name = src.name + " (Copy)";
        c.description = src.description;
        c.categoryId = src.categoryId;
        c.brandId = src.brandId;
        c.unitId = src.unitId;
        c.productType = src.productType;
        c.trackBatch = src.trackBatch;
        c.trackSerial = src.trackSerial;
        c.vatPercent = src.vatPercent;
        c.discountType = src.discountType;
        c.discountValue = src.discountValue;
        c.discountStart = src.discountStart;
        c.discountEnd = src.discountEnd;
        c.tags = src.tags;
        c.attributes = src.attributes;
        c.warrantyMonths = src.warrantyMonths;
        c.bundlePricing = src.bundlePricing;
        c.bundleDiscountPercent = src.bundleDiscountPercent;
        c.images = new ArrayList<>(src.images);
        c.active = false;
        c.bundleItems = src.bundleItems;
        for (Variant v : src.variants) {
            Variant n = new Variant();
            n.size = v.size;
            n.colour = v.colour;
            n.weight = v.weight;
            n.extraAttributes = v.extraAttributes;
            n.purchasePrice = v.purchasePrice;
            n.retailPrice = v.retailPrice;
            n.wholesalePrice = v.wholesalePrice;
            n.wholesaleMinQty = v.wholesaleMinQty;
            n.mrp = v.mrp;
            n.minPrice = v.minPrice;
            n.reorderLevel = v.reorderLevel;
            n.reorderQty = v.reorderQty;
            n.location = v.location;
            n.tiersText = v.tiersText;
            n.packsText = v.packsText == null ? null : v.packsText.replaceAll("#\\S+", "");
            n.active = v.active;
            c.variants.add(n);
        }
        c.priceChangeReason = "Cloned from product #" + productId;
        save(c);
        AuditService.log("CLONE", "PRODUCT", c.productId, "Cloned from #" + productId, null);
        return c.productId;
    }

    public void setActive(int productId, boolean active) throws SQLException {
        Jdbc.update("UPDATE products SET active=? WHERE product_id=?", active, productId);
        AuditService.log(active ? "ACTIVATE" : "DEACTIVATE", "PRODUCT", productId, null, null);
    }

    public void setFavourite(int variantId, boolean fav) throws SQLException {
        Jdbc.update("UPDATE product_variants SET is_favourite=? WHERE variant_id=?", fav, variantId);
    }

    /**
     * Deletes a product. Products with any stock or sales history are soft-deleted (hidden, history
     * kept, restorable); only products that were never used are removed physically.
     * Returns true when soft-deleted.
     */
    public boolean delete(int productId) throws SQLException {
        if (Jdbc.count("SELECT COUNT(*) FROM bundle_items bi JOIN product_variants v ON v.variant_id=bi.component_variant_id "
                + "WHERE v.product_id=?", productId) > 0) {
            throw new AppException("This product is a component of a bundle/combo. Remove it from the bundle first.");
        }
        boolean history = Jdbc.count("SELECT COUNT(*) FROM sale_items si JOIN product_variants v ON v.variant_id=si.variant_id "
                + "WHERE v.product_id=?", productId) > 0
                || Jdbc.count("SELECT COUNT(*) FROM stock_movements m JOIN product_variants v ON v.variant_id=m.variant_id "
                + "WHERE v.product_id=?", productId) > 0;
        if (history) {
            Jdbc.update("UPDATE products SET deleted=1, deleted_at=NOW(), active=0 WHERE product_id=?", productId);
            AuditService.log("SOFT_DELETE", "PRODUCT", productId, "Hidden; stock/sales history kept", null);
            return true;
        }
        Jdbc.update("DELETE FROM products WHERE product_id=?", productId);
        AuditService.log("DELETE", "PRODUCT", productId, "Permanently deleted (no history)", null);
        return false;
    }

    public void restore(int productId) throws SQLException {
        Jdbc.update("UPDATE products SET deleted=0, deleted_at=NULL WHERE product_id=?", productId);
        AuditService.log("RESTORE", "PRODUCT", productId, "Restored from deleted (still inactive)", null);
    }

    // ================================================================== bulk edit

    /** A bulk change applied to many variants at once (null fields are left unchanged). */
    public static class BulkChange {
        public BigDecimal retailPercent;
        public BigDecimal retailAmount;
        public BigDecimal wholesalePercent;
        public BigDecimal vatPercent;
        public Integer categoryId;
        public Integer brandId;
        public BigDecimal reorderLevel;
        public String location;
        public Boolean active;
        public String reason;
    }

    public int bulkUpdate(List<Integer> variantIds, BulkChange c) throws SQLException {
        if (variantIds.isEmpty()) {
            throw new AppException("Select products first.");
        }
        return Database.inTransaction(con -> {
            int n = 0;
            for (int id : variantIds) {
                Object[] cur = Jdbc.one(con, "SELECT retail_price, wholesale_price, mrp, product_id, min_price FROM product_variants WHERE variant_id=? FOR UPDATE",
                        rs -> new Object[]{rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getInt(4), rs.getBigDecimal(5)}, id);
                if (cur == null) {
                    continue;
                }
                BigDecimal retail = (BigDecimal) cur[0];
                BigDecimal wholesale = (BigDecimal) cur[1];
                BigDecimal newRetail = retail;
                if (c.retailPercent != null) {
                    newRetail = Money.r2(retail.add(Money.pct(retail, c.retailPercent)));
                } else if (c.retailAmount != null) {
                    newRetail = Money.r2(retail.add(c.retailAmount));
                }
                BigDecimal newWholesale = c.wholesalePercent == null ? wholesale : Money.r2(wholesale.add(Money.pct(wholesale, c.wholesalePercent)));
                if (newRetail.signum() <= 0) {
                    throw new AppException("The change would make a retail price 0 or negative.");
                }
                checkPriceLimits(con, id, newRetail);
                BigDecimal mrp = (BigDecimal) cur[2];
                if (mrp.signum() > 0 && newRetail.compareTo(mrp) > 0) {
                    throw new AppException("The change would push a retail price above its MRP (variant #" + id + ").");
                }
                Jdbc.update(con, "UPDATE product_variants SET retail_price=?, wholesale_price=?, reorder_level=COALESCE(?, reorder_level), "
                        + "location=COALESCE(?, location), active=COALESCE(?, active) WHERE variant_id=?",
                        newRetail, newWholesale, c.reorderLevel, c.location, c.active, id);
                String reason = c.reason == null || c.reason.isBlank() ? "Bulk edit" : c.reason;
                history(con, id, "RETAIL", retail, newRetail, reason);
                history(con, id, "WHOLESALE", wholesale, newWholesale, reason);
                if (c.vatPercent != null || c.categoryId != null || c.brandId != null) {
                    Jdbc.update(con, "UPDATE products SET vat_percent=COALESCE(?, vat_percent), category_id=COALESCE(?, category_id), "
                            + "brand_id=COALESCE(?, brand_id) WHERE product_id=?", c.vatPercent, c.categoryId, c.brandId, cur[3]);
                }
                n++;
            }
            AuditService.log(con, "BULK_EDIT", "PRODUCT", null, n + " variant(s): " + describe(c));
            return n;
        });
    }

    private static String describe(BulkChange c) {
        List<String> parts = new ArrayList<>();
        if (c.retailPercent != null) {
            parts.add("retail " + c.retailPercent + "%");
        }
        if (c.retailAmount != null) {
            parts.add("retail +" + c.retailAmount);
        }
        if (c.wholesalePercent != null) {
            parts.add("wholesale " + c.wholesalePercent + "%");
        }
        if (c.vatPercent != null) {
            parts.add("VAT " + c.vatPercent + "%");
        }
        if (c.categoryId != null) {
            parts.add("category #" + c.categoryId);
        }
        if (c.brandId != null) {
            parts.add("brand #" + c.brandId);
        }
        if (c.reorderLevel != null) {
            parts.add("reorder " + c.reorderLevel);
        }
        if (c.location != null) {
            parts.add("location " + c.location);
        }
        if (c.active != null) {
            parts.add(c.active ? "activated" : "deactivated");
        }
        return String.join(", ", parts);
    }

    // ================================================================== history & scheduled prices

    public Jdbc.TableData priceHistory(int variantId) throws SQLException {
        return Jdbc.table("SELECT h.effective_at AS `Date`, h.field AS `Price`, h.old_value AS `Old`, h.new_value AS `New`, "
                + "h.reason AS `Reason`, u.username AS `By` FROM price_history h LEFT JOIN users u ON u.user_id = h.user_id "
                + "WHERE h.variant_id = ? ORDER BY h.history_id DESC", variantId);
    }

    public Jdbc.TableData costHistory(int variantId) throws SQLException {
        return Jdbc.table("SELECT h.created_at AS `Date`, h.old_cost AS `Old avg cost`, h.new_cost AS `New avg cost`, "
                + "h.reason AS `Reason`, h.reference AS `Reference`, u.username AS `By` FROM cost_history h "
                + "LEFT JOIN users u ON u.user_id = h.user_id WHERE h.variant_id = ? ORDER BY h.history_id DESC", variantId);
    }

    public Jdbc.TableData supplierPrices(int variantId) throws SQLException {
        return Jdbc.table("SELECT m.created_at AS `Date`, COALESCE(s.name, '(no supplier)') AS `Supplier`, m.quantity AS `Qty`, "
                + "m.unit_cost AS `Unit cost`, m.reference_no AS `Reference`, b.batch_no AS `Batch` FROM stock_movements m "
                + "LEFT JOIN suppliers s ON s.supplier_id = m.supplier_id LEFT JOIN product_batches b ON b.batch_id = m.batch_id "
                + "WHERE m.variant_id = ? AND m.movement_type IN ('STOCK_IN','OPENING') ORDER BY m.movement_id DESC", variantId);
    }

    public Jdbc.TableData productAudit(int productId) throws SQLException {
        return Jdbc.table("SELECT created_at AS `Time`, username AS `User`, action AS `Action`, entity AS `Entity`, details AS `Details`, "
                + "approved_by AS `Approved by` FROM audit_log WHERE (entity = 'PRODUCT' AND entity_id = ?) OR "
                + "(entity = 'VARIANT' AND entity_id IN (SELECT CAST(variant_id AS CHAR) FROM product_variants WHERE product_id = ?)) "
                + "ORDER BY audit_id DESC", String.valueOf(productId), productId);
    }

    public void schedulePrice(List<Integer> variantIds, BigDecimal newRetail, BigDecimal retailPercent, BigDecimal newWholesale,
            LocalDateTime when, String note) throws SQLException {
        if (when == null || when.isBefore(LocalDateTime.now().minusMinutes(1))) {
            throw new AppException("Choose a future date and time for the price change.");
        }
        if (newRetail == null && retailPercent == null && newWholesale == null) {
            throw new AppException("Enter a new retail price, a percent change or a new wholesale price.");
        }
        Database.inTransaction(con -> {
            for (int id : variantIds) {
                BigDecimal retail = newRetail;
                if (retail == null && retailPercent != null) {
                    BigDecimal cur = Jdbc.decimal(con, "SELECT retail_price FROM product_variants WHERE variant_id=?", id);
                    retail = Money.r2(cur.add(Money.pct(cur, retailPercent)));
                }
                checkPriceLimits(con, id, retail);
                Jdbc.insert(con, "INSERT INTO scheduled_prices (variant_id, new_retail, new_wholesale, effective_at, note, created_by) "
                        + "VALUES (?,?,?,?,?,?)", id, retail, newWholesale, when, note, Session.userId());
            }
            AuditService.log(con, "SCHEDULE_PRICE", "PRODUCT", null, variantIds.size() + " variant(s) at " + when);
            return null;
        });
    }

    /** A new retail price must stay between the variant's minimum price and its MRP. */
    private static void checkPriceLimits(Connection con, int variantId, BigDecimal retail) throws SQLException {
        if (retail == null) {
            return;
        }
        Object[] lim = Jdbc.one(con, "SELECT v.min_price, v.mrp, v.sku FROM product_variants v WHERE v.variant_id=?",
                rs -> new Object[]{rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3)}, variantId);
        if (lim == null) {
            return;
        }
        if (retail.signum() <= 0) {
            throw new AppException("New retail price for " + lim[2] + " must be greater than 0.");
        }
        if (((BigDecimal) lim[0]).signum() > 0 && retail.compareTo((BigDecimal) lim[0]) < 0) {
            throw new AppException("New retail price " + Money.fmt(retail) + " for " + lim[2] + " is below its minimum price "
                    + Money.fmt((BigDecimal) lim[0]) + ". Lower the minimum price first.");
        }
        if (((BigDecimal) lim[1]).signum() > 0 && retail.compareTo((BigDecimal) lim[1]) > 0) {
            throw new AppException("New retail price " + Money.fmt(retail) + " for " + lim[2] + " is above its MRP " + Money.fmt((BigDecimal) lim[1]) + ".");
        }
    }

    /** Applies scheduled price changes that are due. Returns how many were applied. */
    public int applyDuePriceSchedules() throws SQLException {
        return Database.inTransaction(con -> {
            List<Object[]> due = Jdbc.list(con, "SELECT schedule_id, variant_id, new_retail, new_wholesale, note FROM scheduled_prices "
                    + "WHERE status='PENDING' AND effective_at <= NOW() FOR UPDATE",
                    rs -> new Object[]{rs.getInt(1), rs.getInt(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getString(5)});
            for (Object[] d : due) {
                int id = (Integer) d[1];
                Object[] cur = Jdbc.one(con, "SELECT retail_price, wholesale_price, mrp FROM product_variants WHERE variant_id=?",
                        rs -> new Object[]{rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)}, id);
                if (cur == null) {
                    continue;
                }
                BigDecimal retail = d[2] == null ? (BigDecimal) cur[0] : (BigDecimal) d[2];
                BigDecimal mrp = (BigDecimal) cur[2];
                if (mrp.signum() > 0 && retail.compareTo(mrp) > 0) {
                    retail = mrp;
                }
                try {
                    checkPriceLimits(con, id, retail);
                } catch (AppException invalid) {
                    Jdbc.update(con, "UPDATE scheduled_prices SET status='CANCELLED', note=CONCAT(COALESCE(note,''), ' [not applied: ', ?, ']') "
                            + "WHERE schedule_id=?", invalid.getMessage(), d[0]);
                    continue;
                }
                BigDecimal wholesale = d[3] == null ? (BigDecimal) cur[1] : (BigDecimal) d[3];
                Jdbc.update(con, "UPDATE product_variants SET retail_price=?, wholesale_price=? WHERE variant_id=?", retail, wholesale, id);
                String reason = "Scheduled change #" + d[0] + (d[4] == null ? "" : ": " + d[4]);
                history(con, id, "RETAIL", (BigDecimal) cur[0], retail, reason);
                history(con, id, "WHOLESALE", (BigDecimal) cur[1], wholesale, reason);
                Jdbc.update(con, "UPDATE scheduled_prices SET status='APPLIED', applied_at=NOW() WHERE schedule_id=?", d[0]);
            }
            return due.size();
        });
    }

    public Jdbc.TableData scheduledPrices() throws SQLException {
        return Jdbc.table("SELECT s.schedule_id AS `ID`, s.effective_at AS `Effective`, p.name AS `Product`, v.sku AS `SKU`, "
                + "v.retail_price AS `Current retail`, s.new_retail AS `New retail`, v.wholesale_price AS `Current wholesale`, "
                + "s.new_wholesale AS `New wholesale`, s.status AS `Status`, s.note AS `Note` FROM scheduled_prices s "
                + "JOIN product_variants v ON v.variant_id = s.variant_id JOIN products p ON p.product_id = v.product_id "
                + "ORDER BY s.status = 'PENDING' DESC, s.effective_at DESC LIMIT 500");
    }

    public void cancelSchedule(int scheduleId) throws SQLException {
        Jdbc.update("UPDATE scheduled_prices SET status='CANCELLED' WHERE schedule_id=? AND status='PENDING'", scheduleId);
    }

    // ================================================================== batches & serials

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

    /** Serial lookup with warranty status. */
    public Jdbc.TableData serialLookup(String text) throws SQLException {
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        return Jdbc.table("SELECT s.serial_no AS `Serial / IMEI`, p.name AS `Product`, v.sku AS `SKU`, s.status AS `Status`, "
                + "sa.invoice_no AS `Invoice`, sa.created_at AS `Sold On`, c.name AS `Customer`, c.phone AS `Phone`, "
                + "si.warranty_months AS `Warranty (months)`, "
                + "CASE WHEN sa.sale_id IS NULL OR si.warranty_months = 0 THEN NULL "
                + "ELSE DATE(DATE_ADD(sa.created_at, INTERVAL si.warranty_months MONTH)) END AS `Warranty Until`, "
                + "CASE WHEN sa.sale_id IS NULL THEN '' WHEN si.warranty_months = 0 THEN 'NO WARRANTY' "
                + "WHEN DATE_ADD(sa.created_at, INTERVAL si.warranty_months MONTH) >= NOW() THEN 'IN WARRANTY' "
                + "ELSE 'WARRANTY EXPIRED' END AS `Warranty` "
                + "FROM product_serials s JOIN product_variants v ON v.variant_id=s.variant_id "
                + "JOIN products p ON p.product_id=v.product_id "
                + "LEFT JOIN sale_items si ON si.sale_item_id = s.sale_item_id "
                + "LEFT JOIN sales sa ON sa.sale_id = si.sale_id LEFT JOIN customers c ON c.customer_id = sa.customer_id "
                + "WHERE s.serial_no LIKE ? OR p.name LIKE ? OR sa.invoice_no LIKE ? ORDER BY p.name, s.serial_no LIMIT 500",
                like, like, like);
    }

    /** Groups variants by product, keeping order (used by the POS grid). */
    public static Map<Integer, List<Variant>> byProduct(List<Variant> list) {
        Map<Integer, List<Variant>> m = new LinkedHashMap<>();
        for (Variant v : list) {
            m.computeIfAbsent(v.productId, k -> new ArrayList<>()).add(v);
        }
        return m;
    }
}
