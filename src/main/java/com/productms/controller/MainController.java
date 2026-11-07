package com.productms.controller;

import com.productms.service.ReportService;
import com.productms.util.Session;
import com.productms.util.Settings;
import com.productms.view.DataTableModel;
import com.productms.view.MainFrame;
import com.productms.view.UiKit;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.Timer;

/**
 * Builds the main window, the modules the current user may use, navigation,
 * clock / shift status and stock alerts.
 */
public class MainController {

    private final MainFrame frame = new MainFrame(Settings.shopName());
    private final Map<String, Module> modules = new LinkedHashMap<>();
    private String current;
    private PosController pos;
    private StockController stock;

    public MainController() {
        boolean manager = Session.isManager();
        register("Dashboard", "▪", new DashboardController(this));
        pos = new PosController(this);
        register("POS", "►", pos);
        if (manager) {
            register("Products", "■", new ProductController(this));
            stock = new StockController(this);
            register("Stock", "≡", stock);
        }
        register("Sales", "◊", new SalesController(this));
        register("Customers", "●", new CustomerController(this));
        register("Shifts", "○", new ShiftController(this));
        if (manager) {
            register("Reports", "▲", new ReportController());
            register("Catalog Setup", "♦", new CatalogController());
        }
        if (Session.isAdmin()) {
            register("Settings", "§", new SettingsController(this));
        }
        frame.setNavListener(this::navigate);
        frame.lblUser.setText("<html><b>" + Session.user().fullName + "</b><br><span style='color:#94A3B8'>"
                + Session.user().role + "</span></html>");
        frame.btnLogout.addActionListener(e -> logout());
        frame.btnAlerts.addActionListener(e -> showAlerts());
        frame.btnTheme.setText(UiKit.isDark() ? "Light mode" : "Dark mode");
        frame.btnTheme.addActionListener(e -> toggleTheme());
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (UiKit.confirm(frame, "Exit the application?")) {
                    System.exit(0);
                }
            }
        });
        new Timer(1000, e -> frame.lblClock.setText(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy  hh:mm:ss a", Locale.ENGLISH)))).start();
        new Timer(120_000, e -> refreshAlerts()).start();
    }

    private void register(String name, String icon, Module m) {
        int index = modules.size() + 1;
        String key = index <= 9 ? "ctrl " + index : (index == 10 ? "ctrl 0" : null);
        modules.put(name, m);
        frame.addNav(name, icon, key == null ? null : key.replace("ctrl ", "Ctrl+"));
        frame.addModule(name, m.view());
        if (key != null) {
            UiKit.bind(frame.getRootPane(), key, () -> navigate(name));
        }
    }

    public void start() {
        frame.setVisible(true);
        navigate(Session.isManager() ? "Dashboard" : "POS");
        refreshShift();
        refreshAlerts();
        if (!Session.isManager() && Session.shift() == null && Settings.getBool("require_shift")) {
            UiKit.info(frame, "Welcome, " + Session.user().fullName + "!\nOpen your cash shift before selling (Shifts → Open Shift).");
            navigate("Shifts");
        }
    }

    public void navigate(String name) {
        Module m = modules.get(name);
        if (m == null) {
            return;
        }
        current = name;
        frame.show(name);
        UiKit.guard(frame, m::onShow);
    }

    public String current() {
        return current;
    }

    public MainFrame frame() {
        return frame;
    }

    public PosController pos() {
        return pos;
    }

    /** Collects a customer's outstanding due (used by Sales and Customers). */
    public void collectDue(java.awt.Component parent, com.productms.model.Customer c) throws Exception {
        SalesController.collectDue(this, parent, c);
    }

    /** Opens Stock → Stock In with the given product pre-selected. */
    public void openStockIn(com.productms.model.Variant v) {
        if (stock != null) {
            navigate("Stock");
            stock.prefillStockIn(v);
        }
    }

    public void refreshShift() {
        if (Session.shift() != null) {
            frame.lblShift.setText("● Shift #" + Session.shift().shiftId + " open");
            frame.lblShift.setForeground(UiKit.SUCCESS);
        } else {
            frame.lblShift.setText("● No shift open");
            frame.lblShift.setForeground(UiKit.WARNING);
        }
    }

    public void refreshAlerts() {
        UiKit.guard(frame, () -> frame.setAlertCount(new ReportService().stockAlerts().rows.size()));
    }

    private void showAlerts() {
        UiKit.guard(frame, () -> {
            DataTableModel model = new DataTableModel();
            model.setData(new ReportService().stockAlerts());
            JTable t = new JTable(model);
            UiKit.styleTable(t);
            t.getColumnModel().getColumn(0).setMaxWidth(130);
            JDialog d = new JDialog(frame, "Stock alerts - low stock, out of stock & expiry", true);
            JPanel p = new JPanel(new BorderLayout(0, 10));
            p.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 14, 14, 14));
            p.add(UiKit.mutedLabel(model.getRowCount() == 0 ? "No alerts - stock levels and expiry dates are fine."
                    : model.getRowCount() + " item(s) need attention. Restock from Stock → Stock In, write off expired batches "
                    + "from Stock → Batches & Expiry."), BorderLayout.NORTH);
            p.add(UiKit.tableCard(t));
            d.setContentPane(p);
            d.setSize(new Dimension(820, 460));
            d.setLocationRelativeTo(frame);
            d.setVisible(true);
        });
    }

    private void toggleTheme() {
        boolean dark = !UiKit.isDark();
        UiKit.switchTheme(dark);
        frame.btnTheme.setText(dark ? "Light mode" : "Dark mode");
        UiKit.guard(frame, () -> Settings.save(Collections.singletonMap("theme", dark ? "dark" : "light")));
        navigate(current);
    }

    private void logout() {
        if (pos.hasOpenCarts() && !UiKit.confirm(frame, "There are unfinished carts in the POS. They will be lost "
                + "unless you put them on hold.\nLog out anyway?")) {
            return;
        }
        if (!UiKit.confirm(frame, "Log out?")) {
            return;
        }
        Session.clear();
        frame.dispose();
        new LoginController().start();
    }
}
