package com.productms.service;

import com.productms.db.Database;
import com.productms.db.Jdbc;
import com.productms.util.AppException;
import com.productms.util.Money;
import com.productms.util.Session;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

/**
 * Physical stock counting sessions:
 * <ol>
 *   <li><b>Start</b> a count (whole shop or one category): system quantities are frozen as a snapshot.</li>
 *   <li><b>Count</b>: scan barcodes (+1 per scan) or type quantities.</li>
 *   <li><b>Submit</b>: the audit differences (qty and value) are shown.</li>
 *   <li><b>Approve</b> (manager, not the counter unless admin): differences are booked as
 *       ADJUSTMENT_IN / ADJUSTMENT_OUT. Batch / serial tracked items are listed for manual
 *       adjustment because the batch or serial must be chosen.</li>
 * </ol>
 * Sales made after the snapshot are allowed for: the booked difference is counted − (system at
 * snapshot − sold since), i.e. counted against the current stock adjusted by the snapshot delta.
 */
public class StockCountService {

    public int start(String name, Integer categoryId) throws SQLException {
        if (name == null || name.isBlank()) {
            throw new AppException("Give the count a name (e.g. \"Month-end November\").");
        }
        return Database.inTransaction(con -> {
            String no = DocNumbers.next(con, "COUNT");
            int id = Jdbc.insert(con, "INSERT INTO stock_counts (count_no, name, category_id, created_by) VALUES (?,?,?,?)",
                    no, name.trim(), categoryId, Session.userId());
            Jdbc.update(con, "INSERT INTO stock_count_items (count_id, variant_id, system_qty, unit_cost) "
                    + "SELECT ?, v.variant_id, v.stock_qty, v.purchase_price FROM product_variants v JOIN products p ON p.product_id = v.product_id "
                    + "LEFT JOIN categories c ON c.category_id = p.category_id WHERE p.product_type <> 'BUNDLE' AND p.deleted = 0 "
                    + "AND v.active = 1 AND (? IS NULL OR p.category_id = ? OR c.parent_id = ?)", id, categoryId, categoryId, categoryId);
            AuditService.log(con, "START_COUNT", "STOCK_COUNT", no, name.trim());
            return id;
        });
    }

    public Jdbc.TableData counts() throws SQLException {
        return Jdbc.table("SELECT c.count_id AS `ID`, c.count_no AS `Count No`, c.name AS `Name`, c.status AS `Status`, "
                + "u.username AS `Started by`, c.created_at AS `Started`, a.username AS `Approved by`, c.approved_at AS `Approved`, "
                + "(SELECT COUNT(*) FROM stock_count_items i WHERE i.count_id = c.count_id) AS `Items`, "
                + "(SELECT COUNT(*) FROM stock_count_items i WHERE i.count_id = c.count_id AND i.counted_qty IS NOT NULL) AS `Counted` "
                + "FROM stock_counts c JOIN users u ON u.user_id = c.created_by LEFT JOIN users a ON a.user_id = c.approved_by "
                + "ORDER BY c.count_id DESC");
    }

    public Jdbc.TableData items(int countId, boolean differencesOnly) throws SQLException {
        return Jdbc.table("SELECT i.variant_id AS `ID`, p.name AS `Product`, CONCAT_WS(' / ', v.size, v.colour, v.weight) AS `Variant`, "
                + "v.sku AS `SKU`, v.location AS `Location`, i.system_qty AS `System`, i.counted_qty AS `Counted`, "
                + "(i.counted_qty - i.system_qty) AS `Difference`, ROUND((i.counted_qty - i.system_qty) * i.unit_cost, 2) AS `Value diff.`, "
                + "CASE WHEN p.track_batch = 1 OR p.track_serial = 1 THEN 'MANUAL' ELSE '' END AS `Note` "
                + "FROM stock_count_items i JOIN product_variants v ON v.variant_id = i.variant_id JOIN products p ON p.product_id = v.product_id "
                + "WHERE i.count_id = ? " + (differencesOnly ? "AND i.counted_qty IS NOT NULL AND i.counted_qty <> i.system_qty " : "")
                + "ORDER BY v.location IS NULL, v.location, p.name", countId);
    }

    private String status(int countId) throws SQLException {
        return Jdbc.one("SELECT status FROM stock_counts WHERE count_id=?", rs -> rs.getString(1), countId);
    }

