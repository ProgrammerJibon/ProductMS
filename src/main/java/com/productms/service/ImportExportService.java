package com.productms.service;

import com.productms.dao.MasterDataDAO;
import com.productms.dao.ProductDAO;
import com.productms.db.Database;
import com.productms.db.Jdbc;
import com.productms.model.Product;
import com.productms.model.Variant;
import com.productms.util.AppException;
import com.productms.util.BarcodeUtil;
import com.productms.util.Csv;
import com.productms.util.Money;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bulk product import / export as CSV (opens in Excel).
 * <p>
 * Import is two steps: <b>preview</b> validates every row and shows what will be created, updated
 * or rejected without changing anything; <b>apply</b> writes all products in ONE transaction, so
 * either everything is imported or nothing is (rows with errors can be skipped or block the import).
 * Rows with the same product name + category + subcategory become variants of one product; an
 * existing SKU is updated.
 */
public class ImportExportService {

    public static final List<String> COLUMNS = Arrays.asList(
            "sku", "barcode", "product_name", "description", "category", "subcategory", "brand", "unit",
            "size", "colour", "weight", "extra_attributes", "purchase_price", "retail_price", "wholesale_price", "wholesale_min_qty",
            "mrp", "min_price", "vat_percent", "discount_type", "discount_value", "reorder_level", "reorder_qty", "location",
            "tags", "warranty_months", "price_tiers", "packs", "extra_barcodes", "opening_stock", "active");

    private final ProductDAO productDAO = new ProductDAO();
    private final MasterDataDAO masterDAO = new MasterDataDAO();

    public int exportProducts(Path file) throws SQLException, IOException {
        ProductDAO.Filter f = new ProductDAO.Filter();
        f.limit = 100000;
        List<List<Object>> rows = new ArrayList<>();
        Map<Integer, Product> cache = new HashMap<>();
        for (Variant v : productDAO.search(f)) {
            if (v.isBundle()) {
                continue;
            }
            Product p = cache.computeIfAbsent(v.productId, id -> {
                try {
                    return productDAO.getProduct(id);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            String cat = v.categoryName == null ? "" : v.categoryName;
            String parent = cat.contains(" › ") ? cat.split(" › ")[0] : cat;
            String sub = cat.contains(" › ") ? cat.split(" › ")[1] : "";
            rows.add(Arrays.asList(v.sku, v.barcode, v.productName, p.description, parent, sub, v.brandName,
                    v.unitShort, v.size, v.colour, v.weight, v.extraAttributes, v.purchasePrice, v.retailPrice, v.wholesalePrice,
                    v.wholesaleMinQty, v.mrp, v.minPrice, v.vatPercent, v.discountType, v.discountValue, v.reorderLevel, v.reorderQty,
                    v.location, v.tags, v.warrantyMonths, v.tiersText, v.packsText, v.barcodesText, v.stockQty,
                    v.isSellable() ? 1 : 0));
        }
        Csv.write(file, COLUMNS, rows);
        return rows.size();
    }

    public void writeTemplate(Path file) throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(Arrays.asList("", "", "Cotton Polo Shirt", "Pique cotton polo", "Clothing", "Men", "Easy Fashion", "PCS",
                "M", "Navy", "", "Material=Cotton", "300", "650", "520", "12", "700", "350", "7.5", "NONE", "0", "5", "20", "B2-05",
                "polo,summer", "0", "10=600; 50=560", "BOX=12@6800", "", "20", "1"));
        rows.add(Arrays.asList("", "", "Cotton Polo Shirt", "Pique cotton polo", "Clothing", "Men", "Easy Fashion", "PCS",
                "L", "Navy", "", "Material=Cotton", "300", "650", "520", "12", "700", "350", "7.5", "NONE", "0", "5", "20", "B2-05",
                "polo,summer", "0", "10=600; 50=560", "", "", "20", "1"));
        Csv.write(file, COLUMNS, rows);
    }

    // ================================================================== preview

    /** One row of the import preview. */
    public static class PreviewRow {
        public int line;
        public String sku;
        public String product;
        public String variant;
        public String action;
        public String message;
    }

    /** Result of a preview: rows plus the products ready to save. */
    public static class Preview {
        public final List<PreviewRow> rows = new ArrayList<>();
        final Map<String, Product> products = new LinkedHashMap<>();
        final Map<Product, List<Integer>> lines = new HashMap<>();
        public int creates;
        public int updates;
        public int errors;
    }

    public Preview preview(Path file) throws IOException, SQLException {
        List<List<String>> rows = Csv.read(file);
        if (rows.isEmpty()) {
            throw new AppException("The file is empty.");
        }
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < rows.get(0).size(); i++) {
            col.put(rows.get(0).get(i).trim().toLowerCase(), i);
        }
        for (String required : new String[]{"product_name", "retail_price"}) {
            if (!col.containsKey(required)) {
                throw new AppException("Missing column \"" + required + "\". Use Export or Template to see the expected columns.");
            }
        }
        Preview pv = new Preview();
        Set<String> skusInFile = new HashSet<>();
        Set<String> codesInFile = new HashSet<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            PreviewRow pr = new PreviewRow();
            pr.line = r + 1;
            pr.sku = get(row, col, "sku").toUpperCase();
            pr.product = get(row, col, "product_name");
            pr.variant = String.join(" / ", nonEmpty(get(row, col, "size"), get(row, col, "colour"), get(row, col, "weight")));
            if (pr.product.isEmpty()) {
                continue;
            }
            try {
                if (!pr.sku.isEmpty() && !skusInFile.add(pr.sku)) {
                    throw new AppException("SKU " + pr.sku + " appears twice in the file.");
                }
                String barcode = get(row, col, "barcode");
                if (!barcode.isEmpty()) {
                    String err = BarcodeUtil.checkDigitError(barcode);
                    if (err != null) {
                        throw new AppException("barcode " + err);
                    }
                    if (!codesInFile.add(barcode)) {
                        throw new AppException("Barcode " + barcode + " appears twice in the file.");
                    }
                }
                ProductDAO.CodeMatch existing = pr.sku.isEmpty() ? null : productDAO.findByCode(pr.sku);
                Product p;
                if (existing != null && existing.variant.sku.equalsIgnoreCase(pr.sku)) {
                    String key = "ID:" + existing.variant.productId;
                    p = pv.products.get(key);
                    if (p == null) {
                        p = productDAO.getProduct(existing.variant.productId);
                        pv.products.put(key, p);
                        pv.lines.put(p, new ArrayList<>());
                    }
                    applyProductFields(p, row, col);
                    for (Variant v : p.variants) {
                        if (v.variantId == existing.variant.variantId) {
                            applyVariantFields(v, row, col);
                            check(v);
                        }
                    }
                    pr.action = "UPDATE";
                    pv.updates++;
                } else {
                    String key = pr.product.toLowerCase() + "|" + get(row, col, "category").toLowerCase() + "|"
                            + get(row, col, "subcategory").toLowerCase();
                    p = pv.products.get(key);
                    if (p == null) {
                        p = new Product();
                        pv.products.put(key, p);
                        pv.lines.put(p, new ArrayList<>());
                    }
                    applyProductFields(p, row, col);
                    Variant v = new Variant();
                    v.sku = pr.sku.isEmpty() ? null : pr.sku;
                    applyVariantFields(v, row, col);
                    v.openingStock = dec(row, col, "opening_stock", BigDecimal.ZERO);
                    check(v);
                    p.variants.add(v);
                    pr.action = "CREATE";
                    pv.creates++;
                }
                pv.lines.get(p).add(pr.line);
                pr.message = "OK";
            } catch (RuntimeException e) {
                pr.action = "ERROR";
                pr.message = e.getMessage();
                pv.errors++;
            }
            pv.rows.add(pr);
        }
        return pv;
    }

