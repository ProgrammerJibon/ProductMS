package com.productms.view;

import com.productms.model.Variant;
import com.productms.service.LabelService;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Window;
import java.awt.image.BufferedImage;
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
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;

/** Choose items and copies, preview the label sheet, and print barcode labels. */
public class LabelPrintDialog extends JDialog {

    private final List<Variant> items = new ArrayList<>();
    private final List<Integer> copies = new ArrayList<>();
    private final JComboBox<LabelService.Layout> cmbLayout = new JComboBox<>();
    private final JCheckBox chkShop = new JCheckBox("Shop name", true);
    private final JCheckBox chkName = new JCheckBox("Name", true);
    private final JCheckBox chkPrice = new JCheckBox("Price", true);
    private final JCheckBox chkMrp = new JCheckBox("MRP", false);
    private final JCheckBox chkSku = new JCheckBox("SKU", false);
    private final JCheckBox chkQr = new JCheckBox("QR code", false);
    private final JCheckBox chkWholesale = new JCheckBox("Use wholesale price", false);
    private final javax.swing.JTextField txtScale = UiKit.field("1.0");
    private final JLabel preview = new JLabel("", SwingConstants.CENTER);
    private final JLabel lblInfo = UiKit.mutedLabel(" ");
    private final CopiesModel model = new CopiesModel();

    public LabelPrintDialog(Window owner, List<Variant> selected) {
        super(owner, "Print barcode labels", ModalityType.APPLICATION_MODAL);
        for (Variant v : selected) {
            items.add(v);
            copies.add(Math.max(1, Math.min(100, v.stockQty.intValue())));
        }
        JTable table = new JTable(model);
        UiKit.styleTable(table);
        table.setAutoCreateRowSorter(false);
        table.putClientProperty("terminateEditOnFocusLost", true);
        table.getColumnModel().getColumn(0).setPreferredWidth(230);

        JButton btnAdd = UiKit.plain("+ Add product");
        JButton btnRemove = UiKit.plain("Remove");
        JButton btnPrint = UiKit.primary("Print...");
        JButton btnClose = UiKit.plain("Close");

        JPanel left = new JPanel(new BorderLayout(0, 10));
        left.setPreferredSize(new Dimension(430, 0));
        left.add(UiKit.tableCard(table));
        UiKit.Form opts = new UiKit.Form();
        JButton btnSaveTpl = UiKit.plain("Save as template...");
        JButton btnCustom = UiKit.plain("Custom size...");
        JButton btnDeleteTpl = UiKit.plain("Delete");
        opts.add("Template", cmbLayout);
        opts.full(UiKit.row(btnCustom, btnSaveTpl, btnDeleteTpl));
        opts.full(UiKit.row(chkShop, chkName, chkPrice, chkMrp, chkSku, chkQr));
        txtScale.setText("1.0");
        txtScale.setPreferredSize(new Dimension(60, 30));
        opts.full(UiKit.row(chkWholesale, new javax.swing.JLabel("Text size"), txtScale));
        btnSaveTpl.addActionListener(e -> saveTemplate());
        btnCustom.addActionListener(e -> customSize());
        btnDeleteTpl.addActionListener(e -> UiKit.guard(this, () -> {
            LabelService.Layout l = (LabelService.Layout) cmbLayout.getSelectedItem();
            if (l != null && UiKit.confirm(this, "Delete template \"" + l.name + "\"?")) {
                LabelService.deleteTemplate(l.name);
                loadTemplates(null);
            }
        }));
        JPanel leftSouth = new JPanel(new BorderLayout(0, 8));
        leftSouth.add(UiKit.row(btnAdd, btnRemove), BorderLayout.NORTH);
        leftSouth.add(opts.panel);
        left.add(leftSouth, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.add(UiKit.scroll(preview));
        right.add(lblInfo, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(14, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        root.add(left, BorderLayout.WEST);
        root.add(right);
        root.add(UiKit.rowRight(btnClose, btnPrint), BorderLayout.SOUTH);
        setContentPane(root);
        setSize(1120, 720);
        setLocationRelativeTo(owner);

        Runnable refresh = this::refresh;
        cmbLayout.addActionListener(e -> {
            applyTemplateOptions();
            refresh.run();
        });
        for (JCheckBox c : new JCheckBox[]{chkShop, chkName, chkPrice, chkMrp, chkSku, chkQr, chkWholesale}) {
            c.addActionListener(e -> refresh.run());
        }
        txtScale.addActionListener(e -> refresh.run());
        loadTemplates(null);
        model.addTableModelListener(e -> refresh.run());
        btnAdd.addActionListener(e -> {
            Variant v = VariantPickerDialog.pick(this, "Add product to labels", false, false);
            if (v != null) {
                items.add(v);
                copies.add(1);
                model.fireTableDataChanged();
            }
        });
        btnRemove.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) {
                items.remove(r);
                copies.remove(r);
                model.fireTableDataChanged();
            }
        });
        btnClose.addActionListener(e -> dispose());
        btnPrint.addActionListener(e -> UiKit.guard(this, () -> {
            List<Variant> labels = LabelService.expand(items, copies);
            if (labels.isEmpty()) {
                UiKit.warn(this, "Add at least one label.");
                return;
            }
            LabelService.print(labels, (LabelService.Layout) cmbLayout.getSelectedItem(), options());
        }));
        refresh();
    }

