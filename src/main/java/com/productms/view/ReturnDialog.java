package com.productms.view;

import com.productms.model.Payment;
import com.productms.model.ReturnLine;
import com.productms.model.Sale;
import com.productms.model.SaleItem;
import com.productms.util.AppException;
import com.productms.util.Money;
import java.awt.BorderLayout;
import java.awt.Window;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;

/** Choose items, quantities and condition for a return, refund or exchange. */
public class ReturnDialog extends JDialog {

    private final Sale sale;
    private final BigDecimal[] qty;
    private final String[] condition;
    public final JRadioButton rbRefund = new JRadioButton("Refund money", true);
    public final JRadioButton rbExchange = new JRadioButton("Exchange (store credit for new items)");
    public final JComboBox<String> cmbMethod = new JComboBox<>(refundMethods());
    public final JTextField txtReason = UiKit.field("Reason (e.g. wrong size, defective, changed mind)");
    private final JLabel lblTotal = UiKit.title(" ", 16f);
    private final ItemsModel model = new ItemsModel();
    private boolean confirmed;

    private static String[] refundMethods() {
        java.util.List<String> m = new java.util.ArrayList<>();
        m.add("ORIGINAL");
        m.addAll(java.util.Arrays.asList(Payment.methods()));
        return m.toArray(new String[0]);
    }

    public ReturnDialog(Window owner, Sale sale, boolean exchange) {
        super(owner, "Return items - " + sale.invoiceNo, ModalityType.APPLICATION_MODAL);
        this.sale = sale;
        qty = new BigDecimal[sale.items.size()];
        condition = new String[sale.items.size()];
        for (int i = 0; i < qty.length; i++) {
            qty[i] = BigDecimal.ZERO;
            condition[i] = "GOOD";
        }
        JTable t = new JTable(model);
        UiKit.styleTable(t);
        t.setAutoCreateRowSorter(false);
        t.putClientProperty("terminateEditOnFocusLost", true);
        t.getColumnModel().getColumn(0).setPreferredWidth(260);
        t.getColumnModel().getColumn(5).setCellEditor(new DefaultCellEditor(new JComboBox<>(new String[]{"GOOD", "DAMAGED"})));

        ButtonGroup g = new ButtonGroup();
        g.add(rbRefund);
        g.add(rbExchange);
        rbExchange.setSelected(exchange);
        rbRefund.addActionListener(e -> cmbMethod.setEnabled(true));
        rbExchange.addActionListener(e -> cmbMethod.setEnabled(false));
        cmbMethod.setEnabled(!exchange);
        cmbMethod.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, Payment.label(String.valueOf(value)), index, isSelected, cellHasFocus);
            }
        });
        JButton all = UiKit.plain("Return everything");
        all.addActionListener(e -> {
            for (int i = 0; i < qty.length; i++) {
                qty[i] = sale.items.get(i).returnable();
            }
            model.fireTableDataChanged();
            updateTotal();
        });
        JButton ok = UiKit.primary("Process return");
        JButton cancel = UiKit.plain("Cancel");
        ok.addActionListener(e -> {
            if (t.isEditing()) {
                t.getCellEditor().stopCellEditing();
            }
            if (lines().isEmpty()) {
                UiKit.warn(this, "Enter a return quantity for at least one item.");
                return;
            }
            if (txtReason.getText().isBlank()) {
                UiKit.warn(this, "Enter the reason for the return.");
                return;
            }
            confirmed = true;
            dispose();
        });
        cancel.addActionListener(e -> dispose());

        UiKit.Form f = new UiKit.Form();
        f.full(UiKit.row(rbRefund, rbExchange));
        f.add("Refund method", cmbMethod);
        f.add("Reason *", txtReason);
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JLabel info = UiKit.mutedLabel("<html>Customer: <b>" + sale.customerName + "</b> · Invoice total "
                + Money.cur(sale.grandTotal) + " · Paid " + Money.cur(sale.paidTotal) + (sale.refundedTotal.signum() > 0 ? " · already refunded " + Money.cur(sale.refundedTotal) : "")
                + (sale.dueTotal.signum() > 0 ? " · <span style='color:#DC2626'>Due " + Money.cur(sale.dueTotal)
                        + " (reduced first)</span>" : "")
                + "<br>Good items go back to stock (same batch). Damaged items go to Damaged Stock. "
                + "Refunds never exceed what was paid; \"Original method(s)\" pays back the way the customer paid.</html>");
        root.add(info, BorderLayout.NORTH);
        root.add(UiKit.tableCard(t));
        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.add(f.panel, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(UiKit.row(all, lblTotal), BorderLayout.WEST);
        buttons.add(UiKit.rowRight(cancel, ok), BorderLayout.EAST);
        south.add(buttons, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(980, 560);
        setLocationRelativeTo(owner);
        updateTotal();
    }

    private void updateTotal() {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < qty.length; i++) {
            total = total.add(value(i));
        }
        lblTotal.setText("   Return value: " + Money.cur(total));
    }

    private BigDecimal value(int i) {
        SaleItem it = sale.items.get(i);
        return Money.r2(it.lineTotal.multiply(qty[i]).divide(it.quantity, 6, RoundingMode.HALF_UP));
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public List<ReturnLine> lines() {
        List<ReturnLine> out = new ArrayList<>();
        for (int i = 0; i < qty.length; i++) {
            if (qty[i].signum() > 0) {
                out.add(new ReturnLine(sale.items.get(i).saleItemId, qty[i], condition[i]));
            }
        }
        return out;
    }

    private class ItemsModel extends AbstractTableModel {
        private final String[] cols = {"Item", "Sold", "Already returned", "Returnable", "Return qty", "Condition", "Refund value"};

        @Override
        public int getRowCount() {
            return sale.items.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 || c == 5 ? String.class : BigDecimal.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return (c == 4 || c == 5) && sale.items.get(r).returnable().signum() > 0;
        }

        @Override
        public Object getValueAt(int r, int c) {
            SaleItem it = sale.items.get(r);
            switch (c) {
                case 0: return it.displayName() + (it.serialNumbers != null ? "  [SN]" : "");
                case 1: return it.quantity;
                case 2: return it.returnedQty;
                case 3: return it.returnable();
                case 4: return qty[r];
                case 5: return condition[r];
                default: return value(r);
            }
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            SaleItem it = sale.items.get(r);
            try {
                if (c == 4) {
                    BigDecimal q = Money.parseOr(String.valueOf(v), BigDecimal.ZERO);
                    if (q.signum() < 0 || q.compareTo(it.returnable()) > 0) {
                        throw new AppException("Return quantity for " + it.displayName() + " must be between 0 and "
                                + Money.qty(it.returnable()) + ".");
                    }
                    qty[r] = q;
                } else if (c == 5) {
                    condition[r] = String.valueOf(v);
                }
            } catch (RuntimeException e) {
                UiKit.warn(ReturnDialog.this, e.getMessage());
            }
            fireTableRowsUpdated(r, r);
            updateTotal();
        }
    }
}
