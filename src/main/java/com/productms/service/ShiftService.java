package com.productms.service;

import com.productms.dao.PaymentMethodDAO;
import com.productms.db.Database;
import com.productms.db.Jdbc;
import com.productms.model.PaymentMethod;
import com.productms.model.Shift;
import com.productms.util.AppException;
import com.productms.util.Money;
import com.productms.util.Session;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cashier shifts: opening float, cash in / out, safe drops, expected cash, denomination count,
 * per-method reconciliation (expected vs declared) and the X / Z report.
 * <p>
 * Expected cash = opening + cash received − cash refunded + cash in − cash out − safe drops.
 */
public class ShiftService {

    private static Shift map(ResultSet rs) throws SQLException {
        Shift s = new Shift();
        s.shiftId = rs.getInt("shift_id");
        s.userId = rs.getInt("user_id");
        s.userName = rs.getString("full_name");
        s.openedAt = Jdbc.time(rs, "opened_at");
        s.closedAt = Jdbc.time(rs, "closed_at");
        s.openingCash = rs.getBigDecimal("opening_cash");
        s.expectedCash = rs.getBigDecimal("expected_cash");
        s.countedCash = rs.getBigDecimal("counted_cash");
        s.difference = rs.getBigDecimal("difference");
        s.status = rs.getString("status");
        s.note = rs.getString("note");
        s.denominations = rs.getString("denominations");
        return s;
    }

    private static final String SELECT = "SELECT s.*, u.full_name FROM shifts s JOIN users u ON u.user_id=s.user_id ";

    public Shift openShiftOf(int userId) throws SQLException {
        return Jdbc.one(SELECT + "WHERE s.user_id=? AND s.status='OPEN' ORDER BY s.shift_id DESC LIMIT 1", ShiftService::map, userId);
    }

    public Shift find(int shiftId) throws SQLException {
        return Jdbc.one(SELECT + "WHERE s.shift_id=?", ShiftService::map, shiftId);
    }

    public Shift open(BigDecimal openingCash) throws SQLException {
        if (openingCash == null || openingCash.signum() < 0) {
            throw new AppException("Enter the opening cash in the drawer (0 or more).");
        }
        if (openShiftOf(Session.userId()) != null) {
            throw new AppException("You already have an open shift.");
        }
        int id = Jdbc.insert("INSERT INTO shifts (user_id, opening_cash) VALUES (?,?)", Session.userId(), openingCash);
        AuditService.log("OPEN_SHIFT", "SHIFT", id, "Opening cash " + Money.fmt(openingCash), null);
        Shift s = find(id);
        Session.setShift(s);
        return s;
    }

    // ------------------------------------------------------------------ cash in / out / safe drop

    public void cashMovement(String type, BigDecimal amount, String reason) throws SQLException {
        if (Session.shift() == null) {
            throw new AppException("Open a shift first.");
        }
        if (!Money.pos(amount)) {
            throw new AppException("Enter an amount greater than 0.");
        }
        if (reason == null || reason.isBlank()) {
            throw new AppException("Enter a reason (e.g. change from bank, paid for tea, moved to safe).");
        }
        int shiftId = Session.shiftId();
        if (!"CASH_IN".equals(type) && amount.compareTo(expectedCash(shiftId)) > 0) {
            throw new AppException("The drawer should only hold " + Money.cur(expectedCash(shiftId)) + ".");
        }
        Jdbc.insert("INSERT INTO cash_movements (shift_id, user_id, type, amount, reason) VALUES (?,?,?,?,?)",
                shiftId, Session.userId(), type, amount, reason.trim());
        AuditService.log(type, "SHIFT", shiftId, Money.fmt(amount) + " - " + reason.trim(), null);
    }

