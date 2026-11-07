package com.productms.service;

import com.productms.dao.MasterDataDAO;
import com.productms.dao.ProductDAO;
import com.productms.db.Jdbc;
import com.productms.model.Product;
import com.productms.model.Variant;
import com.productms.util.AppException;
import com.productms.util.Csv;
import com.productms.util.Money;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bulk product import / export as CSV (opens in Excel).
 * Rows with the same product name + category are grouped as variants of one product.
 * An existing SKU is updated; a new SKU is created.
 */
public class ImportExportService {

    public static final List<String> COLUMNS = Arrays.asList(
            "sku", "barcode", "product_name", "description", "category", "subcategory", "brand", "unit",
            "size", "colour", "weight", "purchase_price", "retail_price", "wholesale_price", "wholesale_min_qty",
            "vat_percent", "discount_type", "discount_value", "reorder_level", "opening_stock", "active");

    private final ProductDAO productDAO = new ProductDAO();
    private final MasterDataDAO masterDAO = new MasterDataDAO();

    public int exportProducts(Path file) throws SQLException, IOException {
        Jdbc.TableData t = Jdbc.table("SELECT v.sku, v.barcode, p.name, p.description, "
                + "COALESCE(par.name, cat.name), IF(par.name IS NULL, NULL, cat.name), b.name, u.name, v.size, v.colour, v.weight, "
                + "v.purchase_price, v.retail_price, v.wholesale_price, v.wholesale_min_qty, p.vat_percent, p.discount_type, "
                + "p.discount_value, v.reorder_level, v.stock_qty, (v.active AND p.active) "
                + "FROM product_variants v JOIN products p ON p.product_id=v.product_id JOIN units u ON u.unit_id=p.unit_id "
                + "LEFT JOIN categories cat ON cat.category_id=p.category_id LEFT JOIN categories par ON par.category_id=cat.parent_id "
                + "LEFT JOIN brands b ON b.brand_id=p.brand_id WHERE p.product_type <> 'BUNDLE' ORDER BY p.name, v.variant_id");
        Csv.write(file, COLUMNS, t.rows);
        return t.rows.size();
    }

    public void writeTemplate(Path file) throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(Arrays.asList("", "", "Cotton Polo Shirt", "Pique cotton polo", "Clothing", "Men", "Easy Fashion", "PCS",
                "M", "Navy", "", "300", "650", "520", "12", "7.5", "NONE", "0", "5", "20", "1"));
        rows.add(Arrays.asList("", "", "Cotton Polo Shirt", "Pique cotton polo", "Clothing", "Men", "Easy Fashion", "PCS",
                "L", "Navy", "", "300", "650", "520", "12", "7.5", "NONE", "0", "5", "20", "1"));
        Csv.write(file, COLUMNS, rows);
    }

    /** Result of an import. */
    public static class ImportResult {
        public int created;
        public int updated;
        public final List<String> errors = new ArrayList<>();
    }

    public ImportResult importProducts(Path file) throws IOException, SQLException {
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
        ImportResult result = new ImportResult();
        Map<String, Product> groups = new LinkedHashMap<>();
        Map<Product, List<Integer>> lineNos = new HashMap<>();

        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            try {
                String name = get(row, col, "product_name");
                if (name.isEmpty()) {
                    continue;
                }
                String sku = get(row, col, "sku").toUpperCase();
                Variant existing = sku.isEmpty() ? null : productDAO.findByCode(sku);
                Product p;
                if (existing != null && existing.sku.equalsIgnoreCase(sku)) {
                    String key = "ID:" + existing.productId;
                    p = groups.get(key);
                    if (p == null) {
                        p = productDAO.getProduct(existing.productId);
                        groups.put(key, p);
                        lineNos.put(p, new ArrayList<>());
                    }
                    applyProductFields(p, row, col);
                    for (Variant v : p.variants) {
                        if (v.variantId == existing.variantId) {
                            applyVariantFields(v, row, col);
                        }
                    }
                    result.updated++;
                } else {
                    String key = name.toLowerCase() + "|" + get(row, col, "category").toLowerCase() + "|"
                            + get(row, col, "subcategory").toLowerCase();
                    p = groups.get(key);
                    if (p == null) {
                        p = new Product();
                        groups.put(key, p);
                        lineNos.put(p, new ArrayList<>());
                    }
                    applyProductFields(p, row, col);
                    Variant v = new Variant();
                    v.sku = sku.isEmpty() ? null : sku;
                    applyVariantFields(v, row, col);
                    v.openingStock = dec(row, col, "opening_stock", BigDecimal.ZERO);
                    p.variants.add(v);
                    result.created++;
                }
                lineNos.get(p).add(r + 1);
            } catch (RuntimeException e) {
                result.errors.add("Line " + (r + 1) + ": " + e.getMessage());
            }
        }
        for (Product p : groups.values()) {
            try {
                productDAO.save(p);
            } catch (AppException | SQLException e) {
                result.errors.add("Line(s) " + lineNos.get(p) + " (" + p.name + "): " + e.getMessage());
                for (Variant v : p.variants) {
                    if (v.variantId == 0) {
                        result.created--;
                    }
                }
            }
        }
        return result;
    }

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
        }
        p.discountValue = dec(row, col, "discount_value", p.discountValue);
        String active = get(row, col, "active");
        if (!active.isEmpty()) {
            p.active = active.equals("1") || active.equalsIgnoreCase("true") || active.equalsIgnoreCase("yes");
        }
    }

    private void applyVariantFields(Variant v, List<String> row, Map<String, Integer> col) {
        String barcode = get(row, col, "barcode");
        if (!barcode.isEmpty()) {
            v.barcode = barcode;
        }
        v.size = nn(get(row, col, "size"), v.size);
        v.colour = nn(get(row, col, "colour"), v.colour);
        v.weight = nn(get(row, col, "weight"), v.weight);
        v.purchasePrice = dec(row, col, "purchase_price", v.purchasePrice);
        v.retailPrice = dec(row, col, "retail_price", v.retailPrice);
        v.wholesalePrice = dec(row, col, "wholesale_price", v.wholesalePrice);
        v.wholesaleMinQty = dec(row, col, "wholesale_min_qty", v.wholesaleMinQty);
        v.reorderLevel = dec(row, col, "reorder_level", v.reorderLevel);
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
        BigDecimal v = Money.parse(s);
        if (v.signum() < 0) {
            throw new AppException(name + " cannot be negative.");
        }
        return v;
    }
}
