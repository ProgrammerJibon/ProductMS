package com.productms.service;

import com.productms.dao.PaymentMethodDAO;
import com.productms.db.Database;
import com.productms.db.Jdbc;
import com.productms.model.BundleItem;
import com.productms.model.Cart;
import com.productms.model.CartItem;
import com.productms.model.Payment;
import com.productms.model.PaymentMethod;
import com.productms.model.ReturnLine;
import com.productms.model.Sale;
import com.productms.model.SaleItem;
import com.productms.model.User;
import com.productms.model.Variant;
import com.productms.util.AppException;
import com.productms.util.ApprovalRequiredException;
import com.productms.util.Money;
import com.productms.util.Session;
import com.productms.util.Settings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checkout, cancellation (reversal), returns / exchanges and due collection. Every operation is
 * one database transaction: the sale, its lines, stock deduction, payments and invoice number
 * succeed together or not at all.
 */
public class SaleService {

    private static void requireShift(Integer shiftId) {
        if (Settings.getBool("require_shift") && shiftId == null) {
            throw new AppException("No cash shift is open.\nOpen a shift first (Shifts → Open Shift) so cash can be tracked.");
        }
    }

    // ================================================================== checkout

    /** Everything needed to complete a sale. */
    public static class CheckoutRequest {
        public Cart cart;
        public List<Payment> payments = new ArrayList<>();
        public int userId;
        public Integer shiftId;
        public String offlineRef;
        public LocalDateTime soldAt;
    }

    public Sale checkout(Cart cart, List<Payment> tendered, String note) throws SQLException {
        CheckoutRequest r = new CheckoutRequest();
        r.cart = cart;
        r.payments = tendered;
        r.userId = Session.userId();
        r.shiftId = Session.shiftId();
        if (note != null) {
            cart.note = note;
        }
        return checkout(r);
    }

