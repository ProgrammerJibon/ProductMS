package com.productms.service;

import com.productms.db.Database;
import com.productms.db.Jdbc;
import com.productms.model.Cart;
import com.productms.model.CartItem;
import com.productms.model.Payment;
import com.productms.model.ReturnLine;
import com.productms.model.Sale;
import com.productms.model.SaleItem;
import com.productms.model.Variant;
import com.productms.util.AppException;
import com.productms.util.Money;
import com.productms.util.Session;
import com.productms.util.Settings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checkout, cancellation, returns / exchanges and due collection. Every operation runs in a
 * single database transaction, so stock, money and the ledger stay consistent.
 */
public class SaleService {

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private static String number(String prefix, int id) {
        return prefix + "-" + LocalDate.now().format(NO_DATE) + "-" + String.format("%05d", id);
    }

    private static void requireShift() {
        if (Settings.getBool("require_shift") && Session.shift() == null) {
            throw new AppException("No cash shift is open.\nOpen a shift first (Shifts → Open Shift) so cash can be tracked.");
        }
    }

    // ================================================================== checkout

    /**
     * Completes a sale.
     *
     * @param tendered payments as handed over by the customer. CASH may exceed what is due
     *                 (change is returned); card / mobile payments may not.
     */
    public Sale checkout(Cart cart, List<Payment> tendered, String note) throws SQLException {
        cart.recalculate();
        if (cart.isEmpty()) {
            throw new AppException("The cart is empty.");
        }
        requireShift();
        for (CartItem it : cart.items) {
            Variant v = it.variant;
            if (!Money.pos(it.quantity)) {
                throw new AppException("Quantity of " + v.displayName() + " must be greater than 0.");
            }
            if (v.trackSerial && it.serials.size() != it.quantity.intValue()) {
                throw new AppException(v.displayName() + " needs " + Money.qty(it.quantity) + " serial number(s) selected.");
            }
        }

        BigDecimal grand = cart.grandTotal;
        BigDecimal credit = Money.min(Money.nz(cart.storeCredit), grand);
        BigDecimal creditLeft = Money.nz(cart.storeCredit).subtract(credit);
        BigDecimal nonCash = BigDecimal.ZERO;
        BigDecimal cashTendered = BigDecimal.ZERO;
        for (Payment p : tendered) {
            if (!Money.pos(p.amount)) {
                throw new AppException("Payment amounts must be greater than 0.");
            }
            if ("CASH".equals(p.method)) {
                cashTendered = cashTendered.add(p.amount);
            } else {
                if (p.reference == null || p.reference.isBlank()) {
                    throw new AppException(Payment.label(p.method) + " payment needs a reference "
                            + ("CARD".equals(p.method) ? "(last 4 digits / approval code)." : "(transaction ID)."));
                }
                nonCash = nonCash.add(p.amount);
            }
        }
        BigDecimal dueAfterCredit = grand.subtract(credit);
        if (nonCash.compareTo(dueAfterCredit) > 0) {
            throw new AppException("Card / mobile payments (" + Money.cur(nonCash) + ") cannot be more than the amount due ("
                    + Money.cur(dueAfterCredit) + ").");
        }
        BigDecimal cashApplied = Money.min(cashTendered, dueAfterCredit.subtract(nonCash));
        BigDecimal change = cashTendered.subtract(cashApplied).add(creditLeft);
        BigDecimal paid = credit.add(nonCash).add(cashApplied);
        BigDecimal due = grand.subtract(paid);
        if (due.signum() > 0 && (cart.customer == null || cart.customer.walkIn)) {
            throw new AppException("Partial / due sales need a registered customer.\nSelect or add a customer first (F4).");
        }
        String paymentStatus = due.signum() <= 0 ? "PAID" : (paid.signum() > 0 ? "PARTIAL" : "DUE");
        BigDecimal tenderedTotal = cashTendered.add(nonCash).add(Money.nz(cart.storeCredit));
        int userId = Session.userId();
        Integer shiftId = Session.shiftId();
        BigDecimal cashTenderedF = cashTendered;

        int saleId = Database.inTransaction(con -> {
            int id = Jdbc.insert(con, "INSERT INTO sales (invoice_no, customer_id, user_id, shift_id, price_mode, subtotal, "
                    + "item_discount, cart_discount, vat_total, grand_total, paid_total, due_total, tendered_total, "
                    + "change_amount, payment_status, note) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    "TMP-" + System.nanoTime(), cart.customer.customerId, userId, shiftId, cart.priceMode,
                    cart.subtotal, cart.itemDiscountTotal, cart.cartDiscount, cart.vatTotal, grand, paid, due,
                    tenderedTotal, change, paymentStatus, note);
            String invoice = number("INV", id);
            Jdbc.update(con, "UPDATE sales SET invoice_no=? WHERE sale_id=?", invoice, id);

            BigDecimal costTotal = BigDecimal.ZERO;
            for (CartItem it : cart.items) {
                Variant v = it.variant;
                Boolean sellable = Jdbc.one(con, "SELECT v.active AND p.active FROM product_variants v "
                        + "JOIN products p ON p.product_id=v.product_id WHERE v.variant_id=?", rs -> rs.getBoolean(1), v.variantId);
                if (sellable == null || !sellable) {
                    throw new AppException(v.displayName() + " is inactive and cannot be sold.");
                }
                int itemId = Jdbc.insert(con, "INSERT INTO sale_items (sale_id, variant_id, product_name, variant_label, sku, "
                        + "unit_name, quantity, unit_price, discount_amount, vat_percent, vat_amount, line_total, serial_numbers) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        id, v.variantId, v.productName, v.label().isEmpty() ? null : v.label(), v.sku, v.unitShort,
                        it.quantity, it.unitPrice, it.totalDiscount(), v.vatPercent, it.vatAmount, it.lineTotal,
                        it.serials.isEmpty() ? null : String.join(", ", it.serials));

                List<InventoryService.Allocation> allocs = InventoryService.deductForSale(con, v.variantId, it.quantity,
                        invoice, userId);
                BigDecimal lineCost = BigDecimal.ZERO;
                for (InventoryService.Allocation a : allocs) {
                    Jdbc.insert(con, "INSERT INTO sale_item_allocations (sale_item_id, variant_id, batch_id, quantity, unit_cost) "
                            + "VALUES (?,?,?,?,?)", itemId, a.variantId, a.batchId, a.quantity, a.unitCost);
                    lineCost = lineCost.add(a.quantity.multiply(a.unitCost));
                }
                BigDecimal unitCost = lineCost.divide(it.quantity, 2, RoundingMode.HALF_UP);
                Jdbc.update(con, "UPDATE sale_items SET unit_cost=? WHERE sale_item_id=?", unitCost, itemId);
                costTotal = costTotal.add(lineCost);

                for (String serial : it.serials) {
                    int n = Jdbc.update(con, "UPDATE product_serials SET status='SOLD', sale_item_id=? "
                            + "WHERE variant_id=? AND serial_no=? AND status='IN_STOCK'", itemId, v.variantId, serial);
                    if (n == 0) {
                        throw new AppException("Serial " + serial + " is no longer in stock.");
                    }
                }
            }

            if (credit.signum() > 0 || creditLeft.signum() > 0) {
                insertPayment(con, id, "STORE_CREDIT", Money.nz(cart.storeCredit), cart.storeCreditRef, "SALE", userId, shiftId);
            }
            if (creditLeft.signum() > 0) {
                // exchange for cheaper goods: the difference is paid back in cash
                insertPayment(con, id, "CASH", creditLeft.negate(), "Exchange difference refunded", "SALE", userId, shiftId);
            }
            for (Payment p : tendered) {
                if (!"CASH".equals(p.method)) {
                    insertPayment(con, id, p.method, p.amount, p.reference.trim(), "SALE", userId, shiftId);
                }
            }
            if (cashApplied.signum() > 0) {
                insertPayment(con, id, "CASH", cashApplied,
                        "Tendered " + Money.fmt(cashTenderedF) + ", change " + Money.fmt(cashTenderedF.subtract(cashApplied)),
                        "SALE", userId, shiftId);
            }
            Jdbc.update(con, "UPDATE sales SET cost_total=? WHERE sale_id=?", Money.r2(costTotal), id);
            if (cart.heldId != null) {
                Jdbc.update(con, "DELETE FROM held_carts WHERE held_id=?", cart.heldId);
            }
            return id;
        });
        return load(saleId);
    }

