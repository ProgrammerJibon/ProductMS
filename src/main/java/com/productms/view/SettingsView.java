package com.productms.view;

import com.productms.model.User;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;

/** Shop details, POS & tax rules, and user accounts. */
public class SettingsView extends JPanel {

    public final JTabbedPane tabs = new JTabbedPane();
    public final JTextField shopName = new JTextField();
    public final JTextField shopAddress = new JTextField();
    public final JTextField shopPhone = new JTextField();
    public final JTextField shopVatReg = new JTextField();
    public final JTextField currency = new JTextField();
    public final JTextField receiptFooter = new JTextField();

    public final JCheckBox vatInclusive = new JCheckBox("Selling prices already include VAT (VAT is extracted, not added)");
    public final JComboBox<String> receiptSize = new JComboBox<>(new String[]{"80", "58", "A4"});
    public final JCheckBox requireShift = new JCheckBox("Cashiers must open a cash shift before selling");
    public final JTextField returnDays = new JTextField();
    public final JTextField expiryDays = new JTextField();
    public final JTextField maxDiscount = new JTextField();
    public final JButton btnSave = UiKit.primary("Save settings");

    public final RowTableModel<User> userModel = new RowTableModel<User>()
            .col("Username", String.class, u -> u.username)
            .col("Full name", String.class, u -> u.fullName)
            .col("Role", String.class, u -> u.role)
            .col("Status", String.class, u -> u.active ? "ACTIVE" : "INACTIVE");
    public final JTable userTable = new JTable(userModel);
    public final JButton btnAddUser = UiKit.primary("+ Add user");
    public final JButton btnEditUser = UiKit.plain("Edit");
    public final JButton btnPassword = UiKit.plain("Reset password");
    public final JButton btnMyPassword = UiKit.plain("Change my password");
    public final JLabel lblDb = UiKit.mutedLabel(" ");

    public SettingsView() {
        super(new BorderLayout());
        UiKit.styleTable(userTable);

        UiKit.Form shop = new UiKit.Form();
        shop.section("Shop details (printed on receipts and invoices)");
        shop.add("Shop name", shopName);
        shop.add("Address", shopAddress);
        shop.add("Phone", shopPhone);
        shop.add("VAT / BIN registration", shopVatReg);
        shop.add("Currency symbol", currency);
        shop.add("Receipt footer", receiptFooter);
        shop.filler();

        UiKit.Form pos = new UiKit.Form();
        pos.section("Tax");
        pos.full(vatInclusive);
        pos.section("Point of Sale");
        pos.add("Default receipt", receiptSize);
        pos.full(requireShift);
        pos.add("Cashier max discount %", maxDiscount);
        pos.add("Return period (days)", returnDays);
        pos.section("Inventory");
        pos.add("Expiry alert (days before)", expiryDays);
        pos.full(UiKit.mutedLabel("Low-stock alerts use each variant's reorder level (Products → Variants)."));
        pos.filler();

        JPanel general = new JPanel(new java.awt.GridLayout(1, 2, 14, 0));
        general.setOpaque(false);
        JPanel c1 = UiKit.card(new BorderLayout());
        c1.add(shop.panel);
        JPanel c2 = UiKit.card(new BorderLayout());
        c2.add(pos.panel);
        general.add(c1);
        general.add(c2);
        JPanel generalTab = new JPanel(new BorderLayout(0, 10));
        generalTab.setOpaque(false);
        generalTab.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        generalTab.add(general);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(lblDb, BorderLayout.WEST);
        south.add(UiKit.rowRight(btnSave), BorderLayout.EAST);
        generalTab.add(south, BorderLayout.SOUTH);

        JPanel users = new JPanel(new BorderLayout(0, 10));
        users.setOpaque(false);
        users.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        users.add(UiKit.row(btnAddUser, btnEditUser, btnPassword, btnMyPassword), BorderLayout.NORTH);
        users.add(UiKit.tableCard(userTable));
        users.add(UiKit.mutedLabel("ADMIN: everything · MANAGER: products, stock, reports, cancellations, discount approval "
                + "· CASHIER: POS, sales, customers, own shift"), BorderLayout.SOUTH);
        userTable.setPreferredScrollableViewportSize(new Dimension(600, 300));

        tabs.addTab("General", generalTab);
        tabs.addTab("Users & Roles", users);
        add(UiKit.page("Settings", "Shop information, tax, POS rules and user accounts", null, tabs));
    }
}
