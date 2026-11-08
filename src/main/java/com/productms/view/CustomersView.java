package com.productms.view;

import com.productms.model.Customer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.math.BigDecimal;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;

/** Customer list with purchases and outstanding dues. */
public class CustomersView extends JPanel {

    public final JTextField txtSearch = UiKit.search("Search name or phone");
    public final JCheckBox chkDueOnly = new JCheckBox("Only customers with due");
    public final JCheckBox chkOverdue = new JCheckBox("Only overdue");
    public final JButton btnNew = UiKit.primary("+ New Customer");
    public final JButton btnEdit = UiKit.plain("Edit");
    public final JButton btnCollect = UiKit.success("Collect Due");
    public final JButton btnHistory = UiKit.plain("Purchase History");
    public final JButton btnCredit = UiKit.plain("Store Credit");
    public final JButton btnExport = UiKit.plain("Export CSV");
    public final RowTableModel<Customer> model = new RowTableModel<Customer>()
            .col("Name", String.class, c -> c.name)
            .col("Phone", String.class, c -> c.phone)
            .col("Email", String.class, c -> c.email)
            .col("Address", String.class, c -> c.address)
            .col("Type", String.class, c -> c.walkIn ? "WALK-IN" : c.customerType)
            .col("Total Purchases", BigDecimal.class, c -> c.totalPurchases)
            .col("Due", BigDecimal.class, c -> c.due)
            .col("Overdue", BigDecimal.class, c -> c.overdueAmount)
            .col("Credit limit", String.class, c -> c.walkIn ? "" : c.creditLimit == null ? "no limit" : com.productms.util.Money.fmt(c.creditLimit))
            .col("Terms (days)", Integer.class, c -> c.paymentTermsDays)
            .col("Store credit", BigDecimal.class, c -> c.storeCredit)
            .col("Notes", String.class, c -> c.notes)
            .col("Status", String.class, c -> !c.active ? "INACTIVE" : c.overdueInvoices > 0 ? "OVERDUE" : "ACTIVE");
    public final JTable table = new JTable(model);

    public CustomersView() {
        super(new BorderLayout());
        UiKit.styleTable(table);
        txtSearch.setPreferredSize(new Dimension(300, 36));
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.add(UiKit.row(txtSearch, chkDueOnly, chkOverdue), BorderLayout.WEST);
        bar.add(UiKit.rowRight(btnNew, btnEdit, btnCollect, btnHistory, btnCredit, btnExport), BorderLayout.EAST);
        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false);
        body.add(bar, BorderLayout.NORTH);
        body.add(UiKit.tableCard(table));
        add(UiKit.page("Customers", "Retail and wholesale customers, purchase history and dues", null, body));
    }
}