    private LabelService.Options options() {
        LabelService.Options o = new LabelService.Options();
        o.showShop = chkShop.isSelected();
        o.showName = chkName.isSelected();
        o.showPrice = chkPrice.isSelected();
        o.showMrp = chkMrp.isSelected();
        o.showSku = chkSku.isSelected();
        o.showQr = chkQr.isSelected();
        o.wholesalePrice = chkWholesale.isSelected();
        try {
            o.fontScale = Math.max(0.5, Math.min(2.0, Double.parseDouble(txtScale.getText().trim())));
        } catch (NumberFormatException e) {
            o.fontScale = 1.0;
        }
        return o;
    }

    private boolean loading;

    private void loadTemplates(String select) {
        UiKit.guard(this, () -> {
            loading = true;
            cmbLayout.removeAllItems();
            java.util.List<LabelService.Layout> list = LabelService.templates();
            if (list.isEmpty()) {
                for (LabelService.Layout l : LabelService.LAYOUTS) {
                    cmbLayout.addItem(l);
                }
            }
            for (LabelService.Layout l : list) {
                cmbLayout.addItem(l);
                if (l.name.equals(select)) {
                    cmbLayout.setSelectedItem(l);
                }
            }
            loading = false;
            applyTemplateOptions();
        });
    }

    private void applyTemplateOptions() {
        LabelService.Layout l = (LabelService.Layout) cmbLayout.getSelectedItem();
        if (loading || l == null || l.defaults == null) {
            return;
        }
        chkShop.setSelected(l.defaults.showShop);
        chkName.setSelected(l.defaults.showName);
        chkPrice.setSelected(l.defaults.showPrice);
        chkMrp.setSelected(l.defaults.showMrp);
        chkSku.setSelected(l.defaults.showSku);
        chkQr.setSelected(l.defaults.showQr);
        txtScale.setText(String.valueOf(l.defaults.fontScale));
    }

    private void saveTemplate() {
        LabelService.Layout l = (LabelService.Layout) cmbLayout.getSelectedItem();
        if (l == null) {
            return;
        }
        String name = UiKit.input(this, "Template name:", l.name);
        if (name == null) {
            return;
        }
        UiKit.guard(this, () -> {
            LabelService.saveTemplate(new LabelService.Layout(name.trim(), l.widthMm, l.heightMm, l.cols, l.rows, l.roll), options());
            loadTemplates(name.trim());
        });
    }

    private void customSize() {
        javax.swing.JTextField w = UiKit.field("mm");
        javax.swing.JTextField h = UiKit.field("mm");
        javax.swing.JTextField cols = UiKit.field("A4 columns");
        javax.swing.JTextField rows = UiKit.field("A4 rows");
        javax.swing.JCheckBox roll = new javax.swing.JCheckBox("Label roll (one label per page)");
        javax.swing.JTextField name = UiKit.field("Template name");
        UiKit.Form f = new UiKit.Form();
        f.add("Name", name);
        f.add("Label width (mm)", w);
        f.add("Label height (mm)", h);
        f.add("Columns on A4", cols);
        f.add("Rows on A4", rows);
        f.full(roll);
        if (javax.swing.JOptionPane.showConfirmDialog(this, f.panel, "Custom label size", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) {
            return;
        }
        UiKit.guard(this, () -> {
            LabelService.Layout l = new LabelService.Layout(name.getText().trim(), Double.parseDouble(w.getText().trim()),
                    Double.parseDouble(h.getText().trim()), roll.isSelected() ? 1 : Integer.parseInt(cols.getText().trim()),
                    roll.isSelected() ? 1 : Integer.parseInt(rows.getText().trim()), roll.isSelected());
            LabelService.saveTemplate(l, options());
            loadTemplates(l.name);
        });
    }

    private void refresh() {
        UiKit.guard(this, () -> {
            LabelService.Layout l = (LabelService.Layout) cmbLayout.getSelectedItem();
            if (l == null || loading) {
                return;
            }
            List<Variant> labels = LabelService.expand(items, copies);
            BufferedImage img = LabelService.preview(labels, l, options(), l.roll ? 4 : 1.2);
            Image scaled = img;
            preview.setIcon(new ImageIcon(scaled));
            int pages = LabelService.pageCount(labels, l);
            lblInfo.setText(labels.size() + " label(s) on " + pages + (l.roll ? " roll label(s)" : " A4 sheet(s)")
                    + "  —  preview shows the first " + (l.roll ? "label" : "sheet") + ".");
        });
    }

    private class CopiesModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return items.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return new String[]{"Product", "Barcode", "Copies"}[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 2 ? Integer.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 2;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Variant v = items.get(r);
            return c == 0 ? v.displayName() : c == 1 ? (v.barcode == null ? v.sku : v.barcode) : copies.get(r);
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            int n = value instanceof Integer ? (Integer) value : 1;
            copies.set(r, Math.max(0, Math.min(1000, n)));
            fireTableRowsUpdated(r, r);
        }
    }
}
