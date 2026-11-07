package com.productms.view;

import com.productms.service.ReportService;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.time.LocalDate;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;

/** Report picker, date range, results table, totals, export and print. */
public class ReportsView extends JPanel {

    public final JList<String> lstReports = new JList<>(ReportService.REPORTS);
    public final JSpinner dtFrom = UiKit.dateSpinner(LocalDate.now().withDayOfMonth(1));
    public final JSpinner dtTo = UiKit.dateSpinner(LocalDate.now());
    public final JButton btnToday = UiKit.plain("Today");
    public final JButton btnWeek = UiKit.plain("7 days");
    public final JButton btnMonth = UiKit.plain("This month");
    public final JButton btnYear = UiKit.plain("This year");
    public final JButton btnRun = UiKit.primary("Run report");
    public final JButton btnExport = UiKit.plain("Export CSV");
    public final JButton btnPrint = UiKit.plain("Print");
    public final JLabel lblTitle = UiKit.title("Daily Sales Summary", 16f);
    public final JLabel lblTotals = UiKit.mutedLabel(" ");
    public final DataTableModel model = new DataTableModel();
    public final JTable table = new JTable(model);
    public final JPanel dateBar;

    public ReportsView() {
        super(new BorderLayout());
        UiKit.styleTable(table);
        lstReports.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lstReports.setSelectedIndex(0);
        lstReports.setFixedCellHeight(34);
        lstReports.setFont(lstReports.getFont().deriveFont(Font.PLAIN, 13.5f));
        JPanel left = UiKit.card(new BorderLayout());
        left.setPreferredSize(new Dimension(240, 0));
        left.add(UiKit.title("Reports", 14f), BorderLayout.NORTH);
        left.add(UiKit.scroll(lstReports));

        dateBar = UiKit.row(new JLabel("From"), dtFrom, new JLabel("To"), dtTo, btnToday, btnWeek, btnMonth, btnYear);
        JPanel bar = new JPanel(new BorderLayout(0, 8));
        bar.setOpaque(false);
        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.add(lblTitle, BorderLayout.WEST);
        titleRow.add(UiKit.rowRight(btnRun, btnExport, btnPrint), BorderLayout.EAST);
        bar.add(titleRow, BorderLayout.NORTH);
        bar.add(dateBar, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(0, 10));
        right.setOpaque(false);
        right.add(bar, BorderLayout.NORTH);
        right.add(UiKit.tableCard(table));
        lblTotals.setBorder(BorderFactory.createEmptyBorder(2, 4, 0, 0));
        right.add(lblTotals, BorderLayout.SOUTH);

        JPanel body = new JPanel(new BorderLayout(14, 0));
        body.setOpaque(false);
        body.add(left, BorderLayout.WEST);
        body.add(right);
        add(UiKit.page("Reports", "Sales, profit, inventory valuation, stock alerts, expiry, dues and more", null, body));
    }
}
