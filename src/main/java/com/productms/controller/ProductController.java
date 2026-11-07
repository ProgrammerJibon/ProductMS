package com.productms.controller;

import com.productms.dao.MasterDataDAO;
import com.productms.dao.ProductDAO;
import com.productms.model.Brand;
import com.productms.model.BundleItem;
import com.productms.model.Category;
import com.productms.model.Product;
import com.productms.model.Variant;
import com.productms.service.ImportExportService;
import com.productms.util.AppException;
import com.productms.util.BarcodeUtil;
import com.productms.util.Money;
import com.productms.view.LabelPrintDialog;
import com.productms.view.ProductEditorDialog;
import com.productms.view.ProductsView;
import com.productms.view.UiKit;
import com.productms.view.VariantPickerDialog;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Product list, filters, editor, activation, delete, labels and CSV import/export. */
public class ProductController implements Module {

    private final ProductsView view = new ProductsView();
    private final ProductDAO dao = new ProductDAO();
    private final MasterDataDAO master = new MasterDataDAO();
    private final MainController main;
    private boolean loadingFilters;

    public ProductController(MainController main) {
        this.main = main;
        view.table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        Timer debounce = new Timer(300, e -> reload());
        debounce.setRepeats(false);
        view.txtSearch.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                debounce.restart();
            }

            public void removeUpdate(DocumentEvent e) {
                debounce.restart();
            }