    private static void insertPayment(Connection con, int saleId, String method, BigDecimal amount, String ref, String type,
            int userId, Integer shiftId) throws SQLException {
        Jdbc.insert(con, "INSERT INTO payments (sale_id, shift_id, user_id, method, amount, reference, payment_type) "
                + "VALUES (?,?,?,?,?,?,?)", saleId, shiftId, userId, method, Money.r2(amount), ref, type);
    }

    // ================================================================== cancel

    public void cancel(int saleId, String reason, String refundMethod) throws SQLException {
        if (!Session.isManager()) {
            throw new AppException("Only an admin or manager can cancel a sale.");
        }
        if (reason == null || reason.isBlank()) {
            throw new AppException("Enter the reason for cancelling.");
        }
        requireShift();
        Database.inTransaction(con -> {
            Sale s = header(con, saleId, true);
            if (s.isCancelled()) {
                throw new AppException("This sale is already cancelled.");
            }
            BigDecimal returned = Jdbc.decimal(con, "SELECT COALESCE(SUM(returned_qty),0) FROM sale_items WHERE sale_id=?", saleId);
            if (returned.signum() > 0) {
                throw new AppException("Some items of this sale were already returned. Use Return for the remaining items.");
            }
            List<Object[]> allocs = Jdbc.list(con, "SELECT a.allocation_id, a.variant_id, a.batch_id, a.quantity, a.unit_cost "
                    + "FROM sale_item_allocations a JOIN sale_items si ON si.sale_item_id=a.sale_item_id WHERE si.sale_id=?",
                    rs -> new Object[]{rs.getInt(1), rs.getInt(2), Jdbc.intOrNull(rs, "batch_id"), rs.getBigDecimal(4),
                        rs.getBigDecimal(5)}, saleId);
            for (Object[] a : allocs) {
                InventoryService.applyChange(con, (Integer) a[1], (Integer) a[2], (BigDecimal) a[3], "SALE_CANCEL",
                        (BigDecimal) a[4], s.invoiceNo, null, "Sale cancelled", Session.userId());
                Jdbc.update(con, "UPDATE sale_item_allocations SET returned_qty=quantity WHERE allocation_id=?", a[0]);
            }
            Jdbc.update(con, "UPDATE product_serials ps JOIN sale_items si ON si.sale_item_id = ps.sale_item_id "
                    + "SET ps.status='IN_STOCK', ps.sale_item_id=NULL WHERE si.sale_id=?", saleId);
            BigDecimal refund = s.paidTotal;
            String method = refund.signum() > 0 ? refundMethod : "NONE";
            int rid = Jdbc.insert(con, "INSERT INTO sales_returns (return_no, sale_id, user_id, shift_id, return_type, "
                    + "refund_method, total_amount, refund_amount, reason) VALUES (?,?,?,?,?,?,?,?,?)",
                    "TMP-" + System.nanoTime(), saleId, Session.userId(), Session.shiftId(), "REFUND", method,
                    s.grandTotal, refund, "Sale cancelled: " + reason.trim());
            Jdbc.update(con, "UPDATE sales_returns SET return_no=? WHERE return_id=?", number("CXL", rid), rid);
            Jdbc.update(con, "UPDATE sales SET status='CANCELLED', cancelled_at=NOW(), cancelled_by=?, cancel_reason=? "
                    + "WHERE sale_id=?", Session.userId(), reason.trim(), saleId);
            return null;
        });
    }