    public Jdbc.TableData cashMovements(int shiftId) throws SQLException {
        return Jdbc.table("SELECT m.created_at AS `Time`, m.type AS `Type`, m.amount AS `Amount`, m.reason AS `Reason`, "
                + "u.username AS `By` FROM cash_movements m JOIN users u ON u.user_id = m.user_id WHERE m.shift_id = ? "
                + "ORDER BY m.movement_id", shiftId);
    }

    // ------------------------------------------------------------------ totals

    private static BigDecimal paid(int shiftId, String method) throws SQLException {
        return Jdbc.decimal("SELECT COALESCE(SUM(amount),0) FROM payments WHERE shift_id=? AND method=?", shiftId, method);
    }

    private static BigDecimal refunded(int shiftId, String method) throws SQLException {
        return Jdbc.decimal("SELECT COALESCE(SUM(amount),0) FROM refund_payments WHERE shift_id=? AND method=?", shiftId, method);
    }

    private static BigDecimal moved(int shiftId, String type) throws SQLException {
        return Jdbc.decimal("SELECT COALESCE(SUM(amount),0) FROM cash_movements WHERE shift_id=? AND type=?", shiftId, type);
    }

    /** Totals for a shift, in display order. */
    public Map<String, BigDecimal> summary(int shiftId) throws SQLException {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        Shift s = find(shiftId);
        m.put("Opening cash", s.openingCash);
        m.put("Invoices", Jdbc.decimal("SELECT COUNT(*) FROM sales WHERE shift_id=? AND status='COMPLETED'", shiftId));
        m.put("Gross sales", Jdbc.decimal("SELECT COALESCE(SUM(grand_total),0) FROM sales WHERE shift_id=? AND status='COMPLETED'", shiftId));
        m.put("Discounts given", Jdbc.decimal("SELECT COALESCE(SUM(item_discount+cart_discount),0) FROM sales WHERE shift_id=? AND status='COMPLETED'", shiftId));
        m.put("VAT collected", Jdbc.decimal("SELECT COALESCE(SUM(vat_total),0) FROM sales WHERE shift_id=? AND status='COMPLETED'", shiftId));
        m.put("Sales on due", Jdbc.decimal("SELECT COALESCE(SUM(due_total),0) FROM sales WHERE shift_id=? AND status='COMPLETED'", shiftId));
        for (PaymentMethod pm : PaymentMethodDAO.tenderMethods()) {
            m.put("Received - " + pm.name, paid(shiftId, pm.code));
        }
        m.put("Received - Store credit", paid(shiftId, "STORE_CREDIT"));
        m.put("Due collected", Jdbc.decimal("SELECT COALESCE(SUM(amount),0) FROM payments WHERE shift_id=? AND payment_type='DUE_COLLECTION'", shiftId));
        m.put("Refunds - Cash", refunded(shiftId, "CASH"));
        m.put("Refunds - Other", Jdbc.decimal("SELECT COALESCE(SUM(amount),0) FROM refund_payments WHERE shift_id=? AND method<>'CASH'", shiftId));
        m.put("Cash in", moved(shiftId, "CASH_IN"));
        m.put("Cash out (payouts)", moved(shiftId, "CASH_OUT"));
        m.put("Safe drops", moved(shiftId, "SAFE_DROP"));
        m.put("Expected cash in drawer", expectedCash(shiftId));
        return m;
    }

    public BigDecimal expectedCash(int shiftId) throws SQLException {
        Shift s = find(shiftId);
        return Money.r2(s.openingCash.add(paid(shiftId, "CASH")).subtract(refunded(shiftId, "CASH"))
                .add(moved(shiftId, "CASH_IN")).subtract(moved(shiftId, "CASH_OUT")).subtract(moved(shiftId, "SAFE_DROP")));
    }

    /** Expected amount per non-cash method (received − refunded) for reconciliation. */
    public Map<String, BigDecimal> expectedByMethod(int shiftId) throws SQLException {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (PaymentMethod pm : PaymentMethodDAO.tenderMethods()) {
            if (!pm.isCash()) {
                m.put(pm.code, paid(shiftId, pm.code).subtract(refunded(shiftId, pm.code)));
            }
        }
        return m;
    }

