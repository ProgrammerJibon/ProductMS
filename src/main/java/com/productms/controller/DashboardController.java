package com.productms.controller;

import com.productms.service.ReportService;
import com.productms.util.Money;
import com.productms.util.Session;
import com.productms.view.DashboardView;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.JComponent;

/** Loads KPIs, chart and alert lists for the dashboard. */
public class DashboardController implements Module {

    private final DashboardView view;
    private final ReportService reports = new ReportService();
    private final MainController main;

    public DashboardController(MainController main) {
        this.main = main;
        view = new DashboardView(Session.user().fullName.split(" ")[0]);
        view.btnRefresh.addActionListener(e -> com.productms.view.UiKit.guard(view, this::onShow));
        view.btnNewSale.addActionListener(e -> main.navigate("POS"));
        MouseAdapter toReports = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                main.frame().btnAlerts.doClick();
            }
        };
        view.alertLow.addMouseListener(toReports);
        view.alertOut.addMouseListener(toReports);
        view.alertExpiring.addMouseListener(toReports);
        view.alertExpired.addMouseListener(toReports);
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void onShow() throws java.sql.SQLException {
        {
            Map<String, BigDecimal> s = reports.dashboardStats();
            view.kpiSales.set(Money.cur(s.get("todaySales")));
            view.kpiProfit.set(Money.cur(s.get("todayProfit")));
            view.kpiInvoices.set(Money.qty(s.get("todayInvoices")));
            view.kpiInvoices.setToolTipText("Returns today: " + Money.cur(s.get("todayReturns")));
            view.kpiMonth.set(Money.cur(s.get("monthSales")));
            view.kpiStock.set(Money.cur(s.get("stockValue")));
            view.kpiDue.set(Money.cur(s.get("totalDue")));
            setAlert(view.alertLow, s.get("lowStock"), "variant(s)");
            setAlert(view.alertOut, s.get("outOfStock"), "variant(s)");
            setAlert(view.alertExpiring, s.get("expiringSoon"), "batch(es)");
            setAlert(view.alertExpired, s.get("expired"), "batch(es)");

            List<String> labels = new ArrayList<>();
            List<BigDecimal> values = new ArrayList<>();
            for (Map.Entry<LocalDate, BigDecimal> e : reports.last7Days().entrySet()) {
                labels.add(e.getKey().equals(LocalDate.now()) ? "Today"
                        : e.getKey().format(DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)));
                values.add(e.getValue());
            }
            view.chart.setData(labels, values);
            view.alertsModel.setData(reports.stockAlerts());
            view.recentModel.setData(reports.recentSales());
            view.topModel.setData(reports.topProducts());
            if (view.alertsTable.getColumnCount() > 0) {
                view.alertsTable.getColumnModel().getColumn(0).setMaxWidth(110);
            }
            main.refreshAlerts();
        }
    }

    private void setAlert(DashboardView.Kpi k, BigDecimal n, String unit) {
        k.set(Money.qty(n) + " " + unit);
        k.setHighlight(n.signum() > 0);
    }
}