    // ================================================================== returns / exchange

    /** Result of a return. */
    public static class ReturnResult {
        public String returnNo;
        public BigDecimal totalAmount;
        public BigDecimal dueAdjusted;
        public BigDecimal refundAmount;
        public boolean exchange;
    }

    /**
     * Returns items of a sale. Good items go back to stock (same batch); damaged items are
     * recorded and written off. Money first reduces any due on the invoice, the rest is refunded
     * (or given as store credit for an exchange).
     */
    public ReturnResult processReturn(int saleId, List<ReturnLine> lines, boolean exchange, String refundMethod,
            String reason) throws SQLException {
        if (lines.isEmpty()) {
            throw new AppException("Enter a return quantity for at least one item.");
        }
        if (reason == null || reason.isBlank()) {
            throw new AppException("Enter the reason for the return.");
        }
        requireShift();
        return Database.inTransaction(con -> {
            Sale s = header(con, saleId, true);
            if (s.isCancelled()) {
                throw new AppException("This sale was cancelled.");
            }
            int days = Settings.getInt("return_days", 7);
            long age = ChronoUnit.DAYS.between(s.createdAt.toLocalDate(), LocalDate.now());
            if (age > days && !Session.isManager()) {
                throw new AppException("This invoice is " + age + " days old. The return period is " + days
                        + " days.\nAsk a manager to process it.");
            }
            int rid = Jdbc.insert(con, "INSERT INTO sales_returns (return_no, sale_id, user_id, shift_id, return_type, "
                    + "refund_method, total_amount, reason) VALUES (?,?,?,?,?,?,0,?)",
                    "TMP-" + System.nanoTime(), saleId, Session.userId(), Session.shiftId(), exchange ? "EXCHANGE" : "REFUND",
                    "NONE", reason.trim());
            String returnNo = number("RET", rid);
            Jdbc.update(con, "UPDATE sales_returns SET return_no=? WHERE return_id=?", returnNo, rid);

            BigDecimal total = BigDecimal.ZERO;
            for (ReturnLine rl : lines) {
                SaleItem si = Jdbc.one(con, "SELECT * FROM sale_items WHERE sale_item_id=? AND sale_id=? FOR UPDATE",
                        SaleService::mapItem, rl.saleItemId, saleId);
                if (si == null) {
                    throw new AppException("Item does not belong to this invoice.");
                }
                if (!Money.pos(rl.quantity) || rl.quantity.compareTo(si.returnable()) > 0) {
                    throw new AppException("Return quantity for " + si.displayName() + " must be between 0 and "
                            + Money.qty(si.returnable()) + ".");
                }
                if (si.serialNumbers != null && !si.serialNumbers.isBlank()) {
                    if (rl.serials.size() != rl.quantity.intValue()) {
                        throw new AppException("Select " + Money.qty(rl.quantity) + " serial number(s) being returned for "
                                + si.displayName() + ".");
                    }
                    for (String serial : rl.serials) {
                        int n = Jdbc.update(con, "UPDATE product_serials SET status=?, sale_item_id=NULL "
                                + "WHERE serial_no=? AND sale_item_id=?",
                                "DAMAGED".equals(rl.condition) ? "DAMAGED" : "IN_STOCK", serial, si.saleItemId);
                        if (n == 0) {
                            throw new AppException("Serial " + serial + " was not sold on this invoice line.");
                        }
                    }
                }
                BigDecimal amount = Money.r2(si.lineTotal.multiply(rl.quantity).divide(si.quantity, 6, RoundingMode.HALF_UP));
                total = total.add(amount);
                Jdbc.insert(con, "INSERT INTO sales_return_items (return_id, sale_item_id, quantity, amount, item_condition) "
                        + "VALUES (?,?,?,?,?)", rid, si.saleItemId, rl.quantity, amount, rl.condition);
                Jdbc.update(con, "UPDATE sale_items SET returned_qty = returned_qty + ? WHERE sale_item_id=?",
                        rl.quantity, si.saleItemId);
                restock(con, si, rl.quantity, "DAMAGED".equals(rl.condition), returnNo);
            }

            BigDecimal dueAdjust = Money.min(s.dueTotal, total);
            BigDecimal refund = total.subtract(dueAdjust);
            if (dueAdjust.signum() > 0) {
                BigDecimal newDue = s.dueTotal.subtract(dueAdjust);
                Jdbc.update(con, "UPDATE sales SET due_total=?, payment_status=? WHERE sale_id=?", newDue,
                        newDue.signum() == 0 ? "PAID" : (s.paidTotal.signum() > 0 ? "PARTIAL" : "DUE"), saleId);
            }
            String method = refund.signum() <= 0 ? "NONE" : (exchange ? "STORE_CREDIT" : refundMethod);
            String type = exchange ? "EXCHANGE" : (refund.signum() <= 0 && dueAdjust.signum() > 0 ? "DUE_ADJUST" : "REFUND");
            Jdbc.update(con, "UPDATE sales_returns SET total_amount=?, refund_amount=?, refund_method=?, return_type=? "
                    + "WHERE return_id=?", total, refund, method, type, rid);

            ReturnResult r = new ReturnResult();
            r.returnNo = returnNo;
            r.totalAmount = total;
            r.dueAdjusted = dueAdjust;
            r.refundAmount = refund;
            r.exchange = exchange;
            return r;
        });
    }

