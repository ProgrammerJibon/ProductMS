package com.productms.view;

import com.productms.model.Batch;
import com.productms.model.Supplier;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.math.BigDecimal;
import java.time.LocalDate;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/** Stock in, adjustments & write-offs, movement history, batches & expiry, serial lookup. */
public class StockView extends JPanel {

    public final JTabbedPane tabs = new JTabbedPane();

    // ---- stock in
    public final JTextField inScan = UiKit.search("Scan barcode / type SKU and press Enter");
    public final JButton inPick = UiKit.plain("Find product...");
    public final JLabel inProduct = UiKit.title("No product selected", 14f);
    public final JLabel inInfo = UiKit.mutedLabel(" ");
    public final JTextField inQty = UiKit.field("Quantity received");
    public final JComboBox<Object> inPack = new JComboBox<>();
    public final JTextField inCost = UiKit.field("Unit purchase price");
    public final JComboBox<Object> inSupplier = new JComboBox<>();
    public final JTextField inRef = UiKit.field("Supplier invoice / GRN no.");
    public final JTextField inBatch = UiKit.field("Batch / lot number");
    public final JTextField inMfg = UiKit.field("dd-mm-yyyy");
    public final JTextField inExpiry = UiKit.field("dd-mm-yyyy");
    public final JTextArea inSerials = new JTextArea(5, 20);
    public final JTextField inNote = UiKit.field("Optional note");
    public final JButton inSave = UiKit.success("Receive stock");
    public final JLabel inTotal = UiKit.mutedLabel(" ");
    public final DataTableModel recentModel = new DataTableModel();
    public final JTable recentTable = new JTable(recentModel);
    public final JPanel batchFields = new JPanel(new BorderLayout());
    public final JPanel serialFields = new JPanel(new BorderLayout());

    // ---- adjust
    public final JTextField adjScan = UiKit.search("Scan barcode / type SKU and press Enter");
    public final JButton adjPick = UiKit.plain("Find product...");
    public final JLabel adjProduct = UiKit.title("No product selected", 14f);
    public final JLabel adjInfo = UiKit.mutedLabel(" ");
    public final JComboBox<String> adjType = new JComboBox<>(new String[]{
        "Adjustment OUT (count is lower / lost / theft)", "Adjustment IN (count is higher / found)",
        "Damaged (write off)", "Expired (write off)"});
    public final JTextField adjQty = UiKit.field("Quantity");
    public final JComboBox<Object> adjBatch = new JComboBox<>();
    public final JTextArea adjSerials = new JTextArea(4, 20);
    public final JTextField adjReason = UiKit.field("Reason (required, kept in history)");
    public final JButton adjSave = UiKit.danger("Apply adjustment");
    public final JTextField adjCount = UiKit.field("Physical count");
    public final JButton adjFromCount = UiKit.plain("Set from physical count");

    // ---- movements
    public final JSpinner mvFrom = UiKit.dateSpinner(LocalDate.now().minusDays(30));
    public final JSpinner mvTo = UiKit.dateSpinner(LocalDate.now());
    public final JComboBox<String> mvType = new JComboBox<>(new String[]{"ALL", "OPENING", "STOCK_IN", "SALE", "SALE_CANCEL",
        "RETURN", "ADJUSTMENT_IN", "ADJUSTMENT_OUT", "DAMAGED", "EXPIRED"});
    public final JTextField mvSearch = UiKit.search("Product, SKU or reference");
    public final JButton mvLoad = UiKit.primary("Search");
    public final JButton mvExport = UiKit.plain("Export CSV");
    public final DataTableModel mvModel = new DataTableModel();
    public final JTable mvTable = new JTable(mvModel);

    // ---- batches
    public final JComboBox<String> btFilter = new JComboBox<>(new String[]{"All batches with stock", "Expiring soon", "Expired"});
    public final JTextField btSearch = UiKit.search("Product, SKU or batch");
    public final JButton btWriteOff = UiKit.danger("Write off selected batch");
    public final RowTableModel<Batch> btModel = new RowTableModel<Batch>()
            .col("Product", String.class, b -> b.productName + (b.variantLabel == null || b.variantLabel.isBlank() ? "" : " (" + b.variantLabel + ")"))
            .col("SKU", String.class, b -> b.sku)
            .col("Batch", String.class, b -> b.batchNo)
            .col("Mfg", LocalDate.class, b -> b.manufactureDate)
            .col("Expiry", LocalDate.class, b -> b.expiryDate)
            .col("Days left", Long.class, b -> b.expiryDate == null ? null : b.daysToExpiry())
            .col("Status", String.class, b -> b.isExpired() ? "EXPIRED" : (b.expiryDate != null && b.daysToExpiry() <= 30 ? "EXPIRING" : "OK"))
            .col("Received", BigDecimal.class, b -> b.qtyReceived)
            .col("Remaining", BigDecimal.class, b -> b.qtyRemaining)
            .col("Unit cost", BigDecimal.class, b -> b.unitCost)
            .col("Value", BigDecimal.class, b -> b.qtyRemaining.multiply(b.unitCost).setScale(2, java.math.RoundingMode.HALF_UP));
    public final JTable btTable = new JTable(btModel);