    /** Scans one unit of a barcode / SKU into the count. Returns the product name and new count. */
    public String scan(int countId, String code, BigDecimal qty) throws SQLException {
        if (!"OPEN".equals(status(countId))) {
            throw new AppException("This count is not open any more.");
        }
        com.productms.dao.ProductDAO.CodeMatch m = new com.productms.dao.ProductDAO().findByCode(code);
        if (m == null) {
            throw new AppException("No product with code " + code.trim() + ".");
        }
        BigDecimal add = (qty == null ? BigDecimal.ONE : qty).multiply(m.quantity);
        int n = Jdbc.update("UPDATE stock_count_items SET counted_qty = COALESCE(counted_qty, 0) + ? WHERE count_id=? AND variant_id=?",
                add, countId, m.variant.variantId);
        if (n == 0) {
            throw new AppException(m.variant.displayName() + " is not part of this count.");
        }
        BigDecimal now = Jdbc.decimal("SELECT counted_qty FROM stock_count_items WHERE count_id=? AND variant_id=?", countId,
                m.variant.variantId);
        return m.variant.displayName() + (m.packName == null ? "" : " (" + m.packName + ")") + ": counted " + Money.qty(now);
    }

    public void setCounted(int countId, int variantId, BigDecimal qty) throws SQLException {
        if (!"OPEN".equals(status(countId))) {
            throw new AppException("This count is not open any more.");
        }
        if (qty != null && qty.signum() < 0) {
            throw new AppException("Counted quantity cannot be negative.");
        }
        Jdbc.update("UPDATE stock_count_items SET counted_qty=? WHERE count_id=? AND variant_id=?", qty, countId, variantId);
    }

    public void submit(int countId) throws SQLException {
        if (!"OPEN".equals(status(countId))) {
            throw new AppException("Only an open count can be submitted.");
        }
        Jdbc.update("UPDATE stock_counts SET status='SUBMITTED', submitted_at=NOW() WHERE count_id=?", countId);
        AuditService.log("SUBMIT_COUNT", "STOCK_COUNT", countId, null, null);
    }

    public void cancel(int countId) throws SQLException {
        Jdbc.update("UPDATE stock_counts SET status='CANCELLED' WHERE count_id=? AND status IN ('OPEN','SUBMITTED')", countId);
        AuditService.log("CANCEL_COUNT", "STOCK_COUNT", countId, null, null);
    }

    /** Books the differences. Returns a summary including items left for manual adjustment. */
    public String approve(int countId) throws SQLException {
        if (!Session.isManager()) {
            throw new AppException("Only a manager or admin can approve a stock count.");
        }
        return Database.inTransaction(con -> {
            Object[] c = Jdbc.one(con, "SELECT status, created_by, count_no FROM stock_counts WHERE count_id=? FOR UPDATE",
                    rs -> new Object[]{rs.getString(1), rs.getInt(2), rs.getString(3)}, countId);
            if (c == null || !"SUBMITTED".equals(c[0])) {
                throw new AppException("Submit the count before approving it.");
            }
            if ((Integer) c[1] == Session.userId() && !Session.isAdmin()) {
                throw new AppException("The person who counted cannot approve the same count. Ask another manager.");
            }
            List<Object[]> rows = Jdbc.list(con, "SELECT i.variant_id, i.system_qty, i.counted_qty, i.unit_cost, p.track_batch, p.track_serial "
                    + "FROM stock_count_items i JOIN product_variants v ON v.variant_id = i.variant_id JOIN products p ON p.product_id = v.product_id "
                    + "WHERE i.count_id=? AND i.counted_qty IS NOT NULL AND i.counted_qty <> i.system_qty",
                    rs -> new Object[]{rs.getInt(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBoolean(5), rs.getBoolean(6)}, countId);
            int booked = 0;
            int manual = 0;
            BigDecimal value = BigDecimal.ZERO;
            for (Object[] r : rows) {
                BigDecimal diff = ((BigDecimal) r[2]).subtract((BigDecimal) r[1]);
                if ((Boolean) r[4] || (Boolean) r[5]) {
                    manual++;
                    continue;
                }
                InventoryService.applyChange(con, (Integer) r[0], null, diff, diff.signum() > 0 ? "ADJUSTMENT_IN" : "ADJUSTMENT_OUT",
                        (BigDecimal) r[3], (String) c[2], null, "Stock count " + c[2], Session.userId());
                value = value.add(diff.multiply((BigDecimal) r[3]));
                booked++;
            }
            Jdbc.update(con, "UPDATE stock_counts SET status='APPROVED', approved_by=?, approved_at=NOW() WHERE count_id=?",
                    Session.userId(), countId);
            AuditService.log(con, "APPROVE_COUNT", "STOCK_COUNT", c[2], booked + " adjustment(s), net value " + Money.fmt(value));
            return booked + " difference(s) booked, net value " + Money.cur(value) + "."
                    + (manual > 0 ? "\n" + manual + " batch / serial tracked item(s) differ: adjust them manually in Stock → Adjust "
                    + "(choose the batch or serials)." : "");
        });
    }
}