    /** Puts returned quantity back into the same variants / batches it came from. */
    private void restock(Connection con, SaleItem si, BigDecimal qty, boolean damaged, String returnNo) throws SQLException {
        List<Object[]> allocs = Jdbc.list(con, "SELECT allocation_id, variant_id, batch_id, quantity, returned_qty, unit_cost "
                + "FROM sale_item_allocations WHERE sale_item_id=? ORDER BY allocation_id",
                rs -> new Object[]{rs.getInt(1), rs.getInt(2), Jdbc.intOrNull(rs, "batch_id"), rs.getBigDecimal(4),
                    rs.getBigDecimal(5), rs.getBigDecimal(6)}, si.saleItemId);
        Map<Integer, BigDecimal> perVariant = new LinkedHashMap<>();
        for (Object[] a : allocs) {
            perVariant.merge((Integer) a[1], (BigDecimal) a[3], BigDecimal::add);
        }
        for (Map.Entry<Integer, BigDecimal> e : perVariant.entrySet()) {
            BigDecimal need = Money.r3(e.getValue().multiply(qty).divide(si.quantity, 6, RoundingMode.HALF_UP));
            for (Object[] a : allocs) {
                if (need.signum() <= 0) {
                    break;
                }
                if (!a[1].equals(e.getKey())) {
                    continue;
                }
                BigDecimal left = ((BigDecimal) a[3]).subtract((BigDecimal) a[4]);
                BigDecimal take = Money.min(need, left);
                if (take.signum() <= 0) {
                    continue;
                }
                Integer batch = (Integer) a[2];
                InventoryService.applyChange(con, e.getKey(), batch, take, "RETURN", (BigDecimal) a[5], returnNo, null,
                        "Customer return" + (damaged ? " (damaged)" : ""), Session.userId());
                if (damaged) {
                    InventoryService.applyChange(con, e.getKey(), batch, take.negate(), "DAMAGED", (BigDecimal) a[5], returnNo,
                            null, "Returned damaged - written off", Session.userId());
                }
                Jdbc.update(con, "UPDATE sale_item_allocations SET returned_qty = returned_qty + ? WHERE allocation_id=?",
                        take, a[0]);
                a[4] = ((BigDecimal) a[4]).add(take);
                need = need.subtract(take);
            }
        }
    }

