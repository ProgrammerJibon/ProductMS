package com.productms.view;

import com.productms.dao.HeldCartDAO;
import java.awt.BorderLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDateTime;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTable;

/** Lists carts on hold; resume or discard them. */
public class HeldCartsDialog extends JDialog {

    private final HeldCartDAO dao = new HeldCartDAO();
    private final RowTableModel<HeldCartDAO.Held> model = new RowTableModel<HeldCartDAO.Held>()
            .col("Label", String.class, h -> h.label)
            .col("Customer", String.class, h -> h.customerName)
            .col("Items", Integer.class, h -> h.itemCount)
            .col("Held at", LocalDateTime.class, h -> h.createdAt)
            .col("By", String.class, h -> h.cashier);
    private final JTable table = new JTable(model);
    private Integer result;

    private HeldCartsDialog(Window owner) {
        super(owner, "Carts on hold", ModalityType.APPLICATION_MODAL);
        UiKit.styleTable(table);
        JButton resume = UiKit.primary("Resume (Enter)");
        JButton delete = UiKit.plain("Discard");
        JButton close = UiKit.plain("Close");
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        root.add(UiKit.tableCard(table));
        JPanel south = new JPanel(new BorderLayout());
        south.add(UiKit.row(delete), BorderLayout.WEST);
        south.add(UiKit.rowRight(close, resume), BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(640, 400);
        setLocationRelativeTo(owner);
        resume.addActionListener(e -> choose());
        close.addActionListener(e -> dispose());
        delete.addActionListener(e -> {
            HeldCartDAO.Held h = model.selected(table);
            if (h != null && UiKit.confirm(this, "Discard held cart \"" + h.label + "\"?")) {
                UiKit.guard(this, () -> {
                    dao.delete(h.heldId);
                    load();
                });
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
        UiKit.bind(root, "ESCAPE", this::dispose);
        load();
        if (table.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private void load() {
        UiKit.guard(this, () -> model.setRows(dao.list()));
    }

    private void choose() {
        HeldCartDAO.Held h = model.selected(table);
        if (h != null) {
            result = h.heldId;
            dispose();
        }
    }

    /** Returns the held cart id to resume, or null. */
    public static Integer pick(java.awt.Component parent) {
        HeldCartsDialog d = new HeldCartsDialog(UiKit.window(parent));
        d.setVisible(true);
        return d.result;
    }
}
