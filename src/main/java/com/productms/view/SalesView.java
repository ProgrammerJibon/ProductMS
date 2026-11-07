package com.productms.view;

import com.productms.model.Payment;
import com.productms.model.Sale;
import com.productms.model.SaleItem;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;

/** Sales history with invoice details, reprint, returns, exchange, cancellation and dues. */
public class SalesView extends JPanel {

    public final JTabbedPane tabs = new JTabbedPane();
    public final JSpinner dtFrom = UiKit.dateSpinner(LocalDate.now().minusDays(7));
    public final JSpinner dtTo = UiKit.dateSpinner(LocalDate.now());
    public final JTextField txtSearch = UiKit.search("Invoice no, customer name or phone");
    public final JComboBox<String> cmbStatus = new JComboBox<>(new String[]{"ALL", "COMPLETED", "CANCELLED"});
    public final JComboBox<String> cmbPay = new JComboBox<>(new String[]{"ALL", "PAID", "PARTIAL", "DUE"});
    public final JButton btnSearch = UiKit.primary("Search");
    public final JButton btnToday = UiKit.plain("Today");
    public final JButton btnExport = UiKit.plain("Export CSV");

    public final RowTableModel<Sale> model = new RowTableModel<Sale>()
            .col("Invoice", String.class, s -> s.invoiceNo)
            .col("Date", LocalDateTime.class, s -> s.createdAt)
            .col("Customer", String.class, s -> s.customerName)
            .col("Cashier", String.class, s -> s.cashierName)
            .col("Lines", Integer.class, s -> s.itemCount)
            .col("Total", BigDecimal.class, s -> s.grandTotal)
            .col("Paid", BigDecimal.class, s -> s.paidTotal)
            .col("Due", BigDecimal.class, s -> s.dueTotal)
            .col("Returned", BigDecimal.class, s -> s.returnedTotal)
            .col("Payment", String.class, s -> s.paymentStatus)
            .col("Status", String.class, s -> s.status);
    public final JTable table = new JTable(model);
    public final JLabel lblSummary = UiKit.mutedLabel(" ");

    public final RowTableModel<SaleItem> itemsModel = new RowTableModel<SaleItem>()
            .col("Item", String.class, SaleItem::displayName)
            .col("Qty", BigDecimal.class, i -> i.quantity)
            .col("Price", BigDecimal.class, i -> i.unitPrice)
            .col("Disc.", BigDecimal.class, i -> i.discountAmount)
            .col("VAT", BigDecimal.class, i -> i.vatAmount)
            .col("Total", BigDecimal.class, i -> i.lineTotal)
            .col("Returned", BigDecimal.class, i -> i.returnedQty)
            .col("Serials", String.class, i -> i.serialNumbers);
    public final JTable itemsTable = new JTable(itemsModel);
    public final RowTableModel<Payment> payModel = new RowTableModel<Payment>()
            .col("Method", String.class, p -> Payment.label(p.method))
            .col("Type", String.class, p -> p.paymentType)
            .col("Amount", BigDecimal.class, p -> p.amount)
            .col("Reference", String.class, p -> p.reference)
            .col("Time", LocalDateTime.class, p -> p.createdAt);
    public final JTable payTable = new JTable(payModel);
    public final JLabel lblDetail = UiKit.title("Select an invoice", 14f);

    public final JButton btnReceipt = UiKit.primary("View / Reprint");
    public final JButton btnReturn = UiKit.plain("Return / Refund");
    public final JButton btnExchange = UiKit.plain("Exchange");
    public final JButton btnCollect = UiKit.plain("Collect Due");
    public final JButton btnCancel = UiKit.danger("Cancel Sale");

    public final JSpinner rtFrom = UiKit.dateSpinner(LocalDate.now().minusDays(30));
    public final JSpinner rtTo = UiKit.dateSpinner(LocalDate.now());
    public final JTextField rtSearch = UiKit.search("Return no, invoice or customer");
    public final JButton rtLoad = UiKit.primary("Search");
    public final DataTableModel rtModel = new DataTableModel();
    public final JTable rtTable = new JTable(rtModel);

    public SalesView() {
        super(new BorderLayout());
        for (JTable t : new JTable[]{table, itemsTable, payTable, rtTable}) {
            UiKit.styleTable(t);
        }
        itemsTable.getColumnModel().getColumn(0).setPreferredWidth(240);
        txtSearch.setPreferredSize(new Dimension(260, 34));

        JPanel invoices = new JPanel(new BorderLayout(0, 10));
        invoices.setOpaque(false);
        invoices.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.add(UiKit.row(new JLabel("From"), dtFrom, new JLabel("To"), dtTo, txtSearch, cmbStatus, cmbPay, btnSearch, btnToday),
                BorderLayout.WEST);
        bar.add(UiKit.rowRight(btnExport), BorderLayout.EAST);
        invoices.add(bar, BorderLayout.NORTH);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(UiKit.tableCard(table));
        top.add(lblSummary, BorderLayout.SOUTH);

        JPanel detail = UiKit.card(new BorderLayout(0, 8));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(lblDetail, BorderLayout.WEST);
        head.add(UiKit.rowRight(btnReceipt, btnReturn, btnExchange, btnCollect, btnCancel), BorderLayout.EAST);
        detail.add(head, BorderLayout.NORTH);
        JSplitPane inner = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, UiKit.scroll(itemsTable), UiKit.scroll(payTable));
        inner.setResizeWeight(0.65);
        inner.setBorder(null);
        detail.add(inner);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, detail);
        split.setResizeWeight(0.55);
        split.setBorder(null);
        split.setOpaque(false);
        invoices.add(split);

        JPanel returns = new JPanel(new BorderLayout(0, 10));
        returns.setOpaque(false);
        returns.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        rtSearch.setPreferredSize(new Dimension(260, 34));
        returns.add(UiKit.row(new JLabel("From"), rtFrom, new JLabel("To"), rtTo, rtSearch, rtLoad), BorderLayout.NORTH);
        returns.add(UiKit.tableCard(rtTable));

        tabs.addTab("Invoices", invoices);
        tabs.addTab("Returns, Refunds & Cancellations", returns);
        add(UiKit.page("Sales", "Invoices, receipts, returns, exchanges, cancellations and due payments", null, tabs));
    }
}
