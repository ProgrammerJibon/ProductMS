package com.productms.view;

import com.productms.model.PaymentMethod;
import com.productms.model.User;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.time.LocalDate;
import java.time.LocalDateTime;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;

/** Shop, POS limits, inventory, numbering, hardware, payment methods, users, security / backups and audit log. */
public class SettingsView extends JPanel {

    public final JTabbedPane tabs = new JTabbedPane();
    public final JButton btnSave = UiKit.primary("Save settings");

    // shop
    public final JTextField shopName = new JTextField();
    public final JTextField shopAddress = new JTextField();
    public final JTextField shopPhone = new JTextField();
    public final JTextField shopVatReg = new JTextField();
    public final JTextField currency = new JTextField();
    public final JTextField receiptFooter = new JTextField();

    // POS & limits
    public final JCheckBox vatInclusive = new JCheckBox("Selling prices already include VAT (VAT is extracted, not added)");
    public final JCheckBox requireShift = new JCheckBox("Cashiers must open a cash shift before selling");
    public final JComboBox<String> receiptSize = new JComboBox<>(new String[]{"80", "58", "A4"});
    public final JTextField maxCarts = new JTextField();
    public final JTextField heldExpiry = new JTextField();
    public final JCheckBox reserveHeld = new JCheckBox("Held carts reserve their stock until they expire");
    public final JTextField maxDiscount = new JTextField();
    public final JTextField maxLineDiscount = new JTextField();
    public final JTextField maxCartPct = new JTextField();
    public final JTextField maxCartAmount = new JTextField();
    public final JTextField refundApproval = new JTextField();
    public final JTextField returnDays = new JTextField();
    public final JComboBox<String> exchangePolicy = new JComboBox<>(new String[]{
        "ACCOUNT - keep the balance as the customer's store credit", "CASH - pay the balance back in cash",
        "BLOCK - new items must cost at least the credit"});
    public final JTextField denominations = new JTextField();
    public final JComboBox<String> tileSort = new JComboBox<>(new String[]{"NAME", "POPULAR", "CUSTOM"});
    public final JTextField scalePrefix = new JTextField();
    public final JCheckBox offlineMode = new JCheckBox("Allow offline selling when the database is unreachable (queued and synced later)");

    // inventory
    public final JTextField expiryDays = new JTextField();
    public final JTextField nearExpiryWarn = new JTextField();
    public final JTextField nearExpiryBlock = new JTextField();
    public final JTextField adjustmentApproval = new JTextField();
    public final JTextField skuFormat = new JTextField();
    public final JComboBox<String> barcodeType = new JComboBox<>(new String[]{"EAN13", "CODE128"});
    public final JLabel skuPreview = UiKit.mutedLabel(" ");

    // numbering
    public final JTextField invoicePrefix = new JTextField();
    public final JTextField returnPrefix = new JTextField();
    public final JTextField cancelPrefix = new JTextField();
    public final JTextField numberFormat = new JTextField();
    public final JComboBox<String> numberReset = new JComboBox<>(new String[]{"DAILY", "MONTHLY", "YEARLY", "NEVER"});
    public final JLabel numberPreview = UiKit.mutedLabel(" ");

    // hardware
    public final JComboBox<String> receiptPrinter = new JComboBox<>();
    public final JComboBox<String> a4Printer = new JComboBox<>();
    public final JCheckBox autoPrint = new JCheckBox("Print the receipt automatically after each sale (no preview)");
    public final JCheckBox cashDrawer = new JCheckBox("Cash drawer connected to the receipt printer (opens on cash sales)");
    public final JButton btnTestDrawer = UiKit.plain("Test drawer");
    public final JCheckBox customerDisplay = new JCheckBox("Show the customer display window (second screen)");

    // payment methods
    public final RowTableModel<PaymentMethod> methodModel = new RowTableModel<PaymentMethod>()
            .col("Code", String.class, m -> m.code)
            .col("Name", String.class, m -> m.name)
            .col("Kind", String.class, m -> m.kind)
            .col("Reference required", Boolean.class, m -> m.requiresReference)
            .col("Reference pattern", String.class, m -> m.referenceRegex)
            .col("Hint", String.class, m -> m.referenceHint)
            .col("Order", Integer.class, m -> m.sortOrder)
            .col("Status", String.class, m -> m.active ? "ACTIVE" : "INACTIVE");
    public final JTable methodTable = new JTable(methodModel);
    public final JButton btnAddMethod = UiKit.primary("+ Add method");
    public final JButton btnEditMethod = UiKit.plain("Edit");

