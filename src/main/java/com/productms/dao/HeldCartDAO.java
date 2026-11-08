package com.productms.dao;

import com.productms.db.Database;
import com.productms.db.Jdbc;
import com.productms.model.BundleItem;
import com.productms.model.Cart;
import com.productms.model.CartItem;
import com.productms.model.Customer;
import com.productms.model.Variant;
import com.productms.service.AuditService;
import com.productms.util.Money;
import com.productms.util.Session;
import com.productms.util.Settings;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Carts put on hold. They survive a restart, optionally <b>reserve</b> their stock so other tills
 * cannot sell it, and <b>expire</b> after the configured number of hours (reservation released,
 * cart discarded).
 * <p>
 * Payload: first line "cartDiscountType:value", then one line per item:
 * {@code variantId|qty|discType|discValue|serials;..|overridePrice|note|excludedIds,..}
 */
public class HeldCartDAO {

    /** Summary row for the "held carts" list. */
    public static class Held {
        public int heldId;
        public String label;
        public String customerName;
        public int itemCount;
        public LocalDateTime createdAt;
        public LocalDateTime expiresAt;
        public String cashier;
    }

    private static String enc(String s) {
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return s == null || s.isEmpty() ? null : URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    public int hold(Cart cart) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append(cart.cartDiscountType).append(':').append(Money.nz(cart.cartDiscountValue).toPlainString()).append('\n');
        for (CartItem it : cart.items) {
            sb.append(it.variant.variantId).append('|').append(it.quantity.toPlainString()).append('|')
                    .append(it.itemDiscountType).append('|').append(Money.nz(it.itemDiscountValue).toPlainString()).append('|')
                    .append(String.join(";", it.serials)).append('|')
                    .append(it.overridePrice == null ? "" : it.overridePrice.toPlainString()).append('|')
                    .append(enc(it.note)).append('|')
                    .append(it.excludedComponents.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""))
                    .append('\n');
        }
        int hours = Math.max(1, Settings.getInt("held_cart_expiry_hours", 24));
        LocalDateTime expires = LocalDateTime.now().plusHours(hours);
        return Database.inTransaction(con -> {
            if (cart.heldId != null) {
                Jdbc.update(con, "DELETE FROM held_carts WHERE held_id=?", cart.heldId);
            }
            int id = Jdbc.insert(con, "INSERT INTO held_carts (label, customer_id, price_mode, cart_discount, payload, user_id, expires_at) "
                    + "VALUES (?,?,?,0,?,?,?)", cart.label, cart.customer == null ? null : cart.customer.customerId,
                    cart.priceMode, sb.toString(), Session.userId(), expires);
            if (Settings.getBool("reserve_held_stock")) {
                for (CartItem it : cart.items) {
                    Variant v = it.variant;
                    if (v.isBundle()) {
                        for (BundleItem b : v.components) {
                            if (!it.excludedComponents.contains(b.componentVariantId)) {
                                Jdbc.insert(con, "INSERT INTO stock_reservations (held_id, variant_id, quantity, expires_at) VALUES (?,?,?,?)",
                                        id, b.componentVariantId, it.quantity.multiply(b.quantity), expires);
                            }
                        }
                    } else {
                        Jdbc.insert(con, "INSERT INTO stock_reservations (held_id, variant_id, quantity, expires_at) VALUES (?,?,?,?)",
                                id, v.variantId, it.quantity, expires);
                    }
                }
            }
            AuditService.log(con, "HOLD", "CART", id, cart.label + " (" + cart.items.size() + " line(s))");
            return id;
        });
    }

    public List<Held> list() throws SQLException {
        purgeExpired();
        return Jdbc.list("SELECT h.*, c.name AS cname, u.username FROM held_carts h LEFT JOIN customers c "
                + "ON c.customer_id=h.customer_id JOIN users u ON u.user_id=h.user_id ORDER BY h.held_id", rs -> {
                    Held h = new Held();
                    h.heldId = rs.getInt("held_id");
                    h.label = rs.getString("label");
                    h.customerName = rs.getString("cname");
                    h.itemCount = rs.getString("payload").split("\n").length - 1;
                    h.createdAt = Jdbc.time(rs, "created_at");
                    h.expiresAt = Jdbc.time(rs, "expires_at");
                    h.cashier = rs.getString("username");
                    return h;
                });
    }

    /** Discards held carts past their expiry time (their reservations go with them). */
    public int purgeExpired() throws SQLException {
        List<Integer> ids = Jdbc.list("SELECT held_id FROM held_carts WHERE expires_at IS NOT NULL AND expires_at < NOW()",
                rs -> rs.getInt(1));
        for (int id : ids) {
            Jdbc.update("DELETE FROM held_carts WHERE held_id=?", id);
            AuditService.log("EXPIRE", "CART", id, "Held cart expired and was discarded; reservation released", null);
        }
        Jdbc.update("DELETE FROM stock_reservations WHERE expires_at < NOW()");
        return ids.size();
    }

    /** Rebuilds the cart with current product data and removes it (and its reservation) from hold. */
    public Cart resume(int heldId, ProductDAO products, CustomerDAO customers) throws SQLException {
        Object[] row = Jdbc.one("SELECT label, customer_id, price_mode, payload FROM held_carts WHERE held_id=?",
                rs -> new Object[]{rs.getString(1), Jdbc.intOrNull(rs, "customer_id"), rs.getString(3), rs.getString(4)}, heldId);
        if (row == null) {
            return null;
        }
        // release first so the cart's own reservation does not block it
        Jdbc.update("DELETE FROM held_carts WHERE held_id=?", heldId);
        Cart cart = new Cart((String) row[0]);
        Customer c = row[1] == null ? null : customers.findById((Integer) row[1]);
        cart.customer = c == null ? customers.walkIn() : c;
        cart.priceMode = (String) row[2];
        String[] lines = ((String) row[3]).split("\n");
        if (lines.length > 0 && lines[0].contains(":")) {
            String[] d = lines[0].split(":");
            cart.cartDiscountType = d[0];
            cart.cartDiscountValue = new BigDecimal(d[1]);
        }
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\\|", -1);
            if (f.length < 4) {
                continue;
            }
            Variant v = products.findVariant(Integer.parseInt(f[0]));
            if (v == null) {
                continue;
            }
            CartItem it = new CartItem(v, new BigDecimal(f[1]));
            it.itemDiscountType = f[2];
            it.itemDiscountValue = new BigDecimal(f[3]);
            if (f.length > 4 && !f[4].isBlank()) {
                it.serials = new ArrayList<>(Arrays.asList(f[4].split(";")));
            }
            if (f.length > 5 && !f[5].isBlank()) {
                it.overridePrice = new BigDecimal(f[5]);
            }
            if (f.length > 6) {
                it.note = dec(f[6]);
            }
            if (f.length > 7 && !f[7].isBlank()) {
                for (String x : f[7].split(",")) {
                    it.excludedComponents.add(Integer.parseInt(x));
                }
            }
            cart.items.add(it);
        }
        AuditService.log("RESUME", "CART", heldId, cart.label, null);
        cart.recalculate();
        return cart;
    }

    public void delete(int heldId) throws SQLException {
        Jdbc.update("DELETE FROM held_carts WHERE held_id=?", heldId);
        AuditService.log("DISCARD", "CART", heldId, "Held cart discarded", null);
    }
}
