package com.productms.service;

import com.productms.dao.CustomerDAO;
import com.productms.dao.ProductDAO;
import com.productms.model.Cart;
import com.productms.model.CartItem;
import com.productms.model.Customer;
import com.productms.model.Payment;
import com.productms.model.Sale;
import com.productms.model.SaleItem;
import com.productms.model.Variant;
import com.productms.util.AppException;
import com.productms.util.Money;
import com.productms.util.Session;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Offline selling: when the database cannot be reached at checkout, the sale is written to a
 * local file in {@code offline-queue/} and a provisional receipt (OFF-...) is printed. Every
 * minute (and on "Sync now") queued sales are replayed through the normal checkout with the same
 * idempotency key, so a sale can never be booked twice. Sales that can no longer be booked
 * (e.g. stock was sold elsewhere meanwhile) are kept as {@code .failed} for a manager to review.
 * <p>
 * Offline sales are limited to what the till can check by itself: full payment (no due), no price
 * overrides, no manager approvals and no serial-numbered items.
 */
public final class OfflineQueue {

    private static final Path DIR = Paths.get("offline-queue");
    private static final DateTimeFormatter REF = DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final Object LOCK = new Object();

    private OfflineQueue() {
    }

    /** True when the exception means "database unreachable" (not a business error). */
    public static boolean isConnectionError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLNonTransientConnectionException || t instanceof SQLTransientConnectionException
                    || t.getClass().getName().contains("CommunicationsException")) {
                return true;
            }
            if (t instanceof SQLException) {
                String state = ((SQLException) t).getSQLState();
                if (state != null && state.startsWith("08")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Rejects carts that cannot be sold without the database. */
    public static void checkOfflineAllowed(Cart cart, List<Payment> payments) {
        BigDecimal paid = BigDecimal.ZERO;
        for (Payment p : payments) {
            paid = paid.add(p.amount);
        }
        if (paid.compareTo(cart.grandTotal.subtract(Money.nz(cart.storeCredit))) < 0) {
            throw new AppException("The database is offline: only fully paid sales can be taken (no due).");
        }
        for (CartItem it : cart.items) {
            if (it.variant.trackSerial) {
                throw new AppException("The database is offline: serial-numbered items (" + it.variant.productName
                        + ") cannot be sold until it is back.");
            }
            if (it.overridePrice != null || it.approvedBy != null) {
                throw new AppException("The database is offline: price overrides and approvals are not possible.");
            }
        }
        if (Money.nz(cart.storeCredit).signum() > 0 || cart.approvedBy != null) {
            throw new AppException("The database is offline: exchanges and approved sales must wait until it is back.");
        }
    }

    /** Saves the sale locally and returns the provisional reference. */
    public static String enqueue(Cart cart, List<Payment> payments) throws IOException {
        synchronized (LOCK) {
            Files.createDirectories(DIR);
            String ref = "OFF-" + LocalDateTime.now().format(REF) + "-" + Session.userId();
            Properties p = new Properties();
            p.setProperty("ref", ref);
            p.setProperty("clientTxnId", cart.clientTxnId);
            p.setProperty("soldAt", LocalDateTime.now().toString());
            p.setProperty("userId", String.valueOf(Session.userId()));
            p.setProperty("shiftId", Session.shiftId() == null ? "" : String.valueOf(Session.shiftId()));
            p.setProperty("customerId", String.valueOf(cart.customer.customerId));
            p.setProperty("priceMode", cart.priceMode);
            p.setProperty("cartDiscountType", cart.cartDiscountType);
            p.setProperty("cartDiscountValue", Money.nz(cart.cartDiscountValue).toPlainString());
            p.setProperty("note", cart.note == null ? "" : cart.note);
            p.setProperty("grandTotal", cart.grandTotal.toPlainString());
            p.setProperty("items", String.valueOf(cart.items.size()));
            for (int i = 0; i < cart.items.size(); i++) {
                CartItem it = cart.items.get(i);
                String k = "item." + i + ".";
                p.setProperty(k + "variantId", String.valueOf(it.variant.variantId));
                p.setProperty(k + "qty", it.quantity.toPlainString());
                p.setProperty(k + "unitPrice", it.unitPrice.toPlainString());
                p.setProperty(k + "discType", it.itemDiscountType);
                p.setProperty(k + "discValue", Money.nz(it.itemDiscountValue).toPlainString());
                p.setProperty(k + "note", it.note == null ? "" : it.note);
                p.setProperty(k + "excluded", it.excludedComponents.stream().map(String::valueOf).collect(Collectors.joining(",")));
            }
            p.setProperty("payments", String.valueOf(payments.size()));
            for (int i = 0; i < payments.size(); i++) {
                Payment pay = payments.get(i);
                p.setProperty("pay." + i + ".method", pay.method);
                p.setProperty("pay." + i + ".amount", pay.amount.toPlainString());
                p.setProperty("pay." + i + ".reference", pay.reference == null ? "" : pay.reference);
            }
            Path tmp = DIR.resolve(ref + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "Offline sale - do not edit");
            }
            Files.move(tmp, DIR.resolve(ref + ".sale"), StandardCopyOption.ATOMIC_MOVE);
            return ref;
        }
    }

    /** A receipt-ready sale built from the cart (not stored in the database yet). */
    public static Sale provisionalSale(Cart cart, List<Payment> payments, String ref) {
        Sale s = new Sale();
        s.invoiceNo = ref;
        s.offlineRef = ref;
        s.createdAt = LocalDateTime.now();
        s.customerName = cart.customer.name;
        s.customerPhone = cart.customer.phone;
        s.cashierName = Session.user().fullName;
        s.priceMode = cart.priceMode;
        s.subtotal = cart.subtotal;
        s.itemDiscount = cart.itemDiscountTotal;
        s.cartDiscount = cart.cartDiscount;
        s.vatTotal = cart.vatTotal;
        s.grandTotal = cart.grandTotal;
        BigDecimal tendered = BigDecimal.ZERO;
        BigDecimal nonCash = BigDecimal.ZERO;
        for (Payment p : payments) {
            tendered = tendered.add(p.amount);
            if (!p.isCash()) {
                nonCash = nonCash.add(p.amount);
            }
        }
        s.tenderedTotal = tendered;
        s.paidTotal = cart.grandTotal;
        s.changeAmount = Money.max(BigDecimal.ZERO, tendered.subtract(cart.grandTotal));
        s.note = "OFFLINE SALE - provisional receipt, will be synced";
        for (CartItem it : cart.items) {
            SaleItem si = new SaleItem();
            si.productName = it.variant.productName;
            si.variantLabel = it.variant.label().isEmpty() ? null : it.variant.label();
            si.sku = it.variant.sku;
            si.unitName = it.variant.unitShort;
            si.quantity = it.quantity;
            si.unitPrice = it.unitPrice;
            si.discountAmount = it.totalDiscount();
            si.vatPercent = it.variant.vatPercent;
            si.vatAmount = it.vatAmount;
            si.lineTotal = it.lineTotal;
            si.note = it.note;
            s.items.add(si);
        }
        BigDecimal cashLeft = cart.grandTotal.subtract(nonCash);
        for (Payment p : payments) {
            Payment x = new Payment(p.method, p.isCash() ? Money.min(p.amount, Money.max(BigDecimal.ZERO, cashLeft)) : p.amount, p.reference);
            if (p.isCash()) {
                cashLeft = cashLeft.subtract(x.amount);
            }
            s.payments.add(x);
        }
        return s;
    }

    /** Entry of the queue for display. */
    public static class Entry {
        public Path file;
        public String ref;
        public String soldAt;
        public BigDecimal total;
        public String status;
        public String error;
    }

    public static List<Entry> list() throws IOException {
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(DIR)) {
            return out;
        }
        try (Stream<Path> s = Files.list(DIR)) {
            for (Path f : s.filter(x -> x.toString().endsWith(".sale") || x.toString().endsWith(".failed")).sorted().collect(Collectors.toList())) {
                Properties p = read(f);
                Entry e = new Entry();
                e.file = f;
                e.ref = p.getProperty("ref");
                e.soldAt = p.getProperty("soldAt");
                e.total = new BigDecimal(p.getProperty("grandTotal", "0"));
                e.status = f.toString().endsWith(".failed") ? "FAILED" : "PENDING";
                e.error = p.getProperty("error");
                out.add(e);
            }
        }
        return out;
    }

    public static int pendingCount() {
        try {
            return (int) list().stream().filter(e -> "PENDING".equals(e.status)).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static Properties read(Path f) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        }
        return p;
    }

    /** Books all pending offline sales. Returns how many were booked. */
    public static int syncAll() throws IOException {
        int n = 0;
        synchronized (LOCK) {
            for (Entry e : list()) {
                if ("PENDING".equals(e.status) && sync(e.file)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Books one queued sale. Returns true when booked (or already booked). */
    public static boolean sync(Path file) throws IOException {
        Properties p = read(file);
        try {
            SaleService.CheckoutRequest r = new SaleService.CheckoutRequest();
            ProductDAO products = new ProductDAO();
            Customer customer = new CustomerDAO().findById(Integer.parseInt(p.getProperty("customerId")));
            Cart cart = new Cart("Offline " + p.getProperty("ref"));
            cart.customer = customer == null ? new CustomerDAO().walkIn() : customer;
            cart.clientTxnId = p.getProperty("clientTxnId");
            cart.priceMode = p.getProperty("priceMode", "RETAIL");
            cart.cartDiscountType = p.getProperty("cartDiscountType", "AMOUNT");
            cart.cartDiscountValue = new BigDecimal(p.getProperty("cartDiscountValue", "0"));
            cart.note = p.getProperty("note");
            cart.approvedBy = "OFFLINE (" + p.getProperty("ref") + ")";
            int items = Integer.parseInt(p.getProperty("items", "0"));
            for (int i = 0; i < items; i++) {
                String k = "item." + i + ".";
                Variant v = products.findVariant(Integer.parseInt(p.getProperty(k + "variantId")));
                if (v == null) {
                    throw new AppException("A product of this sale no longer exists.");
                }
                CartItem it = new CartItem(v, new BigDecimal(p.getProperty(k + "qty")));
                it.itemDiscountType = p.getProperty(k + "discType", "AMOUNT");
                it.itemDiscountValue = new BigDecimal(p.getProperty(k + "discValue", "0"));
                it.note = p.getProperty(k + "note", "").isBlank() ? null : p.getProperty(k + "note");
                String ex = p.getProperty(k + "excluded", "");
                if (!ex.isBlank()) {
                    for (String x : ex.split(",")) {
                        it.excludedComponents.add(Integer.parseInt(x));
                    }
                }
                cart.items.add(it);
            }
            cart.recalculate();
            for (int i = 0; i < items; i++) {
                CartItem it = cart.items.get(i);
                BigDecimal charged = new BigDecimal(p.getProperty("item." + i + ".unitPrice"));
                if (charged.compareTo(it.unitPrice) != 0) {
                    it.overridePrice = charged;      // keep what the customer was actually charged
                    it.approvedBy = cart.approvedBy;
                }
            }
            int pays = Integer.parseInt(p.getProperty("payments", "0"));
            for (int i = 0; i < pays; i++) {
                String ref = p.getProperty("pay." + i + ".reference", "");
                r.payments.add(new Payment(p.getProperty("pay." + i + ".method"), new BigDecimal(p.getProperty("pay." + i + ".amount")),
                        ref.isBlank() ? null : ref));
            }
            r.cart = cart;
            r.userId = Integer.parseInt(p.getProperty("userId"));
            String shift = p.getProperty("shiftId", "");
            r.shiftId = shift.isBlank() ? null : Integer.valueOf(shift);
            r.offlineRef = p.getProperty("ref");
            r.soldAt = LocalDateTime.parse(p.getProperty("soldAt"));
            Sale s = new SaleService().checkout(r);
            Files.createDirectories(DIR.resolve("synced"));
            Files.move(file, DIR.resolve("synced").resolve(file.getFileName().toString().replace(".failed", ".sale")),
                    StandardCopyOption.REPLACE_EXISTING);
            AuditService.log("OFFLINE_SYNC", "INVOICE", s.invoiceNo, "Booked offline sale " + p.getProperty("ref"), null);
            return true;
        } catch (SQLException e) {
            if (isConnectionError(e)) {
                return false;
            }
            markFailed(file, p, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            markFailed(file, p, e.getMessage());
            return false;
        }
    }

    private static void markFailed(Path file, Properties p, String error) throws IOException {
        p.setProperty("error", error == null ? "Unknown error" : error.replace('\n', ' '));
        Path failed = DIR.resolve(p.getProperty("ref") + ".failed");
        try (OutputStream out = Files.newOutputStream(failed)) {
            p.store(out, "Offline sale that could not be booked");
        }
        if (!file.equals(failed)) {
            Files.deleteIfExists(file);
        }
    }

    /** Puts a failed sale back in the queue (after fixing stock, for example). */
    public static void retry(Path failed) throws IOException {
        Properties p = read(failed);
        p.remove("error");
        Path pending = DIR.resolve(p.getProperty("ref") + ".sale");
        try (OutputStream out = Files.newOutputStream(pending)) {
            p.store(out, "Offline sale - do not edit");
        }
        Files.deleteIfExists(failed);
        sync(pending);
    }

    /** Gives up on a failed sale (money must be settled manually); kept in offline-queue/discarded. */
    public static void discard(Path failed, String reason) throws IOException {
        if (reason == null || reason.isBlank()) {
            throw new AppException("Enter why this offline sale is discarded.");
        }
        Files.createDirectories(DIR.resolve("discarded"));
        Files.move(failed, DIR.resolve("discarded").resolve(failed.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        AuditService.log("OFFLINE_DISCARD", "INVOICE", failed.getFileName().toString(), reason, null);
    }

    public static List<String> pendingRefs() throws IOException {
        return list().stream().map(e -> e.ref).collect(Collectors.toList());
    }

    static List<String> split(String s) {
        return s == null || s.isBlank() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }
}