    // ================================================================== due collection

    /** Collects money against a customer's due invoices, oldest first. Returns the invoices paid. */
    public List<String> collectDue(int customerId, BigDecimal amount, String method, String reference) throws SQLException {
        if (!Money.pos(amount)) {
            throw new AppException("Enter the amount received.");
        }
        if (!"CASH".equals(method) && (reference == null || reference.isBlank())) {
            throw new AppException("Enter the transaction reference.");
        }
        requireShift();
        return Database.inTransaction(con -> {
            List<Object[]> dues = Jdbc.list(con, "SELECT sale_id, invoice_no, due_total, paid_total FROM sales "
                    + "WHERE customer_id=? AND status='COMPLETED' AND due_total > 0 ORDER BY created_at, sale_id FOR UPDATE",
                    rs -> new Object[]{rs.getInt(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4)}, customerId);
            BigDecimal totalDue = BigDecimal.ZERO;
            for (Object[] d : dues) {
                totalDue = totalDue.add((BigDecimal) d[2]);
            }
            if (amount.compareTo(totalDue) > 0) {
                throw new AppException("Amount is more than the total due (" + Money.cur(totalDue) + ").");
            }
            List<String> invoices = new ArrayList<>();
            BigDecimal left = amount;
            for (Object[] d : dues) {
                if (left.signum() <= 0) {
                    break;
                }
                BigDecimal pay = Money.min(left, (BigDecimal) d[2]);
                BigDecimal newDue = ((BigDecimal) d[2]).subtract(pay);
                Jdbc.update(con, "UPDATE sales SET paid_total = paid_total + ?, due_total = ?, payment_status = ? WHERE sale_id=?",
                        pay, newDue, newDue.signum() == 0 ? "PAID" : "PARTIAL", d[0]);
                insertPayment(con, (Integer) d[0], method, pay, reference, "DUE_COLLECTION", Session.userId(), Session.shiftId());
                invoices.add(d[1] + " (" + Money.fmt(pay) + ")");
                left = left.subtract(pay);
            }
            return invoices;
        });
    }

