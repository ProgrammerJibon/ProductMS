package com.productms.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

/** Login screen: brand panel on the left, sign-in form on the right. */
public class LoginView extends JFrame {

    public final JTextField txtUsername = UiKit.field("Username");
    public final JPasswordField txtPassword = new JPasswordField();
    public final JButton btnLogin = UiKit.big(UiKit.primary("Sign in"), 44);
    public final JLabel lblError = new JLabel(" ");

    public LoginView(String shopName) {
        super("Sign in - " + shopName);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(920, 580);
        setMinimumSize(new Dimension(760, 520));
        setLocationRelativeTo(null);

        JPanel brand = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(0, 0, new Color(0x1D4ED8), getWidth(), getHeight(), new Color(0x0F172A)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(255, 255, 255, 18));
                g2.fillOval(getWidth() - 220, -80, 320, 320);
                g2.fillOval(-120, getHeight() - 200, 300, 300);
            }
        };
        brand.setPreferredSize(new Dimension(420, 0));
        JPanel brandText = new JPanel(new GridLayout(0, 1, 0, 6));
        brandText.setOpaque(false);
        JLabel logo = new JLabel("■  ProductMS POS");
        logo.setForeground(new Color(0xBFDBFE));
        logo.setFont(logo.getFont().deriveFont(Font.BOLD, 14f));
        JLabel shop = new JLabel("<html><div style='width:300px'>" + shopName + "</div></html>");
        shop.setForeground(Color.WHITE);
        shop.setFont(shop.getFont().deriveFont(Font.BOLD, 28f));
        brandText.add(logo);
        brandText.add(shop);
        brandText.add(new JLabel(" "));
        for (String f : new String[]{"Inventory with variants, batches & serials", "Fast POS with barcode scanning",
            "Split, mobile banking & due payments", "Receipts, invoices, labels & reports"}) {
            JLabel l = new JLabel("•  " + f);
            l.setForeground(new Color(0xDBEAFE));
            l.setFont(l.getFont().deriveFont(14f));
            brandText.add(l);
        }
        brand.add(brandText);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(30, 50, 30, 50));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(4, 0, 4, 0);
        JLabel welcome = UiKit.title("Welcome back", 26f);
        form.add(welcome, c);
        form.add(UiKit.mutedLabel("Sign in to continue to your store"), c);
        c.insets = new Insets(22, 0, 4, 0);
        form.add(new JLabel("Username"), c);
        c.insets = new Insets(2, 0, 4, 0);
        txtUsername.setPreferredSize(new Dimension(300, 40));
        form.add(txtUsername, c);
        c.insets = new Insets(10, 0, 4, 0);
        form.add(new JLabel("Password"), c);
        c.insets = new Insets(2, 0, 4, 0);
        txtPassword.putClientProperty("JPasswordField.showRevealButton", true);
        txtPassword.putClientProperty("JTextField.placeholderText", "Password");
        txtPassword.setPreferredSize(new Dimension(300, 40));
        form.add(txtPassword, c);
        lblError.setForeground(UiKit.DANGER);
        c.insets = new Insets(8, 0, 4, 0);
        form.add(lblError, c);
        c.insets = new Insets(6, 0, 4, 0);
        form.add(btnLogin, c);
        c.insets = new Insets(18, 0, 0, 0);
        form.add(UiKit.mutedLabel("Demo accounts: admin / admin123  ·  cashier / cashier123"), c);

        getContentPane().add(brand, BorderLayout.WEST);
        getContentPane().add(form, BorderLayout.CENTER);
        getRootPane().setDefaultButton(btnLogin);
    }

    public void showError(String msg) {
        lblError.setText(msg);
        txtPassword.selectAll();
        txtPassword.requestFocusInWindow();
    }
}
