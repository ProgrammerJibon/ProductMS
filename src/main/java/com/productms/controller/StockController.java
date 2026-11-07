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
        Variant v = products.findByCode(code);
        if (v == null) {
            throw new AppException("No product with barcode / SKU \"" + code.trim() + "\".");
        }
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
        inventory.adjust(a);
        view.adjQty.setText("");
        view.adjReason.setText("");
        view.adjCount.setText("");
        setAdjVariant(products.findVariant(adjVariant.variantId));
        main.refreshAlerts();
        UiKit.info(view, "Stock updated.");
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
