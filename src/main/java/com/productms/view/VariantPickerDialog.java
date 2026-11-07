package com.productms.view;

import com.productms.dao.ProductDAO;
import com.productms.model.Variant;
import java.awt.BorderLayout;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.Timer;

/** Searchable product/variant lookup used by stock forms, bundles and the POS. */
public class VariantPickerDialog extends JDialog {

    private final JTextField txtSearch = UiKit.search("Type to search name, SKU or scan barcode...");
    private final RowTableModel<Variant> model = new RowTableModel<Variant>()
            .col("Product", String.class, Variant::displayName)
            .col("SKU", String.class, v -> v.sku)
            .col("Barcode", String.class, v -> v.barcode)
            .col("Price", BigDecimal.class, v -> v.retailPrice)
            .col("Available", BigDecimal.class, v -> v.available)
            .col("Unit", String.class, v -> v.unitShort);
    private final JTable table = new JTable(model);
    private final ProductDAO dao = new ProductDAO();
    private final ProductDAO.Filter filter;
    private Variant result;

    private VariantPickerDialog(Window owner, String title, ProductDAO.Filter filter) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.filter = filter;
        UiKit.styleTable(table);
        table.getColumnModel().getColumn(0).setPreferredWidth(280);
        JButton btnSelect = UiKit.primary("Select");
        JButton btnCancel = UiKit.plain("Cancel");
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        p.add(txtSearch, BorderLayout.NORTH);
        p.add(UiKit.tableCard(table));
        p.add(UiKit.rowRight(btnCancel, btnSelect), BorderLayout.SOUTH);
        setContentPane(p);
        setSize(820, 520);
        setLocationRelativeTo(owner);

        Timer debounce = new Timer(250, e -> load());
        debounce.setRepeats(false);
        txtSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                debounce.restart();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                debounce.restart();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                debounce.restart();
            }
        });
        txtSearch.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DOWN && table.getRowCount() > 0) {
                    table.requestFocusInWindow();
                    table.setRowSelectionInterval(0, 0);
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    debounce.stop();
                    load();
                    if (table.getRowCount() >= 1) {
                        table.setRowSelectionInterval(0, 0);
                        if (table.getRowCount() == 1) {
                            choose();
                        }
                    }
                }
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    choose();
                }
            }
        });
        UiKit.bind(table, "ENTER", this::choose);
        UiKit.bind(p, "ESCAPE", this::dispose);
        btnSelect.addActionListener(e -> choose());
        btnCancel.addActionListener(e -> dispose());
        load();
    }

    private void load() {
        UiKit.guard(this, () -> {
            filter.text = txtSearch.getText();
            filter.limit = 300;
            model.setRows(dao.search(filter));
        });
    }

    private void choose() {
        result = model.selected(table);
        if (result != null) {
            dispose();
        }
    }

    /** Shows the picker and returns the chosen variant (or null). */
    public static Variant pick(java.awt.Component parent, String title, ProductDAO.Filter filter) {
        VariantPickerDialog d = new VariantPickerDialog(UiKit.window(parent), title, filter);
        d.setVisible(true);
        return d.result;
    }

    public static Variant pick(java.awt.Component parent, String title, boolean excludeBundles, boolean activeOnly) {
        ProductDAO.Filter f = new ProductDAO.Filter();
        f.excludeBundles = excludeBundles;
        f.status = activeOnly ? "ACTIVE" : "ALL";
        return pick(parent, title, f);
    }
}
