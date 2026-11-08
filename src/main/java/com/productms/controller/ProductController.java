package com.productms.controller;

import com.productms.dao.MasterDataDAO;
import com.productms.dao.ProductDAO;
import com.productms.db.Jdbc;
import com.productms.model.Brand;
import com.productms.model.BundleItem;
import com.productms.model.Category;
import com.productms.model.Product;
import com.productms.model.Variant;
import com.productms.service.ImportExportService;
import com.productms.util.AppException;
import com.productms.util.BarcodeUtil;
import com.productms.util.Money;
import com.productms.util.Session;
import com.productms.view.LabelPrintDialog;
import com.productms.view.ProductEditorDialog;
import com.productms.view.ProductsView;
import com.productms.view.RowTableModel;
import com.productms.view.TableDialog;
import com.productms.view.UiKit;
import com.productms.view.VariantPickerDialog;
import java.awt.BorderLayout;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Product list, filters & sorting, editor, cloning, duplicate warnings, bulk edit, scheduled
 * prices, history, soft delete / restore, labels and CSV import (with preview) / export.
 */
public class ProductController implements Module {

    private final ProductsView view = new ProductsView();
    private final ProductDAO dao = new ProductDAO();
    private final MasterDataDAO master = new MasterDataDAO();
    private final MainController main;
    private boolean loadingFilters;
    private List<String> detailImages = new ArrayList<>();
    private int imageIndex;

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
        view.cmbSort.addActionListener(e -> reload());
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
        view.btnClone.addActionListener(e -> cloneProduct());
        view.btnToggle.addActionListener(e -> toggleActive());
        view.btnDelete.addActionListener(e -> delete());
        view.btnRestore.addActionListener(e -> restore());
        view.btnBulk.addActionListener(e -> bulkEdit());
        view.btnSchedule.addActionListener(e -> schedule());
        view.btnHistory.addActionListener(e -> history());
        view.btnStockIn.addActionListener(e -> main.openStockIn(view.model.selected(view.table)));
        view.btnLabels.addActionListener(e -> labels());
        view.btnExport.addActionListener(e -> exportCsv());
        view.btnImport.addActionListener(e -> importCsv());
        view.btnTemplate.addActionListener(e -> template());
        view.btnPrevImage.addActionListener(e -> moveImage(-1));
        view.btnNextImage.addActionListener(e -> moveImage(1));
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
            f.status = new String[]{"ALL", "ACTIVE", "INACTIVE", "DELETED"}[view.cmbStatus.getSelectedIndex()];
            f.stock = new String[]{"ALL", "IN", "LOW", "OUT", "DAMAGED"}[view.cmbStock.getSelectedIndex()];
            f.sort = new String[]{"NAME", "PRICE_ASC", "PRICE_DESC", "STOCK_ASC", "STOCK_DESC", "NEWEST", "BEST_SELLING",
                "LOCATION"}[view.cmbSort.getSelectedIndex()];
            Variant sel = view.model.selected(view.table);
            List<Variant> rows = dao.search(f);
            view.model.setRows(rows);
            BigDecimal value = BigDecimal.ZERO;
            for (Variant v : rows) {
                value = value.add(v.stockQty.multiply(v.purchasePrice));
            }
            boolean deleted = "DELETED".equals(f.status);
            view.btnRestore.setVisible(deleted);
            view.btnDelete.setVisible(!deleted);
            view.lblCount.setText(rows.size() + " item(s)  ·  stock value at cost " + Money.cur(value)
                    + (f.text != null && !f.text.isBlank() && !rows.isEmpty() && rows.stream().noneMatch(v -> containsText(v, f.text))
                    ? "  ·  showing close matches (fuzzy search)" : ""));
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

    private static boolean containsText(Variant v, String text) {
        String t = text.trim().toLowerCase();
        return (v.productName + " " + v.sku + " " + v.label() + " " + v.brandName + " " + v.tags + " " + v.barcode).toLowerCase().contains(t);
    }

