package com.productms.view;

import com.productms.model.Brand;
import com.productms.model.BundleItem;
import com.productms.model.Category;
import com.productms.model.Product;
import com.productms.model.Unit;
import com.productms.model.Variant;
import com.productms.util.AppException;
import com.productms.util.Money;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Window;
import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;

/** Create / edit a product with its variants, pricing rules, packs, bundle components and images. */
public class ProductEditorDialog extends JDialog {

    public final JTextField txtName = UiKit.field("e.g. Cotton Crew T-Shirt");
    public final JComboBox<Object> cmbCategory = new JComboBox<>();
    public final JComboBox<Object> cmbBrand = new JComboBox<>();
    public final JComboBox<Unit> cmbUnit = new JComboBox<>();
    public final JComboBox<String> cmbType = new JComboBox<>(new String[]{"Standard product", "Bundle / Combo"});
    public final JCheckBox chkBatch = new JCheckBox("Track batch / lot numbers and expiry dates");
    public final JCheckBox chkSerial = new JCheckBox("Track serial numbers / IMEI (one per unit)");
    public final JTextField txtVat = UiKit.field("0");
    public final JComboBox<String> cmbDiscount = new JComboBox<>(new String[]{"No discount", "Percent (%)", "Fixed amount per unit"});
    public final JTextField txtDiscount = UiKit.field("0");
    public final JTextField txtDiscStart = UiKit.field("from dd-mm-yyyy (blank = now)");
    public final JTextField txtDiscEnd = UiKit.field("until dd-mm-yyyy (blank = open)");
    public final JTextField txtWarranty = UiKit.field("0");
    public final JTextField txtTags = UiKit.field("comma separated, e.g. summer, offer, organic");
    public final JTextArea txtAttributes = new JTextArea(3, 20);
    public final JCheckBox chkActive = new JCheckBox("Active (available for sale)", true);
    public final JTextArea txtDescription = new JTextArea(3, 20);

    public final DefaultListModel<String> imageModel = new DefaultListModel<>();
    public final JList<String> lstImages = new JList<>(imageModel);
    public final JLabel imgPreview = new JLabel("No image", SwingConstants.CENTER);
    public final JButton btnImage = UiKit.plain("Add image...");
    public final JButton btnRemoveImage = UiKit.plain("Remove");
    public final JButton btnPrimary = UiKit.plain("Make main");

    public final VariantModel variantModel = new VariantModel();
    public final JTable tblVariants = new JTable(variantModel);
    public final JButton btnAddVariant = UiKit.plain("+ Add variant");
    public final JButton btnRemoveVariant = UiKit.plain("Remove variant");
    public final JButton btnCombos = UiKit.plain("Generate size × colour...");
    public final JButton btnCopyPrices = UiKit.plain("Copy first row prices to all");

    public final RowTableModel<BundleItem> bundleModel = new RowTableModel<BundleItem>()
            .col("Component", String.class, b -> b.componentName)
            .col("SKU", String.class, b -> b.componentSku)
            .col("Qty per combo", BigDecimal.class, b -> b.quantity)
            .col("Optional", Boolean.class, b -> b.optional)
            .col("Value", BigDecimal.class, b -> b.unitPrice.multiply(b.quantity));
    public final JTable tblBundle = new JTable(bundleModel);
    public final JButton btnAddComponent = UiKit.plain("+ Add component");
    public final JButton btnRemoveComponent = UiKit.plain("Remove");
    public final JButton btnComponentQty = UiKit.plain("Change qty");
    public final JButton btnOptional = UiKit.plain("Toggle optional");
    public final JComboBox<String> cmbBundlePricing = new JComboBox<>(new String[]{
        "Fixed combo price (Variants tab)", "Sum of components minus a discount %"});
    public final JTextField txtBundleDiscount = UiKit.field("e.g. 10");
    public final JLabel lblBundleInfo = UiKit.mutedLabel(" ");

    public final JTabbedPane tabs = new JTabbedPane();
    public final JButton btnSave = UiKit.primary("Save product");
    public final JButton btnCancel = UiKit.plain("Cancel");

    /** Images newly chosen in this session (copied into product-images on save). */
    public final List<File> newImages = new ArrayList<>();

