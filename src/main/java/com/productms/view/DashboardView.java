package com.productms.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;

/** Dashboard: KPI cards, 7-day sales chart, alerts, recent sales and top products. */
public class DashboardView extends JPanel {

    public final Kpi kpiSales = new Kpi("Today's Sales", UiKit.PRIMARY);
    public final Kpi kpiProfit = new Kpi("Today's Gross Profit", UiKit.SUCCESS);
    public final Kpi kpiInvoices = new Kpi("Invoices Today", UiKit.PURPLE);
    public final Kpi kpiMonth = new Kpi("This Month", UiKit.INFO);
    public final Kpi kpiStock = new Kpi("Stock Value (cost)", UiKit.SLATE);
    public final Kpi kpiDue = new Kpi("Customer Dues", UiKit.DANGER);

    public final Kpi alertLow = new Kpi("Low Stock", UiKit.WARNING);
    public final Kpi alertOut = new Kpi("Out of Stock", UiKit.DANGER);
    public final Kpi alertExpiring = new Kpi("Expiring Soon", UiKit.WARNING);
    public final Kpi alertExpired = new Kpi("Expired Batches", UiKit.DANGER);

    public final BarChart chart = new BarChart();
    public final DataTableModel alertsModel = new DataTableModel();
    public final DataTableModel recentModel = new DataTableModel();
    public final DataTableModel topModel = new DataTableModel();
    public final JTable alertsTable = new JTable(alertsModel);
    public final JTable recentTable = new JTable(recentModel);
    public final JTable topTable = new JTable(topModel);
    public final JButton btnNewSale = UiKit.big(UiKit.success("+  New Sale"), 40);
    public final JButton btnRefresh = UiKit.plain("Refresh");

    public DashboardView(String greeting) {
        super(new BorderLayout());
        UiKit.styleTable(alertsTable);
        UiKit.styleTable(recentTable);
        UiKit.styleTable(topTable);

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 14, 14);

        JPanel kpis = new JPanel(new GridLayout(1, 6, 14, 0));
        kpis.setOpaque(false);
        for (Kpi k : new Kpi[]{kpiSales, kpiProfit, kpiInvoices, kpiMonth, kpiStock, kpiDue}) {
            kpis.add(k);
        }
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        c.weightx = 1;
        grid.add(kpis, c);

        JPanel alerts = new JPanel(new GridLayout(1, 4, 14, 0));
        alerts.setOpaque(false);
        for (Kpi k : new Kpi[]{alertLow, alertOut, alertExpiring, alertExpired}) {
            k.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            alerts.add(k);
        }
        c.gridy = 1;
        grid.add(alerts, c);

        JPanel chartCard = section("Sales - last 7 days", chart);
        c.gridy = 2;
        c.gridwidth = 2;
        c.weightx = 0.62;
        c.weighty = 0.5;
        grid.add(chartCard, c);

        c.gridx = 2;
        c.gridwidth = 1;
        c.weightx = 0.38;
        grid.add(section("Top products (30 days)", UiKit.scroll(topTable)), c);

        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        c.weightx = 0.62;
        c.weighty = 0.5;
        c.insets = new Insets(0, 0, 0, 14);
        grid.add(section("Recent sales", UiKit.scroll(recentTable)), c);
        c.gridx = 2;
        c.gridwidth = 1;
        c.weightx = 0.38;
        grid.add(section("Stock & expiry alerts", UiKit.scroll(alertsTable)), c);

        add(UiKit.page("Good day, " + greeting, "Here is what is happening in your store today.",
                UiKit.row(btnRefresh, btnNewSale), grid));
    }

    private static JPanel section(String title, java.awt.Component body) {
        JPanel p = UiKit.card(new BorderLayout(0, 8));
        p.add(UiKit.title(title, 14f), BorderLayout.NORTH);
        p.add(body);
        p.setPreferredSize(new Dimension(300, 240));
        return p;
    }

    /** KPI tile with a coloured accent bar. */
    public static class Kpi extends JPanel {
        private final JLabel value = new JLabel("-");
        private final JLabel caption;
        private final Color accent;

        public Kpi(String title, Color accent) {
            super(new BorderLayout(0, 4));
            this.accent = accent;
            caption = UiKit.mutedLabel(title);
            value.setFont(value.getFont().deriveFont(Font.BOLD, 21f));
            add(caption, BorderLayout.NORTH);
            add(value, BorderLayout.CENTER);
            restyle();
        }

        private void restyle() {
            setBackground(UiKit.cardBg());
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 4, 0, 0, accent),
                    BorderFactory.createCompoundBorder(UiKit.cardBorder(), BorderFactory.createEmptyBorder(0, 0, 0, 0))));
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (caption != null) {
                restyle();
            }
        }

        public void set(String text) {
            value.setText(text);
        }

        public void setHighlight(boolean on) {
            value.setForeground(on ? accent : javax.swing.UIManager.getColor("Label.foreground"));
        }
    }
}