    // ================================================================== queries

    private static final String SALE_SELECT = "SELECT s.*, c.name AS customer_name, c.phone AS customer_phone, "
            + "c.address AS customer_address, c.is_walk_in, u.full_name AS cashier_name, "
            + "(SELECT COUNT(*) FROM sale_items si WHERE si.sale_id = s.sale_id) AS item_count, "
            + "(SELECT COALESCE(SUM(r.total_amount),0) FROM sales_returns r WHERE r.sale_id = s.sale_id "
            + "  AND r.return_no NOT LIKE 'CXL%') AS returned_total "
            + "FROM sales s JOIN customers c ON c.customer_id = s.customer_id JOIN users u ON u.user_id = s.user_id ";

    private static Sale mapSale(ResultSet rs) throws SQLException {
        Sale s = new Sale();
        s.saleId = rs.getInt("sale_id");
        s.invoiceNo = rs.getString("invoice_no");
        s.customerId = rs.getInt("customer_id");
        s.customerName = rs.getString("customer_name");
        s.customerPhone = rs.getString("customer_phone");
        s.customerAddress = rs.getString("customer_address");
        s.walkIn = rs.getBoolean("is_walk_in");
        s.userId = rs.getInt("user_id");
        s.cashierName = rs.getString("cashier_name");
        s.shiftId = Jdbc.intOrNull(rs, "shift_id");
        s.priceMode = rs.getString("price_mode");
        s.subtotal = rs.getBigDecimal("subtotal");
        s.itemDiscount = rs.getBigDecimal("item_discount");
        s.cartDiscount = rs.getBigDecimal("cart_discount");
        s.vatTotal = rs.getBigDecimal("vat_total");
        s.grandTotal = rs.getBigDecimal("grand_total");
        s.paidTotal = rs.getBigDecimal("paid_total");
        s.dueTotal = rs.getBigDecimal("due_total");
        s.tenderedTotal = rs.getBigDecimal("tendered_total");
        s.changeAmount = rs.getBigDecimal("change_amount");
        s.costTotal = rs.getBigDecimal("cost_total");
        s.paymentStatus = rs.getString("payment_status");
        s.status = rs.getString("status");
        s.note = rs.getString("note");
        s.createdAt = Jdbc.time(rs, "created_at");
        s.cancelledAt = Jdbc.time(rs, "cancelled_at");
        s.cancelReason = rs.getString("cancel_reason");
        s.itemCount = rs.getInt("item_count");
        s.returnedTotal = rs.getBigDecimal("returned_total");
        return s;
    }

    static SaleItem mapItem(ResultSet rs) throws SQLException {
        SaleItem i = new SaleItem();
        i.saleItemId = rs.getInt("sale_item_id");
        i.saleId = rs.getInt("sale_id");
        i.variantId = rs.getInt("variant_id");
        i.productName = rs.getString("product_name");
        i.variantLabel = rs.getString("variant_label");
        i.sku = rs.getString("sku");
        i.unitName = rs.getString("unit_name");
        i.quantity = rs.getBigDecimal("quantity");
        i.unitPrice = rs.getBigDecimal("unit_price");
        i.discountAmount = rs.getBigDecimal("discount_amount");
        i.vatPercent = rs.getBigDecimal("vat_percent");
        i.vatAmount = rs.getBigDecimal("vat_amount");
        i.lineTotal = rs.getBigDecimal("line_total");
        i.unitCost = rs.getBigDecimal("unit_cost");
        i.returnedQty = rs.getBigDecimal("returned_qty");
        i.serialNumbers = rs.getString("serial_numbers");
        return i;
    }

    private Sale header(Connection con, int saleId, boolean lock) throws SQLException {
        Sale s = Jdbc.one(con, SALE_SELECT + "WHERE s.sale_id=?" + (lock ? " FOR UPDATE" : ""), SaleService::mapSale, saleId);
        if (s == null) {
            throw new AppException("Sale not found.");
        }
        return s;
    }

