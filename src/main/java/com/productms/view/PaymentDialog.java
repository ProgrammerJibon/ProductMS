package com.productms.view;

import com.productms.dao.PaymentMethodDAO;
import com.productms.model.Cart;
import com.productms.model.Payment;
import com.productms.model.PaymentMethod;
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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Checkout payment screen: configurable methods (cash, card, bKash, Nagad, Rocket, custom),
 * reference validation, split payments, quick-cash note buttons from Settings, store credit from
 * the customer's account, change calculation, partial (due) payment and an invoice note.
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
    private final JLabel lblRefCheck = new JLabel(" ");
    private final JLabel lblPaid = big("0.00", 20f);
    private final JLabel lblRemaining = big("0.00", 20f);
    private final JLabel lblChange = big("0.00", 26f);
    private final JPanel quickCash = new JPanel(new GridLayout(2, 3, 6, 6));
    private final JTextField txtNote = UiKit.field("Optional note printed on the invoice");
    private final JComboBox<String> cmbReceipt = new JComboBox<>(new String[]{
        "Thermal 80 mm", "Thermal 58 mm", "A4 invoice", "No receipt"});
    private final JButton btnComplete = UiKit.big(UiKit.success("Complete sale (F9)"), 50);
    private String method = "CASH";
    private boolean confirmed;

    public PaymentDialog(Window owner, Cart cart) {
        super(owner, "Payment - " + cart.label, ModalityType.APPLICATION_MODAL);
        this.cart = cart;
        BigDecimal credit = Money.min(Money.nz(cart.storeCredit), cart.grandTotal);
        this.due = cart.grandTotal.subtract(credit);
        UiKit.styleTable(table);
        table.setAutoCreateRowSorter(false);
        txtNote.setText(cart.note == null ? "" : cart.note);

        JPanel totals = UiKit.card(new GridLayout(0, 2, 8, 6));
        totals.add(new JLabel("Customer"));
        totals.add(right(new JLabel(cart.customer.name)));
        totals.add(new JLabel("Invoice total"));
        totals.add(right(big(Money.fmt(cart.grandTotal), 20f)));
        if (credit.signum() > 0) {
            totals.add(new JLabel("Exchange credit (" + cart.storeCreditRef + ")"));
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
        JButton btnCredit = UiKit.plain("Use account credit");
        BigDecimal balance = Money.nz(cart.customer.storeCredit);
        btnCredit.setVisible(!cart.customer.walkIn && balance.signum() > 0);
        btnCredit.setToolTipText("Customer's store credit balance: " + Money.cur(balance));
        btnCredit.addActionListener(e -> {
            BigDecimal used = BigDecimal.ZERO;
            for (Payment p : lines) {
                if ("STORE_CREDIT".equals(p.method)) {
                    used = used.add(p.amount);
                }
            }
            BigDecimal take = Money.min(balance.subtract(used), remaining());
            if (take.signum() > 0) {
                lines.add(new Payment("STORE_CREDIT", take, "ACCOUNT"));
                refresh();
            }
        });
        JPanel left = new JPanel(new BorderLayout(0, 10));
        left.add(totals, BorderLayout.NORTH);
        left.add(UiKit.tableCard(table));
        left.add(UiKit.row(btnRemove, btnCredit), BorderLayout.SOUTH);

        List<PaymentMethod> methods = PaymentMethodDAO.tenderMethods();
        JPanel methodPanel = new JPanel(new GridLayout(0, Math.min(5, Math.max(1, methods.size())), 6, 6));
        ButtonGroup g = new ButtonGroup();
        for (PaymentMethod m : methods) {
            JToggleButton b = new JToggleButton(m.name, m.isCash());
            b.setFont(b.getFont().deriveFont(Font.BOLD, 14f));
            b.setPreferredSize(new Dimension(90, 46));
            if (m.color != null) {
                try {
                    b.setForeground(Color.decode(m.color));
                } catch (NumberFormatException ignored) {
                    // default colour
                }
            }
            b.addActionListener(e -> selectMethod(m.code));
            g.add(b);
            methodPanel.add(b);
        }
        txtAmount.setFont(txtAmount.getFont().deriveFont(Font.BOLD, 28f));
        txtAmount.setHorizontalAlignment(SwingConstants.RIGHT);
        txtAmount.putClientProperty("JTextField.placeholderText", "Amount received");
        JButton btnAdd = UiKit.big(UiKit.primary("Add payment (Enter)"), 44);
        btnAdd.addActionListener(e -> addLine());
        txtAmount.addActionListener(e -> addOrComplete());
        txtRef.addActionListener(e -> addLine());
        txtRef.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                checkRef();
            }

            public void removeUpdate(DocumentEvent e) {
                checkRef();
            }

            public void changedUpdate(DocumentEvent e) {
                checkRef();
            }
        });
        lblRefCheck.setFont(lblRefCheck.getFont().deriveFont(11.5f));

        UiKit.Form f = new UiKit.Form();
        f.section("Payment method");
        f.full(methodPanel);
        f.section("Amount");
        f.full(txtAmount);
        f.add("", lblRef);
        f.full(txtRef);
        f.full(lblRefCheck);
        f.full(quickCash);
        f.full(btnAdd);
        f.section("Invoice");
        f.add("Note", txtNote);
        f.add("Receipt", cmbReceipt);
        String size = Settings.get("receipt_size", "80");
        cmbReceipt.setSelectedIndex("58".equals(size) ? 1 : "A4".equalsIgnoreCase(size) ? 2 : 0);
        f.filler();
        JPanel rightPanel = UiKit.card(new BorderLayout());
        rightPanel.add(f.panel);
        rightPanel.setPreferredSize(new Dimension(500, 0));

        JButton btnCancel = UiKit.big(UiKit.plain("Back to cart (Esc)"), 50);
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
        setSize(1040, 700);
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

    private boolean isCash(String code) {
        PaymentMethod m = PaymentMethodDAO.get(code);
        return m != null && m.isCash();
    }

    private void selectMethod(String code) {
        method = code;
        PaymentMethod m = PaymentMethodDAO.get(code);
        boolean cash = m != null && m.isCash();
        boolean needsRef = m != null && m.requiresReference;
        txtRef.setEnabled(needsRef);
        lblRef.setText(cash ? "No reference needed for cash" : needsRef ? m.name + " reference *" : m.name + " (no reference)");
        txtRef.putClientProperty("JTextField.placeholderText", m == null || m.referenceHint == null ? "Reference" : m.referenceHint);
        txtRef.setText("");
        lblRefCheck.setText(m != null && m.referenceHint != null ? m.referenceHint : " ");
        lblRefCheck.setForeground(UiKit.muted());
        txtAmount.setText(remaining().signum() > 0 ? remaining().toPlainString() : "");
        quickCash.setVisible(cash);
        txtAmount.requestFocusInWindow();
        txtAmount.selectAll();
    }

    private void checkRef() {
        if (txtRef.getText().isBlank()) {
            return;
        }
        String err = PaymentMethodDAO.validateReference(method, txtRef.getText());
        lblRefCheck.setText(err == null ? "Reference format OK" : err);
        lblRefCheck.setForeground(err == null ? UiKit.SUCCESS : UiKit.DANGER);
    }

    private void buildQuickCash() {
        quickCash.removeAll();
        BigDecimal rem = remaining();
        TreeSet<BigDecimal> opts = new TreeSet<>();
        opts.add(rem);
        for (String s : Settings.get("cash_denominations", "1000,500,100,50,10").split(",")) {
            try {
                BigDecimal n = new BigDecimal(s.trim());
                if (n.compareTo(new BigDecimal("50")) < 0) {
                    continue;
                }
                BigDecimal up = rem.divide(n, 0, RoundingMode.CEILING).multiply(n);
                if (up.compareTo(rem) >= 0 && up.signum() > 0) {
                    opts.add(up);
                }
            } catch (NumberFormatException ignored) {
                // skip bad entry
            }
        }
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
            if (!isCash(method)) {
                String err = PaymentMethodDAO.validateReference(method, txtRef.getText());
                if (err != null) {
                    UiKit.warn(this, err);
                    txtRef.requestFocusInWindow();
                    return;
                }
                BigDecimal nonCash = BigDecimal.ZERO;
                for (Payment p : lines) {
                    if (!isCash(p.method)) {
                        nonCash = nonCash.add(p.amount);
                    }
                    if (p.method.equals(method) && p.reference != null && p.reference.equalsIgnoreCase(txtRef.getText().trim())) {
                        UiKit.warn(this, "This reference is already in the payment list.");
                        return;
                    }
                }
                if (nonCash.add(amt).compareTo(due) > 0) {
                    UiKit.warn(this, "Card / mobile payments cannot be more than the amount to pay ("
                            + Money.cur(due.subtract(nonCash)) + " left).");
                    return;
                }
            }
            String ref = txtRef.getText().isBlank() ? null : txtRef.getText().trim().toUpperCase();
            lines.add(new Payment(method, amt, isCash(method) ? null : ref));
            refresh();
            txtRef.setText("");
        });
    }

    private void refresh() {
        model.setRows(lines);
        BigDecimal rem = remaining();
        lblPaid.setText(Money.fmt(paid()));
        lblRemaining.setText(Money.fmt(rem));
        lblRemaining.setForeground(rem.signum() > 0 ? UiKit.DANGER : UiKit.SUCCESS);
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal nonCash = BigDecimal.ZERO;
        for (Payment p : lines) {
            if (isCash(p.method)) {
                cash = cash.add(p.amount);
            } else {
                nonCash = nonCash.add(p.amount);
            }
        }
        BigDecimal change = Money.max(BigDecimal.ZERO, cash.subtract(Money.max(BigDecimal.ZERO, due.subtract(nonCash))));
        BigDecimal leftoverCredit = Money.nz(cart.storeCredit).subtract(Money.min(Money.nz(cart.storeCredit), cart.grandTotal));
        if ("CASH".equals(Settings.get("exchange_excess_policy", "ACCOUNT"))) {
            change = change.add(leftoverCredit);
        }
        lblChange.setText(Money.fmt(change));
        txtAmount.setText(rem.signum() > 0 ? rem.toPlainString() : "");
        txtAmount.selectAll();
        buildQuickCash();
        txtAmount.requestFocusInWindow();
    }

    private void complete() {
        if (!btnComplete.isEnabled()) {
            return;
        }
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
        btnComplete.setEnabled(false);
        cart.note = txtNote.getText().isBlank() ? null : txtNote.getText().trim();
        confirmed = true;
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public List<Payment> payments() {
        return lines;
    }

    public boolean hasCash() {
        return lines.stream().anyMatch(p -> isCash(p.method));
    }

    /** "80", "58", "A4" or null for no receipt. */
    public String receiptFormat() {
        return new String[]{"80", "58", "A4", null}[cmbReceipt.getSelectedIndex()];
    }
}