    /**
     * Closes the shift with the counted cash (and its denominations) and the totals the cashier
     * declares for each card / mobile method (from terminal slips and phone statements).
     */
    public Shift close(int shiftId, BigDecimal counted, String denominations, Map<String, BigDecimal> declared, String note)
            throws SQLException {
        if (counted == null || counted.signum() < 0) {
            throw new AppException("Enter the cash counted in the drawer.");
        }
        Shift s = find(shiftId);
        if (s == null || !s.isOpen()) {
            throw new AppException("This shift is already closed.");
        }
        if (s.userId != Session.userId() && !Session.isManager()) {
            throw new AppException("You can only close your own shift.");
        }
        BigDecimal expected = expectedCash(shiftId);
        Map<String, BigDecimal> exp = expectedByMethod(shiftId);
        Database.inTransaction(con -> {
            Jdbc.update(con, "UPDATE shifts SET closed_at=NOW(), expected_cash=?, counted_cash=?, difference=?, denominations=?, "
                    + "status='CLOSED', note=? WHERE shift_id=?", expected, counted, counted.subtract(expected), denominations, note, shiftId);
            Jdbc.update(con, "DELETE FROM shift_reconciliation WHERE shift_id=?", shiftId);
            Jdbc.insert(con, "INSERT INTO shift_reconciliation (shift_id, method, expected, declared, difference) VALUES (?,?,?,?,?)",
                    shiftId, "CASH", expected, counted, counted.subtract(expected));
            for (Map.Entry<String, BigDecimal> e : exp.entrySet()) {
                BigDecimal d = declared == null || declared.get(e.getKey()) == null ? e.getValue() : declared.get(e.getKey());
                Jdbc.insert(con, "INSERT INTO shift_reconciliation (shift_id, method, expected, declared, difference) VALUES (?,?,?,?,?)",
                        shiftId, e.getKey(), e.getValue(), d, d.subtract(e.getValue()));
            }
            AuditService.log(con, "CLOSE_SHIFT", "SHIFT", shiftId, "Expected " + Money.fmt(expected) + ", counted " + Money.fmt(counted)
                    + (note == null ? "" : " - " + note));
            return null;
        });
        if (Session.shiftId() != null && Session.shiftId() == shiftId) {
            Session.setShift(null);
        }
        return find(shiftId);
    }

    public Jdbc.TableData reconciliation(int shiftId) throws SQLException {
        return Jdbc.table("SELECT method AS `Method`, expected AS `Expected`, declared AS `Declared / counted`, difference AS `Difference` "
                + "FROM shift_reconciliation WHERE shift_id = ? ORDER BY method = 'CASH' DESC, method", shiftId);
    }

    public Jdbc.TableData history() throws SQLException {
        return Jdbc.table("SELECT s.shift_id AS `Shift`, u.full_name AS `Cashier`, s.opened_at AS `Opened`, s.closed_at AS `Closed`, "
                + "s.opening_cash AS `Opening Cash`, s.expected_cash AS `Expected`, s.counted_cash AS `Counted`, "
                + "s.difference AS `Over/Short`, s.status AS `Status`, "
                + "(SELECT COUNT(*) FROM sales x WHERE x.shift_id=s.shift_id AND x.status='COMPLETED') AS `Invoices`, "
                + "(SELECT COALESCE(SUM(grand_total),0) FROM sales x WHERE x.shift_id=s.shift_id AND x.status='COMPLETED') AS `Sales`, "
                + "(SELECT COALESCE(SUM(ABS(difference)),0) FROM shift_reconciliation r WHERE r.shift_id=s.shift_id AND r.method<>'CASH') "
                + " AS `Non-cash diff.`, s.note AS `Note` FROM shifts s JOIN users u ON u.user_id=s.user_id ORDER BY s.shift_id DESC LIMIT 500");
    }
}