    private void showDetail() {
        Variant v = view.model.selected(view.table);
        view.btnToggle.setText(v != null && !v.productActive ? "Activate" : "Deactivate");
        if (v == null) {
            view.lblName.setText(" ");
            view.txtDetails.setText("Select a product to see its details.");
            detailImages = new ArrayList<>();
            showImage();
            view.imgBarcode.setIcon(null);
            view.imgQr.setIcon(null);
            return;
        }
        view.lblName.setText("<html>" + v.displayName() + "</html>");
        BigDecimal margin = v.retailPrice.signum() == 0 ? BigDecimal.ZERO
                : v.retailPrice.subtract(v.purchasePrice).multiply(Money.HUNDRED).divide(v.retailPrice, 1, java.math.RoundingMode.HALF_UP);
        StringBuilder sb = new StringBuilder();
        String code = v.barcode == null ? v.sku : v.barcode;
        sb.append("SKU: ").append(v.sku).append("\nBarcode: ").append(v.barcode == null ? "-" : v.barcode + " (" + BarcodeUtil.formatName(v.barcode) + ")");
        if (v.barcodesText != null) {
            sb.append("\nExtra barcodes: ").append(v.barcodesText);
        }
        if (v.plu != null) {
            sb.append("\nScale PLU: ").append(v.plu);
        }
        sb.append("\nType: ").append(v.isBundle() ? "Bundle / Combo (" + ("SUM_DISCOUNT".equals(v.bundlePricing)
                ? "sum minus " + Money.qty(v.bundleDiscountPercent) + "%" : "fixed price") + ")" : "Standard")
                .append(v.trackBatch ? " · batch & expiry" : "").append(v.trackSerial ? " · serial / IMEI" : "")
                .append("\nCategory: ").append(v.categoryName == null ? "-" : v.categoryName)
                .append("\nBrand: ").append(v.brandName == null ? "-" : v.brandName);
        if (v.location != null) {
            sb.append("\nShelf: ").append(v.location);
        }
        if (v.tags != null) {
            sb.append("\nTags: ").append(v.tags);
        }
        sb.append("\n\nCost (weighted avg): ").append(Money.cur(v.purchasePrice))
                .append("\nRetail: ").append(Money.cur(v.retailPrice)).append("   (margin ").append(margin).append("%)")
                .append("\nWholesale: ").append(Money.cur(v.wholesalePrice));
        if (v.wholesaleMinQty.signum() > 0) {
            sb.append("  from ").append(Money.qty(v.wholesaleMinQty)).append(" ").append(v.unitShort);
        }
        if (v.mrp.signum() > 0) {
            sb.append("\nMRP: ").append(Money.cur(v.mrp));
        }
        if (v.minPrice.signum() > 0) {
            sb.append("\nMinimum price: ").append(Money.cur(v.minPrice));
        }
        if (v.tiersText != null) {
            sb.append("\nQty prices: ").append(v.tiersText);
        }
        if (v.packsText != null) {
            sb.append("\nPacks: ").append(v.packsText);
        }
        sb.append("\nVAT: ").append(Money.qty(v.vatPercent)).append("%");
        if (!"NONE".equals(v.discountType)) {
            sb.append("\nDiscount: ").append("PERCENT".equals(v.discountType) ? Money.qty(v.discountValue) + "%"
                    : Money.cur(v.discountValue) + " per unit")
                    .append(v.discountStart == null && v.discountEnd == null ? "" : " (" + (v.discountStart == null ? "" : v.discountStart.format(UiKit.DATE))
                            + " → " + (v.discountEnd == null ? "open" : v.discountEnd.format(UiKit.DATE)) + ")")
                    .append(v.discountActive() ? " - ACTIVE" : " - not active today");
        }
        if (v.warrantyMonths > 0) {
            sb.append("\nWarranty: ").append(v.warrantyMonths).append(" months");
        }
        if (v.isBundle()) {
            sb.append("\n\nAvailable combos: ").append(Money.qty(v.available));
        } else {
            sb.append("\n\nSellable stock: ").append(Money.qty(v.stockQty)).append(" ").append(v.unitShort)
                    .append("\nReserved (held carts): ").append(Money.qty(v.reservedQty))
                    .append("\nAvailable to sell: ").append(Money.qty(v.available))
                    .append("\nDamaged (quarantined): ").append(Money.qty(v.damagedQty))
                    .append("\nPhysical on premises: ").append(Money.qty(v.physicalQty()))
                    .append("\nReorder at ").append(Money.qty(v.reorderLevel)).append(", usual order ").append(Money.qty(v.reorderQty));
        }
        if (v.extraAttributes != null) {
            sb.append("\n\n").append(v.extraAttributes);
        }
        if (v.description != null) {
            sb.append("\n\n").append(v.description);
        }
        view.txtDetails.setText(sb.toString());
        view.txtDetails.setCaretPosition(0);
        UiKit.guard(view, () -> {
            detailImages = dao.images(v.productId);
            if (detailImages.isEmpty() && v.imagePath != null) {
                detailImages.add(v.imagePath);
            }
        });
        imageIndex = 0;
        showImage();
        try {
            view.imgBarcode.setIcon(new ImageIcon(BarcodeUtil.barcodeImage(code, 150, 60)));
            view.imgQr.setIcon(new ImageIcon(BarcodeUtil.qrImage(v.sku + "|" + code + "|" + v.displayName() + "|"
                    + Money.fmt(v.retailPrice), 90)));
        } catch (AppException e) {
            view.imgBarcode.setIcon(null);
            view.imgQr.setIcon(null);
        }
    }

