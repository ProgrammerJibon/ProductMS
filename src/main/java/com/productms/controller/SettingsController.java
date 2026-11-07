package com.productms.controller;

import com.productms.dao.UserDAO;
import com.productms.db.Database;
import com.productms.model.User;
import com.productms.util.AppException;
import com.productms.util.Money;
import com.productms.util.Session;
import com.productms.util.Settings;
import com.productms.view.SettingsView;
import com.productms.view.UiKit;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

/** Saves shop / POS settings and manages user accounts. */
public class SettingsController implements Module {

    private final SettingsView view = new SettingsView();
    private final UserDAO users = new UserDAO();
    private final MainController main;

    public SettingsController(MainController main) {
        this.main = main;
        view.btnSave.addActionListener(e -> UiKit.guard(view, this::save));
        view.btnAddUser.addActionListener(e -> editUser(new User()));
        view.btnEditUser.addActionListener(e -> {
            User u = view.userModel.selected(view.userTable);
            if (u != null) {
                editUser(u);
            }
        });
        view.btnPassword.addActionListener(e -> {
            User u = view.userModel.selected(view.userTable);
            if (u == null) {
                UiKit.warn(view, "Select a user.");
                return;
            }
            JPasswordField p = new JPasswordField();
            if (JOptionPane.showConfirmDialog(view, new Object[]{"New password for " + u.username + ":", p}, "Reset password",
                    JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                UiKit.guard(view, () -> {
                    users.changePassword(u.userId, new String(p.getPassword()));
                    UiKit.info(view, "Password changed.");
                });
            }
        });
        view.btnMyPassword.addActionListener(e -> changeMyPassword());
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void onShow() throws Exception {
        Settings.load();
        view.shopName.setText(Settings.get("shop_name", ""));
        view.shopAddress.setText(Settings.get("shop_address", ""));
        view.shopPhone.setText(Settings.get("shop_phone", ""));
        view.shopVatReg.setText(Settings.get("shop_vat_reg", ""));
        view.currency.setText(Settings.currency());
        view.receiptFooter.setText(Settings.get("receipt_footer", ""));
        view.vatInclusive.setSelected(Settings.pricesIncludeVat());
        view.receiptSize.setSelectedItem(Settings.get("receipt_size", "80"));
        view.requireShift.setSelected(Settings.getBool("require_shift"));
        view.returnDays.setText(String.valueOf(Settings.getInt("return_days", 7)));
        view.expiryDays.setText(String.valueOf(Settings.getInt("expiry_alert_days", 30)));
        view.maxDiscount.setText(Settings.get("max_cashier_discount", "10"));
        view.lblDb.setText("Database: " + Database.databaseName() + " on localhost:3306 (MySQL). Override with db.properties.");
        view.userModel.setRows(users.findAll());
    }

    private void save() throws Exception {
        if (view.shopName.getText().isBlank()) {
            throw new AppException("Shop name is required.");
        }
        int ret = parseInt(view.returnDays.getText(), "Return period");
        int exp = parseInt(view.expiryDays.getText(), "Expiry alert days");
        BigDecimal max = Money.parse(view.maxDiscount.getText());
        if (max == null || max.signum() < 0 || max.compareTo(Money.HUNDRED) > 0) {
            throw new AppException("Cashier max discount must be between 0 and 100.");
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("shop_name", view.shopName.getText().trim());
        m.put("shop_address", view.shopAddress.getText().trim());
        m.put("shop_phone", view.shopPhone.getText().trim());
        m.put("shop_vat_reg", view.shopVatReg.getText().trim());
        m.put("currency", view.currency.getText().isBlank() ? "Tk" : view.currency.getText().trim());
        m.put("receipt_footer", view.receiptFooter.getText().trim());
        m.put("price_includes_vat", view.vatInclusive.isSelected() ? "1" : "0");
        m.put("receipt_size", (String) view.receiptSize.getSelectedItem());
        m.put("require_shift", view.requireShift.isSelected() ? "1" : "0");
        m.put("return_days", String.valueOf(ret));
        m.put("expiry_alert_days", String.valueOf(exp));
        m.put("max_cashier_discount", max.toPlainString());
        Settings.save(m);
        main.refreshAlerts();
        UiKit.info(view, "Settings saved.\n(The shop name in the window title updates after the next login.)");
    }

    private static int parseInt(String s, String field) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < 0 || v > 3650) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new AppException(field + " must be a whole number of days.");
        }
    }

    private void editUser(User u) {
        JTextField username = UiKit.field("login name");
        JTextField fullName = UiKit.field("Full name");
        JComboBox<String> role = new JComboBox<>(new String[]{"ADMIN", "MANAGER", "CASHIER"});
        JCheckBox active = new JCheckBox("Active", u.active);
        JPasswordField pass = new JPasswordField();
        username.setText(u.username);
        fullName.setText(u.fullName);
        role.setSelectedItem(u.role);
        UiKit.Form f = new UiKit.Form();
        f.add("Username *", username);
        f.add("Full name *", fullName);
        f.add("Role", role);
        f.add(u.userId == 0 ? "Password *" : "New password", pass);
        f.full(active);
        f.panel.setPreferredSize(new java.awt.Dimension(400, f.panel.getPreferredSize().height));
        while (JOptionPane.showConfirmDialog(view, f.panel, u.userId == 0 ? "New user" : "Edit user",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            boolean[] ok = {false};
            UiKit.guard(view, () -> {
                boolean wasAdmin = "ADMIN".equals(u.role) && u.active;
                String newRole = (String) role.getSelectedItem();
                if (u.userId != 0 && wasAdmin && (!"ADMIN".equals(newRole) || !active.isSelected())
                        && users.countActiveAdmins() <= 1) {
                    throw new AppException("At least one active ADMIN account is required.");
                }
                if (u.userId == Session.userId() && !active.isSelected()) {
                    throw new AppException("You cannot deactivate your own account.");
                }
                u.username = username.getText().trim();
                u.fullName = fullName.getText().trim();
                u.role = newRole;
                u.active = active.isSelected();
                users.save(u, new String(pass.getPassword()));
                ok[0] = true;
                view.userModel.setRows(users.findAll());
            });
            if (ok[0]) {
                return;
            }
        }
    }

    private void changeMyPassword() {
        JPasswordField old = new JPasswordField();
        JPasswordField p1 = new JPasswordField();
        JPasswordField p2 = new JPasswordField();
        if (JOptionPane.showConfirmDialog(view, new Object[]{"Current password:", old, "New password:", p1, "Repeat new password:", p2},
                "Change my password", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        UiKit.guard(view, () -> {
            if (users.authenticate(Session.user().username, new String(old.getPassword())) == null) {
                throw new AppException("Current password is wrong.");
            }
            if (!new String(p1.getPassword()).equals(new String(p2.getPassword()))) {
                throw new AppException("The new passwords do not match.");
            }
            users.changePassword(Session.userId(), new String(p1.getPassword()));
            UiKit.info(view, "Password changed.");
        });
    }
}
