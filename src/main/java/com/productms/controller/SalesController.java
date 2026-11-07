package com.productms.controller;

import com.productms.dao.CustomerDAO;
import com.productms.model.Customer;
import com.productms.model.ReturnLine;
import com.productms.model.Sale;
import com.productms.model.SaleItem;
import com.productms.service.SaleService;
import com.productms.util.Money;
import com.productms.util.Session;
import com.productms.util.Settings;
import com.productms.view.DueCollectionDialog;
import com.productms.view.ReceiptPreviewDialog;
import com.productms.view.ReturnDialog;
import com.productms.view.SalesView;
import com.productms.view.SerialPickerDialog;
import com.productms.view.TableExport;
import com.productms.view.UiKit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JTextField;

/** Sales history, reprints, returns, exchanges, cancellations and due collection. */
public class SalesController implements Module {

    private final SalesView view = new SalesView();
    private final SaleService sales = new SaleService();
    private final MainController main;
    private Sale current;

    public SalesController(MainController main) {
        this.main = main;
        view.btnSearch.addActionListener(e -> load());
        view.txtSearch.addActionListener(e -> load());
        view.cmbStatus.addActionListener(e -> load());
        view.cmbPay.addActionListener(e -> load());
        view.btnToday.addActionListener(e -> {
            UiKit.setDate(view.dtFrom, LocalDate.now());
            UiKit.setDate(view.dtTo, LocalDate.now());
            load();
        });
        view.btnExport.addActionListener(e -> TableExport.csv(view, view.table, "sales"));
        view.table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetail();
            }
        });
        view.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    receipt();
                }
            }
        });
        view.btnReceipt.addActionListener(e -> receipt());
        view.btnReturn.addActionListener(e -> doReturn(false));
        view.btnExchange.addActionListener(e -> doReturn(true));
        view.btnCancel.addActionListener(e -> cancel());
        view.btnCollect.addActionListener(e -> collect());
        view.rtLoad.addActionListener(e -> loadReturns());
        view.rtSearch.addActionListener(e -> loadReturns());
        view.tabs.addChangeListener(e -> {
            if (view.tabs.getSelectedIndex() == 1) {
                loadReturns();
            }
        });
        view.btnCancel.setVisible(Session.isManager());
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
            Integer keep = current == null ? null : current.saleId;
            List<Sale> list = sales.history(UiKit.dateOf(view.dtFrom), UiKit.dateOf(view.dtTo), view.txtSearch.getText(),
                    (String) view.cmbStatus.getSelectedItem(), (String) view.cmbPay.getSelectedItem(), null);
            view.model.setRows(list);
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal due = BigDecimal.ZERO;
            int n = 0;
            for (Sale s : list) {
                if (!s.isCancelled()) {
                    total = total.add(s.grandTotal);
                    due = due.add(s.dueTotal);
                    n++;
                }
            }
            view.lblSummary.setText(n + " completed invoice(s)  ·  sales " + Money.cur(total) + "  ·  outstanding due "
                    + Money.cur(due));
            if (keep != null) {
                for (int i = 0; i < view.table.getRowCount(); i++) {
                    if (view.model.row(view.table.convertRowIndexToModel(i)).saleId == keep) {
                        view.table.setRowSelectionInterval(i, i);
                    }
                }
            }
            showDetail();
        });
    }

    private void showDetail() {
        Sale s = view.model.selected(view.table);
        if (s == null) {
            current = null;
            view.lblDetail.setText("Select an invoice");
            view.itemsModel.setRows(List.of());
            view.payModel.setRows(List.of());
            enable(false);
            return;
        }
        UiKit.guard(view, () -> {
            current = sales.load(s.saleId);
            view.lblDetail.setText("<html>" + current.invoiceNo + "  ·  " + current.customerName
                    + (current.isCancelled() ? "  <span style='color:#DC2626'>CANCELLED: " + current.cancelReason + "</span>" : "")
                    + "</html>");
            view.itemsModel.setRows(current.items);
            view.payModel.setRows(current.payments);
            enable(true);
        });
    }

    private void enable(boolean on) {
        boolean active = on && current != null && !current.isCancelled();
        view.btnReceipt.setEnabled(on);
        view.btnReturn.setEnabled(active);
        view.btnExchange.setEnabled(active);
        view.btnCancel.setEnabled(active && current.returnedTotal.signum() == 0);
        view.btnCollect.setEnabled(active && current.dueTotal.signum() > 0);
    }

    private void receipt() {
        if (current != null) {
            ReceiptPreviewDialog.show(view, current, Settings.get("receipt_size", "80"), true);
        }
    }

    private void doReturn(boolean exchange) {
        if (current == null) {
            return;
        }
        Sale sale = current;
        ReturnDialog d = new ReturnDialog(UiKit.window(view), sale, exchange);
        d.setVisible(true);
        if (!d.isConfirmed()) {
            return;
        }
        UiKit.guard(view, () -> {
            List<ReturnLine> lines = d.lines();
            for (ReturnLine rl : lines) {
                SaleItem it = sale.items.stream().filter(x -> x.saleItemId == rl.saleItemId).findFirst().orElse(null);
                if (it != null && it.serialNumbers != null) {
                    List<String> s = SerialPickerDialog.pick(view, "Which serial(s) of " + it.displayName() + " are returned?",
                            sales.soldSerials(it.saleItemId), List.of(), rl.quantity.intValue());
                    if (s == null) {
                        return;
                    }
                    rl.serials = s;
                }
            }
            boolean ex = d.rbExchange.isSelected();
            SaleService.ReturnResult r = sales.processReturn(sale.saleId, lines, ex, (String) d.cmbMethod.getSelectedItem(),
                    d.txtReason.getText());
            load();
            main.refreshAlerts();
            StringBuilder msg = new StringBuilder("Return " + r.returnNo + " recorded.\n\nGoods value: " + Money.cur(r.totalAmount));
            if (r.dueAdjusted.signum() > 0) {
                msg.append("\nDeducted from invoice due: ").append(Money.cur(r.dueAdjusted));
            }
            if (ex) {
                msg.append("\nStore credit for exchange: ").append(Money.cur(r.refundAmount))
                        .append("\n\nA new cart with this credit is opened in the POS. Add the replacement items.");
                UiKit.info(view, msg.toString());
                if (r.refundAmount.signum() > 0) {
                    Customer c = new CustomerDAO().findById(sale.customerId);
                    main.navigate("POS");
                    main.pos().startExchange(r.refundAmount, r.returnNo, c);
                }
            } else {
                msg.append("\nRefund to customer: ").append(Money.cur(r.refundAmount));
                if (r.refundAmount.signum() > 0) {
                    msg.append(" by ").append(com.productms.model.Payment.label((String) d.cmbMethod.getSelectedItem()));
                }
                UiKit.info(view, msg.toString());
            }
        });
    }

    private void cancel() {
        if (current == null) {
            return;
        }
        Sale sale = current;
        JTextField reason = UiKit.field("Why is this sale cancelled?");
        JComboBox<String> method = new JComboBox<>(com.productms.model.Payment.METHODS);
        UiKit.Form f = new UiKit.Form();
        f.full(new javax.swing.JLabel("<html>Cancel <b>" + sale.invoiceNo + "</b> (" + Money.cur(sale.grandTotal)
                + ")?<br>All items go back to stock. Money paid (" + Money.cur(sale.paidTotal) + ") is refunded.</html>"));
        f.add("Reason *", reason);
        f.add("Refund via", method);
        if (JOptionPane.showConfirmDialog(view, f.panel, "Cancel sale", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        UiKit.guard(view, () -> {
            sales.cancel(sale.saleId, reason.getText(), (String) method.getSelectedItem());
            load();
            main.refreshAlerts();
            UiKit.info(view, "Sale " + sale.invoiceNo + " cancelled and stock restored.");
        });
    }

    private void collect() {
        if (current == null) {
            return;
        }
        UiKit.guard(view, () -> {
            Customer c = new CustomerDAO().findById(current.customerId);
            main.collectDue(view, c);
            load();
        });
    }

    private void loadReturns() {
        UiKit.guard(view, () -> view.rtModel.setData(sales.returns(UiKit.dateOf(view.rtFrom), UiKit.dateOf(view.rtTo),
                view.rtSearch.getText())));
    }

    /** Shared due collection flow (also used by Customers). */
    static void collectDue(MainController main, java.awt.Component parent, Customer c) throws Exception {
        if (c.due.signum() <= 0) {
            UiKit.info(parent, c.name + " has no outstanding due.");
            return;
        }
        DueCollectionDialog.Result r = DueCollectionDialog.ask(parent, c);
        if (r == null) {
            return;
        }
        if (com.productms.util.Settings.getBool("require_shift") && Session.shift() == null) {
            UiKit.warn(parent, "Open a cash shift first (Shifts → Open Shift).");
            return;
        }
        List<String> invoices = new SaleService().collectDue(c.customerId, r.amount, r.method, r.reference);
        UiKit.info(parent, "Collected " + Money.cur(r.amount) + " from " + c.name + ".\nApplied to:\n• "
                + String.join("\n• ", invoices));
    }
}