    private void moveImage(int d) {
        if (!detailImages.isEmpty()) {
            imageIndex = (imageIndex + d + detailImages.size()) % detailImages.size();
            showImage();
        }
    }

    private void showImage() {
        view.btnPrevImage.setEnabled(detailImages.size() > 1);
        view.btnNextImage.setEnabled(detailImages.size() > 1);
        if (detailImages.isEmpty() || !new File(detailImages.get(imageIndex)).exists()) {
            view.imgProduct.setIcon(null);
            view.imgProduct.setText(detailImages.isEmpty() ? "No image" : "Image file missing");
            return;
        }
        ImageIcon icon = new ImageIcon(detailImages.get(imageIndex));
        double s = Math.min(250.0 / Math.max(1, icon.getIconWidth()), 165.0 / Math.max(1, icon.getIconHeight()));
        view.imgProduct.setText(null);
        view.imgProduct.setIcon(new ImageIcon(icon.getImage().getScaledInstance(
                (int) (icon.getIconWidth() * s), (int) (icon.getIconHeight() * s), Image.SCALE_SMOOTH)));
        view.imgProduct.setToolTipText("Image " + (imageIndex + 1) + " of " + detailImages.size());
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
        v.reorderQty = new BigDecimal("10");
        p.variants.add(v);
        return p;
    }