    // users
    public final RowTableModel<User> userModel = new RowTableModel<User>()
            .col("Username", String.class, u -> u.username)
            .col("Full name", String.class, u -> u.fullName)
            .col("Role", String.class, u -> u.role)
            .col("Price override", Boolean.class, u -> u.canOverridePrice || u.isManager())
            .col("Credit sales", Boolean.class, u -> u.canSellOnCredit || u.isManager())
            .col("Max discount %", String.class, u -> u.isManager() ? "manager" : u.maxDiscountPercent == null ? "shop limit"
                    : com.productms.util.Money.qty(u.maxDiscountPercent))
            .col("Max invoice", String.class, u -> u.maxSaleAmount == null ? "no limit" : com.productms.util.Money.fmt(u.maxSaleAmount))
            .col("Last login", LocalDateTime.class, u -> u.lastLogin)
            .col("Status", String.class, u -> !u.active ? "INACTIVE" : u.lockedUntil != null && u.lockedUntil.isAfter(LocalDateTime.now())
                    ? "LOCKED" : u.mustChangePassword ? "MUST CHANGE PASSWORD" : "ACTIVE");
    public final JTable userTable = new JTable(userModel);
    public final JButton btnAddUser = UiKit.primary("+ Add user");
    public final JButton btnEditUser = UiKit.plain("Edit / permissions");
    public final JButton btnPassword = UiKit.plain("Reset password");
    public final JButton btnUnlock = UiKit.plain("Unlock");
    public final JButton btnMyPassword = UiKit.plain("Change my password");

    // security & backup
    public final JLabel lblDb = new JLabel(" ");
    public final JButton btnRestrictedDb = UiKit.plain("Create restricted database account...");
    public final JTextField lockAttempts = new JTextField();
    public final JTextField lockMinutes = new JTextField();
    public final JCheckBox autoBackup = new JCheckBox("Automatic daily backup (taken at start-up when the last one is older than 24 h)");
    public final JTextField backupKeep = new JTextField();
    public final JButton btnBackup = UiKit.success("Back up now");
    public final JButton btnValidate = UiKit.plain("Validate a backup file...");
    public final JButton btnRestore = UiKit.danger("Restore from backup...");
    public final JButton btnOpenFolder = UiKit.plain("Open backup folder");
    public final DataTableModel backupModel = new DataTableModel();
    public final JTable backupTable = new JTable(backupModel);

    // audit
    public final JSpinner auditFrom = UiKit.dateSpinner(LocalDate.now().minusDays(7));
    public final JSpinner auditTo = UiKit.dateSpinner(LocalDate.now());
    public final JComboBox<String> auditEntity = new JComboBox<>(new String[]{"ALL", "INVOICE", "PRODUCT", "VARIANT", "CUSTOMER",
        "USER", "SETTINGS", "SHIFT", "CART", "STOCK_COUNT", "PAYMENT_METHOD"});
    public final JTextField auditSearch = UiKit.search("Search details, user, id");
    public final JButton btnAudit = UiKit.primary("Search");
    public final JButton btnAuditExport = UiKit.plain("Export CSV");
    public final DataTableModel auditModel = new DataTableModel();
    public final JTable auditTable = new JTable(auditModel);

