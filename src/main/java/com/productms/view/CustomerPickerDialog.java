package com.productms.view;

import com.productms.dao.CustomerDAO;
import com.productms.model.Customer;
import java.awt.BorderLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;

/** POS customer lookup by name / phone with quick "new customer". */
public class CustomerPickerDialog extends JDialog {

    private final CustomerDAO dao = new CustomerDAO();
    private final JTextField txt = UiKit.search("Search by name or phone number");
    private final RowTableModel<Customer> model = new RowTableModel<Customer>()
            .col("Name", String.class, c -> c.name)
            .col("Phone", String.class, c -> c.phone)
            .col("Type", String.class, c -> c.customerType)
            .col("Due", BigDecimal.class, c -> c.due);
    private final JTable table = new JTable(model);
    private Customer result;

    private CustomerPickerDialog(Window owner) {
        super(owner, "Select customer", ModalityType.APPLICATION_MODAL);
        UiKit.styleTable(table);
        JButton select = UiKit.primary("Select");
        JButton add = UiKit.success("+ New customer");
        JButton cancel = UiKit.plain("Cancel");
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        root.add(txt, BorderLayout.NORTH);
        root.add(UiKit.tableCard(table));
        JPanel south = new JPanel(new BorderLayout());
        south.add(UiKit.row(add), BorderLayout.WEST);
        south.add(UiKit.rowRight(cancel, select), BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(640, 480);
        setLocationRelativeTo(owner);

        txt.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                load();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                load();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                load();
            }
        });
        txt.addActionListener(e -> {
            if (table.getRowCount() > 0) {
                table.setRowSelectionInterval(0, 0);
                choose();
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
        select.addActionListener(e -> choose());
        cancel.addActionListener(e -> dispose());
        add.addActionListener(e -> quickAdd());
        UiKit.bind(root, "ESCAPE", this::dispose);
        load();
    }

    private void load() {
        UiKit.guard(this, () -> model.setRows(dao.search(txt.getText(), true)));
    }

    private void choose() {
        result = model.selected(table);
        if (result != null) {
            dispose();
        }
    }

    private void quickAdd() {
        JTextField name = UiKit.field("Full name");
        JTextField phone = UiKit.field("01XXXXXXXXX");
        String q = txt.getText().trim();
        if (q.matches("[0-9+ -]+")) {
            phone.setText(q);
        } else {
            name.setText(q);
        }
        JComboBox<String> type = new JComboBox<>(new String[]{"RETAIL", "WHOLESALE"});
        UiKit.Form f = new UiKit.Form();
        f.add("Name *", name);
        f.add("Phone", phone);
        f.add("Type", type);
        if (JOptionPane.showConfirmDialog(this, f.panel, "New customer", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        UiKit.guard(this, () -> {
            Customer c = new Customer();
            c.name = name.getText();
            c.phone = phone.getText();
            c.customerType = (String) type.getSelectedItem();
            dao.save(c);
            result = dao.findById(c.customerId);
            dispose();
        });
    }

    public static Customer pick(java.awt.Component parent) {
        CustomerPickerDialog d = new CustomerPickerDialog(UiKit.window(parent));
        d.setVisible(true);
        return d.result;
    }
}