            public void changedUpdate(DocumentEvent e) {
                debounce.restart();
            }
        });
        view.cmbCategory.addActionListener(e -> reload());
        view.cmbBrand.addActionListener(e -> reload());
        view.cmbStatus.addActionListener(e -> reload());
        view.cmbStock.addActionListener(e -> reload());
        view.table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetail();
            }
        });
        view.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    edit();
                }
            }
        });
        view.btnNew.addActionListener(e -> openEditor(null));
        view.btnEdit.addActionListener(e -> edit());
        view.btnToggle.addActionListener(e -> toggleActive());
        view.btnDelete.addActionListener(e -> delete());
        view.btnStockIn.addActionListener(e -> {
            Variant v = view.model.selected(view.table);
            main.openStockIn(v);
        });
        view.btnLabels.addActionListener(e -> labels());
        view.btnExport.addActionListener(e -> exportCsv());
        view.btnImport.addActionListener(e -> importCsv());
        view.btnTemplate.addActionListener(e -> template());
        UiKit.bind(view, "ctrl N", () -> openEditor(null));
        UiKit.bind(view, "ctrl F", () -> view.txtSearch.requestFocusInWindow());
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void onShow() throws Exception {
        loadingFilters = true;
        try {
            view.fillFilters(master.categories(true), master.brands(true));
        } finally {
            loadingFilters = false;
        }
        reload();
    }

    private void reload() {
        if (loadingFilters) {
            return;
        }
        UiKit.guard(view, () -> {
            ProductDAO.Filter f = new ProductDAO.Filter();
            f.text = view.txtSearch.getText();
            Object c = view.cmbCategory.getSelectedItem();
            f.categoryId = c instanceof Category ? ((Category) c).categoryId : null;
            Object b = view.cmbBrand.getSelectedItem();
            f.brandId = b instanceof Brand ? ((Brand) b).brandId : null;
            f.status = new String[]{"ALL", "ACTIVE", "INACTIVE"}[view.cmbStatus.getSelectedIndex()];
            f.stock = new String[]{"ALL", "IN", "LOW", "OUT"}[view.cmbStock.getSelectedIndex()];
            Variant sel = view.model.selected(view.table);
            List<Variant> rows = dao.search(f);
            view.model.setRows(rows);
            BigDecimal value = BigDecimal.ZERO;
            for (Variant v : rows) {
                value = value.add(v.stockQty.multiply(v.purchasePrice));
            }
            view.lblCount.setText(rows.size() + " item(s)  ·  stock value at cost " + Money.cur(value));
            if (sel != null) {
                for (int i = 0; i < view.table.getRowCount(); i++) {
                    if (view.model.row(view.table.convertRowIndexToModel(i)).variantId == sel.variantId) {
                        view.table.setRowSelectionInterval(i, i);
                    }
                }
            }
            showDetail();
        });
    }

    private void showDetail() {
        Variant v = view.model.selected(view.table);
        view.btnToggle.setText(v != null && !v.productActive ? "Activate" : "Deactivate");
        if (v == null) {
            view.lblName.setText(" ");
            view.txtDetails.setText("Select a product to see its details.");
            view.imgProduct.setIcon(null);
            view.imgProduct.setText("No image");
            view.imgBarcode.setIcon(null);
            view.imgQr.setIcon(null);
            return;
        }
        view.lblName.setText("<html>" + v.displayName() + "</html>");
        BigDecimal margin = v.retailPrice.signum() == 0 ? BigDecimal.ZERO
                : v.retailPrice.subtract(v.purchasePrice).multiply(Money.HUNDRED).divide(v.retailPrice, 1, java.math.RoundingMode.HALF_UP);
        StringBuilder sb = new StringBuilder();
        sb.append("SKU: ").append(v.sku).append("\nBarcode: ").append(v.barcode == null ? "-" : v.barcode)
                .append("\nType: ").append(v.isBundle() ? "Bundle / Combo" : "Standard")
                .append(v.trackBatch ? " · batch & expiry tracked" : "").append(v.trackSerial ? " · serial tracked" : "")
                .append("\nCategory: ").append(v.categoryName == null ? "-" : v.categoryName)
                .append("\nBrand: ").append(v.brandName == null ? "-" : v.brandName)
                .append("\n\nCost: ").append(Money.cur(v.purchasePrice))
                .append("\nRetail: ").append(Money.cur(v.retailPrice)).append("   (margin ").append(margin).append("%)")
                .append("\nWholesale: ").append(Money.cur(v.wholesalePrice));
        if (v.wholesaleMinQty.signum() > 0) {
            sb.append("  from ").append(Money.qty(v.wholesaleMinQty)).append(" ").append(v.unitShort);
        }
        sb.append("\nVAT: ").append(Money.qty(v.vatPercent)).append("%");
        if (!"NONE".equals(v.discountType)) {
            sb.append("\nDiscount: ").append("PERCENT".equals(v.discountType) ? Money.qty(v.discountValue) + "%"
                    : Money.cur(v.discountValue) + " per unit");
        }
        sb.append("\n\nStock: ").append(v.isBundle() ? "from components" : Money.qty(v.stockQty) + " " + v.unitShort)
                .append("\nAvailable to sell: ").append(Money.qty(v.available))
                .append("\nReorder level: ").append(Money.qty(v.reorderLevel));
        if (v.description != null) {
            sb.append("\n\n").append(v.description);
        }
        view.txtDetails.setText(sb.toString());
        view.txtDetails.setCaretPosition(0);
        if (v.imagePath != null && new File(v.imagePath).exists()) {
            ImageIcon icon = new ImageIcon(v.imagePath);
            double s = Math.min(250.0 / Math.max(1, icon.getIconWidth()), 165.0 / Math.max(1, icon.getIconHeight()));
            view.imgProduct.setText(null);
            view.imgProduct.setIcon(new ImageIcon(icon.getImage().getScaledInstance(
                    (int) (icon.getIconWidth() * s), (int) (icon.getIconHeight() * s), Image.SCALE_SMOOTH)));
        } else {
            view.imgProduct.setIcon(null);
            view.imgProduct.setText("No image");
        }
        try {
            String code = v.barcode == null ? v.sku : v.barcode;
            view.imgBarcode.setIcon(new ImageIcon(BarcodeUtil.barcodeImage(code, 150, 60)));
            view.imgQr.setIcon(new ImageIcon(BarcodeUtil.qrImage(v.sku + "|" + code + "|" + v.displayName() + "|"
                    + Money.fmt(v.retailPrice), 90)));
        } catch (AppException e) {
            view.imgBarcode.setIcon(null);
            view.imgQr.setIcon(null);
        }
    }

    // ------------------------------------------------------------------ editor

    private void edit() {
        Variant v = view.model.selected(view.table);
        if (v == null) {
            UiKit.warn(view, "Select a product first.");
            return;
        }
        openEditor(v.productId);
    }

    private void openEditor(Integer productId) {
        UiKit.guard(view, () -> {
            Product p = productId == null ? newProduct() : dao.getProduct(productId);
            ProductEditorDialog d = new ProductEditorDialog(UiKit.window(view), productId == null);
            d.fillLists(master.categories(true), master.brands(true), master.units());
            d.setProduct(p);
            wireEditor(d, p);
            updateBundleState(d);
            d.setVisible(true);
        });
    }

    private Product newProduct() {
        Product p = new Product();
        Variant v = new Variant();
        v.reorderLevel = new BigDecimal("5");
        p.variants.add(v);
        return p;
    }

    private void wireEditor(ProductEditorDialog d, Product p) {
        d.cmbType.addActionListener(e -> updateBundleState(d));
        d.chkBatch.addActionListener(e -> {
            if (d.chkBatch.isSelected()) {
                d.chkSerial.setSelected(false);
            }
        });
        d.chkSerial.addActionListener(e -> {
            if (d.chkSerial.isSelected()) {
                d.chkBatch.setSelected(false);
            }
        });
        d.btnAddVariant.addActionListener(e -> {
            Variant v = new Variant();
            if (!d.variantModel.rows.isEmpty()) {
                copyPrices(d.variantModel.rows.get(0), v);
            }
            d.variantModel.add(v);
            int r = d.variantModel.getRowCount() - 1;
            d.tblVariants.setRowSelectionInterval(r, r);
            d.tblVariants.editCellAt(r, 0);
        });
        d.btnRemoveVariant.addActionListener(e -> {
            int r = d.tblVariants.getSelectedRow();
            if (r < 0) {
                UiKit.warn(d, "Select a variant row.");
                return;
            }
            if (d.tblVariants.isEditing()) {
                d.tblVariants.getCellEditor().cancelCellEditing();
            }
            Variant v = d.variantModel.rows.get(r);
            if (v.variantId != 0 && !UiKit.confirm(d, "Remove variant " + (v.sku == null ? "" : v.sku) + "?\n"
                    + "If it has sales or stock it will be deactivated instead of deleted.")) {
                return;
            }
            d.variantModel.remove(r);
        });
        d.btnCopyPrices.addActionListener(e -> {
            if (d.tblVariants.isEditing()) {
                d.tblVariants.getCellEditor().stopCellEditing();
            }
            List<Variant> rows = d.variantModel.rows;
            for (int i = 1; i < rows.size(); i++) {
                copyPrices(rows.get(0), rows.get(i));
            }
            d.variantModel.fireTableDataChanged();
        });
        d.btnCombos.addActionListener(e -> generateCombos(d));
        d.btnImage.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Images (png, jpg, gif)", "png", "jpg", "jpeg", "gif"));
            if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION) {
                d.chosenImage = fc.getSelectedFile();
                d.imageRemoved = false;
                d.showImage(d.chosenImage);
            }
        });
        d.btnRemoveImage.addActionListener(e -> {
            d.chosenImage = null;
            d.imageRemoved = true;
            d.showImage(null);
        });
        d.btnAddComponent.addActionListener(e -> {
            Variant c = VariantPickerDialog.pick(d, "Add bundle component", true, true);
            if (c == null) {
                return;
            }
            if (c.trackSerial) {
                UiKit.warn(d, "Serial-tracked items cannot be part of a combo (each unit needs its own serial at sale).");
                return;
            }
            for (BundleItem b : d.bundleModel.rows()) {
                if (b.componentVariantId == c.variantId) {
                    b.quantity = b.quantity.add(BigDecimal.ONE);
                    d.bundleModel.fireTableDataChanged();
                    updateBundleState(d);
                    return;
                }
            }
            BundleItem b = new BundleItem();
            b.componentVariantId = c.variantId;
            b.componentName = c.displayName();
            b.componentSku = c.sku;
            List<BundleItem> rows = new ArrayList<>(d.bundleModel.rows());
            rows.add(b);
            d.bundleModel.setRows(rows);
            updateBundleState(d);
        });
        d.btnRemoveComponent.addActionListener(e -> {
            BundleItem b = d.bundleModel.selected(d.tblBundle);
            if (b != null) {
                List<BundleItem> rows = new ArrayList<>(d.bundleModel.rows());
                rows.remove(b);
                d.bundleModel.setRows(rows);
                updateBundleState(d);
            }
        });
        d.btnComponentQty.addActionListener(e -> UiKit.guard(d, () -> {
            BundleItem b = d.bundleModel.selected(d.tblBundle);
            if (b == null) {
                return;
            }
            String s = UiKit.input(d, "Quantity of " + b.componentName + " in one combo:", Money.qty(b.quantity));
            if (s != null) {
                BigDecimal q = Money.parse(s);
                if (!Money.pos(q)) {
                    throw new AppException("Quantity must be greater than 0.");
                }
                b.quantity = q;
                d.bundleModel.fireTableDataChanged();
                updateBundleState(d);
            }
        }));
        d.btnCancel.addActionListener(e -> d.dispose());
        d.btnSave.addActionListener(e -> UiKit.guard(d, () -> save(d, p)));
    }

    private static void copyPrices(Variant from, Variant to) {
        to.purchasePrice = from.purchasePrice;
        to.retailPrice = from.retailPrice;
        to.wholesalePrice = from.wholesalePrice;
        to.wholesaleMinQty = from.wholesaleMinQty;
        to.reorderLevel = from.reorderLevel;
    }

    private void generateCombos(ProductEditorDialog d) {
        String sizes = UiKit.input(d, "Sizes (comma separated), e.g. S, M, L, XL  - leave blank for none:", "S, M, L, XL");
        if (sizes == null) {
            return;
        }
        String colours = UiKit.input(d, "Colours (comma separated), e.g. Black, White - leave blank for none:", "Black, White");
        if (colours == null) {
            return;
        }
        String[] ss = sizes.isBlank() ? new String[]{null} : sizes.split(",");
        String[] cs = colours.isBlank() ? new String[]{null} : colours.split(",");
        Variant template = d.variantModel.rows.isEmpty() ? new Variant() : d.variantModel.rows.get(0);
        boolean replaceBlank = d.variantModel.rows.size() == 1 && template.variantId == 0
                && template.size == null && template.colour == null;
        List<Variant> rows = new ArrayList<>(replaceBlank ? new ArrayList<>() : d.variantModel.rows);
        for (String s : ss) {
            for (String c : cs) {
                Variant v = new Variant();
                copyPrices(template, v);
                v.size = s == null ? null : s.trim();
                v.colour = c == null ? null : c.trim();
                rows.add(v);
            }
        }
        d.variantModel.setRows(rows);
    }

    private void updateBundleState(ProductEditorDialog d) {
        boolean bundle = d.cmbType.getSelectedIndex() == 1;
        d.tabs.setEnabledAt(2, bundle);
        d.chkBatch.setEnabled(!bundle);
        d.chkSerial.setEnabled(!bundle);
        d.btnAddVariant.setEnabled(!bundle);
        d.btnCombos.setEnabled(!bundle);
        if (bundle) {
            d.chkBatch.setSelected(false);
            d.chkSerial.setSelected(false);
            UiKit.guard(d, () -> {
                BigDecimal sum = BigDecimal.ZERO;
                for (BundleItem b : d.bundleModel.rows()) {
                    Variant c = dao.findVariant(b.componentVariantId);
                    if (c != null) {
                        sum = sum.add(c.retailPrice.multiply(b.quantity));
                    }
                }
                BigDecimal price = d.variantModel.rows.isEmpty() ? BigDecimal.ZERO : d.variantModel.rows.get(0).retailPrice;
                d.lblBundleInfo.setText("Items bought separately: " + Money.cur(sum) + "   ·   combo price: "
                        + Money.cur(price) + (sum.compareTo(price) > 0 ? "   ·   customer saves " + Money.cur(sum.subtract(price)) : ""));
            });
        }
    }

    private void save(ProductEditorDialog d, Product p) throws Exception {
        d.readInto(p);
        if (p.isBundle() && p.variants.size() > 1) {
            throw new AppException("A combo has one selling row. Remove the extra variants on the Variants tab.");
        }
        for (Variant v : p.variants) {
            if (v.retailPrice.signum() > 0 && v.purchasePrice.compareTo(v.retailPrice) > 0
                    && !UiKit.confirm(d, "Retail price of " + (v.label().isEmpty() ? p.name : v.label())
                    + " is below its cost. Save anyway?")) {
                return;
            }
        }
        if (d.imageRemoved) {
            p.imagePath = null;
        }
        dao.save(p);
        if (d.chosenImage != null) {
            Path dir = Paths.get("product-images");
            Files.createDirectories(dir);
            String name = d.chosenImage.getName();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : ".png";
            Path target = dir.resolve("product_" + p.productId + "_" + System.currentTimeMillis() + ext);
            Files.copy(d.chosenImage.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            dao.updateImage(p.productId, target.toString().replace('\\', '/'));
        }
        d.dispose();
        reload();
        main.refreshAlerts();
    }

    // ------------------------------------------------------------------ actions

    private void toggleActive() {
        Variant v = view.model.selected(view.table);
        if (v == null) {
            UiKit.warn(view, "Select a product first.");
            return;
        }
        boolean activate = !v.productActive;
        if (UiKit.confirm(view, (activate ? "Activate" : "Deactivate") + " \"" + v.productName + "\" (all its variants)?"
                + (activate ? "" : "\nIt will no longer appear in the POS. History is kept."))) {
            UiKit.guard(view, () -> {
                dao.setActive(v.productId, activate);
                reload();
            });
        }
    }

    private void delete() {
        Variant v = view.model.selected(view.table);
        if (v == null) {
            UiKit.warn(view, "Select a product first.");
            return;
        }
        if (UiKit.confirm(view, "Permanently delete \"" + v.productName + "\" and all its variants, stock history and batches?\n"
                + "This cannot be undone.")) {
            UiKit.guard(view, () -> {
                dao.delete(v.productId);
                reload();
                UiKit.info(view, "Product deleted.");
            });
        }
    }

    private void labels() {
        List<Variant> sel = new ArrayList<>();
        for (int r : view.table.getSelectedRows()) {
            sel.add(view.model.row(view.table.convertRowIndexToModel(r)));
        }
        new LabelPrintDialog(UiKit.window(view), sel).setVisible(true);
    }

    private void exportCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("products_" + java.time.LocalDate.now() + ".csv"));
        if (fc.showSaveDialog(view) == JFileChooser.APPROVE_OPTION) {
            UiKit.guard(view, () -> {
                int n = new ImportExportService().exportProducts(fc.getSelectedFile().toPath());
                UiKit.info(view, n + " product variant(s) exported to\n" + fc.getSelectedFile());
            });
        }
    }

    private void template() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("product_import_template.csv"));
        if (fc.showSaveDialog(view) == JFileChooser.APPROVE_OPTION) {
            UiKit.guard(view, () -> {
                new ImportExportService().writeTemplate(fc.getSelectedFile().toPath());
                UiKit.info(view, "Template saved. Fill it in Excel, save as CSV and use Import CSV.\n\n"
                        + "Columns: " + String.join(", ", ImportExportService.COLUMNS));
            });
        }
    }

    private void importCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        if (fc.showOpenDialog(view) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        UiKit.guard(view, () -> {
            ImportExportService.ImportResult r = new ImportExportService().importProducts(fc.getSelectedFile().toPath());
            StringBuilder sb = new StringBuilder("Import finished.\n\nNew variants: " + r.created + "\nUpdated variants: " + r.updated);
            if (!r.errors.isEmpty()) {
                sb.append("\n\nProblems (").append(r.errors.size()).append("):\n");
                r.errors.stream().limit(15).forEach(x -> sb.append("• ").append(x).append('\n'));
            }
            if (r.errors.isEmpty()) {
                UiKit.info(view, sb.toString());
            } else {
                UiKit.warn(view, sb.toString());
            }
            onShow();
        });
    }
}
