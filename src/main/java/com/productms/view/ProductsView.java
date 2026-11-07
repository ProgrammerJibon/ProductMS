package com.productms.view;

import com.productms.model.Brand;
import com.productms.model.Category;
import com.productms.model.Variant;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.math.BigDecimal;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

/** Product catalogue: search & filters, variant table and a detail panel with image, barcode and QR. */
public class ProductsView extends JPanel {

    public final JTextField txtSearch = UiKit.search("Search name, SKU, barcode, brand, size, colour...");
    public final JComboBox<Object> cmbCategory = new JComboBox<>();
    public final JComboBox<Object> cmbBrand = new JComboBox<>();
    public final JComboBox<String> cmbStatus = new JComboBox<>(new String[]{"All status", "Active", "Inactive"});
    public final JComboBox<String> cmbStock = new JComboBox<>(new String[]{"All stock", "In stock", "Low stock", "Out of stock"});

    public final JButton btnNew = UiKit.primary("+ New Product");
    public final JButton btnEdit = UiKit.plain("Edit");
    public final JButton btnToggle = UiKit.plain("Deactivate");
    public final JButton btnDelete = UiKit.plain("Delete");
    public final JButton btnStockIn = UiKit.plain("Stock In");
    public final JButton btnLabels = UiKit.plain("Print Labels");
    public final JButton btnImport = UiKit.plain("Import CSV");
    public final JButton btnExport = UiKit.plain("Export CSV");
    public final JButton btnTemplate = UiKit.plain("CSV Template");

    public final RowTableModel<Variant> model = new RowTableModel<Variant>()
            .col("SKU", String.class, v -> v.sku)
            .col("Product", String.class, v -> v.productName)
            .col("Variant", String.class, Variant::label)
            .col("Category", String.class, v -> v.categoryName)
            .col("Brand", String.class, v -> v.brandName)
            .col("Unit", String.class, v -> v.unitShort)
            .col("Cost", BigDecimal.class, v -> v.purchasePrice)
            .col("Retail", BigDecimal.class, v -> v.retailPrice)
            .col("Wholesale", BigDecimal.class, v -> v.wholesalePrice)
            .col("Stock", BigDecimal.class, v -> v.isBundle() ? null : v.stockQty)
            .col("Available", BigDecimal.class, v -> v.available)
            .col("Status", String.class, ProductsView::status);
    public final JTable table = new JTable(model);
    public final JLabel lblCount = UiKit.mutedLabel(" ");

    // detail panel
    public final JLabel imgProduct = new JLabel("No image", SwingConstants.CENTER);
    public final JLabel lblName = UiKit.title(" ", 15f);
    public final JTextArea txtDetails = new JTextArea();
    public final JLabel imgBarcode = new JLabel("", SwingConstants.CENTER);
    public final JLabel imgQr = new JLabel("", SwingConstants.CENTER);

    public static String status(Variant v) {
        if (!v.isSellable()) {
            return "INACTIVE";
        }
        if (v.available.signum() <= 0) {
            return "OUT OF STOCK";
        }
        return v.isLowStock() ? "LOW STOCK" : "IN STOCK";
    }

    public ProductsView() {
        super(new BorderLayout());
        UiKit.styleTable(table);
        int[] widths = {125, 200, 105, 160, 100, 50, 80, 80, 80, 65, 75, 105};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        txtSearch.setPreferredSize(new Dimension(320, 36));
        cmbCategory.setPreferredSize(new Dimension(200, 36));
        cmbBrand.setPreferredSize(new Dimension(150, 36));

        JPanel filters = UiKit.row(txtSearch, cmbCategory, cmbBrand, cmbStatus, cmbStock);
        JPanel actions = UiKit.row(btnNew, btnEdit, btnToggle, btnDelete, btnStockIn, btnLabels);
        JPanel io = UiKit.rowRight(btnImport, btnExport, btnTemplate);
        JPanel bar = new JPanel(new BorderLayout(0, 10));
        bar.setOpaque(false);
        bar.add(filters, BorderLayout.NORTH);
        JPanel actionsRow = new JPanel(new BorderLayout());
        actionsRow.setOpaque(false);
        actionsRow.add(actions, BorderLayout.WEST);
        actionsRow.add(io, BorderLayout.EAST);
        bar.add(actionsRow, BorderLayout.SOUTH);

        JPanel detail = UiKit.card(new BorderLayout(0, 10));
        detail.setPreferredSize(new Dimension(290, 0));
        imgProduct.setPreferredSize(new Dimension(250, 170));
        imgProduct.setBorder(BorderFactory.createDashedBorder(UiKit.borderColor(), 4, 4));
        imgProduct.setForeground(UiKit.muted());
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(imgProduct, BorderLayout.NORTH);
        top.add(lblName, BorderLayout.SOUTH);
        detail.add(top, BorderLayout.NORTH);
        txtDetails.setEditable(false);
        txtDetails.setOpaque(false);
        txtDetails.setLineWrap(true);
        txtDetails.setWrapStyleWord(true);
        txtDetails.setBorder(null);
        javax.swing.JScrollPane detailScroll = UiKit.scroll(txtDetails);
        detailScroll.setOpaque(false);
        detailScroll.getViewport().setOpaque(false);
        detail.add(detailScroll, BorderLayout.CENTER);
        JPanel codes = new JPanel(new GridLayout(1, 2, 8, 0));
        codes.setOpaque(false);
        codes.add(imgBarcode);
        codes.add(imgQr);
        detail.add(codes, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(14, 0));
        center.setOpaque(false);
        JPanel tableArea = new JPanel(new BorderLayout(0, 6));
        tableArea.setOpaque(false);
        tableArea.add(UiKit.tableCard(table));
        tableArea.add(lblCount, BorderLayout.SOUTH);
        center.add(tableArea);
        center.add(detail, BorderLayout.EAST);

        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false);
        body.add(bar, BorderLayout.NORTH);
        body.add(center);
        add(UiKit.page("Products", "Catalogue, variants, prices and stock levels", null, body));
    }

    public void fillFilters(java.util.List<Category> cats, java.util.List<Brand> brands) {
        Object c = cmbCategory.getSelectedItem();
        Object b = cmbBrand.getSelectedItem();
        cmbCategory.removeAllItems();
        cmbCategory.addItem("All categories");
        for (Category x : cats) {
            cmbCategory.addItem(x);
        }
        cmbBrand.removeAllItems();
        cmbBrand.addItem("All brands");
        for (Brand x : brands) {
            cmbBrand.addItem(x);
        }
        if (c != null) {
            cmbCategory.setSelectedItem(c);
        }
        if (b != null) {
            cmbBrand.setSelectedItem(b);
        }
    }
}
