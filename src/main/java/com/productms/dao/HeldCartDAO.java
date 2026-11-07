package com.productms.dao;

import com.productms.db.Jdbc;
import com.productms.model.Cart;
import com.productms.model.CartItem;
import com.productms.model.Customer;
import com.productms.model.Variant;
import com.productms.util.Money;
import com.productms.util.Session;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Saves carts put on hold so they can be resumed later (even after a restart).
 * Payload: one line per item "variantId|qty|discountType|discountValue|serial1;serial2".
 */
public class HeldCartDAO {

    /** Summary row for the "held carts" list. */
    public static class Held {
        public int heldId;
        public String label;
        public String customerName;
        public int itemCount;
        public LocalDateTime createdAt;
        public String cashier;
    }

    public int hold(Cart cart) throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (CartItem it : cart.items) {
            sb.append(it.variant.variantId).append('|').append(it.quantity.toPlainString()).append('|')
                    .append(it.itemDiscountType).append('|').append(Money.nz(it.itemDiscountValue).toPlainString()).append('|')
                    .append(String.join(";", it.serials)).append('\n');
        }
        String cartDisc = cart.cartDiscountType + ":" + Money.nz(cart.cartDiscountValue).toPlainString();
        if (cart.heldId != null) {
            Jdbc.update("DELETE FROM held_carts WHERE held_id=?", cart.heldId);
        }
        return Jdbc.insert("INSERT INTO held_carts (label, customer_id, price_mode, cart_discount, payload, user_id) "
                + "VALUES (?,?,?,0,?,?)", cart.label, cart.customer == null ? null : cart.customer.customerId,
                cart.priceMode, cartDisc + "\n" + sb, Session.userId());
    }

    public List<Held> list() throws SQLException {
        return Jdbc.list("SELECT h.*, c.name AS cname, u.username FROM held_carts h LEFT JOIN customers c "
                + "ON c.customer_id=h.customer_id JOIN users u ON u.user_id=h.user_id ORDER BY h.held_id", rs -> {
                    Held h = new Held();
                    h.heldId = rs.getInt("held_id");
                    h.label = rs.getString("label");
                    h.customerName = rs.getString("cname");
                    h.itemCount = rs.getString("payload").split("\n").length - 1;
                    h.createdAt = Jdbc.time(rs, "created_at");
                    h.cashier = rs.getString("username");
                    return h;
                });
    }

    /** Rebuilds the cart with current product data and removes it from the hold list. */
    public Cart resume(int heldId, ProductDAO products, CustomerDAO customers) throws SQLException {
        Object[] row = Jdbc.one("SELECT label, customer_id, price_mode, payload FROM held_carts WHERE held_id=?",
                rs -> new Object[]{rs.getString(1), Jdbc.intOrNull(rs, "customer_id"), rs.getString(3), rs.getString(4)}, heldId);
        if (row == null) {
            return null;
        }
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
            cart.items.add(it);
        }
        Jdbc.update("DELETE FROM held_carts WHERE held_id=?", heldId);
        cart.recalculate();
        return cart;
    }

    public void delete(int heldId) throws SQLException {
        Jdbc.update("DELETE FROM held_carts WHERE held_id=?", heldId);
    }
}
