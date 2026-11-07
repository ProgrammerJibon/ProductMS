package com.productms.view;

import com.productms.util.Money;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.math.BigDecimal;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;

/** Touch-friendly numeric keypad for quantities, weights, discounts and cash. */
public class NumberPadDialog extends JDialog {

    private final JTextField field = new JTextField();
    private final JToggleButton tglAmount = new JToggleButton("Amount", true);
    private final JToggleButton tglPercent = new JToggleButton("Percent %");
    private BigDecimal result;
    private boolean percent;

    private NumberPadDialog(Window owner, String title, String message, BigDecimal initial, boolean decimals,
            boolean percentToggle, boolean startPercent) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        field.setFont(field.getFont().deriveFont(Font.BOLD, 26f));
        field.setHorizontalAlignment(SwingConstants.RIGHT);
        field.setText(initial == null ? "" : Money.qty(initial));
        field.selectAll();

        JPanel pad = new JPanel(new GridLayout(4, 3, 6, 6));
        String[] keys = {"7", "8", "9", "4", "5", "6", "1", "2", "3", decimals ? "." : "00", "0", "⌫"};
        for (String k : keys) {
            JButton b = UiKit.plain(k.equals("⌫") ? "Del" : k);
            b.setFont(b.getFont().deriveFont(Font.BOLD, 20f));
            b.setPreferredSize(new Dimension(80, 58));
            b.setFocusable(false);
            b.addActionListener(e -> press(k));
            pad.add(b);
        }
        JButton ok = UiKit.big(UiKit.success("OK"), 50);
        JButton cancel = UiKit.big(UiKit.plain("Cancel"), 50);
        JButton clear = UiKit.big(UiKit.plain("Clear"), 50);
        clear.addActionListener(e -> field.setText(""));
        ok.addActionListener(e -> accept());
        cancel.addActionListener(e -> dispose());
        field.addActionListener(e -> accept());

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JPanel north = new JPanel(new BorderLayout(0, 8));
        JLabel msg = new JLabel("<html>" + message + "</html>");
        north.add(msg, BorderLayout.NORTH);
        if (percentToggle) {
            ButtonGroup g = new ButtonGroup();
            g.add(tglAmount);
            g.add(tglPercent);
            tglPercent.setSelected(startPercent);
            tglAmount.setSelected(!startPercent);
            north.add(UiKit.row(tglAmount, tglPercent), BorderLayout.CENTER);
        }
        north.add(field, BorderLayout.SOUTH);
        root.add(north, BorderLayout.NORTH);
        root.add(pad);
        JPanel south = new JPanel(new GridLayout(1, 3, 6, 0));
        south.add(cancel);
        south.add(clear);
        south.add(ok);
        root.add(south, BorderLayout.SOUTH);
        UiKit.bind(root, "ESCAPE", this::dispose);
        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
    }

    private void press(String k) {
        if (k.equals("⌫")) {
            String t = field.getText();
            if (field.getSelectedText() != null) {
                field.replaceSelection("");
            } else if (!t.isEmpty()) {
                field.setText(t.substring(0, t.length() - 1));
            }
        } else {
            if (field.getSelectedText() != null) {
                field.replaceSelection("");
            }
            if (k.equals(".") && field.getText().contains(".")) {
                return;
            }
            field.setText(field.getText() + k);
        }
        field.requestFocusInWindow();
    }

    private void accept() {
        try {
            BigDecimal v = Money.parse(field.getText());
            if (v == null || v.signum() < 0) {
                java.awt.Toolkit.getDefaultToolkit().beep();
                return;
            }
            result = v;
            percent = tglPercent.isSelected();
            dispose();
        } catch (IllegalArgumentException e) {
            java.awt.Toolkit.getDefaultToolkit().beep();
        }
    }

    public static BigDecimal ask(java.awt.Component parent, String title, String message, BigDecimal initial, boolean decimals) {
        NumberPadDialog d = new NumberPadDialog(UiKit.window(parent), title, message, initial, decimals, false, false);
        d.setVisible(true);
        return d.result;
    }

    /** Result of a discount prompt. */
    public static class Discount {
        public final BigDecimal value;
        public final boolean percent;

        Discount(BigDecimal value, boolean percent) {
            this.value = value;
            this.percent = percent;
        }
    }

    public static Discount askDiscount(java.awt.Component parent, String title, String message, BigDecimal initial,
            boolean initialPercent) {
        NumberPadDialog d = new NumberPadDialog(UiKit.window(parent), title, message, initial, true, true, initialPercent);
        d.setVisible(true);
        return d.result == null ? null : new Discount(d.result, d.percent);
    }
}
