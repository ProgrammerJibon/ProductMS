package com.productms.dao;

import com.productms.db.Jdbc;
import com.productms.model.Brand;
import com.productms.model.Category;
import com.productms.model.Supplier;
import com.productms.model.Unit;
import com.productms.util.AppException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Categories (with subcategories), brands, units and suppliers. */
public class MasterDataDAO {

    // ------------------------------------------------------------ categories

    private static Category mapCategory(ResultSet rs) throws SQLException {
        Category c = new Category();
        c.categoryId = rs.getInt("category_id");
        c.name = rs.getString("name");
        c.parentId = Jdbc.intOrNull(rs, "parent_id");
        c.parentName = rs.getString("parent_name");
        c.description = rs.getString("description");
        c.active = rs.getBoolean("active");
        return c;
    }

    /** Categories ordered so that subcategories follow their parent. */
    public List<Category> categories(boolean activeOnly) throws SQLException {
        return Jdbc.list("SELECT c.*, p.name AS parent_name FROM categories c "
                + "LEFT JOIN categories p ON p.category_id = c.parent_id "
                + (activeOnly ? "WHERE c.active = 1 " : "")
                + "ORDER BY COALESCE(p.name, c.name), c.parent_id IS NOT NULL, c.name", MasterDataDAO::mapCategory);
    }

    public List<Category> topCategories() throws SQLException {
        return Jdbc.list("SELECT c.*, NULL AS parent_name FROM categories c WHERE parent_id IS NULL ORDER BY name",
                MasterDataDAO::mapCategory);
    }

    public void saveCategory(Category c) throws SQLException {
        require(c.name, "Category name");
        if (c.parentId != null && c.parentId == c.categoryId) {
            throw new AppException("A category cannot be its own parent.");
        }
        if (c.parentId != null && c.categoryId != 0
                && Jdbc.count("SELECT COUNT(*) FROM categories WHERE parent_id = ?", c.categoryId) > 0) {
            throw new AppException("This category has subcategories, so it cannot become a subcategory itself.");
        }
        int dup = Jdbc.count("SELECT COUNT(*) FROM categories WHERE name = ? AND category_id <> ? AND "
                + "(parent_id <=> ?)", c.name.trim(), c.categoryId, c.parentId);
        if (dup > 0) {
            throw new AppException("A category named \"" + c.name.trim() + "\" already exists here.");
        }
        if (c.categoryId == 0) {
            c.categoryId = Jdbc.insert("INSERT INTO categories (name, parent_id, description, active) VALUES (?,?,?,?)",
                    c.name.trim(), c.parentId, c.description, c.active);
        } else {
            Jdbc.update("UPDATE categories SET name=?, parent_id=?, description=?, active=? WHERE category_id=?",
                    c.name.trim(), c.parentId, c.description, c.active, c.categoryId);
        }
    }

    public void deleteCategory(int id) throws SQLException {
        if (Jdbc.count("SELECT COUNT(*) FROM categories WHERE parent_id = ?", id) > 0) {
            throw new AppException("Delete or move its subcategories first.");
        }
        int used = Jdbc.count("SELECT COUNT(*) FROM products WHERE category_id = ?", id);
        if (used > 0) {
            throw new AppException(used + " product(s) use this category. Deactivate it instead, or move the products.");
        }
        Jdbc.update("DELETE FROM categories WHERE category_id = ?", id);
    }

    public int findOrCreateCategory(String parentName, String name) throws SQLException {
        Integer parentId = null;
        if (parentName != null && !parentName.isBlank()) {
            parentId = Jdbc.one("SELECT category_id FROM categories WHERE name=? AND parent_id IS NULL",
                    rs -> rs.getInt(1), parentName.trim());
            if (parentId == null) {
                parentId = Jdbc.insert("INSERT INTO categories (name) VALUES (?)", parentName.trim());
            }
        }
        Integer id = Jdbc.one("SELECT category_id FROM categories WHERE name=? AND parent_id <=> ?",
                rs -> rs.getInt(1), name.trim(), parentId);
        return id != null ? id : Jdbc.insert("INSERT INTO categories (name, parent_id) VALUES (?,?)", name.trim(), parentId);
    }

    // ------------------------------------------------------------ brands

    public List<Brand> brands(boolean activeOnly) throws SQLException {
        return Jdbc.list("SELECT * FROM brands " + (activeOnly ? "WHERE active=1 " : "") + "ORDER BY name", rs -> {
            Brand b = new Brand();
            b.brandId = rs.getInt("brand_id");
            b.name = rs.getString("name");
            b.active = rs.getBoolean("active");
            return b;
        });
    }

    public void saveBrand(Brand b) throws SQLException {
        require(b.name, "Brand name");
        if (Jdbc.count("SELECT COUNT(*) FROM brands WHERE name=? AND brand_id<>?", b.name.trim(), b.brandId) > 0) {
            throw new AppException("Brand \"" + b.name.trim() + "\" already exists.");
        }
        if (b.brandId == 0) {
            b.brandId = Jdbc.insert("INSERT INTO brands (name, active) VALUES (?,?)", b.name.trim(), b.active);
        } else {
            Jdbc.update("UPDATE brands SET name=?, active=? WHERE brand_id=?", b.name.trim(), b.active, b.brandId);
        }
    }