    private static void check(Variant v) {
        if (v.retailPrice.signum() <= 0) {
            throw new AppException("retail_price must be greater than 0.");
        }
        if (v.mrp.signum() > 0 && v.retailPrice.compareTo(v.mrp) > 0) {
            throw new AppException("retail_price is above mrp.");
        }
        if (v.minPrice.signum() > 0 && v.minPrice.compareTo(v.retailPrice) > 0) {
            throw new AppException("min_price is above retail_price.");
        }
        ProductDAO.parseTiers(v.tiersText);
    }

    private static List<String> nonEmpty(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    // ================================================================== apply

    /** Result of an import. */
    public static class ImportResult {
        public int created;
        public int updated;
        public final List<String> errors = new ArrayList<>();
    }

    /**
     * Writes the previewed products in one transaction. When {@code allOrNothing} is true any row
     * error (from the preview or while saving) cancels the whole import; otherwise rows with
     * preview errors are skipped, but a failure while saving still rolls everything back.
     */
    public ImportResult apply(Preview pv, boolean allOrNothing) throws SQLException {
        if (allOrNothing && pv.errors > 0) {
            throw new AppException(pv.errors + " row(s) have errors. Fix them in the file, or untick \"all or nothing\" to "
                    + "import only the valid rows.");
        }
        ImportResult r = new ImportResult();
        Database.inTransaction(con -> {
            for (Product p : pv.products.values()) {
                try {
                    productDAO.save(con, p);
                } catch (AppException e) {
                    throw new AppException("Line(s) " + pv.lines.get(p) + " (" + p.name + "): " + e.getMessage()
                            + "\n\nNothing was imported (the whole import was rolled back).");
                }
            }
            AuditService.log(con, "IMPORT", "PRODUCT", null, pv.creates + " created, " + pv.updates + " updated, "
                    + pv.errors + " skipped");
            return null;
        });
        r.created = pv.creates;
        r.updated = pv.updates;
        for (PreviewRow row : pv.rows) {
            if ("ERROR".equals(row.action)) {
                r.errors.add("Line " + row.line + ": " + row.message);
            }
        }
        return r;
    }

    /** One-step import (preview + apply, skipping invalid rows). */
    public ImportResult importProducts(Path file) throws IOException, SQLException {
        return apply(preview(file), false);
    }

    // ================================================================== field mapping

    private void applyProductFields(Product p, List<String> row, Map<String, Integer> col) {
        try {
            p.name = get(row, col, "product_name");
            String desc = get(row, col, "description");
            if (!desc.isEmpty()) {
                p.description = desc;
            }
            String cat = get(row, col, "category");
            String sub = get(row, col, "subcategory");
            if (!sub.isEmpty()) {
                p.categoryId = masterDAO.findOrCreateCategory(cat, sub);
            } else if (!cat.isEmpty()) {
                p.categoryId = masterDAO.findOrCreateCategory(null, cat);
            }
            String brand = get(row, col, "brand");
            if (!brand.isEmpty()) {
                p.brandId = masterDAO.findOrCreateBrand(brand);
            }
            String unit = get(row, col, "unit");
            if (!unit.isEmpty()) {
                p.unitId = masterDAO.findUnit(unit);
            } else if (p.unitId == 0) {
                p.unitId = masterDAO.findUnit("PCS");
            }
        } catch (SQLException e) {
            throw new AppException(e.getMessage());
        }
        p.vatPercent = dec(row, col, "vat_percent", p.vatPercent);
        String dt = get(row, col, "discount_type").toUpperCase();
        if (dt.equals("PERCENT") || dt.equals("FIXED") || dt.equals("NONE")) {
            p.discountType = dt;
        } else if (!dt.isEmpty()) {
            throw new AppException("discount_type must be NONE, PERCENT or FIXED.");
        }
        p.discountValue = dec(row, col, "discount_value", p.discountValue);
        String tags = get(row, col, "tags");
        if (!tags.isEmpty()) {
            p.tags = tags;
        }
        String w = get(row, col, "warranty_months");
        if (!w.isEmpty()) {
            try {
                p.warrantyMonths = Integer.parseInt(w);
            } catch (NumberFormatException e) {
                throw new AppException("warranty_months must be a whole number.");
            }
        }
        String active = get(row, col, "active");
        if (!active.isEmpty()) {
            p.active = active.equals("1") || active.equalsIgnoreCase("true") || active.equalsIgnoreCase("yes");
        }
        p.priceChangeReason = "CSV import " + LocalDate.now();
    }

    private void applyVariantFields(Variant v, List<String> row, Map<String, Integer> col) {
        String barcode = get(row, col, "barcode");
        if (!barcode.isEmpty()) {
            v.barcode = barcode;
        }
        v.size = nn(get(row, col, "size"), v.size);
        v.colour = nn(get(row, col, "colour"), v.colour);
        v.weight = nn(get(row, col, "weight"), v.weight);
        v.extraAttributes = nn(get(row, col, "extra_attributes"), v.extraAttributes);
        v.purchasePrice = dec(row, col, "purchase_price", v.purchasePrice);
        v.retailPrice = dec(row, col, "retail_price", v.retailPrice);
        v.wholesalePrice = dec(row, col, "wholesale_price", v.wholesalePrice);
        v.wholesaleMinQty = dec(row, col, "wholesale_min_qty", v.wholesaleMinQty);
        v.mrp = dec(row, col, "mrp", v.mrp);
        v.minPrice = dec(row, col, "min_price", v.minPrice);
        v.reorderLevel = dec(row, col, "reorder_level", v.reorderLevel);
        v.reorderQty = dec(row, col, "reorder_qty", v.reorderQty);
        v.location = nn(get(row, col, "location"), v.location);
        v.tiersText = nn(get(row, col, "price_tiers"), v.tiersText);
        v.packsText = nn(get(row, col, "packs"), v.packsText);
        v.barcodesText = nn(get(row, col, "extra_barcodes"), v.barcodesText);
    }

    private static String nn(String value, String def) {
        return value.isEmpty() ? def : value;
    }

    private static String get(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.size() || row.get(i) == null ? "" : row.get(i).trim();
    }

    private static BigDecimal dec(List<String> row, Map<String, Integer> col, String name, BigDecimal def) {
        String s = get(row, col, name);
        if (s.isEmpty()) {
            return def;
        }
        BigDecimal v;
        try {
            v = Money.parse(s);
        } catch (IllegalArgumentException e) {
            throw new AppException(name + ": " + e.getMessage());
        }
        if (v.signum() < 0) {
            throw new AppException(name + " cannot be negative.");
        }
        return v;
    }

    /** Variants of the database whose stock is not matched by the ledger (used by tests and restore). */
    public static int ledgerMismatches() throws SQLException {
        return Jdbc.count("SELECT COUNT(*) FROM product_variants v WHERE v.stock_qty <> "
                + "(SELECT COALESCE(SUM(quantity),0) FROM stock_movements m WHERE m.variant_id = v.variant_id)");
    }
}