    // ---- damaged stock
    public final DataTableModel dmModel = new DataTableModel();
    public final JTable dmTable = new JTable(dmModel);
    public final JButton dmDispose = UiKit.danger("Dispose (final loss)");
    public final JButton dmRestore = UiKit.plain("Restore to sellable");
    public final DataTableModel dmLogModel = new DataTableModel();
    public final JTable dmLogTable = new JTable(dmLogModel);

    // ---- stock counts
    public final DataTableModel scModel = new DataTableModel();
    public final JTable scTable = new JTable(scModel);
    public final JButton scNew = UiKit.primary("+ New count");
    public final JButton scSubmit = UiKit.plain("Submit for approval");
    public final JButton scApprove = UiKit.success("Approve & book differences");
    public final JButton scCancel = UiKit.plain("Cancel count");
    public final JButton scSheet = UiKit.plain("Print count sheet");
    public final JTextField scScan = UiKit.search("Scan barcode to count +1 (or qty*code)");
    public final JComboBox<String> scShow = new JComboBox<>(new String[]{"All items", "Differences only"});
    public final DataTableModel sciModel = new DataTableModel();
    public final JTable sciTable = new JTable(sciModel);
    public final JButton scSetQty = UiKit.plain("Enter counted qty");
    public final JLabel scInfo = UiKit.mutedLabel("Select a count.");

    // ---- approvals
    public final DataTableModel apModel = new DataTableModel();
    public final JTable apTable = new JTable(apModel);
    public final JComboBox<String> apFilter = new JComboBox<>(new String[]{"Pending", "All"});
    public final JButton apApprove = UiKit.success("Approve");
    public final JButton apReject = UiKit.danger("Reject");

    // ---- serials
    public final JTextField srSearch = UiKit.search("Serial / IMEI or product name");
    public final DataTableModel srModel = new DataTableModel();
    public final JTable srTable = new JTable(srModel);

    public StockView() {
        super(new BorderLayout());
        for (JTable t : new JTable[]{recentTable, mvTable, btTable, srTable, dmTable, dmLogTable, scTable, sciTable, apTable}) {
            UiKit.styleTable(t);
        }
        tabs.addTab("Stock In (receive goods)", stockInTab());
        tabs.addTab("Adjust / Damaged / Expired", adjustTab());
        tabs.addTab("Movement History", movementsTab());
        tabs.addTab("Batches & Expiry", batchesTab());
        tabs.addTab("Damaged Stock", damagedTab());
        tabs.addTab("Stock Counts", countsTab());
        tabs.addTab("Approvals", approvalsTab());
        tabs.addTab("Serial Numbers & Warranty", serialsTab());
        add(UiKit.page("Stock", "Receive goods, correct counts, write off damaged & expired items, and trace every movement", null, tabs));
    }

