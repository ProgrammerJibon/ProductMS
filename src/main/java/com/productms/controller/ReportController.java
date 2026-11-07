package com.productms.controller;

import com.productms.service.ReportService;
import com.productms.util.Money;
import com.productms.view.ReportsView;
import com.productms.view.TableExport;
import com.productms.view.UiKit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;

/** Runs the selected report for the date range and shows totals. */
public class ReportController implements Module {

    private final ReportsView view = new ReportsView();
    private final ReportService reports = new ReportService();

    public ReportController() {
        view.lstReports.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                run();
            }
        });
        view.btnRun.addActionListener(e -> run());
        view.btnToday.addActionListener(e -> range(LocalDate.now(), LocalDate.now()));
        view.btnWeek.addActionListener(e -> range(LocalDate.now().minusDays(6), LocalDate.now()));
        view.btnMonth.addActionListener(e -> range(LocalDate.now().withDayOfMonth(1), LocalDate.now()));
        view.btnYear.addActionListener(e -> range(LocalDate.now().withDayOfYear(1), LocalDate.now()));
        view.btnExport.addActionListener(e -> TableExport.csv(view, view.table, view.lstReports.getSelectedValue()));
        view.btnPrint.addActionListener(e -> TableExport.print(view, view.table, view.lstReports.getSelectedValue()
                + "  (" + UiKit.dateOf(view.dtFrom).format(UiKit.DATE) + " to " + UiKit.dateOf(view.dtTo).format(UiKit.DATE) + ")"));
    }

    private void range(LocalDate from, LocalDate to) {
        UiKit.setDate(view.dtFrom, from);
        UiKit.setDate(view.dtTo, to);
        run();
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void onShow() {
        run();
    }

    private void run() {
        String r = view.lstReports.getSelectedValue();
        if (r == null) {
            return;
        }
        boolean dates = ReportService.usesDates(r);
        for (java.awt.Component c : view.dateBar.getComponents()) {
            c.setEnabled(dates);
        }
        view.lblTitle.setText(r + (dates ? "" : "  (current)"));
        UiKit.guard(view, () -> {
            view.model.setData(reports.run(r, UiKit.dateOf(view.dtFrom), UiKit.dateOf(view.dtTo)));
            List<String> totals = new ArrayList<>();
            for (int c = 0; c < view.model.getColumnCount(); c++) {
                String name = view.model.getColumnName(c);
                Class<?> type = view.model.getColumnClass(c);
                boolean summable = type == BigDecimal.class || type == Long.class || type == Integer.class;
                if (summable && !name.contains("%") && !name.contains("Price") && !name.contains("Cost") && !name.contains("Level")
                        && !name.contains("Days") && !name.contains("Avg") && !name.equals("Stock") && !name.equals("Balance")
                        && !name.equals("Shift")) {
                    BigDecimal s = view.model.sum(c);
                    totals.add(name + ": " + (type == BigDecimal.class && s.scale() == 2 ? Money.fmt(s) : Money.qty(s)));
                }
            }
            view.lblTotals.setText(view.model.getRowCount() + " row(s)" + (totals.isEmpty() ? "" : "   ·   Totals — "
                    + String.join("   ·   ", totals)));
        });
    }
}
