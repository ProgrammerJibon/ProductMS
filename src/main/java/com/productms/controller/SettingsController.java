package com.productms.controller;

import com.productms.dao.PaymentMethodDAO;
import com.productms.dao.UserDAO;
import com.productms.db.Database;
import com.productms.db.Jdbc;
import com.productms.model.PaymentMethod;
import com.productms.model.User;
import com.productms.service.AuditService;
import com.productms.service.BackupService;
import com.productms.service.DocNumbers;
import com.productms.service.HardwareService;
import com.productms.util.AppException;
import com.productms.util.Money;
import com.productms.util.Session;
import com.productms.util.Settings;
import com.productms.view.ChangePasswordDialog;
import com.productms.view.SettingsView;
import com.productms.view.TableExport;
import com.productms.view.UiKit;
import java.awt.Desktop;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Saves all settings, manages payment methods, users & permissions, database security, backups and the audit log. */
public class SettingsController implements Module {

    private static final String ASK = "(ask every time)";
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
        view.btnPassword.addActionListener(e -> resetPassword());
        view.btnUnlock.addActionListener(e -> {
            User u = view.userModel.selected(view.userTable);
            if (u != null) {
                UiKit.guard(view, () -> {
                    users.unlock(u.userId);
                    view.userModel.setRows(users.findAll());
                });
            }
        });
        view.btnMyPassword.addActionListener(e -> ChangePasswordDialog.show(view, Session.user(), false));
        view.btnAddMethod.addActionListener(e -> editMethod(new PaymentMethod(), true));
        view.btnEditMethod.addActionListener(e -> {
            PaymentMethod m = view.methodModel.selected(view.methodTable);
            if (m != null) {
                editMethod(m, false);
            }
        });
        view.btnTestDrawer.addActionListener(e -> UiKit.guard(view, () -> {
            saveHardwareOnly();
            String err = HardwareService.openCashDrawer();
            UiKit.info(view, err == null ? "Drawer kick sent to " + Settings.get("receipt_printer", "") + "." : err);
        }));
        view.btnRestrictedDb.addActionListener(e -> restrictedAccount());
        view.btnBackup.addActionListener(e -> UiKit.guard(view, () -> {
            Path f = new BackupService().backup("manual");
            loadBackups();
            AuditService.log("BACKUP", "SETTINGS", null, f.getFileName().toString(), null);
            UiKit.info(view, "Backup saved:\n" + f.toAbsolutePath());
        }));
        view.btnValidate.addActionListener(e -> {
            Path f = chooseBackup();
            if (f != null) {
                UiKit.guard(view, () -> {
                    BackupService.Check c = new BackupService().validate(f);
                    if (c.valid) {
                        UiKit.info(view, c.message);
                    } else {
                        UiKit.warn(view, c.message);
                    }
                });
            }
        });
        view.btnRestore.addActionListener(e -> restore());
        view.btnOpenFolder.addActionListener(e -> UiKit.guard(view, () -> {
            Files.createDirectories(BackupService.folder());
            Desktop.getDesktop().open(BackupService.folder().toFile());
        }));
        view.btnAudit.addActionListener(e -> loadAudit());
        view.auditSearch.addActionListener(e -> loadAudit());
        view.btnAuditExport.addActionListener(e -> TableExport.csv(view, view.auditTable, "audit_log"));
        DocumentListener preview = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                previews();
            }

            public void removeUpdate(DocumentEvent e) {
                previews();
            }

            public void changedUpdate(DocumentEvent e) {
                previews();
            }
        };
        for (JTextField f : new JTextField[]{view.invoicePrefix, view.numberFormat, view.skuFormat}) {
            f.getDocument().addDocumentListener(preview);
        }
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void onShow() throws Exception {
        Settings.load();
        PaymentMethodDAO.reload();
        view.shopName.setText(Settings.get("shop_name", ""));
        view.shopAddress.setText(Settings.get("shop_address", ""));
        view.shopPhone.setText(Settings.get("shop_phone", ""));
        view.shopVatReg.setText(Settings.get("shop_vat_reg", ""));
        view.currency.setText(Settings.currency());
        view.receiptFooter.setText(Settings.get("receipt_footer", ""));
        view.vatInclusive.setSelected(Settings.pricesIncludeVat());
        view.requireShift.setSelected(Settings.getBool("require_shift"));
        view.receiptSize.setSelectedItem(Settings.get("receipt_size", "80"));
        view.maxCarts.setText(Settings.get("max_carts", "8"));
        view.heldExpiry.setText(Settings.get("held_cart_expiry_hours", "24"));
        view.reserveHeld.setSelected(Settings.getBool("reserve_held_stock"));
        view.maxDiscount.setText(Settings.get("max_cashier_discount", "10"));
        view.maxLineDiscount.setText(Settings.get("max_line_discount_percent", "50"));
        view.maxCartPct.setText(Settings.get("max_cart_discount_percent", "30"));
        view.maxCartAmount.setText(Settings.get("max_cart_discount_amount", "0"));
        view.refundApproval.setText(Settings.get("refund_approval_amount", "0"));
        view.returnDays.setText(Settings.get("return_days", "7"));
        String pol = Settings.get("exchange_excess_policy", "ACCOUNT");
        view.exchangePolicy.setSelectedIndex("CASH".equals(pol) ? 1 : "BLOCK".equals(pol) ? 2 : 0);
        view.denominations.setText(Settings.get("cash_denominations", "1000,500,200,100,50,20,10,5,2,1"));
        view.tileSort.setSelectedItem(Settings.get("pos_tile_sort", "NAME"));
        view.scalePrefix.setText(Settings.get("scale_barcode_prefix", "21"));
        view.offlineMode.setSelected(Settings.getBool("offline_mode_enabled"));
        view.expiryDays.setText(Settings.get("expiry_alert_days", "30"));
        view.nearExpiryWarn.setText(Settings.get("near_expiry_warn_days", "7"));
        view.nearExpiryBlock.setText(Settings.get("near_expiry_block_days", "0"));
        view.adjustmentApproval.setText(Settings.get("adjustment_approval_value", "0"));
        view.skuFormat.setText(Settings.get("sku_format", "{NAME}-{SEQ:5}"));
        view.barcodeType.setSelectedItem(Settings.get("barcode_type", "EAN13"));
        view.invoicePrefix.setText(Settings.get("invoice_prefix", "INV"));
        view.returnPrefix.setText(Settings.get("return_prefix", "RET"));
        view.cancelPrefix.setText(Settings.get("cancel_prefix", "CXL"));
        view.numberFormat.setText(Settings.get("number_format", "{PREFIX}-{YYMMDD}-{SEQ:4}"));
        view.numberReset.setSelectedItem(Settings.get("number_reset", "DAILY"));
        fillPrinters(view.receiptPrinter, Settings.get("receipt_printer", ""));
        fillPrinters(view.a4Printer, Settings.get("a4_printer", ""));
        view.autoPrint.setSelected(Settings.getBool("auto_print_receipt"));
        view.cashDrawer.setSelected(Settings.getBool("cash_drawer_enabled"));
        view.customerDisplay.setSelected(Settings.getBool("customer_display"));
        view.lockAttempts.setText(Settings.get("lockout_attempts", "5"));
        view.lockMinutes.setText(Settings.get("lockout_minutes", "15"));
        view.autoBackup.setSelected(Settings.getBool("auto_backup_enabled"));
        view.backupKeep.setText(Settings.get("backup_keep", "14"));
        String warn = Database.isInsecureRoot() ? "<br><b style='color:#DC2626'>Unsafe: connected as root with an empty password. "
                + "Create a restricted account below and set a root password in MySQL.</b>" : "";
        view.lblDb.setText("<html>Database <b>" + Database.databaseName() + "</b> on " + Database.host() + " as user <b>"
                + Database.user() + "</b>." + warn + "</html>");
        view.userModel.setRows(users.findAll());
        view.methodModel.setRows(new PaymentMethodDAO().findAll());
        loadBackups();
        loadAudit();
        previews();
    }

    private static void fillPrinters(JComboBox<String> cmb, String current) {
        cmb.removeAllItems();
        cmb.addItem(ASK);
        for (String p : HardwareService.printers()) {
            cmb.addItem(p);
        }
        if (!current.isBlank()) {
            boolean found = false;
            for (int i = 0; i < cmb.getItemCount(); i++) {
                found |= cmb.getItemAt(i).equals(current);
            }
            if (!found) {
                cmb.addItem(current);
            }
            cmb.setSelectedItem(current);
        }
    }

    private void previews() {
        try {
            view.numberPreview.setText("Next invoice looks like: " + DocNumbers.format(view.numberFormat.getText(),
                    view.invoicePrefix.getText().trim(), LocalDate.now(), 1));
        } catch (RuntimeException e) {
            view.numberPreview.setText("Invalid format");
        }
        view.skuPreview.setText("Example SKU: " + view.skuFormat.getText().replace("{NAME}", "COTCRE").replace("{CAT}", "CLO")
                .replace("{BRAND}", "EAS").replace("{SIZE}", "L").replace("{COLOUR}", "BLACK").replace("{YY}", "26")
                .replaceAll("\\{SEQ(:\\d)?\\}", "00021"));
    }

    private void loadBackups() {
        UiKit.guard(view, () -> {
            Jdbc.TableData t = new Jdbc.TableData();
            t.columns.add("File");
            t.columns.add("Size (KB)");
            t.columns.add("Created");
            for (Path p : new BackupService().list()) {
                List<Object> r = new ArrayList<>();
                r.add(p.getFileName().toString());
                r.add(Files.size(p) / 1024);
                r.add(java.time.LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), java.time.ZoneId.systemDefault()));
                t.rows.add(r);
            }
            view.backupModel.setData(t);
        });
    }

    private void loadAudit() {
        UiKit.guard(view, () -> view.auditModel.setData(AuditService.search(UiKit.dateOf(view.auditFrom), UiKit.dateOf(view.auditTo),
                (String) view.auditEntity.getSelectedItem(), view.auditSearch.getText())));
    }

    // ------------------------------------------------------------------ save

    private static int intIn(String s, String field, int min, int max) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < min || v > max) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new AppException(field + " must be a whole number from " + min + " to " + max + ".");
        }
    }

    private static String dec(String s, String field, boolean percent) {
        BigDecimal v = Money.parse(s);
        if (v == null || v.signum() < 0 || (percent && v.compareTo(Money.HUNDRED) > 0)) {
            throw new AppException(field + (percent ? " must be between 0 and 100." : " must be 0 or more."));
        }
        return v.toPlainString();
    }

    private void save() throws Exception {
        if (view.shopName.getText().isBlank()) {
            throw new AppException("Shop name is required.");
        }
        for (String d : view.denominations.getText().split(",")) {
            if (Money.parse(d) == null || !Money.pos(Money.parse(d))) {
                throw new AppException("Cash buttons: comma separated note values, e.g. 1000,500,100,50");
            }
        }
        if (!view.numberFormat.getText().contains("{SEQ")) {
            throw new AppException("The number format must contain {SEQ} (or {SEQ:4}) so numbers are unique.");
        }
        if (!view.invoicePrefix.getText().matches("[A-Za-z0-9]{1,8}") || !view.returnPrefix.getText().matches("[A-Za-z0-9]{1,8}")
                || !view.cancelPrefix.getText().matches("[A-Za-z0-9]{1,8}")) {
            throw new AppException("Prefixes: 1-8 letters or digits.");
        }
        if (!view.scalePrefix.getText().matches("\\d{0,2}")) {
            throw new AppException("Scale barcode prefix: 2 digits (usually 20-29) or empty to disable.");
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("shop_name", view.shopName.getText().trim());
        m.put("shop_address", view.shopAddress.getText().trim());
        m.put("shop_phone", view.shopPhone.getText().trim());
        m.put("shop_vat_reg", view.shopVatReg.getText().trim());
        m.put("currency", view.currency.getText().isBlank() ? "Tk" : view.currency.getText().trim());
        m.put("receipt_footer", view.receiptFooter.getText().trim());
        m.put("price_includes_vat", flag(view.vatInclusive));
        m.put("require_shift", flag(view.requireShift));
        m.put("receipt_size", (String) view.receiptSize.getSelectedItem());
        m.put("max_carts", String.valueOf(intIn(view.maxCarts.getText(), "Max open carts", 1, 20)));
        m.put("held_cart_expiry_hours", String.valueOf(intIn(view.heldExpiry.getText(), "Held cart expiry", 1, 720)));
        m.put("reserve_held_stock", flag(view.reserveHeld));
        m.put("max_cashier_discount", dec(view.maxDiscount.getText(), "Cashier max discount", true));
        m.put("max_line_discount_percent", dec(view.maxLineDiscount.getText(), "Max line discount", true));
        m.put("max_cart_discount_percent", dec(view.maxCartPct.getText(), "Max cart discount %", true));
        m.put("max_cart_discount_amount", dec(view.maxCartAmount.getText(), "Max cart discount amount", false));
        m.put("refund_approval_amount", dec(view.refundApproval.getText(), "Refund approval amount", false));
        m.put("return_days", String.valueOf(intIn(view.returnDays.getText(), "Return period", 0, 3650)));
        m.put("exchange_excess_policy", new String[]{"ACCOUNT", "CASH", "BLOCK"}[view.exchangePolicy.getSelectedIndex()]);
        m.put("cash_denominations", view.denominations.getText().replace(" ", ""));
        m.put("pos_tile_sort", (String) view.tileSort.getSelectedItem());
        m.put("scale_barcode_prefix", view.scalePrefix.getText().trim());
        m.put("offline_mode_enabled", flag(view.offlineMode));
        m.put("expiry_alert_days", String.valueOf(intIn(view.expiryDays.getText(), "Expiry alert days", 0, 3650)));
        m.put("near_expiry_warn_days", String.valueOf(intIn(view.nearExpiryWarn.getText(), "Near-expiry warning", 0, 365)));
        m.put("near_expiry_block_days", String.valueOf(intIn(view.nearExpiryBlock.getText(), "Near-expiry block", 0, 365)));
        m.put("adjustment_approval_value", dec(view.adjustmentApproval.getText(), "Adjustment approval value", false));
        m.put("sku_format", view.skuFormat.getText().trim().isEmpty() ? "{NAME}-{SEQ:5}" : view.skuFormat.getText().trim());
        m.put("barcode_type", (String) view.barcodeType.getSelectedItem());
        m.put("invoice_prefix", view.invoicePrefix.getText().trim().toUpperCase());
        m.put("return_prefix", view.returnPrefix.getText().trim().toUpperCase());
        m.put("cancel_prefix", view.cancelPrefix.getText().trim().toUpperCase());
        m.put("number_format", view.numberFormat.getText().trim());
        m.put("number_reset", (String) view.numberReset.getSelectedItem());
        m.put("lockout_attempts", String.valueOf(intIn(view.lockAttempts.getText(), "Failed attempts", 3, 20)));
        m.put("lockout_minutes", String.valueOf(intIn(view.lockMinutes.getText(), "Lock minutes", 1, 1440)));
        m.put("auto_backup_enabled", flag(view.autoBackup));
        m.put("backup_keep", String.valueOf(intIn(view.backupKeep.getText(), "Backups to keep", 1, 365)));
        putHardware(m);
        Map<String, String> before = new LinkedHashMap<>();
        for (String k : m.keySet()) {
            before.put(k, Settings.get(k, ""));
        }
        Settings.save(m);
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!e.getValue().equals(before.get(e.getKey()))) {
                changed.add(e.getKey() + ": " + before.get(e.getKey()) + " → " + e.getValue());
            }
        }
        if (!changed.isEmpty()) {
            AuditService.log("UPDATE", "SETTINGS", null, String.join("; ", changed), null);
        }
        main.refreshAlerts();
        UiKit.info(view, changed.isEmpty() ? "Nothing changed." : "Settings saved (" + changed.size() + " change(s), recorded in the audit log).");
    }

    private void putHardware(Map<String, String> m) {
        String rp = (String) view.receiptPrinter.getSelectedItem();
        String ap = (String) view.a4Printer.getSelectedItem();
        m.put("receipt_printer", rp == null || ASK.equals(rp) ? "" : rp);
        m.put("a4_printer", ap == null || ASK.equals(ap) ? "" : ap);
        m.put("auto_print_receipt", flag(view.autoPrint));
        m.put("cash_drawer_enabled", flag(view.cashDrawer));
        m.put("customer_display", flag(view.customerDisplay));
    }

    private void saveHardwareOnly() throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        putHardware(m);
        Settings.save(m);
    }

    private static String flag(JCheckBox c) {
        return c.isSelected() ? "1" : "0";
    }

    // ------------------------------------------------------------------ users

    private void editUser(User u) {
        JTextField username = UiKit.field("login name");
        JTextField fullName = UiKit.field("Full name");
        JComboBox<String> role = new JComboBox<>(new String[]{"ADMIN", "MANAGER", "CASHIER"});
        JCheckBox active = new JCheckBox("Active", u.active);
        JCheckBox override = new JCheckBox("May change selling prices at the POS", u.canOverridePrice);
        JCheckBox credit = new JCheckBox("May sell on credit (due)", u.canSellOnCredit);
        JTextField maxDisc = UiKit.field("blank = shop limit (" + Settings.get("max_cashier_discount", "10") + "%)");
        JTextField maxSale = UiKit.field("blank = no limit");
        JPasswordField pass = new JPasswordField();
        username.setText(u.username);
        fullName.setText(u.fullName);
        role.setSelectedItem(u.role);
        maxDisc.setText(u.maxDiscountPercent == null ? "" : Money.qty(u.maxDiscountPercent));
        maxSale.setText(u.maxSaleAmount == null ? "" : u.maxSaleAmount.toPlainString());
        UiKit.Form f = new UiKit.Form();
        f.add("Username *", username);
        f.add("Full name *", fullName);
        f.add("Role", role);
        f.add(u.userId == 0 ? "Temporary password *" : "New password", pass);
        f.full(UiKit.mutedLabel("8+ characters with letters and numbers. The user must change it at first sign-in."));
        f.section("Cashier permissions (managers & admins are not limited)");
        f.full(override);
        f.full(credit);
        f.add("Max discount %", maxDisc);
        f.add("Max invoice amount", maxSale);
        f.full(active);
        f.panel.setPreferredSize(new java.awt.Dimension(460, f.panel.getPreferredSize().height));
        while (JOptionPane.showConfirmDialog(view, f.panel, u.userId == 0 ? "New user" : "Edit user",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            boolean[] ok = {false};
            UiKit.guard(view, () -> {
                String newRole = (String) role.getSelectedItem();
                if (u.userId != 0 && "ADMIN".equals(u.role) && u.active && (!"ADMIN".equals(newRole) || !active.isSelected())
                        && users.countActiveAdmins() <= 1) {
                    throw new AppException("At least one active ADMIN account is required.");
                }
                if (u.userId == Session.userId() && !active.isSelected()) {
                    throw new AppException("You cannot deactivate your own account.");
                }
                BigDecimal md = Money.parse(maxDisc.getText());
                if (md != null && (md.signum() < 0 || md.compareTo(Money.HUNDRED) > 0)) {
                    throw new AppException("Max discount must be 0-100%.");
                }
                u.username = username.getText().trim();
                u.fullName = fullName.getText().trim();
                u.role = newRole;
                u.active = active.isSelected();
                u.canOverridePrice = override.isSelected();
                u.canSellOnCredit = credit.isSelected();
                u.maxDiscountPercent = md;
                u.maxSaleAmount = Money.parse(maxSale.getText());
                users.save(u, new String(pass.getPassword()));
                ok[0] = true;
                view.userModel.setRows(users.findAll());
            });
            if (ok[0]) {
                return;
            }
        }
    }

    private void resetPassword() {
        User u = view.userModel.selected(view.userTable);
        if (u == null) {
            UiKit.warn(view, "Select a user.");
            return;
        }
        JPasswordField p = new JPasswordField();
        if (JOptionPane.showConfirmDialog(view, new Object[]{"Temporary password for " + u.username
                + " (they must change it at next sign-in):", p}, "Reset password", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            UiKit.guard(view, () -> {
                users.resetPassword(u.userId, new String(p.getPassword()));
                view.userModel.setRows(users.findAll());
                UiKit.info(view, "Password reset. " + u.username + " must choose a new one at next sign-in.");
            });
        }
    }

    // ------------------------------------------------------------------ payment methods

    private void editMethod(PaymentMethod m, boolean isNew) {
        JTextField code = UiKit.field("e.g. UPAY");
        JTextField name = UiKit.field("e.g. Upay");
        JComboBox<String> kind = new JComboBox<>(new String[]{"MOBILE", "CARD", "CASH", "OTHER"});
        JCheckBox ref = new JCheckBox("Reference required", m.requiresReference);
        JTextField regex = UiKit.field("e.g. ^[A-Z0-9]{10}$ (blank = any)");
        JTextField hint = UiKit.field("e.g. TrxID, 10 letters/digits");
        JTextField color = UiKit.field("#RRGGBB");
        JTextField order = UiKit.field("10");
        JCheckBox active = new JCheckBox("Active", m.active);
        code.setText(m.code);
        code.setEnabled(isNew);
        name.setText(m.name);
        kind.setSelectedItem(m.kind);
        regex.setText(m.referenceRegex);
        hint.setText(m.referenceHint);
        color.setText(m.color);
        order.setText(String.valueOf(m.sortOrder));
        UiKit.Form f = new UiKit.Form();
        f.add("Code *", code);
        f.add("Name *", name);
        f.add("Kind", kind);
        f.full(ref);
        f.add("Reference pattern", regex);
        f.add("Hint for cashier", hint);
        f.add("Button colour", color);
        f.add("Order", order);
        f.full(active);
        f.panel.setPreferredSize(new java.awt.Dimension(460, f.panel.getPreferredSize().height));
        while (JOptionPane.showConfirmDialog(view, f.panel, isNew ? "New payment method" : "Edit payment method",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            boolean[] ok = {false};
            UiKit.guard(view, () -> {
                m.code = code.getText().trim().toUpperCase();
                m.name = name.getText();
                m.kind = "STORE_CREDIT".equals(m.code) ? "CREDIT" : (String) kind.getSelectedItem();
                m.requiresReference = ref.isSelected();
                m.referenceRegex = regex.getText();
                m.referenceHint = hint.getText();
                m.color = color.getText();
                m.sortOrder = intIn(order.getText().isBlank() ? "0" : order.getText(), "Order", 0, 999);
                m.active = active.isSelected();
                new PaymentMethodDAO().save(m, isNew);
                ok[0] = true;
                view.methodModel.setRows(new PaymentMethodDAO().findAll());
            });
            if (ok[0]) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ database security & backups

    private void restrictedAccount() {
        String name = UiKit.input(view, "Create a MySQL account limited to the " + Database.databaseName() + " database\n"
                + "(no DROP, no other databases, no admin rights). A strong random password is generated and saved in db.properties.\n\n"
                + "Account name:", "productms_app");
        if (name == null || name.isBlank()) {
            return;
        }
        if (!name.matches("[A-Za-z0-9_]{3,32}")) {
            UiKit.warn(view, "Use 3-32 letters, digits or underscore.");
            return;
        }
        UiKit.guard(view, () -> {
            String file = Database.createRestrictedAccount(name.trim());
            AuditService.log("DB_ACCOUNT", "SETTINGS", name.trim(), "Restricted database account created; db.properties written", null);
            UiKit.info(view, "Account \"" + name.trim() + "\" created and saved to:\n" + file + "\n\nRestart the application to use it.\n"
                    + "Then protect MySQL's root account with a password (XAMPP: phpMyAdmin → User accounts).\n"
                    + "Keep db.properties private; it contains the password.");
        });
    }

    private Path chooseBackup() {
        JFileChooser fc = new JFileChooser(BackupService.folder().toFile());
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("ProductMS backup (*.sql)", "sql"));
        return fc.showOpenDialog(view) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile().toPath() : null;
    }

    private void restore() {
        Path f = chooseBackup();
        if (f == null) {
            return;
        }
        UiKit.guard(view, () -> {
            BackupService svc = new BackupService();
            BackupService.Check c = svc.validate(f);
            if (!c.valid) {
                UiKit.warn(view, "This file cannot be restored:\n" + c.message);
                return;
            }
            if (main.pos().hasOpenCarts()) {
                throw new AppException("Finish or void the open POS carts first.");
            }
            String confirm = UiKit.input(view, c.message + "\n\nRESTORE REPLACES ALL CURRENT DATA with this backup.\n"
                    + "A safety backup of the current data is taken first.\n\nType RESTORE to continue:", "");
            if (!"RESTORE".equals(confirm)) {
                return;
            }
            String result = svc.restore(f);
            Settings.load();
            AuditService.log("RESTORE", "SETTINGS", null, f.getFileName() + " - " + result.split("\n")[0], null);
            loadBackups();
            UiKit.info(view, result + "\n\nPlease sign in again.");
            main.forceLogout();
        });
    }
}
