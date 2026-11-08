package com.productms.controller;

import com.productms.dao.PaymentMethodDAO;
import com.productms.db.Jdbc;
import com.productms.model.PaymentMethod;
import com.productms.model.Shift;
import com.productms.service.HardwareService;
import com.productms.service.ReceiptService;
import com.productms.service.ShiftService;
import com.productms.util.AppException;
import com.productms.util.Money;
import com.productms.util.Session;
import com.productms.util.Settings;
import com.productms.view.NumberPadDialog;
import com.productms.view.ShiftView;
import com.productms.view.TableDialog;
import com.productms.view.UiKit;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Open / close shifts, cash in / out / safe drops, denomination count, reconciliation and X / Z reports. */
public class ShiftController implements Module {

    private final ShiftView view = new ShiftView();
    private final ShiftService service = new ShiftService();
    private final MainController main;

    public ShiftController(MainController main) {
        this.main = main;
        view.btnOpen.addActionListener(e -> open());
        view.btnClose.addActionListener(e -> close());
        view.btnRefresh.addActionListener(e -> UiKit.guard(view, this::onShow));
        view.btnCashIn.addActionListener(e -> movement("CASH_IN", "Cash in", "Why is cash added? (e.g. change from bank)"));
        view.btnCashOut.addActionListener(e -> movement("CASH_OUT", "Cash out", "What was paid? (e.g. delivery charge, tea)"));
        view.btnDrop.addActionListener(e -> movement("SAFE_DROP", "Safe drop", "Note (e.g. moved to safe, envelope #)"));
        view.btnX.addActionListener(e -> {
            if (Session.shift() != null) {
                print(Session.shift().shiftId);
            }
        });
        view.btnZ.addActionListener(e -> {
            Integer id = selectedHistory();
            if (id != null) {
                print(id);
            }
        });
        view.btnRecon.addActionListener(e -> {
            Integer id = selectedHistory();
            if (id != null) {
                UiKit.guard(view, () -> {
                    Map<String, Jdbc.TableData> tabs = new LinkedHashMap<>();
                    tabs.put("Reconciliation", service.reconciliation(id));
                    tabs.put("Cash in / out", service.cashMovements(id));
                    Shift s = service.find(id);
                    TableDialog.show(view, "Shift #" + id + " - " + s.userName, "Expected vs counted / declared per payment method."
                            + (s.denominations == null ? "" : "<br>Cash count: " + s.denominations.replace(";", ", ")), tabs);
                });
            }
        });
    }

    private Integer selectedHistory() {
        int r = view.historyTable.getSelectedRow();
        if (r < 0) {
            UiKit.warn(view, "Select a shift in the history.");
            return null;
        }
        return ((Number) view.historyModel.getValueAt(view.historyTable.convertRowIndexToModel(r), 0)).intValue();
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void onShow() throws Exception {
        Session.setShift(service.openShiftOf(Session.userId()));
        Shift s = Session.shift();
        boolean open = s != null;
        view.btnOpen.setEnabled(!open);
        for (javax.swing.JButton b : new javax.swing.JButton[]{view.btnClose, view.btnX, view.btnCashIn, view.btnCashOut, view.btnDrop}) {
            b.setEnabled(open);
        }
        if (open) {
            view.lblStatus.setText("Shift #" + s.shiftId + " is open");
            view.lblStatus.setForeground(UiKit.SUCCESS);
            view.lblInfo.setText("Opened " + s.openedAt.format(ReceiptService.DT) + " by " + s.userName
                    + "  ·  opening cash " + Money.cur(s.openingCash));
            List<Object[]> rows = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> e : service.summary(s.shiftId).entrySet()) {
                rows.add(new Object[]{e.getKey(), e.getValue()});
            }
            view.summaryModel.setRows(rows);
            view.movesModel.setData(service.cashMovements(s.shiftId));
        } else {
            view.lblStatus.setText("No shift open");
            view.lblStatus.setForeground(UiKit.WARNING);
            view.lblInfo.setText(Settings.getBool("require_shift") ? "Open a shift with the cash in the drawer before selling."
                    : "Shifts are optional (see Settings), but recommended for cash control.");
            view.summaryModel.setRows(List.of());
            view.movesModel.setData(null);
        }
        view.historyModel.setData(service.history());
        main.refreshShift();
    }

