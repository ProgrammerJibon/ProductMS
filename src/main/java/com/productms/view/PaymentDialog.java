package com.productms.view;

import com.productms.model.Cart;
import com.productms.model.Payment;
import com.productms.util.Money;
import com.productms.util.Settings;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;

/**
 * Checkout payment screen: cash / card / bKash / Nagad / Rocket, split payments,
 * cash received with change, quick-cash note buttons and partial (due) payment.
 */
public class PaymentDialog extends JDialog {

    private final Cart cart;
    private final BigDecimal due;
    private final List<Payment> lines = new ArrayList<>();
    private final RowTableModel<Payment> model = new RowTableModel<Payment>()
            .col("Method", String.class, p -> Payment.label(p.method))
            .col("Amount", BigDecimal.class, p -> p.amount)
            .col("Reference", String.class, p -> p.reference);
    private final JTable table = new JTable(model);
    private final JTextField txtAmount = new JTextField();
    private final JTextField txtRef = UiKit.field("Transaction ID");
    private final JLabel lblRef = new JLabel("Reference");
    private final JLabel lblPaid = big("0.00", 20f);
    private final JLabel lblRemaining = big("0.00", 20f);
    private final JLabel lblChange = big("0.00", 26f);
    private final JPanel quickCash = new JPanel(new GridLayout(2, 3, 6, 6));
    private final JComboBox<String> cmbReceipt = new JComboBox<>(new String[]{
        "Thermal 80 mm", "Thermal 58 mm", "A4 invoice", "No receipt"});
    private String method = "CASH";
    private boolean confirmed;