    private void wireEditor(ProductEditorDialog d, Product p) {
        d.cmbType.addActionListener(e -> updateBundleState(d));
        d.cmbBundlePricing.addActionListener(e -> updateBundleState(d));
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
                    + "If it has sales or stock history it will be deactivated instead of deleted.")) {
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
            fc.setMultiSelectionEnabled(true);
            fc.setFileFilter(new FileNameExtensionFilter("Images (png, jpg, gif)", "png", "jpg", "jpeg", "gif"));
            if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION) {
                for (File f : fc.getSelectedFiles()) {
                    d.newImages.add(f);
                    d.imageModel.addElement(f.getAbsolutePath());
                }
                d.lstImages.setSelectedIndex(d.imageModel.size() - 1);
            }
        });
        d.btnRemoveImage.addActionListener(e -> {
            int i = d.lstImages.getSelectedIndex();
            if (i >= 0) {
                d.imageModel.remove(i);
                d.showImage(null);
            }
        });
        d.btnPrimary.addActionListener(e -> {
            int i = d.lstImages.getSelectedIndex();
            if (i > 0) {
                String s = d.imageModel.remove(i);
                d.imageModel.add(0, s);
                d.lstImages.setSelectedIndex(0);
            }
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
            b.unitPrice = c.retailPrice;
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
        d.btnOptional.addActionListener(e -> {
            BundleItem b = d.bundleModel.selected(d.tblBundle);
            if (b != null) {
                b.optional = !b.optional;
                d.bundleModel.fireTableDataChanged();
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
        to.mrp = from.mrp;
        to.minPrice = from.minPrice;
        to.reorderLevel = from.reorderLevel;
        to.reorderQty = from.reorderQty;
        to.tiersText = from.tiersText;
        to.location = from.location;
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
        d.txtBundleDiscount.setEnabled(d.cmbBundlePricing.getSelectedIndex() == 1);
        if (bundle) {
            d.chkBatch.setSelected(false);
            d.chkSerial.setSelected(false);
            UiKit.guard(d, () -> {
                BigDecimal sum = BigDecimal.ZERO;
                for (BundleItem b : d.bundleModel.rows()) {
                    Variant c = dao.findVariant(b.componentVariantId);
                    if (c != null) {
                        b.unitPrice = c.retailPrice;
                        sum = sum.add(c.retailPrice.multiply(b.quantity));
                    }
                }
                BigDecimal price = d.cmbBundlePricing.getSelectedIndex() == 1
                        ? sum.subtract(Money.pct(sum, Money.parseOr(d.txtBundleDiscount.getText(), BigDecimal.ZERO)))
                        : (d.variantModel.rows.isEmpty() ? BigDecimal.ZERO : d.variantModel.rows.get(0).retailPrice);
                d.lblBundleInfo.setText("Bought separately: " + Money.cur(sum) + "  ·  combo price: " + Money.cur(Money.r2(price))
                        + (sum.compareTo(price) > 0 ? "  ·  customer saves " + Money.cur(Money.r2(sum.subtract(price))) : ""));
            });
        }
    }

    private void save(ProductEditorDialog d, Product p) throws Exception {
        d.readInto(p);
        if (p.isBundle() && p.variants.size() > 1) {
            throw new AppException("A combo has one selling row. Remove the extra variants on the Variants tab.");
        }
        if (p.isBundle() && "SUM_DISCOUNT".equals(p.bundlePricing)) {
            // keep the stored retail price in step with the computed combo price (used for labels & reports)
            BigDecimal sum = BigDecimal.ZERO;
            for (BundleItem b : p.bundleItems) {
                sum = sum.add(b.unitPrice.multiply(b.quantity));
            }
            p.variants.get(0).retailPrice = Money.r2(sum.subtract(Money.pct(sum, p.bundleDiscountPercent)));
        }
        for (Variant v : p.variants) {
            if (v.retailPrice.signum() > 0 && v.purchasePrice.compareTo(v.retailPrice) > 0
                    && !UiKit.confirm(d, "Retail price of " + (v.label().isEmpty() ? p.name : v.label()) + " is below its cost. Save anyway?")) {
                return;
            }
        }
        if (p.productId == 0) {
            List<String> similar = dao.findSimilar(p);
            if (!similar.isEmpty() && !UiKit.confirm(d, "Possible duplicate product:\n• " + String.join("\n• ", similar)
                    + "\n\nSave this as a new product anyway?")) {
                return;
            }
        } else if (pricesChanged(p)) {
            String reason = UiKit.input(d, "Prices changed. Reason for the price history (optional):", "Price update");
            if (reason == null) {
                return;
            }
            p.priceChangeReason = reason;
        }
        // copy newly chosen images into product-images and keep the list order
        List<String> images = new ArrayList<>();
        Path dir = Paths.get("product-images");
        for (int i = 0; i < d.imageModel.size(); i++) {
            String path = d.imageModel.get(i);
            File f = new File(path);
            if (d.newImages.contains(f)) {
                Files.createDirectories(dir);
                String name = f.getName();
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : ".png";
                Path target = dir.resolve("product_" + System.currentTimeMillis() + "_" + i + ext);
                Files.copy(f.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                images.add(target.toString().replace('\\', '/'));
            } else {
                images.add(path);
            }
        }
        p.images = images;
        dao.save(p);
        d.dispose();
        reload();
        main.refreshAlerts();
    }

    private boolean pricesChanged(Product p) throws Exception {
        for (Variant v : p.variants) {
            if (v.variantId == 0) {
                continue;
            }
            Variant old = dao.findVariant(v.variantId);
            if (old != null && (old.retailPrice.compareTo(v.retailPrice) != 0 || old.wholesalePrice.compareTo(v.wholesalePrice) != 0
                    || old.purchasePrice.compareTo(v.purchasePrice) != 0)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ actions

    private Variant requireSelection() {
        Variant v = view.model.selected(view.table);
        if (v == null) {
            UiKit.warn(view, "Select a product first.");
        }
        return v;
    }

    private List<Variant> selection() {
        List<Variant> sel = new ArrayList<>();
        for (int r : view.table.getSelectedRows()) {
            sel.add(view.model.row(view.table.convertRowIndexToModel(r)));
        }
        return sel;
    }

    private void cloneProduct() {
        Variant v = requireSelection();
        if (v == null) {
            return;
        }
        UiKit.guard(view, () -> {
            int id = dao.cloneProduct(v.productId);
            reload();
            UiKit.info(view, "Copied as an INACTIVE product (no SKU, barcode or stock carried over).\nReview it, then activate it.");
            openEditor(id);
        });
    }

    private void toggleActive() {
        Variant v = requireSelection();
        if (v == null) {
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
        Variant v = requireSelection();
        if (v == null) {
            return;
        }
        if (UiKit.confirm(view, "Delete \"" + v.productName + "\"?\n\nProducts with stock or sales history are only hidden "
                + "(soft delete) so reports stay correct; you can restore them from Status → Deleted.\n"
                + "Products that were never used are removed permanently.")) {
            UiKit.guard(view, () -> {
                boolean soft = dao.delete(v.productId);
                reload();
                UiKit.info(view, soft ? "Product hidden (soft delete). History kept; restore it from the Deleted filter."
                        : "Product deleted permanently.");
            });
        }
    }

    private void restore() {
        Variant v = requireSelection();
        if (v != null && UiKit.confirm(view, "Restore \"" + v.productName + "\"? It comes back as INACTIVE.")) {
            UiKit.guard(view, () -> {
                dao.restore(v.productId);
                reload();
            });
        }
    }

    private void bulkEdit() {
        List<Variant> sel = selection();
        if (sel.isEmpty()) {
            UiKit.warn(view, "Select one or more rows (Ctrl / Shift + click).");
            return;
        }
        UiKit.guard(view, () -> {
            JTextField retailPct = UiKit.field("e.g. 5 or -10");
            JTextField retailAmt = UiKit.field("e.g. 20 or -15");
            JTextField wholesalePct = UiKit.field("e.g. 5");
            JTextField vat = UiKit.field("leave blank = unchanged");
            JComboBox<Object> cat = new JComboBox<>();
            cat.addItem("(unchanged)");
            master.categories(true).forEach(cat::addItem);
            JComboBox<Object> brand = new JComboBox<>();
            brand.addItem("(unchanged)");
            master.brands(true).forEach(brand::addItem);
            JTextField reorder = UiKit.field("leave blank = unchanged");
            JTextField location = UiKit.field("leave blank = unchanged");
            JComboBox<String> active = new JComboBox<>(new String[]{"(unchanged)", "Activate", "Deactivate"});
            JTextField reason = UiKit.field("Reason for price history");
            UiKit.Form f = new UiKit.Form();
            f.full(UiKit.mutedLabel(sel.size() + " variant(s) selected. Empty fields are not changed."));
            f.add("Retail price change %", retailPct);
            f.add("or retail change amount", retailAmt);
            f.add("Wholesale change %", wholesalePct);
            f.add("VAT %", vat);
            f.add("Category", cat);
            f.add("Brand", brand);
            f.add("Reorder level", reorder);
            f.add("Shelf location", location);
            f.add("Status", active);
            f.add("Reason", reason);
            f.panel.setPreferredSize(new java.awt.Dimension(460, f.panel.getPreferredSize().height));
            if (JOptionPane.showConfirmDialog(view, f.panel, "Bulk edit", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                    != JOptionPane.OK_OPTION) {
                return;
            }
            ProductDAO.BulkChange c = new ProductDAO.BulkChange();
            c.retailPercent = Money.parse(retailPct.getText());
            c.retailAmount = Money.parse(retailAmt.getText());
            c.wholesalePercent = Money.parse(wholesalePct.getText());
            c.vatPercent = Money.parse(vat.getText());
            c.categoryId = cat.getSelectedItem() instanceof Category ? ((Category) cat.getSelectedItem()).categoryId : null;
            c.brandId = brand.getSelectedItem() instanceof Brand ? ((Brand) brand.getSelectedItem()).brandId : null;
            c.reorderLevel = Money.parse(reorder.getText());
            c.location = location.getText().isBlank() ? null : location.getText().trim();
            c.active = active.getSelectedIndex() == 0 ? null : active.getSelectedIndex() == 1;
            c.reason = reason.getText();
            List<Integer> ids = new ArrayList<>();
            sel.forEach(v -> ids.add(v.variantId));
            int n = dao.bulkUpdate(ids, c);
            reload();
            UiKit.info(view, n + " variant(s) updated. Price changes are in each product's History.");
        });
    }

    private void schedule() {
        List<Variant> sel = selection();
        UiKit.guard(view, () -> {
            JTextField newRetail = UiKit.field(sel.size() == 1 ? "new retail price" : "new retail price (same for all)");
            JTextField pct = UiKit.field("or % change, e.g. 10 or -5");
            JTextField newWholesale = UiKit.field("new wholesale price (optional)");
            JTextField date = UiKit.field("dd-mm-yyyy");
            date.setText(java.time.LocalDate.now().plusDays(1).format(UiKit.DATE));
            JTextField time = UiKit.field("HH:mm");
            time.setText("00:00");
            JTextField note = UiKit.field("e.g. Eid offer, supplier price increase");
            UiKit.Form f = new UiKit.Form();
            f.full(UiKit.mutedLabel(sel.isEmpty() ? "No rows selected: this only shows the schedule list."
                    : sel.size() + " variant(s) selected."));
            f.add("New retail", newRetail);
            f.add("or retail %", pct);
            f.add("New wholesale", newWholesale);
            f.add("Effective date", date);
            f.add("Time", time);
            f.add("Note", note);
            Object[] options = sel.isEmpty() ? new Object[]{"View schedule", "Close"} : new Object[]{"Schedule", "View schedule", "Cancel"};
            int a = JOptionPane.showOptionDialog(view, f.panel, "Scheduled price change", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (sel.isEmpty() ? a == 0 : a == 1) {
                showSchedules();
                return;
            }
            if (sel.isEmpty() || a != 0) {
                return;
            }
            java.time.LocalDate d = UiKit.parseDate(date.getText(), "Effective date");
            if (d == null) {
                throw new AppException("Enter the date the new price starts.");
            }
            java.time.LocalTime t;
            try {
                t = java.time.LocalTime.parse(time.getText().trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw new AppException("Time must be HH:mm, e.g. 08:00.");
            }
            List<Integer> ids = new ArrayList<>();
            sel.forEach(v -> ids.add(v.variantId));
            dao.schedulePrice(ids, Money.parse(newRetail.getText()), Money.parse(pct.getText()), Money.parse(newWholesale.getText()),
                    LocalDateTime.of(d, t), note.getText().isBlank() ? null : note.getText().trim());
            UiKit.info(view, "Scheduled. The price changes automatically at " + d.format(UiKit.DATE) + " " + t
                    + " (checked every minute while the app runs, and at start-up).");
        });
    }

    private void showSchedules() throws Exception {
        Jdbc.TableData data = dao.scheduledPrices();
        com.productms.view.DataTableModel m = new com.productms.view.DataTableModel();
        m.setData(data);
        JTable t = new JTable(m);
        UiKit.styleTable(t);
        JDialog dlg = new JDialog(UiKit.window(view), "Scheduled price changes", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        javax.swing.JButton cancel = UiKit.danger("Cancel selected");
        cancel.addActionListener(e -> UiKit.guard(dlg, () -> {
            int r = t.getSelectedRow();
            if (r >= 0) {
                dao.cancelSchedule(((Number) m.getValueAt(t.convertRowIndexToModel(r), 0)).intValue());
                m.setData(dao.scheduledPrices());
            }
        }));
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 14, 14, 14));
        p.add(UiKit.tableCard(t));
        p.add(UiKit.rowRight(cancel), BorderLayout.SOUTH);
        dlg.setContentPane(p);
        dlg.setSize(1000, 480);
        dlg.setLocationRelativeTo(view);
        dlg.setVisible(true);
    }

    private void history() {
        Variant v = requireSelection();
        if (v == null) {
            return;
        }
        UiKit.guard(view, () -> {
            Map<String, Jdbc.TableData> tabs = new LinkedHashMap<>();
            tabs.put("Price history", dao.priceHistory(v.variantId));
            tabs.put("Cost history", dao.costHistory(v.variantId));
            tabs.put("Supplier prices", dao.supplierPrices(v.variantId));
            tabs.put("Change log", dao.productAudit(v.productId));
            TableDialog.show(view, "History - " + v.displayName(), "SKU " + v.sku + ". Weighted average cost is the product's cost; "
                    + "batch-tracked sales use each batch's own cost.", tabs);
        });
    }

    private void labels() {
        new LabelPrintDialog(UiKit.window(view), selection()).setVisible(true);
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

    /** Import with a preview: validate everything first, then apply in one transaction. */
    private void importCsv() {
        if (!Session.isManager()) {
            UiKit.warn(view, "Only managers can import products.");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        if (fc.showOpenDialog(view) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        UiKit.guard(view, () -> {
            ImportExportService svc = new ImportExportService();
            ImportExportService.Preview pv = svc.preview(fc.getSelectedFile().toPath());
            RowTableModel<ImportExportService.PreviewRow> m = new RowTableModel<ImportExportService.PreviewRow>()
                    .col("Line", Integer.class, r -> r.line)
                    .col("Action", String.class, r -> r.action)
                    .col("SKU", String.class, r -> r.sku)
                    .col("Product", String.class, r -> r.product)
                    .col("Variant", String.class, r -> r.variant)
                    .col("Message", String.class, r -> r.message);
            m.setRows(pv.rows);
            JTable t = new JTable(m);
            UiKit.styleTable(t);
            t.getColumnModel().getColumn(5).setPreferredWidth(360);
            JCheckBox allOrNothing = new JCheckBox("All or nothing: cancel the whole import if any row has an error", pv.errors > 0);
            JDialog dlg = new JDialog(UiKit.window(view), "Import preview - nothing is saved yet", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
            boolean[] go = {false};
            javax.swing.JButton apply = UiKit.success("Import");
            javax.swing.JButton cancel = UiKit.plain("Cancel");
            apply.addActionListener(e -> {
                go[0] = true;
                dlg.dispose();
            });
            cancel.addActionListener(e -> dlg.dispose());
            JPanel p = new JPanel(new BorderLayout(0, 10));
            p.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 14, 14, 14));
            p.add(UiKit.title(pv.creates + " to create  ·  " + pv.updates + " to update  ·  " + pv.errors + " with errors", 15f),
                    BorderLayout.NORTH);
            p.add(UiKit.tableCard(t));
            JPanel south = new JPanel(new BorderLayout());
            south.add(allOrNothing, BorderLayout.WEST);
            south.add(UiKit.rowRight(cancel, apply), BorderLayout.EAST);
            p.add(south, BorderLayout.SOUTH);
            dlg.setContentPane(p);
            dlg.setSize(1000, 560);
            dlg.setLocationRelativeTo(view);
            dlg.setVisible(true);
            if (!go[0]) {
                return;
            }
            ImportExportService.ImportResult r = svc.apply(pv, allOrNothing.isSelected());
            UiKit.info(view, "Import finished (one transaction).\n\nNew variants: " + r.created + "\nUpdated variants: " + r.updated
                    + (r.errors.isEmpty() ? "" : "\nSkipped rows with errors: " + r.errors.size()));
            onShow();
        });
    }
}