    /**
     * Completes a sale. Calling it twice with the same {@code cart.clientTxnId} (double click,
     * retry after a timeout, offline re-sync) returns the first sale instead of creating another.
     */
    public Sale checkout(CheckoutRequest r) throws SQLException {
        Cart cart = r.cart;
        Integer existing = Jdbc.one("SELECT sale_id FROM sales WHERE client_txn_id = ?", rs -> rs.getInt(1), cart.clientTxnId);
        if (existing != null) {
            return load(existing);
        }
        cart.recalculate();
        if (cart.isEmpty()) {
            throw new AppException("The cart is empty.");
        }
        requireShift(r.shiftId);
        for (CartItem it : cart.items) {
            Variant v = it.variant;
            if (!Money.pos(it.quantity)) {
                throw new AppException("Quantity of " + v.displayName() + " must be greater than 0.");
            }
            if (v.trackSerial && it.serials.size() != it.quantity.intValue()) {
                throw new AppException(v.displayName() + " needs " + Money.qty(it.quantity) + " serial number(s) selected.");
            }
        }

        // ---- money
        BigDecimal grand = cart.grandTotal;
        BigDecimal credit = Money.min(Money.nz(cart.storeCredit), grand);
        BigDecimal creditLeft = Money.nz(cart.storeCredit).subtract(credit);
        BigDecimal nonCash = BigDecimal.ZERO;
        BigDecimal cashTendered = BigDecimal.ZERO;
        BigDecimal accountCredit = BigDecimal.ZERO;
        Set<String> refsSeen = new HashSet<>();
        for (Payment p : r.payments) {
            if (!Money.pos(p.amount)) {
                throw new AppException("Payment amounts must be greater than 0.");
            }
            PaymentMethod m = PaymentMethodDAO.get(p.method);
            if (m == null || (!m.active && !"STORE_CREDIT".equals(p.method))) {
                throw new AppException("Payment method " + p.method + " is not available.");
            }
            if ("STORE_CREDIT".equals(p.method)) {
                accountCredit = accountCredit.add(p.amount);
                continue;
            }
            if (m.isCash()) {
                cashTendered = cashTendered.add(p.amount);
                continue;
            }
            String err = PaymentMethodDAO.validateReference(p.method, p.reference);
            if (err != null) {
                throw new AppException(err);
            }
            p.reference = p.reference.trim().toUpperCase(Locale.ROOT);
            if (!refsSeen.add(p.method + ":" + p.reference)) {
                throw new AppException(m.name + " reference " + p.reference + " is entered twice.");
            }
            nonCash = nonCash.add(p.amount);
        }
        BigDecimal dueAfterCredit = grand.subtract(credit).subtract(accountCredit);
        if (dueAfterCredit.signum() < 0) {
            throw new AppException("Store credit used is more than the invoice total.");
        }
        if (nonCash.compareTo(dueAfterCredit) > 0) {
            throw new AppException("Card / mobile payments (" + Money.cur(nonCash) + ") cannot be more than the amount due ("
                    + Money.cur(dueAfterCredit) + ").");
        }
        BigDecimal cashApplied = Money.min(cashTendered, dueAfterCredit.subtract(nonCash));
        BigDecimal change = cashTendered.subtract(cashApplied);
        String policy = Settings.get("exchange_excess_policy", "ACCOUNT");
        boolean leftoverToAccount = false;
        if (creditLeft.signum() > 0) {
            if ("BLOCK".equals(policy)) {
                throw new AppException("The exchange credit (" + Money.cur(cart.storeCredit) + ") is more than the new items ("
                        + Money.cur(grand) + "). Shop policy: add items worth at least the credit.");
            } else if ("ACCOUNT".equals(policy)) {
                if (cart.customer == null || cart.customer.walkIn) {
                    throw new AppException("The exchange leaves " + Money.cur(creditLeft) + " of credit. Shop policy keeps it on the "
                            + "customer's account: select or add the customer (F4), or add more items.");
                }
                leftoverToAccount = true;
            } else {
                change = change.add(creditLeft);
            }
        }
        BigDecimal paid = credit.add(accountCredit).add(nonCash).add(cashApplied);
        BigDecimal due = grand.subtract(paid);
        if (due.signum() > 0 && (cart.customer == null || cart.customer.walkIn)) {
            throw new AppException("Partial / due sales need a registered customer.\nSelect or add a customer first (F4).");
        }

        // ---- limits that a manager can approve
        List<String> needs = approvalReasons(r, cart, due);
        if (!needs.isEmpty() && cart.approvedBy == null) {
            throw new ApprovalRequiredException(needs);
        }

        String paymentStatus = due.signum() <= 0 ? "PAID" : (paid.signum() > 0 ? "PARTIAL" : "DUE");
        BigDecimal tenderedTotal = cashTendered.add(nonCash).add(accountCredit).add(Money.nz(cart.storeCredit));
        BigDecimal changeF = change;
        BigDecimal accountCreditF = accountCredit;
        boolean toAccount = leftoverToAccount;

        int saleId;
        try {
            saleId = Database.inTransaction(con -> {
                for (Payment p : r.payments) {
                    if (p.reference != null && !p.isCash() && !"STORE_CREDIT".equals(p.method)
                            && Jdbc.one(con, "SELECT 1 FROM payments WHERE method = ? AND UPPER(reference) = ? LIMIT 1", rs -> 1,
                                    p.method, p.reference) != null) {
                        throw new AppException(Payment.label(p.method) + " transaction " + p.reference
                                + " was already used on another invoice. Check the customer's payment.");
                    }
                }
                if (accountCreditF.signum() > 0) {
                    BigDecimal bal = Jdbc.decimal(con, "SELECT store_credit FROM customers WHERE customer_id=? FOR UPDATE",
                            cart.customer.customerId);
                    if (bal.compareTo(accountCreditF) < 0) {
                        throw new AppException("The customer's store credit is only " + Money.cur(bal) + ".");
                    }
                }
                String invoice = DocNumbers.next(con, "INVOICE");
                int id = Jdbc.insert(con, "INSERT INTO sales (invoice_no, client_txn_id, offline_ref, customer_id, user_id, shift_id, "
                        + "price_mode, subtotal, item_discount, cart_discount, vat_total, grand_total, paid_total, due_total, "
                        + "tendered_total, change_amount, payment_status, note, approved_by, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        invoice, cart.clientTxnId, r.offlineRef, cart.customer.customerId, r.userId, r.shiftId, cart.priceMode,
                        cart.subtotal, cart.itemDiscountTotal, cart.cartDiscount, cart.vatTotal, grand, paid, due, tenderedTotal,
                        changeF, paymentStatus, cart.note, cart.approvedBy, r.soldAt == null ? LocalDateTime.now() : r.soldAt);

                BigDecimal costTotal = BigDecimal.ZERO;
                List<String> overrides = new ArrayList<>();
                for (CartItem it : cart.items) {
                    Variant v = it.variant;
                    Boolean sellable = Jdbc.one(con, "SELECT v.active AND p.active AND NOT p.deleted FROM product_variants v "
                            + "JOIN products p ON p.product_id=v.product_id WHERE v.variant_id=?", rs -> rs.getBoolean(1), v.variantId);
                    if (sellable == null || !sellable) {
                        throw new AppException(v.displayName() + " is inactive and cannot be sold.");
                    }
                    String excludedNames = null;
                    if (!it.excludedComponents.isEmpty()) {
                        List<String> names = new ArrayList<>();
                        for (BundleItem b : v.components) {
                            if (it.excludedComponents.contains(b.componentVariantId)) {
                                names.add(b.componentName);
                            }
                        }
                        excludedNames = "Without: " + String.join(", ", names);
                    }
                    int itemId = Jdbc.insert(con, "INSERT INTO sale_items (sale_id, variant_id, product_name, variant_label, sku, "
                            + "unit_name, quantity, unit_price, original_price, discount_amount, vat_percent, vat_amount, line_total, "
                            + "serial_numbers, warranty_months, excluded_components, note) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            id, v.variantId, v.productName, v.label().isEmpty() ? null : v.label(), v.sku, v.unitShort,
                            it.quantity, it.unitPrice, it.overridePrice == null ? null : it.listPrice, it.totalDiscount(),
                            v.vatPercent, it.vatAmount, it.lineTotal, it.serials.isEmpty() ? null : String.join(", ", it.serials),
                            v.warrantyMonths, excludedNames, it.note == null || it.note.isBlank() ? null : it.note.trim());
                    if (it.overridePrice != null) {
                        overrides.add(v.sku + " " + Money.fmt(it.listPrice) + "→" + Money.fmt(it.overridePrice)
                                + (it.approvedBy == null ? "" : " (approved by " + it.approvedBy + ")"));
                    }
                    List<InventoryService.Allocation> allocs = InventoryService.deductForSale(con, v.variantId, it.quantity,
                            invoice, r.userId, it.excludedComponents);
                    BigDecimal lineCost = BigDecimal.ZERO;
                    for (InventoryService.Allocation a : allocs) {
                        Jdbc.insert(con, "INSERT INTO sale_item_allocations (sale_item_id, variant_id, batch_id, quantity, unit_cost) "
                                + "VALUES (?,?,?,?,?)", itemId, a.variantId, a.batchId, a.quantity, a.unitCost);
                        lineCost = lineCost.add(a.quantity.multiply(a.unitCost));
                    }
                    Jdbc.update(con, "UPDATE sale_items SET unit_cost=? WHERE sale_item_id=?",
                            lineCost.divide(it.quantity, 2, RoundingMode.HALF_UP), itemId);
                    costTotal = costTotal.add(lineCost);
                    for (String serial : it.serials) {
                        int n = Jdbc.update(con, "UPDATE product_serials SET status='SOLD', sale_item_id=? "
                                + "WHERE variant_id=? AND serial_no=? AND status='IN_STOCK'", itemId, v.variantId, serial);
                        if (n == 0) {
                            throw new AppException("Serial " + serial + " is no longer in stock.");
                        }
                    }
                }

                if (Money.nz(cart.storeCredit).signum() > 0) {
                    insertPayment(con, id, "STORE_CREDIT", cart.storeCredit, cart.storeCreditRef, "SALE", r.userId, r.shiftId);
                }
                if (creditLeft.signum() > 0) {
                    if (toAccount) {
                        insertPayment(con, id, "STORE_CREDIT", creditLeft.negate(), "Balance kept as store credit", "SALE", r.userId, r.shiftId);
                        addCustomerCredit(con, cart.customer.customerId, creditLeft, invoice, "Exchange balance " + cart.storeCreditRef);
                    } else {
                        insertPayment(con, id, "CASH", creditLeft.negate(), "Exchange difference refunded", "SALE", r.userId, r.shiftId);
                    }
                }
                if (accountCreditF.signum() > 0) {
                    insertPayment(con, id, "STORE_CREDIT", accountCreditF, "Customer account", "SALE", r.userId, r.shiftId);
                    addCustomerCredit(con, cart.customer.customerId, accountCreditF.negate(), invoice, "Used on invoice");
                }
                for (Payment p : r.payments) {
                    if (!p.isCash() && !"STORE_CREDIT".equals(p.method)) {
                        insertPayment(con, id, p.method, p.amount, p.reference, "SALE", r.userId, r.shiftId);
                    }
                }
                if (cashApplied.signum() > 0) {
                    BigDecimal cashTenderedOnly = cashApplied.add(changeF).subtract(toAccount ? BigDecimal.ZERO : creditLeft);
                    insertPayment(con, id, "CASH", cashApplied, "Tendered " + Money.fmt(cashTenderedOnly) + ", change "
                            + Money.fmt(changeF), "SALE", r.userId, r.shiftId);
                }
                Jdbc.update(con, "UPDATE sales SET cost_total=? WHERE sale_id=?", Money.r2(costTotal), id);
                if (cart.heldId != null) {
                    Jdbc.update(con, "DELETE FROM held_carts WHERE held_id=?", cart.heldId);
                }
                AuditService.log(con, "SALE", "INVOICE", invoice, "Total " + Money.fmt(grand) + ", paid " + Money.fmt(paid)
                        + (due.signum() > 0 ? ", due " + Money.fmt(due) : "")
                        + (cart.cartDiscount.signum() > 0 ? ", cart discount " + Money.fmt(cart.cartDiscount) : "")
                        + (overrides.isEmpty() ? "" : ", price overrides: " + String.join("; ", overrides))
                        + (r.offlineRef == null ? "" : ", synced from offline " + r.offlineRef)
                        + (needs.isEmpty() ? "" : ", approved: " + String.join("; ", needs)), cart.approvedBy);
                return id;
            });
        } catch (SQLIntegrityConstraintViolationException dup) {
            Integer again = Jdbc.one("SELECT sale_id FROM sales WHERE client_txn_id = ?", rs -> rs.getInt(1), cart.clientTxnId);
            if (again != null) {
                return load(again);
            }
            throw dup;
        }
        return load(saleId);
    }