    private void open() {
        BigDecimal cash = NumberPadDialog.ask(view, "Open shift", "Count the cash in the drawer.<br><b>Opening cash</b>:",
                BigDecimal.ZERO, true);
        if (cash == null) {
            return;
        }
        UiKit.guard(view, () -> {
            service.open(cash);
            onShow();
        });
    }

    private void movement(String type, String title, String hint) {
        BigDecimal amount = NumberPadDialog.ask(view, title, title + " amount:", null, true);
        if (amount == null) {
            return;
        }
        String reason = UiKit.input(view, hint, "");
        if (reason == null) {
            return;
        }
        UiKit.guard(view, () -> {
            service.cashMovement(type, amount, reason);
            if (Settings.getBool("cash_drawer_enabled")) {
                HardwareService.openCashDrawer();
            }
            onShow();
        });
    }

    /** Close: count cash by denomination and declare card / mobile totals from slips & statements. */
    private void close() {
        Shift s = Session.shift();
        if (s == null) {
            return;
        }
        UiKit.guard(view, () -> {
            BigDecimal expected = service.expectedCash(s.shiftId);
            Map<String, BigDecimal> expectedOther = service.expectedByMethod(s.shiftId);
            List<BigDecimal> notes = new ArrayList<>();
            for (String d : Settings.get("cash_denominations", "1000,500,200,100,50,20,10,5,2,1").split(",")) {
                try {
                    notes.add(new BigDecimal(d.trim()));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
            JPanel grid = new JPanel(new GridLayout(0, 3, 6, 4));
            List<JTextField> counts = new ArrayList<>();
            JLabel total = UiKit.title("Counted: 0.00", 15f);
            Runnable recalc = () -> {
                BigDecimal sum = BigDecimal.ZERO;
                for (int i = 0; i < notes.size(); i++) {
                    try {
                        BigDecimal n = Money.parseOr(counts.get(i).getText(), BigDecimal.ZERO);
                        sum = sum.add(n.multiply(notes.get(i)));
                    } catch (IllegalArgumentException ignored) {
                        // typing
                    }
                }
                BigDecimal diff = sum.subtract(expected);
                total.setText("Counted: " + Money.fmt(sum) + "   (" + (diff.signum() == 0 ? "exact" : diff.signum() < 0
                        ? "short " + Money.fmt(diff.abs()) : "over " + Money.fmt(diff)) + ")");
                total.setForeground(diff.signum() == 0 ? UiKit.SUCCESS : UiKit.DANGER);
            };
            grid.add(new JLabel("<html><b>Note / coin</b></html>"));
            grid.add(new JLabel("<html><b>Count</b></html>"));
            grid.add(new JLabel(""));
            for (BigDecimal n : notes) {
                JTextField f = UiKit.field("0");
                f.getDocument().addDocumentListener(new DocumentListener() {
                    public void insertUpdate(DocumentEvent e) {
                        recalc.run();
                    }

                    public void removeUpdate(DocumentEvent e) {
                        recalc.run();
                    }

                    public void changedUpdate(DocumentEvent e) {
                        recalc.run();
                    }
                });
                counts.add(f);
                grid.add(new JLabel(Settings.currency() + " " + Money.qty(n)));
                grid.add(f);
                grid.add(new JLabel(""));
            }
            JPanel declared = new JPanel(new GridLayout(0, 3, 6, 4));
            Map<String, JTextField> declaredFields = new LinkedHashMap<>();
            if (!expectedOther.isEmpty()) {
                declared.add(new JLabel("<html><b>Method</b></html>"));
                declared.add(new JLabel("<html><b>System</b></html>"));
                declared.add(new JLabel("<html><b>Your slips / statement</b></html>"));
                for (Map.Entry<String, BigDecimal> e : expectedOther.entrySet()) {
                    PaymentMethod pm = PaymentMethodDAO.get(e.getKey());
                    JTextField f = UiKit.field(Money.fmt(e.getValue()));
                    f.setText(e.getValue().toPlainString());
                    declaredFields.put(e.getKey(), f);
                    declared.add(new JLabel(pm == null ? e.getKey() : pm.name));
                    declared.add(new JLabel(Money.fmt(e.getValue())));
                    declared.add(f);
                }
            }
            JTextField note = UiKit.field("Explain any difference");
            JPanel p = new JPanel(new BorderLayout(0, 10));
            p.add(new JLabel("<html>System expects <b>" + Money.cur(expected) + "</b> cash in the drawer.<br>Count each note and coin:</html>"),
                    BorderLayout.NORTH);
            JPanel center = new JPanel(new BorderLayout(0, 10));
            center.add(grid, BorderLayout.NORTH);
            center.add(total, BorderLayout.CENTER);
            JPanel south = new JPanel(new BorderLayout(0, 6));
            if (!declaredFields.isEmpty()) {
                south.add(UiKit.title("Card / mobile totals", 13f), BorderLayout.NORTH);
                south.add(declared, BorderLayout.CENTER);
            }
            south.add(note, BorderLayout.SOUTH);
            center.add(south, BorderLayout.SOUTH);
            p.add(center);
            p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            javax.swing.JScrollPane sp = new javax.swing.JScrollPane(p);
            sp.setPreferredSize(new java.awt.Dimension(480, 560));
            sp.setBorder(null);
            if (JOptionPane.showConfirmDialog(view, sp, "Close shift #" + s.shiftId, JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
                return;
            }
            BigDecimal counted = BigDecimal.ZERO;
            StringBuilder den = new StringBuilder();
            for (int i = 0; i < notes.size(); i++) {
                BigDecimal n = Money.parseOr(counts.get(i).getText(), BigDecimal.ZERO);
                if (n.signum() < 0 || !Money.isWhole(n)) {
                    throw new AppException("Counts must be whole numbers.");
                }
                if (n.signum() > 0) {
                    counted = counted.add(n.multiply(notes.get(i)));
                    den.append(den.length() == 0 ? "" : ";").append(Money.qty(notes.get(i))).append('x').append(Money.qty(n));
                }
            }
            Map<String, BigDecimal> decl = new LinkedHashMap<>();
            boolean otherDiff = false;
            for (Map.Entry<String, JTextField> e : declaredFields.entrySet()) {
                BigDecimal v = Money.parseOr(e.getValue().getText(), BigDecimal.ZERO);
                decl.put(e.getKey(), v);
                otherDiff |= v.compareTo(expectedOther.get(e.getKey())) != 0;
            }
            if ((counted.compareTo(expected) != 0 || otherDiff) && note.getText().isBlank()) {
                throw new AppException("There is a difference. Please explain it in the note.");
            }
            Shift closed = service.close(s.shiftId, counted, den.toString(), decl, note.getText().isBlank() ? null : note.getText().trim());
            onShow();
            if (UiKit.confirm(view, "Shift closed. Cash " + (closed.difference.signum() == 0 ? "matches." : (closed.difference.signum() < 0
                    ? "SHORT " : "OVER ") + Money.cur(closed.difference.abs()) + ".") + "\nPrint the shift (Z) report?")) {
                print(closed.shiftId);
            }
        });
    }

    private void print(int shiftId) {
        UiKit.guard(view, () -> {
            Shift s = service.find(shiftId);
            int mm = "58".equals(Settings.get("receipt_size", "80")) ? 58 : 80;
            List<ReceiptService.Line> lines = ReceiptService.shiftReportLines(s, service.summary(shiftId), mm);
            Jdbc.TableData rec = service.reconciliation(shiftId);
            if (!rec.rows.isEmpty()) {
                int w = ReceiptService.charsFor(mm);
                lines.add(lines.size() - 1, ReceiptService.Line.bold("RECONCILIATION"));
                for (List<Object> r : rec.rows) {
                    lines.add(lines.size() - 1, ReceiptService.Line.left(ReceiptService.pair(PaymentMethodDAO.label((String) r.get(0))
                            + " diff", Money.fmt((BigDecimal) r.get(3)), w)));
                }
            }
            if (s.denominations != null) {
                lines.add(lines.size() - 1, ReceiptService.Line.left("Count: " + s.denominations.replace(";", " ")));
            }
            ReceiptService.printThermal(lines, mm, "Shift report #" + shiftId, true);
        });
    }
}