    private JPanel productBox(JTextField scan, JButton pick, JLabel product, JLabel info) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(scan);
        top.add(pick, BorderLayout.EAST);
        p.add(top, BorderLayout.NORTH);
        p.add(product, BorderLayout.CENTER);
        p.add(info, BorderLayout.SOUTH);
        return p;
    }

    private JPanel stockInTab() {
        UiKit.Form f = new UiKit.Form();
        f.section("Product");
        f.full(productBox(inScan, inPick, inProduct, inInfo));
        f.section("Receiving");
        JPanel qtyRow = new JPanel(new BorderLayout(6, 0));
        qtyRow.setOpaque(false);
        qtyRow.add(inQty);
        inPack.setPreferredSize(new Dimension(150, 34));
        qtyRow.add(inPack, BorderLayout.EAST);
        f.add("Quantity *", qtyRow);
        f.add("Unit cost *", inCost);
        f.full(inTotal);
        f.add("Supplier", inSupplier);
        f.add("Reference no.", inRef);
        UiKit.Form bf = new UiKit.Form();
        bf.add("Batch / lot no. *", inBatch);
        bf.add("Manufactured", inMfg);
        bf.add("Expiry date", inExpiry);
        batchFields.setOpaque(false);
        batchFields.add(bf.panel);
        f.full(batchFields);
        serialFields.setOpaque(false);
        inSerials.setLineWrap(false);
        serialFields.add(new JLabel("Serial numbers / IMEI * (one per line, scanner friendly)"), BorderLayout.NORTH);
        serialFields.add(new JScrollPane(inSerials));
        f.full(serialFields);
        f.add("Note", inNote);
        f.full(UiKit.row(inSave));
        f.filler();
        JPanel form = UiKit.card(new BorderLayout());
        form.add(UiKit.scroll(f.panel));
        form.setPreferredSize(new Dimension(470, 0));

        JPanel right = UiKit.card(new BorderLayout(0, 8));
        right.add(UiKit.title("Recently received (30 days)", 14f), BorderLayout.NORTH);
        right.add(UiKit.scroll(recentTable));
        return split(form, right);
    }

    private JPanel adjustTab() {
        UiKit.Form f = new UiKit.Form();
        f.section("Product");
        f.full(productBox(adjScan, adjPick, adjProduct, adjInfo));
        f.section("Stock count helper");
        f.full(UiKit.row(adjCount, adjFromCount));
        adjCount.setPreferredSize(new Dimension(140, 34));
        f.section("Adjustment");
        f.add("Type", adjType);
        f.add("Quantity *", adjQty);
        f.add("Batch", adjBatch);
        f.add("Serials", new JScrollPane(adjSerials));
        f.add("Reason *", adjReason);
        f.full(UiKit.row(adjSave));
        f.filler();
        JPanel form = UiKit.card(new BorderLayout());
        form.add(UiKit.scroll(f.panel));
        form.setPreferredSize(new Dimension(520, 0));
        JPanel help = UiKit.card(new BorderLayout());
        JTextArea t = new JTextArea("How stock corrections work\n\n"
                + "• Adjustment OUT / IN: use after a physical stock count. Type the counted quantity in the helper and "
                + "click \"Set from physical count\" - the difference is filled in for you.\n\n"
                + "• Damaged: broken, spoiled or unsellable goods. The value is recorded as a loss "
                + "(Reports → Damaged & Expired Products).\n\n"
                + "• Expired: goods past their expiry date. For batch-tracked products choose the batch; "
                + "you can also write off a whole batch from the Batches & Expiry tab.\n\n"
                + "• Serial-tracked products: list the exact serial numbers being removed or added.\n\n"
                + "Every change is stored in Movement History with the user, time and reason.");
        t.setEditable(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setOpaque(false);
        help.add(t);
        return split(form, help);
    }

    private JPanel movementsTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        p.setOpaque(false);
        mvSearch.setPreferredSize(new Dimension(240, 34));
        p.add(UiKit.row(new JLabel("From"), mvFrom, new JLabel("To"), mvTo, new JLabel("Type"), mvType, mvSearch, mvLoad, mvExport),
                BorderLayout.NORTH);
        p.add(UiKit.tableCard(mvTable));
        return p;
    }

    private JPanel batchesTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        p.setOpaque(false);
        btSearch.setPreferredSize(new Dimension(260, 34));
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.add(UiKit.row(btFilter, btSearch), BorderLayout.WEST);
        bar.add(UiKit.rowRight(btWriteOff), BorderLayout.EAST);
        p.add(bar, BorderLayout.NORTH);
        p.add(UiKit.tableCard(btTable));
        p.add(UiKit.mutedLabel("Expired batches are never sold - the POS automatically sells the batch that expires first (FEFO)."),
                BorderLayout.SOUTH);
        return p;
    }

    private JPanel serialsTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        p.setOpaque(false);
        srSearch.setPreferredSize(new Dimension(320, 34));
        p.add(UiKit.row(srSearch, UiKit.mutedLabel("Find which invoice and customer a serial / IMEI was sold to (warranty checks).")),
                BorderLayout.NORTH);
        p.add(UiKit.tableCard(srTable));
        return p;
    }

    private JPanel damagedTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        p.setOpaque(false);
        p.add(UiKit.row(dmDispose, dmRestore, UiKit.mutedLabel("Damaged units are kept aside (not sellable) until disposed "
                + "or restored. Physical stock = sellable + damaged.")), BorderLayout.NORTH);
        javax.swing.JSplitPane sp = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, UiKit.tableCard(dmTable),
                UiKit.tableCard(dmLogTable));
        sp.setResizeWeight(0.5);
        sp.setBorder(null);
        p.add(sp);
        return p;
    }

    private JPanel countsTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        p.setOpaque(false);
        p.add(UiKit.row(scNew, scSubmit, scApprove, scCancel, scSheet), BorderLayout.NORTH);
        JPanel items = new JPanel(new BorderLayout(0, 8));
        items.setOpaque(false);
        scScan.setPreferredSize(new Dimension(320, 34));
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.add(UiKit.row(scScan, scShow, scSetQty), BorderLayout.WEST);
        bar.add(scInfo, BorderLayout.EAST);
        items.add(bar, BorderLayout.NORTH);
        items.add(UiKit.tableCard(sciTable));
        javax.swing.JSplitPane sp = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, UiKit.tableCard(scTable), items);
        sp.setResizeWeight(0.3);
        sp.setBorder(null);
        p.add(sp);
        return p;
    }

    private JPanel approvalsTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        p.setOpaque(false);
        p.add(UiKit.row(apFilter, apApprove, apReject, UiKit.mutedLabel("Adjustments at or above the approval value (Settings) "
                + "wait here for a manager. The requester cannot approve their own (admins can).")), BorderLayout.NORTH);
        p.add(UiKit.tableCard(apTable));
        return p;
    }

    private static JPanel split(JPanel left, JPanel right) {
        JPanel p = new JPanel(new BorderLayout(14, 0));
        p.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        p.setOpaque(false);
        p.add(left, BorderLayout.WEST);
        p.add(right);
        return p;
    }

    public void fillSuppliers(java.util.List<Supplier> list) {
        inSupplier.removeAllItems();
        inSupplier.addItem("(no supplier)");
        list.forEach(inSupplier::addItem);
    }
}
