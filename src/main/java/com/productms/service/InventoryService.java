package com.productms.service;

import com.productms.db.Database;
import com.productms.db.Jdbc;
import com.productms.model.Variant;
import com.productms.util.AppException;
import com.productms.util.Money;
import com.productms.util.Session;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * All stock changes go through here so that variant stock, batch quantities,
 * serial numbers and the stock movement ledger always agree.
 */
public class InventoryService {

    /** What was physically taken from stock for a sale line. */
    public static class Allocation {
        public int variantId;
        public Integer batchId;
        public BigDecimal quantity;
        public BigDecimal unitCost;

        public Allocation(int variantId, Integer batchId, BigDecimal quantity, BigDecimal unitCost) {
            this.variantId = variantId;
            this.batchId = batchId;
            this.quantity = quantity;
            this.unitCost = unitCost;
        }
    }

    /**
     * Low-level stock change. Locks the variant row, refuses to go below zero,
     * updates the batch (if any) and writes a ledger entry. Returns the new balance.
     */
    public static BigDecimal applyChange(Connection con, int variantId, Integer batchId, BigDecimal delta, String type,
            BigDecimal unitCost, String refNo, Integer supplierId, String note, int userId) throws SQLException {
        BigDecimal stock = Jdbc.one(con, "SELECT stock_qty FROM product_variants WHERE variant_id=? FOR UPDATE",
                rs -> rs.getBigDecimal(1), variantId);
        if (stock == null) {
            throw new AppException("Product variant #" + variantId + " no longer exists.");
        }
        BigDecimal balance = stock.add(delta);
        if (balance.signum() < 0) {
            String name = Jdbc.one(con, "SELECT CONCAT(p.name, ' [', v.sku, ']') FROM product_variants v "
                    + "JOIN products p ON p.product_id=v.product_id WHERE v.variant_id=?", rs -> rs.getString(1), variantId);
            throw new AppException("Not enough stock for " + name + ". In stock: " + Money.qty(stock)
                    + ", requested: " + Money.qty(delta.negate()) + ".");
        }
        Jdbc.update(con, "UPDATE product_variants SET stock_qty=? WHERE variant_id=?", balance, variantId);
        if (batchId != null) {
            int n = Jdbc.update(con, "UPDATE product_batches SET qty_remaining = qty_remaining + ? "
                    + "WHERE batch_id=? AND qty_remaining + ? >= 0", delta, batchId, delta);
            if (n == 0) {
                throw new AppException("Not enough quantity left in the selected batch.");
            }
        }
        Jdbc.insert(con, "INSERT INTO stock_movements (variant_id, batch_id, movement_type, quantity, balance_after, "
                + "unit_cost, reference_no, supplier_id, note, user_id) VALUES (?,?,?,?,?,?,?,?,?,?)",
                variantId, batchId, type, delta, balance, Money.r2(Money.nz(unitCost)), refNo, supplierId, note,
                userId == 0 ? null : userId);
        return balance;
    }

    private static Variant info(Connection con, int variantId) throws SQLException {
        return Jdbc.one(con, "SELECT v.variant_id, v.stock_qty, v.purchase_price, p.name, p.track_batch, p.track_serial, "
                + "p.product_type, u.allow_decimal FROM product_variants v JOIN products p ON p.product_id=v.product_id "
                + "JOIN units u ON u.unit_id=p.unit_id WHERE v.variant_id=?", rs -> {
                    Variant v = new Variant();
                    v.variantId = rs.getInt("variant_id");
                    v.stockQty = rs.getBigDecimal("stock_qty");
                    v.purchasePrice = rs.getBigDecimal("purchase_price");
                    v.productName = rs.getString("name");
                    v.trackBatch = rs.getBoolean("track_batch");
                    v.trackSerial = rs.getBoolean("track_serial");
                    v.productType = rs.getString("product_type");
                    v.allowDecimal = rs.getBoolean("allow_decimal");
                    return v;
                }, variantId);
    }

