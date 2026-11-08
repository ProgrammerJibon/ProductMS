package com.productms.controller;

import com.productms.dao.MasterDataDAO;
import com.productms.dao.ProductDAO;
import com.productms.db.Jdbc;
import com.productms.model.Batch;
import com.productms.model.Supplier;
import com.productms.model.Variant;
import com.productms.service.InventoryService;
import com.productms.util.AppException;
import com.productms.util.Money;
import com.productms.view.StockView;
import com.productms.view.TableExport;
import com.productms.view.UiKit;
import com.productms.view.VariantPickerDialog;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Stock in, adjustments, write-offs, movement history, batches and serial lookup. */
public class StockController implements Module {

    private final StockView view = new StockView();
    private final ProductDAO products = new ProductDAO();
    private final InventoryService inventory = new InventoryService();
    private final MasterDataDAO master = new MasterDataDAO();
    private final MainController main;
    private final com.productms.service.StockCountService counts = new com.productms.service.StockCountService();
    private Variant inVariant;
    private Variant adjVariant;

    public StockController(MainController main) {
        this.main = main;
        view.inPick.addActionListener(e -> setInVariant(VariantPickerDialog.pick(view, "Receive stock for...", true, false)));
        view.inScan.addActionListener(e -> UiKit.guard(view, () -> setInVariant(byCode(view.inScan.getText()))));
        view.inSave.addActionListener(e -> UiKit.guard(view, this::receive));
        DocumentListener total = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                updateTotal();
            }

            public void removeUpdate(DocumentEvent e) {
                updateTotal();
            }