    /** Limits a manager must approve for this cart (empty = nothing to approve). */
    private List<String> approvalReasons(CheckoutRequest r, Cart cart, BigDecimal due) throws SQLException {
        List<String> out = new ArrayList<>();
        User u = Jdbc.one("SELECT * FROM users WHERE user_id = ?", rs -> {
            User x = new User();
            x.role = rs.getString("role");
            x.canOverridePrice = rs.getBoolean("can_override_price");
            x.canSellOnCredit = rs.getBoolean("can_sell_on_credit");
            x.maxDiscountPercent = rs.getBigDecimal("max_discount_percent");
            x.maxSaleAmount = rs.getBigDecimal("max_sale_amount");
            return x;
        }, r.userId);
        if (u == null) {
            throw new AppException("Unknown cashier.");
        }
        boolean manager = u.isManager();
        BigDecimal maxLine = Settings.bd("max_line_discount_percent", "100");
        BigDecimal personal = u.maxDiscountPercent != null ? u.maxDiscountPercent : Settings.bd("max_cashier_discount", "10");
        for (CartItem it : cart.items) {
            String name = it.variant.displayName();
            if (it.overridePrice != null && !manager && !u.canOverridePrice && it.approvedBy == null) {
                out.add("Price override on " + name);
            }
            if (it.variant.minPrice.signum() > 0 && it.effectiveUnitNet().compareTo(it.variant.minPrice) < 0 && !manager
                    && it.approvedBy == null) {
                out.add(name + " would sell at " + Money.fmt(it.effectiveUnitNet()) + ", below its minimum price "
                        + Money.fmt(it.variant.minPrice));
            }
            BigDecimal pct = it.discountPercent();
            if (pct.compareTo(maxLine) > 0 && it.approvedBy == null && !Session.isAdmin()) {
                out.add(name + ": total discount " + pct + "% is above the shop limit of " + Money.qty(maxLine) + "%");
            }
            BigDecimal given = it.gross.signum() == 0 ? BigDecimal.ZERO
                    : it.itemDiscount.add(it.cartDiscountShare).multiply(Money.HUNDRED).divide(it.gross, 2, RoundingMode.HALF_UP);
            if (!manager && given.compareTo(personal) > 0 && it.approvedBy == null) {
                out.add(name + ": discount " + given + "% is above your limit of " + Money.qty(personal) + "%");
            }
        }
        BigDecimal base = cart.subtotal.subtract(cart.itemDiscountTotal);
        if (cart.cartDiscount.signum() > 0 && !Session.isAdmin()) {
            BigDecimal maxPct = Settings.bd("max_cart_discount_percent", "100");
            BigDecimal maxAmt = Settings.bd("max_cart_discount_amount", "0");
            BigDecimal cartPct = base.signum() == 0 ? BigDecimal.ZERO
                    : cart.cartDiscount.multiply(Money.HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
            if (cartPct.compareTo(maxPct) > 0) {
                out.add("Cart discount " + cartPct + "% is above the maximum of " + Money.qty(maxPct) + "%");
            }
            if (maxAmt.signum() > 0 && cart.cartDiscount.compareTo(maxAmt) > 0) {
                out.add("Cart discount " + Money.cur(cart.cartDiscount) + " is above the maximum of " + Money.cur(maxAmt));
            }
        }
        if (u.maxSaleAmount != null && cart.grandTotal.compareTo(u.maxSaleAmount) > 0 && !manager) {
            out.add("Invoice total " + Money.cur(cart.grandTotal) + " is above your sales limit of " + Money.cur(u.maxSaleAmount));
        }
        if (due.signum() > 0) {
            if (!u.canSellOnCredit && !manager) {
                out.add("You are not allowed to sell on credit (due " + Money.cur(due) + ")");
            }
            Object[] c = Jdbc.one("SELECT credit_limit, payment_terms_days FROM customers WHERE customer_id = ?",
                    rs -> new Object[]{rs.getBigDecimal(1), rs.getInt(2)}, cart.customer.customerId);
            BigDecimal limit = c == null ? null : (BigDecimal) c[0];
            BigDecimal owing = Jdbc.decimal("SELECT COALESCE(SUM(due_total),0) FROM sales WHERE customer_id=? AND status='COMPLETED'",
                    cart.customer.customerId);
            if (limit != null && owing.add(due).compareTo(limit) > 0) {
                out.add(cart.customer.name + " would owe " + Money.cur(owing.add(due)) + ", above the credit limit of "
                        + Money.cur(limit));
            }
            int terms = c == null ? 30 : (Integer) c[1];
            int overdue = Jdbc.count("SELECT COUNT(*) FROM sales WHERE customer_id=? AND status='COMPLETED' AND due_total > 0 "
                    + "AND created_at < NOW() - INTERVAL ? DAY", cart.customer.customerId, terms);
            if (overdue > 0) {
                out.add(cart.customer.name + " has " + overdue + " overdue invoice(s) (older than " + terms + " days)");
            }
        }
        return out;
    }

    private static void insertPayment(Connection con, int saleId, String method, BigDecimal amount, String ref, String type,
            int userId, Integer shiftId) throws SQLException {
        Jdbc.insert(con, "INSERT INTO payments (sale_id, shift_id, user_id, method, amount, reference, payment_type) "
                + "VALUES (?,?,?,?,?,?,?)", saleId, shiftId, userId, method, Money.r2(amount), ref, type);
    }

    static void addCustomerCredit(Connection con, int customerId, BigDecimal amount, String ref, String note) throws SQLException {
        int n = Jdbc.update(con, "UPDATE customers SET store_credit = store_credit + ? WHERE customer_id=? AND store_credit + ? >= 0",
                amount, customerId, amount);
        if (n == 0) {
            throw new AppException("The customer does not have enough store credit.");
        }
        Jdbc.insert(con, "INSERT INTO customer_credit_txns (customer_id, amount, reference, note, user_id) VALUES (?,?,?,?,?)",
                customerId, Money.r2(amount), ref, note, Session.userId() == 0 ? null : Session.userId());
    }

    // ================================================================== refunds (shared by cancel and return)

    /** Money already refunded on a sale. */
    private static BigDecimal refunded(Connection con, int saleId) throws SQLException {
        return Jdbc.decimal(con, "SELECT COALESCE(SUM(refund_amount),0) FROM sales_returns WHERE sale_id=?", saleId);
    }

    /**
     * Records refund rows. "ORIGINAL" splits the amount over the sale's original payments
     * (card / mobile first, cash last); any other code refunds everything by that method.
     */
    private static List<String> recordRefund(Connection con, int returnId, int saleId, BigDecimal amount, String method,
            Integer shiftId) throws SQLException {
        List<String> parts = new ArrayList<>();
        if (amount.signum() <= 0) {
            return parts;
        }
        if (!"ORIGINAL".equals(method)) {
            Jdbc.insert(con, "INSERT INTO refund_payments (return_id, method, amount, shift_id) VALUES (?,?,?,?)",
                    returnId, method, amount, shiftId);
            parts.add(Payment.label(method) + " " + Money.fmt(amount));
            return parts;
        }
        List<Object[]> pays = Jdbc.list(con, "SELECT p.payment_id, p.method, p.amount - COALESCE((SELECT SUM(rp.amount) FROM refund_payments rp "
                + "WHERE rp.original_payment_id = p.payment_id),0), p.reference FROM payments p WHERE p.sale_id=? AND p.amount > 0 "
                + "ORDER BY p.method = 'CASH', p.payment_id DESC",
                rs -> new Object[]{rs.getInt(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4)}, saleId);
        BigDecimal left = amount;
        for (Object[] p : pays) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal take = Money.min(left, (BigDecimal) p[2]);
            if (take.signum() <= 0) {
                continue;
            }
            String m = (String) p[1];
            Jdbc.insert(con, "INSERT INTO refund_payments (return_id, original_payment_id, method, amount, reference, shift_id) "
                    + "VALUES (?,?,?,?,?,?)", returnId, p[0], m, take, p[3], shiftId);
            if ("STORE_CREDIT".equals(m)) {
                Integer customer = Jdbc.one(con, "SELECT customer_id FROM sales WHERE sale_id=?", rs -> rs.getInt(1), saleId);
                addCustomerCredit(con, customer, take, "REFUND", "Refund of store credit");
            }
            parts.add(Payment.label(m) + " " + Money.fmt(take) + (p[3] == null || "CASH".equals(m) ? "" : " (orig. ref " + p[3] + ")"));
            left = left.subtract(take);
        }
        if (left.signum() > 0) {
            Jdbc.insert(con, "INSERT INTO refund_payments (return_id, method, amount, shift_id) VALUES (?,?,?,?)",
                    returnId, "CASH", left, shiftId);
            parts.add("Cash " + Money.fmt(left));
        }
        return parts;
    }

