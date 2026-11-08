package com.productms.view;

import com.productms.model.Customer;
import com.productms.model.Payment;
import com.productms.util.Money;
import java.awt.Component;
import java.math.BigDecimal;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;

/** Asks how much due a customer is paying and how. */
public final class DueCollectionDialog {

    /** Entered values. */
    public static class Result {
        public BigDecimal amount;
        public String method;
        public String reference;
    }

    private DueCollectionDialog() {
    }

    public static Result ask(Component parent, Customer c) {
        JTextField amount = UiKit.field("Amount received");
        amount.setText(c.due.toPlainString());
        JComboBox<String> method = new JComboBox<>(Payment.methods());
        method.setRenderer(new MethodRenderer());
        JTextField ref = UiKit.field("TrxID / card last 4 (not needed for cash)");
        UiKit.Form f = new UiKit.Form();
        f.full(new JLabel("<html><b>" + c.name + "</b> owes <b style='color:#DC2626'>" + Money.cur(c.due)
                + "</b>.<br>Payment is applied to the oldest invoices first.</html>"));
        f.add("Amount", amount);
        f.add("Method", method);
        f.add("Reference", ref);
        while (JOptionPane.showConfirmDialog(parent, f.panel, "Collect due", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                Result r = new Result();
                r.amount = Money.parse(amount.getText());
                r.method = (String) method.getSelectedItem();
                r.reference = ref.getText().isBlank() ? null : ref.getText().trim();
                return r;
            } catch (IllegalArgumentException e) {
                UiKit.warn(parent, e.getMessage());
            }
        }
        return null;
    }

    /** Shows payment method names instead of codes. */
    public static class MethodRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            return super.getListCellRendererComponent(list, Payment.label(String.valueOf(value)), index, isSelected, cellHasFocus);
        }
    }
}