    private static void checkQty(Variant v, BigDecimal qty) {
        if (!Money.pos(qty)) {
            throw new AppException("Quantity must be greater than 0.");
        }
        if ((!v.allowDecimal || v.trackSerial) && !Money.isWhole(qty)) {
            throw new AppException(v.productName + " is sold in whole units only.");
        }
        if (v.isBundle()) {
            throw new AppException("Bundles/combos have no stock of their own. Receive stock for their components.");
        }
    }

    private static List<String> cleanSerials(List<String> serials) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String s : serials) {
            String t = s == null ? "" : s.trim();
            if (!t.isEmpty()) {
                if (!seen.add(t)) {
                    throw new AppException("Serial number " + t + " is entered twice.");
                }
                out.add(t);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ stock in

    /** Goods received from a supplier. */
    public static class StockIn {
        public int variantId;
        public BigDecimal quantity;
        public BigDecimal unitCost;
        public Integer supplierId;
        public String referenceNo;
        public String batchNo;
        public LocalDate manufactureDate;
        public LocalDate expiryDate;
        public List<String> serials = new ArrayList<>();
        public String note;
    }

    public void stockIn(StockIn in) throws SQLException {
        Database.inTransaction(con -> {
            Variant v = info(con, in.variantId);
            if (v == null) {
                throw new AppException("Select a product first.");
            }
            checkQty(v, in.quantity);
            if (in.unitCost == null || in.unitCost.signum() < 0) {
                throw new AppException("Enter a valid unit cost (purchase price).");
            }
            Integer batchId = null;
            if (v.trackBatch) {
                if (in.batchNo == null || in.batchNo.isBlank()) {
                    throw new AppException(v.productName + " is batch tracked. Enter a batch / lot number.");
                }
                if (in.expiryDate != null && in.expiryDate.isBefore(LocalDate.now())) {
                    throw new AppException("The expiry date is in the past. Expired goods cannot be received.");
                }
                if (in.manufactureDate != null && in.expiryDate != null && !in.manufactureDate.isBefore(in.expiryDate)) {
                    throw new AppException("Manufacture date must be before the expiry date.");
                }
                batchId = Jdbc.one(con, "SELECT batch_id FROM product_batches WHERE variant_id=? AND batch_no=?",
                        rs -> rs.getInt(1), in.variantId, in.batchNo.trim());
                if (batchId == null) {
                    batchId = Jdbc.insert(con, "INSERT INTO product_batches (variant_id, batch_no, manufacture_date, expiry_date, "
                            + "qty_received, qty_remaining, unit_cost) VALUES (?,?,?,?,?,0,?)",
                            in.variantId, in.batchNo.trim(), in.manufactureDate, in.expiryDate, in.quantity, in.unitCost);
                } else {
                    Jdbc.update(con, "UPDATE product_batches SET qty_received = qty_received + ? WHERE batch_id=?",
                            in.quantity, batchId);
                }
            }
            if (v.trackSerial) {
                List<String> serials = cleanSerials(in.serials);
                if (serials.size() != in.quantity.intValue()) {
                    throw new AppException("Enter exactly " + Money.qty(in.quantity) + " serial number(s), one per line. "
                            + "You entered " + serials.size() + ".");
                }
                for (String s : serials) {
                    if (Jdbc.one(con, "SELECT 1 FROM product_serials WHERE variant_id=? AND serial_no=?", rs -> 1,
                            in.variantId, s) != null) {
                        throw new AppException("Serial number " + s + " already exists for this product.");
                    }
                    Jdbc.insert(con, "INSERT INTO product_serials (variant_id, serial_no) VALUES (?,?)", in.variantId, s);
                }
            }
            // Weighted average cost
            BigDecimal oldQty = v.stockQty;
            BigDecimal newQty = oldQty.add(in.quantity);
            BigDecimal avg = oldQty.signum() <= 0 ? in.unitCost
                    : oldQty.multiply(v.purchasePrice).add(in.quantity.multiply(in.unitCost))
                            .divide(newQty, 2, RoundingMode.HALF_UP);
            Jdbc.update(con, "UPDATE product_variants SET purchase_price=? WHERE variant_id=?", avg, in.variantId);

            String ref = in.referenceNo == null || in.referenceNo.isBlank() ? null : in.referenceNo.trim();
            applyChange(con, in.variantId, batchId, in.quantity, "STOCK_IN", in.unitCost, ref, in.supplierId,
                    in.note, Session.userId());
            return null;
        });
    }

    // ------------------------------------------------------------------ adjustments / write-offs

    /** Manual stock correction, damage or expiry write-off. */
    public static class Adjustment {
        public int variantId;
        /** ADJUSTMENT_IN, ADJUSTMENT_OUT, DAMAGED, EXPIRED */
        public String type;
        public BigDecimal quantity;
        public Integer batchId;
        public List<String> serials = new ArrayList<>();
        public String note;
    }

    public void adjust(Adjustment a) throws SQLException {
        if (a.note == null || a.note.isBlank()) {
            throw new AppException("Enter a reason for the adjustment (it is kept in the stock history).");
        }
        Database.inTransaction(con -> {
            Variant v = info(con, a.variantId);
            if (v == null) {
                throw new AppException("Select a product first.");
            }
            checkQty(v, a.quantity);
            boolean in = "ADJUSTMENT_IN".equals(a.type);
            if (v.trackBatch && a.batchId == null) {
                throw new AppException(v.productName + " is batch tracked. Select the batch to adjust.");
            }
            if (v.trackSerial) {
                List<String> serials = cleanSerials(a.serials);
                if (serials.size() != a.quantity.intValue()) {
                    throw new AppException("Enter exactly " + Money.qty(a.quantity) + " serial number(s).");
                }
                for (String s : serials) {
                    if (in) {
                        int n = Jdbc.update(con, "UPDATE product_serials SET status='IN_STOCK', sale_item_id=NULL "
                                + "WHERE variant_id=? AND serial_no=? AND status='DAMAGED'", a.variantId, s);
                        if (n == 0) {
                            if (Jdbc.one(con, "SELECT 1 FROM product_serials WHERE variant_id=? AND serial_no=?", rs -> 1,
                                    a.variantId, s) != null) {
                                throw new AppException("Serial " + s + " is already in stock or sold.");
                            }
                            Jdbc.insert(con, "INSERT INTO product_serials (variant_id, serial_no) VALUES (?,?)", a.variantId, s);
                        }
                    } else {
                        int n = Jdbc.update(con, "UPDATE product_serials SET status='DAMAGED' WHERE variant_id=? "
                                + "AND serial_no=? AND status='IN_STOCK'", a.variantId, s);
                        if (n == 0) {
                            throw new AppException("Serial " + s + " is not in stock for this product.");
                        }
                    }
                }
            }
            BigDecimal cost = v.purchasePrice;
            if (a.batchId != null) {
                cost = Jdbc.decimal(con, "SELECT unit_cost FROM product_batches WHERE batch_id=?", a.batchId);
            }
            applyChange(con, a.variantId, a.batchId, in ? a.quantity : a.quantity.negate(), a.type, cost,
                    "ADJ", null, a.note.trim(), Session.userId());
            return null;
        });
    }

    /** Writes off everything left in a batch (expired or damaged). */
    public void writeOffBatch(int batchId, String type, String note) throws SQLException {
        Database.inTransaction(con -> {
            BigDecimal left = Jdbc.decimal(con, "SELECT qty_remaining FROM product_batches WHERE batch_id=? FOR UPDATE", batchId);
            if (left.signum() <= 0) {
                throw new AppException("This batch has no stock left.");
            }
            Integer variantId = Jdbc.one(con, "SELECT variant_id FROM product_batches WHERE batch_id=?", rs -> rs.getInt(1), batchId);
            BigDecimal cost = Jdbc.decimal(con, "SELECT unit_cost FROM product_batches WHERE batch_id=?", batchId);
            applyChange(con, variantId, batchId, left.negate(), type, cost, "WRITE-OFF", null, note, Session.userId());
            return null;
        });
    }

    // ------------------------------------------------------------------ sales

    /**
     * Takes stock out for a sale line. Bundles deduct their components; batch tracked
     * items are taken First-Expiry-First-Out and expired batches are never sold.
     */
    public static List<Allocation> deductForSale(Connection con, int variantId, BigDecimal qty, String invoiceNo,
            int userId) throws SQLException {
        List<Allocation> out = new ArrayList<>();
        Variant v = info(con, variantId);
        if (v.isBundle()) {
            List<Object[]> comps = Jdbc.list(con, "SELECT component_variant_id, quantity FROM bundle_items WHERE bundle_variant_id=?",
                    rs -> new Object[]{rs.getInt(1), rs.getBigDecimal(2)}, variantId);
            if (comps.isEmpty()) {
                throw new AppException("Bundle " + v.productName + " has no components.");
            }
            for (Object[] c : comps) {
                Variant comp = info(con, (Integer) c[0]);
                if (comp.trackSerial) {
                    throw new AppException("Bundle component " + comp.productName + " is serial tracked; sell it separately.");
                }
                out.addAll(deductSingle(con, comp, qty.multiply((BigDecimal) c[1]), invoiceNo,
                        "Bundle: " + v.productName, userId));
            }
        } else {
            out.addAll(deductSingle(con, v, qty, invoiceNo, null, userId));
        }
        return out;
    }

    private static List<Allocation> deductSingle(Connection con, Variant v, BigDecimal qty, String invoiceNo, String note,
            int userId) throws SQLException {
        List<Allocation> out = new ArrayList<>();
        if (!v.trackBatch) {
            applyChange(con, v.variantId, null, qty.negate(), "SALE", v.purchasePrice, invoiceNo, null, note, userId);
            out.add(new Allocation(v.variantId, null, qty, v.purchasePrice));
            return out;
        }
        List<Object[]> batches = Jdbc.list(con, "SELECT batch_id, qty_remaining, unit_cost FROM product_batches "
                + "WHERE variant_id=? AND qty_remaining > 0 AND (expiry_date IS NULL OR expiry_date >= CURDATE()) "
                + "ORDER BY expiry_date IS NULL, expiry_date, batch_id FOR UPDATE",
                rs -> new Object[]{rs.getInt(1), rs.getBigDecimal(2), rs.getBigDecimal(3)}, v.variantId);
        BigDecimal need = qty;
        for (Object[] b : batches) {
            if (need.signum() <= 0) {
                break;
            }
            BigDecimal take = Money.min(need, (BigDecimal) b[1]);
            applyChange(con, v.variantId, (Integer) b[0], take.negate(), "SALE", (BigDecimal) b[2], invoiceNo, null, note, userId);
            out.add(new Allocation(v.variantId, (Integer) b[0], take, (BigDecimal) b[2]));
            need = need.subtract(take);
        }
        if (need.signum() > 0) {
            throw new AppException("Not enough unexpired stock of " + v.productName + ". Short by " + Money.qty(need)
                    + ". (Expired batches cannot be sold.)");
        }
        return out;
    }

    // ------------------------------------------------------------------ queries

    public Jdbc.TableData movements(LocalDate from, LocalDate to, String type, String text) throws SQLException {
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        return Jdbc.table("SELECT m.created_at AS `Date`, p.name AS `Product`, CONCAT_WS(' / ', v.size, v.colour, v.weight) AS `Variant`, "
                + "v.sku AS `SKU`, m.movement_type AS `Type`, m.quantity AS `Qty`, m.balance_after AS `Balance`, "
                + "m.unit_cost AS `Unit Cost`, ROUND(ABS(m.quantity) * m.unit_cost, 2) AS `Value`, b.batch_no AS `Batch`, "
                + "m.reference_no AS `Reference`, s.name AS `Supplier`, m.note AS `Note`, u.username AS `User` "
                + "FROM stock_movements m JOIN product_variants v ON v.variant_id = m.variant_id "
                + "JOIN products p ON p.product_id = v.product_id LEFT JOIN product_batches b ON b.batch_id = m.batch_id "
                + "LEFT JOIN suppliers s ON s.supplier_id = m.supplier_id LEFT JOIN users u ON u.user_id = m.user_id "
                + "WHERE DATE(m.created_at) BETWEEN ? AND ? AND (? = 'ALL' OR m.movement_type = ?) "
                + "AND (p.name LIKE ? OR v.sku LIKE ? OR m.reference_no LIKE ?) ORDER BY m.movement_id DESC LIMIT 2000",
                from, to, type, type, like, like, like);
    }
}