    public Sale load(int saleId) throws SQLException {
        try (Connection con = Database.getConnection()) {
            Sale s = header(con, saleId, false);
            s.items = Jdbc.list(con, "SELECT * FROM sale_items WHERE sale_id=? ORDER BY sale_item_id", SaleService::mapItem, saleId);
            s.payments = Jdbc.list(con, "SELECT * FROM payments WHERE sale_id=? ORDER BY payment_id", rs -> {
                Payment p = new Payment(rs.getString("method"), rs.getBigDecimal("amount"), rs.getString("reference"));
                p.paymentId = rs.getInt("payment_id");
                p.paymentType = rs.getString("payment_type");
                p.createdAt = Jdbc.time(rs, "created_at");
                return p;
            }, saleId);
            return s;
        }
    }

    public Sale findByInvoice(String invoiceNo) throws SQLException {
        Integer id = Jdbc.one("SELECT sale_id FROM sales WHERE invoice_no=?", rs -> rs.getInt(1), invoiceNo.trim());
        return id == null ? null : load(id);
    }

    public Integer lastSaleId() throws SQLException {
        return Jdbc.one("SELECT MAX(sale_id) FROM sales WHERE user_id=?", rs -> {
            int v = rs.getInt(1);
            return rs.wasNull() ? null : v;
        }, Session.userId());
    }

    public List<Sale> history(LocalDate from, LocalDate to, String text, String status, String payStatus, Integer customerId)
            throws SQLException {
        StringBuilder sql = new StringBuilder(SALE_SELECT).append("WHERE DATE(s.created_at) BETWEEN ? AND ? ");
        List<Object> params = new ArrayList<>();
        params.add(from);
        params.add(to);
        if (text != null && !text.isBlank()) {
            String like = "%" + text.trim() + "%";
            sql.append("AND (s.invoice_no LIKE ? OR c.name LIKE ? OR c.phone LIKE ?) ");
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (status != null && !"ALL".equals(status)) {
            sql.append("AND s.status=? ");
            params.add(status);
        }
        if (payStatus != null && !"ALL".equals(payStatus)) {
            sql.append("AND s.payment_status=? ");
            params.add(payStatus);
        }
        if (customerId != null) {
            sql.append("AND s.customer_id=? ");
            params.add(customerId);
        }
        sql.append("ORDER BY s.sale_id DESC LIMIT 2000");
        return Jdbc.list(sql.toString(), SaleService::mapSale, params.toArray());
    }

    public Jdbc.TableData returns(LocalDate from, LocalDate to, String text) throws SQLException {
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        return Jdbc.table("SELECT r.return_no AS `Return No`, r.created_at AS `Date`, s.invoice_no AS `Invoice`, "
                + "c.name AS `Customer`, r.return_type AS `Type`, r.total_amount AS `Goods Value`, "
                + "r.refund_amount AS `Refunded`, r.refund_method AS `Method`, r.reason AS `Reason`, u.username AS `By` "
                + "FROM sales_returns r JOIN sales s ON s.sale_id=r.sale_id JOIN customers c ON c.customer_id=s.customer_id "
                + "JOIN users u ON u.user_id=r.user_id WHERE DATE(r.created_at) BETWEEN ? AND ? "
                + "AND (r.return_no LIKE ? OR s.invoice_no LIKE ? OR c.name LIKE ?) ORDER BY r.return_id DESC",
                from, to, like, like, like);
    }

    /** Serials sold on a sale item that have not been returned yet. */
    public List<String> soldSerials(int saleItemId) throws SQLException {
        return Jdbc.list("SELECT serial_no FROM product_serials WHERE sale_item_id=? AND status='SOLD' ORDER BY serial_no",
                rs -> rs.getString(1), saleItemId);
    }

    /** Helper used by tests and the cart: checks there are no duplicate serials across a cart. */
    public static void checkDuplicateSerials(Cart cart) {
        Set<String> seen = new HashSet<>();
        for (CartItem it : cart.items) {
            for (String s : it.serials) {
                if (!seen.add(it.variant.variantId + ":" + s)) {
                    throw new AppException("Serial " + s + " is in the cart twice.");
                }
            }
        }
    }
}