            public void changedUpdate(DocumentEvent e) {
                updateTotal();
            }
        };
        view.inQty.getDocument().addDocumentListener(total);
        view.inCost.getDocument().addDocumentListener(total);

        view.adjPick.addActionListener(e -> setAdjVariant(VariantPickerDialog.pick(view, "Adjust stock of...", true, false)));
        view.adjScan.addActionListener(e -> UiKit.guard(view, () -> setAdjVariant(byCode(view.adjScan.getText()))));
        view.adjFromCount.addActionListener(e -> UiKit.guard(view, this::fromCount));
        view.adjSave.addActionListener(e -> UiKit.guard(view, this::adjust));

        view.mvLoad.addActionListener(e -> loadMovements());
        view.mvSearch.addActionListener(e -> loadMovements());
        view.mvExport.addActionListener(e -> TableExport.csv(view, view.mvTable, "stock_movements"));

        Timer bt = new Timer(300, e -> loadBatches());
        bt.setRepeats(false);
        view.btFilter.addActionListener(e -> loadBatches());
        view.btSearch.getDocument().addDocumentListener(restart(bt));
        view.btWriteOff.addActionListener(e -> writeOffBatch());

        Timer sr = new Timer(300, e -> UiKit.guard(view, () -> view.srModel.setData(products.serialLookup(view.srSearch.getText()))));
        sr.setRepeats(false);
        view.srSearch.getDocument().addDocumentListener(restart(sr));

        view.tabs.addChangeListener(e -> UiKit.guard(view, this::loadTab));
        view.dmDispose.addActionListener(e -> damagedAction(false));
        view.dmRestore.addActionListener(e -> damagedAction(true));
        view.scNew.addActionListener(e -> newCount());
        view.scSubmit.addActionListener(e -> countAction("SUBMIT"));
        view.scApprove.addActionListener(e -> countAction("APPROVE"));
        view.scCancel.addActionListener(e -> countAction("CANCEL"));
        view.scSheet.addActionListener(e -> com.productms.view.TableExport.print(view, view.sciTable, "Stock count sheet"));
        view.scScan.addActionListener(e -> scanCount());
        view.scSetQty.addActionListener(e -> setCountQty());
        view.scShow.addActionListener(e -> loadCountItems());
        view.scTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadCountItems();
            }
        });
        view.apFilter.addActionListener(e -> UiKit.guard(view, () ->
                view.apModel.setData(inventory.adjustmentRequests(view.apFilter.getSelectedIndex() == 0))));
        view.apApprove.addActionListener(e -> decide(true));
        view.apReject.addActionListener(e -> decide(false));
        setInVariant(null);
        setAdjVariant(null);
    }

    private static DocumentListener restart(Timer t) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                t.restart();
            }

            public void removeUpdate(DocumentEvent e) {
                t.restart();
            }

            public void changedUpdate(DocumentEvent e) {
                t.restart();
            }
        };
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void onShow() throws Exception {
        List<Supplier> s = master.suppliers(true);
        Object sel = view.inSupplier.getSelectedItem();
        view.fillSuppliers(s);
        if (sel != null) {
            view.inSupplier.setSelectedItem(sel);
        }
        loadTab();
    }

    private void loadTab() throws Exception {
        switch (view.tabs.getSelectedIndex()) {
            case 0:
                view.recentModel.setData(Jdbc.table("SELECT m.created_at AS `Date`, p.name AS `Product`, v.sku AS `SKU`, "
                        + "m.quantity AS `Qty`, m.unit_cost AS `Unit Cost`, ROUND(m.quantity*m.unit_cost,2) AS `Value`, "
                        + "b.batch_no AS `Batch`, s.name AS `Supplier`, m.reference_no AS `Ref` FROM stock_movements m "
                        + "JOIN product_variants v ON v.variant_id=m.variant_id JOIN products p ON p.product_id=v.product_id "
                        + "LEFT JOIN product_batches b ON b.batch_id=m.batch_id LEFT JOIN suppliers s ON s.supplier_id=m.supplier_id "
                        + "WHERE m.movement_type='STOCK_IN' AND m.created_at >= NOW() - INTERVAL 30 DAY ORDER BY m.movement_id DESC LIMIT 300"));
                break;
            case 2:
                loadMovements();
                break;
            case 3:
                loadBatches();
                break;
            case 4:
                loadDamaged();
                break;
            case 5:
                loadCounts();
                break;
            case 6:
                view.apModel.setData(inventory.adjustmentRequests(view.apFilter.getSelectedIndex() == 0));
                break;
            case 7:
                view.srModel.setData(products.serialLookup(view.srSearch.getText()));
                break;
            default:
                break;
        }
    }

    /** Called from Products → Stock In. */
    public void prefillStockIn(Variant v) {
        view.tabs.setSelectedIndex(0);
        if (v != null) {
            UiKit.guard(view, () -> setInVariant(products.findVariant(v.variantId)));
        }
    }

    private Variant byCode(String code) throws Exception {
        if (code == null || code.isBlank()) {
            return null;
        }
        ProductDAO.CodeMatch m = products.findByCode(code);
        if (m == null) {
            throw new AppException("No product with barcode / SKU \"" + code.trim() + "\".");
        }
        Variant v = m.variant;
        if (v.isBundle()) {
            throw new AppException(v.productName + " is a combo. Receive stock for its components instead.");
        }
        return v;
    }

    // ------------------------------------------------------------------ stock in

    private void setInVariant(Variant v) {
        inVariant = v;
        view.inScan.setText("");
        boolean batch = v != null && v.trackBatch;
        boolean serial = v != null && v.trackSerial;
        view.batchFields.setVisible(batch);
        view.serialFields.setVisible(serial);
        if (v == null) {
            view.inProduct.setText("No product selected");
            view.inInfo.setText("Scan or search a product to receive.");
            return;
        }
        view.inProduct.setText(v.displayName());
        view.inInfo.setText("SKU " + v.sku + "  ·  in stock " + Money.qty(v.stockQty) + " " + v.unitShort
                + "  ·  avg cost " + Money.cur(v.purchasePrice) + (batch ? "  ·  batch tracked" : "")
                + (serial ? "  ·  serial tracked" : ""));
        view.inCost.setText(v.purchasePrice.toPlainString());
        view.inPack.removeAllItems();
        view.inPack.addItem(v.unitShort);
        UiKit.guard(view, () -> {
            for (Object[] pk : products.packs(v.variantId)) {
                view.inPack.addItem(pk[0] + " (" + Money.qty((BigDecimal) pk[1]) + " " + v.unitShort + ")");
            }
        });
        view.inQty.requestFocusInWindow();
        view.revalidate();
    }

    private void updateTotal() {
        try {
            BigDecimal q = Money.parse(view.inQty.getText());
            BigDecimal c = Money.parse(view.inCost.getText());
            view.inTotal.setText(q != null && c != null ? "Total purchase value: " + Money.cur(q.multiply(c)) : " ");
        } catch (IllegalArgumentException e) {
            view.inTotal.setText(" ");
        }
    }

    private void receive() throws Exception {
        if (inVariant == null) {
            throw new AppException("Select the product being received.");
        }
        InventoryService.StockIn in = new InventoryService.StockIn();
        in.variantId = inVariant.variantId;
        in.quantity = Money.parse(view.inQty.getText());
        in.unitCost = Money.parse(view.inCost.getText());
        int packIndex = view.inPack.getSelectedIndex();
        if (packIndex > 0 && in.quantity != null) {
            BigDecimal perPack = (BigDecimal) products.packs(inVariant.variantId).get(packIndex - 1)[1];
            if (in.unitCost != null && !UiKit.confirm(view, "Receiving " + Money.qty(in.quantity) + " pack(s) = "
                    + Money.qty(in.quantity.multiply(perPack)) + " " + inVariant.unitShort + ".\nIs " + Money.cur(in.unitCost)
                    + " the cost of ONE " + inVariant.unitShort + "? (No = it is the cost per pack)")) {
                in.unitCost = in.unitCost.divide(perPack, 2, java.math.RoundingMode.HALF_UP);
            }
            in.quantity = in.quantity.multiply(perPack);
        }
        Object s = view.inSupplier.getSelectedItem();
        in.supplierId = s instanceof Supplier ? ((Supplier) s).supplierId : null;
        in.referenceNo = view.inRef.getText();
        in.batchNo = view.inBatch.getText();
        in.manufactureDate = UiKit.parseDate(view.inMfg.getText(), "Manufacture date");
        in.expiryDate = UiKit.parseDate(view.inExpiry.getText(), "Expiry date");
        in.serials = new ArrayList<>(Arrays.asList(view.inSerials.getText().split("\\R")));
        in.note = view.inNote.getText().isBlank() ? null : view.inNote.getText().trim();
        if (inVariant.trackBatch && in.expiryDate == null
                && !UiKit.confirm(view, "No expiry date entered for a batch-tracked product. Continue?")) {
            return;
        }
        inventory.stockIn(in);
        String msg = "Received " + Money.qty(in.quantity) + " " + inVariant.unitShort + " of " + inVariant.displayName() + ".";
        view.inQty.setText("");
        view.inBatch.setText("");
        view.inMfg.setText("");
        view.inExpiry.setText("");
        view.inSerials.setText("");
        view.inNote.setText("");
        setInVariant(products.findVariant(inVariant.variantId));
        loadTab();
        main.refreshAlerts();
        UiKit.info(view, msg);
        view.inScan.requestFocusInWindow();
    }

    // ------------------------------------------------------------------ adjust

    private void setAdjVariant(Variant v) {
        adjVariant = v;
        view.adjScan.setText("");
        view.adjBatch.removeAllItems();
        view.adjBatch.setEnabled(v != null && v.trackBatch);
        view.adjSerials.setEnabled(v != null && v.trackSerial);
        view.adjSerials.setText("");
        if (v == null) {
            view.adjProduct.setText("No product selected");
            view.adjInfo.setText("Scan or search a product to adjust.");
            return;
        }
        view.adjProduct.setText(v.displayName());
        view.adjInfo.setText("SKU " + v.sku + "  ·  system stock " + Money.qty(v.stockQty) + " " + v.unitShort);
        UiKit.guard(view, () -> {
            if (v.trackBatch) {
                for (Batch b : products.batches(v.variantId, false)) {
                    view.adjBatch.addItem(b);
                }
            }
            if (v.trackSerial) {
                view.adjInfo.setText(view.adjInfo.getText() + "  ·  in-stock serials: "
                        + String.join(", ", products.serialsInStock(v.variantId)));
            }
        });
    }

    private void fromCount() throws Exception {
        if (adjVariant == null) {
            throw new AppException("Select a product first.");
        }
        BigDecimal counted = Money.parse(view.adjCount.getText());
        if (counted == null || counted.signum() < 0) {
            throw new AppException("Enter the physically counted quantity.");
        }
        Variant fresh = products.findVariant(adjVariant.variantId);
        BigDecimal diff = counted.subtract(fresh.stockQty);
        if (diff.signum() == 0) {
            UiKit.info(view, "The count matches the system stock (" + Money.qty(fresh.stockQty) + "). No adjustment needed.");
            return;
        }
        view.adjType.setSelectedIndex(diff.signum() < 0 ? 0 : 1);
        view.adjQty.setText(Money.qty(diff.abs()));
        view.adjReason.setText("Stock count: system " + Money.qty(fresh.stockQty) + ", counted " + Money.qty(counted));
    }

    private void adjust() throws Exception {
        if (adjVariant == null) {
            throw new AppException("Select a product first.");
        }
        InventoryService.Adjustment a = new InventoryService.Adjustment();
        a.variantId = adjVariant.variantId;
        a.type = new String[]{"ADJUSTMENT_OUT", "ADJUSTMENT_IN", "DAMAGED", "EXPIRED"}[view.adjType.getSelectedIndex()];
        a.quantity = Money.parse(view.adjQty.getText());
        Object b = view.adjBatch.getSelectedItem();
        a.batchId = b instanceof Batch ? ((Batch) b).batchId : null;
        a.serials = new ArrayList<>(Arrays.asList(view.adjSerials.getText().split("[\\r\\n,]+")));
        a.note = view.adjReason.getText();
        if (!UiKit.confirm(view, view.adjType.getSelectedItem() + "\n\n" + adjVariant.displayName() + "\nQuantity: "
                + (a.quantity == null ? "?" : Money.qty(a.quantity)) + "\n\nApply?")) {
            return;
        }
        Integer pending = inventory.adjust(a);
        view.adjQty.setText("");
        view.adjReason.setText("");
        view.adjCount.setText("");
        setAdjVariant(products.findVariant(adjVariant.variantId));
        main.refreshAlerts();
        UiKit.info(view, pending == null ? "Stock updated." : "This adjustment is worth more than the approval limit.\n"
                + "Request #" + pending + " is waiting for a manager in Stock \u2192 Approvals.");
    }

    // ------------------------------------------------------------------ damaged stock

    private void loadDamaged() throws Exception {
        view.dmModel.setData(inventory.damagedStock());
        view.dmLogModel.setData(Jdbc.table("SELECT l.created_at AS `Date`, p.name AS `Product`, v.sku AS `SKU`, l.action AS `Action`, "
                + "l.quantity AS `Qty`, l.unit_cost AS `Unit cost`, ROUND(ABS(l.quantity) * l.unit_cost, 2) AS `Value`, "
                + "l.reference AS `Reference`, l.note AS `Note`, u.username AS `By` FROM damaged_stock_log l "
                + "JOIN product_variants v ON v.variant_id = l.variant_id JOIN products p ON p.product_id = v.product_id "
                + "LEFT JOIN users u ON u.user_id = l.user_id ORDER BY l.log_id DESC LIMIT 500"));
    }

    private void damagedAction(boolean restore) {
        int r = view.dmTable.getSelectedRow();
        if (r < 0) {
            UiKit.warn(view, "Select a product in the damaged stock list.");
            return;
        }
        int variantId = ((Number) view.dmModel.getValueAt(view.dmTable.convertRowIndexToModel(r), 0)).intValue();
        String name = String.valueOf(view.dmModel.getValueAt(view.dmTable.convertRowIndexToModel(r), 1));
        UiKit.guard(view, () -> {
            String q = UiKit.input(view, (restore ? "Restore" : "Dispose") + " how many damaged unit(s) of " + name + "?",
                    String.valueOf(view.dmModel.getValueAt(view.dmTable.convertRowIndexToModel(r), 3)));
            if (q == null) {
                return;
            }
            String note = UiKit.input(view, "Reason (e.g. thrown away, returned to supplier, repaired):", "");
            if (note == null) {
                return;
            }
            if (restore) {
                inventory.restoreDamaged(variantId, Money.parse(q), note);
            } else {
                inventory.disposeDamaged(variantId, Money.parse(q), note);
            }
            loadDamaged();
            main.refreshAlerts();
        });
    }

    // ------------------------------------------------------------------ stock counts

    private Integer selectedCount() {
        int r = view.scTable.getSelectedRow();
        return r < 0 ? null : ((Number) view.scModel.getValueAt(view.scTable.convertRowIndexToModel(r), 0)).intValue();
    }

    private String selectedCountStatus() {
        int r = view.scTable.getSelectedRow();
        return r < 0 ? null : String.valueOf(view.scModel.getValueAt(view.scTable.convertRowIndexToModel(r), 3));
    }

    private void loadCounts() throws Exception {
        Integer keep = selectedCount();
        view.scModel.setData(counts.counts());
        if (keep != null) {
            for (int i = 0; i < view.scTable.getRowCount(); i++) {
                if (((Number) view.scModel.getValueAt(view.scTable.convertRowIndexToModel(i), 0)).intValue() == keep) {
                    view.scTable.setRowSelectionInterval(i, i);
                }
            }
        }
        loadCountItems();
    }

    private void loadCountItems() {
        Integer id = selectedCount();
        UiKit.guard(view, () -> {
            if (id == null) {
                view.sciModel.setData(null);
                view.scInfo.setText("Select a count, or start a new one.");
                return;
            }
            view.sciModel.setData(counts.items(id, view.scShow.getSelectedIndex() == 1));
            String st = selectedCountStatus();
            view.scInfo.setText("Status: " + st + ("OPEN".equals(st) ? " - scan items or enter counted quantities" : ""));
            boolean open = "OPEN".equals(st);
            view.scScan.setEnabled(open);
            view.scSetQty.setEnabled(open);
            view.scSubmit.setEnabled(open);
            view.scApprove.setEnabled("SUBMITTED".equals(st));
        });
    }

    private void newCount() {
        UiKit.guard(view, () -> {
            javax.swing.JTextField name = UiKit.field("e.g. Month-end count November");
            javax.swing.JComboBox<Object> cat = new javax.swing.JComboBox<>();
            cat.addItem("Whole shop");
            master.categories(true).forEach(cat::addItem);
            UiKit.Form f = new UiKit.Form();
            f.add("Name *", name);
            f.add("Scope", cat);
            f.full(UiKit.mutedLabel("System quantities are frozen now. Sales during the count are allowed for."));
            if (javax.swing.JOptionPane.showConfirmDialog(view, f.panel, "New stock count", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                    javax.swing.JOptionPane.PLAIN_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) {
                return;
            }
            Object c = cat.getSelectedItem();
            counts.start(name.getText(), c instanceof com.productms.model.Category ? ((com.productms.model.Category) c).categoryId : null);
            loadCounts();
            if (view.scTable.getRowCount() > 0) {
                view.scTable.setRowSelectionInterval(0, 0);
            }
            view.scScan.requestFocusInWindow();
        });
    }

    private void scanCount() {
        Integer id = selectedCount();
        String text = view.scScan.getText().trim();
        if (id == null || text.isEmpty()) {
            return;
        }
        UiKit.guard(view, () -> {
            BigDecimal qty = null;
            String code = text;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*[*xX]\\s*(.+)$").matcher(text);
            if (m.matches()) {
                qty = new BigDecimal(m.group(1));
                code = m.group(2);
            }
            String result = counts.scan(id, code, qty);
            view.scInfo.setText(result);
            view.scScan.setText("");
            view.sciModel.setData(counts.items(id, view.scShow.getSelectedIndex() == 1));
        });
    }

    private void setCountQty() {
        Integer id = selectedCount();
        int r = view.sciTable.getSelectedRow();
        if (id == null || r < 0) {
            UiKit.warn(view, "Select an item of the count.");
            return;
        }
        int variantId = ((Number) view.sciModel.getValueAt(view.sciTable.convertRowIndexToModel(r), 0)).intValue();
        Object cur = view.sciModel.getValueAt(view.sciTable.convertRowIndexToModel(r), 6);
        UiKit.guard(view, () -> {
            String q = UiKit.input(view, "Counted quantity of " + view.sciModel.getValueAt(view.sciTable.convertRowIndexToModel(r), 1)
                    + " (blank = not counted):", cur == null ? "" : Money.qty((BigDecimal) cur));
            if (q == null) {
                return;
            }
            counts.setCounted(id, variantId, Money.parse(q));
            view.sciModel.setData(counts.items(id, view.scShow.getSelectedIndex() == 1));
        });
    }

    private void countAction(String action) {
        Integer id = selectedCount();
        if (id == null) {
            UiKit.warn(view, "Select a count.");
            return;
        }
        UiKit.guard(view, () -> {
            switch (action) {
                case "SUBMIT":
                    if (UiKit.confirm(view, "Submit this count? Counting stops and a manager reviews the differences.")) {
                        counts.submit(id);
                    }
                    break;
                case "APPROVE":
                    view.scShow.setSelectedIndex(1);
                    if (UiKit.confirm(view, "Book all differences of this count as stock adjustments?")) {
                        UiKit.info(view, counts.approve(id));
                        main.refreshAlerts();
                    }
                    break;
                case "CANCEL":
                    if (UiKit.confirm(view, "Cancel this count? Nothing is booked.")) {
                        counts.cancel(id);
                    }
                    break;
                default:
                    break;
            }
            loadCounts();
        });
    }

    // ------------------------------------------------------------------ approvals

    private void decide(boolean approve) {
        int r = view.apTable.getSelectedRow();
        if (r < 0) {
            UiKit.warn(view, "Select a request.");
            return;
        }
        int id = ((Number) view.apModel.getValueAt(view.apTable.convertRowIndexToModel(r), 0)).intValue();
        String note = UiKit.input(view, (approve ? "Approve" : "Reject") + " request #" + id + ". Note (optional):", "");
        if (note == null) {
            return;
        }
        UiKit.guard(view, () -> {
            inventory.decide(id, approve, note.isBlank() ? null : note.trim());
            view.apModel.setData(inventory.adjustmentRequests(view.apFilter.getSelectedIndex() == 0));
            main.refreshAlerts();
        });
    }

    // ------------------------------------------------------------------ lists

    private void loadMovements() {
        UiKit.guard(view, () -> view.mvModel.setData(inventory.movements(UiKit.dateOf(view.mvFrom), UiKit.dateOf(view.mvTo),
                (String) view.mvType.getSelectedItem(), view.mvSearch.getText())));
    }

    private void loadBatches() {
        UiKit.guard(view, () -> view.btModel.setRows(products.allBatches(
                new String[]{"ALL", "EXPIRING", "EXPIRED"}[view.btFilter.getSelectedIndex()],
                com.productms.util.Settings.getInt("expiry_alert_days", 30), view.btSearch.getText())));
    }

    private void writeOffBatch() {
        Batch b = view.btModel.selected(view.btTable);
        if (b == null) {
            UiKit.warn(view, "Select a batch.");
            return;
        }
        String type = b.isExpired() ? "EXPIRED" : "DAMAGED";
        if (!UiKit.confirm(view, "Write off batch " + b.batchNo + " of " + b.productName + "?\n\n"
                + Money.qty(b.qtyRemaining) + " unit(s), value " + Money.cur(b.qtyRemaining.multiply(b.unitCost))
                + "\nRecorded as: " + type)) {
            return;
        }
        UiKit.guard(view, () -> {
            inventory.writeOffBatch(b.batchId, type, (b.isExpired() ? "Expired batch " : "Batch written off ") + b.batchNo);
            loadBatches();
            main.refreshAlerts();
        });
    }
}
