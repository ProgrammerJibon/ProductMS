package com.productms.service;

import com.productms.db.Database;
import com.productms.db.Jdbc;
import com.productms.model.Variant;
import com.productms.util.AppException;
import com.productms.util.Money;
import com.productms.util.Session;
import com.productms.util.Settings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * All stock changes go through here so that variant stock, batch quantities, serial numbers,
 * the damaged-stock bucket and the stock movement ledger always agree.
 * <p>
 * Stock buckets per variant: <b>sellable</b> ({@code stock_qty}), <b>reserved</b> (held carts),
 * <b>available</b> = sellable − reserved − expired / near-expiry batches, <b>damaged</b>
 * ({@code damaged_qty}, quarantined, not sellable) and <b>physical</b> = sellable + damaged.
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
     * Low-level stock change. Locks the variant row (so two tills cannot oversell the same item),
     * refuses to go below zero, updates the batch (if any) and writes a ledger entry.
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
            throw new AppException("Not enough stock for " + name(con, variantId) + ". In stock: " + Money.qty(stock)
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

    static String name(Connection con, int variantId) throws SQLException {
        return Jdbc.one(con, "SELECT CONCAT(p.name, ' [', v.sku, ']') FROM product_variants v "
                + "JOIN products p ON p.product_id=v.product_id WHERE v.variant_id=?", rs -> rs.getString(1), variantId);
    }

    /** Moves units into the damaged bucket (they stay on premises but cannot be sold). */
    static void addDamaged(Connection con, int variantId, Integer batchId, BigDecimal qty, BigDecimal cost, String ref, String note)
            throws SQLException {
        Jdbc.update(con, "UPDATE product_variants SET damaged_qty = damaged_qty + ? WHERE variant_id=?", qty, variantId);
        Jdbc.insert(con, "INSERT INTO damaged_stock_log (variant_id, batch_id, quantity, action, unit_cost, reference, note, user_id) "
                + "VALUES (?,?,?,?,?,?,?,?)", variantId, batchId, qty, "DAMAGED", Money.r2(cost), ref, note, Session.userId());
    }

    private static Variant info(Connection con, int variantId) throws SQLException {
        return Jdbc.one(con, "SELECT v.variant_id, v.stock_qty, v.damaged_qty, v.purchase_price, p.name, p.track_batch, "
                + "p.track_serial, p.product_type, u.allow_decimal FROM product_variants v JOIN products p ON p.product_id=v.product_id "
                + "JOIN units u ON u.unit_id=p.unit_id WHERE v.variant_id=?", rs -> {
                    Variant v = new Variant();
                    v.variantId = rs.getInt("variant_id");
                    v.stockQty = rs.getBigDecimal("stock_qty");
                    v.damagedQty = rs.getBigDecimal("damaged_qty");
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
            throw new AppException(v.productName + " is counted in whole units only.");
        }
        if (v.isBundle()) {
            throw new AppException("Bundles/combos have no stock of their own. Use their components.");
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

    // ================================================================== stock in

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
                    Jdbc.update(con, "UPDATE product_batches SET qty_received = qty_received + ? WHERE batch_id=?", in.quantity, batchId);
                }
            }
            if (v.trackSerial) {
                List<String> serials = cleanSerials(in.serials);
                if (serials.size() != in.quantity.intValue()) {
                    throw new AppException("Enter exactly " + Money.qty(in.quantity) + " serial number(s), one per line. "
                            + "You entered " + serials.size() + ".");
                }
                for (String s : serials) {
                    if (Jdbc.one(con, "SELECT 1 FROM product_serials WHERE variant_id=? AND serial_no=?", rs -> 1, in.variantId, s) != null) {
                        throw new AppException("Serial number " + s + " already exists for this product.");
                    }
                    Jdbc.insert(con, "INSERT INTO product_serials (variant_id, serial_no) VALUES (?,?)", in.variantId, s);
                }
            }
            // Weighted average cost (the variant's cost); batches keep their own specific cost.
            BigDecimal oldQty = v.stockQty;
            BigDecimal newQty = oldQty.add(in.quantity);
            BigDecimal avg = oldQty.signum() <= 0 ? in.unitCost
                    : oldQty.multiply(v.purchasePrice).add(in.quantity.multiply(in.unitCost)).divide(newQty, 2, RoundingMode.HALF_UP);
            String ref = in.referenceNo == null || in.referenceNo.isBlank() ? null : in.referenceNo.trim();
            if (avg.compareTo(v.purchasePrice) != 0) {
                Jdbc.update(con, "UPDATE product_variants SET purchase_price=? WHERE variant_id=?", avg, in.variantId);
                Jdbc.insert(con, "INSERT INTO cost_history (variant_id, old_cost, new_cost, reason, reference, user_id) VALUES (?,?,?,?,?,?)",
                        in.variantId, v.purchasePrice, avg, "STOCK_IN", ref, Session.userId());
            }
            applyChange(con, in.variantId, batchId, in.quantity, "STOCK_IN", in.unitCost, ref, in.supplierId, in.note, Session.userId());
            AuditService.log(con, "STOCK_IN", "VARIANT", in.variantId, "+" + Money.qty(in.quantity) + " @ " + Money.fmt(in.unitCost)
                    + (ref == null ? "" : " ref " + ref));
            return null;
        });
    }

    // ================================================================== adjustments, damaged, approvals

    /** Manual stock correction, damage or expiry write-off. */
    public static class Adjustment {
        public int variantId;
        /** ADJUSTMENT_IN, ADJUSTMENT_OUT, DAMAGED, EXPIRED */
        public String type;
        public BigDecimal quantity;
        public Integer batchId;
        public List<String> serials = new ArrayList<>();
        public String note;
        public String reference = "ADJ";
    }

    /**
     * Applies an adjustment, or files it for approval when its value reaches the approval
     * threshold (Settings). Returns null when applied, otherwise the pending request number.
     */
    public Integer adjust(Adjustment a) throws SQLException {
        if (a.note == null || a.note.isBlank()) {
            throw new AppException("Enter a reason for the adjustment (it is kept in the stock history).");
        }
        BigDecimal value = valueOf(a);
        BigDecimal threshold = Settings.bd("adjustment_approval_value", "0");
        if (threshold.signum() > 0 && value.compareTo(threshold) >= 0 && !Session.isAdmin()) {
            Variant v = Jdbc.one("SELECT product_type, track_batch FROM products p JOIN product_variants x ON x.product_id = p.product_id "
                    + "WHERE x.variant_id = ?", rs -> {
                        Variant t = new Variant();
                        t.productType = rs.getString(1);
                        return t;
                    }, a.variantId);
            if (v == null) {
                throw new AppException("Product not found.");
            }
            int id = Jdbc.insert("INSERT INTO adjustment_requests (variant_id, adj_type, quantity, batch_id, serials, note, value, requested_by) "
                    + "VALUES (?,?,?,?,?,?,?,?)", a.variantId, a.type, a.quantity, a.batchId, String.join("\n", a.serials),
                    a.note.trim(), value, Session.userId());
            AuditService.log("REQUEST_ADJUSTMENT", "VARIANT", a.variantId, a.type + " " + Money.qty(a.quantity) + " value "
                    + Money.fmt(value) + " (request #" + id + ")", null);
            return id;
        }
        Database.inTransaction(con -> {
            apply(con, a, null);
            return null;
        });
        return null;
    }

    private BigDecimal valueOf(Adjustment a) throws SQLException {
        BigDecimal cost = a.batchId != null ? Jdbc.decimal("SELECT unit_cost FROM product_batches WHERE batch_id=?", a.batchId)
                : Jdbc.decimal("SELECT purchase_price FROM product_variants WHERE variant_id=?", a.variantId);
        return Money.r2(cost.multiply(Money.nz(a.quantity)));
    }

    private void apply(Connection con, Adjustment a, String approvedBy) throws SQLException {
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
                        if (Jdbc.one(con, "SELECT 1 FROM product_serials WHERE variant_id=? AND serial_no=?", rs -> 1, a.variantId, s) != null) {
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
        BigDecimal cost = a.batchId != null ? Jdbc.decimal(con, "SELECT unit_cost FROM product_batches WHERE batch_id=?", a.batchId)
                : v.purchasePrice;
        applyChange(con, a.variantId, a.batchId, in ? a.quantity : a.quantity.negate(), a.type, cost, a.reference, null,
                a.note.trim(), Session.userId());
        if ("DAMAGED".equals(a.type)) {
            addDamaged(con, a.variantId, a.batchId, a.quantity, cost, a.reference, a.note.trim());
        }
        AuditService.log(con, a.type, "VARIANT", a.variantId, Money.qty(a.quantity) + " - " + a.note.trim(), approvedBy);
    }

    /** Approves or rejects a pending adjustment. Approver must be a manager other than the requester (admins may self-approve). */
    public void decide(int requestId, boolean approve, String note) throws SQLException {
        if (!Session.isManager()) {
            throw new AppException("Only a manager or admin can approve adjustments.");
        }
        Database.inTransaction(con -> {
            Object[] r = Jdbc.one(con, "SELECT variant_id, adj_type, quantity, batch_id, serials, note, requested_by, status "
                    + "FROM adjustment_requests WHERE request_id=? FOR UPDATE", rs -> new Object[]{rs.getInt(1), rs.getString(2),
                        rs.getBigDecimal(3), Jdbc.intOrNull(rs, "batch_id"), rs.getString(5), rs.getString(6), rs.getInt(7),
                        rs.getString(8)}, requestId);
            if (r == null || !"PENDING".equals(r[7])) {
                throw new AppException("This request is no longer pending.");
            }
            if ((Integer) r[6] == Session.userId() && !Session.isAdmin()) {
                throw new AppException("You cannot approve your own adjustment. Ask another manager or an admin.");
            }
            if (approve) {
                Adjustment a = new Adjustment();
                a.variantId = (Integer) r[0];
                a.type = (String) r[1];
                a.quantity = (BigDecimal) r[2];
                a.batchId = (Integer) r[3];
                a.serials = r[4] == null || ((String) r[4]).isBlank() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(((String) r[4]).split("\n")));
                a.note = r[5] + " (request #" + requestId + ")";
                a.reference = "ADJ-REQ-" + requestId;
                apply(con, a, Session.user().username);
            }
            Jdbc.update(con, "UPDATE adjustment_requests SET status=?, decided_by=?, decided_at=NOW(), decision_note=? WHERE request_id=?",
                    approve ? "APPROVED" : "REJECTED", Session.userId(), note, requestId);
            AuditService.log(con, approve ? "APPROVE_ADJUSTMENT" : "REJECT_ADJUSTMENT", "VARIANT", r[0], "Request #" + requestId
                    + (note == null ? "" : ": " + note));
            return null;
        });
    }

    public Jdbc.TableData adjustmentRequests(boolean pendingOnly) throws SQLException {
        return Jdbc.table("SELECT r.request_id AS `ID`, r.requested_at AS `Requested`, u.username AS `By`, p.name AS `Product`, "
                + "v.sku AS `SKU`, r.adj_type AS `Type`, r.quantity AS `Qty`, r.value AS `Value`, r.note AS `Reason`, "
                + "r.status AS `Status`, d.username AS `Decided by`, r.decision_note AS `Decision note` FROM adjustment_requests r "
                + "JOIN product_variants v ON v.variant_id = r.variant_id JOIN products p ON p.product_id = v.product_id "
                + "JOIN users u ON u.user_id = r.requested_by LEFT JOIN users d ON d.user_id = r.decided_by "
                + (pendingOnly ? "WHERE r.status = 'PENDING' " : "") + "ORDER BY r.request_id DESC LIMIT 500");
    }

    /** Damaged units leave the premises (thrown away / returned to supplier): the loss is final. */
    public void disposeDamaged(int variantId, BigDecimal qty, String note) throws SQLException {
        moveDamaged(variantId, qty, note, false);
    }

    /** Damaged units were repaired / found sellable: they go back to sellable stock. */
    public void restoreDamaged(int variantId, BigDecimal qty, String note) throws SQLException {
        moveDamaged(variantId, qty, note, true);
    }

    private void moveDamaged(int variantId, BigDecimal qty, String note, boolean restore) throws SQLException {
        if (!Money.pos(qty)) {
            throw new AppException("Quantity must be greater than 0.");
        }
        if (note == null || note.isBlank()) {
            throw new AppException("Enter a reason.");
        }
        Database.inTransaction(con -> {
            Variant v = info(con, variantId);
            if (v.trackBatch || v.trackSerial) {
                if (restore) {
                    throw new AppException("Restore batch / serial tracked items with Adjustment IN (choose the batch / serials).");
                }
            }
            int n = Jdbc.update(con, "UPDATE product_variants SET damaged_qty = damaged_qty - ? WHERE variant_id=? AND damaged_qty >= ?",
                    qty, variantId, qty);
            if (n == 0) {
                throw new AppException("Only " + Money.qty(v.damagedQty) + " damaged unit(s) are recorded for " + v.productName + ".");
            }
            Jdbc.insert(con, "INSERT INTO damaged_stock_log (variant_id, quantity, action, unit_cost, note, user_id) VALUES (?,?,?,?,?,?)",
                    variantId, qty.negate(), restore ? "RESTORED" : "DISPOSED", v.purchasePrice, note.trim(), Session.userId());
            if (restore) {
                applyChange(con, variantId, null, qty, "ADJUSTMENT_IN", v.purchasePrice, "DMG-RESTORE", null,
                        "Restored from damaged: " + note.trim(), Session.userId());
            }
            AuditService.log(con, restore ? "RESTORE_DAMAGED" : "DISPOSE_DAMAGED", "VARIANT", variantId, Money.qty(qty) + " - " + note.trim());
            return null;
        });
    }

    public Jdbc.TableData damagedStock() throws SQLException {
        return Jdbc.table("SELECT v.variant_id AS `ID`, p.name AS `Product`, v.sku AS `SKU`, v.damaged_qty AS `Damaged qty`, "
                + "v.purchase_price AS `Unit cost`, ROUND(v.damaged_qty * v.purchase_price, 2) AS `Value`, "
                + "(SELECT MAX(created_at) FROM damaged_stock_log l WHERE l.variant_id = v.variant_id) AS `Last change` "
                + "FROM product_variants v JOIN products p ON p.product_id = v.product_id WHERE v.damaged_qty > 0 ORDER BY p.name");
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
            if ("DAMAGED".equals(type)) {
                addDamaged(con, variantId, batchId, left, cost, "WRITE-OFF", note);
            }
            AuditService.log(con, "WRITE_OFF_BATCH", "VARIANT", variantId, type + " batch #" + batchId + " qty " + Money.qty(left));
            return null;
        });
    }

    // ================================================================== sales

    /** Reserved quantity of a variant by held carts (optionally ignoring one held cart). */
    public static BigDecimal reserved(Connection con, int variantId, Integer exceptHeldId) throws SQLException {
        return Jdbc.decimal(con, "SELECT COALESCE(SUM(quantity),0) FROM stock_reservations WHERE variant_id=? AND expires_at > NOW() "
                + "AND (? IS NULL OR held_id <> ?)", variantId, exceptHeldId, exceptHeldId);
    }

    /**
     * Takes stock out for a sale line. Bundles deduct their (included) components; batch tracked
     * items are taken First-Expiry-First-Out, never from expired or near-expiry-blocked batches,
     * and never from quantity reserved by held carts.
     */
    public static List<Allocation> deductForSale(Connection con, int variantId, BigDecimal qty, String invoiceNo, int userId,
            List<Integer> excludedComponents) throws SQLException {
        List<Allocation> out = new ArrayList<>();
        Variant v = info(con, variantId);
        if (v.isBundle()) {
            List<Object[]> comps = Jdbc.list(con, "SELECT component_variant_id, quantity, optional FROM bundle_items WHERE bundle_variant_id=?",
                    rs -> new Object[]{rs.getInt(1), rs.getBigDecimal(2), rs.getBoolean(3)}, variantId);
            if (comps.isEmpty()) {
                throw new AppException("Bundle " + v.productName + " has no components.");
            }
            for (Object[] c : comps) {
                int compId = (Integer) c[0];
                if (excludedComponents != null && excludedComponents.contains(compId)) {
                    if (!(Boolean) c[2]) {
                        throw new AppException("Only optional combo parts can be left out.");
                    }
                    continue;
                }
                Variant comp = info(con, compId);
                if (comp.trackSerial) {
                    throw new AppException("Bundle component " + comp.productName + " is serial tracked; sell it separately.");
                }
                out.addAll(deductSingle(con, comp, qty.multiply((BigDecimal) c[1]), invoiceNo, "Bundle: " + v.productName, userId));
            }
        } else {
            out.addAll(deductSingle(con, v, qty, invoiceNo, null, userId));
        }
        return out;
    }

    private static List<Allocation> deductSingle(Connection con, Variant v, BigDecimal qty, String invoiceNo, String note,
            int userId) throws SQLException {
        Jdbc.one(con, "SELECT stock_qty FROM product_variants WHERE variant_id=? FOR UPDATE", rs -> 1, v.variantId);
        BigDecimal reserved = reserved(con, v.variantId, null);
        List<Allocation> out = new ArrayList<>();
        if (!v.trackBatch) {
            BigDecimal stock = Jdbc.decimal(con, "SELECT stock_qty FROM product_variants WHERE variant_id=?", v.variantId);
            if (stock.subtract(reserved).compareTo(qty) < 0 && stock.compareTo(qty) >= 0) {
                throw new AppException("Only " + Money.qty(stock.subtract(reserved)) + " of " + v.productName
                        + " can be sold: " + Money.qty(reserved) + " are reserved for held carts.");
            }
            applyChange(con, v.variantId, null, qty.negate(), "SALE", v.purchasePrice, invoiceNo, null, note, userId);
            out.add(new Allocation(v.variantId, null, qty, v.purchasePrice));
            return out;
        }
        int block = Math.max(0, Settings.getInt("near_expiry_block_days", 0));
        List<Object[]> batches = Jdbc.list(con, "SELECT batch_id, qty_remaining, unit_cost FROM product_batches "
                + "WHERE variant_id=? AND qty_remaining > 0 AND (expiry_date IS NULL OR expiry_date >= CURDATE() + INTERVAL " + block
                + " DAY) ORDER BY expiry_date IS NULL, expiry_date, batch_id FOR UPDATE",
                rs -> new Object[]{rs.getInt(1), rs.getBigDecimal(2), rs.getBigDecimal(3)}, v.variantId);
        BigDecimal sellable = BigDecimal.ZERO;
        for (Object[] b : batches) {
            sellable = sellable.add((BigDecimal) b[1]);
        }
        if (sellable.subtract(reserved).compareTo(qty) < 0) {
            throw new AppException("Not enough sellable stock of " + v.productName + ": " + Money.qty(Money.max(BigDecimal.ZERO,
                    sellable.subtract(reserved))) + " available (expired" + (block > 0 ? " / expiring within " + block + " days" : "")
                    + " batches and held-cart reservations are excluded).");
        }
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
        return out;
    }

    // ================================================================== queries

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
