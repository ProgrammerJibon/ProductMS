package com.productms.controller;

import com.productms.dao.CustomerDAO;
import com.productms.model.Customer;
import com.productms.model.Sale;
import com.productms.service.SaleService;
import com.productms.view.CustomersView;
import com.productms.view.RowTableModel;
import com.productms.view.TableExport;
import com.productms.view.UiKit;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;

/** Customer CRUD, due collection and purchase history. */
public class CustomerController implements Module {

    private final CustomersView view = new CustomersView();
    private final CustomerDAO dao = new CustomerDAO();
    private final MainController main;

    public CustomerController(MainController main) {
        this.main = main;
        view.txtSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
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
        view.chkDueOnly.addActionListener(e -> load());
        view.btnNew.addActionListener(e -> edit(new Customer()));
        view.btnEdit.addActionListener(e -> {
            Customer c = view.model.selected(view.table);
            if (c != null) {
                edit(c);
            }
        });
        view.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Customer c = view.model.selected(view.table);
                if (e.getClickCount() == 2 && c != null) {
                    edit(c);
                }
            }
        });
        view.btnCollect.addActionListener(e -> {
            Customer c = view.model.selected(view.table);
            if (c == null) {
                UiKit.warn(view, "Select a customer.");
                return;
            }
            UiKit.guard(view, () -> {
                main.collectDue(view, dao.findById(c.customerId));
                load();
            });
        });
        view.btnHistory.addActionListener(e -> history());
        view.btnExport.addActionListener(e -> TableExport.csv(view, view.table, "customers"));
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void onShow() {
        load();
    }

    private void load() {
        UiKit.guard(view, () -> {
            List<Customer> list = dao.search(view.txtSearch.getText(), false);
            if (view.chkDueOnly.isSelected()) {
                list = list.stream().filter(c -> c.due.signum() > 0).collect(Collectors.toList());
            }
            view.model.setRows(list);
        });
    }

    private void edit(Customer c) {
        if (c.walkIn) {
            UiKit.info(view, "The walk-in customer is used for anonymous sales and cannot be edited.");
            return;
        }
        JTextField name = UiKit.field("Full name");
        JTextField phone = UiKit.field("01XXXXXXXXX");
        JTextField email = UiKit.field("Optional");
        JTextField address = UiKit.field("Optional");
        JComboBox<String> type = new JComboBox<>(new String[]{"RETAIL", "WHOLESALE"});
        JCheckBox active = new JCheckBox("Active", c.active);
        name.setText(c.name);
        phone.setText(c.phone);
        email.setText(c.email);
        address.setText(c.address);
        type.setSelectedItem(c.customerType);
        UiKit.Form f = new UiKit.Form();
        f.add("Name *", name);
        f.add("Phone", phone);
        f.add("Email", email);
        f.add("Address", address);
        f.add("Type", type);
        f.full(active);
        f.panel.setPreferredSize(new java.awt.Dimension(420, f.panel.getPreferredSize().height));
        while (JOptionPane.showConfirmDialog(view, f.panel, c.customerId == 0 ? "New customer" : "Edit customer",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            c.name = name.getText();
            c.phone = phone.getText();
            c.email = email.getText();
            c.address = address.getText();
            c.customerType = (String) type.getSelectedItem();
            c.active = active.isSelected();
            boolean[] ok = {false};
            UiKit.guard(view, () -> {
                dao.save(c);
                ok[0] = true;
            });
            if (ok[0]) {
                load();
                return;
            }
        }
    }

    private void history() {
        Customer c = view.model.selected(view.table);
        if (c == null) {
            UiKit.warn(view, "Select a customer.");
            return;
        }
        UiKit.guard(view, () -> {
            List<Sale> list = new SaleService().history(LocalDate.of(2000, 1, 1), LocalDate.now(), null, "ALL", "ALL", c.customerId);
            RowTableModel<Sale> m = new RowTableModel<Sale>()
                    .col("Invoice", String.class, s -> s.invoiceNo)
                    .col("Date", LocalDateTime.class, s -> s.createdAt)
                    .col("Total", BigDecimal.class, s -> s.grandTotal)
                    .col("Paid", BigDecimal.class, s -> s.paidTotal)
                    .col("Due", BigDecimal.class, s -> s.dueTotal)
                    .col("Returned", BigDecimal.class, s -> s.returnedTotal)
                    .col("Status", String.class, s -> s.status);
            m.setRows(list);
            JTable t = new JTable(m);
            UiKit.styleTable(t);
            JDialog d = new JDialog(UiKit.window(view), "Purchase history - " + c.name, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
            JPanel p = new JPanel(new BorderLayout(0, 10));
            p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
            p.add(UiKit.title(c.name + "  ·  " + list.size() + " invoice(s)  ·  purchases "
                    + com.productms.util.Money.cur(c.totalPurchases) + "  ·  due " + com.productms.util.Money.cur(c.due), 14f),
                    BorderLayout.NORTH);
            p.add(UiKit.tableCard(t));
            d.setContentPane(p);
            d.setSize(820, 480);
            d.setLocationRelativeTo(view);
            d.setVisible(true);
        });
    }
}