    // ================================================================== cancel (reversal)

    /**
     * Cancels a sale by reversal: the invoice, its lines and payments stay as they were; a
     * CANCEL return records the reversed goods, VAT and cost, and refund rows reverse each
     * payment (to its original method unless another is chosen). Stock is restored.
     */
    public String cancel(int saleId, String reason, String refundMethod) throws SQLException {
        if (!Session.isManager()) {
            throw new AppException("Only an admin or manager can cancel a sale.");
        }
        if (reason == null || reason.isBlank()) {
            throw new AppException("Enter the reason for cancelling.");
        }
        requireShift(Session.shiftId());
        return Database.inTransaction(con -> {
            Sale s = header(con, saleId, true);
            if (s.isCancelled()) {
                throw new AppException("This sale is already cancelled.");
            }
            if (Jdbc.decimal(con, "SELECT COALESCE(SUM(returned_qty),0) FROM sale_items WHERE sale_id=?", saleId).signum() > 0) {
                throw new AppException("Some items of this sale were already returned. Use Return for the remaining items.");
            }
            BigDecimal cost = BigDecimal.ZERO;
            for (Object[] a : Jdbc.list(con, "SELECT a.allocation_id, a.variant_id, a.batch_id, a.quantity, a.unit_cost "
                    + "FROM sale_item_allocations a JOIN sale_items si ON si.sale_item_id=a.sale_item_id WHERE si.sale_id=?",
                    rs -> new Object[]{rs.getInt(1), rs.getInt(2), Jdbc.intOrNull(rs, "batch_id"), rs.getBigDecimal(4),
                        rs.getBigDecimal(5)}, saleId)) {
                InventoryService.applyChange(con, (Integer) a[1], (Integer) a[2], (BigDecimal) a[3], "SALE_CANCEL",
                        (BigDecimal) a[4], s.invoiceNo, null, "Sale cancelled", Session.userId());
                Jdbc.update(con, "UPDATE sale_item_allocations SET returned_qty=quantity WHERE allocation_id=?", a[0]);
                cost = cost.add(((BigDecimal) a[3]).multiply((BigDecimal) a[4]));
            }
            Jdbc.update(con, "UPDATE product_serials ps JOIN sale_items si ON si.sale_item_id = ps.sale_item_id "
                    + "SET ps.status='IN_STOCK', ps.sale_item_id=NULL WHERE si.sale_id=?", saleId);
            BigDecimal refund = s.paidTotal.subtract(refunded(con, saleId));
            String returnNo = DocNumbers.next(con, "CANCEL");
            int rid = Jdbc.insert(con, "INSERT INTO sales_returns (return_no, sale_id, user_id, shift_id, return_type, refund_method, "
                    + "total_amount, vat_amount, cost_amount, refund_amount, reason) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    returnNo, saleId, Session.userId(), Session.shiftId(), "CANCEL",
                    refund.signum() > 0 ? (refundMethod == null ? "ORIGINAL" : refundMethod) : "NONE",
                    s.grandTotal, s.vatTotal, Money.r2(cost), refund, "Sale cancelled: " + reason.trim());
            for (SaleItem it : Jdbc.list(con, "SELECT * FROM sale_items WHERE sale_id=?", SaleService::mapItem, saleId)) {
                Jdbc.insert(con, "INSERT INTO sales_return_items (return_id, sale_item_id, quantity, amount, vat_amount, cost_amount) "
                        + "VALUES (?,?,?,?,?,?)", rid, it.saleItemId, it.quantity, it.lineTotal, it.vatAmount,
                        Money.r2(it.unitCost.multiply(it.quantity)));
            }
            List<String> parts = recordRefund(con, rid, saleId, refund, refundMethod == null ? "ORIGINAL" : refundMethod, Session.shiftId());
            Jdbc.update(con, "UPDATE sales SET status='CANCELLED', cancelled_at=NOW(), cancelled_by=?, cancel_reason=? WHERE sale_id=?",
                    Session.userId(), reason.trim(), saleId);
            AuditService.log(con, "CANCEL", "INVOICE", s.invoiceNo, reason.trim() + "; refunded " + String.join(", ", parts));
            return returnNo + (parts.isEmpty() ? "" : "\nRefund: " + String.join(", ", parts));
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
        public List<String> refundParts = new ArrayList<>();
    }

    public ReturnResult processReturn(int saleId, List<ReturnLine> lines, boolean exchange, String refundMethod,
            String reason) throws SQLException {
        return processReturn(saleId, lines, exchange, refundMethod, reason, null);
    }

    /**
     * Returns items of a sale. Good items go back to stock (same batch); damaged items go to the
     * damaged bucket. Money reduces any due first; the refund can never exceed what was actually
     * paid minus earlier refunds, and refunds above the approval amount need a manager.
     */
    public ReturnResult processReturn(int saleId, List<ReturnLine> lines, boolean exchange, String refundMethod,
            String reason, String approvedBy) throws SQLException {
        if (lines.isEmpty()) {
            throw new AppException("Enter a return quantity for at least one item.");
        }
        if (reason == null || reason.isBlank()) {
            throw new AppException("Enter the reason for the return.");
        }
        requireShift(Session.shiftId());
        return Database.inTransaction(con -> {
            Sale s = header(con, saleId, true);
            if (s.isCancelled()) {
                throw new AppException("This sale was cancelled.");
            }
            int days = Settings.getInt("return_days", 7);
            long age = ChronoUnit.DAYS.between(s.createdAt.toLocalDate(), LocalDate.now());
            if (age > days && !Session.isManager() && approvedBy == null) {
                throw new ApprovalRequiredException(List.of("Invoice is " + age + " days old; the return period is " + days + " days"));
            }
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal vat = BigDecimal.ZERO;
            List<Object[]> pending = new ArrayList<>();
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
                BigDecimal amount;
                BigDecimal lineVat;
                if (rl.quantity.compareTo(si.returnable()) == 0) {
                    // last units of the line: take exactly what is left, so rounding never drifts
                    BigDecimal[] prev = Jdbc.one(con, "SELECT COALESCE(SUM(amount),0), COALESCE(SUM(vat_amount),0) "
                            + "FROM sales_return_items WHERE sale_item_id=?", rs -> new BigDecimal[]{rs.getBigDecimal(1), rs.getBigDecimal(2)},
                            si.saleItemId);
                    amount = si.lineTotal.subtract(prev[0]);
                    lineVat = si.vatAmount.subtract(prev[1]);
                } else {
                    amount = Money.r2(si.lineTotal.multiply(rl.quantity).divide(si.quantity, 6, RoundingMode.HALF_UP));
                    lineVat = Money.r2(si.vatAmount.multiply(rl.quantity).divide(si.quantity, 6, RoundingMode.HALF_UP));
                }
                total = total.add(amount);
                vat = vat.add(lineVat);
                pending.add(new Object[]{rl, si, amount, lineVat});
            }
            BigDecimal dueAdjust = Money.min(s.dueTotal, total);
            BigDecimal refund = total.subtract(dueAdjust);
            BigDecimal refundable = s.paidTotal.subtract(refunded(con, saleId));
            if (refund.compareTo(refundable) > 0) {
                refund = Money.max(BigDecimal.ZERO, refundable);
            }
            BigDecimal limit = Settings.bd("refund_approval_amount", "0");
            if (!exchange && limit.signum() > 0 && refund.compareTo(limit) >= 0 && !Session.isManager() && approvedBy == null) {
                throw new ApprovalRequiredException(List.of("Refund of " + Money.cur(refund) + " is at or above the approval amount "
                        + Money.cur(limit)));
            }

            String returnNo = DocNumbers.next(con, "RETURN");
            int rid = Jdbc.insert(con, "INSERT INTO sales_returns (return_no, sale_id, user_id, shift_id, return_type, refund_method, "
                    + "total_amount, reason, approved_by) VALUES (?,?,?,?,?,?,0,?,?)", returnNo, saleId, Session.userId(),
                    Session.shiftId(), exchange ? "EXCHANGE" : "REFUND", "NONE", reason.trim(), approvedBy);
            BigDecimal costTotal = BigDecimal.ZERO;
            for (Object[] p : pending) {
                ReturnLine rl = (ReturnLine) p[0];
                SaleItem si = (SaleItem) p[1];
                if (si.serialNumbers != null && !si.serialNumbers.isBlank()) {
                    if (rl.serials.size() != rl.quantity.intValue()) {
                        throw new AppException("Select " + Money.qty(rl.quantity) + " serial number(s) being returned for "
                                + si.displayName() + ".");
                    }
                    for (String serial : rl.serials) {
                        int n = Jdbc.update(con, "UPDATE product_serials SET status=?, sale_item_id=NULL WHERE serial_no=? AND sale_item_id=?",
                                "DAMAGED".equals(rl.condition) ? "DAMAGED" : "IN_STOCK", serial, si.saleItemId);
                        if (n == 0) {
                            throw new AppException("Serial " + serial + " was not sold on this invoice line.");
                        }
                    }
                }
                BigDecimal cost = restock(con, si, rl.quantity, "DAMAGED".equals(rl.condition), returnNo);
                costTotal = costTotal.add(cost);
                Jdbc.insert(con, "INSERT INTO sales_return_items (return_id, sale_item_id, quantity, amount, vat_amount, cost_amount, "
                        + "item_condition) VALUES (?,?,?,?,?,?,?)", rid, si.saleItemId, rl.quantity, p[2], p[3], cost, rl.condition);
                Jdbc.update(con, "UPDATE sale_items SET returned_qty = returned_qty + ? WHERE sale_item_id=?", rl.quantity, si.saleItemId);
            }
            if (dueAdjust.signum() > 0) {
                BigDecimal newDue = s.dueTotal.subtract(dueAdjust);
                Jdbc.update(con, "UPDATE sales SET due_total=?, payment_status=? WHERE sale_id=?", newDue,
                        newDue.signum() == 0 ? "PAID" : (s.paidTotal.signum() > 0 ? "PARTIAL" : "DUE"), saleId);
            }
            String method = refund.signum() <= 0 ? "NONE" : (exchange ? "STORE_CREDIT" : refundMethod);
            String type = exchange ? "EXCHANGE" : (refund.signum() <= 0 && dueAdjust.signum() > 0 ? "DUE_ADJUST" : "REFUND");
            Jdbc.update(con, "UPDATE sales_returns SET total_amount=?, vat_amount=?, cost_amount=?, refund_amount=?, refund_method=?, "
                    + "return_type=? WHERE return_id=?", total, vat, Money.r2(costTotal), refund, method, type, rid);
            ReturnResult r = new ReturnResult();
            if (!exchange) {
                r.refundParts = recordRefund(con, rid, saleId, refund, refundMethod, Session.shiftId());
            } else if (refund.signum() > 0) {
                Jdbc.insert(con, "INSERT INTO refund_payments (return_id, method, amount, reference, shift_id) VALUES (?,?,?,?,?)",
                        rid, "STORE_CREDIT", refund, returnNo, Session.shiftId());
                r.refundParts.add("Store credit " + Money.fmt(refund));
            }
            AuditService.log(con, exchange ? "EXCHANGE" : "RETURN", "INVOICE", s.invoiceNo, returnNo + ": goods " + Money.fmt(total)
                    + ", refund " + Money.fmt(refund) + " (" + String.join(", ", r.refundParts) + "); " + reason.trim(), approvedBy);
            r.returnNo = returnNo;
            r.totalAmount = total;
            r.dueAdjusted = dueAdjust;
            r.refundAmount = refund;
            r.exchange = exchange;
            return r;
        });
    }

    /** Puts returned quantity back into the variants / batches it came from. Returns the cost restored. */
    private BigDecimal restock(Connection con, SaleItem si, BigDecimal qty, boolean damaged, String returnNo) throws SQLException {
        List<Object[]> allocs = Jdbc.list(con, "SELECT allocation_id, variant_id, batch_id, quantity, returned_qty, unit_cost "
                + "FROM sale_item_allocations WHERE sale_item_id=? ORDER BY allocation_id",
                rs -> new Object[]{rs.getInt(1), rs.getInt(2), Jdbc.intOrNull(rs, "batch_id"), rs.getBigDecimal(4),
                    rs.getBigDecimal(5), rs.getBigDecimal(6)}, si.saleItemId);
        Map<Integer, BigDecimal> perVariant = new LinkedHashMap<>();
        for (Object[] a : allocs) {
            perVariant.merge((Integer) a[1], (BigDecimal) a[3], BigDecimal::add);
        }
        BigDecimal cost = BigDecimal.ZERO;
        for (Map.Entry<Integer, BigDecimal> e : perVariant.entrySet()) {
            BigDecimal need = Money.r3(e.getValue().multiply(qty).divide(si.quantity, 6, RoundingMode.HALF_UP));
            for (Object[] a : allocs) {
                if (need.signum() <= 0) {
                    break;
                }
                if (!a[1].equals(e.getKey())) {
                    continue;
                }
                BigDecimal take = Money.min(need, ((BigDecimal) a[3]).subtract((BigDecimal) a[4]));
                if (take.signum() <= 0) {
                    continue;
                }
                Integer batch = (Integer) a[2];
                BigDecimal unitCost = (BigDecimal) a[5];
                InventoryService.applyChange(con, e.getKey(), batch, take, "RETURN", unitCost, returnNo, null,
                        "Customer return" + (damaged ? " (damaged)" : ""), Session.userId());
                if (damaged) {
                    InventoryService.applyChange(con, e.getKey(), batch, take.negate(), "DAMAGED", unitCost, returnNo, null,
                            "Returned damaged - moved to damaged stock", Session.userId());
                    InventoryService.addDamaged(con, e.getKey(), batch, take, unitCost, returnNo, "Customer return (damaged)");
                }
                Jdbc.update(con, "UPDATE sale_item_allocations SET returned_qty = returned_qty + ? WHERE allocation_id=?", take, a[0]);
                a[4] = ((BigDecimal) a[4]).add(take);
                cost = cost.add(take.multiply(unitCost));
                need = need.subtract(take);
            }
        }
        return Money.r2(cost);
    }

    // ================================================================== due collection

    public List<String> collectDue(int customerId, BigDecimal amount, String method, String reference) throws SQLException {
        if (!Money.pos(amount)) {
            throw new AppException("Enter the amount received.");
        }
        PaymentMethod m = PaymentMethodDAO.get(method);
        if (m == null || "CREDIT".equals(m.kind)) {
            throw new AppException("Choose a payment method.");
        }
        if (!m.isCash()) {
            String err = PaymentMethodDAO.validateReference(method, reference);
            if (err != null) {
                throw new AppException(err);
            }
            reference = reference.trim().toUpperCase(Locale.ROOT);
        }
        requireShift(Session.shiftId());
        String ref = reference;
        return Database.inTransaction(con -> {
            if (ref != null && !m.isCash() && Jdbc.one(con, "SELECT 1 FROM payments WHERE method=? AND UPPER(reference)=? LIMIT 1",
                    rs -> 1, method, ref) != null) {
                throw new AppException(m.name + " transaction " + ref + " was already used.");
            }
            List<Object[]> dues = Jdbc.list(con, "SELECT sale_id, invoice_no, due_total FROM sales WHERE customer_id=? "
                    + "AND status='COMPLETED' AND due_total > 0 ORDER BY created_at, sale_id FOR UPDATE",
                    rs -> new Object[]{rs.getInt(1), rs.getString(2), rs.getBigDecimal(3)}, customerId);
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
                insertPayment(con, (Integer) d[0], method, pay, ref, "DUE_COLLECTION", Session.userId(), Session.shiftId());
                invoices.add(d[1] + " (" + Money.fmt(pay) + ")");
                left = left.subtract(pay);
            }
            AuditService.log(con, "COLLECT_DUE", "CUSTOMER", customerId, Money.fmt(amount) + " by " + m.name + ": "
                    + String.join(", ", invoices));
            return invoices;
        });
    }

