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
    private final JComboBox<LabelService.Layout> cmbLayout = new JComboBox<>(LabelService.LAYOUTS);
    private final JCheckBox chkShop = new JCheckBox("Shop name", true);
    private final JCheckBox chkPrice = new JCheckBox("Price", true);
    private final JCheckBox chkQr = new JCheckBox("QR code", false);
    private final JCheckBox chkWholesale = new JCheckBox("Use wholesale price", false);
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
        opts.add("Label stock", cmbLayout);
        opts.full(UiKit.row(chkShop, chkPrice, chkQr, chkWholesale));
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
        cmbLayout.addActionListener(e -> refresh.run());
        for (JCheckBox c : new JCheckBox[]{chkShop, chkPrice, chkQr, chkWholesale}) {
            c.addActionListener(e -> refresh.run());
        }
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
        o.showPrice = chkPrice.isSelected();
        o.showQr = chkQr.isSelected();
        o.wholesalePrice = chkWholesale.isSelected();
        return o;
    }

    private void refresh() {
        UiKit.guard(this, () -> {
            LabelService.Layout l = (LabelService.Layout) cmbLayout.getSelectedItem();
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