    public void deleteBrand(int id) throws SQLException {
        int used = Jdbc.count("SELECT COUNT(*) FROM products WHERE brand_id = ?", id);
        if (used > 0) {
            throw new AppException(used + " product(s) use this brand. Deactivate it instead.");
        }
        Jdbc.update("DELETE FROM brands WHERE brand_id = ?", id);
    }

    public int findOrCreateBrand(String name) throws SQLException {
        Integer id = Jdbc.one("SELECT brand_id FROM brands WHERE name=?", rs -> rs.getInt(1), name.trim());
        return id != null ? id : Jdbc.insert("INSERT INTO brands (name) VALUES (?)", name.trim());
    }

    // ------------------------------------------------------------ units

    public List<Unit> units() throws SQLException {
        return Jdbc.list("SELECT * FROM units ORDER BY unit_id", rs -> {
            Unit u = new Unit();
            u.unitId = rs.getInt("unit_id");
            u.name = rs.getString("name");
            u.shortName = rs.getString("short_name");
            u.allowDecimal = rs.getBoolean("allow_decimal");
            return u;
        });
    }

    public void saveUnit(Unit u) throws SQLException {
        require(u.name, "Unit name");
        require(u.shortName, "Short name");
        if (Jdbc.count("SELECT COUNT(*) FROM units WHERE (name=? OR short_name=?) AND unit_id<>?",
                u.name.trim(), u.shortName.trim(), u.unitId) > 0) {
            throw new AppException("A unit with this name or short name already exists.");
        }
        if (u.unitId == 0) {
            u.unitId = Jdbc.insert("INSERT INTO units (name, short_name, allow_decimal) VALUES (?,?,?)",
                    u.name.trim(), u.shortName.trim().toUpperCase(), u.allowDecimal);
        } else {
            Jdbc.update("UPDATE units SET name=?, short_name=?, allow_decimal=? WHERE unit_id=?",
                    u.name.trim(), u.shortName.trim().toUpperCase(), u.allowDecimal, u.unitId);
        }
    }

    public void deleteUnit(int id) throws SQLException {
        int used = Jdbc.count("SELECT COUNT(*) FROM products WHERE unit_id = ?", id);
        if (used > 0) {
            throw new AppException(used + " product(s) use this unit, so it cannot be deleted.");
        }
        Jdbc.update("DELETE FROM units WHERE unit_id = ?", id);
    }

    public int findUnit(String nameOrShort) throws SQLException {
        Integer id = Jdbc.one("SELECT unit_id FROM units WHERE name=? OR short_name=?", rs -> rs.getInt(1),
                nameOrShort.trim(), nameOrShort.trim());
        if (id == null) {
            String s = nameOrShort.trim();
            return Jdbc.insert("INSERT INTO units (name, short_name) VALUES (?,?)", s,
                    s.length() > 10 ? s.substring(0, 10).toUpperCase() : s.toUpperCase());
        }
        return id;
    }

    // ------------------------------------------------------------ suppliers

    public List<Supplier> suppliers(boolean activeOnly) throws SQLException {
        return Jdbc.list("SELECT * FROM suppliers " + (activeOnly ? "WHERE active=1 " : "") + "ORDER BY name", rs -> {
            Supplier s = new Supplier();
            s.supplierId = rs.getInt("supplier_id");
            s.name = rs.getString("name");
            s.phone = rs.getString("phone");
            s.address = rs.getString("address");
            s.active = rs.getBoolean("active");
            return s;
        });
    }

    public void saveSupplier(Supplier s) throws SQLException {
        require(s.name, "Supplier name");
        if (Jdbc.count("SELECT COUNT(*) FROM suppliers WHERE name=? AND supplier_id<>?", s.name.trim(), s.supplierId) > 0) {
            throw new AppException("Supplier \"" + s.name.trim() + "\" already exists.");
        }
        if (s.supplierId == 0) {
            s.supplierId = Jdbc.insert("INSERT INTO suppliers (name, phone, address, active) VALUES (?,?,?,?)",
                    s.name.trim(), s.phone, s.address, s.active);
        } else {
            Jdbc.update("UPDATE suppliers SET name=?, phone=?, address=?, active=? WHERE supplier_id=?",
                    s.name.trim(), s.phone, s.address, s.active, s.supplierId);
        }
    }

    public void deleteSupplier(int id) throws SQLException {
        int used = Jdbc.count("SELECT COUNT(*) FROM stock_movements WHERE supplier_id = ?", id);
        if (used > 0) {
            throw new AppException("This supplier appears in stock history. Deactivate it instead.");
        }
        Jdbc.update("DELETE FROM suppliers WHERE supplier_id = ?", id);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AppException(field + " is required.");
        }
    }
}