    // ================================================================== queries

    private static final String SALE_SELECT = "SELECT s.*, c.name AS customer_name, c.phone AS customer_phone, "
            + "c.address AS customer_address, c.is_walk_in, u.full_name AS cashier_name, "
            + "(SELECT COUNT(*) FROM sale_items si WHERE si.sale_id = s.sale_id) AS item_count, "
            + "(SELECT COALESCE(SUM(r.total_amount),0) FROM sales_returns r WHERE r.sale_id = s.sale_id AND r.return_type <> 'CANCEL') AS returned_total, "
            + "(SELECT COALESCE(SUM(r.refund_amount),0) FROM sales_returns r WHERE r.sale_id = s.sale_id) AS refunded_total "
            + "FROM sales s JOIN customers c ON c.customer_id = s.customer_id JOIN users u ON u.user_id = s.user_id ";

    private static Sale mapSale(ResultSet rs) throws SQLException {
        Sale s = new Sale();
        s.saleId = rs.getInt("sale_id");
        s.invoiceNo = rs.getString("invoice_no");
        s.clientTxnId = rs.getString("client_txn_id");
        s.offlineRef = rs.getString("offline_ref");
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
        s.approvedBy = rs.getString("approved_by");
        s.createdAt = Jdbc.time(rs, "created_at");
        s.cancelledAt = Jdbc.time(rs, "cancelled_at");
        s.cancelReason = rs.getString("cancel_reason");
        s.itemCount = rs.getInt("item_count");
        s.returnedTotal = rs.getBigDecimal("returned_total");
        s.refundedTotal = rs.getBigDecimal("refunded_total");
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
        i.originalPrice = rs.getBigDecimal("original_price");
        i.discountAmount = rs.getBigDecimal("discount_amount");
        i.vatPercent = rs.getBigDecimal("vat_percent");
        i.vatAmount = rs.getBigDecimal("vat_amount");
        i.lineTotal = rs.getBigDecimal("line_total");
        i.unitCost = rs.getBigDecimal("unit_cost");
        i.returnedQty = rs.getBigDecimal("returned_qty");
        i.serialNumbers = rs.getString("serial_numbers");
        i.warrantyMonths = rs.getInt("warranty_months");
        i.excludedComponents = rs.getString("excluded_components");
        i.note = rs.getString("note");
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
        Integer id = Jdbc.one("SELECT sale_id FROM sales WHERE invoice_no=? OR offline_ref=?", rs -> rs.getInt(1),
                invoiceNo.trim(), invoiceNo.trim());
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
            sql.append("AND (s.invoice_no LIKE ? OR s.offline_ref LIKE ? OR c.name LIKE ? OR c.phone LIKE ?) ");
            params.add(like);
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
                + "c.name AS `Customer`, r.return_type AS `Type`, r.total_amount AS `Goods Value`, r.vat_amount AS `VAT part`, "
                + "r.refund_amount AS `Refunded`, (SELECT GROUP_CONCAT(CONCAT(rp.method, ' ', rp.amount) SEPARATOR ', ') "
                + "FROM refund_payments rp WHERE rp.return_id = r.return_id) AS `Refund paid as`, r.reason AS `Reason`, "
                + "u.username AS `By`, r.approved_by AS `Approved by` "
                + "FROM sales_returns r JOIN sales s ON s.sale_id=r.sale_id JOIN customers c ON c.customer_id=s.customer_id "
                + "JOIN users u ON u.user_id=r.user_id WHERE DATE(r.created_at) BETWEEN ? AND ? "
                + "AND (r.return_no LIKE ? OR s.invoice_no LIKE ? OR c.name LIKE ?) ORDER BY r.return_id DESC",
                from, to, like, like, like);
    }

    public List<String> soldSerials(int saleItemId) throws SQLException {
        return Jdbc.list("SELECT serial_no FROM product_serials WHERE sale_item_id=? AND status='SOLD' ORDER BY serial_no",
                rs -> rs.getString(1), saleItemId);
    }
}
