package com.productms.view;

import com.productms.model.Cart;
import com.productms.model.CartItem;
import com.productms.util.Money;
import com.productms.util.Settings;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.math.BigDecimal;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;

/**
 * Customer-facing display: a second window (full screen on the second monitor when one is
 * connected) showing the items being rung up, the total, and the change after payment.
 */
public final class CustomerDisplay extends JFrame {

    private static CustomerDisplay instance;
    private final DefaultListModel<String> lines = new DefaultListModel<>();
    private final JLabel lblTotal = new JLabel("0.00", SwingConstants.RIGHT);
    private final JLabel lblMessage = new JLabel("Welcome!", SwingConstants.CENTER);
    private final JLabel lblShop = new JLabel("", SwingConstants.CENTER);

    private CustomerDisplay() {
        super("Customer display");
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        Color bg = new Color(0x0F172A);
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(bg);
        root.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));
        lblShop.setForeground(new Color(0x93C5FD));
        lblShop.setFont(lblShop.getFont().deriveFont(Font.BOLD, 26f));
        root.add(lblShop, BorderLayout.NORTH);
        JList<String> list = new JList<>(lines);
        list.setBackground(new Color(0x1E293B));
        list.setForeground(Color.WHITE);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 20));
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createEmptyBorder());
        root.add(sp);
        JPanel bottom = new JPanel(new GridLayout(2, 1, 0, 6));
        bottom.setOpaque(false);
        lblTotal.setForeground(Color.WHITE);
        lblTotal.setFont(lblTotal.getFont().deriveFont(Font.BOLD, 54f));
        lblMessage.setForeground(new Color(0x86EFAC));
        lblMessage.setFont(lblMessage.getFont().deriveFont(Font.BOLD, 26f));
        bottom.add(lblTotal);
        bottom.add(lblMessage);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(new Dimension(900, 600));
    }

    public static boolean enabled() {
        return Settings.getBool("customer_display");
    }

    public static CustomerDisplay get() {
        if (instance == null) {
            instance = new CustomerDisplay();
            GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            if (screens.length > 1) {
                Rectangle b = screens[1].getDefaultConfiguration().getBounds();
                instance.setUndecorated(false);
                instance.setBounds(b);
            } else {
                instance.setLocation(40, 40);
            }
        }
        return instance;
    }

    public static void showCart(Cart cart) {
        if (!enabled() || cart == null) {
            return;
        }
        CustomerDisplay d = get();
        d.lblShop.setText(Settings.shopName());
        d.lines.clear();
        for (CartItem it : cart.items) {
            String name = it.variant.displayName();
            if (name.length() > 30) {
                name = name.substring(0, 29) + "…";
            }
            d.lines.addElement(String.format("%-30s %7s x %10s %12s", name, Money.qty(it.quantity), Money.fmt(it.unitPrice),
                    Money.fmt(it.lineTotal)));
        }
        if (cart.itemDiscountTotal.add(cart.cartDiscount).signum() > 0) {
            d.lines.addElement(String.format("%-52s %12s", "You save", "-" + Money.fmt(cart.itemDiscountTotal.add(cart.cartDiscount))));
        }
        d.lblTotal.setText("TOTAL  " + Settings.currency() + " " + Money.fmt(cart.grandTotal));
        d.lblMessage.setText(cart.isEmpty() ? "Welcome!" : cart.items.size() + " item(s)");
        if (!d.isVisible()) {
            d.setVisible(true);
        }
    }

    public static void showThanks(BigDecimal total, BigDecimal change) {
        if (!enabled()) {
            return;
        }
        CustomerDisplay d = get();
        d.lblTotal.setText("PAID  " + Settings.currency() + " " + Money.fmt(total));
        d.lblMessage.setText(change.signum() > 0 ? "Your change: " + Settings.currency() + " " + Money.fmt(change) + "  —  Thank you!"
                : "Thank you for shopping with us!");
    }

    public static void hideDisplay() {
        if (instance != null) {
            instance.setVisible(false);
        }
    }
}