    public SettingsView() {
        super(new BorderLayout());
        for (JTable t : new JTable[]{methodTable, userTable, backupTable, auditTable}) {
            UiKit.styleTable(t);
        }

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
        pos.section("Tax & shift");
        pos.full(vatInclusive);
        pos.full(requireShift);
        pos.section("Carts");
        pos.add("Default receipt", receiptSize);
        pos.add("Max open carts", maxCarts);
        pos.add("Held cart expiry (hours)", heldExpiry);
        pos.full(reserveHeld);
        pos.section("Discount, refund & exchange limits");
        pos.add("Cashier max discount %", maxDiscount);
        pos.add("Max total line discount %", maxLineDiscount);
        pos.add("Max cart discount %", maxCartPct);
        pos.add("Max cart discount amount", maxCartAmount);
        pos.add("Refunds needing approval from", refundApproval);
        pos.add("Return period (days)", returnDays);
        pos.add("Exchange balance policy", exchangePolicy);
        pos.section("Speed");
        pos.add("Cash buttons (notes)", denominations);
        pos.add("Product tile order", tileSort);
        pos.add("Scale barcode prefix", scalePrefix);
        pos.full(offlineMode);
        pos.filler();

        UiKit.Form inv = new UiKit.Form();
        inv.section("Expiry");
        inv.add("Expiry alert (days before)", expiryDays);
        inv.add("Warn cashier (days before)", nearExpiryWarn);
        inv.add("Block sale (days before)", nearExpiryBlock);
        inv.full(UiKit.mutedLabel("Block = batches expiring within this many days are not sold (0 = only expired batches)."));
        inv.section("Approvals");
        inv.add("Adjustments needing approval from (value)", adjustmentApproval);
        inv.section("Codes");
        inv.add("SKU format", skuFormat);
        inv.full(UiKit.mutedLabel("Tokens: {NAME} {CAT} {BRAND} {SIZE} {COLOUR} {YY} {SEQ:5}"));
        inv.full(skuPreview);
        inv.add("Generated barcodes", barcodeType);
        inv.full(UiKit.mutedLabel("EAN13 = internal 200... numbers; CODE128 = the SKU. Scanned codes may be EAN-8, UPC-A, EAN-13 or Code-128."));
        inv.filler();

        UiKit.Form num = new UiKit.Form();
        num.section("Document numbers");
        num.add("Invoice prefix", invoicePrefix);
        num.add("Return prefix", returnPrefix);
        num.add("Cancellation prefix", cancelPrefix);
        num.add("Format", numberFormat);
        num.full(UiKit.mutedLabel("Tokens: {PREFIX} {YYYY} {YY} {MM} {DD} {YYMMDD} {SEQ:4}"));
        num.add("Counter resets", numberReset);
        num.full(numberPreview);
        num.filler();

        JPanel general = new JPanel(new java.awt.GridLayout(2, 2, 14, 14));
        general.setOpaque(false);
        for (UiKit.Form f : new UiKit.Form[]{shop, pos, inv, num}) {
            JPanel c = UiKit.card(new BorderLayout());
            c.add(UiKit.scroll(f.panel));
            general.add(c);
        }
        JPanel generalTab = wrap(general, null);

        UiKit.Form hw = new UiKit.Form();
        hw.section("Printers (leave on \"(ask every time)\" to choose in the print dialog)");
        hw.add("Receipt printer", receiptPrinter);
        hw.add("A4 printer", a4Printer);
        hw.full(autoPrint);
        hw.section("Cash drawer & customer display");
        hw.full(cashDrawer);
        hw.full(UiKit.row(btnTestDrawer));
        hw.full(customerDisplay);
        hw.full(UiKit.mutedLabel("<html>Payment terminals and bKash / Nagad / Rocket APIs are not connected automatically: "
                + "cashiers type the approval code / TrxID, which is format-checked and never accepted twice.</html>"));
        hw.filler();
        JPanel hwCard = UiKit.card(new BorderLayout());
        hwCard.add(hw.panel);
        hwCard.setPreferredSize(new Dimension(640, 0));
        JPanel hwTab = wrap(hwCard, null);

        JPanel methods = new JPanel(new BorderLayout(0, 10));
        methods.setOpaque(false);
        methods.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        methods.add(UiKit.row(btnAddMethod, btnEditMethod, UiKit.mutedLabel("Add e.g. Upay, bank transfer or a gift voucher. "
                + "The reference pattern is a regular expression.")), BorderLayout.NORTH);
        methods.add(UiKit.tableCard(methodTable));

        JPanel users = new JPanel(new BorderLayout(0, 10));
        users.setOpaque(false);
        users.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        users.add(UiKit.row(btnAddUser, btnEditUser, btnPassword, btnUnlock, btnMyPassword), BorderLayout.NORTH);
        users.add(UiKit.tableCard(userTable));
        users.add(UiKit.mutedLabel("ADMIN: everything · MANAGER: products, stock, reports, cancellations, approvals "
                + "· CASHIER: POS, sales, customers, own shift, limited by the permissions above"), BorderLayout.SOUTH);

        UiKit.Form sec = new UiKit.Form();
        sec.section("Database connection");
        sec.full(lblDb);
        sec.full(UiKit.row(btnRestrictedDb));
        sec.section("Sign-in protection");
        sec.add("Failed attempts before lock", lockAttempts);
        sec.add("Lock for (minutes)", lockMinutes);
        sec.section("Backups");
        sec.full(autoBackup);
        sec.add("Keep newest", backupKeep);
        sec.full(UiKit.row(btnBackup, btnValidate, btnRestore, btnOpenFolder));
        sec.filler();
        JPanel secCard = UiKit.card(new BorderLayout());
        secCard.add(sec.panel);
        secCard.setPreferredSize(new Dimension(560, 0));
        JPanel secBody = new JPanel(new BorderLayout(14, 0));
        secBody.setOpaque(false);
        secBody.add(secCard, BorderLayout.WEST);
        JPanel bk = UiKit.card(new BorderLayout(0, 8));
        bk.add(UiKit.title("Backup files (newest first)", 14f), BorderLayout.NORTH);
        bk.add(UiKit.scroll(backupTable));
        secBody.add(bk);
        JPanel secTab = wrap(secBody, null);

        JPanel audit = new JPanel(new BorderLayout(0, 10));
        audit.setOpaque(false);
        audit.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        auditSearch.setPreferredSize(new Dimension(240, 34));
        audit.add(UiKit.row(new JLabel("From"), auditFrom, new JLabel("To"), auditTo, auditEntity, auditSearch, btnAudit, btnAuditExport),
                BorderLayout.NORTH);
        audit.add(UiKit.tableCard(auditTable));

        tabs.addTab("General", generalTab);
        tabs.addTab("Printing & Hardware", hwTab);
        tabs.addTab("Payment Methods", methods);
        tabs.addTab("Users & Permissions", users);
        tabs.addTab("Security & Backup", secTab);
        tabs.addTab("Audit Log", audit);
        add(UiKit.page("Settings", "Shop, POS rules and limits, numbering, hardware, payments, users, security, backups and audit",
                UiKit.row(btnSave), tabs));
    }

    private static JPanel wrap(Component body, JButton unused) {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        p.add(body, BorderLayout.CENTER);
        return p;
    }
}
