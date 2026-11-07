package com.productms.view;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.math.BigDecimal;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;

/** Current cashier shift with live cash summary, and shift history. */
public class ShiftView extends JPanel {

    public final JLabel lblStatus = UiKit.title("No shift open", 18f);
    public final JLabel lblInfo = UiKit.mutedLabel(" ");
    public final JButton btnOpen = UiKit.success("Open Shift");
    public final JButton btnClose = UiKit.danger("Close Shift");
    public final JButton btnX = UiKit.plain("Print X report (mid-shift)");
    public final JButton btnZ = UiKit.plain("Print selected shift report");
    public final JButton btnRefresh = UiKit.plain("Refresh");
    public final RowTableModel<Object[]> summaryModel = new RowTableModel<Object[]>()
            .col("Item", String.class, r -> r[0])
            .col("Amount", BigDecimal.class, r -> r[1]);
    public final JTable summaryTable = new JTable(summaryModel);
    public final DataTableModel historyModel = new DataTableModel();
    public final JTable historyTable = new JTable(historyModel);

    public ShiftView() {
        super(new BorderLayout());
        UiKit.styleTable(summaryTable);
        UiKit.styleTable(historyTable);
        summaryTable.setAutoCreateRowSorter(false);

        JPanel current = UiKit.card(new BorderLayout(0, 10));
        current.setPreferredSize(new Dimension(420, 0));
        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.add(lblStatus, BorderLayout.NORTH);
        head.add(lblInfo, BorderLayout.SOUTH);
        current.add(head, BorderLayout.NORTH);
        current.add(UiKit.scroll(summaryTable));
        JPanel btns = new JPanel(new java.awt.GridLayout(2, 2, 8, 8));
        btns.setOpaque(false);
        btns.add(btnOpen);
        btns.add(btnClose);
        btns.add(btnX);
        btns.add(btnRefresh);
        current.add(btns, BorderLayout.SOUTH);

        JPanel history = UiKit.card(new BorderLayout(0, 8));
        JPanel hh = new JPanel(new BorderLayout());
        hh.setOpaque(false);
        hh.add(UiKit.title("Shift history", 14f), BorderLayout.WEST);
        hh.add(UiKit.rowRight(btnZ), BorderLayout.EAST);
        history.add(hh, BorderLayout.NORTH);
        history.add(UiKit.scroll(historyTable));

        JPanel body = new JPanel(new BorderLayout(14, 0));
        body.setOpaque(false);
        body.add(current, BorderLayout.WEST);
        body.add(history);
        add(UiKit.page("Cashier Shifts", "Opening & closing cash balance, expected cash and over / short", null, body));
    }
}