    public ProductEditorDialog(Window owner, boolean isNew) {
        super(owner, isNew ? "New product" : "Edit product", ModalityType.APPLICATION_MODAL);
        UiKit.styleTable(tblVariants);
        tblVariants.setAutoCreateRowSorter(false);
        tblVariants.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        tblVariants.putClientProperty("terminateEditOnFocusLost", true);
        tblVariants.setSurrendersFocusOnKeystroke(true);
        int[] widths = {55, 70, 65, 150, 130, 125, 75, 80, 80, 80, 75, 75, 150, 170, 150, 60, 70, 70, 80, 80, 50};
        for (int i = 0; i < widths.length; i++) {
            tblVariants.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        UiKit.styleTable(tblBundle);

        UiKit.Form f = new UiKit.Form();
        f.section("Basic information");
        f.add("Product name *", txtName);
        f.add("Category", cmbCategory);
        f.add("Brand", cmbBrand);
        f.add("Unit", cmbUnit);
        f.add("Product type", cmbType);
        f.add("Tags", txtTags);
        f.section("Pricing rules");
        f.add("VAT %", txtVat);
        f.add("Product discount", cmbDiscount);
        f.add("Discount value", txtDiscount);
        f.add("Discount starts", txtDiscStart);
        f.add("Discount ends", txtDiscEnd);
        f.section("Tracking & warranty");
        f.full(chkBatch);
        f.full(chkSerial);
        f.add("Warranty (months)", txtWarranty);
        f.full(chkActive);
        f.section("Custom attributes (one \"name: value\" per line)");
        txtAttributes.setLineWrap(true);
        f.full(new JScrollPane(txtAttributes));
        f.section("Description");
        txtDescription.setLineWrap(true);
        txtDescription.setWrapStyleWord(true);
        f.full(new JScrollPane(txtDescription));
        f.filler();

        JPanel imgBox = UiKit.card(new BorderLayout(0, 8));
        imgPreview.setPreferredSize(new Dimension(230, 200));
        imgPreview.setForeground(UiKit.muted());
        imgBox.add(UiKit.title("Images (first = main)", 14f), BorderLayout.NORTH);
        JPanel imgCenter = new JPanel(new BorderLayout(0, 6));
        imgCenter.setOpaque(false);
        imgCenter.add(imgPreview, BorderLayout.NORTH);
        lstImages.setVisibleRowCount(4);
        imgCenter.add(new JScrollPane(lstImages));
        imgBox.add(imgCenter);
        imgBox.add(UiKit.row(btnImage, btnPrimary, btnRemoveImage), BorderLayout.SOUTH);
        lstImages.addListSelectionListener(e -> {
            String path = lstImages.getSelectedValue();
            showImage(path == null ? null : new File(path));
        });

        JPanel general = new JPanel(new BorderLayout(18, 0));
        general.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        general.add(UiKit.scroll(f.panel));
        JPanel side = new JPanel(new BorderLayout());
        side.add(imgBox, BorderLayout.NORTH);
        general.add(side, BorderLayout.EAST);

        JPanel variants = new JPanel(new BorderLayout(0, 10));
        variants.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        variants.add(UiKit.mutedLabel("<html><div style='width:1050px'>Each row is a sellable item. Blank SKU / barcode are generated. "
                + "<b>Qty price tiers</b>: <i>10=1100; 50=1050</i> &nbsp; <b>Packs</b>: <i>BOX=12@1300#barcode</i> (1 BOX = 12 units, "
                + "pack price and barcode optional) &nbsp; <b>Extra barcodes</b>: old / supplier codes, comma separated &nbsp; "
                + "<b>Scale PLU</b>: 5 digits for weighed-item labels. Opening stock only for new untracked rows.</div></html>"),
                BorderLayout.NORTH);
        JScrollPane vs = new JScrollPane(tblVariants);
        variants.add(vs);
        variants.add(UiKit.row(btnAddVariant, btnRemoveVariant, btnCombos, btnCopyPrices), BorderLayout.SOUTH);

        JPanel bundle = new JPanel(new BorderLayout(0, 10));
        bundle.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        UiKit.Form bf = new UiKit.Form();
        bf.full(UiKit.mutedLabel("<html>A combo is sold as one item; selling it deducts each included component. "
                + "Optional components can be left out at the POS (the price drops by their value).</html>"));
        bf.add("Combo pricing", cmbBundlePricing);
        bf.add("Combo discount %", txtBundleDiscount);
        bundle.add(bf.panel, BorderLayout.NORTH);
        bundle.add(UiKit.tableCard(tblBundle));
        JPanel bundleSouth = new JPanel(new BorderLayout());
        bundleSouth.add(UiKit.row(btnAddComponent, btnComponentQty, btnOptional, btnRemoveComponent), BorderLayout.WEST);
        bundleSouth.add(lblBundleInfo, BorderLayout.EAST);
        bundle.add(bundleSouth, BorderLayout.SOUTH);

        tabs.addTab("General", general);
        tabs.addTab("Variants, SKU, Prices & Packs", variants);
        tabs.addTab("Bundle components", bundle);

        JPanel root = new JPanel(new BorderLayout());
        root.add(tabs);
        JPanel south = UiKit.rowRight(btnCancel, btnSave);
        south.setBorder(BorderFactory.createEmptyBorder(8, 14, 12, 14));
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(1180, 740);
        setLocationRelativeTo(owner);
        UiKit.bind(root, "ctrl S", btnSave::doClick);
        UiKit.bind(root, "ESCAPE", btnCancel::doClick);
    }

    public void fillLists(List<Category> cats, List<Brand> brands, List<Unit> units) {
        cmbCategory.addItem("(none)");
        cats.forEach(cmbCategory::addItem);
        cmbBrand.addItem("(none)");
        brands.forEach(cmbBrand::addItem);
        units.forEach(cmbUnit::addItem);
    }

    public void showImage(File file) {
        if (file == null || !file.exists()) {
            imgPreview.setIcon(null);
            imgPreview.setText(file == null ? "No image" : "File not found");
            return;
        }
        ImageIcon icon = new ImageIcon(file.getAbsolutePath());
        int w = icon.getIconWidth();
        int h = icon.getIconHeight();
        if (w <= 0) {
            imgPreview.setIcon(null);
            imgPreview.setText("Cannot read image");
            return;
        }
        double s = Math.min(220.0 / w, 190.0 / h);
        imgPreview.setText(null);
        imgPreview.setIcon(new ImageIcon(icon.getImage().getScaledInstance((int) (w * s), (int) (h * s), Image.SCALE_SMOOTH)));
    }

    /** Loads a product into the form. */
    public void setProduct(Product p) {
        txtName.setText(p.name);
        selectById(cmbCategory, p.categoryId, true);
        selectById(cmbBrand, p.brandId, false);
        for (int i = 0; i < cmbUnit.getItemCount(); i++) {
            if (cmbUnit.getItemAt(i).unitId == p.unitId) {
                cmbUnit.setSelectedIndex(i);
            }
        }
        cmbType.setSelectedIndex(p.isBundle() ? 1 : 0);
        chkBatch.setSelected(p.trackBatch);
        chkSerial.setSelected(p.trackSerial);
        txtVat.setText(Money.qty(p.vatPercent));
        cmbDiscount.setSelectedIndex("PERCENT".equals(p.discountType) ? 1 : "FIXED".equals(p.discountType) ? 2 : 0);
        txtDiscount.setText(Money.qty(p.discountValue));
        txtDiscStart.setText(p.discountStart == null ? "" : p.discountStart.format(UiKit.DATE));
        txtDiscEnd.setText(p.discountEnd == null ? "" : p.discountEnd.format(UiKit.DATE));
        txtWarranty.setText(String.valueOf(p.warrantyMonths));
        txtTags.setText(p.tags);
        txtAttributes.setText(p.attributes);
        chkActive.setSelected(p.active);
        txtDescription.setText(p.description);
        cmbBundlePricing.setSelectedIndex("SUM_DISCOUNT".equals(p.bundlePricing) ? 1 : 0);
        txtBundleDiscount.setText(Money.qty(p.bundleDiscountPercent));
        imageModel.clear();
        p.images.forEach(imageModel::addElement);
        if (!imageModel.isEmpty()) {
            lstImages.setSelectedIndex(0);
        } else {
            showImage(null);
        }
        variantModel.setRows(p.variants);
        bundleModel.setRows(p.bundleItems);
    }

    private static void selectById(JComboBox<Object> cmb, Integer id, boolean category) {
        cmb.setSelectedIndex(0);
        if (id == null) {
            return;
        }
        for (int i = 1; i < cmb.getItemCount(); i++) {
            Object o = cmb.getItemAt(i);
            int oid = category ? ((Category) o).categoryId : ((Brand) o).brandId;
            if (oid == id) {
                cmb.setSelectedIndex(i);
            }
        }
    }

    /** Copies the form into the product (validation of numbers happens here). */
    public void readInto(Product p) {
        if (tblVariants.isEditing()) {
            tblVariants.getCellEditor().stopCellEditing();
        }
        p.name = txtName.getText().trim();
        Object cat = cmbCategory.getSelectedItem();
        p.categoryId = cat instanceof Category ? ((Category) cat).categoryId : null;
        Object br = cmbBrand.getSelectedItem();
        p.brandId = br instanceof Brand ? ((Brand) br).brandId : null;
        Unit u = (Unit) cmbUnit.getSelectedItem();
        if (u == null) {
            throw new AppException("Create a unit first (Catalog Setup → Units).");
        }
        p.unitId = u.unitId;
        p.productType = cmbType.getSelectedIndex() == 1 ? "BUNDLE" : "STANDARD";
        p.trackBatch = chkBatch.isSelected();
        p.trackSerial = chkSerial.isSelected();
        p.vatPercent = Money.parseOr(txtVat.getText(), BigDecimal.ZERO);
        p.discountType = new String[]{"NONE", "PERCENT", "FIXED"}[cmbDiscount.getSelectedIndex()];
        p.discountValue = "NONE".equals(p.discountType) ? BigDecimal.ZERO : Money.parseOr(txtDiscount.getText(), BigDecimal.ZERO);
        p.discountStart = UiKit.parseDate(txtDiscStart.getText(), "Discount start");
        p.discountEnd = UiKit.parseDate(txtDiscEnd.getText(), "Discount end");
        try {
            p.warrantyMonths = txtWarranty.getText().isBlank() ? 0 : Integer.parseInt(txtWarranty.getText().trim());
        } catch (NumberFormatException e) {
            throw new AppException("Warranty must be a whole number of months.");
        }
        p.tags = txtTags.getText().isBlank() ? null : txtTags.getText().trim();
        p.attributes = txtAttributes.getText().isBlank() ? null : txtAttributes.getText().trim();
        p.active = chkActive.isSelected();
        p.description = txtDescription.getText().isBlank() ? null : txtDescription.getText().trim();
        p.bundlePricing = cmbBundlePricing.getSelectedIndex() == 1 ? "SUM_DISCOUNT" : "FIXED";
        p.bundleDiscountPercent = Money.parseOr(txtBundleDiscount.getText(), BigDecimal.ZERO);
        p.variants = new ArrayList<>(variantModel.rows);
        p.bundleItems = new ArrayList<>(bundleModel.rows());
    }

    /** Editable variant grid. */
    public static class VariantModel extends AbstractTableModel {
        private static final String[] COLS = {"Size", "Colour", "Weight", "Other attributes", "SKU", "Barcode", "Cost", "Retail *",
            "Wholesale", "Wholesale from", "MRP", "Min price", "Qty price tiers", "Packs", "Extra barcodes", "Scale PLU",
            "Reorder at", "Reorder qty", "Shelf", "Opening stock", "Active"};
        public List<Variant> rows = new ArrayList<>();

        public void setRows(List<Variant> list) {
            rows = new ArrayList<>(list);
            fireTableDataChanged();
        }

        public void add(Variant v) {
            rows.add(v);
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        public void remove(int i) {
            rows.remove(i);
            fireTableRowsDeleted(i, i);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLS[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            if (c == 20) {
                return Boolean.class;
            }
            return (c >= 6 && c <= 11) || c == 16 || c == 17 || c == 19 ? BigDecimal.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c != 19 || rows.get(r).variantId == 0;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Variant v = rows.get(r);
            switch (c) {
                case 0: return v.size;
                case 1: return v.colour;
                case 2: return v.weight;
                case 3: return v.extraAttributes;
                case 4: return v.sku;
                case 5: return v.barcode;
                case 6: return v.purchasePrice;
                case 7: return v.retailPrice;
                case 8: return v.wholesalePrice;
                case 9: return v.wholesaleMinQty;
                case 10: return v.mrp;
                case 11: return v.minPrice;
                case 12: return v.tiersText;
                case 13: return v.packsText;
                case 14: return v.barcodesText;
                case 15: return v.plu;
                case 16: return v.reorderLevel;
                case 17: return v.reorderQty;
                case 18: return v.location;
                case 19: return v.variantId == 0 ? v.openingStock : null;
                default: return v.active;
            }
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            Variant v = rows.get(r);
            String s = value == null ? "" : value.toString().trim();
            try {
                switch (c) {
                    case 0: v.size = s; break;
                    case 1: v.colour = s; break;
                    case 2: v.weight = s; break;
                    case 3: v.extraAttributes = s; break;
                    case 4: v.sku = s.toUpperCase(); break;
                    case 5: v.barcode = s; break;
                    case 6: v.purchasePrice = Money.parseOr(s, BigDecimal.ZERO); break;
                    case 7: v.retailPrice = Money.parseOr(s, BigDecimal.ZERO); break;
                    case 8: v.wholesalePrice = Money.parseOr(s, BigDecimal.ZERO); break;
                    case 9: v.wholesaleMinQty = Money.parseOr(s, BigDecimal.ZERO); break;
                    case 10: v.mrp = Money.parseOr(s, BigDecimal.ZERO); break;
                    case 11: v.minPrice = Money.parseOr(s, BigDecimal.ZERO); break;
                    case 12: v.tiersText = s; break;
                    case 13: v.packsText = s; break;
                    case 14: v.barcodesText = s; break;
                    case 15: v.plu = s; break;
                    case 16: v.reorderLevel = Money.parseOr(s, BigDecimal.ZERO); break;
                    case 17: v.reorderQty = Money.parseOr(s, BigDecimal.ZERO); break;
                    case 18: v.location = s; break;
                    case 19: v.openingStock = Money.parseOr(s, BigDecimal.ZERO); break;
                    default: v.active = Boolean.TRUE.equals(value); break;
                }
            } catch (IllegalArgumentException e) {
                java.awt.Toolkit.getDefaultToolkit().beep();
            }
            fireTableRowsUpdated(r, r);
        }
    }
}
