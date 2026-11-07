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
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;

/** Create / edit a product with its variants, bundle components and image. */
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
    public final JCheckBox chkActive = new JCheckBox("Active (available for sale)", true);
    public final JTextArea txtDescription = new JTextArea(4, 20);
    public final JLabel imgPreview = new JLabel("No image", SwingConstants.CENTER);
    public final JButton btnImage = UiKit.plain("Choose image...");
    public final JButton btnRemoveImage = UiKit.plain("Remove");

    public final VariantModel variantModel = new VariantModel();
    public final JTable tblVariants = new JTable(variantModel);
    public final JButton btnAddVariant = UiKit.plain("+ Add variant");
    public final JButton btnRemoveVariant = UiKit.plain("Remove variant");
    public final JButton btnCombos = UiKit.plain("Generate size × colour...");
    public final JButton btnCopyPrices = UiKit.plain("Copy first row prices to all");

    public final RowTableModel<BundleItem> bundleModel = new RowTableModel<BundleItem>()
            .col("Component", String.class, b -> b.componentName)
            .col("SKU", String.class, b -> b.componentSku)
            .col("Qty per combo", BigDecimal.class, b -> b.quantity);
    public final JTable tblBundle = new JTable(bundleModel);
    public final JButton btnAddComponent = UiKit.plain("+ Add component");
    public final JButton btnRemoveComponent = UiKit.plain("Remove");
    public final JButton btnComponentQty = UiKit.plain("Change qty");
    public final JLabel lblBundleInfo = UiKit.mutedLabel(" ");

    public final JTabbedPane tabs = new JTabbedPane();
    public final JButton btnSave = UiKit.primary("Save product");
    public final JButton btnCancel = UiKit.plain("Cancel");

    /** Image chosen in this session (copied on save); null = unchanged. */
    public File chosenImage;
    public boolean imageRemoved;
    public String existingImage;

    public ProductEditorDialog(Window owner, boolean isNew) {
        super(owner, isNew ? "New product" : "Edit product", ModalityType.APPLICATION_MODAL);
        UiKit.styleTable(tblVariants);
        tblVariants.setAutoCreateRowSorter(false);
        tblVariants.putClientProperty("terminateEditOnFocusLost", true);
        tblVariants.setSurrendersFocusOnKeystroke(true);
        UiKit.styleTable(tblBundle);

        UiKit.Form f = new UiKit.Form();
        f.section("Basic information");
        f.add("Product name *", txtName);
        f.add("Category", cmbCategory);
        f.add("Brand", cmbBrand);
        f.add("Unit", cmbUnit);
        f.add("Product type", cmbType);
        f.section("Pricing rules");
        f.add("VAT %", txtVat);
        f.add("Product discount", cmbDiscount);
        f.add("Discount value", txtDiscount);
        f.section("Tracking");
        f.full(chkBatch);
        f.full(chkSerial);
        f.full(chkActive);
        f.section("Description");
        txtDescription.setLineWrap(true);
        txtDescription.setWrapStyleWord(true);
        f.full(new JScrollPane(txtDescription));
        f.filler();

        JPanel imgBox = UiKit.card(new BorderLayout(0, 8));
        imgPreview.setPreferredSize(new Dimension(220, 220));
        imgPreview.setForeground(UiKit.muted());
        imgBox.add(UiKit.title("Product image", 14f), BorderLayout.NORTH);
        imgBox.add(imgPreview);
        imgBox.add(UiKit.row(btnImage, btnRemoveImage), BorderLayout.SOUTH);

        JPanel general = new JPanel(new BorderLayout(18, 0));
        general.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        general.add(f.panel);
        JPanel side = new JPanel(new BorderLayout());
        side.add(imgBox, BorderLayout.NORTH);
        general.add(side, BorderLayout.EAST);

        JPanel variants = new JPanel(new BorderLayout(0, 10));
        variants.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        variants.add(UiKit.mutedLabel("<html>Each row is a sellable item with its own SKU, barcode, prices and stock. "
                + "Leave SKU / barcode blank to generate them. Opening stock is only for new rows of untracked products."
                + "</html>"), BorderLayout.NORTH);
        variants.add(UiKit.tableCard(tblVariants));
        variants.add(UiKit.row(btnAddVariant, btnRemoveVariant, btnCombos, btnCopyPrices), BorderLayout.SOUTH);

        JPanel bundle = new JPanel(new BorderLayout(0, 10));
        bundle.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        bundle.add(UiKit.mutedLabel("<html>A combo is sold as one item; selling it deducts each component from stock. "
                + "Set the combo selling price on the Variants tab.</html>"), BorderLayout.NORTH);
        bundle.add(UiKit.tableCard(tblBundle));
        JPanel bundleSouth = new JPanel(new BorderLayout());
        bundleSouth.add(UiKit.row(btnAddComponent, btnComponentQty, btnRemoveComponent), BorderLayout.WEST);
        bundleSouth.add(lblBundleInfo, BorderLayout.EAST);
        bundle.add(bundleSouth, BorderLayout.SOUTH);

        tabs.addTab("General", general);
        tabs.addTab("Variants, SKU & Prices", variants);
        tabs.addTab("Bundle components", bundle);

        JPanel root = new JPanel(new BorderLayout());
        root.add(tabs);
        JPanel south = UiKit.rowRight(btnCancel, btnSave);
        south.setBorder(BorderFactory.createEmptyBorder(8, 14, 12, 14));
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(1080, 680);
        setLocationRelativeTo(owner);
        getRootPane().setDefaultButton(null);
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
            imgPreview.setText("No image");
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
        double s = Math.min(210.0 / w, 210.0 / h);
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
        chkActive.setSelected(p.active);
        txtDescription.setText(p.description);
        existingImage = p.imagePath;
        showImage(p.imagePath == null ? null : new File(p.imagePath));
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
        p.active = chkActive.isSelected();
        p.description = txtDescription.getText().isBlank() ? null : txtDescription.getText().trim();
        p.variants = new ArrayList<>(variantModel.rows);
        p.bundleItems = new ArrayList<>(bundleModel.rows());
    }

    /** Editable variant grid. */
    public static class VariantModel extends AbstractTableModel {
        private static final String[] COLS = {"Size", "Colour", "Weight", "SKU", "Barcode", "Cost", "Retail *",
            "Wholesale", "Wholesale from qty", "Reorder level", "Opening stock", "Active"};
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
            return c == 11 ? Boolean.class : (c >= 5 ? BigDecimal.class : String.class);
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c != 10 || rows.get(r).variantId == 0;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Variant v = rows.get(r);
            switch (c) {
                case 0: return v.size;
                case 1: return v.colour;
                case 2: return v.weight;
                case 3: return v.sku;
                case 4: return v.barcode;
                case 5: return v.purchasePrice;
                case 6: return v.retailPrice;
                case 7: return v.wholesalePrice;
                case 8: return v.wholesaleMinQty;
                case 9: return v.reorderLevel;
                case 10: return v.variantId == 0 ? v.openingStock : null;
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
                    case 3: v.sku = s.toUpperCase(); break;
                    case 4: v.barcode = s; break;
                    case 5: v.purchasePrice = Money.parseOr(s, BigDecimal.ZERO); break;
                    case 6: v.retailPrice = Money.parseOr(s, BigDecimal.ZERO); break;
                    case 7: v.wholesalePrice = Money.parseOr(s, BigDecimal.ZERO); break;
                    case 8: v.wholesaleMinQty = Money.parseOr(s, BigDecimal.ZERO); break;
                    case 9: v.reorderLevel = Money.parseOr(s, BigDecimal.ZERO); break;
                    case 10: v.openingStock = Money.parseOr(s, BigDecimal.ZERO); break;
                    default: v.active = Boolean.TRUE.equals(value); break;
                }
            } catch (IllegalArgumentException e) {
                java.awt.Toolkit.getDefaultToolkit().beep();
            }
            fireTableRowsUpdated(r, r);
        }
    }
}
