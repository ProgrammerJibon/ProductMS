package com.productms.service;

import com.productms.db.Jdbc;
import com.productms.model.Shift;
import com.productms.util.AppException;
import com.productms.util.Money;
import com.productms.util.Session;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cashier shifts: opening float, cash expected at close, and the shift (Z) report. */
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
        Shift s = find(id);
        Session.setShift(s);
        return s;
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
        for (String method : new String[]{"CASH", "CARD", "BKASH", "NAGAD", "ROCKET", "STORE_CREDIT"}) {
            m.put("Received - " + com.productms.model.Payment.label(method),
                    Jdbc.decimal("SELECT COALESCE(SUM(amount),0) FROM payments WHERE shift_id=? AND method=?", shiftId, method));
        }
        m.put("Due collected", Jdbc.decimal("SELECT COALESCE(SUM(amount),0) FROM payments WHERE shift_id=? AND payment_type='DUE_COLLECTION'", shiftId));
        m.put("Refunds - Cash", Jdbc.decimal("SELECT COALESCE(SUM(refund_amount),0) FROM sales_returns WHERE shift_id=? AND refund_method='CASH'", shiftId));
        m.put("Refunds - Other", Jdbc.decimal("SELECT COALESCE(SUM(refund_amount),0) FROM sales_returns WHERE shift_id=? AND refund_method NOT IN ('CASH','NONE')", shiftId));
        m.put("Expected cash in drawer", expectedCash(shiftId));
        return m;
    }

    public BigDecimal expectedCash(int shiftId) throws SQLException {
        Shift s = find(shiftId);
        BigDecimal cashIn = Jdbc.decimal("SELECT COALESCE(SUM(amount),0) FROM payments WHERE shift_id=? AND method='CASH'", shiftId);
        BigDecimal cashOut = Jdbc.decimal("SELECT COALESCE(SUM(refund_amount),0) FROM sales_returns WHERE shift_id=? AND refund_method='CASH'", shiftId);
        return Money.r2(s.openingCash.add(cashIn).subtract(cashOut));
    }

    public Shift close(int shiftId, BigDecimal counted, String note) throws SQLException {
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
        Jdbc.update("UPDATE shifts SET closed_at=NOW(), expected_cash=?, counted_cash=?, difference=?, status='CLOSED', note=? "
                + "WHERE shift_id=?", expected, counted, counted.subtract(expected), note, shiftId);
        if (Session.shiftId() != null && Session.shiftId() == shiftId) {
            Session.setShift(null);
        }
        return find(shiftId);
    }

    public Jdbc.TableData history() throws SQLException {
        return Jdbc.table("SELECT s.shift_id AS `Shift`, u.full_name AS `Cashier`, s.opened_at AS `Opened`, s.closed_at AS `Closed`, "
                + "s.opening_cash AS `Opening Cash`, s.expected_cash AS `Expected`, s.counted_cash AS `Counted`, "
                + "s.difference AS `Over/Short`, s.status AS `Status`, "
                + "(SELECT COUNT(*) FROM sales x WHERE x.shift_id=s.shift_id AND x.status='COMPLETED') AS `Invoices`, "
                + "(SELECT COALESCE(SUM(grand_total),0) FROM sales x WHERE x.shift_id=s.shift_id AND x.status='COMPLETED') AS `Sales`, "
                + "s.note AS `Note` FROM shifts s JOIN users u ON u.user_id=s.user_id ORDER BY s.shift_id DESC LIMIT 500");
    }
}