    public PaymentDialog(Window owner, Cart cart) {
        super(owner, "Payment - " + cart.label, ModalityType.APPLICATION_MODAL);
        this.cart = cart;
        BigDecimal credit = Money.min(Money.nz(cart.storeCredit), cart.grandTotal);
        this.due = cart.grandTotal.subtract(credit);
        UiKit.styleTable(table);
        table.setAutoCreateRowSorter(false);

        // ---- left: totals and payment lines
        JPanel totals = UiKit.card(new GridLayout(0, 2, 8, 6));
        totals.add(new JLabel("Customer"));
        totals.add(right(new JLabel(cart.customer.name)));
        totals.add(new JLabel("Invoice total"));
        totals.add(right(big(Money.fmt(cart.grandTotal), 20f)));
        if (credit.signum() > 0) {
            totals.add(new JLabel("Store credit (" + cart.storeCreditRef + ")"));
            totals.add(right(big("-" + Money.fmt(Money.nz(cart.storeCredit)), 16f)));
        }
        totals.add(new JLabel("Amount to pay"));
        JLabel dueLbl = big(Money.fmt(due), 26f);
        dueLbl.setForeground(UiKit.PRIMARY);
        totals.add(right(dueLbl));
        totals.add(new JLabel("Paid"));
        totals.add(right(lblPaid));
        totals.add(new JLabel("Remaining / due"));
        totals.add(right(lblRemaining));
        totals.add(new JLabel("Change to return"));
        lblChange.setForeground(UiKit.SUCCESS);
        totals.add(right(lblChange));

        JButton btnRemove = UiKit.plain("Remove payment line");
        btnRemove.addActionListener(e -> {
            Payment p = model.selected(table);
            if (p != null) {
                lines.remove(p);
                refresh();
            }
        });
        JPanel left = new JPanel(new BorderLayout(0, 10));
        left.add(totals, BorderLayout.NORTH);
        left.add(UiKit.tableCard(table));
        left.add(UiKit.row(btnRemove), BorderLayout.SOUTH);

        // ---- right: method + amount entry
        JPanel methods = new JPanel(new GridLayout(1, 5, 6, 0));
        ButtonGroup g = new ButtonGroup();
        Color[] colors = {UiKit.SUCCESS, UiKit.PRIMARY, new Color(0xE2136E), new Color(0xF7941D), new Color(0x8C3494)};
        for (int i = 0; i < Payment.METHODS.length; i++) {
            String m = Payment.METHODS[i];
            JToggleButton b = new JToggleButton(Payment.label(m), m.equals("CASH"));
            b.setFont(b.getFont().deriveFont(Font.BOLD, 14f));
            b.setPreferredSize(new Dimension(90, 48));
            b.setForeground(colors[i]);
            b.addActionListener(e -> selectMethod(m));
            g.add(b);
            methods.add(b);
        }
        txtAmount.setFont(txtAmount.getFont().deriveFont(Font.BOLD, 28f));
        txtAmount.setHorizontalAlignment(SwingConstants.RIGHT);
        txtAmount.putClientProperty("JTextField.placeholderText", "Amount received");
        JButton btnAdd = UiKit.big(UiKit.primary("Add payment (Enter)"), 44);
        btnAdd.addActionListener(e -> addLine());
        txtAmount.addActionListener(e -> addOrComplete());
        txtRef.addActionListener(e -> addLine());

        UiKit.Form f = new UiKit.Form();
        f.section("Payment method");
        f.full(methods);
        f.section("Amount");
        f.full(txtAmount);
        f.add("", lblRef);
        f.full(txtRef);
        f.full(quickCash);
        f.full(btnAdd);
        f.section("Receipt");
        f.add("Print", cmbReceipt);
        String size = Settings.get("receipt_size", "80");
        cmbReceipt.setSelectedIndex("58".equals(size) ? 1 : "A4".equalsIgnoreCase(size) ? 2 : 0);
        f.filler();
        JPanel rightPanel = UiKit.card(new BorderLayout());
        rightPanel.add(f.panel);
        rightPanel.setPreferredSize(new Dimension(470, 0));

        JButton btnCancel = UiKit.big(UiKit.plain("Back to cart (Esc)"), 50);
        JButton btnComplete = UiKit.big(UiKit.success("Complete sale (F9)"), 50);
        btnCancel.addActionListener(e -> dispose());
        btnComplete.addActionListener(e -> complete());

        JPanel root = new JPanel(new BorderLayout(14, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        root.add(left);
        root.add(rightPanel, BorderLayout.EAST);
        JPanel south = new JPanel(new GridLayout(1, 2, 12, 0));
        south.add(btnCancel);
        south.add(btnComplete);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        UiKit.bind(root, "ESCAPE", this::dispose);
        UiKit.bind(root, "F9", this::complete);
        UiKit.bind(root, "ctrl ENTER", this::complete);
        setSize(1000, 660);
        setLocationRelativeTo(owner);
        selectMethod("CASH");
        refresh();
    }

    private static JLabel big(String t, float size) {
        JLabel l = new JLabel(t);
        l.setFont(l.getFont().deriveFont(Font.BOLD, size));
        return l;
    }

    private static JLabel right(JLabel l) {
        l.setHorizontalAlignment(SwingConstants.RIGHT);
        return l;
    }

    private BigDecimal paid() {
        BigDecimal s = BigDecimal.ZERO;
        for (Payment p : lines) {
            s = s.add(p.amount);
        }
        return s;
    }

    private BigDecimal remaining() {
        return Money.max(BigDecimal.ZERO, due.subtract(paid()));
    }

    private void selectMethod(String m) {
        method = m;
        boolean cash = "CASH".equals(m);
        txtRef.setEnabled(!cash);
        lblRef.setText(cash ? "No reference needed for cash" : "CARD".equals(m) ? "Card last 4 digits / approval code *"
                : Payment.label(m) + " transaction ID (TrxID) *");
        txtRef.putClientProperty("JTextField.placeholderText", "CARD".equals(m) ? "e.g. 4821" : "e.g. 9KX7Q2LM1P");
        txtRef.setText("");
        txtAmount.setText(remaining().signum() > 0 ? remaining().toPlainString() : "");
        quickCash.setVisible(cash);
        txtAmount.requestFocusInWindow();
        txtAmount.selectAll();
    }

    private void buildQuickCash() {
        quickCash.removeAll();
        BigDecimal rem = remaining();
        TreeSet<BigDecimal> opts = new TreeSet<>();
        opts.add(rem);
        for (int note : new int[]{10, 50, 100, 500, 1000}) {
            BigDecimal n = BigDecimal.valueOf(note);
            BigDecimal up = rem.divide(n, 0, RoundingMode.CEILING).multiply(n);
            if (up.compareTo(rem) >= 0 && up.signum() > 0) {
                opts.add(up);
            }
        }
        opts.add(BigDecimal.valueOf(1000).max(rem.divide(BigDecimal.valueOf(1000), 0, RoundingMode.CEILING).multiply(BigDecimal.valueOf(1000))));
        int count = 0;
        for (BigDecimal v : opts) {
            if (count++ >= 6 || v.signum() <= 0) {
                break;
            }
            JButton b = UiKit.plain(v.compareTo(rem) == 0 ? "Exact " + Money.fmt(v) : Money.fmt(v));
            b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
            b.setFocusable(false);
            b.addActionListener(e -> {
                lines.add(new Payment("CASH", v, null));
                refresh();
            });
            quickCash.add(b);
        }
        quickCash.revalidate();
        quickCash.repaint();
    }

    private void addOrComplete() {
        if (txtAmount.getText().isBlank() && remaining().signum() == 0 && !lines.isEmpty()) {
            complete();
        } else {
            addLine();
        }
    }

    private void addLine() {
        UiKit.guard(this, () -> {
            BigDecimal amt = Money.parse(txtAmount.getText());
            if (!Money.pos(amt)) {
                UiKit.warn(this, "Enter an amount greater than 0.");
                return;
            }
            amt = Money.r2(amt);
            if (!"CASH".equals(method)) {
                if (txtRef.getText().isBlank()) {
                    UiKit.warn(this, lblRef.getText().replace(" *", "") + " is required.");
                    txtRef.requestFocusInWindow();
                    return;
                }
                BigDecimal nonCash = BigDecimal.ZERO;
                for (Payment p : lines) {
                    if (!"CASH".equals(p.method)) {
                        nonCash = nonCash.add(p.amount);
                    }
                }
                if (nonCash.add(amt).compareTo(due) > 0) {
                    UiKit.warn(this, "Card / mobile payments cannot be more than the amount to pay ("
                            + Money.cur(due.subtract(nonCash)) + " left).");
                    return;
                }
            }
            lines.add(new Payment(method, amt, "CASH".equals(method) ? null : txtRef.getText().trim()));
            refresh();
            if (remaining().signum() > 0 && !"CASH".equals(method)) {
                txtRef.setText("");
            }
        });
    }

    private void refresh() {
        model.setRows(lines);
        BigDecimal paid = paid();
        lblPaid.setText(Money.fmt(paid));
        BigDecimal rem = remaining();
        lblRemaining.setText(Money.fmt(rem));
        lblRemaining.setForeground(rem.signum() > 0 ? UiKit.DANGER : UiKit.SUCCESS);
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal nonCash = BigDecimal.ZERO;
        for (Payment p : lines) {
            if ("CASH".equals(p.method)) {
                cash = cash.add(p.amount);
            } else {
                nonCash = nonCash.add(p.amount);
            }
        }
        BigDecimal change = Money.max(BigDecimal.ZERO, cash.subtract(Money.max(BigDecimal.ZERO, due.subtract(nonCash))));
        BigDecimal leftoverCredit = Money.nz(cart.storeCredit).subtract(Money.min(Money.nz(cart.storeCredit), cart.grandTotal));
        lblChange.setText(Money.fmt(change.add(leftoverCredit)));
        txtAmount.setText(rem.signum() > 0 ? rem.toPlainString() : "");
        txtAmount.selectAll();
        buildQuickCash();
        txtAmount.requestFocusInWindow();
    }

    private void complete() {
        if (!txtAmount.getText().isBlank() && lines.isEmpty() && due.signum() > 0) {
            addLine();
            if (lines.isEmpty()) {
                return;
            }
        }
        BigDecimal rem = remaining();
        if (rem.signum() > 0) {
            if (cart.customer == null || cart.customer.walkIn) {
                UiKit.warn(this, Money.cur(rem) + " is still unpaid.\nTake full payment, or select a registered customer "
                        + "in the cart (F4) to record a due / partial sale.");
                return;
            }
            if (!UiKit.confirm(this, "Record " + Money.cur(rem) + " as DUE for " + cart.customer.name + "?")) {
                return;
            }
        }
        confirmed = true;
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public List<Payment> payments() {
        return lines;
    }

    /** "80", "58", "A4" or null for no receipt. */
    public String receiptFormat() {
        return new String[]{"80", "58", "A4", null}[cmbReceipt.getSelectedIndex()];
    }
}
